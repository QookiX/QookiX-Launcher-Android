//! PaperMC「Hangar」插件平台客户端 + 服务端插件安装。
//!
//! 为什么单独一个模块而不塞进 `browse.rs`：那边是**面向实例**的模型
//! （Modrinth / CurseForge 的 project → version → files[]），目标目录、版本语义、
//! 「装到哪」都不同；硬塞进去只会长出一堆 `if provider == "hangar"`。
//!
//! 用的都是公开接口，**不需要 API key**：
//!   - 搜索：`GET /api/v1/projects?limit=&offset=&q=`
//!   - 版本：`GET /api/v1/projects/{slug}/versions?limit=&offset=`
//!   - 直链：版本对象里的 `downloads.PAPER.downloadUrl`（托管在 `hangarcdn.papermc.io`）
//!
//! 两个实测坑（2026-10-10 真机/真接口验证）：
//!   1. **必须原样使用接口返回的 URL**：版本号里带 `+` 时它以 `%2B` 编码，
//!      自己按 `+` 拼一遍会 404（ViaVersion 5.12.2-SNAPSHOT+1096 实测）。
//!   2. 有的插件**没托管在 Hangar**（只有 `externalUrl` 指向 GitHub 发布页，
//!      `downloadUrl` 为 null）—— 那种不能假装能装，要如实告诉用户去外部下载。
//!      真按 Hangar 的 download 端点去拉会拿到一个 GitHub 的 HTML 页面（实测）。

use anyhow::{Context, Result};
use serde_json::Value;

const HANGAR_API: &str = "https://hangar.papermc.io/api/v1";

/// 平台标识。Bukkit / Spigot / Purpur 等 Paper 系服务端统一用 Hangar 的 `PAPER` 构建
/// （这是该平台对这些服务端的最小公分母）。
const PLATFORM: &str = "PAPER";

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HangarPlugin {
    /// 调接口时用的准确 slug（来自 `namespace.slug`，**大小写敏感**）
    pub slug: String,
    pub owner: String,
    pub name: String,
    pub description: String,
    pub downloads: u64,
    pub icon: Option<String>,
    /// 该插件声明支持的 Paper 游戏版本（用于判断与服务器版本是否兼容）
    pub versions: Vec<String>,
}

/// 搜索插件。`page` 从 0 开始。
pub async fn search(query: &str, page: u32, page_size: u32) -> Result<Vec<HangarPlugin>> {
    let client = crate::util::http_client().await;
    let limit = page_size.clamp(1, 50);
    let offset = page.saturating_mul(limit);
    let url = format!("{HANGAR_API}/projects?limit={limit}&offset={offset}&q={}", urlencode(query));
    let resp = client.get(&url).send().await.context("请求 Hangar 失败")?;
    if !resp.status().is_success() {
        anyhow::bail!("Hangar 返回 {}", resp.status());
    }
    let body: Value = resp.json().await.context("解析 Hangar 响应失败")?;
    let mut out = Vec::new();
    for it in body["result"].as_array().cloned().unwrap_or_default() {
        let slug = it["namespace"]["slug"].as_str().unwrap_or("").to_string();
        if slug.is_empty() {
            continue;
        }
        out.push(HangarPlugin {
            slug,
            owner: it["namespace"]["owner"].as_str().unwrap_or("").to_string(),
            name: it["name"].as_str().unwrap_or("").to_string(),
            description: it["description"].as_str().unwrap_or("").to_string(),
            downloads: it["stats"]["downloads"].as_u64().unwrap_or(0),
            icon: it["avatarUrl"].as_str().map(|s| s.to_string()),
            versions: it["supportedPlatforms"][PLATFORM]
                .as_array()
                .map(|a| a.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect())
                .unwrap_or_default(),
        });
    }
    Ok(out)
}

/// 已装插件列表（`<服务器>/plugins/*.jar`）。
pub fn list_plugins(server_id: &str) -> Result<Vec<Value>> {
    let dir = crate::servers::server_sub_dir(server_id, "plugins")
        .map_err(|e| anyhow::anyhow!(e))?;
    let mut out: Vec<Value> = Vec::new();
    if let Ok(rd) = std::fs::read_dir(&dir) {
        for e in rd.flatten() {
            let p = e.path();
            if !p.is_file() {
                continue;
            }
            let name = e.file_name().to_string_lossy().to_string();
            // 只认 jar：plugins 目录里还会有插件自己的配置目录（如 EssentialsX 的）
            if !name.ends_with(".jar") {
                continue;
            }
            out.push(serde_json::json!({
                "fileName": name,
                "size": std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0),
            }));
        }
    }
    out.sort_by(|a, b| a["fileName"].as_str().cmp(&b["fileName"].as_str()));
    Ok(out)
}

/// 删除某个已装插件。只允许单层文件名（`fsutil::validate_name` 挡路径穿越）。
pub fn delete_plugin(server_id: &str, file_name: &str) -> Result<()> {
    crate::fsutil::validate_name(file_name).map_err(|e| anyhow::anyhow!(e))?;
    if !crate::util::is_safe_filename(file_name) || !file_name.ends_with(".jar") {
        anyhow::bail!("非法的插件文件名");
    }
    let dir = crate::servers::server_sub_dir(server_id, "plugins")
        .map_err(|e| anyhow::anyhow!(e))?;
    let path = dir.join(file_name);
    std::fs::remove_file(&path).with_context(|| format!("删除失败：{}", path.display()))?;
    Ok(())
}

