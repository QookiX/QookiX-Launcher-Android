//! 托管服务器（多人游戏开服）—— 配置层。
//!
//! 与桌面版 `QookiX-Launcher/src-tauri/src/servers.rs` 保持同一套数据格式：
//! 每个服务器一个目录 `servers/<id>/`，里面放 `server.json` + 服务端文件。
//! 这样两端通用，用户也能手动改配置。
//!
//! 存储格式与前端契约（`src/api.ts` 的 12 个 `*HostedServer*` 命令）严格对齐。

use crate::models::{ServerConfig, ServerCore, ServerEntry, ServerStatus};
use std::path::{Path, PathBuf};

// 列表与根目录定位已拆到 `server_list`（Q2 体量棘轮）；这里保留再导出，
// 调用方仍写 `crate::servers::list_servers()`，不用到处改路径。
pub use crate::server_list::{list_hosted_servers, list_servers};

pub fn server_dir(id: &str) -> Result<PathBuf, String> {
    validate_server_id(id)?;
    let root = crate::server_list::servers_root()
        .ok_or_else(|| "数据目录不可用".to_string())?;
    Ok(root.join(id))
}

fn server_meta_path(id: &str) -> Result<PathBuf, String> {
    Ok(server_dir(id)?.join("server.json"))
}

/// 服务器 id 来自前端，必须是安全文件名（防目录穿越）
pub fn validate_server_id(id: &str) -> Result<(), String> {
    if id.is_empty() || id.len() > 64 {
        return Err("服务器 ID 不合法".into());
    }
    if !id
        .chars()
        .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        return Err("服务器 ID 含有非法字符".into());
    }
    Ok(())
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn get_server(id: &str) -> Result<ServerConfig, String> {
    let path = server_meta_path(id)?;
    let text =
        std::fs::read_to_string(&path).map_err(|_| format!("服务器 {id} 不存在"))?;
    serde_json::from_str(&text).map_err(|e| format!("服务器数据损坏: {e}"))
}

pub fn save_server(s: &ServerConfig) -> Result<(), String> {
    let path = server_meta_path(&s.id)?;
    if let Some(p) = path.parent() {
        std::fs::create_dir_all(p).map_err(|e| e.to_string())?;
    }
    // 运行期字段（running / address）不落盘，见 `persisted_json` 的说明。
    let json = crate::server_list::persisted_json(s).map_err(|e| e.to_string())?;
    // 原子写：server.json 撕裂会让该服务器从列表里静默消失。
    crate::fsutil::write_text_atomic(&path, &json).map_err(|e| e.to_string())
}

/**
 * 手机可用内存的 40%（上限 2GB，最少 512MB）。
 *
 * 手机上开服最怕和系统/游戏抢内存导致被 OOM 杀掉，所以默认给得保守；
 * 用户可以在服务器设置里手动改。
 */
pub fn suggest_memory_mb() -> (u32, u32) {
    let total_mb = total_device_memory_mb().unwrap_or(4096);
    let max = (total_mb as f64 * 0.4).round() as u32;
    let max = max.clamp(512, 2048);
    let min = (max / 2).max(256);
    (min, max)
}

/// 读系统总内存（MB）。拿不到就返回 None，调用方走保守默认值。
fn total_device_memory_mb() -> Option<u32> {
    // /proc/meminfo 第一行: MemTotal:  8123456 kB
    let text = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            let kb: u64 = rest.trim().trim_end_matches(" kB").trim().parse().ok()?;
            return Some((kb / 1024) as u32);
        }
    }
    None
}

// ── Tauri 命令 ────────────────────────────────────────────────────────
pub fn get_hosted_server(id: String) -> Result<ServerConfig, String> {
    Ok(crate::server_list::with_runtime(get_server(&id)?, local_wifi_ip().as_deref()))
}

pub fn create_hosted_server(
    name: String,
    core: String,
    mc_version: String,
) -> Result<ServerConfig, String> {
    let core_parsed = ServerCore::parse(&core).ok_or_else(|| "不支持的服务端核心".to_string())?;
    let version = mc_version.trim().to_string();
    if version.is_empty() {
        return Err("请选择游戏版本".into());
    }
    // 名称留空是允许的：前端输入框的提示就是「留空则自动命名」，
    // 这里与 `instances::create_instance` 用同一套约定 —— 兜底成游戏版本号，
    // 否则会建出一个名字为空的服务器，列表里看着像空白项。
    let name = match name.trim() {
        "" => version.clone(),
        given => given.to_string(),
    };
    // id 用时间戳+名随机段，避免重名冲突
    let id = format!("srv_{}_{}", now_secs(), uuid::Uuid::new_v4().simple());
    let (min_mem, max_mem) = suggest_memory_mb();
    let s = ServerConfig {
        id,
        name,
        core: core_parsed,
        mc_version: version,
        port: 25565,
        max_memory_mb: max_mem,
        min_memory_mb: min_mem,
        motd: format!("{} 的服务器", "QookiX"),
        eula: false, // 必须用户自己在界面里同意 EULA
        created: now_secs(),
        last_started: None,
        java_path: None,
        jvm_args: None,
        stop_command: None,
        // 默认 30 分钟：无人在线才触发，不打断正在玩的人（见 models.rs 的说明）。
        sleep_timeout_min: 30,
        sleep_hint: None,
        wake_hint: None,
        max_players: None,
        running: false,
        address: None,
    };
    let dir = server_dir(&s.id)?;
    std::fs::create_dir_all(dir.parent().ok_or("服务器目录无效")?).map_err(|e| e.to_string())?;
    std::fs::create_dir(&dir).map_err(|e| e.to_string())?;
    // 列表以 server.json 为发现标志，基础文件齐全后才发布它。
    if let Err(e) = prepare_server_files(&dir, &s).and_then(|()| save_server(&s)) {
        let _ = std::fs::remove_dir_all(&dir);
        return Err(format!("初始化服务器文件失败: {e}"));
    }
    Ok(s)
}
pub fn update_hosted_server(patch: serde_json::Value) -> Result<ServerConfig, String> {
    let _operation = crate::server_state::try_host_operation()?;
    let id = patch
        .get("id")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "缺少服务器 ID".to_string())?
        .to_string();
    let mut s = get_server(&id)?;
    if let Some(v) = patch.get("name").and_then(|v| v.as_str()) {
        s.name = v.trim().to_string();
    }
    if let Some(v) = patch.get("port").and_then(|v| v.as_u64()) {
        // **必须校验范围**：原来直接 `v as u16` 截断 —— 前端传 70000 会静默变成 4464，
        // 用户以为「端口已改成 70000」，实际服务器监听在 4464，别人根本连不上。
        if !(1..=65535).contains(&v) {
            return Err(format!("端口号必须在 1–65535 之间（收到 {v}）"));
        }
        s.port = v as u16;
    }
    if let Some(v) = patch.get("motd").and_then(|v| v.as_str()) {
        s.motd = v.to_string();
    }
    if let Some(v) = patch.get("eula").and_then(|v| v.as_bool()) {
        s.eula = v;
    }
    if let Some(v) = patch.get("max_memory_mb").and_then(|v| v.as_u64()) {
        s.max_memory_mb = v.clamp(256, 8192) as u32;
    }
    if let Some(v) = patch.get("min_memory_mb").and_then(|v| v.as_u64()) {
        s.min_memory_mb = v.clamp(128, 8192) as u32;
    }
    if let Some(v) = patch.get("jvm_args") {
        s.jvm_args = v.as_str().map(|x| x.to_string());
    }
    // 空闲休眠时长：0 = 关闭，其余按分钟。上限给到 24 小时 —— 再大就等于「永不休眠」了，
    // 那种需求直接选「关」更清楚。
    if let Some(v) = patch.get("sleep_timeout_min").and_then(|v| v.as_u64()) {
        s.sleep_timeout_min = v.min(24 * 60) as u32;
    }
    // 两个提示语是「留空则用界面自带文案」，所以这里必须区分「没传这个键」与「传了空值」：
    // 传 null 或空串都表示清空自定义、回到默认。
    if let Some(v) = patch.get("sleep_hint") {
        s.sleep_hint = v
            .as_str()
            .map(|x| x.trim().to_string())
            .filter(|x| !x.is_empty());
    }
    if let Some(v) = patch.get("wake_hint") {
        s.wake_hint = v
            .as_str()
            .map(|x| x.trim().to_string())
            .filter(|x| !x.is_empty());
    }
    // 关掉休眠功能时，正在休眠的服务器要**解除**休眠：它现在是停着的，
    // 只是靠唤醒监听「随时能被叫起来」。留着监听而设置里写着「关」，等同于
    // 用户以为不会自动启动、实际朋友一连就起来了 —— 状态与预期相反。
    if s.sleep_timeout_min == 0 && crate::sleep::is_sleeping(&id) {
        crate::sleep::disarm(&id);
    }
    // 改设置时正在跑的那台要**立刻**跟着变，不能等下次启动：
    // 巡检只在启动成功时挂一次，用户把「关闭」改成「30 分钟」后如果没有任何反应，
    // 他会以为设置没保存（而界面显示已保存），下次还是得手动重启服务端才生效。
    if is_server_up(&id) {
        if s.sleep_timeout_min == 0 {
            crate::sleep::cancel_supervisor(&id);
        } else {
            crate::sleep::arm_supervisor(&id);
        }
    }
    save_server(&s)?;
    Ok(crate::server_list::with_runtime(s, local_wifi_ip().as_deref()))
}
pub fn delete_hosted_server(id: String) -> Result<(), String> {
    validate_server_id(&id)?;
    let _operation = crate::server_state::try_host_operation()?;
    ensure_server_inactive(&id)?;
    // 先撤掉后台任务：休眠中的服务器有监听占着游戏端口，目录删了、监听还在，
    // 这个端口就被一台「已经不存在的服务器」永久占住，之后新建的服务器再也 bind 不上。
    crate::sleep::disarm(&id);
    let dir = server_dir(&id)?;
    if dir.exists() {
        std::fs::remove_dir_all(&dir).map_err(|e| format!("删除失败: {e}"))?;
    }
    Ok(())
}
pub fn suggest_server_memory() -> serde_json::Value {
    let (min_mb, max_mb) = suggest_memory_mb();
    serde_json::json!({ "minMemoryMb": min_mb, "maxMemoryMb": max_mb })
}

// ── 服务端基础文件 ────────────────────────────────────────────────────

/**
 * 生成服务端最小可运行文件集。
 *
 * 重点是 **eula.txt**：Minecraft EULA 要求运行前必须显式同意，官方服务端
 * 第一次启动会自己退出并提示「You need to agree to the EULA」。
 * 我们预生成一个 false 的文件，让用户在界面上明确勾选后改成 true。
 */
pub fn ensure_server_files(id: &str, s: &ServerConfig) -> Result<(), String> {
    let dir = server_dir(id)?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    prepare_server_files(&dir, s)
}

fn prepare_server_files(dir: &Path, s: &ServerConfig) -> Result<(), String> {
    // EULA 只有一个事实源：server.json。每次启动/安装都按它重写文件，
    // 用户撤回同意后不会再被旧文件里的 true 覆盖回来。
    crate::fsutil::write_text_atomic(&dir.join("eula.txt"), &format!("eula={}\n", s.eula))
        .map_err(|e| format!("写入 EULA 失败: {e}"))?;

    let rcon = crate::rcon::load_or_create_creds(&dir, s.port)?;
    let props = dir.join("server.properties");
    let fresh = !props.exists();
    let mut changes: Vec<(&str, String)> = vec![
        ("motd", s.motd.clone()),
        ("server-port", s.port.to_string()),
        ("enable-rcon", "true".to_string()),
        ("rcon.password", rcon.password.clone()),
        ("rcon.port", rcon.port.to_string()),
        ("broadcast-rcon-to-ops", "false".to_string()),
    ];
    if fresh {
        changes.push(("max-players", "10".to_string()));
        changes.push(("online-mode", "true".to_string()));
        changes.push(("difficulty", "normal".to_string()));
        changes.push(("gamemode", "survival".to_string()));
        changes.push(("level-name", "world".to_string()));
        changes.push(("view-distance", "6".to_string()));
        changes.push(("simulation-distance", "4".to_string()));
        changes.push(("sync-chunk-writes", "false".to_string()));
    }
    merge_server_properties(&dir, &changes)
}

