package com.zhayi.qookix.compose.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
/**
 * 页面转场动画集合。
 *
 * ── 设计原则 ────────────────────────────────────────────────────────────
 * 1. **只用淡入 + 轻微位移**，不用花哨的缩放/旋转。
 *    启动器是工具，用户要的是"知道自己点到哪了"，
 *    过度动效反而拖慢感知。
 *
 * 2. **方向语义化**：
 *    - 进入二级页（设置/详情）→ 从右往左滑
 *    - 返回上一级           → 从左往右滑
 *    - 底栏同级切换         → 纯淡入（同级不该有方向暗示）
 *
 * 3. **时长统一走 [QookiX]**，不在这里另设。
 *
 * ── 为什么不用 navigation-compose 的转场 ─────────────────────────────────
 * 本项目没用 Navigation 组件（页面切换是 enum + when 手写的），
 * 所以转场要自己做。用 `AnimatedContent` 足够，
 * 不需要引 nav 库（还能少一个依赖）。
 */

/** 纯淡入淡出（同级页面切换）。 */
fun qkFadeIn(): EnterTransition =
    fadeIn(animationSpec = tween(QookiX.FADE_MS, easing = QookiX.EaseInOut))

fun qkFadeOut(): ExitTransition =
    fadeOut(animationSpec = tween(QookiX.FADE_MS, easing = QookiX.EaseInOut))

/** 从右滑入（进入二级页）。 */
fun qkSlideInRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(QookiX.LONG_MS, easing = QookiX.EaseOut),
        initialOffsetX = { it / 3 },
    ) + fadeIn(tween(QookiX.LONG_MS))

/** 滑出到右（从二级页返回）。 */
fun qkSlideOutRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(QookiX.LONG_MS, easing = QookiX.EaseIn),
        targetOffsetX = { it / 3 },
    ) + fadeOut(tween(QookiX.LONG_MS))

/** 从左滑入（返回手势）。 */
fun qkSlideInLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(QookiX.LONG_MS, easing = QookiX.EaseOut),
        initialOffsetX = { -it / 3 },
    ) + fadeIn(tween(QookiX.LONG_MS))

/** 从下滑入（弹层 / 底部面板）。 */
fun qkSlideInUp(): EnterTransition =
    slideInVertically(
        animationSpec = tween(QookiX.MEDIUM_MS, easing = QookiX.EaseOut),
        initialOffsetY = { it / 4 },
    ) + fadeIn(tween(QookiX.MEDIUM_MS))

/** 下滑出。 */
fun qkSlideOutDown(): ExitTransition =
    slideOutVertically(
        animationSpec = tween(QookiX.MEDIUM_MS, easing = QookiX.EaseIn),
        targetOffsetY = { it / 4 },
    ) + fadeOut(tween(QookiX.MEDIUM_MS))

/**
 * 列表项依次进场。
 *
 * @param index 项在列表中的位置（决定延迟多久出现）
 *
 * 为什么用 index 做延迟：整页同时淡入会显得"糊"，
 * 依次出现能让视线有落点。但延迟不能太大 ——
 * 超过 ~50ms × 项数 就会让人觉得慢。
 */
fun qkListItemEnter(index: Int): EnterTransition {
    val delay = (index * 40).coerceAtMost(300)
    return fadeIn(tween(QookiX.MEDIUM_MS, delayMillis = delay, easing = QookiX.EaseOut)) +
        slideInVertically(
            animationSpec = tween(QookiX.MEDIUM_MS, delayMillis = delay, easing = QookiX.EaseOut),
            initialOffsetY = { it / 6 },
        )
}

/** 列表项退场（快速，不拖泥带水）。 */
fun qkListItemExit(): ExitTransition =
    fadeOut(tween(QookiX.SHORT_MS))

/** 展开（内容变多，如展开折叠面板）。 */
fun qkExpandEnter(): EnterTransition =
    fadeIn(tween(QookiX.MEDIUM_MS)) +
        androidx.compose.animation.expandVertically(
            animationSpec = tween(QookiX.MEDIUM_MS, easing = QookiX.EaseOut),
        )

/** 收起。 */
fun qkCollapseExit(): ExitTransition =
    fadeOut(tween(QookiX.MEDIUM_MS)) +
        androidx.compose.animation.shrinkVertically(
            animationSpec = tween(QookiX.MEDIUM_MS, easing = QookiX.EaseIn),
        )

/**
 * 页面转场的通用包装。
 *
 * 用于 [com.zhayi.qookix.compose.PageHost] 的 `when(dest)` 分支 ——
 * 所有页面切换统一走这里，保证行为一致。
 */
@Composable
fun QkPageTransition(
    targetState: Any,
    modifier: Modifier = Modifier,
    // 默认纯淡入：同级切换不该有方向暗示
    enter: EnterTransition = qkFadeIn(),
    exit: ExitTransition = qkFadeOut(),
    label: String = "page",
    content: @Composable AnimatedVisibilityScope.(targetState: Any) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = { enter togetherWith exit },
        label = label,
    ) { s ->
        content(s)
    }
}
