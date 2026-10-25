//! FRP 内网穿透：**设备码登录 → 用户信息 / 隧道列表 → 拉起 frpc 子进程**。
//!
//! 建隧道、联机房间那些都在服务商网页上做，这里不碰（按需求只做这三件事）。
//!
//! 面板协议是「HTTP + 表单/JSON + Bearer Token」，有几条**必须照做**的约定，
//! 违反的表现就是被限流（下面每条都是踩过才知道的）：
//!
//! 1. **强制 HTTP/1.1**、UA 明确、总超时 20 秒 —— 面板前面有 WAF，对 HTTP/2 请求敏感；
//! 2. **设备注册按 IP 限流**（每分钟几次）。命中限流要**等满 60 秒**再重试：
//!    用短间隔重试会一直刷新滑动窗口，等于永远解不开限流；
//! 3. **Token 轮询每一轮都先等 `interval` 秒再发请求**（首轮同样先等），且间隔不小于 5 秒 ——
//!    注册与轮询共用同一个限流桶，注册完立刻轮询必然被限流；
//! 4. 校验登录态时，若失败只是**网络错误或限流**，**不能**判定成"登录已失效"
//!    （否则用户一断网就被登出，还得重新扫码授权）。
//!
//! 隧道进程：配置写到 `<data>/frp/tunnels/<名字>.toml`，然后执行
//! `<APK 原生库目录>/libfrpc.so -c <配置>`。
//!
//! 为什么从 APK 的 `nativeLibraryDir` 执行而不是抽到 `<data>/`：安卓 10+ 禁止执行
//! 应用可写目录里的文件（W^X），而 APK 的 `lib/<abi>/` 是允许执行的位置 ——
//! 所以 frpc 以 `libfrpc.so` 的名义随包发（它本身是个静态链接的 AArch64 可执行文件）。