/// 给某个服务器装一个 Hangar 插件。
///
/// 版本选择：先挑**声明支持该服务器 MC 版本**的版本（优先 Release 渠道，其次最新）；
/// 一个都不兼容就如实报错并列出它支持的版本 —— 装错版本的插件只会让服务端起不来，
/// 而用户看着「装好了」完全不知道为什么。
pub async fn install_plugin(server_id: &str, slug: &str, project_name: &str) -> Result<Value> {
    let server = crate::servers::get_server(server_id).map_err(|e| anyhow::anyhow!(e))?;
    use crate::server_install_guard as guard;
    guard::ensure_plugin_core(&server.name, server.core).map_err(anyhow::Error::msg)?;
    let mc = &server.mc_version;
    let client = crate::util::http_client().await;
    let mut versions = Vec::new();
    let mut total = None;
    for offset in (0..250).step_by(25) {
        let url = format!("{HANGAR_API}/projects/{}/versions?limit=25&offset={offset}", urlencode(slug));
        let resp = client.get(url).send().await.context("请求 Hangar 版本列表失败")?;
        if !resp.status().is_success() { anyhow::bail!("Hangar 返回 {}", resp.status()); }
        let body: Value = resp.json().await.context("解析 Hangar 版本列表失败")?;
        total = body["pagination"]["count"].as_u64().or(total);
        let page = body["result"].as_array().context("Hangar 版本列表格式错误")?;
        versions.extend(page.iter().cloned());
        if guard::page_has_compatible_release(page, PLATFORM, mc) || page.len() < 25 { break; }
    }
    let Some(pick) = guard::pick_hangar_version(&versions, PLATFORM, mc) else {
        let supported = guard::collected_supported_versions(&versions, PLATFORM);
        anyhow::bail!("{}", guard::hangar_no_version_message(project_name, mc, versions.len(), total, &supported));
    };
    let picked = pick.version;
    let compat_verified = pick.compat_verified;
    let version_name = picked["name"].as_str().unwrap_or("").to_string();
    // **原样使用接口给的 URL**（含 `%2B` 这类编码），不要自己拼
    let dl = &picked["downloads"][PLATFORM];
    let download_url = dl["downloadUrl"]
        .as_str()
        .filter(|s| !s.is_empty())
        .or_else(|| dl["fileInfo"]["url"].as_str().filter(|s| !s.is_empty()));
    let Some(download_url) = download_url else {
        // 没托管在 Hangar（只有 `externalUrl`，指向 GitHub 之类）——
        // **不当作错误**：把外部地址原样交回前端去开浏览器，让用户自己下载。
        // 之前这里直接 bail，用户只看到一句「请到 xxx 手动下载」，还得自己抄网址。
        let external = dl["externalUrl"].as_str().unwrap_or("").trim().to_string();
        return Ok(serde_json::json!({
            "installed": false,
            "fileName": null,
            "compatVerified": compat_verified,
            "externalUrl": if external.is_empty() { Value::Null } else { Value::String(external) },
        }));
    };
    let download_url = download_url.to_string();

    // 文件名取自直链最后一段（`%2B` 要解码回 `+`，否则文件名里会留一串 %2B）
    let raw_name = download_url.rsplit('/').next().unwrap_or("").to_string();
    let file_name = percent_decode(&raw_name);
    let file_name = if file_name.ends_with(".jar") && crate::util::is_safe_filename(&file_name) {
        file_name
    } else {
        format!(
            "{}-{}.jar",
            crate::util::safe_stem(project_name),
            version_name.replace(['+', '/'], "_")
        )
    };

    let dir = crate::servers::server_sub_dir(server_id, "plugins")
        .map_err(|e| anyhow::anyhow!(e))?;
    let dest = dir.join(&file_name);

    // 进度：TaskCtx 的两个字段语义是「实例 id/名」，这里塞服务器 id/名 —— 它只用于
    // 任务卡片上的归属显示，下载中心照旧能显示进度，不必为服务端再造一套事件。
    let ctx = crate::progress::TaskCtx::new(server_id, &server.name, "服务端插件");
    crate::progress::emit_install(&ctx, "plugin", &format!("正在下载 {file_name}…"), 0, 1);
    let result = crate::download::download_file(&download_url, &dest.to_string_lossy(), None).await;
    match &result {
        Ok(p) => crate::progress::emit_download(
            &ctx,
            "plugin",
            1,
            1,
            &file_name,
            p.downloaded_bytes.max(0) as u64,
            p.total_bytes.max(0) as u64,
            true,
        ),
        Err(e) => crate::progress::emit_install_done(&ctx, false, &format!("下载失败：{e}"), 0, 1),
    }
    result?;
    crate::progress::emit_install_done(&ctx, true, "插件安装完成", 1, 1);

    Ok(serde_json::json!({
        "installed": true,
        "fileName": file_name,
        "version": version_name,
        // 兼容性是「按声明匹配」还是「接口没给版本信息」——界面要区别对待
        "compatVerified": compat_verified,
        "externalUrl": null,
    }))
}

// ── 小工具 ──────────────────────────────────────────────────────────────

/// 查询串转义（只处理会破坏 URL 的字符，插件名里的空格/中文都要能用）。
fn urlencode(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.as_bytes() {
        match *b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(*b as char)
            }
            _ => out.push_str(&format!("%{b:02X}")),
        }
    }
    out
}

/// 把 `%2B` 这样的转义还原成字符（直链文件名里会出现）。
fn percent_decode(s: &str) -> String {
    let bytes = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).unwrap_or("");
            if let Ok(v) = u8::from_str_radix(hex, 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}
