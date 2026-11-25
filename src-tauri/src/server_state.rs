//! `:server` 进程的运行状态（2026-11-04 从 `server_process.rs` 抽出）。
//!
//! 拆出来有两个原因，都是审计 `BE-07` 的内容：
//!
//! 1. **语义修正**：原来只有一个 `RUNNING` 标志，实际含义是「收到停止请求」，
//!    而 `is_running()` 直接读它 —— 于是服务**运行中**报 false、**已退出**报 true。
//!    `nativeIsRunning()` 也跟着反相，UI 上「启动中 / 运行中 / 已停止」全程是反的。
//!    现在拆成两个独立标志：`STOP`（停止请求）与 `ALIVE`（JVM 活着）。
//!
//! 2. **重复启动**：`nativeStart` 原来没有任何互斥，连点或并发调用会起两套
//!    IPC 线程 + JVM，互相覆盖 `RUNTIME_PORT` 和 `runtime.json` —— 后起的那个
//!    把前一个的端口写掉，`/stop` 就打到错误的进程上。

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

// The launcher owns a single Android service process. Serialize user operations
// across server IDs, including the time before a runtime record is published.
static HOST_OPERATION: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

pub async fn host_operation() -> tokio::sync::MutexGuard<'static, ()> {
    HOST_OPERATION.lock().await
}

pub fn try_host_operation() -> Result<tokio::sync::MutexGuard<'static, ()>, String> {
    HOST_OPERATION.try_lock().map_err(|_| "服务器正在处理启动、停止或安装任务，请稍后再试".into())
}

/// A broken IPC connection is not proof that the separate JVM process exited.
#[cfg(target_os = "android")]
pub fn process_may_be_alive(pid: i32) -> bool {
    pid > 0 && (unsafe { libc::kill(pid, 0) } == 0
        || std::io::Error::last_os_error().raw_os_error() != Some(libc::ESRCH))
}

#[cfg(not(target_os = "android"))]
pub fn process_may_be_alive(_pid: i32) -> bool {
    false
}

#[derive(serde::Deserialize)]
pub struct IpcStatus {
    pub pid: i32,
    // Keep the numeric field compatible with earlier server processes.
    jvm: u8,
}

impl IpcStatus {
    pub fn from_http(response: &str) -> Option<Self> {
        let (headers, body) = response.split_once("\r\n\r\n")?;
        if headers.lines().next()?.split_whitespace().nth(1)? != "200" {
            return None;
        }
        serde_json::from_str(body).ok()
    }

    pub fn jvm_active(&self) -> bool { self.jvm == 1 }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ipc_status_agrees_with_process_contract() {
        let response = |jvm| format!("HTTP/1.1 200 OK\r\n\r\n{{\"pid\":7,\"jvm\":{jvm}}}");
        assert!(IpcStatus::from_http(&response(1)).unwrap().jvm_active());
        assert!(!IpcStatus::from_http(&response(0)).unwrap().jvm_active());
        assert!(IpcStatus::from_http(&response(1).replace("200", "404")).is_none());
        assert!(IpcStatus::from_http("HTTP/1.1 200 OK\r\n\r\n{}").is_none());
    }

    #[tokio::test]
    async fn synchronous_mutation_refuses_inflight_operation() {
        let guard = host_operation().await;
        assert!(try_host_operation().is_err());
        drop(guard);
        assert!(try_host_operation().is_ok());
    }
}

/// 停止请求：`serve_ipc` 收到 `/stop`、或 JVM 退出后置 true。
static STOP: OnceLock<Arc<AtomicBool>> = OnceLock::new();
/// JVM 是否活着（`try_begin` 抢占，`AliveGuard` 复位）。
static ALIVE: OnceLock<Arc<AtomicBool>> = OnceLock::new();

fn stop_cell() -> Arc<AtomicBool> {
    STOP.get_or_init(|| Arc::new(AtomicBool::new(false))).clone()
}

fn alive_cell() -> Arc<AtomicBool> {
    ALIVE.get_or_init(|| Arc::new(AtomicBool::new(false))).clone()
}

/// 停止请求标志（IPC 线程与 JVM 线程共享）。
pub fn stop_flag() -> Arc<AtomicBool> {
    stop_cell()
}

/// 供 UI 轮询：`:server` 进程内当前是否有服在跑。
///
/// ⚠️ 读的是 `ALIVE`，**不是** `STOP` —— 这是 BE-07 的修复点。
pub fn is_running() -> bool {
    alive_cell().load(Ordering::Acquire)
}

/// 抢占「启动中」。返回 `false` = 已经在跑，调用方应拒绝本次启动。
///
/// 用 `swap` 而不是「先 load 再 store」：两个 `nativeStart` 并发时，
/// 非原子的读改写会让两边都通过检查。
pub fn try_begin() -> bool {
    !alive_cell().swap(true, Ordering::AcqRel)
}

/// JVM 线程内的守卫：正常返回、提前 `return`、panic unwind 都会复位。
///
/// 没有这个守卫时，JVM 线程里一次 panic 就会让 `ALIVE` 永远是 true ——
/// 之后所有启动请求都被当成「已在运行」拒绝，只能杀进程才能恢复。
pub struct AliveGuard(Arc<AtomicBool>);

impl AliveGuard {
    pub fn new() -> Self {
        Self(alive_cell())
    }
}

impl Default for AliveGuard {
    fn default() -> Self {
        Self::new()
    }
}

impl Drop for AliveGuard {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Release);
    }
}
