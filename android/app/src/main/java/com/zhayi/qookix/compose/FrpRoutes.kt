package com.zhayi.qookix.compose

import com.zhayi.qookix.compose.theme.QkSpacing
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import android.graphics.Bitmap
import org.json.JSONObject
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.withFrameNanos
import com.zhayi.qookix.compose.motion.QookiX
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.zhayi.qookix.compose.bridge.*
import com.zhayi.qookix.compose.motion.qkFadeIn
import com.zhayi.qookix.compose.motion.qkFadeOut
import com.zhayi.qookix.compose.motion.qkSlideInLeft
import com.zhayi.qookix.compose.motion.qkSlideInRight
import com.zhayi.qookix.compose.pages.AccountPanel
import com.zhayi.qookix.compose.pages.AccountUi
import com.zhayi.qookix.compose.pages.BrowsePage
import com.zhayi.qookix.compose.pages.BrowseType
import com.zhayi.qookix.compose.pages.BrowseRepo
import com.zhayi.qookix.compose.pages.BrowseUiState
import com.zhayi.qookix.compose.pages.CreateInstancePage
import com.zhayi.qookix.compose.pages.CreateRepo
import com.zhayi.qookix.compose.pages.ServerCreatePage
import com.zhayi.qookix.compose.pages.ServerCreateUiState
import com.zhayi.qookix.compose.pages.loadServerVersions
import com.zhayi.qookix.compose.pages.CreateUiState
import com.zhayi.qookix.compose.pages.settings.AppUpdateUiState
import com.zhayi.qookix.compose.pages.settings.loadContributors
import com.zhayi.qookix.compose.pages.DownloadsPage
import com.zhayi.qookix.compose.pages.DownloadsTab
import com.zhayi.qookix.compose.pages.DownloadsUiState
import com.zhayi.qookix.compose.pages.FileEditorOverlay
import com.zhayi.qookix.compose.pages.rememberFileEditor
import com.zhayi.qookix.compose.pages.FileManagerPage
import com.zhayi.qookix.compose.pages.FileManagerUiState
import com.zhayi.qookix.compose.pages.FileOps
import com.zhayi.qookix.compose.pages.fmtBytes
import com.zhayi.qookix.compose.pages.tooLargeToEdit
import com.zhayi.qookix.compose.pages.FrpNodeUi
import com.zhayi.qookix.compose.pages.FrpRoomUi
import com.zhayi.qookix.compose.pages.HomePage
import com.zhayi.qookix.compose.pages.HomeUiState
import com.zhayi.qookix.compose.pages.InstanceFileOps
import com.zhayi.qookix.compose.pages.InstanceDetailPage
import com.zhayi.qookix.compose.pages.InstanceDetailRepo
import com.zhayi.qookix.compose.pages.InstanceDetailToolbar
import com.zhayi.qookix.compose.pages.FileReportUi
import com.zhayi.qookix.compose.pages.MissingItemUi
import com.zhayi.qookix.compose.pages.InstanceDetailUiState
import com.zhayi.qookix.compose.pages.ControlButtonUi
import com.zhayi.qookix.compose.pages.SharedUsageUi
import com.zhayi.qookix.compose.pages.rememberInstanceSettings
import com.zhayi.qookix.compose.pages.InstanceTab
import com.zhayi.qookix.compose.pages.InstanceSection
import com.zhayi.qookix.compose.pages.ProjectUi
import com.zhayi.qookix.compose.pages.ContentVersionUi
import com.zhayi.qookix.compose.pages.DependencyUi
import com.zhayi.qookix.compose.pages.ExportPlanUi
import com.zhayi.qookix.compose.pages.InstallTargetUi
import com.zhayi.qookix.compose.pages.InstallDialog
import com.zhayi.qookix.compose.pages.ServerUi
import com.zhayi.qookix.compose.pages.InstanceUi
import com.zhayi.qookix.compose.pages.InstancesRepo
import com.zhayi.qookix.compose.pages.FrpPage
import com.zhayi.qookix.compose.pages.FrpUiState
import com.zhayi.qookix.compose.pages.FrpTunnelUi
import com.zhayi.qookix.compose.pages.FrpLoginDialog
import com.zhayi.qookix.compose.pages.FrpLoginUiState
import com.zhayi.qookix.compose.pages.FrpLoginPhase
import com.zhayi.qookix.compose.pages.FrpTunnelLogDialog
import com.zhayi.qookix.compose.pages.FrpRoomsPage
import com.zhayi.qookix.compose.pages.InstancesPage
import com.zhayi.qookix.compose.pages.InstancesUiState
import com.zhayi.qookix.compose.pages.FrpRoomsUiState
import com.zhayi.qookix.compose.pages.MorePage
import com.zhayi.qookix.compose.pages.MsLoginFlow
import com.zhayi.qookix.compose.pages.PlaytimePage
import com.zhayi.qookix.compose.pages.PlaytimeRoute
import com.zhayi.qookix.compose.ui.QkFeedback
import com.zhayi.qookix.compose.pages.PlaytimeUiState
import com.zhayi.qookix.compose.pages.InstancePlaytimeUi
import com.zhayi.qookix.compose.pages.DailyPlaytimeUi
import com.zhayi.qookix.compose.pages.MultiplayerPage
import com.zhayi.qookix.compose.pages.MultiplayerRepo
import com.zhayi.qookix.compose.pages.MultiplayerUiState
import com.zhayi.qookix.compose.pages.ServerDetailPage
import com.zhayi.qookix.compose.pages.ServerDetailRepo
import com.zhayi.qookix.compose.pages.ServerDetailRoute
import com.zhayi.qookix.compose.pages.ServerDetailUiState
import com.zhayi.qookix.compose.pages.ServerFileOps
import com.zhayi.qookix.compose.pages.CapeUi
import com.zhayi.qookix.compose.pages.Skin3dHost
import com.zhayi.qookix.compose.pages.SkinPage
import com.zhayi.qookix.compose.pages.SkinRepo
import com.zhayi.qookix.compose.pages.SkinUiState
import com.zhayi.qookix.compose.pages.NewsItemUi
import com.zhayi.qookix.compose.pages.NewsPage
import com.zhayi.qookix.compose.pages.NewsUiState
import com.zhayi.qookix.compose.pages.TaskUi
import com.zhayi.qookix.compose.pages.TasksRepo
import com.zhayi.qookix.compose.pages.loadNews
import com.zhayi.qookix.compose.pages.parseNews
import com.zhayi.qookix.compose.pages.settings.AboutPane
import com.zhayi.qookix.compose.pages.settings.AppearancePane
import com.zhayi.qookix.compose.pages.settings.ContentServicesPane
import com.zhayi.qookix.compose.pages.settings.DownloadPane
import com.zhayi.qookix.compose.pages.settings.GamePane
import com.zhayi.qookix.compose.pages.settings.GeneralPane
import com.zhayi.qookix.compose.pages.settings.InfoDialog
import com.zhayi.qookix.compose.pages.settings.MemoryInfoUi
import com.zhayi.qookix.compose.pages.settings.PluginUi
import com.zhayi.qookix.compose.pages.settings.PluginsPane
import com.zhayi.qookix.compose.pages.settings.PluginsUiState
import com.zhayi.qookix.compose.pages.settings.SettingsPage
import com.zhayi.qookix.compose.pages.settings.SettingsPatch
import com.zhayi.qookix.compose.pages.settings.SettingsPlaceholder
import com.zhayi.qookix.compose.pages.settings.SettingsRepo
import com.zhayi.qookix.compose.pages.settings.SettingsTab
import com.zhayi.qookix.compose.pages.settings.SettingsUiState
import com.zhayi.qookix.compose.pages.settings.StoragePane
import com.zhayi.qookix.compose.shell.NavDestination

