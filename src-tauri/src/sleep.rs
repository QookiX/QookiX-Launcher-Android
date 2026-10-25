//! 服务器空闲休眠与唤醒。
//!
//! ## 要解决的矛盾
//!
//! 手机上开服只有两种极端：一直挂着（费电、占内存，还会被 ROM 省电策略掐），
//! 或者手动关掉（朋友想进来时人不在，进不来）。这里取中间：
//! **没人在线就把服务端 JVM 停掉**（省电、释放内存），但**游戏端口继续占着** ——
//! 任何连接尝试都说明「有人来了」，于是立刻把服务端拉起来。
//!
//! ## 为什么监听放在启动器进程里
//!
//! `:tunnel`（陶瓦联机）与 `:server`（服务端 JVM）都是独立进程，但都不能同时满足
//! 「休眠时什么也不跑」和「随时能把服务端拉起来」：前者没有拉起服务端的逻辑（那套
//! 在 Rust 侧：挑 JRE、写 launch.json、等 JVM 起来），后者一旦停掉 JVM 就跟着结束了。
//! 启动器进程挂在隧道的前台服务之下被系统保活，所以监听放这里；进程若被系统杀掉再
//! 启动，`resume_on_start()` 会把监听与巡检按 `sleep.json` 补回来。
//!
//! ## 第一版不做的事
//!
//! 休眠期间玩家的「服务器列表」里看到的仍是连接失败，而不是一条自定义 MOTD ——
//! 那要实现 Minecraft 的服务器列表 ping 协议（`mcping.rs` 里已有现成的实现可复用），
//! 留到 v2。

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// 巡检间隔。1 分钟一次：休眠时长的档位最小是 15 分钟，1 分钟的粒度足够，
/// 又不会让 RCON 查询本身成为耗电源。
const TICK: Duration = Duration::from_secs(60);

/// 状态文件：`servers/{id}/sleep.json`。
///
/// 单独一个文件而不是塞进 `server.json`：它描述的是**运行态**（此刻是否睡着），
/// 而 `server.json` 是用户配置。混在一起的话，「服务器配置」会被运行过程不断改写，
/// 用户手改的配置与服务端写回的内容会互相覆盖。
const STATE_FILE: &str = "sleep.json";

/// 休眠状态。
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub struct SleepState {
    /// 是否处于休眠（JVM 已停，端口由唤醒监听占着）
    pub sleeping: bool,
    /// 进入休眠的时间戳（秒）
    #[serde(default)]
    pub since: u64,
    /// 最近一次唤醒失败的原因。界面要显示它 —— 否则「点唤醒没反应」用户完全查不到原因。
    #[serde(default)]
    pub error: Option<String>,
}

fn now_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn state_path(id: &str) -> Option<PathBuf> {
    Some(crate::servers::server_dir(id).ok()?.join(STATE_FILE))
}

/// 读休眠状态。文件不存在/损坏都当作「没休眠」—— 不能因为一个读不出来的状态文件
/// 就让界面显示「休眠中」，那样用户会以为服还活着。
pub fn read_state(id: &str) -> Option<SleepState> {
    let p = state_path(id)?;
    let text = std::fs::read_to_string(p).ok()?;
    serde_json::from_str(&text).ok()
}

pub fn is_sleeping(id: &str) -> bool {
    read_state(id).map(|s| s.sleeping).unwrap_or(false)
}

fn write_state(id: &str, st: &SleepState) {
    let Some(p) = state_path(id) else { return };
    match serde_json::to_string_pretty(st) {
        Ok(json) => {
            if let Err(e) = std::fs::write(&p, json) {
                tracing::warn!("[sleep] 写休眠状态失败（{}）：{e}", p.display());
            }
        }
        Err(e) => tracing::warn!("[sleep] 序列化休眠状态失败：{e}"),
    }
}

/// 清掉休眠标记（唤醒开始、手动启动、删除服务器时都要清）。
pub fn clear_state(id: &str) {
    if let Some(p) = state_path(id) {
        let _ = std::fs::remove_file(p);
    }
}

// ── 任务取消 ──────────────────────────────────────────────────────────
//
// 用「取消标记 + Notify」而不是 `JoinHandle::abort()`：
// abort 会在任意 await 点打断任务，可能把 sleep.json 写到一半，
// 也可能在「已经松开端口」与「服务端还没 bind」之间被掐断。
// 标记是显式的：任务自己在安全的点上退出。

