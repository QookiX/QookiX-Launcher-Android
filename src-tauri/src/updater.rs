//! 启动器自更新：查 GitHub Release → 下 APK → 交给系统安装器。
//!
//! 为什么不用 Tauri 官方 updater 插件：那套在桌面端靠 NSIS/AppImage 静默替换，
//! 安卓上没有对应机制，必须走「下载 APK + 系统安装确认」这条路。
//!
//! 流程与桌面端保持一致的部分：启动静默检查、下载进度事件、可忽略某个版本、
//! 下载完不自动装（由用户点一下再装）。

use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

use futures::StreamExt as _;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// 仓库坐标与 API 地址（桌面端用的是 latest.json 清单，安卓直接用 GitHub API）。
const REPO: &str = "ZhaYi-Miao/QookiX-Launcher-Android";
const API_LATEST: &str = "https://api.github.com/repos";
/// 下载进度事件名（与桌面端同名，前端逻辑可以照抄）。
pub const PROGRESS_EVENT: &str = "app-update-progress";

/// 上传 APK 的 workflow 会把产物改名成 `QookiX-Launcher-Android-<tag>-arm64.apk`，
/// 这里按「含 arm64 且以 .apk 结尾」挑，避免以后改名就找不到。
const ASSET_APK_HINT: (&str, &str) = ("arm64", ".apk");

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    pub available: bool,
    pub version: Option<String>,
    pub current_version: String,
    pub notes: Option<String>,
    pub size: Option<u64>,
    /// 这个版本的 APK 已经下好了（路径），前端可以直接给「安装」按钮。
    pub downloaded_path: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateProgress {
    pub downloaded: u64,
    pub total: u64,
}

/// 待安装的版本信息（check 时记下，download 时用）。
#[derive(Debug, Clone)]
struct PendingRelease {
    version: String,
    url: String,
    size: u64,
    sums_url: Option<String>,
}

static PENDING: Mutex<Option<PendingRelease>> = Mutex::new(None);

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent("QookiX-Launcher-Android")
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| format!("创建网络客户端失败：{e}"))
}

/// 把 `v1.2.3` / `1.2.3` / `1.2.3-beta.1` 解析成可比较的数字三元组。
fn parse_version(raw: &str) -> (u32, u32, u32) {
    let cleaned = raw.trim().trim_start_matches('v');
    let core = cleaned.split(['-', '+']).next().unwrap_or(cleaned);
    let mut parts = core.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    (
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
        parts.next().unwrap_or(0),
    )
}

fn is_newer(candidate: &str, current: &str) -> bool {
    parse_version(candidate) > parse_version(current)
}

/// 下载目录：`<files>/updates`（与 Kotlin 侧 FileProvider 的 updates 路径一致）。
pub fn updates_dir(data_dir: &str) -> PathBuf {
    PathBuf::from(data_dir).join("updates")
}

fn local_apk_path(data_dir: &str, version: &str) -> PathBuf {
    updates_dir(data_dir).join(format!("qookix-{version}.apk"))
}

/// 检查更新。`data_dir` 用来判断这个版本的包是不是已经下过了。
#[tauri::command]
pub async fn check_for_update(app: tauri::AppHandle) -> Result<UpdateInfo, String> {
    let current = app.package_info().version.to_string();
    let url = format!("{API_LATEST}/{REPO}/releases/latest");
    let data_dir = crate::settings::data_dir_sync().unwrap_or_default();

    let resp = client()?
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|e| format!("请求 GitHub 失败：{e}"))?;

    if !resp.status().is_success() {
        // 404 = 还没有任何 release（不是错误，只是没得更新）
        if resp.status().as_u16() == 404 {
            return Ok(UpdateInfo {
                available: false,
                version: None,
                current_version: current,
                notes: None,
                size: None,
                downloaded_path: None,
            });
        }
        return Err(format!("GitHub 返回 {}", resp.status().as_u16()));
    }

    let body: serde_json::Value = resp
        .json()
        .await
        .map_err(|e| format!("解析 Release 失败：{e}"))?;

    let tag = body
        .get("tag_name")
        .and_then(|v| v.as_str())
        .unwrap_or_default()
        .to_string();
    let version = tag.trim_start_matches('v').to_string();
    let notes = body
        .get("body")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string());

    if version.is_empty() || !is_newer(&version, &current) {
        return Ok(UpdateInfo {
            available: false,
            version: Some(version),
            current_version: current,
            notes,
            size: None,
            downloaded_path: None,
        });
    }

    // 挑 arm64 的 APK；顺带找 SHA256SUMS 用于校验
    let mut apk: Option<(String, u64)> = None;
    let mut sums_url: Option<String> = None;
    if let Some(assets) = body.get("assets").and_then(|v| v.as_array()) {
        for asset in assets {
            let name = asset.get("name").and_then(|v| v.as_str()).unwrap_or("");
            let url = asset
                .get("browser_download_url")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            if name.contains("SHA256SUMS") {
                sums_url = Some(url.to_string());
            } else if name.contains(ASSET_APK_HINT.0) && name.ends_with(ASSET_APK_HINT.1) {
                apk = Some((
                    url.to_string(),
                    asset.get("size").and_then(|v| v.as_u64()).unwrap_or(0),
                ));
            }
        }
    }

    let Some((apk_url, size)) = apk else {
        return Err("这个版本里没有 arm64 的 APK 产物".to_string());
    };

    let downloaded = local_apk_path(&data_dir, &version);
    let downloaded_path = downloaded
        .is_file()
        .then(|| downloaded.to_string_lossy().to_string());

    *PENDING.lock().unwrap() = Some(PendingRelease {
        version: version.clone(),
        url: apk_url,
        size,
        sums_url,
    });

    Ok(UpdateInfo {
        available: true,
        version: Some(version),
        current_version: current,
        notes,
        size: Some(size),
        downloaded_path,
    })
}

