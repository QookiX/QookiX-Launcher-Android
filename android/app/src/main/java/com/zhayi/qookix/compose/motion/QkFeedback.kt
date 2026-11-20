package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale

private const val PRESSED_SCALE = QookiX.PRESS_SCALE
private const val PRESSED_SCALE_SMALL = 0.90f
private const val PRESSED_ALPHA = 0.75f

/**
 * 按下缩放（遵循 [QkMotionPolicy.animationsEnabled]，关动效时无缩放）。
 */
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    small: Boolean = false,
): Modifier = composed {
    if (!QkMotionPolicy.animationsEnabled) return@composed this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) {
            if (small) PRESSED_SCALE_SMALL else PRESSED_SCALE
        } else {
            1f
        },
        animationSpec = QookiX.pressSpec(),
        label = "pressScale",
    )
    this.scale(scale)
}

/** 按下透明度（遵循无障碍与动效策略，无过冲）。 */
fun Modifier.pressAlpha(
    interactionSource: MutableInteractionSource,
): Modifier = composed {
    if (!QkMotionPolicy.animationsEnabled) return@composed this
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) PRESSED_ALPHA else 1f,
        animationSpec = QookiX.fastEffectsSpec(),
        label = "pressAlpha",
    )
    this.alpha(alpha)
}

/** 选中态缩放（遵循动效策略与快空间弹簧）。 */
fun Modifier.selectedScale(selected: Boolean): Modifier = composed {
    if (!QkMotionPolicy.animationsEnabled) return@composed this
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = QookiX.fastSpatialSpec(),
        label = "selectedScale",
    )
    this.scale(scale)
}
