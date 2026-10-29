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

// 上传链路（手机 → 电脑）拆到单独文件，见 lan_sync_upload.rs
pub(crate) use crate::lan_sync_upload::upload_instance;

/// 桌面端 `GET /api/info` 返回的设备信息。
///
/// ⚠️ 每个字段都要能吃 camelCase 且**缺了也不能整条解析失败**（2026-10-30）：
/// `docs/lan-sync.md` 写的是「snake_case 与 camelCase 都能吃」，但原来只有
/// `mc_version` / `loader_version` 加了 alias，`instance_count` 没有 ——
/// 桌面端只要返回 `instanceCount`，这里就 `missing field` 报错，
/// 界面表现为「连不上」，用户完全看不出是字段名对不上。
#[derive(Debug, Serialize, Deserialize)]
pub struct LanDeviceInfo {
    #[serde(alias = "deviceName", alias = "name")]
    pub device: String,
    #[serde(default)]
    pub version: String,
    #[serde(alias = "instanceCount", default)]
    pub instance_count: usize,
}

/// 桌面端 `GET /api/instances` 返回的单条实例摘要。
#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct LanInstanceSummary {
    pub id: String,
    pub name: String,
    #[serde(rename = "mc_version", alias = "mcVersion", default)]
    pub mc_version: String,
    #[serde(default)]
    pub loader: String,
    #[serde(rename = "loader_version", alias = "loaderVersion", default)]
    pub loader_version: Option<String>,
    /// 同上：没有就按 0 显示，不让一个缺字段废掉整张列表
    #[serde(alias = "sizeBytes", default)]
    pub size_bytes: u64,
    #[serde(alias = "hasSaves", default)]
    pub has_saves: bool,
    #[serde(alias = "hasMods", default)]
    pub has_mods: bool,
}

// ── 内部工具 ─────────────────────────────────────────────────────────────

pub(crate) fn client() -> reqwest::Client {
    reqwest::Client::builder()
        // 大存档可能传几分钟；下载本身靠事件推进度，不靠这个超时判死。
        .timeout(std::time::Duration::from_secs(600))
        // ⚠️ 连接阶段必须单独限时（2026-10-30）：原来只有整体 600 秒，
        // 电脑没起服务 / IP 填错时 TCP 要等系统超时（可达 2 分钟），
        // 用户看到的就是「点了连接，一直转圈，没反应」。
        .connect_timeout(std::time::Duration::from_secs(8))
        .build()
        .unwrap_or_else(|_| reqwest::Client::new())
}

pub(crate) fn endpoint(host: &str, port: u16, path: &str) -> String {
    format!("http://{host}:{port}{path}")
}

