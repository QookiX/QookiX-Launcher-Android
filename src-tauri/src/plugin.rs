//! 插件系统（v1：组件类插件；v2 渲染器 / v3 驱动沿用同一套机制）。
//!
//! 为什么要有它：
//! 1. **瘦身**：为 MC 26.3 塞进包里的组件（LWJGL 3.4.1 + SDL3 + spirv-cross）约 65MB，
//!    只玩 1.18/1.21 的用户纯属负重 —— 改成「按需下载」后基础包能瘦回去；
//! 2. **可更新**：26.3 那类新版本对 SDL3 的要求还在变（我们当前就是卡在 SDL 的多窗口限制上），
//!    组件做成插件后，换一份 SDL3 只需用户更新插件，不必等启动器重新发版；
//! 3. **可替换**：同一类东西（渲染器 / 驱动）将来会有多个实现，插件化后能按机型和版本挑。
//!
//! 目录布局（`<data>/plugins/` 下）：
//! ```text
//! plugins/
//!   config.json                    插件源地址等配置
//!   manifest.json                  最近一次拉到的远程清单缓存
//!   <id>/
//!     state.json                   已安装版本 / 是否启用
//!     <version>/jars/*.jar         → classpath
//!     <version>/natives/*.so       → org.lwjgl.librarypath
//!     <version>/libs/*.so          → java.library.path（SDL3 / spirv-cross 这类）
//! ```
//!
//! 三件事都遵守「**插件优先、随包兜底**」：插件没装或下载失败时，启动流程
//! 仍然走随包内置的那套（见 `launch.rs`），不会因为插件缺失导致整个启动器不可用。

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

/// 默认插件源：我们仓库的 GitHub Release 资产。
/// 用户可以在「设置 → 插件」里改（空字符串 = 只用本地安装，不联网）。
pub const DEFAULT_MANIFEST_URL: &str =
    "https://github.com/QookiX/QookiX-Launcher-Android/releases/download/plugins/manifest.json";

/// 清单缓存有效期：6 小时。既不至于每次进设置都打网络，也不至于长期看到过期信息。
const MANIFEST_TTL_SECS: i64 = 6 * 3600;

// ═══════════════════════════════════════════════════════════════════════════
// 清单格式
// ═══════════════════════════════════════════════════════════════════════════

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Manifest {
    #[serde(default)]
    pub schema: u32,
    #[serde(default)]
    pub updated_at: Option<String>,
    #[serde(default)]
    pub plugins: Vec<PluginSpec>,
}

/// 清单里的单个插件描述。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PluginSpec {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub summary: String,
    pub version: String,
    /// `components`（v1）/ `renderer`（v2）/ `driver`（v3）。
    #[serde(default = "default_kind")]
    pub kind: String,
    /// ABI → 下载信息。这样同一个插件能按机型给不同的包（体积差一倍以上）。
    #[serde(default)]
    pub files: HashMap<String, ArchiveSpec>,
    /// 包内各子目录的作用；留空则用约定名（jars / natives / libs）。
    #[serde(default)]
    pub layout: Layout,
    /// **渲染器插件专用**：提供哪些渲染后端（v2）。
    ///
    /// 取值是设置里的渲染器键：`opengles2`（GL4ES）/ `mobileglues` / `vulkan_zink`。
    /// 一个包可以给多个（比如把 GL4ES 与 MobileGlues 打在一起），
    /// 启动时按用户选的那个去匹配；匹配上才优先用插件里的库。
    #[serde(default)]
    pub renderers: Vec<String>,
}

