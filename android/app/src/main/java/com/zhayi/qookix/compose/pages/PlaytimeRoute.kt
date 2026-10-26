package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge

/**
 * 游玩统计页。
 *
 * 对应旧界面 `PlaytimeCard.vue`。
 * 不依赖网络也不依赖实例数据 —— 数据来自 accounts/instances 目录里的游玩记录。
 *
 * 从 `PageHost` 拆出来（Q2 体量棘轮）：这个页面自带数据加载，和宿主没有耦合。
 */
@Composable
fun PlaytimeRoute(bridge: RustBridge, ready: Boolean) {
    val context = LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    var state by remember { mutableStateOf(PlaytimeUiState()) }

    fun reload() {
        state = state.copy(loading = true, error = null)
        api.playtimeStats { r ->
            r.fold(
                onSuccess = { o ->
                    // ⚠️ 字段是 camelCase（PlaytimeStats 带 rename_all = "camelCase"）
                    val byInstance = o.optJSONArray("byInstance")
                    val inst = (0 until (byInstance?.length() ?: 0)).mapNotNull { i ->
                        val e = byInstance?.optJSONObject(i) ?: return@mapNotNull null
                        InstancePlaytimeUi(
                            id = e.optString("id"),
                            name = e.optString("name").ifBlank { e.optString("id") },
                            seconds = e.optLong("seconds", 0L),
                            lastPlayed = e.optLong("lastPlayed", 0L),
                        )
                    }.sortedByDescending { it.seconds }

                    val byDayArr = o.optJSONArray("byDay")
                    val days = (0 until (byDayArr?.length() ?: 0)).mapNotNull { i ->
                        val e = byDayArr?.optJSONObject(i) ?: return@mapNotNull null
                        DailyPlaytimeUi(
                            day = e.optLong("day"),
                            seconds = e.optLong("seconds", 0L),
                        )
                    }.takeLast(30)

                    state = state.copy(
                        totalSeconds = o.optLong("totalSeconds", 0L),
                        byInstance = inst,
                        byDay = days,
                        loading = false,
                    )
                },
                onFailure = { e ->
                    state = state.copy(
                        loading = false,
                        error = context.getString(R.string.frp_stats_read_failed, e.message),
                    )
                },
            )
        }
    }

    LaunchedEffect(ready) {
        if (ready) reload()
    }

    PlaytimePage(state = state, onRefresh = { reload() })
}