/// 简单校验 host/port/remote_id，防注入到 URL。
///
/// 顺便容忍「host 里已经带了端口」这种输入（用户手填 `192.168.1.5:8765`，
/// 或桌面端二维码把端口拼进 host）：这种情况直接拒绝，报出来的
/// `http://192.168.1.5:8765:8765/api/info` 谁都看不懂。
pub(crate) fn validate_host(host: &str) -> Result<(), String> {
    if host.is_empty() || host.contains('/') || host.contains(' ') {
        return Err(format!("非法 host：{host}"));
    }
    if host.matches(':').count() == 1 {
        return Err(format!(
            "host 里不要带端口（填 IP，端口另外填）：{host}"
        ));
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
    // ⚠️ 2026-10-28（真机：下载请求失败 error sending request）：
    // 列表能拉出来、下载却连不上 —— 说明不是网络不通，而是**这一次连接**被拒/超时。
    // 常见原因是 PC 端在打包 zip（几十秒），期间不再 accept 新连接。
    //
    // 所以重试 3 次（0.5s / 1s / 1.5s 退避）；仍失败才报错，并且错误文案区分
    // 「连不上电脑」和「电脑返回错误」—— 前者让用户去看防火墙/PC 是否还在跑。
    let url = endpoint(host, port, &format!("/api/instances/{remote_id}"));
    let mut last_err = String::new();
    let mut resp = None;
    for attempt in 0..3u32 {
        if attempt > 0 {
            let wait = std::time::Duration::from_millis(500 * attempt as u64);
            crate::util::log_line(&format!(
                "[lan] 下载连接失败，{}ms 后重试（第 {} 次）：{last_err}",
                wait.as_millis(),
                attempt + 1
            ));
            tokio::time::sleep(wait).await;
        }
        match client().get(&url).bearer_auth(token).send().await {
            Ok(r) => {
                resp = Some(r);
                break;
            }
            Err(e) => last_err = e.to_string(),
        }
    }
    let resp = resp.ok_or_else(|| {
        format!(
            "连不上电脑（{host}:{port}），已重试 3 次。\n\
             请确认电脑上的 QookiX 还在运行、没有正在打包大文件，并检查防火墙是否放行。\n\
             原始错误：{last_err}"
        )
    })?;
    if !resp.status().is_success() {
        let status = resp.status();
        // ⚠️ 必须把 body 读出来（2026-10-30）：桌面端 500 的 body 是
        // 「Pack error: 读文件失败: ...」，只显示 HTTP 500 的话，
        // 用户和我们都不知道到底是哪个文件出了事。
        let body = resp.text().await.unwrap_or_default();
        let detail = body.trim();
        let detail = if detail.len() > 300 {
            format!("{}…", &detail[..300])
        } else {
            detail.to_string()
        };
        return Err(if detail.is_empty() {
            format!("电脑返回错误：HTTP {status}（接口 {url}）")
        } else {
            format!("电脑返回错误：HTTP {status} —— {detail}")
        });
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
    // 速度用「两次推送之间的增量 / 间隔」算，再做一次指数平滑。
    // 直接拿瞬时值会让数字疯狂跳动（250ms 的窗口里抖动很大）。
    let mut last_bytes: u64 = 0;
    let mut speed_bps: f64 = 0.0;

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("下载中断：{e}"))?;
        file.write_all(&chunk)
            .await
            .map_err(|e| format!("写盘失败：{e}"))?;
        done += chunk.len() as u64;

        // 每 250ms 推一次进度，别刷爆事件通道（Kotlin 那侧要切主线程分发）
        let elapsed = last_emit.elapsed();
        if elapsed > std::time::Duration::from_millis(250) {
            let instant = (done - last_bytes) as f64 / elapsed.as_secs_f64().max(0.001);
            speed_bps = if speed_bps <= 0.0 {
                instant
            } else {
                // 0.6 旧值 + 0.4 新值：既跟得上变化，又不至于每 250ms 跳一下
                speed_bps * 0.6 + instant * 0.4
            };
            last_bytes = done;
            crate::progress::push(
                "lan://progress",
                serde_json::json!({
                    "phase": "download",
                    "instanceId": new_id,
                    "instanceName": instance_name,
                    "bytesDone": done,
                    "bytesTotal": total,
                    // 字节/秒。前端自己算 ETA（total 为 0 时也能显示"已传 X · Y/s"）
                    "speedBps": speed_bps.round() as u64,
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

    // 2026-10-29（审计 B13）：解压预算（条目数 / 单文件 / 总量），
    // 防对端给一个解压后几十 GB 的包把手机存储写满。
    let mut budget = crate::zip_guard::ZipBudget::new();

    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("zip 条目 {i} 失败：{e}"))?;
        let outpath = match entry.enclosed_name() {
            Some(p) => dest.join(p),
            None => continue, // 不安全的路径（含 .. / 绝对路径）跳过
        };
        budget.admit(&outpath.to_string_lossy(), entry.size())?;
        if entry.is_dir() {
            std::fs::create_dir_all(&outpath).map_err(|e| format!("建目录失败：{e}"))?;
        } else {
            if let Some(parent) = outpath.parent() {
                std::fs::create_dir_all(parent).map_err(|e| format!("建父目录失败：{e}"))?;
            }
            let mut outfile =
                std::fs::File::create(&outpath).map_err(|e| format!("建文件失败：{e}"))?;
            let n = std::io::copy(&mut entry, &mut outfile)
                .map_err(|e| format!("解压写失败：{e}"))?;
            // 按**实际写出**的字节再记一次：zip 头里的大小可以伪造
            budget.add_written(n)?;
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
/// 把桌面端的 `qookix.json` 洗成安卓 `MinecraftProfile` 能吃的样子。
///
/// ⚠️ 2026-10-30（真机：`invalid type: null, expected a string at line 17`）：
/// 桌面端元数据里 `java_path` / `game_dir` 这些字段可能是 **null 或干脆没有**，
/// 而安卓端把它们定义成 `String`（不是 `Option<String>`）—— 直接
/// `from_str::<MinecraftProfile>()` 会整条失败，报错信息还只说「第 17 行」。
/// 手工逐字段映射太容易漏（结构体有二十多个字段），所以这里只做「补默认值」，
/// 让 serde 继续负责剩下的解析。
fn normalize_desktop_meta(v: &mut serde_json::Value, inst_dir: &std::path::Path, new_id: &str) {
    let Some(obj) = v.as_object_mut() else {
        return;
    };
    let dir = inst_dir.to_string_lossy().to_string();

    fn ensure_str(obj: &mut serde_json::Map<String, serde_json::Value>, key: &str, fallback: &str) {
        let ok = matches!(obj.get(key), Some(serde_json::Value::String(_)));
        if !ok {
            obj.insert(key.to_string(), serde_json::Value::String(fallback.to_string()));
        }
    }
    fn ensure_i64(obj: &mut serde_json::Map<String, serde_json::Value>, key: &str, fallback: i64) {
        let ok = matches!(obj.get(key), Some(serde_json::Value::Number(_)));
        if !ok {
            obj.insert(key.to_string(), serde_json::json!(fallback));
        }
    }
    fn ensure_array(obj: &mut serde_json::Map<String, serde_json::Value>, key: &str) {
        let ok = matches!(obj.get(key), Some(serde_json::Value::Array(_)));
        if !ok {
            obj.insert(key.to_string(), serde_json::json!([]));
        }
    }

    ensure_str(obj, "id", new_id);
    ensure_str(obj, "name", "未命名实例");
    ensure_str(obj, "mc_version", "");
    ensure_str(obj, "game_dir", &dir);
    // 关键：桌面端没设过 Java 路径时这里是 null
    ensure_str(obj, "java_path", "");
    ensure_str(obj, "loader", "vanilla");
    ensure_i64(
        obj,
        "created",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0),
    );
    ensure_i64(obj, "total_play_time", 0);
    ensure_array(obj, "mods");
    ensure_array(obj, "resource_packs");
    ensure_array(obj, "shaders");
}

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
    let mut v: serde_json::Value =
        serde_json::from_str(&content).map_err(|e| format!("解析实例元数据失败：{e}"))?;
    normalize_desktop_meta(&mut v, inst_dir, new_id);
    let mut profile: crate::models::MinecraftProfile =
        serde_json::from_value(v).map_err(|e| format!("解析实例元数据失败：{e}"))?;

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
#[cfg(test)]
mod tests {
    use super::*;
    // 上传链路的打包函数已拆到 lan_sync_upload.rs
    use crate::lan_sync_upload::{build_instance_zip, read_upload_meta};

    /// 桌面端元数据里 `java_path` / `game_dir` 是 null 或缺失时，
    /// 不能直接 from_str::<MinecraftProfile>（真机踩过：invalid type: null）
    #[test]
    fn normalize_desktop_meta_survives_nulls() {
        let mut v = serde_json::json!({
            "id": "c3f314a0",
            "name": "DeceasedCraft",
            "mc_version": "1.20.1",
            "loader": "forge",
            "loader_version": "8.0.4",
            "created": 1788965076,
            "last_played": 1789141417,
            "total_play_time": 595,
            "installed": true,
            "icon": "bg:blue",
            "alias": null,
            "max_memory_mb": null,
            "memory_mode": null,
            "jvm_args": null,
            "game_args": null,
            "java_path": null,
            "account_id": null,
            "resolution": null,
            "mods": [],
            "resource_packs": [],
            "shaders": [],
            "is_symlink": true,
            "source_path": "E:\\\\.minecraft",
            "group": null
        });
        let dir = std::path::Path::new("/tmp/inst");
        normalize_desktop_meta(&mut v, dir, "new-id");
        let p: crate::models::MinecraftProfile = serde_json::from_value(v)
            .expect("洗过之后必须能解析成安卓实例元数据");
        assert_eq!(p.java_dir, "", "java_path 为 null 时要落成空串");
        assert_eq!(p.game_dir, "/tmp/inst");
        assert_eq!(p.name, "DeceasedCraft");
    }

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
