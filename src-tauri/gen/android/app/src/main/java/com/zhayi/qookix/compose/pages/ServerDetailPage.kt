package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.zhayi.qookix.compose.ui.QkTabRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 服务器详情。
 *
 * 对应旧界面 views/ServerDetailView.vue —— **1383 行 / 15 个 api 调用**，
 * 是除设置外最大的一块。
 *
 * ── 设计（完全重做）─────────────────────────────────────────────────────
 * 旧界面是「顶部状态条 + 长滚动」。横屏下改成 **三 tab** 结构：
 *
 *   ┌──────────────────────────────────────────────────┐
 *   │ ← 我的服务器        [●运行中]  [停止]            │  ← 顶栏
 *   ├──────────────────────────────────────────────────┤
 *   │  控制台  │  文件  │  配置                         │  ← TabRow
 *   ├──────────────────────────────────────────────────┤
 *   │  [12:01:03] Done (1.2s)! For help, type "help"   │
 *   │  [12:01:10] Player Steve joined the game         │  ← 控制台
 *   │  ┌──────────────────────────────┐ [发送]          │
 *   │  │ 输入命令…                    │                 │
 *   └──────────────────────────────────────────────────┘
 *
 * **为什么分 tab**：服务器管理有三类互不相关的操作（看日志/发命令、
 * 管理文件、改配置），堆在一页要滚很久；tab 让每类都有自己的完整空间。
 *
 * **控制台优先**：开服后最高频的操作是看日志和发命令（`list`/`op`），
 * 所以它是第一个 tab、且占满高度。
 */
