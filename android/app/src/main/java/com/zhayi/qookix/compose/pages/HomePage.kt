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
fun HomePage(
    state: HomeUiState,
    onLaunch: () -> Unit,
    onSwitchInstance: () -> Unit,
    onOpenInstance: (String) -> Unit,
    onOpenNews: () -> Unit,
    // 启动阶段文案（检查文件 / 补全 / 启动中）。
    // 比笼统的「启动中…」有用 —— 补全大包要几分钟，用户需要知道在等什么。
    launchStage: String? = null,
    /** 启动进度 0f..1f（来自 `launch://progress`）。 */
    launchProgress: Float = 0f,
    onCancelTask: (Long) -> Unit = {},
) {
    // ── 响应式：横屏左右分栏，竖屏改单列 ────────────────────────────────
    //
    // ⚠️ 2026-10-15 真机竖屏实测：左栏原写死 width(360.dp)，而竖屏可用宽度只有 384dp，
    // 右栏只剩 24dp，文字被压成竖条。判据用「宽度够不够并排」而不是「是不是竖屏」。
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val wideEnough = cfg.screenWidthDp >= 600

    if (wideEnough) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── 左栏：当前实例主卡片（门面）─────────────────────────────
            Column(
                modifier = Modifier
                    // ⚠️ 2026-10-18：左栏宽度改为「可用宽度的 42%，夹在 300~420dp」。
                    // 写死 360dp 时，600~720dp 的窄平板/分屏下右栏只剩 240~360，
                    // 卡片长文本开始逐字换行，更窄时内容直接被挤出边界。
                    .width(
                        (cfg.screenWidthDp * 0.42f).coerceIn(300f, 420f).dp,
                    )
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(QkSpacing.lg),
            ) {
                QkAppear(scope = "home", index = 0) {
                    CurrentInstanceCard(
                        inst = state.current,
                        launching = state.launching,
                        launchStage = launchStage,
                        launchProgress = launchProgress,
                        running = state.current?.id == state.runningInstanceId,
                        onLaunch = onLaunch,
                        onSwitch = onSwitchInstance,
                    )
                }
            }

            // ── 右栏：次要信息 ───────────────────────────────────────────
            Column(
                modifier = Modifier
                    // ⚠️ weight(1f) 而非 fillMaxSize()：左边是固定 360dp 的实例栏，
                    // fillMaxSize() 会和它抢宽度（竖屏/大缩放时右栏被压成一条）。
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = QkSpacing.xxs, end = 20.dp, top = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.lg),
            ) {
                // 置顶实例（2026-10-21 补：getPins/setPins 一直没 UI）
                if (state.pinned.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.home_pinned))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                        itemsIndexed(state.pinned, key = { _, it -> it.id }) { index, inst ->
                            QkAppear(scope = "home", index = index) {
                                RecentInstanceCard(inst) { onOpenInstance(inst.id) }
                            }
                        }
                    }
                }
                // 横屏时「最近实例」用横向滚动更省纵向空间
                if (state.recent.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.home_recent_instances))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                        itemsIndexed(state.recent, key = { _, it -> it.id }) { index, inst ->
                            // 统一走 QkAppear（全项目唯一一套入场）。
                            // 和实例列表的 QkAppear 是两套实现（时长/曲线都不同）。
                            QkAppear(index = index, scope = "home") {
                                RecentInstanceCard(inst) { onOpenInstance(inst.id) }
                            }
                        }
                    }
                }

                if (state.tasks.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.home_in_progress))
                    state.tasks.forEach { t -> TaskRow(t, onCancel = { onCancelTask(t.id) }) }
                }

                if (state.news.isNotEmpty()) {
                    SectionTitle(
                        text = stringResource(R.string.home_latest_news),
                        action = stringResource(R.string.home_view_all),
                        onAction = onOpenNews,
                    )
                    state.news.take(4).forEach { n -> NewsRow(n) }
                }

                // ⚠️ 这里原来还有一次 `EmptyHint()` —— 与上面 HeroCard 的空态分支
                // **重复显示同样的话**。实机验证（2026-10-14）看到的效果是：
                // 卡片里正常显示两行，右侧还多出一列被挤成竖排的同样文字。
                // 空态由 HeroCard 一处承担就够了，这里不再重复。
            }
        }
    } else {
        // ── 窄屏（竖屏 / 小窗）：单列纵向排布 ──────────────────────────
        //
        // 主卡片 + 次要信息依次堆叠、整页滚动。
        // 窄屏下并排必然把内容压扁，纵向排列是唯一可读的选择。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(QkSpacing.md),
        ) {
            QkAppear(scope = "home", index = 0) {
                CurrentInstanceCard(
                    inst = state.current,
                    launching = state.launching,
                        launchStage = launchStage,
                        launchProgress = launchProgress,
                    running = state.current?.id == state.runningInstanceId,
                    onLaunch = onLaunch,
                    onSwitch = onSwitchInstance,
                )
            }

            Spacer(Modifier.height(QkSpacing.lg))
            HomeSecondaryContent(state, onOpenInstance, onOpenNews, onCancelTask)
        }
    }
}

/**
 * 首页次要区（最近实例 / 进行中 / 最新新闻）。
 *
 * 抽出来是为了**横屏右栏**与**窄屏单列**共用同一份内容 ——
 * 否则两套布局各写一遍，将来加一个区块就会漏掉一边。
 */
