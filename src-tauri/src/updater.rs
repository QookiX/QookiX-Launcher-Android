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

use serde::{Deserialize, Serialize};

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

/// 查版本元数据的超时（纯 JSON，几十 KB）。
const META_TIMEOUT: Duration = Duration::from_secs(20);

use crate::updater_version::{
    find_sha256, is_newer, parse_version_full, published_after, UpdateChannel,
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
    //
    // ⚠️ 这一步**走不了镜像链**：`ghfast.top` / `gh-proxy.com` / `ghproxy.net`
    // 都不代理 `api.github.com`（2026-11-07 实测全部 403），只代 `github.com` 文件。
    // 所以元数据只能直连；真正的瓶颈（几十 MB 的 APK）在 download_update 里换源。
    let url = format!("{API_LATEST}/{REPO}/releases?per_page=30");
    let resp = crate::util::http_client()
        .await
        .get(&url)
        .timeout(META_TIMEOUT)
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
    // BE-12：固定 `.part` 名时，连点两次「下载更新」（或静默检查与手动下载重叠）会互相截断 ——
    // 第二个流 `File::create` 直接把第一个清空，两个流再交错写，rename 出来就是撕裂的包。
    // 用时间戳区分，两次各写各的。
    let part = target.with_extension(format!("{}.part", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)));

    // ① 先取 SHA256SUMS：期望摘要要在**下载前**拿到，候选链每下完一个源就能立刻校验、
    //    对不上自动换源。以前是「先下完再取摘要」，换个源就得整包重下。
    // ⚠️ 2026-11-04（审计 BE-04）：原来 `if let Ok(sums) = ...` —— release 没附
    // SHA256SUMS、请求失败/超时，全都会**静默跳过校验**，然后照样 rename 并返回成功。
    // 现在：拿不到校验文件就明确拒绝，不再放行。
    let sums_url = pending
        .sums_url
        .as_deref()
        .ok_or_else(|| "该版本没有提供 SHA256SUMS.txt，拒绝安装未校验的更新包".to_string())?;
    let sums = crate::netdl::fetch_text(&candidates_for(sums_url))
        .await
        .map_err(|e| format!("无法获取 SHA256SUMS.txt（{e}），拒绝安装未校验的更新包"))?;

    // ⚠️ 用**远程资产原名**，不是本地落盘名（B14）：
    // 本地是 local_apk_path() 拼的名字，去 SHA256SUMS 里永远找不到。
    let file_name = pending.asset_name.clone();
    let expected = find_sha256(&sums, &file_name).ok_or_else(|| {
        format!("SHA256SUMS.txt 里没有找到 {file_name} 的校验值，拒绝安装未校验的更新包")
    })?;

    // ② 按候选链下载（镜像 → 官方）：每源限时、下完立即校验 sha256，对不上自动换源。
    //    用 `util::http_client` 而不是本文件那个 30s 总超时的 `client()` ——
    //    79MB 的包在 30 秒内下不完，那等于把更新功能废掉。
    let size = pending.size;
    let progress = |done: u64, total: u64| {
        crate::progress::push_ser(
            PROGRESS_EVENT,
            UpdateProgress {
                downloaded: done,
                total: size.max(total),
            },
        );
    };
    crate::netdl::download_candidates(
        &candidates_for(&pending.url),
        &part,
        size,
        crate::netdl::Expected::Sha256(&expected),
        &progress,
    )
    .await
    .map_err(|e| format!("下载失败：{e}"))?;

    std::fs::rename(&part, &target).map_err(|e| format!("落盘失败：{e}"))?;
    crate::util::log_line(&format!("更新包校验通过：{file_name}"));
    Ok(target.to_string_lossy().to_string())
}

/// 某个 GitHub 地址的候选链（镜像 → 官方）。
fn candidates_for(url: &str) -> Vec<String> {
    crate::mirror::candidate_urls(&crate::mirror::current_base(), url)
}

/// 交给系统安装器（会弹「是否安装」确认，安卓不允许静默装）。
pub fn install_update(path: String) -> Result<(), String> {
    // ⚠️ BE-10：只允许装「更新目录」里的包。
    // 原样把调用方给的路径交给系统安装器，等于 IPC 就能让手机装任意位置的 APK
    // （同文件 download_file:188 有 dest_allowed，这里一直是对称缺口）。
    let data_dir = crate::settings::data_dir_sync().unwrap_or_default();
    let dir = updates_dir(&data_dir);
    let canon_dir = std::fs::canonicalize(&dir).unwrap_or_else(|_| dir.clone());
    let canon = std::fs::canonicalize(&path).map_err(|e| format!("安装包不存在：{e}"))?;
    if !canon.starts_with(&canon_dir) {
        return Err("只能安装更新目录下的安装包".to_string());
    }
    crate::android_bridge::install_apk(&path)
}