@Composable
fun ServerDetailPage(
    state: ServerDetailUiState,
    onTabChange: (ServerTab) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCommandInput: (String) -> Unit,
    onCommandSend: () -> Unit,
    onInstallCore: () -> Unit,
    onOpenDirectory: () -> Unit,
    onOpenFiles: () -> Unit,
    /**
     * 改一条 server.properties 属性。
     *
     * 键名用后端白名单里的 **camelCase**（`viewDistance` / `onlineMode` …）。
     * 后端整批校验并做范围钳制，所以界面以**返回值**为准刷新。
     */
    onSetProperty: (key: String, value: String) -> Unit = { _, _ -> },
    onToggleProperty: (key: String, value: Boolean) -> Unit = { _, _ -> },
    // ── 分享给朋友（缺陷 1）─────────────────────────────────────────────
    /** 打开 / 关闭对外分享（对应旧 Vue 的 `toggleShare`）。 */
    /** 勾选 / 取消同意 Minecraft EULA（写进 servers/<id>.json 的 eula 字段）。 */
    onSetEula: (Boolean) -> Unit = {},
    onToggleShare: (Boolean) -> Unit = {},
    /** 复制房间码。 */
    onCopyRoom: (String) -> Unit = {},
    // ── 插件（缺陷 2）───────────────────────────────────────────────────
    /** 删除插件（已确认后）。 */
    onDeletePlugin: (InstalledPlugin) -> Unit = {},
    /** 请求删除插件（弹确认框）。 */
    onAskDeletePlugin: (InstalledPlugin?) -> Unit = {},
    /** 用文件管理器打开 plugins 子目录。 */
    onOpenPluginFolder: () -> Unit = {},
    // ── 日志 / 内存 / 删除（缺陷 3）─────────────────────────────────────
    /** 手动刷新日志。 */
    onRefreshLogs: () -> Unit = {},
    /** 改服务器内存。 */
    onSetMemory: (Int, Int) -> Unit = { _, _ -> },
    /** 删除整台服务器（危险操作，需二次确认）。 */
    onDeleteServer: () -> Unit = {},
    /** 清除提示。 */
    onDismissNotice: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 顶栏已上移（2026-10-17）────────────────────────────────────
        //
        // 和外层 TopAppBar 重复显示返回箭头 + 标题，现在搬进 title 槽位
        // （见 PageHost 注册的 ServerDetailToolbar）。

        // 和设置页 / 实例详情用同一套 tab 容器（2026-10-17 统一）
        // 统一 tab 组件（2026-10-17）：4 个 tab 在横屏会自动等分填满，
        // 不再像原来那样右边空一片。
        QkTabRow(
            labels = ServerTab.entries.map { stringResource(it.labelRes) },
            selectedIndex = state.tab.ordinal,
            onSelect = { onTabChange(ServerTab.entries[it]) },
        )

        // ── 操作失败提示（启动/停止等）────────────────────────────────
        //
        // ⚠️ 2026-10-16 补：`state.error` 原来只在「配置」页当作空态文案用，
        // 其他页面上**完全看不到**。真机实测：服务器没装核心时点「启动」，
        // 后端返回「还没下载服务端核心」，界面却毫无反应 —— 用户会以为按钮坏了。
        //
        // 放在 TabRow 下面、所有 tab 之上，保证任何页签都能看到。
        if (!state.error.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
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
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── 上次运行的结束原因 ────────────────────────────────────────
        //
        // ⚠️ 2026-10-19 补：服务端「关服后再也起不来」时，唯一能说明原因的就是
        // Rust 写进 `runtime.json.exit` / `launch-error.txt` 的那句话。
        // 以前界面完全不显示它 —— 用户只能看到「点了没反应」，
        // 而唯一的日志页也没有入口，等于断了所有排查手段。
        if (!state.exitNote.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.exitNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 操作成功提示 ──────────────────────────────────────────────
        if (!state.notice.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.server_detail_notice_ok)) }
                }
            }
        }

        when (state.tab) {
            ServerTab.Console -> ConsoleTab(
                state = state,
                onInputChange = onCommandInput,
                onSend = onCommandSend,
                onToggleShare = onToggleShare,
                onCopyRoom = onCopyRoom,
                onRefreshLogs = onRefreshLogs,
            )
            ServerTab.Files -> FilesTab(
                state = state,
                onOpenDirectory = onOpenDirectory,
                onOpenFiles = onOpenFiles,
                onRefreshLogs = onRefreshLogs,
                onSetMemory = onSetMemory,
                onDeleteServer = onDeleteServer,
            )
            ServerTab.Plugins -> PluginsTab(
                state = state,
                onOpenPluginFolder = onOpenPluginFolder,
                onAskDeletePlugin = onAskDeletePlugin,
            )
            ServerTab.Config -> ConfigTab(state, onSetProperty, onToggleProperty)
            // 服务器设置：EULA 同意 + 对外分享（陶瓦联机）
            ServerTab.Settings -> SettingsTab(
                state = state,
                onToggleShare = onToggleShare,
                onCopyRoom = onCopyRoom,
                onSetEula = onSetEula,
            )
        }
    }

    // ── 删除插件的确认框 ───────────────────────────────────────────────
    //
    // 删除是不可撤销的，必须二次确认（旧 Vue 用 app-sheet 做确认）。
    if (state.confirmDeletePlugin != null) {
        AlertDialog(
            onDismissRequest = { onAskDeletePlugin(null) },
            title = { Text(stringResource(R.string.server_detail_delete_plugin_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.server_detail_delete_plugin_message,
                        state.confirmDeletePlugin.fileName,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onDeletePlugin(state.confirmDeletePlugin) }) {
                    Text(stringResource(R.string.server_detail_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { onAskDeletePlugin(null) }) { Text(stringResource(R.string.server_detail_cancel)) }
            },
        )
    }
}

@Composable
private fun ConsoleTab(
    state: ServerDetailUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleShare: (Boolean) -> Unit,
    onCopyRoom: (String) -> Unit,
    onRefreshLogs: () -> Unit,
) {
    val listState = rememberLazyListState()
    // ⚠️ 2026-10-21 真机修：用户反馈「开服务器连着 WiFi 的话，有个连接地址，
    // 更新后找不到连接地址在哪」。原来地址只在 toolbar 的小字里、且仅运行时显示，
    // 字小又不起眼。现在在控制台顶部放一张显眼的地址卡片，无论运行与否都显示，
    // 并带复制按钮 —— 朋友要连的就是这个地址。
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var copied by remember { mutableStateOf(false) }

    // 新日志到达时自动滚到底（控制台的基本要求）
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 连接地址卡片 ──────────────────────────────────────────────
        if (state.address.isNotBlank()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_detail_connect_address),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = state.address,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.address))
                        copied = true
                    }) {
                        Text(
                            if (copied) stringResource(R.string.server_detail_address_copied)
                            else stringResource(R.string.server_detail_copy_address),
                        )
                    }
                }
            }
        }

        // 陶瓦联机已移到「服务器设置」tab（2026-10-18）：
        // 用户要求「对外开放陶瓦联机的这四个给它放到服务器设置里，而不是这儿」。

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            if (state.logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.running) stringResource(R.string.server_detail_waiting_output)
                        else stringResource(R.string.server_detail_not_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.logs) { line ->
                        Text(
                            text = line,
                            // 等宽字体：日志里的对齐与数字可读性依赖它
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 命令输入行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.commandInput,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.server_detail_command_placeholder)) },
                singleLine = true,
                enabled = state.running,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                // 键盘上的「发送」与右侧按钮走同一条路
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            Spacer(Modifier.width(8.dp))
            // 手动刷新日志：轮询是 2 秒一次，但用户刚发了命令时想立刻看到回显
            IconButton(onClick = onRefreshLogs) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.server_detail_refresh_logs_cd))
            }
            IconButton(
                onClick = onSend,
                enabled = state.running && state.commandInput.isNotBlank(),
            ) {
                Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.server_detail_send_cd))
            }
        }
    }
}