/// 一次读取、一次原子写回：批量改属性不会出现「前几个键生效、后面报错」的
/// 半截状态，也避免两个并发写互相覆盖。
fn merge_server_properties(dir: &Path, changes: &[(&str, String)]) -> Result<(), String> {
    let path = dir.join("server.properties");
    let mut lines: Vec<String> = match std::fs::read_to_string(&path) {
        Ok(text) => text.lines().map(|l| l.to_string()).collect(),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Vec::new(),
        Err(e) => return Err(format!("读取 server.properties 失败（未覆盖原文件）: {e}")),
    };
    for (key, value) in changes {
        let mut replaced = false;
        let mut out = Vec::with_capacity(lines.len() + 1);
        for line in lines.drain(..) {
            let is_key = line
                .trim_start()
                .split_once(|c| c == '=' || c == ':')
                .is_some_and(|(k, _)| k.trim() == *key);
            if is_key {
                if !replaced {
                    out.push(format!("{key}={value}"));
                    replaced = true;
                }
            } else {
                out.push(line);
            }
        }
        if !replaced {
            out.push(format!("{key}={value}"));
        }
        lines = out;
    }
    crate::fsutil::write_text_atomic(&path, &(lines.join("\n") + "\n"))
        .map_err(|e| format!("写入 server.properties 失败: {e}"))
}
pub fn write_server_property(id: &str, key: &str, value: &str) -> Result<(), String> {
    let dir = server_dir(id)?;
    let path = dir.join("server.properties");
    // ⚠️ 读失败必须报错而不是拿空串继续：原来这里 unwrap_or_default()，
    // 文件被占用/权限问题时整份 properties 会被清成只剩刚改的这一个键，
    // 其余服务器设置（motd、难度、视距…）在下次启动全部回落默认值。
    let cur = std::fs::read_to_string(&path)
        .map_err(|e| format!("读取 server.properties 失败（拒绝覆盖写入）：{e}"))?;
    let mut lines: Vec<String> = cur.lines().map(|l| l.to_string()).collect();
    let kv = format!("{key}={value}");
    if let Some(slot) = lines
        .iter_mut()
        .find(|l| l.split('=').next().map(|k| k.trim() == key).unwrap_or(false))
    {
        *slot = kv;
    } else {
        lines.push(kv);
    }
    // 原子写：撕裂的 properties 会让服务端读_config 出错（同 settings.json 的教训）。
    crate::fsutil::write_text_atomic(&path, &lines.join("\n")).map_err(|e| e.to_string())
}

/// 读 `server.properties` 的某个键
pub fn read_server_property(id: &str, key: &str) -> Option<String> {
    let dir = server_dir(id).ok()?;
    let cur = std::fs::read_to_string(dir.join("server.properties")).ok()?;
    cur.lines()
        .find_map(|l| {
            let mut it = l.splitn(2, '=');
            let k = it.next()?.trim();
            let v = it.next().unwrap_or("").trim();
            if k == key {
                Some(v.to_string())
            } else {
                None
            }
        })
}

// ── server.properties 的可编辑项 ──────────────────────────────────────
//
// ## 为什么要有白名单
//
// `server.properties` 有六十多个键，其中不少**写错就让服务端起不来**
// （`level-type`、`level-seed`、`max-world-size`…）；更要紧的是 `enable-rcon` /
// `rcon.password` / `server-port` 这几个是启动器自己要用或已有专门入口的键 ——
// 让设置页能改它们等于把优雅停服（以及端口冲突检查）绕过去了。
// 所以只开放玩家真正会调的这几项，每项都带取值范围。
//
// ## 生效时机
//
// Minecraft **只在启动时读** `server.properties`。所以改完必须重启服务端才生效，
// 界面上要写明这一点（前端文案 `server-detail.props-need-restart`）。
// 唯一的例外是 `motd`：它在每次 ping 时都要读，但我们的 MOTD 走的是重启时重写文件。

/// 一个可编辑项的取值类型与范围
enum PropKind {
    Bool,
    Int { min: i64, max: i64 },
    Choice(&'static [&'static str]),
}

struct PropSpec {
    /// 前端用的名字（camelCase，与 `ServerConfig` 的字段风格一致）
    field: &'static str,
    /// `server.properties` 里的键名
    key: &'static str,
    kind: PropKind,
}

/// 允许设置页读写的项。
fn editable_props() -> &'static [PropSpec] {
    static SPECS: &[PropSpec] = &[
        PropSpec {
            field: "onlineMode",
            key: "online-mode",
            kind: PropKind::Bool,
        },
        PropSpec {
            field: "whiteList",
            key: "white-list",
            kind: PropKind::Bool,
        },
        PropSpec {
            field: "viewDistance",
            key: "view-distance",
            kind: PropKind::Int { min: 3, max: 32 },
        },
        PropSpec {
            field: "simulationDistance",
            key: "simulation-distance",
            kind: PropKind::Int { min: 3, max: 32 },
        },
        PropSpec {
            field: "maxPlayers",
            key: "max-players",
            kind: PropKind::Int { min: 1, max: 100 },
        },
        PropSpec {
            field: "difficulty",
            key: "difficulty",
            kind: PropKind::Choice(&["peaceful", "easy", "normal", "hard"]),
        },
        PropSpec {
            field: "gameMode",
            key: "gamemode",
            kind: PropKind::Choice(&["survival", "creative", "adventure", "spectator"]),
        },
    ];
    SPECS
}

/// 键在文件里缺失时的取值：用 Minecraft 自己那一份默认值。
/// **不能**用「我们生成模板里的值」当默认 —— 老服务器是 Minecraft 自己生成的
/// 完整文件，拿模板值会让界面显示的数和实际跑的数对不上。
fn prop_default(spec: &PropSpec) -> serde_json::Value {
    match spec.kind {
        PropKind::Bool => match spec.key {
            "white-list" => serde_json::json!(false),
            _ => serde_json::json!(true),
        },
        PropKind::Int { .. } => match spec.key {
            "max-players" => serde_json::json!(20),
            // view / simulation distance 的原版默认都是 10
            _ => serde_json::json!(10),
        },
        PropKind::Choice(_) => match spec.key {
            "difficulty" => serde_json::json!("easy"),
            _ => serde_json::json!("survival"),
        },
    }
}

/// 把文件里读到的字符串按类型还原成 JSON 值。认不出来就退回默认值 ——
/// 文件被手改坏时界面不该整片报错，显示默认值 + 用户一保存就写回规范值。
fn prop_value(spec: &PropSpec, raw: &str) -> serde_json::Value {
    let v = raw.trim();
    match spec.kind {
        PropKind::Bool => serde_json::json!(v.eq_ignore_ascii_case("true")),
        PropKind::Int { .. } => v
            .parse::<i64>()
            .map(|n| serde_json::json!(n))
            .unwrap_or_else(|_| prop_default(spec)),
        PropKind::Choice(allowed) => {
            let low = v.to_ascii_lowercase();
            if allowed.contains(&low.as_str()) {
                serde_json::json!(low)
            } else {
                prop_default(spec)
            }
        }
    }
}

/// 读设置页要用的那几项 `server.properties`。
pub fn get_hosted_server_properties(id: String) -> Result<serde_json::Value, String> {
    validate_server_id(&id)?;
    server_dir(&id)?;
    let mut out = serde_json::Map::new();
    for spec in editable_props() {
        let val = match read_server_property(&id, spec.key) {
            Some(raw) => prop_value(spec, &raw),
            None => prop_default(spec),
        };
        out.insert(spec.field.to_string(), val);
    }
    Ok(serde_json::Value::Object(out))
}

/// 写设置页来的那几项。只认白名单里的键，逐项校验后再落盘。
/// 返回写入后的完整值，前端直接用返回值刷新界面（避免「界面和文件不一致」）。
pub fn set_hosted_server_properties(
    id: String,
    patch: serde_json::Value,
) -> Result<serde_json::Value, String> {
    validate_server_id(&id)?;
    let obj = patch
        .as_object()
        .ok_or_else(|| "参数必须是对象".to_string())?;
    // 先整批校验、再整批写盘：只要有一项非法就整体拒绝，
    // 避免「前面的项已写进文件、后面的项报错」这种半截写入。
    let mut pending: Vec<(&'static str, String)> = Vec::new();
    for spec in editable_props() {
        let Some(v) = obj.get(spec.field) else {
            continue;
        };
        let text = match &spec.kind {
            PropKind::Bool => match v.as_bool() {
                Some(b) => if b { "true" } else { "false" }.to_string(),
                None => return Err(format!("{} 只能填 true / false", spec.field)),
            },
            PropKind::Int { min, max } => {
                let n = v
                    .as_i64()
                    .ok_or_else(|| format!("{} 需要一个整数", spec.field))?;
                if n < *min || n > *max {
                    return Err(format!(
                        "{} 需要在 {min}–{max} 之间（收到 {n}）",
                        spec.field
                    ));
                }
                n.to_string()
            }
            PropKind::Choice(allowed) => {
                let s = v.as_str().unwrap_or("").trim().to_ascii_lowercase();
                if !allowed.contains(&s.as_str()) {
                    return Err(format!("{} 只能填 {}", spec.field, allowed.join(" / ")));
                }
                s
            }
        };
        pending.push((spec.key, text));
    }
    merge_server_properties(&server_dir(&id)?, &pending)?;
    get_hosted_server_properties(id)
}

/// 服务端目录里某个子目录是否存在（给 ServerFileManager 的「打开目录」用）
pub fn server_sub_dir(id: &str, sub: &str) -> Result<PathBuf, String> {
    let dir = server_dir(id)?;
    if sub.is_empty() || sub == "." {
        return Ok(dir);
    }
    // 只允许单层目录名，防穿越
    if sub.contains('/') || sub.contains('\\') || sub.contains("..") {
        return Err("非法路径".into());
    }
    let p = dir.join(sub);
    std::fs::create_dir_all(&p).map_err(|e| e.to_string())?;
    Ok(p)
}

/// 服务器 jar 的实际文件名（Fabric 的启动器 jar 名字带版本号）
pub fn launch_jar_name(dir: &Path, core: ServerCore) -> String {
    if core == ServerCore::Fabric {
        if let Ok(rd) = std::fs::read_dir(dir) {
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if name.starts_with("fabric-server-launch") && name.ends_with(".jar") {
                    return name;
                }
            }
        }
    }
    "server.jar".to_string()
}

// ── 局域网「别人的服务器」列表与 ping（沿用原模块，改名避免与上面的 list_servers 冲突）──
use serde_json::Value;
use std::io::Read;
use tokio::fs;

