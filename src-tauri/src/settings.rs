use std::collections::HashMap;
use std::path::{Path, PathBuf};
use serde_json::Value;
use tokio::fs;
use anyhow::{Context, Result};
use crate::models::*;

/// 设置文件的键名统一使用 snake_case（与前端 `Settings` 类型一致）。
/// 早期版本写过 camelCase，这里按「snake_case 优先、camelCase 兜底」读取，保证老文件不丢设置。
pub fn raw_str(map: &HashMap<String, Value>, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| map.get(*k))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

fn raw_i64(map: &HashMap<String, Value>, keys: &[&str], default: i64) -> i64 {
    keys.iter()
        .find_map(|k| map.get(*k))
        .and_then(|v| v.as_i64())
        .unwrap_or(default)
}

fn raw_bool(map: &HashMap<String, Value>, keys: &[&str], default: bool) -> bool {
    keys.iter()
        .find_map(|k| map.get(*k))
        .and_then(|v| v.as_bool())
        .unwrap_or(default)
}

/// 同步版数据目录解析：供无法 `await` 的调用点（如只能同步构造 HTTP 客户端的地方）使用。
/// 与 [`get_data_dir`] 保持同一套包名解析规则。
pub fn data_dir_sync() -> Option<String> {
    #[cfg(target_os = "android")]
    {
        android_package_name().map(|pkg| format!("/data/data/{pkg}/files"))
    }
    #[cfg(not(target_os = "android"))]
    {
        dirs::data_dir().map(|d| d.join("Qookix").to_string_lossy().to_string())
    }
}

/// 实例目录的**新**名字（2026-10-17 从 `instances` 改为 `game`）。
///
/// 为什么改：`instances` 对用户不直观。别的启动器（PojavLauncher / HMCL）用
/// `.minecraft`，玩家一眼能懂；Android 上没法用 `.minecraft`（`files/` 是
/// `Context.getFilesDir()` 的标准路径，且沙箱已经隔离，`.` 前缀无意义），
/// 但至少可以叫 `game` —— 比 `instances` 短、直白。
pub const GAME_DIR_NAME: &str = "game";

/// 实例目录的**旧**名字。保留只为**兼容读取**（老用户数据还在 `instances/`）。
///
/// 迁移策略（用户确认的方案 B）：
///   - **写**：永远写新目录 `game/`
///   - **读**：两个都认 —— 新目录存在就用新目录，否则回退旧目录
///
/// 这样老用户升级后数据还在、不会"消失"；新装用户从零开始就是新名字。
pub const GAME_DIR_NAME_LEGACY: &str = "instances";

/// 服务器目录的**新**名字（原 `servers`）。
pub const SERVER_DIR_NAME: &str = "server";

/// 服务器目录的**旧**名字（兼容读取）。
pub const SERVER_DIR_NAME_LEGACY: &str = "servers";

/// 解析「实例目录根」：优先新名 `game/`，不存在则回退旧名 `instances/`。
///
/// 注意：**只在旧目录存在且新目录不存在时**才回退 —— 一旦用户有了新目录，
/// 就再也不看旧目录（避免两个目录各有一半实例、界面上分裂）。
pub fn resolve_instances_root(root: &Path) -> PathBuf {
    let new_dir = root.join(GAME_DIR_NAME);
    let legacy = root.join(GAME_DIR_NAME_LEGACY);
    if new_dir.exists() || !legacy.exists() {
        new_dir
    } else {
        legacy
    }
}

