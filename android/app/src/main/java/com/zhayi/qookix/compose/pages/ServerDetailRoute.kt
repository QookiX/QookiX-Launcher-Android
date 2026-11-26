package com.zhayi.qookix.compose.pages


import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.*

/**
 * 服务器详情的**路由层**（状态 + 与 Rust 的所有交互）。
 *
 * 过程态订阅 Rust 事件；运行状态与日志定期读取。
 */
@Composable
fun ServerDetailRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    serverId: String,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    /** 复制文本到系统剪贴板（由 Activity 提供）。 */
    onCopyText: (String) -> Unit = {},
    /** 向顶栏注册服务器信息（名字/状态/启停按钮）。 */
    onRegisterToolbar: ((@Composable () -> Unit)?) -> Unit = {},
) {
    val api = remember(bridge) { QookixApi(bridge) }
    val ctx = LocalContext.current
    val repo = remember(api) { ServerDetailRepo(api, ctx) }

    var state by remember(serverId) { mutableStateOf(ServerDetailUiState(id = serverId)) }
    // ── 监听 server://stage（过程态轮询查不到，按 serverId 过滤）
    androidx.compose.runtime.DisposableEffect(bridge, serverId) {
        repo.shareAlive = true
        val sub = bridge.listen("server://stage") { payload ->
            runCatching {
                org.json.JSONObject(payload).optString("id") to
                    org.json.JSONObject(payload).optString("stage")
            }.getOrNull()?.let { (id, st) ->
                if (id == serverId) {
                    state = state.copy(stage = st)
                }
            }
        }
        onDispose { repo.shareAlive = false; bridge.unlisten(sub) }
    }

    val scope = remember(bridgeReady, repo, serverId) { DetailRouteScope() }
    DisposableEffect(scope) { onDispose { scope.active = false } }
    var serverName by remember(scope) { mutableStateOf("") }
    var serverConfigLoaded by remember(scope) { mutableStateOf(false) }
    var renameDraft by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var renamePending by remember(scope) { mutableStateOf(false) }
    var renameError by remember(scope) { mutableStateOf<String?>(null) }

    LaunchedEffect(scope) {
        if (!bridgeReady) return@LaunchedEffect
        // Read this server's persisted name, port (required by LAN sharing), and memory.
        api.getHostedServer(serverId) { result ->
            if (!scope.active) return@getHostedServer
            result.onSuccess { config ->
                serverName = config.optString("name")
                state = state.copy(
                    port = config.optInt("port", 25565),
                    maxMemoryMb = config.optInt("max_memory_mb", 1024),
                    minMemoryMb = config.optInt("min_memory_mb", 512),
                )
                serverConfigLoaded = true
            }
        }
        // 服务器内存建议（2026-10-21 补：suggestServerMemory 一直没 UI）
        api.suggestServerMemory { r ->
            r.onSuccess { o ->
                state = state.copy(
                    suggestedMemMin = o.optInt("minMemoryMb", 0),
                    suggestedMemMax = o.optInt("maxMemoryMb", 0),
                )
            }
        }
    }

    val runtime = remember(scope) {
        DetailRead<ServerDetailRuntime> { complete ->
            val revision = scope.lifecycleRevision
            repo.loadRuntime(serverId) { result ->
                if (scope.active && revision == scope.lifecycleRevision) {
                    result.onSuccess {
                        state = state.copy(
                            running = it.running, jvmUp = it.jvmUp, sleeping = it.sleeping,
                            wakeError = it.wakeError, exitNote = it.exitNote, runtimeCurrent = true,
                            effectiveMaxMemoryMb = it.effectiveMaxMemoryMb,
                            logs = if (it.startedAt != scope.startedAt) emptyList() else state.logs,
                        )
                        scope.startedAt = it.startedAt
                    }.onFailure { state = state.copy(runtimeCurrent = false) }
                }
                complete(result)
            }
        }
    }
    val logs = remember(scope) {
        DetailRead<List<String>> { complete ->
            val startedAt = scope.startedAt
            repo.loadLogs(serverId) { result ->
                val current = scope.active && startedAt == scope.startedAt
                if (current) {
                    result.onSuccess { state = state.copy(logs = it, logsError = null) }
                        .onFailure { state = state.copy(logsError = friendlyError(ctx, it)) }
                }
                complete(if (current) result else Result.failure(kotlinx.coroutines.CancellationException()))
            }
        }
    }

    /** 拉一次已装插件列表（缺陷 2）。 */
    fun refreshPlugins() {
        state = state.copy(loadingPlugins = true)
        repo.loadPlugins(serverId) { r ->
            state = r.fold(
                onSuccess = { state.copy(plugins = it, loadingPlugins = false) },
                onFailure = {
                    state.copy(
                        loadingPlugins = false,
                        error = ctx.getString(
                            R.string.server_detail_load_plugins_failed,
                            friendlyError(ctx, it),
                        ),
                    )
                },
            )
        }
    }

    /**
     * 房间码轮询（缺陷 1 的收尾环节）。
     *
     * 为什么用协程而不是 Handler / setInterval：
     *   它**跟 composition 生命周期绑定** —— 用户离开详情页时这个
     *   LaunchedEffect 自动取消。旧 Vue 用 setInterval，只在 onUnmounted 里
     *   clearInterval，一旦中途出错就漏掉清理，变成永远在跑的轮询。
     *
     * 间隔 1500ms / 上限 45s，与旧 Vue 的 `waited >= 45000` 一致
     * （太短会在 Terracotta 还没扫到广播时就报「没拿到房间码」）。
     */
    LaunchedEffect(bridgeReady, serverId, state.shareOn, state.shareRoom, state.shareBusy) {
        if (!bridgeReady) return@LaunchedEffect
        if (!state.shareOn || state.shareRoom.isNotBlank()) return@LaunchedEffect
        // 还在发请求的阶段（连隧道 / 要 VPN 授权）先别轮询 —— 房都还没开
        if (state.shareBusy) return@LaunchedEffect

        var waited = 0L
        while (waited < ServerDetailRepo.sharePollTimeoutMs) {
            kotlinx.coroutines.delay(ServerDetailRepo.sharePollDelayMs)
            waited += ServerDetailRepo.sharePollDelayMs

            // ⚠️ pollRoom 是**异步回调**，回调不保证在 delay 之前回来。
            // 用一个「已收到」标记 + 短促等待，避免读到上一轮的空值。
            var code = ""
            var pollError = ""
            var done = false
            repo.pollRoom { room, error ->
                if (room.isNotBlank()) code = room
                if (error.isNotBlank()) pollError = error
                done = true
            }
            var spin = 0
            while (!done && spin < 40) {
                kotlinx.coroutines.delay(50)
                spin++
            }
            if (pollError.isNotBlank()) {
                repo.stopShare { }
                TunnelSharing.release(serverId)
                state = state.copy(shareOn = false, shareBusy = false, shareMessage = pollError,
                    error = ctx.getString(R.string.server_detail_share_failed_reason, pollError))
                return@LaunchedEffect
            }
            if (code.isNotBlank()) {
                state = state.copy(
                    shareRoom = code,
                    shareMessage = "",
                    notice = ctx.getString(R.string.server_detail_room_ready_notice),
                )
                return@LaunchedEffect
            }
        }
        // 等不到房间码：把这次广播/开房收尾掉，别把半开的房间留在隧道里
        repo.stopShare { }
        TunnelSharing.release(serverId)
        state = state.copy(
            shareMessage = ctx.getString(R.string.server_detail_room_timeout),
            shareOn = false,
            shareBusy = false,
        )
    }

    // 首屏：拉详情 + 日志 + 配置 + 插件
    LaunchedEffect(bridgeReady, serverId) {
        if (!bridgeReady) return@LaunchedEffect
        // 普通进页不探测陶瓦。状态查询可能拉起隧道；只恢复明确属于本服的房间。
        if (TunnelSharing.ownerServerId == serverId) {
            repo.tunnelState { st, room, tunnelError ->
                if (!scope.active || state.shareBusy || TunnelSharing.ownerServerId != serverId) return@tunnelState
                if (tunnelError.isNotBlank()) {
                    state = state.copy(shareMessage = tunnelError.removePrefix("java.lang.IllegalStateException: "))
                    return@tunnelState
                }
                if (st == "hosting" || st == "host-scanning") {
                    state = state.copy(shareOn = true, shareRoom = room, shareMessage = "")
                }
            }
        }
        repo.load(serverId) { r ->
            if (!scope.active) return@load
            r.onSuccess {
                state = state.copy(address = it.address, coreInstalled = it.coreInstalled, eulaAgreed = it.eulaAgreed)
            }.onFailure {
                state = state.copy(error = ctx.getString(R.string.server_detail_load_failed, friendlyError(ctx, it)))
            }
        }
        state = state.copy(loadingConfig = true)
        repo.loadProperties(serverId) { r ->
            state = r.fold(
                onSuccess = { state.copy(properties = it, loadingConfig = false) },
                onFailure = {
                    state.copy(
                        loadingConfig = false,
                        error = ctx.getString(
                            R.string.server_detail_load_config_failed,
                            friendlyError(ctx, it),
                        ),
                    )
                },
            )
        }
        refreshPlugins()
    }

    // Skip periodic ticks while a read is in flight; manual log refresh joins that read.
    LaunchedEffect(scope) {
        if (!bridgeReady) return@LaunchedEffect
        while (true) {
            runtime.request()
            logs.request()
            kotlinx.coroutines.delay(2000)
        }
    }

    var lifecycleAction by remember(scope) { mutableStateOf<Boolean?>(null) } // true = start/wake
    fun changeLifecycle(start: Boolean) {
        if (!bridgeReady || lifecycleAction != null || !state.runtimeCurrent || state.stage.isNotBlank()) return
        if (start && state.jvmUp || !start && !state.jvmUp) return
        lifecycleAction = start
        scope.lifecycleRevision++
        state = state.copy(error = null, notice = null)
        val complete: (Result<String>) -> Unit = complete@{ result ->
            if (!scope.active) return@complete
            scope.lifecycleRevision++
            lifecycleAction = null
            state = state.copy(stage = "", runtimeCurrent = false)
            result.onSuccess { if (!start) state = state.copy(notice = it) }
                .onFailure {
                    val label = if (start) R.string.server_detail_start_failed else R.string.server_detail_stop_failed
                    state = state.copy(error = ctx.getString(label, friendlyError(ctx, it)))
                }
            runtime.request()
        }
        if (start) repo.start(serverId, complete) else repo.stop(serverId, complete)
    }
    val startAction: () -> Unit = { changeLifecycle(true) }
    val stopAction: () -> Unit = { changeLifecycle(false) }
    val installCoreAction: () -> Unit = {
        state = state.copy(installing = true, error = null)
        repo.installCore(serverId) { r ->
            r.fold(
                onSuccess = {
                    state = state.copy(
                        installing = false,
                        notice = ctx.getString(R.string.server_detail_core_installed_notice),
                    )
                    repo.load(serverId) { lr ->
                        lr.onSuccess {
                            state = state.copy(
                                coreInstalled = it.coreInstalled,
                                address = it.address,
                            )
                        }
                    }
                },
                onFailure = { e ->
                    state = state.copy(
                        installing = false,
                        error = ctx.getString(
                            R.string.server_detail_install_core_failed,
                            e.message ?: ctx.getString(R.string.server_detail_unknown_error),
                        ),
                    )
                },
            )
        }
    }

    // ── 把服务器信息注册到顶栏 ──────────────────────────────────────
    //
    // 和实例详情同样的改法：页面内那套自绘标题栏（返回 + 名字 + 状态点 + 启停）
    // 和外层 TopAppBar 重复，整块搬进 title 槽位。
    SideEffect {
        onRegisterToolbar {
            ServerDetailToolbar(
                name = serverName,
                running = state.running,
                jvmUp = state.jvmUp,
                sleeping = state.sleeping,
                runtimeCurrent = state.runtimeCurrent,
                address = state.address,
                stage = state.stage,
                coreInstalled = state.coreInstalled,
                installing = state.installing,
                lifecycleAction = lifecycleAction,
                onStart = startAction,
                onStop = stopAction,
                onInstallCore = installCoreAction,
                onCopyAddress = onCopyText,
            )
        }
    }
    DisposableEffect(Unit) { onDispose { onRegisterToolbar(null) } }

    fun sendCommand(command: String) {
        if (!state.runtimeCurrent || !state.running || state.commandPending || command.isBlank()) return
        val revision = scope.inputRevision
        state = state.copy(commandPending = true, commandFeedback = null)
        repo.sendCommand(serverId, command) { result ->
            if (!scope.active) return@sendCommand
            val clear = revision == scope.inputRevision && result.isSuccess
            state = state.copy(
                commandPending = false,
                commandInput = if (clear) "" else state.commandInput,
                commandFeedback = result.fold(
                    onSuccess = { "> $command\n${it.take(4000).ifBlank { ctx.getString(R.string.server_detail_command_no_output) }}" },
                    onFailure = { ctx.getString(R.string.server_detail_send_failed, friendlyError(ctx, it)) },
                ),
                commandFailed = result.isFailure,
            )
        }
    }

    fun saveServerName() {
        val requestedName = renameDraft?.trim().orEmpty()
        if (!serverConfigLoaded || renamePending || requestedName.isBlank() || requestedName == serverName) return
        renamePending = true
        renameError = null
        api.updateHostedServer(org.json.JSONObject().put("id", serverId).put("name", requestedName)) { result ->
            result.onSuccess { config ->
                val savedName = config.optString("name", requestedName)
                if (TunnelSharing.ownerServerId == serverId) TunnelSharing.ownerServerName = savedName
                if (scope.active) {
                    serverName = savedName
                    renameDraft = null
                    state = state.copy(notice = ctx.getString(R.string.server_detail_name_saved))
                }
            }.onFailure {
                if (scope.active) {
                    renameError = ctx.getString(R.string.server_detail_rename_failed, friendlyError(ctx, it))
                }
            }
            if (scope.active) renamePending = false
        }
    }

    renameDraft?.let { draft ->
        ServerRenameDialog(
            draft = draft,
            pending = renamePending,
            error = renameError,
            canSave = serverConfigLoaded && draft.isNotBlank() && draft.trim() != serverName,
            onChange = {
                renameDraft = it
                renameError = null
            },
            onSave = ::saveServerName,
            onDismiss = {
                if (!renamePending) renameDraft = null
            },
        )
    }

    ServerDetailPage(
        state = state.copy(name = serverName.ifBlank { ctx.getString(R.string.server_detail_untitled) }),
        onRename = if (serverConfigLoaded) {
            {
                renameDraft = serverName
                renameError = null
            }
        } else null,
        onTabChange = { state = state.copy(tab = it) },
        onBack = onBack,
        onStart = startAction,
        onStop = stopAction,
        onCommandInput = {
            scope.inputRevision++
            state = state.copy(commandInput = it)
        },
        onCommandSend = { sendCommand(state.commandInput.trim()) },
        onInstallCore = installCoreAction,
        onOpenDirectory = { repo.openDirectory(serverId) { } },
        onOpenFiles = onOpenFiles,

        // ── 缺陷 1：分享给朋友 ──────────────────────────────────────────
        //
        // 与旧 Vue `toggleShare`（ServerDetailView.vue:548-609）一一对应。
                // 勾选 / 取消 Minecraft EULA。
                // 走 update_hosted_server 的 patch（Rust servers.rs:207 只认 `eula` 字段），
                // 成功后同步本地状态 —— 否则开关看着变了、实际没写盘。
                onSetEula = { agreed ->
                    api.updateHostedServer(
                        org.json.JSONObject().put("id", serverId).put("eula", agreed),
                    ) { r ->
                        r.fold(
                            onSuccess = { state = state.copy(eulaAgreed = agreed) },
                            onFailure = { e ->
                                state = state.copy(
                                    error = ctx.getString(R.string.server_detail_save_config_failed, e.message),
                                )
                            },
                        )
                    }
                },
        onToggleShare = { want ->
            if (state.shareBusy) return@ServerDetailPage
            if (TunnelSharing.ownedByOther(serverId)) {
                state = state.copy(shareMessage = ctx.getString(
                    R.string.server_detail_share_owned_by, TunnelSharing.ownerServerName,
                ))
                return@ServerDetailPage
            }

            if (!want) {
                state = state.copy(shareBusy = true, shareMessage = "")
                // 顺序有讲究：先停广播，再让 Terracotta 回到等待 ——
                // `/waiting` 里也会顺手停广播，但先停一次能保证「立刻」
                // 不再对外宣称有台服务器。
                repo.stopShare { r ->
                    r.fold(
                        onSuccess = {
                            TunnelSharing.release(serverId) // 房间停了，归属清空（B24）
                            state = state.copy(
                                shareOn = false,
                                shareRoom = "",
                                shareBusy = false,
                                shareMessage = "",
                                notice = ctx.getString(R.string.server_detail_share_stopped),
                            )
                        },
                        onFailure = { e ->
                            state = state.copy(
                                shareBusy = false,
                                error = ctx.getString(
                                    R.string.server_detail_share_stop_failed,
                                    e.message ?: ctx.getString(R.string.server_detail_unknown_error),
                                ),
                            )
                        },
                    )
                }
                return@ServerDetailPage
            }

            // 服务器没跑就不给开房：没有进程在监听，
            // 广播出去的端口谁也连不上（用户只会看到「房间码有了但进不来」）
            if (!state.running) {
                state = state.copy(
                    shareOn = false,
                    shareMessage = ctx.getString(R.string.server_detail_share_need_running),
                )
                return@ServerDetailPage
            }

            state = state.copy(
                shareOn = true,
                shareBusy = true,
                shareRoom = "",
                shareMessage = ctx.getString(R.string.server_detail_share_preparing),
                error = null,
            )
            TunnelSharing.claim(serverId, serverName.ifBlank { ctx.getString(R.string.server_detail_untitled) })
            repo.startShare(
                id = serverId,
                port = state.port,
                name = serverName.ifBlank { ctx.getString(R.string.server_detail_untitled) },
                onProgress = { msg -> state = state.copy(shareMessage = msg) },
            ) { r ->
                r.fold(
                    onSuccess = {
                        // 房间码由上面那个 LaunchedEffect 轮询拿到
                        state = state.copy(shareBusy = false)
                    },
                    onFailure = { e ->
                        // 开房失败：归属必须撤掉，否则下一次会被当成「别的服务器在分享」
                        TunnelSharing.release(serverId)
                        state = state.copy(
                            shareBusy = false,
                            shareOn = false,
                            shareMessage = e.message
                                ?: ctx.getString(R.string.server_detail_share_failed),
                            error = ctx.getString(
                                R.string.server_detail_share_failed_reason,
                                e.message ?: ctx.getString(R.string.server_detail_unknown_error),
                            ),
                        )
                    },
                )
            }
        },
        onCopyRoom = { room ->
            if (room.isNotBlank()) {
                onCopyText(room)
                state = state.copy(
                    notice = ctx.getString(R.string.server_detail_room_copied, room),
                )
            }
        },

        // ── 缺陷 2：插件 ────────────────────────────────────────────────
        onAskDeletePlugin = { p -> state = state.copy(confirmDeletePlugin = p) },
        onDeletePlugin = { p ->
            state = state.copy(deletingPlugin = p.fileName, confirmDeletePlugin = null)
            repo.deletePlugin(serverId, p.fileName) { r ->
                r.fold(
                    onSuccess = {
                        state = state.copy(
                            deletingPlugin = null,
                            notice = ctx.getString(R.string.server_detail_plugin_deleted, p.fileName),
                        )
                        refreshPlugins()
                    },
                    onFailure = { e ->
                        state = state.copy(
                            deletingPlugin = null,
                            error = ctx.getString(
                                R.string.server_detail_delete_plugin_failed,
                                e.message,
                            ),
                        )
                    },
                )
            }
        },
        onOpenPluginFolder = {
            // open_hosted_server_folder 在 Android 上**不弹文件管理器**，
            // 只返回路径（server_files.rs:72 的注释写了原因）。
            // 所以这里把路径提示出来 / 复制走，否则用户点了会以为按钮坏了。
            repo.openFolder(serverId, "plugins") { r ->
                r.fold(
                    onSuccess = { path ->
                        onCopyText(path)
                        state = state.copy(
                            notice = ctx.getString(R.string.server_detail_plugin_folder_copied, path),
                        )
                    },
                    onFailure = { e ->
                        state = state.copy(
                            error = ctx.getString(R.string.server_detail_plugin_folder_failed, e.message),
                        )
                    },
                )
            }
        },

        // ── 缺陷 3：日志 / 内存 / 删除 ──────────────────────────────────
        onRefreshLogs = {
            if (bridgeReady) logs.request { result ->
                if (scope.active && result.isSuccess) state = state.copy(notice = ctx.getString(R.string.server_detail_logs_refreshed))
            }
        },
        onRequestBatteryUnrestricted = {
            api.requestBatteryUnrestricted { r ->
                r.fold(
                    onSuccess = { state = state.copy(notice = ctx.getString(R.string.server_detail_request_battery)) },
                    onFailure = { state = state.copy(error = it.message) },
                )
            }
        },
        onSetMemory = { maxMb, minMb ->
            // 先纠正「最小 > 最大」（JVM 会直接拒绝启动），再交给后端钳制
            val fixedMin = minMb.coerceAtMost(maxMb)
            repo.setMemory(serverId, maxMb, fixedMin) { r ->
                r.fold(
                    onSuccess = { cfg ->
                        // 以**后端返回值**为准：它会 clamp 到 256–8192，
                        // 界面显示的必须是真正落盘的值
                        state = state.copy(
                            maxMemoryMb = cfg.optInt("max_memory_mb", maxMb),
                            minMemoryMb = cfg.optInt("min_memory_mb", fixedMin),
                            notice = ctx.getString(R.string.server_detail_memory_saved),
                        )
                    },
                    onFailure = { e ->
                        state = state.copy(error = ctx.getString(R.string.server_detail_memory_failed, e.message))
                    },
                )
            }
        },
        onDeleteServer = {
            repo.deleteServer(serverId) { r ->
                r.fold(
                    // 删完必须退回列表页 —— 留着详情页会对着一个不存在的 id
                    // 一直轮询，每 2 秒报一次「服务器不存在」
                    onSuccess = { onBack() },
                    onFailure = { e ->
                        state = state.copy(error = ctx.getString(R.string.server_detail_delete_failed, e.message))
                    },
                )
            }
        },
        onDismissNotice = { state = state.copy(notice = null) },
        // 顶部失败提示可手动关掉（2026-10-22）：用户反馈「提示占地方、
        // 看不到日志」—— 给他一个一键收起。
        onDismissError = { state = state.copy(error = null) },

        // ── 改 server.properties ────────────────────────────────────────
        //
        // Choice / Int 走字符串值（后端按字段类型自己解析），
        // Bool 单独一个回调是为了传真正的布尔 ——
        // 传字符串 "true" 后端也收，但类型更明确不易出错。
        onSetProperty = { key, value ->
            val num = value.toIntOrNull()
            repo.setProperty(
                id = serverId,
                key = key,
                value = num ?: value,
            ) { r ->
                // 以**后端返回值**为准刷新：它会做范围钳制，
                // 用户填了超范围的值时，界面显示的才是真正落盘的。
                r.fold(
                    onSuccess = { props -> state = state.copy(properties = props) },
                    onFailure = { e ->
                        state = state.copy(error = ctx.getString(R.string.server_detail_save_config_failed, e.message))
                    },
                )
            }
        },
        onToggleProperty = { key, value ->
            repo.setProperty(serverId, key, value) { r ->
                r.fold(
                    onSuccess = { props -> state = state.copy(properties = props) },
                    onFailure = { e ->
                        state = state.copy(error = ctx.getString(R.string.server_detail_save_config_failed, e.message))
                    },
                )
            }
        },
    )
}

private class DetailRouteScope {
    var active = true
    var lifecycleRevision = 0L
    var inputRevision = 0L
    var startedAt = 0L
}