/// 读取某个实例的多人服务器列表（servers.json 优先，回退到 servers.dat）
pub async fn list_remote_servers(instance_id: &str) -> Result<Vec<ServerEntry>, String> {
    crate::fsutil::validate_id(instance_id, "实例")?;
    let dir = crate::settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())?;

    // 现代 Minecraft (1.20.5+) 使用 servers.json
    let json_path = dir.join("servers.json");
    if json_path.is_file() {
        if let Ok(text) = fs::read_to_string(&json_path).await {
            if let Ok(v) = serde_json::from_str::<Value>(&text) {
                if let Some(servers) = v.get("servers").and_then(|x| x.as_array()) {
                    let list: Vec<ServerEntry> = servers
                        .iter()
                        .filter_map(|s| {
                            let name = s.get("name").and_then(|x| x.as_str())?.to_string();
                            let ip = s
                                .get("ip")
                                .and_then(|x| x.as_str())
                                .unwrap_or("")
                                .to_string();
                            if ip.is_empty() {
                                return None;
                            }
                            let icon = s.get("icon").and_then(|x| x.as_str()).map(|x| x.to_string());
                            Some(ServerEntry { name, address: ip, icon })
                        })
                        .collect();
                    return Ok(list);
                }
            }
        }
    }

    // 旧版 Minecraft 使用 servers.dat (GZIP 压缩的 NBT)
    let dat_path = dir.join("servers.dat");
    if dat_path.is_file() {
        if let Ok(bytes) = fs::read(&dat_path).await {
            let raw: Vec<u8> = if bytes.starts_with(&[0x1f, 0x8b]) {
                let mut decompressed = Vec::new();
                match flate2::read::GzDecoder::new(&bytes[..]).read_to_end(&mut decompressed) {
                    Ok(_) => decompressed,
                    Err(_) => bytes,
                }
            } else {
                bytes
            };
            if let Ok(root) = fastnbt::from_bytes::<ServersDat>(&raw) {
                let list: Vec<ServerEntry> = root
                    .servers
                    .into_iter()
                    .filter_map(|s| {
                        if s.ip.trim().is_empty() {
                            return None;
                        }
                        let icon = s.icon.filter(|i| !i.trim().is_empty()).map(|i| {
                            if i.starts_with("data:") {
                                i
                            } else {
                                format!("data:image/png;base64,{}", i)
                            }
                        });
                        Some(ServerEntry { name: s.name, address: s.ip, icon })
                    })
                    .collect();
                return Ok(list);
            }
        }
    }

    Ok(Vec::new())
}

/// 实时 ping 一个服务器（Server List Ping 协议）
pub async fn ping_remote_server(address: &str) -> ServerStatus {
    crate::mcping::ping_server(address).await
}

#[derive(serde::Deserialize)]
struct ServersDat {
    servers: Vec<ServerNbt>,
}

#[derive(serde::Deserialize)]
struct ServerNbt {
    name: String,
    ip: String,
    #[serde(default)]
    icon: Option<String>,
}

// ── 服务端核心下载 ────────────────────────────────────────────────────

/// PaperMC 官方 fill API（v3）。**必须带 User-Agent**，否则 403。
const PAPERMC_API: &str = "https://fill.papermc.io/v3/projects";
const PAPERMC_UA: &str =
    "QookiX-Launcher/1.1.0 (https://github.com/weimosheng/QookiX-Launcher)";

/// Paper 支持的 MC 版本（新→旧，只留正式版）。
///
/// v3 项目接口把版本**按小版本分组**返回：
///   `{"1.21": ["1.21.11", "1.21.11-rc3", …, "1.21"], "1.8": ["1.8.8"], …}`
/// 组间与组内都是新→旧，但 `serde_json` 的 Map 默认按 key 排序 —— 所以这里自己按
/// 版本号数值段重排一遍，免得下拉里出现「1.10 排在 1.9 后面」这种顺序。
///
/// **为什么要这份清单**：Paper 并不支持所有 MC 版本，1.8 那条线只有 `1.8.8`
/// （`1.8.9` 不存在；两者客户端协议相同，1.8.9 的客户端照样能进 1.8.8 服务器）。
/// 拿 1.8.9 去问 builds 接口只会 404，所以创建服务器时用这份清单限制下拉，
/// 从源头上就选不到「没有构建」的版本。
pub async fn list_paper_versions() -> Result<Vec<String>, String> {
    let meta = meta_client().await?;
    let url = format!("{PAPERMC_API}/paper");
    let body = get_json_retry(&meta, &url, 4)
        .await
        .map_err(|e| format!("获取 Paper 版本列表失败: {e}"))?;
    let mut out: Vec<String> = Vec::new();
    if let Some(groups) = body.get("versions").and_then(|v| v.as_object()) {
        for arr in groups.values() {
            let Some(list) = arr.as_array() else { continue };
            for v in list {
                if let Some(s) = v.as_str() {
                    // rc / pre 不是开服该选的版本
                    if !s.contains('-') {
                        out.push(s.to_string());
                    }
                }
            }
        }
    }
    if out.is_empty() {
        return Err("Paper 没有返回任何版本".to_string());
    }
    out.sort_by(|a, b| ver_key(b).cmp(&ver_key(a)));
    out.dedup();
    Ok(out)
}

pub async fn list_fabric_server_versions() -> Result<Vec<String>, String> {
    let client = meta_client().await?;
    let list = get_json_retry(&client, "https://meta.fabricmc.net/v2/versions/game", 4).await?;
    let versions = list.as_array().ok_or("Fabric 版本清单格式错误")?;
    Ok(versions.iter().filter(|v| v["stable"].as_bool() == Some(true))
        .filter_map(|v| v["version"].as_str().map(str::to_owned)).collect())
}

/// 版本号 → 可比较的数字段（`"1.21.11"` → `[1, 21, 11]`）。
/// 直接比字符串会把 `1.9` 排在 `1.21` 后面。
fn ver_key(v: &str) -> Vec<i64> {
    v.split('.').map(|p| p.parse::<i64>().unwrap_or(-1)).collect()
}

/// Paper 支持版本里与 `want` 同一条小版本线的那个（`1.8.9` → `1.8.8`）。
async fn nearest_paper_version(want: &str) -> Option<String> {
    let list = list_paper_versions().await.ok()?;
    let (minor, _) = want.rsplit_once('.')?;
    list.into_iter().find(|v| v.starts_with(&format!("{minor}.")))
}

/// 原版服务端核心的下载 URL：走官方 version manifest。
/// Android 侧没有 mcmeta 模块，这里直接用 piston 元数据。
const PISTON_META: &str = "https://piston-meta.mojang.com/v1/packages";

async fn http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(PAPERMC_UA)
        .timeout(std::time::Duration::from_secs(600))
        .build()
        .map_err(|e| e.to_string())
}

/// 取元数据用的 client：**短超时**（15 秒）。
///
/// 不能复用下载那个 600 秒超时的 client：Mojang / CDN 在这台设备上
/// 经常连得上却发不出数据（实测 install 挂到 CDP 超时也没返回），
/// 600 秒的等待会让「点一下装核心」看起来像卡死。15 秒 × 4 次重试
/// 最多 1 分钟就能明确告诉用户「这个源连不上」。
async fn meta_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent(PAPERMC_UA)
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| e.to_string())
}

/// 带重试的 JSON GET。
///
/// 这台设备上到 CDN / API 的连接**会中途断**（实测大文件固定在 1.8MB 左右被切，
/// 小 JSON 也会偶发 `error decoding response body`）。一次失败就报错的话，
/// 「点一下装核心」经常直接失败，而重试一次通常就好。
async fn get_json_retry(
    client: &reqwest::Client,
    url: &str,
    tries: usize,
) -> Result<serde_json::Value, String> {
    let mut last = String::new();
    for i in 1..=tries.max(1) {
        let attempt = async {
            let resp = client.get(url).send().await.map_err(|e| e.to_string())?;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            resp.json::<serde_json::Value>()
                .await
                .map_err(|e| format!("解析响应失败: {e}"))
        }
        .await;
        match attempt {
            Ok(v) => return Ok(v),
            Err(e) => {
                last = e;
                tracing::warn!("[core] 请求 {url} 第 {i} 次失败: {last}");
                if i < tries.max(1) {
                    tokio::time::sleep(std::time::Duration::from_millis(500 * i as u64)).await;
                }
            }
        }
    }
    Err(last)
}

/**
 * 流式下载到文件，**带 sha256 校验与断点续传**。
 *
 * ## 为什么必须做这两件事
 *
 * 实测发现下载会被中途截断（同一个 1.8MB 上限，PC curl 和手机 reqwest 都中招）：
 * 拿到的是**半个 jar**，大小 1.8MB 而非 49MB。旧实现直接把它当成功返回，
 * 于是「核心已安装」为真，启动时才炸（zip 解析失败 / 主类找不到），
 * 错误信息完全指不到真凶。
 *
 * - **sha256 校验**：Paper 的 builds 接口给了 `downloads["server:default"].sha256`，
 *   是唯一可靠的「文件完整」判据。
 * - **断点续传**：既然每次连接只给 ~1.8MB，就用 `Range: bytes=N-` 一段段接上，
 *   而不是每次从 0 开始（那样永远下不完）。
 */
/// 算文件的 sha256（下载校验 / 「已经下好就跳过」都用它）。
/// 同步读，核心 jar 也就几十 MB。
fn file_sha256(path: &Path) -> std::io::Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read as _;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut rf = std::fs::File::open(path)?;
    loop {
        let n = rf.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(format!("{:x}", h.finalize()))
}