/// 解析「服务器目录根」：优先新名 `server/`，不存在则回退旧名 `servers/`。
pub fn resolve_servers_root(root: &Path) -> PathBuf {
    let new_dir = root.join(SERVER_DIR_NAME);
    let legacy = root.join(SERVER_DIR_NAME_LEGACY);
    if new_dir.exists() || !legacy.exists() {
        new_dir
    } else {
        legacy
    }
}
/// 游戏数据根目录（`instances/` 的父目录）。
///
/// 默认 `<data>`（内部私有目录）；用户在「存储 → 游戏目录」里选了外部目录时，
/// 读 `settings.json` 的 `game_root`。**只影响实例（游戏目录）本身** ——
/// `libraries/ assets/ versions/ natives/ runtimes/ plugins/` 一律留在内部
/// （`natives` 要解压 `.so` 再 `dlopen`，SD 卡多为 noexec 挂载，放外面会加载失败）。
pub fn game_root_sync() -> Option<String> {
    let map = load_raw_settings_sync();
    let custom = map
        .get("game_root")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim();
    if !custom.is_empty() {
        Some(custom.to_string())
    } else {
        data_dir_sync()
    }
}

/// 同步版实例目录根 = [`game_root_sync`] + `instances`（供无法 `await` 的调用点）。
pub fn instances_root_sync() -> Option<String> {
    game_root_sync().map(|r| resolve_instances_root(Path::new(&r)).to_string_lossy().to_string())
}

/// 同步读取设置文件原始 JSON（不存在或损坏时返回空表）。
pub fn load_raw_settings_sync() -> HashMap<String, Value> {
    let Some(dir) = data_dir_sync() else {
        return HashMap::new();
    };
    let settings_path = Path::new(&dir).join("settings.json");
    match std::fs::read_to_string(&settings_path) {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
}

/// 读取设置文件原始 JSON（不存在或损坏时返回空表）。
pub async fn load_raw_settings() -> HashMap<String, Value> {
    let Ok(data_dir) = get_data_dir().await else {
        return HashMap::new();
    };
    let settings_path = Path::new(&data_dir).join("settings.json");
    if !settings_path.exists() {
        return HashMap::new();
    }
    match fs::read_to_string(&settings_path).await {
        Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
        Err(_) => HashMap::new(),
    }
}

/// 写入设置文件原始 JSON。
pub async fn save_raw_settings(map: &HashMap<String, Value>) -> Result<()> {
    let data_dir = get_data_dir().await?;
    let settings_path = Path::new(&data_dir).join("settings.json");
    let content = serde_json::to_string_pretty(map)?;
    fs::write(&settings_path, content)
        .await
        .with_context(|| format!("Failed to write settings file: {}", settings_path.display()))?;
    // 写盘必须是**唯一**的失效入口：缓存与文件不一致的话，用户改完设置不生效才是最难查的 bug
    invalidate_settings_cache();
    // 代理/镜像等设置改变后让缓存的 HTTP 客户端立即重建
    invalidate_settings_cache();
    crate::util::reset_http_client();
    Ok(())
}

/// 进程内缓存：`(data_dir, 解析后的设置原始 JSON)`。
///
/// 为什么需要：`get_settings()` 的实现是「`fs::read_to_string` + `serde_json` 解析」，
/// 而下载热路径上**每个文件**都要问它一次（`download.rs` 里读并发数、读镜像、读代理），
/// 并发 8~32 个文件时就是每秒几十次磁盘读 + 全量解析。设置只在用户改的时候变，
/// 缓存到进程内、写盘时失效即可。
static SETTINGS_CACHE: std::sync::OnceLock<
    std::sync::Mutex<Option<(String, HashMap<String, Value>)>>,
> = std::sync::OnceLock::new();

fn settings_cache() -> &'static std::sync::Mutex<Option<(String, HashMap<String, Value>)>> {
    SETTINGS_CACHE.get_or_init(|| std::sync::Mutex::new(None))
}

/// 设置文件被写过之后调用（见 `save_raw_settings`）。
pub fn invalidate_settings_cache() {
    if let Ok(mut guard) = settings_cache().lock() {
        *guard = None;
    }
}

/// 取值期间持锁、克隆后立刻释放（**不跨 await**）。
fn cached_settings_map(data_dir: &str) -> Option<HashMap<String, Value>> {
    settings_cache()
        .lock()
        .ok()
        .and_then(|g| {
            g.as_ref()
                .filter(|(cached_dir, _)| cached_dir == data_dir)
                .map(|(_, map)| map.clone())
        })
}

