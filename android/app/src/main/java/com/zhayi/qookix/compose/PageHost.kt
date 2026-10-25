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
 * 冷启动的「渲染器健康检查」是否已跑过。
 *
 * 进程级（不是 remember）：横竖屏重建会把组合整个丢掉，
 * 用 remember 的话旋转一次就会再查一次、再弹一次提示。
 */
private var coldRendererCheckDone = false

/**
 * 页面路由：按当前导航目标渲染对应页面。
 *
 * 未迁移的页面显示明确的占位（避免误以为页面坏了），
 * 迁移进度一眼可见。
 */
@Composable
fun PageHost(
    dest: NavDestination,
    bridge: RustBridge,
    bridgeReady: Boolean,
    onNavigate: (NavDestination) -> Unit,
    // 打开外部链接用（关于页的 GitHub、许可证）。
    // 传 Activity 而不是传一个 lambda：调用点太多，
    // 传一个 openUrl 函数要一层层往下透传（至少 3 层），
    // 而 Activity 本身就是宿主，页面需要什么就取什么。
    activity: ComposeMainActivity? = null,
    /**
     * 设置页改了「外观」（深浅模式 / 主题色）时回调给根节点。
     *
     * ⚠️ 为什么需要这个：`QookiXTheme` 在 `LauncherRoot` 外层，
     * 而设置状态在这一层。不回传的话，用户改完要**重启应用**才生效 ——
     * 那不算"设置能用"。
     *
     * 参数：(themeMode, themeColor)
     */
    onAppearanceChanged: (String, String, Int, String) -> Unit = { _, _, _, _ -> },
    /**
     * 侧边栏宽度变更（dp）。2026-10-17 新增，用户可在外观页拖动调节。
     *
     * 用**独立回调**而不是塞进 onAppearanceChanged：那个已经 4 个参数了，
     * 再加一个要把 6 处调用点全改一遍，容易漏、也容易错位。
     */
    onSidebarWidthChanged: (Int) -> Unit = {},
    /**
     * 3D 预览渲染方式（compose=省电 / webview=流畅）。
     * 由根节点从 `get_settings` 读出并一路传下来 —— 皮肤页要靠它决定
     * 是"把像素搬回 Compose"还是"让 WebView 直接显示"。
     */
    previewRenderMode: String = "compose",
    /**
     * 顶栏标题的覆盖值（null = 用 NavDestination 的默认标题）。
     *
     * ⚠️ 2026-10-17 加：文件浏览是「实例详情」下钻的一层，但它**不是**一个
     * NavDestination（只是本文件里的 fileBrowserTarget 状态）。
     * 结果进文件浏览后顶栏仍显示「实例详情」，用户以为没跳转成功。
     * 通过这个回调把真实标题报上去。
     */
    /**
     * 由 PageHost 注册一个「内部返回处理器」：返回 true 表示已消费。
     *
     * 用于文件浏览这类**不在 backStack 里**的下钻层 ——
     * 外层点顶栏返回时先问它，它说"我处理了"就不再做 nav 回退。
     */
    onBackHandlerChanged: ((() -> Boolean)?) -> Unit = {},
    onTitleChange: (String?) -> Unit = {},
    /**
     * 由 PageHost 向顶栏**注册一段自定义内容**（null = 清空）。
     *
     * ⚠️ 2026-10-17 加（用户要求「搜索条要跟『内容』标题平着」）：
     * 内容中心的搜索框 / 筛选 / 排序 / 紧凑切换状态都在 PageHost 内部，
     * 而 TopAppBar 在 ComposeMainActivity 外层 —— 必须靠这个回调把
     * 一段 @Composable 递上去，才能让它们出现在标题行里。
     */
    onRegisterToolbar: ((@Composable () -> Unit)?) -> Unit = {},
    /**
     * 当前选中的服务器 / 实例 id。
     *
     * ⚠️ 2026-10-17 从 PageHost 内部状态**提升到调用方**（ComposeMainActivity）。
     * 原因：LauncherShell 的 `if (size.useRail)` 把 content 放在**两个不同的
     * 组合位置**（横屏 Row 里 / 竖屏 Scaffold 里），旋转屏幕时整个 PageHost
     * 子树被销毁重建 —— 内部的 remember 全部清空，`selectedServerId` 变 null，
     * 于是走到 ServerDetail 分支的兜底 `LaunchedEffect { onNavigate(Multiplayer) }`，
     * 用户看到的就是「横屏看详情 → 转竖屏 → 自动退回列表」。
     *
     * `rememberSaveable` 也不够：它依赖稳定的组合位置，跨分支切换照样丢。
     * 提到 Activity 层（它不随屏幕形态重建）才是根治。
     */
    selectedServerId: String? = null,
    onSelectedServerIdChange: (String?) -> Unit = {},
    selectedInstanceId: String? = null,
    onSelectedInstanceIdChange: (String?) -> Unit = {},
) {
    // i18n（2026-10-17）：错误消息在非 Composable 的 lambda 里拼，
    // `stringResource` 用不了，只能靠 Context。这里取一次全程复用。
    val context = androidx.compose.ui.platform.LocalContext.current

    // 实例列表在首页与实例页共用 —— 提在这里加载一次，避免两个页面各拉一遍。
    //
    // ⚠️ 但「只加载一次」不够 —— 实例创建/删除后必须重新拉。
    //
    // 实测踩到（2026-10-13）：创建实例成功后导航回列表，列表仍显示「还没有实例」，
    // 用户以为失败 → 再创建一次 → **产生重复实例**（实测产生了 3 个）。
    //
    // 修法：加 `instancesReloadKey` 作为 LaunchedEffect 的 key。
    // 任何「改了实例集合」的操作只需 `instancesReloadKey++` 触发重载。
    //
    // 为什么用 key 而不是直接调 load：LaunchedEffect 首次也要跑（初始加载），
    // 复用同一机制能避免「初始加载」与「手动刷新」两套代码。
    //
    // ⚠️ 2026-10-25（A2 第一步）：列表**不再由这里持有** ——
    // 唯一份在 `CoreState`（StateFlow），这里只读；`instancesReloadKey`
    // 保留为「强制刷新」的触发器（语义 = CoreState.refresh()）。
    // 好处：Rust 侧写操作完成后推 `state://dirty`，全局自动重拉，
    // 不会再有「某个调用点忘了 ++」导致的幽灵 bug。
    val instances by CoreState.instances.collectAsState()
    // 写操作在飞的 key（A2）：用于防重复提交与卡片转圈
    val pendingWrites by CoreState.pending.collectAsState()
    // 实例页的写操作错误（不弹窗、不打断，但必须说出来）
    var instancesError by remember { mutableStateOf<String?>(null) }
    // 读列表失败的原因由 CoreState 统一记录（以前彻底静默 → 显示「还没有实例」）
    val instancesReadError by CoreState.instancesError.collectAsState()
    var instancesReloadKey by remember { mutableStateOf(0) }
    // 置顶实例 id 列表（2026-10-21 补：getPins/setPins 一直没 UI）
    var pinnedIds by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(bridgeReady, instancesReloadKey) {
        if (!bridgeReady) return@LaunchedEffect
        QookixApi(bridge).getPins { r ->
            r.onSuccess { arr ->
                pinnedIds = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i)
                    o?.optString("id")?.takeIf { it.isNotBlank() }
                }
            }
        }
    }

    // 启动器自更新状态 —— 放在 PageHost 层（2026-10-18）。
    //
    // 原来在 SettingsRoute 内部：切设置 tab 就会清空，
    // 「检查出新版本 → 切 tab → 回来变回当前版本」。
    // 而且下载进度事件要在一个不会被切页销毁的地方监听。
    var appUpdateState by remember { mutableStateOf(AppUpdateUiState()) }

    // 下载进度：Rust `updater::PROGRESS_EVENT` 每 200ms 发一次
    // payload = {"downloaded": u64, "total": u64}
    //
    // ⚠️ 之前**完全没监听** —— 点「下载」只看到一个转圈小圈，
    // 用户不知道下了多少，容易以为卡死（2026-10-18 修）。
    DisposableEffect(bridge) {
        val handler: (String) -> Unit = { payload ->
            runCatching {
                val o = org.json.JSONObject(payload)
                val done = o.optLong("downloaded", 0)
                val total = o.optLong("total", 0)
                appUpdateState = appUpdateState.copy(
                    downloadedBytes = done,
                    totalBytes = total,
                    progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f,
                )
            }
        }
        val sub = bridge.listen("app-update-progress", handler)
        onDispose { bridge.unlisten(sub) }
    }
    LaunchedEffect(bridgeReady, instancesReloadKey) {
        if (bridgeReady) {
            // attach 幂等：首次订阅 state://dirty，之后每次只是 refresh()
            CoreState.attach(context, bridge)
        }
    }

    // ── 实例分组（对应旧 UI InstancesView.vue:33-41 的 `sections`）─────────
    //
    // ⚠️ 2026-10-17 补（P2）：原来实例页的分节是**硬编码**的单节「全部」：
    //     listOf(InstanceSection(key = "all", name = "全部", items = instances))
    // 于是 `QookixApi.listInstanceGroups` 全工程零调用点，用户建了分组也看不到分节
    //（旧 UI 是「按组分节 + 未归组单列一节」，见 InstancesView.vue:35-40）。
    //
    // 数据需要两份，缺一不可：
    //   1. 分组本身（id / name）—— `list_instance_groups`
    //   2. 每个实例属于哪个组 —— `list_instances` 里每个对象的 `group` 字段
    //
    // 为什么第 2 份要在这里单独再取一次：展示模型 `InstanceUi` 由
    // `InstancesRepo.parse` 构造，而它**只取展示字段**（id/name/subtitle/icon_color），
    // 把 `group` 丢掉了。这里用「实例 id -> 分组 id」映射表把归属补回 `instances`，
    // 既不用改 InstancesRepo 的解析结果，也不影响首页等其它消费方。
    var groups by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    /** 实例 id -> 分组 id（缺失 = 未归组）。 */
    var instanceGroupOf by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(bridgeReady, instancesReloadKey) {
        if (!bridgeReady) return@LaunchedEffect
        QookixApi(bridge).listInstanceGroups { r ->
            r.onSuccess { arr ->
                groups = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (id.isBlank()) null else id to o.optString("name").ifBlank { id }
                }
            }
            // 失败静默：拿不到分组就退化成单节「全部」，不影响实例列表本身可用
        }
        // 实例 -> 分组 的归属（`list_instances` 的元素带 `group` 字段）
        bridge.invoke("list_instances", "{}") { r ->
            r.onSuccess { json ->
                instanceGroupOf = try {
                    val arr = org.json.JSONArray(json)
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id")
                        val g = o.optString("group")
                        if (id.isBlank() || g.isBlank()) null else id to g
                    }.toMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }
        }
    }

    /**
     * 实例页的分节。
     *
     * 规则对齐旧 UI `InstancesView.vue:35-40`：
     *   - 每个分组一节（**空分组也保留** —— 否则刚建好的组看不见，
     *     用户会以为没建成功。旧实现的 `.filter((s) => s.items.length || s.group)`
     *     就是这个语义）
     *   - 未归组的实例收进末尾的「未分组」一节（旧实现 `__ungrouped__`）
     *   - 没有任何分组时退化成单节「全部」，与补接线之前的行为一致
     */
    val instanceSections: List<InstanceSection> = remember(instances, groups, instanceGroupOf) {
        if (instances.isEmpty()) {
            emptyList()
        } else {
            val decorated = instances.map { it.copy(groupId = instanceGroupOf[it.id]) }
            if (groups.isEmpty()) {
                listOf(InstanceSection(key = "all", name = context.getString(R.string.instances_section_all), items = decorated))
            } else {
                buildList {
                    groups.forEach { (gid, gname) ->
                        add(
                            InstanceSection(
                                key = gid,
                                name = gname,
                                items = decorated.filter { it.groupId == gid },
                            ),
                        )
                    }
                    val rest = decorated.filter { it.groupId == null }
                    if (rest.isNotEmpty()) {
                        add(InstanceSection(key = "__ungrouped__", name = context.getString(R.string.instances_section_ungrouped), items = rest))
                    }
                }
            }
        }
    }

    // 任务（下载/安装）状态：由 Rust 的事件流驱动，首页与下载页共用。
    // 用 remember 固定一个 repo 实例（它内部持有订阅，不能每次重组都重建）。
    val tasksRepo = remember(bridge) { TasksRepo(bridge, context) }
    // A2：任务状态也只有一个真相源（`TasksRepo.state`），页面只读。
    // 以前是 `var tasksState` + 单槽 onChange —— 第二个订阅者会把第一个顶掉。
    // 这里保留「当前 tab」这个纯 UI 选择叠加在快照上（不写回 repo）。
    val taskSnapshot by tasksRepo.state.collectAsState()
    var downloadsTab by remember { mutableStateOf(DownloadsTab.Active) }
    val tasksState = taskSnapshot.copy(tab = downloadsTab)

    // ── 实例详情页要能看到「正在下载」并取消 ────────────────────────────
    //
    // 原来详情页完全看不到下载进度、也没有取消入口，只能切到下载页去翻。
    //
    // 只记「本次查看的实例 id」，任务快照仍由外层传下去：
    //   1. `TasksRepo.state` 已经是多订阅安全的 StateFlow，详情页**可以**自己
    //      collect —— 但它每次进出都会重建组合，多一个订阅者就多一次重组；
    //   2. 外层那份 `tasksState` 本来就是全量快照（`DownloadsUiState.active`
    //      里已经带了每个任务的归属实例），够详情页用了。
    //
    // 真正把「哪个任务属于这个实例」挑出来的动作在 InstanceDetailRoute 里
    //（`tasksRepo.activeTaskFor(instanceId)`，按 `Entry.instanceId` 精确匹配）。
    var taskQueryInstanceId by remember { mutableStateOf<String?>(null) }

    // 离开详情页就清掉，免得 `?: selectedInstanceId` 永远读不到兜底值
    //（那会把「当前查看的实例」改坏）。
    LaunchedEffect(dest) {
        if (dest != NavDestination.InstanceDetail) taskQueryInstanceId = null
    }

    // 当前正在查看的服务器（多人页点进去时设置）。
    // 之所以不用路由参数：NavDestination 是 enum，带不了参数；
    // 而这个「选中项」本质是会话状态，放在这里比给每个页面加参数更简单。
    // 当前正在查看的服务器 / 实例 —— 由调用方持有（见签名注释）。

    // 文件浏览器：非空表示「详情页下钻到文件浏览」这一层。
    // 用同一个状态服务实例与服务器两种场景 —— 它们的页面代码完全一样，
    // 只是底层的 FileOps 实现不同（见 FileOps.kt 的注释）。
    var fileBrowserTarget by remember { mutableStateOf<FileTarget?>(null) }

    // ── 渲染器健康检查（对应旧 UI 的 RendererHintDialog）────────────────
    //
    // 为什么需要：某些渲染器与 MC 版本不匹配时，游戏会**白屏卡住**
    //（1.8.9 + MobileGlues 实测），用户等几十秒被系统收掉 Activity，
    // 完全不知道发生了什么、也不知道该换哪个渲染器。
    //
    // 触发时机：游戏退出后查一次启动日志；后端在里面发现渲染器失败特征
    // **且**当前渲染器不是该版本推荐时，返回一条 issue，我们弹窗提示一键切换。
    var rendererIssue by remember { mutableStateOf<org.json.JSONObject?>(null) }
    // 已处理过的「实例:退出码」组合 —— 避免同一份日志反复弹
    val handledExits = remember { mutableSetOf<String>() }

    // ── 生命周期取消闸 ─────────────────────────────────────────────────
    // checkRendererHealth 走 Handler.postDelayed（游戏退出后延迟 0.6~2.2s 才触发），
    // 用户可能已经离开这个页面 —— 迟到回调再写 rendererIssue 会把状态
    // 写进一个已经不存在的页面。
    //
    // 为什么用 alive 布尔闸而不是 removeCallbacks：
    //   这里的多个循环共用 Main Looper，postDelayed 的句柄没有统一存下来，
    //   要精确 removeCallbacks 就得给每次调度都留 Handler+Runnable 引用，
    //   再在 onDispose 里逐个取消 —— 句柄管理复杂且容易漏。
    //   一个局部布尔闸最简单可靠：DisposableEffect 保证 onDispose 一定执行，
    //   回调醒来后读 alive 就知道自己该不该干活。
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }

    fun checkRendererHealth(instanceId: String) {
        // 页面已销毁：别再发起请求
        if (!alive) return
        if (instanceId.isBlank()) return
        QookixApi(bridge).checkRendererHealth(instanceId) { r ->
            // 迟到回调：页面可能已经不在了，别写它的状态
            if (!alive) return@checkRendererHealth
            r.onSuccess { o ->
                // 后端返回 null 表示健康 —— 桥会把 JSON null 当成空对象，
                // 所以判断依据是 instance_id 是否为空
                if (o.optString("instance_id").isNotBlank()) {
                    rendererIssue = o
                }
            }
            // 检查失败不打扰用户（旧 UI：`checkRendererHealth` 抛错时静默）
        }
    }

    // ── 冷启动静默检查一次渲染器（D9）──────────────────────────────────
    //
    // 触发时机：启动器起来后查一次「最近一次启动」的日志。之前出现过
    // 某次更新后渲染器配置悄悄失效、用户进游戏才白屏（等几十秒被系统收掉），
    // 完全不知道该换哪个渲染器。
    //
    // 每次进程只查一次（`coldRendererCheckDone` 是进程级的，不用 remember —
    // 否则横竖屏重建组合会再查一次、再弹一次），不阻塞首屏：
    // 失败完全静默，有问题才复用上面那个 rendererIssue 弹窗。
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady || coldRendererCheckDone) return@LaunchedEffect
        coldRendererCheckDone = true
        QookixApi(bridge).checkRendererHealthLatest { r ->
            r.onSuccess { o ->
                if (o.optString("instance_id").isNotBlank() && rendererIssue == null) {
                    rendererIssue = o
                }
            }
        }
    }

    /** 把当前实例的渲染器切成推荐值（弹窗的「切换」按钮）。 */
    fun applyRecommendedRenderer(issue: org.json.JSONObject) {
        val id = issue.optString("instance_id")
        val rec = issue.optString("recommended")
        if (id.isBlank() || rec.isBlank()) return
        // 命令名是 `update_instance`（不是 update_instance_settings），
        // 且 patch 里必须带 `id` —— patch 对象本身就是参数。
        val patch = org.json.JSONObject().put("id", id).put("renderer", rec)
        QookixApi(bridge).updateInstance(patch) { r ->
            r.fold(
                onSuccess = { rendererIssue = null },
                onFailure = { android.util.Log.w("QkRenderer", "切换渲染器失败", it) },
            )
        }
    }


    DisposableEffect(bridgeReady) {
        if (bridgeReady) {
            tasksRepo.start(
                onExit = { instanceId, code ->
                    // 游戏退出后查一次渲染器健康度（旧 UI 的行为：非正常退出延迟一点，
                    // 免得和崩溃弹窗叠在一起）
                    val key = "$instanceId:$code"
                    if (handledExits.add(key)) {
                        val delay = if (code != 0) 2200L else 600L
                        android.os.Handler(android.os.Looper.getMainLooper())
                            .postDelayed({ checkRendererHealth(instanceId) }, delay)
                    }
                },
            )
        }
        onDispose { tasksRepo.stop() }
    }

    // ── 页面切换转场 ────────────────────────────────────────────────────
    //
    // ⚠️ 这里**不能**用 `AnimatedContent` 包整个 `when`（踩过这个坑）。
    //
    // 实测（2026-10-13）：包上之后各页面的 `LaunchedEffect(bridgeReady)`
    // **不再执行** —— 内容页永远空白、新建实例页的版本清单永远加载不出来、
    // 创建按钮因为 version 为空而一直禁用。
    //
    // 原因：AnimatedContent 用自己的 targetState 管理子组合，页面内容被放进
    // 它的子 compose 作用域，effect 的启动/取消时机与外部 bridgeReady 脱钩
    // （动画期间还会有新旧两份内容并存）。
    //
    // 改法：保持 `when` 结构原样（各页 effect 正常跑），转场改成
    // **淡入 + 位移 + 轻微缩放** —— 视觉上明显是"换页了"，
    // 但不侵入任何页面的生命周期（不进 AnimatedContent 作用域）。
    //
    // ⚠️ 2026-10-17 二次修：原来用 `animateFloatAsState` + `pageVisible` 的
    // false→true 往返驱动，实机表现为**切页时抽搐一下**。
    //
    // 抽搐有三个来源：
    //   1. `pageVisible=false` 会先渲染一帧"起点状态"，再开始动画 ——
    //      这一帧虽然 alpha=0 看不见，但 scale/translation 已经生效，
    //      页面内容瞬间缩一下再放大，眼睛能捕捉到。
    //   2. `goingDeeper` 用 `remember(dest)` 缓存，但它依赖的 `prevDest`
    //      是在 LaunchedEffect **末尾**才赋值 —— 于是第一次切换用的是
    //      "上一次的上一次"，方向会突然翻转（+28dp 跳 -28dp），画面横跳。
    //   3. 四个独立的 `animateXxxAsState` 各自有启动时机，
    //      首帧不保证同时起跑，会有几毫秒的错位。
    //
    // 改法：改用 `Animatable` 显式驱动 —— 一个协程里按固定顺序
    // `snapTo(起点)` → `animateTo(终点)`，四个值同步推进，不会错位；
    // 也不再需要 `pageVisible` 这个中间状态。
    //
    // 方向在 LaunchedEffect 里**当场算**（用上一次真正生效的 dest），
    // 不放进 `remember` 缓存 —— 避免"用了过期值"。
    val pageAnim = remember { androidx.compose.animation.core.Animatable(1f) }
    // 用单独的状态记录"本次动画的方向"，由 LaunchedEffect 写入
    var slideFromRight by remember { mutableStateOf(true) }
    val lastDest = remember { mutableStateOf<NavDestination?>(null) }

    LaunchedEffect(dest) {
        val prev = lastDest.value
        if (prev != null && prev != dest) {
            val order = NavDestination.bottomBar
            val pi = order.indexOf(prev)
            val ci = order.indexOf(dest)
            // 一级页面之间：索引增大 = 往右走（从右进）；二级页面：都从右进
            slideFromRight = if (pi >= 0 && ci >= 0) ci >= pi else true
            // ⚠️ 先 snap 到起点（这一帧页面是 alpha=0，用户看不到跳变），
            // 再 animateTo(1f) —— 保证"起点 → 终点"是一条连续的曲线。
            pageAnim.snapTo(0f)
            pageAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260, easing = QookiX.EaseOut),
            )
        }
        lastDest.value = dest
    }

    // 由同一个 progress（0→1）派生出全部视觉量，保证完全同步
    //
    // ⚠️ 2026-10-17 三次修：**去掉横向位移**。
    //
    // 上一版给了 ±24dp 的 translationX，真机上表现为「内容整体往右窜一下、
    // 又弹回来」—— 因为内容区左边紧贴侧边栏的 VerticalDivider，
    // 平移时**左边会露出一条空白缝**，看起来像被推歪了，这就是用户说的"抽搐"。
    //
    // 现在只用「透明度 + 极轻微缩放」：
    //   - 缩放从**中心**收缩（transformOrigin 0.5/0.5），四周对称内收，
    //     不会露出任何一边的缝隙
    //   - 幅度只有 4%（0.96→1.0），是"内容浮上来"而不是"页面被推动"
    //   - 位移只保留纵向 6dp，向上浮起 —— 纵向不会碰到侧边栏
    val progress = pageAnim.value
    // ⚠️ alpha 不做 0→1，而是 0.65→1：
    // 真机抓帧发现「内容先完全消失（纯白）、再淡入」非常刺眼 ——
    // 那不是"转场"，是"闪一下"。留 65% 底色后视觉上是「新内容浮上来」，
    // 连贯得多，也符合 MD3 的 fade-through 思路（旧内容退到半透明，不是消失）。
    val pageAlpha = 0.65f + 0.35f * progress
    val pageShiftX = 0.dp
    // 纵向轻微上浮：6dp（原来 12dp，配合缩放后已经够了，再多会显得飘）
    val pageShiftY = (4.dp * (1f - progress))
    val pageScale = 1f - (1f - progress) * 0.02f   // 1.0 ↔ 0.98（幅度减半：4% 实机看还是"缩一下"太明显）

    Box(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ 用 `graphicsLayer` 而不是 `offset`：
            //
            // `Modifier.offset` **只改绘制位置、不改触摸命中区域**
            // （Compose 的既定行为），动画期间会出现
            // 「内容画在这里、点击却要按在原位」的错位 ——
            // 实测表现是「按钮点不动」，而且只在动画进行中复现，极难排查。
            //
            // `graphicsLayer` 的 translationY 会同步移动命中区域，没有这个问题。
            .graphicsLayer {
                translationX = pageShiftX.toPx()
                translationY = pageShiftY.toPx()
                alpha = pageAlpha
                scaleX = pageScale
                scaleY = pageScale
                // 中心锚点：四周对称内收，不会露出任何一侧的缝隙
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.5f)
            },
    ) {
    when (dest) {
            NavDestination.Home -> HomeRoute(
                bridge = bridge,
                bridgeReady = bridgeReady,
                instances = instances,
                tasks = tasksState.active,
                runningInstanceId = tasksRepo.runningInstanceId,
                onNavigate = onNavigate,
                onOpenInstance = { id ->
                    onSelectedInstanceIdChange(id)
                    onNavigate(NavDestination.InstanceDetail)
                },
                pinnedIds = pinnedIds,
            )

            NavDestination.Browse -> BrowseRoute(
                bridge = bridge,
                ready = bridgeReady,
                openUrl = activity?.let { a -> { url: String -> a.openExternalUrl(url) } },
                // 内容中心「没有可用实例」时的直达入口：跳到新建实例页。
                // 修的是新用户路径断点：原来只提示"需要一个 Fabric 实例"，无路可走。
                onRequestCreateInstance = { onNavigate(NavDestination.CreateInstance) },
        // 把搜索/筛选/排序/紧凑工具栏注册到顶栏（2026-10-17）
        onRegisterToolbar = onRegisterToolbar,
        activity = activity,
            )

        NavDestination.CreateInstance -> CreateRoute(
            bridge = bridge,
            activity = activity,
            bridgeReady = bridgeReady,
            onBack = { onNavigate(NavDestination.Instances) },
            onCreated = {
                // ⚠️ 必须先刷新再导航 —— 否则回到列表页看到的还是旧数据，
                // 用户会以为创建失败（实测踩到：连续创建出 3 个重复实例）。
                instancesReloadKey++
                onNavigate(NavDestination.Instances)
            },
        )


        // 新建服务器 —— 独立页面（2026-10-18 从弹窗改过来）。
        NavDestination.ServerCreate -> ServerCreateRoute(
            bridge = bridge,
            onBack = { onNavigate(NavDestination.Multiplayer) },
            onCreated = {
                // 与实例同理：先刷新再回去，否则列表还是旧的
                onNavigate(NavDestination.Multiplayer)
            },
        )
        NavDestination.Multiplayer -> MultiplayerRoute(
            bridge = bridge,
            bridgeReady = bridgeReady,
            activity = activity,
            onOpenServer = { id ->
                onSelectedServerIdChange(id)
                onNavigate(NavDestination.ServerDetail)
            },
            onNavigate = onNavigate,
        )

        NavDestination.ServerDetail -> {
            val id = selectedServerId
            if (id.isNullOrBlank()) {
                // 直接落到这个页面（没有选中服务器）时退回多人页，
                // 否则会显示一个空壳界面让人困惑
                LaunchedEffect(Unit) { onNavigate(NavDestination.Multiplayer) }
            } else if (fileBrowserTarget != null) {
                // 服务器文件浏览（从详情页「文件」tab 进入）
                FileBrowserRoute(
                    bridge = bridge,
                    bridgeReady = bridgeReady,
                    ops = remember(id) { ServerFileOps(QookixApi(bridge), id) },
                    onBack = { fileBrowserTarget = null },
                )
            } else {
                ServerDetailRoute(
                    bridge = bridge,
                    bridgeReady = bridgeReady,
                    serverId = id,
                    onBack = { onNavigate(NavDestination.Multiplayer) },
                    onOpenFiles = { fileBrowserTarget = FileTarget.Server },
                    // 房间码 / 插件目录路径都要能一键复制 ——
                    // 房间码本来就是发给朋友的，不能复制就得手抄
                    onCopyText = { text -> activity?.copyToClipboard("QookiX", text) },
                    onRegisterToolbar = onRegisterToolbar,
                )
            }
        }

        NavDestination.InstanceDetail -> {
            // 当前查看的实例。instanceId 优先用 `taskQueryInstanceId`
            // ——这是 InstanceDetailRoute 回写的**同一个值**，
            // 只是它一定在详情页组合之后才被赋值，而这里用它能让
            // 「详情页首帧」就带着正确的实例 id 去查任务（少一次空转）。
            val id = taskQueryInstanceId ?: selectedInstanceId
            if (id.isNullOrBlank()) {
                LaunchedEffect(Unit) { onNavigate(NavDestination.Instances) }
            } else if (fileBrowserTarget != null) {
                // 进文件浏览时把标题改掉（否则顶栏还显示「实例详情」）
                LaunchedEffect(fileBrowserTarget) { onTitleChange(context.getString(R.string.nav_file)) }
                // 注册「内部返回」：外层顶栏点返回时先问这里，
                // 这样才不会直接跳实例列表（跳过详情页）。
                LaunchedEffect(fileBrowserTarget) {
                    onBackHandlerChanged({ fileBrowserTarget = null; true })
                }
                FileBrowserRoute(
                    bridge = bridge,
                    bridgeReady = bridgeReady,
                    ops = remember(id) { InstanceFileOps(QookixApi(bridge), id) },
                    onBack = { fileBrowserTarget = null },
                )
            } else {
                InstanceDetailRoute(
                    bridge = bridge,
                    bridgeReady = bridgeReady,
                    instanceId = id,
                    activity = activity,
                    onBack = { onNavigate(NavDestination.Instances) },
                    onOpenFiles = { fileBrowserTarget = FileTarget.Instance },
                    tasksRepo = tasksRepo,
                    // 任务列表快照（详情页据此显示「正在下载 + 取消」）
                    tasksState = tasksState,
                    onTaskQuery = { taskQueryInstanceId = it },
                    // 把实例信息注册到顶栏（替换掉「实例详情」那个标题）
                    onRegisterToolbar = onRegisterToolbar,
                    // 换图标后要让实例列表重载（D3）
                    onInstanceChanged = { instancesReloadKey++ },
                )
            }
        }

        NavDestination.Settings -> SettingsRoute(
            bridge = bridge,
            bridgeReady = bridgeReady,
            openUrl = activity?.let { a -> { url: String -> a.openExternalUrl(url) } },
            activity = activity,
            onAppearanceChanged = onAppearanceChanged,
        onSidebarWidthChanged = onSidebarWidthChanged,
            updateState = appUpdateState,
            onUpdateStateChange = { appUpdateState = it },
        )

        NavDestination.Skins -> SkinRoute(bridge, bridgeReady, activity, previewRenderMode)

        NavDestination.News -> NewsRoute(
            bridge = bridge,
            ready = bridgeReady,
            openUrl = activity?.let { a -> { url: String -> a.openExternalUrl(url) } },
        )

        NavDestination.More -> MorePage(
            onOpenSkins = { onNavigate(NavDestination.Skins) },
            onOpenNews = { onNavigate(NavDestination.News) },
            onOpenSettings = { onNavigate(NavDestination.Settings) },
            onOpenPlaytime = { onNavigate(NavDestination.Playtime) },
        )

        NavDestination.Instances -> InstancesPage(
            state = InstancesUiState(sections = instanceSections),
            onCreate = { onNavigate(NavDestination.CreateInstance) },
            onOpen = { id ->
                onSelectedInstanceIdChange(id)
                onNavigate(NavDestination.InstanceDetail)
            },
            onLaunch = { id ->
                QookixApi(bridge).launchGame(id, null) { }
            },
            onDelete = { target ->
                // A2 写状态机：登记 key → 发请求 → 无论成败解除。
                // **不再本地摘列表**（那是乐观更新）：列表由 Rust 的
                // state://dirty → CoreState.refresh() 的结果决定。
                val key = CoreState.instanceKey(target.id)
                if (CoreState.beginWrite(key)) {
                    instancesError = null
                    QookixApi(bridge).deleteInstance(target.id) { r ->
                        CoreState.endWrite(key)
                        r.onFailure { e ->
                            // 以前这里是静默的：用户以为删掉了，实际还在
                            instancesError = context.getString(
                                R.string.instances_delete_failed,
                                friendlyError(context, e),
                            )
                        }
                    }
                }
            },
            pendingIds = pendingWrites
                .filter { it.startsWith("instance:") }
                .map { it.removePrefix("instance:") }
                .toSet(),
            error = instancesError ?: instancesReadError,
            onRetry = { CoreState.refresh() },
            // 分组管理（2026-10-21 补：后端命令一直没 UI）
            onCreateGroup = { name ->
                QookixApi(bridge).createInstanceGroup(name, null) { r ->
                    r.onSuccess { instancesReloadKey++ }
                }
            },
            onRenameGroup = { id, newName ->
                QookixApi(bridge).renameInstanceGroup(id, newName, null) { r ->
                    r.onSuccess { instancesReloadKey++ }
                }
            },
            onDeleteGroup = { id ->
                QookixApi(bridge).deleteInstanceGroup(id) { r ->
                    r.onSuccess { instancesReloadKey++ }
                }
            },
            onMoveGroup = { fromIdx, toIdx ->
                // 交换两个真实分组的位置，重排后调 reorderInstanceGroups
                val currentOrder = groups.map { it.first }
                if (fromIdx in currentOrder.indices && toIdx in currentOrder.indices) {
                    val swapped = currentOrder.toMutableList()
                    val tmp = swapped[fromIdx]
                    swapped[fromIdx] = swapped[toIdx]
                    swapped[toIdx] = tmp
                    QookixApi(bridge).reorderInstanceGroups(swapped) { r ->
                        r.onSuccess { instancesReloadKey++ }
                    }
                }
            },
            pinnedIds = pinnedIds.toSet(),
            onTogglePin = { id ->
                val newPins = if (id in pinnedIds) pinnedIds.filterNot { it == id } else pinnedIds + id
                val arr = org.json.JSONArray()
                newPins.forEach { pid ->
                    arr.put(org.json.JSONObject()
                        .put("id", pid)
                        .put("type", "instance")
                        .put("target", "home")
                        .put("instance_id", pid))
                }
                QookixApi(bridge).setPins(arr) { r ->
                    r.onSuccess { pinnedIds = newPins }
                }
            },
        )

        NavDestination.Downloads -> DownloadsPage(
            // tasksState 已经叠了 downloadsTab（见声明处）
            state = tasksState,
            onTabChange = { downloadsTab = it },
            onCancel = { id -> tasksRepo.cancel(id) },
            onClearFinished = { tasksRepo.clearFinished() },
        )

        // ⚠️ FRP 隧道页 —— 上一轮对照旧 UI 时整个漏掉了（FrpView.vue 568 行）。
        // 它是 MohuaFrp 的入口：登录面板、隧道列表、启停隧道。
        NavDestination.Frp -> FrpRoute(
            bridge = bridge,
            bridgeReady = bridgeReady,
            activity = activity,
            onBack = { onNavigate(NavDestination.Multiplayer) },
            onOpenRooms = { onNavigate(NavDestination.FrpRooms) },
        )

        NavDestination.FrpRooms -> FrpRoomsRoute(
            bridge = bridge,
            bridgeReady = bridgeReady,
            activity = activity,
        )

        NavDestination.Playtime -> PlaytimeRoute(bridge, bridgeReady)

        NavDestination.Account -> AccountRoute(
            bridge = bridge,
            bridgeReady = bridgeReady,
            activity = activity,
        )

        else -> PlaceholderPage(dest)
    }

    // ── 渲染器建议弹窗（对应旧 UI 的 RendererHintDialog）────────────
    //
    // 文案照抄旧 UI 的要点：说清「当前用什么 → 推荐用什么」+ 为什么，
    // 并给两个明确按钮（切换 / 仍用当前）。
    rendererIssue?.let { issue ->
        val usedName = issue.optString("used_name").ifBlank { issue.optString("used") }
        val recName = issue.optString("recommended_name").ifBlank { issue.optString("recommended") }
        val mcVersion = issue.optString("mc_version")
        val reason = issue.optString("reason")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rendererIssue = null },
            title = { Text(stringResource(R.string.renderer_hint_title)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text(
                        text = context.getString(R.string.renderer_hint_current, usedName, recName),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                    androidx.compose.foundation.layout.Spacer(
                        androidx.compose.ui.Modifier.height(8.dp),
                    )
                    if (reason.isNotBlank()) {
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                        androidx.compose.foundation.layout.Spacer(
                            androidx.compose.ui.Modifier.height(8.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.renderer_hint_body, mcVersion, usedName, recName),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { applyRecommendedRenderer(issue) }) {
                    Text(stringResource(R.string.renderer_hint_switch, recName))
                }
            },
            dismissButton = {
                TextButton(onClick = { rendererIssue = null }) { Text(stringResource(R.string.renderer_hint_keep)) }
            },
        )
    }
    }
}

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
@Composable
private fun FrpRoute(
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
    // FRP 登录轮询（frpLoginPoll 的 Runnable）通过 postDelayed 自我重排，
    // 用户放弃登录/离开页面后循环还会继续跑到 maxRounds，迟到回调会写
    // `login` 状态 —— 写进一个已经不存在的页面。
    //
    // 为什么用 alive 布尔闸而不是 removeCallbacks：
    //   轮询 Runnable 在 api 回调内部自我 postDelayed，外部拿不到稳定的
    //   句柄；页面里多个循环又共用 Main Looper，逐个存 Handler+Runnable
    //   再在 onDispose 里取消既繁琐又容易漏。布尔闸最简单可靠：
    //   onDispose 一定执行，Runnable/回调醒来后读 alive 就知道该不该继续。
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
                                // 页面已销毁（用户放弃登录/离开）：停止自我重排，
                                // 也不再写 `login` 状态。这里用布尔闸而不是
                                // removeCallbacks —— Runnable 句柄不在外部手里，
                                // 闸一关循环自然停。
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
private fun FrpRoomsRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity? = null,
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
            reviewReason = o.optString("reviewReason"),
        )
    }

    fun reload() {
        state = state.copy(busy = true, error = null)
        // ⚠️ frp_room_list 返回的是**对象** {rooms:[...], total, page, pageSize}，
        // 不是数组（Rust frp.rs:1043-1048）。以前按数组解，必然抛 JSONException。
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
                    // ⚠️ 节点 id 在 Rust 侧是 **i64**（frp.rs:982-983 `RoomNode.id`）。
                    // 以前用 optString("id") 读成字符串 "3"，发给 frp_room_create
                    // （Rust `node_id: i64`）就报 invalid type: string "3", expected i64
                    // → 建房 100% 失败。必须用 optInt 保持数字类型。
                    val nid = o.optInt("id", -1).takeIf { it > 0 }
                    if (nid == null) null
                    else FrpNodeUi(nid, o.optString("name").ifBlank { nid.toString() })
                }
                state = state.copy(nodes = nodes)
            }
        }
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) reload()
    }

    FrpRoomsPage(
        state = state,
        onRefresh = { reload() },
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
            val afterPrimary: (Result<org.json.JSONObject>) -> Unit = {
                api.frpRoomStop(room.id) { _ ->
                    state = state.copy(busy = false, connectAddress = null)
                    reload()
                }
            }
            if (room.isHost) api.frpRoomClose(room.id, afterPrimary)
            else api.frpRoomLeave(room.id, afterPrimary)
        },
        onOpenConnect = { addr ->
            activity?.copyToClipboard(context.getString(R.string.multiplayer_address), addr)
        },
        // D5b：密码框的「粘贴」走 Rust 的 read_clipboard 命令
        // （它内部回调宿主读原生剪贴板）——绕开 Compose 输入框
        // 长按菜单在 WebView 混合壳里弹不出来的问题。
        // 读不到（空/失败）时回调 null，页面静默收场。
        onReadClipboard = { cb ->
            api.readClipboard { r ->
                cb(r.getOrNull())
            }
        },
    )
}

