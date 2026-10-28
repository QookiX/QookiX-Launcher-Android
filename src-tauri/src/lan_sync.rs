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
//!
//! ## 方向
//!
//! 双向：
//! * **电脑 → 手机**：[`connect`] / [`list_instances`] / [`download_instance`]（GET）
//! * **手机 → 电脑**：[`upload_instance`]（把本地实例打成 zip 后
//!   `POST /api/instances/upload`）
//!
//! 协议（含桌面端收到后要做的事、状态码约定）见 `docs/lan-sync.md`。

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

// ── 上传（手机 → 电脑）──────────────────────────────────────────────────

/// 打包实例时要跳过的目录。
///
/// 共享游戏文件（`libraries` / `versions` / `assets`）**不能**跟着实例传：
/// 它们由桌面端按 `mc_version` + `loader` 自己下载，跟着传等于每次多传几百 MB，
/// 而且可能和桌面端已有版本冲突。日志/崩溃报告/截图同理，纯垃圾。
const UPLOAD_SKIP_DIRS: &[&str] = &[
    "logs",
    "crash-reports",
    "screenshots",
    "natives",
    "libraries",
    "versions",
    "assets",
];

/// 上传前要提交的实例元数据。
struct UploadMeta {
    name: String,
    mc_version: String,
    loader: String,
    loader_version: String,
}

/// `POST /api/instances/upload` —— 把本地实例打包后传给电脑。
///
/// 返回桌面端给出的新实例 id（可能为空串：老服务端不回这个字段）。
///
/// 进度经 `lan://progress` 推给前端，`direction = "upload"`：
/// `phase` 依次是 `pack`（打包，`files` 是已打包文件数）/ `upload`
///（`bytesDone` / `bytesTotal`）/ `done` / `error`，与下载共用同一套字段。
pub async fn upload_instance(
    host: &str,
    port: u16,
    token: &str,
    instance_id: &str,
) -> Result<String, String> {
    validate_host(host)?;
    if instance_id.is_empty() || instance_id.contains('/') || instance_id.contains('\\') {
        return Err(format!("非法实例 id：{instance_id}"));
    }

    let inst_dir = crate::settings::instance_dir(instance_id)
        .await
        .map_err(|e| format!("无法定位实例目录：{e}"))?;
    if !inst_dir.is_dir() {
        return Err("实例目录不存在".to_string());
    }

    let meta = read_upload_meta(&inst_dir)?;
    let display_name = meta.name.clone();

    // 1. 打包（阻塞 IO，别卡住 tokio 的 worker）
    let tmp_zip = inst_dir.join(".lan_sync.upload.zip");
    let _ = std::fs::remove_file(&tmp_zip);
    let zip_for_task = tmp_zip.clone();
    let dir_for_task = inst_dir.clone();
    let name_for_task = display_name.clone();
    // spawn_blocking 要求 'static：先把 &str 的 id 拷成 String 再 move 进去
    let id_for_task = instance_id.to_string();
    let packed = tokio::task::spawn_blocking(move || {
        build_instance_zip(&dir_for_task, &zip_for_task, &id_for_task, &name_for_task)
    })
    .await
    .map_err(|e| format!("打包线程失败：{e}"))?;

    let size = match packed {
        Ok(s) => s,
        Err(e) => {
            let _ = std::fs::remove_file(&tmp_zip);
            emit_upload_error(instance_id, &display_name, &e);
            return Err(e);
        }
    };

    // 2. 上传
    let result = do_upload(host, port, token, &tmp_zip, size, instance_id, &display_name, &meta).await;
    let _ = std::fs::remove_file(&tmp_zip);

    match result {
        Ok(new_id) => {
            crate::progress::push(
                "lan://progress",
                serde_json::json!({
                    "phase": "done",
                    "direction": "upload",
                    "instanceId": instance_id,
                    "instanceName": display_name,
                    "remoteId": new_id,
                    "ok": true,
                }),
            );
            Ok(new_id)
        }
        Err(e) => {
            emit_upload_error(instance_id, &display_name, &e);
            Err(e)
        }
    }
}

fn emit_upload_error(instance_id: &str, instance_name: &str, message: &str) {
    crate::progress::push(
        "lan://progress",
        serde_json::json!({
            "phase": "error",
            "direction": "upload",
            "instanceId": instance_id,
            "instanceName": instance_name,
            "ok": false,
            "message": message,
        }),
    );
}

