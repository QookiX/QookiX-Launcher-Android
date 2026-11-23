package com.zhayi.qookix.compose.shell

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.motion.QkMotionPolicy
import com.zhayi.qookix.compose.motion.QookiX
import com.zhayi.qookix.compose.theme.QkSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherTopBar(
    current: NavDestination,
    title: String?,
    titleContent: (@Composable () -> Unit)?,
    onBack: (() -> Unit)?,
    onCreateInstance: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val barResolvedTitle = title ?: when {
        onBack != null &&
            (current == NavDestination.InstanceDetail ||
                current == NavDestination.ServerDetail) -> ""
        else -> titleFor(current)
    }
    val titleShift = with(LocalDensity.current) { 12.dp.roundToPx() }
    val barTitle = BarTitle(current, barResolvedTitle, titleContent)
    TopAppBar(
        navigationIcon = {
            AnimatedContent(
                targetState = onBack != null,
                transitionSpec = {
                    if (!QkMotionPolicy.animationsEnabled) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (slideInHorizontally(QookiX.fastSpatialSpec()) { it / 2 } +
                            fadeIn(QookiX.fastEffectsSpec())) togetherWith
                            (slideOutHorizontally(QookiX.fastEffectsSpec()) { -it / 2 } +
                                fadeOut(QookiX.fastEffectsSpec()))
                    }
                },
                label = "barIcon",
            ) { hasBack ->
                if (hasBack) {
                    IconButton(onClick = { onBack?.invoke() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.shell_action_back))
                    }
                } else {
                    Spacer(Modifier.width(4.dp))
                }
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (current == NavDestination.Browse || (titleContent == null && barResolvedTitle.isNotEmpty())) {
                    Text(
                        text = "QookiX",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(QkSpacing.md))
                }
                AnimatedContent(
                    targetState = barTitle,
                    contentKey = { it.transitionKey },
                    modifier = Modifier.clipToBounds(),
                    contentAlignment = Alignment.CenterStart,
                    transitionSpec = {
                        val transition = if (QkMotionPolicy.animationsEnabled) {
                            (slideInHorizontally(QookiX.fastSpatialSpec()) { titleShift } +
                                fadeIn(QookiX.fastEffectsSpec())) togetherWith
                                (slideOutHorizontally(QookiX.fastEffectsSpec()) { -titleShift } +
                                    fadeOut(QookiX.fastEffectsSpec()))
                        } else {
                            EnterTransition.None togetherWith ExitTransition.None
                        }
                        transition.using(null)
                    },
                    label = "barTitle",
                ) { shown ->
                    val body = shown.content
                    if (body != null) {
                        body()
                    } else if (shown.text.isNotEmpty()) {
                        // 页面按钮在自己的过渡内容里测量，避免挤压其他页面的工具栏。
                        val showCreate = shown.destination == NavDestination.Instances && current == shown.destination
                        Row(
                            modifier = if (showCreate) Modifier.fillMaxWidth() else Modifier,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = shown.text,
                                modifier = if (showCreate) Modifier.weight(1f) else Modifier,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (showCreate) {
                                FilledTonalButton(
                                    onClick = onCreateInstance,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.instances_new), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        },
        actions = {
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

/** 顶栏标题（i18n：取自 string 资源）。 */
private data class BarTitle(
    val destination: NavDestination,
    val text: String,
    val content: (@Composable () -> Unit)?,
) {
    // 自定义工具栏的回调经常随输入更新，只在切换页面或工具栏形态时做过渡。
    val transitionKey: Pair<NavDestination, String?>
        get() = destination to when {
            content != null || destination == NavDestination.Browse ||
                destination == NavDestination.ContentDetail || text.isEmpty() -> null
            else -> text
        }
}

@Composable
private fun titleFor(d: NavDestination): String = stringResource(
    when (d) {
        // 首页在横屏下品牌名已经出现在侧栏，顶栏不必重复 —— 用「首页」更清楚
        NavDestination.Home -> R.string.shell_title_home
        NavDestination.Instances -> R.string.shell_title_instances
        NavDestination.Browse -> R.string.shell_title_browse
        // 详情页会通过 onTitleChange 覆盖成资源名，这里只是兜底
        NavDestination.ContentDetail -> R.string.nav_content_detail
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
        NavDestination.QookixAccount -> R.string.qookix_account_title
        NavDestination.Playtime -> R.string.shell_title_playtime
        NavDestination.FrpRooms -> R.string.shell_title_frp_rooms
        NavDestination.Frp -> R.string.shell_title_frp
        NavDestination.LanSync -> R.string.nav_lan_sync
    }
)
