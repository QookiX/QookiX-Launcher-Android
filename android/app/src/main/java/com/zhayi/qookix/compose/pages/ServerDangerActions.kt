package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 服务器的「危险操作」：删除入口 + 确认框。
 *
 * ── 为什么要单独一个文件（2026-10-26）──────────────────────────────
 * 这条入口原来只写在「文件」tab 的最底部（要滚好几屏才看得到），用户凭直觉去
 * 「服务器设置」找删除会找不到（审查 B-17）。现在设置页也放一个入口，两个入口
 * 共用同一个确认框 —— 所以卡片和对话框都提到这里，`ServerDetailPage` 只留调用。
 */

/** 删除服务器的入口卡（红色，服务器运行中时禁用）。 */
@Composable
fun DeleteServerCard(
    running: Boolean,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QkSpacing.sm),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.padding(QkSpacing.md)) {
            Text(
                text = stringResource(R.string.server_detail_delete_server_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(QkSpacing.xxs))
            Text(
                text = stringResource(R.string.server_detail_delete_server_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(QkSpacing.sm))
            TextButton(onClick = onDelete, enabled = !running) {
                Text(
                    stringResource(R.string.server_detail_delete_this_server),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 删除服务器的二次确认（不可撤销）。 */
@Composable
fun DeleteServerConfirmDialog(
    serverName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_detail_delete_server_title)) },
        text = { Text(stringResource(R.string.server_detail_delete_server_message, serverName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.server_detail_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.server_detail_cancel))
            }
        },
    )
}