fn default_kind() -> String {
    "components".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Layout {
    #[serde(default)]
    pub classpath: Option<String>,
    #[serde(default)]
    pub lightgl: Option<String>,
    #[serde(default)]
    pub librarypath: Option<String>,
}

/// 本地安装时 zip 里自带的 `plugin.json`（让包自描述，不用靠文件名猜）。
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PluginDescriptor {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub version: String,
    #[serde(default = "default_kind")]
    pub kind: String,
    #[serde(default)]
    pub layout: Layout,
    #[serde(default)]
    pub renderers: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ArchiveSpec {
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(default)]
    pub size: Option<u64>,
}

// ═══════════════════════════════════════════════════════════════════════════
// 落盘状态
// ═══════════════════════════════════════════════════════════════════════════

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct PluginState {
    version: String,
    #[serde(default)]
    enabled: bool,
    #[serde(default)]
    abi: String,
    #[serde(default)]
    installed_at: i64,
    /// `remote`（从清单下载）或 `local`（用户选的 zip）。
    #[serde(default)]
    source: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct PluginConfig {
    #[serde(default)]
    manifest_url: Option<String>,
    /// 用户在首启窗口点了「稍后」，别再每次启动都弹
    #[serde(default)]
    setup_dismissed: bool,
}

/// 给前端用的插件视图（清单信息 + 本机安装状态合并后）。
#[derive(Debug, Clone, Serialize)]
pub struct PluginInfo {
    pub id: String,
    pub name: String,
    pub summary: String,
    pub kind: String,
    pub version: Option<String>,
    pub installed_version: Option<String>,
    pub enabled: bool,
    pub update_available: bool,
    pub abi_supported: bool,
    pub device_abi: String,
    pub size: Option<u64>,
    pub installed_size: u64,
    pub source: Option<String>,
    pub error: Option<String>,
    /// 渲染器插件提供的渲染后端键（设置里选的渲染器要能在这里找到才生效）。
    pub renderers: Vec<String>,
    /// 属于「首启要装」的类别（渲染器 / 驱动 / 组件），设置页据此打「推荐」角标
    pub recommended: bool,
}

// ═══════════════════════════════════════════════════════════════════════════
// 路径与配置
// ═══════════════════════════════════════════════════════════════════════════

pub fn plugins_root(data_dir: &str) -> PathBuf {
    Path::new(data_dir).join("plugins")
}

fn manifest_cache_path(data_dir: &str) -> PathBuf {
    plugins_root(data_dir).join("manifest.json")
}

fn config_path(data_dir: &str) -> PathBuf {
    plugins_root(data_dir).join("config.json")
}

fn state_path(data_dir: &str, id: &str) -> PathBuf {
    plugins_root(data_dir).join(id).join("state.json")
}

/// 插件源地址（默认见 `DEFAULT_MANIFEST_URL`；空字符串代表不联网）。
pub fn manifest_url(data_dir: &str) -> String {
    let cfg = read_json::<PluginConfig>(&config_path(data_dir));
    match cfg.and_then(|c| c.manifest_url) {
        Some(u) => u,
        None => DEFAULT_MANIFEST_URL.to_string(),
    }
}

pub fn set_manifest_url(data_dir: &str, url: &str) -> Result<(), String> {
    // 读-改-写：整体覆盖会把 setup_dismissed 这类同文件里的标记一起抹掉
    let mut cfg = read_json::<PluginConfig>(&config_path(data_dir)).unwrap_or_default();
    cfg.manifest_url = Some(url.trim().to_string());
    write_json(&config_path(data_dir), &cfg)
}

/// 记下「首启准备」是否已被用户跳过。
pub fn set_setup_dismissed(data_dir: &str, dismissed: bool) -> Result<(), String> {
    let mut cfg = read_json::<PluginConfig>(&config_path(data_dir)).unwrap_or_default();
    cfg.setup_dismissed = dismissed;
    write_json(&config_path(data_dir), &cfg)
}

fn read_json<T: for<'de> Deserialize<'de>>(path: &Path) -> Option<T> {
    let text = std::fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败：{e}"))?;
    }
    let text = serde_json::to_string_pretty(value).map_err(|e| e.to_string())?;
    std::fs::write(path, text).map_err(|e| format!("写入失败：{e}"))
}

pub fn device_abi() -> String {
    crate::java::get_device_arch().to_string()
}

// ═══════════════════════════════════════════════════════════════════════════
// 首启准备：自动补齐渲染器
// ═══════════════════════════════════════════════════════════════════════════

/// 首启要装的插件类别：渲染器 / 驱动 / 组件。
///
/// 前两类就是「用哪个渲染器」本身；**组件**（LWJGL 3.4 + SDL3 + spirv-cross）也得有 ——
/// 它 47MB 是最重的一块，但 MC 26.x 走 SDL3 窗口层、zink 走 spirv-cross，而这两样都是
/// 构建期产物、被 `.gitignore` 排除，release 包里并没有：不装的话那些版本照样起不来，
/// 随包只剩 GL4ES 这一条老路。
const RECOMMENDED_KINDS: &[&str] = &["renderer", "driver", "components"];

/// 首启准备状态：还缺哪些、一共多大、用户跳过没有。
///
/// 字段名与 `PluginInfo` 一样是 snake_case（前端 `types.ts::PluginSetupStatus`
/// 也按 snake_case 读）。这里以前挂着 `rename_all = "camelCase"`，于是 `total_size`
/// 发出去变成 `totalSize` → 首启窗口的「需要下载 X MB」恒为 0。
#[derive(Debug, Clone, Serialize)]
pub struct SetupStatus {
    /// 还有缺的（前端据此决定弹不弹首启窗口）
    pub needed: bool,
    /// 用户点过「稍后」，别每次启动都弹
    pub dismissed: bool,
    pub missing: Vec<PluginInfo>,
    /// 缺的那些加起来要下载多少字节
    pub total_size: u64,
    /// 清单拿不到时的说明（首启离线很常见，让前端能「重试 / 稍后」而不是卡在错误页）
    pub error: Option<String>,
}

/// 一次「补齐首启插件」的结果。
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SetupResult {
    pub plugins: Vec<PluginInfo>,
    pub installed: usize,
    /// 形如「MobileGlues：下载失败：…」
    pub failed: Vec<String>,
}

/// 首启还缺哪些插件。
pub async fn setup_status(data_dir: &str) -> SetupStatus {
    let dismissed = read_json::<PluginConfig>(&config_path(data_dir))
        .map(|c| c.setup_dismissed)
        .unwrap_or(false);

    if let Err(e) = fetch_manifest(data_dir, false).await {
        return SetupStatus {
            needed: false,
            dismissed,
            missing: Vec::new(),
            total_size: 0,
            error: Some(e),
        };
    }

    let missing: Vec<PluginInfo> = list(data_dir)
        .into_iter()
        .filter(|p| p.recommended && p.abi_supported && p.installed_version.is_none())
        .collect();
    let total_size = missing.iter().filter_map(|p| p.size).sum();
    SetupStatus {
        needed: !missing.is_empty(),
        dismissed,
        missing,
        total_size,
        error: None,
    }
}

/// 逐个装首启要装的插件（每个都会通过 `plugin://progress` 报进度）。
///
/// 单个失败不中断：网络抖一下很常见，让用户看到「装好了几个、哪个没成」比整体报错有用；
/// 没装成的下次启动还会出现在待装清单里，也能去「设置 → 插件」手动补。
pub async fn install_recommended(data_dir: &str) -> Result<SetupResult, String> {
    let status = setup_status(data_dir).await;
    if let Some(err) = status.error {
        return Err(err);
    }

    let mut failed: Vec<String> = Vec::new();
    let mut installed = 0usize;
    for item in &status.missing {
        match install(data_dir, &item.id).await {
            Ok(()) => installed += 1,
            Err(e) => {
                crate::util::log_line(&format!("首启安装插件「{}」失败：{e}", item.id));
                failed.push(format!("{}：{e}", item.name));
            }
        }
    }
    crate::util::log_line(&format!(
        "首启准备：装了 {installed} 个插件，{} 个失败",
        failed.len()
    ));
    Ok(SetupResult {
        plugins: list(data_dir),
        installed,
        failed,
    })
}

// ═══════════════════════════════════════════════════════════════════════════
// 清单获取
// ═══════════════════════════════════════════════════════════════════════════

/// 读缓存清单（不联网），用于列表展示。
pub fn cached_manifest(data_dir: &str) -> Option<Manifest> {
    read_json::<Manifest>(&manifest_cache_path(data_dir))
}

/// 清单缓存是否还在有效期内（用于决定要不要联网刷新）。
pub fn manifest_is_fresh(data_dir: &str) -> bool {
    let path = manifest_cache_path(data_dir);
    let Ok(meta) = std::fs::metadata(&path) else {
        return false;
    };
    let Ok(modified) = meta.modified() else {
        return false;
    };
    let Ok(age) = std::time::SystemTime::now().duration_since(modified) else {
        return false;
    };
    (age.as_secs() as i64) < MANIFEST_TTL_SECS
}

/// 拉取远程清单（`force=false` 时命中新鲜缓存就直接返回，不打网络）。
pub async fn fetch_manifest(data_dir: &str, force: bool) -> Result<Manifest, String> {
    if !force {
        if manifest_is_fresh(data_dir) {
            if let Some(m) = cached_manifest(data_dir) {
                return Ok(m);
            }
        }
    }

    let url = manifest_url(data_dir);
    if url.trim().is_empty() {
        return cached_manifest(data_dir).ok_or_else(|| "未配置插件源地址".to_string());
    }

    let client = crate::util::http_client().await;
    let resp = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("请求插件清单失败：{e}"))?;
    if !resp.status().is_success() {
        // 网络失败但本地有旧清单：降级用旧的，别把设置页变成错误页
        if let Some(m) = cached_manifest(data_dir) {
            crate::util::log_line(&format!("插件清单刷新失败（HTTP {}），沿用缓存", resp.status()));
            return Ok(m);
        }
        return Err(format!("插件清单返回 HTTP {}", resp.status()));
    }
    let text = resp
        .text()
        .await
        .map_err(|e| format!("读取插件清单失败：{e}"))?;
    let manifest: Manifest =
        serde_json::from_str(&text).map_err(|e| format!("插件清单格式不正确：{e}"))?;

    let _ = std::fs::create_dir_all(plugins_root(data_dir));
    let _ = std::fs::write(manifest_cache_path(data_dir), &text);
    crate::util::log_line(&format!("插件清单已更新：{} 个条目", manifest.plugins.len()));
    Ok(manifest)
}

