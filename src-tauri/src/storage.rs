//! 存储统计：扫描数据根目录各分类大小，支持磁盘缓存与缓存清理。

use crate::models::{CacheClearResult, InstanceStorage, StorageCategory, StorageStats};
use crate::settings::get_data_dir;
use std::path::{Path, PathBuf};

/// 统计结果缓存文件名（位于数据根目录）
const CACHE_FILE: &str = "storage-cache.json";

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// 递归统计目录大小与文件数。
fn dir_size(path: &Path, files: &mut u64) -> u64 {
    let mut total = 0u64;
    let Ok(rd) = std::fs::read_dir(path) else {
        return 0;
    };
    for entry in rd.flatten() {
        let p = entry.path();
        let Ok(meta) = entry.metadata() else {
            continue;
        };
        if meta.is_dir() {
            total += dir_size(&p, files);
        } else if meta.is_file() {
            *files += 1;
            total += meta.len();
        }
    }
    total
}

/// 从实例元数据（instance.json）读取名称
fn instance_name_of(id: &str, dir: &Path) -> String {
    std::fs::read_to_string(dir.join("instance.json"))
        .ok()
        .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .and_then(|v| v.get("name").and_then(|n| n.as_str()).map(String::from))
        .filter(|n| !n.trim().is_empty())
        .unwrap_or_else(|| id.to_string())
}

fn empty_stats() -> StorageStats {
    StorageStats {
        categories: Vec::new(),
        instances: Vec::new(),
        servers: Vec::new(),
        total: 0,
        instance_count: 0,
        server_count: 0,
        updated_at: now_secs(),
        cached: false,
    }
}

/// 扫描数据目录，得到各分类的大小统计。
///
/// 整段都是**同步递归 IO**，必须挪到阻塞线程池：直接写在 `async fn` 里会占住一个
/// tokio worker，同一个 worker 上的其它 `invoke` 全部排队 —— 表现就是
/// 「打开存储页卡几十秒，期间连返回键都迟钝」。（同 `natives.rs` 的 `extract_sync` 形态。）
pub async fn scan() -> StorageStats {
    let Ok(root_str) = get_data_dir().await else {
        return empty_stats();
    };
    let root = PathBuf::from(&root_str);
    // 实例根可能被用户改到外部目录（见 settings::game_root）——统计必须用实际位置，
    // 否则存储页会漏掉外部实例，还会把它们算进「其他数据」。
    let inst_root = crate::settings::instances_root()
        .await
        .unwrap_or_else(|_| crate::settings::resolve_instances_root(&root));
    match tokio::task::spawn_blocking(move || scan_sync(&root, &inst_root)).await {
        Ok(stats) => stats,
        Err(e) => {
            tracing::warn!("存储扫描任务失败: {e}");
            empty_stats()
        }
    }
}

