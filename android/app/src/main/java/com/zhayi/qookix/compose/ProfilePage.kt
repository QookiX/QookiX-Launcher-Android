package com.zhayi.qookix.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.pages.AccountUi
import com.zhayi.qookix.compose.pages.MoreEntry
import com.zhayi.qookix.compose.pages.MoreEntryList
import com.zhayi.qookix.compose.pages.TaskCard
import com.zhayi.qookix.compose.pages.TaskUi
import com.zhayi.qookix.compose.shell.NavDestination
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 「我的」页的三个展示块：身份卡 / 进行中任务 / 入口列表。
 *
 * 拆出来说明见 [ProfileSections] 的调用处（AccountHomeRoutes.kt）。
 */

/** 身份卡：头像（名字首字母）+ 名字 + 账号类型；没有账号时引导添加。 */
@Composable
internal fun ProfileIdentityCard(current: AccountUi?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QkSpacing.lg, vertical = 10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像：取名字首字母（离线/正版都这么画，正式头像等正版皮肤接口接入后再换）
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = current?.name?.take(1)?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(QkSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                if (current != null) {
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        // 名字已经在上面一行了，这里只说账号类型，别重复名字
                        text = if (current.online) {
                            stringResource(R.string.profile_account_online)
                        } else {
                            stringResource(R.string.profile_account_offline)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.account_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.profile_add_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 进行中的任务（下载 / 安装 / 补全），直接复用下载页的 TaskCard。 */
@Composable
internal fun ProfileTasksSection(tasks: List<TaskUi>, onCancelTask: (Long) -> Unit) {
    if (tasks.isEmpty()) return
    Text(
        text = stringResource(R.string.profile_tasks),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = QkSpacing.lg),
    )
    tasks.forEach { t ->
        TaskCard(t, onCancel = { onCancelTask(t.id) })
    }
}

/** 入口列表：下载任务 / 皮肤 / 游戏统计 / 新闻 / 设置。 */
@Composable
internal fun ProfileEntryList(onNavigate: (NavDestination) -> Unit, modifier: Modifier = Modifier) {
    MoreEntryList(
        listOf(
            MoreEntry("downloads", R.string.profile_downloads, Icons.Filled.Download) {
                onNavigate(NavDestination.Downloads)
            },
            // 局域网同步原来只能靠桌面端二维码 deep link 唤起，
            // 应用里根本找不到入口（NavDestination 的注释写着「或设置页入口」，
            // 但设置页一直没做）。放在「下载任务」旁边最顺手 —— 都是「把东西搬进来」。
            MoreEntry("lan", R.string.nav_lan_sync, Icons.Filled.SwapHoriz) {
                onNavigate(NavDestination.LanSync)
            },
            MoreEntry("skins", R.string.more_skins, Icons.Filled.Person) {
                onNavigate(NavDestination.Skins)
            },
            MoreEntry("playtime", R.string.more_playtime, Icons.Filled.Timeline) {
                onNavigate(NavDestination.Playtime)
            },
            MoreEntry("news", R.string.more_news, Icons.Filled.Newspaper) {
                onNavigate(NavDestination.News)
            },
            MoreEntry("settings", R.string.more_settings, Icons.Filled.Settings) {
                onNavigate(NavDestination.Settings)
            },
        ),
        modifier = modifier,
    )
}
