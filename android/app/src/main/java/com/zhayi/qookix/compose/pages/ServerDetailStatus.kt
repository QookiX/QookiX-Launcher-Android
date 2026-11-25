package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing

/** 自动休眠 / 唤醒失败的顶部提示。 */
@Composable
internal fun ServerRuntimeBanners(state: ServerDetailUiState) {
    state.wakeError?.let { err ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = QkSpacing.md, vertical = 4.dp),
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Row(
                modifier = Modifier.padding(QkSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.server_detail_wake_failed, err),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
    if (state.sleeping) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = QkSpacing.md, vertical = 4.dp),
            shape = MaterialTheme.shapes.small,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Text(
                text = stringResource(R.string.server_detail_sleeping),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(QkSpacing.sm),
            )
        }
    }
}

/**
 * 服务器详情的**顶栏内容**：`服务器名 / ● 状态  [启动|停止|唤醒]`。
 */
@Composable
fun ServerDetailToolbar(
    name: String,
    running: Boolean,
    jvmUp: Boolean,
    sleeping: Boolean,
    runtimeCurrent: Boolean,
    address: String,
    /** 启动阶段文案（`server://stage` 推来）。空串 = 没有进行中的阶段。 */
    stage: String,
    coreInstalled: Boolean,
    installing: Boolean,
    /** null = 没有进行中的启停；true = 启动/唤醒；false = 停服。 */
    lifecycleAction: Boolean?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInstallCore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name.ifBlank { stringResource(R.string.server_detail_untitled) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = when {
                        running -> MaterialTheme.colorScheme.primary
                        sleeping || jvmUp -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    },
                ) {}
                Spacer(Modifier.width(6.dp))
                Text(
                    text = when {
                        !runtimeCurrent -> stringResource(R.string.server_detail_state_unknown)
                        sleeping -> stringResource(R.string.server_detail_sleeping)
                        running -> address.ifBlank { stringResource(R.string.server_detail_running) }
                        jvmUp -> stage.ifBlank { stringResource(R.string.server_detail_starting) }
                        else -> stringResource(R.string.server_detail_stopped)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        when {
            !coreInstalled -> Button(onClick = onInstallCore, enabled = !installing) {
                Text(
                    if (installing) stringResource(R.string.server_detail_installing)
                    else stringResource(R.string.server_detail_install_core),
                )
            }
            lifecycleAction != null -> FilledTonalButton(onClick = {}, enabled = false) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stage.ifBlank {
                        if (lifecycleAction) stringResource(R.string.server_detail_starting)
                        else stringResource(R.string.server_detail_stopping)
                    },
                    maxLines = 1,
                )
            }
            sleeping -> Button(onClick = onStart, enabled = runtimeCurrent) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.server_detail_wake))
            }
            running -> FilledTonalButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.server_detail_stop))
            }
            jvmUp -> FilledTonalButton(onClick = {}, enabled = false) {
                Text(stage.ifBlank { stringResource(R.string.server_detail_starting) }, maxLines = 1)
            }
            else -> Button(onClick = onStart, enabled = runtimeCurrent) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.server_detail_start))
            }
        }
    }
}
