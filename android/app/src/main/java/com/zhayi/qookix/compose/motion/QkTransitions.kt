package com.zhayi.qookix.compose.motion

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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

/** 详情/设置/下载页共享的方向性 Tab 转场。 */
fun qkTabTransition(forward: Boolean): ContentTransform {
    if (!QkMotionPolicy.animationsEnabled) {
        return EnterTransition.None togetherWith ExitTransition.None
    }
    val enter = if (forward) {
        slideInHorizontally(QookiX.defaultSpatialSpec()) { (it * 0.14f).toInt() } +
            fadeIn(QookiX.fastEffectsSpec())
    } else {
        slideInHorizontally(QookiX.defaultSpatialSpec()) { -(it * 0.14f).toInt() } +
            fadeIn(QookiX.fastEffectsSpec())
    }
    val exit = if (forward) {
        slideOutHorizontally(QookiX.fastEffectsSpec()) { -(it * 0.08f).toInt() } +
            fadeOut(QookiX.fastEffectsSpec())
    } else {
        slideOutHorizontally(QookiX.fastEffectsSpec()) { (it * 0.08f).toInt() } +
            fadeOut(QookiX.fastEffectsSpec())
    }
    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = false),
    )
}

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
