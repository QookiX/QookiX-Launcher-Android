package com.zhayi.qookix.compose.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * 动效曲线。
 *
 * ## 为什么要有「过冲」
 *
 * 标准 `EaseOut` 是**单调减速**：值从 0 涨到 1 就停住。看起来正确，但没有"重量"——
 * 用户说不像 ZL2，差的正是这个：它的曲线会先冲过头一点点，再弹回来。
 *
 * 这里的两条「弹」曲线用的是**阻尼余弦模型**：
 *
 *     f(t) = 1 - A · e^(-λt) · cos(ωt)
 *
 *   · A  振幅：决定冲过头多少（0.6 = 冲出去 60%）
 *   · λ  衰减：震荡多快停下来
 *   · ω  频率：震荡几次
 *
 * 这是**物理模型**（弹簧-阻尼系统的解析解），不是贝塞尔近似 —— 所以它的
 * 过冲和衰减是自洽的，不会出现"弹得很假"。Compose 的 `Easing` 允许返回值
 * 超出 [0,1]，`animate*AsState` / `Animatable` 会正确地把过冲画出来。
 *
 * ⚠️ 别到处用：位移类转场用 [SoftBounce]（克制），只有需要「存在感」的
 * 弹出/切换才用 [Jelly]。全用果冻会晕。
 */
object QkEasing {

    /**
     * 果冻弹：A=0.6、衰减 8、约 3 个震荡周期。
     *
     * 冲过头 60%，肉眼可见地"弹"，适合：弹层出现、Tab 指示器切换、主题切换。
     */
    val Jelly: Easing = Easing { t ->
        (1.0 - 0.6 * exp(-8.0 * t) * cos(6.0 * PI * t)).toFloat()
    }

    /**
     * 轻弹：A=0.35、衰减 9、约 2.5 个周期。
     *
     * 只冲过头 35%，是"有弹性但不出戏"的那种 —— **页面转场默认用它**。
     */
    val SoftBounce: Easing = Easing { t ->
        (1.0 - 0.35 * exp(-9.0 * t) * cos(5.0 * PI * t)).toFloat()
    }

    /**
     * 标准减速（近似 MD3 的 emphasized decelerate）。
     *
     * 用于「大块内容移动」：拖动、滚动、窗口尺寸变化 —— 这些地方不该有回弹。
     */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}
