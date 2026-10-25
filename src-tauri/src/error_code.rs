//! 错误分类与失败信封（A2 错误契约，背景见 `docs/adr/0002-single-source-of-truth.md`）。
//!
//! ## 为什么在信封层做，而不是给 155 个命令改签名
//!
//! 命令现在都返回 `Result<T, impl Display>`。逐个改成 `enum AppError` 是周级改动，
//! 但 UI 真正需要的是**能区分原因**（可重试 / 不可重试、网络 / 配错了 Key），
//! 不是类型完美。这里在出口处归一一次，上层就能按 `code` 给稳定文案。
//!
//! ## 事实来源
//!
//! `code` 与 `retryable` 的定义只在这里；Kotlin 侧 `ApiError` 只是解析者，
//! 不要在两端各维护一张分类表。

/// 把错误文本归到稳定的 `code`，并判断「重试有没有意义」。
///
/// 命中顺序：网络 → 鉴权 → 找不到 → 磁盘/权限 → 解析 → 未知。
/// 顺序有讲究：`连接失败: status 404` 应该算**网络**（可重试），
/// 而不是 not_found。
pub(crate) fn classify_error(msg: &str) -> (&'static str, bool) {
    let low = msg.to_lowercase();
    let has = |keys: &[&str]| keys.iter().any(|k| low.contains(k));

    if has(&[
        "timed out",
        "timeout",
        "dns",
        "connection refused",
        "connection reset",
        "network is unreachable",
        "error sending request",
        "no address associated",
        "tls",
        "handshake",
        "status 429",
        "rate limit",
        "status 5",
        "502",
        "503",
        "连接失败",
        "网络",
        "超时",
    ]) {
        return ("network", true);
    }
    if has(&["401", "403", "unauthorized", "forbidden", "api key", "token", "未登录", "认证"]) {
        return ("auth", false);
    }
    if has(&["404", "not found", "no such file", "不存在", "找不到"]) {
        return ("not_found", false);
    }
    if has(&[
        "permission denied",
        "eacces",
        // Android 上「所有文件访问」没真正生效时报的是 EPERM，文案是
        // "Operation not permitted (os error 1)" —— 不走 eacces 那条分支，
        // 不加这几个词就会被归到 unknown，界面给不出「去开权限」的引导。
        "operation not permitted",
        "eperm",
        "read-only file system",
        "no space left",
        "enospc",
        "写入失败",
        "读取失败",
        "磁盘",
        "权限",
    ]) {
        return ("io", false);
    }
    if has(&["parse", "invalid", "malformed", "解析", "序列化失败"]) {
        return ("parse", false);
    }
    // 认不出来就**不**标可重试：宁可少提示，也不要用「重试」误导用户
    ("unknown", false)
}

/// 构造失败信封：`{ ok: false, error, code, retryable }`。
///
/// ⚠️ 新增字段是向后兼容的：老 Kotlin 代码只读 `error`，照常工作；
/// 新代码（`ApiError`）能据此区分「网络抖动，重试有用」与
/// 「Key 没配，重试一万次也没用」。
pub(crate) fn error_envelope(msg: &str) -> String {
    let (code, retryable) = classify_error(msg);
    serde_json::json!({
        "ok": false,
        "error": msg,
        "code": code,
        "retryable": retryable,
    })
    .to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn network_errors_are_retryable() {
        assert_eq!(
            classify_error("error sending request for url: connection refused"),
            ("network", true)
        );
        assert_eq!(classify_error("请求超时"), ("network", true));
        assert_eq!(classify_error("HTTP status 503"), ("network", true));
    }

    #[test]
    fn rate_limit_counts_as_retryable_network() {
        assert_eq!(
            classify_error("status 429 Too Many Requests"),
            ("network", true)
        );
    }

    #[test]
    fn auth_errors_are_not_retryable() {
        assert_eq!(classify_error("401 Unauthorized"), ("auth", false));
        // 这是移除内置 Key 之后 CurseForge 未配置时的真实文案
        assert_eq!(
            classify_error("未配置 CurseForge API Key。请到「设置 → 内容服务」填写自己的 Key"),
            ("auth", false)
        );
    }

    #[test]
    fn network_keyword_wins_over_status_code() {
        assert_eq!(classify_error("连接失败: status 404").0, "network");
    }

    #[test]
    fn io_and_parse_are_classified() {
        assert_eq!(classify_error("no space left on device"), ("io", false));
        assert_eq!(classify_error("解析 JSON 失败"), ("parse", false));
    }

    #[test]
    fn android_eperm_counts_as_io() {
        // 真实事故文案：「所有文件访问」显示已允许，写 /sdcard/QookiX 却 EPERM。
        assert_eq!(
            classify_error("读取服务器目录失败：/sdcard/QookiX（Operation not permitted (os error 1))"),
            ("io", false)
        );
    }

    #[test]
    fn unknown_is_not_marked_retryable() {
        assert_eq!(classify_error("something odd happened"), ("unknown", false));
    }

    #[test]
    fn envelope_has_all_four_fields() {
        let raw = error_envelope("请求超时");
        let v: serde_json::Value = serde_json::from_str(&raw).expect("必须是合法 JSON");
        assert_eq!(v["ok"], serde_json::json!(false));
        assert_eq!(v["error"], serde_json::json!("请求超时"));
        assert_eq!(v["code"], serde_json::json!("network"));
        assert_eq!(v["retryable"], serde_json::json!(true));
    }
}