/**
 * FRP 联机页（隧道页 + 房间页）。
 *
 * 从 PageHost.kt 拆出：两个页面各自持有自己的状态（FrpUiState / FrpRoomsUiState），
 * 只通过 bridge 取数据，与主机其它部分没有共享变量。纯搬运，逻辑没动。
 */

@Composable
internal fun FrpRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity?,
    onBack: () -> Unit,
    onOpenRooms: () -> Unit,
) {
    // i18n（2026-10-17）：本函数内的错误消息需要 Context
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    var state by remember { mutableStateOf(FrpUiState()) }
    var login by remember { mutableStateOf(FrpLoginUiState()) }
    var logDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var logLoading by remember { mutableStateOf(false) }

    // ── 生命周期取消闸 ─────────────────────────────────────────────────
    // 登录轮询用 postDelayed 自我重排，离开页面后迟到回调仍会写 login；
    // Runnable 句柄在回调内部（外部拿不到，removeCallbacks 不可行），
    // 所以 onDispose 关掉 alive 布尔闸，回调醒来读到就自行停下。
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }

    /** 拉一次状态（status + running 必拉；登录后才拉 tunnels/userInfo）。 */
    fun refresh() {
        state = state.copy(loading = true, loadError = "")
        api.frpStatus { sr ->
            val st = sr.getOrNull()
            if (st == null) {
                state = state.copy(loading = false, loadError = sr.exceptionOrNull()?.message ?: context.getString(R.string.frp_read_failed))
                return@frpStatus
            }
            val frpc = st.optJSONObject("frpc")
            val loggedIn = st.optBoolean("loggedIn", false)
            state = state.copy(
                loggedIn = loggedIn,
                username = st.optString("username"),
                apiBase = st.optString("apiBase"),
                frpcReady = frpc?.optBoolean("ready", false) ?: false,
                frpcDetail = frpc?.optString("detail").orEmpty(),
            )
            // running 与 tunnels 互不依赖，分开拉
            api.frpRunning { rr ->
                val runningArr = rr.getOrNull()
                // 把 running 信息合进 tunnels（按 tunnelId 匹配）
                val runningMap = HashMap<Int, org.json.JSONObject>()
                if (runningArr != null) {
                    for (i in 0 until runningArr.length()) {
                        val o = runningArr.optJSONObject(i) ?: continue
                        runningMap[o.optInt("tunnelId")] = o
                    }
                }
                if (!loggedIn) {
                    state = state.copy(loading = false, tunnels = emptyList(), tunnelStats = "")
                    return@frpRunning
                }
                api.frpUserInfo { ur ->
                    ur.onSuccess { u ->
                        val bal = listOf("balance", "coppers", "crystal")
                            .firstNotNullOfOrNull { k ->
                                u.opt(k)?.takeIf { it.toString().isNotBlank() }?.toString()
                            }.orEmpty()
                        state = state.copy(
                            email = u.optString("email"),
                            balance = bal,
                        )
                    }
                }
                api.frpTunnels { tr ->
                    val arr = tr.getOrNull()
                    if (arr == null) {
                        state = state.copy(
                            loading = false,
                            tunnels = emptyList(),
                            loadError = tr.exceptionOrNull()?.message.orEmpty(),
                        )
                        return@frpTunnels
                    }
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optInt("id")
                        val run = runningMap[id]
                        FrpTunnelUi(
                            id = id,
                            name = o.optString("name"),
                            protocol = o.optString("protocol"),
                            localPort = o.optInt("localPort"),
                            address = o.optString("address"),
                            status = o.optString("status"),
                            nodeName = o.optString("nodeName"),
                            alive = run?.optBoolean("alive", false) ?: false,
                            hasRunning = run != null,
                            runningAddress = run?.optString("address").orEmpty(),
                            exitNote = run?.optString("exit").orEmpty(),
                        )
                    }
                    // 统计**从自己拉到的列表算**，不用面板的 tunnel_stats：
                    // 那个统计和 list.php 的实际列表对不上（真机实测：显示"共 2·可用 2"
                    // 而列表里只有 1 条）。用户眼睛看到的是列表，统计就该跟列表一致。
                    val count = { s: String -> list.count { it.status == s } }
                    state = state.copy(
                        loading = false,
                        tunnels = list,
                        tunnelStats = context.getString(
                            R.string.frp_tunnel_stats,
                            list.size, count("active"), count("pending"), count("expired"),
                        ),
                    )
                }
            }
        }
    }

    LaunchedEffect(bridgeReady) { if (bridgeReady) refresh() }

    // 隧道进程可能自己退出（掉线/被面板踢），列表要能反映出来
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(4000)
            // 只刷 running，不整个 refresh（避免每 4 秒重拉隧道列表）
            api.frpRunning { rr ->
                val arr = rr.getOrNull() ?: return@frpRunning
                val map = HashMap<Int, org.json.JSONObject>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    map[o.optInt("tunnelId")] = o
                }
                state = state.copy(
                    tunnels = state.tunnels.map { t ->
                        val run = map[t.id]
                        t.copy(
                            alive = run?.optBoolean("alive", false) ?: false,
                            hasRunning = run != null,
                            runningAddress = run?.optString("address").orEmpty(),
                            exitNote = run?.optString("exit").orEmpty(),
                        )
                    },
                )
            }
        }
    }

    FrpPage(
        state = state,
        onRefresh = { refresh() },
        onOpenSite = {
            val url = state.apiBase
            if (url.isBlank()) activity?.openExternalUrl("")
            else activity?.openExternalUrl(url)
        },
        onProbe = {
            state = state.copy(probeBusy = true, probeResult = "")
            api.frpProbe { pr ->
                // 返回的是说明文本（String），不是对象
                state = state.copy(
                    probeBusy = false,
                    probeResult = pr.fold(
                        onSuccess = { context.getString(R.string.selfcheck_passed, it) },
                        onFailure = { context.getString(R.string.selfcheck_failed, friendlyError(context, it)) },
                    ),
                )
            }
        },
        onLogin = {
            login = FrpLoginUiState(visible = true, phase = FrpLoginPhase.Waiting)
            api.frpLoginStart { lr ->
                lr.fold(
                    onSuccess = { s ->
                        val code = s.optString("userCode")
                        val uri = s.optString("verificationUri")
                        val session = s.optString("session")
                        val expiresIn = s.optInt("expiresIn", 300)
                        val interval = s.optInt("interval", 5).coerceAtLeast(1)
                        login = login.copy(userCode = code, uri = uri)
                        // 手机上的顺路做法：直接把授权页拉起来
                        // ⚠️ 2026-10-18 加：拿到设备码先复制到剪贴板，再开浏览器 ——
                        // 浏览器会盖住 App，自动复制省得用户来回切换抄码。失败静默忽略。
                        if (code.isNotBlank()) {
                            runCatching {
                                activity?.copyToClipboard(
                                    context.getString(R.string.account_code_label),
                                    code,
                                )
                            }
                        }
                        if (uri.isNotBlank()) activity?.openExternalUrl(uri)
                        // 轮询上限按「有效期 / 间隔 + 余量」，超时如实说重来
                        val maxRounds = maxOf(3, expiresIn / interval + 2)
                        android.os.Handler(android.os.Looper.getMainLooper()).post(object : Runnable {
                            var round = 0
                            override fun run() {
                                // 页面已销毁：不再重排、不再写 login。Runnable 句柄不在外部手里，
                                // 用 alive 布尔闸，一关循环自然停。
                                if (!alive) return
                                if (round++ >= maxRounds) {
                                    login = login.copy(
                                        phase = FrpLoginPhase.Failed,
                                        message = context.getString(R.string.login_timeout),
                                    )
                                    return
                                }
                                api.frpLoginPoll(session) { pr ->
                                    // 迟到回调：页面可能已经不在了，别写它的状态
                                    if (!alive) return@frpLoginPoll
                                    val r = pr.getOrNull()
                                    val st = r?.optString("status").orEmpty()
                                    when (st) {
                                        "authorized" -> {
                                            login = login.copy(
                                                phase = FrpLoginPhase.Done,
                                                message = context.getString(R.string.login_success, r?.optString("username") ?: ""),
                                            )
                                            refresh()
                                            android.os.Handler(android.os.Looper.getMainLooper())
                                                .postDelayed({
                                                    // 900ms 的收尾延时也可能跨过页面销毁
                                                    if (alive) login = login.copy(visible = false)
                                                }, 900)
                                        }
                                        "expired", "denied" -> login = login.copy(
                                            phase = FrpLoginPhase.Failed,
                                            message = r?.optString("message").orEmpty().ifBlank { context.getString(R.string.login_denied) },
                                        )
                                        else -> android.os.Handler(android.os.Looper.getMainLooper())
                                            .postDelayed(this, interval * 1000L)
                                    }
                                }
                            }
                        })
                    },
                    onFailure = { e ->
                        login = login.copy(phase = FrpLoginPhase.Failed, message = e.message.orEmpty())
                    },
                )
            }
        },
        onLogout = {
            api.frpLogout {
                refresh()
            }
        },
        onToggleTunnel = { t ->
            if (state.busyTunnelId != null) return@FrpPage
            state = state.copy(busyTunnelId = t.id)
            if (t.alive) {
                api.frpStopTunnel(t.id) { r ->
                    state = state.copy(busyTunnelId = null)
                    refresh()
                }
            } else {
                api.frpStartTunnel(t.id) { r ->
                    state = state.copy(busyTunnelId = null)
                    // 启动失败时后端会把 frpc 的日志尾巴带回来 —— 原样给用户看，
                    // 比"启动失败"有用得多
                    r.onFailure { e ->
                        state = state.copy(loadError = context.getString(R.string.frp_start_failed, e.message))
                    }
                    refresh()
                }
            }
        },
        onOpenLog = { t ->
            logDialog = t.name to ""
            logLoading = true
            api.frpTunnelLog(t.id) { r ->
                logLoading = false
                logDialog = t.name to (r.getOrNull() ?: r.exceptionOrNull()?.message.orEmpty())
            }
        },
        onOpenRooms = onOpenRooms,
        onCopyAddress = { addr -> activity?.copyToClipboard(context.getString(R.string.frp_tunnel_address), addr) },
    )

    if (login.visible) {
        FrpLoginDialog(
            state = login,
            onDismiss = { login = login.copy(visible = false) },
            onOpenUri = { activity?.openExternalUrl(it) },
        )
    }

    logDialog?.let { (name, text) ->
        FrpTunnelLogDialog(
            title = context.getString(R.string.frpc_log_title, name),
            text = text,
            loading = logLoading,
            onDismiss = { logDialog = null },
        )
    }
}


