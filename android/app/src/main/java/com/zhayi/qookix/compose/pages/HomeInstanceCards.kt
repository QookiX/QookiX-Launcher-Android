/*
 * 首页的实例卡片（2026-11-04 从 HomePage.kt 拆出）。
 *
 * 拆分原因：HomePage.kt 顶到 664 行预算后，给空态加一个按钮都得先删两行注释。
 * 而这三块（小节标题 / 主卡 / 最近卡片）本来就是独立组件，与页面布局无关。
 */

package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.theme.QkRadius

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import com.zhayi.qookix.compose.motion.pressScale
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.ui.QkAppear

/**
 * 首页（门面）。
 *
 * ── 设计取向：游戏启动器的门面感 ────────────────────────────────────────
 * 这是用户明确要的「混合风格」里的**游戏感那一半**：
 *   - 左侧：当前实例的**主卡片** —— 大、突出、带醒目的「启动游戏」大按钮
 *   - 右侧：最近实例的横向卡片列表 + 进行中任务 + 最新新闻
 *
 * 横屏（本项目优先）下左右分栏；竖屏时自然堆叠成单列。
 *
 * ── 为什么这样布局 ─────────────────────────────────────────────────────
 * 横屏宽度充裕（本机 ~1800dp 可用宽），把「当前实例 + 启动」放左侧固定宽度，
 * 视线不用横跨整屏就能完成最高频动作；右侧放次要信息（可滚动）。
 *
 * 全部用 MD3 组件：Card / Button / LinearProgressIndicator，
 * 靠 surface-container 明度差分层，不画描边。
 */

@Composable
internal fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            // ⚠️ 原来只有 `.then(Modifier)`（空操作）→「查看全部」是死文本，P1-13
            Text(
                text = action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onAction)
                    .padding(horizontal = QkSpacing.xs, vertical = 4.dp),
            )
        }
    }
}

/**
 * 当前实例主卡片 —— 首页的视觉重心。
 *
 * 设计：图标区 + 实例名 + 版本徽标 + **大号「启动游戏」按钮** + 切换按钮。
 * 按钮用 `primary` 填充色，是整屏最强的视觉元素（游戏启动器该有的样子）。
 */
@Composable
internal fun CurrentInstanceCard(
    inst: InstanceUi?,
    launching: Boolean,
    // 启动阶段文案：补全大包要几分钟，用户需要知道在等什么
    launchStage: String? = null,
    launchProgress: Float = 0f,
    running: Boolean = false,
    onLaunch: () -> Unit,
    onSwitch: () -> Unit,
    onCreateInstance: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        // 对齐 QkRadius.card（28dp）与 QkCard 的容器色。
        // 原来用 shapes.large(20dp) + surfaceContainerHigh —— 比其它卡片更亮、更方，
        // 同一个屏幕里两种卡放一起不像一套（Expressive 的层次应该是
        // 「背景 → 卡片(container) → 卡片内条目(containerLow)」三级，不是四级）。
        shape = RoundedCornerShape(QkRadius.card),
        // 主卡用 High（比背景亮两档），小卡用 Low —— 三级层次：
        //   背景 background → 主卡 High → 小卡 Low
        // 之前全部用 Container，卡片和背景只差一档，视觉上"糊"在一起，
        // 这正是和 ZL2 最大的差距（它的卡片明显是浮起来的）。
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        // ⚠️ 2026-10-28：内容改成**水平居中**（原来全部左对齐）。
        //
        // 左对齐时整张卡的视觉重心偏左上，右边和下方各留一大块空 —— 就是
        // 「上挤下空」。ZL2 那张主卡是居中英雄式的：图标居中、名字居中、
        // 按钮压底撑满，重心落在卡片中央，看起来"实"。
        Column(
            modifier = Modifier.padding(QkSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (inst == null) {
                Text(
                    text = stringResource(R.string.home_no_instances),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(QkSpacing.md))
                Button(onClick = onCreateInstance) {
                    Text(stringResource(R.string.home_no_instances_hint))
                }
                return@Column
            }

            // 实例图标（大）
            Surface(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(QkRadius.card)),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        // ⚠️ 取「首个字母或数字」而不是 take(1)：
                        // 实例名常以版本号开头（如「26.3」），take(1) 只会得到 "2"，
                        // 看起来像页码而不是图标。这里取首个字母/数字组成的短标记。
                        text = iconGlyph(inst.name),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(QkSpacing.md))
            Text(
                text = inst.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                // 居中卡片里文字也要居中，否则名字贴着左边、图标居中，重心又歪了
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(QkSpacing.xs))
            Text(
                text = inst.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )

            Spacer(Modifier.height(QkSpacing.lg))

            // 启动按钮：整屏最强的视觉元素
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 按下缩放会干扰点击命中（见下方注释），这里不再对启动按钮使用
                val launchSource = remember { MutableInteractionSource() }
                Button(
                    onClick = onLaunch,
                    enabled = !launching,
                    interactionSource = launchSource,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    if (launching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (launching) {
                            launchStage ?: stringResource(R.string.home_launching)
                        } else if (running) {
                            stringResource(R.string.home_running)
                        } else {
                            stringResource(R.string.home_launch_game)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                // 切换实例
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    IconButton(
                        onClick = onSwitch,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = stringResource(R.string.home_switch_instance),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 启动进度：`launch://progress` 的百分比一直只写不读（审查 P1-2）。
            // 补全大实例要好几分钟，光有阶段文案用户不知道还剩多少。
            if (launching && launchProgress > 0f) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { launchProgress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun RecentInstanceCard(inst: InstanceUi, onClick: () -> Unit) {
    // 按下缩放：source 必须同时传给 Card 本体，否则收不到按压事件
    val pressSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = pressSource,
        modifier = Modifier
            .width(150.dp)
            .pressScale(pressSource),
        shape = MaterialTheme.shapes.medium,
        // Low(tone 10) 和背景(tone 6)只差 4 档，卡片会"看不见"；提到 Container(12)。
        // 三级变成 背景 6 → 小卡 12 → 主卡 17。
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(QkSpacing.md)) {
            Surface(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(QkRadius.medium)),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = inst.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(QkSpacing.sm))
            Text(
                // ⚠️ 不能直接用 inst.name：它带着「（1.21.1）3.1.2」这类版本后缀，
                // 而这张卡只有 150dp 宽 —— 表现是「月落花野（1.21.1…」永远被截断。
                // 版本信息下面 subtitle 已经有一份（NeoForge 21.1.251），这里只取实例名。
                text = inst.name
                    .substringBefore("（")
                    .substringBefore("(")
                    .trim()
                    .ifBlank { inst.name },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = inst.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun iconGlyph(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"

    // 有字母：取首个字母
    val letter = trimmed.firstOrNull { it.isLetter() }
    if (letter != null) return letter.uppercase()

    // 纯数字：取「.」前的主版本号，最多 2 字符
    val major = trimmed.substringBefore('.')
    return major.take(2).ifEmpty { "?" }
}