use anyhow::{anyhow, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::Read;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// 面板基址默认值。可在应用内改（`frp_set_api_base`）——换一个兼容的面板照样能用。
pub const DEFAULT_API_BASE: &str = "https://frp.mhjz1.cn";

const USER_AGENT: &str = "QookiX-Launcher-Android/1.0 (frp-client)";
const API_TIMEOUT_SECS: u64 = 20;
const CONNECT_TIMEOUT_SECS: u64 = 15;
/// 轮询间隔下限：面板侧是「同一 IP 每 5 秒最多查一次」
const MIN_POLL_INTERVAL_SECS: u64 = 5;
/// 设备注册的限流窗口：等满一轮再重试，短间隔重试只会一直踩限流
const REGISTER_BACKOFF_SECS: u64 = 60;
const REGISTER_MAX_TRIES: u32 = 3;
/// 面板发的 Token 一般 1 天有效。本地按 24 小时记，并提前 5 分钟当作过期。
const TOKEN_TTL_SECS: i64 = 24 * 3600;
const TOKEN_EXPIRY_SKEW_SECS: i64 = 300;

fn now_secs() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

// ==================== 本地登录态 ====================

#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase")]
struct Stored {
    api_base: String,
    token: String,
    expires_at: i64,
    username: String,
}

impl Stored {
    fn logged_in(&self) -> bool {
        !self.token.is_empty() && self.expires_at - TOKEN_EXPIRY_SKEW_SECS > now_secs()
    }
}

fn config_path(data_dir: &str) -> PathBuf {
    PathBuf::from(data_dir).join("frp.json")
}

async fn load_stored() -> Result<Stored> {
    let dir = crate::settings::get_data_dir().await?;
    let path = config_path(&dir);
    if !path.is_file() {
        return Ok(Stored {
            api_base: DEFAULT_API_BASE.to_string(),
            ..Default::default()
        });
    }
    let text = tokio::fs::read_to_string(&path)
        .await
        .with_context(|| format!("读取 {} 失败", path.display()))?;
    let mut s: Stored = serde_json::from_str(&text).unwrap_or_default();
    // 面板地址**写死**：这个客户端只对接这一家面板，不给改（界面上也没有入口）。
    // 这里直接覆盖磁盘上的值 —— 否则旧配置或别的入口改过之后，会出现"登着另一个面板"
    // 这种查起来极费劲的状态。
    s.api_base = DEFAULT_API_BASE.to_string();
    Ok(s)
}

async fn save_stored(s: &Stored) -> Result<()> {
    let dir = crate::settings::get_data_dir().await?;
    let path = config_path(&dir);
    let text = serde_json::to_string_pretty(s)?;
    crate::fsutil::write_text_atomic(&path, &text)
        .with_context(|| format!("写入 {} 失败", path.display()))?;
    Ok(())
}

/// 有登录态就返回它，否则给出「请先登录」这种能直接给用户看的错误。
async fn require_auth() -> Result<Stored> {
    let s = load_stored().await?;
    if s.token.is_empty() {
        return Err(anyhow!("还没有登录面板"));
    }
    if !s.logged_in() {
        return Err(anyhow!("登录已过期，请重新登录"));
    }
    Ok(s)
}

// ==================== HTTP ====================

/// 专用于面板的客户端：HTTP/1.1 + 固定 UA + 20 秒超时（不共用 `util::http_client()`，
/// 那个是全局缓存的、开 HTTP/2、超时 600 秒，改不动也不该被这里改）。
async fn client() -> Result<reqwest::Client> {
    let mut builder = reqwest::Client::builder()
        .http1_only()
        .user_agent(USER_AGENT)
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .timeout(Duration::from_secs(API_TIMEOUT_SECS));
    let proxy = crate::util::resolved_proxy().await;
    if !proxy.trim().is_empty() {
        builder = builder.proxy(reqwest::Proxy::all(proxy.trim()).context("代理地址无效")?);
    }
    builder.build().context("创建面板 HTTP 客户端失败")
}

/// 限流关键字（面板的中文/英文提示都认）
fn is_rate_limited(text: &str) -> bool {
    let lower = text.to_lowercase();
    ["频繁", "稍后再试", "过于频繁", "rate", "too many", "slow down"]
        .iter()
        .any(|k| text.contains(k) || lower.contains(k))
}

fn brief(body: &str) -> String {
    let t = body.trim().replace(['\n', '\r'], " ");
    if t.chars().count() > 200 {
        format!("{}…", t.chars().take(200).collect::<String>())
    } else {
        t
    }
}

/// 解析统一响应包装 `{status,message,error,data}`：
/// `status == "error"` 视为业务失败，错误信息取 `message`。
fn unwrap_envelope(v: &Value) -> Result<Value> {
    let status = v.get("status").and_then(|s| s.as_str()).unwrap_or("");
    if status.eq_ignore_ascii_case("error") {
        let msg = v
            .get("message")
            .and_then(|m| m.as_str())
            .filter(|m| !m.trim().is_empty())
            .or_else(|| {
                v.get("error")
                    .and_then(|e| e.as_str())
                    .filter(|e| !e.trim().is_empty())
            })
            .unwrap_or("面板返回了错误");
        return Err(anyhow!("{}", msg));
    }
    Ok(v.get("data").cloned().unwrap_or(Value::Null))
}

async fn send(
    c: &reqwest::Client,
    method: reqwest::Method,
    url: &str,
    token: Option<&str>,
    form: Option<&[(&str, String)]>,
) -> Result<Value> {
    let mut req = c.request(method, url);
    if let Some(t) = token {
        req = req.bearer_auth(t);
    }
    if let Some(f) = form {
        req = req.form(f);
    }
    let resp = req.send().await.context("请求面板失败（网络不通或超时）")?;
    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    if !status.is_success() {
        // 4xx/5xx 里也常带业务信息，优先把它捞出来给用户看
        if let Ok(v) = serde_json::from_str::<Value>(&body) {
            if let Err(e) = unwrap_envelope(&v) {
                return Err(e);
            }
        }
        return Err(anyhow!("HTTP {}：{}", status.as_u16(), brief(&body)));
    }
    let v: Value = serde_json::from_str(&body)
        .with_context(|| format!("响应不是 JSON：{}", brief(&body)))?;
    unwrap_envelope(&v)
}

async fn get(c: &reqwest::Client, url: &str, token: Option<&str>) -> Result<Value> {
    send(c, reqwest::Method::GET, url, token, None).await
}

async fn post_form(c: &reqwest::Client, url: &str, form: &[(&str, String)]) -> Result<Value> {
    send(c, reqwest::Method::POST, url, None, Some(form)).await
}

/// 原样返回响应 JSON，**不做统一包装解析**。
///
/// Token 轮询那个接口是协议里唯一的例外：它没有 `data` 外壳，`status/token` 都在顶层。
/// 走通用解析会把 token 丢掉（`data` 不存在 → Null），于是"授权成功了却拿不到 token"。
async fn post_form_raw(c: &reqwest::Client, url: &str, form: &[(&str, String)]) -> Result<Value> {
    let resp = c
        .post(url)
        .form(form)
        .send()
        .await
        .context("请求面板失败（网络不通或超时）")?;
    let status = resp.status();
    let body = resp.text().await.unwrap_or_default();
    serde_json::from_str(&body)
        .with_context(|| format!("HTTP {} 响应不是 JSON：{}", status.as_u16(), brief(&body)))
}

/// 面板返回的字段可能是数字、数字字符串，甚至时间字符串 —— 一律宽容解析。
fn flex_i64(v: Option<&Value>) -> i64 {
    match v {
        Some(Value::Number(n)) => n.as_i64().unwrap_or(0),
        Some(Value::String(s)) => s.trim().parse::<i64>().unwrap_or(0),
        _ => 0,
    }
}

fn flex_str(v: Option<&Value>) -> String {
    match v {
        Some(Value::String(s)) => s.to_string(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => String::new(),
    }
}

// ==================== 设备码登录 ====================

/// 一次登录会话（`device_code` 只留在后端，绝不给前端 —— 它等价于本次轮询的凭据）。
#[derive(Clone)]
struct LoginSession {
    api_base: String,
    device_code: String,
    interval: u64,
    expires_at: i64,
}

static SESSIONS: OnceLock<Mutex<HashMap<String, LoginSession>>> = OnceLock::new();

fn sessions() -> &'static Mutex<HashMap<String, LoginSession>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn base_url(api_base: &str) -> String {
    api_base.trim().trim_end_matches('/').to_string()
}

/// 步骤 1：设备注册。成功后前端展示「授权链接 + 用户码」，用户去浏览器里点一下。
pub async fn login_start() -> Result<Value> {
    let stored = load_stored().await?;
    let base = base_url(&stored.api_base);
    if base.is_empty() {
        return Err(anyhow!("面板地址为空"));
    }
    let c = client().await?;
    let url = format!("{base}/auth/device_register.php");

    let mut last_err: Option<anyhow::Error> = None;
    let mut data = Value::Null;
    for attempt in 0..REGISTER_MAX_TRIES {
        if attempt > 0 {
            // 限流要等满整个窗口，短间隔重试等于一直在刷新窗口
            tokio::time::sleep(Duration::from_secs(REGISTER_BACKOFF_SECS)).await;
        }
        // 空表单：按面板网页端的习惯发（否则容易被判成异常客户端）
        match post_form(&c, &url, &[]).await {
            Ok(v) => {
                data = v;
                break;
            }
            Err(e) => {
                let msg = e.to_string();
                if !is_rate_limited(&msg) {
                    return Err(e);
                }
                last_err = Some(e);
            }
        }
    }
    let data = if data.is_null() {
        return Err(last_err.unwrap_or_else(|| anyhow!("设备注册失败")));
    } else {
        data
    };

    let device_code = flex_str(data.get("device_code"));
    let user_code = flex_str(data.get("user_code"));
    if device_code.is_empty() || user_code.is_empty() {
        return Err(anyhow!("面板没有返回设备码：{}", brief(&data.to_string())));
    }
    let expires_in = {
        let v = flex_i64(data.get("expires_in"));
        if v > 0 {
            v
        } else {
            300
        }
    };
    let interval = {
        let v = flex_i64(data.get("interval")).max(0) as u64;
        v.max(MIN_POLL_INTERVAL_SECS)
    };
    // 优先用「已带用户码」的完整链接；没有就退回基础链接
    let verification = {
        let complete = flex_str(data.get("verification_uri_complete"));
        if !complete.trim().is_empty() {
            complete
        } else {
            let uri = flex_str(data.get("verification_uri"));
            if uri.trim().is_empty() {
                String::new()
            } else {
                let sep = if uri.contains('?') { '&' } else { '?' };
                format!("{uri}{sep}user_code={user_code}")
            }
        }
    };

    let session_id = uuid::Uuid::new_v4().to_string();
    if let Ok(mut g) = sessions().lock() {
        // 顺手清掉已经过期的会话，别让这个表越积越多
        let now = now_secs();
        g.retain(|_, s| s.expires_at > now);
        g.insert(
            session_id.clone(),
            LoginSession {
                api_base: base,
                device_code,
                interval,
                expires_at: now + expires_in,
            },
        );
    }

    Ok(json!({
        "session": session_id,
        "userCode": user_code,
        "verificationUri": verification,
        "expiresIn": expires_in,
        "interval": interval,
    }))
}

/// 步骤 2：轮询换取 Token。**每轮先等 `interval` 秒**（面板的硬要求）。
pub async fn login_poll(session: String) -> Result<Value> {
    let s = {
        let g = sessions()
            .lock()
            .map_err(|_| anyhow!("内部状态异常"))?;
        g.get(&session).cloned()
    };
    let Some(s) = s else {
        return Err(anyhow!("登录会话已失效，请重新发起"));
    };
    if s.expires_at <= now_secs() {
        if let Ok(mut g) = sessions().lock() {
            g.remove(&session);
        }
        return Ok(json!({ "status": "expired", "message": "授权码已过期，请重新登录" }));
    }

    tokio::time::sleep(Duration::from_secs(s.interval)).await;

    let c = client().await?;
    let url = format!("{}/auth/device_token.php", s.api_base);
    let value = post_form_raw(&c, &url, &[("device_code", s.device_code.clone())]).await?;

    let state = flex_str(value.get("status"));
    let message = flex_str(value.get("message"));
    let error = flex_str(value.get("error"));
    match state.as_str() {
        "authorized" => {}
        "error" => {
            // 限流只是「这次查得太快」，留着重试；其余是终态
            if is_rate_limited(&message) || is_rate_limited(&error) {
                return Ok(json!({ "status": "pending", "message": message }));
            }
            let drop_session = || {
                if let Ok(mut g) = sessions().lock() {
                    g.remove(&session);
                }
            };
            if error.contains("expired_token") {
                drop_session();
                return Ok(json!({ "status": "expired", "message": "授权码已过期，请重新登录" }));
            }
            if error.contains("access_denied") {
                drop_session();
                return Ok(json!({ "status": "denied", "message": "你在授权页拒绝了本次登录" }));
            }
            return Err(anyhow!(if message.is_empty() { error } else { message }));
        }
        // pending / 未知状态：继续等下一轮
        _ => return Ok(json!({ "status": "pending" })),
    }

    let token = {
        let t = flex_str(value.get("token"));
        if t.is_empty() {
            flex_str(value.get("access_token"))
        } else {
            t
        }
    };
    if token.is_empty() {
        // 声称授权成功却没给 token —— 不能当成功，否则用户会卡在"已登录"的假象里
        return Err(anyhow!("面板说授权成功，却没有返回 Token"));
    }

    // 存下来，并立刻换一次用户信息（顺便验证这个 Token 真能用、也把用户名带回去）
    let mut stored = load_stored().await?;
    stored.api_base = s.api_base.clone();
    stored.token = token;
    stored.expires_at = now_secs() + TOKEN_TTL_SECS;
    stored.username = String::new();
    save_stored(&stored).await?;
    if let Ok(mut g) = sessions().lock() {
        g.remove(&session);
    }

    let username = match user_info().await {
        Ok(info) => {
            let name = flex_str(info.get("username"));
            let mut st = load_stored().await?;
            st.username = name.clone();
            if let Err(e) = save_stored(&st).await {
                tracing::warn!("[frp] 保存面板登录名失败（不影响登录）：{e:#}");
            }
            name
        }
        Err(_) => String::new(),
    };

    Ok(json!({ "status": "authorized", "username": username }))
}

pub async fn logout() -> Result<()> {
    let mut stored = load_stored().await?;
    stored.token.clear();
    stored.username.clear();
    stored.expires_at = 0;
    save_stored(&stored).await
}

// ==================== 用户信息 / 隧道列表 ====================

pub async fn status() -> Value {
    let stored = load_stored().await.unwrap_or_default();
    let (bin_ok, bin_path) = match frpc_binary().await {
        Ok(p) => (true, p.to_string_lossy().to_string()),
        Err(e) => (false, e.to_string()),
    };
    json!({
        "apiBase": if stored.api_base.trim().is_empty() { DEFAULT_API_BASE } else { stored.api_base.as_str() },
        "loggedIn": stored.logged_in(),
        "username": stored.username,
        "expiresAt": stored.expires_at,
        "frpc": { "ready": bin_ok, "detail": bin_path },
    })
}

pub async fn set_api_base(api_base: String) -> Result<Value> {
    let base = base_url(&api_base);
    if base.is_empty() || !base.starts_with("http") {
        return Err(anyhow!("面板地址要以 http(s):// 开头"));
    }
    let mut stored = load_stored().await?;
    if stored.api_base != base {
        // 换面板等于换账号：旧 Token 一定无效，直接清掉，避免"登着别的面板"的错觉
        stored.token.clear();
        stored.username.clear();
        stored.expires_at = 0;
        stored.api_base = base;
        save_stored(&stored).await?;
    }
    Ok(status().await)
}

pub async fn user_info() -> Result<Value> {
    let stored = load_stored().await?;
    if stored.token.is_empty() {
        return Err(anyhow!("还没有登录面板"));
    }
    let c = client().await?;
    let url = format!("{}/proxy/user_balance.php", base_url(&stored.api_base));
    let data = get(&c, &url, Some(&stored.token)).await?;
    Ok(data)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TunnelInfo {
    pub id: i64,
    pub name: String,
    pub protocol: String,
    pub local_ip: String,
    pub local_port: i64,
    pub remote_port: i64,
    pub custom_domain: String,
    pub status: String,
    pub node_name: String,
    pub node_hostname: String,
    pub node_port: i64,
    pub bandwidth_limit: i64,
    pub expire_time: i64,
    pub package_name: String,
    /// 分享给别人的地址：`节点:远端端口`（有自定义域名时用域名）
    pub address: String,
}

pub async fn tunnels() -> Result<Vec<TunnelInfo>> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!(
        "{}/proxy/list.php?page=1&pageSize=100",
        base_url(&stored.api_base)
    );
    let data = get(&c, &url, Some(&stored.token)).await?;
    let arr = data
        .get("tunnels")
        .and_then(|t| t.as_array())
        .cloned()
        .unwrap_or_default();
    Ok(arr.iter().map(parse_tunnel).collect())
}

fn parse_tunnel(v: &Value) -> TunnelInfo {
    let remote_port = flex_i64(v.get("remote_port"));
    let custom_domain = flex_str(v.get("custom_domain"));
    let node_hostname = flex_str(v.get("node_hostname"));
    // HTTP/HTTPS 隧道展示域名，其它协议展示端口（和面板网页上的列一致）
    let address = if !custom_domain.trim().is_empty() {
        custom_domain.clone()
    } else if node_hostname.trim().is_empty() {
        String::new()
    } else {
        format!("{node_hostname}:{remote_port}")
    };
    TunnelInfo {
        id: flex_i64(v.get("id")),
        name: flex_str(v.get("name")),
        protocol: flex_str(v.get("protocol")),
        local_ip: {
            let ip = flex_str(v.get("local_ip"));
            if ip.trim().is_empty() {
                "127.0.0.1".to_string()
            } else {
                ip
            }
        },
        local_port: flex_i64(v.get("local_port")),
        remote_port,
        custom_domain,
        status: flex_str(v.get("status")),
        node_name: flex_str(v.get("node_name")),
        node_hostname,
        node_port: flex_i64(v.get("node_port")),
        bandwidth_limit: flex_i64(v.get("bandwidth_limit")),
        expire_time: flex_i64(v.get("expire_time")),
        package_name: flex_str(v.get("package_name")),
        address,
    }
}

// ==================== 隧道进程 ====================

struct Running {
    name: String,
    addr: String,
    log: PathBuf,
    started_at: i64,
    child: std::process::Child,
}

static RUNNING: OnceLock<Mutex<HashMap<i64, Running>>> = OnceLock::new();

fn running() -> &'static Mutex<HashMap<i64, Running>> {
    RUNNING.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 插件装的那份 frpc：`<data>/plugins/qookix-frpc/<版本>/libs/libfrpc.so`，取版本最高的一份。
fn plugin_frpc(data_dir: &str) -> Option<PathBuf> {
    let root = crate::plugin::plugins_root(data_dir).join("qookix-frpc");
    let mut best: Option<(String, PathBuf)> = None;
    for entry in std::fs::read_dir(&root).ok()?.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        // 安装中的半成品目录（`.tmp`）不算
        if name.ends_with(".tmp") {
            continue;
        }
        let p = entry.path().join("libs").join("libfrpc.so");
        if !p.is_file() {
            continue;
        }
        if best
            .as_ref()
            .map(|(v, _)| name.as_str() > v.as_str())
            .unwrap_or(true)
        {
            best = Some((name, p));
        }
    }
    best.map(|(_, p)| p)
}