/**
 * 账号面板 + 数据加载。
 *
 * 没有账号就没法启动游戏，所以这个页面挡在「能用」与「不能用」之间。
 */
/**
 * FRP 联机房间。
 *
 * ⚠️ 这个页面在之前的覆盖对照里**被漏掉了** —— 见 FrpRoomsPage 的注释。
 * 344 行 / 9 个命令，不是什么小功能。
 */
@Composable
internal fun FrpRoomsRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity? = null,
    /** 「还没登录面板」时回 FRP 面板页登录（null = 不显示该入口）。 */
    onOpenFrpPanel: (() -> Unit)? = null,
) {
    // i18n（2026-10-17）：本函数内的错误消息需要 Context
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }

    var state by remember { mutableStateOf(FrpRoomsUiState(busy = true)) }

    fun parseRoom(o: org.json.JSONObject): FrpRoomUi? {
        val id = o.optInt("id", -1)
        if (id < 0) return null
        return FrpRoomUi(
            id = id,
            name = o.optString("name"),
            status = o.optString("status"),
            gameName = o.optString("gameName"),
            hostName = o.optString("hostName"),
            playerCount = o.optInt("playerCount", 0),
            maxPlayers = o.optInt("maxPlayers", 0),
            hasPassword = o.optBoolean("hasPassword", false),
            isHost = o.optBoolean("isHost", false),
            reviewStatus = o.optString("reviewStatus"),
            // Rust 是 Option<String>：裸 optString 遇 JSON null 会显示「审核原因：null」
            reviewReason = o.optText("reviewReason").orEmpty(),
        )
    }

    fun reload() {
        state = state.copy(busy = true, error = null)
        // ⚠️ frp_room_list 返回**对象** {rooms:[...], total}，不是数组（按数组解必抛 JSONException）。
        api.frpRoomList { r ->
            r.fold(
                onSuccess = { obj ->
                    val arr = obj.optJSONArray("rooms")
                    val rooms = if (arr == null) emptyList() else {
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.let { parseRoom(it) }
                        }
                    }
                    state = state.copy(rooms = rooms, busy = false)
                },
                onFailure = { e ->
                    state = state.copy(busy = false, error = context.getString(R.string.frp_rooms_read_failed, e.message))
                },
            )
        }
        api.frpRoomNodes { r ->
            r.onSuccess { arr ->
                val nodes = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    // ⚠️ 节点 id 在 Rust 侧是 i64：用 optString 读成 "3" 再发回去会
                    // invalid type，建房 100% 失败。必须 optInt 保持数字类型。
                    val nid = o.optInt("id", -1).takeIf { it > 0 }
                    if (nid == null) null
                    else FrpNodeUi(nid, o.optString("name").ifBlank { nid.toString() })
                }
                state = state.copy(nodes = nodes)
            }
            // 节点拉不到时建房下拉是空的，得让用户看到原因而不是干等
            r.onFailure { e -> state = state.copy(error = context.getString(R.string.frp_room_join_failed, e.message)) }
        }
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) reload()
    }

    FrpRoomsPage(
        state = state,
        onRefresh = { reload() },
        onOpenFrpPanel = onOpenFrpPanel,
        onCreate = { name, nodeId, port, pwd ->
            state = state.copy(busy = true, error = null)
            api.frpRoomCreate(name, nodeId, port, pwd) { r ->
                r.fold(
                    onSuccess = { reload() },
                    onFailure = { e -> state = state.copy(busy = false, error = context.getString(R.string.frp_room_create_failed, e.message)) },
                )
            }
        },
        onEnter = { room ->
            state = state.copy(busy = true, error = null)
            if (room.isHost) {
                api.frpRoomHostStart(room.id) { r ->
                    state = r.fold(
                        onSuccess = { o ->
                            val local = o.optString("local").ifBlank { context.getString(R.string.frp_local_started) }
                            state.copy(
                                busy = false,
                                notes = state.notes + (room.id to context.getString(R.string.frp_room_opened, local)),
                            )
                        },
                        onFailure = { e -> state.copy(busy = false, error = context.getString(R.string.frp_room_open_failed, e.message)) },
                    )
                }
            } else {
                api.frpRoomVisitorStart(room.id) { r ->
                    state = r.fold(
                        onSuccess = { o ->
                            val connect = o.optString("connect")
                            state.copy(
                                busy = false,
                                connectAddress = connect.ifBlank { null },
                                notes = state.notes + (room.id to context.getString(R.string.frp_room_joined)),
                            )
                        },
                        onFailure = { e -> state.copy(busy = false, error = context.getString(R.string.frp_room_join_failed, e.message)) },
                    )
                }
            }
        },
        onEnterWithPassword = { room, pwd ->
            state = state.copy(busy = true, error = null)
            api.frpRoomJoin(room.id, pwd) { r ->
                state = r.fold(
                    onSuccess = { o ->
                        val connect = o.optString("connect")
                        state.copy(
                            busy = false,
                            connectAddress = connect.ifBlank { null },
                            notes = state.notes + (room.id to context.getString(R.string.frp_room_joined)),
                        )
                    },
                    onFailure = { e -> state.copy(busy = false, error = context.getString(R.string.frp_room_join_failed, e.message)) },
                )
            }
        },
        onExit = { room ->
            state = state.copy(busy = true, error = null)
            // ⚠️ 原来完全不读 Result：关闭/离开房间失败也照样清地址 + reload，界面和成功一样
            val afterPrimary: (Result<org.json.JSONObject>) -> Unit = { r ->
                r.fold(
                    onSuccess = {
                        api.frpRoomStop(room.id) { _ ->
                            state = state.copy(busy = false, connectAddress = null)
                            reload()
                        }
                    },
                    onFailure = { e -> state = state.copy(busy = false,
                        error = context.getString(R.string.frp_room_join_failed, e.message)) },
                )
            }
            if (room.isHost) api.frpRoomClose(room.id, afterPrimary)
            else api.frpRoomLeave(room.id, afterPrimary)
        },
        onOpenConnect = { addr ->
            activity?.copyToClipboard(context.getString(R.string.multiplayer_address), addr)
        },
        // 密码框「粘贴」走 Rust read_clipboard（绕开长按菜单弹不出来的问题）；空/失败回调 null。
        onReadClipboard = { cb ->
            api.readClipboard { r ->
                cb(r.getOrNull())
            }
        },
    )
}
