//! 局域网直传同步：手机 → 电脑（上传）那条链路。
//!
//! 从 `lan_sync.rs` 拆出来（2026-10-30，体量闸门：原文件已顶到预算）。
//! 下载 / 连接相关仍在 `lan_sync.rs`，这里只放「打包本地实例并发给电脑」。

use std::io::Write;
use std::path::Path as StdPath;

use crate::lan_sync::{client, endpoint, validate_host};

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
pub(crate) struct UploadMeta {
    pub(crate) name: String,
    pub(crate) mc_version: String,
    pub(crate) loader: String,
    pub(crate) loader_version: String,
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
pub(crate) fn read_upload_meta(inst_dir: &std::path::Path) -> Result<UploadMeta, String> {
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
pub(crate) fn build_instance_zip(
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
pub(crate) async fn do_upload(
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
            // 桌面端还没实现 `POST /api/instances/upload`（见 docs/lan-sync.md §2），
            // 这时它会回 404/405 —— 必须说人话，否则用户对着「HTTP 405」无从下手
            (404 | 405, _) => {
                "电脑端这个版本还不支持接收实例，请升级桌面端后再试".to_string()
            }
            (code, _) if (500..600).contains(&code) => {
                format!("电脑端处理失败（HTTP {code}），请查看桌面端日志")
            }
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