// ═══════════════════════════════════════════════════════════════════════════
// 列表（清单 × 本机状态）
// ═══════════════════════════════════════════════════════════════════════════

fn dir_size(path: &Path) -> u64 {
    let mut total = 0u64;
    let Ok(entries) = std::fs::read_dir(path) else {
        return 0;
    };
    for entry in entries.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            total += dir_size(&entry.path());
        } else {
            total += meta.len();
        }
    }
    total
}

fn installed_state(data_dir: &str, id: &str) -> Option<PluginState> {
    read_json::<PluginState>(&state_path(data_dir, id))
}

/// 合并「远程清单」与「本机已安装」，得到设置页要展示的列表。
///
/// 已安装但不在清单里的插件（比如手动装的 zip）也会列出来 —— 否则用户装完
/// 反而在界面上找不到它，只能靠删目录卸载。
pub fn list(data_dir: &str) -> Vec<PluginInfo> {
    let abi = device_abi();
    let manifest = cached_manifest(data_dir);
    let mut out: Vec<PluginInfo> = Vec::new();

    if let Some(manifest) = &manifest {
        for spec in &manifest.plugins {
            let state = installed_state(data_dir, &spec.id);
            let archive = spec.files.get(&abi);
            let installed_version = state.as_ref().map(|s| s.version.clone());
            let update_available = match (&installed_version, &spec.version) {
                (Some(cur), remote) => cur != remote,
                (None, _) => false,
            };
            out.push(PluginInfo {
                id: spec.id.clone(),
                name: spec.name.clone(),
                summary: spec.summary.clone(),
                kind: spec.kind.clone(),
                version: Some(spec.version.clone()),
                installed_version: installed_version.clone(),
                enabled: state.as_ref().is_some_and(|s| s.enabled),
                update_available,
                abi_supported: archive.is_some(),
                device_abi: abi.clone(),
                size: archive.and_then(|a| a.size),
                installed_size: installed_version
                    .as_ref()
                    .map(|v| dir_size(&plugins_root(data_dir).join(&spec.id).join(v)))
                    .unwrap_or(0),
                source: state.as_ref().map(|s| s.source.clone()),
                error: None,
                renderers: spec.renderers.clone(),
                recommended: RECOMMENDED_KINDS.contains(&spec.kind.as_str()),
            });
        }
    }

    // 已安装但清单里没有的（本地 zip 装的 / 清单里被下架的）
    if let Ok(entries) = std::fs::read_dir(plugins_root(data_dir)) {
        for entry in entries.flatten() {
            let id = entry.file_name().to_string_lossy().to_string();
            if !entry.path().is_dir() || id.starts_with('.') {
                continue;
            }
            if out.iter().any(|p| p.id == id) {
                continue;
            }
            let Some(state) = installed_state(data_dir, &id) else {
                continue;
            };
            out.push(PluginInfo {
                id: id.clone(),
                name: id.clone(),
                summary: "本地安装的插件".to_string(),
                kind: "components".to_string(),
                version: None,
                installed_version: Some(state.version.clone()),
                enabled: state.enabled,
                update_available: false,
                abi_supported: true,
                device_abi: abi.clone(),
                size: None,
                installed_size: dir_size(&entry.path().join(&state.version)),
                source: Some(state.source.clone()),
                error: None,
                renderers: Vec::new(),
                // 清单里没有的（本地 zip 装的）谈不上「首启要装」
                recommended: false,
            });
        }
    }

    out.sort_by(|a, b| a.id.cmp(&b.id));
    out
}

