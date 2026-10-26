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
 * PageHost 里几个「自成一体」的页面 / 对话框。
 *
 * 从 PageHost.kt 拆出（同包，符号不用 import）：新闻页、占位页、
 * 重命名对话框、文件浏览页。纯搬运，逻辑没动，private 改 internal。
 */

/** 重命名对话框。
 *
 * 单独抽出来是因为状态必须**活在对话框自己的作用域里** ——
 * 写在 AlertDialog 的 text lambda 里没法把输入值回传给 confirmButton。
 */
@Composable
internal fun RenameDialog(
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


/**
 *
 * 同一份页面代码服务两种场景，只是 [ops] 不同 ——
 * 这是相对旧界面（两份复制粘贴的组件）的主要改进。
 *
 * 内部两层：文件列表 ↔ 文件编辑器（编辑时覆盖列表）。
 */
@Composable
internal fun FileBrowserRoute(
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


/** 新闻页 + 数据加载。这是 Phase 1 的桥验证点。 */
@Composable
internal fun NewsRoute(
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
internal fun PlaceholderPage(dest: NavDestination) {
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
