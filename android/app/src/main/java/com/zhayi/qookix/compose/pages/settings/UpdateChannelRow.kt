package com.zhayi.qookix.compose.pages.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 更新渠道（2026-10-30）。
 *
 * 之前只查 `/releases/latest`：装了 rc 的人收不到下一个 rc，想试 beta 的人也没有入口。
 * 现在三档：
 *  - 正式版：只收正式
 *  - RC：正式 + 候选版
 *  - Beta：全收（含 beta）
 *
 * 值存在 `settings.json` 的 `update_channel`，由 Rust 侧 `check_for_update` 读取过滤。
 */
private val CHANNELS = listOf(
    "stable" to R.string.about_update_channel_stable,
    "rc" to R.string.about_update_channel_rc,
    "beta" to R.string.about_update_channel_beta,
)

@Composable
fun UpdateChannelChips(
    updateChannel: String,
    onPickChannel: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
    ) {
        CHANNELS.forEach { (id, label) ->
            FilterChip(
                selected = updateChannel == id,
                onClick = { onPickChannel(id) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}


/** 「关于 → 更新」里那一整行（标题 + 副标题 + 三个 chip） */
@Composable
fun UpdateChannelSettingsRow(updateChannel: String, onPickChannel: (String) -> Unit) {
    SettingsRow(
        title = stringResource(R.string.about_update_channel),
        subtitle = updateChannelDesc(updateChannel),
        column = true,
        showDivider = false,
    ) {
        UpdateChannelChips(updateChannel, onPickChannel)
    }
}

/** 渠道副标题文案 */
@Composable
fun updateChannelDesc(updateChannel: String): String = stringResource(
    when (updateChannel) {
        "beta" -> R.string.about_update_channel_beta_desc
        "rc" -> R.string.about_update_channel_rc_desc
        else -> R.string.about_update_channel_stable_desc
    }
)

/**
 * 读一次当前渠道并存成可写状态（点击切换时由调用方赋值）。
 *
 * 单独放这里是为了让 `BrowseSettingsRoutes` 保持体量（那个文件已顶到预算）。
 */
@Composable
fun rememberUpdateChannel(api: QookixApi, bridgeReady: Boolean): MutableState<String> {
    val state = remember { mutableStateOf("stable") }
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        api.getSettings { r ->
            r.onSuccess { o ->
                state.value = o.optString("update_channel").ifBlank { "stable" }
            }
        }
    }
    return state
}