// ═══════════════════════════════════════════════════════════════════════════
// 安装 / 卸载 / 启停
// ═══════════════════════════════════════════════════════════════════════════

/// 从清单安装或更新插件（按本机 ABI 选包）。
pub async fn install(data_dir: &str, id: &str) -> Result<(), String> {
    let manifest = fetch_manifest(data_dir, false).await?;
    let spec = manifest
        .plugins
        .iter()
        .find(|p| p.id == id)
        .ok_or_else(|| format!("插件源里没有 {id}"))?
        .clone();

    let abi = device_abi();
    let archive = spec
        .files
        .get(&abi)
        .ok_or_else(|| format!("插件 {} 没有适配本机架构（{abi}）的包", spec.name))?;

    let root = plugins_root(data_dir);
    let tmp_dir = root.join(".tmp");
    std::fs::create_dir_all(&tmp_dir).map_err(|e| format!("创建临时目录失败：{e}"))?;
    let zip_path = tmp_dir.join(format!("{id}-{}.zip", spec.version));

    // 1) 下载
    emit(id, "download", 0, archive.size.unwrap_or(0), "开始下载");
    download_with_progress(&archive.url, &zip_path, id, archive.size.unwrap_or(0)).await?;

    // 2) 校验（清单里给了 sha1 才校验；本地无网络调试时允许留空）
    if let Some(expected) = &archive.sha1 {
        emit(id, "verify", 0, 0, "校验文件完整性");
        let actual = sha1_file(&zip_path).await?;
        if !actual.eq_ignore_ascii_case(expected) {
            let _ = std::fs::remove_file(&zip_path);
            return Err(format!(
                "文件校验失败（期望 {expected}，实际 {actual}），已删除下载内容"
            ));
        }
    }

    // 3) 解压到 <id>/<version>.tmp，成功后改名 —— 中途失败不会留下「半成品」版本目录
    emit(id, "extract", 0, 0, "解压组件");
    let final_dir = root.join(id).join(&spec.version);
    let staging = root.join(id).join(format!("{}.tmp", spec.version));
    if staging.exists() {
        let _ = std::fs::remove_dir_all(&staging);
    }
    std::fs::create_dir_all(&staging).map_err(|e| format!("创建插件目录失败：{e}"))?;
    let staging_for_zip = staging.clone();
    let zip_for_extract = zip_path.clone();
    let count = tokio::task::spawn_blocking(move || {
        // 允许的扩展名：jar / so（外加格式说明用的 json，便于排查）
        crate::util::extract_zip(&zip_for_extract, &staging_for_zip, &["jar", "so", "json"])
    })
    .await
    .map_err(|e| format!("解压任务失败：{e}"))?
    .map_err(|e| format!("解压失败：{e}"))?;
    if count == 0 {
        let _ = std::fs::remove_dir_all(&staging);
        return Err("压缩包是空的（或格式不对）".to_string());
    }
    if final_dir.exists() {
        let _ = std::fs::remove_dir_all(&final_dir);
    }
    std::fs::rename(&staging, &final_dir).map_err(|e| format!("落盘失败：{e}"))?;
    let _ = std::fs::remove_file(&zip_path);

    // 4) 写状态（启用），清掉旧版本目录
    write_state(
        data_dir,
        id,
        PluginState {
            version: spec.version.clone(),
            enabled: true,
            abi,
            installed_at: now_secs(),
            source: "remote".to_string(),
        },
    )?;
    prune_old_versions(data_dir, id, &spec.version);

    emit(id, "done", 0, 0, "安装完成");
    crate::util::log_line(&format!("插件 {} 已安装（{}）", id, spec.version));
    Ok(())
}

