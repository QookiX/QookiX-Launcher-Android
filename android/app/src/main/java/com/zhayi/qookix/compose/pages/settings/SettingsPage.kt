package com.zhayi.qookix.compose.pages.settings

import com.zhayi.qookix.compose.theme.QkSpacing

import com.zhayi.qookix.compose.ui.QkTabRow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R

/**
 * 设置页（外壳 + 8 个子页路由）。
 *
 * 对应旧界面 views/SettingsView.vue —— **3770 行 / 31 个 api 调用**，
 * 是全项目最大的一块。直接照搬必然失控，所以按旧界面的 tab 划分**拆成子页**。
 *
 * ── 布局（横屏优先）─────────────────────────────────────────────────────
 * 设置项是「多而浅」的典型场景，横屏下用**左侧二级导航**比顶部 tab 更好：
 *   - 顶部 TabRow 放 8 个 tab 会挤（每个 ~110px，中文标签勉强）
 *   - 左侧竖排列表每项可放「图标 + 文字」，一眼看全 8 个分组
 *   - 右侧内容区宽度充裕，设置项不会拉得太长
 *
 *   ┌──────────────┬──────────────────────────────┐
 *   │ ⚙ 常规       │  常规                        │
 *   │ 🧩 插件       │  ┌──────────────────────────┐│
 *   │ 🖼 外观       │  │ 主题        [浅色/深色]   ││
 *   │ ⬇ 下载       │  │ 语言        [简体中文]    ││
 *   │ 🌐 内容服务   │  └──────────────────────────┘│
 *   │ 🎮 游戏       │                              │
 *   │ 💾 存储       │                              │
 *   │ ℹ 关于       │                              │
 *   └──────────────┴──────────────────────────────┘
 *
 * 这与 MD3 的「二级导航用 NavigationDrawer + 内容区」范式一致，
 * 也是 Android 官方 Settings 类应用的形态。
 */