pub async fn get_settings() -> Result<Settings> {
    let data_dir = get_data_dir().await?;

    let settings_path = Path::new(&data_dir).join("settings.json");

    if settings_path.exists() {
        // 命中缓存就跳过磁盘读 + 解析：下载热路径上**每个文件**都会问一次设置
        // （并发数、镜像、代理都在里面），几十路并发时这里就是每秒几十次文件 IO。
        // 设置只在用户改的时候变，写入路径（`save_raw_settings`）会清缓存。
        let settings: HashMap<String, Value> = match cached_settings_map(&data_dir) {
            Some(map) => map,
            None => {
                let content = fs::read_to_string(&settings_path).await
                    .context("Failed to read settings file")?;
                let parsed: HashMap<String, Value> = serde_json::from_str(&content)
                    .context("Failed to parse settings")?;
                if let Ok(mut guard) = settings_cache().lock() {
                    *guard = Some((data_dir.clone(), parsed.clone()));
                }
                parsed
            }
        };

        Ok(Settings {
            data_dir: data_dir.clone(),
            java_path: raw_str(&settings, &["java_path", "javaPath"]),
            max_memory_mb: raw_i64(&settings, &["max_memory_mb", "maxMemoryMb"], 2048) as i32,
            min_memory_mb: raw_i64(&settings, &["min_memory_mb", "minMemoryMb"], 512) as i32,
            memory_mode: raw_str(&settings, &["memory_mode", "memoryMode"]).unwrap_or_else(|| "auto".into()),
            jvm_args: raw_str(&settings, &["jvm_args", "jvmArgs"]).unwrap_or_default(),
            game_args: raw_str(&settings, &["game_args", "gameArgs"]).unwrap_or_default(),
            // 默认值与桌面端一致（文件 8 / 分片 4）：以前是 4 / 2，装机时几百个库加几千个
            // 资源对象全部按这个并发走，手机上就显得很慢（同样的网络桌面端明显更快）。
            download_threads: raw_i64(&settings, &["download_threads", "downloadThreads"], 8) as i32,
            download_chunk_threads: raw_i64(&settings, &["download_chunk_threads", "downloadChunkThreads"], 4) as i32,
            curseforge_api_key: raw_str(&settings, &["curseforge_api_key", "curseforgeApiKey"]),
            translate_provider: raw_str(&settings, &["translate_provider", "translateProvider"])
                .unwrap_or_else(|| "default".into()),
            translate_api_base: raw_str(&settings, &["translate_api_base", "translateApiBase"])
                .unwrap_or_default(),
            translate_api_key: raw_str(&settings, &["translate_api_key", "translateApiKey"]),
            translate_api_model: raw_str(&settings, &["translate_api_model", "translateApiModel"])
                .unwrap_or_default(),
            body_translate_auto: raw_bool(&settings, &["body_translate_auto", "bodyTranslateAuto"], false),
            theme: raw_str(&settings, &["theme"]).unwrap_or_else(|| "dark".into()),
            theme_color: raw_str(&settings, &["theme_color", "themeColor"]).unwrap_or_else(|| "#E89A4B".into()),
            close_behavior: raw_str(&settings, &["close_behavior", "closeBehavior"]).unwrap_or_else(|| "minimize".into()),
            auto_launch: raw_bool(&settings, &["auto_launch", "autoLaunch"], false),
            keep_open: raw_bool(&settings, &["keep_open", "keepOpen"], true),
            ms_client_id: raw_str(&settings, &["ms_client_id", "msClientId"]).unwrap_or_else(|| "00000000402b5328".into()),
            selected_account: raw_str(&settings, &["selected_account", "selectedAccount"]),
            proxy_mode: raw_str(&settings, &["proxy_mode", "proxyMode"]).unwrap_or_else(|| "system".into()),
            proxy: raw_str(&settings, &["proxy"]),
            mirror: raw_str(&settings, &["mirror"]).unwrap_or_else(|| "bmclapi".into()),
            mirror_custom: raw_str(&settings, &["mirror_custom", "mirrorCustom"]),
            background_image: raw_str(&settings, &["background_image", "backgroundImage"]),
            background_blur: raw_i64(&settings, &["background_blur", "backgroundBlur"], 0) as i32,
            background_dim: raw_i64(&settings, &["background_dim", "backgroundDim"], 0) as i32,
            glass_blur: raw_i64(&settings, &["glass_blur", "glassBlur"], 0) as i32,
            ui_scale: raw_i64(&settings, &["ui_scale", "uiScale"], 100).clamp(60, 200) as i32,
            show_sidebar_collapse_btn: raw_bool(&settings, &["show_sidebar_collapse_btn", "showSidebarCollapseBtn"], true),
            show_news: raw_bool(&settings, &["show_news", "showNews"], true),
            show_home_downloads: raw_bool(&settings, &["show_home_downloads", "showHomeDownloads"], true),
            show_home_recent: raw_bool(&settings, &["show_home_recent", "showHomeRecent"], true),
            show_home_stats: raw_bool(&settings, &["show_home_stats", "showHomeStats"], true),
            dismissed_update_version: raw_str(&settings, &["dismissed_update_version", "dismissedUpdateVersion"]),
            auto_update: raw_bool(&settings, &["auto_update", "autoUpdate"], true),
            update_source: raw_str(&settings, &["update_source", "updateSource"]).unwrap_or_else(|| "bucket".into()),
            // 手机上启动器是「清单+操作」型界面，竖屏单列信息密度最合适（游戏自己会转横屏）
            orientation: raw_str(&settings, &["orientation"]).unwrap_or_else(|| "portrait".into()),
        })
    } else {
        // Default settings
        Ok(Settings {
            data_dir: data_dir.clone(),
            java_path: None,
            max_memory_mb: 2048,
            min_memory_mb: 512,
            memory_mode: "auto".to_string(),
            jvm_args: String::new(),
            game_args: String::new(),
            download_threads: 8,
            download_chunk_threads: 4,
            curseforge_api_key: None,
            translate_provider: "default".to_string(),
            translate_api_base: String::new(),
            translate_api_key: None,
            translate_api_model: String::new(),
            body_translate_auto: false,
            theme: "dark".to_string(),
            theme_color: "#E89A4B".to_string(),
            close_behavior: "minimize".to_string(),
            auto_launch: false,
            keep_open: true,
            ms_client_id: "00000000402b5328".to_string(),
            selected_account: None,
            proxy_mode: "system".to_string(),
            proxy: None,
            mirror: "bmclapi".to_string(),
            mirror_custom: None,
            background_image: None,
            background_blur: 0,
            background_dim: 0,
            glass_blur: 0,
            ui_scale: 100,
            show_sidebar_collapse_btn: true,
            show_news: true,
            show_home_downloads: true,
            show_home_recent: true,
            show_home_stats: true,
            dismissed_update_version: None,
            auto_update: true,
            update_source: "bucket".to_string(),
            orientation: "portrait".to_string(),
        })
    }
}

