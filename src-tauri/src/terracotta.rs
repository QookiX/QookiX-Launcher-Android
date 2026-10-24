//! 陶瓦联机（Terracotta）隧道客户端 —— **只走 HTTP，不链接那个 .so**。
//!
//! ## 为什么要分进程 + HTTP
//!
//! Terracotta 是 AGPL-3.0-or-later，但它 README 里给了例外：只要「打包未修改的二进制
//! 且不静态/动态链接」，或「通过 IPC 与未修改的程序交互并在界面署名」，就不被 AGPL 涵盖。
//! 我们走第二条（也正是桌面版 `Terracotta.exe` + localhost HTTP 的做法）：
//! `.so` 只在 `:tunnel` 独立进程里加载（见 `TerracottaTunnelService.kt`），
//! 主进程**只发 HTTP 请求**，因此启动器保持 GPL-3.0。署名见「设置 → 关于 → 第三方声明」。
//!
//! 端口由 `:tunnel` 进程写进 `filesDir/tunnel_port`（同一应用的数据目录跨进程共享），
//! 主进程读文件即可，无需新增 JNI 调用。

use std::path::PathBuf;

/// 隧道 HTTP 端口文件名（与 Kotlin 侧约定一致）
const PORT_FILE: &str = "tunnel_port";

/// 隧道不可达时给用户看的话。原来的做法是把 reqwest 的原始报错直接抛出去，
/// 用户看到的是 `error sending request for url (http://127.0.0.1:37889/host?player=…)`
/// 这种完全看不懂的东西。
///
/// **注意别再写成「请完全退出应用后重试」**：绝大多数情况隧道只是**还在启动**
/// （冷启动要 `loadLibrary` + 初始化 EasyTier，手机上同时跑着服务器 JVM 时更慢），
/// 照着「完全退出应用」去提示会把用户引到错误的操作上。
const TUNNEL_DOWN: &str = "隧道还在启动中，请稍后重试；若一直没反应请完全退出应用后重试";

/// 读隧道端口；服务没起来时返回 None
fn tunnel_port() -> Option<u16> {
    let dir = crate::settings::data_dir_sync()?;
    let p = PathBuf::from(dir).join(PORT_FILE);
    let s = std::fs::read_to_string(p).ok()?;
    s.trim().parse::<u16>().ok()
}

/// 删掉端口文件。
///
/// **为什么必须删**：端口文件是上一次隧道进程写下的，进程没了文件还在。于是
/// `tunnel_port()` 照样返回一个「看起来很对」的端口，请求打过去却被连接拒绝 ——
/// 表现就是「点了开启房间就报错，而且怎么重试都报同一个错」。删掉之后
/// `tunnel_port()` 返回 None，界面才会正确显示「隧道未启动」。
fn clear_port_file() {
    if let Some(dir) = crate::settings::data_dir_sync() {
        let _ = std::fs::remove_file(PathBuf::from(dir).join(PORT_FILE));
        tracing::warn!("[terracotta] 隧道不可达，已清理过期端口文件");
    }
}

/// 隧道连不上时的**自愈**：清掉过期端口文件、叫 Activity 再 `startService` 一次
/// （服务那边是「按需重绑」，见 `TerracottaTunnelService.onStartCommand`），然后等它
/// 重新监听并重写端口文件。返回 `true` 表示隧道已经回来，调用方可以重试。
///
/// 真机踩过的坑：`:tunnel` 进程被 libterracotta 的原生线程吊着，**Service 停了进程也不退出**，
/// 于是出现「进程在、但 HTTP 套接字已经关了」的状态，而 `tunnel_port` 还留着旧端口 ——
/// 那个端口根本没有进程在监听（`/proc/net/tcp` 里查不到），主进程每次请求都吃连接被拒，
/// 界面表现就是点「开启房间」报一条看不懂的错、怎么重试都一样。
/// 现在这样就不必用户「完全退出应用」了。
async fn heal() -> bool {
    clear_port_file();
    #[cfg(target_os = "android")]
    crate::android_bridge::ensure_tunnel_service();
    // 最多等约 10 秒。冷启动时 Terracotta 要 `loadLibrary` + 初始化 EasyTier
    // （实测手机上同时跑着 1.25GB 的服务器 JVM 时要好几秒），**只等 1.5 秒会误报
    // 「请完全退出应用后重试」** —— 用户其实只是点得太早。已经在跑的情况一两次就返回。
    for _ in 0..50 {
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        if let Some(p) = tunnel_port() {
            if get_text(&format!("http://127.0.0.1:{p}/ping")).await.is_ok() {
                return true;
            }
        }
    }
    false
}

