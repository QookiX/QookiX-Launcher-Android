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
     * 解析 `qookix://lan/pair?host=…&port=…&token=…`。
     *
     * @return 解析成功则写入 [pending] 并返回 true；不是同步 URI 或缺参则返回 false。
     */
    fun handle(uri: Uri): Boolean {
        if (uri.scheme != "qookix" || uri.host != "lan") return false
        val pcHost = uri.getQueryParameter("host")
        val port = uri.getQueryParameter("port")?.toIntOrNull()
        val token = uri.getQueryParameter("token")
        if (pcHost.isNullOrBlank() || port == null || token.isNullOrBlank()) {
            Log.w(TAG, "lan/pair URI 缺参数：$uri")
            return false
        }
        pending.value = LanSyncParams(pcHost, port, token)
        Log.i(TAG, "收到配对请求：$pcHost:$port")
        return true
    }
}
