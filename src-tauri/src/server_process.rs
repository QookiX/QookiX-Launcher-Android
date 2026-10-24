//! 服务端运行进程（跑在 `:server` 独立进程里）。
//!
//! # 为什么要独立进程
//!
//! 服务端和游戏都是 JVM，各自在**自己进程**里建 VM。放在主进程会有两个致命问题：
//!
//! 1. 服务端吃内存把系统 OOM 判定打到整个 App → **UI 一起死**（用户在设置界面
//!    被系统杀掉，服务器也没了）。
//! 2. 服务端 core dump / JVM 崩了会带走 UI，表现为「开服后手机应用闪退」。
//!
//! 独立进程后：服务端崩了主进程还活着，UI 里能读到退出原因并提示。
//!
//! # 进程间通信
//!
//! 不做 AIDL/Binder（要额外 AIDL 编译），用最省事的 **localhost HTTP + 文件**：
//!
//! ```text
//! 主进程(Rust)                      :server 进程(Rust + Kotlin Service)
//!   │ 写 launch.json（启动参数）           │ onStartCommand 读 launch.json
//!   │ 调 Kotlin startServerService(id) ──→ │
//!   │                                     ├─ 起后台线程 JvmLauncher::launch()（阻塞）
//!   │                                     └─ 主线程跑 IPC HTTP：/status /stop /console
//!   │ 读 runtime.json（ipc 端口 + token）←──┤ 启动成功后写
//!   │ GET /status、POST /stop ────────────→ │
//!   │ 读 logs/latest.log（Paper 自己写的）   │
//! ```

use jni::objects::{JClass, JString};
use jni::sys::jint;
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::OnceLock;

/// 启动参数：主进程写进 `launch.json`，`:server` 进程读。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LaunchSpec {
    pub id: String,
    pub jre_home: String,
    pub jar: String,
    pub xmx_mb: u32,
    /// -Xms（launch 时会夹到 [256, xmx_mb]）
    pub xmin_mb: u32,
    pub work_dir: String,
    /// 传给 MinecraftServer 的参数，第一项通常是 `nogui`
    pub args: Vec<String>,
    /// 随机 token，IPC 请求必须带上，防同机其它应用乱调
    pub token: String,
}

/// 运行态：`:server` 进程启动成功后写 `runtime.json`，主进程据此判断「在跑」并找到 IPC 端口。
///
/// **字段名必须是 camelCase**：这份文件由 Kotlin（`ServerService`）先写、
/// Rust 之后可能再改写，两边都用 camelCase 才能互通。
/// 之前 Rust 侧按 snake_case（`ipc_port`）反序列化，读 Kotlin 写的文件直接失败，
/// 于是 `hosted_server_runtime` 永远返回 null —— 表现为「服明明在跑，UI 说没在跑」。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RuntimeInfo {
    pub id: String,
    pub pid: i32,
    pub ipc_port: u16,
    pub token: String,
    pub started_at: u64,
    /// 退出码 / 错误（JVM 结束后补写，UI 显示「已停止：xxx」）
    pub exit: Option<String>,
}

impl Default for RuntimeInfo {
    fn default() -> Self {
        Self {
            id: String::new(),
            pid: 0,
            ipc_port: 0,
            token: String::new(),
            started_at: 0,
            exit: None,
        }
    }
}

fn unix_now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn server_dir(id: &str) -> Result<PathBuf, String> {
    let base = crate::settings::data_dir_sync()
        .ok_or_else(|| "数据目录不可用（data_dir_sync 返回 None）".to_string())?;
    Ok(crate::settings::resolve_servers_root(Path::new(&base)).join(id))
}

/// 服务端 JVM 用的临时目录（保证存在、可写）。
///
/// **为什么不能用 `/tmp`**：Android 上 `/tmp` 是系统根的目录，应用进程既没有写权限、
/// 也不该往里写。默认情况下 JVM 的 `java.io.tmpdir` 就是它，于是任何用临时文件的库
/// 都会失败 —— 最典型的是 JNA（进而 oshi），Paper 启动时那一大屏
/// `JNA temporary directory '/tmp' is not writable` 就是这么来的。
///
/// 取值顺序：
///   1. `TMPDIR` 环境变量 —— Android 给每个应用进程都设了它，指向该应用的 cache 目录，
///      也就是和 Java 侧 `context.getCacheDir()` 同一个地方（`perf_counters.c` 也用它）；
///   2. 兜底：服务器目录下的 `cache/`（`:server` 进程切换 CWD 后仍能解析）。
fn server_tmp_dir(work_dir: &str) -> String {
    let dir = std::env::var("TMPDIR")
        .ok()
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| Path::new(work_dir).join("cache"));
    // 建不出来也不致命（真失败了 JNA 会照旧报错，但至少不是必然失败）
    let _ = std::fs::create_dir_all(&dir);
    dir.to_string_lossy().to_string()
}

