package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import com.zhayi.qookix.compose.bridge.*

import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.zhayi.qookix.compose.ui.QkInlineError
import com.zhayi.qookix.compose.ui.QkTabRow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import com.zhayi.qookix.compose.motion.qkSlideInLeft
import com.zhayi.qookix.compose.motion.qkSlideInRight
import com.zhayi.qookix.compose.motion.qkFadeOut
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.StringRes
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.SkeletonList
import androidx.compose.foundation.shape.RoundedCornerShape
import com.zhayi.qookix.compose.theme.QkRadius
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import com.zhayi.qookix.compose.motion.SharedHero
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.zhayi.qookix.compose.motion.QookiX

/**
 * 实例详情。
 *
 * 对应旧界面 `InstanceDetailView.vue`（533 行）+ 7 个子组件
 * （ContentTab 928 / SavesTab 868 / SettingsTab 820 / KeysTab 662 行）。
 *
 * ── 设计（完全重做）─────────────────────────────────────────────────────
 * 旧界面用**左侧竖排 tab**。横屏下 7 个 tab 竖排会占掉 ~160px 宽度，
 * 而内容区本来就不宽；改用**顶部 TabRow + 横向滚动**：
 * 高度只占 ~48px，宽度可以滑，内容区完整拿回。
 *
 *   ┌──────────────────────────────────────────────────┐
 *   │ ←  26.3  [原版]                    [▶ 启动游戏]  │
 *   ├──────────────────────────────────────────────────┤
 *   │ 内容 │ 存档 │ 按键 │ 设置 │ 文件 │ 日志 │ 崩溃    │ ← 可横滑
 *   ├──────────────────────────────────────────────────┤
 *   │  （当前 tab 的内容）                              │
 *   └──────────────────────────────────────────────────┘
 *
 * 「文件」tab 复用**同一个**文件管理器页面（`FileOps` 抽象），
 * 不再像旧界面那样为实例文件单独写一份。
 */
