package com.zhayi.qookix.compose.lansync

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 局域网同步配对参数。
 *
 * 由桌面端二维码 `qookix://lan/pair?host=<ip>&port=<port>&token=<token>` 解析而来。
 */
data class LanSyncParams(
    val host: String,
    val port: Int,
    val token: String,
)

/**
 * Deep link `qookix://lan/pair?…` 的参数中转。
 *
 * Activity 侧（`onNewIntent` / `onCreate`）解析 URI 后写入 [pending]；
 * Compose 侧（`LauncherRoot`）观察 [pending]，非 null 时跳转到同步页。
 * 同步页消费完（连上 PC 或用户手动输入后）清空。
 */
object LanSyncDeepLink {
    private const val TAG = "LanSyncDeepLink"

    /** 非 null = 有待处理的配对请求。 */
    val pending = MutableStateFlow<LanSyncParams?>(null)

    /**
     * 纯解析：`qookix://lan/pair?host=…&port=…&token=…` → [LanSyncParams]。
     *
     * 不写 [pending] —— 页内扫码拿到就用（见 LanSyncPage 的 onScanned），
     * 避免「连过之后 pending 消费条件不成立 → 扫码没反应」。
     */
    fun parse(uri: Uri): LanSyncParams? {
        if (uri.scheme != "qookix" || uri.host != "lan") return null
        val pcHost = uri.getQueryParameter("host")
        val port = uri.getQueryParameter("port")?.toIntOrNull()
        val token = uri.getQueryParameter("token")
        if (pcHost.isNullOrBlank() || port == null || token.isNullOrBlank()) {
            Log.w(TAG, "lan/pair URI 缺参数：$uri")
            return null
        }
        return LanSyncParams(pcHost, port, token)
    }

    /**
     * 解析并写入 [pending]（供 Activity 的 deep link 入口使用）。
     *
     * @return 解析成功则写入 [pending] 并返回 true；不是同步 URI 或缺参则返回 false。
     */
    fun handle(uri: Uri): Boolean {
        val parsed = parse(uri) ?: return false
        // StateFlow 按 equals 去重：同一台 PC、同一个 token 再扫一次不会发射。
        // 先置空再赋值，保证「重复扫同一个码」也能重新触发。
        pending.value = null
        pending.value = parsed
        Log.i(TAG, "收到配对请求：${parsed.host}:${parsed.port}")
        return true
    }
}
