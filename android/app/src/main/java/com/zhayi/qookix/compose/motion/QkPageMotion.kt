package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.shell.NavDestination

/**
 * 导航转场语义：一级标签切换（轻纵移+淡入）vs 向前下钻（右进+淡入）vs 返回（左进+淡入）。
 */
enum class NavTransitionKind {
    TopLevel,
    Forward,
    Back,
}

@Immutable
data class PageTransitionState(
    val alpha: Float,
    val translationX: Dp,
    val translationY: Dp,
)

fun classifyNavTransition(
    to: NavDestination,
    wentBack: Boolean,
): NavTransitionKind = when {
    wentBack -> NavTransitionKind.Back
    to in NavDestination.bottomBar -> NavTransitionKind.TopLevel
    else -> NavTransitionKind.Forward
}

/**
 * 保持 `PageHost` 原本的 `when (dest)` 结构不变（防止 `AnimatedContent` 打断各页的
 * `LaunchedEffect(bridgeReady)`），用方向语义区分 TopLevel / Forward / Back，且绝不缩放防抽搐。
 */
@Composable
fun rememberPageTransition(
    dest: NavDestination,
    kind: NavTransitionKind,
): PageTransitionState {
    if (!QkMotionPolicy.animationsEnabled) {
        return PageTransitionState(alpha = 1f, translationX = 0.dp, translationY = 0.dp)
    }

    var visible by remember(dest) { mutableStateOf(false) }
    LaunchedEffect(dest) {
        withFrameNanos { }
        visible = true
    }

    val startX = when (kind) {
        NavTransitionKind.Forward -> 18.dp
        NavTransitionKind.Back -> (-14).dp
        NavTransitionKind.TopLevel -> 0.dp
    }
    val startY = when (kind) {
        NavTransitionKind.TopLevel -> 12.dp
        NavTransitionKind.Forward, NavTransitionKind.Back -> 0.dp
    }

    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = QookiX.effectsSpec(),
        label = "pageAlpha",
    )
    val tx by animateDpAsState(
        targetValue = if (visible) 0.dp else startX,
        animationSpec = QookiX.defaultSpatialSpec(),
        label = "pageShiftX",
    )
    val ty by animateDpAsState(
        targetValue = if (visible) 0.dp else startY,
        animationSpec = QookiX.defaultSpatialSpec(),
        label = "pageShiftY",
    )
    return PageTransitionState(alpha = alpha, translationX = tx, translationY = ty)
}

fun Modifier.qkPageLayer(state: PageTransitionState): Modifier =
    graphicsLayer {
        alpha = state.alpha
        translationX = state.translationX.toPx()
        translationY = state.translationY.toPx()
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
