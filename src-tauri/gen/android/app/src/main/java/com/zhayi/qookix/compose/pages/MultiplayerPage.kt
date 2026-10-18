package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.zhayi.qookix.compose.motion.pressScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

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
    onToggleTunnel: () -> Unit,
    /** 加入别人的房间（房间码）。旧 UI 有、先前漏做的功能。 */
    onJoinRoom: (String) -> Unit = {},
    /** 复制房间码到剪贴板。 */
    onCopyRoom: () -> Unit = {},
    /** 陶瓦操作进行中（禁用按钮 + 显示转圈）。 */
    tunnelBusy: Boolean = false,
    /** 进入 FRP 联机房间页。 */
    onOpenFrpRooms: () -> Unit = {},
    /** 进入 FRP 隧道页（MohuaFrp 主入口）。 */
    onOpenFrp: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = state.tab.ordinal) {
            Tab(
                selected = state.tab == MultiplayerTab.Servers,
                onClick = { onTabChange(MultiplayerTab.Servers) },
                text = { Text(stringResource(R.string.multiplayer_tab_servers)) },
            )
            Tab(
                selected = state.tab == MultiplayerTab.Tunnel,
                onClick = { onTabChange(MultiplayerTab.Tunnel) },
                // 这里原本叫「联机房间」，但实际内容是**陶瓦联机** ——
                // 而真正的「联机房间」（FRP / XTCP）是另一个页面。
                // 两个名字撞车会让用户以为是一回事，所以改名。
                text = { Text(stringResource(R.string.multiplayer_tab_tunnel)) },
            )
            Tab(
                selected = state.tab == MultiplayerTab.FrpRooms,
                onClick = { onTabChange(MultiplayerTab.FrpRooms) },
                text = { Text(stringResource(R.string.multiplayer_tab_rooms)) },
            )
        }

        when (state.tab) {
            MultiplayerTab.Servers -> ServersTab(
                state = state,
                onStartServer = onStartServer,
                onStopServer = onStopServer,
                onOpenServer = onOpenServer,
                onCreateServer = onCreateServer,
            )
            MultiplayerTab.Tunnel -> TunnelTab(
                state = state,
                onToggle = onToggleTunnel,
                onJoin = onJoinRoom,
                onCopyRoom = onCopyRoom,
                busy = tunnelBusy,
            )
            // FRP 房间是独立页面（内容多），这里只放入口卡片 ——
            // 与旧版一致：联机类功能都从多人页进，但房间有自己的完整页面。
            MultiplayerTab.FrpRooms -> FrpRoomsEntryTab(onOpenFrp, onOpenFrpRooms)
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
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.multiplayer_frp_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.multiplayer_frp_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpenFrp) { Text(stringResource(R.string.multiplayer_frp_open_tunnel)) }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenRooms) {
            Text(stringResource(R.string.multiplayer_frp_open_rooms))
        }
        Spacer(Modifier.height(8.dp))
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
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            FilledTonalButton(onClick = onCreateServer) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.multiplayer_new_server))
            }
        }

        when {
            state.loading -> SkeletonList(rows = 3, rowHeight = 96.dp)
            state.servers.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.multiplayer_empty_servers_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.multiplayer_empty_servers_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.servers, key = { it.id }) { s ->
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
}

@Composable
private fun ServerCard(
    server: ServerUi,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    // 极窄判定（竖屏 + 大缩放）：用等效宽度，与 SettingsPage 同一套算法
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val densityNow = androidx.compose.ui.platform.LocalDensity.current
    val baseline = cfg.densityDpi / 160f
    val scaleF = if (baseline > 0f) densityNow.density / baseline else 1f
    val veryNarrow = (cfg.screenWidthDp.dp / scaleF) < 360.dp
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
            modifier = Modifier.padding(16.dp),
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
                Spacer(Modifier.height(2.dp))
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

/**
 * 陶瓦联机 tab。
 *
 * ── 这块补了什么（2026-10-13）────────────────────────────────────────────
 * 用户反馈「多人缺很多功能」，对照旧 `MultiplayerView.vue` 发现两个硬缺失：
 *
 *   1. **加入别人的房间**（旧 `joinRoom`）—— 调 `/guest?room=<码>&player=<名>`。
 *      我原来只有「开启联机」，等于只能当房主、不能当客人 ——
 *      而「进朋友的房间」恰恰是这个功能最常见的用法。
 *
 *   2. **复制房间码**（旧 `copyRoom`）—— 房间码是一串要发给朋友的字符，
 *      不能复制的话用户体验很差（要手抄）。
 *
 * 另外按旧 UI 的信息层级重排：**开房** 和 **加入** 是两个独立区域，
 * 各自有明确的输入与按钮，而不是混在一起。
 */
@Composable
private fun TunnelTab(
    state: MultiplayerUiState,
    onToggle: () -> Unit,
    onJoin: (String) -> Unit,
    onCopyRoom: () -> Unit,
    busy: Boolean,
) {
    // 加入房间的输入码（受控状态放这里 —— 它只服务这个 tab）
    var roomInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 开房 ────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.multiplayer_host_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.multiplayer_host_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 房间码：出现了才显示（并给复制按钮）
                if (state.tunnelRunning && state.roomCode.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.multiplayer_room_code_label),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = state.roomCode,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            // 复制 —— 房间码本来就是要发给别人的，
                            // 不能复制就得手抄，体验差
                            IconButton(onClick = onCopyRoom) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = stringResource(R.string.multiplayer_copy_room_code),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onToggle,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            busy -> stringResource(R.string.multiplayer_busy)
                            state.tunnelRunning -> stringResource(R.string.multiplayer_tunnel_stop)
                            else -> stringResource(R.string.multiplayer_tunnel_start)
                        },
                    )
                }
            }
        }

        // ── 加入别人的房间 ──────────────────────────────────────────────
        //
        // 这是旧 UI 有、我先前完全漏掉的功能。
        // 对多数用户来说「进朋友的房间」比「自己开房」更常用。
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.multiplayer_guest_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.multiplayer_guest_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = roomInput,
                    onValueChange = { roomInput = it.take(12) },
                    label = { Text(stringResource(R.string.multiplayer_room_code)) },
                    placeholder = { Text(stringResource(R.string.multiplayer_room_code_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    // 房间码是等宽短串，用等宽字体更易核对（避免 0/O 看错）
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (roomInput.isNotBlank()) onJoin(roomInput.trim())
                    },
                    // 码为空就不给点 —— 免得发一个无意义的请求
                    enabled = roomInput.isNotBlank() && !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    ),
                ) {
                    Text(stringResource(R.string.multiplayer_join_room))
                }
            }
        }
    }
}

enum class MultiplayerTab {
    Servers,
    Tunnel,
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
    val tunnelRunning: Boolean = false,
    val roomCode: String = "",
    val error: String? = null,
)