/// 随包的 Android 版 JNA 原生库（`libjnidispatch.so`，各 ABI 一份，从 JNA 官方
/// AAR `net.java.dev.jna:jna:<版本>@aar` 的 `jni/<abi>/` 里原样抽出）。
#[cfg(target_os = "android")]
#[cfg(target_arch = "aarch64")]
const JNA_LIB: &[u8] = include_bytes!("../jna/arm64-v8a/libjnidispatch.so");
#[cfg(target_os = "android")]
#[cfg(target_arch = "x86_64")]
const JNA_LIB: &[u8] = include_bytes!("../jna/x86_64/libjnidispatch.so");

/// 把随包的 `libjnidispatch.so` 落到 `<数据目录>/jna/<arch>/`，返回该目录；拿不到就 None。
///
/// ## 为什么必须自带一份
///
/// 服务端（Paper 通过 oshi 采硬件信息）要用 JNA。JNA 启动时从自己 jar 里**按平台前缀**
/// 挑一份原生库解压出来加载，而平台判定看的是 `java.vm.name` 里有没有 `Dalvik` ——
/// 我们这套 JRE 是 OpenJDK 移植（不是 Dalvik），于是 JNA 认为自己在 Linux 上，挑了
/// `linux-aarch64` 那份 glibc 版本：
///
/// ```text
/// UnsatisfiedLinkError: ... dlopen failed: library "libc.so.6" not found
/// ```
///
/// Android 的 bionic 里只有 `libc.so`，没有 `libc.so.6`，所以必然失败（oshi 整个初始化
/// 不了）。竞品 Anvil-MC 的做法就是自带原生库 + 设 `jna.boot.library.path`
/// （它 dex 里有 `-Djna.boot.library.path=`）。该属性**优先于** JNA 自己的平台探测，
/// 于是平台判定绕过去了。
///
/// ## 版本必须与服务端下载的 jna.jar 对得上
///
/// JNA 只比对**主.次版本**（`Native.isCompatibleVersion`），不一致会抛
/// `JNA native support library version ... does not match expected version`。
/// 服务端那边的 jna.jar 由 Paper 自己下到
/// `servers/<id>/libraries/net/java/dev/jna/jna/<版本>/`（实测 26.x = **5.17.0**），
/// 随包这两份就是 5.17.0 的 AAR 抽出来的。换了核心/升级后若对不上，症状只是
/// 「JNA 依旧不可用」（等同没有这个修复），去那个目录看版本号即可。
fn ensure_jna_dir() -> Option<String> {
    if !cfg!(target_os = "android") {
        return None;
    }
    #[cfg(target_os = "android")]
    let lib: &[u8] = JNA_LIB;
    #[cfg(not(target_os = "android"))]
    let lib: &[u8] = &[];
    let dir = PathBuf::from(crate::settings::data_dir_sync()?)
        .join("jna")
        .join(std::env::consts::ARCH);
    let file = dir.join("libjnidispatch.so");
    // 已经是对的那份就不重写：每次启动都写一遍纯属白耗 IO。
    // 比大小就够 —— 这里只有「随包那一份」一种可能的来源。
    if std::fs::metadata(&file).map(|m| m.len()).ok() == Some(lib.len() as u64) {
        return Some(dir.to_string_lossy().to_string());
    }
    if std::fs::create_dir_all(&dir).is_err() {
        return None;
    }
    if let Err(e) = std::fs::write(&file, lib) {
        tracing::warn!("[server] 写 libjnidispatch.so 失败（{e}），JNA 可能仍不可用");
        return None;
    }
    tracing::info!("[server] 已就绪 Android 版 libjnidispatch.so：{}", file.display());
    Some(dir.to_string_lossy().to_string())
}

/// 设备当前**可用**内存（MB）。读 `/proc/meminfo` 的 `MemAvailable`。
///
/// 用 `MemAvailable` 而不是 `MemTotal`：后者把内核收回、内核仍保留的内存也算进来，
/// 会严重高估「真能拿来分配」的量 —— 这一点在手机上尤其致命（按 MemTotal 给 JVM 设堆，
/// 结果是系统先动手 lowmemorykiller）。
pub fn device_available_memory_mb() -> Option<u64> {
    let text = std::fs::read_to_string("/proc/meminfo").ok()?;
    for line in text.lines() {
        if let Some(rest) = line.strip_prefix("MemAvailable:") {
            let kb: u64 = rest.split_whitespace().next()?.parse().ok()?;
            return Some(kb / 1024);
        }
    }
    None
}

