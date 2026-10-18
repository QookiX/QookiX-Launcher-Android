package com.zhayi.qookix.compose.pages


import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.friendlyError
import com.zhayi.qookix.compose.bridge.RustBridge

/**
 * 服务器详情的**路由层**（状态 + 与 Rust 的所有交互）。
 *
 * ── 为什么单独一个文件（2026-10-17）─────────────────────────────────────
 * 原先这一整段写在 `PageHost.kt` 的 `ServerDetailRoute` 里。补这三个缺陷
 * （分享 / 插件 / 日志内存）要往里面塞十来个回调 + 两个 LaunchedEffect，
 * 而 `PageHost.kt` 有 4000 行、是好几个功能共用的接线文件 —— 全塞进去
 * 会让本来就难读的文件更难读。
 *
 * 所以把「服务器详情」这一整块（本来是 PageHost 里的一个私有函数）
 * **原样搬到这里**，PageHost 只留一行调用。界面在 `ServerDetailPage.kt`，
 * 数据在 `ServerDetailRepo.kt`，接线在这里。
 *
 * ── 状态轮询 ──────────────────────────────────────────────────────────
 * 服务器运行状态会自己变（JVM 起来 / 崩溃 / 空闲休眠），而 Rust 侧没有
 * 为它发事件，所以用**定时轮询**。间隔 2 秒 —— 更密浪费，更疏则
 * 「点启动后半天没反应」。
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
    // ── 监听 server://stage（2026-10-17 补，审计 H-4）─────────────────
    //
    // Rust 侧 servers.rs（4 处）+ sleep.rs（4 处）都通过
    // progress::emit_server_stage 发这个事件，payload = { id, stage }。
    //
    // 原来 Compose 只靠 2 秒轮询 hosted_server_runtime 查运行态 ——
    // 但"正在生成补丁 / 正在下原版包"这种**过程**不在运行态里，
    // 用户看到的只是按钮转圈，几十秒里完全不知道在干什么。
    //
    // ⚠️ 必须按 serverId 过滤：同一时刻可能有多台服务器在启动。
    androidx.compose.runtime.DisposableEffect(bridge, serverId) {
        bridge.listen("server://stage") { payload ->
            runCatching {
                org.json.JSONObject(payload).optString("id") to
                    org.json.JSONObject(payload).optString("stage")
            }.getOrNull()?.let { (id, st) ->
                if (id == serverId) {
                    state = state.copy(stage = st)
                }
            }
        }
        onDispose { bridge.unlisten("server://stage") }
    }

    var serverName by remember(serverId) { mutableStateOf("") }

    /**
     * 记下这台服务器的端口 / 内存。
     *
     * ⚠️ 这几项**不在详情页自己的命令里**（`hosted_server_*` 只给运行状态、
     * 地址、核心是否装好），必须另走 `list_hosted_servers` 拿 ServerConfig。
     *
     * `port` 是 `/advertise?port=…` 的**必需参数**（缺陷 1）—— 拿不到就只能
     * 用一个猜的端口，广播出去的端口和实际监听的对不上，朋友永远连不进来，
     * 而且**完全没有报错**（Terracotta 那边只会说「没扫到」）。
     */
    LaunchedEffect(bridgeReady, serverId) {
        if (!bridgeReady) return@LaunchedEffect
        api.listHostedServers { r ->
            r.onSuccess { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    if (o.optString("id") != serverId) continue
                    serverName = o.optString("name")
                    state = state.copy(
                        port = o.optInt("port", 25565),
                        maxMemoryMb = o.optInt("max_memory_mb", 1024),
                        minMemoryMb = o.optInt("min_memory_mb", 512),
                    )
                    break
                }
            }
        }
    }

    /** 刷新日志（手动按钮与轮询走同一条路，保证行为一致）。 */
    fun refreshLogs() {
        repo.loadLogs(serverId) { r ->
            // 只保留最近 2000 行：控制台不需要全量，且越长重组越慢
            r.onSuccess { logs -> state = state.copy(logs = logs.takeLast(2000)) }
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
                            friendlyError(ctx, it.message),
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
            var done = false
            repo.pollRoom { room ->
                if (room.isNotBlank()) code = room
                done = true
            }
            var spin = 0
            while (!done && spin < 40) {
                kotlinx.coroutines.delay(50)
                spin++
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
        state = state.copy(
            shareMessage = ctx.getString(R.string.server_detail_room_timeout),
            shareOn = false,
        )
    }

    // 首屏：拉详情 + 日志 + 配置 + 插件
    LaunchedEffect(bridgeReady, serverId) {
        if (!bridgeReady) return@LaunchedEffect
        repo.load(serverId) { r ->
            r.onSuccess {
                state = state.copy(
                    running = it.running,
                    address = it.address,
                    coreInstalled = it.coreInstalled,
                )
            }
            r.onFailure {
                state = state.copy(
                    error = ctx.getString(
                        R.string.server_detail_load_failed,
                        friendlyError(ctx, it.message),
                    )
                )
            }
        }
        repo.loadLogs(serverId) { r ->
            r.onSuccess { state = state.copy(logs = it) }
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
                            friendlyError(ctx, it.message),
                        ),
                    )
                },
            )
        }
        refreshPlugins()
    }

    // 轮询：状态 + 日志
    LaunchedEffect(bridgeReady, serverId) {
        if (!bridgeReady) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(2000)
            repo.load(serverId) { r ->
                r.onSuccess {
                    state = state.copy(
                        running = it.running,
                        address = it.address,
                        coreInstalled = it.coreInstalled,
                    )
                }
            }
            repo.loadLogs(serverId) { r ->
                r.onSuccess { logs ->
                    // 只保留最近 2000 行：控制台不需要全量，且越长重组越慢
                    state = state.copy(logs = logs.takeLast(2000))
                }
            }
        }
    }

    // ── 启动 / 停止 / 装核心：顶栏和页面内共用（2026-10-17）────────────
    val startAction: () -> Unit = {
        state = state.copy(error = null)
        repo.start(serverId) { r ->
            r.onFailure { e ->
                state = state.copy(
                    error = ctx.getString(
                        R.string.server_detail_start_failed,
                        e.message ?: ctx.getString(R.string.server_detail_unknown_error),
                    )
                )
            }
        }
    }
    val stopAction: () -> Unit = {
        state = state.copy(error = null)
        repo.stop(serverId) { r ->
            r.onFailure { e ->
                state = state.copy(
                    error = ctx.getString(
                        R.string.server_detail_stop_failed,
                        e.message ?: ctx.getString(R.string.server_detail_unknown_error),
                    )
                )
            }
        }
    }
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
                                running = it.running,
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
                address = state.address,
                stage = state.stage,
                coreInstalled = state.coreInstalled,
                installing = state.installing,
                onStart = startAction,
                onStop = stopAction,
                onInstallCore = installCoreAction,
            )
        }
    }
    DisposableEffect(Unit) { onDispose { onRegisterToolbar(null) } }

    ServerDetailPage(
        state = state.copy(name = serverName.ifBlank { ctx.getString(R.string.server_detail_untitled) }),
        onTabChange = { state = state.copy(tab = it) },
        onBack = onBack,
        onStart = startAction,
        onStop = stopAction,
        onCommandInput = { state = state.copy(commandInput = it) },
        onCommandSend = {
            val cmd = state.commandInput.trim()
            if (cmd.isNotEmpty()) {
                repo.sendCommand(serverId, cmd)
                state = state.copy(commandInput = "")
            }
        },
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

            if (!want) {
                state = state.copy(shareBusy = true, shareMessage = "")
                // 顺序有讲究：先停广播，再让 Terracotta 回到等待 ——
                // `/waiting` 里也会顺手停广播，但先停一次能保证「立刻」
                // 不再对外宣称有台服务器。
                repo.stopShare { }
                state = state.copy(
                    shareOn = false,
                    shareRoom = "",
                    shareBusy = false,
                    shareMessage = "",
                    notice = ctx.getString(R.string.server_detail_share_stopped),
                )
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
            refreshLogs()
            state = state.copy(notice = ctx.getString(R.string.server_detail_logs_refreshed))
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