@Composable
fun InstanceDetailPage(
    state: InstanceDetailUiState,
    onTabChange: (InstanceTab) -> Unit,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    onOpenFiles: () -> Unit,
    /** 游戏文件检查报告：非 null 表示缺文件，显示提示条 + 「补全缺失文件」按钮。 */
    fileReport: FileReportUi? = null,
    /** 触发补全缺失文件（走 repair_instance_files）。 */
    onRepair: (() -> Unit)? = null,
    /** 补全进行中（按钮显示转圈）。 */
    repairing: Boolean = false,
    /** 补全进度：当前阶段文案（来自 install://progress 的 message） */
    repairStage: String = "",
    /** 补全进度：0..1；负值表示未知（画不确定进度条） */
    repairFraction: Float = -1f,
    /**
     * 这个实例**正在进行中**的任务（下载 / 安装 / 补全文件）。
     *
     * ⚠️ 之前详情页完全看不到下载进度、也没有取消入口 ——
     * 用户必须切到下载页才找得到（老 Vue 的 `InstanceDetailView.vue`
     * 是有一条 `installTask` 提示条的，Compose 版漏了）。
     * 现在由 `TasksRepo.activeTaskFor(instanceId)` 提供，非 null 就在这里
     * 画一条带进度条 + 「取消」的提示条。null 表示这个实例没有在跑的任务。
     */
    instanceTask: TaskUi? = null,
    /** 取消 [instanceTask]（调 `cancel_install`）。 */
    onCancelTask: (() -> Unit)? = null,
    /** 复制文本到剪贴板（崩溃报告等求助内容）。 */
    onCopyCrashText: ((String) -> Unit)? = null,
    /** 查看崩溃报告原文（2026-10-21 补）。 */
    onViewCrashRaw: (String, (Result<String>) -> Unit) -> Unit = { _, _ -> },
    /** 文件操作实现（实例侧）—— 由外层构造，因为它需要 instanceId。 */
    fileOps: FileOps,
    // 存档：(世界名, 备份文件名) —— 必须带世界名，否则多世界实例会恢复到错的世界
    onBackup: (String) -> Unit,
    onRestore: (String, String) -> Unit,
    onDeleteBackup: (String, String) -> Unit,
    /** 快速进入某个世界（quick-play，直接带着世界启动）。 */
    onLaunchWorld: ((String) -> Unit)? = null,
    /** 正在启动的世界名。 */
    launchingWorld: String? = null,
    // 按键
    onLayoutSelect: (String) -> Unit,
    onLayoutEdit: (String) -> Unit,
    onLayoutRename: (String) -> Unit,
    onLayoutDuplicate: (String) -> Unit,
    onLayoutDelete: (String) -> Unit,
    onLayoutImport: () -> Unit,
    onLayoutExport: (String) -> Unit = {},
    /** 拉当前布局的按键列表（D2）。 */
    onLoadControlButtons: () -> Unit = {},
    /** 改某个按键的透传开关（D2）。 */
    onSetControlPassthru: (Int, Boolean) -> Unit = { _, _ -> },
    // 内容
    onToggleContent: (ContentUi, Boolean) -> Unit,
    onUninstallContent: (ContentUi) -> Unit,
    onCheckUpdates: () -> Unit,
    /**
     * 识别未识别的内容（异步：结果通过 `content::identified` 事件逐个推回来）。
     *
     * 用途：手动放进 mods 目录的模组没有元数据（没有名称/图标/来源），
     * 这个功能按 sha1 去 Modrinth/CurseForge 反查补上。
     */
    onIdentify: () -> Unit = {},
    /** 识别进行中（按钮转圈）。 */
    identifying: Boolean = false,
    /** 切换内容分类（审计 H-2）。 */
    onContentKindChange: (String) -> Unit = {},
    /** 「导入文件」：选本地文件复制进当前分类目录（mods / resourcepacks / shaderpacks）。 */
    onImportContent: (() -> Unit)? = null,
    /**
     * 共享游戏文件占用（「文件」页顶部显示）。
     *
     * 为什么要有它：游戏本体**不在实例目录里** —— 刚建的实例目录只有一个
     * instance.json，用户打开「文件」看到空空如也，会以为「什么都没下」
     *（旧 UI 注释里记过这个真机反馈）。
     */
    sharedUsage: SharedUsageUi? = null,
    onApplyUpdate: (ContentUi) -> Unit,
    onLoadLog: () -> Unit,
    onExportLog: () -> Unit = {},
    /** D5a「另存为」：把日志文本存成 .txt（弹系统新建文档对话框）。 */
    onSaveLog: (() -> Unit)? = null,
    onLoadCrashes: () -> Unit,
    onAnalyzeCrash: (String) -> Unit,
    // 实例设置 tab
    settings: InstanceSettingsUiState = InstanceSettingsUiState(),
    onSettingsChange: (InstanceSettingsPatch) -> Unit = {},
    onSettingsSave: () -> Unit = {},
    settingsSaving: Boolean = false,
    settingsHint: String? = null,
    settingsError: String? = null,
    onDismissSettingsHint: () -> Unit = {},
    // ── 导出整合包（实例设置 tab 底部）────────────────────────────────
    exportPlan: ExportPlanUi? = null,
    exportPlanLoading: Boolean = false,
    exporting: Boolean = false,
    exportHint: String? = null,
    onExport: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> },
) {
    Column(modifier = Modifier.fillMaxSize()) {
        state.error?.let { QkInlineError(it) }

        // ── Hero：图标 + 名称 + 版本（2026-10-27 新增）──────────────────
        //
        // 两个用途：
        //   1. 详情页顶部原来直接就是提示条 / tab，没有「这是哪个实例」的视觉锚点
        //      （标题 2026-10-17 搬进外层贯穿顶栏了，页内就空了）
        //   2. **共享元素转场的落点** —— 实例卡片上的图标会飞到这里
        //
        // 刻意做紧凑（48dp 图标，总高约 56dp）：横屏可用高度只有 320dp 左右，
        // 72dp 的大 hero 会把内容区压掉一截。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.lg, vertical = QkSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 飞行期间 hero 是**透明的**，但仍然占位、仍然测量位置：
            //  - 占位是必须的：落点坐标靠 onGloballyPositioned 测，真隐藏了就没落点；
            //  - 透明是必须的：飞到位之前不让真实 hero 出现，否则会看到两个图标重叠
            //    （用户：「等他飞到位之后，那个实例的东西再加载出来」）。
            // 落地后 SharedHero.clear() 把 pending 置空，hero 淡入。
            // ⚠️ 落地瞬间**硬切换**，不做淡入：浮层与真实 hero 的位置、尺寸、颜色
            // 完全一致，淡入会让两者在半透明的那几帧里同时露背景 —— 用户看到的
            // 就是「到位之后闪一下」。
            val heroAlpha = if (SharedHero.pending == null) 1f else 0f
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer { alpha = heroAlpha }
                    .onGloballyPositioned {
                        // 上报**落点**（窗口坐标）：上游的飞行浮层拿它当目标位置。
                        // 只在真有飞行任务时才写，避免平时无谓地触发重组。
                        if (SharedHero.pending != null) {
                            // 测量「点击 → hero 就位」的耗时。
                            // 用户反馈的「图标先卡一下，等那边加载完才飞」就是这个数 ——
                            // 组合是单线程的，详情页组合完之前任何 effect 都跑不了。
                            val dt = (System.nanoTime() - SharedHero.launchedAtNanos) / 1_000_000
                            android.util.Log.i("QkPerf", "hero 就位耗时 ${dt}ms")
                            SharedHero.updateLanding(it.boundsInWindow())
                        }
                    },
                shape = RoundedCornerShape(QkRadius.medium),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = state.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(QkSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                // 名字里自带「（1.21.1）3.1.2」这类后缀，和下面的版本信息重复，
                // 而且在 48dp 图标旁边会把名字挤断。拆开显示：
                //   第一行：纯实例名（月落花野）
                //   第二行：加载器 + MC 版本（NeoForge 21.1.251 · 1.21.1）
                val displayName = state.name
                    .substringBefore("（").substringBefore("(")
                    .trim()
                    .ifBlank { state.name }
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // subtitle 现在已经是「Fabric 0.16.0 · 1.21.1」（见 loadInfo）——
                // 不再从名字里正则提取 MC 版本，否则会和它重复。
                val meta = state.subtitle
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 缺文件时的**唯一常驻入口**：hero 行末尾一个小红按钮。
            // 原来是页面顶上一整块红卡，横屏能吃掉半个屏幕（用户报「看不到下面」）。
            val fr = fileReport
            if (fr != null && !repairing && onRepair != null) {
                Spacer(Modifier.width(QkSpacing.sm))
                TextButton(onClick = onRepair) {
                    Text(
                        text = stringResource(
                            R.string.instance_detail_files_incomplete_short,
                            (fr.total - fr.ok).coerceAtLeast(1),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // ── 顶栏已上移（2026-10-17）────────────────────────────────────
        //
        // 原来这里是一整块自绘标题栏（返回箭头 + 实例名 + 版本 + 启动/修复按钮），
        // 和外层 LauncherShell 的 TopAppBar **重复显示两遍返回箭头和标题**
        // （用户实测指出「左边也有一个返回，两套叠在一起不太好」）。
        //
        // 现在那套内容搬进了顶栏的 title 槽位（见 PageHost 里注册的
        // InstanceDetailToolbar），这里直接删掉 —— 省下约 50dp 竖向空间。
        // ── 游戏文件缺失提示条 ──────────────────────────────────────────
        //
        // 对应旧 UI `InstanceDetailView.vue` 的 `fileReport` 提示条。
        //
        // ⚠️ 为什么这个必须有（2026-10-13 实测定位）：
        // 创建实例的后台安装**中途失败**（网络断流 / 进程被杀 / 磁盘满）时，
        // 实例会停在「只有 instance.json + 一堆空目录」的状态。
        //
        // 而 `check_instance_files` 检查的是 5 个**粗粒度**关键项，
        // 实测它会把「缺 10 个 jar」判成 `ok:5 total:5 can_launch:true` ——
        // 于是前端不触发补全，用户点启动直接崩在
        // `NoClassDefFoundError: com/google/gson/TypeAdapter`，
        // 而且**没有任何提示指向"文件没下完"**。
        //
        // 这个按钮是**用户唯一的恢复入口**：`repair_instance_files`
        // 是逐文件校验的（比 check 细致），即使自动检查漏报，手动点也能补。
        // ── 这个实例正在下载 / 安装 ─────────────────────────────────────
        //
        // 对应老 Vue `InstanceDetailView.vue` 的 `installTask` 提示条
        //（那一条带进度条 + 取消按钮）。
        //
        // ⚠️ 顺序很重要：它必须画在下面的「游戏文件不完整」提示条**之前**，
        // 并且**互斥**——用户刚建完实例点进来，后台正在下载，如果这时
        // 显示「缺 4 项，点补全」，他会以为启动器坏了。
        // 所以这里用 if / else if 而不是两条并列的 if。
        if (instanceTask != null && !instanceTask.finished) {
            // 智能转换对 `instanceTask`（可空参数）不适用，先取成局部量
            @Suppress("NAME_SHADOWING")
            val t = instanceTask
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QkSpacing.lg, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = QkSpacing.md, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t.message.ifBlank { stringResource(R.string.instance_detail_task_installing) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.height(QkSpacing.xs))
                        LinearProgressIndicator(
                            progress = { t.fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp),
                        )
                        Spacer(Modifier.height(QkSpacing.xxs))
                        Text(
                            text = listOf(t.progressText, t.speedText)
                                .filter { it.isNotBlank() }
                                .joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if (onCancelTask != null) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onCancelTask) { Text(stringResource(R.string.instance_detail_cancel)) }
                    }
                }
            }
        } else if (repairing) fileReport?.let { report ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QkSpacing.lg, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = QkSpacing.md, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ⚠️ 只占**一行**：之前这里是「标题 + 建议 + 最多 3 条缺失项 + 进度条」，
                    // 在横屏 320dp 高度里能吃掉半个屏幕 —— 用户报「补全提示占满，下面的
                    // 存档/按键/文件根本看不到，文件 tab 上面还压着一张共享文件卡片，更没救」。
                    // 缺哪几项属于排查信息，点「补全」后失败时也会再说，不必常驻。
                    Text(
                        text = if (repairing) {
                            repairStage.ifBlank { stringResource(R.string.instance_detail_repairing) } +
                                if (repairFraction >= 0f) " ${(repairFraction * 100).toInt()}%" else ""
                        } else {
                            stringResource(R.string.instance_detail_files_incomplete, report.ok, report.total)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (onRepair != null) {
                        TextButton(
                            onClick = onRepair,
                            // 全是「不可修」项时不给点 —— 白等几百 MB 没意义
                            enabled = report.canRepair && !repairing,
                        ) {
                            if (repairing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                if (repairing) stringResource(R.string.instance_detail_repairing)
                                else stringResource(R.string.instance_detail_repair_files),
                            )
                        }
                    }
                }
            }
        }

        // ── tab 条 ────────────────────────────────────────────────────
        //
        // ⚠️ 必须用 ScrollableTabRow，**不能**给 TabRow 套 horizontalScroll。
        // 实测崩溃（2026-10-13）：
        //   IllegalArgumentException: Can't represent a width of 306783378 and height of 0
        //     at TabRowKt$TabRowWithSubcomposeImpl
        // 原因：TabRow 内部用 SubcomposeLayout 算**固有高度**，
        // 而 horizontalScroll 给的是无限宽约束 → 固有高度计算拿到非法约束。
        //
        // ScrollableTabRow 是官方为「tab 放不下要横滑」准备的组件，
        // 它自己处理滚动与指示器，不会再撞上这个问题。
        // 统一 tab 组件（2026-10-17）
        QkTabRow(
            labels = InstanceTab.entries.map { stringResource(it.labelRes) },
            selectedIndex = state.tab.ordinal,
            onSelect = { onTabChange(InstanceTab.entries[it]) },
        )

        // ── 内容区 ────────────────────────────────────────────────────
        //
        // ⚠️ tab 切换加转场（fade + 轻微上移）：
        // 之前是硬切 —— 点 tab 的瞬间内容整个换掉，感觉"闪了一下"。
        // 用短时长（MEDIUM_MS）避免拖慢频繁的 tab 往返。
        // ⚠️ 用 Box 提供**稳定有界高度**，AnimatedContent 放在里面。
        //
        // 踩过的坑：直接把 `Modifier.weight(1f)` 给 AnimatedContent 时，
        // 它内部的内容（各 tab 的 verticalScroll）拿不到正确约束 ——
        // 表现为「设置 tab 内容超一屏但滑不动」。
        // 外层先给一个明确尺寸的 Box，AnimatedContent 填满它，就稳定了。
        Box(modifier = Modifier.weight(1f)) {
            // ⚠️ tab 内容等**图标落地**再组合：详情页首次组合要 100ms+（161 个模组
            // 的列表），Compose 单线程——这段时间一帧都画不出来。只在首帧挡一下的话，
            // 重组合正好落在飞行途中 → 「飞到一半卡一下」。落地后再付这笔成本，飞行
            // 期间帧预算全给动画；500ms 兜底防内容永不出现。
            var forceReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(500)
                forceReady = true
            }
            val bodyReady = forceReady || SharedHero.pending == null
            if (!bodyReady) {
                Spacer(Modifier.fillMaxSize())
            } else {
            AnimatedContent(
                targetState = state.tab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // 按索引方向滑动：往右的 tab 从右进，往左的从左进，
                    // 与 tab 排列顺序一致，方向感自然
                    val forward = targetState.ordinal > initialState.ordinal
                    (if (forward) qkSlideInRight() else qkSlideInLeft()) togetherWith qkFadeOut()
                },
                label = "detailTab",
            ) { tab ->
            when (tab) {
                InstanceTab.Saves -> SavesTab(
                state = state,
                onBackup = onBackup,
                onRestore = onRestore,
                onDeleteBackup = onDeleteBackup,
                onLaunchWorld = onLaunchWorld,
                launchingWorld = launchingWorld,
            )
                InstanceTab.Keys -> KeysTab(state, onLayoutSelect, onLayoutEdit, onLayoutRename, onLayoutDuplicate, onLayoutDelete, onLayoutImport, onLayoutExport, onLoadControlButtons, onSetControlPassthru)
                InstanceTab.Content -> ContentTab(
                    state = state,
                    onToggle = onToggleContent,
                    onUninstall = onUninstallContent,
                    onCheckUpdates = onCheckUpdates,
                    onApplyUpdate = onApplyUpdate,
                    onIdentify = onIdentify,
                    identifying = identifying,
                    onKindChange = onContentKindChange,
                    onImport = onImportContent,
                )
                InstanceTab.Settings -> InstanceSettingsTab(
                    state = settings,
                    onChange = onSettingsChange,
                    onSave = onSettingsSave,
                    saving = settingsSaving,
                    savedHint = settingsHint,
                    error = settingsError,
                    onDismissHint = onDismissSettingsHint,
                    exportPlan = exportPlan,
                    exportPlanLoading = exportPlanLoading,
                    exporting = exporting,
                    exportHint = exportHint,
                    onExport = onExport,
                )
                // 「文件」tab：内嵌文件浏览器（顶部有共享占用卡）。
                InstanceTab.Files -> FilesTab(
                    ops = fileOps,
                    onOpenSystemFiles = onOpenFiles,
                    // ⚠️ 2026-10-27 按用户要求移除：文件页顶部那张「共享游戏文件」
                    // 占用卡（版本/Java/库/素材）太占地方，把下面的文件列表挤没了。
                    // 传 null 即不渲染该卡；这块信息后面要挪到设置页再看。
                    usage = null,
                )
                InstanceTab.Logs -> LogsTab(state, onLoadLog, onExportLog, onSaveLog)
                InstanceTab.Crash -> CrashTab(state, onLoadCrashes, onAnalyzeCrash, onCopyCrashText, onViewCrashRaw)
            }
        }
            }   // ← else（推迟一帧的 tab 内容）
        }   // ← Box
    }
}