/**
 * 「分享给朋友」卡片（缺陷 1）。
 *
 * 对应旧 Vue `ServerDetailView.vue:810-827` 的那块 UI + `:548-609` 的逻辑。
 *
 * 为什么放在**控制台**这一页而不是别处：开完服第一件事就是把它分享出去，
 * 而控制台是打开详情页的默认页签 —— 放在这里用户不用切页就能看到。
 */
/**
 * 「服务器设置」tab（2026-10-18 新增）。
 *
 * ⚠️ 为什么加这个 tab：
 *   Rust 的 `start_hosted_server` 在 EULA 未同意时会拒绝启动，并提示
 *   「请先在『服务器设置』里勾选同意 Minecraft EULA」—— 但界面里**根本没有**
 *   这个 tab（只有控制台/文件/插件/配置文件），用户被指到一个不存在的地方。
 *
 * 同时把原来挤在「控制台」顶部的「对外开放（陶瓦联机）」卡片移进来 ——
 * 用户要求「这四个给它放到服务器设置里，而不是这儿」。
 * 控制台应该只放日志和命令输入。
 */
@Composable
private fun SettingsTab(
    state: ServerDetailUiState,
    onToggleShare: (Boolean) -> Unit,
    onCopyRoom: (String) -> Unit,
    onSetEula: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── EULA 同意 ────────────────────────────────────────────────
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = if (state.eulaAgreed) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    // 未同意时用警示色：这是启动的前置阻塞项
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                },
            ),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // ⚠️ 2026-10-20 真机反馈修：整行都要能点。
                //
                // 原来只有最右侧那个 `Switch` 可点，卡片主体（标题和说明文字）
                // 完全不可交互 —— 用户反馈「EULA 那玩意无法打开」，
                // 因为绝大多数人第一反应是点那行字，而不是去精确命中开关。
                // 现在整行 `toggleable`，点哪儿都能切换（无障碍语义也对齐）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = state.eulaAgreed,
                            onValueChange = onSetEula,
                            role = Role.Switch,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.server_settings_eula_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (state.eulaAgreed) {
                                stringResource(R.string.server_settings_eula_agreed)
                            } else {
                                stringResource(R.string.server_settings_eula_pending)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = state.eulaAgreed,
                        // 开关自身不再持有回调：点击已被整行吞掉，避免重复触发
                        onCheckedChange = null,
                    )
                }
            }
        }

        // ── 对外开放（陶瓦联机）──────────────────────────────────────
        // 从「控制台」移过来的，逻辑完全不变。
        ShareCard(state, onToggleShare, onCopyRoom)
    }
}


