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
                        Text(
                            text = t,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge,
                        )
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
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }

    if (size.useRail) {
        // ── 横屏 / 平板：常驻侧栏（图标+文字横排）────────────────────────
        Row(modifier = Modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    // 2026-10-17：196dp → 168dp（用户反馈侧边栏偏宽，占掉内容区）。
                    // 168dp 仍能放下「内容服务」等 4 字标签（48dp 触控高度不变）。
                    .width(sidebarWidthDp.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceContainer,
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
                    // 顶栏放页面标题 —— 两者不重复）
                    // 横屏高度紧张（本机 384dp），品牌区压到最小
                    Text(
                        text = "QookiX",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = QkSpacing.md, top = 4.dp, bottom = 8.dp),
                    )

                    NavDestination.bottomBar.forEach { dest ->
                        val selected = dest == current
                        // ── 切换动画（2026-10-17 加，用户要求「侧边栏切换 tab 要有动画」）──
                        //
                        // 原来 `selected` 一变，背景色和图标是**瞬间**跳的 —— 硬切，没有过渡。
                        // 这里补三样：
                        //   1. 背景色 0→1 渐变（animateFloatAsState 驱动颜色插值）
                        //   2. 图标 Filled↔Outlined 用 AnimatedContent 淡入淡出
                        //   3. 文字色同步渐变 + 选中时轻微加粗
                        //
                        // ⚠️ 时长 180ms（比页面转场的 260ms 短）：
                        // 侧边栏是「即时反馈」，等太久会显得迟钝；
                        // 而页面内容需要更长时间才能看清，两者节奏不同是对的。
                        val selAnim by animateFloatAsState(
                            targetValue = if (selected) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = 180,
                                easing = QookiX.EaseOut,
                            ),
                            label = "navSel_${dest.route}",
                        )
                        // 每项**各自**一个 source：
                        //   - 不能共享 —— 共享的话按 A 项会把 6 项一起缩放
                        //   - 又必须传给 item 本体 —— 不传的话它内部自己新建，
                        //     缩放 modifier 拿到的永远是空状态（看起来对但不生效）
                        val itemSource = remember { MutableInteractionSource() }
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = stringResource(dest.labelRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = androidx.compose.ui.graphics.lerp(
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                        MaterialTheme.colorScheme.onSecondaryContainer,
                                        selAnim,
                                    ),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            icon = {
                                // 图标淡入淡出切换：Filled（选中）↔ Outlined（未选中）
                                androidx.compose.animation.AnimatedContent(
                                    targetState = selected,
                                    transitionSpec = {
                                        (androidx.compose.animation.fadeIn(
                                            tween(180, easing = QookiX.EaseOut)
                                        ) togetherWith androidx.compose.animation.fadeOut(
                                            tween(120, easing = QookiX.EaseIn)
                                        ))
                                    },
                                    label = "navIcon_${dest.route}",
                                ) { isSel ->
                                    Icon(
                                        imageVector = if (isSel) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = null,
                                    )
                                }
                            },
                            selected = selected,
                            onClick = { onNavigate(dest) },
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                // 选中底色也走渐变：否则背景还是硬切，只有图标淡入会很怪
                                selectedContainerColor = androidx.compose.ui.graphics.lerp(
                                    MaterialTheme.colorScheme.surfaceContainer,
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    selAnim,
                                ),
                            ),
                            interactionSource = itemSource,
                            // ⚠️ 必须固定高度：外层 verticalScroll 会给出
                            // maxHeight = Infinity 的约束，NavigationDrawerItem
                            // 内部用的是 minHeight 语义，在无限约束下会被拉伸
                            // （实测：6 项铺满整屏、「更多」被挤出屏幕）。
                            //
                            // 取 48dp 而非 56dp：本机横屏可用高度只有 384dp，
                            // 6 项 × 56 + 品牌区 ≈ 380dp 会顶到边缘。
                            // 48dp 是 MD3 的**最小触控目标**，既合规又放得下。
                            modifier = Modifier
                                .height(48.dp)
                                .pressScale(interactionSource = itemSource)
                                .padding(vertical = 1.dp),
                        )
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Scaffold(
                // ⚠️ 必须用 weight(1f)，不能 fillMaxSize()：左边是固定 196dp 的导航栏，
                // fillMaxSize() 会和它**抢宽度**（窄屏/大缩放时内容区被压成一条竖线）。
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = topBar,
                snackbarHost = { SnackbarHost(snackbarHost) },
            ) { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(inner),
                ) { content() }
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









