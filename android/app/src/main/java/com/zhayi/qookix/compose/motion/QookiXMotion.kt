package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

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

    // ── 带过冲的曲线（阻尼余弦）────────────────────────────────────────

    /**
     * 轻弹：`1 - 0.35·e^(-9t)·cos(5πt)`。
     *
     * 上面那三条 [EaseOut] / [EaseIn] / [EaseInOut] 都是**单调减速**：值涨到 1
     * 就停住，所以「到位得太平」，没有重量感。这条会先冲过头 35% 再收回来，
     * 那一「收」就是重量感的来源。
     *
     * 用的是**阻尼余弦物理模型**（弹簧-阻尼系统的解析解），不是贝塞尔近似 ——
     * 过冲和衰减自洽，不会「弹得很假」。**页面转场用它**（[pageSpec]）。
     */
    val SoftBounce: Easing = Easing { t ->
        (1.0 - 0.35 * exp(-9.0 * t) * cos(5.0 * PI * t)).toFloat()
    }

    /**
     * 果冻弹：`1 - 0.6·e^(-8t)·cos(6πt)`，冲过头 60%、约 3 个周期。
     *
     * 只在需要「存在感」的地方用（弹层出现、主题切换）。**不要用在列表或转场上** ——
     * 高频动作里回弹会变成噪音。
     */
    val Jelly: Easing = Easing { t ->
        (1.0 - 0.6 * exp(-8.0 * t) * cos(6.0 * PI * t)).toFloat()
    }

    // ── 语义化 spec ────────────────────────────────────────────────────
    //
    // 调用方只需要说「这是进入 / 退出 / 整页切换 / 按压」，不再各自挑时长和曲线。
    // 以前那些 `tween(200, …)` / `tween(320, …)` 就是这么长出六种的。

    /** 元素**进入**屏幕：MEDIUM + EaseOut。 */
    fun <T> enterSpec(): FiniteAnimationSpec<T> = tween(MEDIUM_MS, easing = EaseOut)

    /** 元素**离开**屏幕：MEDIUM + EaseIn。 */
    fun <T> exitSpec(): FiniteAnimationSpec<T> = tween(MEDIUM_MS, easing = EaseIn)

    /**
     * 整页切换：MEDIUM + [EaseOut]。
     *
     * ⚠️ 这里**不能用 [SoftBounce]**（踩过）：
     * 阻尼余弦在 t=0.1 时就已经接近 1（0.65 → 1 只用了 10% 的时间），
     * 拿它驱动 alpha 时，300ms 的淡入实际只有 30ms 可见 —— 看起来就是「没有动画」。
     *
     * 回弹曲线只适合**位移/缩放**（冲过头再收回来才有弹性感）；
     * 透明度必须用单调曲线，否则「淡入」会被压缩到肉眼看不见的一瞬。
     */
    fun <T> pageSpec(): FiniteAnimationSpec<T> = tween(MEDIUM_MS, easing = EaseOut)

    /** 按压缩放：阻尼 0.55、刚度 900（跟手、略回弹）。 */
    fun <T> pressSpec(): FiniteAnimationSpec<T> = spring(
        dampingRatio = 0.55f,
        stiffness = 900f,
    )

    /** 按下时的缩放比例。 */
    const val PRESS_SCALE = 0.97f
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
