package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * FRP 联机房间。
 *
 * 对应旧界面 `FrpRoomsView.vue`（344 行 / 9 个调用点）。
 *
 * ⚠️ **我第一遍做页面覆盖对照时漏了这个页面。**
 * 原因是搜「FrpRooms」没命中（Compose 侧还没有对应文件），
 * 而它又是 14 个 view 里唯一名字比较特别的。是逐页列清单才发现的。
 * 教训：**页面覆盖不能靠"搜一搜看有没有"，要逐条列出来对**。
 *
 * ── 与「陶瓦联机」的区别 ────────────────────────────────────────────────
 * | | 陶瓦（Terracotta） | FRP 房间 |
 * |---|---|---|
 * | 原理 | 独立进程 + localhost HTTP | frpc 客户端把端口暴露到公网节点 |
 * | 网络要求 | 需要 TUN（VPN 授权） | 走公网节点中转，不需要 VPN |
 * | 适用 | 朋友之间直连 | 有公网节点时更稳 |
 *
 * 两种并存，用户按情况选。
 *
 * ── 界面 ────────────────────────────────────────────────────────────────
 * **房主**与**访客**的操作不同，所以按钮文案按 `isHost` 切换：
 *   房主：「开启」（起 frpc 暴露端口）/「关闭」
 *   访客：「加入」（把房主代理绑到本地）/「离开」
 *
 * 访客加入后会拿到一个 `connect` 地址（如 `127.0.0.1:25565`），
 * 要**显示出来**让用户去游戏里连 —— 这是这个页面最关键的一步，
 * 只显示"已加入"而不给地址等于没用。
 */
@Composable
fun FrpRoomsPage(
    state: FrpRoomsUiState,
    onRefresh: () -> Unit,
    onCreate: (name: String, nodeId: Int, localPort: Int, password: String?) -> Unit,
    onEnter: (FrpRoomUi) -> Unit,
    onExit: (FrpRoomUi) -> Unit,
    onOpenConnect: (String) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶部说明 + 操作 ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.frp_rooms_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = stringResource(R.string.frp_rooms_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onRefresh, enabled = !state.busy) {
                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.frp_rooms_refresh))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { showCreate = true }, enabled = !state.busy) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.frp_rooms_create))
            }
        }

        // ── 已加入时显示的连接地址（最关键的信息）──────────────────────
        state.connectAddress?.let { addr ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.frp_rooms_connect_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        // 等宽 + 大字：用户要照着敲
                        Text(
                            text = addr,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    TextButton(onClick = { onOpenConnect(addr) }) {
                        Text(stringResource(R.string.frp_rooms_copy))
                    }
                }
            }
        }

        // ── 列表 ──────────────────────────────────────────────────────
        when {
            state.busy && state.rooms.isEmpty() -> Column(modifier = Modifier.padding(20.dp)) {
                SkeletonList(rows = 2, rowHeight = 96.dp)
            }

            state.rooms.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.frp_rooms_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.frp_rooms_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.rooms, key = { it.id }) { r ->
                    FrpRoomCard(
                        room = r,
                        busy = state.busy,
                        note = state.notes[r.id],
                        onEnter = { onEnter(r) },
                        onExit = { onExit(r) },
                    )
                }
            }
        }

        state.error?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (showCreate) {
        FrpCreateRoomDialog(
            nodes = state.nodes,
            creating = state.busy,
            error = state.error,
            onDismiss = { showCreate = false },
            onCreate = { n, node, port, pwd ->
                onCreate(n, node, port, pwd)
                showCreate = false
            },
        )
    }
}