#[derive(Clone)]
struct Cancel {
    flag: Arc<AtomicBool>,
    notify: Arc<tokio::sync::Notify>,
}

impl Cancel {
    fn new() -> Self {
        Self {
            flag: Arc::new(AtomicBool::new(false)),
            notify: Arc::new(tokio::sync::Notify::new()),
        }
    }
    /// 通知对方退出。先置标记再唤醒 —— 唤醒后对方回到循环顶部看标记。
    /// 用 `notify_one`（而不是 `notify_waiters`）：前者会存一个 permit，
    /// 即使此刻对方还没开始 await，下一次 `notified()` 也会立刻返回。
    fn cancel(&self) {
        self.flag.store(true, Ordering::SeqCst);
        self.notify.notify_one();
    }
}

fn supervisors() -> &'static Mutex<HashMap<String, Cancel>> {
    static M: OnceLock<Mutex<HashMap<String, Cancel>>> = OnceLock::new();
    M.get_or_init(|| Mutex::new(HashMap::new()))
}

fn listeners() -> &'static Mutex<HashMap<String, Cancel>> {
    static M: OnceLock<Mutex<HashMap<String, Cancel>>> = OnceLock::new();
    M.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 锁中毒时取回内部值继续用：这里的临界区只有 map 的插入/删除，没有会 panic 的逻辑，