/// 服务端 `-Xmx` 的最终值：**不超过设备当前可用内存的 60%**，且不低于 512MB。
///
/// ## 为什么必须钳制
///
/// 手机上「总内存」不是能全部分给服务端的钱：主进程（Rust + WebView）要几百 MB、
/// `:tunnel` 陶瓦隧道几十 MB、系统与前台服务要留余量、还有相机/GPU 等其它进程。
/// 用户按「总内存的一半」填一个看着合理的数，JVM 会照 `-Xmx` 去预留，于是**系统先动手**：
/// lowmemorykiller 把 :server（甚至主进程）杀掉 —— 表现是「开服后过一会儿应用被杀 /
/// 服务器自己没了」，logcat 里能看到 `low watermark is breached`。
/// 竞品 Anvil-MC 也做了「内存钳制 + 内存看门狗」这一层。
///
/// 60% 是留给非服务端的余量；下限 512MB —— 低于这个数的 Minecraft 服务端基本跑不动
/// （加载世界就 OOM），与其让它起来就崩，不如给一个能活的最小值。
fn clamp_server_heap_mb(requested_mb: u32) -> u32 {
    const MIN_HEAP_MB: u32 = 512;
    // 读不到就**不干预**：宁可按用户填的值启动（顶多被杀），也不要凭空砍配置。
    let Some(avail_mb) = device_available_memory_mb() else {
        return requested_mb;
    };
    let cap = ((avail_mb as f64) * 0.6) as u32;
    requested_mb.min(cap.max(MIN_HEAP_MB))
}

fn rand_token() -> String {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    use std::sync::Mutex;
    // 同一个进程里连续调用要给出不同 token，所以加个自增计数器
    static COUNTER: Mutex<u64> = Mutex::new(0);
    let mut h = RandomState::new().build_hasher();
    h.write_u64(unix_now());
    let n = {
        let mut c = COUNTER.lock().unwrap_or_else(|e| e.into_inner());
        *c += 1;
        *c
    };
    h.write_u64(n);
    format!("{:016x}{:016x}", h.finish(), n)
}

/// 供主进程生成 IPC token（写进 launch.json）
pub fn new_token() -> String {
    rand_token()
}

/**
 * 读取 jar 的 `META-INF/MANIFEST.MF`（诊断用）。
 *
 * 单独暴露成命令是有血的教训：之前 `read_main_class` 读不到就返回 -3，
 * 但看不出是「jar 没有 Main-Class」还是「zip 解析失败」——两者在真机上都表现为
 * 同一个错误码。Paper 1.21.x 的 jar 里到底有没有 Main-Class、
 * zip crate 能不能正常打开 49MB 的包，都需要能直接看到原文才能判断。
 */
pub fn inspect_server_core_manifest(id: String) -> String {
    let dir = match server_dir(&id) {
        Ok(d) => d,
        Err(e) => return format!("目录错误: {e}"),
    };
    let jar = dir.join("server.jar");
    let meta = format!(
        "jar={} 存在={} 大小={}",
        jar.display(),
        jar.exists(),
        std::fs::metadata(&jar).map(|m| m.len()).unwrap_or(0)
    );
    let file = match std::fs::File::open(&jar) {
        Ok(f) => f,
        Err(e) => return format!("{meta}\n打开失败: {e}"),
    };
    let mut zip = match zip::ZipArchive::new(file) {
        Ok(z) => z,
        Err(e) => return format!("{meta}\nzip 解析失败: {e}"),
    };
    let mut out = format!("{meta}\n条目数={}", zip.len());
    // 先列出前几个条目，证明能正常遍历
    for i in 0..3.min(zip.len()) {
        if let Ok(e) = zip.by_index(i) {
            out.push_str(&format!("\n  [{}] {}", i, e.name()));
        }
    }
    // 先把可能的 manifest 名字都记下来（by_name 会借用 zip，所以要在它之前遍历）
    let mut candidates: Vec<String> = Vec::new();
    for i in 0..zip.len() {
        if let Ok(e) = zip.by_index(i) {
            let n = e.name().to_lowercase();
            if n.contains("manifest") {
                candidates.push(e.name().to_string());
            }
        }
    }
    if !candidates.is_empty() {
        out.push_str(&format!("\n含 manifest 的条目: {}", candidates.join(", ")));
    }

    let mut got = false;
    for name in ["META-INF/MANIFEST.MF", "meta.inf/manifest.mf"] {
        if let Ok(mut mf) = zip.by_name(name) {
            let mut text = String::new();
            match mf.read_to_string(&mut text) {
                Ok(_) => {
                    out.push_str(&format!("\n--- {name} ---\n"));
                    for line in text.lines().take(20) {
                        out.push_str(line);
                        out.push('\n');
                    }
                    got = true;
                }
                Err(e) => out.push_str(&format!("\n读 {name} 失败: {e}")),
            }
            break;
        }
    }
    if !got {
        out.push_str("\n两个候选名都没读到");
    }
    out
}

// ── 主类探测 ──────────────────────────────────────────────────────────

/**
 * 从 jar 的 `META-INF/MANIFEST.MF` 里读 `Main-Class`。
 *
 * 不硬编码主类：原版是 `net.minecraft.server.MinecraftServer`，
 * Paper/Spigot 是 `org.bukkit.craftbukkit.Main`，Fabric 又是另一个，
 * 而且 Paper 各版本改过好几次（1.20.5+ 包名带版本号后缀，如
 * `org.bukkit.craftbukkit.v1_21_R1.Main`）。硬编码列表必然漏。
 * 读 manifest 是唯一稳定做法。
 */