@Composable
fun SettingsPage(
    current: SettingsTab,
    onTabChange: (SettingsTab) -> Unit,
    content: @Composable (SettingsTab) -> Unit,
) {
    // ── 左导航宽度：随屏幕收缩（2026-10-16 修）────────────────────────────
    //
    // 原来写死 `width(220.dp)`。竖屏只有 ~411dp 宽，左栏吃掉 220dp 后
    // 右侧内容区只剩 **~190dp** —— 表现为：
    //   - 「界面缩放」「背景图」等标题被折成两行
    //   - 主题色色块被裁掉
    //   - chips 行更容易溢出
    //
    // ── ⚠️ 第一版修得过头了（同日）────────────────────────────────────
    // 我把宽度直接改成「屏宽 30%」，411dp 屏 → **123dp** ——
    // 结果 **「常规」「插件」「外观」逐字竖排**。
    //
    // 原因是 `NavigationDrawerItem` 同时要放**图标(20dp) + 间距 + 文字**，
    // 而「内容服务」是 4 个字，123dp 装不下。
    //
    // ── 正确做法 ────────────────────────────────────────────────────
    // 窄屏下**不显示图标**，把宽度全留给文字 —— 这是移动端的常规处理
    //（底部导航/侧栏在窄屏下只留文字）。
    //   411dp 屏 → 150dp 导航 + ~261dp 内容，两边都够。
    //   横屏/平板 → 220dp，保留图标。
    // ⚠️⚠️ 必须按**缩放后的等效宽度**判断（2026-10-16 修，真机实测）。
    //
    // `LocalConfiguration.screenWidthDp` 是**缩放的基准宽度**，它不随
    // 「界面缩放」变化 —— 缩放只改了 Density.density。
    //
    // 后果：用户把缩放调到 150% 时，实际可用宽度只有 853/1.5 ≈ 569dp，
    // 但这里仍按 853dp 判断 → `narrow` 为 false → 导航栏保持 220dp 固定宽，
    // 而 220dp 在 1.5 倍密度下相当于占掉 330dp 的真实空间 ——
    // **内容区被压成一条**，「界面缩放」逐字竖排、滑杆和「恢复默认」完全看不到。
    //（这是同类响应式缺陷第 10 次出现：这次的「窄屏」是缩放造成的等效窄屏。）
    //
    // 算法：把当前 density 与基准 density 的比值除掉，得到等效 dp 宽度。
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val densityNow = androidx.compose.ui.platform.LocalDensity.current
    // tab 标签要用字符串资源，而 `map { }` 的 lambda 不是 @Composable 上下文，
    // 所以在一开始就把 Context 取出来（LocalContext 只能在这里读）。
    val context = androidx.compose.ui.platform.LocalContext.current
    // 基准 density 由 Configuration.densityDpi 推导（160dpi = 1.0x）。
    // 用密度比反推出「缩放前的等效 dp 宽度」，这样缩放拉大时能正确判为窄屏。
    val baselineDensity = cfg.densityDpi / 160f
    val scaleFactor = if (baselineDensity > 0f) densityNow.density / baselineDensity else 1f
    val screenW = cfg.screenWidthDp.dp / scaleFactor
    val narrow = screenW < 500.dp
    val veryNarrow = screenW < 360.dp
    val navW = when {
        // 极窄（缩放拉大后的横屏/竖屏）：导航再收一档，优先保内容区
        // 96dp 导航在 1.5x 密度下等于 144dp 真实空间，而字号也放大了 1.5 倍 ——
        // 「内容服务」等长标签仍会被截成「内…」。配合下面允许 2 行，72dp 起就能读。
        veryNarrow -> (screenW * 0.32f).coerceIn(72.dp, 120.dp)
        narrow -> (screenW * 0.36f).coerceIn(140.dp, 170.dp)
        else -> 220.dp
    }

    // ⚠️⚠️ 极窄（竖屏 + 大缩放）改用**横向 tab**，不再用侧边导航（2026-10-17 真机实测）。
    //
    // 侧边导航在这个场景下空间根本不够：竖屏 384dp + 150% 缩放 → 等效 256dp，
    // 导航只能分到 ~82dp，而「内容服务」四个汉字需要约 80dp 且字号还被放大 1.5 倍
    // → 无论怎么调宽度/行数，都只能显示「内…」。
    //
    // 横向 tab 把导航放顶部，宽度由 tab 自己横向滚动承担，内容区拿到完整宽度。
    // ══════════════════════════════════════════════════════════════════
    // 2026-10-17 重构：**取消二级侧边栏**，改为顶部横向 tab。
    //
    // 用户反馈：「设置页面点开之后设置还有一个侧边栏，就是这样两个侧边栏
    // 叠在一起我觉得有点怪，而且侧边栏的宽度有点宽」。
    //
    // 确实如此：外层 LauncherShell 已经有一个 168dp 的主侧边栏（首页/实例/
    // 内容/多人/下载/更多），设置页再放一个 220dp 的二级侧边栏 →
    // **两道竖栏并排吃掉 388dp**，横屏 800dp 宽下内容区只剩一半。
    //
    // 三个可选方案，选了这个：
    //   ① 二级导航收窄到 120dp —— 治标，「内容服务」仍会被截。
    //   ② 二级导航做成抽屉（点按钮滑出）—— 多一次点击，且设置页切换频繁。
    //   ③ **改为顶部横向 tab（本方案）** —— 竖向空间充裕（横屏 384dp 高度里
    //      顶栏已占 56dp，再占 48dp 也还好），横向空间才是稀缺的。
    //      而且 8 个 tab 用 ScrollableTabRow 横滚，宽度不够也只是滚动，
    //      不会像侧边栏那样把标签截成「内…」。
    //
    // 兼得：极窄屏（竖屏 + 大缩放）本来就是横向 tab，现在两种屏幕**统一**，
    // 少一套分支、少一处只在特定设备上才暴露的 bug。
    // ══════════════════════════════════════════════════════════════════
    Column(modifier = Modifier.fillMaxSize()) {
        // 统一 tab 组件（2026-10-17）：见 ui/QkTabRow.kt 的注释。
        // 8 个 tab 在竖屏放不下 → 自动横滚；横屏够宽 → 等分填满。
        QkTabRow(
            // 标签在 map 的 lambda 里取，用 context.getString 而不是 stringResource，
            // 避免依赖 inline lambda 里能否调用 composable 的细则。
            labels = SettingsTab.entries.map { context.getString(it.labelRes) },
            selectedIndex = SettingsTab.entries.indexOf(current),
            onSelect = { onTabChange(SettingsTab.entries[it]) },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            content(current)
        }
    }
}