/// 读 `instance.json`，抽出上传需要的四个字段。
///
/// 用 `serde_json::Value` 而不是 `MinecraftProfile`：这里只要几个字符串，
/// 没必要为了上传把整个结构体（含一堆 `Option` / 枚举）反序列化一遍。
fn read_upload_meta(inst_dir: &std::path::Path) -> Result<UploadMeta, String> {
    let path = inst_dir.join("instance.json");
    let text = std::fs::read_to_string(&path).map_err(|e| format!("读实例元数据失败：{e}"))?;
    let v: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| format!("解析实例元数据失败：{e}"))?;
    let s = |keys: &[&str]| -> String {
        keys.iter()
            .find_map(|k| v.get(k).and_then(|x| x.as_str()))
            .unwrap_or("")
            .to_string()
    };
    let name = s(&["name"]).trim().to_string();
    Ok(UploadMeta {
        name: if name.is_empty() { "未命名实例".to_string() } else { name },
        mc_version: s(&["mc_version", "mcVersion"]),
        loader: {
            let l = s(&["loader"]);
            if l.is_empty() { "vanilla".to_string() } else { l }
        },
        loader_version: s(&["loader_version", "loaderVersion"]),
    })
}

/// 把实例目录打成 zip，返回 zip 字节数。
///
/// 规则见模块文档与 `docs/lan-sync.md` 2.2：跳过共享游戏文件与运行时垃圾。
fn build_instance_zip(
    inst_dir: &std::path::Path,
    zip_path: &std::path::Path,
    instance_id: &str,
    instance_name: &str,
) -> Result<u64, String> {
    let file = std::fs::File::create(zip_path).map_err(|e| format!("创建 zip 失败：{e}"))?;
    let mut zw = zip::ZipWriter::new(file);
    let opts = zip::write::FileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated);

    let mut stack = vec![inst_dir.to_path_buf()];
    let mut files: u32 = 0;
    let mut last_emit = std::time::Instant::now();

    while let Some(dir) = stack.pop() {
        let entries = std::fs::read_dir(&dir).map_err(|e| format!("遍历实例目录失败：{e}"))?;
        for entry in entries.flatten() {
            let path = entry.path();
            let file_name = entry.file_name().to_string_lossy().to_string();
            if path.is_dir() {
                if UPLOAD_SKIP_DIRS.iter().any(|d| *d == file_name) {
                    continue;
                }
                stack.push(path);
                continue;
            }
            // 半成品 zip 自己不能进包
            if file_name.starts_with(".lan_sync.") {
                continue;
            }
            let rel = match path.strip_prefix(inst_dir) {
                Ok(r) => r.to_string_lossy().replace('\\', "/"),
                Err(_) => continue,
            };
            zw.start_file(rel, opts)
                .map_err(|e| format!("写入 zip 失败：{e}"))?;
            let mut f = std::fs::File::open(&path)
                .map_err(|e| format!("读取文件失败（{}）：{e}", path.display()))?;
            std::io::copy(&mut f, &mut zw).map_err(|e| format!("压缩失败：{e}"))?;
            files += 1;

            if last_emit.elapsed() > std::time::Duration::from_millis(250) {
                crate::progress::push(
                    "lan://progress",
                    serde_json::json!({
                        "phase": "pack",
                        "direction": "upload",
                        "instanceId": instance_id,
                        "instanceName": instance_name,
                        "files": files,
                    }),
                );
                last_emit = std::time::Instant::now();
            }
        }
    }

    zw.finish().map_err(|e| format!("收尾 zip 失败：{e}"))?;
    std::fs::metadata(zip_path)
        .map(|m| m.len())
        .map_err(|e| format!("读取 zip 大小失败：{e}"))
}