/// 是否已获得陶瓦联机的 VPN 授权（Android 上没有 TUN 就没有数据面，客人连不进来）。
pub async fn terracotta_vpn_granted() -> Result<bool, String> {
    #[cfg(target_os = "android")]
    {
        Ok(crate::android_bridge::vpn_consent_granted())
    }
    #[cfg(not(target_os = "android"))]
    {
        Ok(true)
    }
}

/// 弹 VPN 授权对话框。开房前先调用它 —— Terracotta 的请求只有 30 秒答复窗口。
pub async fn terracotta_request_vpn() -> Result<(), String> {
    #[cfg(target_os = "android")]
    crate::android_bridge::ensure_vpn_consent();
    Ok(())
}

/// 隧道是否已就绪（HTTP 服务在监听）
pub async fn terracotta_ping() -> Result<bool, String> {
    if tunnel_port().is_none() {
        // 连端口文件都没有：可能是隧道从没起来，也可能是它停了但文件被清了。
        // 拉一次让它自己写端口文件，本次仍按「未就绪」返回（前端会继续轮询）。
        heal().await;
        return Ok(false);
    }
    let port = tunnel_port().unwrap_or_default();
    match get_text(&format!("http://127.0.0.1:{port}/ping")).await {
        Ok(body) => Ok(body.contains("\"ready\":true")),
        Err(_) => {
            // 端口文件过期 → 自愈；别让每次 ping 都抱着一个死端口
            heal().await;
            Ok(false)
        }
    }
}

/// 调隧道的一个接口（原样返回 JSON 字符串）。
/// `path` 形如 `/state`、`/host?player=名字`。
///
/// 注意：这里**只做转发**，不含任何 Terracotta 逻辑，也不加载它的库。
pub async fn terracotta_request(path: String) -> Result<String, String> {
    if !path.starts_with('/') {
        return Err("接口路径必须以 / 开头".to_string());
    }
    let port = match tunnel_port() {
        Some(p) => p,
        None => {
            // 端口文件都没有：可能是隧道从没起来，也可能被清掉了/是上一代进程写的。
            // 先自愈（重绑 + 重写文件）再试一次。
            if !heal().await {
                return Err(TUNNEL_DOWN.to_string());
            }
            tunnel_port().ok_or_else(|| TUNNEL_DOWN.to_string())?
        }
    };
    let url = format!("http://127.0.0.1:{port}{path}");
    // 全部按 POST 处理：Terracotta 侧的接口都是「动作」，GET 只用于 /state、/ping、/logs，
    // 而这些用 POST 调也没问题，省得分支。
    match post_text(&url).await {
        Ok(body) => Ok(body),
        Err(e) => {
            if !(e.is_connect() || e.is_timeout()) {
                return Err(format!("隧道请求失败: {e}"));
            }
            if heal().await {
                if let Some(p) = tunnel_port() {
                    return post_text(&format!("http://127.0.0.1:{p}{path}"))
                        .await
                        .map_err(|e| format!("隧道请求失败: {e}"));
                }
            }
            Err(TUNNEL_DOWN.to_string())
        }
    }
}

async fn get_text(url: &str) -> Result<String, reqwest::Error> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(8))
        .build()?;
    let resp = client.get(url).send().await?;
    resp.text().await
}

async fn post_text(url: &str) -> Result<String, reqwest::Error> {
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(20))
        .build()?;
    let resp = client
        .post(url)
        .header("Content-Type", "application/json")
        .body("{}")
        .send()
        .await?;
    resp.text().await
}
