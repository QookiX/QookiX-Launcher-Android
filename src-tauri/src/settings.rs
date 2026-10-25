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
/// ⚠️ 2026-10-22 修：同 [resolve_servers_root] 的坑 —— 只要 `game/`
/// 目录**存在**（哪怕是空的），旧目录 `instances/` 里用户真正的实例
/// 就再也读不到了（界面显示「还没有实例」）。
/// 现在改成「选真正有实例的那个」。
pub fn resolve_instances_root(root: &Path) -> PathBuf {
    let new_dir = root.join(GAME_DIR_NAME);
    let legacy = root.join(GAME_DIR_NAME_LEGACY);
    if !legacy.exists() {
        return new_dir;
    }
    if !new_dir.exists() {
        return legacy;
    }
    if count_instances_in(&new_dir) == 0 && count_instances_in(&legacy) > 0 {
        crate::util::log_line(
            "[settings] game/ 是空的但 instances/ 里有实例 —— 改用旧目录（数据在旧目录）",
        );
        return legacy;
    }
    new_dir
}

/// 数一个目录里有几个实例（子目录里带 `instance.json` 或 `game` 标记的才算）。
fn count_instances_in(dir: &Path) -> usize {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return 0;
    };
    entries
        .flatten()
        .filter(|e| {
            let p = e.path();
            p.is_dir() && (p.join("instance.json").is_file() || p.join("version.json").is_file())
        })
        .count()
}

/// 解析「服务器目录根」：优先新名 `server/`，不存在则回退旧名 `servers/`。
///
/// ⚠️ 2026-10-22 修（真机复现：服务器列表整个空了）。
///
/// 原来的判断是「新目录**存在**就用新目录」—— 但只要有任何代码在
/// `files/server/` 下建过一个空目录（哪怕只是 `create_dir_all`），
/// 旧目录 `files/servers/` 里用户真正的服务器就再也读不到了：
/// 列表接口遍历新目录 → 一个都没有 → 界面显示「还没有服务器」，
/// 而用户的数据好好地躺在旧目录里。
///
/// 现在改成「**选真正有服务器的那个**」：
///   - 两个目录都有服务器 → 用新的（保持「新名优先」的既定语义）
///   - 只有旧的里面有 → 用旧的（数据可见性优先于命名整洁）
///   - 都没有 → 用新的（后续创建时落在新目录）
pub fn resolve_servers_root(root: &Path) -> PathBuf {
    let new_dir = root.join(SERVER_DIR_NAME);
    let legacy = root.join(SERVER_DIR_NAME_LEGACY);
    if !legacy.exists() {
        println!("[settings] 服务器目录 -> 新目录 {}（旧的不存在）", new_dir.display());
        return new_dir;
    }
    if !new_dir.exists() {
        println!("[settings] 服务器目录 -> 旧目录 {}", legacy.display());
        return legacy;
    }
    // 两个都存在：看谁真的有服务器（子目录里带 server.json 的才算）
    let n_new = count_servers_in(&new_dir);
    let n_old = count_servers_in(&legacy);
    println!(
        "[settings] 服务器目录：server/ 有 {} 个，servers/ 有 {} 个",
        n_new, n_old
    );
    if n_new == 0 && n_old > 0 {
        crate::util::log_line(
            "[settings] server/ 是空的但 servers/ 里有服务器 —— 改用旧目录（数据在旧目录）",
        );
        println!("[settings] server/ 是空的但 servers/ 里有服务器 -> 用旧目录");
        return legacy;
    }
    new_dir
}

/// 兜底：在**数据根**下找「里面装着服务器的目录」。
///
/// 极端情况下服务器目录会被改名/搬家（老版本用过别的名字、
/// 或迁移时被搬到了游戏目录下面）。这时硬编码 `server/` / `servers/`
/// 就会让用户的服务器凭空消失 —— 界面显示「还没有服务器」，
/// 数据却好好地躺在某个我们没预料到的目录里。
///
/// 判据：该目录的**直接子目录**里至少有一个带 `server.json`。
pub fn find_servers_root(data_root: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(data_root).ok()?;
    for e in entries.flatten() {
        let p = e.path();
        if !p.is_dir() {
            continue;
        }
        if count_servers_in(&p) > 0 {
            return Some(p);
        }
    }
    None
}

/// 数一个目录里有几个「带 server.json 的子目录」（= 几个服务器）。
fn count_servers_in(dir: &Path) -> usize {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return 0;
    };
    entries
        .flatten()
        .filter(|e| e.path().join("server.json").is_file())
        .count()
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

/// ⚠️ 给**合并写手**用的严格读取（2026-10-25 修「设置被重置」）。
///
/// 长期以来 `update_settings_patch` / `set_game_root` / `select_account` 的模式是
/// 「读旧 map → 合并 → 写回」，而读用的是 [`load_raw_settings`] —— **文件损坏时
/// 它静默返回空表**，于是写回的文件只剩本次 patch 的那几个键：
/// 用户看到的正是「某些设置每次开启就被重置」（丢 `game_root` 时实例全部消失，
/// 最严重）。损坏来源：fs::write 不是原子的（进程被杀/并发写交错都会留下半截 JSON）。
///
/// 这里**绝不吞解析错误**：损坏时返回 Err，调用方把错误抛给用户，
/// 损坏文件原样保留（可手工修复），绝不能被一个空合并覆盖掉。
pub(crate) async fn load_raw_settings_strict() -> Result<HashMap<String, Value>> {
    let data_dir = get_data_dir().await?;
    let settings_path = Path::new(&data_dir).join("settings.json");
    if !settings_path.exists() {
        return Ok(HashMap::new());
    }
    let content = fs::read_to_string(&settings_path).await
        .with_context(|| format!("设置文件读取失败：{}", settings_path.display()))?;
    serde_json::from_str(&content)
        .with_context(|| format!("设置文件 JSON 解析失败（{}），拒绝覆盖写入", settings_path.display()))
}

