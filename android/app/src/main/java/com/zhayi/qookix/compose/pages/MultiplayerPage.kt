package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.ui.QkCompactSearchField
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.motion.pressScale
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkTabContent
import com.zhayi.qookix.compose.ui.QkTabRow

/**
 * 多人游戏。
 *
 * 对应旧界面 views/MultiplayerView.vue，它有两块功能：
 *   1. **本地服务器**（`srv-`）—— 创建/启动/停止 Minecraft 服务端
 *   2. **陶瓦联机**（`tc-`）—— 内网穿透，让朋友连进来
 *
 * ── 设计（完全重做）─────────────────────────────────────────────────────
 * 旧界面把两块内容堆在一页里纵向滚动，横屏下要滚很久。
 * 这里用 MD3 `TabRow` 分成两个 tab —— 横屏高度紧张，分 tab 比长滚动好。
 *
 * 每张服务器卡片按 MD3 的「三段式」组织：
 *   状态点 + 名称 + 版本徽标（leading）/ 运行信息（supporting）/ 操作按钮（trailing）
 */
@Composable
fun MultiplayerPage(
    state: MultiplayerUiState,
    onTabChange: (MultiplayerTab) -> Unit,
    onStartServer: (String) -> Unit,
    onStopServer: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onCreateServer: () -> Unit,
    tunnelContent: @Composable () -> Unit,
    friendRoomsContent: @Composable () -> Unit,
    /** 进入 FRP 联机房间页。 */
    onOpenFrpRooms: () -> Unit = {},
    /** 进入 FRP 隧道页（MohuaFrp 主入口）。 */
    onOpenFrp: () -> Unit = {},
    /** 直接连接服务器并测试（2026-10-21 补）。 */
    onPingServer: (String, (Result<String>) -> Unit) -> Unit = { _, _ -> },
    /** 列表读取失败时的重试（错误态必须有出口，否则用户只能杀进程）。 */
    onRetry: () -> Unit = {},
) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = state.tab.ordinal) { MultiplayerTab.entries.size }
    Column(modifier = Modifier.fillMaxSize()) {
        QkTabRow(
            labels = listOf(
                stringResource(R.string.multiplayer_tab_servers),
                stringResource(R.string.multiplayer_tab_tunnel),
                stringResource(R.string.qookix_friend_rooms),
                stringResource(R.string.multiplayer_tab_rooms),
            ),
            selectedIndex = state.tab.ordinal,
            onSelect = { onTabChange(MultiplayerTab.entries[it]) },
            pagerState = pager,
        )

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            QkTabContent(
                tabs = MultiplayerTab.entries,
                selected = state.tab,
                onSelect = onTabChange,
                modifier = Modifier.fillMaxSize(),
                pagerState = pager,
            ) { tab ->
                when (tab) {
                    MultiplayerTab.Servers -> ServersTab(
                        state = state,
                        onStartServer = onStartServer,
                        onStopServer = onStopServer,
                        onOpenServer = onOpenServer,
                        onCreateServer = onCreateServer,
                        onPingServer = onPingServer,
                        onRetry = onRetry,
                    )
                    MultiplayerTab.Tunnel -> tunnelContent()
                    MultiplayerTab.FriendRooms -> friendRoomsContent()
                    // FRP 房间是独立页面（内容多），这里只放入口卡片 ——
                    // 与旧版一致：联机类功能都从多人页进，但房间有自己的完整页面。
                    MultiplayerTab.FrpRooms -> FrpRoomsEntryTab(onOpenFrp, onOpenFrpRooms)
                }
            }
        }
    }
}

/**
 * 「联机房间」tab：MohuaFrp 的两个入口。
 *
 * 旧 UI 的多人页在这里放了一张「MohuaFrp」卡片 —— 点进去是 FRP 隧道页，
 * 隧道页里再进联机房间（XTCP 是 MohuaFrp 的另一种模式）。
 * 上一轮我把隧道页整个漏了，这里把两个入口都摆出来。
 */
