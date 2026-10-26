//! 安装 / 下载 / 启动的进度事件上报。
//!
//! 前端 `stores/tasks.ts` 完全靠事件驱动「下载中心」和「正在运行」状态，
//! 事件名与载荷字段必须和桌面端保持一致，否则界面收不到任何动静：
//!
//! - `install://progress`：`{ taskId, stage, message, done, total, instanceId, instanceName, source, ok? }`
//! - `download://progress`：`{ taskId, phase, done, total, current, ok, bytesDone?, bytesTotal? }`
//! - `launch://state`：`{ instanceId, state: "running" | "exited", pid, code }`
//! - `launch://log`：`{ instanceId, stream, line }`
//!
//! `stage` / `phase` 取值要与前端 `DownloadsView` 的 `STAGE_LABELS` 对得上
//! （manifest / client / libraries / natives / assets / loader / content / done…）。

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex as StdMutex};
lazy_static::lazy_static! {
    /// 安装任务的取消标志，key 与前端 `TaskEntry.id` / `TaskCtx.task_id` 同值。
    ///
    /// 为什么需要：前端「下载中心」以前**没有任何可用的取消入口** ——
    /// `download::cancel_download` 的 key 是内部生成的 `dl_<uuid>`（前端拿不到），
    /// 而且它只删进度条目、不真的停下载。这里按**安装任务**注册标志，
    /// 下载层逐块检查，取消按钮才真的有用。
    static ref INSTALL_CANCELS: StdMutex<HashMap<u64, Arc<AtomicBool>>> =
        StdMutex::new(HashMap::new());
}

/// 取消一个安装任务。返回是否真的找到了该任务
/// （找不到说明已经结束或 id 不对，前端应据此提示而不是静默假装成功）。
pub fn cancel_install(task_id: u64) -> bool {
    match crate::util::lock_recover(&INSTALL_CANCELS).get(&task_id) {
        Some(flag) => {
            flag.store(true, Ordering::Relaxed);
            true
        }
        None => false,
    }
}

static NEXT_TASK_ID: AtomicU64 = AtomicU64::new(1);

/// 由 `lib.rs` 在 setup 阶段注入；没有它时所有事件静默丢弃（例如单元测试）。
// 阶段 4：set_app_handle 已删除（事件全走 JNI）

/// 全局 AppHandle。供 `browse.rs` 之类模块发送自己的业务事件
/// （如 `content::identified` / `content://update-finished`）——
/// 没有它时事件静默丢弃（例如单元测试环境）。


/// 生成一个自增的任务 id（与桌面端 `state.next_task_id()` 语义一致）。
pub fn next_task_id() -> u64 {
    NEXT_TASK_ID.fetch_add(1, Ordering::Relaxed)
}

/// 一个安装/下载任务的上下文：让过程中的每条进度事件都带上实例信息，
/// 前端「下载中心」据此显示任务归属并可跳转到实例详情。
#[derive(Clone, Debug)]
pub struct TaskCtx {
    pub task_id: u64,
    pub instance_id: String,
    pub instance_name: String,
    /// 任务来源描述，例如「游戏本体」「Modrinth：Sodium」
    pub source: String,
    /// 取消标志条目的「所有权」。
    ///
    /// ⚠️ 2026-10-26 审查 B-P2-6：`TaskCtx::release()` 全仓只有一个调用点，
    /// 其余 7 处 `TaskCtx::new` 都不收尾 → `INSTALL_CANCELS` 随任务数单调增长，
    /// 而且已结束的任务在 `cancel_install` 里仍返回「取消成功」。
    /// 现在靠最后一个 `TaskCtx`（含 clone）drop 时自动摘除，调用方不用记得收尾。
    slot: Arc<TaskSlot>,
}

/// 只负责在最后一个 `TaskCtx` 消失时把取消标志从表里摘掉。
#[derive(Debug)]
struct TaskSlot {
    task_id: u64,
}

impl Drop for TaskSlot {
    fn drop(&mut self) {
        crate::util::lock_recover(&INSTALL_CANCELS).remove(&self.task_id);
    }
}