@Composable
private fun ShareCard(
    state: ServerDetailUiState,
    onToggleShare: (Boolean) -> Unit,
    onCopyRoom: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (state.shareRoom.isNotBlank()) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.server_detail_share_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    // 文案优先级与旧 Vue 一致：房间码 > 错误 > 等待 > 说明
                    Text(
                        text = when {
                            state.shareRoom.isNotBlank() ->
                                stringResource(R.string.server_detail_share_room_code, state.shareRoom)
                            state.shareMessage.isNotBlank() -> state.shareMessage
                            state.shareOn -> stringResource(R.string.server_detail_share_waiting)
                            else -> stringResource(R.string.server_detail_share_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = state.shareOn,
                    onCheckedChange = onToggleShare,
                    // 服务器没跑就别开房（旧 Vue 会警告「需要先开服」）——
                    // 没有进程在监听时，广播出去的端口谁也连不上
                    enabled = !state.shareBusy && (state.running || state.shareOn),
                )
            }

            if (state.shareBusy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.server_detail_share_busy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (state.shareRoom.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { onCopyRoom(state.shareRoom) }) {
                    Text(stringResource(R.string.server_detail_copy_room))
                }
            }
        }
    }
}

@Composable
private fun FilesTab(
    state: ServerDetailUiState,
    onOpenDirectory: () -> Unit,
    onOpenFiles: () -> Unit,
    onRefreshLogs: () -> Unit,
    onSetMemory: (Int, Int) -> Unit,
    onDeleteServer: () -> Unit,
) {
    // 删除服务器的二次确认（不可撤销，绝不能一点就删）
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            // 内置文件浏览器：改配置、看日志、传插件都在这里
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.server_detail_browse_files_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.server_detail_browse_files_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenFiles) {
                        Text(stringResource(R.string.server_detail_open_file_browser))
                    }
                }
            }
        }

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
                        text = stringResource(R.string.server_detail_system_file_manager_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.server_detail_system_file_manager_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onOpenDirectory) {
                        Text(stringResource(R.string.server_detail_open_with_file_manager))
                    }
                }
            }
        }

        // ── 服务器日志（缺陷 3）────────────────────────────────────────
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
                        text = stringResource(R.string.server_detail_server_logs_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (state.logs.isEmpty()) {
                            stringResource(R.string.server_detail_logs_empty)
                        } else {
                            stringResource(
                                R.string.server_detail_logs_recent,
                                state.logs.size,
                                state.logs.last().take(80),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onRefreshLogs) {
                        Text(stringResource(R.string.server_detail_refresh_logs))
                    }
                }
            }
        }

        // ── 内存（缺陷 3）──────────────────────────────────────────────
        item {
            MemoryCard(state, onSetMemory)
        }

        // ── 删除服务器（缺陷 3）────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.server_detail_delete_server_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.server_detail_delete_server_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { confirmDelete = true },
                        // 服务器还在跑就别删：文件被 JVM 占着，
                        // 删到一半会留下半截目录（后端也会拒绝）
                        enabled = !state.running,
                    ) {
                        Text(stringResource(R.string.server_detail_delete_this_server))
                    }
                }
            }
        }

        if (state.running) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.server_detail_running_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.server_detail_delete_server_title)) },
            text = {
                Text(stringResource(R.string.server_detail_delete_server_message, state.name))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeleteServer()
                }) {
                    Text(stringResource(R.string.server_detail_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.server_detail_cancel)) }
            },
        )
    }
}

/**
 * 内存调整卡片（缺陷 3）。
 *
 * 为什么用 ± 按钮而不是滑条 / 输入框：
 *   · 后端 `update_hosted_server` 会把值 clamp 到 256–8192（max）和 128–8192（min），
 *     输入框里填了超范围的值只会被静默改掉，用户看着数字和实际不一致；
 *   · 手机屏幕上 ± 每次 256MB 比拖滑条精确得多，也不会误触。
 *
 * ⚠️ 「最小内存 > 最大内存」是 JVM 直接拒绝启动的经典错误
 * （新建服务器的默认值就正好是反的：min 1024 / max 512），
 * 所以调大值时同步抬高 min、调小 min 时同步压低 max（见 Repo.setMemory）。
 */
