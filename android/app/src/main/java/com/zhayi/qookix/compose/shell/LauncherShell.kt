package com.zhayi.qookix.compose.shell

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.runtime.remember
import com.zhayi.qookix.compose.motion.pressScale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.zhayi.qookix.compose.ui.QkFeedback
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.zhayi.qookix.compose.motion.QookiX
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.zhayi.qookix.compose.theme.QkRadius
import com.zhayi.qookix.compose.theme.qkPressable
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * 启动器主壳（Material 3）—— 完全重做，不参照旧 Vue 界面。
 *
 * 按 MD3 window size class 自适应两种形态：
 *
 * ── Compact（<600dp，手机竖屏）────────────────────────────────────────
 *   ┌───────────────────────┐
 *   │ TopAppBar             │
 *   ├───────────────────────┤
 *   │ 内容                  │
 *   ├───────────────────────┤
 *   │ NavigationBar（底栏） │
 *   └───────────────────────┘
 *
 * ── Medium / Expanded（≥600dp，手机横屏 / 平板）—— **本项目当前优先** ──
 *   ┌──────────┬──────────────────────┐
 *   │ QookiX   │ TopAppBar            │
 *   │          ├──────────────────────┤
 *   │ ▸ 首页   │                      │
 *   │ ▸ 实例   │ 内容（更宽，可多列） │
 *   │ ▸ 内容   │                      │
 *   │ …        │                      │
 *   └──────────┴──────────────────────┘
 *     常驻侧栏
 *
 * ── 为什么横屏用「常驻侧栏」而不是窄 Rail ──────────────────────────────
 * 手机横过来可用高度只剩 ~411dp：
 *   - 底栏要吃掉 ~60dp（15%）—— 横屏最不缺的是宽度，不该占高度
 *   - 窄 Rail（图标+竖排文字）6 项实测**溢出**，「更多」被裁掉
 * 改用 MD3 在 expanded 宽度下的标准组件 **NavigationDrawerItem**
 * （图标与文字**横排**），每项 ~56dp，6 项 = 336dp，稳稳放得下，
 * 而且中文标签横排比竖排更好读。
 *
 * 这不是两套设计，是同一套 MD3 组件按尺寸切换形态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherShell(
    current: NavDestination,
    onNavigate: (NavDestination) -> Unit,
    onOpenAccount: () -> Unit = {},
    title: String? = null,
    /**
     * 顶栏左侧的返回箭头（null = 不显示）。
     *
     * ⚠️ 2026-10-17 加：二级页面（新建实例 / 实例详情 / 服务器详情 …）
     * 需要返回入口。以前是各页面自己在内容区顶部画一条标题栏，
     * 结果和外层 TopAppBar **重复显示两遍标题**（用户实测截图指出）。
     * 现在统一由 TopAppBar 提供返回箭头 + 标题，页面内部不再自绘。
     */
    onBack: (() -> Unit)? = null,
    /**
     * 侧边栏宽度（dp，2026-10-17 用户可调）。
     *
     * 默认 168（原来是写死的 196）。范围 140~260 在设置页滑杆里约束 ——
     * 140 以下「内容服务」会被截断，260 以上横屏内容区会被挤得太窄。
     */
    sidebarWidthDp: Int = 168,
    /**
     * 顶栏标题区的**自定义内容**（null = 显示普通标题文字）。
     *
     * ⚠️ 2026-10-17 加（用户要求「把搜索条放到标题那个位置」）：
     * 内容中心原来在标题下面又占一行放搜索框 + 两个按钮，
     * 横屏 384dp 高度里白吃掉约 60dp。放进标题行后这部分完全省下来。
     */
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val size = rememberWindowSize()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 全局一句话反馈：删除/复制/备份这类「做完了」的操作统一在这里冒一下。
    // 每个页面自己弹 Dialog 的话，位置和样式又会各不相同（审查 A-1 / X-3）。
    val snackbarHost = remember { SnackbarHostState() }
    val feedback = QkFeedback.message.collectAsState().value
    LaunchedEffect(feedback) {
        val text = feedback ?: return@LaunchedEffect
        snackbarHost.showSnackbar(text)
        QkFeedback.clear()
    }

    val topBar: @Composable () -> Unit = {
        TopAppBar(
            navigationIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.shell_action_back))
                    }
                }
            },
            title = {
                if (titleContent != null) {
                    titleContent()
                } else {
                    // ⚠️ 2026-10-17 修「标题闪一下」：
                    //
                    // 详情页（实例/服务器）第一帧时 titleContent 还是 null
                    //（它要等 SideEffect 跑完才注册上来），于是这里 fallback 到
                    // `titleFor(current)` 显示「实例详情」/「服务器详情」，
                    // 下一帧才变成实例名 —— 用户看到的就是标题**闪一下**。
                    //
                    // 修法：`title` 显式传空串时**不渲染任何文字**（而不是 fallback），
                    // 详情页用这个表示「我的标题马上由 titleContent 提供，别填默认值」。
                    val t = title ?: titleFor(current)
                    if (t.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 品牌名从侧栏移到顶栏（2026-10-27 布局重构）：
                            // 顶栏现在贯穿全宽，左侧腾出位置放它；侧栏则只放导航。
                            // 用 onSurface（亮）而不是灰色，让它是唯一"实在"的文字。
                            Text(
                                text = "QookiX",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(QkSpacing.md))
                            // 页面名用 titleMedium + 灰色：ZL2 的标题就是这个气质 ——
                            // 故意退后，让内容当主角（原来是 24sp 白，抢焦点）。
                            Text(
                                text = t,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            actions = {
                actions()
                // 账号入口固定最右（对齐旧界面「顶栏右侧是账号」的位置语义）
                IconButton(onClick = onOpenAccount) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = stringResource(R.string.shell_action_account),
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                // 滚动时也不再变成一块更亮的色块 —— 那会在内容上方压出一条"假分隔"，
                // 和 ZL2 那种「顶栏完全融进背景」相反。
                scrolledContainerColor = MaterialTheme.colorScheme.background,
                // 标题用次级色（灰），和 ZL2 一致：焦点留给内容
                titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }

    if (size.useRail) {
        // ── 侧栏收起 / 展开（2026-10-27）─────────────────────────────
        //
        // 默认收起成 88dp 的图标 rail（省出 80dp 给内容），**长按任一导航项**切换。
        // 宽度用 spatialSpec（spring，dampingRatio 0.8）—— 会轻微冲过目标再回来，
        // 就是用户要的那种「Q 弹」。
        // 默认**展开**（用户要求）；长按任一导航项收起成纯图标 rail。
        var sidebarExpanded by remember { mutableStateOf(true) }
        val sidebarW by animateDpAsState(
            targetValue = if (sidebarExpanded) 168.dp else 88.dp,
            // ⚠️ 宽度**不能**用 spatialSpec（带过冲）：
            // 宽度每帧变化都会触发整个 Row（侧栏 + 内容区）重新测量布局，
            // 而 spring 的过冲会让它「冲过 168 → 弹回 162 → 再到 168」，
            // 触发三四轮重排 —— 视觉上就是卡顿。
            // 会引起布局变化的属性（width / height / padding）只能走单调曲线；
            // 弹性的爽感留给 scale / translation（它们只走绘制，不重排）。
            animationSpec = tween(240, easing = QookiX.EaseOut),
            label = "sidebarW",
        )
        // ── 横屏 / 平板：常驻侧栏（图标+文字横排）────────────────────────
        // ⚠️ 2026-10-27 布局重构：顶栏**贯穿全宽**（原来它只在内容区，
        // 左边被侧栏挡住，看起来像两个独立的头部）。
        // 现在的层次：顶栏（全宽）→ 下面才是 侧栏 + 内容。
        Column(modifier = Modifier.fillMaxSize()) {
            topBar()
            Row(modifier = Modifier.weight(1f)) {
            Surface(
                modifier = Modifier
                    // 2026-10-17：196dp → 168dp（用户反馈侧边栏偏宽，占掉内容区）。
                    // 168dp 仍能放下「内容服务」等 4 字标签（48dp 触控高度不变）。
                    .width(sidebarW)
                    .fillMaxHeight(),
                // 侧栏原来和卡片同色（surfaceContainer），于是「侧栏」和「卡片」看起来
                // 是同一层级，整屏糊成一片。退到 surface：比背景略亮、比卡片暗，
                // 层次才是「背景 → 侧栏 → 卡片」。
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    // ⚠️ 这里**不能**再写 fillMaxHeight()：
                    // 外层 Surface 已经撑满高度，而 verticalScroll 会把子项的
                    // 高度约束变成 Infinity —— 两者叠加会让 NavigationDrawerItem
                    // 被拉伸、项间距异常放大（实测：6 项铺满整屏且「更多」被裁）。
                    // 只保留 verticalScroll 作兜底（将来加入口也不会被裁）。
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = QkSpacing.sm, vertical = 6.dp),
                ) {
                    // 品牌区（MD3 常驻抽屉的标准做法：抽屉顶部放产品名，

                    NavDestination.bottomBar.forEach { dest ->
                        QkRailItem(
                            dest = dest,
                            selected = dest == current,
                            expanded = sidebarExpanded,
                            onClick = { onNavigate(dest) },
                            onLongPress = { sidebarExpanded = !sidebarExpanded },
                        )
                    }
                }
            }

            Scaffold(
                // ⚠️ 必须用 weight(1f)，不能 fillMaxSize()：左边是固定 196dp 的导航栏，
                // fillMaxSize() 会和它**抢宽度**（窄屏/大缩放时内容区被压成一条竖线）。
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHost) },
            ) { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                ) { content() }
            }
        }
        }
    } else {
        // ── 竖屏：底部 NavigationBar ──────────────────────────────────────
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = topBar,
            snackbarHost = { SnackbarHost(snackbarHost) },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavDestination.bottomBar.forEach { dest ->
                        val selected = dest == current
                        NavigationBarItem(
                            selected = selected,
                            onClick = { onNavigate(dest) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = stringResource(dest.labelRes),
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            },
        ) { inner ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
            ) { content() }
        }
    }
}