/// 原子写盘：先写**唯一命名的** tmp 文件再 rename。
///
/// ⚠️ 2026-10-25：原来所有写路径都直接 `fs::write`（open+O_TRUNC+write）——
/// 进程在写入中途被杀（安卓后台杀进程很常见）就留下半截/0 字节 JSON；
/// 这些残废文件一出现，下一轮「读空表→合并→写回」就把用户全部设置抹掉（真机实锤）。
/// rename 在同一文件系统内是原子的，写坏最多坏掉 tmp，正主永远完整。
///
/// tmp 名带 pid+序号：并发写若共用一个固定 tmp 名，A/B 交错写同一个 tmp，
/// rename 出去的仍可能是撕裂内容 —— 唯一名让每次写互不干扰。
async fn write_settings_file(settings_path: &Path, content: &str) -> Result<()> {
    use std::sync::atomic::{AtomicU64, Ordering};
    static TMP_SEQ: AtomicU64 = AtomicU64::new(0);
    let seq = TMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let file_name = settings_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "settings.json".to_string());
    let tmp_path = settings_path.with_file_name(format!(
        "{file_name}.tmp.{}-{seq}",
        std::process::id()
    ));
    {
        let mut f = fs::File::create(&tmp_path).await
            .with_context(|| format!("无法创建临时设置文件：{}", tmp_path.display()))?;
        use tokio::io::AsyncWriteExt;
        f.write_all(content.as_bytes()).await
            .with_context(|| format!("写入临时设置文件失败：{}", tmp_path.display()))?;
        f.sync_all().await.ok();
    }
    fs::rename(&tmp_path, settings_path).await
        .with_context(|| format!("设置文件落盘失败：{} → {}", tmp_path.display(), settings_path.display()))?;
    Ok(())
}

/// ⚠️ 所有「读-改-写设置」周期的全局串行锁（2026-10-25）。
///
/// 没有它，两个合并写手（改设置 patch、选账号、改游戏目录）并发时各自
/// 读到同一份旧 map、各自写回 —— **后写的把先写的那次改动整个覆盖掉**，
/// 表现还是「某个设置莫名被重置」。锁必须罩住**整个**读改写周期，
/// 只锁写盘是不够的（读发生在锁外照样丢更新）。
static SETTINGS_WRITE_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

/// 合并写手的**统一入口**：持锁 → 严格读 → 调用方改 map → 原子写 → 失效缓存。
///
/// 新代码要改设置请走这里；直接 `load_raw_settings + save_raw_settings`
/// 的组合自带丢更新竞态，别再用了。
pub(crate) async fn mutate_raw_settings<F>(f: F) -> Result<()>
where
    F: FnOnce(&mut HashMap<String, Value>),
{
    let _guard = SETTINGS_WRITE_LOCK.lock().await;
    let mut map = load_raw_settings_strict().await?;
    f(&mut map);
    let data_dir = get_data_dir().await?;
    let settings_path = Path::new(&data_dir).join("settings.json");
    let content = serde_json::to_string_pretty(&map)?;
    write_settings_file(&settings_path, &content).await?;
    invalidate_settings_cache();
    crate::util::reset_http_client();
    Ok(())
}

/// 写入设置文件原始 JSON。
///
/// 调用方自带 map（先读过再改的旧模式）——同样要持全局写锁，
/// 否则与 `mutate_raw_settings` 并发时又会回到丢更新的老路。
pub async fn save_raw_settings(map: &HashMap<String, Value>) -> Result<()> {
    let _guard = SETTINGS_WRITE_LOCK.lock().await;
    let data_dir = get_data_dir().await?;
    let settings_path = Path::new(&data_dir).join("settings.json");
    let content = serde_json::to_string_pretty(map)?;
    write_settings_file(&settings_path, &content).await?;
    // 写盘必须是**唯一**的失效入口：缓存与文件不一致的话，用户改完设置不生效才是最难查的 bug
    invalidate_settings_cache();
    // 代理/镜像等设置改变后让缓存的 HTTP 客户端立即重建
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
    // ⚠️ 2026-10-25：从**现有文件**出发，而不是新建空表。
    // 这份「全量写」的键名单当年定下后就再没同步过（ui_scale / game_root 都不在），
    // 直接覆盖会把不在名单里的键抹掉 —— game_root 丢了实例就全部消失。
    // 虽然目前安卓链路走的是 update_settings_patch，但这是 pub 接口，必须防患。
    // 全程走 mutate_raw_settings：严格读 + 持锁（防丢更新）+ 原子写。
    mutate_raw_settings(move |map| {
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
    })
    .await
}

/// 以 patch 模式更新设置：读取现有设置 → 合并 patch → 写回
pub async fn update_settings_patch(patch: Value) -> Result<()> {
    // ⚠️ 必须走 mutate_raw_settings：严格读（坏文件拒绝写）+ 全程持锁（防丢更新）。
    mutate_raw_settings(move |map| {
        if let Some(obj) = patch.as_object() {
            for (k, v) in obj {
                map.insert(k.clone(), v.clone());
            }
        }
    })
    .await
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
    // ⚠️ 走 mutate_raw_settings：严格读（game_root 是最不能丢的键，
    // 丢了实例全「消失」）+ 持锁（防丢更新）+ 原子写。
    let root = root.map(|p| p.trim().to_string());
    mutate_raw_settings(move |map| {
        match root {
            Some(p) if !p.is_empty() => {
                map.insert("game_root".to_string(), Value::String(p));
            }
            _ => {
                map.remove("game_root");
            }
        }
    })
    .await
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

