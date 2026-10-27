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
import com.zhayi.qookix.compose.pages.NewsCache
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
 * 账号页 / 首页 / 插件页。
 *
 * 从 PageHost.kt 拆出（同包，符号不用 import）：三个页面各自 remember 自己的状态，
 * 只通过 bridge 取数据。纯搬运，private 改 internal，逻辑没动。
 */

/**
 * FRP 隧道页（对应旧 UI `FrpView.vue`）。
 *
 * ⚠️ 这一页在之前的覆盖对照里**被整个漏掉了** —— 用户点不到 FRP，
 * 也就用不了「把本机端口映射到公网」这条最常用的联机路径。
 *
 * 三条数据线各自独立：status（登录态+frpc）/ running（本进程跑的隧道）/
 * tunnels + userInfo（登录后才拉）。旧 UI 用 `Promise.allSettled` 保证
 * 「一个失败不该让另一边也空着」—— 这里同样分开处理。
 */

/**
 * 账号面板 + 数据加载。
 *
 * 没有账号就没法启动游戏，所以这个页面挡在「能用」与「不能用」之间。
 */
@Composable
internal fun AccountRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }

    var accounts by remember { mutableStateOf<List<AccountUi>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var msFlow by remember { mutableStateOf<MsLoginFlow?>(null) }
    var currentUuid by remember { mutableStateOf("") }

    fun reload() {
        api.listAccounts { r ->
            r.fold(
                onSuccess = { arr ->
                    accounts = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val uuid = o.optString("uuid")
                        if (uuid.isBlank()) return@mapNotNull null
                        AccountUi(
                            uuid = uuid,
                            name = o.optString("username").ifBlank { o.optString("name") },
                            // 账号类型字段各版本叫法不同，几种都认
                            online = o.optString("type").contains("microsoft", true) ||
                                o.optString("accountType").contains("microsoft", true) ||
                                o.optBoolean("online", false),
                        )
                    }
                    // 高亮哪一行必须跟后端「真正会用来启动的账号」一致 ——
                    // 读 settings.selected_account（Rust Settings 结构体是 snake_case，
                    // 但 update_settings_patch 的别名白名单两个都认，这里两个都试一遍）。
                    if (currentUuid.isBlank()) {
                        currentUuid = accounts.firstOrNull()?.uuid ?: ""
                        api.getSettings { sr ->
                            val sel = sr.getOrNull()?.let { s ->
                                s.optString("selected_account")
                                    .ifBlank { s.optString("selectedAccount") }
                            }.orEmpty()
                            if (sel.isNotBlank() && accounts.any { it.uuid == sel }) {
                                currentUuid = sel
                            }
                        }
                    }
                    loading = false
                },
                onFailure = {
                    loading = false
                    error = context.getString(R.string.account_read_failed, friendlyError(context, it))
                },
            )
        }
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) reload()
    }

    // 正版登录：拿到设备码后**轮询**等用户在浏览器里完成授权。
    //
    // 轮询间隔 3 秒 —— 微软设备码的典型等待是 10-30 秒，
    // 3 秒足够及时又不浪费。最多轮 3 分钟（60 × 3s）。
    LaunchedEffect(msFlow?.userCode) {
        if (msFlow == null) return@LaunchedEffect
        repeat(60) {
            kotlinx.coroutines.delay(3000)
            var done = false
            api.loginMsPoll { r ->
                r.onSuccess {
                    msFlow = null
                    done = true
                    reload()
                }
                // 失败大多表示「用户还没授权」→ 继续轮询。
                // 真失败（设备码过期）也走这里，靠轮询上限兜底。
            }
            if (done) return@LaunchedEffect
        }
        msFlow = null
        error = context.getString(R.string.login_timeout_detail)
    }

    AccountPanel(
        accounts = accounts,
        loading = loading,
        msFlow = msFlow,
        error = error,
        currentUuid = currentUuid,
        onAddOffline = { name ->
            api.loginOffline(name) { r ->
                r.fold(
                    onSuccess = { reload() },
                    onFailure = { e -> error = context.getString(R.string.account_add_failed, e.message) },
                )
            }
        },
        onStartMsLogin = {
            api.loginMsStart { r ->
                r.fold(
                    onSuccess = { o ->
                        msFlow = MsLoginFlow(
                            userCode = o.optString("userCode"),
                            verificationUri = o.optString("verificationUri"),
                            expiresIn = o.optInt("expiresIn", 0),
                        )
                        // ⚠️ 2026-10-18 加（修「微软登录不自动复制设备码」）：
                        // 之前只更新了界面状态，复制和开浏览器都要用户手动点 ——
                        // 但浏览器一弹出来就盖住 App，手动流程要来回切换抄那 9 位码。
                        // 现在拿到设备码先复制到剪贴板，再直接把授权页拉起来。
                        //（上一轮我误加到了 FRP 登录的设备码流程里，这次改对地方。）
                        val msCode = o.optString("userCode")
                        if (msCode.isNotBlank()) {
                            runCatching {
                                activity?.copyToClipboard(
                                    context.getString(R.string.account_code_label),
                                    msCode,
                                )
                            }
                        }
                        val msUri = o.optString("verificationUri")
                        if (msUri.isNotBlank()) activity?.openExternalUrl(msUri)
                    },
                    onFailure = { e -> error = context.getString(R.string.login_start_failed, e.message) },
                )
            }
        },
        onOpenVerification = { url ->
            if (url.isNotBlank()) activity?.openExternalUrl(url)
        },
        // ⚠️ 切换账号必须**写后端 settings.selected_account**，不能只改本地状态。
        // 否则：高亮条动了、看起来切成功，但 launch_game 用的还是旧账号
        //（Rust `resolve_launch_account` 按 settings 里的 selected_account 选人）。
        onSelect = { uuid ->
            currentUuid = uuid
            api.selectAccount(uuid) { r ->
                r.onFailure { e ->
                    error = context.getString(R.string.account_switch_failed, e.message)
                }
            }
        },
        onRemove = { uuid ->
            api.logoutAccount(uuid) { r ->
                r.fold(
                    onSuccess = { reload() },
                    onFailure = { e -> error = context.getString(R.string.account_delete_failed, e.message) },
                )
            }
        },
        onDismissError = { error = null },
    )
}


