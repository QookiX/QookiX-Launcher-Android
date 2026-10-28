//! 局域网直传同步：安卓端 HTTP 客户端。
//!
//! 连桌面端起的 HTTP 服务（见桌面端 `lan_sync.rs` 的 `start_server`），
//! 把 PC 上的实例拉到手机本地。
//!
//! 流程：[`connect`]（验证 token + 拿设备信息）→ [`list_instances`]（实例列表）
//!       → [`download_instance`]（流式下载 zip + 解压 + 元数据转换）。
//!
//! ## 进度
//!
//! 下载与解压过程经 `crate::progress::push("lan://progress", …)` 推给 Kotlin，
//! 字段：`phase`（download / extract / done / error）、`instanceId`、`instanceName`、
//! `bytesDone`、`bytesTotal`、`ok`。前端 `LanSyncPage` 订阅这个事件画进度条。
//!
//! ## 元数据格式差异
//!
//! 桌面端实例元数据文件是 `qookix.json`（`Instance` 结构），安卓端是 `instance.json`
//!（`MinecraftProfile` 结构）。`MinecraftProfile` 的 serde alias 能吃桌面端字段名，
//! 所以解压后把 `qookix.json` 读出来 → 换 id / game_dir → 写成 `instance.json` →
//! 删掉 `qookix.json`。

use serde::{Deserialize, Serialize};

/// 桌面端 `GET /api/info` 返回的设备信息。
#[derive(Debug, Serialize, Deserialize)]
pub struct LanDeviceInfo {
    pub device: String,
    pub version: String,
    pub instance_count: usize,
}

/// 桌面端 `GET /api/instances` 返回的单条实例摘要。
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LanInstanceSummary {
    pub id: String,
    pub name: String,
    #[serde(rename = "mc_version", alias = "mcVersion")]
    pub mc_version: String,
    pub loader: String,
    #[serde(rename = "loader_version", alias = "loaderVersion")]
    pub loader_version: Option<String>,
    pub size_bytes: u64,
    pub has_saves: bool,
    pub has_mods: bool,
}

// ── 内部工具 ─────────────────────────────────────────────────────────────

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        // 大存档可能传几分钟；下载本身靠事件推进度，不靠这个超时判死。
        .timeout(std::time::Duration::from_secs(600))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

fn endpoint(host: &str, port: u16, path: &str) -> String {
    format!("http://{host}:{port}{path}")
}

/// 简单校验 host/port/remote_id，防注入到 URL。
fn validate_host(host: &str) -> Result<(), String> {
    if host.is_empty() || host.contains('/') || host.contains(' ') {
        return Err(format!("非法 host：{host}"));
    }
    Ok(())
}

// ── 对外 API ─────────────────────────────────────────────────────────────

/// `GET /api/info` —— 验证 token + 拿设备信息（设备名 / 版本 / 实例数）。
///
/// 这一步成功 = 配对有效，前端据此显示「已连接：XXX 的 PC」。
pub async fn connect(host: &str, port: u16, token: &str) -> Result<LanDeviceInfo, String> {
    validate_host(host)?;
    let resp = client()
        .get(endpoint(host, port, "/api/info"))
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| format!("连接失败：{e}"))?;
    if !resp.status().is_success() {
        return Err(format!(
            "桌面端拒绝连接（HTTP {}）— token 可能已失效或服务已关闭",
            resp.status()
        ));
    }
    resp.json::<LanDeviceInfo>()
        .await
        .map_err(|e| format!("解析设备信息失败：{e}"))
}

/// `GET /api/instances` —— 拿 PC 上的实例列表。
pub async fn list_instances(
    host: &str,
    port: u16,
    token: &str,
) -> Result<Vec<LanInstanceSummary>, String> {
    validate_host(host)?;
    let resp = client()
        .get(endpoint(host, port, "/api/instances"))
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| format!("获取实例列表失败：{e}"))?;
    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }
    resp.json::<Vec<LanInstanceSummary>>()
        .await
        .map_err(|e| format!("解析实例列表失败：{e}"))
}