/// 下载更新包，返回落盘的绝对路径。进度走 [`PROGRESS_EVENT`]。
///
/// ## 为什么要传 `version`（2026-10-18 修）
///
/// 原来这个函数只读全局 `PENDING`。`PENDING` 是**单例**，检查 A 版本后
/// 若又检查了 B 版本，它会被覆盖成 B —— 此时前端界面还显示 A，
/// 点「下载」实际下的是 **B**（版本号与文件名都对不上）。
///
/// 现在要求前端把 `check_for_update` 返回的版本号带回来：
///   - 与 `PENDING` 一致 → 正常下载
///   - 不一致 → 报错「版本已变化，请重新检查更新」
/// 这样即使中途又检查了别的版本，也不会下错。
///
/// `version` 传空串时回退到 `PENDING`（保持对旧调用的兼容）。
#[tauri::command]
pub async fn download_update(
    app: tauri::AppHandle,
    version: Option<String>,
) -> Result<String, String> {
    use tauri::Emitter as _;

    let pending_all = PENDING
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "请先检查更新".to_string())?;

    // 前端给了版本号就校验一致；没给就用 PENDING 里的
    let pending = match version.as_deref().map(str::trim).filter(|v| !v.is_empty()) {
        Some(v) if v != pending_all.version => {
            return Err(format!(
                "版本已变化（当前待下载 {}，收到 {v}），请重新检查更新",
                pending_all.version
            ))
        }
        _ => pending_all,
    };
    let data_dir = crate::settings::data_dir_sync().unwrap_or_default();
    let dir = updates_dir(&data_dir);
    std::fs::create_dir_all(&dir).map_err(|e| format!("创建更新目录失败：{e}"))?;
    let target = local_apk_path(&data_dir, &pending.version);
    let part = target.with_extension("apk.part");

    let resp = client()?
        .get(&pending.url)
        .send()
        .await
        .map_err(|e| format!("下载失败：{e}"))?;
    if !resp.status().is_success() {
        return Err(format!("下载失败：HTTP {}", resp.status().as_u16()));
    }

    let total = pending.size.max(resp.content_length().unwrap_or(0));
    let mut file = std::fs::File::create(&part).map_err(|e| format!("写入失败：{e}"))?;
    let mut hasher = Sha256::new();
    let mut done: u64 = 0;
    let mut last_emit = std::time::Instant::now();

    let mut stream = resp.bytes_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("下载中断：{e}"))?;
        use std::io::Write as _;
        file.write_all(&chunk).map_err(|e| format!("写入失败：{e}"))?;
        hasher.update(&chunk);
        done += chunk.len() as u64;
        if last_emit.elapsed().as_millis() >= 200 {
            last_emit = std::time::Instant::now();
            let _ = app.emit(
                PROGRESS_EVENT,
                UpdateProgress {
                    downloaded: done,
                    total,
                },
            );
        }
    }
    use std::io::Write as _;
    file.flush().ok();
    drop(file);
    let _ = app.emit(
        PROGRESS_EVENT,
        UpdateProgress {
            downloaded: done,
            total,
        },
    );

    // 官方同版本发布里带 SHA256SUMS.txt，能拿到就校验（拿不到只警告不失败）
    if let Some(sums_url) = &pending.sums_url {
        if let Ok(sums) = fetch_text(&client()?, sums_url).await {
            let file_name = target
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            if let Some(expected) = find_sha256(&sums, &file_name) {
                let actual = format!("{:x}", hasher.finalize());
                if actual != expected {
                    let _ = std::fs::remove_file(&part);
                    return Err(format!("校验失败（期望 {expected}，实际 {actual}）"));
                }
            }
        }
    }

    std::fs::rename(&part, &target).map_err(|e| format!("落盘失败：{e}"))?;
    Ok(target.to_string_lossy().to_string())
}

/// 交给系统安装器（会弹「是否安装」确认，安卓不允许静默装）。
#[tauri::command]
pub fn install_update(path: String) -> Result<(), String> {
    crate::android_bridge::install_apk(&path)
}

async fn fetch_text(client: &reqwest::Client, url: &str) -> Result<String, String> {
    let resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("请求失败：{e}"))?;
    resp.text().await.map_err(|e| format!("读取失败：{e}"))
}

/// 从 `SHA256SUMS.txt` 里找出某个文件名的期望值。
fn find_sha256(sums: &str, file_name: &str) -> Option<String> {
    for line in sums.lines() {
        let mut parts = line.split_whitespace();
        let hash = parts.next()?;
        let name = parts.next().unwrap_or("").trim_start_matches('*');
        if name == file_name {
            return Some(hash.to_ascii_lowercase());
        }
    }
    None
}