pub async fn update_settings(settings: Settings) -> Result<()> {
    let data_dir = get_data_dir().await?;
    let settings_path = Path::new(&data_dir).join("settings.json");

    let mut map = HashMap::new();
    map.insert("java_path".to_string(), Value::String(settings.java_path.unwrap_or_default()));
    map.insert("max_memory_mb".to_string(), Value::Number(settings.max_memory_mb.into()));
    map.insert("min_memory_mb".to_string(), Value::Number(settings.min_memory_mb.into()));
    map.insert("memory_mode".to_string(), Value::String(settings.memory_mode));
    map.insert("jvm_args".to_string(), Value::String(settings.jvm_args));
    map.insert("game_args".to_string(), Value::String(settings.game_args));
    map.insert("download_threads".to_string(), Value::Number(settings.download_threads.into()));
    map.insert("download_chunk_threads".to_string(), Value::Number(settings.download_chunk_threads.into()));
    map.insert("curseforge_api_key".to_string(), Value::String(settings.curseforge_api_key.unwrap_or_default()));
    map.insert("translate_provider".to_string(), Value::String(settings.translate_provider));
    map.insert("translate_api_base".to_string(), Value::String(settings.translate_api_base));
    map.insert("translate_api_key".to_string(), Value::String(settings.translate_api_key.unwrap_or_default()));
    map.insert("translate_api_model".to_string(), Value::String(settings.translate_api_model));
    map.insert("body_translate_auto".to_string(), Value::Bool(settings.body_translate_auto));
    map.insert("theme".to_string(), Value::String(settings.theme));
    map.insert("theme_color".to_string(), Value::String(settings.theme_color));
    map.insert("close_behavior".to_string(), Value::String(settings.close_behavior));
    map.insert("auto_launch".to_string(), Value::Bool(settings.auto_launch));
    map.insert("keep_open".to_string(), Value::Bool(settings.keep_open));
    map.insert("ms_client_id".to_string(), Value::String(settings.ms_client_id));
    map.insert("selected_account".to_string(), Value::String(settings.selected_account.unwrap_or_default()));
    map.insert("proxy_mode".to_string(), Value::String(settings.proxy_mode));
    map.insert("proxy".to_string(), Value::String(settings.proxy.unwrap_or_default()));
    map.insert("mirror".to_string(), Value::String(settings.mirror));
    map.insert("mirror_custom".to_string(), Value::String(settings.mirror_custom.unwrap_or_default()));
    map.insert("background_image".to_string(), Value::String(settings.background_image.unwrap_or_default()));
    map.insert("background_blur".to_string(), Value::Number(settings.background_blur.into()));
    map.insert("background_dim".to_string(), Value::Number(settings.background_dim.into()));
    map.insert("glass_blur".to_string(), Value::Number(settings.glass_blur.into()));
    map.insert("show_sidebar_collapse_btn".to_string(), Value::Bool(settings.show_sidebar_collapse_btn));
    map.insert("show_news".to_string(), Value::Bool(settings.show_news));
    map.insert("show_home_downloads".to_string(), Value::Bool(settings.show_home_downloads));
    map.insert("show_home_recent".to_string(), Value::Bool(settings.show_home_recent));
    map.insert("show_home_stats".to_string(), Value::Bool(settings.show_home_stats));
    map.insert("dismissed_update_version".to_string(), Value::String(settings.dismissed_update_version.unwrap_or_default()));
    map.insert("auto_update".to_string(), Value::Bool(settings.auto_update));
    map.insert("update_source".to_string(), Value::String(settings.update_source));
    map.insert("orientation".to_string(), Value::String(settings.orientation));

    let content = serde_json::to_string_pretty(&map)?;
    fs::write(&settings_path, content).await
        .with_context(|| format!("Failed to write settings file: {}", settings_path.display()))?;
    // ⚠️ 缓存失效必须挂到**每一个**写盘路径：设置有三条写入口
    // （`save_raw_settings` / `update_settings` / `update_settings_patch`），
    // 只挂其中一条的后果不是"稍微晚一点生效"，而是「文件写进去了、界面读回旧值」——
    // 用户看到的就是「设置里怎么调都调不动」。真机踩过（2026-10-11）。
    invalidate_settings_cache();
    crate::util::reset_http_client();

    Ok(())
}

