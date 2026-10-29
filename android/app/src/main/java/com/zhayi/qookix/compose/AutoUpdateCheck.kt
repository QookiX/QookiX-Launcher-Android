package com.zhayi.qookix.compose

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.checkForUpdate
import com.zhayi.qookix.compose.bridge.RustBridge
import kotlinx.coroutines.delay

/**
 * 冷启动时自动检查更新。
 *
 * ## 为什么需要这个组件
 *
 * 2026-10-29 用户反馈：「只有手动点才有检查，即使开了自动检查」——属实。
 *
 * 「关于」页那个 `autoCheck` 开关原来只是一个 Composable 的 state：
 *   · 点它改的是内存里的值，**不持久化**；
 *   · 没有任何地方读它去触发 `check_for_update` ——
 *     那个命令只在用户手动点「检查更新」时才发。
 *
 * 所以那是个完全没接线的开关。这里补上真正的自动检查。
 *
 * ## 三个约束
 *
 * 1. **24 小时节流**：未登录的 GitHub API 限额是 60 次/小时，
 *    每次冷启动都请求既浪费又容易被限流。存「上次检查时间」到
 *    SharedPreferences，一天最多查一次。
 * 2. **等桥就绪**：`RustBridge` 是异步注入的，太早调用会直接失败。
 *    延迟 4 秒再发（和界面首次加载错开，也不争主线程）。
 * 3. **失败静默**：网络不通、被限流、解析失败 —— 用户没主动要这个结果，
 *    不能弹错误框打断。只在**确认有新版本**时提示一次。
 *
 * @param activity 用来读/写自动检查的开关与上次检查时间
 */
@Composable
fun AutoUpdateCheck(bridge: RustBridge, activity: ComposeMainActivity?) {
    var newVersion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val act = activity ?: return@LaunchedEffect
        if (!act.autoCheckUpdatePref()) return@LaunchedEffect

        // 24 小时节流（`since` 为负说明系统时间被调过，也当成该检查）
        val since = System.currentTimeMillis() - act.lastUpdateCheckAt()
        if (since in 0 until DAY_MS) return@LaunchedEffect

        delay(4_000)
        QookixApi(bridge).checkForUpdate { r ->
            // 无论成功失败都记账：失败也等下个 24 小时，
            // 否则离线启动会每次都等 4 秒再失败一遍。
            act.markUpdateChecked()
            r.onSuccess { o ->
                if (o.optBoolean("available")) {
                    val v = o.optString("version").takeIf { it.isNotBlank() }
                    if (v != null) newVersion = v
                }
            }
        }
    }

    newVersion?.let { v ->
        AlertDialog(
            onDismissRequest = { newVersion = null },
            title = { Text(stringResource(R.string.auto_update_title)) },
            text = { Text(stringResource(R.string.auto_update_body, v)) },
            confirmButton = {
                TextButton(onClick = { newVersion = null }) {
                    Text(stringResource(R.string.auto_update_go))
                }
            },
            dismissButton = {
                TextButton(onClick = { newVersion = null }) {
                    Text(stringResource(R.string.auto_update_later))
                }
            },
        )
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