/**
 * 账号面板 + 数据加载。
 *
 * 没有账号就没法启动游戏，所以这个页面挡在「能用」与「不能用」之间。
 */
/**
 * 游玩统计页。
 *
 * 对应旧界面 `PlaytimeCard.vue`。
 * 不依赖网络也不依赖实例数据 —— 数据来自 accounts/instances 目录里的游玩记录。
 */
@Composable
private fun PlaytimeRoute(bridge: RustBridge, ready: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    var state by remember { mutableStateOf(PlaytimeUiState()) }

    fun reload() {
        state = state.copy(loading = true, error = null)
        api.playtimeStats { r ->
            r.fold(
                onSuccess = { o ->
                    // ⚠️ 字段是 camelCase（PlaytimeStats 带 rename_all = "camelCase"）
                    val byInstance = o.optJSONArray("byInstance")
                    val inst = (0 until (byInstance?.length() ?: 0)).mapNotNull { i ->
                        val e = byInstance?.optJSONObject(i) ?: return@mapNotNull null
                        InstancePlaytimeUi(
                            id = e.optString("id"),
                            name = e.optString("name").ifBlank { e.optString("id") },
                            seconds = e.optLong("seconds", 0L),
                            lastPlayed = e.optLong("lastPlayed", 0L),
                        )
                    }.sortedByDescending { it.seconds }

                    val byDayArr = o.optJSONArray("byDay")
                    val days = (0 until (byDayArr?.length() ?: 0)).mapNotNull { i ->
                        val e = byDayArr?.optJSONObject(i) ?: return@mapNotNull null
                        DailyPlaytimeUi(
                            day = e.optLong("day"),
                            seconds = e.optLong("seconds", 0L),
                        )
                    }.takeLast(30)

                    state = state.copy(
                        totalSeconds = o.optLong("totalSeconds", 0L),
                        byInstance = inst,
                        byDay = days,
                        loading = false,
                    )
                },
                onFailure = { e ->
                    state = state.copy(loading = false, error = context.getString(R.string.frp_stats_read_failed, e.message))
                },
            )
        }
    }

    LaunchedEffect(ready) {
        if (ready) reload()
    }

    PlaytimePage(state = state, onRefresh = { reload() })
}
@Composable
private fun AccountRoute(
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
private fun HomeRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    instances: List<InstanceUi>,
    tasks: List<TaskUi>,
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
    var news by remember { mutableStateOf<List<NewsItemUi>>(emptyList()) }
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
            bridge.invoke("fetch_news", "{}") { r ->
                r.onSuccess { news = parseNews(it) }
                // 失败静默：新闻不该影响首页可用性
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
private fun PluginsRoute(bridge: RustBridge, bridgeReady: Boolean) {
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

/** 皮肤页。
 *
 * 3D 预览降级成 2D 双视图（见 SkinRepo / SkinPage 的注释说明取舍）。
 */
@Composable
private fun SkinRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    activity: ComposeMainActivity? = null,
    /** 3D 预览渲染方式（compose=省电 / webview=流畅）。 */
    previewMode: String = "compose",
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { SkinRepo(api, context) }

    var state by remember { mutableStateOf(SkinUiState(loading = true)) }

    // 载入皮肤库
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        repo.listSkins { r ->
            state = r.fold(
                onSuccess = { list ->
                    val first = list.firstOrNull()
                    state.copy(skins = list, loading = false, selected = first)
                },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.skin_lib_read_failed, friendlyError(context, it))) },
            )
        }
        // 顺带读一下当前账号（用于「应用到账号」按钮）
        api.listAccounts { r ->
            r.onSuccess { arr ->
                val acc = arr.optJSONObject(0) ?: return@onSuccess
                val isOnline = acc.optString("type").contains("microsoft", ignoreCase = true)
                val accName = acc.optString("username").ifBlank { acc.optString("name") }
                state = state.copy(
                    accountUuid = acc.optString("uuid"),
                    accountName = accName,
                    accountOnline = isOnline,
                )
                // ⚠️ 2026-10-18 加（修「正版账号皮肤页永远显示未选择皮肤」）：
                // 正版账号的皮肤在 Mojang 服务器上，本地皮肤库是空的 ——
                // 界面只能显示「未选择皮肤」，用户以为皮肤功能坏了。
                // 现在进皮肤页时**自动按账号名拉一次皮肤**存进皮肤库，
                // 与「按正版玩家名获取」按钮同一条路，只是不用用户手填名字。
                // 静默失败（网络不通等），不打扰用户。
                if (isOnline && accName.isNotBlank() && state.skins.isEmpty()) {
                    repo.fetchPlayerSkin(accName) { fr ->
                        fr.onSuccess { ps ->
                            if (ps.dataUrl.isNotBlank()) {
                                repo.saveFromData("player-$accName-${System.currentTimeMillis()}", ps.dataUrl) { sr ->
                                    sr.onSuccess { savedJson ->
                                        // savedJson 是 Rust SkinEntry 的 JSON 串，取 filename 用于选中
                                        val savedFile = runCatching {
                                            org.json.JSONObject(savedJson).optString("filename")
                                        }.getOrNull().orEmpty()
                                        repo.listSkins { lr ->
                                            lr.onSuccess { list ->
                                                state = state.copy(
                                                    skins = list,
                                                    selected = list.firstOrNull { it.filename == savedFile }
                                                        ?: list.firstOrNull(),
                                                    imageDataUrl = ps.dataUrl,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 选中变化时加载图片
    LaunchedEffect(state.selected?.filename) {
        val f = state.selected?.filename ?: return@LaunchedEffect
        if (state.imageDataUrl.isNotBlank() && state.selected?.filename == f) return@LaunchedEffect
        repo.loadImage(f) { r ->
            r.onSuccess { state = state.copy(imageDataUrl = it) }
            r.onFailure { state = state.copy(error = context.getString(R.string.skin_image_read_failed, friendlyError(context, it))) }
        }
    }

    // ── 3D 预览（复用隐形 WebView 跑 skinview3d）────────────────────────
    //
    // 旧 UI 用 skinview3d 做 3D 预览（旋转 / 动画 / 纤细模型）。
    // Compose 没有 3D 引擎，所以复用那个承载 IPC 的隐形 WebView
    // 离屏渲染，再用 gl.readPixels 读回像素。
    //
    // 细节与"为什么不用 toDataURL"见 Skin3dHost / skin3d.js 的注释。
    val host3d = remember(bridge, activity) {
        activity?.let { Skin3dHost(bridge, it) }
    }
    var preview3d by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var preview3dRendering by remember { mutableStateOf(false) }
    var preview3dError by remember { mutableStateOf<String?>(null) }
    var preview3dAnim by remember { mutableStateOf("idle") }
    var preview3dSlim by remember { mutableStateOf(false) }
    // 皮肤删除的二次确认（删除不可恢复）
    var skinDeleteTarget by remember { mutableStateOf<String?>(null) }

    /** 触发一次 3D 渲染（皮肤 / 动画 / 模型 / 视角变化后调）。 */
    fun render3d(dataUrl: String) {
        val h = host3d ?: return
        if (dataUrl.isBlank()) {
            preview3d = null
            return
        }
        preview3dRendering = true
        preview3dError = null
        h.loadSkin(dataUrl, preview3dSlim) { r ->
            preview3dRendering = false
            r.fold(
                onSuccess = { bmp ->
                    preview3d = bmp.asImageBitmap()
                },
                onFailure = { e ->
                    preview3dError = context.getString(R.string.skin_3d_render_failed, e.message)
                    android.util.Log.w("SkinRoute", "3D 渲染失败", e)
                },
            )
        }
    }

    // 皮肤图片就绪后就渲染一次 3D
    LaunchedEffect(state.imageDataUrl, preview3dSlim) {
        if (state.imageDataUrl.isNotBlank()) render3d(state.imageDataUrl)
    }

    // ── 自动旋转：模型自转需要持续取帧 ────────────────────────────────
    //
    // ⚠️⚠️ 2026-10-16 修 —— **不能只依赖 skinview3d 的 autoRotate**。
    //
    // 它内部靠 `requestAnimationFrame` 循环累积角度：
    //   draw() { playerWrapper.rotation.y += clock.getDelta() * speed; ... }
    // 但我们跑在**不可见的 WebView** 里，实测 rAF 不推进 ——
    // 真机自检 `rotY=0.000`（开了也纹丝不动）。
    //
    // 所以改成自己驱动：每轮先 `stepAutoRotate(6°)` 推进角度，再取帧。
    //
    // ⚠️ 频率必须克制：每帧的 PNG base64（几十 KB）要穿过 `evaluateJavascript`，
    // 也就是走 binder。之前用 22KB 高频回传时被系统判为
    // "excessive binder traffic" 直接杀进程。
    // 150ms ≈ 6.7fps，6°/步 → 约 9 秒转一圈，流量只有 16ms 的 1/9。
    // ⚠️ 频率：从固定 150ms 改成**上一帧完成后立刻取下一帧**。
    //
    // 原来写死 `delay(150)`，但单帧实际只要 30–40ms —— 等于人为把
    // 上限压到 6.7fps。改成"等上一帧落地再发下一帧"后，实际帧率
    // 由设备能力决定（实测能到 ~20fps），而且不会积压请求。
    //
    // 保留一个**下限节流**（MIN_FRAME_MS）：没有它的话循环会空转，
    // 白白占 CPU 还制造不必要的 binder 流量。
    // 自动旋转的最小帧间隔（下限节流）。
    // 取值依据：单帧实测 30–40ms，留一点余量 → 45ms ≈ 22fps 上限。
    // 不设下限的话循环会空转（刚发完就立刻发下一个，白占 CPU 和 binder）。
    val MIN_FRAME_MS = 45L
    var preview3dAutoRotate by remember { mutableStateOf(false) }
    var perfTick by remember { mutableStateOf(0) }
    LaunchedEffect(preview3dAutoRotate, state.imageDataUrl) {
        if (!preview3dAutoRotate || state.imageDataUrl.isBlank()) return@LaunchedEffect
        while (true) {
            val t0 = android.os.SystemClock.uptimeMillis()
            // 等这一帧真正落地（用挂起而不是轮询，省 CPU）
            val done = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                host3d?.snapshot { r ->
                    r.onSuccess { preview3d = it.asImageBitmap() }
                    cont.resumeWith(Result.success(r.isSuccess))
                } ?: cont.resumeWith(Result.success(false))
            }
            val cost = android.os.SystemClock.uptimeMillis() - t0
            perfTick++
            if (perfTick % 20 == 0) {
                android.util.Log.i("SkinRoute", "自转帧耗时 ${cost}ms（约 ${if (cost > 0) 1000 / cost else 0}fps）")
                host3d?.logPerf()
            }
            // 只在**快于下限**时补一个 delay，避免空转
            if (cost < MIN_FRAME_MS) kotlinx.coroutines.delay(MIN_FRAME_MS - cost)
            // 先推进角度，下一轮取帧时就是新姿态
            host3d?.stepAutoRotate(3f)
            if (!done) kotlinx.coroutines.delay(50)   // 失败时缓一下，别死循环
        }
    }

    // ── 披风列表（仅正版账号）────────────────────────────────────────
    var capes by remember { mutableStateOf<List<CapeUi>>(emptyList()) }
    var capesLoading by remember { mutableStateOf(false) }
    var selectedCapeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.accountUuid, state.accountOnline) {
        val uuid = state.accountUuid
        if (!state.accountOnline || uuid.isBlank()) {
            capes = emptyList()
            return@LaunchedEffect
        }
        capesLoading = true
        api.fetchPlayerCapes(uuid) { r ->
            capesLoading = false
            r.fold(
                onSuccess = { arr ->
                    val list = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        CapeUi(
                            id = o.optString("id"),
                            name = o.optString("name"),
                            dataUrl = o.optString("dataUrl").ifBlank { o.optString("data_url") },
                            active = o.optBoolean("active", false),
                        )
                    }
                    capes = list
                    // 默认选中账号当前正穿着的那件，并让 3D 预览穿上
                    val cur = list.firstOrNull { it.active }
                    selectedCapeId = cur?.id
                    if (cur != null && cur.dataUrl.isNotBlank()) host3d?.loadCape(cur.dataUrl)
                },
                onFailure = { e ->
                    capes = emptyList()
                    android.util.Log.w("SkinRoute", "读取披风失败: ${e.message}")
                },
            )
        }
    }

    SkinPage(
        state = state,
        preview3d = preview3d,
        preview3dRendering = preview3dRendering,
        preview3dError = preview3dError,
        preview3dAnimation = preview3dAnim,
        preview3dSlim = preview3dSlim,
        preview3dAutoRotate = preview3dAutoRotate,
        // 渲染方式来自全局设置（外观页可切）
        previewRenderMode = previewMode,
        // webview 模式：把预览框的位置交给 Activity，让它调整 wry WebView
        // 的位置/尺寸 —— 那是「直接渲染」模式的实际画面来源。
        // compose 模式回调 null（收回隐形）。
        onPreviewBoxChanged = { visible, x, y, w, h ->
            if (visible == true) {
                activity?.setSkinPreviewVisible(true, x, y, w, h)
            } else {
                activity?.setSkinPreviewVisible(false, 0, 0, 0, 0)
            }
        },
        onPreview3dAutoRotate = { on ->
            preview3dAutoRotate = on
            host3d?.setAutoRotate(on)
        },
        onPreview3dDrag = { dx, dy ->
            // 拖拽：交给 JS 的 OrbitControls，然后重取一帧
            host3d?.orbit(dx, dy) {
                if (state.imageDataUrl.isNotBlank()) {
                    host3d.snapshot { r ->
                        r.onSuccess { preview3d = it.asImageBitmap() }
                    }
                }
            }
        },
        capes = capes,
        capesLoading = capesLoading,
        selectedCapeId = selectedCapeId,
        accountOnline = state.accountOnline,
        onSelectCape = { cape ->
            selectedCapeId = cape?.id
            // 本地预览立刻换装
            host3d?.loadCape(cape?.dataUrl?.takeIf { it.isNotBlank() })
            // 正版账号还要同步到 Mojang
            if (state.accountUuid.isNotBlank()) {
                api.applyCapeToAccount(state.accountUuid, cape?.id) { r ->
                    r.onFailure { e ->
                        state = state.copy(error = context.getString(R.string.skin_cape_apply_failed, e.message))
                    }
                }
            }
            if (state.imageDataUrl.isNotBlank()) {
                host3d?.snapshot { r ->
                    r.onSuccess { preview3d = it.asImageBitmap() }
                }
            }
        },
        onPreview3dAnimation = { id ->
            preview3dAnim = id
            host3d?.setAnimation(id)
            // 动画切换后要重取一帧，否则画面还是旧姿态
            if (state.imageDataUrl.isNotBlank()) {
                host3d?.snapshot { r ->
                    r.onSuccess { preview3d = it.asImageBitmap() }
                }
            }
        },
        onPreview3dModel = { slim ->
            preview3dSlim = slim
            // 模型切换走 loadSkin（需要重建几何体），LaunchedEffect 会触发
        },
        onPreview3dReset = {
            host3d?.reset {
                if (state.imageDataUrl.isNotBlank()) {
                    host3d.snapshot { r ->
                        r.onSuccess { preview3d = it.asImageBitmap() }
                    }
                }
            }
        },
        onSelect = { s -> state = state.copy(selected = s, imageDataUrl = "") },
        onApply = { s ->
            state = state.copy(applying = s.filename)
            // ⚠️ 把当前选中的模型（经典/纤细）一起传下去 ——
            // Rust 的 apply_skin_offline 需要 variant 才能把皮肤记成对应模型，
            // 否则永远按 classic 存，纤细模型下的皮肤会显示错位。
            repo.applyToAccount(
                uuid = state.accountUuid,
                filename = s.filename,
                online = state.accountOnline,
                slim = preview3dSlim,
            ) { r ->
                state = r.fold(
                    onSuccess = { state.copy(applying = "") },
                    onFailure = { state.copy(applying = "", error = context.getString(R.string.skin_apply_failed, friendlyError(context, it))) },
                )
            }
        },
        onDelete = { s ->
            // ⚠️⚠️ 2026-10-16 修 —— 这里原来是错的。
            //
            // 我此前写的是「暂不支持在应用内删除皮肤」，注释还声称
            // 「api.ts 里确实没有对应命令（核对过）」。**那个结论是错的**：
            // `delete_skin` 一直都在（skins.rs:194 / commands.rs:944 /
            // lib.rs:289 注册），我只是只看了前端 api.ts 就下结论。
            //
            // **"前端没包"不等于"后端没有"** —— 这类判断必须去 Rust 侧确认。
            //
            // ⚠️ 2026-10-17：删除前必须确认 —— 原来点一下垃圾桶图标就**直接删了**，
            // 皮肤文件不可恢复（用户自己导入的 PNG 会彻底丢失）。
            // 这里只记下待删目标，真正的删除在下面的确认框里做。
            skinDeleteTarget = s.filename
        },
        onImport = {
            // 导入皮肤：弹图片选择器 → 读成 data URL → save_skin_from_data。
            // 皮肤是 64×64 的 PNG，用 base64 传（Rust 侧接口就是收 data URL）。
            val act = activity
            if (act == null) {
                state = state.copy(error = context.getString(R.string.file_picker_unsupported))
                return@SkinPage
            }
            act.pickImage { path ->
                if (path == null) return@pickImage
                // 读文件并转成 data URL
                val dataUrl = runCatching {
                    val f = java.io.File(path)
                    if (!f.isFile) return@runCatching null
                    // 皮肤都很小（64×64 ≈ 几 KB），一次读完没问题；
                    // 加个上限兜底，避免误选大图把内存打爆
                    if (f.length() > 2 * 1024 * 1024) return@runCatching null
                    val bytes = f.readBytes()
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    "data:image/png;base64,$b64"
                }.getOrNull()

                if (dataUrl == null) {
                    state = state.copy(error = context.getString(R.string.skin_invalid_png))
                    return@pickImage
                }
                repo.saveFromData(java.io.File(path).name, dataUrl) { r ->
                    r.fold(
                        onSuccess = {
                            // 重新载入列表，让新皮肤出现
                            repo.listSkins { lr ->
                                lr.onSuccess { state = state.copy(skins = it) }
                            }
                        },
                        onFailure = { e -> state = state.copy(error = context.getString(R.string.skin_import_failed, e.message)) },
                    )
                }
            }
        },
        onFetchPlayer = { name ->
            // 按正版玩家名从 Mojang 拉皮肤 → 存进皮肤库 → 自动选中预览。
            //
            // 对应旧 UI `SkinView.vue` 的 fetchPlayer()。
            state = state.copy(fetchingPlayer = name, fetchMessage = null)
            repo.fetchPlayerSkin(name) { r ->
                r.fold(
                    onSuccess = { ps ->
                        if (ps.dataUrl.isBlank()) {
                            state = state.copy(
                                fetchingPlayer = "",
                                fetchMessage = context.getString(R.string.skin_fetch_no_image),
                            )
                            return@fold
                        }
                        // 存进皮肤库（名字带时间戳避免覆盖同名的）
                        repo.saveFromData("player-$name-${System.currentTimeMillis()}", ps.dataUrl) { sr ->
                            sr.fold(
                                onSuccess = {
                                    state = state.copy(
                                        fetchingPlayer = "",
                                        fetchMessage = context.getString(R.string.skin_fetched, name, ps.model),
                                    )
                                    // 重新拉列表，再选中刚存的那张
                                    repo.listSkins { lr ->
                                        lr.onSuccess { list ->
                                            state = state.copy(skins = list)
                                        }
                                    }
                                    state = state.copy(imageDataUrl = ps.dataUrl)
                                },
                                onFailure = { e ->
                                    state = state.copy(
                                        fetchingPlayer = "",
                                        fetchMessage = context.getString(R.string.skin_save_failed, e.message),
                                    )
                                },
                            )
                        }
                    },
                    onFailure = { e ->
                        state = state.copy(
                            fetchingPlayer = "",
                            fetchMessage = context.getString(R.string.skin_fetch_failed, e.message),
                        )
                    },
                )
            }
        },
    )

    // 皮肤删除确认（不可恢复：用户自己导入的 PNG 会彻底丢失）
    skinDeleteTarget?.let { target ->
        com.zhayi.qookix.compose.pages.settings.ConfirmDialog(
            title = context.getString(R.string.skin_delete_title),
                message = context.getString(R.string.skin_delete_msg, target),
            confirmText = context.getString(R.string.common_delete),
            danger = true,
            onDismiss = { skinDeleteTarget = null },
            onConfirm = {
                skinDeleteTarget = null
                api.deleteSkin(target) { r ->
                    r.fold(
                        onSuccess = {
                            repo.listSkins { rr ->
                                rr.onSuccess { list ->
                                    state = state.copy(
                                        skins = list,
                                        selected = null,
                                        imageDataUrl = "",
                                        error = null,
                                    )
                                    preview3d = null
                                }
                            }
                        },
                        onFailure = { e ->
                            state = state.copy(error = context.getString(R.string.skin_delete_failed, friendlyError(context, e)))
                        },
                    )
                }
            },
        )
    }
}

/** 设置页（8 个子分组）。 */
@Composable
private fun SettingsRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    openUrl: ((String) -> Unit)? = null,
    activity: ComposeMainActivity? = null,
    /** 外观变更回传给根节点（见 PageHost 的 onAppearanceChanged 注释）。 */
    onAppearanceChanged: (String, String, Int, String) -> Unit = { _, _, _, _ -> },
    /**
     * 自更新状态 —— **由 PageHost 持有**（2026-10-18 改）。
     *
     * 原来放在这个函数内部的 `remember` 里：切到别的设置 tab 再回来，
     * 状态就被清空 —— 用户刚检查出「有新版本 1.8.0」，
     * 切个 tab 回来又变回「当前版本 1.7.0」，得重新检查一次。
     */
    updateState: AppUpdateUiState = AppUpdateUiState(),
    onUpdateStateChange: (AppUpdateUiState) -> Unit = {},
    onSidebarWidthChanged: (Int) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { SettingsRepo(api) }

    var tab by remember { mutableStateOf(SettingsTab.General) }
    var state by remember { mutableStateOf(SettingsUiState(loading = true)) }
    var showLicenses by remember { mutableStateOf(false) }
    // 自更新状态由 PageHost 传入（见签名注释）
    // 社区贡献者名单 —— 从 APK 的 assets/contributors.json 读，只读一次
    val contributors = remember { loadContributors(context) }
    // 清翻译缓存（返回释放字节数）
    var clearingTranslation by remember { mutableStateOf(false) }
    var clearTranslationHint by remember { mutableStateOf<String?>(null) }
    // 翻译 API 连通性测试（D1）
    var testingTranslate by remember { mutableStateOf(false) }
    var translateTestHint by remember { mutableStateOf<String?>(null) }

    // 设备内存探测结果（游戏子页 + 常规页的「设备可用内存」共用）
    var memoryInfo by remember { mutableStateOf<MemoryInfoUi?>(null) }

    // D8：设备可用内存（MB）。0 = 还没探测到。
    //
    // 用的是 `device_available_memory_mb`（后端另有同名的 `deviceAvailableMemoryMb` 包装）——
    // 它读 /proc/meminfo 的 MemAvailable，只返回「真正能分配的量」，
    // 而 auto_detect_memory 还要算推荐区间。两者数据同源，展示用前者即可。
    var deviceAvailableMb by remember { mutableStateOf(0L) }

    /** 探测设备可用内存（D8）。失败不打扰 —— 行里显示「未知」。 */
    fun detectDeviceMemory() {
        api.deviceAvailableMemoryMb { r ->
            r.onSuccess { mb ->
                deviceAvailableMb = mb
            }
        }
    }

    /** 探测设备内存：游戏子页的「探测」按钮和常规页的刷新都用这一份。 */
    fun detectMemory() {
        api.autoDetectMemory { r ->
            r.fold(
                onSuccess = { o ->
                    memoryInfo = MemoryInfoUi(
                        totalMb = o.optInt("total_mb", 0),
                        usedMb = o.optInt("used_mb", 0),
                        availableMb = o.optInt("available_mb", 0),
                        maxMb = o.optInt("max_mb", 2048),
                        minMb = o.optInt("min_mb", 512),
                    )
                },
                onFailure = { e ->
                    state = state.copy(error = context.getString(R.string.memory_probe_failed, e.message))
                },
            )
        }
    }

    // 进设置页后台探一次内存（D8）：常规页常驻显示「可用内存」，
    // 开服的人据此判断能给 JVM 分多少。失败不打扰（行里显示「未知」）。
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        if (memoryInfo == null) detectMemory()
        if (deviceAvailableMb <= 0L) detectDeviceMemory()
    }

    // 渲染器（存在 Pojav 偏好里，不在 settings.json）
    var renderer by remember { mutableStateOf("opengles2") }

    // 进设置页读一次 Pojav 偏好里的渲染器（它不在 settings.json 里，
    // 所以不能跟着 settings 一起加载）
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        api.getPojavPrefs { r ->
            r.onSuccess { o ->
                val v = o.optString("renderer")
                if (v.isNotBlank()) renderer = v
            }
            // 失败就用默认值（GL4ES）—— 它一定随包可用
        }
    }

    // 更新检查的状态（结果文本用对话框展示）
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }

    // 镜像测速进行中
    var mirrorTesting by remember { mutableStateOf(false) }
    // 结果弹层的内容是测速结果还是更新检查（决定标题）
    var resultIsMirror by remember { mutableStateOf(false) }

    // 代理测试状态
    var proxyTesting by remember { mutableStateOf(false) }
    var proxyDetecting by remember { mutableStateOf(false) }
    var proxyResult by remember { mutableStateOf<String?>(null) }
    var proxyError by remember { mutableStateOf<String?>(null) }
    val appVersion = currentAppVersion(androidx.compose.ui.platform.LocalContext.current)

    // 外部改了设置（如「存储」子页切换游戏目录）后，靠这个 key 触发重拉
    var settingsReloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(bridgeReady, settingsReloadKey) {
        if (!bridgeReady) return@LaunchedEffect
        repo.load { r ->
            state = r.fold(
                onSuccess = {
                    // ⚠️ 3D 预览渲染方式**不在 Rust 的 settings 里** ——
                    // `Settings` 是强类型结构体（models.rs L355）没有这个字段，
                    // 写进 settings.json 也读不回来。它存在 Android 的
                    // SharedPreferences 里，所以这里单独覆盖一次，
                    // 否则设置页的 chip 永远显示默认的「省电」。
                    it.copy(
                        loading = false,
                        previewRenderMode = activity?.previewRenderModePref() ?: "compose",
                    )
                },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.settings_read_failed, friendlyError(context, it))) },
            )
        }
    }

    /** 改一项：先乐观更新 UI，再发 patch 给 Rust（失败则回滚提示）。 */
    fun apply(patch: SettingsPatch) {
        // 乐观更新：设置项点击后立刻响应，不等 IPC 往返。
        // 不这么做的话，滑杆拖动会一顿一顿（每次都要等一个 IPC 往返）。
        state = when (patch) {
            // 外观
            is SettingsPatch.Theme -> state.copy(theme = patch.value)
            is SettingsPatch.ThemeColor -> state.copy(themeColor = patch.value)
            is SettingsPatch.UiScale -> state.copy(uiScale = patch.value)
            is SettingsPatch.SidebarWidth -> state.copy(sidebarWidth = patch.value)
            is SettingsPatch.PreviewRenderMode -> state.copy(previewRenderMode = patch.value)
            is SettingsPatch.ShowHomeDownloads -> state.copy(showHomeDownloads = patch.value)
            is SettingsPatch.ShowHomeRecent -> state.copy(showHomeRecent = patch.value)
            is SettingsPatch.ShowHomeStats -> state.copy(showHomeStats = patch.value)
            is SettingsPatch.ShowNews -> state.copy(showNews = patch.value)

            // 常规
            is SettingsPatch.CloseBehavior -> state.copy(closeBehavior = patch.value)
            is SettingsPatch.KeepOpen -> state.copy(keepOpen = patch.value)
            is SettingsPatch.AutoLaunch -> state.copy(autoLaunch = patch.value)
            is SettingsPatch.Orientation -> state.copy(orientation = patch.value)
            is SettingsPatch.Mirror -> state.copy(mirror = patch.value)

            // 下载
            is SettingsPatch.DownloadThreads -> state.copy(downloadThreads = patch.value)
            is SettingsPatch.DownloadChunkThreads -> state.copy(downloadChunkThreads = patch.value)
            is SettingsPatch.AutoUpdate -> state.copy(autoUpdate = patch.value)
            is SettingsPatch.UpdateSource -> state.copy(updateSource = patch.value)

            // 游戏
            is SettingsPatch.MemoryMode -> state.copy(memoryMode = patch.value)
            is SettingsPatch.MaxMemory -> state.copy(maxMemoryMb = patch.value)
            is SettingsPatch.MinMemory -> state.copy(minMemoryMb = patch.value)

            // 启动参数
            is SettingsPatch.JvmArgs -> state.copy(jvmArgs = patch.value)
            is SettingsPatch.GameArgs -> state.copy(gameArgs = patch.value)
            is SettingsPatch.JavaPath -> state.copy(javaPath = patch.value)

            // 背景图（空串 = 清除）
            is SettingsPatch.BackgroundImage -> state.copy(backgroundImage = patch.value)

            // 内容服务
            is SettingsPatch.CurseforgeKey -> state.copy(curseforgeKey = patch.value)
            is SettingsPatch.TranslateProvider -> state.copy(translateProvider = patch.value)
            is SettingsPatch.AutoTranslate -> state.copy(autoTranslate = patch.value)
            // 网络代理
            is SettingsPatch.ProxyMode -> state.copy(proxyMode = patch.value)
            is SettingsPatch.ProxyAddress -> state.copy(proxyAddress = patch.value)

            is SettingsPatch.TranslateApiBase -> state.copy(translateApiBase = patch.value)
            is SettingsPatch.TranslateApiKey -> state.copy(translateApiKey = patch.value)
            is SettingsPatch.TranslateApiModel -> state.copy(translateApiModel = patch.value)
        }

        // ── 外观变更 → 立刻回传根节点，让 QookiXTheme 重新生成配色 ──────
        //
        // 不做这一步的话，用户改完主题要重启应用才生效 —— 那不算"能用"。
        // 只在改到外观项时才回调（其他设置项与主题无关，不必触发重组）。
        when (patch) {
            is SettingsPatch.Theme ->
                onAppearanceChanged(patch.value, state.themeColor, state.uiScale, state.previewRenderMode)
            is SettingsPatch.ThemeColor ->
                onAppearanceChanged(state.theme, patch.value, state.uiScale, state.previewRenderMode)
            is SettingsPatch.UiScale ->
                onAppearanceChanged(state.theme, state.themeColor, patch.value, state.previewRenderMode)
            // 3D 预览渲染方式：主题三件套没变，只回传新模式即可
            is SettingsPatch.PreviewRenderMode -> {
                onAppearanceChanged(state.theme, state.themeColor, state.uiScale, patch.value)
                // 同时落到 Android 自己的 SharedPreferences —— Rust 的 `Settings`
                // 是强类型结构体（models.rs L355），没有这个字段，写进
                // settings.json 也读不回来。详见 ComposeMainActivity 里的注释。
                activity?.setPreviewRenderModePref(patch.value)
            }
            // 侧边栏宽度：不影响主题，只回传数值给根节点
            is SettingsPatch.SidebarWidth -> onSidebarWidthChanged(patch.value)

            // ── 屏幕方向：除了写设置，还要**立刻下发到原生层** ──────────
            //
            // ⚠️ 2026-10-15 修（逐个核对设置项时发现）：
            // `set_orientation` 这条命令在 API 层一直有，但**从没被调用过** ——
            // 用户改方向后要**重启应用**才生效。
            //
            // 旧 UI 是即时的（`SettingsView.vue` L229 的注释原话：
            // 「立即下发到原生层，无需重启应用」）。这是迁移时漏掉的一步。
            is SettingsPatch.Orientation -> api.setOrientation(patch.value) { }

            else -> Unit
        }

        repo.update(patch) { r ->
            r.onFailure { e ->
                state = state.copy(error = context.getString(R.string.settings_save_failed, e.message))
                // 乐观更新失败：回读一次真实设置，把界面上那个「假的新值」冲掉。
                // 只弹一句错误但界面还显示新值，用户会以为已经改好了（审查发现）。
                settingsReloadKey++
            }
        }
    }

    SettingsPage(
        current = tab,
        onTabChange = { tab = it },
        content = { t ->
            when (t) {
                SettingsTab.General -> GeneralPane(
                    state = state,
                    onChange = { apply(it) },
                    // D8：优先用 device_available_memory_mb 的结果，
                    // 探不到时退回 auto_detect_memory 的 available_mb（同源）。
                    availableMemoryMb = if (deviceAvailableMb > 0L) {
                        deviceAvailableMb.toInt()
                    } else {
                        memoryInfo?.availableMb ?: 0
                    },
                    onRefreshMemory = {
                        detectMemory()
                        detectDeviceMemory()
                    },
                )
                SettingsTab.Appearance -> AppearancePane(
                    state = state,
                    onChange = { apply(it) },
                    onPickBackground = activity?.let { a ->
                        {
                            // 弹系统图片选择器 → 拿到路径 → 交给 Rust 导入。
                            // 用 two-step（选完回调）而不是同步等待：
                            // Android 的文件选择天生异步，JNI 侧不能阻塞。
                            a.pickImage { path ->
                                if (path != null) {
                                    api.importBackgroundImage(path) { r ->
                                        r.fold(
                                            onSuccess = {
                                                // 导入成功后 Rust 会把新路径写进设置，
                                                // 这里乐观更新，界面立刻反映
                                                apply(SettingsPatch.BackgroundImage(path))
                                            },
                                            onFailure = { e ->
                                                state = state.copy(error = context.getString(R.string.appearance_bg_import_failed, e.message))
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    },
                )
                SettingsTab.Download -> DownloadPane(
                    state = state,
                    onChange = { apply(it) },
                    onCheckUpdate = {
                        checkingUpdate = true
                        resultIsMirror = false
                        updateResult = context.getString(R.string.update_checking)
                        api.checkForUpdate { r ->
                            checkingUpdate = false
                            updateResult = r.fold(
                                onSuccess = { o ->
                                    // 字段名可能是最新的（hasUpdate/latestVersion），
                                    // 也可能是「没有更新」的空对象，两种都兜住
                                    val has = o.optBoolean("hasUpdate", false) ||
                                        o.optBoolean("available", false)
                                    if (has) {
                                        val ver = o.optString("latestVersion")
                                            .ifBlank { o.optString("version") }
                                        val notes = o.optString("notes").ifBlank { o.optString("body") }
                                        buildString {
                                            append(context.getString(R.string.update_new_version, ver)).append("\n\n")
                                            if (notes.isNotBlank()) append(notes)
                                        }
                                    } else {
                                        context.getString(R.string.update_latest)
                                    }
                                },
                                onFailure = { e ->
                                    // 网络不通在这里很常见（本项目设备上 DNS 就被劫持过），
                                    // 所以把原因说清楚，而不是笼统的「检查失败」
                    context.getString(R.string.update_check_failed, e.message)
                                },
                            )
                        }
                    },
                    updateResult = updateResult,
                    onDismissUpdateResult = { updateResult = null },
                    onTestMirrors = {
                        // 对每个镜像发一次请求，比首字节耗时。
                        //
                        // ⚠️ 用返回里的 `ok` 字段判断是否真连通 ——
                        // 只看耗时会把「连接失败」误判成「很快」。
                        mirrorTesting = true
                        resultIsMirror = true
                        api.listMirrors { lr ->
                            val list = lr.getOrNull() ?: org.json.JSONArray()
                            val entries = (0 until list.length()).mapNotNull { i ->
                                val o = list.optJSONObject(i) ?: return@mapNotNull null
                                o.optString("label").ifBlank { o.optString("id") } to o.optString("base")
                            }
                            if (entries.isEmpty()) {
                                mirrorTesting = false
                                updateResult = context.getString(R.string.speedtest_no_mirrors)
                                return@listMirrors
                            }
                            // 并发测所有镜像（串行的话总耗时 = 各源之和，太慢）
                            val results = java.util.Collections.synchronizedList(
                                mutableListOf<Pair<String, String>>(),
                            )
                            var pending = entries.size
                            entries.forEach { (label, base) ->
                                api.testMirror(base) { tr ->
                                    val line = tr.fold(
                                        onSuccess = { o ->
                                            if (o.optBoolean("ok", false)) {
                                                "$label：${o.optInt("ms", 0)} ms"
                                            } else {
                                                context.getString(R.string.speedtest_connect_failed, label)
                                            }
                                        },
                                        onFailure = { e -> context.getString(R.string.speedtest_failed, label, e.message) },
                                    )
                                    results.add(label to line)
                                    if (--pending == 0) {
                                        mirrorTesting = false
                                        updateResult = buildString {
                                            append(context.getString(R.string.speedtest_result_header))
                                            append(results.joinToString("\n") { it.second })
                                            append(context.getString(R.string.speedtest_footer))
                                            append(context.getString(R.string.speedtest_official_note))
                                            append(context.getString(R.string.speedtest_bmclapi_note))
                                        }
                                    }
                                }
                            }
                        }
                    },
                    updateResultTitle = if (resultIsMirror) context.getString(R.string.speedtest_title) else context.getString(R.string.update_check_title),
                    mirrorTesting = mirrorTesting,
                    // 网络代理
                    proxyMode = state.proxyMode,
                    proxyAddress = state.proxyAddress,
                    proxyDetecting = proxyDetecting,
                    proxyTesting = proxyTesting,
                    proxyResult = proxyResult,
                    proxyError = proxyError,
                    onProxyModeChange = { m ->
                        resultIsMirror = false
                        proxyResult = null
                        proxyError = null
                        apply(SettingsPatch.ProxyMode(m))
                    },
                    onProxyAddressChange = { a ->
                        // 与旧实现一致：手动改地址即视为切到自定义模式
                        // （否则用户填了地址但模式还是「跟随系统」，填了也不生效）
                        if (state.proxyMode != "custom") apply(SettingsPatch.ProxyMode("custom"))
                        apply(SettingsPatch.ProxyAddress(a))
                    },
                    onProxyDetect = {
                        proxyDetecting = true
                        proxyResult = null
                        proxyError = null
                        api.detectSystemProxy { r ->
                            proxyDetecting = false
                            r.fold(
                                onSuccess = { addr ->
                                    proxyResult = if (addr.isBlank() || addr == "null") {
                                        context.getString(R.string.proxy_none_configured)
                                    } else {
                                        context.getString(R.string.proxy_system, addr)
                                    }
                                },
                                onFailure = { e -> proxyError = context.getString(R.string.proxy_probe_failed, e.message) },
                            )
                        }
                    },
                    onProxyTest = {
                        // ⚠️ 自定义模式必须填地址 —— 否则后端会**退化为直连而误报成功**
                        // （旧实现的注释里明确记了这个坑）
                        if (state.proxyMode == "custom" && state.proxyAddress.isBlank()) {
                            proxyError = context.getString(R.string.proxy_custom_needs_address)
                        } else {
                            proxyTesting = true
                            proxyResult = null
                            proxyError = null
                            api.testProxy(
                                state.proxyMode,
                                if (state.proxyMode == "custom") state.proxyAddress else null,
                            ) { r ->
                                proxyTesting = false
                                r.fold(
                                    onSuccess = { o ->
                                        proxyResult = if (o.optBoolean("ok", false)) {
                                            context.getString(R.string.proxy_available, o.optInt("ms", 0))
                                        } else {
                                            context.getString(R.string.proxy_unreachable)
                                        }
                                    },
                                    onFailure = { e -> proxyError = context.getString(R.string.proxy_test_failed, e.message) },
                                )
                            }
                        }
                    },
                    mirrorHint = if (mirrorTesting) context.getString(R.string.speedtest_running) else context.getString(R.string.speedtest_desc),
                )
                SettingsTab.Game -> GamePane(
                    state = state,
            renderer = renderer,
            onRendererChange = { r ->
                renderer = r
                // 渲染器存 Pojav 偏好（不是 settings.json）——旧 UI 也走 setPojavPrefs
                api.setPojavPrefs(org.json.JSONObject().put("renderer", r)) { res ->
                    res.onFailure { updateResult = context.getString(R.string.renderer_save_failed, friendlyError(context, it)) }
                }
            },
                    onChange = { apply(it) },
                    onDetectMemory = { detectMemory() },
                    memoryInfo = memoryInfo,
                    onDismissMemoryInfo = { memoryInfo = null },
                )
                SettingsTab.Storage -> StoragePane(
                    state = state,
                    onChange = { apply(it) },
                    bridge = bridge,
                    onReloadSettings = { settingsReloadKey++ },
                )
                SettingsTab.ContentServices -> ContentServicesPane(
                    state = state,
                    onChange = { apply(it) },
                    onOpenPlugins = { tab = SettingsTab.Plugins },
                    onClearTranslationCache = {
                        if (clearingTranslation) return@ContentServicesPane
                        clearingTranslation = true
                        clearTranslationHint = null
                        // 传 null = 清全部翻译服务（不挑服务商）；
                        // Rust 返回**释放的字节数**（u64）。
                        api.clearTranslationCache(null) { r ->
                            clearingTranslation = false
                            r.fold(
                                onSuccess = { s ->
                                    val bytes = s.trim().toLongOrNull() ?: 0L
                                    clearTranslationHint = if (bytes > 0) {
                                        context.getString(R.string.cache_cleared, fmtBytes(bytes))
                                    } else {
                                        context.getString(R.string.translation_cache_empty)
                                    }
                                },
                                onFailure = { e ->
                                    clearTranslationHint = context.getString(R.string.cache_clear_failed, e.message)
                                },
                            )
                        }
                    },
                    clearingTranslation = clearingTranslation,
                    clearTranslationHint = clearTranslationHint,
                    onTestTranslateApi = {
                        if (testingTranslate) return@ContentServicesPane
                        testingTranslate = true
                        translateTestHint = null
                        api.testTranslateApi(
                            base = state.translateApiBase,
                            key = state.translateApiKey,
                            model = state.translateApiModel,
                        ) { r ->
                            testingTranslate = false
                            r.fold(
                                onSuccess = { o ->
                                    // 容错：不同版本可能给 message / error / detail
                                    val ok = o.optBoolean("ok", false)
                                    val msg = sequenceOf("message", "error", "detail", "result")
                                        .map { o.optString(it) }
                                        .firstOrNull { it.isNotBlank() }
                                    translateTestHint = if (ok) {
                                        context.getString(R.string.content_services_test_ok)
                                    } else {
                                        context.getString(
                                            R.string.content_services_test_failed,
                                            msg ?: context.getString(R.string.server_detail_unknown_error),
                                        )
                                    }
                                },
                                onFailure = { e ->
                                    translateTestHint = context.getString(
                                        R.string.content_services_test_failed,
                                        e.message ?: "",
                                    )
                                },
                            )
                        }
                    },
                    testingTranslate = testingTranslate,
                    translateTestHint = translateTestHint,
                )
                SettingsTab.About -> AboutPane(
                    state = state,
                    appVersion = appVersion,
                    onOpenLicenses = { showLicenses = true },
                    // ⚠️ 仓库地址以 git remote 为准：
                    //   https://github.com/QookiX/QookiX-Launcher-Android
                    // 我原先按「weimosheng/QookiX-Launcher」写（那是桌面版仓库名，
                    // 从注释里看到的），但本仓库的 remote 是上面这个。
                    onOpenProject = { openUrl?.invoke("https://github.com/QookiX/QookiX-Launcher-Android") },
                    // 开源依赖的仓库/协议链接（2026-10-17 补全清单后需要）
                    onOpenUrl = { url -> openUrl?.invoke(url) },
                    onOpenDesktopRepo = {
                        openUrl?.invoke("https://github.com/weimosheng/QookiX-Launcher")
                    },
                    // 检查更新（2026-10-18 补回：Rust updater 一直都在，只是前端没接）
                    updateState = updateState,
                    onCheckUpdate = {
                        onUpdateStateChange(updateState.copy(checking = true, error = null))
                        api.checkForUpdate { r ->
                            r.fold(
                                onSuccess = { o ->
                                    onUpdateStateChange(updateState.copy(
                                        checking = false,
                                        available = o.optBoolean("available"),
                                        latestVersion = o.optString("version").takeIf { it.isNotBlank() },
                                        notes = o.optString("notes").takeIf { it.isNotBlank() },
                                        downloadedPath = o.optString("downloaded_path").takeIf { it.isNotBlank() },
                                    ))
                                },
                                onFailure = { e ->
                                    onUpdateStateChange(updateState.copy(
                                        checking = false,
                                        error = context.getString(R.string.settings_read_failed, e.message),
                                    ))
                                },
                            )
                        }
                    },
                    onDownloadUpdate = {
                        onUpdateStateChange(updateState.copy(downloading = true, error = null))
                        // 把版本号带过去 —— 后端会校验与「检查时记下的版本」一致，
                        // 防止中途又检查了别的版本导致下错（2026-10-18 修）
                        api.downloadUpdate(updateState.latestVersion.orEmpty()) { r ->
                            r.fold(
                                onSuccess = { path ->
                                    onUpdateStateChange(updateState.copy(downloading = false, downloadedPath = path))
                                },
                                onFailure = { e ->
                                    onUpdateStateChange(updateState.copy(
                                        downloading = false,
                                        error = context.getString(R.string.settings_read_failed, e.message),
                                    ))
                                },
                            )
                        }
                    },
                    // 安装结果必须冒泡：签名校验不过时原生侧会**删包并拒绝安装**，
                    // 这里把空回调改成把原因写进 updateState.error ——
                    // 否则用户点「安装」看着像没反应（正是「失败仍提示成功」那类坑）。
                    onInstallUpdate = { path ->
                        api.installUpdate(path) { r ->
                            r.onFailure { e ->
                                onUpdateStateChange(
                                    updateState.copy(
                                        error = context.getString(
                                            R.string.about_update_install_failed,
                                            friendlyError(context, e),
                                        ),
                                    ),
                                )
                            }
                        }
                    },
                    onToggleAutoCheck = { enabled ->
                        onUpdateStateChange(updateState.copy(autoCheck = enabled))
                    },
                    // 社区贡献者名单（assets/contributors.json，2026-10-18）
                    contributors = contributors,
                )
                SettingsTab.Plugins -> PluginsRoute(
                    bridge = bridge,
                    bridgeReady = bridgeReady,
                )
            }
        },
    )

    // 第三方许可证详情。
    //
    // ⚠️ 这不是装饰 —— GPL-3.0 要求分发时提供完整许可文本与第三方声明。
    // 尤其是 Terracotta（AGPL-3.0）：它之所以不传染本项目，靠的是
    // 「独立进程 + localhost HTTP」这个架构决策，声明里必须写清楚。
    if (showLicenses) {
        InfoDialog(
            title = context.getString(R.string.licenses_title),
            body = THIRD_PARTY_LICENSES,
            onDismiss = { showLicenses = false },
        )
    }
}

/** 第三方组件与许可证声明（GPL 合规要求，不可省略）。 */
private const val THIRD_PARTY_LICENSES = """QookiX Launcher Android 本体
  许可证：GPL-3.0
  完整文本：https://www.gnu.org/licenses/gpl-3.0.html

PojavLauncher（部分代码与运行库）
  许可证：LGPL-3.0
  说明：以独立动态库 libpojavexec.so 形式分发，未静态链接。

Terracotta（联机服务）
  许可证：AGPL-3.0
  说明：作为独立进程（:tunnel）运行，通过 localhost HTTP 交互，
        未与本项目链接，因此其 AGPL 条款不传染本项目。

LWJGL / GL4ES / Mesa / SDL
  许可证：MIT / BSD 系

AndroidX / Jetpack Compose
  许可证：Apache-2.0

Coil（图片加载）
  许可证：Apache-2.0

Lottie（动画）
  许可证：Apache-2.0

本项目不含 Minecraft 官方代码或资源。
"""

/** 重命名对话框。
 *
 * 单独抽出来是因为状态必须**活在对话框自己的作用域里** ——
 * 写在 AlertDialog 的 text lambda 里没法把输入值回传给 confirmButton。
 */
@Composable
private fun RenameDialog(
    target: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    // 2026-10-17 加：重命名 / 复制 共用同一个对话框组件，标题与按钮文案可配
    title: String = "",
    label: String = "",
    confirmText: String = "",
) {
    var value by remember(target) { mutableStateOf(target) }

    androidx.compose.material3.AlertDialog(
        // ⚠️ 改过名字就别让点外部关掉（用户可能刚敲完一个长名字）
        onDismissRequest = { if (value == target) onDismiss() },
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value.trim()) },
                enabled = value.isNotBlank() && value != target,
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/** 文件浏览的目标场景。 */
enum class FileTarget { Instance, Server }

/**
 *
 * 同一份页面代码服务两种场景，只是 [ops] 不同 ——
 * 这是相对旧界面（两份复制粘贴的组件）的主要改进。
 *
 * 内部两层：文件列表 ↔ 文件编辑器（编辑时覆盖列表）。
 */
@Composable
private fun FileBrowserRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    ops: FileOps,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var path by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(FileManagerUiState(title = context.getString(ops.labelRes))) }

    // 正在编辑的文件（null = 在列表层）；读/改/存都在 FileEditorController 里
    val editor = rememberFileEditor(ops, context)

    // ⚠️ 系统返回键：编辑中就先退出编辑器，否则退出文件浏览（2026-10-17）。
    //
    // 不加这个的话，系统返回走的是 ComposeMainActivity 里的全局 handler，
    // 它只看 backStack / current，**不知道文件浏览这一层的存在** ——
    // 结果按返回直接跳回首页，连实例详情都跳过。
    androidx.activity.compose.BackHandler {
        if (editor.state != null) editor.close() else onBack()
    }

    fun reload() {
        state = state.copy(loading = true, error = null)
        ops.list(path) { r ->
            state = r.fold(
                onSuccess = { state.copy(entries = it, loading = false) },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.read_failed, friendlyError(context, it))) },
            )
        }
    }

    LaunchedEffect(bridgeReady, path) {
        if (bridgeReady) reload()
    }

    // 编辑中：整页覆盖列表（同一份状态机也给实例详情的「文件」tab 用）
    if (FileEditorOverlay(editor)) return

    FileManagerPage(
        state = state.copy(path = path),
        onOpenDir = { path = it },
        onOpenFile = { e ->
            // 大文件不进编辑器（会把界面卡死），直接提示
            if (!editor.open(e)) {
                state = state.copy(error = context.getString(R.string.file_too_large, e.name, fmtBytes(e.size)))
            }
        },
        onBreadcrumb = { path = it },
        onGoUp = {
            path = path.substringBeforeLast('/', "")
        },
        onCreate = { name, isDir ->
            val rel = if (path.isBlank()) name else "$path/$name"
            ops.create(rel, isDir) { reload() }
        },
        onRename = { e, newName ->
            ops.rename(e.rel, newName) { reload() }
        },
        onDelete = { e ->
            ops.delete(e.rel) { reload() }
        },
        onRefresh = { reload() },
        // ⚠️ 2026-10-17：以前没传 onBack —— 独立文件浏览页没有任何返回入口，
        // 只能按系统返回键，而那个会直接跳回首页（详见 FileManagerPage 的 onBack 注释）。
        onBack = onBack,
    )
}

/**
 * 实例详情（7 个 tab）。
 *
 * 数据按 tab **懒加载**：只有切到某个 tab 才拉它的数据 ——
 * 一进页面就并发拉 7 份（内容/存档/按键/日志…）既慢又浪费，
 * 而且大部分用户只看其中一两个。
 */
@Composable
private fun InstanceDetailRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    instanceId: String,
    /** 宿主 Activity：日志「另存为」与「导入文件」都要弹系统选择器。 */
    activity: ComposeMainActivity? = null,
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    /** 任务仓库：判断该实例是否已有进行中的下载（防止重复开任务）。 */
    tasksRepo: TasksRepo,
    /**
     * 任务列表快照（由外层那份**同一个**事件订阅维护）。
     *
     * ⚠️ 之所以要把它传进来而不是在这里再 `start()` 一次 `TasksRepo`：
     * `TasksRepo.onChange` 是**单槽位**（`private var onChange`），
     * 第二个订阅者会把第一个顶掉；而且详情页每次进出都重建订阅，
     * 事件还会漏。共用外层那一份最省事也最不容易错。
     */
    tasksState: DownloadsUiState,
    /** 把「正在查哪个实例」告诉外层（取消任务要用，见调用处）。 */
    onTaskQuery: (String) -> Unit,
    /** 向顶栏注册实例信息（名字/版本/加载器/按钮）。 */
    onRegisterToolbar: ((@Composable () -> Unit)?) -> Unit = {},
    /**
     * 实例元数据变了（换图标等）—— 通知外层刷新实例列表。
     *
     * 列表数据由 PageHost 持有（`instancesReloadKey`），详情页改不到，
     * 只能靠这个回调触发一次重载，否则回到列表还是旧图标。
     */
    onInstanceChanged: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { InstanceDetailRepo(api, context) }

    var state by remember(instanceId) { mutableStateOf(InstanceDetailUiState(id = instanceId)) }

    // 启动阶段文案（检查文件 / 补全 / 启动中）
    var launchStage by remember(instanceId) { mutableStateOf<String?>(null) }

    // 游戏文件检查报告（缺文件时非 null，显示提示条 + 补全按钮）
    var fileReport by remember(instanceId) { mutableStateOf<FileReportUi?>(null) }
    var repairing by remember(instanceId) { mutableStateOf(false) }
    // 识别未识别内容的任务状态（异步：结果靠 content::identified 事件推回）
    var identifying by remember(instanceId) { mutableStateOf(false) }
    // 导出整合包（体检结果 + 三项开关 + 进行中 + 提示）
    var exportPlan by remember(instanceId) { mutableStateOf<ExportPlanUi?>(null) }
    var exportPlanLoading by remember(instanceId) { mutableStateOf(false) }
    var exporting by remember(instanceId) { mutableStateOf(false) }
    var exportHint by remember(instanceId) { mutableStateOf<String?>(null) }

    // ── 共享游戏文件占用（「文件」tab 顶部的卡片）────────────────────────
    //
    // ⚠️ 2026-10-17 补接线：`InstanceDetailPage` 早就声明了
    // `sharedUsage: SharedUsageUi? = null` 这个参数（它有默认值，所以**不传也能编译**），
    // 于是这个调用点一直没赋值 —— 参数恒为 null，卡片永远不渲染，
    // `QookixApi.sharedGameUsage` 也全工程零调用点。
    //
    // 为什么这张卡重要（旧 UI `InstanceDetailView.vue:88-104` 记过同一个真机反馈）：
    // **游戏本体不在实例目录里** —— 刚建的实例目录只有一个 instance.json，
    // 用户打开「文件」看到空空如也，会以为「什么都没下」。
    //
    // 数据源现成：Rust `shared_game_usage`（`src-tauri/src/instance_files.rs:427`）
    // → `QookixApi.sharedGameUsage`（`QookixApi.kt:832`），返回
    // mcVersion / versionBytes / javaVersion / javaBytes /
    // librariesBytes / assetsBytes / instancesTotal。
    var sharedUsage by remember(instanceId) { mutableStateOf<SharedUsageUi?>(null) }

    // ── 监听 `content::identified`（识别结果逐个文件推回来）────────────
    //
    // 旧 UI 的 ContentTab 就是这么做的：收到事件后**就地更新那一行**的
    // name/icon/authors。不监听的话「识别」按钮看起来完全没效果
    //（只能整页重刷，而识别是异步的，重刷往往还没出结果）。
    DisposableEffect(bridgeReady, instanceId) {
        if (!bridgeReady) {
            onDispose { }
        } else {
            val sub = bridge.listen("content::identified") { payload ->
                runCatching {
                    val o = org.json.JSONObject(payload)
                    // 只处理当前实例 + 当前分类的事件
                    if (o.optString("instanceId") != instanceId) return@runCatching
                    val filename = o.optString("filename")
                    val name = o.optString("name")
                    val icon = o.optString("icon")
                    val authors = o.optJSONArray("authors")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                    }.orEmpty()
                    // 就地更新那一行（避免整页重刷）
                    state = state.copy(
                        content = state.content.map { item ->
                            if (item.rel.endsWith(filename) || item.name == filename) {
                                item.copy(
                                    name = name.ifBlank { item.name },
                                    meta = if (authors.isNotEmpty()) authors.joinToString(", ") else item.meta,
                                )
                            } else item
                        },
                    )
                }.onFailure { android.util.Log.w("InstanceDetail", "解析 content::identified 失败", it) }
            }
            onDispose { bridge.unlisten(sub) }
        }
    }

    // 后台更新完成事件（A1）：`apply_update` 是后台跑的，完成后后端 emit
    // `content://update-finished`（payload：{filename, ok, error?}）。
    // 不监听的话用户点「更新」后要退出再进才知道成没成。
    DisposableEffect(bridgeReady, instanceId) {
        if (!bridgeReady) {
            onDispose { }
        } else {
            val sub = bridge.listen("content://update-finished") { payload ->
                runCatching {
                    val o = org.json.JSONObject(payload)
                    val ok = o.optBoolean("ok", false)
                    val filename = o.optString("filename")
                    state = state.copy(
                        error = if (ok) {
                            context.getString(R.string.update_done_for, filename)
                        } else {
                            context.getString(R.string.update_failed, o.optString("error"))
                        },
                    )
                    // 重新拉当前分类，让新版本号/状态即时反映
                    repo.loadContent(state.contentKind, instanceId) { lr ->
                        state = lr.fold(
                            onSuccess = { state.copy(content = it, loading = false) },
                            onFailure = { state.copy(loading = false) },
                        )
                    }
                }.onFailure { android.util.Log.w("InstanceDetail", "解析 content://update-finished 失败", it) }
            }
            onDispose { bridge.unlisten(sub) }
        }
    }

    // 游戏运行时实时日志（A2）：后端后台跟随日志文件，新增行通过
    // `launch://log` 推送（payload：{instanceId, stream, line}）。
    // 不监听的话日志面板只有启动那一刻的快照，看不到游戏运行中的输出。
    DisposableEffect(bridgeReady, instanceId) {
        if (!bridgeReady) {
            onDispose { }
        } else {
            val sub = bridge.listen("launch://log") { payload ->
                runCatching {
                    val o = org.json.JSONObject(payload)
                    if (o.optString("instanceId") != instanceId) return@runCatching
                    val line = o.optString("line")
                    if (line.isNotEmpty()) {
                        // 只保留最近 2000 行，避免长跑游戏把内存吃光
                        state = state.copy(logs = (state.logs + line).takeLast(2000))
                    }
                }.onFailure { android.util.Log.w("InstanceDetail", "解析 launch://log 失败", it) }
            }
            onDispose { bridge.unlisten(sub) }
        }
    }
    var launchingWorld by remember(instanceId) { mutableStateOf<String?>(null) }

    // ── 实例设置 tab 的状态 ────────────────────────────────────────────
    // 单独管理而不是塞进 InstanceDetailUiState：
    // 它有「dirty（未保存）」与「saving」这类**编辑态**，
    // 与其它 tab 的「展示态」性质不同，混在一起容易互相干扰。
    // 值 / 载入 / 保存都收在 InstanceSettingsController 里（进 tab 时读真实配置）。
    val settingsCtl = rememberInstanceSettings(instanceId, state.tab, api, context)
    // ⚠️ 必须 key 到 instanceId（与上面三个一致）：否则在实例 A 打开重命名框、
    // 退出来进实例 B 时，B 会凭空弹出指向 A 的重命名框（状态串台）。
    var renameTarget by remember(instanceId) { mutableStateOf<String?>(null) }
    // 复制按键布局的目标（2026-10-17 修审计 H-3：原来和重命名共用一个状态）
    var duplicateTarget by remember(instanceId) { mutableStateOf<String?>(null) }
    // 布局操作（复制/删除）的错误提示
    var layoutError by remember(instanceId) { mutableStateOf<String?>(null) }
    // 换图标面板（D3）：游戏素材图标 + 本地图片导入
    var showIconPicker by remember(instanceId) { mutableStateOf(false) }
    var gameIcons by remember(instanceId) { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var gameIconsLoading by remember(instanceId) { mutableStateOf(false) }
    // ⚠️ 危险操作的二次确认（2026-10-17 加，真机/审查发现 5 处直接执行）。
    //
    // 原来这些操作点一下就执行、**没有任何确认**：
    //   - 删除皮肤（不可恢复）
    //   - 删除存档备份（备份是唯一回滚手段）
    //   - 删除控制布局（整套按键配置）
    //   - 卸载模组/资源包（文件直接删）
    //   - **恢复存档备份**（覆盖当前存档，进度直接丢失）
    //
    // 用一个通用的 pending 状态：存「标题 + 说明 + 确认按钮文字 + 真正要执行的动作」。
    var dangerConfirm by remember(instanceId) {
        mutableStateOf<DangerConfirm?>(null)
    }


    // 进页面检查一次游戏文件完整性 —— 缺了就显示提示条 + 补全入口。
    //
    // ⚠️ 这个检查**必须放在前端**，不能只依赖启动流程里的那次：
    // 用户可能只是进来看看，而提示条要在他点启动**之前**就告诉他
    // 「文件不完整」—— 否则他点启动、崩了、再回来，才知道有问题。
    LaunchedEffect(bridgeReady, instanceId) {
        if (!bridgeReady) return@LaunchedEffect
        api.checkInstanceFiles(instanceId) { r ->
            fileReport = r.getOrNull()?.let { o ->
                if (o.optBoolean("can_launch", false)) {
                    null  // 后端说能启动就不显示提示条
                } else {
                    val arr = o.optJSONArray("missing")
                    FileReportUi(
                        ok = o.optInt("ok", 0),
                        total = o.optInt("total", 0),
                        advice = o.optString("advice"),
                        missing = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                            arr?.optJSONObject(i)?.let { m ->
                                MissingItemUi(
                                    kind = m.optString("kind"),
                                    detail = m.optString("detail"),
                                    // 默认 true：后端没给字段时按可修处理
                                    // （不给用户「不能修」的错误暗示）
                                    fixable = m.optBoolean("fixable", true),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // 换图标面板（D3）：打开时才去拉游戏素材图标列表
    LaunchedEffect(showIconPicker, instanceId) {
        if (!showIconPicker) return@LaunchedEffect
        gameIconsLoading = true
        api.extractGameIcons(instanceId) { r ->
            gameIconsLoading = false
            r.onSuccess { arr ->
                gameIcons = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
            }
        }
    }

    // 基本信息：进页面就拉
    LaunchedEffect(bridgeReady, instanceId) {
        if (!bridgeReady) return@LaunchedEffect
        repo.loadInfo(instanceId) { r ->
            r.onSuccess { (name, sub) -> state = state.copy(name = name, subtitle = sub) }
            // ⚠️ 2026-10-19 补：失败**必须**记录。
            // 原来这里是空的 —— `get_instance` 一旦失败（实例 JSON 损坏、
            // 命令异常等），`state.name` 就永远是空串；而顶栏「启动游戏」
            // 按钮是 `enabled = state.name.isNotBlank()`，于是按钮永久灰掉、
            // 点了没反应，界面上没有任何解释。
            r.onFailure { e ->
                android.util.Log.w(
                    "PageHost",
                    "读取实例基本信息失败（$instanceId）: ${e.message}",
                )
                state = state.copy(
                    error = context.getString(
                        R.string.load_failed,
                        friendlyError(context, e),
                    ),
                )
            }
        }
    }

    // 共享游戏文件占用：进页面就拉一次（数据要遍历 versions/runtimes/libraries/assets
    // 算目录大小，比较重，所以只在 instanceId 变化时拉，不跟着 tab 切换重复拉）。
    //
    // 失败静默降级（保持 null，少一张卡而已）—— 与旧 UI
    // `InstanceDetailView.vue:100-103` 的处理一致：统计失败不该影响文件页本身。
    LaunchedEffect(bridgeReady, instanceId) {
        if (!bridgeReady) return@LaunchedEffect
        sharedUsage = null
        api.sharedGameUsage(instanceId) { r ->
            sharedUsage = r.getOrNull()?.let { o ->
                SharedUsageUi(
                    mcVersion = o.optString("mcVersion"),
                    versionBytes = o.optLong("versionBytes", 0),
                    javaVersion = o.optInt("javaVersion", 0),
                    javaBytes = o.optLong("javaBytes", 0),
                    librariesBytes = o.optLong("librariesBytes", 0),
                    assetsBytes = o.optLong("assetsBytes", 0),
                    instancesTotal = o.optInt("instancesTotal", 0),
                )
            }
        }
    }

    // 按 tab 懒加载
    LaunchedEffect(bridgeReady, instanceId, state.tab) {
        if (!bridgeReady) return@LaunchedEffect
        state = state.copy(loading = true, error = null)
        when (state.tab) {
            InstanceTab.Saves -> repo.loadWorlds(instanceId) { r ->
                state = r.fold(
                    onSuccess = { state.copy(worlds = it, loading = false) },
                    onFailure = { state.copy(loading = false, error = context.getString(R.string.saves_read_failed, friendlyError(context, it))) },
                )
            }
            InstanceTab.Keys -> repo.loadLayouts { r ->
                state = r.fold(
                    onSuccess = { state.copy(layouts = it, loading = false) },
                    onFailure = { state.copy(loading = false, error = context.getString(R.string.read_failed, friendlyError(context, it))) },
                )
            }
            InstanceTab.Content -> {
                // ⚠️ 2026-10-23 真机反馈「列表里有 3 个模组，页签却写 模组 0 / 材质包 0 / 光影 0」：
                // `contentKinds[].count` **从来没有任何写入点**（只有 DEFAULT_CONTENT_KINDS 里的 0），
                // 因为这里只加载了「当前」kind。改成：三类各查一次条数写回 chips，
                // 当前 kind 顺便把列表也填上（只多两次轻量查询，都是本地扫目录）。
                state.contentKinds.forEach { k ->
                    val isCurrent = k.id == state.contentKind
                    repo.loadContent(k.id, instanceId) { r ->
                        r.getOrNull()?.let { list ->
                            state = state.copy(
                                contentKinds = state.contentKinds.map { kk ->
                                    if (kk.id == k.id) kk.copy(count = list.size) else kk
                                },
                            )
                            if (isCurrent) {
                                state = state.copy(content = list, loading = false)
                            }
                        }
                        val err = r.exceptionOrNull()
                        if (isCurrent && err != null) {
                            state = state.copy(
                                loading = false,
                                error = context.getString(R.string.read_failed, friendlyError(context, err)),
                            )
                        }
                    }
                }
            }
            else -> state = state.copy(loading = false)
        }
    }

    // 重命名对话框（按键布局）
    renameTarget?.let { target ->
        RenameDialog(
            target = target,
            onDismiss = { renameTarget = null },
            title = context.getString(R.string.layout_rename_title),
            label = context.getString(R.string.layout_rename_label),
            confirmText = context.getString(R.string.layout_rename_confirm),
            onConfirm = { newName ->
                renameTarget = null
                api.renameControlLayout(
                    target.removeSuffix(".json"),
                    newName.removeSuffix(".json"),
                ) { r ->
                    r.fold(
                        onSuccess = { state = state.copy(tab = InstanceTab.Keys) },
                        onFailure = { e -> state = state.copy(error = context.getString(R.string.layout_rename_failed, e.message ?: "")) },
                    )
                }
            },
        )
    }

    // 复制对话框（按键布局）—— 2026-10-17 修审计 H-3。
    //
    // 原来 `onLayoutDuplicate = { name -> renameTarget = name }`，
    // 点「复制」图标却弹出**重命名**对话框，确认后走 renameControlLayout ——
    // 用户的原始布局被改名，等于丢了一份；真正的
    // `duplicateControlLayout`（QookixApi:762）全工程零调用。
    duplicateTarget?.let { target ->
        RenameDialog(
            target = target,
            title = context.getString(R.string.layout_copy_title),
            label = context.getString(R.string.layout_new_name),
            confirmText = context.getString(R.string.common_copy),
            onDismiss = { duplicateTarget = null },
            onConfirm = { newName ->
                duplicateTarget = null
                api.duplicateControlLayout(
                    target.removeSuffix(".json"),
                    newName.removeSuffix(".json"),
                ) { r ->
                    r.fold(
                        onSuccess = {
                            // 触发按键 tab 重新加载，新布局出现在列表里
                            state = state.copy(tab = InstanceTab.Keys)
                        },
                        onFailure = { e ->
                            layoutError = context.getString(R.string.layout_copy_failed, friendlyError(context, e))
                        },
                    )
                }
            },
        )
    }

    // ── 换图标面板（D3）───────────────────────────────────────────────
    //
    // `extract_game_icons`（从游戏素材抽图标）+ `import_instance_image`
    // （导入本地图片）两个后端能力一直零调用 —— 实例只有首字母色块。
    if (showIconPicker) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showIconPicker = false },
            title = { androidx.compose.material3.Text(context.getString(R.string.instance_detail_change_icon)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    if (gameIconsLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (gameIcons.isEmpty()) {
                        androidx.compose.material3.Text(
                            context.getString(R.string.instance_detail_icon_no_game_icons),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = 64.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(QkSpacing.xs),
                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(QkSpacing.xs),
                        ) {
                            items(gameIcons.size) { i ->
                                val ic = gameIcons[i]
                                androidx.compose.material3.TextButton(onClick = {
                                    val p = ic.optString("path")
                                    if (p.isNotBlank()) {
                                        api.updateInstance(
                                            org.json.JSONObject().put("id", instanceId).put("icon", p)
                                        ) { r ->
                                            r.fold(
                                                onSuccess = {
                                                    showIconPicker = false
                                                    state = state.copy(error = context.getString(R.string.instance_detail_icon_set))
                                                    onInstanceChanged()
                                                },
                                                onFailure = { e ->
                                                    state = state.copy(error = context.getString(R.string.instance_detail_icon_set_failed, e.message ?: ""))
                                                },
                                            )
                                        }
                                    }
                                }) {
                                    androidx.compose.material3.Text(
                                        text = ic.optString("label").ifBlank { ic.optString("name") },
                                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // 从本地选一张图片导入
                    activity?.pickImage { path ->
                        if (path.isNullOrBlank()) return@pickImage
                        api.importInstanceImage(path) { r ->
                            r.fold(
                                onSuccess = { dest ->
                                    api.updateInstance(
                                        org.json.JSONObject().put("id", instanceId).put("icon", dest)
                                    ) { ur ->
                                        ur.fold(
                                            onSuccess = {
                                                showIconPicker = false
                                                state = state.copy(error = context.getString(R.string.instance_detail_icon_set))
                                                onInstanceChanged()
                                            },
                                            onFailure = { e ->
                                                state = state.copy(error = context.getString(R.string.instance_detail_icon_set_failed, e.message ?: ""))
                                            },
                                        )
                                    }
                                },
                                onFailure = { e ->
                                    state = state.copy(error = context.getString(R.string.instance_detail_icon_set_failed, e.message ?: ""))
                                },
                            )
                        }
                    }
                }) {
                    androidx.compose.material3.Text(context.getString(R.string.instance_detail_icon_from_file))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showIconPicker = false }) {
                    androidx.compose.material3.Text(context.getString(R.string.instance_detail_cancel))
                }
            },
        )
    }

    // ── 「这个实例正在下什么」──────────────────────────────────────────
    //
    // ⚠️ 这里**刻意不按 id 匹配**（`tasksState.active.find { it.id == id }`）。
    // `TaskUi.id` 是 Rust 的 `taskId`（Long，全局自增），和实例 id 是两套
    // 完全不同的编号 —— 写成 `it.id == instanceId` 从类型上就编不过，
    // 而「按实例名匹配」也不行：`TaskUi.title` 只在事件里带 `instanceName`
    // 时才是实例名（见 TasksRepo.titleFor），创建实例的后台安装通常不带，
    // 那时 title 是空/来源，匹配必然落空。
    //
    // 所以走外层那个「当前查询实例」的槽位：先把 instanceId 交给外层，
    // 由 `tasksRepo.activeTaskFor(instanceId)`（按 Entry.instanceId 精确匹配）
    // 算出结果再传回来。用 remember(instanceId, tasksState) 而不是
    // LaunchedEffect，是因为后者要多等一帧才出结果（提示条会闪一下）。
    val instanceTask = remember(instanceId, tasksState) { tasksRepo.activeTaskFor(instanceId) }
    LaunchedEffect(instanceId) { onTaskQuery(instanceId) }


    // 启动 / 修复文件 —— 顶栏和页面内共用同一份实现。
    //
    // ⚠️ 必须提取成变量：它们是两块 UI（顶栏按钮 + 页面内提示条的「补全文件」）
    // 共用的动作，复制两份容易改一处漏一处。
    val launchAction: () -> Unit = {
        repo.launch(
            instanceId = instanceId,
            onStage = { launchStage = it },
        ) { r ->
            launchStage = null
            r.onFailure { e ->
                // 把后端哨兵错误翻译成人话
                // （`INSTANCE_NOT_INSTALLED` 直接甩给用户等于没说）
                val raw = e.message.orEmpty()
                state = state.copy(
                    error = if (raw.contains("INSTANCE_NOT_INSTALLED")) {
                        context.getString(R.string.launch_not_installed)
                    } else {
                        context.getString(R.string.launch_failed_msg, raw)
                    },
                )
            }
        }
    }

    val repairAction: () -> Unit = {
        // ⚠️ 防重入必须查 TasksRepo，不能只看局部 repairing：
        // 局部状态在离开页面后重置，能对同一实例再开一次任务，
        // 于是两条任务抢同一条带宽（实测速度掉到十几 KB/s）。
        if (!(repairing || tasksRepo.hasActiveTaskFor(instanceId))) {
            repairing = true
            api.repairInstanceFiles(instanceId) { r ->
                repairing = false
                r.fold(
                    onSuccess = {
                        api.checkInstanceFiles(instanceId) { cr ->
                            if (cr.getOrNull()?.optBoolean("can_launch", false) == true) {
                                fileReport = null
                            }
                        }
                    },
                    onFailure = { e ->
                        state = state.copy(error = context.getString(R.string.repair_failed, e.message))
                    },
                )
            }
        }
    }

    // ── 补全进度（2026-10-23）─────────────────────────────────────────
    // 横幅上原来只有一个转圈，用户看不出"到底在下什么、还要多久"。
    // 后端在补全时一直发 `install://progress`（阶段文案 + done/total），
    // 这里转成「阶段 + 百分比」，横幅据此画确定进度条。
    // 注意只在 `repairing` 时采纳：别的任务（导入、装插件）也发同一个事件。
    var repairStage by remember { mutableStateOf("") }
    var repairFraction by remember { mutableStateOf(-1f) }
    DisposableEffect(bridge) {
        val sub = bridge.listen("install://progress") { payload ->
            runCatching {
                val o = org.json.JSONObject(payload)
                Triple(o.optString("message"), o.optInt("done", 0), o.optInt("total", 0))
            }.getOrNull()?.let { (msg, done, total) ->
                if (repairing) {
                    repairStage = msg
                    repairFraction = if (total > 0) done.toFloat() / total else -1f
                }
            }
        }
        onDispose { bridge.unlisten(sub) }
    }

    // ── 把实例信息注册到顶栏（2026-10-17）────────────────────────────
    //
    // 用户反馈：「实例详情页左上角有个返回，还有实例名字和原版，
    // 左边**也有一个返回** —— 两套标题栏叠在一起不太好。可以把实例名、
    // 版本、加载器、启动游戏和扳手全放到最上面那个标题栏，
    // 把现在那个『实例详情』替换掉，这样底下空间也能大一点。」
    //
    // 改法：页面内那套自绘标题栏整块删掉，改由这里注册到
    // LauncherShell 的 TopAppBar.title 槽位 ——
    // 和内容中心搜索框用的是同一个机制。
    //
    // ⚠️ 必须放在 launchAction / repairAction **之后**：
    // Kotlin 局部变量不能前向引用（这里要捕获它们）。
    SideEffect {
        onRegisterToolbar {
            InstanceDetailToolbar(
                name = state.name,
                subtitle = state.subtitle,
                // ⚠️ 2026-10-19 修：原来这里是 `state.name.isNotBlank()` ——
                // 拿「实例名有没有加载出来」决定「能不能启动」，是错的。
                // `get_instance` 一旦失败（实例 JSON 损坏、IPC 抖动），
                // `state.name` 为空 → 启动按钮永久 `enabled=false`，
                // 用户看到的是「按钮点不动」，而不是「读取失败」。
                // 现在只避开「正在补全文件」这一个明确不该启动的时刻，
                // 其余情况都允许点 —— 真正的失败由 onClick 报出来。
                canLaunch = !repairing,
                repairing = repairing,
                onLaunch = launchAction,
                onRepair = repairAction,
                onChangeIcon = { showIconPicker = true },
            )
        }
    }
    DisposableEffect(Unit) { onDispose { onRegisterToolbar(null) } }

    InstanceDetailPage(
        state = state,
        onTabChange = { state = state.copy(tab = it) },
        onBack = onBack,
        onLaunch = launchAction,
        onOpenFiles = onOpenFiles,
        fileOps = remember(instanceId) { InstanceFileOps(api, instanceId) },
        // 「文件」tab 顶部的共享占用卡（见上方 sharedUsage 状态的注释）
        sharedUsage = sharedUsage,
        fileReport = fileReport,
        instanceTask = instanceTask,
        onCancelTask = instanceTask?.let { t -> { tasksRepo.cancel(t.id) } },
        repairing = repairing,
        repairStage = repairStage,
        repairFraction = repairFraction,
        onCopyCrashText = { text ->
            // 复制到剪贴板（写走 Rust 的 writeClipboardText 桥）
            QookixApi(bridge).writeClipboard(text) { _ -> }
        },
        onViewCrashRaw = { filename, onResult ->
            api.getCrashReportContent(instanceId, filename, onResult)
        },
        onRepair = repairAction,
        onLaunchWorld = { world ->
            launchingWorld = world
            // quick-play：带着世界名启动。
            // ⚠️ launch_game 的签名是 (instance_id, world, server) —— **没有 accountUuid**，
            // 账号由后端 resolve_launch_account 自己定（见 commands.rs:196-197）。
            // 这里只负责把 world 传到位。
            repo.launch(
                instanceId = instanceId,
                world = world,
                onStage = { launchStage = it },
            ) { r ->
                launchingWorld = null
                launchStage = null
                r.onFailure { e ->
                    state = state.copy(error = context.getString(R.string.enter_world_failed, friendlyError(context, e)))
                }
            }
        },
        launchingWorld = launchingWorld,
        onBackup = { world ->
            api.createWorldBackup(instanceId, world) { r ->
                r.fold(
                    onSuccess = { state = state.copy(tab = InstanceTab.Saves) },
                    onFailure = { e -> state = state.copy(error = context.getString(R.string.backup_create_failed, e.message ?: "")) },
                )
            }
        },
        onRestore = { world, file ->
            // ⚠️ 恢复备份会覆盖当前存档、进度不可撤销 —— 必须二次确认。
            // world 由行内传下来（以前这里取 worlds.first()，多世界实例会恢复进错的世界）。
            dangerConfirm = DangerConfirm(
                title = context.getString(R.string.backup_restore_title),
                message = context.getString(R.string.backup_restore_msg, file),
                confirmText = context.getString(R.string.backup_restore_confirm),
                action = {
                    api.restoreWorldBackup(instanceId, world, file) { r ->
                        r.fold(
                            onSuccess = { state = state.copy(tab = InstanceTab.Saves) },
                            onFailure = { e -> state = state.copy(error = context.getString(R.string.backup_restore_failed, e.message ?: "")) },
                        )
                    }
                },
            )
        },
        onDeleteBackup = { world, file ->
            dangerConfirm = DangerConfirm(
                title = context.getString(R.string.backup_delete_title),
                message = context.getString(R.string.backup_delete_msg),
                confirmText = context.getString(R.string.backup_delete_confirm),
                action = {
                    api.deleteWorldBackup(instanceId, world, file) { r ->
                        r.fold(
                            onSuccess = { state = state.copy(tab = InstanceTab.Saves) },
                            onFailure = { e -> state = state.copy(error = context.getString(R.string.backup_delete_failed, e.message ?: "")) },
                        )
                    }
                },
            )
        },
        onLayoutSelect = { name ->
            api.setCurrentControlLayout(name) { r ->
                r.fold(
                    onSuccess = { state = state.copy(tab = InstanceTab.Keys) },
                    onFailure = { e -> state = state.copy(error = context.getString(R.string.layout_select_failed, e.message ?: "")) },
                )
            }
        },
        onLayoutEdit = { name -> api.openControlLayoutEditor(name, preview = false, saveAs = null) },
        onLayoutRename = { name -> renameTarget = name },
        onLayoutDuplicate = { name -> duplicateTarget = name },
        onLayoutDelete = { name ->
            dangerConfirm = DangerConfirm(
                title = context.getString(R.string.layout_delete_title, name),
                message = context.getString(R.string.layout_delete_msg),
                confirmText = context.getString(R.string.layout_delete_confirm),
                action = {
                    api.deleteControlLayout(name) { r ->
                        r.fold(
                            onSuccess = { state = state.copy(tab = InstanceTab.Keys) },
                            onFailure = { e -> state = state.copy(error = context.getString(R.string.layout_delete_failed, e.message ?: "")) },
                        )
                    }
                },
            )
        },
        onLayoutImport = {
            // 导入控制布局。
            //
            // 命令名是 `pick_control_layout`（核对自 api.ts）。
            // 它**自己会回调宿主弹系统文件选择器**，所以这里不用先弹 ——
            // 我一开始想写成 `import_control_layout`，编译期才发现没这条命令。
            api.pickControlLayout { r ->
                r.onFailure { e ->
                    android.util.Log.w("PageHost", "导入布局失败: ${e.message}")
                }
            }
            // ⚠️ 2026-10-18 补（修「按键导入选完文件没反应」后半段）：
            // 原生命令选完文件把结果写进 control-import.json，
            // **前端必须轮询 take_control_import 取走** —— 这一步迁移时整个丢了，
            // 结果文件永远没人读，界面毫无反馈。
            // 拿到 ok=true 后把 TMP_IMPORT_FILE 填进预览（与旧 UI 一致）。
            var importPolls = 0
            fun pollControlImport() {
                if (importPolls++ > 40) return  // 最多轮 40 次（约 40 秒）
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    api.takeControlImport { pr ->
                        pr.onSuccess { s ->
                            val o = runCatching { org.json.JSONObject(s ?: "") }.getOrNull()
                            if (o == null) {
                                pollControlImport()  // 还没写好，继续等
                            } else if (o.optBoolean("ok")) {
                                // 成功：刷新按键列表即可看到 TMP_IMPORT_FILE
                                state = state.copy(tab = InstanceTab.Keys)
                                state = state.copy(tab = InstanceTab.Keys)
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.control_import_done, o.optInt("buttons")),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                state = state.copy(
                                    error = o.optString("error").ifBlank { context.getString(R.string.import_failed, "") },
                                )
                            }
                        }
                        pr.onFailure { pollControlImport() }
                    }
                }, 1000L)
            }
            pollControlImport()
        },
        // 导出按键布局（D2）：`export_control_layout` 一直没有 UI。
        // 后端把布局 json 写到导出目录并返回路径；这里提示一下即可。
        onLayoutExport = { name ->
            api.exportControlLayout(name.removeSuffix(".json")) { r ->
                r.fold(
                    onSuccess = { path ->
                        state = state.copy(error = context.getString(R.string.layout_export_done, path))
                    },
                    onFailure = { e ->
                        state = state.copy(error = context.getString(R.string.layout_export_failed, e.message ?: ""))
                    },
                )
            }
        },
        // 按键穿透设置（D2）：`get_control_buttons` / `set_control_button_passthru`
        // 一直零调用 —— 按键挡住游戏画面时用户关不掉。
        onLoadControlButtons = {
            api.getControlButtons { r ->
                r.fold(
                    onSuccess = { arr ->
                        state = state.copy(
                            controlButtons = (0 until arr.length()).mapNotNull { i ->
                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                ControlButtonUi(
                                    index = o.optInt("index", i),
                                    name = o.optString("name"),
                                    passThru = o.optBoolean("passThru", false),
                                )
                            },
                        )
                    },
                    onFailure = { e ->
                        state = state.copy(error = context.getString(R.string.instance_detail_passthru_failed, e.message ?: ""))
                    },
                )
            }
        },
        onSetControlPassthru = { index, enabled ->
            api.setControlButtonPassthru(index, enabled) { r ->
                r.fold(
                    onSuccess = { arr ->
                        state = state.copy(
                            controlButtons = (0 until arr.length()).mapNotNull { i ->
                                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                ControlButtonUi(
                                    index = o.optInt("index", i),
                                    name = o.optString("name"),
                                    passThru = o.optBoolean("passThru", false),
                                )
                            },
                        )
                    },
                    onFailure = { e ->
                        state = state.copy(error = context.getString(R.string.instance_detail_passthru_failed, e.message ?: ""))
                    },
                )
            }
        },
        // ⚠️ 2026-10-21 真机修：以前只调 API、不更新 state.content，
        // 开关 UI 靠 state.content[i].enabled 驱动 → 点了没反应，
        // 要切走再切回（重新 loadContent）才看到变化。
        // 现在乐观更新本地 state，失败时回滚并提示。
        onToggleContent = { c, enabled ->
            val prev = c.enabled
            state = state.copy(content = state.content.map { if (it.rel == c.rel) it.copy(enabled = enabled) else it })
            api.toggleContentEnabled(instanceId, state.contentKind, c.rel, enabled) { r ->
                r.onFailure { e ->
                    state = state.copy(
                        content = state.content.map { if (it.rel == c.rel) it.copy(enabled = prev) else it },
                        error = context.getString(R.string.update_failed, e.message),
                    )
                }
            }
        },
        // 切换内容分类（2026-10-17 修审计 H-2）。
        //
        // 原来 FilterChip 的 onClick 是空 lambda，注释谎称「由外层处理」——
        // 但 ContentTab 形参里根本没有 onKindChange，外层也没传。
        // 现在补上，并且切分类后**重新加载该分类的内容**（否则只高亮不换数据）。
        onContentKindChange = { kind ->
            state = state.copy(contentKind = kind, content = emptyList())
            repo.loadContent(kind, instanceId) { r ->
                state = r.fold(
                    onSuccess = { state.copy(content = it, loading = false) },
                    onFailure = { state.copy(loading = false, error = context.getString(R.string.load_failed, friendlyError(context, it))) },
                )
            }
        },
        onUninstallContent = { c ->
            dangerConfirm = DangerConfirm(
                title = context.getString(R.string.content_uninstall_title, c.name),
                message = context.getString(R.string.content_uninstall_msg),
                confirmText = context.getString(R.string.common_uninstall),
                // ⚠️ 2026-10-21 真机修：以前只调 API、不从 state.content 移除，
                // 列表不变 → 要切走再切回才看到删除生效。
                // 现在乐观移除该行，失败时重载整个分类兜底。
                action = {
                    val removed = state.content
                    state = state.copy(content = state.content.filterNot { it.rel == c.rel })
                    api.uninstallContent(instanceId, state.contentKind, c.rel) { r ->
                        r.onFailure { e ->
                            state = state.copy(
                                content = removed,
                                error = context.getString(R.string.update_failed, e.message),
                            )
                        }
                    }
                },
            )
        },
        //
        // ⚠️ 2026-10-17 修：以前这里是 `{ }` —— 把「检查更新」的结果**整个丢弃**了。
        // 后果有两层：
        //   1. `updatable` 永远保持 loadContent 时的初值，用户点了检查更新也看不到「更新」按钮；
        //   2. 即便有按钮，`applyUpdate` 需要的 `new_version_id` 根本无从获得 → 更新必然失败。
        //
        // 现在把结果按 filename 回填到对应行的 `latestVersionId`，同时把 `updatable` 置 true。
        onCheckUpdates = {
            api.checkUpdates(instanceId, state.contentKind) { r ->
                r.fold(
                    onSuccess = { arr ->
                        // 每条 update 形如：
                        // {filename, projectId, currentVersion, latestVersion, latestVersionId, projectTitle, kind, provider}
                        val byName = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }
                            .associateBy { it.optString("filename") }
                        if (byName.isEmpty()) {
                            state = state.copy(error = context.getString(R.string.update_none))
                        } else {
                            val updated = state.content.map { c ->
                                val u = byName[c.rel]
                                if (u == null) c else c.copy(
                                    updatable = true,
                                    latestVersionId = u.optString("latestVersionId"),
                                    projectId = u.optString("projectId").ifBlank { c.projectId },
                                    provider = u.optString("provider").ifBlank { c.provider },
                                )
                            }
                            state = state.copy(
                                content = updated,
                                error = context.getString(R.string.updates_found, byName.size),
                            )
                        }
                    },
                    onFailure = { e -> state = state.copy(error = context.getString(R.string.update_check_failed, e.message ?: "")) },
                )
            }
        },

        // ── 识别未识别的内容 ────────────────────────────────────────────
        //
        // 异步任务：`identify_content` 立刻返回，真正的识别结果通过
        // `content::identified` 事件**逐个文件**推回来（见 browse.rs）。
        // 所以这里要：① 起任务；② 监听事件并就地更新那一行。
        onIdentify = {
            if (identifying) return@InstanceDetailPage
            identifying = true
            api.identifyContent(instanceId, state.contentKind) { r ->
                // ⚠️ 这条命令是**跑完才返回**的（不是「已受理」）。以前只在失败分支
                // 复位 identifying，成功一次之后按钮就永远转圈 / 永久禁用。
                identifying = false
                r.onFailure { e ->
                    state = state.copy(error = context.getString(R.string.content_identify_failed, e.message))
                }
            }
        },
        identifying = identifying,
        exportPlan = exportPlan,
        exportPlanLoading = exportPlanLoading,
        exporting = exporting,
        exportHint = exportHint,
        onExport = { cfg, saves, shots ->
            if (exporting) return@InstanceDetailPage
            exporting = true
            exportHint = null
            // 先体检（纯本地统计，不联网）—— 失败不挡导出，静默降级
            api.planModpackExport(instanceId) { pr ->
                pr.onSuccess { o ->
                    val bundled = o.optJSONArray("bundled")
                    var count = 0
                    var bytes = 0L
                    if (bundled != null) {
                        for (i in 0 until bundled.length()) {
                            val b = bundled.optJSONObject(i) ?: continue
                            count += b.optInt("count", 1)
                            bytes += b.optLong("bytes", 0)
                        }
                    }
                    exportPlan = ExportPlanUi(
                        fileName = o.optString("file_name").ifBlank { o.optString("fileName") },
                        mcVersion = o.optString("mc_version").ifBlank { o.optString("mcVersion") },
                        loader = o.optString("loader"),
                        linked = o.optInt("linked", 0),
                        bundledCount = count,
                        bundledBytes = if (bytes > 0) bytes else o.optLong("bundled_bytes", 0),
                        hasSaves = o.optBoolean("has_saves", false),
                        hasScreenshots = o.optBoolean("has_screenshots", false),
                        hasConfig = o.optBoolean("has_config", false),
                    )
                }
                // 体检失败就带着空统计导出（不挡流程）
                val options = org.json.JSONObject()
                    .put("include_saves", saves)
                    .put("include_screenshots", shots)
                    .put("include_config", cfg)
                api.exportModpack(instanceId, options) { er ->
                    exporting = false
                    er.fold(
                        onSuccess = { res ->
                            val name = res.optString("file_name").ifBlank { res.optString("fileName") }
                            val bytes = res.optLong("bytes", 0)
                            exportHint = context.getString(R.string.export_done, name, fmtBytes(bytes))
                        },
                        onFailure = { e -> exportHint = context.getString(R.string.export_failed, e.message) },
                    )
                }
            }
        },
        // ⚠️ 2026-10-17 修：以前只传 `(instanceId, c.rel)` 两个参数，
        // 而 Rust 需要 6 个（commands.rs:1079-1086）→ 更新必然失败。
        // 现在把 kind / oldFilename / provider / projectId / newVersionId 都传过去。
        onApplyUpdate = { c ->
            val newId = c.latestVersionId
            if (newId.isBlank()) {
                state = state.copy(error = context.getString(R.string.update_none_for, c.name))
            } else {
                // ⚠️ 2026-10-21 真机修：以前成功后只弹一句提示，不刷新列表 →
                // 该行 updatable 仍为 true、「更新」按钮还在，且看不到版本号变化。
                // 现在成功后重载该分类内容，让新版本号/状态即时反映。
                api.applyUpdate(
                    instanceId = instanceId,
                    kind = state.contentKind,
                    oldFilename = c.rel,
                    provider = c.provider,
                    projectId = c.projectId,
                    newVersionId = newId,
                ) { r ->
                    r.fold(
                        onSuccess = {
                            state = state.copy(error = context.getString(R.string.update_done_for, c.name))
                            repo.loadContent(state.contentKind, instanceId) { lr ->
                                state = lr.fold(
                                    onSuccess = { state.copy(content = it, loading = false) },
                                    onFailure = { state.copy(loading = false) },
                                )
                            }
                        },
                        onFailure = { e -> state = state.copy(error = context.getString(R.string.update_failed, e.message)) },
                    )
                }
            }
        },
        onLoadLog = {
            api.readInstanceLog(instanceId) { r ->
                r.fold(
                    onSuccess = { text -> state = state.copy(logs = text.lines().takeLast(3000)) },
                    onFailure = { e -> state = state.copy(error = context.getString(R.string.instance_detail_log_load_failed, e.message ?: "")) },
                )
            }
        },
        onExportLog = {
            api.exportInstanceLogs(instanceId) { r ->
                r.onFailure { e ->
                    state = state.copy(error = context.getString(R.string.update_failed, e.message))
                }
            }
        },
        // D5a「另存为」：走系统「新建文档」选择器拿 content:// URI，再交给
        // Rust 的 save_text_file 写回（它内部对 content:// 走 ContentResolver）。
        // 与上面的「导出 zip」区别：那个走分享面板，这个直接落成 .txt 文件。
        onSaveLog = activity?.let { act ->
            {
                if (state.logs.isNotEmpty()) {
                    val content = state.logs.joinToString("\n")
                    val stamp = java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss",
                        java.util.Locale.US,
                    ).format(java.util.Date())
                    act.createTextDocument("log-$stamp.txt") { uri ->
                        // 用户取消选择时 uri 为 null，静默收场
                        if (uri != null) {
                            api.saveTextFile(uri, content) { r ->
                                state = r.fold(
                                    onSuccess = {
                                        state.copy(error = context.getString(R.string.instance_detail_save_log_done))
                                    },
                                    onFailure = { e ->
                                        state.copy(
                                            error = context.getString(
                                                R.string.instance_detail_save_log_failed,
                                                friendlyError(context, e),
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        // D4「导入文件」：把本地下载好的 jar/zip 复制进当前分类目录。
        // 后端按 kind 决定落到 mods / resourcepacks / shaderpacks。
        onImportContent = activity?.let { act ->
            {
                // 不限定 MIME：模组是 .jar、资源包是 .zip，各机型的 provider
                // 给的 MIME 五花八门，限定成 application/java-archive 会漏文件。
                act.pickFileByMime("*/*") { path ->
                    if (!path.isNullOrBlank()) {
                        val kind = state.contentKind
                        val fileName = path.substringAfterLast('/')
                        api.importLocalFile(instanceId, kind, path) { r ->
                            r.fold(
                                onSuccess = {
                                    state = state.copy(
                                        error = context.getString(R.string.instance_detail_import_file_done, fileName),
                                    )
                                    repo.loadContent(kind, instanceId) { lr ->
                                        state = lr.fold(
                                            onSuccess = { state.copy(content = it, loading = false) },
                                            onFailure = { state.copy(loading = false) },
                                        )
                                    }
                                },
                                onFailure = { e ->
                                    state = state.copy(
                                        error = context.getString(
                                            R.string.instance_detail_import_file_failed,
                                            friendlyError(context, e),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        onLoadCrashes = {
            // 崩溃日志是「文件名列表」，不是内容 —— 内容要点了「分析」才拉。
            // ⚠️ list_crash_logs 返回的是**对象数组**（[{filename, ...}]），
            // 不是字符串数组 —— 之前用 optString(i) 读，拿到的是整个对象的
            // JSON 文本当文件名用，analyze 就报「非法的文件名」。
            api.listCrashLogs(instanceId) { r ->
                r.fold(
                    onSuccess = { arr ->
                        val names = (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i)
                            (o?.optString("filename").orEmpty().ifBlank { arr.optString(i) })
                                .takeIf { it.isNotBlank() }
                        }
                        state = state.copy(crashLogs = names)
                    },
                    onFailure = { e -> state = state.copy(error = context.getString(R.string.instance_detail_crash_list_failed, e.message ?: "")) },
                )
            }
        },
        settings = settingsCtl.value,
        onSettingsChange = settingsCtl::applyPatch,
        onSettingsSave = settingsCtl::save,
        settingsSaving = settingsCtl.saving,
        settingsHint = settingsCtl.hint,
        settingsError = settingsCtl.error,
        onDismissSettingsHint = { settingsCtl.hint = null; settingsCtl.error = null },
        onAnalyzeCrash = { file ->
            android.util.Log.i("QkCrash", "analyze file=[" + file + "] len=" + file.length)
            api.analyzeCrashLog(instanceId, file) { r ->
                r.fold(
                    onSuccess = { o ->
                        // 分析结果字段名不固定，优先找常见的几个
                        val text = o.optString("analysis")
                            .ifBlank { o.optString("summary") }
                            .ifBlank { o.optString("reason") }
                            .ifBlank { o.toString(2) }
                        state = state.copy(crashHint = text)
                    },
                    onFailure = { e -> state = state.copy(crashHint = context.getString(R.string.crash_analyze_failed, e.message)) },
                )
            }
        },
    )

    // 危险操作确认框。放在这里而不是各调用点内联，保证**所有**危险操作
    // 都走同一条确认路径，不会有漏网的。
    dangerConfirm?.let { dc ->
        com.zhayi.qookix.compose.pages.settings.ConfirmDialog(
            title = dc.title,
            message = dc.message,
            confirmText = dc.confirmText,
            danger = true,
            onDismiss = { dangerConfirm = null },
            onConfirm = {
                dangerConfirm = null
                dc.action()
            },
        )
    }
}

/** 多人游戏（本地服务器 + 陶瓦联机）。 */
@Composable
private fun MultiplayerRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    onOpenServer: (String) -> Unit,
    onNavigate: (NavDestination) -> Unit,
        // 复制房间码要用（房间码是发给朋友的短串）
        activity: ComposeMainActivity? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { MultiplayerRepo(api) }

    var state by remember { mutableStateOf(MultiplayerUiState(loading = true)) }

    // 新建服务器对话框
    var showCreate by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }

    // 联机房间（陶瓦）状态
    var tunnelBusy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tunnelReady by remember { mutableStateOf<Boolean?>(null) }

    // ── 生命周期取消闸 ─────────────────────────────────────────────────
    // 陶瓦开房的房间码轮询（postDelayed({ poll(tries - 1) }, 1500) × 8 轮）
    // 会在用户离开页面后继续跑：迟到回调会写 `state` / `notice` /
    // `tunnelBusy`，把状态写进一个已经不存在的页面，还可能卡住下一次
    // 进来时的 `tunnelBusy`。
    //
    // 为什么用 alive 布尔闸而不是 removeCallbacks：
    //   poll 是局部嵌套函数，靠自我 postDelayed 重排，Runnable 引用在
    //   lambda 深处，外部要存句柄再逐一取消既繁琐又容易漏；页面里的
    //   多个循环还共用 Main Looper。布尔闸最简单可靠 —— onDispose 一定
    //   执行，回调醒来后读 alive 就知道自己该不该继续/写状态。
    var alive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { alive = false } }

    fun reload() {
        // error 在**开始时**清，不在成功回调里清 —— 否则「启动失败」的红字
        // 会被紧随其后的列表刷新顺手抹掉。
        state = state.copy(loading = true, error = null)
        repo.loadServers { r ->
            state = r.fold(
                onSuccess = { state.copy(servers = it, loading = false) },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.server_list_read_failed, friendlyError(context, it))) },
            )
        }
    }

    /** 启停失败必须给反馈（以前 `{ reload() }` 把 Result 丢了，失败零提示）。 */
    fun serverActionFailed(failRes: Int, e: Throwable?) {
        if (e == null) return
        state = state.copy(error = context.getString(failRes, friendlyError(context, e)))
    }

    // 服务器列表：bridge 就绪时拉一次；Rust 推 servers 脏事件时重拉（A2）。
    // 以前只在进页面时拉一次 —— 建/删服务器后要退出重进才看到变化。
    val serversRevision by CoreState.serversRevision.collectAsState()
    LaunchedEffect(bridgeReady, serversRevision) {
        if (bridgeReady) reload()
    }

    LaunchedEffect(bridgeReady) {
        if (bridgeReady) {
            // 顺带探一次陶瓦隧道是否就绪（决定按钮文案是「下载」还是「开启」）。
            //
            // ⚠️ 这里以前调 `terracotta_detect` —— Android 上没有这个命令，
            // 运行时报「Command terracotta_detect not found」。
            // Android 的正确探法是 `terracotta_ping`（见 [terracottaTunnelReady]）。
            api.terracottaTunnelReady { ready -> tunnelReady = ready }
        }
    }

    MultiplayerPage(
        state = state,
        onTabChange = { state = state.copy(tab = it) },
        onStartServer = { id -> repo.start(id) { r -> reload(); serverActionFailed(R.string.server_detail_start_failed, r.exceptionOrNull()) } },
        onStopServer = { id -> repo.stop(id) { r -> reload(); serverActionFailed(R.string.server_detail_stop_failed, r.exceptionOrNull()) } },
        onOpenServer = onOpenServer,
        // 2026-10-18：从弹窗改成独立页面（用户反馈「对话框不合适，
        // 像新建实例那样做成页面会好一点」）。见 ServerCreatePage.kt。
        onCreateServer = { onNavigate(NavDestination.ServerCreate) },
        onPingServer = { address, onResult ->
            QookixApi(bridge).pingMcServer(address) { r ->
                r.fold(
                    onSuccess = { o ->
                        val isOnline = o.optBoolean("online", false)
                        if (isOnline) {
                            val latency = o.optInt("latency_ms", 0)
                            val online = o.optInt("players_online", 0)
                            val max = o.optInt("players_max", 0)
                            onResult(Result.success(context.getString(R.string.multiplayer_ping_ok, latency, online, max)))
                        } else {
                            onResult(Result.failure(Exception(o.optString("error", "无法连接"))))
                        }
                    },
                    onFailure = { onResult(Result.failure(it)) },
                )
            }
        },
        onOpenFrp = { onNavigate(NavDestination.Frp) },
        onOpenFrpRooms = { onNavigate(NavDestination.FrpRooms) },
        tunnelBusy = tunnelBusy, onRetry = { reload() },
        // 复制房间码 —— 房间码本来就是要发给朋友的，
        // 不能复制就得手抄（旧 UI 的 copyRoom 就是这个作用）
        onCopyRoom = {
            if (state.roomCode.isNotBlank()) {
                activity?.copyToClipboard(context.getString(R.string.frp_room_code), state.roomCode)
                notice = context.getString(R.string.frp_room_code_copied)
            }
        },
        // ── 加入别人的房间（旧 UI 的 joinRoom，我先前完全漏做）──────────
        //
        // 对多数用户来说「进朋友的房间」比「自己开房」更常用，
        // 漏掉它等于这个功能只能单向使用。
        onJoinRoom = { code ->
            if (tunnelBusy) return@MultiplayerPage
            tunnelBusy = true
            notice = null

            // 加入前同样要确保隧道在跑 —— 隧道没起来的话 /guest 会直接连接被拒，
            // 报错看不懂。探活走 terracotta_ping（Android 上没有 detect 命令）。
            api.terracottaTunnelReady { ready ->
                if (!ready) {
                    tunnelBusy = false
                    notice = context.getString(R.string.frp_tunnel_not_ready_join)
                    return@terracottaTunnelReady
                }
                // 顺序：先确保 VPN 授权（数据面要走 TUN），否则客人的包根本进不来
                api.terracottaRequestVpn { _ ->
                    api.terracottaGuest(code, "player") { jr ->
                        tunnelBusy = false
                        val body = jr.getOrNull().orEmpty()
                        if (body.contains("\"ok\":true")) {
                            state = state.copy(tunnelRunning = true, roomCode = code)
                            notice = context.getString(R.string.frp_joined_room, code)
                        } else {
                            val err = Regex("\"error\":\"([^\"]*)\"")
                                .find(body)?.groupValues?.getOrNull(1)
                notice = if (err != null) context.getString(R.string.frp_join_failed, err)
                else context.getString(R.string.frp_join_invalid)
                        }
                    }
                }
            }
        },
        onToggleTunnel = {
            if (tunnelBusy) return@MultiplayerPage
            tunnelBusy = true
            notice = null

            // 陶瓦联机在 **Android** 上的正确顺序（对照旧 Vue 的 startHosting）：
            //   1. 探活   —— 隧道进程可能刚被系统回收，直接发请求只会撞上
            //               「连接被拒」这种看不懂的报错
            //   2. VPN    —— TUN 模式必须授权，否则房间码出得来、朋友却连不上
            //   3. /host  —— 真正开房
            //   4. 轮询 /state —— 等房间码（房主要先在游戏里「对局域网开放」）
            //
            // ⚠️ 这里以前调 terracotta_detect / terracotta_download / terracotta_launch
            // —— 三个**在 Android 上不存在**的命令（桌面版接口），整个功能是死的。
            fun startHosting() {
                api.terracottaRequestVpn { _ ->
                    val playerName = "player"
                    api.terracottaHost(playerName) { hr ->
                        val body = hr.getOrNull().orEmpty()
                        if (!body.contains("\"ok\":true")) {
                            tunnelBusy = false
                            val err = Regex("\"error\":\"([^\"]*)\"")
                                .find(body)?.groupValues?.getOrNull(1)
                notice = if (err != null) context.getString(R.string.frp_status_prefix, err)
                else context.getString(R.string.frp_tunnel_not_ready)
                            return@terracottaHost
                        }
                        // 房间码要等房主在游戏里「对局域网开放」才出现 —— 轮询 8 次
                        fun poll(tries: Int) {
                            // 页面已销毁：停止重排，也别再碰 tunnelBusy/notice
                            if (!alive) return
                            if (tries <= 0) {
                                tunnelBusy = false
                notice = context.getString(R.string.frp_room_creating)
                                return
                            }
                            api.terracottaTunnelState { st ->
                                // 迟到回调：页面可能已经不在了，别写它的状态
                                if (!alive) return@terracottaTunnelState
                                if (st.room.isNotBlank()) {
                                    tunnelBusy = false
                                    state = state.copy(tunnelRunning = true, roomCode = st.room)
                                    notice = context.getString(R.string.frp_room_opened2, st.room)
                                } else {
                                    android.os.Handler(android.os.Looper.getMainLooper())
                                        .postDelayed({ poll(tries - 1) }, 1500)
                                }
                            }
                        }
                        poll(8)
                    }
                }
            }

            // 先探活：ping 会在端口文件过期时顺手自愈（见 Rust terracotta_ping）
            api.terracottaTunnelReady { ready ->
                if (ready) {
                    startHosting()
                } else {
                    // 隧道没在跑。Android 上**没有**「启动隧道」的命令 ——
                    // 它由 :tunnel 进程自己管。给用户一个能自己动手的提示，
                    // 而不是报一个不存在的命令名。
                    tunnelBusy = false
                    notice = context.getString(R.string.frp_tunnel_not_ready_host)
                }
            }
        },
    )

    notice?.let { msg ->
        InfoDialog(
            title = context.getString(R.string.multiplayer_short),
            body = msg,
            onDismiss = { notice = null },
        )
    }
}

/** 新建实例 + 表单状态。 */
@Composable
private fun CreateRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    onBack: () -> Unit,
    onCreated: () -> Unit,
    activity: ComposeMainActivity? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { CreateRepo(api) }

    var state by remember { mutableStateOf(CreateUiState(loadingVersions = true)) }

    // 预估下载体积（2026-10-21 补：estimateDownload 一直没 UI）
    LaunchedEffect(bridgeReady, state.version) {
        if (!bridgeReady || state.version.isBlank()) {
            state = state.copy(estimatedDownloadMb = null)
        } else {
            api.estimateDownload(state.version) { r ->
                r.onSuccess { o ->
                    val bytes = o.optLong("download_bytes", 0)
                    if (bytes > 0) state = state.copy(estimatedDownloadMb = bytes / 1_000_000)
                }
            }
        }
    }

    // ── 加载器版本（非 vanilla 必填）────────────────────────────────────
    //
    // 旧注释原话：选了加载器就必须带上版本号 —— 空着创建出来的实例
    // 加载器装不上、依赖也解析不出来，连「补全游戏文件」都没得下手。
    var loaderVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var loaderVersionsLoading by remember { mutableStateOf(false) }
    var loaderVersionsError by remember { mutableStateOf<String?>(null) }

    /** 换加载器或换游戏版本都重取，默认选最新（列表第一项）。 */
    fun loadLoaderVersions() {
        state = state.copy(loaderVersion = "")
        if (state.loader == "vanilla" || state.version.isBlank()) {
            loaderVersions = emptyList()
            loaderVersionsError = null
            return
        }
        loaderVersionsLoading = true
        loaderVersionsError = null
        api.getLoaderVersions(state.loader, state.version) { r ->
            loaderVersionsLoading = false
            r.fold(
                onSuccess = { arr ->
                    val list = (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotBlank() }
                    }
                    loaderVersions = list
                    if (list.isEmpty()) {
                        loaderVersionsError = context.getString(R.string.loader_no_versions, state.loader)
                    } else {
                        state = state.copy(loaderVersion = list.first())
                    }
                },
                onFailure = { e -> loaderVersionsError = context.getString(R.string.loader_versions_failed, e.message) },
            )
        }
    }

    // 选了非 vanilla 加载器 / 换了游戏版本 → 重新拉版本列表
    LaunchedEffect(state.loader, state.version) { loadLoaderVersions() }

    // ── 导入整合包模式 ──────────────────────────────────────────────
    //
    // 对应旧 UI `CreateInstanceView.vue` 的第二种模式。整合包自带 MC 版本与
    // 加载器，用户只要选文件 —— 再手填一遍只会填错（旧注释原话）。
    // 解析 / 建实例 / 解 overrides / 下载内容全在后端一条流水线里跑。
    var importMode by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    // ── 导入进度（2026-10-23）───────────────────────────────────────────
    // 直接听 install://progress：`TasksRepo.onChange` 是**单槽位**（见本文件上面的注释），
    // 抢它的订阅会让下载页没有进度；而 `bridge.listen` 支持多订阅者。
    // 只更新「阶段文案 + done/total」，导入页据此画确定进度条。
    var importStage by remember { mutableStateOf("") }
    var importDone by remember { mutableStateOf(0) }
    var importTotal by remember { mutableStateOf(0) }
    DisposableEffect(bridge) {
        val sub = bridge.listen("install://progress") { payload ->
            runCatching {
                val o = org.json.JSONObject(payload)
                Triple(o.optString("message"), o.optInt("done", 0), o.optInt("total", 0))
            }.getOrNull()?.let { (msg, done, total) ->
                // 只在导入期间采纳：别的安装（补全/装插件）也会发同样的阶段，别串到导入页
                if (importing) {
                    importStage = msg
                    importDone = done
                    importTotal = total
                }
            }
        }
        onDispose { bridge.unlisten(sub) }
    }
    var importMsg by remember { mutableStateOf<String?>(null) }

    fun pickAndImportModpack() {
        if (importing) return
        importMsg = null
        // 用系统文件选择器拿路径（走 HostCapabilities 的 pickFile）
        val act = activity ?: return
        act.pickFileByMime("*/*") { path ->
            if (path.isNullOrBlank()) return@pickFileByMime
            importing = true
            api.importModpack(path) { r ->
                importing = false
                r.fold(
                    onSuccess = { json ->
                        val o = runCatching { org.json.JSONObject(json) }.getOrNull()
                        val inst = o?.optJSONObject("instance")
                        val name = inst?.optString("name").orEmpty()
                        // ⚠️ 「少下了一部分」必须当场告诉用户：只写日志的后果是
                        // 他进游戏才崩，而那时错误信息指不到真凶（旧注释原话）。
                        val bad = (o?.optInt("failed", 0) ?: 0) +
                            (o?.optInt("skippedNoUrl", 0) ?: 0)
                        importMsg = if (bad > 0) {
                            context.getString(R.string.import_partial, bad)
                        } else {
                            context.getString(R.string.import_done, name)
                        }
                        onCreated()
                    },
                    onFailure = { e -> importMsg = context.getString(R.string.import_failed, e.message) },
                )
            }
        }
    }


    // 进页面就拉版本清单
    LaunchedEffect(bridgeReady) {
        if (!bridgeReady) return@LaunchedEffect
        repo.loadVersions { r ->
            state = r.fold(
                onSuccess = { vs ->
                    // 默认选中最新正式版（与旧界面一致）
                    val latest = vs.firstOrNull { it.type == "release" }?.id ?: vs.firstOrNull()?.id ?: ""
                    state.copy(versions = vs, version = latest, loadingVersions = false)
                },
                onFailure = { e ->
                    state.copy(loadingVersions = false, error = context.getString(R.string.version_manifest_failed, e.message))
                },
            )
        }
    }

    CreateInstancePage(
        state = state,
        onNameChange = { state = state.copy(name = it) },
        onVersionChange = { state = state.copy(version = it) },
        onLoaderChange = { state = state.copy(loader = it) },
        onMemoryChange = { state = state.copy(memoryMb = it) },
        loaderVersions = loaderVersions,
        loaderVersionsLoading = loaderVersionsLoading,
        loaderVersionsError = loaderVersionsError,
        onLoaderVersionChange = { state = state.copy(loaderVersion = it) },
        onBack = onBack,
        importMode = importMode,
        onImportModeChange = { importMode = it },
        onPickModpack = { pickAndImportModpack() },
        importing = importing,
    importStage = importStage,
    importDone = importDone,
    importTotal = importTotal,
        onCreate = {
            if (state.creating) return@CreateInstancePage
            state = state.copy(creating = true, error = null)
            repo.create(
                name = state.name.trim(),
                mcVersion = state.version,
                loader = state.loader,
                loaderVersion = state.loaderVersion,
                // ⚠️ 必须显式传渲染器（2026-10-16 修，阻断性缺陷）。
                //
                // 不传的话实例 `renderer` 为 null → Rust 走「按版本自动」→
                // 1.21.x 被判给 **GL4ES**，而 GL4ES 不支持 `glMapBufferRange`，
                // 游戏一点启动就崩：
                //     IllegalStateException: Can't map buffer, opengl error 0
                // 真机实测：GL4ES 崩、MobileGlues 正常（见 CreateRepo.autoRendererFor）。
                renderer = CreateRepo.autoRendererFor(state.version),
                memoryMb = state.memoryMb,
            ) { r ->
                r.fold(
                    onSuccess = { json ->
                        // ⚠️ 创建实例 ≠ 装好游戏 —— 这是旧界面有、我漏掉的**关键一步**。
                        //
                        // 实测（用户反馈「实例下载没有」）：只调 create_instance 的话
                        // 生成的是一个**空壳**（只有 instance.json，没有游戏文件），
                        // 启动必然失败。旧 UI 创建后立刻调 install_game
                        // （CreateInstanceView.vue L123）逐文件下载游戏本体。
                        //
                        // install_game 返回 taskId 后进度走 install://progress
                        // 事件 → 下载页能看到（TasksRepo 已监听）。所以这里
                        // 不等安装完成 —— 回实例页即可，进度去下载页看。
                        val instId = runCatching { JSONObject(json).optString("id") }
                            .getOrDefault("")
                        if (instId.isNotBlank()) {
                            // ⚠️ 2026-10-20 真机修：**必须接结果**。
                            //
                            // 原来这里 `api.installGame(instId)` 不带回调 ——
                            // 安装失败时（弱网、meta 拉不到、磁盘满）**一点痕迹都没有**：
                            // 下载页显示「暂无进行中的任务」，实例详情显示「依赖 0/7」，
                            // 用户完全不知道为什么，只能看到游戏一启动就闪退。
                            // 真机实测的「Fabric 26.3 加载界面闪退」就是这么来的。
                            api.installGame(instId) { r ->
                                r.onFailure { e ->
                                    android.util.Log.w(
                                        "PageHost",
                                        "自动安装实例 $instId 失败: ${e.message}",
                                    )
                                    state = state.copy(
                                        error = context.getString(
                                            R.string.create_install_failed,
                                            e.message ?: "",
                                        ),
                                    )
                                }
                            }
                        }
                        state = state.copy(creating = false)
                        onCreated()
                    },
                    onFailure = { e ->
                        state = state.copy(creating = false, error = context.getString(R.string.create_failed, e.message))
                    },
                )
            }
        },
    )
}

/** 内容中心每页条数。与 `BrowseRepo.search` 里的 pageSize 必须一致。 */
private const val BROWSE_PAGE_SIZE = 20

/** 内容中心 + 搜索状态。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BrowseRoute(
    bridge: RustBridge,
    ready: Boolean,
    openUrl: ((String) -> Unit)? = null,
    onRequestCreateInstance: (() -> Unit)? = null,
    /** 向顶栏注册自定义内容（搜索/筛选/排序/紧凑）。 */
    onRegisterToolbar: ((@Composable () -> Unit)?) -> Unit = {},
    /** 宿主 Activity（读写内容中心的紧凑模式偏好）。 */
    activity: ComposeMainActivity? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { BrowseRepo(api) }

    // 项目详情对话框（2026-10-21 补：projectInfo 一直没 UI，原来只跳浏览器）
    var projectDetail by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var projectDetailLoading by remember { mutableStateOf(false) }
    // 正文翻译（D1）：`translate_project_body` 一直没 UI —— 详情页正文只能是原文。
    var projectBodyZh by remember { mutableStateOf<String?>(null) }
    var projectBodyTranslating by remember { mutableStateOf(false) }
    var projectBodyError by remember { mutableStateOf<String?>(null) }

    // 初始化时读上次的紧凑模式偏好（2026-10-17：记住用户选择）
    var state by remember {
        mutableStateOf(BrowseUiState(compact = activity?.browseCompactPref() ?: false))
    }

    // 当前页码（0 起）。分页状态放在这里而不是 BrowseUiState 里，
    // 因为它是「请求进度」而不是「界面状态」—— 界面只关心 items 列表。
    var page by remember { mutableStateOf(0) }
    var loadingMore by remember { mutableStateOf(false) }
    /** 还有没有下一页（上一页返回满页就认为有）。 */
    var hasMore by remember { mutableStateOf(true) }
    /** 整页翻译进行中（B1）。 */
    var translatingPage by remember { mutableStateOf(false) }

    // ── 安装对话框状态（对应旧 UI InstallDialog.vue）────────────────────
    var installTarget by remember { mutableStateOf<ProjectUi?>(null) }
    var installVersions by remember { mutableStateOf<List<ContentVersionUi>>(emptyList()) }
    var installLoadingVersions by remember { mutableStateOf(false) }
    var installVersion by remember { mutableStateOf<String?>(null) }
    var installInstanceId by remember { mutableStateOf<String?>(null) }
    var installServerId by remember { mutableStateOf<String?>(null) }
    var installTypeFilter by remember { mutableStateOf("all") }
    var installDeps by remember { mutableStateOf<List<DependencyUi>>(emptyList()) }
    var installDescZh by remember { mutableStateOf<String?>(null) }
    var installDescLoading by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    // 版本请求序号：并发只认最后一次，避免"选好了又跳回默认"
    var installVersionsSeq by remember { mutableStateOf(0) }
    // 目标候选（实例 / 服务器列表，打开对话框时拉一次）
    var installInstances by remember { mutableStateOf<List<InstallTargetUi>>(emptyList()) }
    var installServers by remember { mutableStateOf<List<ServerUi>>(emptyList()) }

    /** 解析版本列表的接口返回。 */
    fun parseVersions(json: org.json.JSONObject): List<ContentVersionUi> {
        val arr = json.optJSONArray("versions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val type = o.optString("version_type")
                .ifBlank { o.optString("release_type") }
            ContentVersionUi(
                id = id,
                versionNumber = o.optString("version_number").ifBlank { o.optString("name") },
                type = type,
                typeLabel = when (type) {
                    "beta" -> context.getString(R.string.version_type_beta)
                    "alpha" -> context.getString(R.string.version_type_alpha)
                    else -> context.getString(R.string.version_type_release)
                },
                gameVersions = o.optJSONArray("game_versions")?.let { gv ->
                    (0 until gv.length()).mapNotNull { gv.optString(it) }
                        .takeLast(2).joinToString(", ")
                }.orEmpty(),
                datePublished = o.optString("date_published"),
            )
        }
    }

    /** 拉版本列表（按目标实例筛选，无结果回退全量）。 */
    fun loadInstallVersions(p: ProjectUi) {
        val seq = ++installVersionsSeq
        installLoadingVersions = true
        installVersion = null
        installDeps = emptyList()
        val inst = installInstances.firstOrNull { it.id == installInstanceId }
        val mc = inst?.mcVersion ?: ""
        val loader = inst?.loader?.takeUnless { it == "vanilla" } ?: ""
        api.projectVersions(p.provider, p.id, mc, loader) { r ->
            if (seq != installVersionsSeq) return@projectVersions
            r.fold(
                onSuccess = { json ->
                    var list = parseVersions(json)
                    // 上游按实例筛选无结果时，回退拉全部版本供选择
                    if (list.isEmpty() && (mc.isNotBlank() || loader.isNotBlank())) {
                        api.projectVersions(p.provider, p.id, "", "") { r2 ->
                            if (seq != installVersionsSeq) return@projectVersions
                            r2.onSuccess { all ->
                                list = parseVersions(all)
                                installVersions = list
                                installVersion = list.firstOrNull { it.type == "release" }?.id
                                    ?: list.firstOrNull()?.id
                            }
                            installLoadingVersions = false
                        }
                        return@fold
                    }
                    installVersions = list
                    installVersion = list.firstOrNull { it.type == "release" }?.id
                        ?: list.firstOrNull()?.id
                    installLoadingVersions = false
                    // 拉依赖
                    installVersion?.let { vid ->
                        api.projectDependencies(p.provider, p.id, vid) { dr ->
                            dr.onSuccess { arr ->
                                installDeps = (0 until arr.length()).mapNotNull { i ->
                                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                                    val dt = o.optString("dependencyType")
                                        .ifBlank { o.optString("dependency_type") }
                                    DependencyUi(
                                        projectId = o.optString("projectId")
                                            .ifBlank { o.optString("project_id") },
                                        title = o.optString("title").ifBlank { o.optString("name") },
                                        type = dt,
                                        typeLabel = when (dt) {
                                            "required" -> context.getString(R.string.dep_required)
                                            "optional" -> context.getString(R.string.dep_optional)
                                            "incompatible" -> context.getString(R.string.dep_incompatible)
                                            "embedded" -> context.getString(R.string.dep_embedded)
                                            else -> dt
                                        },
                                        provider = o.optString("provider").ifBlank { p.provider },
                                    )
                                }
                            }
                        }
                    }
                },
                onFailure = { e ->
                    installLoadingVersions = false
                    installError = context.getString(R.string.version_load_failed, e.message)
                },
            )
        }
    }

    fun doSearch() {
        if (!ready) return
        // ⚠️ 空关键词**允许**：进内容中心要能直接看到热门内容，
        // 而不是一面白墙等用户猜关键词。
        //
        // 用户反馈「内容默认加载不出来」就是这里造成的 ——
        // 旧 BrowseView onMounted 就 load（Modrinth 空 query = 按下载量
        // 排序的热门项目），我却写成空词直接 return。
        //
        // Rust browse 对空 query 的行为：facets 仍带 project_type 过滤，
        // 返回的就是热门列表（实测 curl 空 query 有数据）。
        val q = state.query.trim()
        page = 0
        hasMore = true
        state = state.copy(loading = true, error = null)
        repo.search(q, state.type, page = 0, loader = state.loader, sort = state.sort) { r ->
            state = r.fold(
                onSuccess = { state.copy(items = it, loading = false) },
                onFailure = { state.copy(loading = false, error = context.getString(R.string.search_failed, friendlyError(context, it))) },
            )
            // 首页返回不满一页 ⇒ 后面没有了（避免触底后空跑一次请求）
            hasMore = r.getOrNull()?.size == BROWSE_PAGE_SIZE
        }
    }

    // ── 把工具栏注册到顶栏（2026-10-17）──────────────────────────────
    //
    // 用户要求「搜索条要跟『内容』标题平着、右边一直到用户头像」。
    // 状态都在本函数里，而 TopAppBar 在 ComposeMainActivity 外层 ——
    // 靠 onRegisterToolbar 把一段 composable 递上去。
    //
    // ⚠️ 放在 doSearch() 定义**之后**：Kotlin 局部函数不能前向引用。
    //
    // ⚠️ 必须用 `SideEffect` 而不是 `DisposableEffect(Unit)`：
    //   - DisposableEffect(Unit) 只在**首次组合**执行一次，注册的 lambda 闭包
    //     捕获的是**那一刻的 state**（永远是初始值）—— 搜索框输入不会更新、
    //     紧凑模式点了没反应、筛选徽标永远 0。
    //   - SideEffect 每次成功重组后都跑，注册的 lambda 拿到的是最新 state。
    //     这里只是给外层赋一个 lambda 引用，代价极低。
    var filterOpen by remember { mutableStateOf(false) }
    SideEffect {
        onRegisterToolbar {
            com.zhayi.qookix.compose.pages.BrowseToolbar(
                title = context.getString(R.string.browse_title),
                query = state.query,
                onQueryChange = { state = state.copy(query = it) },
                onSearch = { doSearch() },
                onOpenFilter = { filterOpen = true },
                activeFilterCount = (if (state.type != BrowseType.Mod.id) 1 else 0) +
                    (if (state.loader.isNotBlank()) 1 else 0),
                sort = state.sort,
                onSortChange = {
                    state = state.copy(sort = it, items = emptyList())
                    doSearch()
                },
                compact = state.compact,
                onCompactChange = {
                    state = state.copy(compact = it)
                    // 记住选择（下次进内容中心还是这个模式）
                    activity?.setBrowseCompactPref(it)
                },
            )
        }
    }
    // 离开内容页时清空顶栏（否则切到别的页面顶栏还挂着搜索框）
    DisposableEffect(Unit) {
        onDispose { onRegisterToolbar(null) }
    }

    /** 加载下一页并**追加**（不是替换）。 */
    fun loadMore() {
        // ⚠️ 原来这里还有 `|| state.query.isBlank()` 的门槛，等于
        // 「空关键词（= 进页看热门列表）永远翻不了页」—— 而空关键词
        // 恰恰是内容中心最常驻的状态。既然 doSearch 允许空词，这里就必须允许。
        if (!ready || loadingMore || !hasMore || state.loading) return
        loadingMore = true
        val next = page + 1
        // type/loader 一起带上：用户在翻页途中换了筛选，
        // 请求必须和当前列表同源，否则会把别的内容追加进来。
        repo.search(state.query.trim(), state.type, page = next, loader = state.loader, sort = state.sort) { r ->
            loadingMore = false
            r.fold(
                onSuccess = { more ->
                    // 不满一页 ⇒ 到底了（对齐旧 BrowseView 的
                    // `if (!r.hits.length || hits.length >= r.total) done = true`；
                    // 这里拿不到 total，用页大小判断 —— 20 条一页，够用且不误判）
                    hasMore = more.size >= BROWSE_PAGE_SIZE
                    if (more.isEmpty()) {
                        // 没有更多了：记一下，免得用户反复点
                        state = state.copy(error = null)
                    } else {
                        page = next
                        // 按 id 去重再追加 —— Modrinth/CurseForge 分页边界上
                        // 偶尔会重复返回同一项，直接追加会出现重复卡片
                        val existing = state.items.map { it.id }.toSet()
                        state = state.copy(
                            items = state.items + more.filter { it.id !in existing },
                        )
                    }
                },
                onFailure = { e -> state = state.copy(error = context.getString(R.string.load_more_failed, e.message)) },
            )
        }
    }

    // 切换类型 / 加载器时自动重搜（与旧界面行为一致）
    LaunchedEffect(state.type, state.loader) {
        // ⚠️ 进页无条件加载（对齐旧 BrowseView 的 onMounted）：
        // 空关键词 = 热门列表（Modrinth 空查询按下载量排序）。
        // 之前的 isNotBlank 判断就是「内容中心默认加载不出来」的直接原因。
        doSearch()
    }

    BrowsePage(
        state = state,
        onQueryChange = { state = state.copy(query = it) },
        onSearch = { doSearch() },
        onTypeChange = {
            state = state.copy(type = it, items = emptyList())
            // ⚠️ 换类型后必须重搜（2026-10-17）：原来只清空列表不重拉，
            // 用户切到「整合包」看到的是一片空白，以为没有内容。
            doSearch()
        },
        onLoaderChange = {
            state = state.copy(loader = it, items = emptyList())
            doSearch()
        },
        // 排序变更：同样要重搜（排序是服务端行为，本地排没用）
        onSortChange = {
            state = state.copy(sort = it, items = emptyList())
            doSearch()
        },
        // 紧凑模式切换：只改 UI，不重新请求（同一批数据换种排布）
        onCompactChange = { state = state.copy(compact = it) },
        onOpenProject = { p ->
            // 应用内项目详情（2026-10-21 补：projectInfo 一直没 UI，原来只跳浏览器）
            projectDetailLoading = true
            projectDetail = null
            QookixApi(bridge).projectInfo(p.provider, p.id) { r ->
                projectDetailLoading = false
                r.onSuccess { projectDetail = it }
            }
        },
        onInstall = { p ->
            // ── 打开安装对话框 ────────────────────────────────────────
            //
            // 这是「装模组」的唯一路径（之前完全缺失：卡片只能打开浏览器，
            // 而 install_content 这条命令连 API 层都没有）。
            installTarget = p
            installError = null
            installDescZh = null
            installVersion = null
            installTypeFilter = "all"
            installVersions = emptyList()
            // 拉目标候选。
            //
            // ⚠️ 2026-10-18 改：原来只有插件才拉服务器列表，模组只拉实例。
            // 但模组装到服务器是支持的（`servers/<id>/mods/`），
            // 不拉服务器列表 → 对话框永远显示「还没有服务器」→ 选了也没用。
            val canTargetServer = state.type == "plugin" || state.type == "mod"
            if (canTargetServer) {
                api.listHostedServers { r ->
                    r.onSuccess { arr ->
                        // 复用 MultiplayerRepo 的解析（原来这里抄了一份内联版）
                        installServers = MultiplayerRepo.parseServers(arr)
                        installServerId = installServers.firstOrNull()?.id
                    }
                }
            }
            // 模组和「其它」都要拉实例列表（模组两个目标都能选）
            if (state.type != "plugin") {
                api.listInstances { r ->
                    r.onSuccess { arr ->
                        installInstances = (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            InstallTargetUi(
                                id = o.optString("id"),
                                name = o.optString("name").ifBlank { o.optString("version_id") },
                                loader = o.optString("loader"),
                                mcVersion = o.optString("version_id"),
                            )
                        }
                        // 默认选第一个可用的（非 vanilla）
                        installInstanceId = installInstances
                            .firstOrNull { it.loader != "vanilla" }?.id
                        loadInstallVersions(p)
                    }
                }
            }
            // 顺带拉译文（用 slug，不用 id）
            installDescLoading = true
            api.translateModDescriptions(p.provider, listOf(p.id)) { r ->
                installDescLoading = false
                r.onSuccess { o ->
                    val t = o.optJSONObject("translations")
                    installDescZh = t?.optString(p.id)?.takeIf { it.isNotBlank() }
                }
            }
        },
        onLoadMore = { loadMore() },
        loadingMore = loadingMore,
        hasMore = hasMore,
        onTranslatePage = {
            // 整页翻译（B1）：把当前页所有项目的简介批量翻成中文。
            // `translate_mod_descriptions` 返回 { translations: { "<id>": "译文" } }。
            val items = state.items
            if (items.isNotEmpty() && !translatingPage) {
                translatingPage = true
                api.translateModDescriptions(items.first().provider, items.map { it.id }) { r ->
                    translatingPage = false
                    r.fold(
                        onSuccess = { o ->
                            val t = o.optJSONObject("translations") ?: org.json.JSONObject()
                            state = state.copy(
                                items = state.items.map { p ->
                                    val zh = t.optString(p.id)
                                    if (zh.isNotBlank()) p.copy(description = zh) else p
                                },
                            )
                        },
                        onFailure = { e ->
                            state = state.copy(error = context.getString(R.string.browse_translate_failed, e.message ?: ""))
                        },
                    )
                }
            }
        },
        translatingPage = translatingPage,
    )

    // ── 安装对话框 ──────────────────────────────────────────────────────
    installTarget?.let { p ->
        val isPlugin = state.type == "plugin"
        InstallDialog(
            project = p,
            kind = state.type,
            isPlugin = isPlugin,
            versions = installVersions.filter { installTypeFilter == "all" || it.type == installTypeFilter },
            loadingVersions = installLoadingVersions,
            selectedVersion = installVersion,
            instances = installInstances,
            servers = installServers,
            selectedInstanceId = installInstanceId,
            selectedServerId = installServerId,
            typeFilter = installTypeFilter,
            deps = installDeps,
            descZh = installDescZh,
            descLoading = installDescLoading,
            installing = installing,
            error = installError,
            onSelectVersion = { v ->
                installVersion = v
                // 换版本重拉依赖
                api.projectDependencies(p.provider, p.id, v) { dr ->
                    dr.onSuccess { arr ->
                        installDeps = (0 until arr.length()).mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            DependencyUi(
                                projectId = o.optString("projectId"),
                                title = o.optString("title").ifBlank { o.optString("name") },
                                type = o.optString("dependencyType"),
                                typeLabel = context.getString(R.string.deps_title),
                                provider = o.optString("provider").ifBlank { p.provider },
                            )
                        }
                    }
                }
            },
            // 目标二选一：不清掉另一侧的话，两个 id 同时非空 → 模组会被装进服务器目录
            onSelectInstance = { id ->
                installInstanceId = id
                installServerId = null
                loadInstallVersions(p)
            },
            onSelectServer = { installServerId = it; installInstanceId = null },
            onTypeFilterChange = { installTypeFilter = it },
            onOpenDependency = { d ->
                // 切到那个依赖项目（在同一个对话框里）
                installTarget = ProjectUi(
                    id = d.projectId, provider = d.provider, title = d.title,
                    description = "", author = "", iconUrl = "", downloads = 0,
                )
                loadInstallVersions(installTarget!!)
            },
            onTranslate = {
                installDescLoading = true
                api.translateModDescriptions(p.provider, listOf(p.id)) { r ->
                    installDescLoading = false
                    r.onSuccess { o ->
                        val t = o.optJSONObject("translations")
                        installDescZh = t?.optString(p.id)?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.translation_none)
                    }
                }
            },
            onCreateTargetInstance = onRequestCreateInstance?.let { cb ->
                {
                    // 先关掉安装对话框，再跳去新建实例页 ——
                    // 否则回来时对话框还开着，用户会以为没生效。
                    installTarget = null
                    cb()
                }
            },
            onInstall = {
                if (installing) return@InstallDialog
                val vid = installVersion
                val iid = installInstanceId
                // ── 装到服务器 ────────────────────────────────────────────
                //
                // 2026-10-18 改。原来只有「插件」走服务器分支（Hangar），
                // 模组选了服务器也不生效 —— 会被下面的「请选择实例与版本」挡住。
                //
                // 用户问「fabric 服务器能直接在内容中心装 mod 吗」，查证：不能。
                // 现在模组也走服务器分支，落到 `servers/<id>/mods/`。
                val sid = installServerId
                val wantsServer = sid != null && (isPlugin || state.type == "mod")
                if (wantsServer) {
                    if (isPlugin) {
                        installing = true
                        api.installServerPlugin(sid!!, p.id, p.title) { r ->
                            installing = false
                            r.fold(
                                onSuccess = { installTarget = null },
                                onFailure = { e -> installError = context.getString(R.string.install_failed, e.message) },
                            )
                        }
                        return@InstallDialog
                    }
                    // 模组：需要版本号（插件那条路由 Hangar 自己挑版本）
                    if (vid == null) {
                        installError = context.getString(R.string.install_pick_server_version)
                        return@InstallDialog
                    }
                    installing = true
                    installError = null
                    api.installServerContent(sid!!, p.provider, p.id, vid, state.type) { r ->
                        installing = false
                        r.fold(
                            onSuccess = { installTarget = null },
                            onFailure = { e -> installError = context.getString(R.string.install_failed, e.message) },
                        )
                    }
                    return@InstallDialog
                }
                if (iid == null || vid == null) {
                    installError = context.getString(R.string.install_pick_instance_version)
                    return@InstallDialog
                }
                installing = true
                installError = null
                api.installContent(iid, p.provider, p.id, vid, state.type) { r ->
                    installing = false
                    r.fold(
                        onSuccess = { installTarget = null },
                        onFailure = { e -> installError = context.getString(R.string.install_failed, e.message) },
                    )
                }
            },
            onDismiss = { installTarget = null },
        )
    }
    
    // ── 筛选面板（2026-10-18 修：原来只设 filterOpen=true，没人读它，
    //    筛选图标点了没反应。组件早就写好了，就差这一处渲染）
    com.zhayi.qookix.compose.pages.BrowseFilterSheet(
        visible = filterOpen,
        onDismiss = { filterOpen = false },
        type = state.type,
        onTypeChange = { newType ->
            filterOpen = false
            if (newType != state.type) {
                state = state.copy(type = newType)
                doSearch()
            }
        },
        loader = state.loader,
        onLoaderChange = { newLoader ->
            filterOpen = false
            if (newLoader != state.loader) {
                state = state.copy(loader = newLoader)
                doSearch()
            }
        },
        // 插件（Hangar）没有加载器维度
        hideLoader = state.type == BrowseType.Plugin.id,
    )

    // 项目详情对话框（2026-10-21 补）
    projectDetail?.let { info ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { projectDetail = null; projectBodyZh = null; projectBodyError = null },
            title = { androidx.compose.material3.Text(info.optString("title")) },
            text = {
                androidx.compose.foundation.layout.Column {
                    val author = info.optString("author")
                    if (author.isNotBlank()) {
                        androidx.compose.material3.Text("作者：$author", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    val downloads = info.optLong("downloads", 0)
                    if (downloads > 0) {
                        androidx.compose.material3.Text("下载量：$downloads", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    val desc = info.optString("description")
                    if (desc.isNotBlank()) {
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.material3.Text(desc, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                    val body = info.optString("body")
                    val shown = projectBodyZh ?: body
                    if (shown.isNotBlank()) {
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.material3.Text(
                            shown.take(500) + if (shown.length > 500) "…" else "",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            maxLines = 10,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    if (projectBodyTranslating) {
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.material3.Text(
                            context.getString(R.string.browse_body_translating),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    projectBodyError?.let { err ->
                        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(8.dp))
                        androidx.compose.material3.Text(
                            err,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { projectDetail = null; projectBodyZh = null; projectBodyError = null }) {
                    androidx.compose.material3.Text(context.getString(R.string.instances_group_cancel))
                }
            },
            dismissButton = {
                // 正文翻译（D1）：只对能翻译的数据源显示按钮
                val provider = info.optString("provider")
                val slug = info.optString("slug")
                androidx.compose.foundation.layout.Row {
                    // MC 百科跳转（D7）：`mc_wiki_url` 一直没 UI
                    val title = info.optString("title")
                    if (title.isNotBlank()) {
                        androidx.compose.material3.TextButton(onClick = {
                            api.mcWikiUrl(title, slug.ifBlank { null }, provider.ifBlank { null }) { r ->
                                r.onSuccess { url ->
                                    if (url.isNotBlank()) activity?.openExternalUrl(url)
                                }
                            }
                        }) {
                            androidx.compose.material3.Text(context.getString(R.string.browse_open_wiki))
                        }
                    }
                    // 翻译质量反馈（D1）：译文出来后给「翻译不准」入口
                    if (projectBodyZh != null) {
                        androidx.compose.foundation.layout.Row {
                            androidx.compose.material3.TextButton(onClick = {
                                api.reportTranslationQuality(provider, slug, "inaccurate") { }
                                projectBodyError = context.getString(R.string.browse_body_report_thanks)
                            }) {
                                androidx.compose.material3.Text(context.getString(R.string.browse_body_report_bad))
                            }
                            // 「译文过时」是另一个后端能力（report_translation_stale）：
                            // 服务端确认后会清掉本地译文缓存，下次重新翻。
                            if (provider.isNotBlank() && slug.isNotBlank()) {
                                androidx.compose.material3.TextButton(onClick = {
                                    api.reportTranslationStale(provider, slug) { }
                                    projectBodyError = context.getString(R.string.browse_body_report_stale_thanks)
                                }) {
                                    androidx.compose.material3.Text(context.getString(R.string.browse_body_report_stale))
                                }
                            }
                        }
                    } else if (provider.isNotBlank() && slug.isNotBlank()) {
                        androidx.compose.material3.TextButton(
                            enabled = !projectBodyTranslating,
                            onClick = {
                                projectBodyTranslating = true
                                projectBodyError = null
                                api.translateProjectBody(provider, slug, true) { r ->
                                    projectBodyTranslating = false
                                    r.fold(
                                        onSuccess = { o ->
                                            val supported = o.optBoolean("supported", false)
                                            val zh = o.optString("body").takeIf { it.isNotBlank() }
                                            val err = o.optString("error").takeIf { it.isNotBlank() }
                                            when {
                                                zh != null -> projectBodyZh = zh
                                                err != null -> projectBodyError = err
                                                !supported -> projectBodyError = context.getString(R.string.browse_body_translate_unsupported)
                                                else -> projectBodyError = context.getString(R.string.browse_body_translate_empty)
                                            }
                                        },
                                        onFailure = { e ->
                                            projectBodyError = context.getString(R.string.browse_translate_failed, e.message ?: "")
                                        },
                                    )
                                }
                            },
                        ) {
                            androidx.compose.material3.Text(context.getString(R.string.browse_body_translate))
                        }
                    }
                }
            },
        )
    }
}

/** 新闻页 + 数据加载。这是 Phase 1 的桥验证点。 */
@Composable
private fun NewsRoute(
    bridge: RustBridge,
    ready: Boolean,
    openUrl: ((String) -> Unit)? = null,
) {
    var state by remember { mutableStateOf(NewsUiState(loading = true)) }

    // 首次进入自动加载（与旧实现 onMounted 的行为一致）
    LaunchedEffect(ready) {
        if (ready) loadNews(bridge, state) { state = it }
    }

    NewsPage(
        state = state,
        // 带上已有内容：刷新失败不该把已经读到的新闻清空
        onRefresh = { if (ready) loadNews(bridge, state) { state = it } },
        onOpen = { url ->
            // 用系统浏览器打开原文。不用内嵌 WebView 看新闻 —— 那又要留一个
            // WebView，而且系统浏览器有阅读模式、翻译、稍后读。
            // 注意 NewsPage 传过来的**已经是 url 字符串**（见 NewsPage.kt 的 onOpen(n.url)）。
            url.takeIf { it.isNotBlank() }?.let { openUrl?.invoke(it) }
        },
    )
}

/** 未迁移页面的占位。 */
@Composable
private fun PlaceholderPage(dest: NavDestination) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(QkSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    val context = androidx.compose.ui.platform.LocalContext.current
        Text(
            text = stringResource(dest.labelRes),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(QkSpacing.sm))
        Text(
            text = context.getString(R.string.page_wip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(QkSpacing.xxs))
        Text(
            text = context.getString(R.string.page_wip_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * 应用版本号。
 *
 * 从 APK 的 PackageInfo 读 —— 与 `tauri.conf.json` 的 version 一致
 * （构建时由 tauri 写进 versionName）。
 */
private fun currentAppVersion(ctx: android.content.Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ctx.getString(R.string.unknown)
}.getOrDefault(ctx.getString(R.string.unknown))





























































































/**
 * 危险操作的待确认项。
 *
 * 用法：[PageHost] 里把「真正要执行的动作」存进 lambda，先弹确认框，
 * 用户点确认后才 `action()`。这样**执行路径不会绕过确认** ——
 * 比在每个调用点各写一遍 if/else 安全。
 */
data class DangerConfirm(
    val title: String,
    val message: String,
    val confirmText: String = "",
    val action: () -> Unit,
)

































/**
 * 「新建服务器」页面的状态容器。
 *
 * ⚠️ 版本清单**按核心类型分别取**：Paper 用后端自己的支持清单，
 * Vanilla / Fabric 用官方 version manifest。切换核心时要重新拉，
 * 否则会出现「选了 Paper 却看到原版才有的版本」。
 */
@Composable
private fun ServerCreateRoute(
    bridge: RustBridge,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    var state by remember { mutableStateOf(ServerCreateUiState()) }

    // 拉版本清单。key 用 core —— 换核心就重拉。
    LaunchedEffect(state.core) {
        // 换核心时必须**清空已选版本** ——
        // 否则会出现「选了 Paper 但 version 还是原版才有的版本」，
        // 提交后 Rust 那边 404。
        state = state.copy(loadingVersions = true, versionsError = null, version = "")
        loadServerVersions(api, state.core) { r ->
            r.fold(
                onSuccess = { list ->
                    // ⚠️ 不自动选中。用户要求「只能从右面选择」——
                    // 自动帮选最新也是替他做决定：开服版本错了要重下几百 MB。
                    state = state.copy(loadingVersions = false, versions = list)
                },
                onFailure = { e ->
                    state = state.copy(
                        loadingVersions = false,
                        versions = emptyList(),
                        versionsError = context.getString(R.string.server_version_load_failed, e.message),
                    )
                },
            )
        }
    }

    ServerCreatePage(
        state = state,
        onNameChange = { state = state.copy(name = it) },
        onCoreChange = { state = state.copy(core = it) },
        onVersionChange = { state = state.copy(version = it) },
        onBack = onBack,
        onCreate = {
            state = state.copy(creating = true, createError = null)
            api.createHostedServer(state.name.trim(), state.core, state.version.trim()) { r ->
                state = state.copy(creating = false)
                r.fold(
                    onSuccess = { onCreated() },
                    onFailure = { e -> state = state.copy(createError = e.message.orEmpty()) },
                )
            }
        },
    )
}


