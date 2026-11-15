package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha

/**
 * 交互反馈动画。
 *
 * ── 为什么要有这一层 ────────────────────────────────────────────────────
 * Material 3 的默认涟漪（ripple）只覆盖**按下去那一小块**，
 * 但用户按的是"卡片"这种大目标时，缺少整体反馈：
 * 看不出自己点中了什么，也看不出"这一下生效了没"。
 *
 * 这一层补三件事：
 *   1. **按下时整体缩放** —— 大目标（卡片）被"按下去"的物理感
 *   2. **按下时透明度变化** —— 小目标（图标按钮）的即时反馈
 *   3. **选中态平滑过渡** —— 开关/chip 切换不跳变
 *
 * 幅度刻意做小（0.97 / 0.9），因为这是**日常操作**，
 * 不是展示动画 —— 幅度大了每一下都晃眼。
 */

// 引用 QookiX 的唯一真源，别再各写一份（原来这里硬编码 0.97，
// 而 QookiX.PRESS_SCALE 也是 0.97 —— 两处独立维护，改一处另一处不生效）。
private const val PRESSED_SCALE = QookiX.PRESS_SCALE
private const val PRESSED_SCALE_SMALL = 0.90f
private const val PRESSED_ALPHA = 0.75f

/**
 * 按下缩放。
 *
 * @param small 是否小目标（图标按钮用更大幅度）
 *
 * 用 `interactionSource` 而不是 `onPress` 回调：前者能拿到
 * 完整的按下/抬起状态机，且和 ripple 共享同一个 source
 * （否则会出现"涟漪在跳但卡片没缩"的割裂感）。
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    small: Boolean = false,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) {
            if (small) PRESSED_SCALE_SMALL else PRESSED_SCALE
        } else {
            1f
        },
        // 与 qkPressable 共用同一套弹簧：原来这里是 tween(80/150)，
        // 两条按压路径一个弹一个不弹，同一个按钮在不同页面手感不一样。
        animationSpec = QookiX.pressSpec(),
        label = "pressScale",
    )
    this.scale(scale)
}

/** 按下透明度（用于小图标按钮）。 */
fun Modifier.pressAlpha(
    interactionSource: MutableInteractionSource,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) PRESSED_ALPHA else 1f,
        animationSpec = tween(QkMotionPolicy.PRESS_IN_MS, easing = QookiX.EaseOut),
        label = "pressAlpha",
    )
    this.alpha(alpha)
}

/**
 * 选中态缩放（用于 chip / tab 指示器）。
 *
 * 选中的项略微放大，给"当前在哪"一个除颜色外的**第二重线索** ——
 * 对色觉障碍用户更友好（不只能靠颜色区分）。
 */
fun Modifier.selectedScale(selected: Boolean): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = tween(QookiX.SHORT_MS, easing = QookiX.EaseOut),
        label = "selectedScale",
    )
    this.scale(scale)
}