/// 中毒只可能来自别处的 panic，不该因此让整个休眠功能失效。
fn lock<T>(m: &'static Mutex<T>) -> std::sync::MutexGuard<'static, T> {
    m.lock().unwrap_or_else(|e| e.into_inner())
}

// ── 空闲巡检 ──────────────────────────────────────────────────────────

/// 开始巡检这台服务器的在线人数。已有巡检会被替换掉（重启服务器时用）。
pub fn arm_supervisor(id: &str) {
    cancel_supervisor(id);
    let cancel = Cancel::new();
    lock(supervisors()).insert(id.to_string(), cancel.clone());
    let sid = id.to_string();
    crate::util::spawn(async move { supervise(sid, cancel).await });
}

/// 停止巡检（手动停服、休眠、删除服务器）。
pub fn cancel_supervisor(id: &str) {
    if let Some(c) = lock(supervisors()).remove(id) {
        c.cancel();
    }
}

/// 停止这台服务器上的一切后台任务（巡检 + 唤醒监听），并清掉休眠标记。
pub fn disarm(id: &str) {
    cancel_supervisor(id);
    cancel_wake_listener(id);
    clear_state(id);
}

/// 巡检主循环：每 `TICK` 问一次 RCON `list`，连续无人在线超过设定时长就休眠。
async fn supervise(id: String, cancel: Cancel) {
    let mut idle_since: Option<std::time::Instant> = None;
    loop {
        tokio::select! {
            _ = tokio::time::sleep(TICK) => {}
            _ = cancel.notify.notified() => {}
        }
        if cancel.flag.load(Ordering::SeqCst) {
            return;
        }
        let Ok(s) = crate::servers::get_server(&id) else {
            return;
        };
        if s.sleep_timeout_min == 0 {
            return;
        }
        // 服务器不是「正在跑」就别巡检了：可能是用户手动停了，或者它自己崩了。
        // 这两种情况下 `sleep.json` 不该出现 —— 界面上「停止」与「休眠」是两回事。
        if !crate::servers::is_server_up(&id) {
            return;
        }

        // RCON 客户端是阻塞 IO（`std::net`，最多各 5 秒超时）。巡检是 async 任务，
        // 直接调用会把运行时的线程占住，拖慢同一运行时里的命令。
        // 丢到 blocking 池里问，问完再回到 async 上下文。
        let probe_id = id.clone();
        let probed = tokio::task::spawn_blocking(move || online_players(&probe_id))
            .await
            .unwrap_or(None);
        let Some(n) = probed else {
            // 问不出来（还在启动、RCON 被关掉、服务端正卡在 GC）→ 当作**未知**，
            // 计时器归零。绝不能把查询失败当成「0 人」：那会把一台有人的服务器睡掉，
            // 而且用户拿到的现象是「服务器自己关了」，日志里却一切正常，极难排查。
            idle_since = None;
            continue;
        };
        if n > 0 {
            idle_since = None;
            continue;
        }
        let since = *idle_since.get_or_insert_with(std::time::Instant::now);
        if since.elapsed() >= Duration::from_secs(u64::from(s.sleep_timeout_min) * 60) {
            sleep_now(&id).await;
            return;
        }
    }
}

/// 问一次在线人数。返回 `None` = 这次没问出来（不等于 0 人）。
fn online_players(id: &str) -> Option<u32> {
    let s = crate::servers::get_server(id).ok()?;
    let dir = crate::servers::server_dir(id).ok()?;
    let creds = crate::rcon::load_or_create_creds(&dir, s.port).ok()?;
    let out = crate::rcon::exec(creds.port, &creds.password, "list").ok()?;
    parse_list_count(&out)
}

/// 解析 RCON `list` 的输出。
///
/// 原版/Paper 的格式是 `There are 0 of a max of 20 players online:`，
/// 后面还可能跟一串玩家名（用逗号分隔）。这里只取「There are 」后面那个数字，
/// 名字里出现数字也不会串味。
fn parse_list_count(out: &str) -> Option<u32> {
    let rest = out.split("There are ").nth(1)?;
    let digits: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
    digits.parse().ok()
}

/// 进入休眠：优雅停服 → 挂上唤醒监听。
///
/// 顺序不能颠倒：服务端占着端口，不先停下来就 bind 不上监听。
async fn sleep_now(id: &str) {
    let Ok(s) = crate::servers::get_server(id) else {
        return;
    };
    crate::progress::emit_server_stage(id, "无人在线，正在休眠服务器…");
    // 巡检任务马上就要 return，这里先取消掉，免得它再进来一次
    cancel_supervisor(id);

    // 复用「优雅停服」：RCON stop 让服务端自己存盘关世界。
    // 直接杀进程会让玩家的建筑回档，休眠是自动行为，绝不能冒这个险。
    match crate::servers::stop_hosted_server(id.to_string()).await {
        Ok(msg) => tracing::info!("[sleep] {id} 进入休眠：{msg}"),
        Err(e) => {
            // 停不掉就**不能**写「已休眠」：界面会显示「唤醒」，而服务器其实还在跑，
            // 点唤醒只会得到「已经在运行了」，用户彻底懵。
            tracing::warn!("[sleep] {id} 自动休眠失败：{e}");
            crate::progress::emit_server_stage(id, &format!("休眠失败：{e}"));
            write_state(
                id,
                &SleepState {
                    sleeping: false,
                    since: 0,
                    error: Some(format!("自动休眠失败：{e}")),
                },
            );
            return;
        }
    }

    write_state(
        id,
        &SleepState {
            sleeping: true,
            since: now_secs(),
            error: None,
        },
    );
    arm_wake_listener(id, s.port);
}

// ── 唤醒监听 ──────────────────────────────────────────────────────────

/// 在服务器端口上挂一个监听：有人连进来就把服务端拉起来。
pub fn arm_wake_listener(id: &str, port: u16) {
    cancel_wake_listener(id);
    let cancel = Cancel::new();
    lock(listeners()).insert(id.to_string(), cancel.clone());
    let sid = id.to_string();
    crate::util::spawn(async move { listen(sid, port, cancel).await });
}

pub fn cancel_wake_listener(id: &str) {
    if let Some(c) = lock(listeners()).remove(id) {
        c.cancel();
    }
}

/// 监听循环：等到第一个连接就唤醒。
async fn listen(id: String, port: u16, cancel: Cancel) {
    // 端口刚被 JVM 释放，可能还要等一会儿（进程退出、TIME_WAIT）。
    // 不重试的话会得到「已休眠，但没有任何东西会唤醒它」——最糟的状态：界面看着正常，
    // 朋友却永远连不进来。
    let mut bound = None;
    for _ in 0..40 {
        match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
            Ok(l) => {
                bound = Some(l);
                break;
            }
            Err(e) => {
                tracing::warn!("[sleep] {id} 监听端口 {port} 失败（{e}），0.5 秒后重试");
                if cancel.flag.load(Ordering::SeqCst) {
                    return;
                }
                tokio::select! {
                    _ = tokio::time::sleep(Duration::from_millis(500)) => {}
                    _ = cancel.notify.notified() => {}
                }
            }
        }
    }
    let Some(listener) = bound else {
        // 20 秒都占不到端口：如实记下来，别装作已休眠。
        tracing::error!("[sleep] {id} 端口 {port} 一直被占用，无法监听唤醒");
        write_state(
            &id,
            &SleepState {
                sleeping: false,
                since: 0,
                error: Some(format!("端口 {port} 被占用，无法监听唤醒连接")),
            },
        );
        return;
    };
    tracing::info!("[sleep] {id} 已休眠，正在 {port} 端口等待唤醒连接");

    let addr = loop {
        if cancel.flag.load(Ordering::SeqCst) {
            // 用户手动启动了服务器（或删了它）→ 让出端口
            tracing::info!("[sleep] {id} 唤醒监听被取消");
            return;
        }
        tokio::select! {
            r = listener.accept() => match r {
                Ok((_stream, addr)) => break addr,
                Err(e) => {
                    // 单个连接出错（客户端半路断开）不能把监听搞没，
                    // 否则下一次真的有人来连时已经没人听了。
                    tracing::warn!("[sleep] {id} 唤醒监听 accept 出错：{e}");
                }
            },
            _ = cancel.notify.notified() => {}
        }
    };
    // **必须先松开端口**：服务端起来时要 bind 同一个端口，监听还在就是
    // "Address already in use"，用户看到的会是「唤醒失败：启动后立刻退出」。
    drop(listener);
    tracing::info!("[sleep] {id} 收到来自 {addr} 的连接，开始唤醒");

    // 从监听表里摘掉自己：后面要么成功启动（start_hosted_server 会重新挂巡检），
    // 要么失败重挂监听，两条路都不该留着这个已死的条目。
    lock(listeners()).remove(&id);
    wake(id).await;
}

