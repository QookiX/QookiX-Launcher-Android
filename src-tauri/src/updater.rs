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
const REPO: &str = "QookiX/QookiX-Launcher-Android";
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
    /// 远程资产原名（`QookiX-Launcher-Android-<tag>-arm64.apk`）。
    /// 审计 B14：原来拿本地拼的名字去 SHA256SUMS 里查，永远查不到 → 校验被静默跳过。
    asset_name: String,
}

static PENDING: Mutex<Option<PendingRelease>> = Mutex::new(None);

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent("QookiX-Launcher-Android")
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| format!("创建网络客户端失败：{e}"))
}

use crate::updater_version::{
    fetch_text, find_sha256, is_newer, parse_version_full, published_after, UpdateChannel,
};

/// 下载目录：`<files>/updates`（与 Kotlin 侧 FileProvider 的 updates 路径一致）。
pub fn updates_dir(data_dir: &str) -> PathBuf {
    PathBuf::from(data_dir).join("updates")
}

fn local_apk_path(data_dir: &str, version: &str) -> PathBuf {
    updates_dir(data_dir).join(format!("qookix-{version}.apk"))
}

/// 检查更新。`data_dir` 用来判断这个版本的包是不是已经下过了。
pub async fn check_for_update() -> Result<UpdateInfo, String> {
    let current = env!("CARGO_PKG_VERSION").to_string();
    let data_dir = crate::settings::data_dir_sync().unwrap_or_default();

    let raw_settings = crate::settings::load_raw_settings_sync();
    let channel = UpdateChannel::from_str(
        &crate::settings::raw_str(&raw_settings, &["update_channel", "updateChannel"])
            .unwrap_or_default(),
    );

    // 用列表接口而不是 `/releases/latest`：后者只给一个「最新」，
    // 没法按渠道挑（正式版用户不该被推 beta）。
    let url = format!("{API_LATEST}/{REPO}/releases?per_page=30");
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

    let list: Vec<serde_json::Value> = resp
        .json()
        .await
        .map_err(|e| format!("解析 Release 列表失败：{e}"))?;

    // 先定位「当前这版」的发布时间。
    //
    // 版本线重置过一次（1.8.0 → 0.2.x），从此语义化顺序 ≠ 时间顺序：
    // `(1,8,0) > (0,2,2)` 恒成立，所以光比版本号会让每个 0.2.x 用户
    // 一开 App 就被提示「发现新版本 1.8.0」（2026-10-30 真机复现）。
    // 以当前版本的发布时间为界，只认比它更晚发布的 Release。
    let current_published = list.iter().find_map(|rel| {
        let tag = rel.get("tag_name").and_then(|v| v.as_str())?;
        if tag.trim_start_matches('v') != current {
            return None;
        }
        rel.get("published_at")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string())
    });

    let mut best: Option<(String, serde_json::Value)> = None;
    for rel in list {
        let tag = rel
            .get("tag_name")
            .and_then(|v| v.as_str())
            .unwrap_or_default();
        let v = tag.trim_start_matches('v').to_string();
        if v.is_empty() {
            continue;
        }
        let parsed = parse_version_full(&v);
        let published = rel.get("published_at").and_then(|v| v.as_str());
        if !published_after(published, current_published.as_deref())
            || !channel.allows(parsed.3)
            || !is_newer(&v, &current)
        {
            continue;
        }
        let better = match &best {
            None => true,
            Some((bv, _)) => parse_version_full(bv) < parsed,
        };
        if better {
            best = Some((v, rel));
        }
    }

    let Some((version, body)) = best else {
        return Ok(UpdateInfo {
            available: false,
            version: None,
            current_version: current,
            notes: None,
            size: None,
            downloaded_path: None,
        });
    };
    let notes = body
        .get("body")
        .and_then(|v| v.as_str())
        .map(|s| s.trim().to_string());

    // 挑 arm64 的 APK；顺带找 SHA256SUMS 用于校验
    // (url, 资产原名, 大小) —— 资产原名要留着给 SHA256SUMS 比对（见 B14）
    let mut apk: Option<(String, String, u64)> = None;
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
                    name.to_string(),
                    asset.get("size").and_then(|v| v.as_u64()).unwrap_or(0),
                ));
            }
        }
    }

    let Some((apk_url, apk_asset_name, size)) = apk else {
        return Err("这个版本里没有 arm64 的 APK 产物".to_string());
    };

    let downloaded = local_apk_path(&data_dir, &version);
    let downloaded_path = downloaded
        .is_file()
        .then(|| downloaded.to_string_lossy().to_string());

    *crate::util::lock_recover(&PENDING) = Some(PendingRelease {
        version: version.clone(),
        url: apk_url,
        size,
        sums_url,
        asset_name: apk_asset_name,
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
pub async fn download_update(
    version: Option<String>,
) -> Result<String, String> {

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
            crate::progress::push_ser(
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
    crate::progress::push_ser(
        PROGRESS_EVENT,
        UpdateProgress {
            downloaded: done,
            total,
        },
    );

    // 官方同版本发布里带 SHA256SUMS.txt，能拿到就校验（拿不到只警告不失败）
    if let Some(sums_url) = &pending.sums_url {
        if let Ok(sums) = fetch_text(&client()?, sums_url).await {
            // ⚠️ 用**远程资产原名**，不是本地落盘名（B14）：
            // 本地是 local_apk_path() 拼的名字，去 SHA256SUMS 里永远找不到，
            // 于是校验被静默跳过、装的是没有校验过的包。
            let local_name = target
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            let file_name = if pending.asset_name.is_empty() {
                local_name.as_str()
            } else {
                pending.asset_name.as_str()
            };
            match find_sha256(&sums, file_name) {
                Some(expected) => {
                    let actual = format!("{:x}", hasher.finalize());
                    if actual != expected {
                        let _ = std::fs::remove_file(&part);
                        return Err(format!("校验失败（期望 {expected}，实际 {actual}）"));
                    }
                    crate::util::log_line(&format!("更新包校验通过：{file_name}"));
                }
                None => {
                    // 以前这里是静默跳过，出了问题完全看不出来。
                    let _ = std::fs::remove_file(&part);
                    return Err(format!(
                        "SHA256SUMS.txt 里没有找到 {file_name} 的校验值，拒绝安装未校验的更新包"
                    ));
                }
            }
        }
    }

    std::fs::rename(&part, &target).map_err(|e| format!("落盘失败：{e}"))?;
    Ok(target.to_string_lossy().to_string())
}

/// 交给系统安装器（会弹「是否安装」确认，安卓不允许静默装）。
pub fn install_update(path: String) -> Result<(), String> {
    crate::android_bridge::install_apk(&path)
}
