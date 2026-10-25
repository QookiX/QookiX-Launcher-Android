package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * QookiX 的动画规格。
 *
 * ── 为什么集中在一处 ────────────────────────────────────────────────────
 * 动画散在各文件里最容易出的问题不是"没有动画"，而是**每个文件的时长都不一样** ——
 * 首页 300ms、设置 150ms、列表 500ms，串起来像三套系统。
 *
 * 集中定义后：
 *   1. 换风格只改这里
 *   2. 新写组件有明确参照，不用猜
 *   3. 出问题时能整体调（比如低端机上统一缩短）
 *
 * ── 取值依据（MD3 动效规范）────────────────────────────────────────────
 * MD3 定义了四类动效时长：
 *
 * | 类型 | 时长 | 用途 |
 * |---|---|---|
 * | Short (短) | 100–200ms | 小控件反馈：开关、chip 选中、涟漪 |
 * | Medium (中) | 250–400ms | 页面内元素进出、卡片展开 |
 * | Long (长) | 400–500ms | 全屏页面转场 |
 * | Extra long | 500ms+ | 启动动画、加载完成 |
 *
 * 本项目绝大多数用 Medium 与 Long，因为启动器以**页面切换**为主。
 */
object QookiX {

    // ── 时长 ────────────────────────────────────────────────────────────

    /** 小控件反馈（Switch / Chip / Ripple）。 */
    const val SHORT_MS = 150

    /** 页面内元素进出、卡片展开。 */
    const val MEDIUM_MS = 300

    /** 全屏页面转场。 */
    const val LONG_MS = 420

    /** 加载完成、首次进场。 */
    const val XLONG_MS = 550

    // ── 缓动 ────────────────────────────────────────────────────────────

    /**
     * 标准减速曲线（MD3 "Emphasized Decelerate"）。
     *
     * 用于**进入屏幕**的元素：起步快、收尾慢，
     * 给人"到位了就稳了"的感觉。
     */
    val EaseOut: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /**
     * 标准加速曲线（MD3 "Emphasized Accelerate"）。
     *
     * 用于**离开屏幕**的元素：起步慢、收尾快，
     * 给人"被送走"的感觉。
     */
    val EaseIn: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /**
     * 进出都用（MD3 "Standard"）。
     *
     * 两端对称，适合**位置移动**这类中性动效。
     */
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** 弹性曲线：用于"回弹"类效果（下拉刷新、按钮按下）。 */
    val Springy: FiniteAnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium,
    )

    /** 位置移动用的弹性规格（弹簧在 Compose 里对 Offset 更自然）。 */
    fun <T> springOffset(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = null,
    )

    // ── 页面转场 ────────────────────────────────────────────────────────

    /** 页面淡入淡出时长。 */
    const val FADE_MS = LONG_MS

    /** 页面位移距离（dp）—— 横屏下用小一点的位移更克制。 */
    const val SLIDE_DP = 24

    /** 页面缩放的起始值（1.0 = 不缩放）。 */
    const val SCALE_START = 0.96f
}

/** 页面转场的位移规格（IntOffset 专用，用于 AnimatedContent 的 slideIntoContainer）。 */
fun <T> slideSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = QookiX.LONG_MS,
    easing = QookiX.EaseOut,
)

/** 淡入淡出规格。 */
fun <T> fadeSpec(): FiniteAnimationSpec<T> = tween(
    durationMillis = QookiX.FADE_MS,
    easing = QookiX.EaseInOut,
)

/** IntOffset 的 slideIntoContainer 辅助。 */
fun slideIntoStart(): IntOffset = IntOffset(QookiX.SLIDE_DP * 4, 0)

fun slideIntoEnd(): IntOffset = IntOffset(-QookiX.SLIDE_DP * 4, 0)

fun slideOutToStart(): IntOffset = IntOffset(-QookiX.SLIDE_DP * 4, 0)

fun slideOutToEnd(): IntOffset = IntOffset(QookiX.SLIDE_DP * 4, 0)