/// 算文件的 sha1（官方原版核心只给 sha1）
fn file_sha1(path: &Path) -> std::io::Result<String> {
    use sha1::{Digest, Sha1};
    use std::io::Read as _;
    let mut h = Sha1::new();
    let mut buf = vec![0u8; 256 * 1024];
    let mut rf = std::fs::File::open(path)?;
    loop {
        let n = rf.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(format!("{:x}", h.finalize()))
}

#[derive(Clone, Copy)]
enum CoreDigest<'a> {
    None,
    Sha1(&'a str),
    Sha256(&'a str),
}

fn digest_matches(path: &Path, digest: CoreDigest<'_>) -> Result<bool, String> {
    match digest {
        CoreDigest::None => Ok(false),
        CoreDigest::Sha1(want) => Ok(file_sha1(path).map_err(|e| e.to_string())?
            .eq_ignore_ascii_case(want)),
        CoreDigest::Sha256(want) => Ok(file_sha256(path).map_err(|e| e.to_string())?
            .eq_ignore_ascii_case(want)),
    }
}

fn publish_core_file(part: &Path, dest: &Path) -> Result<(), String> {
    std::fs::rename(part, dest).map_err(|e| format!("发布核心文件失败: {e}"))
}

fn parse_core_range(header: &str, offset: u64) -> Option<(u64, u64)> {
    let (range, total) = header.strip_prefix("bytes ")?.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    let (start, end, total) = (start.parse::<u64>().ok()?, end.parse::<u64>().ok()?, total.parse::<u64>().ok()?);
    (start == offset && start <= end && end < total).then(|| (end - start + 1, total))
}

/// 下载核心：断点续传只在 `.part` 上发生，校验通过才原子替换正式文件。
/// 失败时旧核心保持不动；200 响应（源不支持 Range）会在本轮内重置分片，
/// 不会像旧实现那样每次重试都丢掉已经下到的一半。
async fn download_core_file(
    client: &reqwest::Client,
    urls: &[String],
    dest: &Path,
    digest: CoreDigest<'_>,
) -> Result<u64, String> {
    if let Some(p) = dest.parent() {
        std::fs::create_dir_all(p).map_err(|e| format!("创建下载目录失败: {e}"))?;
    }
    // Bind persisted partial data to the requested artifact. An update or a
    // different mirror without a trusted digest must not append foreign bytes.
    let part = |url: &str| {
        use sha2::{Digest, Sha256};
        let key = match digest {
            CoreDigest::Sha1(s) | CoreDigest::Sha256(s) => s,
            CoreDigest::None => url,
        };
        dest.with_extension(format!("{:x}.part", Sha256::digest(key.as_bytes())))
    };

    if let Ok(meta) = std::fs::metadata(dest) {
        if meta.len() > 0 && digest_matches(dest, digest)? {
            tracing::info!("[core] 目标已存在且校验通过（{} 字节），跳过下载", meta.len());
            if let Some(url) = urls.first() { let _ = std::fs::remove_file(part(url)); }
            return Ok(meta.len());
        }
    }

    let mut last_err = String::from("下载失败");
    for url in urls {
        let part = part(url);
        match download_one_core(client, url, &part, digest).await {
            Ok(n) => {
                if let Some(problem) = validate_core_jar(&part) {
                    let _ = std::fs::remove_file(&part);
                    return Err(problem);
                }
                publish_core_file(&part, dest)?;
                return Ok(n);
            }
            Err(e) => {
                tracing::warn!("[core] 从 {url} 下载失败: {e}");
                last_err = e;
            }
        }
    }
    Err(last_err)
}

async fn download_one_core(
    client: &reqwest::Client,
    url: &str,
    part: &Path,
    digest: CoreDigest<'_>,
) -> Result<u64, String> {
    use tokio::io::AsyncWriteExt;
    const MAX_CHUNKS: usize = 200;

    for attempt in 1..=MAX_CHUNKS {
        let have = std::fs::metadata(part).map(|m| m.len()).unwrap_or(0);
        let resp = match client
            .get(url)
            .header("Range", format!("bytes={have}-"))
            .send()
            .await
        {
            Ok(r) => r,
            Err(e) => {
                if attempt == 1 {
                    return Err(format!("下载失败: {e}"));
                }
                tracing::warn!("[core] 第 {attempt} 段请求失败: {e}，重试");
                tokio::time::sleep(std::time::Duration::from_millis(800)).await;
                continue;
            }
        };
        let status = resp.status();
        if status == reqwest::StatusCode::RANGE_NOT_SATISFIABLE {
            if have == 0 {
                return Err(format!("下载失败: HTTP {status}"));
            }
            tracing::warn!("[core] Range bytes={have}- 被拒（416），删掉分片从头重下");
            let _ = std::fs::remove_file(part);
            continue;
        }
        if !status.is_success() && status != reqwest::StatusCode::PARTIAL_CONTENT {
            return Err(format!("下载失败: HTTP {status}"));
        }
        let partial = status == reqwest::StatusCode::PARTIAL_CONTENT;
        let length = resp.content_length();
        let (segment_len, expected_total) = if partial {
            let (len, total) = resp.headers().get(reqwest::header::CONTENT_RANGE)
                .and_then(|v| v.to_str().ok()).and_then(|v| parse_core_range(v, have))
                .ok_or("服务器返回的续传范围不匹配")?;
            if length.is_some_and(|n| n != len) { return Err("续传长度和范围不匹配".into()); }
            (Some(len), Some(total))
        } else if status == reqwest::StatusCode::OK {
            (length, length)
        } else {
            return Err(format!("无效下载状态: HTTP {status}"));
        };
        let append = partial && have > 0;
        let mut f = tokio::fs::OpenOptions::new()
            .create(true)
            .truncate(!append)
            .append(append)
            .write(true)
            .open(part)
            .await
            .map_err(|e| format!("打开分片失败: {e}"))?;
        let mut resp = resp;
        let mut got = 0u64;
        let mut cut = false;
        loop {
            let step = tokio::time::timeout(std::time::Duration::from_secs(15), resp.chunk()).await;
            match step {
                Ok(Ok(Some(chunk))) => {
                    got += chunk.len() as u64;
                    if segment_len.is_some_and(|n| got > n) {
                        drop(f);
                        let _ = std::fs::remove_file(part);
                        return Err("服务端发送的数据超过声明的续传范围".into());
                    }
                    f.write_all(&chunk).await.map_err(|e| format!("写入失败: {e}"))?;
                }
                Ok(Ok(None)) => break,
                Ok(Err(e)) => {
                    tracing::warn!("[core] 本段被中断（已写入 {got} 字节）: {e}");
                    cut = true;
                    break;
                }
                Err(_) => {
                    tracing::warn!("[core] 本段 15 秒无进展，续传（已写入 {got} 字节）");
                    cut = true;
                    break;
                }
            }
        }
        f.flush().await.map_err(|e| format!("保存分片失败: {e}"))?;
        drop(f);

        let total = std::fs::metadata(part).map_err(|e| e.to_string())?.len();
        if cut || segment_len.is_some_and(|n| n != got) || expected_total.is_some_and(|n| total < n) {
            continue;
        }
        tracing::info!("[core] 第 {attempt} 段 +{got} 字节，累计 {total}");

        match digest {
            CoreDigest::None => {
                if let Some(want) = expected_total {
                    if total < want {
                        continue;
                    }
                    if total > want {
                        tracing::warn!("[core] 分片比预期大（{total} > {want}），删掉重下");
                        let _ = std::fs::remove_file(part);
                        continue;
                    }
                } else if cut {
                    continue;
                }
                if total == 0 {
                    return Err("下载中断：服务器没有返回任何数据".into());
                }
                return Ok(total);
            }
            _ => {
                if total > 0 && digest_matches(part, digest)? {
                    return Ok(total);
                }
                let finished_response = !cut && got > 0
                    && expected_total.is_some_and(|want| total >= want);
                if finished_response {
                    tracing::warn!("[core] 完整响应但摘要不符，删除分片并报错");
                    let _ = std::fs::remove_file(part);
                    return Err("下载文件校验失败（内容不完整或被源替换），请重试".into());
                }
            }
        }
    }
    Err("下载未完成：超过最大重试次数".into())
}

#[cfg(test)]
mod download_tests {
    use super::*;

    #[test]
    fn resume_checks_start_and_full_length() {
        assert_eq!(parse_core_range("bytes 10-19/30", 10), Some((10, 30)));
        assert_eq!(parse_core_range("bytes 0-19/30", 10), None);
        assert_eq!(parse_core_range("bytes 10-30/30", 10), None);
        assert_eq!(parse_core_range("bytes 10-19/*", 10), None);
    }

    #[test]
    fn failed_publish_keeps_previous_core() {
        let dir = std::env::temp_dir().join(format!("qk-core-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let dest = dir.join("server.jar");
        std::fs::write(&dest, b"old").unwrap();
        let missing_part = dir.join("missing.part");
        assert!(publish_core_file(&missing_part, &dest).is_err());
        assert_eq!(std::fs::read(&dest).unwrap(), b"old");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn sha256_digest_accepts_matching_file() {
        let dir = std::env::temp_dir().join(format!("qk-digest-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("a.bin");
        std::fs::write(&p, b"abc").unwrap();
        let sha = file_sha256(&p).unwrap();
        assert!(digest_matches(&p, CoreDigest::Sha256(&sha)).unwrap());
        assert!(!digest_matches(&p, CoreDigest::Sha256("deadbeef")).unwrap());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
/**
 * 下载 Paper 核心。
 *
 * API：`GET /v3/projects/paper/versions/{mc}/builds` → 取 STABLE 通道的
 * `downloads["server:default"].url`。v3 的 body 直接是数组（老 v2 有 {ok, builds} 包装）。
 */
async fn download_paper(
    client: &reqwest::Client,
    id: &str,
    mc_version: &str,
) -> Result<String, String> {
    let url = format!("{PAPERMC_API}/paper/versions/{mc_version}/builds");
    let meta = meta_client().await?;
    let body = match get_json_retry(&meta, &url, 4).await {
        Ok(b) => b,
        Err(e) => {
            // Paper 没有这个版本时 builds 接口返回 404。别把「HTTP 404 Not Found」
            // 原样甩给用户 —— 说清是「这个版本没有构建」，并指出最接近的可用版本
            // （1.8.9 → 1.8.8 是最常见的一例：1.8 那条线只有 1.8.8）。
            return Err(if e.contains("404") {
                match nearest_paper_version(mc_version).await {
                    Some(n) => format!("Paper 没有 {mc_version} 的构建，最接近的可用版本是 {n}"),
                    None => format!("Paper 没有 {mc_version} 的构建，换个版本或改用原版核心"),
                }
            } else {
                format!("获取 Paper {mc_version} 的构建信息失败: {e}")
            });
        }
    };

    let builds = body
        .as_array()
        .ok_or_else(|| "Paper API 返回格式异常".to_string())?;
    if builds.is_empty() {
        return Err(format!("Paper 暂无 {mc_version} 的构建"));
    }
    // 选 build：**在 STABLE 里取 id 最大的那个**。
    //
    // 两个坑叠在一起：
    // ① 不能靠数组顺序。虽然实测 fill v3 是新→旧，但依赖顺序很脆；
    // ② 字段名是 **`id`**，没有 `build` 字段。写成 `b.get("build")` 时所有 key 都是 0，
    //    `max_by_key` 会**静默返回最后一个元素** —— 而那恰好是最旧的 build 2。
    //    它在 CDN 上没缓存，实测速率 ~1KB/s（49MB 要十几个小时），
    //    最新的 build 133 却是 10 秒下完。字段名写错会伪装成「网络慢」。
    let build_no = |b: &serde_json::Value| {
        b.get("id")
            .and_then(|v| v.as_i64())
            .or_else(|| b.get("build").and_then(|v| v.as_i64()))
            .unwrap_or(0)
    };
    if builds.iter().all(|b| build_no(b) == 0) {
        return Err("Paper API 返回格式异常：所有构建都没有 id 字段".to_string());
    }
    let build = builds
        .iter()
        .filter(|b| b.get("channel").and_then(|v| v.as_str()) == Some("STABLE"))
        .max_by_key(|b| build_no(b))
        .or_else(|| builds.iter().max_by_key(|b| build_no(b)))
        .ok_or_else(|| "Paper 没有可用构建".to_string())?;
    tracing::info!(
        "[core] 选用 Paper build {}（channel={}）",
        build_no(build),
        build.get("channel").and_then(|v| v.as_str()).unwrap_or("?")
    );

    let dl_info = build
        .get("downloads")
        .and_then(|d| d.get("server:default"))
        .ok_or_else(|| "该构建没有服务端核心".to_string())?;
    let dl = dl_info
        .get("url")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "下载地址为空".to_string())?
        .to_string();
    // sha256 在 `downloads["server:default"].checksums.sha256`（v3 是嵌套的，
    // 不是 v2 那种直接挂在 downloads 下）。取不到就退化为「无校验」而不是假装有。
    let sha = dl_info
        .get("checksums")
        .and_then(|c| c.get("sha256"))
        .or_else(|| dl_info.get("sha256"))
        .and_then(|v| v.as_str())
        .map(|s| s.to_string());
    if sha.is_none() {
        tracing::warn!("[core] Paper 没给 sha256，只能按长度判断完整性");
    }

    let dest = server_dir(id)?.join("server.jar");
    let digest = match sha.as_deref() {
        Some(want) => CoreDigest::Sha256(want),
        None => CoreDigest::None,
    };
    let bytes = download_core_file(client, std::slice::from_ref(&dl), &dest, digest).await?;
    tracing::info!("[core] Paper 核心完成 {bytes} 字节");
    Ok(dl)
}

/**
 * 下载原版服务端核心。
 *
 * 路径是「版本清单 → 该版本详情 → downloads.server」两步：
 * `piston-meta.mojang.com/v1/packages/...` 那个新接口实测全部 404（已废弃），
 * 只有 `version_manifest_v2.json` 这条老路是通的（项目 mirror.rs 也在用它）。
 *
 * 官方只给 **sha1**（不是 sha256），所以完整性判据用 sha1。
 */
async fn download_vanilla(
    client: &reqwest::Client,
    id: &str,
    mc_version: &str,
) -> Result<(), String> {
    let meta = meta_client().await?;
    let manifest = get_json_retry(&meta, crate::mirror::OFFICIAL_MANIFEST, 4)
        .await
        .map_err(|e| format!("获取版本清单失败: {e}"))?;
    let entry = manifest
        .get("versions")
        .and_then(|v| v.as_array())
        .and_then(|arr| {
            arr.iter()
                .find(|v| v.get("id").and_then(|x| x.as_str()) == Some(mc_version))
        })
        .ok_or_else(|| format!("版本清单里没有 {mc_version}"))?;
    let vurl = entry
        .get("url")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "版本清单缺少 url".to_string())?
        .to_string();
    let vj = get_json_retry(&meta, &vurl, 4)
        .await
        .map_err(|e| format!("获取 {mc_version} 详情失败: {e}"))?;
    let dl = vj
        .get("downloads")
        .and_then(|d| d.get("server"))
        .ok_or_else(|| format!("版本 {mc_version} 没有官方服务端核心"))?;
    let url = dl
        .get("url")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "下载地址为空".to_string())?
        .to_string();
    let sha1 = dl.get("sha1").and_then(|v| v.as_str()).map(|s| s.to_string());

    // 官方源慢就换 BMCLAPI 镜像（同路径替换）。
    // 注意：这里**必须直接 .await**，不能套 `tauri::async_runtime::block_on` ——
    // 本函数已经在 tokio 运行时里，运行时内再 block_on 会直接死锁，
    // 表现为「点装核心后命令永远不返回」（实测卡到 CDP 超时也没任何错误）。
    let mirror_base = match crate::settings::get_settings().await {
        Ok(s) => crate::mirror::resolve_from(&s.mirror, s.mirror_custom.as_deref().unwrap_or("")),
        Err(_) => String::new(),
    };
    let dest = server_dir(id)?.join("server.jar");
    let urls: Vec<String> = if mirror_base.is_empty() {
        vec![url]
    } else {
        vec![
            url.clone(),
            format!("{mirror_base}{}", url.strip_prefix("https://piston-data.mojang.com").unwrap_or(&url)),
        ]
    };

    let mut last_err = String::new();
    for u in urls {
        let digest = match sha1.as_deref() {
            Some(want) => CoreDigest::Sha1(want),
            None => CoreDigest::None,
        };
        match download_core_file(client, std::slice::from_ref(&u), &dest, digest).await {
            Ok(_) => return Ok(()),
            Err(e) => {
                tracing::warn!("[core] 原版核心从 {u} 下载失败: {e}");
                last_err = e;
            }
        }
    }
    Err(format!("原版核心下载失败: {last_err}"))
}

/**
 * 下载 Fabric 服务端（官方 meta API）。
 *
 * **直接下 meta 组装好的 `fabric-server-launch.jar`**，而不是安装器。
 *
 * 原来只下 `fabric-installer.jar`（约 1MB，几秒就完）—— 但注释里说的
 * 「Stage 3 再接安装流程」**根本不存在**：全仓没有任何代码会去运行这个安装器，
 * 而 `core_installed()` 只认 `server.jar` / `fabric-server-launch*.jar`。
 * 于是 Fabric 服是一条死路，用户的实测体验就是：
 *   「下载核心」秒完成 → 按钮变「启动」→ 点启动 → 「还没下载服务端核心」。
 *
 * `fabric-server-launch.jar` 自带 Main-Class（`server_process.rs::read_main_class`
 * 从 manifest 读，不硬编码），首次启动会自己把 libraries 与原版 server jar
 * 拉下来 —— 与官方「java -jar fabric-server-launch.jar」用法等价。
 */
async fn download_fabric(
    client: &reqwest::Client,
    id: &str,
    mc_version: &str,
) -> Result<(), String> {
    let mc = meta_client().await?;
    // loader 版本：取第一个 stable；整个列表都没标 stable 就退回第一个
    let loaders = get_json_retry(&mc, "https://meta.fabricmc.net/v2/versions/loader", 4)
        .await
        .map_err(|e| format!("获取 Fabric loader 版本失败: {e}"))?;
    let loader = loaders
        .as_array()
        .and_then(|a| {
            a.iter()
                .find(|v| v.get("stable").and_then(|s| s.as_bool()) == Some(true))
                .or_else(|| a.first())
        })
        .and_then(|v| v.get("version"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| "Fabric loader 列表为空".to_string())?
        .to_string();
    // installer 版本：取最新的（第一个）
    let installers = get_json_retry(&mc, "https://meta.fabricmc.net/v2/versions/installer", 4)
        .await
        .map_err(|e| format!("获取 Fabric installer 版本失败: {e}"))?;
    let installer = installers
        .as_array()
        .and_then(|a| a.first())
        .and_then(|v| v.get("version"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| "Fabric installer 列表为空".to_string())?
        .to_string();

    let dir = server_dir(id)?;
    let dest = dir.join("fabric-server-launch.jar");
    // ⚠️ 2026-10-19 真机修：原来只判「文件非空」就跳过下载。
    //
    // 弱网下这个 launcher 会被下成半截（实测设备上 104KB，官方 164KB）。
    // 半截 zip 打不开 → `read_main_class` 返回 None → `nativeStart` 返回 -3
    // （「启动失败（-3）」，界面上没有任何可操作提示）。
    // 更糟的是：半截文件本身又成了「已存在」的证据，**永远不重下** —— 死锁。
    //
    // 现在要求「能读出 Main-Class」才算已有，否则删掉重下。
    if std::fs::metadata(&dest).map(|m| m.len() > 0).unwrap_or(false) {
        if crate::server_process::read_main_class(&dest).is_some() {
            tracing::info!("[core] Fabric launcher 已存在且可用，跳过下载");
            return Ok(());
        }
        crate::util::log_line(&format!(
            "[core] Fabric launcher 不完整（{} 字节，读不到 Main-Class），删除后重下",
            std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0)
        ));
        let _ = std::fs::remove_file(&dest);
    }
    let url = format!(
        "https://meta.fabricmc.net/v2/versions/loader/{mc_version}/{loader}/{installer}/server/jar"
    );
    if let Err(e) = download_core_file(client, std::slice::from_ref(&url), &dest, CoreDigest::None).await {
        // 404 = 这个 MC 版本还没有对应的 loader 组合，如实说清而不是甩一条 HTTP 码
        if e.contains("404") {
            return Err(format!(
                "Fabric 还没有支持 {mc_version} 的服务端（loader {loader}），换个 MC 版本试试"
            ));
        }
        return Err(format!("下载 Fabric 服务端失败: {e}"));
    }
    Ok(())
}

/// 核心是否已就绪（能否启动）
/// 核心是否真的可以启动：文件存在还不够，必须能读出 JAR 的 Main-Class。
/// 判断失败时界面会重新显示「安装核心」，这就是损坏核心的修复入口。
pub fn core_installed(id: &str) -> bool {
    core_problem(id).is_none()
}

fn core_problem(id: &str) -> Option<String> {
    let dir = match server_dir(id) {
        Ok(d) => d,
        Err(e) => return Some(e),
    };
    let server = dir.join("server.jar");
    if server.exists() {
        return validate_core_jar(&server);
    }
    match fabric_launcher_jar(&dir) {
        Some(name) => validate_core_jar(&dir.join(name)),
        None => Some("还没下载服务端核心".into()),
    }
}

fn validate_core_jar(path: &Path) -> Option<String> {
    let len = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    if len == 0 {
        return Some("服务端核心是空文件，需要重新安装".into());
    }
    if crate::server_process::read_main_class(path).is_none() {
        return Some("服务端核心已损坏（读不到主类），需要重新安装".into());
    }
    None
}

#[cfg(test)]
#[test]
fn invalid_core_jar_is_not_considered_installed() {
    let dir = std::env::temp_dir().join(format!("qk-core-bad-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let jar = dir.join("server.jar");
    std::fs::write(&jar, b"not a zip").unwrap();
    assert!(validate_core_jar(&jar).is_some());
    std::fs::remove_file(&jar).unwrap();
    assert!(validate_core_jar(&jar).is_some());
    let _ = std::fs::remove_dir_all(&dir);
}
fn fabric_launcher_jar(dir: &Path) -> Option<String> {
    let rd = std::fs::read_dir(dir).ok()?;
    for e in rd.flatten() {
        let n = e.file_name().to_string_lossy().to_string();
        if n.starts_with("fabric-server-launch") && n.ends_with(".jar") {
            return Some(n);
        }
    }
    None
}

// ── Tauri 命令：核心安装 ───────────────────────────────────────────────

/// 下载/安装服务端核心。Paper 与原版是单文件；Fabric 下组装好的 launcher jar。
pub async fn install_hosted_server_core(id: String) -> Result<String, String> {
    let _operation = crate::server_state::try_host_operation()?;
    ensure_server_inactive(&id)?;
    let s = get_server(&id)?;
    let client = http_client().await?;
    let res = match s.core {
        ServerCore::Paper => download_paper(&client, &id, &s.mc_version).await?,
        ServerCore::Vanilla => {
            download_vanilla(&client, &id, &s.mc_version).await?;
            "server.jar".to_string()
        }
        ServerCore::Fabric => {
            download_fabric(&client, &id, &s.mc_version).await?;
            "fabric-server-launch.jar".to_string()
        }
        other => {
            return Err(format!(
                "{} 核心还在开发中，先用 Paper 或原版",
                other.as_str()
            ))
        }
    };
    // 核心到位后补一次 EULA/配置（端口/motd 可能刚改过）
    ensure_server_files(&id, &s)?;
    Ok(res)
}
pub fn hosted_server_core_installed(id: String) -> bool {
    core_installed(&id)
}

// ── 服务端运行（主进程侧，:server 独立进程配合） ────────────────────────

/// 这台服务器需要哪个 Java 版本。
///
/// 精确值在版本清单的 `javaVersion` 字段里，但服务器启动**不该强依赖联网清单** ——
/// 用户完全可能「刚下完核心、开着飞行模式」去启动。所以用本地规则表，规则就是
/// Minecraft 历史上几次 Java 要求变更的节点：
///
/// | MC 版本 | 所需 Java |
/// |---|---|
/// | 1.16 及以前 | 8 |
/// | 1.17 ~ 1.19.x | 17 |
/// | 1.20.0 ~ 1.20.4 | 17 |
/// | 1.20.5 ~ 1.21.x | 21 |
/// | 26.x 起（新版本号方案） | 25 |
///
/// 26.3 实测就是 Java 25 —— 用户日志里那行 `runtimes/JRE-25` 即由此而来。
/// 取不到版本号时按 8 走（最宽松，任何已装 JRE 都能满足它）。
/// MC 版本 → 需要的 Java 大版本。`pub(crate)`：实例补全也要用它
/// （见 `instance_files::ensure_instance_java`），别在那边再抄一份映射。
pub(crate) fn required_java_for_mc(mc_version: &str) -> i32 {
    crate::launch_compat::required_java_for_mc_str(mc_version)
}

/// 找一个可用的 JRE；一个都没有就按这台服务器需要的版本**自动下载**。
///
/// ## 为什么必须自动下载
///
/// 游戏启动（`launch.rs`）缺 JRE 会自动下（那套 `JreManager::install`），服务器这边
/// 原来却是直接报错：
///
/// ```text
/// 没有可用的 Java 运行时：请先在「设置 → 运行环境」里装一个 JRE
/// ```
///
/// 而**设置页根本没有「运行环境」这一项**（只有 常规/外观/下载/内容服务/游戏内/存储/关于）——
/// 用户照着提示找过去是死路，最后只能靠「先启动一次游戏」把 JRE 顺手带下来，
/// 而没人会想到这一步。现在与游戏端行为对齐：缺什么就下什么。
///
/// 选版本用 `get_compatible_runtime`（要求「已装版本 ≥ 所需版本**且架构匹配**」），
/// 所以离线时只要本地有够新的 JRE 就直接用，不会无谓联网。
async fn ensure_server_jre(id: &str, mc_version: &str) -> Result<String, String> {
    let required = required_java_for_mc(mc_version);

    // 已装的能满足就直接用（离线也能走通这条）
    if let Ok(Some(rt)) = crate::java::MultiRTManager::get_compatible_runtime(required).await {
        return Ok(rt.path);
    }

    let arch = crate::java::get_device_arch();
    // 先确认版本下得下来：报「没有可用的 Java 运行时」会让人以为是自己没装。
    if let Err(e) = crate::java::resolve_download_url(required, arch) {
        return Err(format!(
            "这台服务器（{mc_version}）需要 Java {required}，但镜像里没有 {arch} 对应的包（{e}）。\
             可以改用其它版本的服务端核心。"
        ));
    }

    let mb = crate::java::download_size_mb(required, arch);
    crate::progress::emit_server_stage(
        id,
        &format!("正在下载 Java {required} 运行时（约 {mb}MB）…"),
    );
    // 5% 一档地上报：JRE 下载很慢，不报进度用户以为卡死；报太密又会频繁跨 FFI
    let last_pct = std::sync::Arc::new(std::sync::atomic::AtomicI32::new(-100));
    let last_pct_progress = last_pct.clone();
    let progress_id = id.to_string();
    let phase_id = id.to_string();
    let installed = crate::java::JreManager::install(
        required,
        arch,
        move |p: f32| {
            let pct = (p * 100.0).round() as i32;
            if pct - last_pct_progress.load(std::sync::atomic::Ordering::Relaxed) >= 5 {
                last_pct_progress.store(pct, std::sync::atomic::Ordering::Relaxed);
                crate::progress::emit_server_stage(
                    &progress_id,
                    &format!("正在下载 Java {required} 运行时 {pct}%"),
                );
            }
        },
        move |phase| crate::progress::emit_server_stage(&phase_id, phase),
    )
    .await
    .map_err(|e| format!("下载 Java {required} 运行时失败：{e}"))?;

    if !crate::java::JREValidator::validate(&installed)
        .await
        .unwrap_or(false)
    {
        return Err(format!(
            "Java {required} 解压后找不到 libjli.so（可能下载不完整），请重试或换个网络"
        ));
    }
    Ok(installed.path)
}


/// 手动停服；撤销休眠意图，即使另一个操作尚未返回。
pub async fn stop_hosted_server(id: String) -> Result<String, String> {
    get_server(&id)?;
    crate::sleep::disarm(&id);
    let _operation = crate::server_state::host_operation().await;
    crate::sleep::disarm(&id);
    match stop_server_under_lock(&id).await? {
        true => Ok("服务端已正常退出".into()),
        false => Ok("服务器当前没有运行".into()),
    }
}

// Callers must hold the lifecycle lock. true requires a clean exit record
// from the same run, not merely a dropped connection or a dead process.
pub(crate) async fn stop_server_under_lock(id: &str) -> Result<bool, String> {
    let Some(run) = read_runtime(id) else {
        ensure_server_inactive(id)?;
        return Ok(false);
    };
    if !runtime_busy(&run) {
        ensure_server_inactive(id)?;
        return Ok(false);
    }
    if !run.clean_exit {
        if run.exit.is_some() || !active_ipc(&run) {
            return Err("尚未确认当前 JVM 正在运行，未发送停服，也不会强制结束".into());
        }
        // 优先走 IPC /stop：它让 :server 进程执行 System.exit(0)，
        // atexit 能补写 cleanExit；RCON 停服没有这个进程内信号。
        let ipc_port = run.ipc_port;
        let token = run.token.clone();
        let ipc = tokio::task::spawn_blocking(move || ipc_request_stop(ipc_port, &token))
            .await
            .map_err(|e| e.to_string())?;
        if ipc.is_err() {
            let creds = crate::rcon::load_or_create_creds(&server_dir(id)?, get_server(id)?.port)?;
            tokio::task::spawn_blocking(move || crate::rcon::request_stop(&creds)).await
                .map_err(|e| e.to_string())?
                .map_err(|e| format!("未能发送正常停服命令，不会强制结束：{e}"))?;
        }
    }
    let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(25);
    while tokio::time::Instant::now() < deadline {
        if let Some(current) = read_runtime(id).filter(|current| same_run(&run, current)) {
            if current.exit.is_some() && !current.clean_exit {
                return Err("服务端异常退出，无法确认世界已正常保存".into());
            }
            if confirmed_server_exit(&run, &current, crate::server_state::process_may_be_alive(run.pid)) {
                crate::progress::emit_server_stage(id, "");
                return Ok(true);
            }
        }
        if !crate::server_state::process_may_be_alive(run.pid) {
            return Err("服务进程已退出，但没有正常退出记录，无法确认存档状态".into());
        }
        tokio::time::sleep(std::time::Duration::from_millis(350)).await;
    }
    Err("已请求正常停服，但尚未确认退出；没有强制结束，请等待并查看控制台".into())
}

fn same_run(a: &crate::server_process::RuntimeInfo, b: &crate::server_process::RuntimeInfo) -> bool {
    !a.token.is_empty() && a.token == b.token && a.pid == b.pid
}

fn confirmed_server_exit(
    expected: &crate::server_process::RuntimeInfo,
    actual: &crate::server_process::RuntimeInfo,
    process_alive: bool,
) -> bool {
    same_run(expected, actual) && actual.clean_exit && actual.exit.is_some() && !process_alive
}

#[cfg(test)]
#[test]
fn stop_requires_this_runs_clean_exit_and_process_death() {
    use crate::server_process::RuntimeInfo;
    let run = RuntimeInfo { pid: 1, token: "current".into(), ..RuntimeInfo::default() };
    assert!(!confirmed_server_exit(&run, &run, false));
    let mut ended = RuntimeInfo { clean_exit: true, exit: Some("exit 0".into()), ..run.clone() };
    assert!(!confirmed_server_exit(&run, &ended, true));
    assert!(confirmed_server_exit(&run, &ended, false));
    ended.token = "previous".into();
    assert!(!confirmed_server_exit(&run, &ended, false));
}

fn runtime_busy(run: &crate::server_process::RuntimeInfo) -> bool {
    crate::server_state::process_may_be_alive(run.pid)
}

pub(crate) fn ensure_server_inactive(id: &str) -> Result<(), String> {
    let path = server_dir(id)?.join("runtime.json");
    match std::fs::read_to_string(path) {
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(format!("无法读取运行记录，不能确认服务器已停止：{e}")),
        Ok(text) => {
            let run: crate::server_process::RuntimeInfo = serde_json::from_str(&text)
                .map_err(|e| format!("运行记录损坏，不能确认服务器已停止：{e}"))?;
            if run.pid <= 0 || runtime_busy(&run) {
                return Err("服务器进程尚未确认退出，请先停止并等待退出完成".into());
            }
            Ok(())
        }
    }
}

/// 执行一条服务端控制台命令（RCON）。
pub fn server_console_command(id: String, command: String) -> Result<String, String> {
    let cmd = command.trim().to_string();
    if cmd.is_empty() {
        return Ok(String::new());
    }
    let dir = server_dir(&id)?;
    let creds = crate::rcon::load_or_create_creds(&dir, get_server(&id)?.port)?;
    crate::rcon::exec(creds.port, &creds.password, &cmd)
}

fn read_runtime(id: &str) -> Option<crate::server_process::RuntimeInfo> {
    let text = std::fs::read_to_string(server_dir(id).ok()?.join("runtime.json")).ok()?;
    serde_json::from_str(&text).ok()
}

fn active_ipc(info: &crate::server_process::RuntimeInfo) -> bool {
    ipc_status(info.ipc_port, &info.token).is_some_and(|status| status.pid == info.pid && status.jvm_active())
}

/// 服务器运行态（UI 轮询用）。
pub fn hosted_server_runtime(id: String) -> Option<ServerRuntime> {
    server_runtime(&id)
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct ServerRuntime {
    pub running: bool,
    #[serde(rename = "startedAt")]
    pub started_at: u64,
    /// JVM 起来了吗（false = 还在启动中）
    #[serde(rename = "jvmUp")]
    pub jvm_up: bool,
    #[serde(rename = "exitNote")]
    pub exit_note: Option<String>,
    /// 空闲休眠中：JVM 已停，但游戏端口由唤醒监听占着，有人连接会自动启动
    pub sleeping: bool,
    #[serde(rename = "sleepSince")]
    pub sleep_since: u64,
    /// 最近一次自动休眠 / 唤醒失败的原因（界面直接显示，否则「点了没反应」没法查）
    #[serde(rename = "wakeError")]
    pub wake_error: Option<String>,
    #[serde(rename = "maxMemoryMb")]
    pub max_memory_mb: u32,
    #[serde(rename = "minMemoryMb")]
    pub min_memory_mb: u32,
    /// 启动时按设备可用内存再次钳制后的实际堆上限（与配置值区分）。
    #[serde(rename = "effectiveMaxMemoryMb")]
    pub effective_max_memory_mb: Option<u32>,
}

/// 服务端此刻是否真的在跑（IPC 探活通）。休眠中的服务器返回 false。
pub fn is_server_up(id: &str) -> bool {
    server_runtime(id).map(|r| r.running).unwrap_or(false)
}

/// 读 runtime.json 并探活（IPC /status 通了才算真在跑）。
pub fn server_runtime(id: &str) -> Option<ServerRuntime> {
    let cfg = get_server(id).ok();
    let state = crate::sleep::read_state(id);
    let sleeping = state.as_ref().map(|s| s.sleeping).unwrap_or(false);
    let sleep_since = state.as_ref().map(|s| s.since).unwrap_or(0);
    let wake_error = state.as_ref().and_then(|s| s.error.clone());

    match read_runtime(id) {
        Some(info) => {
            let alive = info.exit.is_none() && active_ipc(&info);
            let listening = alive && get_server(id).is_ok_and(|s| local_game_listener(s.port));
            Some(ServerRuntime {
                running: listening,
                started_at: info.started_at,
                jvm_up: alive,
                exit_note: info.exit,
                sleeping,
                sleep_since,
                wake_error,
                max_memory_mb: cfg.as_ref().map(|c| c.max_memory_mb).unwrap_or(0),
                min_memory_mb: cfg.as_ref().map(|c| c.min_memory_mb).unwrap_or(0),
                effective_max_memory_mb: info.effective_max_memory_mb.filter(|_| alive),
            })
        }
        // 没有 runtime.json = JVM 已经停了。**休眠时也必须返回 Some**：
        // 界面靠它区分「用户停掉的」与「睡着等唤醒的」—— 前者按钮是「启动」，
        // 后者是「唤醒」。返回 None 的话两者在界面上长得一模一样。
        None if sleeping => Some(ServerRuntime {
            running: false,
            started_at: 0,
            jvm_up: false,
            exit_note: None,
            sleeping: true,
            sleep_since,
            wake_error,
            max_memory_mb: cfg.as_ref().map(|c| c.max_memory_mb).unwrap_or(0),
            min_memory_mb: cfg.as_ref().map(|c| c.min_memory_mb).unwrap_or(0),
            effective_max_memory_mb: None,
        }),
        None => None,
    }
}

/// GET http://127.0.0.1:{port}/status，返回 Some 表示进程活着且鉴权通过
fn ipc_status(port: u16, token: &str) -> Option<crate::server_state::IpcStatus> {
    use std::io::{Read, Write};
    use std::net::TcpStream;
    if port == 0 {
        return None;
    }
    let addr = format!("127.0.0.1:{port}");
    let mut s = TcpStream::connect(&addr).ok()?;
    s.set_read_timeout(Some(std::time::Duration::from_millis(2000)))
        .ok()?;
    s.set_write_timeout(Some(std::time::Duration::from_millis(2000)))
        .ok()?;
    s.write_all(
        format!("GET /status HTTP/1.1\r\nX-Token: {token}\r\nConnection: close\r\n\r\n").as_bytes(),
    )
    .ok()?;
    let mut buf = String::new();
    s.read_to_string(&mut buf).ok()?;
    crate::server_state::IpcStatus::from_http(&buf)
}

/// POST /stop：让 :server 进程走 `JvmLauncher::shutdown()`（System.exit(0)）。
/// 这条路径会置 STOP 标志，atexit 才能在 JVM 退出时补写 cleanExit。
fn ipc_request_stop(port: u16, token: &str) -> Result<(), String> {
    use std::io::{Read, Write};
    use std::net::TcpStream;
    if port == 0 {
        return Err("IPC 端口为 0".into());
    }
    let addr = format!("127.0.0.1:{port}");
    let mut s = TcpStream::connect(&addr).map_err(|e| format!("连不上 IPC（{addr}）：{e}"))?;
    s.set_read_timeout(Some(std::time::Duration::from_millis(3000)))
        .map_err(|e| e.to_string())?;
    s.set_write_timeout(Some(std::time::Duration::from_millis(3000)))
        .map_err(|e| e.to_string())?;
    s.write_all(
        format!("POST /stop HTTP/1.1\r\nX-Token: {token}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .as_bytes(),
    )
    .map_err(|e| format!("发送 IPC 停服请求失败：{e}"))?;
    // JVM 的 System.exit 可能先于响应把进程带走；请求已写出就算送达，
    // 后面统一由 runtime.json 的 cleanExit + 进程存活判断确认结果。
    let mut buf = String::new();
    let _ = s.read_to_string(&mut buf);
    if buf.contains("\"ok\":false") {
        Err(format!("IPC 停服被拒绝：{}", buf.lines().next().unwrap_or("空响应")))
    } else {
        Ok(())
    }
}

/// 读服务端日志尾部（Paper 写自己的 logs/latest.log，这里只取最后 n 行）。
pub fn hosted_server_log(id: String, lines: Option<usize>) -> Result<Vec<String>, String> {
    let dir = server_dir(&id)?;
    // 不能只看 latest.log：上一轮留下的旧日志比本轮的 jvm-stdout.log 还新时，
    // 控制台会一直显示上一轮内容。按修改时间挑最新且非空的日志源。
    let mut best: Option<(PathBuf, std::time::SystemTime)> = None;
    let mut consider = |p: PathBuf| {
        if let Ok(meta) = std::fs::metadata(&p) {
            if meta.len() > 0 {
                let t = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
                if best.as_ref().is_none_or(|(_, bt)| t > *bt) {
                    best = Some((p, t));
                }
            }
        }
    };
    consider(dir.join("logs").join("latest.log"));
    consider(dir.join("latest.log"));
    consider(dir.join("jvm-stdout.log"));
    if let Some(p) = newest_log_in(&dir.join("logs")) {
        consider(p);
    }
    let Some((path, _)) = best else { return Ok(Vec::new()) };
    // 只读文件尾部：几十 MB 的日志不必整份读进内存。
    let text = read_tail_text(&path, 2 * 1024 * 1024)?;
    let n = lines.unwrap_or(200).min(2000);
    let all: Vec<&str> = text.lines().collect();
    Ok(all[all.len().saturating_sub(n)..]
        .iter()
        .map(|s| s.to_string())
        .collect())
}

fn read_tail_text(path: &Path, max_bytes: u64) -> Result<String, String> {
    use std::io::{Read, Seek, SeekFrom};
    let mut f = std::fs::File::open(path).map_err(|e| format!("打开日志失败: {e}"))?;
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    let start = len.saturating_sub(max_bytes);
    f.seek(SeekFrom::Start(start)).map_err(|e| format!("定位日志尾部失败: {e}"))?;
    let mut buf = Vec::new();
    f.take(max_bytes).read_to_end(&mut buf).map_err(|e| format!("读取日志失败: {e}"))?;
    let begin = if start == 0 { 0 } else { buf.iter().position(|&b| b == b'\n').map(|i| i + 1).unwrap_or(buf.len()) };
    Ok(String::from_utf8_lossy(&buf[begin..]).into_owned())
}

#[cfg(test)]
#[test]
fn missing_log_is_an_error_not_empty_output() {
    let dir = std::env::temp_dir().join(format!("qk-log-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    assert!(read_tail_text(&dir.join("nope.log"), 1024).is_err());
    let _ = std::fs::remove_dir_all(&dir);
}
/// 在 `logs/` 目录里找**修改时间最新**的 .log 文件（不看 .gz）。
///
/// 为什么要这个兜底：Paper 每天一个日志文件（`2026-10-22-1.log`），
/// `latest.log` 并非所有版本都有。没有它的话，用户明明能通过文件管理器
/// 看到日志，界面上的控制台却一片空白。
fn newest_log_in(logs_dir: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(logs_dir).ok()?;
    let mut best: Option<(std::time::SystemTime, PathBuf)> = None;
    for e in entries.flatten() {
        let p = e.path();
        if p.extension().and_then(|s| s.to_str()) != Some("log") {
            continue;
        }
        let Ok(meta) = p.metadata() else { continue };
        if meta.len() == 0 {
            continue;
        }
        let mtime = meta.modified().unwrap_or(std::time::UNIX_EPOCH);
        if best.as_ref().map(|(t, _)| mtime > *t).unwrap_or(true) {
            best = Some((mtime, p));
        }
    }
    best.map(|(_, p)| p)
}

/// 取得本机在局域网里的地址，用于显示「朋友用这个地址连进来」。
///
/// 优先 wlan0（WiFi）；数据网（移动网络）没法让人直连，所以只给 WiFi 地址。
pub fn hosted_server_address(id: String) -> Option<String> {
    let s = get_server(&id).ok()?;
    let ip = local_wifi_ip()?;
    Some(format!("{ip}:{}", s.port))
}

/// 设备当前**可用**内存（MB）。界面用它提示「服务端内存最多会被用到多少」。
///
/// 配合 `server_process::clamp_server_heap_mb` 的钳制。只钳制不提示的话，用户会以为
/// 自己填的 4G 在跑、实际只有 1.5G，出问题都不知道该调哪个旋钮。
pub fn device_available_memory_mb() -> Option<u64> {
    crate::server_process::device_available_memory_mb()
}

/// 系统「电池优化」是否已对本应用放行。false = 后台服务可能被 ROM 省电策略掐掉
/// （ColorOS/OnePlus 息屏一会儿就动手），表现是「开服玩一会儿服自己没了」。
pub fn battery_unrestricted() -> bool {
    crate::android_bridge::is_battery_unrestricted()
}

/// 弹系统「忽略电池优化」请求页，用户点「允许」后长期有效。
pub fn request_battery_unrestricted() {
    crate::android_bridge::request_ignore_battery_optimizations()
}

/// 设备当前热状态：0 正常 … 2 中等 … 4 危急 … 6 关机（读不到＝0）。
///
/// 界面拿它提示「服务器变卡可能是手机过热降频」，免得用户以为是服务器坏了。
pub fn device_thermal_status() -> i32 {
    crate::android_bridge::thermal_status()
}

/// 把这台服务器的文件目录交给系统文件管理器打开。
///
/// 服务器文件在应用私有区，系统文件管理器默认进不去，所以走 FileProvider 的
/// `content://` 目录 URI（`file_paths.xml` 里的 `servers` 一项）。**能否真的打开
/// 取决于本机装了哪个管理器**，返回值让前端能兜底：
/// 1 = 已交给外部应用；0 = 本机没有能打开文件夹的应用；-1 = 目录还没生成。
pub fn open_hosted_server_directory(id: String) -> Result<i32, String> {
    let dir = server_dir(&id)?;
    if !dir.is_dir() {
        return Ok(-1);
    }
    Ok(crate::android_bridge::open_directory(&dir.to_string_lossy()))
}

/// 读 wlan0 的 IPv4（拿不到就 None，UI 显示「未连接 WiFi」）
pub(crate) fn local_wifi_ip() -> Option<String> {
    // 应用沙盒里没有 `ip` 命令，必须走 JNI 问 NetworkInterface
    #[cfg(target_os = "android")]
    {
        crate::android_bridge::wifi_ipv4()
    }
    #[cfg(not(target_os = "android"))]
    {
        None
    }
}

/// 列出本机所有 IPv4（非 127.0.0.1），用于排查「该用哪个地址连」
pub fn hosted_server_addresses() -> Vec<String> {
    local_wifi_ip().into_iter().collect()
}

//
// 前端 `src/api.ts` / `stores/servers.ts` 早于本次实现就写好了，约定：
//   start_hosted_server      → 返回 { pid }
//   stop_hosted_server       → void
//   is_hosted_server_running → boolean
//   read_hosted_server_log   → string[]
// 而我先实现的是 start/stop_hosted_server(→String)、hosted_server_runtime、
// hosted_server_log。名字和返回类型对不上会让 UI 直接崩
// （`const { pid } = await api.startHostedServer(id)` 拿到 undefined 就抛错）。
// 这里按 UI 的契约补一层，不去改已经写好的前端。

/// 启动服务器（UI 契约：返回 `{ pid }`）
pub async fn start_hosted_server(id: String) -> Result<serde_json::Value, String> {
    let _operation = crate::server_state::try_host_operation()?;
    start_server_under_lock(id).await
}

pub(crate) async fn start_server_under_lock(id: String) -> Result<serde_json::Value, String> {
    let s = get_server(&id)?;
    if !core_installed(&id) {
        return Err("还没下载服务端核心".to_string());
    }
    for server in list_servers()? {
        ensure_server_inactive(&server.id).map_err(|e| format!("{}：{e}", server.name))?;
    }
    // 这台服务器可能正处休眠：唤醒监听占着游戏端口。**必须先把监听让出来**，
    // 否则服务端起来时 bind 会撞在监听上，报的还是「启动后立刻退出」这种看不懂的话。
    // 顺带清掉休眠标记：从现在起它是「正在启动」，不是「睡着」。
    crate::sleep::cancel_wake_listener(&id);
    crate::sleep::clear_state(&id);
    let dir = server_dir(&id)?;
    crate::fsutil::write_text_atomic(&dir.join("eula.txt"), &format!("eula={}\n", s.eula))
        .map_err(|e| format!("无法写入 Minecraft EULA 状态：{e}"))?;
    if !s.eula {
        return Err("请先在「服务器设置」里勾选同意 Minecraft EULA".into());
    }
    if server_runtime(&id).map(|r| r.running) == Some(true) {
        return Err("这个服务器已经在运行了".to_string());
    }
    // 新建的服务器端口都是默认的 25565：另一台在跑时这台一定 bind 失败，而 Paper 只在
    // 自己的日志深处抛一句 "Address already in use"，用户根本看不出是端口冲突。
    // 这里提前挑明，并说清去哪儿改。
    for other in list_servers().unwrap_or_default() {
        if other.id == id || other.port != s.port {
            continue;
        }
        let other_running = server_runtime(&other.id).map(|r| r.running).unwrap_or(false);
        // **休眠中的服务器同样占着端口**：它的 JVM 停了，但唤醒监听还挂在那个端口上
        // （这正是「随时能被叫起来」的代价）。以前只检查「正在运行」的，
        // 于是启动第二台同端口服务器时 JVM 会 bind 失败，用户看到的还是
        // 「启动后立刻退出」—— 一句查不到根因的话。
        let other_sleeping = crate::sleep::is_sleeping(&other.id);
        if other_running || other_sleeping {
            return Err(format!(
                "端口 {} 已被「{}」占用（{}）。\n先停掉它，或在设置里给这台换一个端口。",
                s.port,
                other.name,
                if other_running {
                    "它正在运行"
                } else {
                    "它休眠中，端口仍被占着"
                }
            ));
        }
    }
    // 端口被**别的应用**占着（我们自己的那几台上面已经查过了）。不提前查的话，
    // Paper 会在起来十几秒后才在日志深处抛一句英文 "Address already in use",
    // 用户看到的是「启动后立刻退出」这种指不到根因的话；而这十几秒里还要下载 JRE、
    // 装核心，白等一场。放在这些重活之前。
    if !wait_port_free(s.port).await {
        return Err(format!(
            "端口 {} 已被别的应用占用。\n先关掉占用它的应用（另一个开服 App 最常见）再启动。",
            s.port
        ));
    }
    ensure_server_files(&id, &s)?;
    // 从这一刻起把「正在干什么」报给界面（按钮上直接显示），别让用户对着转圈白等
    crate::progress::emit_server_stage(&id, "正在检查服务端配置…");
    // 缺 JRE 会自动下载（原来只报一句指向「设置 → 运行环境」的错，而那个入口并不存在）
    let jre_home = ensure_server_jre(&id, &s.mc_version).await?;
    let base_jar = dir.join("server.jar");
    let base_jar = if base_jar.exists() {
        base_jar
    } else {
        std::fs::read_dir(&dir)
            .ok()
            .and_then(|rd| {
                rd.flatten()
                    .find(|e| {
                        let n = e.file_name().to_string_lossy().to_string();
                        n.starts_with("fabric-server-launch") && n.ends_with(".jar")
                    })
                    .map(|e| e.path())
            })
            .ok_or_else(|| "找不到服务端核心 jar".to_string())?
    };
    let mem = crate::server_process::clamp_server_heap_mb(s.max_memory_mb);

    // **旧版** paperclip 包装器（主类 `io.papermc.paperclip.Paperclip`，1.8.x~1.16 那批）
    // 不能直接当服务端跑：它要把「补丁后的服务端 jar」用 Instrumentation 挂进 classpath，
    // 而我们是「-cp + 主类」启动的，拿不到 Instrumentation 就会退出（日志里那句
    // "Unable to retrieve Instrumentation API…"）。补丁产物在 `cache/patched_*.jar`，
    // 那份才是能直接启动的服务端 jar，所以这类核心分两步走：
    //   ① 先跑一轮 paperclip（它打完补丁会**正常退出**）→ 等补丁 jar 落盘；
    //   ② 停掉那一轮，用补丁 jar 正式启动。
    // **别加 `-javaagent:<paperclip.jar>`**（paperclip 的报错就是这么提示的）：实测
    // JRE-25 安卓版会在 `libinstrument.so` 里 SIGSEGV，整个 :server 进程带崩、什么都不留。
    //
    // **新版** paperclip（主类 `io.papermc.paperclip.Main`，26.x 这类）不需要这套：
    // 它自己就能下原版包、打补丁、再用 `org.bukkit.craftbukkit.Main` 起服（26.3 真机实测）。
    // 对它走两步纯属白等 —— 它不产出 `cache/patched_*.jar`，会把等待撑满整个超时。
    let needs_patch = needs_patch_jar(&base_jar);
    if needs_patch && patched_core_jar(&dir).is_none() {
        tracing::info!("[core] 首次启动旧版 Paper：先让 paperclip 生成补丁 jar（会多花十几秒）");
        crate::progress::emit_server_stage(&id, "首次启动：正在生成服务端补丁（约一分钟）…");
        write_launch_spec(&id, &dir, &jre_home, &base_jar, mem, s.min_memory_mb)?;
        crate::android_bridge::start_server_process(&id)?;
        let mut patched: Option<PathBuf> = None;
        for _ in 0..100 {
            tokio::time::sleep(std::time::Duration::from_millis(600)).await;
            if let Some(p) = patched_core_jar(&dir) {
                patched = Some(p);
                break;
            }
            // 这一轮的 JVM 已经退出（paperclip 打完补丁就退，或者干脆失败）→ 不用把 60 秒等满
            let exited = std::fs::read_to_string(dir.join("runtime.json"))
                .ok()
                .and_then(|t| serde_json::from_str::<crate::server_process::RuntimeInfo>(&t).ok())
                .map(|i| i.exit.is_some())
                .unwrap_or(false);
            if exited {
                break;
            }
        }
        if patched.is_none() {
            return Err(start_failed_hint(&id, "核心初始化失败：paperclip 没能生成补丁 jar"));
        }
        tracing::info!("[core] 补丁 jar 已生成");
        // 这一轮的进程即使还活着也不再有用（paperclip 自己起不了服），先停掉再重新拉起。
        let bootstrap = read_runtime(&id).ok_or("缺少补丁进程的运行记录")?;
        // Let the bootstrap finish naturally. Never retire a process still writing a jar.
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(15);
        while runtime_busy(&bootstrap) && tokio::time::Instant::now() < deadline {
            tokio::time::sleep(std::time::Duration::from_millis(250)).await;
        }
        ensure_server_inactive(&id)?;
    }

    // 旧版 paperclip 用补丁 jar 启动；其余核心（原版 / Fabric / 新版 paperclip）直接用原 jar。
    let jar = if needs_patch {
        patched_core_jar(&dir).unwrap_or(base_jar)
    } else {
        base_jar
    };
    write_launch_spec(&id, &dir, &jre_home, &jar, mem, s.min_memory_mb)?;
    crate::progress::emit_server_stage(&id, "正在启动服务端进程…");
    let log_mark = last_log_line(&id);
    crate::android_bridge::start_server_process(&id)?;
    // 「进程拉起来了」不等于「服能跑」：核心/JRE 不匹配、内存参数非法都会让它几秒内就死。
    // 原来这里直接返回 pid，前端就弹「服务器已启动」而日志还是空的 —— 用户完全不知道出了什么事。
    let pid = wait_server_alive(&id, s.port, log_mark.as_deref()).await?;
    // 确认起来了才开始巡检。放在这里（而不是启动前）有两个原因：
    // ① 没起来时 RCON 问不到人数，巡检白跑；② 更要紧的是「停着的服务器」
    // 不该被写进 sleep.json —— 界面上的「停止」与「休眠」必须是两回事。
    if s.sleep_timeout_min > 0 {
        crate::sleep::arm_supervisor(&id);
    }
    Ok(serde_json::json!({ "pid": pid }))
}

/// 这个端口现在能不能绑（= 没有被本机其它东西监听）。
///
/// 做法是「试绑一下」。**不用 `/proc/net/tcp`**：Android 16 上应用读它是
/// `Permission denied`（真机实测），那条路不通。
///
/// 试绑为什么不会误报「刚停掉的服务器」：Rust 标准库在 Unix 上建 `TcpListener` 时会设
/// `SO_REUSEADDR`，只有 TIME_WAIT 残留时仍能绑上；真的还有人在 **LISTEN** 才会失败。
///
/// 重试是必须的：**休眠唤醒**这条路上，唤醒监听刚被取消，socket 未必已经松开 ——
/// 不重试就会把「本来能起来」的服务器误判成端口被占，玩家连进来却永远起不来。
async fn wait_port_free(port: u16) -> bool {
    for _ in 0..10 {
        if std::net::TcpListener::bind(("0.0.0.0", port)).is_ok() {
            return true;
        }
        tokio::time::sleep(std::time::Duration::from_millis(300)).await;
    }
    false
}

/// 启动失败时给用户看的**短**消息：一句原因 + 一句指点，最多再带一行关键输出。
///
/// 之前把日志尾巴 10 行整段塞进错误里，弹到手机屏幕上根本放不下（用户原话：
/// 「非常非常长的一个报错」）。完整输出在「日志」页，那里能滚动、能选中复制。
fn start_failed_hint(id: &str, what: &str) -> String {
    let line = hosted_server_log(id.to_string(), Some(12))
        .ok()
        .and_then(|lines| {
            // 从后往前找第一句「像错误」的话：跳过空行、纯堆栈行与日志分隔线
            let skip = |l: &String| {
                let t = l.trim();
                t.is_empty()
                    || t.starts_with("at ")
                    || t.starts_with("...")
                    || t.starts_with("===")
                    || t.starts_with('#')
            };
            lines.iter().rev().find(|l| !skip(l)).cloned()
        })
        .map(|l| l.trim().chars().take(120).collect::<String>())
        .unwrap_or_default();
    if line.is_empty() {
        format!("{what}。\n完整输出见「日志」页。")
    } else {
        format!("{what}。\n{line}\n（完整输出见「日志」页）")
    }
}

/// 这个核心是不是**旧版** paperclip 包装器（需要先产出补丁 jar 才能启动）。
/// 旧版主类最后一段是 `Paperclip`（`io.papermc.paperclip.Paperclip`）；
/// 新版是 `Main`（`io.papermc.paperclip.Main`），能自己打完补丁接着起服。
fn needs_patch_jar(jar: &Path) -> bool {
    crate::server_process::read_main_class(jar)
        .map(|c| c.rsplit('.').next().unwrap_or("").eq_ignore_ascii_case("paperclip"))
        .unwrap_or(false)
}

/// 写 `launch.json`（`:server` 进程启动时读它）
fn write_launch_spec(
    id: &str,
    dir: &Path,
    jre_home: &str,
    jar: &Path,
    xmx_mb: u32,
    xmin_mb: u32,
) -> Result<(), String> {
    let spec = crate::server_process::LaunchSpec {
        id: id.to_string(),
        jre_home: jre_home.to_string(),
        jar: jar.to_string_lossy().to_string(),
        xmx_mb,
        xmin_mb,
        work_dir: dir.to_string_lossy().to_string(),
        args: vec!["nogui".to_string()],
        token: crate::server_process::new_token(),
    };
    crate::fsutil::write_text_atomic(
        &dir.join("launch.json"),
        &serde_json::to_string_pretty(&spec).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    // 旧的 runtime.json 属于上一次进程：留着会让「这一轮 JVM 是否已退出 / 是否还活着」
    // 读到过期状态（比如上一轮的 exit 仍在，等待循环会立刻误判本轮已结束）。
    let _ = std::fs::remove_file(dir.join("runtime.json"));
    Ok(())
}

fn local_game_listener(port: u16) -> bool {
    use std::net::{Ipv4Addr, SocketAddr, TcpStream};
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    TcpStream::connect_timeout(&addr, std::time::Duration::from_millis(400)).is_ok()
}

/// Require both the current JVM and its game listener; IPC alone starts too early.
async fn wait_server_alive(id: &str, game_port: u16, mark: Option<&str>) -> Result<i32, String> {
    use std::time::Duration;
    let deadline = tokio::time::Instant::now() + Duration::from_secs(90);
    let mut last = mark.unwrap_or("").to_string();
    while tokio::time::Instant::now() < deadline {
        if let Some(info) = read_runtime(id) {
            if let Some(note) = info.exit {
                return Err(start_failed_hint(id, &note));
            }
            if !runtime_busy(&info) {
                return Err(start_failed_hint(id, "服务端进程已退出"));
            }
            if local_game_listener(game_port) && active_ipc(&info) {
                return Ok(info.pid);
            }
        }
        if let Some(line) = last_log_line(id).filter(|line| line != &last) {
            last = line;
            crate::progress::emit_server_stage(id, &format!("启动中：{last}"));
        }
        tokio::time::sleep(Duration::from_millis(500)).await;
    }
    Err(start_failed_hint(id, "等待服务端端口就绪超时，未强制结束进程"))
}

/// 日志里最后一行有内容的话（跳过空行与分隔线），用于「正在做什么」的实时反馈。
fn last_log_line(id: &str) -> Option<String> {
    let lines = hosted_server_log(id.to_string(), Some(4)).ok()?;
    lines
        .iter()
        .rev()
        .map(|l| l.trim())
        .find(|l| !l.is_empty() && !l.starts_with("==="))
        .map(|l| l.chars().take(80).collect())
}

/// paperclip 打好的补丁 jar（`cache/patched_*.jar`）——真正可直接启动的服务端。
/// 取体积最大的那个，避免旧版本残留被选中。
fn patched_core_jar(dir: &Path) -> Option<PathBuf> {
    let rd = std::fs::read_dir(dir.join("cache")).ok()?;
    let mut best: Option<(u64, PathBuf)> = None;
    for e in rd.flatten() {
        let n = e.file_name().to_string_lossy().to_string();
        if !n.starts_with("patched_") || !n.ends_with(".jar") {
            continue;
        }
        let size = e.metadata().map(|m| m.len()).unwrap_or(0);
        if size == 0 {
            continue;
        }
        if best.as_ref().map(|(s, _)| size > *s).unwrap_or(true) {
            best = Some((size, e.path()));
        }
    }
    best.map(|(_, p)| p)
}

/// 服务器是否在运行（UI 契约：boolean）
pub fn is_hosted_server_running(id: String) -> bool {
    server_runtime(&id).map(|r| r.running).unwrap_or(false)
}

/// 读服务器日志（UI 契约：string[]）
pub fn read_hosted_server_log(id: String) -> Result<Vec<String>, String> {
    hosted_server_log(id, Some(300))
}