/// [scan] 的同步实现。
/// `inst_root` 是实例目录根（默认 `<root>/instances`，也可能被用户改到外部目录）。
fn scan_sync(root: &Path, inst_root: &Path) -> StorageStats {
    let mut categories: Vec<StorageCategory> = Vec::new();
    let mut total = 0u64;

    let add_dir = |categories: &mut Vec<StorageCategory>,
                       total: &mut u64,
                       key: &str,
                       label: &str,
                       dir: PathBuf| {
        let mut files = 0u64;
        let size = dir_size(&dir, &mut files);
        categories.push(StorageCategory {
            key: key.into(),
            label: label.into(),
            size,
            files,
        });
        *total += size;
    };

    // 游戏实例：**一次遍历**同时拿到「每个实例的大小」和「分类合计」。
    // 以前是 `add_dir`（整棵树）+ 再逐实例 `dir_size`（又是整棵树），同一份数据走了两遍 ——
    // 一个 20GB 的实例目录在手机上白多花好几秒。
    let inst_dir = inst_root.to_path_buf();
    let mut instance_count = 0u64;
    let mut instances: Vec<InstanceStorage> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&inst_dir) {
        for entry in rd.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            instance_count += 1;
            let id = entry.file_name().into_string().unwrap_or_default();
            let name = instance_name_of(&id, &path);
            let mut files = 0u64;
            let size = dir_size(&path, &mut files);
            instances.push(InstanceStorage {
                id,
                name,
                size,
                files,
            });
        }
    }
    instances.sort_by(|a, b| b.size.cmp(&a.size));
    let inst_size: u64 = instances.iter().map(|i| i.size).sum();
    let inst_files: u64 = instances.iter().map(|i| i.files).sum();
    categories.push(StorageCategory {
        key: "instances".into(),
        label: "游戏实例".into(),
        size: inst_size,
        files: inst_files,
    });
    total += inst_size;

    // 托管服务器实例：同样改成一次遍历
    let srv_dir = crate::settings::resolve_servers_root(&root);
    let mut server_count = 0u64;
    let mut servers: Vec<InstanceStorage> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&srv_dir) {
        for entry in rd.flatten() {
            let path = entry.path();
            if !path.is_dir() {
                continue;
            }
            server_count += 1;
            let id = entry.file_name().into_string().unwrap_or_default();
            let name = std::fs::read_to_string(path.join("server.json"))
                .ok()
                .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
                .and_then(|v| v.get("name").and_then(|n| n.as_str()).map(String::from))
                .filter(|n| !n.trim().is_empty())
                .unwrap_or_else(|| id.clone());
            let mut files = 0u64;
            let size = dir_size(&path, &mut files);
            servers.push(InstanceStorage {
                id,
                name,
                size,
                files,
            });
        }
    }
    servers.sort_by(|a, b| b.size.cmp(&a.size));
    let srv_size: u64 = servers.iter().map(|s| s.size).sum();
    let srv_files: u64 = servers.iter().map(|s| s.files).sum();
    categories.push(StorageCategory {
        key: "servers".into(),
        label: "服务器实例".into(),
        size: srv_size,
        files: srv_files,
    });
    total += srv_size;

    // 启动器共享数据
    add_dir(&mut categories, &mut total, "libraries", "库文件", root.join("libraries"));
    add_dir(&mut categories, &mut total, "assets", "资源文件", root.join("assets"));
    add_dir(&mut categories, &mut total, "versions", "版本文件", root.join("versions"));
    let runtime_dl = root.join("runtimes").join("downloads");
    add_dir(&mut categories, &mut total, "runtime", "Java 运行时", root.join("runtimes"));
    add_dir(&mut categories, &mut total, "logs", "日志", root.join("logs"));

    // 缓存：可安全清理的内容（Java 下载临时目录 + Java 检测缓存 + 本统计缓存）
    let mut cache_files = 0u64;
    let mut cache_size = 0u64;
    if runtime_dl.exists() {
        cache_size += dir_size(&runtime_dl, &mut cache_files);
    }
    for cf in [root.join("java-cache.json"), cache_path(&root)] {
        if cf.exists() {
            if let Ok(meta) = cf.metadata() {
                cache_files += 1;
                cache_size += meta.len();
            }
        }
    }
    categories.push(StorageCategory {
        key: "cache".into(),
        label: "缓存".into(),
        size: cache_size,
        files: cache_files,
    });
    total += cache_size;

    // 其他：数据根下未被上面覆盖的条目
    let mut other_files = 0u64;
    let mut other_size = 0u64;
    if let Ok(rd) = std::fs::read_dir(&root) {
        for entry in rd.flatten() {
            let p = entry.path();
            let covered = [
                inst_dir.clone(),
                root.join("libraries"),
                root.join("assets"),
                root.join("versions"),
                root.join("runtimes"),
                root.join("logs"),
                srv_dir.clone(),
            ]
            .iter()
            .any(|d| p.starts_with(d));
            let fname = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
            if covered || fname == CACHE_FILE || fname == "java-cache.json" {
                continue;
            }
            if let Ok(meta) = entry.metadata() {
                if meta.is_dir() {
                    other_size += dir_size(&p, &mut other_files);
                } else if meta.is_file() {
                    other_files += 1;
                    other_size += meta.len();
                }
            }
        }
    }
    categories.push(StorageCategory {
        key: "other".into(),
        label: "其他数据".into(),
        size: other_size,
        files: other_files,
    });
    total += other_size;

    // 去掉无数据的分类，保持列表干净
    categories.retain(|c| c.size > 0);

    StorageStats {
        categories,
        instances,
        servers,
        total,
        instance_count,
        server_count,
        updated_at: now_secs(),
        cached: false,
    }
}

fn cache_path(root: &Path) -> PathBuf {
    root.join(CACHE_FILE)
}

fn load_cache(root: &Path) -> Option<StorageStats> {
    let data = std::fs::read_to_string(cache_path(root)).ok()?;
    serde_json::from_str::<StorageStats>(&data)
        .ok()
        .map(|mut s| {
            s.cached = true;
            s
        })
}

fn save_cache(root: &Path, stats: &StorageStats) {
    if let Ok(json) = serde_json::to_string(stats) {
        let _ = std::fs::write(cache_path(root), json);
    }
}

/// 获取存储统计：优先返回上次扫描的缓存，无缓存时执行实时扫描
pub async fn get_storage_stats() -> StorageStats {
    let root_str = get_data_dir().await.unwrap_or_default();
    let root = PathBuf::from(&root_str);
    if let Some(cached) = load_cache(&root) {
        return cached;
    }
    let stats = scan().await;
    save_cache(&root, &stats);
    stats
}

/// 强制重新扫描并保存缓存
pub async fn refresh_storage_stats() -> StorageStats {
    let stats = scan().await;
    if let Ok(root_str) = get_data_dir().await {
        save_cache(&PathBuf::from(&root_str), &stats);
    }
    stats
}

/// 清除可安全清理的缓存（Java 下载临时文件、Java 检测缓存、本统计缓存）。
pub async fn clear_cache() -> Result<CacheClearResult, String> {
    let root_str = get_data_dir().await.map_err(|e| e.to_string())?;
    let root = PathBuf::from(&root_str);
    let mut freed = 0u64;

    let dl_dir = root.join("runtimes").join("downloads");
    if dl_dir.exists() {
        let mut files = 0u64;
        freed += dir_size(&dl_dir, &mut files);
        std::fs::remove_dir_all(&dl_dir).map_err(|e| format!("清理 Java 下载缓存失败: {e}"))?;
    }

    let java_cache = root.join("java-cache.json");
    if java_cache.exists() {
        freed += java_cache.metadata().map(|m| m.len()).unwrap_or(0);
        let _ = std::fs::remove_file(&java_cache);
    }

    let stat_cache = cache_path(&root);
    if stat_cache.exists() {
        freed += stat_cache.metadata().map(|m| m.len()).unwrap_or(0);
        let _ = std::fs::remove_file(&stat_cache);
    }

    Ok(CacheClearResult { freed })
}