impl TaskCtx {
    pub fn new(instance_id: &str, instance_name: &str, source: &str) -> Self {
        let task_id = next_task_id();
        crate::util::lock_recover(&INSTALL_CANCELS)
            .insert(task_id, Arc::new(AtomicBool::new(false)));
        Self {
            task_id,
            instance_id: instance_id.to_string(),
            instance_name: instance_name.to_string(),
            source: source.to_string(),
            slot: Arc::new(TaskSlot { task_id }),
        }
    }

    /// 本任务的取消标志。下载层逐块检查它，才能真正中途停下。
    pub fn cancel_flag(&self) -> Arc<AtomicBool> {
        crate::util::lock_recover(&INSTALL_CANCELS)
            .entry(self.task_id)
            .or_insert_with(|| Arc::new(AtomicBool::new(false)))
            .clone()
    }

    /// 已取消就返回 Err —— 各安装流水线在每个循环开头调它。
    pub fn check_cancelled(&self) -> Result<(), anyhow::Error> {
        if self.cancel_flag().load(Ordering::Relaxed) {
            return Err(anyhow::anyhow!("任务已取消"));
        }
        Ok(())
    }

    /// 任务收尾。
    ///
    /// 现在由 [`TaskSlot`] 的 Drop 自动完成，保留这个空方法只是为了让
    /// 现有调用点不改（显式写一句「我收尾了」读代码时更直白）。
    pub fn release(&self) {}
}

/// 把一个事件**同时**推给 Kotlin（JNI 直连）与 Tauri（过渡期兜底）。
///
/// 阶段 2（2026-10-24）：事件原来是 `AppHandle.emit` → 走隐藏 WebView 的 JS 通道。
/// 那个网页/渲染进程一挂，进度条与状态角标就全停（真机反复遇到「点了没反应」）。
/// 现在优先走 JNI：`TauriBridge.onCoreEvent(event, json)`，失败才回落到 Tauri。
pub fn push_ser<T: serde::Serialize>(event: &str, value: T) {
    push(event, serde_json::to_value(value).unwrap_or(serde_json::Value::Null));
}

pub fn push(event: &str, payload: serde_json::Value) {
    #[cfg(target_os = "android")]
    {
        let json = payload.to_string();
        if crate::android_bridge::push_event_to_kotlin(event, &json).is_some() {
            return;
        }
        // 桥未就绪（`nativeAttach` 之前）：先存着，attach 后由 flush_pending_events 补发。
        // 以前这里直接落空 —— 冷启动那几百毫秒里的 install://progress、launch://state
        // 全部永久丢失，用户看到的就是「点了没反应」（审查 B-P1-4）。
        queue_pending(event, json);
    }
}

/// 桥未就绪期间最多缓存多少条事件 / 多少字节，超了丢最旧的。
///
/// 冷启动窗口里正常只有几条到几十条；给这么大的余量是为了「安装中切后台再回前台」
/// 这类桥长时间不可用的场景，同时防止内存无上限增长。
const MAX_PENDING_EVENTS: usize = 512;
const MAX_PENDING_BYTES: usize = 256 * 1024;

lazy_static::lazy_static! {
    /// `(event, json)` 队列，只由 [push] 写入、[flush_pending_events] 消费。
    static ref PENDING_EVENTS: StdMutex<std::collections::VecDeque<(String, String)>> =
        StdMutex::new(std::collections::VecDeque::new());
    static ref PENDING_BYTES: AtomicU64 = AtomicU64::new(0);
    /// 因为超限被丢掉的条数（只用于日志，不上报）。
    static ref PENDING_DROPPED: AtomicU64 = AtomicU64::new(0);
}

#[cfg(target_os = "android")]
fn queue_pending(event: &str, json: String) {
    let mut q = crate::util::lock_recover(&PENDING_EVENTS);
    let mut bytes = PENDING_BYTES.load(Ordering::Relaxed);
    bytes += json.len() as u64;
    while q.len() >= MAX_PENDING_EVENTS || bytes > MAX_PENDING_BYTES as u64 {
        let Some((_, old)) = q.pop_front() else { break };
        bytes -= old.len() as u64;
        PENDING_DROPPED.fetch_add(1, Ordering::Relaxed);
    }
    q.push_back((event.to_string(), json));
    PENDING_BYTES.store(bytes, Ordering::Relaxed);
}

