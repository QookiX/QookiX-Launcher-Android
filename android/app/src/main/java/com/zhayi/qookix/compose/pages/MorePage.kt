package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.animation.AnimatedVisibility
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhayi.qookix.compose.motion.qkListItemEnter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.QkAppear

/**
 * 「更多」页。
 *
 * 对应旧界面 views/MoreView.vue。行为一致：分组列表、整行可点、右侧箭头。
 * 入口清单也保持一致：皮肤 / 新闻（可被设置关掉）/ 设置。
 *
 * 与旧实现的差异（都是 MD3 规范要求）：
 *   - 用 Surface(containerLow) 代替 `.glass` 卡片（MD3 靠明度分层，不靠描边）
 *   - 圆角 12dp（medium）而非 14px
 *   - 行高 56dp 保持（这是触控底线，旧实现也是 56px，无需改）
 */
@Composable
fun MorePage(
    onOpenSkins: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaytime: () -> Unit,
    showNews: Boolean = true,
) {
    val entries = buildList {
        add(MoreEntry("skins", R.string.more_skins, Icons.Filled.Person, onOpenSkins))
        if (showNews) add(MoreEntry("news", R.string.more_news, Icons.Filled.Newspaper, onOpenNews))
        // 游玩统计放在设置之前 —— 它是"想看自己玩了多少"的常客，
        // 属于使用频次较高的功能，不该藏在设置深处
        add(MoreEntry("playtime", R.string.more_playtime, Icons.Filled.Timeline, onOpenPlaytime))
        add(MoreEntry("settings", R.string.more_settings, Icons.Filled.Settings, onOpenSettings))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = QkSpacing.md, end = 16.dp, top = 4.dp, bottom = 16.dp),
    ) {
        MoreEntryList(entries)
    }
}

/**
 * 「更多」式入口列表（Surface 卡 + 56dp 行 + 分隔线）。
 *
 * 2026-10-27 从 MorePage 抽出来：「我的」页（Account）也要一份一模一样的
 * 入口列表（皮肤 / 统计 / 设置…），复制一份必然走样。
 */
@Composable
internal fun MoreEntryList(entries: List<MoreEntry>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            entries.forEachIndexed { i, e ->
                // 统一走 QkAppear（全项目唯一一套入场）。
                // 原来这里是内联的 AnimatedVisibility + qkListItemEnter，
                // 和实例列表/首页是另外两套实现（时长、曲线、错开间隔都不同）。
                QkAppear(index = i, scope = "more") {
                    MoreRow(e)
                }
                if (i != entries.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

internal data class MoreEntry(
    val name: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun MoreRow(e: MoreEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = e.onClick)
            .padding(horizontal = QkSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = e.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(e.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


