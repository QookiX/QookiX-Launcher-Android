package com.zhayi.qookix.compose.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhayi.qookix.compose.motion.QkMotionPolicy
import com.zhayi.qookix.compose.motion.QookiX
import com.zhayi.qookix.compose.motion.qkExpandVertically
import com.zhayi.qookix.compose.motion.qkShrinkVertically
import com.zhayi.qookix.compose.theme.QkSpacing
import kotlinx.coroutines.launch

/**
 * 点击与滑动共用页面的选中状态，手势停稳后才切换业务状态。
 * [pagerState] 可由调用方与页签栏共享，页数必须与 [tabs] 一致。
 */
@Composable
fun <T> QkTabContent(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    skipInterveningPages: Boolean = false,
    pagerState: PagerState? = null,
    content: @Composable (T) -> Unit,
) {
    val selectedIndex = tabs.indexOf(selected).coerceAtLeast(0)
    val pager = pagerState ?: rememberPagerState(initialPage = selectedIndex) { tabs.size }
    val selectTab by rememberUpdatedState(onSelect)
    val currentTabs by rememberUpdatedState(tabs)
    LaunchedEffect(pager, selectedIndex) {
        val distance = kotlin.math.abs(pager.settledPage - selectedIndex)
        if (pager.settledPage != selectedIndex) {
            // 触摸可以打断点击触发的滚动。独立子任务取消时，仍要继续观察手势。
            launch {
                when {
                    // 隔了好几个页签的点击（横屏很常见）：直接定位。
                    // 滚动过去会把途经的页整页扫一遍，看着像在晃整张桌面。
                    skipInterveningPages && distance > 1 -> pager.scrollToPage(selectedIndex)
                    QkMotionPolicy.animationsEnabled -> pager.animateScrollToPage(selectedIndex)
                    else -> pager.scrollToPage(selectedIndex)
                }
            }.join()
        }
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page != selectedIndex) currentTabs.getOrNull(page)?.let(selectTab)
        }
    }
    HorizontalPager(
        state = pager,
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) { page ->
        content(tabs[page])
    }
}

/** 统一的内容状态交叉淡入容器（加载 / 空态 / 错误 / 数据）。 */
@Composable
fun <T> QkStateCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    label: String = "qkStateCrossfade",
    content: @Composable (T) -> Unit,
) {
    Crossfade(
        targetState = targetState,
        modifier = modifier,
        animationSpec = QookiX.effectsSpec(),
        label = label,
    ) { state ->
        content(state)
    }
}

/** 平滑进度条：确定进度走 [QookiX.progressSpec]，不确定进度展示统一指示器。 */
@Composable
fun QkProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
        )
    } else {
        val target = progress.coerceIn(0f, 1f)
        val animated by animateFloatAsState(
            targetValue = target,
            animationSpec = QookiX.progressSpec(),
            label = "qkProgress",
        )
        LinearProgressIndicator(
            progress = { if (QkMotionPolicy.animationsEnabled) animated else target },
            modifier = modifier.fillMaxWidth(),
            color = color,
            trackColor = trackColor,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    }
}

/** 提示条 / 可展开区的平滑显现。 */
@Composable
fun QkBannerVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = qkExpandVertically(),
        exit = qkShrinkVertically(),
    ) {
        content()
    }
}

/** 平滑旋转的展开指示箭头。 */
@Composable
fun QkExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val deg by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = QookiX.fastSpatialSpec(),
        label = "qkChevron",
    )
    val applied = if (QkMotionPolicy.animationsEnabled) deg else if (expanded) 180f else 0f
    Icon(
        imageVector = Icons.Filled.ExpandMore,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.rotate(applied),
    )
}

/**
 * 全应用统一的底部弹窗抽屉。
 *
 * 保持 `Dialog(decorFitsSystemWindows = false)`（不改用会顶出系统状态栏的 `ModalBottomSheet`）。
 */
@Composable
fun QkBottomSheetDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val sheetMaxHeight = (screenHeightDp * 0.85f).dp
    val startOffset = 96.dp.coerceAtMost((screenHeightDp * 0.15f).dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var shown by remember { mutableStateOf(!QkMotionPolicy.animationsEnabled) }
        LaunchedEffect(Unit) { shown = true }
        val slide by animateDpAsState(
            targetValue = if (shown) 0.dp else startOffset,
            animationSpec = QookiX.defaultSpatialSpec(),
            label = "qkSheetSlide",
        )
        val fade by animateFloatAsState(
            targetValue = if (shown) 1f else 0f,
            animationSpec = QookiX.effectsSpec(),
            label = "qkSheetFade",
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { }
                    .heightIn(max = sheetMaxHeight)
                    .offset { IntOffset(0, slide.roundToPx()) }
                    .graphicsLayer { alpha = fade },
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = QkSpacing.lg)
                        .padding(top = QkSpacing.sm, bottom = 28.dp),
                    content = content,
                )
            }
        }
    }
}