/// 桥就绪后把缓存的事件按顺序补发（`set_activity` 里调用）。
///
/// 仍然发不出去的放回队首，等下一次 attach —— 不丢。
#[cfg(target_os = "android")]
pub fn flush_pending_events() {
    let batch: Vec<(String, String)> = {
        let mut q = crate::util::lock_recover(&PENDING_EVENTS);
        PENDING_BYTES.store(0, Ordering::Relaxed);
        q.drain(..).collect()
    };
    if batch.is_empty() {
        return;
    }
    let dropped = PENDING_DROPPED.swap(0, Ordering::Relaxed);
    if dropped > 0 {
        tracing::warn!("桥未就绪期间丢了 {dropped} 条事件（超过缓存上限）");
    }

    let mut leftover: Vec<(String, String)> = Vec::new();
    for (event, json) in batch {
        if !leftover.is_empty()
            || crate::android_bridge::push_event_to_kotlin(&event, &json).is_none()
        {
            leftover.push((event, json));
        }
    }
    if !leftover.is_empty() {
        let mut q = crate::util::lock_recover(&PENDING_EVENTS);
        for (event, json) in leftover.into_iter().rev() {
            q.push_front((event, json));
        }
        PENDING_BYTES.store(q.iter().map(|(_, j)| j.len() as u64).sum(), Ordering::Relaxed);
    }
}

/// 安装阶段进度（步骤型）。
pub fn emit_install(ctx: &TaskCtx, stage: &str, message: &str, done: usize, total: usize) {
    push("install://progress", serde_json::json!({
                "taskId": ctx.task_id,
                "stage": stage,
                "message": message,
                "done": done,
                "total": total,
                "instanceId": ctx.instance_id,
                "instanceName": ctx.instance_name,
                "source": ctx.source,
            }),);
}

/// 任务收尾：前端收到 `stage == "done"` 才会把任务标记为已完成。
pub fn emit_install_done(ctx: &TaskCtx, ok: bool, message: &str, done: usize, total: usize) {
    push("install://progress", serde_json::json!({
                "taskId": ctx.task_id,
                "stage": "done",
                "message": message,
                "done": done,
                "total": total,
                "instanceId": ctx.instance_id,
                "instanceName": ctx.instance_name,
                "source": ctx.source,
                "ok": ok,
            }),);
}

/// 文件/字节型下载进度。
#[allow(clippy::too_many_arguments)]
pub fn emit_download(
    ctx: &TaskCtx,
    phase: &str,
    done: usize,
    total: usize,
    current: &str,
    bytes_done: u64,
    bytes_total: u64,
    ok: bool,
) {
    push("download://progress", serde_json::json!({
                "taskId": ctx.task_id,
                "phase": phase,
                "done": done,
                "total": total,
                "current": current,
                "ok": ok,
                "bytesDone": bytes_done,
                "bytesTotal": bytes_total,
            }),);
}

/// 插件安装进度（设置 → 插件页的进度条靠它）。
///
/// `phase`：download / verify / extract / done；
/// `done`/`total`：下载按字节，其它阶段可给 0。
pub fn emit_plugin_progress(plugin_id: &str, phase: &str, done: u64, total: u64, message: &str) {
    push("plugin://progress", serde_json::json!({
                "id": plugin_id,
                "phase": phase,
                "done": done,
                "total": total,
                "message": message,
            }),);
}

/// 启动阶段进度（前端的悬浮启动卡片 `LaunchProgress.vue` 靠它显示步骤与百分比）。
pub fn emit_launch_progress(step: &str, progress: f64) {
    push("launch://progress", serde_json::json!({ "step": step, "progress": progress }),);
}

