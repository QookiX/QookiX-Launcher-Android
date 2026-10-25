package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 服务器列表的四种状态：加载中 / 读取失败 / 空 / 列表。
 *
 * 从 `MultiplayerPage.ServersTab` 里拆出来的（Q2 体量棘轮：那个文件只许变小）。
 *
 * ── 为什么要单独区分「读取失败」和「空」──────────────────────────────
 * `list_hosted_servers` 以前读不到目录也返回空数组，界面只有「还没有服务器」
 * 这一种说法。2026-10-25 有用户升级后看到这句话，以为自己的服务器被删了，
 * 实际只是没权限读目录。现在 Rust 会如实报错，这里必须把错误显示出来，
 * 否则等于继续骗用户。
 */
@Composable
internal fun ServersListPane(
    state: MultiplayerUiState,
    filtered: List<ServerUi>,
    query: String,
    onStartServer: (String) -> Unit,
    onStopServer: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        // 只有「还没有数据」时才显示骨架屏；已有列表时刷新不该闪成空页。
        state.loading && state.servers.isEmpty() ->
            SkeletonList(rows = 3, rowHeight = 96.dp)

        // 读不到目录 ≠ 没有服务器：给出原因 + 重试出口。
        state.servers.isEmpty() && state.error != null -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(QkSpacing.sm))
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.first_run_retry))
                }
            }
        }

        filtered.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (query.isNotBlank() && state.servers.isNotEmpty())
                        stringResource(R.string.multiplayer_search_no_result)
                    else stringResource(R.string.multiplayer_empty_servers_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                if (query.isBlank()) {
                    Text(
                        text = stringResource(R.string.multiplayer_empty_servers_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
        ) {
            // 列表还在、只是这次刷新失败：保留旧数据，但把原因摆出来。
            state.error?.let { msg ->
                item {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(filtered, key = { it.id }) { s ->
                ServerCard(
                    server = s,
                    onStart = { onStartServer(s.id) },
                    onStop = { onStopServer(s.id) },
                    onOpen = { onOpenServer(s.id) },
                )
            }
        }
    }
}