/// 真正发请求：流式 POST，边传边推 `phase = upload` 的字节进度。
#[allow(clippy::too_many_arguments)]
async fn do_upload(
    host: &str,
    port: u16,
    token: &str,
    zip_path: &std::path::Path,
    size: u64,
    instance_id: &str,
    instance_name: &str,
    meta: &UploadMeta,
) -> Result<String, String> {
    let file = tokio::fs::File::open(zip_path)
        .await
        .map_err(|e| format!("打开 zip 失败：{e}"))?;
    let reader = tokio::io::BufReader::new(file);

    let id = instance_id.to_string();
    let name = instance_name.to_string();
    use tokio::io::AsyncReadExt;
    // 每读一块就推一次进度（250ms 节流）—— 与下载共用同一套事件字段
    let stream = futures::stream::unfold(
        (reader, 0u64, std::time::Instant::now()),
        move |(mut r, mut done, mut last)| {
            let id = id.clone();
            let name = name.clone();
            async move {
                let mut buf = vec![0u8; 256 * 1024];
                match r.read(&mut buf).await {
                    Ok(0) => None,
                    Ok(n) => {
                        buf.truncate(n);
                        done += n as u64;
                        if last.elapsed() > std::time::Duration::from_millis(250) {
                            crate::progress::push(
                                "lan://progress",
                                serde_json::json!({
                                    "phase": "upload",
                                    "direction": "upload",
                                    "instanceId": id,
                                    "instanceName": name,
                                    "bytesDone": done,
                                    "bytesTotal": size,
                                }),
                            );
                            last = std::time::Instant::now();
                        }
                        Some((Ok::<Vec<u8>, std::io::Error>(buf), (r, done, last)))
                    }
                    Err(e) => Some((Err(e), (r, done, last))),
                }
            }
        },
    );

    let resp = client()
        .post(endpoint(host, port, "/api/instances/upload"))
        .bearer_auth(token)
        .query(&[
            ("name", meta.name.as_str()),
            ("mc_version", meta.mc_version.as_str()),
            ("loader", meta.loader.as_str()),
            ("loader_version", meta.loader_version.as_str()),
        ])
        .header(reqwest::header::CONTENT_TYPE, "application/zip")
        // 流式 body 没有已知长度，必须显式给 —— 桌面端要靠它算进度/预检空间
        .header(reqwest::header::CONTENT_LENGTH, size)
        .body(reqwest::Body::wrap_stream(stream))
        .send()
        .await
        .map_err(|e| format!("上传失败：{e}"))?;

    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();

    if !status.is_success() {
        let server_msg = serde_json::from_str::<serde_json::Value>(&body)
            .ok()
            .and_then(|v| {
                v.get("error")
                    .and_then(|e| e.as_str())
                    .map(|s| s.to_string())
            })
            .filter(|s| !s.is_empty());
        return Err(match (status.as_u16(), server_msg) {
            (401, _) => "电脑端拒绝了令牌，请重新扫码配对".to_string(),
            (409, Some(m)) => format!("电脑上已有同名实例（{m}），请改个名字再传"),
            (413, Some(m)) => format!("实例太大，电脑端不收：{m}"),
            (_, Some(m)) => m,
            (code, None) => format!("上传失败：HTTP {code}"),
        });
    }

    Ok(serde_json::from_str::<serde_json::Value>(&body)
        .ok()
        .and_then(|v| {
            v.get("instanceId")
                .and_then(|x| x.as_str())
                .map(|s| s.to_string())
        })
        .unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 打包必须跳过 logs / 共享游戏文件，其余照传。
    #[test]
    fn build_zip_skips_shared_and_junk_dirs() {
        let root = std::env::temp_dir().join(format!("qk-lan-zip-{}", uuid::Uuid::new_v4()));
        let inst = root.join("inst");
        std::fs::create_dir_all(inst.join("mods")).unwrap();
        std::fs::create_dir_all(inst.join("logs")).unwrap();
        std::fs::create_dir_all(inst.join("libraries")).unwrap();
        std::fs::create_dir_all(inst.join("versions")).unwrap();
        std::fs::create_dir_all(inst.join("saves").join("world")).unwrap();
        std::fs::write(inst.join("instance.json"), "{}").unwrap();
        std::fs::write(inst.join("options.txt"), "fov:0.5\n").unwrap();
        std::fs::write(inst.join("mods").join("a.jar"), b"AAA").unwrap();
        std::fs::write(inst.join("saves").join("world").join("level.dat"), b"LVL").unwrap();
        std::fs::write(inst.join("logs").join("latest.log"), b"LOG").unwrap();
        std::fs::write(inst.join("libraries").join("x.jar"), b"LIB").unwrap();
        std::fs::write(inst.join("versions").join("1.21.1.jar"), b"JAR").unwrap();

        let zip_path = root.join("out.zip");
        let size = build_instance_zip(&inst, &zip_path, "inst-id", "测试实例").unwrap();
        assert!(size > 0, "zip 不该是空的");

        let f = std::fs::File::open(&zip_path).unwrap();
        let mut ar = zip::ZipArchive::new(f).unwrap();
        let names: Vec<String> = (0..ar.len())
            .map(|i| ar.by_index(i).unwrap().name().to_string())
            .collect();

        assert!(names.iter().any(|n| n == "instance.json"));
        assert!(names.iter().any(|n| n == "options.txt"));
        assert!(names.iter().any(|n| n == "mods/a.jar"));
        // 子目录要保留（存档是实例的核心资产）
        assert!(names.iter().any(|n| n == "saves/world/level.dat"), "{names:?}");
        // 垃圾与共享文件一律不进包
        assert!(!names.iter().any(|n| n.starts_with("logs/")), "{names:?}");
        assert!(!names.iter().any(|n| n.starts_with("libraries/")), "{names:?}");
        assert!(!names.iter().any(|n| n.starts_with("versions/")), "{names:?}");

        let _ = std::fs::remove_dir_all(&root);
    }

    /// 元数据读不到就明确失败，不要传一个空名字上去。
    #[test]
    fn read_upload_meta_reads_android_instance_json() {
        let root = std::env::temp_dir().join(format!("qk-lan-meta-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&root).unwrap();
        std::fs::write(
            root.join("instance.json"),
            r#"{"id":"x","name":"月落花野（1.21.1）","mc_version":"1.21.1",
                "loader":"neoforge","loader_version":"21.1.251","game_dir":"/tmp/x"}"#,
        )
        .unwrap();

        let meta = read_upload_meta(&root).unwrap();
        assert_eq!(meta.name, "月落花野（1.21.1）");
        assert_eq!(meta.mc_version, "1.21.1");
        assert_eq!(meta.loader, "neoforge");
        assert_eq!(meta.loader_version, "21.1.251");

        let _ = std::fs::remove_dir_all(&root);
    }
}