/// frpc 可执行文件的位置：**APK 原生库目录那份优先**。
///
/// ⚠️ 为什么不是"插件优先"（2026-10-11 真机实测推翻）：
/// 安卓 10+ 的 W^X **只允许执行 APK 的 `lib/<abi>/` 里的文件**，应用可写目录
/// （插件装到 `<data>/plugins/...`）里的可执行文件**以 App 身份 execve 会被拒**。
/// 我一开始用 `run-as` 测出"能跑"，那是错的 —— `run-as` 落在 `u:r:runas_app:s0` 域，
/// 和 App 自己的 `untrusted_app` 不是同一个域，权限也不同。
/// 所以插件里那份 frpc 目前**执行不了**，只能当兜底占位（`plugin_frpc` 保留，
/// 等以后把 frpc 编译成**可加载的 .so**（Go `-buildmode=c-shared` + 一层 C 入口）再用 ——
/// dlopen 应用可写目录里的 .so 是允许的（渲染器插件就是这么加载的）。
async fn frpc_binary() -> Result<PathBuf> {
    #[cfg(target_os = "android")]
    {
        let dir = crate::android_env::native_lib_dir()
            .ok_or_else(|| anyhow!("拿不到 APK 的原生库目录"))?;
        let p = PathBuf::from(dir).join("libfrpc.so");
        if p.is_file() {
            return Ok(p);
        }
        if let Ok(data_dir) = crate::settings::get_data_dir().await {
            if let Some(pp) = plugin_frpc(&data_dir) {
                // 补可执行位（zip 解压不一定保留），但这条路上 exec 大概率仍会被 W^X 拒
                #[cfg(unix)]
                {
                    use std::os::unix::fs::PermissionsExt;
                    let _ = std::fs::set_permissions(&pp, std::fs::Permissions::from_mode(0o755));
                }
                return Ok(pp);
            }
        }
        Err(anyhow!(
            "没有可用的 frpc：APK 里没有（当前 CPU 架构可能没随包），插件那份也没有"
        ))
    }
    #[cfg(not(target_os = "android"))]
    {
        Err(anyhow!("frpc 只能在手机上运行"))
    }
}

