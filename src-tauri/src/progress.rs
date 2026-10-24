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
use std::sync::{Arc, Mutex as StdMutex, OnceLock};
use tauri::{AppHandle, Emitter};

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
    match INSTALL_CANCELS.lock().unwrap().get(&task_id) {
        Some(flag) => {
            flag.store(true, Ordering::Relaxed);
            true
        }
        None => false,
    }
}

static APP_HANDLE: OnceLock<AppHandle> = OnceLock::new();
static NEXT_TASK_ID: AtomicU64 = AtomicU64::new(1);

/// 由 `lib.rs` 在 setup 阶段注入；没有它时所有事件静默丢弃（例如单元测试）。
pub fn set_app_handle(handle: AppHandle) {
    let _ = APP_HANDLE.set(handle);
}

/// 全局 AppHandle。供 `browse.rs` 之类模块发送自己的业务事件
/// （如 `content::identified` / `content://update-finished`）——
/// 没有它时事件静默丢弃（例如单元测试环境）。
pub fn app() -> Option<&'static AppHandle> {
    APP_HANDLE.get()
}

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
}

impl TaskCtx {
    pub fn new(instance_id: &str, instance_name: &str, source: &str) -> Self {
        let task_id = next_task_id();
        INSTALL_CANCELS
            .lock()
            .unwrap()
            .insert(task_id, Arc::new(AtomicBool::new(false)));
        Self {
            task_id,
            instance_id: instance_id.to_string(),
            instance_name: instance_name.to_string(),
            source: source.to_string(),
        }
    }

    /// 本任务的取消标志。下载层逐块检查它，才能真正中途停下。
    pub fn cancel_flag(&self) -> Arc<AtomicBool> {
        INSTALL_CANCELS
            .lock()
            .unwrap()
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

    /// 任务收尾：把标志摘掉，避免长会话里无限累积。
    pub fn release(&self) {
        INSTALL_CANCELS.lock().unwrap().remove(&self.task_id);
    }
}

/// 把一个事件**同时**推给 Kotlin（JNI 直连）与 Tauri（过渡期兜底）。
///
/// 阶段 2（2026-10-24）：事件原来是 `AppHandle.emit` → 走隐藏 WebView 的 JS 通道。
/// 那个网页/渲染进程一挂，进度条与状态角标就全停（真机反复遇到「点了没反应」）。
/// 现在优先走 JNI：`TauriBridge.onCoreEvent(event, json)`，失败才回落到 Tauri。
pub fn push(event: &str, payload: serde_json::Value) {
    #[cfg(target_os = "android")]
    {
        let json = payload.to_string();
        if crate::android_bridge::push_event_to_kotlin(event, &json).is_some() {
            return;
        }
    }
    if let Some(app) = app() {
        let _ = app.emit(event, payload);
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
pub fn emit_launch_exit() {
    push("launch://exit", serde_json::json!({}));
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
pub fn emit_launch_log(instance_id: &str, stream: &str, line: &str) {
    push("launch://log", serde_json::json!({
                "instanceId": instance_id,
                "stream": stream,
                "line": line,
            }),);
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
        {
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
