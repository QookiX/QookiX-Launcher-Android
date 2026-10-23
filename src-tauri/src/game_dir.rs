//! 「游戏目录」：内部存储 / 应用专属外部目录 / 自定义目录（SAF 选）。
//!
//! 这里只管**设置项与迁移**；路径解析在 `settings`（`game_root` / `instances_root` /
//! `instance_dir`），目录枚举与权限由 Android 侧 `StorageDirs.kt` + `MainActivity` 提供。
//!
//! 为什么自定义目录必须配「所有文件访问」：游戏 JVM（`--gameDir` / `-Duser.home`）与
//! Rust 的 `std::fs` 都只认**真实路径**，而 Android 11+ 的 scoped storage 会拦掉对
//! 公共目录的原始读写 —— SAF 授权只对 `ContentResolver` 生效，救不了 `java.io.File`。

use serde::{Deserialize, Serialize};
use std::path::Path;

/// 一个可选的游戏目录（Android 侧枚举各卷后回传）。
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct GameDirOption {
    /// `internal` / `external`
    pub kind: String,
    pub label: String,
    /// 游戏数据根（`instances/` 会建在其下）
    pub path: String,
    /// 可用空间（字节）
    pub free: u64,
    pub total: u64,
    /// 是否可插拔（SD 卡）
    pub removable: bool,
}

/// 当前「游戏目录」状态。
#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct GameDirState {
    /// 内部私有数据目录（默认值）
    pub default_root: String,
    /// `settings.json` 里的 `game_root`（`None` = 用内部目录）
    pub custom_root: Option<String>,
    /// 实际生效的数据根
    pub root: String,
    /// 实际生效的实例目录根（= `root/instances`）
    pub instances_dir: String,
    /// Android 11+ 的「所有文件访问」是否已授权
    pub all_files_access: bool,
}

fn all_files_access() -> bool {
    #[cfg(target_os = "android")]
    {
        crate::android_bridge::has_all_files_access()
    }
    #[cfg(not(target_os = "android"))]
    {
        true
    }
}

async fn state() -> Result<GameDirState, String> {
    let default_root = crate::settings::get_data_dir()
        .await
        .map_err(|e| e.to_string())?;
    let custom_root = crate::settings::custom_game_root().await;
    let root = crate::settings::game_root().await.map_err(|e| e.to_string())?;
    let instances_dir = crate::settings::resolve_instances_root(Path::new(&root))
        .to_string_lossy()
        .to_string();
    Ok(GameDirState {
        default_root,
        custom_root,
        root,
        instances_dir,
        all_files_access: all_files_access(),
    })
}

/// 当前「游戏目录」状态。
#[tauri::command]
pub async fn get_game_dir_state() -> Result<GameDirState, String> {
    state().await
}

/// 可选目录（内部存储 + 各卷的应用专属外部目录，含剩余空间）。
#[tauri::command]
pub fn get_game_dir_options() -> Vec<GameDirOption> {
    #[cfg(target_os = "android")]
    {
        crate::android_bridge::game_dir_options_json()
            .and_then(|s| serde_json::from_str::<Vec<GameDirOption>>(&s).ok())
            .unwrap_or_default()
    }
    #[cfg(not(target_os = "android"))]
    {
        Vec::new()
    }
}

/// 「所有文件访问」是否已授权。
#[tauri::command]
pub fn has_all_files_access() -> bool {
    all_files_access()
}

/// 跳到系统设置页申请「所有文件访问」（Android 11+）。
#[tauri::command]
pub fn request_all_files_access() {
    #[cfg(target_os = "android")]
    crate::android_bridge::request_all_files_access();
}

/// 弹出系统的目录选择器（异步；结果用 [`take_picked_game_dir`] 取回）。
#[tauri::command]
pub fn pick_game_dir() -> Result<(), String> {
    #[cfg(target_os = "android")]
    {
        crate::android_bridge::pick_game_dir();
        Ok(())
    }
    #[cfg(not(target_os = "android"))]
    {
        Err("桌面端不支持系统目录选择器".to_string())
    }
}

