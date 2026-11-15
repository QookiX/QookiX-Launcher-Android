package com.zhayi.qookix.compose.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally

/**
 * 页面内转场集合（详情页 tab 切换在用）。
 *
 * 方向语义化：往右的 tab 从右进、往左的从左进，同级切换纯淡入。
 * 时长与曲线统一走 [QookiX]。
 *
 * ⚠️ 整页切换**不走这里**：PageHost 用 `when (dest)` + `graphicsLayer` 手写方案。
 * 换成 `AnimatedContent` 会让各页的 `LaunchedEffect(bridgeReady)` 不再执行
 * （2026-10-13 踩过），所以这里不提供整页转场包装。
 */

/** 纯淡入淡出（同级页面切换）。 */
fun qkFadeIn(): EnterTransition =
    fadeIn(animationSpec = tween(QookiX.FADE_MS, easing = QookiX.EaseInOut))

fun qkFadeOut(): ExitTransition =
    fadeOut(animationSpec = tween(QookiX.FADE_MS, easing = QookiX.EaseInOut))

/** 从右滑入（进入下一级 / 往右的 tab）。 */
fun qkSlideInRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(QookiX.LONG_MS, easing = QookiX.EaseOut),
        initialOffsetX = { it / 3 },
    ) + fadeIn(tween(QookiX.LONG_MS))

/** 从左滑入（返回 / 往左的 tab）。 */
fun qkSlideInLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(QookiX.LONG_MS, easing = QookiX.EaseOut),
        initialOffsetX = { -it / 3 },
    ) + fadeIn(tween(QookiX.LONG_MS))
