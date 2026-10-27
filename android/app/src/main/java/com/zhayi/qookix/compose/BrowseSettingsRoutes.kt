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
import com.zhayi.qookix.compose.pages.ContentDetailRoute
import com.zhayi.qookix.compose.pages.ContentSelection
import com.zhayi.qookix.compose.pages.ContentInstallHost
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
 * 内容浏览页 / 设置页。
 *
 * 从 PageHost.kt 拆出（同包，符号不用 import）。两页体量最大，但输入明确
 *（bridge + 回调）、状态各自 remember。纯搬运，private 改 internal。
 */

/** 设置页（8 个子分组）。 */
@Composable
internal fun SettingsRoute(
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
                    // ⚠️ 固定 compose：`webview` 那条链路已经死了（`findTauriWebView`
                    // 恒 null），选它只会得到一块空白预览且没有任何说明（审查 P1-9）。
                    // 设置页的选项也一并撤掉了，等链路真的接通再放出来。
                    it.copy(loading = false, previewRenderMode = "compose")
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


/** 内容中心 + 搜索状态。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun BrowseRoute(
    bridge: RustBridge,
    ready: Boolean,
    openUrl: ((String) -> Unit)? = null,
    onRequestCreateInstance: (() -> Unit)? = null,
    /** 向顶栏注册自定义内容（搜索/筛选/排序/紧凑）。 */
    onRegisterToolbar: ((@Composable () -> Unit)?) -> Unit = {},
    /** 宿主 Activity（读写内容中心的紧凑模式偏好）。 */
    activity: ComposeMainActivity? = null,
    /**
     * 点条目 → 打开内容详情（2026-10-27：从弹窗改成独立页面）。
     *
     * 第二个参数是内容类型（mod / resourcepack / …），详情页安装时要它。
     */
    onOpenContent: (ProjectUi, String) -> Unit = { _, _ -> },
    /**
     * 当前打开的内容详情（null = 显示列表）。
     *
     * ⚠️ 详情页**画在本函数内部**：列表的 items / 分页 / 滚动位置都在这里，
     * 若交给 PageHost 的另一个 when 分支，返回时整个内容中心会重建，
     * 列表要重新拉、滚动位置也回到顶部。
     */
    selection: ContentSelection? = null,
    /** 详情页把资源名报到顶栏。 */
    onTitleChange: (String?) -> Unit = {},
    /** 进行中的任务快照（详情页显示安装进度，复用外层 TasksRepo）。 */
    tasks: DownloadsUiState = DownloadsUiState(),
    onCancelTask: (Long) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val repo = remember(api) { BrowseRepo(api) }

    // 列表网格的滚动状态：提到这里才能在「进详情 → 返回」之间存活
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

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

    // ── 安装对话框 ──────────────────────────────────────────────────────
    //
    // 只记「要装哪个项目」。版本筛选 / 依赖 / 目标实例与服务器、安装请求
    // 全部在 ContentInstallHost 里 —— 详情页用的是同一个宿主，
    // 两处各维护一份的话必然改一处漏一处。
    var installTarget by remember { mutableStateOf<ProjectUi?>(null) }

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
        // 详情页开着时顶栏要让位（否则「内容」标题 + 搜索框会盖在资源名上）
        if (selection != null) {
            onRegisterToolbar(null)
        } else {
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

    // ── 内容详情（2026-10-27：从弹窗改成独立页面）────────────────────────
    //
    // ⚠️ 详情页画在**本函数内部**，而不是 PageHost 的另一个 when 分支：
    // 列表 items / 分页 / gridState 都声明在这里，只有保持同一份组合，
    // 「返回内容中心」时列表和滚动位置才原样还在。
    if (selection != null) {
        ContentDetailRoute(
            bridge = bridge,
            selection = selection,
            activity = activity,
            tasks = tasks.active,
            onCancelTask = onCancelTask,
            onTitleChange = onTitleChange,
            onRequestCreateInstance = onRequestCreateInstance,
        )
        return
    }

    BrowsePage(
        state = state,
        // 滚动状态由本函数持有，进详情再返回不会回到顶部
        gridState = gridState,
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
        // 点条目 → 独立详情页（2026-10-27：原来是一个正文只显示 500 字的弹窗）
        onOpenProject = { p -> onOpenContent(p, state.type) },
        // 卡片上的「安装」：直接弹安装对话框，不走详情页
        onInstall = { p -> installTarget = p },
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
    //
    // 版本筛选 / 依赖 / 目标实例与服务器 / 真正的安装请求都在
    // ContentInstallHost 里 —— 内容详情页共用同一份，别再各写一套。
    ContentInstallHost(
        api = api,
        project = installTarget,
        kind = state.type,
        onCreateTargetInstance = onRequestCreateInstance?.let { cb ->
            {
                // 先关掉安装对话框，再跳去新建实例页 ——
                // 否则回来时对话框还开着，用户会以为没生效。
                installTarget = null
                cb()
            }
        },
        onDismiss = { installTarget = null },
    )

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

}
