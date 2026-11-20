package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * QookiX 的统一动效规格表（对齐 Material 3 Expressive：Spatial / Effects 双通道）。
 *
 * - **Spatial（空间移动 / 几何偏移 / 弹性尺度）**：`fastSpatialSpec` / `defaultSpatialSpec` / `spatialSpec`，
 *   仅用于视觉层位移、缩放、指示器滑块，不得用于透明度或频繁触发整屏重排的宽高等属性。
 * - **Effects（透明度 / 颜色 / 布局宽高单调收敛）**：`fastEffectsSpec` / `effectsSpec` / `layoutSpec` / `progressSpec`，
 *   dampingRatio = 1f 或单调缓动，确保无过冲、不拖影。
 */
object QookiX {

    /** 小控件反馈（Switch / Chip / Icon）。 */
    const val SHORT_MS = 150

    /** 页面内元素进出、卡片展开。 */
    const val MEDIUM_MS = 300

    /** 全屏/标签内容转场。 */
    const val LONG_MS = 400

    /** 数字滚动等长周期统计动效。 */
    const val XLONG_MS = 550

    /** 标准减速曲线（MD3 "Emphasized Decelerate"）。 */
    val EaseOut: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

    /** 标准加速曲线（MD3 "Emphasized Accelerate"）。 */
    val EaseIn: Easing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** 进出对称曲线（MD3 "Standard"）。 */
    val EaseInOut: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)

    /** 按下时的缩放比例。 */
    const val PRESS_SCALE = 0.95f

    /** 系统关闭动画时所有 spec 退化为瞬时切换（见 QkMotionPolicy）。 */
    private fun <T> reduced(): FiniteAnimationSpec<T>? =
        if (QkMotionPolicy.animationsEnabled) null else snap()

    /** 快空间弹簧（指示器切换、小微交互位移/旋转）。 */
    fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        reduced() ?: spring(dampingRatio = 0.85f, stiffness = 700f)

    /** 默认空间弹簧（页面内位移、卡片滑入、底抽屉弹升）。 */
    fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        reduced() ?: spring(dampingRatio = 0.82f, stiffness = 420f)

    /** 位移兼容入口。 */
    fun <T> spatialSpec(): FiniteAnimationSpec<T> = defaultSpatialSpec()

    /** 快效果规格（按压透明、芯片颜色、小图标切换，无过冲）。 */
    fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        reduced() ?: tween(SHORT_MS, easing = EaseOut)

    /** 标准效果规格（透明度 / 颜色，无弹性、快收敛）。 */
    fun <T> effectsSpec(): FiniteAnimationSpec<T> =
        reduced() ?: spring(dampingRatio = 1f, stiffness = 1400f)

    /** 进度条专用平滑规格（避免网络进度跳变时生硬卡顿）。 */
    fun progressSpec(): FiniteAnimationSpec<Float> =
        reduced() ?: tween(240, easing = EaseOut)

    /** 会触发布局测量（宽度/高度/展开收起）的单调规格，绝不过冲。 */
    fun <T> layoutSpec(): FiniteAnimationSpec<T> =
        reduced() ?: tween(220, easing = EaseOut)

    /** 元素**进入**屏幕：MEDIUM + EaseOut。 */
    fun <T> enterSpec(): FiniteAnimationSpec<T> =
        reduced() ?: tween(MEDIUM_MS, easing = EaseOut)

    /** 兼容入口：单调页面收敛。 */
    fun <T> pageSpec(): FiniteAnimationSpec<T> = layoutSpec()

    /** 按压缩放弹簧（明显回弹手感）。 */
    fun <T> pressSpec(): FiniteAnimationSpec<T> =
        reduced() ?: spring(dampingRatio = 0.55f, stiffness = 900f)
}