/** 顶栏标题（i18n：取自 string 资源）。 */
@Composable
private fun titleFor(d: NavDestination): String = stringResource(
    when (d) {
        // 首页在横屏下品牌名已经出现在侧栏，顶栏不必重复 —— 用「首页」更清楚
        NavDestination.Home -> R.string.shell_title_home
        NavDestination.Instances -> R.string.shell_title_instances
        NavDestination.Browse -> R.string.shell_title_browse
        NavDestination.Multiplayer -> R.string.shell_title_multiplayer
        NavDestination.Downloads -> R.string.shell_title_downloads
        NavDestination.More -> R.string.shell_title_more
        NavDestination.News -> R.string.shell_title_news
        NavDestination.Skins -> R.string.shell_title_skins
        NavDestination.Settings -> R.string.shell_title_settings
        NavDestination.CreateInstance -> R.string.shell_title_create_instance
        NavDestination.ServerCreate -> R.string.nav_server_create
        NavDestination.ServerDetail -> R.string.shell_title_server_detail
        NavDestination.InstanceDetail -> R.string.shell_title_instance_detail
        NavDestination.Account -> R.string.shell_title_account
        NavDestination.Playtime -> R.string.shell_title_playtime
        NavDestination.FrpRooms -> R.string.shell_title_frp_rooms
        NavDestination.Frp -> R.string.shell_title_frp
    }
)

