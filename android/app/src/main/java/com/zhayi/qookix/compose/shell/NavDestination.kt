package com.zhayi.qookix.compose.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Settings
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.zhayi.qookix.R

/**
 * 底部导航的入口。
 *
 * 对应现有 App.vue 的底栏：PRIMARY_TABS（首页/实例/内容/多人）
 * + downloads + more，共 6 格。
 *
 * ⚠️ 注意一处**遗留的不一致**：现有 App.vue 的注释写着
 * 「手机底栏的经验值是 5 个」，但 PRIMARY_TABS 实际放了 4 个，
 * 加上 downloads 与 more 共 **6 格**。MD3 规范建议 NavigationBar 为 3~5 项。
 * 这里先按现状保留 6 项（**不丢功能**优先），
 * 待 UI 定稿时再决定是否把「下载」并入「更多」。
 */
enum class NavDestination(
    val route: String,
    /**
     * ⚠️ 兼容保留：**旧的硬编码中文标签**。
     *
     * i18n 迁移后新代码应使用 [labelRes] + `stringResource(...)`。
     * 这个字段仍保留是因为 `PageHost` / `ComposeMainActivity` 等
     * 非 Composable 上下文暂时还在读它；等那些位置全部迁移完再删。
     */
    /** 真正的标签资源（i18n）。 */
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home(
        route = "home",
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    Instances(
        route = "instances",
        labelRes = R.string.nav_instances,
        selectedIcon = Icons.Filled.GridView,
        unselectedIcon = Icons.Outlined.GridView,
    ),
    Browse(
        route = "browse",
        labelRes = R.string.nav_browse,
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
    ),
    Multiplayer(
        route = "multiplayer",
        labelRes = R.string.nav_multiplayer,
        selectedIcon = Icons.Filled.People,
        unselectedIcon = Icons.Outlined.People,
    ),
    Downloads(
        route = "downloads",
        labelRes = R.string.nav_downloads,
        selectedIcon = Icons.Filled.Download,
        unselectedIcon = Icons.Outlined.Download,
    ),
    More(
        route = "more",
        labelRes = R.string.nav_more,
        selectedIcon = Icons.Filled.MoreVert,
        unselectedIcon = Icons.Filled.MoreVert,
    ),

    // ── 以下不在底栏，是二级页（由底栏或页面内跳转进入）──────────────────
    News(
        route = "news",
        labelRes = R.string.nav_news,
        selectedIcon = Icons.Filled.Newspaper,
        unselectedIcon = Icons.Outlined.Newspaper,
    ),
    Skins(
        route = "skins",
        labelRes = R.string.nav_skins,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
    ),
    Settings(
        route = "settings",
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
    ),
    CreateInstance(
        route = "create-instance",
        labelRes = R.string.nav_create_instance,
        selectedIcon = Icons.Filled.Add,
        unselectedIcon = Icons.Filled.Add,
    ),
    ServerDetail(
        route = "server-detail",
        labelRes = R.string.nav_server_detail,
        selectedIcon = Icons.Filled.Dns,
        unselectedIcon = Icons.Filled.Dns,
    ),
    ServerCreate(
        route = "server-create",
        labelRes = R.string.nav_server_create,
        selectedIcon = Icons.Filled.Add,
        unselectedIcon = Icons.Filled.Add,
    ),
    InstanceDetail(
        route = "instance-detail",
        labelRes = R.string.nav_instance_detail,
        selectedIcon = Icons.Filled.GridView,
        unselectedIcon = Icons.Outlined.GridView,
    ),
    Frp(
        route = "frp",
        labelRes = R.string.nav_frp,
        selectedIcon = Icons.Filled.SwapHoriz,
        unselectedIcon = Icons.Outlined.SwapHoriz,
    ),
    FrpRooms(
        route = "frp-rooms",
        labelRes = R.string.nav_frp_rooms,
        selectedIcon = Icons.Filled.Public,
        unselectedIcon = Icons.Outlined.Public,
    ),
    Playtime(
        route = "playtime",
        labelRes = R.string.nav_playtime,
        selectedIcon = Icons.Filled.Timeline,
        unselectedIcon = Icons.Outlined.Timeline,
    ),
    Account(
        route = "account",
        labelRes = R.string.nav_account,
        selectedIcon = Icons.Filled.AccountCircle,
        unselectedIcon = Icons.Outlined.AccountCircle,
    ),

    /**
     * 内容详情（内容中心点条目进入）。
     *
     * ⚠️ 2026-10-27：从「项目详情对话框」改成独立页面。
     * 弹窗里塞不下 Markdown 正文（旧实现还把正文截断到 500 字），
     * 也不能滚动看版本列表。
     */
    ContentDetail(
        route = "content-detail",
        labelRes = R.string.nav_content_detail,
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
    ),
    ;

    companion object {
        /**
         * 底栏展示的项（2026-10-27 改）：
         * 「下载」「更多」从底栏撤下，换成「我的」（Account）——
         * 下载任务在首页/实例详情/「我的」里都能看到；「更多」的皮肤/统计/
         * 新闻/设置入口全部挪进「我的」。两个页面本身保留，从「我的」进入。
         */
        val bottomBar: List<NavDestination> = listOf(
            Home, Instances, Browse, Multiplayer, Account,
        )
    }
}





