//! 诊断日志落盘。
//!
//! 从 `util` 拆出来的：这里同时管两个落点 ——
//!
//! 1. `qookix-install-debug.log`（安装/设置这类全程诊断，日志页读不到）
//! 2. `logs/launch-<实例>.log`（本次启动，日志页与「分享日志」包读的就是它）
//!
//! 两个落点以前只写了第一个，于是闪退后用户在日志页只看到「暂无日志」，
//! 而最关键的 classpath 明细全在被藏起来的文件里。

/// 诊断日志落盘位置。
///
/// Android 上 `std::env::temp_dir()` 是 `/data/local/tmp`，应用沙箱**不可写**，
/// 日志会静默丢失；因此优先写进应用私有数据目录。
fn log_path() -> std::path::PathBuf {
    if let Some(dir) = crate::settings::data_dir_sync() {
        if std::path::Path::new(&dir).is_dir() {
            return std::path::Path::new(&dir).join("qookix-install-debug.log");
        }
    }
    std::env::temp_dir().join("qookix-install-debug.log")
}

/// 本次启动的日志文件（`<私有目录>/logs/launch-<实例>.log`）。
///
/// 启动流程登记一次，`log_line` 就同时写两份；游戏退出后清掉。
static LAUNCH_LOG: std::sync::Mutex<Option<std::path::PathBuf>> = std::sync::Mutex::new(None);

/// 登记本次启动的日志文件，之后的 `log_line` 会同时写进它。
pub fn set_launch_log(path: std::path::PathBuf) {
    if let Ok(mut g) = LAUNCH_LOG.lock() {
        *g = Some(path);
    }
}

/// 启动结束（游戏进程退出）后调用，避免后续日志继续写进上一次的启动日志。
pub fn clear_launch_log() {
    if let Ok(mut g) = LAUNCH_LOG.lock() {
        *g = None;
    }
}

fn append_line(path: &std::path::Path, line: &str) {
    if let Ok(mut f) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
    {
        use std::io::Write;
        let _ = f.write_all(line.as_bytes());
    }
}

/// 落盘诊断日志（追加写）。
/// 供 `log_debug` 命令与关键错误排查使用，避免 GUI 无控制台时无法定位问题。
pub fn log_line(msg: &str) {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let line = format!("[{now}] {msg}\n");
    append_line(&log_path(), &line);
    if let Ok(g) = LAUNCH_LOG.lock() {
        if let Some(path) = g.as_ref() {
            append_line(path, &line);
        }
    }
}