/// 从本地 zip 安装（离线可用：测试、内网分发、用户手动拿包）。
///
/// 要求包里带 `plugin.json`（自描述），缺了就直接报错 —— 靠文件名猜 id/版本会埋坑。
pub async fn install_from_zip(data_dir: &str, zip: &str) -> Result<String, String> {
    let zip_path = PathBuf::from(zip);
    if !zip_path.exists() {
        return Err(format!("找不到文件：{zip}"));
    }

    let descriptor = read_descriptor(&zip_path).await?;
    let abi = device_abi();
    let root = plugins_root(data_dir);
    let staging = root.join(&descriptor.id).join(format!("{}.tmp", descriptor.version));
    if staging.exists() {
        let _ = std::fs::remove_dir_all(&staging);
    }
    std::fs::create_dir_all(&staging).map_err(|e| format!("创建插件目录失败：{e}"))?;

    emit(&descriptor.id, "extract", 0, 0, "解压本地插件包");
    let staging_for_zip = staging.clone();
    let zip_for_extract = zip_path.clone();
    let count = tokio::task::spawn_blocking(move || {
        crate::util::extract_zip(&zip_for_extract, &staging_for_zip, &["jar", "so", "json"])
    })
    .await
    .map_err(|e| format!("解压任务失败：{e}"))?
    .map_err(|e| format!("解压失败：{e}"))?;
    if count == 0 {
        let _ = std::fs::remove_dir_all(&staging);
        return Err("压缩包是空的（或格式不对）".to_string());
    }

    let final_dir = root.join(&descriptor.id).join(&descriptor.version);
    if final_dir.exists() {
        let _ = std::fs::remove_dir_all(&final_dir);
    }
    std::fs::rename(&staging, &final_dir).map_err(|e| format!("落盘失败：{e}"))?;

    write_state(
        data_dir,
        &descriptor.id,
        PluginState {
            version: descriptor.version.clone(),
            enabled: true,
            abi,
            installed_at: now_secs(),
            source: "local".to_string(),
        },
    )?;
    prune_old_versions(data_dir, &descriptor.id, &descriptor.version);

    emit(&descriptor.id, "done", 0, 0, "本地插件已安装");
    crate::util::log_line(&format!(
        "本地插件 {} 已安装（{}）",
        descriptor.id, descriptor.version
    ));
    Ok(descriptor.id)
}