/// 启动流程结束（成功或失败）：收起悬浮启动卡片。
///
/// ⚠️ 2026-10-26 审查 P1-6：这里原来发的是空对象，而 Kotlin 侧解析
/// `instanceId` / `code` → 恒得 `("", 0)`。后果是 PageHost 的退出事件去重 key
/// 永远是 `":0"`，**同一进程内第二次游戏退出不再触发渲染器健康检查**，
/// 「非正常退出」的等待分支也永不可达。载荷现在带上真实字段。
pub fn emit_launch_exit(instance_id: &str, code: i32) {
    push(
        "launch://exit",
        serde_json::json!({ "instanceId": instance_id, "code": code }),
    );
}

/// 服务端启动阶段（`server://stage`：`{ id, stage }`）。
///
/// 启动一台服务器可能要几十秒（旧版 Paper 首次还要先生成补丁、新版要下几十 MB 的原版包），
/// 期间界面只有一颗转圈的按钮 —— 用户完全不知道是在干活还是卡死了。
/// 服务端详情页把它直接显示在「启动」按钮上（见 ServerDetailView.vue）。
pub fn emit_server_stage(server_id: &str, stage: &str) {
    push("server://stage", serde_json::json!({ "id": server_id, "stage": stage }),);
}

/// 游戏进程状态变化。前端据此显示「运行中」并提供「关闭所有实例」。
pub fn emit_launch_state(instance_id: &str, state: &str, pid: u32, code: Option<i32>) {
    push("launch://state", serde_json::json!({
                "instanceId": instance_id,
                "state": state,
                "pid": pid,
                "code": code,
            }),);
}

/// 游戏日志行。
///
/// ⚠️ 2026-10-26 审查 B-P2-8：`android_env.rs` 的日志 tail 是**每读一行**调一次，
/// 每次 2×`NewStringUTF` + 一次同步 `CallVoidMethod`。启动期 MC 每秒几百行，
/// 于是 tail 线程被 JNI 卡住、局部引用表（修复前不释放）迅速膨胀。
/// 这里给一个「每秒最多 1000 行」的预算：超出的行丢弃并补一条提示，
/// 宁可日志少几行，也不要拖住 tail 线程。
pub fn emit_launch_log(instance_id: &str, stream: &str, line: &str) {
    if !log_budget_take() {
        return;
    }
    push("launch://log", serde_json::json!({
                "instanceId": instance_id,
                "stream": stream,
                "line": line,
            }),);
}

/// 当前这一秒的日志行预算。
fn log_budget_take() -> bool {
    use std::sync::OnceLock;
    use std::time::{Duration, Instant};
    struct Budget {
        window_start: Instant,
        used: u32,
        dropped: u32,
        warned: bool,
    }
    static BUDGET: OnceLock<StdMutex<Budget>> = OnceLock::new();
    const PER_SECOND: u32 = 1000;

    let mut b = crate::util::lock_recover(BUDGET.get_or_init(|| {
        StdMutex::new(Budget {
            window_start: Instant::now(),
            used: 0,
            dropped: 0,
            warned: false,
        })
    }));
    if b.window_start.elapsed() >= Duration::from_secs(1) {
        b.window_start = Instant::now();
        b.used = 0;
        b.dropped = 0;
        b.warned = false;
    }
    if b.used < PER_SECOND {
        b.used += 1;
        return true;
    }
    b.dropped += 1;
    // 每秒只提示一次，避免提示本身又把通道打满
    if !b.warned {
        b.warned = true;
        let dropped = b.dropped;
        drop(b);
        push("launch://log", serde_json::json!({
            "instanceId": "",
            "stream": "stderr",
            "line": format!("（日志过快，已省略 {dropped} 行）"),
        }));
    }
    false
}

// ---------------------------------------------------------------------------
// 文件计数进度器
// ---------------------------------------------------------------------------

/// 把「第几个文件 / 共几个文件 + 累计字节」聚合成节流的进度事件。
///
/// 下载流程会遍历上千个资源对象，逐条上报会把 IPC 打满，因此：
/// - 距上次上报 ≥150ms 才发一条；
/// - 最后一个文件必定上报（保证进度能走满 100%）。
pub struct ProgressCounter<'a> {
    ctx: Option<&'a TaskCtx>,
    phase: &'a str,
    total_files: usize,
    total_bytes: u64,
    done_files: usize,
    done_bytes: u64,
    last_emit_ms: u64,
}

