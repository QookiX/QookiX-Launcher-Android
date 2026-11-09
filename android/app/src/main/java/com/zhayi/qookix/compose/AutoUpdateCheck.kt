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
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 冷启动自动检查更新：开关持久化在 SharedPreferences，24 小时节流，
 * 等桥就绪后延迟 4 秒再发；失败静默（用户没主动要这个结果），只在有新版本时提示一次。
 * 2026-10-29 补：原来「自动检查」开关只改内存、没人读，也没人调 check_for_update。
 */
/** 「去「关于」页」的导航请求：LauncherRoot 负责切页，SettingsRoute 负责切到关于 tab。 */
object UpdateNavRequest {
    val target = MutableStateFlow<String?>(null)
    fun goToAbout() { target.value = "about" }
    fun consume() { target.value = null }
}
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
                TextButton(onClick = {
                    newVersion = null
                    UpdateNavRequest.goToAbout()
                }) {
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
