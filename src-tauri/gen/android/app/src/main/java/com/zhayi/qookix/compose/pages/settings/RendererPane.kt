package com.zhayi.qookix.compose.pages.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R

/**
 * 渲染器设置。
 *
 * ── 为什么这个设置是「游戏能不能启动」的前提 ────────────────────────────
 * 实测（2026-10-13）：实例创建、下载都正常，点「启动游戏」后
 * Minecraft 崩在：
 *
 * ```
 * java.lang.UnsatisfiedLinkError: Failed to locate library: libmobileglues.so
 *   Description: Loading library OpenGL
 * ```
 *
 * 原因：渲染器由 Rust 侧的「按版本自动」规则决定 ——
 * **MC 26.3 会被自动判定用 MobileGlues**，而 MobileGlues
 * **不在 APK 里**（已改为插件分发，APK 只随包 GL4ES 兜底）。
 *
 * Rust 有「找不到就回退 GL4ES」的逻辑，但**渲染器的键在别处仍被当成
 * mobileglues 传给 C 层**，于是 C 层仍然去 dlopen 那个不存在的库。
 *
 * **绕开办法就是显式把渲染器设成 `opengles2`（GL4ES）。**
 * 旧 Vue 界面有这个设置（`SettingsView.vue` 的 `rendererOptions`），
 * 我先前**完全漏做了** —— 结果就是「装好了却启动不了」。
 *
 * ── 三个选项 ────────────────────────────────────────────────────────────
 * | 键 | 名字 | 什么时候用 |
 * |---|---|---|
 * | `opengles2` | GL4ES | **随包自带，最稳**。兼容性最好，性能一般 |
 * | `mobileglues` | MobileGlues | 性能好，但**需要先在「设置 → 插件」装** |
 * | `vulkan_zink` | Vulkan (Zink) | 需要 Turnip 驱动插件，配置门槛高 |
 *
 * UI 上把「随包自带」标出来，避免用户选了没装的渲染器然后启动失败。
 */
@Composable
fun RendererPane(
    current: String,
    onChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_renderer_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_renderer_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Renderer.entries.forEach { r ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(r.labelRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (r.bundled) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.settings_renderer_bundled),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                text = stringResource(r.hintRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(
                            onClick = { onChange(r.id) },
                            enabled = current != r.id,
                        ) {
                            Text(
                                if (current == r.id) stringResource(R.string.settings_renderer_in_use)
                                else stringResource(R.string.settings_renderer_switch)
                            )
                        }
                    }
                }
            }
        }

        // 选到需要插件、但可能没装的渲染器时给一句明确提醒 ——
        // 否则用户会遇到「启动就崩，崩在 libXxx.so 找不到」，很难自己归因
        val selected = Renderer.entries.firstOrNull { it.id == current }
        if (selected != null && !selected.bundled) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = stringResource(
                        R.string.settings_renderer_not_bundled_warning,
                        stringResource(selected.labelRes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** 渲染器选项（键与 Rust `resolve_renderer` 认识的值一致）。 */
enum class Renderer(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
    /** 是否随 APK 打包（true = 不需要装插件就能用）。 */
    val bundled: Boolean,
) {
    Gl4es(
        id = "opengles2",
        labelRes = R.string.settings_renderer_label_gl4es,
        hintRes = R.string.settings_renderer_hint_gl4es,
        bundled = true,
    ),
    MobileGlues(
        id = "mobileglues",
        labelRes = R.string.settings_renderer_label_mobileglues,
        hintRes = R.string.settings_renderer_hint_mobileglues,
        bundled = false,
    ),
    Zink(
        id = "vulkan_zink",
        labelRes = R.string.settings_renderer_label_zink,
        hintRes = R.string.settings_renderer_hint_zink,
        bundled = false,
    ),
}