fn frp_root(data_dir: &str) -> PathBuf {
    PathBuf::from(data_dir).join("frp")
}

/// 执行 frpc 的 `--version`：既是功能自检，也是「这个设备到底允许不允许 exec」的探针。
pub async fn probe() -> Result<String> {
    let bin = frpc_binary().await?;
    // 把「用的是哪一份」也带回去：插件那份和 APK 那份都能跑，出问题时第一个要问的就是这个
    // （插件优先、APK 兜底，见 frpc_binary）
    let bin_show = bin.to_string_lossy().to_string();
    let out = tokio::task::spawn_blocking(move || {
        std::process::Command::new(&bin).arg("--version").output()
    })
    .await
    .map_err(|e| anyhow!("自检任务失败：{e}"))?
    .with_context(|| "执行 frpc 失败（这台设备可能不允许执行这个位置的二进制）")?;
    let mut text = String::from_utf8_lossy(&out.stdout).trim().to_string();
    let err = String::from_utf8_lossy(&out.stderr).trim().to_string();
    if text.is_empty() {
        text = err;
    }
    if !out.status.success() {
        return Err(anyhow!("frpc 自检退出码 {}：{}", out.status, brief(&text)));
    }
    let where_ = if bin_show.contains("/plugins/") {
        "插件"
    } else {
        "随包"
    };
    Ok(format!("{text}（{where_}）"))
}