/// 唤醒：把服务端按正常流程拉起来。
///
/// 直接复用 `start_hosted_server` —— 挑 JRE、写 launch.json、等 JVM 起来、
/// 检查端口冲突这些都在里面，另写一份必然与它走偏。
async fn wake(id: String) {
    clear_state(&id);
    crate::progress::emit_server_stage(&id, "有玩家连接，正在唤醒服务器…");
    if let Err(e) = crate::servers::start_hosted_server(id.clone()).await {
        // 唤醒失败（核心被删、JRE 下载不到、内存不够……）时**把监听挂回去**：
        // 否则这台服就永久「睡着」了 —— 界面显示休眠、朋友连进来却石沉大海。
        // 同时把原因写进状态，界面直接显示出来。
        tracing::warn!("[sleep] {id} 唤醒失败：{e}");
        crate::progress::emit_server_stage(&id, &format!("唤醒失败：{e}"));
        let port = crate::servers::get_server(&id).map(|s| s.port).unwrap_or(25565);
        write_state(
            &id,
            &SleepState {
                sleeping: true,
                since: now_secs(),
                error: Some(format!("唤醒失败：{e}")),
            },
        );
        arm_wake_listener(&id, port);
    } else {
        tracing::info!("[sleep] {id} 已唤醒");
    }
}

// ── 进程重启后的恢复 ──────────────────────────────────────────────────

/// 启动器进程重启后把后台任务补回来。
///
/// 不补的话：休眠中的服务器没人监听（朋友连不进来，界面却写着「休眠中」），
/// 运行中的服务器不再巡检（永远不休眠）。这两种都是静默失效，最不该出现。
pub fn resume_on_start() {
    for s in crate::servers::list_servers().unwrap_or_default() {
        if read_state(&s.id).map(|st| st.sleeping).unwrap_or(false) {
            if s.sleep_timeout_min == 0 {
                // 用户在休眠期间把功能关了：不再监听，如实回到「已停止」。
                clear_state(&s.id);
                continue;
            }
            tracing::info!("[sleep] 恢复 {} 的唤醒监听（端口 {}）", s.id, s.port);
            arm_wake_listener(&s.id, s.port);
        } else if crate::servers::is_server_up(&s.id) {
            tracing::info!("[sleep] 恢复 {} 的空闲巡检", s.id);
            arm_supervisor(&s.id);
        }
    }
}