pub fn uninstall(data_dir: &str, id: &str) -> Result<(), String> {
    // 只删插件自身目录；`plugins/` 下的 config/manifest 是共用的，不能跟着删
    if id.is_empty() || id.contains('/') || id.contains('\\') || id.starts_with('.') {
        return Err("插件 id 不合法".to_string());
    }
    let dir = plugins_root(data_dir).join(id);
    if dir.exists() {
        std::fs::remove_dir_all(&dir).map_err(|e| format!("删除插件失败：{e}"))?;
    }
    crate::util::log_line(&format!("插件 {id} 已卸载"));
    Ok(())
}

pub fn set_enabled(data_dir: &str, id: &str, enabled: bool) -> Result<(), String> {
    let path = state_path(data_dir, id);
    let mut state = read_json::<PluginState>(&path).ok_or_else(|| "插件未安装".to_string())?;
    state.enabled = enabled;
    write_json(&path, &state)
}

fn write_state(data_dir: &str, id: &str, state: PluginState) -> Result<(), String> {
    write_json(&state_path(data_dir, id), &state)
}

/// 只保留当前版本目录，删掉历史版本（更新后清理，避免磁盘越占越多）。
fn prune_old_versions(data_dir: &str, id: &str, keep: &str) {
    let dir = plugins_root(data_dir).join(id);
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if entry.path().is_dir() && name != keep && name != ".tmp" {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
}

fn now_secs() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// 读 zip 里的 `plugin.json`（本地安装用；也接受根目录下一层的同名文件）。
async fn read_descriptor(zip_path: &Path) -> Result<PluginDescriptor, String> {
    let path = zip_path.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<PluginDescriptor, String> {
        let file = std::fs::File::open(&path).map_err(|e| format!("打开压缩包失败：{e}"))?;
        let mut archive =
            zip::ZipArchive::new(file).map_err(|e| format!("解析压缩包失败：{e}"))?;
        for i in 0..archive.len() {
            let mut entry = archive
                .by_index(i)
                .map_err(|e| format!("读取条目失败：{e}"))?;
            let name = entry.name().to_string();
            if name == "plugin.json" || name.ends_with("/plugin.json") {
                let mut text = String::new();
                std::io::Read::read_to_string(&mut entry, &mut text)
                    .map_err(|e| format!("读取 plugin.json 失败：{e}"))?;
                let mut d: PluginDescriptor = serde_json::from_str(&text)
                    .map_err(|e| format!("plugin.json 格式不正确：{e}"))?;
                if d.id.trim().is_empty() {
                    return Err("plugin.json 缺少 id".to_string());
                }
                if d.version.trim().is_empty() {
                    d.version = "1.0.0".to_string();
                }
                return Ok(d);
            }
        }
        Err("这个压缩包里没有 plugin.json —— 不是合法的插件包".to_string())
    })
    .await
    .map_err(|e| format!("读取任务失败：{e}"))?
}

// ═══════════════════════════════════════════════════════════════════════════
// 下载与校验
// ═══════════════════════════════════════════════════════════════════════════