@Composable
private fun FrpRoomsEntryTab(
    onOpenFrp: () -> Unit,
    onOpenRooms: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = QkSpacing.lg, vertical = QkSpacing.sm),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.multiplayer_frp_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(QkSpacing.xs))
        Text(
            text = stringResource(R.string.multiplayer_frp_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(QkSpacing.lg))
        Button(onClick = onOpenFrp) { Text(stringResource(R.string.multiplayer_frp_open_tunnel)) }
        Spacer(Modifier.height(QkSpacing.sm))
        OutlinedButton(onClick = onOpenRooms) {
            Text(stringResource(R.string.multiplayer_frp_open_rooms))
        }
        Spacer(Modifier.height(QkSpacing.xs))
        Text(
            text = stringResource(R.string.multiplayer_frp_rooms_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ServersTab(
    state: MultiplayerUiState,
    onStartServer: (String) -> Unit,
    onStopServer: (String) -> Unit,
    onOpenServer: (String) -> Unit,
    onCreateServer: () -> Unit,
    onPingServer: (String, (Result<String>) -> Unit) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var showDirectConnect by remember { mutableStateOf(false) }
    val filtered = remember(state.servers, query) {
        if (query.isBlank()) state.servers
        else state.servers.filter { it.name.contains(query, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QkCompactSearchField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                placeholder = stringResource(R.string.multiplayer_search_servers),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showDirectConnect = true }) {
                Text(stringResource(R.string.multiplayer_direct_connect))
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalButton(onClick = onCreateServer) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.multiplayer_new_server))
            }
        }
        if (showDirectConnect) {
            var address by remember { mutableStateOf("") }
            var pingResult by remember { mutableStateOf<String?>(null) }
            var pinging by remember { mutableStateOf(false) }
            val pingTesting = stringResource(R.string.multiplayer_ping_testing)
            val pingFail = stringResource(R.string.multiplayer_ping_fail)
            AlertDialog(
                onDismissRequest = { showDirectConnect = false },
                title = { Text(stringResource(R.string.multiplayer_direct_connect)) },
                text = {
                    Column {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it; pingResult = null },
                            label = { Text(stringResource(R.string.multiplayer_direct_connect_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        pingResult?.let {
                            Spacer(Modifier.height(QkSpacing.xs))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Row {
                        TextButton(
                            onClick = {
                                if (address.isBlank()) return@TextButton
                                pinging = true
                                pingResult = pingTesting
                                onPingServer(address.trim()) { r ->
                                    pinging = false
                                    pingResult = r.fold(
                                        onSuccess = { it },
                                        // 后端给了具体原因（超时/协议不符）就别用通用「无法连接」盖掉
                                        onFailure = { e -> e.message?.takeIf { it.isNotBlank() } ?: pingFail },
                                    )
                                }
                            },
                            enabled = !pinging && address.isNotBlank(),
                        ) { Text(stringResource(R.string.multiplayer_ping)) }
                        TextButton(onClick = { showDirectConnect = false }) {
                            Text(stringResource(R.string.instances_group_cancel))
                        }
                    }
                },
            )
        }

        ServersListPane(
            state = state,
            filtered = filtered,
            query = query,
            onStartServer = onStartServer,
            onStopServer = onStopServer,
            onOpenServer = onOpenServer,
            onRetry = onRetry,
            modifier = Modifier,
        )
    }
}

@Composable
internal fun ServerCard(
    server: ServerUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    // 极窄判定（竖屏 + 大缩放）：等效宽度，算法见 ui/ScreenMetrics.kt
    val veryNarrow = com.zhayi.qookix.compose.ui.scaledScreenWidthDp().dp < 360.dp
    val pressSource = remember { MutableInteractionSource() }
    Card(
        onClick = onOpen,
        interactionSource = pressSource,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(pressSource),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 运行状态指示点（MD3：用色点表示状态，比文字徽标更直观）
            Surface(
                modifier = Modifier.size(10.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = if (server.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ) {}

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(QkSpacing.xxs))
                Text(
                    text = buildString {
                        append(server.core)
                        if (server.mcVersion.isNotBlank()) append(" · ").append(server.mcVersion)
                        if (server.running && server.address.isNotBlank()) {
                            append(" · ").append(server.address)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(12.dp))

            // ⚠️ 极窄（竖屏 + 大缩放）用**纯图标按钮**（2026-10-17 真机实测）。
            //
            // 原来是「图标 + 文字」的 Button。竖屏 384dp + 150% 缩放时，
            // 按钮占掉约一半卡片宽，左边的服务器名只剩 ~90dp →
            // 「TestSrv」被截成「Test…」、「paper · 1.21.8」被截成「paper …」，
            // 用户看不出是哪台服务器、什么版本。图标按钮省出约 40dp，名字就完整了。
            if (veryNarrow) {
                if (server.running) {
                    FilledTonalIconButton(onClick = onStop) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = stringResource(R.string.multiplayer_stop),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = onStart,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.multiplayer_start),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            } else if (server.running) {
                FilledTonalButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.multiplayer_stop))
                }
            } else {
                Button(onClick = onStart) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.multiplayer_start))
                }
            }
        }
    }
}

enum class MultiplayerTab {
    Servers,
    Tunnel,
    FriendRooms,
    FrpRooms,
}

/** 服务器（UI 模型）。 */
data class ServerUi(
    val id: String,
    val name: String,
    val core: String,
    val mcVersion: String,
    val running: Boolean,
    val address: String = "",
)

data class MultiplayerUiState(
    val tab: MultiplayerTab = MultiplayerTab.Servers,
    val servers: List<ServerUi> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)