/// 取回系统选择器刚选中的目录（**取即清空**，只生效一次）。
///
/// 原生侧结果文件：`<files>/game-dir-pick.json`（`{"path","error"}`）。
#[tauri::command]
pub async fn take_picked_game_dir() -> Result<Option<String>, String> {
    let data_dir = crate::settings::get_data_dir()
        .await
        .map_err(|e| e.to_string())?;
    let file = Path::new(&data_dir).join("game-dir-pick.json");
    if !file.is_file() {
        return Ok(None);
    }
    let text = tokio::fs::read_to_string(&file)
        .await
        .map_err(|e| e.to_string())?;
    let _ = tokio::fs::remove_file(&file).await;
    let value: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let error = value.get("error").and_then(|v| v.as_str()).unwrap_or("");
    if !error.is_empty() {
        return Err(error.to_string());
    }
    let path = value.get("path").and_then(|v| v.as_str()).unwrap_or("");
    if path.trim().is_empty() {
        return Ok(None); // 用户取消
    }
    Ok(Some(path.to_string()))
}

/// 设置游戏目录（`None` / 空串 = 回到内部存储）。
///
/// `migrate = true` 时把已有实例搬到新目录（同分区是 `rename`，跨分区回退复制+删除）。
/// **先搬成功再落盘设置** —— 搬失败就保持原设置不动，免得把界面指向一个半空的目录。
#[tauri::command]
pub async fn set_game_dir(root: Option<String>, migrate: bool) -> Result<GameDirState, String> {
    let mut new_root: Option<String> = match root.as_deref().map(str::trim) {
        None | Some("") => None,
        Some(p) => {
            if !Path::new(p).is_absolute() {
                return Err("请选择绝对路径的目录".to_string());
            }
            Some(p.trim_end_matches('/').to_string())
        }
    };

    // 选中的其实就是内部目录时按「未设置」处理，别把可变的应用数据路径写死进设置。
    // `/data/user/0/…` 与 `/data/data/…` 是同一个目录（前者是指向后的符号链接），
    // 所以必须 canonicalize 后再比，不能直接比字符串。
    if let Some(picked) = new_root.clone() {
        if let Ok(internal) = crate::settings::get_data_dir().await {
            let same = std::fs::canonicalize(&picked).ok() == std::fs::canonicalize(&internal).ok();
            if same {
                new_root = None;
            }
        }
    }

    let old_instances = crate::settings::instances_root()
        .await
        .map_err(|e| e.to_string())?;
    let new_instances = match &new_root {
        Some(r) => crate::settings::resolve_instances_root(Path::new(r)),
        None => crate::settings::resolve_instances_root(Path::new(
            &crate::settings::get_data_dir().await.map_err(|e| e.to_string())?,
        )),
    };

    tokio::fs::create_dir_all(&new_instances)
        .await
        .map_err(|e| format!("无法创建目录 {}：{e}", new_instances.display()))?;

    // ⚠️ 真机反馈（OPPO ColorOS 12 / iQOO OriginOS / 澎湃 OS 三台**全部**）：换目录"完全不生效"。
    // 光「目录建得出来」不够 —— 从 Android 11 起访问 `/storage/...` 需要「所有文件访问」
    // （MANAGE_EXTERNAL_STORAGE）；没授时可能建得出目录、但后续游戏与 Rust 的读写全被拒，
    // 而界面还显示"已切换"，用户看到的就是「换了没用」。
    // 所以在**落盘设置之前**做一次真实写入探测：写一字节 → 读回来 → 删掉。
    let probe = new_instances.join(".qookix-write-test");
    let probe_res: std::io::Result<()> = async {
        tokio::fs::write(&probe, b"ok").await?;
        let back = tokio::fs::read(&probe).await?;
        tokio::fs::remove_file(&probe).await?;
        if back != b"ok" {
            return Err(std::io::Error::other("回读内容不一致"));
        }
        Ok(())
    }
    .await;
    if let Err(e) = probe_res {
        let _ = tokio::fs::remove_file(&probe).await;
        crate::util::log_line(&format!(
            "[game_dir] 写入探测失败 {}：{e}",
            new_instances.display()
        ));
        return Err(format!(
            "这个目录无法读写（{e}）。请先授予「所有文件访问」权限，或换一个目录（例如 SD 卡根目录下新建一个文件夹）。"
        ));
    }
    crate::util::log_line(&format!(
        "[game_dir] 准备切换：old={} new={} migrate={migrate}",
        old_instances.display(),
        new_instances.display()
    ));

    if migrate && old_instances != new_instances {
        // ⚠️ 2026-10-22 加护栏：`old_instances` **绝不能**是数据根目录本身。
        //
        // 一旦它是 `files/`，`migrate_instances` 就会把 `files/` 下的
        // 每个子目录都当成实例搬走 —— 包括 `servers/`（用户的服务器）、
        // `skins/`、`accounts/`。跨分区时还会 `remove_dir_all` 删源目录，
        // 结果就是「用户的数据凭空消失，界面上一个都不剩」。
        let data_dir = crate::settings::get_data_dir().await.map_err(|e| e.to_string())?;
        let old_is_data_root = std::fs::canonicalize(&old_instances).ok()
            == std::fs::canonicalize(&data_dir).ok();
        if old_is_data_root {
            crate::util::log_line(&format!(
                "[game_dir] 拒绝迁移：来源 {} 就是数据根目录（会误删 servers/skins/accounts）",
                old_instances.display()
            ));
            println!(
                "[game_dir] 拒绝迁移：来源 {} 就是数据根目录（会误删 servers/skins/accounts）",
                old_instances.display()
            );
        } else {
            let moved = migrate_instances(&old_instances, &new_instances)
                .await
                .map_err(|e| format!("迁移失败：{e}"))?;
            crate::util::log_line(&format!(
                "[game_dir] 已迁移 {} 个实例到 {}",
                moved,
                new_instances.display()
            ));
            println!("[game_dir] 已迁移 {} 个实例到 {}", moved, new_instances.display());
        }
    }

    crate::settings::set_game_root(new_root.as_deref())
        .await
        .map_err(|e| e.to_string())?;

    state().await
}