/// 从面板取服务端拼好的配置（已含认证 Token，优先用这份）
async fn server_config(stored: &Stored, tunnel_id: i64) -> Result<String> {
    let c = client().await?;
    let url = format!(
        "{}/frps/frpc_download.php?tunnel_id={tunnel_id}&reset_key=0&format=json",
        base_url(&stored.api_base)
    );
    let data = get(&c, &url, Some(&stored.token)).await?;
    let cfg = flex_str(data.get("config"));
    if cfg.trim().is_empty() {
        return Err(anyhow!("面板没有下发配置"));
    }
    Ok(cfg)
}

/// 面板下不出配置时的兜底：按隧道信息本地拼一份 frpc 配置。
/// 认证用的 frp token 就用面板 Token（面板把两者当同一个东西）。
fn local_config(stored: &Stored, t: &TunnelInfo) -> Result<String> {
    if t.node_hostname.trim().is_empty() || t.node_port <= 0 {
        return Err(anyhow!("隧道缺少节点信息，无法本地生成配置"));
    }
    let mut s = String::new();
    s.push_str("# 由 QookiX 启动器根据隧道信息生成\n");
    s.push_str(&format!("serverAddr = \"{}\"\n", t.node_hostname));
    s.push_str(&format!("serverPort = {}\n\n", t.node_port));
    s.push_str("auth.method = \"token\"\n");
    s.push_str(&format!("auth.token = \"{}\"\n\n", stored.token));
    s.push_str("[log]\nto = \"console\"\nlevel = \"info\"\n\n");
    s.push_str("[[proxies]]\n");
    s.push_str(&format!("name = \"{}\"\n", t.name));
    s.push_str(&format!("type = \"{}\"\n", t.protocol));
    s.push_str(&format!("localIP = \"{}\"\n", t.local_ip));
    s.push_str(&format!("localPort = {}\n", t.local_port));
    if t.remote_port > 0 {
        s.push_str(&format!("remotePort = {}\n", t.remote_port));
    }
    if !t.custom_domain.trim().is_empty() {
        s.push_str(&format!("customDomains = [\"{}\"]\n", t.custom_domain));
    }
    Ok(s)
}

fn is_ip_literal(s: &str) -> bool {
    s.parse::<std::net::IpAddr>().is_ok()
}