@Composable
private fun HomeSecondaryContent(
    state: HomeUiState,
    onOpenInstance: (String) -> Unit,
    onOpenNews: () -> Unit,
    onCancelTask: (Long) -> Unit = {},
) {
    if (state.pinned.isNotEmpty()) {
        SectionTitle(stringResource(R.string.home_pinned))
        state.pinned.forEach { inst ->
            RecentInstanceCard(inst) { onOpenInstance(inst.id) }
            Spacer(Modifier.height(QkSpacing.sm))
        }
    }
    if (state.recent.isNotEmpty()) {
        SectionTitle(stringResource(R.string.home_recent_instances))
        // 横屏时用横向滚动更省纵向空间；窄屏用纵向列表更易读。
        // 这里统一走纵向 —— 窄屏是它的主场，横屏下也只是多占一点高度。
        state.recent.forEachIndexed { index, inst ->
            QkAppear(index = index, scope = "home") {
                RecentInstanceCard(inst) { onOpenInstance(inst.id) }
            }
        }
    }

    if (state.tasks.isNotEmpty()) {
        SectionTitle(stringResource(R.string.home_in_progress))
        state.tasks.forEach { t -> TaskRow(t, onCancel = { onCancelTask(t.id) }) }
    }

    if (state.news.isNotEmpty()) {
        SectionTitle(
            text = stringResource(R.string.home_latest_news),
            action = stringResource(R.string.home_view_all),
            onAction = onOpenNews,
        )
        state.news.take(4).forEach { n -> NewsRow(n) }
    }

    // 游玩统计（B3）：旧 Vue 首页底部有 PlaytimeCard，迁移时漏了。
    // 独立页「更多 → 游玩统计」还在，这里给首页一个总时长的入口。
    if (state.playtimeSeconds > 0L) {
        SectionTitle(stringResource(R.string.home_playtime))
        PlaytimeCard(state.playtimeSeconds)
    }
}

/** 首页游玩统计卡（总时长）。 */
@Composable
private fun PlaytimeCard(seconds: Long) {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (hours > 0) {
                    stringResource(R.string.home_playtime_hm, hours, minutes)
                } else {
                    stringResource(R.string.home_playtime_m, minutes)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
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
private fun CurrentInstanceCard(
    inst: InstanceUi?,
    launching: Boolean,
    // 启动阶段文案（检查文件 / 补全 / 启动中）。
    // 比笼统的「启动中…」有用 —— 补全大包要几分钟，
    // 用户需要知道在等什么。
    launchStage: String? = null,
    /** 启动进度 0f..1f（来自 `launch://progress`）。 */
    launchProgress: Float = 0f,
    running: Boolean = false,
    onLaunch: () -> Unit,
    onSwitch: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        // 对齐 QkRadius.card（28dp）与 QkCard 的容器色。
        // 原来用 shapes.large(20dp) + surfaceContainerHigh —— 比其它卡片更亮、更方，
        // 同一个屏幕里两种卡放一起不像一套（Expressive 的层次应该是
        // 「背景 → 卡片(container) → 卡片内条目(containerLow)」三级，不是四级）。
        shape = RoundedCornerShape(QkRadius.card),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(QkSpacing.lg)) {
            if (inst == null) {
                Text(
                    text = stringResource(R.string.home_no_instances),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(QkSpacing.xs))
                Text(
                    text = stringResource(R.string.home_no_instances_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
            )
            Spacer(Modifier.height(QkSpacing.xs))
            Text(
                text = inst.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
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
private fun RecentInstanceCard(inst: InstanceUi, onClick: () -> Unit) {
    // 按下缩放：source 必须同时传给 Card 本体，否则收不到按压事件
    val pressSource = remember { MutableInteractionSource() }
    Card(
        onClick = onClick,
        interactionSource = pressSource,
        modifier = Modifier
            .width(150.dp)
            .pressScale(pressSource),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
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
                text = inst.name,
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

@Composable
private fun TaskRow(t: TaskUi, onCancel: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(QkSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = t.progressText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            Spacer(Modifier.height(QkSpacing.xs))
            LinearProgressIndicator(
                progress = { t.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun NewsRow(n: NewsItemUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(QkSpacing.md)) {
            Text(
                text = n.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(QkSpacing.xxs))
            Text(
                text = n.date,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}


/** 首页状态。 */
data class HomeUiState(
    val current: InstanceUi? = null,
    val recent: List<InstanceUi> = emptyList(),
    val pinned: List<InstanceUi> = emptyList(),
    val tasks: List<TaskUi> = emptyList(),
    val news: List<NewsItemUi> = emptyList(),
    val launching: Boolean = false,
    /** 正在运行的实例 id（launch://state 推送），用于在卡片上显示「运行中」角标。 */
    val runningInstanceId: String? = null,
    /** 总游玩时长（秒）；0 = 不显示统计卡。 */
    val playtimeSeconds: Long = 0L,
)

/**
 * 由实例名生成图标短标记。
 *
 * 实例名常是版本号（「26.3」「1.20.1」）或带前缀的名字（「Fabric 1.20.1」）。
 * 规则：
 *   - 有字母 → 取首个字母（大写）
 *   - 纯数字/版本号 → 取主版本号（「26.3」→「26」），最多 2 字符
 * 目的是让图标一眼可辨，而不是孤零零一个 "2"。
 */
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
