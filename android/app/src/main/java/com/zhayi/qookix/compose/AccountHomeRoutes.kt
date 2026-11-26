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
import androidx.compose.foundation.background
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
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
import com.zhayi.qookix.compose.pages.MoreEntry
import com.zhayi.qookix.compose.pages.MoreEntryList
import com.zhayi.qookix.compose.pages.TaskCard
import com.zhayi.qookix.compose.pages.TaskUi
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
import com.zhayi.qookix.compose.pages.home.CustomHome
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
import com.zhayi.qookix.compose.pages.SkinServerLoginDialog
import com.zhayi.qookix.compose.pages.SkinRepo
import com.zhayi.qookix.compose.pages.SkinUiState
import com.zhayi.qookix.compose.pages.NewsCache
import com.zhayi.qookix.compose.pages.NewsItemUi
import com.zhayi.qookix.compose.pages.NewsPage
import com.zhayi.qookix.compose.pages.NewsUiState
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

private data class ProfileAccounts(
    val accounts: List<AccountUi>,
    val currentUuid: String,
)

/**
 * 账号页 / 首页 / 插件页。
 *
 * 从 PageHost.kt 拆出（同包，符号不用 import）：三个页面各自 remember 自己的状态，
 * 只通过 bridge 取数据。纯搬运，private 改 internal，逻辑没动。
 */

/**
 * 「我的」页（2026-10-27 从「账号」页扩容，接管底栏第五个位置）。
 *
 * 底栏原来有 6 项（首页/实例/内容/多人/下载/更多），「下载」和「更多」
 * 都是低频入口 —— 下载任务在首页/实例详情/这里都能看到，皮肤/统计/新闻/
 * 设置则全部挪进这个页面的入口列表。两个旧页面本身保留，从这里点进去。
 *
 * 结构：身份卡（头像/名字/账号类型）→ 进行中的任务 → 入口列表 → 账号管理。
 */