/// `GET /api/instances/:id` —— 流式下载 zip + 解压到新实例目录。
///
/// 返回新实例的 id（UUID）。下载/解压进度经 `lan://progress` 事件推给前端。
///
/// `instance_name` 仅用于进度事件里的显示名，不影响实际文件。
pub async fn download_instance(
    host: &str,
    port: u16,
    token: &str,
    remote_id: &str,
    instance_name: &str,
) -> Result<String, String> {
    validate_host(host)?;
    if remote_id.is_empty() || remote_id.contains('/') {
        return Err(format!("非法实例 id：{remote_id}"));
    }

    // 1. 新建实例目录（用新 UUID，不复用桌面端的 id —— 避免和本地已有实例撞 id）
    let new_id = uuid::Uuid::new_v4().to_string();
    let inst_dir = crate::settings::instance_dir(&new_id)
        .await
        .map_err(|e| format!("无法定位实例目录：{e}"))?;
    tokio::fs::create_dir_all(&inst_dir)
        .await
        .map_err(|e| format!("创建实例目录失败：{e}"))?;

    // 2. 流式下载到临时文件
    let tmp_zip = inst_dir.join(".lan_sync.tmp.zip");
    let download_result = stream_download(
        host,
        port,
        token,
        remote_id,
        &tmp_zip,
        &new_id,
        instance_name,
    )
    .await;
    if let Err(e) = &download_result {
        emit_error(&new_id, instance_name, e);
        let _ = std::fs::remove_file(&tmp_zip);
        let _ = std::fs::remove_dir_all(&inst_dir);
        return Err(e.clone());
    }

    // 3. 解压
    crate::progress::push(
        "lan://progress",
        serde_json::json!({
            "phase": "extract",
            "instanceId": new_id,
            "instanceName": instance_name,
            "bytesDone": 0,
            "bytesTotal": 0,
        }),
    );
    if let Err(e) = extract_zip(&tmp_zip, &inst_dir) {
        emit_error(&new_id, instance_name, &e);
        let _ = std::fs::remove_file(&tmp_zip);
        let _ = std::fs::remove_dir_all(&inst_dir);
        return Err(e);
    }
    let _ = std::fs::remove_file(&tmp_zip);

    // 4. 元数据格式转换：qookix.json → instance.json
    if let Err(e) = convert_metadata(&inst_dir, &new_id) {
        emit_error(&new_id, instance_name, &e);
        let _ = std::fs::remove_dir_all(&inst_dir);
        return Err(e);
    }

    // 5. 通知 UI 实例列表变了 + 收尾
    crate::app_state::mark_instances_dirty();
    crate::progress::push(
        "lan://progress",
        serde_json::json!({
            "phase": "done",
            "instanceId": new_id,
            "instanceName": instance_name,
            "ok": true,
        }),
    );
    Ok(new_id)
}

// ── 下载 ─────────────────────────────────────────────────────────────────

/// 流式下载一个实例 zip 到文件，边下边推 `lan://progress`。
async fn stream_download(
    host: &str,
    port: u16,
    token: &str,
    remote_id: &str,
    tmp_zip: &std::path::Path,
    new_id: &str,
    instance_name: &str,
) -> Result<(), String> {
    let resp = client()
        .get(endpoint(
            host,
            port,
            &format!("/api/instances/{remote_id}"),
        ))
        .bearer_auth(token)
        .send()
        .await
        .map_err(|e| format!("下载请求失败：{e}"))?;
    if !resp.status().is_success() {
        return Err(format!("下载失败：HTTP {}", resp.status()));
    }

    let total = resp.content_length().unwrap_or(0);

    let mut file = tokio::fs::File::create(tmp_zip)
        .await
        .map_err(|e| format!("创建临时文件失败：{e}"))?;
    use tokio::io::AsyncWriteExt;

    let mut stream = resp.bytes_stream();
    use futures::StreamExt;

    let mut done: u64 = 0;
    let mut last_emit = std::time::Instant::now();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("下载中断：{e}"))?;
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("写盘失败：{e}"))?;
        done += chunk.len() as u64;

        // 每 250ms 推一次进度，别刷爆事件通道（Kotlin 那侧要切主线程分发）
        if last_emit.elapsed() > std::time::Duration::from_millis(250) {
            crate::progress::push(
                "lan://progress",
                serde_json::json!({
                    "phase": "download",
                    "instanceId": new_id,
                    "instanceName": instance_name,
                    "bytesDone": done,
                    "bytesTotal": total,
                }),
            );
            last_emit = std::time::Instant::now();
        }
    }
    file.flush().await.map_err(|e| format!("flush 失败：{e}"))?;
    Ok(())
}

