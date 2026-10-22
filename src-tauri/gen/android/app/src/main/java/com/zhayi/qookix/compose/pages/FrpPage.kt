package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import com.zhayi.qookix.compose.ui.SkeletonList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R

/**
 * FRP 隧道页（对应旧 UI `FrpView.vue`，568 行）。
 *
 * ── 为什么这一页不能少 ──────────────────────────────────────────────────
 * 这是「MohuaFrp 内网穿透」的**唯一入口**：登录面板、看隧道列表、启停隧道。
 * 建隧道 / 开房间 / 充值在面板网页上做，客户端只负责这三件事。
 *
 * 上一轮对照旧 UI 时**整个漏掉了它** —— 用户点不到 FRP，
 * 也就用不了「把本机端口映射到公网」这条最常用的联机路径
 *（联机房间 XTCP 是另一种模式：双方都要跑 frpc）。
 *
 * ── 与「联机房间」页的区别 ──────────────────────────────────────────────
 * | | FRP 隧道（本页）| XTCP 联机房间 |
 * |---|---|---|
 * | 方式 | TCP 直连 | XTCP 代理 + 访问器 |
 * | 谁跑 frpc | 只有房主 | **双方都要跑** |
 * | 客人连什么 | `节点:远端端口`（原版 MC 直接连）| `127.0.0.1:<bindPort>` |
 */
@Composable
fun FrpPage(
    state: FrpUiState,
    onRefresh: () -> Unit,
    onOpenSite: () -> Unit,
    onProbe: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onToggleTunnel: (FrpTunnelUi) -> Unit,
    onOpenLog: (FrpTunnelUi) -> Unit,
    onOpenRooms: () -> Unit,
    onCopyAddress: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 联机房间入口 ──────────────────────────────────────────────
            //
            // 联机房间**不是**另一个功能，而是 MohuaFrp 的另一种模式
            //（XTCP：房主开房、客人加入，双方都要跑客户端），
            // 所以入口在这一页里，而不是丢到侧栏或「更多」。
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.frp_rooms_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.frp_rooms_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenRooms) {
                            Text(stringResource(R.string.frp_rooms_open))
                        }
                    }
                }
            }

            // ── 面板状态 ──────────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.frp_panel_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Text(
                                text = stringResource(R.string.frp_panel_address),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(64.dp),
                            )
                            // 面板地址**写死在后端**（frp.rs 的 DEFAULT_API_BASE）：
                            // 这个客户端只对接这一家面板，让用户能改只会带来
                            //「地址填错 → 连不上 → 又不知道错在哪」的麻烦。
                            Text(
                                text = state.apiBase.ifBlank { "…" },
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onProbe,
                                enabled = !state.probeBusy,
                            ) {
                                if (state.probeBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    if (state.probeBusy) stringResource(R.string.frp_probe_busy)
                                    else stringResource(R.string.frp_probe),
                                )
                            }
                            OutlinedButton(onClick = onOpenSite) {
                                Text(stringResource(R.string.frp_open_site))
                            }
                        }
                        // 自检结果优先；否则显示 frpc 未就绪的原因
                        val hint = state.probeResult.ifBlank {
                            if (!state.frpcReady) state.frpcDetail else ""
                        }
                        if (hint.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.probeResult.startsWith("自检失败") || !state.frpcReady) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            // ── 登录 / 账号 ───────────────────────────────────────────────
            if (!state.loggedIn) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.frp_login),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.frp_login_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(12.dp))
                            androidx.compose.material3.Button(onClick = onLogin) {
                                Text(stringResource(R.string.frp_login))
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.frp_account_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(8.dp))
                            InfoRow(
                                stringResource(R.string.frp_account_username),
                                state.username.ifBlank { "—" },
                            )
                            if (state.email.isNotBlank()) {
                                InfoRow(stringResource(R.string.frp_account_email), state.email)
                            }
                            if (state.balance.isNotBlank()) {
                                InfoRow(stringResource(R.string.frp_account_balance), state.balance)
                            }
                            if (state.tunnelStats.isNotBlank()) {
                                InfoRow(stringResource(R.string.frp_tunnel), state.tunnelStats)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onRefresh,
                                    enabled = !state.loading,
                                ) { Text(stringResource(R.string.frp_refresh)) }
                                OutlinedButton(onClick = onLogout) {
                                    Text(stringResource(R.string.frp_logout))
                                }
                            }
                        }
                    }
                }
            }

            if (state.loadError.isNotBlank()) {
                item {
                    Text(
                        text = state.loadError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ── 隧道列表 ──────────────────────────────────────────────────
            if (state.loggedIn) {
                item {
                    Text(
                        text = stringResource(R.string.frp_tunnel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (state.loading && state.tunnels.isEmpty()) {
                    item { SkeletonList(rows = 3, rowHeight = 96.dp) }
                } else if (state.tunnels.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.frp_tunnel_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.tunnels, key = { it.id }) { t ->
                        FrpTunnelCard(
                            tunnel = t,
                            busy = state.busyTunnelId == t.id,
                            onToggle = { onToggleTunnel(t) },
                            onOpenLog = { onOpenLog(t) },
                            onCopyAddress = { onCopyAddress(t.runningAddress) },
                        )
                    }
                }
            }
        }
    }
}

/** 一行「键 + 值」。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 一条隧道。
 *
 * 显示：名称 + 状态徽标 + `协议 · 本地端口 → 地址` + 节点名。
 * 运行中额外显示地址 + 复制按钮；已退出显示退出码提示。
 */
@Composable
private fun FrpTunnelCard(
    tunnel: FrpTunnelUi,
    busy: Boolean,
    onToggle: () -> Unit,
    onOpenLog: () -> Unit,
    onCopyAddress: () -> Unit = {},
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
                    text = tunnel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(tunnel.status)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(tunnel.protocol)
                    append(" · ")
                    append(tunnel.localPort)
                    append(" → ")
                    append(tunnel.address.ifBlank { "—" })
                    if (tunnel.nodeName.isNotBlank()) {
                        append(" · ")
                        append(tunnel.nodeName)
                    }
                },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 运行中：把「分享给别人的地址」单独提出来（朋友要填这个）
            if (tunnel.runningAddress.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = tunnel.runningAddress,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = onCopyAddress) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.frp_copy_address))
                    }
                }
            }

            if (tunnel.alive && !tunnel.exitNote.isBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.frp_tunnel_exited, tunnel.exitNote),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggle, enabled = !busy) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        if (tunnel.alive) stringResource(R.string.frp_stop)
                        else stringResource(R.string.frp_start),
                    )
                }
                if (tunnel.hasRunning) {
                    TextButton(onClick = onOpenLog) { Text(stringResource(R.string.frp_view_log)) }
                }
            }
        }
    }
}