@Composable
private fun MemoryCard(
    state: ServerDetailUiState,
    onSetMemory: (Int, Int) -> Unit,
) {
    val step = 256
    val hardMin = 256
    val hardMax = 8192

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.server_detail_memory_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.server_detail_memory_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.server_detail_memory_max, state.maxMemoryMb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSetMemory(state.maxMemoryMb - step, state.minMemoryMb) },
                    enabled = state.maxMemoryMb - step >= hardMin,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("−") }
                TextButton(
                    onClick = { onSetMemory(state.maxMemoryMb + step, state.minMemoryMb) },
                    enabled = state.maxMemoryMb + step <= hardMax,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("+") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.server_detail_memory_min, state.minMemoryMb),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSetMemory(state.maxMemoryMb, state.minMemoryMb - step) },
                    enabled = state.minMemoryMb - step >= 128,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("−") }
                TextButton(
                    onClick = { onSetMemory(state.maxMemoryMb, state.minMemoryMb + step) },
                    // 不允许超过最大内存 —— 后端会钳，但让用户根本点不到更好
                    enabled = state.minMemoryMb + step <= state.maxMemoryMb,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text("+") }
            }
        }
    }
}

/**
 * 「插件」tab（缺陷 2）。
 *
 * 对应的三个 API 一直存在但**零调用**：
 *   · `listServerPlugins`     → 列出 `plugins/` 下的 jar
 *   · `deleteServerPlugin`    → 删一个
 *   · `openHostedServerFolder(id, "plugins")` → 拿到目录路径
 *
 * 为什么不做「搜索 + 安装」（旧 Vue 的 Hangar 面板）：
 *   那是另一个完整流程（Hangar 搜索 → 挑版本 → 下载），
 *   归「浏览」页的内容中心更合适；这里先把**已装什么、能不能删**做出来，
 *   因为那才是用户点进「插件」tab 时最想确认的事。
 */
