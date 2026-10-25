//! 实例分组：读写数据根目录下的 `instance_groups.json`。
//! 实例上的 `group` 字段存分组 id，删除分组时同步清空引用。

use crate::models::InstanceGroup;
use crate::settings::get_data_dir;
use std::collections::HashMap;
use std::path::Path;
use tokio::fs;
use uuid::Uuid;

const GROUPS_FILE: &str = "instance_groups.json";

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

async fn groups_path() -> Result<std::path::PathBuf, String> {
    let data_dir = get_data_dir().await.map_err(|e| e.to_string())?;
    Ok(Path::new(&data_dir).join(GROUPS_FILE))
}

async fn load_all() -> HashMap<String, InstanceGroup> {
    let Ok(path) = groups_path().await else {
        return HashMap::new();
    };
    fs::read_to_string(&path)
        .await
        .ok()
        .and_then(|s| serde_json::from_str::<Vec<InstanceGroup>>(&s).ok())
        .map(|list| {
            list.into_iter()
                .map(|g| (g.id.clone(), g))
                .collect::<HashMap<_, _>>()
        })
        .unwrap_or_default()
}

/// ⚠️ 给「读改写」入口用的严格版：文件损坏时**报错**而不是拿空表继续 ——
/// 否则下一次任意分组操作会把损坏文件（连带全部分组）覆盖成空表，永久丢失。
async fn load_all_strict() -> Result<HashMap<String, InstanceGroup>, String> {
    let path = groups_path().await?;
    if !path.exists() {
        return Ok(HashMap::new());
    }
    let text = fs::read_to_string(&path)
        .await
        .map_err(|e| format!("读取分组文件失败：{e}"))?;
    let list: Vec<InstanceGroup> = serde_json::from_str(&text)
        .map_err(|e| format!("分组文件损坏（{e}），拒绝写入以免覆盖丢失；可手工修复 {path:?}"))?;
    Ok(list
        .into_iter()
        .map(|g| (g.id.clone(), g))
        .collect())
}

async fn save_all(map: &HashMap<String, InstanceGroup>) -> Result<(), String> {
    let path = groups_path().await?;
    let mut list: Vec<&InstanceGroup> = map.values().collect();
    list.sort_by_key(|g| g.created);
    let json = serde_json::to_string_pretty(&list).map_err(|e| e.to_string())?;
    // ⚠️ 原子写：撕裂的分组文件 + 「损坏→静默空表→写回」= 分组永久丢失。
    crate::fsutil::write_text_atomic(&path, &json).map_err(|e| e.to_string())
}

/// 分组列表（按创建时间排序）
pub async fn list_groups() -> Result<Vec<InstanceGroup>, String> {
    let map = load_all().await;
    let mut list: Vec<InstanceGroup> = map.into_values().collect();
    list.sort_by_key(|g| g.created);
    Ok(list)
}

/// 新建分组
pub async fn create_group(name: String, color: Option<String>) -> Result<InstanceGroup, String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("分组名称不能为空".into());
    }
    let mut map = load_all_strict().await?;
    let group = InstanceGroup {
        id: Uuid::new_v4().to_string(),
        name,
        color: color.filter(|c| !c.trim().is_empty()),
        created: now_secs(),
    };
    map.insert(group.id.clone(), group.clone());
    save_all(&map).await?;
    Ok(group)
}

/// 重命名 / 改色分组
pub async fn rename_group(
    id: String,
    name: String,
    color: Option<String>,
) -> Result<InstanceGroup, String> {
    let name = name.trim().to_string();
    if name.is_empty() {
        return Err("分组名称不能为空".into());
    }
    let mut map = load_all_strict().await?;
    let group = map.get_mut(&id).ok_or_else(|| "分组不存在".to_string())?;
    group.name = name;
    group.color = color.filter(|c| !c.trim().is_empty());
    let out = group.clone();
    save_all(&map).await?;
    Ok(out)
}

/// 删除分组，同时清空所有实例上的 group 引用
pub async fn delete_group(id: String) -> Result<(), String> {
    let mut map = load_all_strict().await?;
    if map.remove(&id).is_none() {
        return Err("分组不存在".into());
    }
    save_all(&map).await?;

    // 清空实例上的分组引用。
    // ⚠️ 与实例设置编辑（update_instance）并发时，读改写必须持实例写锁，
    // 否则后写者会把先写者的整份实例元数据覆盖掉（丢 renderer / 内存等）。
    let instances = crate::instances::list_instances().await.map_err(|e| e.to_string())?;
    let affected: Vec<String> = instances
        .iter()
        .filter(|i| i.group.as_deref() == Some(&id))
        .map(|i| i.id.clone())
        .collect();
    if !affected.is_empty() {
        let _guard = crate::instances::INSTANCE_WRITE_LOCK.lock().await;
        // 锁内逐个重读最新值再改，避免用列表快照盖掉加锁前别人落的改动。
        for iid in &affected {
            let mut cur = match crate::instances::get_instance(iid).await {
                Ok(c) => c,
                Err(_) => continue,
            };
            if cur.group.as_deref() != Some(&id) {
                continue;
            }
            cur.group = None;
            crate::instances::write_instance(&cur).await.map_err(|e| e.to_string())?;
        }
    }
    Ok(())
}

/// 按给定顺序重排分组（返回新列表）
pub async fn reorder_groups(ids: Vec<String>) -> Result<Vec<InstanceGroup>, String> {
    let map = load_all_strict().await?;
    let mut out = Vec::with_capacity(ids.len());
    for id in ids {
        if let Some(g) = map.get(&id) {
            out.push(g.clone());
        }
    }
    // 补上未出现在 ids 中的分组（保持稳定）
    let mut rest: Vec<InstanceGroup> = map
        .into_values()
        .filter(|g| !out.iter().any(|o| o.id == g.id))
        .collect();
    rest.sort_by_key(|g| g.created);
    out.extend(rest);
    save_all(
        &out.iter()
            .map(|g| (g.id.clone(), g.clone()))
            .collect::<HashMap<_, _>>(),
    )
    .await?;
    Ok(out)
}