/** 隧道状态徽标（active / pending / expired）。 */
@Composable
private fun StatusBadge(status: String) {
    val (bg, fg) = when (status.lowercase()) {
        "active" -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        "pending" -> MaterialTheme.colorScheme.tertiaryContainer to
            MaterialTheme.colorScheme.onTertiaryContainer
        "expired" -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Text(
            text = status.ifBlank { stringResource(R.string.frp_status_unknown) },
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/**
 * 设备码登录弹层。
 *
 * 显示用户码（等宽大字）+ 授权链接；轮询期间显示「等待授权」。
 * 失败（过期/拒绝/超时）如实显示原因，别让用户干等。
 */
@Composable
fun FrpLoginDialog(
    state: FrpLoginUiState,
    onDismiss: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.frp_login_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.frp_login_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (state.userCode.isNotBlank()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = state.userCode,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (state.uri.isNotBlank()) {
                    TextButton(onClick = { onOpenUri(state.uri) }) {
                        Text(
                            text = state.uri,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                when (state.phase) {
                    FrpLoginPhase.Waiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.frp_login_waiting),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FrpLoginPhase.Done -> Text(
                        text = state.message.ifBlank { stringResource(R.string.frp_login_success) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FrpLoginPhase.Failed -> Text(
                        text = state.message.ifBlank { stringResource(R.string.frp_login_failed) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FrpLoginPhase.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.frp_close)) }
        },
    )
}

/** 隧道日志弹层（frpc 的 stdout 尾巴）。 */
@Composable
fun FrpTunnelLogDialog(
    title: String,
    text: String,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.frp_log_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    text = text.ifBlank { stringResource(R.string.frp_log_empty) },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    ),
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.frp_close)) }
        },
    )
}

// ── UI 模型 ──────────────────────────────────────────────────────────────

/** 一条隧道（UI 模型）。 */
data class FrpTunnelUi(
    val id: Int = 0,
    val name: String = "",
    val protocol: String = "",
    val localPort: Int = 0,
    /** 分享给别人的地址（`节点:远端端口`）。 */
    val address: String = "",
    val status: String = "",
    val nodeName: String = "",
    /** 本进程是否正在跑这条隧道的 frpc。 */
    val alive: Boolean = false,
    /** 有 running 记录（哪怕已退出）—— 决定要不要显示「看日志」。 */
    val hasRunning: Boolean = false,
    /** 运行中实际用的地址（可能和列表里的不同）。 */
    val runningAddress: String = "",
    /** 已退出时的退出码/说明。 */
    val exitNote: String = "",
)

/** FRP 页状态。 */
data class FrpUiState(
    val loading: Boolean = false,
    val loggedIn: Boolean = false,
    val username: String = "",
    val email: String = "",
    val balance: String = "",
    val apiBase: String = "",
    val frpcReady: Boolean = true,
    val frpcDetail: String = "",
    val tunnels: List<FrpTunnelUi> = emptyList(),
    /** 从自己拉到的隧道列表算出来的统计（不用面板的 tunnel_stats，它和列表对不上）。 */
    val tunnelStats: String = "",
    val loadError: String = "",
    val probeBusy: Boolean = false,
    val probeResult: String = "",
    /** 正在启停的隧道 id（按钮转圈）。 */
    val busyTunnelId: Int? = null,
)

/** 设备码登录弹层状态。 */
data class FrpLoginUiState(
    val visible: Boolean = false,
    val phase: FrpLoginPhase = FrpLoginPhase.Idle,
    val userCode: String = "",
    val uri: String = "",
    val message: String = "",
)

enum class FrpLoginPhase { Idle, Waiting, Done, Failed }

