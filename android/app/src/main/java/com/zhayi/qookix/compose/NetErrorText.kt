package com.zhayi.qookix.compose

/**
 * 把 reqwest 的原始异常翻成一句人话（审计 FR-03）。
 *
 * 以前「新建实例」页直接把这一整串甩给用户：
 *
 * ```
 * Failed to fetch version manifest: https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
 *   -> error sending request for url (...) | client error (Connect) | tls handshake eof
 * ```
 *
 * 里面有 URL、有 reqwest 内部措辞、还有底层原因 —— 用户既看不懂也不知道该做什么，
 * 而真正的线索（`tls handshake eof` = TLS 被中途掐断）恰好藏在最后面。
 *
 * 现在界面只留「能照做的那一句」，完整串仍然由调用方打进 logcat
 * （`Log.w(TAG, ...)`），排查时照样拿得到。
 *
 * ⚠️ 匹配顺序有讲究：`tls handshake eof` 里没有 timeout，但 `operation timed out`
 * 里也没有 tls —— 两类互斥，先后无碍；但 DNS 类错误有时会带 "connect"，
 * 所以具体原因必须排在通用兜底之前。
 */
fun friendlyNetError(raw: String?): String {
    val s = raw.orEmpty()
    return when {
        s.contains("timed out", true) || s.contains("timeout", true) -> "连接超时"
        s.contains("tls", true) || s.contains("handshake", true) || s.contains("certificate", true) ->
            "安全连接失败（代理或 VPN 可能拦截了请求）"
        s.contains("dns", true) || s.contains("lookup", true) || s.contains("resolve", true) -> "域名解析失败"
        s.contains("refused", true) -> "服务器拒绝连接"
        s.contains("404") -> "资源不存在（404）"
        else -> "网络请求失败"
    }
}