/// 以 patch 模式更新设置：读取现有设置 → 合并 patch → 写回
pub async fn update_settings_patch(patch: Value) -> Result<()> {
    let data_dir = get_data_dir().await?;
    let settings_path = Path::new(&data_dir).join("settings.json");

    // 读取现有设置（JSON map）
    let mut map: HashMap<String, Value> = if settings_path.exists() {
        let content = fs::read_to_string(&settings_path).await
            .context("Failed to read settings file")?;
        serde_json::from_str(&content).unwrap_or_default()
    } else {
        HashMap::new()
    };

    // 合并 patch
    if let Some(obj) = patch.as_object() {
        for (k, v) in obj {
            map.insert(k.clone(), v.clone());
        }
    }

    let content = serde_json::to_string_pretty(&map)?;
    fs::write(&settings_path, content).await
        .with_context(|| format!("Failed to write settings file: {}", settings_path.display()))?;
    // ⚠️ 缓存失效必须挂到**每一个**写盘路径：设置有三条写入口
    // （`save_raw_settings` / `update_settings` / `update_settings_patch`），
    // 只挂其中一条的后果不是"稍微晚一点生效"，而是「文件写进去了、界面读回旧值」——
    // 用户看到的就是「设置里怎么调都调不动」。真机踩过（2026-10-11）。
    invalidate_settings_cache();
    crate::util::reset_http_client();

    Ok(())
}