pub(crate) fn read_main_class(jar: &Path) -> Option<String> {
    let file = std::fs::File::open(jar).ok()?;
    let mut zip = zip::ZipArchive::new(file).ok()?;
    let mut mf = zip.by_name("META-INF/MANIFEST.MF").ok()?;
    let mut text = String::new();
    mf.read_to_string(&mut text).ok()?;

    // ⚠️ 2026-10-19：必须处理 manifest 的**续行**。
    //
    // MANIFEST 规范规定每行最多 72 字节，超长值会被拆成多行，
    // 续行以单个空格开头。`net.fabricmc.loader.impl.launch.knot.KnotServer`
    // 这类全限定名很容易超过阈值。原来的实现只取 `Main-Class:` 那一行，
    // 拿到的是**被截断的类名** —— 提交给 JLI 后必然 ClassNotFoundException。
    //（注释里写了"续行"，但代码从来没拼过。）
    let mut collecting: Option<String> = None;
    for line in text.lines() {
        let Some(v) = line.strip_prefix("Main-Class:") else {
            // 正在收集时，遇到以空格开头的行就当作续行拼接
            if let Some(buf) = collecting.as_mut() {
                if line.starts_with(' ') {
                    buf.push_str(line.trim_start());
                    continue;
                }
            }
            // 非续行：结束收集
            if collecting.is_some() {
                break;
            }
            continue;
        };
        collecting = Some(v.trim().to_string());
    }
    collecting.map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

// ── 极简 IPC HTTP ─────────────────────────────────────────────────────

fn http_ok(body: &str) -> String {
    format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
        body.as_bytes().len(),
        body
    )
}

fn http_404() -> &'static str {
    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
}