/// 带进度的下载：先写 `.part` 再改名，配合 sha1 校验做到「要么完整，要么没有」。
///
/// 没有复用 `download::download_file` 的原因：那条路径会套用用户的 Minecraft 镜像设置
/// （对我们的插件源是错的），而且进度只存在全局表里、前端拿不到 taskId 没法订阅。
async fn download_with_progress(
    url: &str,
    dest: &Path,
    id: &str,
    expected_size: u64,
) -> Result<(), String> {
    use futures::StreamExt;

    let client = crate::util::http_client().await;
    let resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("下载失败：{e}"))?;
    if !resp.status().is_success() {
        return Err(format!("下载失败：HTTP {}", resp.status()));
    }
    let total = resp.content_length().unwrap_or(expected_size);

    if let Some(parent) = dest.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败：{e}"))?;
    }
    let part = dest.with_extension("part");
    let mut file = std::fs::File::create(&part).map_err(|e| format!("创建文件失败：{e}"))?;
    let mut hasher = Sha1::new();
    let mut done = 0u64;
    let mut last_emit = std::time::Instant::now();

    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("下载中断：{e}"))?;
        std::io::Write::write_all(&mut file, &chunk).map_err(|e| format!("写入失败：{e}"))?;
        hasher.update(&chunk);
        done += chunk.len() as u64;
        if last_emit.elapsed().as_millis() >= 200 {
            last_emit = std::time::Instant::now();
            emit(id, "download", done, total, "下载中");
        }
    }
    std::io::Write::flush(&mut file).ok();
    drop(file);
    emit(id, "download", done, total, "下载完成");

    std::fs::rename(&part, dest).map_err(|e| format!("落盘失败：{e}"))?;
    Ok(())
}

async fn sha1_file(path: &Path) -> Result<String, String> {
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<String, String> {
        use std::io::Read;
        let mut file = std::fs::File::open(&path).map_err(|e| format!("打开文件失败：{e}"))?;
        let mut hasher = Sha1::new();
        let mut buf = vec![0u8; 1 << 20];
        loop {
            let n = file.read(&mut buf).map_err(|e| format!("读取失败：{e}"))?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        Ok(format!("{:x}", hasher.finalize()))
    })
    .await
    .map_err(|e| format!("校验任务失败：{e}"))?
}

use sha1::{Digest, Sha1};

fn emit(id: &str, phase: &str, done: u64, total: u64, message: &str) {
    crate::progress::emit_plugin_progress(id, phase, done, total, message);
}

// ═══════════════════════════════════════════════════════════════════════════
// 启动流程用的解析结果
// ═══════════════════════════════════════════════════════════════════════════

/// 已启用插件提供的路径。启动流程据此决定「用插件还是用随包内置」。
#[derive(Debug, Clone, Default)]
pub struct ResolvedPluginPaths {
    /// classpath 条目（jar）
    pub classpath: Vec<PathBuf>,
    /// LWJGL 自己的库搜索目录（`-Dorg.lwjgl.librarypath`）
    pub lightgl_dir: Option<PathBuf>,
    /// 需要进 `java.library.path` 的目录。
    ///
    /// **顺序即优先级**：渲染器 / 驱动插件的 libs 排在最前 —— 它们和随包内置的同名库
    /// 抢的正是这个优先级，排后面就等于没装。
    pub library_dirs: Vec<PathBuf>,
    /// 渲染后端键 → 其 libs 目录（v2：`mobileglues` / `opengles2` / `vulkan_zink`）。
    pub renderer_libs: Vec<(String, PathBuf)>,
}

fn subdir(dir: &Path, custom: &Option<String>, default: &str) -> Option<PathBuf> {
    let name = custom.clone().unwrap_or_else(|| default.to_string());
    let path = dir.join(name);
    path.is_dir().then_some(path)
}

/// 一个「已启用 + 已安装 + 适配本机 ABI」的插件（运行时视图）。
struct EnabledPlugin {
    id: String,
    kind: String,
    renderers: Vec<String>,
    layout: Layout,
    /// `<data>/plugins/<id>/<version>`
    dir: PathBuf,
}

fn collect_enabled(
    data_dir: &str,
    id: &str,
    kind: &str,
    renderers: &[String],
    layout: &Layout,
    dir: PathBuf,
    out: &mut Vec<EnabledPlugin>,
) {
    let Some(state) = installed_state(data_dir, id) else {
        return;
    };
    if !state.enabled {
        return;
    }
    let dir = dir.join(&state.version);
    if !dir.is_dir() {
        return;
    }
    out.push(EnabledPlugin {
        id: id.to_string(),
        kind: kind.to_string(),
        renderers: renderers.to_vec(),
        layout: layout.clone(),
        dir,
    });
}