/// Android 上运行进程对应的包名（`/proc/self/cmdline` 的首段，形如 `com.zhayi.qookix`）。
/// debug 变体带 `.debug` 后缀，因此不能把包名写死。
#[cfg(target_os = "android")]
fn android_package_name() -> Option<String> {
    let raw = std::fs::read("/proc/self/cmdline").ok()?;
    let first = raw.split(|b| *b == 0).next()?;
    let name = String::from_utf8_lossy(first).to_string();
    let name = name.split(':').next().unwrap_or("").to_string();
    let valid = !name.is_empty()
        && name.contains('.')
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_');
    if valid {
        Some(name)
    } else {
        None
    }
}

pub async fn get_data_dir() -> Result<String> {
    // Use platform-specific data directory
    #[cfg(target_os = "android")]
    {
        // 应用私有目录 /data/data/<package>/files。
        // 包名动态解析，保证 release / debug(带 .debug 后缀) 变体各写各的目录。
        let dir = match android_package_name() {
            Some(pkg) => format!("/data/data/{pkg}/files"),
            None => "/data/data/com.zhayi.qookix/files".to_string(),
        };
        if !Path::new(&dir).exists() {
            fs::create_dir_all(&dir).await.ok();
        }
        Ok(dir)
    }
    #[cfg(not(target_os = "android"))]
    {
        // On desktop, use the user's data directory
        let dirs = dirs::data_dir()
            .ok_or_else(|| anyhow::anyhow!("Failed to get data directory"))?;
        Ok(dirs.join("Qookix").to_string_lossy().to_string())
    }
}

/// 只取 `settings.json` 里的 `game_root`（None / 空串 = 用内部目录）。
pub async fn custom_game_root() -> Option<String> {
    let map = load_raw_settings().await;
    let value = map
        .get("game_root")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .trim()
        .to_string();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}

/// 写入 / 清除 `game_root`（`None` 或空串 = 用内部目录）。
pub async fn set_game_root(root: Option<&str>) -> Result<()> {
    let mut map = load_raw_settings().await;
    match root {
        Some(p) if !p.trim().is_empty() => {
            map.insert("game_root".to_string(), Value::String(p.trim().to_string()));
        }
        _ => {
            map.remove("game_root");
        }
    }
    save_raw_settings(&map).await
}

/// 异步版游戏数据根目录（规则同 [`game_root_sync`]）。
pub async fn game_root() -> Result<String> {
    match custom_game_root().await {
        Some(custom) => Ok(custom),
        None => get_data_dir().await,
    }
}

/// 异步版实例目录根 = [`game_root`] + `instances`。
///
/// **所有实例路径都必须走这里**（取代以前散在各文件的
/// `Path::new(&data_dir).join("instances")`）：用户把游戏目录改到外部后，
/// 漏改一处就会出现「实例分成两处、界面上看不见」。
pub async fn instances_root() -> Result<std::path::PathBuf> {
    Ok(resolve_instances_root(Path::new(&game_root().await?)))
}

/// 单个实例目录 = `instances_root()/<id>`。
/// ID 的合法性由调用方用 `fsutil::validate_id` 负责（防路径穿越）。
pub async fn instance_dir(instance_id: &str) -> Result<std::path::PathBuf> {
    Ok(instances_root().await?.join(instance_id))
}

