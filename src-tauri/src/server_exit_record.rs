//! 服务端 JVM 退出时补写 cleanExit。
//!
//! JVM 里的 `System.exit(0)` 会直接结束 `:server` 进程，Rust 线程来不及执行
//! `server_process.rs` 里 JVM 返回后的收尾代码。`/stop` 明确请求退出时会置 STOP
//! 标志，这里用 `atexit` 在进程退出前把 `runtime.json` 补成 cleanExit。

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};

struct ExitRecord {
    path: PathBuf,
    token: String,
}

static EXIT_RECORD: OnceLock<Mutex<Option<ExitRecord>>> = OnceLock::new();
static EXIT_STOP_FLAG: OnceLock<std::sync::Arc<AtomicBool>> = OnceLock::new();
#[cfg(target_os = "android")]
static ATEXIT_ONCE: std::sync::Once = std::sync::Once::new();

#[cfg(target_os = "android")]
extern "C" fn record_clean_exit_on_process_exit() {
    let Some(flag) = EXIT_STOP_FLAG.get() else { return };
    if !flag.load(Ordering::Acquire) {
        return;
    }
    let Some(record) = EXIT_RECORD
        .get()
        .and_then(|slot| slot.lock().ok()?.take())
    else {
        return;
    };
    let _ = std::panic::catch_unwind(|| {
        let Ok(text) = std::fs::read_to_string(&record.path) else { return };
        let Ok(mut json) = serde_json::from_str::<serde_json::Value>(&text) else { return };
        if json.get("token").and_then(|t| t.as_str()) != Some(record.token.as_str()) {
            return;
        }
        json["exit"] = serde_json::Value::String("已停止（退出码 0）".into());
        json["cleanExit"] = serde_json::Value::Bool(true);
        if let Ok(out) = serde_json::to_string(&json) {
            let _ = crate::fsutil::write_text_atomic(&record.path, &out);
        }
    });
}

pub fn arm(path: PathBuf, token: String, stop: std::sync::Arc<AtomicBool>) {
    let slot = EXIT_RECORD.get_or_init(|| Mutex::new(None));
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(ExitRecord { path, token });
    }
    let _ = EXIT_STOP_FLAG.set(stop);
    #[cfg(target_os = "android")]
    ATEXIT_ONCE.call_once(|| unsafe {
        libc::atexit(record_clean_exit_on_process_exit);
    });
}
