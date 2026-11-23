package com.zhayi.qookix.compose.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment

/**
 * 页面内与局部转场集合（Tab / 状态 / 折叠容器）。
 *
 * 所有 spec 均走 [QookiX] 且响应 [QkMotionPolicy]：系统关闭动画时自动退化为瞬时切换。
 * 整页切换仍保持 `PageHost` 的 `when (dest)` + [qkPageLayer]（避免 `AnimatedContent` 破坏 `LaunchedEffect`）。
 */

fun qkFadeIn(): EnterTransition =
    fadeIn(animationSpec = QookiX.fastEffectsSpec())

fun qkFadeOut(): ExitTransition =
    fadeOut(animationSpec = QookiX.fastEffectsSpec())

fun qkSlideInRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = QookiX.defaultSpatialSpec(),
        initialOffsetX = { (it * 0.14f).toInt() },
    ) + fadeIn(QookiX.fastEffectsSpec())

fun qkSlideInLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = QookiX.defaultSpatialSpec(),
        initialOffsetX = { -(it * 0.14f).toInt() },
    ) + fadeIn(QookiX.fastEffectsSpec())

/** 无方向状态切换（加载中 / 错误 / 空态 / 内容）。 */
fun qkFadeThrough(): ContentTransform {
    if (!QkMotionPolicy.animationsEnabled) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    return fadeIn(QookiX.effectsSpec()) togetherWith fadeOut(QookiX.fastEffectsSpec())
}

/** 提示条与折叠项单调展开（绝不过冲高度）。 */
fun qkExpandVertically(): EnterTransition =
    if (!QkMotionPolicy.animationsEnabled) {
        EnterTransition.None
    } else {
        expandVertically(
            animationSpec = QookiX.layoutSpec(),
            expandFrom = Alignment.Top,
        ) + fadeIn(QookiX.fastEffectsSpec())
    }

/** 提示条与折叠项单调收起。 */
fun qkShrinkVertically(): ExitTransition =
    if (!QkMotionPolicy.animationsEnabled) {
        ExitTransition.None
    } else {
        shrinkVertically(
            animationSpec = QookiX.layoutSpec(),
            shrinkTowards = Alignment.Top,
        ) + fadeOut(QookiX.fastEffectsSpec())
    }