/// 列出所有「已启用 + 已安装」的插件（清单里的 + 本地装的）。
fn enabled_plugins(data_dir: &str) -> Vec<EnabledPlugin> {
    let manifest = cached_manifest(data_dir);
    let abi = device_abi();
    let mut out = Vec::new();

    if let Some(manifest) = &manifest {
        for spec in &manifest.plugins {
            // 清单说这个插件没有本机 ABI 的包，就跳过（装了也不是给这台机器的）
            if !spec.files.is_empty() && !spec.files.contains_key(&abi) {
                continue;
            }
            collect_enabled(
                data_dir,
                &spec.id,
                &spec.kind,
                &spec.renderers,
                &spec.layout,
                plugins_root(data_dir).join(&spec.id),
                &mut out,
            );
        }
    }

    // 本地安装但清单里没有的（`list()` 也会列出来，这里保持一致）
    if let Ok(entries) = std::fs::read_dir(plugins_root(data_dir)) {
        for entry in entries.flatten() {
            let id = entry.file_name().to_string_lossy().to_string();
            if !entry.path().is_dir() || id.starts_with('.') {
                continue;
            }
            if manifest
                .as_ref()
                .is_some_and(|m| m.plugins.iter().any(|p| p.id == id))
            {
                continue;
            }
            collect_enabled(
                data_dir,
                &id,
                "components",
                &[],
                &Layout::default(),
                entry.path(),
                &mut out,
            );
        }
    }

    out
}

/// 目录里的 jar，按「类加载顺序」排好。
///
/// 顺序敏感：`lwjgl.jar`（核心：Version/MemoryUtil/system.*）→ merged-modules
/// （**补丁类在这里**：glfw stub、GLCapabilities、SDL 集成）→ 其余模块。
/// merged 必须排在 sdl.jar 之前，否则官方原版 SDLInit/SDLMouse 会盖掉补丁版。
fn sorted_jars(dir: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut jars: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "jar"))
        .collect();
    jars.sort_by_key(|p| {
        let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
        match name {
            "lwjgl.jar" => 0,
            n if n.contains("merged-modules") => 1,
            _ => 2,
        }
    });
    jars
}

/// 收集「已启用且已安装」插件的路径。
///
/// 三类插件都参与：
/// - `components`：jar（classpath）+ natives（org.lwjgl.librarypath）+ libs（java.library.path）；
/// - `renderer`：libs（渲染器实现），并按 `renderers` 键做映射，谁被选中谁生效；
/// - `driver`：libs（Turnip / ANGLE / Vulkan 层这类），只参与路径优先级。
pub fn resolved_component_paths(data_dir: &str) -> ResolvedPluginPaths {
    let mut out = ResolvedPluginPaths::default();
    let mut renderer_dirs: Vec<PathBuf> = Vec::new();
    let mut other_dirs: Vec<PathBuf> = Vec::new();

    for p in enabled_plugins(data_dir) {
        if let Some(jars_dir) = subdir(&p.dir, &p.layout.classpath, "jars") {
            out.classpath.extend(sorted_jars(&jars_dir));
        }
        if let Some(natives) = subdir(&p.dir, &p.layout.lightgl, "natives") {
            out.lightgl_dir = Some(natives);
        }
        if let Some(libs) = subdir(&p.dir, &p.layout.librarypath, "libs") {
            if p.kind == "renderer" || p.kind == "driver" {
                for key in &p.renderers {
                    out.renderer_libs.push((key.clone(), libs.clone()));
                }
                let _ = &p.id;
                renderer_dirs.push(libs);
            } else {
                other_dirs.push(libs);
            }
        }
    }

    // 渲染器 / 驱动在前：它们要和内置同名库抢优先权；组件插件的 libs（SDL3 等）随后。
    renderer_dirs.extend(other_dirs);
    out.library_dirs = renderer_dirs;
    out
}

/// 提供 `key` 这个渲染后端的、已启用插件的 libs 目录。
///
/// `key` 用的是设置里的渲染器名（`opengles2` / `mobileglues` / `vulkan_zink`），
/// 与清单里 `renderers` 字段的取值一致。
pub fn renderer_libs_dir(data_dir: &str, key: &str) -> Option<PathBuf> {
    if key.is_empty() {
        return None;
    }
    resolved_component_paths(data_dir)
        .renderer_libs
        .into_iter()
        .find(|(k, _)| k == key)
        .map(|(_, dir)| dir)
}