// ── 解压 ─────────────────────────────────────────────────────────────────

/// 把 zip 解压到目标目录。用 `enclosed_name()` 防路径穿越。
fn extract_zip(zip_path: &std::path::Path, dest: &std::path::Path) -> Result<(), String> {
    let file = std::fs::File::open(zip_path).map_err(|e| format!("打开 zip 失败：{e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("zip 解析失败：{e}"))?;

    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("zip 条目 {i} 失败：{e}"))?;
        let outpath = match entry.enclosed_name() {
            Some(p) => dest.join(p),
            None => continue, // 不安全的路径（含 .. / 绝对路径）跳过
        };
        if entry.is_dir() {
            std::fs::create_dir_all(&outpath).map_err(|e| format!("建目录失败：{e}"))?;
        } else {
            if let Some(parent) = outpath.parent() {
                std::fs::create_dir_all(parent).map_err(|e| format!("建父目录失败：{e}"))?;
            }
            let mut outfile =
                std::fs::File::create(&outpath).map_err(|e| format!("建文件失败：{e}"))?;
            std::io::copy(&mut entry, &mut outfile)
                .map_err(|e| format!("解压写失败：{e}"))?;
        }
    }
    Ok(())
}

// ── 元数据转换 ───────────────────────────────────────────────────────────

/// 桌面端 `qookix.json` → 安卓端 `instance.json`。
///
/// `MinecraftProfile` 的 serde alias（`mcVersion`/`loaderVersion`/…）能直接
/// 反序列化桌面端字段名。换掉 `id` 和 `game_dir` 指向新目录即可。
///
/// 如果 zip 里已经是 `instance.json`（比如从别的安卓端传来的），就只更新 id/game_dir。
fn convert_metadata(inst_dir: &std::path::Path, new_id: &str) -> Result<(), String> {
    let qkx_path = inst_dir.join("qookix.json");
    let inst_json_path = inst_dir.join("instance.json");

    // 优先从 qookix.json 读；没有就尝试 instance.json
    let (src_path, had_qkx) = if qkx_path.exists() {
        (qkx_path.clone(), true)
    } else if inst_json_path.exists() {
        (inst_json_path.clone(), false)
    } else {
        return Err("zip 里找不到 qookix.json 或 instance.json".to_string());
    };

    let content =
        std::fs::read_to_string(&src_path).map_err(|e| format!("读元数据失败：{e}"))?;
    let mut profile: crate::models::MinecraftProfile =
        serde_json::from_str(&content).map_err(|e| format!("解析实例元数据失败：{e}"))?;

    // 指向新目录
    profile.id = new_id.to_string();
    profile.game_dir = inst_dir.to_string_lossy().to_string();

    let out = serde_json::to_string_pretty(&profile).map_err(|e| format!("序列化失败：{e}"))?;
    crate::fsutil::write_text_atomic(&inst_json_path, &out)
        .map_err(|e| format!("写 instance.json 失败：{e}"))?;

    // 删掉桌面端的元数据文件，免得留着让人困惑
    if had_qkx {
        let _ = std::fs::remove_file(&qkx_path);
    }
    Ok(())
}

fn emit_error(instance_id: &str, instance_name: &str, message: &str) {
    crate::progress::push(
        "lan://progress",
        serde_json::json!({
            "phase": "error",
            "instanceId": instance_id,
            "instanceName": instance_name,
            "ok": false,
            "message": message,
        }),
    );
}
