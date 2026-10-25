package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 实例列表「没有内容」时的两种含义，必须分开说：
 *
 * - **读失败**（[error] 非空）：权限没开、`instance.json` 全坏、目录不在 ——
 *   这时说「还没有任何实例」等于告诉用户「你的数据没了」。
 *   0.2.0 上真实发生过：升级后实例列表空空如也，实际文件都在外部存储里。
 * - **真的是空的**：新用户，引导他去创建。
 *
 * 抽成独立文件也是为了让 `InstancesPage` 不再变大（它已在体量棘轮名单里）。
 */
@Composable
fun InstancesEmptyState(
    error: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(QkSpacing.sm))
                TextButton(onClick = { onRetry?.invoke() }) {
                    Text(stringResource(R.string.first_run_retry))
                }
            } else {
                Text(
                    text = stringResource(R.string.instances_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                Text(
                    text = stringResource(R.string.instances_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