/**
 * 首页 + 数据装配。
 *
 * 设计取舍：首页要显示「当前实例 / 最近实例 / 进行中 / 新闻」四块。
 * 其中：
 *   - 实例：`list_instances`（本地，桥）
 *   - 新闻：`fetch_news`（联网，桥）—— 拉不到就静默留空（与旧实现一致，
 *     新闻不该拖垮首页）
 *   - 进行中任务：需要事件订阅（阶段 2），当前留空
 */
@Composable
internal fun HomeRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    instances: List<InstanceUi>,
    tasks: List<TaskUi>,
    /** 启动进度 0f..1f（`launch://progress` 的百分比）。 */
    launchProgress: Float = 0f,
    onNavigate: (NavDestination) -> Unit,
    // 由调用方（PageHost）注入：点首页「最近实例」卡片时先设 selectedInstanceId
    // 再导航到详情页 —— 这个状态定义在 PageHost 的作用域（L333），
    // HomeRoute 自己拿不到，必须走回调（2026-10-17 修「点了没反应」）。
    onOpenInstance: (String) -> Unit,
    pinnedIds: List<String> = emptyList(),
    runningInstanceId: String? = null,
) {
    // i18n（2026-10-17）：本函数内的错误消息需要 Context
    val context = androidx.compose.ui.platform.LocalContext.current
    // 初始值直接取缓存：切回首页时新闻立刻在，不再「先空着再出现」。
    var news by remember { mutableStateOf(NewsCache.items) }
    val api = remember(bridge) { QookixApi(bridge) }

    // 启动状态：正在启动的实例 id（按钮转圈用）、提示消息
    var launchingId by remember { mutableStateOf<String?>(null) }

    // 启动阶段文案（检查文件 / 补全 / 启动中）—— 让用户知道在做什么，
    // 而不是点完按钮一片安静
    var launchStage by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 账号列表 —— 启动前必须检查：没有账号时 Rust 侧会直接报错，
    // 与其让用户看到一句技术性错误，不如提前拦住并引导去添加。
    var accounts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // 游玩总时长（B3）：首页统计卡用。失败静默（不该影响首页可用性）。
    var playtimeSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) {
            // 有缓存且没过 TTL 就跳过 —— 否则每次进首页都打一次接口，
            // 既慢又浪费（用户感知到的就是「新闻晚一步出现」）。
            if (NewsCache.shouldRefresh()) {
                bridge.invoke("fetch_news", "{}") { r ->
                    r.onSuccess {
                        val list = parseNews(it)
                        NewsCache.update(list)
                        news = list
                    }
                    // 失败静默：新闻不该影响首页可用性（缓存里的旧内容继续显示）
                }
            } else {
                // 缓存可能比初始值新（并发情况），同步一次
                news = NewsCache.items
            }
            api.playtimeStats { r ->
                r.onSuccess { o -> playtimeSeconds = o.optLong("totalSeconds", 0L) }
            }
            api.listAccounts { r ->
                r.onSuccess { arr ->
                    accounts = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val uuid = o.optString("uuid")
                        val name = o.optString("username").ifBlank { o.optString("name") }
                        if (uuid.isBlank()) null else uuid to name
                    }
                }
            }
        }
    }

    HomePage(
        state = HomeUiState(
            current = instances.firstOrNull(),
            recent = instances.drop(1).take(8),
            pinned = instances.filter { it.id in pinnedIds },
            tasks = tasks,
            news = news,
            runningInstanceId = runningInstanceId,
            playtimeSeconds = playtimeSeconds,
        ),
        launchProgress = launchProgress,
        onLaunch = {
              android.util.Log.i("QkLaunch", "click instances=" + instances.size + " accounts=" + accounts.size)
            val inst = instances.firstOrNull()
            when {
                inst == null -> notice = context.getString(R.string.no_instances_hint)

                // 没账号时提前拦截。旧实现也是这个行为
                // （弹提示 + 引导去账号管理），只是它用 message + 弹层，
                // 这里用对话框，对横屏更友好。
                accounts.isEmpty() ->
                    notice = context.getString(R.string.no_account_hint)

                launchingId != null -> Unit  // 已经在启动了，忽略重复点击

                else -> {
                    launchingId = inst.id
                    // ⚠️ 首页启动也要走**完整流程**（检查文件 → 缺就补 → 再启动），
                    // 不能裸调 launch_game。详见 InstanceDetailRepo.launch 的注释：
                    // 下载中断的实例裸启动必然失败，而用户只看到英文错误码。
                    //
                    // 进度走 install://progress 事件 → 下载页可见，
                    // 所以这里只在按钮上显示阶段文案。
                    launchStage = context.getString(R.string.launch_checking_files)
                    val detailRepo = InstanceDetailRepo(api, context)
                    detailRepo.launch(
                        instanceId = inst.id,
                        onStage = { launchStage = it },
                    ) { r ->
                        launchingId = null
                        launchStage = null
                        r.fold(
                            onSuccess = { /* 成功就不打扰，界面已经切到游戏了 */ },
                            onFailure = { e ->
                                // 同 InstanceDetailRoute：把 INSTANCE_NOT_INSTALLED
                                // 这类哨兵错误翻成用户能照做的提示。
                                val raw = e.message.orEmpty()
                                notice = if (raw.contains("INSTANCE_NOT_INSTALLED")) {
                                    context.getString(R.string.launch_not_installed_short)
                                } else {
                                    context.getString(R.string.launch_failed_msg, raw)
                                }
                            },
                        )
                    }
                }
            }
        },
        onSwitchInstance = { onNavigate(NavDestination.Instances) },
        onOpenInstance = { id ->
            // 打开首页实例卡片对应的详情。
            //
            // ⚠️ 2026-10-17 修（用户报的 bug：首页点「最近实例」卡片毫无反应）。
            //
            // 原来这里**把回调参数丢了** —— 签名是 `onOpenInstance = { ... }`，
            // 连形参都没有，函数体里又用 `instances.firstOrNull()` 自己另找一个
            // 实例，于是：
            //   1. 无论点哪张卡片，行为都一样（永远打开第一个实例）；
            //   2. 最致命的是**从没设过 `selectedInstanceId`** ——
            //      而 `NavDestination.InstanceDetail` 分支（见上方 `:405-408`）
            //      在 `selectedInstanceId` 为空时会立刻
            //      `onNavigate(NavDestination.Instances)` 把用户弹回实例列表。
            //   两条一叠加，真机表现就是「点了卡片，什么都没发生」。
            //
            // 修法：用上形参 `id`，先把选中实例写进会话状态，再导航 ——
            // 与实例列表页 `onOpen`（`:460-463`）完全同构。
            if (id.isBlank()) {
                notice = context.getString(R.string.no_instances)
            } else {
                notice = null
                onOpenInstance(id)
            }
        },
        onOpenNews = { onNavigate(NavDestination.News) },
        onCancelTask = { taskId -> api.cancelInstall(taskId) { } },
    )

    // 提示对话框（统一出口：没有实例 / 没有账号 / 启动失败）
    notice?.let { msg ->
        InfoDialog(
            title = context.getString(R.string.hint_title),
            body = msg,
            onDismiss = { notice = null },
        )
    }
}