/** 设置的 8 个分组（对齐旧界面 SettingsView.vue 的 tabs 定义）。 */
enum class SettingsTab(
    val key: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    General("general", R.string.settings_tab_general, Icons.Filled.Tune),
    Plugins("plugins", R.string.settings_tab_plugins, Icons.Filled.Widgets),
    Appearance("appearance", R.string.settings_tab_appearance, Icons.Filled.Image),
    Download("download", R.string.settings_tab_download, Icons.Filled.Download),
    ContentServices("content", R.string.settings_tab_content_services, Icons.Filled.Public),
    Game("game", R.string.settings_tab_game, Icons.Filled.SportsEsports),
    Storage("storage", R.string.settings_tab_storage, Icons.Filled.Refresh),
    About("about", R.string.settings_tab_about, Icons.Filled.Info),
}

// ── 设置项的通用构件（各子页共用，保证视觉一致）────────────────────────────

/** 设置分组卡片。 */
@Composable
fun SettingsGroup(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = QkSpacing.lg)) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = QkSpacing.xxs, bottom = 8.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column { content() }
        }
    }
}

/**
 * 设置行（MD3 ListItem 三段式）。
 *
 * ⚠️ 参数顺序：`trailing` 必须是**最后一个**参数，这样调用点能写成
 * `SettingsRow(title = "...") { Switch(...) }` 的尾随 lambda 形式。
 * 之前把 `showDivider` 放最后，导致尾随 lambda 被当成 Boolean 传给它。
 *
 * @param title 主标题
 * @param subtitle 副标题（说明文字）
 * @param onClick 整行可点（为 null 时不可点）
 * @param showDivider 是否画底部分隔线
 * @param column true = 标题在上、控件在下。用于 trailing 是多个
 *        FilterChip 的宽控件行 —— 横排会把标题挤到换行
 *        （实测：「启动游戏后」被拆成两行）
 * @param trailing 右侧（或下方）控件
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = true,
    column: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column {
        if (column) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) Modifier.androidxClickable(onClick) else Modifier
                    )
                    .padding(horizontal = QkSpacing.md, vertical = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(QkSpacing.xxs))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trailing != null) {
                    Spacer(Modifier.height(QkSpacing.sm))
                    trailing()
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) Modifier.androidxClickable(onClick) else Modifier
                    )
                    .padding(horizontal = QkSpacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(QkSpacing.xxs))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trailing != null) {
                    Spacer(Modifier.width(12.dp))
                    trailing()
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = QkSpacing.md),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/** 设置子页的统一容器（带标题 + 滚动）。 */
@Composable
fun SettingsPane(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(QkSpacing.xl),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = QkSpacing.lg),
        )
        content()
    }
}

/** clickable 的小包装（避免各处重复 import）。 */
private fun Modifier.androidxClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))
/** 空态（子页尚未迁移时用）。 */
@Composable
fun SettingsPlaceholder(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(QkSpacing.xs))
            Text(
                text = stringResource(R.string.settings_placeholder_wip),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}