@Composable
private fun FrpRoomCard(
    room: FrpRoomUi,
    busy: Boolean,
    note: String?,
    onEnter: () -> Unit,
    onExit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = room.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (room.hasPassword) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.frp_rooms_has_password),
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                RoomStatusBadge(room.status)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    if (room.gameName.isNotBlank()) append(room.gameName).append(" · ")
                    if (room.hostName.isNotBlank()) append(room.hostName).append(" · ")
                    append("${room.playerCount}/${room.maxPlayers}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 审核中的房间要说明原因 —— 否则用户以为「建了但用不了」是 bug
            if (room.reviewStatus == "pending") {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (room.reviewReason.isNotBlank()) {
                        stringResource(R.string.frp_rooms_reviewing_reason, room.reviewReason)
                    } else {
                        stringResource(R.string.frp_rooms_reviewing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            if (note != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEnter, enabled = !busy) {
                    Text(
                        if (room.isHost) stringResource(R.string.frp_rooms_start)
                        else stringResource(R.string.frp_rooms_join),
                    )
                }
                TextButton(onClick = onExit, enabled = !busy) {
                    Text(
                        text = if (room.isHost) stringResource(R.string.frp_rooms_stop)
                        else stringResource(R.string.frp_rooms_leave),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomStatusBadge(status: String) {
    // 状态色：在线用主题色，离线/关闭用灰，其余用中性
    val (label, color) = when (status.lowercase()) {
        "online", "running", "active" -> stringResource(R.string.frp_rooms_status_online) to
            MaterialTheme.colorScheme.primary
        "offline", "closed", "stopped" -> stringResource(R.string.frp_rooms_status_closed) to
            MaterialTheme.colorScheme.outline
        "pending", "reviewing" -> stringResource(R.string.frp_rooms_reviewing) to
            MaterialTheme.colorScheme.tertiary
        else -> status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun FrpCreateRoomDialog(
    nodes: List<FrpNodeUi>,
    creating: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onCreate: (name: String, nodeId: Int, localPort: Int, password: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var nodeId by remember { mutableStateOf(nodes.firstOrNull()?.id ?: -1) }
    // 默认 25565（Minecraft 默认端口）—— 大多数用户就是开这个
    var port by remember { mutableStateOf("25565") }
    var password by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text(stringResource(R.string.frp_rooms_create)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.frp_rooms_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() } },
                    label = { Text(stringResource(R.string.frp_rooms_local_port)) },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.frp_rooms_port_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.frp_rooms_password)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = stringResource(R.string.frp_rooms_nodes),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                if (nodes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.frp_rooms_no_nodes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    // 节点可能多个，横向排列可滑
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScrollable(),
                    ) {
                        nodes.forEach { n ->
                            FilterChip(
                                selected = nodeId == n.id,
                                onClick = { nodeId = n.id },
                                label = { Text(n.label.ifBlank { n.id.toString() }) },
                            )
                        }
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(name.trim(), nodeId, port.toIntOrNull() ?: 25565, password.ifBlank { null })
                },
                enabled = name.trim().isNotEmpty() &&
        nodeId > 0 &&
                    (port.toIntOrNull() ?: 0) in 1..65535 &&
                    !creating,
            ) {
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.frp_rooms_confirm_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) {
                Text(stringResource(R.string.frp_rooms_cancel))
            }
        },
    )
}

/** 横向滚动包装（rememberScrollState 是 @Composable，不能放进普通扩展函数）。 */
@Composable
private fun Modifier.horizontalScrollable(): Modifier =
    this.horizontalScroll(rememberScrollState())

/** FRP 房间（UI 模型）。 */
data class FrpRoomUi(
    val id: Int,
    val name: String,
    val status: String = "",
    val gameName: String = "",
    val hostName: String = "",
    val playerCount: Int = 0,
    val maxPlayers: Int = 0,
    val hasPassword: Boolean = false,
    /** 是否是自己建的房间（决定按钮文案）。 */
    val isHost: Boolean = false,
    val reviewStatus: String = "",
    val reviewReason: String = "",
)

/** FRP 节点。 */
data class FrpNodeUi(
    /**
     * 节点 id。**必须是 Int** —— Rust 的 `RoomNode.id` 是 i64（`frp.rs:982-983`），
     * 而 `frp_room_create` 签名是 `node_id: i64`。
     *
     * 这里曾是 String：读取时 `optString("id")` 把 i64 变成 "3"，
     * 建房时发出去就是 JSON 字符串，Tauri 报
     * `invalid type: string "3", expected i64` → **建房 100% 失败**。
     */
    val id: Int,
    val label: String = "",
)

/** FRP 房间页状态。 */
data class FrpRoomsUiState(
    val rooms: List<FrpRoomUi> = emptyList(),
    val nodes: List<FrpNodeUi> = emptyList(),
    /** 每个房间的备注（房间 id → 用户写的备注）。房间 id 是 Int（见 [FrpRoomUi.id]）。 */
    val notes: Map<Int, String> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    /** 进房后拿到的连接地址（显示给用户复制给朋友）。 */
    val connectAddress: String? = null,
)