/// 把配置里的 `serverAddr` 换成**已解析的 IP**。
///
/// 为什么必须自己解析：frpc 是静态链接的 Go 程序，它用自己的解析器读 `/etc/resolv.conf` ——
/// 在安卓上那个文件不存在/指向本机 DNS，实测直接报
/// `lookup <节点> on [::1]:53: connection refused`（App 自己的请求没问题，因为走的是系统解析器）。
/// 所以在启动 frpc 之前，我们先用系统解析器（和登录同一条路）拿到 IP 写进配置。
///
/// ⚠️ 副作用：这样子会绕过「按域名分流」的代理规则（走 IP 直连）。对 frp 的国内/香港节点
/// 通常更稳，但如果哪天线路上只有按域名分流的代理能通，这里就是嫌疑点。
/// 解析不了就原样返回，交给 frpc 自己试 —— 瞎改只会更难查。
async fn resolve_server_addr(config: &str, fallback_host: &str) -> String {
    let host = config
        .lines()
        .find_map(|l| {
            let rest = l.trim().strip_prefix("serverAddr")?.trim_start();
            let rest = rest.strip_prefix('=')?.trim();
            Some(rest.trim_matches('"').trim().to_string())
        })
        .filter(|h| !h.is_empty())
        .unwrap_or_else(|| fallback_host.trim().to_string());
    if host.is_empty() || is_ip_literal(&host) {
        return config.to_string();
    }
    // 走 `spawn_blocking` + std 的 `to_socket_addrs`：getaddrinfo 是阻塞调用，
    // 不该占着异步线程；而且这样也不用和 tokio 那个会借用入参的返回迭代器纠缠。
    let host_for_std = host.clone();
    let resolved: Option<String> = tokio::task::spawn_blocking(move || {
        use std::net::ToSocketAddrs;
        (host_for_std.as_str(), 0u16)
            .to_socket_addrs()
            .ok()
            .and_then(|mut it| it.next())
            .map(|a| a.ip().to_string())
    })
    .await
    .ok()
    .flatten();

    let Some(ip) = resolved else {
        tracing::warn!("解析节点地址 {host} 失败，交给 frpc 自己处理");
        return config.to_string();
    };
    tracing::info!("隧道节点 {host} 解析为 {ip}（安卓上 Go 自带解析器不可用，先在这里解析）");
    config
        .lines()
        .map(|l| {
            if l.trim_start().starts_with("serverAddr") {
                let indent = &l[..l.len() - l.trim_start().len()];
                format!("{indent}serverAddr = \"{ip}\"")
            } else {
                l.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// 启动一条隧道。已经在跑的直接返回（不重复拉起）。
/// 把一份配置写到盘上、起 frpc、确认它没立刻退出，并登记进运行表（返回给前端的信息）。
///
/// 抽出来是因为**两种模式共用它**：TCP 直连隧道（配置由面板下发）与 XTCP 联机房间
/// （房主端按房间详情里的隧道字段拼、访问端拼访问器配置）。复制一份 spawn/日志/存活检查
/// 等于给自己留两个会各自走偏的实现 —— 这次会话里已经因为"同一个操作两套写法"踩过坑。
async fn start_with_config(id: i64, name: &str, addr: &str, config: &str) -> Result<Value> {
    let data_dir = crate::settings::get_data_dir().await?;
    let root = frp_root(&data_dir);
    let stem = crate::util::safe_stem(name);
    let tunnels_dir = root.join("tunnels");
    let logs_dir = root.join("logs");
    std::fs::create_dir_all(&tunnels_dir).context("创建隧道配置目录失败")?;
    std::fs::create_dir_all(&logs_dir).context("创建隧道日志目录失败")?;
    let cfg_path = tunnels_dir.join(format!("{stem}.toml"));
    let log_path = logs_dir.join(format!("{stem}.log"));
    std::fs::write(&cfg_path, config)
        .with_context(|| format!("写入配置失败 {}", cfg_path.display()))?;

    let bin = frpc_binary().await?;
    let stdout = std::fs::File::create(&log_path)
        .with_context(|| format!("创建日志失败 {}", log_path.display()))?;
    let stderr = stdout.try_clone().context("复制日志句柄失败")?;

    // 只传 `-c`：`--no-banner` 之类装饰性参数一旦不被这个版本支持，进程会立刻退出，
    // 而横幅写进日志文件完全无害。
    let mut child = std::process::Command::new(&bin)
        .arg("-c")
        .arg(&cfg_path)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::from(stdout))
        .stderr(std::process::Stdio::from(stderr))
        .spawn()
        .with_context(|| format!("启动 frpc 失败：{}", bin.display()))?;

    // 起来之后等一下再看它活着没：立刻退出的情况（token/节点不对）要当场把日志告诉用户，
    // 否则界面上显示「已启动」、实际什么都没发生。
    tokio::time::sleep(Duration::from_millis(900)).await;
    match child.try_wait() {
        Ok(Some(status)) => {
            let tail = read_log_tail(&log_path, 20);
            return Err(anyhow!("frpc 启动后立刻退出（{status}）：\n{tail}"));
        }
        Ok(None) => {}
        Err(e) => {
            tracing::warn!("查询 frpc 状态失败：{e}");
        }
    }

    if let Ok(mut g) = running().lock() {
        g.insert(
            id,
            Running {
                name: name.to_string(),
                addr: addr.to_string(),
                log: log_path,
                started_at: now_secs(),
                child,
            },
        );
    }
    tracing::info!("frpc 已启动：{name} -> {addr}");
    Ok(json!({ "name": name, "address": addr, "alreadyRunning": false }))
}

pub async fn start_tunnel(tunnel_id: i64) -> Result<Value> {
    if let Ok(g) = running().lock() {
        if let Some(r) = g.get(&tunnel_id) {
            return Ok(json!({ "name": r.name, "address": r.addr, "alreadyRunning": true }));
        }
    }

    let stored = require_auth().await?;
    let all = tunnels().await?;
    let t = all
        .into_iter()
        .find(|t| t.id == tunnel_id)
        .ok_or_else(|| anyhow!("找不到这条隧道（{tunnel_id}），刷新一下列表"))?;
    if t.status == "expired" || t.status == "disabled" {
        // 不拦着，但要提醒 —— 面板侧这类隧道通常连不上
        tracing::warn!("隧道 {} 状态为 {}，可能连不上", t.name, t.status);
    }

    let config = match server_config(&stored, tunnel_id).await {
        Ok(c) => c,
        Err(e) => {
            tracing::warn!("面板下发配置失败，改用本地拼装：{e}");
            local_config(&stored, &t)?
        }
    };
    // 面板下发的与本地拼的配置里，serverAddr 都是域名 —— 见 resolve_server_addr 的注释：
    // frpc 在安卓上自己解析不了，这里换成 IP 再交给它。
    let config = resolve_server_addr(&config, &t.node_hostname).await;

    start_with_config(tunnel_id, &t.name, &t.address, &config).await
}

pub fn stop_tunnel(tunnel_id: i64) -> Result<()> {
    let mut entry = {
        let mut g = running().lock().map_err(|_| anyhow!("内部状态异常"))?;
        g.remove(&tunnel_id)
    };
    if let Some(r) = entry.as_mut() {
        let _ = r.child.kill();
        let _ = r.child.wait();
        tracing::info!("隧道已停止：{}", r.name);
    }
    Ok(())
}

/// 正在运行的隧道（顺便回收已经自己退出的）
pub fn running_list() -> Vec<Value> {
    let mut g = match running().lock() {
        Ok(g) => g,
        Err(_) => return Vec::new(),
    };
    let mut dead: Vec<i64> = Vec::new();
    let mut out: Vec<Value> = Vec::new();
    for (id, r) in g.iter_mut() {
        match r.child.try_wait() {
            Ok(Some(status)) => {
                dead.push(*id);
                let tail = read_log_tail(&r.log, 8);
                out.push(json!({
                    "tunnelId": id,
                    "name": r.name,
                    "address": r.addr,
                    "startedAt": r.started_at,
                    "alive": false,
                    "exit": status.to_string(),
                    "logTail": tail,
                }));
            }
            _ => out.push(json!({
                "tunnelId": id,
                "name": r.name,
                "address": r.addr,
                "startedAt": r.started_at,
                "alive": true,
            })),
        }
    }
    for id in dead {
        g.remove(&id);
    }
    out
}

pub fn tunnel_log(tunnel_id: i64) -> Result<String> {
    let path = {
        let g = running().lock().map_err(|_| anyhow!("内部状态异常"))?;
        g.get(&tunnel_id).map(|r| r.log.clone())
    };
    let Some(path) = path else {
        return Err(anyhow!("这条隧道没有在运行"));
    };
    Ok(read_log_tail(&path, 400))
}

fn read_log_tail(path: &PathBuf, lines: usize) -> String {
    let Ok(mut f) = std::fs::File::open(path) else {
        return String::new();
    };
    let mut text = String::new();
    if f.read_to_string(&mut text).is_err() {
        return String::new();
    }
    let all: Vec<&str> = text.lines().collect();
    let start = all.len().saturating_sub(lines);
    all[start..].join("\n")
}

// ==================== XTCP 联机房间 ====================
//
// TCP 直连（上面那套）只有房主跑 frpc，客人用任意客户端连 `节点:远端端口` 就行。
// 房间是另一种模式：房主跑一条 `xtcp` **代理**把本地端口暴露出去，访问者跑一条 `xtcp`
// **访问器**把它绑到自己机器的某个端口上 —— 也就是**双方都得跑 frpc**，
// 客人最终连的是 `127.0.0.1:<bindPort>`。
//
// 这里只负责面板那一半：节点 / 房间列表 / 详情 / 建房 / 进出房 / 关房，以及
// **拼访问端配置**（房主端配置由建房接口直接下发，不自己拼）。

/// 建房间可选用的节点
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomNode {
    pub id: i64,
    pub name: String,
    pub description: String,
    pub hostname: String,
    pub online: bool,
}

/// 房间列表 / 详情里的房间
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RoomInfo {
    pub id: i64,
    pub name: String,
    pub game_name: String,
    /// `waiting` / `playing` / `closed`
    pub status: String,
    pub max_players: i64,
    pub host_uid: i64,
    pub host_name: String,
    pub has_password: bool,
    pub description: String,
    /// `pending` = 审核中，别人搜不到（界面要如实说，别让人以为已经能联机了）
    pub review_status: String,
    pub review_reason: Option<String>,
    pub created_at: String,
    pub player_count: i64,
    pub is_host: bool,
}

/// 节点列表（建房时选节点用）
pub async fn room_nodes() -> Result<Vec<RoomNode>> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!("{}/info/nodes-public.php?pageSize=100", base_url(&stored.api_base));
    let data = get(&c, &url, Some(&stored.token)).await?;
    let Some(list) = data.as_array() else {
        return Ok(Vec::new());
    };
    Ok(list
        .iter()
        .map(|n| RoomNode {
            id: flex_i64(n.get("id")),
            name: flex_str(n.get("name")),
            description: flex_str(n.get("description")),
            hostname: flex_str(n.get("hostname")),
            online: n.get("online").and_then(|v| v.as_bool()).unwrap_or(false),
        })
        .collect())
}

/// 房间列表
pub async fn room_list(page: i64) -> Result<Value> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!(
        "{}/game/room_list.php?page={}&pageSize=50",
        base_url(&stored.api_base),
        page.max(1)
    );
    let data = get(&c, &url, Some(&stored.token)).await?;
    Ok(json!({
        "rooms": parse_rooms(data.get("rooms")),
        "total": flex_i64(data.get("total")),
        "page": flex_i64(data.get("page")),
        "pageSize": flex_i64(data.get("pageSize")),
    }))
}

fn parse_rooms(v: Option<&Value>) -> Vec<RoomInfo> {
    let Some(list) = v.and_then(|v| v.as_array()) else {
        return Vec::new();
    };
    list.iter()
        .map(|r| RoomInfo {
            id: flex_i64(r.get("id")),
            name: flex_str(r.get("name")),
            game_name: flex_str(r.get("game_name")),
            status: flex_str(r.get("status")),
            max_players: flex_i64(r.get("max_players")),
            host_uid: flex_i64(r.get("host_uid")),
            host_name: flex_str(r.get("host_name")),
            has_password: r
                .get("has_password")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            description: flex_str(r.get("description")),
            review_status: flex_str(r.get("review_status")),
            review_reason: {
                let s = flex_str(r.get("review_reason"));
                if s.trim().is_empty() {
                    None
                } else {
                    Some(s)
                }
            },
            created_at: flex_str(r.get("created_at")),
            player_count: flex_i64(r.get("player_count")),
            is_host: r.get("is_host").and_then(|v| v.as_bool()).unwrap_or(false),
        })
        .collect()
}

/// 房间详情（含 `tunnel`：房主建立后才有，里面有 `node_token` 与代理名/密钥）
pub async fn room_detail(room_id: i64) -> Result<Value> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!("{}/game/room_detail.php", base_url(&stored.api_base));
    let data = post_form(&c, &url, &[("room_id", room_id.to_string())]).await?;
    Ok(data)
}