/** 插件子页。 */
@Composable
internal fun PluginsRoute(bridge: RustBridge, bridgeReady: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    var state by remember { mutableStateOf(PluginsUiState(loading = true)) }

    fun reload() {
        state = state.copy(loading = true, error = null)
        // 初始化状态
        api.getPluginSetupStatus { r ->
            r.onSuccess { o ->
                // 字段名可能是 ready / initialized / dismissed
                val ready = o.optBoolean("ready", true) ||
                    o.optBoolean("initialized", false)
                state = state.copy(ready = ready)
            }
        }
        // 已装插件
        api.getPlugins { r ->
            state = r.fold(
                onSuccess = { arr ->
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        PluginUi(
                            id = o.optString("id").ifBlank { o.optString("name") },
                            name = o.optString("name").ifBlank { o.optString("id") },
                            version = o.optString("version"),
                            description = o.optString("description"),
                            enabled = o.optBoolean("enabled", true),
                        )
                    }
                    state.copy(plugins = list, loading = false)
                },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.plugins_read_failed, friendlyError(context, it))) },
            )
        }
        // 插件清单地址（2026-10-21 补：get_plugin_manifest_url 一直没 UI）
        api.getPluginManifestUrl { r ->
            r.onSuccess { state = state.copy(manifestUrl = it) }
        }
    }

    // ── 监听 plugin://progress（2026-10-17 补，审计 H-6）─────────────
    //
    // payload = { id, phase, done, total, message }
    // plugin.rs 在装插件过程中持续推，原来 Compose 端零监听。
    DisposableEffect(bridge) {
        val sub = bridge.listen("plugin://progress") { payload ->
            runCatching {
                val o = org.json.JSONObject(payload)
                val msg = o.optString("message")
                val done = o.optInt("done", 0)
                val total = o.optInt("total", 0)
                Triple(msg, done, total)
            }.getOrNull()?.let { (msg, done, total) ->
                state = state.copy(
                    installMessage = msg,
                    installProgress = if (total > 0) done.toFloat() / total else 0f,
                )
            }
        }
        onDispose { bridge.unlisten(sub) }
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) reload()
    }

    PluginsPane(
        state = state,
        onRefresh = {
            state = state.copy(busy = true)
            api.refreshPluginManifest {
                state = state.copy(busy = false)
                reload()
            }
        },
        onInstallRecommended = {
            state = state.copy(busy = true)
            api.installRecommendedPlugins { r ->
                state = r.fold(
                    onSuccess = { state.copy(busy = false, ready = true) },
                    onFailure = { state.copy(busy = false, error = context.getString(R.string.plugins_install_failed, friendlyError(context, it))) },
                )
                reload()
            }
        },
        onTogglePlugin = { p, enabled ->
            // 乐观更新，失败回滚
            val prev = p.enabled
            state = state.copy(plugins = state.plugins.map { if (it.id == p.id) it.copy(enabled = enabled) else it })
            api.setPluginEnabled(p.id, enabled) { r ->
                r.onFailure { state = state.copy(plugins = state.plugins.map { if (it.id == p.id) it.copy(enabled = prev) else it }) }
            }
        },
        onUninstallPlugin = { p ->
            api.uninstallPlugin(p.id) { r ->
                r.fold(
                    onSuccess = { state = state.copy(plugins = state.plugins.filterNot { it.id == p.id }) },
                    onFailure = { state = state.copy(error = context.getString(R.string.plugins_install_failed, friendlyError(context, it))) },
                )
            }
        },
        onSaveManifestUrl = { url ->
            api.setPluginManifestUrl(url) { r ->
                r.fold(
                    onSuccess = { state = state.copy(manifestUrl = url) },
                    onFailure = { state = state.copy(error = context.getString(R.string.plugins_install_failed, friendlyError(context, it))) },
                )
            }
        },
    )
}