@Composable
private fun PluginsTab(
    state: ServerDetailUiState,
    onOpenPluginFolder: () -> Unit,
    onAskDeletePlugin: (InstalledPlugin?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.server_detail_plugins_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.server_detail_plugins_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenPluginFolder) { Text(stringResource(R.string.server_detail_plugins_folder)) }
        }

        if (state.loadingPlugins) {
            SkeletonList(rows = 3, rowHeight = 56.dp)
            return@Column
        }

        if (state.plugins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.server_detail_plugins_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.plugins, key = { it.fileName }) { p ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = p.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = ServerDetailRepo.fmtSize(p.sizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        TextButton(
                            onClick = { onAskDeletePlugin(p) },
                            enabled = state.deletingPlugin == null,
                        ) {
                            Text(
                                text = if (state.deletingPlugin == p.fileName)
                                    stringResource(R.string.server_detail_plugin_deleting)
                                else stringResource(R.string.server_detail_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigTab(
    state: ServerDetailUiState,
    /** 改一个 Bool / Choice / Int 属性（键名用后端期望的 camelCase）。 */
    onSetProperty: (key: String, value: String) -> Unit = { _, _ -> },
    onToggleProperty: (key: String, value: Boolean) -> Unit = { _, _ -> },
) {
    if (state.loadingConfig) {
        Column(modifier = Modifier.padding(20.dp)) {
            SkeletonList(rows = 6, rowHeight = 56.dp)
        }
        return
    }

    if (state.properties.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = state.error ?: stringResource(R.string.server_detail_config_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.properties, key = { it.key }) { p ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                // ── 窄屏纵向堆叠，宽屏左右并排（2026-10-16 修）────────────
                //
                // 原来是 `Row { Column(weight(1f)){标签} ; Spacer ; 控件 }`。
                // 竖屏（411dp）下 Choice 型有 4 个 chip，控件本身就占满宽度，
                // 标签列被压到只剩几十 dp，于是 `difficulty` / `gameMode`
                // **逐字竖排**（同一天第 6 次遇到这类问题）。
                //
                // 窄屏改成上下两行：标签在上、控件在下，两边都有完整宽度。
                val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
                val stack = screenW < 520.dp

                if (stack) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        PropertyLabel(p)
                        Spacer(Modifier.height(10.dp))
                        PropertyValue(p, onSetProperty, onToggleProperty)
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            PropertyLabel(p)
                        }
                        Spacer(Modifier.width(12.dp))
                        PropertyValue(p, onSetProperty, onToggleProperty)
                    }
                }
            }
        }
    }
}

/** 配置项的名称 + 说明。 */
@Composable
private fun PropertyLabel(p: PropertyUi) {
    Text(
        text = p.key,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (p.description.isNotBlank()) {
        Text(
            text = p.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 配置项的编辑控件（按类型分派）。 */
@Composable
private fun PropertyValue(
    p: PropertyUi,
    onSetProperty: (String, String) -> Unit,
    onToggleProperty: (String, Boolean) -> Unit,
) {
    when (p.kind) {
        PropKind.Bool -> Switch(
            checked = p.value.equals("true", ignoreCase = true),
            onCheckedChange = { onToggleProperty(p.key, it) },
        )
        PropKind.Choice -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // 选项多时（如 gameMode 4 个）窄屏会溢出，允许横向滚动
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            p.choices.forEach { c ->
                FilterChip(
                    selected = p.value.equals(c, ignoreCase = true),
                    onClick = { onSetProperty(p.key, c) },
                    label = { Text(c) },
                )
            }
        }
        PropKind.Int -> Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PropertyIntStepper(p, onSetProperty)
        }
        PropKind.ReadOnly -> Text(
            text = p.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PropertyIntStepper(p: PropertyUi, onSetProperty: (String, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = p.value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        // 用 ± 按钮而不是输入框：值域窄（3–32 / 1–100），
        // 点两下比调键盘快，也不会输入非法值。
        TextButton(
            onClick = {
                val cur = p.value.toIntOrNull() ?: p.min
                val next = (cur - 1).coerceAtLeast(p.min)
                onSetProperty(p.key, next.toString())
            },
            enabled = (p.value.toIntOrNull() ?: p.min) > p.min,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("−") }
        TextButton(
            onClick = {
                val cur = p.value.toIntOrNull() ?: p.min
                val next = (cur + 1).coerceAtMost(p.max)
                onSetProperty(p.key, next.toString())
            },
            enabled = (p.value.toIntOrNull() ?: p.min) < p.max,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("+") }
    }
}

/** 服务器详情的四个 tab。 */
enum class ServerTab(@StringRes val labelRes: Int) {
    Console(R.string.server_detail_tab_console),
    Files(R.string.server_detail_tab_files),
    Plugins(R.string.server_detail_tab_plugins),
    Config(R.string.server_detail_tab_config),
    // ⚠️ 2026-10-18 加（用户反馈「服务器设置又给塞哪儿了？」）：
    // Rust 启动前检查 EULA 并提示「请先在『服务器设置』里勾选」，
    // 但界面根本没有这个 tab —— 用户被指到一个不存在的地方。
    // 现在补上：EULA 同意、对外分享（陶瓦联机）都收进这里。
    Settings(R.string.server_detail_tab_settings),
}

/**
 * 一个已安装的插件。
 *
 * 字段与 Rust `hangar.rs:92` 的返回一一对应：
 * `{ "fileName": "EssentialsX.jar", "size": 1234567 }`。
 * `size` 在 Rust 侧是 `u64`，用 [Long] 接。
 */
data class InstalledPlugin(
    val fileName: String,
    val sizeBytes: Long = 0L,
)

/** 一条 server.properties 配置。 */
data class PropertyUi(
    val key: String,
    val value: String,
    val description: String = "",
    /**
     * 可编辑类型（决定用哪种控件）。
     *
     * ⚠️ 白名单与校验范围**照抄后端** `editable_props()`：
     * 后端整批校验、越界会整体拒绝（例如 view-distance 只接受 3–32）。
     * 前端按同样的范围做控件，能避免用户填了才被拒。
     */
    val kind: PropKind = PropKind.ReadOnly,
    /** Choice 类型的候选值。 */
    val choices: List<String> = emptyList(),
    /** Int 类型的范围。 */
    val min: Int = 0,
    val max: Int = 0,
)

/** server.properties 一项的编辑类型。 */
enum class PropKind { ReadOnly, Bool, Int, Choice }

data class ServerDetailUiState(
    val id: String = "",
    val name: String = "",
    val running: Boolean = false,
    val coreInstalled: Boolean = true,
    /** 是否已同意 Minecraft EULA（servers/<id>.json 的 eula 字段）。 */
    val eulaAgreed: Boolean = false,
    val installing: Boolean = false,
    /**
     * 启动阶段文案（`server://stage` 事件推来，2026-10-17 补）。
     *
     * ⚠️ 审计报告 H-4：这个事件 Rust 侧一直在发（servers.rs 4 处 + sleep.rs 4 处），
     * 但 Compose 端从没监听 —— 首次启动几十秒里界面只有转圈按钮，
     * 用户分不清"正在干活"和"卡死了"。
     */
    val stage: String = "",
    /**
     * 上一次运行的结束原因（`hosted_server_runtime` 的 `exitNote`）。
     *
     * ⚠️ 2026-10-19 补：Rust 侧一直把这个写进 `runtime.json.exit` 与
     * `launch-error.txt`，但界面**从不显示** —— 服务端起不来时用户只能看到
     * 「启动失败」四个字，没有任何原因，唯一的线索（日志）也没入口。
     * 现在直接显示在详情页顶部，不用再去找日志。
     */
    val exitNote: String? = null,
    val address: String = "",
    val tab: ServerTab = ServerTab.Console,
    val logs: List<String> = emptyList(),
    val commandInput: String = "",
    val properties: List<PropertyUi> = emptyList(),
    val loadingConfig: Boolean = false,
    val error: String? = null,

    // ── 分享给朋友（陶瓦联机）──────────────────────────────────────────
    /** 服务器监听端口 —— `/advertise?port=…` 要用。 */
    val port: Int = 25565,
    /** 是否已对外分享。 */
    val shareOn: Boolean = false,
    /** 分享进行中（请求飞行中，按钮要禁用防重复点击）。 */
    val shareBusy: Boolean = false,
    /** 拿到的房间码（朋友凭它加入）。 */
    val shareRoom: String = "",
    /** 分享相关的错误 / 等待文案。 */
    val shareMessage: String = "",

    // ── 插件 ────────────────────────────────────────────────────────────
    val plugins: List<InstalledPlugin> = emptyList(),
    val loadingPlugins: Boolean = false,
    /** 正在删除的插件文件名（null = 没有删除在进行）。 */
    val deletingPlugin: String? = null,
    /** 待确认删除的插件（非 null 时弹确认框）。 */
    val confirmDeletePlugin: InstalledPlugin? = null,

    // ── 内存 / 其它 ─────────────────────────────────────────────────────
    val maxMemoryMb: Int = 1024,
    val minMemoryMb: Int = 512,
    /** 操作成功提示（如「已复制房间码」「插件已删除」）。 */
    val notice: String? = null,
)







/**
 * 服务器详情的**顶栏内容**（2026-10-17 新增）。
 *
 * 和 [InstanceDetailToolbar] 同样的改法：页面内那套自绘标题栏
 * （返回箭头 + 服务器名 + 状态点 + 启停按钮）和外层 TopAppBar 重复，
 * 现在整体搬进 title 槽位。
 *
 * 布局：`服务器名 / ● 地址（或「已停止」）  [启动|停止]`
 */
@Composable
fun ServerDetailToolbar(
    name: String,
    running: Boolean,
    address: String,
    /** 启动阶段文案（`server://stage` 推来）。空串 = 没有进行中的阶段。 */
    stage: String,
    coreInstalled: Boolean,
    installing: Boolean,
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
                // 状态点：MD3 惯例，一眼看出在不在跑
                Surface(
                    modifier = Modifier.size(8.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                ) {}
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (running) {
                        address.ifBlank { stringResource(R.string.server_detail_running) }
                    } else {
                        stringResource(R.string.server_detail_stopped)
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
            running -> FilledTonalButton(onClick = onStop) {
                Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.server_detail_stop))
            }
            else -> Button(onClick = onStart) {
                if (stage.isNotBlank()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stage, maxLines = 1)
                } else {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.server_detail_start))
                }
            }
        }
    }
}