/// 建房。面板会返回 `host_config`（房主端 frpc 配置）与 `review_status`。
pub async fn room_create(
    name: String,
    node_id: i64,
    local_port: i64,
    password: Option<String>,
) -> Result<Value> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!("{}/game/create_room.php", base_url(&stored.api_base));
    let mut form: Vec<(&str, String)> = vec![
        ("room_name", name),
        ("node_id", node_id.to_string()),
        ("local_port", local_port.to_string()),
        ("game_name", "Minecraft".to_string()),
    ];
    if let Some(pw) = password.filter(|p| !p.is_empty()) {
        form.push(("password", pw));
    }
    post_form(&c, &url, &form).await
}

pub async fn room_join(room_id: i64, password: Option<String>) -> Result<Value> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!("{}/game/join_room.php", base_url(&stored.api_base));
    let mut form: Vec<(&str, String)> = vec![("room_id", room_id.to_string())];
    if let Some(pw) = password.filter(|p| !p.is_empty()) {
        form.push(("password", pw));
    }
    post_form(&c, &url, &form).await
}

pub async fn room_leave(room_id: i64) -> Result<Value> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!("{}/game/leave_room.php", base_url(&stored.api_base));
    post_form(&c, &url, &[("room_id", room_id.to_string())]).await
}

/// 关房（仅房主）
pub async fn room_close(room_id: i64) -> Result<Value> {
    let stored = require_auth().await?;
    let c = client().await?;
    let url = format!("{}/game/close_room.php", base_url(&stored.api_base));
    post_form(&c, &url, &[("room_id", room_id.to_string())]).await
}

/// 访问端要绑定的本地端口：`localPort + 10000 + (uid % 1000)`，越界退回 `25565 + (uid % 1000)`。
///
/// 必须和官网客户端算得一模一样 —— 算错的话，客人的游戏会去连一个没人监听的端口，
/// 表现是「加不进去」但看不出原因。
pub fn visitor_bind_port(local_port: i64, uid: i64) -> i64 {
    let offset = uid.rem_euclid(1000);
    let candidate = local_port + 10000 + offset;
    if (1..=65535).contains(&candidate) {
        candidate
    } else {
        25565 + offset
    }
}