/**
 * 侧栏项：收起时竖排（图标 + 小字），展开时横排（图标 + 文字）。
 *
 * 为什么不用 `NavigationDrawerItem`：
 *   · 它在 88dp 下会溢出（内置最小内边距比 rail 宽）；
 *   · 它未选中时也画背景，看起来"每个按钮都有个框"（用户指出多余）——
 *     这里只有选中项有 pill 底。
 *
 * 长按切换侧栏展开/收起：手势挂在每一项上，而不是容器 —— 容器的
 * `Column(modifier = …)` 里塞 pointerInput 很容易破坏参数列表结构（踩过）。
 */
@Composable
private fun QkRailItem(
    dest: NavDestination,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else Color.Transparent,
        animationSpec = tween(180, easing = QookiX.EaseOut),
        label = "railBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        animationSpec = tween(180, easing = QookiX.EaseOut),
        label = "railFg",
    )
    val icon = if (selected) dest.selectedIcon else dest.unselectedIcon
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }

    // ⚠️ 只能有一份点击处理。
    // 原来同时挂了 `qkPressable { onClick() }`（内含 clickable）和
    // `combinedClickable(onClick = onClick)` —— 一次点击会触发两次导航。
    // 现在：pressScale 只负责"按下缩放"（它自己不挂 clickable），
    //       combinedClickable 负责「点击 + 长按」。
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(QkRadius.medium))
        .background(bg)
        .pressScale(interaction)
        .combinedClickable(
            interactionSource = interaction,
            indication = androidx.compose.foundation.LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongPress,
        )

    if (expanded) {
        Row(
            modifier = base
                .height(48.dp)
                .padding(horizontal = QkSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(QkSpacing.sm))
            Text(
                text = stringResource(dest.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        // 收起态：**只显示图标**（用户要求），图标居中、整项 48dp 高。
        // 不显示文字后每项从 56dp 降到 48dp，6 项在横屏下也不会超。
        // contentDescription 仍传标签 —— 收起只是视觉上省空间，无障碍不能丢。
        Box(
            modifier = base.height(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(dest.labelRes),
                tint = fg,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
