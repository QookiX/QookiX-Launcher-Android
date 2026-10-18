package com.zhayi.qookix.compose.pages.settings

import androidx.annotation.StringRes
import com.zhayi.qookix.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * 「常规」设置子页。
 *
 * 对应旧界面 SettingsView.vue 的 general tab。
 * 覆盖 `models.rs::Settings` 里的这几个字段：
 *   close_behavior / keep_open / auto_launch / orientation / mirror
 *
 * ── 设计说明 ────────────────────────────────────────────────────────────
 * 「关闭行为」用 `FilterChip` 而不是下拉框：只有 2~3 个选项时，
 * chip 一眼看全、一点即选，比下拉少一次点击。
 */
@Composable
fun GeneralPane(
    state: SettingsUiState,
    onChange: (SettingsPatch) -> Unit,
) {
    SettingsPane(title = stringResource(R.string.settings_general)) {
        SettingsGroup(stringResource(R.string.settings_launch_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_after_launch),
                subtitle = stringResource(R.string.settings_after_launch_desc),
            column = true,
            ) {
                // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                 FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                 ) {
                    CloseBehavior.entries.forEach { b ->
                        FilterChip(
                            selected = state.closeBehavior == b.id,
                            onClick = { onChange(SettingsPatch.CloseBehavior(b.id)) },
                            label = { Text(stringResource(b.labelRes)) },
                        )
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_keep_alive),
                subtitle = stringResource(R.string.settings_keep_alive_desc),
            ) {
                Switch(
                    checked = state.keepOpen,
                    onCheckedChange = { onChange(SettingsPatch.KeepOpen(it)) },
                )
            }

            SettingsRow(
                title = stringResource(R.string.settings_autostart),
                subtitle = stringResource(R.string.settings_autostart_desc),
                showDivider = false,
            ) {
                Switch(
                    checked = state.autoLaunch,
                    onCheckedChange = { onChange(SettingsPatch.AutoLaunch(it)) },
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_orientation)) {
            SettingsRow(
                title = stringResource(R.string.settings_orientation),
                subtitle = stringResource(R.string.settings_orientation_desc),
                showDivider = false,
            column = true,
            ) {
                // ⚠️ 必须**可横向滚动**（2026-10-16 修）。
                //
                // 踩到的坑：竖屏下这一行只有 ~380dp 宽，三个 chip
                //（跟随系统 / 横屏 / 竖屏）放不下，后两个**溢出屏幕**、
                // 点都点不到 —— 而「界面方向」恰恰是用户在竖屏下最想改的
                // 那一项（想切回横屏），结果正好改不了。
                //
                // 加 horizontalScroll 后，窄屏也能滑到全部选项。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OrientationMode.entries.forEach { o ->
                        FilterChip(
                            selected = state.orientation == o.id,
                            onClick = { onChange(SettingsPatch.Orientation(o.id)) },
                            label = { Text(stringResource(o.labelRes)) },
                        )
                    }
                }
            }
        }

        SettingsGroup(stringResource(R.string.settings_download_source_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_mirror),
                subtitle = stringResource(R.string.settings_mirror_desc),
            column = true,
            ) {
                // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                 FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                 ) {
                    MirrorPreset.entries.forEach { mp ->
                        FilterChip(
                            selected = state.mirror == mp.id,
                            onClick = { onChange(SettingsPatch.Mirror(mp.id)) },
                            label = { Text(stringResource(mp.labelRes)) },
                        )
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_current_mirror),
                subtitle = state.mirror.ifBlank { stringResource(R.string.settings_mirror_official) },
                showDivider = false,
            ) {
                Text(
                    text = "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 关闭行为（对应 `close_behavior`）。 */
enum class CloseBehavior(val id: String, @StringRes val labelRes: Int) {
    Minimize("minimize", R.string.close_behavior_minimize),
    KeepOpen("keep_open", R.string.close_behavior_keep_open),
    Close("close", R.string.close_behavior_close),
}

/** 界面方向（对应 `orientation`）。 */
enum class OrientationMode(val id: String, @StringRes val labelRes: Int) {
    System("system", R.string.orientation_system),
    Landscape("landscape", R.string.orientation_landscape),
    Portrait("portrait", R.string.orientation_portrait),
}

/**
 * 下载镜像预设（对应 `mirror`）。
 *
 * ⚠️ 只有 `official` 和 `bmclapi` 两项 —— 与 Rust `mirror.rs::PRESETS`（L26-39）
 * 严格对齐（2026-10-17 修）。
 *
 * 这里曾经还有个 `Custom("custom", "自定义")`，但：
 *   · Rust 的 PRESETS 里**没有** custom（它只认 official / bmclapi 两个 id）
 *   · `resolve_from("custom", custom)` 会去读 `mirror_custom` 字段，
 *     而 **Kotlin 侧从来不写这个字段**（全仓库无写入点）
 *   · 结果：用户点「自定义」→ mirror="custom" → resolve_from 返回**空串**
 *     → 等价于官方源直连。用户以为切到了自定义镜像，**实际什么都没变，
 *     而且没有任何提示**。
 *
 * 要么补一个填 base URL 的输入框并写 `mirror_custom`，要么就别给这个选项。
 * 当前选后者：手机端做自定义镜像属于小众需求，给一个「选了不生效」的选项
 * 比没有更糟。
 */
enum class MirrorPreset(val id: String, @StringRes val labelRes: Int) {
    Official("official", R.string.mirror_preset_official),
    Bmclapi("bmclapi", R.string.mirror_preset_bmclapi),
}






