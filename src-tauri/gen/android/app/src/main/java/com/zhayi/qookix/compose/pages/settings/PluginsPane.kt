package com.zhayi.qookix.compose.pages.settings

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 插件设置子页（8 个子页的最后一个）。
 *
 * 对应旧界面 SettingsView.vue 的 plugins tab。
 * 命令：`get_plugins` / `get_plugin_setup_status` /
 *       `refresh_plugin_manifest` / `install_recommended_plugins`
 *
 * ── 插件是什么 ──────────────────────────────────────────────────────────
 * 启动器的插件不是 Minecraft 模组，而是**启动器自身的扩展**
 * （如渲染器适配、翻译服务）。它们以独立文件形式存在，
 * 由 Rust 侧的 plugin.rs（40 KB）加载。
 *
 * ⚠️ 首次使用的引导很重要：插件没初始化时，
 * 渲染器相关功能会「看起来能用但实际不生效」——
 * 所以未就绪时这里要显眼提示，而不是静默。
 */
@Composable
fun PluginsPane(
    state: PluginsUiState,
    onRefresh: () -> Unit,
    onInstallRecommended: () -> Unit,
    onTogglePlugin: (PluginUi, Boolean) -> Unit = { _, _ -> },
    onUninstallPlugin: (PluginUi) -> Unit = {},
    onSaveManifestUrl: (String) -> Unit = {},
) {
    SettingsPane(title = stringResource(R.string.plugins_title)) {
        // ── 初始化状态 ────────────────────────────────────────────────
        if (!state.ready) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(QkSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.plugins_not_initialized),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.height(QkSpacing.xxs))
                        Text(
                            text = stringResource(R.string.plugins_not_initialized_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(QkSpacing.md))

            FilledTonalButton(
                onClick = onInstallRecommended,
                enabled = !state.busy,
            ) {
                Text(
                    if (state.busy) stringResource(R.string.plugins_installing)
                    else stringResource(R.string.plugins_install_recommended)
                )
            }
            // 安装进度（审计 H-6）：显示正在装哪个、进度多少
            if (state.busy && (state.installMessage.isNotBlank() || state.installProgress > 0f)) {
                Spacer(Modifier.height(QkSpacing.sm))
                Column {
                    LinearProgressIndicator(
                        progress = { state.installProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = state.installMessage.ifBlank {
                            stringResource(R.string.plugins_installing_progress)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(QkSpacing.lg))
        }

        // ── 已装插件列表 ──────────────────────────────────────────────
        SettingsGroup(stringResource(R.string.plugins_installed_count, state.plugins.size)) {
            when {
                state.loading && state.plugins.isEmpty() -> {
                    Column(modifier = Modifier.padding(QkSpacing.md)) {
                        SkeletonList(rows = 2, rowHeight = 56.dp)
                    }
                }

                state.plugins.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(QkSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.plugins_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.plugins_refresh))
                        }
                    }
                }

                else -> state.plugins.forEachIndexed { i, p ->
                    PluginRow(
                        p,
                        showDivider = i != state.plugins.lastIndex,
                        onToggle = { enabled -> onTogglePlugin(p, enabled) },
                        onUninstall = { onUninstallPlugin(p) },
                    )
                }
            }
        }

        // ── 手动刷新 ──────────────────────────────────────────────────
        if (state.plugins.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                FilledTonalButton(onClick = onRefresh, enabled = !state.busy) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.plugins_check_updates))
                }
            }
        }

        // ── 插件清单源（2026-10-21 补：后端 get/set_plugin_manifest_url 一直没 UI）───
        Spacer(Modifier.height(QkSpacing.md))
        var manifestUrl by remember(state.manifestUrl) { mutableStateOf(state.manifestUrl) }
        SettingsGroup(stringResource(R.string.plugins_manifest_url)) {
            Column(modifier = Modifier.padding(QkSpacing.md)) {
                OutlinedTextField(
                    value = manifestUrl,
                    onValueChange = { manifestUrl = it },
                    label = { Text(stringResource(R.string.plugins_manifest_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                TextButton(
                    onClick = { onSaveManifestUrl(manifestUrl.trim()) },
                    enabled = manifestUrl.trim() != state.manifestUrl,
                ) {
                    Text(stringResource(R.string.plugins_save_manifest_url))
                }
            }
        }

        // ── 错误提示 ──────────────────────────────────────────────────
        if (state.error != null) {
            Spacer(Modifier.height(QkSpacing.sm))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ── 说明 ──────────────────────────────────────────────────────
        Spacer(Modifier.height(QkSpacing.lg))
        Text(
            text = stringResource(R.string.plugins_about),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun PluginRow(
    p: PluginUi,
    showDivider: Boolean,
    onToggle: (Boolean) -> Unit = {},
    onUninstall: () -> Unit = {},
) {
    SettingsRow(
        title = p.name,
        subtitle = buildString {
            if (p.version.isNotBlank()) append("v").append(p.version)
            if (p.description.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(p.description)
            }
        },
        showDivider = showDivider,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 启停开关（2026-10-21 补：set_plugin_enabled 一直没 UI）
            Switch(
                checked = p.enabled,
                onCheckedChange = onToggle,
            )
            Spacer(Modifier.width(4.dp))
            // 卸载按钮（2026-10-21 补：uninstall_plugin 一直没 UI）
            IconButton(onClick = onUninstall) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.plugins_uninstall),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 插件条目（UI 模型）。 */
data class PluginUi(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val enabled: Boolean = true,
)

/** 插件页状态。 */
data class PluginsUiState(
    val ready: Boolean = true,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val plugins: List<PluginUi> = emptyList(),
    val manifestUrl: String = "",
    val error: String? = null,
    /**
     * 正在安装的插件名与进度（2026-10-17 补，审计 H-6）。
     *
     * `plugin://progress` 的 payload = { id, phase, done, total, message }。
     * 原来 Compose 完全不监听 —— 装插件时只有转圈，
     * 不知道在下什么、下了多少。Vue 侧 FirstRunSetup/SettingsView 都是正常的。
     */
    val installMessage: String = "",
    val installProgress: Float = 0f,
)


