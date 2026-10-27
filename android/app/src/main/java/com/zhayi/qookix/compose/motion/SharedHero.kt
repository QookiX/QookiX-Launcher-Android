package com.zhayi.qookix.compose.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.theme.QkRadius
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 共享元素「飞行层」。
 *
 * ## 为什么不用 `SharedTransitionLayout`
 *
 * 官方的共享元素要求源和目标**同时存在于组合树里**，通常配 `AnimatedContent`。
 * 而本项目 `PageHost` 用 `when (dest)` 切页 —— 换成 `AnimatedContent` 后各页的
 * `LaunchedEffect(bridgeReady)` 不再执行（2026-10-13 踩过）。
 *
 * ## 这里的做法
 *
 * 在页面之上画一个临时浮层，从卡片图标位置飞到详情页 hero：
 *
 * ```
 * 点卡片 → 记录图标窗口位置 → 切页
 *        → 浮层**立即**起飞（目标先用估算位置，不等落点）
 *        → hero 布局完成上报真实落点 → 改目标继续飞（从当前值接续，不跳）
 *        → 落地清除
 * ```
 *
 * ## 两个必须注意的坐标问题（都踩过）
 *
 * 1. 起点/落点用 `boundsInWindow()`（窗口坐标），不能用 `boundsInParent()`
 *    —— 后者相对直接父容器，跨层就差一个 padding。
 * 2. **浮层自己的原点也不是窗口原点**：它挂在内容区（侧栏右侧、顶栏下方），
 *    所以绘制时要减掉 `localToWindow(0,0)`，否则整体偏移（用户反馈「飞的位置不对」）。
 */
object SharedHero {

    /** 一次飞行的起飞信息。 */
    data class Launch(
        /** 起点（窗口坐标，含尺寸）。 */
        val from: Rect,
        /** 飞行块上显示的字符（实例首字母）。 */
        val label: String,
        val background: Color,
        val foreground: Color,
    )

    /** 待起飞：卡片点击时写入。 */
    var pending by mutableStateOf<Launch?>(null)
        private set

    /** 落点：目标页 hero 布局完成后写入。 */
    var landing by mutableStateOf<Rect?>(null)

    fun launch(from: Rect, label: String, background: Color, foreground: Color) {
        landing = null
        pending = Launch(from, label, background, foreground)
    }

    fun clear() {
        pending = null
        landing = null
    }
}

/**
 * 落点还没上报时的**估算位置**。
 *
 * 详情页 hero 固定在「内容区左上 + 页面内边距」处（48dp 图标），
 * 这里按横屏布局估算一个矩形。误差几十 dp 无妨：飞行本身只有几百毫秒，
 * 真实落点到达后会自动改目标、从当前值接续。
 */
private fun estimatedLanding(density: Density): Rect {
    val sideBar = 168.dp      // 侧栏展开宽（收起是 88dp，误差可接受）
    val pagePad = 20.dp       // 内容区 padding
    val icon = 48.dp
    val topBar = 64.dp
    val cx = sideBar + pagePad + icon / 2
    val cy = topBar + 20.dp + icon / 2
    val half = icon / 2
    return with(density) {
        Rect(
            left = (cx - half).toPx(),
            top = (cy - half).toPx(),
            right = (cx + half).toPx(),
            bottom = (cy + half).toPx(),
        )
    }
}

/**
 * 飞行浮层。挂在 `PageHost` 的**转场层之外**（否则会跟着页面淡入，
 * 看起来像「切完页才开始飞」）。
 *
 * @param origin 内容区左上角在**窗口**里的位置。起点/落点都是窗口坐标，
 *   绘制时要减掉它。由 PageHost 稳定提供 —— 浮层自己测的话要多等一帧
 *   （布局完成后才有值），用户会看到「图标先顿一下再飞」。
 */
@Composable
fun SharedHeroOverlay(origin: Offset, modifier: Modifier = Modifier) {
    val launch = SharedHero.pending ?: return
    val landing = SharedHero.landing
    val density = LocalDensity.current

    // 四个维度各自 Animatable：好处是「目标中途改变」时能从当前值接续，
    // 而不是像 progress + lerp 那样一换目标就整体跳一下。
    val left = remember(launch) { Animatable(launch.from.left) }
    val top = remember(launch) { Animatable(launch.from.top) }
    val w = remember(launch) { Animatable(launch.from.width) }
    val h = remember(launch) { Animatable(launch.from.height) }

    LaunchedEffect(launch, landing) {
        // ⚠️ 不等落点：先用估算位置起飞。
        // 之前是 `val landing = SharedHero.landing ?: return` —— 要等详情页
        // 布局完成才动，用户感知到的是「点了半天才飞」（反馈里的「延迟」）。
        val target = landing ?: estimatedLanding(density)
        coroutineScope {
            // spatialSpec（spring）而不是 slowSpatialSpec：后者为大范围慢速设计，
            // 400-600ms 的飞行在手机尺寸上显得拖沓。
            launch { left.animateTo(target.left, QookiX.spatialSpec()) }
            launch { top.animateTo(target.top, QookiX.spatialSpec()) }
            launch { w.animateTo(target.width, QookiX.spatialSpec()) }
            launch { h.animateTo(target.height, QookiX.spatialSpec()) }
        }
        // 只有落到**真实**落点才算完成；用的是估算位置就继续等（effect 会因
        // landing 变化重启，从当前值接着飞）。
        if (landing != null) SharedHero.clear()
    }

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (left.value - origin.x).roundToInt(),
                    (top.value - origin.y).roundToInt(),
                )
            }
            .size(
                width = with(density) { w.value.coerceAtLeast(1f).toDp() },
                height = with(density) { h.value.coerceAtLeast(1f).toDp() },
            )
            .clip(RoundedCornerShape(QkRadius.medium))
            .background(launch.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = launch.label,
            color = launch.foreground,
            fontSize = with(density) { (h.value * 0.42f).toSp() },
            fontWeight = FontWeight.Bold,
        )
    }
}
