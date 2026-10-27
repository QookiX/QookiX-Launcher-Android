package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.zhayi.qookix.compose.theme.QkRadius
import kotlin.math.roundToInt

/**
 * 共享元素「飞行层」。
 *
 * ## 为什么不用 `SharedTransitionLayout`
 *
 * Compose 官方的共享元素（`SharedTransitionLayout` + `Modifier.sharedElement`）
 * 要求源和目标**同时存在于组合树里**，通常配合 `AnimatedContent` 使用。
 * 而本项目 `PageHost` 用 `when (dest)` 切页 —— 一旦改成 `AnimatedContent`，
 * 各页的 `LaunchedEffect(bridgeReady)` 就不再执行（2026-10-13 踩过：
 * 内容页永远空白、版本清单加载不出来）。
 *
 * ## 这里的做法
 *
 * 在页面**之上**画一个临时浮层：
 *
 * ```
 * 点卡片 → 记录图标位置(Rect) → 切页 → 目标页上报落点(Rect)
 *        → 浮层从起点飞到落点 → 落地后清除，把画面交给真实内容
 * ```
 *
 * 源页和目标页**不需要共存**，只是让一瞬间的视觉元素连续起来。
 *
 * ## 坐标约定
 *
 * 源和落点都必须用 `boundsInWindow()`（窗口坐标），不能用 `boundsInParent()`
 * ——后者是相对直接父容器，跨页面/跨层就会错位（侧栏选中框那次踩过）。
 * 浮层本身放在全屏 Box 里，因此窗口坐标可以直接当父坐标用。
 */
object SharedHero {

    /** 一次飞行的起飞信息。 */
    data class Launch(
        /** 起点（窗口坐标，含尺寸）。 */
        val from: Rect,
        /** 飞行块上显示的字符（实例首字母）。 */
        val label: String,
        val background: Color,
        val foreground: Color,
    )

    /** 待起飞：卡片点击时写入。 */
    var pending by mutableStateOf<Launch?>(null)
        private set

    /** 落点：目标页 hero 布局完成后写入。 */
    var landing by mutableStateOf<Rect?>(null)

    fun launch(from: Rect, label: String, background: Color, foreground: Color) {
        landing = null
        pending = Launch(from, label, background, foreground)
    }

    fun clear() {
        pending = null
        landing = null
    }
}

/**
 * 飞行浮层。挂在 `PageHost` 的最外层 Box（全屏）里，覆盖在所有页面之上。
 *
 * 只有「起点和落点都就绪」时才真正开始动画 —— 否则会先飞到一个占位位置再跳一下。
 */
@Composable
fun SharedHeroOverlay(modifier: Modifier = Modifier) {
    val launch = SharedHero.pending ?: return
    val landing = SharedHero.landing ?: return

    val density = LocalDensity.current
    val progress = remember(launch) { Animatable(0f) }
    LaunchedEffect(launch, landing) {
        // slowSpatialSpec：大范围移动要慢一点才有分量（spring，会轻微过冲）
        progress.animateTo(1f, QookiX.slowSpatialSpec())
        SharedHero.clear()
    }

    val p = progress.value
    val left = launch.from.left + (landing.left - launch.from.left) * p
    val top = launch.from.top + (landing.top - launch.from.top) * p
    val w = launch.from.width + (landing.width - launch.from.width) * p
    val h = launch.from.height + (landing.height - launch.from.height) * p
    // 最后 15% 淡出：让浮层把画面"交给"真实的 hero，避免两个图标同时存在
    val alpha by animateFloatAsState(
        targetValue = if (p < 0.85f) 1f else 0f,
        animationSpec = QookiX.effectsSpec(),
        label = "heroFade",
    )

    Box(
        modifier = modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(
                width = with(density) { w.coerceAtLeast(1f).toDp() },
                height = with(density) { h.coerceAtLeast(1f).toDp() },
            )
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(QkRadius.medium))
            .background(launch.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = launch.label,
            color = launch.foreground,
            fontSize = with(density) { (h * 0.42f).toSp() },
            fontWeight = FontWeight.Bold,
        )
    }
}