fn read_line_timeout(stream: &mut TcpStream, buf: &mut String) -> Option<String> {
    // **每次都从空开始**。之前沿用调用方的缓冲区，读第二行时把第一行内容
    // 累加进去，于是「读到空行」这个终止条件永远不成立（返回的是累积内容），
    // 服务端会卡在读 header 上直到超时 —— 客户端表现为「连接建立了但永远收不到响应」，
    // 状态查询与停服全部失效。
    buf.clear();
    stream
        .set_read_timeout(Some(std::time::Duration::from_secs(5)))
        .ok()?;
    let mut byte = [0u8; 1];
    loop {
        match stream.read(&mut byte) {
            Ok(0) => break,
            Ok(_) => {
                let ch = byte[0] as char;
                if ch == '\n' {
                    return Some(buf.clone());
                }
                if ch != '\r' {
                    buf.push(ch);
                }
                if buf.len() > 8192 {
                    return None;
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return if buf.is_empty() { None } else { Some(buf.clone()) },
        }
    }
    if buf.is_empty() {
        None
    } else {
        Some(buf.clone())
    }
}

/// IPC 服务：只认 `/status`（GET）与 `/stop`（POST），其余 404。
/// 跑在 `:server` 进程的一个后台线程里，主线程被 JVM 阻塞。
///
/// ## 端口由本函数自己定（bind 0）
///
/// 之前是 Kotlin 用 `ServerSocket` 试探一个「空闲」端口、关掉后把号传给 Rust 绑定。
/// 那样几乎必然失败：关闭后的端口处于 TIME_WAIT，而 Rust 的 `TcpListener::bind`
/// 默认**不设 SO_REUSEADDR**，于是 bind 报 Address already in use，
/// IPC 线程直接死掉 —— 表现是「服务端好好地跑着，但状态永远显示未运行、
/// 停服按钮没反应」，而且日志里一行都没有（tracing 没接到 stdout）。
/// 现在改成 bind(0) 让系统分配，再把真实端口回写 runtime.json。
/// IPC 线程的落盘日志。
///
/// 不能用 tracing：这个进程里没有初始化 tracing subscriber，日志一行都看不到
/// （「进程起来了但端口没监听，且毫无线索」就是这么来的）。
static IPC_DEBUG: OnceLock<std::path::PathBuf> = OnceLock::new();

fn dbg_log(msg: &str) {
    use std::io::Write as _;
    if let Some(p) = IPC_DEBUG.get() {
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(p) {
            let _ = writeln!(f, "[{:.3}] {msg}", unix_now() as f64);
        }
    }
    tracing::info!("[ipc] {msg}");
}

fn serve_ipc(
    want: u16,
    token: String,
    stop_flag: Arc<AtomicBool>,
    ready: Arc<std::sync::Mutex<u16>>,
) {
    let listener = TcpListener::bind(("127.0.0.1", want))
        .or_else(|e| {
            dbg_log(&format!("bind({want}) 失败: {e}，改用系统分配"));
            TcpListener::bind(("127.0.0.1", 0))
        });
    let listener = match listener {
        Ok(l) => l,
        Err(e) => {
            dbg_log(&format!("IPC 端口绑定彻底失败: {e}"));
            return;
        }
    };
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    if let Ok(mut g) = ready.lock() {
        *g = port;
    }
    dbg_log(&format!("已监听 127.0.0.1:{port}"));

    // 服务端运行期间可能几分钟都不该请求，poll 超时循环即可（不 accept 阻塞）
    if let Err(e) = listener.set_nonblocking(true) {
        dbg_log(&format!("set_nonblocking 失败: {e}"));
        return;
    }
    let mut spins: u64 = 0;
    while !stop_flag.load(Ordering::Acquire) {
        spins += 1;
        if spins % 50 == 0 {
            dbg_log(&format!("仍在监听（{spins} 次轮询）"));
        }
        match listener.accept() {
            Ok((mut stream, _)) => {
                dbg_log("收到连接");
                let _ = stream.set_nonblocking(false);
                let mut line = String::new();
                let req = match read_line_timeout(&mut stream, &mut line) {
                    Some(r) => r,
                    None => continue,
                };
                // 读掉 header 直到空行（body 忽略）
                let mut hdr_all = String::new();
                {
                    let mut hdr = String::new();
                    loop {
                        match read_line_timeout(&mut stream, &mut hdr) {
                            Some(l) if l.is_empty() => break,
                            Some(l) => {
                                hdr_all.push_str(&l);
                                hdr_all.push('\n');
                                continue;
                            }
                            None => break,
                        }
                    }
                }
                let mut parts = req.split_whitespace();
                let method = parts.next().unwrap_or("");
                let path = parts.next().unwrap_or("");
                // token 在 **header** 里（X-Token），不在请求行上。
                // 之前只拿请求行去 contains()，永远匹配不上 → 所有请求都吃 404。
                let mut all = req.clone();
                all.push('\n');
                all.push_str(&hdr_all);
                let authorized = all.contains(&format!("X-Token: {token}"));

                let resp = match (method, path) {
                    ("GET", "/status") if authorized => {
                        // 顺带报 JVM 是否还活着：UI 轮询这个判断「还在启动中 / 已就绪 / 已挂」
                        http_ok(&format!(
                            r#"{{"ok":true,"pid":{},"jvm":{},"started":{}}}"#,
                            std::process::id(),
                            if is_running() { 1 } else { 0 },
                            unix_now()
                        ))
                    }
                    ("POST", "/stop") if authorized => {
                        stop_flag.store(true, Ordering::Release);
                        // 走 JvmLauncher::shutdown：它用 System.exit(0) 让 JVM 自己跑
                        // shutdown hook（服务端才有机会存盘、关世界）。
                        // 它的 jre_home 是 launch() 成功时写进内部 state 的，
                        // 所以不需要在这里重新 dlopen —— 但**不能**查 libart.so，
                        // 那会拿到应用自己的 VM 并把整个 :server 进程拆掉
                        // （详见 jvm_launcher.rs 里 request_jvm_exit 的注释）。
                        match crate::jvm_launcher::JvmLauncher::shutdown() {
                            Ok(_) => http_ok(r#"{"ok":true,"stopping":true}"#),
                            Err(e) => {
                                tracing::warn!("[server] 优雅停服失败，交给 Android 杀进程: {e}");
                                http_ok(r#"{"ok":false,"fallback":"kill"}"#)
                            }
                        }
                    }
                    _ => http_404().to_string(),
                };
                let _ = stream.write_all(resp.as_bytes());
                let _ = stream.flush();
                dbg_log(&format!(
                    "{method} {path} auth={authorized} → {}",
                    resp.lines().next().unwrap_or("")
                ));
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                std::thread::sleep(std::time::Duration::from_millis(200));
            }
            // EINTR（被信号打断）必须重试而不是退出。
            // 之前这里是 `Err(_) => break`：JVM 起来后会装各种信号处理，
            // accept 被 EINTR 打断一次，整个 IPC 线程就退出了 ——
            // 端口随之消失，表现为「服务端好好跑着，但状态永远显示未运行、
            // 停服按钮毫无反应」，且日志里一行都没有。
            Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => {
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            Err(e) => {
                dbg_log(&format!("accept 错误，退出 IPC 循环: {e}"));
                break;
            }
        }
    }
    dbg_log("IPC 已停止");
}

use std::sync::Arc;

// ── 状态（`:server` 进程内） ──────────────────────────────────────────

static RUNNING: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static RUNTIME_PORT: OnceLock<std::sync::Mutex<u16>> = OnceLock::new();

fn running_flag() -> Arc<AtomicBool> {
    RUNNING
        .get_or_init(|| Arc::new(AtomicBool::new(false)))
        .clone()
}

/// 供 UI 轮询：`:server` 进程内当前是否有服在跑
pub fn is_running() -> bool {
    running_flag().load(Ordering::Acquire)
}

// ── Kotlin 调用的 JNI 入口 ────────────────────────────────────────────

/**
 * Kotlin `ServerService.nativeStart(...)` 调用。
 *
 * 立即返回（不阻塞）：启动参数交给后台线程跑 JVM，本进程主线程随后
 * 由 Kotlin 用来维持前台服务。返回值：0 成功，负数为错误码。
 */
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_services_ServerService_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    launch_json: JString,
    ipc_port: jint,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path: String = match env.get_string(&launch_json) {
            Ok(s) => s.to_str().unwrap_or("").to_string(),
            Err(_) => return -1,
        };
        let spec: LaunchSpec = match std::fs::read_to_string(&path)
            .ok()
            .and_then(|t| serde_json::from_str(&t).ok())
        {
            Some(s) => s,
            None => {
                tracing::error!("[server] launch.json 读取/解析失败: {path}");
                return -2;
            }
        };

        // 前台服务通知用：告诉主进程「我起来了」
        let work = PathBuf::from(&spec.work_dir);
        let _ = std::fs::create_dir_all(work.join("logs"));
        let _ = IPC_DEBUG.set(work.join("ipc-debug.log"));
        dbg_log(&format!("nativeStart: {} / {}", spec.id, spec.jar));

        // 落盘诊断：`tracing::error!` 在本项目**没有接到日志文件/logcat**，
        // 所以 rc=-3 这类失败在真机上完全无迹可寻。这里改用 log_line 落盘。
        let jar_path = Path::new(&spec.jar);
        // 双通道：log_line 落文件 + println 进 logcat
        //（`:server` 进程的 tracing 没接到 logcat，只能靠 println!）
        let diag = format!(
            "[server] nativeStart: id={} jar={} 存在={} 大小={}",
            spec.id,
            spec.jar,
            jar_path.exists(),
            std::fs::metadata(jar_path).map(|m| m.len()).unwrap_or(0)
        );
        crate::util::log_line(&diag);
        println!("{diag}");

        let main_class = match read_main_class(jar_path) {
            Some(m) => m,
            None => {
                // 把目录里实际有哪些 jar 一并记下来，方便判断是不是核心没装对
                let mut siblings: Vec<String> = std::fs::read_dir(&work)
                    .map(|rd| {
                        rd.flatten()
                            .filter(|e| {
                                e.file_name()
                                    .to_string_lossy()
                                    .to_ascii_lowercase()
                                    .ends_with(".jar")
                            })
                            .map(|e| e.file_name().to_string_lossy().to_string())
                            .collect()
                    })
                    .unwrap_or_default();
                siblings.sort();
                let msg = format!(
                    "[server] jar 里没有 Main-Class: {}（同目录 jar: {:?}）",
                    spec.jar, siblings
                );
                crate::util::log_line(&msg);
                println!("{msg}");
                return -3;
            }
        };
        let mc_msg = format!("[server] 主类 = {main_class}");
        crate::util::log_line(&mc_msg);
        println!("{mc_msg}");

        let stop_flag = running_flag();
        stop_flag.store(false, Ordering::Release);
        *RUNTIME_PORT
            .get_or_init(|| std::sync::Mutex::new(0))
            .lock()
            .unwrap() = ipc_port as u16;

        // IPC 线程：端口 0 = 让系统分配，真实端口拿到后回写 runtime.json
        let ipc_ready = Arc::new(std::sync::Mutex::new(0u16));
        {
            let token = spec.token.clone();
            let flag = stop_flag.clone();
            let ready = ipc_ready.clone();
            std::thread::spawn(move || serve_ipc(0, token, flag, ready));
        }
        // 等 IPC 起来（最多 3 秒），把真实端口写回 runtime.json —— 主进程靠它发 /stop
        let mut chosen = 0u16;
        for _ in 0..30 {
            chosen = *ipc_ready.lock().unwrap_or_else(|e| e.into_inner());
            if chosen != 0 {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        if chosen != 0 {
            if let Ok(dir) = server_dir(&spec.id) {
                let rt = dir.join("runtime.json");
                if let Ok(text) = std::fs::read_to_string(&rt) {
                    if let Ok(mut info) = serde_json::from_str::<RuntimeInfo>(&text) {
                        info.ipc_port = chosen;
                        if let Ok(t) = serde_json::to_string_pretty(&info) {
                            let _ = std::fs::write(rt, t);
                        }
                    }
                }
            }
        } else {
            tracing::warn!("[server] IPC 未能在 3 秒内监听，状态查询与停服会不可用");
        }

        // JVM 线程（阻塞到服停）
        let spec2 = spec.clone();
        std::thread::spawn(move || {
            // **必须把进程 CWD 切到服务器目录** —— 这是从游戏启动路径抄来的关键一步
            // （launch.rs 在 JvmLauncher::launch 之前就 `set_current_dir(&game_dir)`）。
            // 只给 `-Duser.dir=` 不够：paperclip / Minecraft 启动早期用 CWD 解析
            // 相对路径（版本清单、世界目录、日志），CWD 停在 `/` 时会直接崩，
            // 表现为「`:server` 进程起来几秒后 died: prcp FGS」，且没有任何 Java 侧日志。
            // 该进程只服务这一个服，切了 CWD 不需要还原。
            if let Err(e) = std::env::set_current_dir(&spec2.work_dir) {
                let msg = format!("[server] 切换工作目录失败: {e}");
                tracing::error!("{msg}");
                let _ = std::fs::write(Path::new(&spec2.work_dir).join("launch-error.txt"), &msg);
            }
            // JVM 参数在这里先构造好，**因为下面的启动日志要打印真实参数**。
            //
            // 原来日志打的是 `all_jvm_preview()` —— 一个只有 `user.dir` + `Xmx` 两项的
            // 「预览」，排查「`-D` 参数到底有没有传进去」时从日志里根本看不出来
            // （2026-10-08 查 JNA 临时目录问题时就被它误导过一次）。
            let tmp_dir = server_tmp_dir(&spec2.work_dir);
            // 堆上限按**设备当前可用内存**钳制，不照抄用户填的值（理由见 clamp_server_heap_mb）。
            // 钳制后的真实值会出现在下面的启动日志里，所以「实际跑了多少内存」始终查得到。
            let xmx_mb = clamp_server_heap_mb(spec2.xmx_mb);
            if xmx_mb != spec2.xmx_mb {
                tracing::warn!(
                    "[server] -Xmx 由 {}M 钳到 {xmx_mb}M：设备可用内存不足，避免被 lowmemorykiller 杀",
                    spec2.xmx_mb
                );
            }
            let mut jvm_args = vec![
                format!("-Xmx{xmx_mb}M"),
                // -Xms 用配置里的「最小内存」，但要夹紧：
                // ① 下限 256M —— 初始堆会**立即**提交给系统（实测 -Xms512M 让 :server
                //    起步就占 500MB），主进程 + :tunnel + :server 一起会被
                //    lowmemorykiller 杀（logcat: "low watermark is breached"）。
                // ② 上限不超过 -Xmx —— 配置里的 min_memory_mb 默认值可能比用户设的
                //    max 还大（实测新建服就是 min=1024 / max=512），照抄会让 JVM
                //    启动即报「初始堆大于最大堆」而拒绝启动。
                format!("-Xms{}M", spec2.xmin_mb.clamp(256, xmx_mb)),
                "-XX:+UseG1GC".to_string(),
                "-Dfile.encoding=UTF-8".to_string(),
                // 服务端不需要窗口
                "-Djava.awt.headless=true".to_string(),
                // **让 Paper 跳过它自己的 Java 版本校验**。新版 Paper（26.x）会检查运行时的
                // 版本串是不是「正式发布版」，而安卓这套自带 JRE 报的是 `25.0.5-internal`
                // 这种自定义后缀，于是直接被拒：
                //   Unsupported Java detected (25.0.5-internal) … Only general availability
                //   versions of Java are supported.
                // （真机 26.3 实测。）这个属性是 Paper 提供的官方开关，设置后它只警告不拦。
                // 老版本核心没有这个检查，属性无副作用。
                "-DPaper.IgnoreJavaVersion=true".to_string(),
                // 临时目录必须指到应用可写的地方，**`java.io.tmpdir` 和 `jna.tmpdir` 都要**：
                // JNA 优先看 `jna.tmpdir`，没设才退回 `java.io.tmpdir`。Android 上两者默认
                // 都是 `/tmp`，而应用没有它的写权限，于是服务端日志里会刷：
                //   JNA Warning: IOException removing temporary files
                //   java.io.IOException: JNA temporary directory '/tmp' is not writable
                //   java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native
                // 后果不只是难看：**oshi**（Paper 用它采硬件信息）整个初始化失败，
                // 插件里凡是用 JNA / 落临时文件的也会各种诡异失败。
                format!("-Djava.io.tmpdir={tmp_dir}"),
                format!("-Djna.tmpdir={tmp_dir}"),
                // **关掉 JVM 对 jar/zip 的内存映射**。
                // JVM 读 jar 靠 mmap，而 Android 的 bionic 上 mmap 行为和桌面差异很大
                // （页大小、文件锁、写回时机），容易出现「类加载到一半失败」「随机崩溃」
                // 「NoSuchMethodError / NoClassDefFoundError 之类找不到的怪错」这类
                // 很难定位的问题。关掉后改走 read + copy，代价是启动和首次读盘慢一点，
                // 开服场景完全值得换。竞品 Anvil-MC 的服务端 JVM 也设了同一个参数。
                "-Dsun.zip.disableMemoryMapping=true".to_string(),
            ];
            // 让 JNA 用**随包的 Android 版**原生库，而不是它自己按 `java.vm.name`
            // 猜出来的 linux-aarch64（glibc 版，在 bionic 上必然 dlopen 失败）。
            // 详见 ensure_jna_dir。
            if let Some(dir) = ensure_jna_dir() {
                jvm_args.push(format!("-Djna.boot.library.path={dir}"));
            }
            // JvmLauncher::launch 成功时会把自己的 jre_home 写进内部 state，
            // /stop 时 JvmLauncher::shutdown() 直接从那里取，不用在这里传。
            let mut all_jvm = vec![format!("-Duser.dir={}", spec2.work_dir)];
            all_jvm.extend(jvm_args);

            // **把 stdout/stderr 重定向到文件**。
            //
            // JLI_Launch 起来后 JVM 会自己接管 fd 1/2（游戏那边靠 attach 收集日志），
            // 在这个独立进程里没人收集，JVM 的任何输出（包括 hs_err 崩溃报告）
            // 都随进程一起消失 —— 表现就是「进程起来几秒后 died，什么都查不到」。
            // dup2 到普通文件后，至少能看到 JVM 自己写的错误。
            let log_path = Path::new(&spec2.work_dir).join("jvm-stdout.log");
            if let Ok(mut f) = std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
            {
                use std::os::fd::AsRawFd;
                let fd = f.as_raw_fd();
                unsafe {
                    nix::libc::dup2(fd, 1);
                    nix::libc::dup2(fd, 2);
                }
                // 借 println! 写一行标记，确认重定向生效
                println!(
                    "=== QookiX 服务端 JVM 启动 {} | {} | 主类 {} ===",
                    spec2.jar, spec2.jre_home, main_class
                );
                use std::io::Write as _;
                let _ = writeln!(
                    f,
                    "=== 启动 {} | cwd={} | jvm_args={:?} | args={:?} ===",
                    main_class,
                    spec2.work_dir,
                    all_jvm,
                    spec2.args
                );
                let _ = f.flush();
            }

            // **必须先预加载 JRE 内部库**，否则 JVM 走到 libnio.so 时必然报
            // `library "libnet.so" not found`（安卓 linker 不查 JRE 的 lib 目录，
            // 而 JDK 8+ 的 libnio.so 依赖一个 Bionic 里根本不存在的 libnet.so）。
            //
            // 这个坑游戏侧早就踩过并修好了（launch.rs 里同样是先 preload 再 launch），
            // 服务端复用同一个函数即可 —— 这也是「JVM 在 Android 上能跑起来」
            // 的真正前提，缺了它 JVM 会立刻 abort，且只在 stderr 里留一行
            // UnsatisfiedLinkError（不重定向 stdout 就完全看不见）。
            crate::android_env::preload_jre_libraries(Path::new(&spec2.jre_home));

            // 注意：**不要**给 paperclip 加 `-javaagent:<paperclip.jar>`。
            // 它看起来是对的（paperclip 的报错就是这么提示的），但在 JRE-25 安卓版上
            // 会在 `libinstrument.so` 里 SIGSEGV（Fatal signal 11），整个 :server 进程
            // 带崩且不留任何日志。正确做法是让 paperclip 跑完「打补丁」这一步，然后
            // 直接用 `cache/patched_*.jar` 启动 —— 见 servers.rs::patched_core_jar。

            tracing::info!("[server] 启动 JVM: {} -cp jar {}", spec2.jre_home, main_class);
            let r = crate::jvm_launcher::JvmLauncher::launch(
                Path::new(&spec2.jre_home),
                &all_jvm,
                &spec2.jar,
                &main_class,
                &spec2.args,
                &spec2.work_dir,
            );

            // JVM 退出后把结果写回 runtime.json，UI 能显示停止原因
            let note = match &r {
                Ok(code) => format!("已停止（退出码 {code}）"),
                Err(e) => format!("异常退出: {e}"),
            };
            tracing::info!("[server] {note}");
            if let Ok(dir) = server_dir(&spec2.id) {
                // 同时落一份纯文本：JVM 崩溃（abort/segfault）时 runtime.json
                // 可能来不及更新，launch-error.txt 是唯一能看到的线索。
                let _ = std::fs::write(dir.join("launch-error.txt"), format!("{note}\n"));
                if let Ok(rt) = std::fs::read_to_string(dir.join("runtime.json")) {
                    if let Ok(mut info) = serde_json::from_str::<RuntimeInfo>(&rt) {
                        info.exit = Some(note);
                        if let Ok(t) = serde_json::to_string_pretty(&info) {
                            let _ = std::fs::write(dir.join("runtime.json"), t);
                        }
                    }
                }
            }
            stop_flag.store(true, Ordering::Release);
        });

        0
    }));
    result.unwrap_or(-99)
}

/**
 * Kotlin `ServerService.nativeIsRunning()` 调用。
 * 用来在服务被系统重启后（如内存紧张杀了 :server 进程又被拉起）纠正 UI 状态。
 */
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_services_ServerService_nativeIsRunning(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if is_running() {
        1
    } else {
        0
    }
}
