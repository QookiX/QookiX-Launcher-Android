package com.zhayi.qookix.compose.bridge

/**
 * Rust 命令失败的结构化错误（A2 错误契约的第一步，见 `docs/adr/0002`）。
 *
 * ## 为什么要有它
 *
 * 改造前失败只有一句 `IllegalStateException("Failed to search Modrinth")`：
 * UI 拿到的是一段**英文技术串**，分不清「网络抖动能重试」还是
 * 「Key 没配，重试一万次也没用」。于是每个页面各自拿中文关键词去猜
 * （`friendlyError` 里那一长串 `contains`），猜不中就原样丢给用户。
 *
 * 现在 Rust 在信封里直接给 `code` + `retryable`：
 * ```
 * { "ok": false, "error": "...", "code": "network", "retryable": true }
 * ```
 * Kotlin 侧解析成这个异常，页面只判断 `retryable`，不再猜文本。
 *
 * ## 兼容性
 *
 * 老命令只回 `{ok:false, error}`，没有 `code` —— 此时 `code="unknown"`、
 * `retryable=false`，行为与改造前一致（`message` 仍是原文）。
 *
 * @property code `network` / `auth` / `not_found` / `io` / `parse` / `unknown`
 * @property retryable 重试是否**可能**成功。`false` 时应引导用户改配置/换操作，
 *   而不是给一个「重试」按钮
 */
class ApiError(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : Exception(message) {

    val isNetwork: Boolean get() = code == CODE_NETWORK

    companion object {
        const val CODE_NETWORK = "network"
        const val CODE_AUTH = "auth"
        const val CODE_NOT_FOUND = "not_found"
        const val CODE_IO = "io"
        const val CODE_PARSE = "parse"
        const val CODE_UNKNOWN = "unknown"

        /**
         * 桥层自己产生的超时（Rust 侧不会发这个 code）。
         * 见 [RustBridge.invoke] 的 `timeoutMs`：命令卡住时至少给界面一个可重试的出口。
         */
        const val CODE_TIMEOUT = "timeout"
    }
}