// ── 存档 tab ────────────────────────────────────────────────────────────

@Composable
private fun SavesTab(
    state: InstanceDetailUiState,
    onBackup: (String) -> Unit,
    onRestore: (String, String) -> Unit,
    /** 快速进入某个世界（quick-play）。旧 UI 的 launchWorld。 */
    onLaunchWorld: ((String) -> Unit)? = null,
    /** 正在启动的世界名（用于按钮态）。 */
    launchingWorld: String? = null,
    onDeleteBackup: (String, String) -> Unit,
) {
    when {
        state.loading -> Column(modifier = Modifier.padding(QkSpacing.lg)) {
            SkeletonList(rows = 4, rowHeight = 72.dp)
        }
        state.worlds.isEmpty() -> EmptyHint(
            stringResource(R.string.instance_detail_no_saves_title),
            stringResource(R.string.instance_detail_no_saves_desc),
        )
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
        ) {
            items(state.worlds, key = { it.name }) { w ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(modifier = Modifier.padding(QkSpacing.md)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = w.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = w.meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Spacer(Modifier.height(QkSpacing.sm))
                        Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                            // 「进入世界」—— 直接启动游戏并加载这个存档（quick-play）。
                            // 旧 UI 的 launchWorld；没有它用户只能先启动游戏再手动选世界。
                            if (onLaunchWorld != null) {
                                FilledTonalButton(
                                    onClick = { onLaunchWorld(w.name) },
                                    enabled = launchingWorld == null,
                                ) {
                                    if (launchingWorld == w.name) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.instance_detail_enter_world))
                                }
                            }
                            FilledTonalButton(onClick = { onBackup(w.name) }) {
                                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.instance_detail_backup))
                            }
                            if (w.backups.isNotEmpty()) {
                                TextButton(onClick = { onRestore(w.name, w.backups.first()) }) {
                                    Icon(Icons.Filled.Restore, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.instance_detail_restore_latest))
                                }
                            }
                        }
                        // 已有备份列表
                        if (w.backups.isNotEmpty()) {
                            Spacer(Modifier.height(QkSpacing.xs))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            w.backups.forEach { b ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 40.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = b,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = { onRestore(w.name, b) }) {
                                        Icon(Icons.Filled.Restore, stringResource(R.string.instance_detail_restore), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDeleteBackup(w.name, b) }) {
                                        Icon(
                                            Icons.Filled.Delete, stringResource(R.string.instance_detail_delete),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error,
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

// ── 按键（控制布局）tab ─────────────────────────────────────────────────

@Composable
private fun KeysTab(
    state: InstanceDetailUiState,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRename: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onExport: (String) -> Unit = {},
    /** 拉当前布局的按键列表（D2 按键穿透）。 */
    onLoadControlButtons: () -> Unit = {},
    /** 改某个按键的透传开关（D2）。 */
    onSetPassthru: (Int, Boolean) -> Unit = { _, _ -> },
) {
    var showPassthru by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        // ⚠️ 2026-10-28（竖屏审计）：原来是「导入 + 按键穿透 + 说明文字」挤在一个 Row，
        // 竖屏 411dp 下说明文字只剩半宽、被压成 4 行。改成按钮一行、说明独占下一行。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.lg, vertical = 10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onImport) {
                    Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.instance_detail_import_layout))
                }
                // 按键穿透设置（D2）：get_control_buttons / set_control_button_passthru
                // 一直没有 UI —— 按键挡住游戏画面时用户无从关掉它。
                TextButton(onClick = {
                    showPassthru = true
                    onLoadControlButtons()
                }) {
                    Icon(Icons.Filled.TouchApp, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.instance_detail_passthru))
                }
            }
            Spacer(Modifier.height(QkSpacing.xxs))
            Text(
                text = stringResource(R.string.instance_detail_keys_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (showPassthru) {
            AlertDialog(
                onDismissRequest = { showPassthru = false },
                title = { Text(stringResource(R.string.instance_detail_passthru)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(QkSpacing.xxs),
                    ) {
                        Text(
                            text = stringResource(R.string.instance_detail_passthru_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.controlButtons.isEmpty()) {
                            Spacer(Modifier.height(QkSpacing.xs))
                            Text(
                                text = stringResource(R.string.instance_detail_passthru_empty),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        state.controlButtons.forEach { b ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = b.name.ifBlank { "#${b.index}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = b.passThru,
                                    onCheckedChange = { onSetPassthru(b.index, it) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showPassthru = false }) {
                        Text(stringResource(R.string.instance_detail_cancel))
                    }
                },
            )
        }

        if (state.layouts.isEmpty()) {
            EmptyHint(
                stringResource(R.string.instance_detail_no_layouts_title),
                stringResource(R.string.instance_detail_no_layouts_desc),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
        ) {
            items(state.layouts, key = { it.name }) { l ->
                Card(
                    onClick = { onSelect(l.name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(
                        containerColor = if (l.current) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(QkSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = l.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (l.current) FontWeight.Bold else FontWeight.Normal,
                                    color = if (l.current) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (l.current) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.instance_detail_in_use),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                            if (l.meta.isNotBlank()) {
                                Text(
                                    text = l.meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }

                        IconButton(onClick = { onEdit(l.name) }) {
                            Icon(Icons.Filled.Edit, stringResource(R.string.instance_detail_edit), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onRename(l.name) }) {
                            Icon(Icons.Filled.Upload, stringResource(R.string.instance_detail_rename), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDuplicate(l.name) }) {
                            Icon(Icons.Filled.ContentCopy, stringResource(R.string.instance_detail_copy), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onExport(l.name) }) {
                            Icon(
                                Icons.Filled.Share, stringResource(R.string.instance_detail_export_layout),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        IconButton(onClick = { onDelete(l.name) }) {
                            Icon(
                                Icons.Filled.Delete, stringResource(R.string.instance_detail_delete),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 内容（模组/资源包）tab ──────────────────────────────────────────────

@Composable
private fun ContentTab(
    state: InstanceDetailUiState,
    onToggle: (ContentUi, Boolean) -> Unit,
    onUninstall: (ContentUi) -> Unit,
    onCheckUpdates: () -> Unit,
    /** 识别未识别的内容（异步，结果靠事件推回）。 */
    onIdentify: () -> Unit = {},
    /** 识别进行中。 */
    identifying: Boolean = false,
    /** 切换内容分类（2026-10-17 修审计 H-2：原来 onClick 是空 lambda）。 */
    onKindChange: (String) -> Unit = {},
    /** 「导入文件」：把本地下载好的 jar/zip 复制进当前分类目录（D4）。 */
    onImport: (() -> Unit)? = null,
    onApplyUpdate: (ContentUi) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // ── 分类筛选 + 操作按钮（2026-10-17 改响应式）──────────────────
        //
        // 原来是「chips(weight 1 + horizontalScroll) | 检查更新 | 识别」一行。
        // 竖屏 1080px 下 chips 被挤到只剩一半，第三个 chip「光影」直接被
        // 右侧按钮压住看不清（真机截图可见）。
        //
        // 现在按可用宽度分两种排布：
        //   - 够宽（横屏）：还是一行，chips 占剩余空间可横滚
        //   - 太窄（竖屏）：chips 单独一行，按钮移到下一行右侧
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            if (wide) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = QkSpacing.lg, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    ) {
                        state.contentKinds.forEach { k ->
                            // ⚠️ 2026-10-23 真机反馈「下面列着 3 个模组，页签却写 模组 0」：
                            // `count` 是加载时统计出来的（PageHost 那侧写回），但操作后
                            // （启用/删除/更新）可能滞后。当前分类以**实际列表条数**兜底，
                            // 保证 chips 和下面的列表永远一致。
                            val shownCount =
                                if (k.id == state.contentKind) maxOf(k.count, state.content.size)
                                else k.count
                            FilterChip(
                                selected = state.contentKind == k.id,
                                onClick = { onKindChange(k.id) },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.instance_detail_kind_label_count,
                                            stringResource(k.labelRes),
                                            shownCount,
                                        )
                                    )
                                },
                            )
                        }
                    }
                    ContentActions(onCheckUpdates, onIdentify, identifying, onImport)
                }
            } else {
                Column(modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    ) {
                        state.contentKinds.forEach { k ->
                            // ⚠️ 2026-10-23 真机反馈「下面列着 3 个模组，页签却写 模组 0」：
                            // `count` 是加载时统计出来的（PageHost 那侧写回），但操作后
                            // （启用/删除/更新）可能滞后。当前分类以**实际列表条数**兜底，
                            // 保证 chips 和下面的列表永远一致。
                            val shownCount =
                                if (k.id == state.contentKind) maxOf(k.count, state.content.size)
                                else k.count
                            FilterChip(
                                selected = state.contentKind == k.id,
                                onClick = { onKindChange(k.id) },
                                label = {
                                    Text(
                                        stringResource(
                                            R.string.instance_detail_kind_label_count,
                                            stringResource(k.labelRes),
                                            shownCount,
                                        )
                                    )
                                },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ContentActions(onCheckUpdates, onIdentify, identifying, onImport)
                    }
                }
            }
        }

        if (state.loading) {
            Column(modifier = Modifier.padding(QkSpacing.lg)) {
                SkeletonList(rows = 4, rowHeight = 64.dp)
            }
            return@Column
        }

        if (state.content.isEmpty()) {
            EmptyHint(
                stringResource(R.string.instance_detail_no_content_title),
                stringResource(R.string.instance_detail_no_content_desc),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        ) {
            items(state.content, key = { it.rel }) { c ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = c.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (c.meta.isNotBlank()) {
                                Text(
                                    text = c.meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        if (c.updatable) {
                            TextButton(onClick = { onApplyUpdate(c) }) {
                                Text(stringResource(R.string.instance_detail_update))
                            }
                        }
                        // 启用/禁用：禁用 = 重命名成 .disabled，这是 Minecraft 的惯例
                        Switch(
                            checked = c.enabled,
                            onCheckedChange = { onToggle(c, it) },
                        )
                        IconButton(onClick = { onUninstall(c) }) {
                            Icon(
                                Icons.Filled.Delete, stringResource(R.string.instance_detail_uninstall),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── 占位 tab（明确标注，不假装能用）────────────────────────────────────

/**
 * 「文件」tab：**内嵌**文件浏览器。
 *
 * 旧实现这里只有一个「打开文件浏览器」按钮 —— 用户要进实例详情 →
 * 点按钮 → 才看到文件列表，多一跳。
 *
 * 而「看这个实例有哪些文件」是高频操作（查模组、看配置、找日志），
 * 所以直接把浏览器嵌进 tab 里。
 *
 * 复用同一个 [FileManagerPage]（它本来就是可复用的 composable），
 * 只是数据源换成实例的 [InstanceFileOps]。
 * 另外保留一个「用系统文件管理器打开」的次要入口 ——
 */
@Composable
private fun FilesTab(
    ops: FileOps,
    onOpenSystemFiles: () -> Unit,
    /** 共享游戏文件占用（非 null 时显示占用卡片）。 */
    usage: SharedUsageUi? = null,
) {
    val ctx = LocalContext.current
    var path by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(FileManagerUiState(title = ctx.getString(ops.labelRes))) }
    // 点文件进编辑器（原来 onOpenFile 是空 lambda：点 jar / options.txt 毫无反应）
    val editor = rememberFileEditor(ops, ctx)
    if (FileEditorOverlay(editor)) return
    // `reload()` 是普通局部函数（不是 @Composable），拿不到 stringResource，
    // 所以先在 composable 作用域里取好 Context。

    fun reload() {
        state = state.copy(loading = true, error = null)
        ops.list(path) { r ->
            state = r.fold(
                onSuccess = { state.copy(entries = it, loading = false) },
                onFailure = {
                    state.copy(
                        loading = false,
                        error = ctx.getString(
                            R.string.instance_detail_read_failed,
                            friendlyError(ctx, it),
                        ),
                    )
                },
            )
        }
    }

    // path 变化就重新拉（含首次）
    LaunchedEffect(path) { reload() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── 共享游戏文件占用 ──────────────────────────────────────────
        //
        // 为什么要在文件页顶部显示（旧 UI 注释里记过这个真机反馈）：
        // **游戏本体不在实例目录里** —— 刚建的实例目录只有一个 instance.json，
        // 用户打开「文件」看到空空如也，会以为「什么都没下」。
        // 这张卡把版本 / Java / 库 / 素材的占用摆出来。
        usage?.let { u ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QkSpacing.lg, vertical = 8.dp),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(modifier = Modifier.padding(QkSpacing.md)) {
                    Text(
                        text = stringResource(R.string.instance_detail_shared_files_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    if (u.mcVersion.isNotBlank()) {
                        UsageLine(stringResource(R.string.instance_detail_usage_game_version, u.mcVersion), u.versionBytes)
                    }
                    if (u.javaVersion > 0) {
                        UsageLine("Java ${u.javaVersion}", u.javaBytes)
                    }
                    UsageLine(stringResource(R.string.instance_detail_usage_libraries), u.librariesBytes)
                    UsageLine(stringResource(R.string.instance_detail_usage_assets), u.assetsBytes)
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = stringResource(R.string.instance_detail_shared_instances, u.instancesTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // 次要入口：批量操作走系统文件管理器更顺手
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.lg, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.instance_detail_files_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSystemFiles) {
                Text(stringResource(R.string.instance_detail_system_file_manager))
            }
        }

        FileManagerPage(
            // ⚠️ 必须给 `weight(1f)` —— 否则它（内部的 LazyColumn）在
            // Column 里拿不到剩余高度，被上方的占用卡片 + 提示行挤成 0 高，
            // 表现就是「文件列表永远是空的」，但后端其实返回了数据
            //（实测：logcat 里 entries 有 mods / instance.json）。
            //
            // 与 `CreateServerDialog` 的 weight+scroll 是同一类坑：
            // Column 里的子项要「占满剩余空间」就得显式 weight。
            modifier = Modifier.weight(1f),
            state = state.copy(path = path),
            onOpenDir = { path = it },
            onOpenFile = { e ->
                if (!editor.open(e)) {
                    state = state.copy(error = ctx.getString(R.string.file_too_large, e.name, fmtBytes(e.size)))
                }
            },
            onBreadcrumb = { path = it },
            onGoUp = { path = path.substringBeforeLast('/', "") },
            onCreate = { name, isDir ->
                val rel = if (path.isBlank()) name else "$path/$name"
                ops.create(rel, isDir) { r ->
                    r.fold(
                        onSuccess = { reload() },
                        onFailure = { e ->
                            state = state.copy(
                                error = ctx.getString(
                                    R.string.instance_detail_file_op_failed,
                                    friendlyError(ctx, e),
                                ),
                            )
                        },
                    )
                }
            },
            onRename = { e, newName ->
                ops.rename(e.rel, newName) { r ->
                    r.fold(
                        onSuccess = { reload() },
                        onFailure = { err ->
                            state = state.copy(
                                error = ctx.getString(
                                    R.string.instance_detail_file_op_failed,
                                    friendlyError(ctx, err),
                                ),
                            )
                        },
                    )
                }
            },
            onDelete = { e ->
                ops.delete(e.rel) { r ->
                    r.fold(
                        onSuccess = { reload() },
                        onFailure = { err ->
                            state = state.copy(
                                error = ctx.getString(
                                    R.string.instance_detail_file_op_failed,
                                    friendlyError(ctx, err),
                                ),
                            )
                        },
                    )
                }
            },
            onRefresh = { reload() },
        )
    }
}

/**
 * 日志 tab。
 *
 * 与「控制台」的区别：控制台是**服务器**的实时输出，这里是**客户端游戏**的日志。
 * 客户端日志在游戏退出后才完整，所以是「读一次」而不是流式。
 */
@Composable
private fun LogsTab(
    state: InstanceDetailUiState,
    onLoadLog: () -> Unit,
    onExportLog: () -> Unit = {},
    onSaveLog: (() -> Unit)? = null,
) {
    // 进 tab 时自动读一次
    LaunchedEffect(Unit) { onLoadLog() }

    if (state.logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.instance_detail_no_logs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                Text(
                    text = stringResource(R.string.instance_detail_no_logs_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(QkSpacing.md))
                FilledTonalButton(onClick = onLoadLog) { Text(stringResource(R.string.instance_detail_reload)) }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.lg, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.instance_detail_log_lines, state.logs.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // ⚠️ 2026-10-21 真机修：用户反馈「上面更新日志说增加了启动器日志导出，
            // 的导出到哪儿了？」—— exportInstanceLogs 一直存在但界面从未调用，
            // 所以用户找不到入口。现在在日志 tab 加「导出」按钮。
            // 导出走系统分享 Intent（share_logs_zip），用户可存到任意应用/文件管理器。
            TextButton(onClick = onExportLog) { Text(stringResource(R.string.instance_detail_export_log)) }
            // D5a：直接存成 .txt（区别于上面的「导出 zip」——那个走分享）。
            if (onSaveLog != null) {
                TextButton(onClick = onSaveLog) { Text(stringResource(R.string.instance_detail_save_log)) }
            }
            TextButton(onClick = onLoadLog) { Text(stringResource(R.string.instance_detail_refresh)) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(state.logs) { line ->
                // 日志按级别着色 —— 满屏同色时「哪行是错误」要靠肉眼找，很慢
                val color = when {
                    line.contains("ERROR", true) || line.contains("FATAL", true) ->
                        MaterialTheme.colorScheme.error
                    line.contains("WARN", true) -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = color,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * 崩溃分析 tab。
 *
 * 对应旧界面的「崩溃」页：列出崩溃日志 → 点开分析 → 给出可能原因。
 *
 * 这个 tab 的价值很实际：游戏崩溃时用户看到的只是「闪退」，
 * 而崩溃日志能说明是**内存不足 / 模组冲突 / 渲染器问题** —— 差别很大。
 */
@Composable
private fun CrashTab(
    state: InstanceDetailUiState,
    onLoadCrashes: () -> Unit,
    onAnalyze: (String) -> Unit,
    /** 复制崩溃报告正文（拿去求助用）。 */
    onCopyCrashText: ((String) -> Unit)? = null,
    /** 查看崩溃报告原文（2026-10-21 补：getCrashReportContent 一直没 UI）。 */
    onViewRaw: (String, (Result<String>) -> Unit) -> Unit = { _, _ -> },
) {
    LaunchedEffect(Unit) { onLoadCrashes() }

    // 分析结果弹层。结果由 state.crashHint 承载（外层 AI 分析完成后写入），
    // 关闭时调一次 onAnalyzeCrash("") 之外的清空由外层负责 —— 
    // 这里用局部 dismissed 标记避免重复弹出。
    var dismissed by remember { mutableStateOf(false) }
    if (state.crashHint.isNotBlank() && !dismissed) {
        CrashReportDialog(
            title = stringResource(R.string.instance_detail_crash_analysis),
            body = state.crashHint,
            onDismiss = { dismissed = true },
            // 崩溃报告是「要拿去求助」的内容，必须能复制（旧 UI 的 copyRaw）。
            // 走 Rust 的 write_clipboard 桥 —— WebView 的 clipboard API 在这台设备上
            // 没给权限（旧 UI 的 CodeEditor 注释里记过）。
            onCopy = onCopyCrashText?.let { cb -> { cb(state.crashHint) } },
        )
    }

    if (state.crashLogs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.instance_detail_no_crashes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                Text(
                    text = stringResource(R.string.instance_detail_no_crashes_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(QkSpacing.md))
                FilledTonalButton(onClick = onLoadCrashes) { Text(stringResource(R.string.instance_detail_recheck)) }
            }
        }
        return
    }

    // 查看原文对话框（2026-10-21 补）
    var rawFilename by remember { mutableStateOf<String?>(null) }
    var rawContent by remember { mutableStateOf<String?>(null) }
    rawFilename?.let { fname ->
        CrashReportDialog(
            title = fname,
            body = rawContent ?: stringResource(R.string.instance_detail_loading),
            onDismiss = { rawFilename = null; rawContent = null },
            onCopy = onCopyCrashText?.let { cb -> { cb(rawContent ?: "") } },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
    ) {
        items(state.crashLogs) { name ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(QkSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.instance_detail_analyze_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                    FilledTonalButton(onClick = { onAnalyze(name) }) {
                        Text(stringResource(R.string.instance_detail_analyze))
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        rawFilename = name
                        rawContent = null
                        onViewRaw(name) { r ->
                            r.onSuccess { rawContent = it }
                            r.onFailure { rawContent = it.message ?: "读取失败" }
                        }
                    }) {
                        Text(stringResource(R.string.instance_detail_view_raw))
                    }
                }
            }
        }
    }
}

/** 崩溃分析结果弹层（内容较长，用可滚动的对话框）。 */
@Composable
private fun CrashReportDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    /** 复制正文（发给朋友/贴论坛求助用）。旧 UI 的 copyRaw。 */
    onCopy: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row {
                // 复制 —— 崩溃报告是「要拿去求助」的内容，
                // 不能复制的话用户只能截图（长文本截图体验很差）
                if (onCopy != null) {
                    TextButton(onClick = onCopy) { Text(stringResource(R.string.instance_detail_copy)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.instance_detail_close)) }
            }
        },
    )
}
@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(QkSpacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 实例详情的 tab（7 个，对齐旧界面）。 */
/**
 * 游戏文件检查结果（只在**缺文件**时非 null）。
 *
 * 对应旧 UI `InstanceDetailView.vue` 的 `fileReport`：
 * 创建实例时的后台安装一旦失败（网络断流 / 进程被杀 / 磁盘满），
 * 实例会停在「只有 instance.json」的状态 ——
 * 界面看不出原因、也没有补救入口。
 *
 * 这个提示条把「缺什么」和「怎么补」摆到启动按钮下面。
 */
data class FileReportUi(
    val ok: Int = 0,
    val total: Int = 0,
    /** 缺的项，每项一句人话说明 + 是否可自动修复。 */
    val missing: List<MissingItemUi> = emptyList(),
    /** 后端给的建议。 */
    val advice: String = "",
) {
    /** 只要有「可修」的项就允许点补全（全是不可修时按钮禁用）。 */
    val canRepair: Boolean get() = missing.any { it.fixable }
}

/** 缺失项。`fixable=false` 表示补全也修不好（如加载器版本号为空，得换游戏版本）。 */
data class MissingItemUi(
    val kind: String = "",
    val detail: String = "",
    val fixable: Boolean = true,
)
enum class InstanceTab(@StringRes val labelRes: Int) {
    Content(R.string.instance_detail_tab_content),
    Saves(R.string.instance_detail_tab_saves),
    Keys(R.string.instance_detail_tab_keys),
    Settings(R.string.instance_detail_tab_settings),
    Files(R.string.instance_detail_tab_files),
    Logs(R.string.instance_detail_tab_logs),
    Crash(R.string.instance_detail_tab_crash),
}

/** 存档（世界）。 */
data class WorldUi(
    val name: String,
    val meta: String,
    val backups: List<String> = emptyList(),
)

/** 控制布局。 */
data class LayoutUi(
    val name: String,
    val meta: String = "",
    val current: Boolean = false,
)

/** 布局里的一个虚拟按键（按键穿透设置用，D2）。 */
data class ControlButtonUi(
    /** 在 mControlDataList 里的下标（写回时用它定位）。 */
    val index: Int,
    val name: String,
    /** 是否透传触摸（true = 不拦截，交给游戏）。 */
    val passThru: Boolean,
)

/** 已装内容项。 */
data class ContentUi(
    val rel: String,
    val name: String,
    val meta: String = "",
    val enabled: Boolean = true,
    val updatable: Boolean = false,
    /**
     * 内容来源：`modrinth` / `curseforge` / `manual`。
     *
     * ⚠️ 对应 Rust `InstalledContent.source`（models.rs:108），也正是
     * `apply_update` 需要的 `provider` 参数（commands.rs:1083）。
     * 缺了它就无法构造更新请求 —— 这是审计报的"apply_update 数据结构缺口"。
     */
    val provider: String = "",
    /** 项目 id（`apply_update` 的 `project_id`，commands.rs:1084）。 */
    val projectId: String = "",
    /** 当前已装版本 id，用于判断"有没有新版本"。 */
    val versionId: String = "",
    /**
     * 有更新时可升级到的目标版本 id（`apply_update` 的 `new_version_id`，commands.rs:1085）。
     * 由「检查更新」回填；为空表示暂无可用更新。
     */
    val latestVersionId: String = "",
)

/** 内容分类（带数量，用于 chip 标签）。 */
data class ContentKind(val id: String, @StringRes val labelRes: Int, val count: Int)

/**
 * 内容分类的固定四种（和旧 Vue 的 kind 取值一致）。
 *
 * count 在 list_content 返回后才知道 —— 这里先给 0，
 * 切分类时按实际条数更新（见 PageHost 的 onContentKindChange）。
 *
 * ⚠️ 「世界」是存档，不走 list_content 的 kind（它在存档 tab 里），
 * 所以这里只列三种真正的内容类型 + 「全部」。
 */
private val DEFAULT_CONTENT_KINDS: List<ContentKind> = listOf(
    ContentKind("mod", R.string.instance_detail_kind_mod, 0),
    ContentKind("resourcepack", R.string.instance_detail_kind_resourcepack, 0),
    ContentKind("shader", R.string.instance_detail_kind_shader, 0),
)

data class InstanceDetailUiState(
    val id: String = "",
    val name: String = "",
    val subtitle: String = "",
    // ⚠️ 默认「内容」而不是「存档」—— 与旧 UI 一致（InstanceDetailView.vue:
    // `const tab = ref("content")`）。用户点进实例最常看的是「装了哪些模组」，
    // 而不是「有哪些存档」。
    val tab: InstanceTab = InstanceTab.Content,
    val loading: Boolean = false,
    val error: String? = null,
    // 各 tab 的数据
    val worlds: List<WorldUi> = emptyList(),
    val layouts: List<LayoutUi> = emptyList(),
    /** 当前布局的虚拟按键列表（按键穿透设置用，D2；空 = 没读或还没生成布局）。 */
    val controlButtons: List<ControlButtonUi> = emptyList(),
    val content: List<ContentUi> = emptyList(),
    /**
     * 内容分类（模组/资源包/光影/世界）。
     *
     * ⚠️ 2026-10-17 补（审计 H-2 的更深一层）：这个字段**从来没有被赋值过**，
     * 一直是 emptyList —— 也就是说分类 chips 从来没显示过。
     * 审计报告只发现了 onClick 是空 lambda，没发现整个列表是空的。
     *
     * 现在给固定四种分类（和旧 Vue 的 kind 取值一致），count 由 loadContent 后更新。
     */
    val contentKinds: List<ContentKind> = DEFAULT_CONTENT_KINDS,
    val contentKind: String = "mod",
    val logs: List<String> = emptyList(),
    /** 崩溃日志文件名列表（list_crash_logs 返回的是文件名，不是内容）。 */
    val crashLogs: List<String> = emptyList(),
    /** 崩溃分析结果（点「分析」后由外层写入）。 */
    val crashHint: String = "",
)

/**
 * 共享游戏文件占用。
 *
 * 对应 Rust `shared_game_usage` 的返回（字段名已核对）：
 * mcVersion / versionBytes / javaVersion / javaBytes /
 * librariesBytes / assetsBytes / instancesTotal
 */
data class SharedUsageUi(
    val mcVersion: String = "",
    val versionBytes: Long = 0,
    val javaVersion: Int = 0,
    val javaBytes: Long = 0,
    val librariesBytes: Long = 0,
    val assetsBytes: Long = 0,
    val instancesTotal: Int = 0,
)

/** 一行「名字 + 大小」。 */
@Composable
private fun UsageLine(label: String, bytes: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = fmtBytes(bytes),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
























/**
 * 实例详情的**顶栏内容**（2026-10-17 新增）。
 *
 * 用户反馈：「点进实例之后，可以把实例名字、版本、加载器、启动游戏和扳手
 * 全放到最上面那个标题栏，把现在那个『实例详情』替换掉，底下空间也能大一点。」
 *
 * 原来页面内有**两套标题栏**：
 *   ① 外层 LauncherShell 的 TopAppBar（「实例详情」+ 返回箭头）
 *   ② 页面自绘的 Row（返回箭头 + 实例名 + 版本 + 启动/修复按钮）
 * 两套叠在一起既重复又浪费约 50dp 竖向空间。
 *
 * 现在把 ② 的内容整体搬进 ① 的 title 槽位，② 删掉。
 * 布局：`实例名 · 版本 · 加载器  [修复][启动]`
 */
@Composable
fun InstanceDetailToolbar(
    name: String,
    /** 版本 + 加载器（如「原版 26.3」「Fabric 0.20.0 1.21.8」）。 */
    subtitle: String,
    canLaunch: Boolean,
    repairing: Boolean,
    onLaunch: () -> Unit,
    onRepair: (() -> Unit)? = null,
    /** 打开换图标面板（D3）。 */
    onChangeIcon: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 顶栏只写**固定词「实例」**，不写实例名。
        //
        // 实例名和版本放在下面的 hero（标题栏下方、tab 上方），那里有图标当锚点，
        // 一眼能看清是哪个实例。
        // ⚠️ 但顶栏也不能空着：之前把那列整个删掉后只剩一个返回箭头，
        // 看起来像没渲染完（用户反馈「标题栏不显示了」）。
        Text(
            text = "实例",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))

        Spacer(Modifier.width(8.dp))

        // 「修复文件」常驻（不绑在「检查说不完整」上）：
        // check_instance_files 粒度太粗，会把「缺 10 个 jar」判成 can_launch=true。
        // 若只在它报缺时才显示入口，用户就没有任何恢复手段。
        if (onRepair != null) {
            IconButton(onClick = onRepair, enabled = !repairing) {
                if (repairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.Build,
                        contentDescription = stringResource(R.string.instance_detail_repair_game_files_cd),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // 换图标（D3）：`extract_game_icons` / `import_instance_image`
        // 一直零调用 —— 实例只能用首字母色块，没有真正的图标。
        if (onChangeIcon != null) {
            IconButton(onClick = onChangeIcon) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = stringResource(R.string.instance_detail_change_icon),
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Button(onClick = onLaunch, enabled = canLaunch) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.instance_detail_launch), maxLines = 1)
        }
    }
}



/** 「导入 / 检查更新 / 识别」文字按钮（横竖屏共用）。 */
@Composable
private fun ContentActions(
    onCheckUpdates: () -> Unit,
    onIdentify: () -> Unit,
    identifying: Boolean,
    onImport: (() -> Unit)? = null,
) {
    // 导入排在最前：手动装 mod 是「找不到资源就本地下载」的兜底路径，
    // 也是唯一不需要网络的入口，不该藏在别处。
    if (onImport != null) {
        TextButton(onClick = onImport) { Text(stringResource(R.string.instance_detail_import_file)) }
    }
    TextButton(onClick = onCheckUpdates) { Text(stringResource(R.string.instance_detail_check_updates)) }
    TextButton(onClick = onIdentify, enabled = !identifying) {
        if (identifying) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(stringResource(R.string.instance_detail_identify))
    }
}