/// 把 `from` 下的实例目录搬到 `to`。目标已存在则跳过（不覆盖）。返回搬动数量。
///
/// ⚠️ 2026-10-22 加防呆：**只搬「真正的实例」**，别的一概不碰。
///
/// 之前是「`from` 下的每个子目录都搬」，而 `from` 来自
/// `resolve_instances_root(game_root)` —— 一旦那个路径算错
/// （比如指向了数据根目录本身），就会把 `servers/`（用户的服务器！）、
/// `skins/`、`accounts/` 这些**根本不是实例**的目录一起搬走，
/// 其中跨分区失败时还会 `remove_dir_all` 删掉源目录 ——
/// 结果就是「服务器莫名消失，界面上一个都不剩」。
///
/// 现在只搬带实例标记（`instance.json` / `version.json`）的子目录。
async fn migrate_instances(from: &Path, to: &Path) -> anyhow::Result<usize> {
    if !from.is_dir() {
        return Ok(0);
    }
    tokio::fs::create_dir_all(to).await?;
    let mut entries = tokio::fs::read_dir(from).await?;
    let mut moved = 0usize;
    while let Some(entry) = entries.next_entry().await? {
        if !entry.file_type().await?.is_dir() {
            continue;
        }
        let src = entry.path();
        let name = entry.file_name();
        // ⚠️ 只搬真正的实例（见上面的说明）。
        if !is_instance_dir(&src).await {
            continue;
        }
        let dst = to.join(&name);
        if dst.exists() {
            continue;
        }
        // 同分区 rename 是即时的；跨分区（内部 → SD 卡）会失败，退回复制+删除。
        if tokio::fs::rename(&src, &dst).await.is_err() {
            let (s, d) = (src.clone(), dst.clone());
            tokio::task::spawn_blocking(move || copy_dir_all_sync(&s, &d))
                .await
                .map_err(|e| anyhow::anyhow!("复制任务失败: {e}"))??;
            tokio::fs::remove_dir_all(&src).await?;
        }
        moved += 1;
    }
    Ok(moved)
}

/// 判断一个目录是不是「一个实例」（有实例标记文件）。
async fn is_instance_dir(dir: &Path) -> bool {
    if tokio::fs::metadata(dir.join("instance.json")).await.is_ok() {
        return true;
    }
    if tokio::fs::metadata(dir.join("version.json")).await.is_ok() {
        return true;
    }
    // 没有标记文件时，看目录里有没有「实例该有的东西」（mods/config 等）。
    // ⚠️ 刻意**不**看目录名 —— 老实例目录名是 UUID，新的是 id，
    // 而且「名字里带 servers」这种判断太脆。
    for marker in ["mods", "config", "options.txt", "logs"] {
        if tokio::fs::metadata(dir.join(marker)).await.is_ok() {
            return true;
        }
    }
    false
}

/// 递归复制目录（同步实现；`tokio::fs` 没有开箱版本，交给阻塞线程池跑）。
fn copy_dir_all_sync(src: &Path, dst: &Path) -> std::io::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let target = dst.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            copy_dir_all_sync(&entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}
