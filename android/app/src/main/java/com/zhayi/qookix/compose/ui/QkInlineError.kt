package com.zhayi.qookix.compose.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 页面内的错误条：一行红底提示，用于「某个状态字段里攒了错误但没人渲染」的场景。
 *
 * ⚠️ 为什么要有（2026-10-26 UI 审查 P1-1 家族）：`InstanceDetailUiState.error`、
 * `CreateUiState.error` 这类字段是**只写不读**的 —— 几十处失败分支把原因写进 state，
 * 界面上一个字都不显示，用户看到的就是「点了没反应」。各页面自己画一条又要各写 30 行，
 * 所以统一收在这里，调用方只写一行。
 */
@Composable
fun QkInlineError(
    text: String,
    modifier: Modifier = Modifier.padding(horizontal = QkSpacing.lg, vertical = 4.dp),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(QkSpacing.xs))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