impl<'a> ProgressCounter<'a> {
    pub fn new(ctx: Option<&'a TaskCtx>, phase: &'a str, total_files: usize, total_bytes: u64) -> Self {
        Self {
            ctx,
            phase,
            total_files,
            total_bytes,
            done_files: 0,
            done_bytes: 0,
            last_emit_ms: 0,
        }
    }

    /// 切换到下一个阶段（客户端 → 依赖库 → …），分母保持不变。
    pub fn set_phase(&mut self, phase: &'a str) {
        self.phase = phase;
        // 换阶段时立刻上报一次，让「下载中心」的标签及时更新
        self.last_emit_ms = 0;
    }

    /// 已完成文件数 / 累计字节：用于把后续阶段（如资源文件）接在同一根进度条上，
    /// 否则前端 `max()` 会保留上一阶段的字节数，看起来像卡住。
    pub fn done_files(&self) -> usize {
        self.done_files
    }

    pub fn done_bytes(&self) -> u64 {
        self.done_bytes
    }

    fn now_ms() -> u64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0)
    }

    /// 记录一个文件已完成。`size_hint` 用于累计字节（未知时传 0）。
    pub fn tick(&mut self, current: &str, size_hint: u64) {
        self.done_files += 1;
        self.done_bytes += size_hint;
        let Some(ctx) = self.ctx else { return };

        let now = Self::now_ms();
        let finished = self.done_files >= self.total_files;
        if !finished && now.saturating_sub(self.last_emit_ms) < 150 {
            return;
        }
        self.last_emit_ms = now;
        emit_download(
            ctx,
            self.phase,
            self.done_files,
            self.total_files,
            current,
            self.done_bytes,
            self.total_bytes,
            true,
        );
    }

}

/// 供并发下载（如资源对象）共享的原子计数进度器。
pub struct AtomicProgress<'a> {
    ctx: Option<&'a TaskCtx>,
    phase: &'a str,
    total_files: usize,
    total_bytes: u64,
    done_files: std::sync::atomic::AtomicUsize,
    done_bytes: AtomicU64,
    last_emit_ms: AtomicU64,
    throttle_ms: u64,
}

impl<'a> AtomicProgress<'a> {
    /// `total_*` / `done_*` 都包含之前阶段已完成的量，保证多阶段共用一根单调递增的进度条。
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        ctx: Option<&'a TaskCtx>,
        phase: &'a str,
        total_files: usize,
        total_bytes: u64,
        done_files: usize,
        done_bytes: u64,
        throttle_ms: u64,
    ) -> Self {
        Self {
            ctx,
            phase,
            total_files,
            total_bytes,
            done_files: std::sync::atomic::AtomicUsize::new(done_files),
            done_bytes: AtomicU64::new(done_bytes),
            last_emit_ms: AtomicU64::new(0),
            throttle_ms,
        }
    }

    pub fn tick(&self, current: &str, size_hint: u64) {
        let done = self.done_files.fetch_add(1, Ordering::Relaxed) + 1;
        let bytes = self.done_bytes.fetch_add(size_hint, Ordering::Relaxed) + size_hint;
        let Some(ctx) = self.ctx else { return };

        let now = ProgressCounter::now_ms();
        let last = self.last_emit_ms.load(Ordering::Relaxed);
        let finished = done >= self.total_files;
        if !finished && now.saturating_sub(last) < self.throttle_ms {
            return;
        }
        if self
            .last_emit_ms
            .compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed)
            .is_err()
            && !finished
        {
            // 抢不到时间戳就维持节流；但「最后一个」必须发出去：
            // 并发 tick 时输掉 CAS 的往往正是 done==total 的那个线程，
            // 赢家上报的 done<total，进度条会永远停在 99%（单线程的
            // `ProgressCounter::tick` 明确保证最后一个必定上报，这里对齐它）。
            return;
        }
        emit_download(
            ctx,
            self.phase,
            done,
            self.total_files,
            current,
            bytes,
            self.total_bytes,
            true,
        );
    }
}