/// 访问端 frpc 配置（TOML）。房主端用面板下发的 `host_config`，不在这里拼。
#[allow(clippy::too_many_arguments)]
pub fn visitor_config(
    server_addr: &str,
    server_port: i64,
    token: &str,
    proxy_name: &str,
    secret_key: &str,
    visitor_name: &str,
    bind_port: i64,
) -> String {
    format!(
        "serverAddr = \"{server_addr}\"\n\
         serverPort = {server_port}\n\
         auth.token = \"{token}\"\n\
         \n\
         [[visitors]]\n\
         name = \"{visitor_name}\"\n\
         type = \"xtcp\"\n\
         serverName = \"{proxy_name}\"\n\
         secretKey = \"{secret_key}\"\n\
         bindAddr = \"127.0.0.1\"\n\
         bindPort = {bind_port}\n\
         transport.useEncryption = false\n\
         transport.useCompression = false\n"
    )
}

/// 房主端 frpc 配置（XTCP 代理）。按房间详情里的隧道字段拼 ——
/// 建房接口也会下发一份 `host_config`，这里用它兜底（换设备/重启后重建时用得上）。
#[allow(clippy::too_many_arguments)]
pub fn host_config(
    server_addr: &str,
    server_port: i64,
    token: &str,
    proxy_name: &str,
    secret_key: &str,
    user_signature: &str,
    local_ip: &str,
    local_port: i64,
) -> String {
    let local_ip = if local_ip.trim().is_empty() {
        "127.0.0.1"
    } else {
        local_ip
    };
    format!(
        "serverAddr = \"{server_addr}\"\n\
         serverPort = {server_port}\n\
         auth.token = \"{token}\"\n\
         \n\
         [[proxies]]\n\
         name = \"{proxy_name}\"\n\
         type = \"xtcp\"\n\
         localIP = \"{local_ip}\"\n\
         localPort = {local_port}\n\
         secretKey = \"{secret_key}\"\n\
         metadatas.user = \"{user_signature}\"\n\
         metadatas.vkey = \"{secret_key}\"\n\
         transport.useEncryption = false\n\
         transport.useCompression = false\n"
    )
}

/// 取出房间详情里的隧道信息。`tunnel` 可能是 null（房间还在审核、或隧道没建好），
/// 这时不能当成"可以启动"，要如实告诉用户。
fn room_tunnel(detail: &Value) -> Result<Value> {
    let tunnel = detail.get("tunnel").cloned().unwrap_or(Value::Null);
    if tunnel.is_null() {
        anyhow::bail!("这个房间还没有隧道信息 —— 可能仍在审核中，通过后再试");
    }
    Ok(tunnel)
}

/// 房主：起 frpc，把本地端口经 XTCP 暴露出去。
pub async fn room_host_start(room_id: i64) -> Result<Value> {
    let detail = room_detail(room_id).await?;
    let tunnel = room_tunnel(&detail)?;
    let tunnel_id = flex_i64(tunnel.get("id"));
    let local_ip = {
        let s = flex_str(tunnel.get("local_ip"));
        if s.trim().is_empty() {
            "127.0.0.1".to_string()
        } else {
            s
        }
    };
    let local_port = flex_i64(tunnel.get("local_port"));
    let host = flex_str(tunnel.get("node_hostname"));
    let name = {
        let n = flex_str(tunnel.get("proxy_name"));
        if n.trim().is_empty() {
            format!("room-{room_id}")
        } else {
            n
        }
    };
    let config = host_config(
        &host,
        flex_i64(tunnel.get("node_port")),
        &flex_str(tunnel.get("node_token")),
        &name,
        &flex_str(tunnel.get("validate_key")),
        &flex_str(tunnel.get("user_signature")),
        &local_ip,
        local_port,
    );
    let config = resolve_server_addr(&config, &host).await;
    let local = format!("{local_ip}:{local_port}");
    let running = start_with_config(tunnel_id, &name, &local, &config).await?;
    Ok(json!({
        "roomId": room_id,
        "tunnelId": tunnel_id,
        "name": name,
        "local": local,
        "running": running,
    }))
}

/// 访问者：把房主的 XTCP 代理绑到本地端口；调用方要把 `connect` 给用户去连。
pub async fn room_visitor_start(room_id: i64) -> Result<Value> {
    let detail = room_detail(room_id).await?;
    let tunnel = room_tunnel(&detail)?;
    let tunnel_id = flex_i64(tunnel.get("id"));
    // 绑定端口必须按**自己**的 uid 算（官网客户端同款算法），算错客人就连不上
    let uid = detail
        .get("players")
        .and_then(|p| p.as_array())
        .and_then(|arr| {
            arr.iter()
                .find(|p| p.get("is_self").and_then(|v| v.as_bool()).unwrap_or(false))
                .map(|p| flex_i64(p.get("uid")))
        })
        .unwrap_or(0);
    let bind_port = visitor_bind_port(flex_i64(tunnel.get("local_port")), uid);
    let host = flex_str(tunnel.get("node_hostname"));
    let name = format!("room-{room_id}-visitor");
    let config = visitor_config(
        &host,
        flex_i64(tunnel.get("node_port")),
        &flex_str(tunnel.get("node_token")),
        &flex_str(tunnel.get("proxy_name")),
        &flex_str(tunnel.get("validate_key")),
        &name,
        bind_port,
    );
    let config = resolve_server_addr(&config, &host).await;
    let connect = format!("127.0.0.1:{bind_port}");
    let running = start_with_config(tunnel_id, &name, &connect, &config).await?;
    Ok(json!({
        "roomId": room_id,
        "tunnelId": tunnel_id,
        "bindPort": bind_port,
        "connect": connect,
        "running": running,
    }))
}

/// 停掉房间的 frpc。房主端与访问端共用同一个隧道 id，所以一条命令够。
pub async fn room_stop(room_id: i64) -> Result<Value> {
    let detail = room_detail(room_id).await?;
    let tunnel = room_tunnel(&detail)?;
    let tunnel_id = flex_i64(tunnel.get("id"));
    stop_tunnel(tunnel_id)?;
    Ok(json!({ "roomId": room_id, "tunnelId": tunnel_id }))
}