@Composable
internal fun AccountRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity? = null,
    /** 进行中的任务（下载/安装/补全），由外层共用的 TasksRepo 快照传入。 */
    tasks: List<TaskUi> = emptyList(),
    /** 页面内入口跳转（皮肤 / 下载任务 / 统计 / 新闻 / 设置）。 */
    onNavigate: (NavDestination) -> Unit = {},
    /** 取消一个进行中的任务。 */
    onCancelTask: (Long) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }

    var profileAccounts by remember(bridge) { mutableStateOf<ProfileAccounts?>(null) }
    var loadFailure by remember(bridge) { mutableStateOf<String?>(null) }
    var reloadGeneration by remember(bridge) { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var msFlow by remember { mutableStateOf<MsLoginFlow?>(null) }

    /** 身份卡上「添加账号」按钮的请求计数（AccountPanel 靠它弹对话框）。 */
    var addAccountRequest by remember { mutableStateOf(0) }

    fun reload() {
        val generation = ++reloadGeneration
        loadFailure = null
        api.listAccounts { r ->
            if (generation != reloadGeneration) return@listAccounts
            r.fold(
                onSuccess = { arr ->
                    val loadedAccounts = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val uuid = o.optString("uuid")
                        if (uuid.isBlank()) return@mapNotNull null
                        AccountUi(
                            uuid = uuid,
                            name = o.optString("username").ifBlank { o.optString("name") },
                            skinServerName = o.optString("skin_server_name").takeIf { !o.isNull("skin_server_name") },
                            online = o.optString("type").contains("microsoft", true) ||
                                o.optString("accountType").contains("microsoft", true) ||
                                o.optBoolean("online", false),
                        )
                    }
                    if (loadedAccounts.isEmpty()) {
                        profileAccounts = ProfileAccounts(emptyList(), "")
                    } else {
                        // 启动用的账号和列表一起提交，避免先显示第一个再跳到已选账号。
                        api.getSettings settings@{ sr ->
                            if (generation != reloadGeneration) return@settings
                            val selected = sr.getOrNull()?.let { s ->
                                s.optString("selected_account").takeUnless { s.isNull("selected_account") }
                                    .orEmpty().ifBlank { s.optString("selectedAccount") }
                            } ?: profileAccounts?.currentUuid
                            val current = loadedAccounts.firstOrNull { it.uuid == selected }
                                ?: loadedAccounts.first()
                            profileAccounts = ProfileAccounts(loadedAccounts, current.uuid)
                        }
                    }
                },
                onFailure = {
                    val message = context.getString(R.string.account_read_failed, friendlyError(context, it))
                    if (profileAccounts == null) loadFailure = message else error = message
                },
            )
        }
    }

    fun selectAccount(uuid: String, refreshOnFailure: Boolean = false) {
        // 启动读取后端的 selected_account，写入成功后再刷新档案和高亮。
        api.selectAccount(uuid) { result ->
            result.onSuccess { reload() }
            result.onFailure { e ->
                error = context.getString(R.string.account_switch_failed, e.message)
                if (refreshOnFailure) reload()
            }
        }
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) reload()
    }

    // 正版登录：拿到设备码后每 3 秒轮询一次授权结果，最多 60 次（3 分钟）。
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
                // 真失败（非「还没授权」）立刻停轮询并给具体原因，别干等 3 分钟超时。
                r.onFailure { e ->
                    if (e.message?.contains("__auth_pending__") != true) {
                        msFlow = null; done = true; error = friendlyError(context, e)
                    }
                }
            }
            if (done) return@LaunchedEffect
        }
        msFlow = null
        error = context.getString(R.string.login_timeout_detail)
    }

    val snapshot = profileAccounts
    val accounts = snapshot?.accounts.orEmpty()
    val currentUuid = snapshot?.currentUuid.orEmpty()
    val loading = snapshot == null && loadFailure == null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ProfileIdentityCard(
            current = accounts.firstOrNull { it.uuid == currentUuid },
            accounts = accounts,
            loading = loading,
            loadFailure = loadFailure,
            onRetry = { reload() },
            onSelectAccount = { selectAccount(it) },
            onAddAccount = { addAccountRequest += 1 },
        )

        ProfileTasksSection(tasks, onCancelTask)

        ProfileEntryList(
            onNavigate,
            modifier = Modifier.padding(horizontal = QkSpacing.lg, vertical = 10.dp),
        )

        AccountPanel(
        accounts = accounts,
        loading = loading,
        msFlow = msFlow,
        error = error,
        currentUuid = currentUuid,
        skinLogin = { onDismiss ->
            SkinServerLoginDialog(api, onDismiss) { uuid ->
                selectAccount(uuid, refreshOnFailure = true)
            }
        },
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
        onSelect = { selectAccount(it) },
        onRemove = { uuid ->
            api.logoutAccount(uuid) { r ->
                r.fold(
                    onSuccess = { reload() },
                    onFailure = { e -> error = context.getString(R.string.account_delete_failed, e.message) },
                )
            }
        },
        onDismissError = { error = null },
        // 嵌在上面的滚动 Column 里：内部不能用 fillMaxSize / LazyColumn
        embedded = true,
        // 身份卡上的「添加账号」按钮 → 打开这里的添加对话框
        addRequest = addAccountRequest,
    )
        Spacer(Modifier.height(QkSpacing.xl))
    }
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
    var selectedAccount by remember { mutableStateOf("") }

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
            api.getSettings { result ->
                result.onSuccess { settings ->
                    selectedAccount = settings.optString("selected_account").ifBlank { settings.optString("selectedAccount") }
                }
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

    val current = instances.firstOrNull()
    fun launch(inst: InstanceUi?) {
        when {
            inst == null -> notice = context.getString(R.string.no_instances_hint)
            accounts.isEmpty() -> notice = context.getString(R.string.no_account_hint)
            launchingId != null -> Unit
            else -> {
                launchingId = inst.id
                InstanceDetailRepo(api, context).launch(instanceId = inst.id, onStage = { launchStage = it }) { result ->
                    launchingId = null
                    launchStage = null
                    result.onFailure { error ->
                        val raw = error.message.orEmpty()
                        notice = when {
                            raw.contains("INSTANCE_NOT_INSTALLED") -> context.getString(R.string.launch_not_installed_short)
                            raw.contains("NO_ACCOUNT") -> context.getString(R.string.no_account_hint)
                            else -> context.getString(R.string.launch_failed_msg, raw)
                        }
                    }
                }
            }
        }
    }

    fun openInstance(id: String) {
        if (instances.any { it.id == id && id.isNotBlank() }) {
            notice = null
            onOpenInstance(id)
        } else {
            notice = context.getString(R.string.custom_home_instance_unavailable, id)
        }
    }

    CustomHome(
        account = accounts.firstOrNull { it.first == selectedAccount }?.second ?: accounts.firstOrNull()?.second,
        instance = current?.name,
        launchStage = launchStage,
        launchProgress = launchProgress,
        onLaunch = { name ->
            val target = if (name.isBlank()) current else instances.firstOrNull { it.name == name }
                ?: instances.firstOrNull { it.id == name }
            if (name.isNotBlank() && target == null) notice = context.getString(R.string.custom_home_instance_unavailable, name)
            else launch(target)
        },
        onNavigate = onNavigate,
        onOpenInstance = ::openInstance,
    ) {
        HomePage(
            state = HomeUiState(
                current = current,
                recent = instances.drop(1).take(8),
                pinned = instances.filter { it.id in pinnedIds },
                tasks = tasks,
                news = news,
                launching = launchingId != null,
                runningInstanceId = runningInstanceId,
                playtimeSeconds = playtimeSeconds,
            ),
            onLaunch = { launch(current) },
            onCreateInstance = { onNavigate(NavDestination.Instances) },
            onSwitchInstance = { onNavigate(NavDestination.Instances) },
            onOpenInstance = ::openInstance,
            onOpenNews = { onNavigate(NavDestination.News) },
            launchStage = launchStage,
            launchProgress = launchProgress,
            onCancelTask = { taskId -> api.cancelInstall(taskId) { } },
        )
    }

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
                // ⚠️ Rust `SetupStatus` 只有 needed / dismissed（「稍后」）/ error，没有
                // ready / initialized。`ready` 只表示「清单拉到了」，别再掺 needed/dismissed：
                // 原来 ready = `!needed || dismissed`，用户点过一次「稍后」后它就恒为 true，
                // 之后再卸掉渲染器/驱动插件，「补齐」入口永久消失（只能进游戏撞黑屏）。
                val needed = o.optBoolean("needed", false)
                val ready = o.optText("error") == null
                val missing = if (needed) (o.optJSONArray("missing")?.length() ?: 0) else 0
                state = state.copy(ready = ready, missingRecommended = missing)
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
                            // ⚠️ 2026-11-04（审计 C-05/C-06）：
                            // · PluginInfo 的说明字段叫 summary，不是 description → 原来描述恒为空
                            // · version 是 Option<String>，未安装时为 JSON null → 裸 optString 显示 "null"
                            version = o.optText("version") ?: o.optText("installed_version").orEmpty(),
                            description = o.optText("summary") ?: o.optText("description").orEmpty(),
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
            state = state.copy(busy = true, error = null)
            api.installRecommendedPlugins { r ->
                val failed = r.exceptionOrNull()
                state = state.copy(busy = false)
                reload()
                // ⚠️ reload() 第一行就会把 error 清成 null，失败提示必须在它之后写回，
                // 否则「清单拉取失败 / 网络不通」这种安装失败会彻底静默。
                if (failed != null) state = state.copy(
                    error = context.getString(R.string.plugins_install_failed, friendlyError(context, failed)),
                )
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
