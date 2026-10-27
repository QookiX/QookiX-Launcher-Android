package com.zhayi.qookix.compose.motion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.theme.QkRadius
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs
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
 * ## 为什么不用 `Animatable`
 *
 * 转场动画（16dp 上浮）会让 hero 的位置**每一帧都在变**，而 hero 每帧都上报
 * 新的落点。如果用 `Animatable.animateTo`，新目标一来就会取消旧动画、从当前值
 * 重启 —— 转场 300ms 期间动画被**反复打断**，位置几乎不动，直到目标稳定才
 * 真正开始飞。用户看到的就是「图标先卡一下，等那边加载完才飞」。
 *
 * 现在改成**每帧向目标指数趋近**：目标怎么变都只是换终点，永远平滑追。
 *
 * ## 坐标约定（踩过）
 *
 * 起点/落点用 `boundsInWindow()`；浮层绘制时减掉 `origin`
 * （内容区左上角在窗口里的位置 —— 浮层不在窗口 (0,0)，上边有顶栏、左边有侧栏）。
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

    /** 飞行块的当前位置（窗口坐标）。浮层读它绘制。 */
    val current = mutableStateOf<Rect?>(null)

    /** 当前目标（可能是估算值，也可能是真实落点；转场期间会变）。 */
    var target by mutableStateOf<Rect?>(null)
        private set

    /** 起飞信息。非空 = 飞行中。落地后置 null（overlay 随之消失）。 */
    var pending by mutableStateOf<Launch?>(null)
        private set

    /**
     * 当前 [target] 是不是**真实落点**。
     *
     * 起飞瞬间用的是估算位置（[estimatedLanding]），那时不能判定"到了" ——
     * 否则浮层会在估算点提前消失。只有目标页 hero 上报（[updateTarget]）之后才允许落地。
     */
    var targetReal by mutableStateOf(false)
        private set

    /** 起飞时刻（`System.nanoTime()`），只用于耗时诊断。 */
    var launchedAtNanos by mutableStateOf(0L)
        private set

    fun start(from: Rect, label: String, background: Color, foreground: Color, estimate: Rect) {
        pending = Launch(from, label, background, foreground)
        current.value = from
        target = estimate
        targetReal = false
        launchedAtNanos = System.nanoTime()
    }

    /** 目标页 hero 上报真实落点（此后才允许落地）。 */
    fun updateLanding(rect: Rect) {
        target = rect
        targetReal = true
    }

    /** 落地：清掉飞行任务，目标页 hero 由 `pending == null` 淡入。 */
    fun finish() {
        pending = null
        targetReal = false
    }

    /**
     * 飞行循环：每帧向目标指数趋近（k=0.22），接近且目标已确认为真实落点时落地。
     *
     * 必须在 `rememberCoroutineScope()` 里启动（那个作用域带 Compose 的
     * `MonotonicFrameClock`，`withFrameNanos` 才能工作；全局 scope 没有它，
     * 实测直接抛 IllegalStateException）。
     *
     * @param onArrive 落地回调（置 landed=true，让目标页 hero 现身）
     */
    suspend fun flyLoop(onArrive: () -> Unit) {
        while (pending != null) {
            withFrameNanos { }                       // 等下一帧
            val t = target ?: continue
            val c = current.value ?: continue
            val k = 0.22f                            // 每帧趋近 22%（指数趋近，永不超调）
            val next = Rect(
                c.left + (t.left - c.left) * k,
                c.top + (t.top - c.top) * k,
                c.right + (t.right - c.right) * k,
                c.bottom + (t.bottom - c.bottom) * k,
            )
            current.value = next
            // 接近且目标已是真实落点 → 落地（估算阶段不能落地，否则会提前消失）
            val cx = (next.left + next.right) / 2f
            val cy = (next.top + next.bottom) / 2f
            val tx = (t.left + t.right) / 2f
            val ty = (t.top + t.bottom) / 2f
            if (targetReal && abs(cx - tx) < 2f && abs(cy - ty) < 2f) {
                current.value = t
                onArrive()
            }
        }
    }
}

/**
 * 落点还没上报时的**估算位置**。
 *
 * 详情页 hero 固定在「内容区左上 + 页面内边距」处（48dp 图标）。
 * 误差几十 dp 无妨：真实落点到达后目标会变，指数趋近自动追。
 */
fun estimatedLanding(density: Density): Rect {
    val sideBar = 168.dp
    val pagePad = 20.dp
    val icon = 48.dp
    val topBar = 64.dp
    val cx = sideBar + pagePad + icon / 2
    val cy = topBar + 20.dp + icon / 2
    val half = icon / 2
    return with(density) {
        Rect((cx - half).toPx(), (cy - half).toPx(), (cx + half).toPx(), (cy + half).toPx())
    }
}

/**
 * 飞行浮层。挂在 `PageHost` 的**转场层之外**（否则会跟着页面淡入，
 * 看起来像「切完页才开始飞」）。
 *
 * @param origin 内容区左上角在**窗口**里的位置。起点/落点都是窗口坐标，
 *   绘制时要减掉它。由 PageHost 稳定提供。
 */
@Composable
fun SharedHeroOverlay(origin: Offset, modifier: Modifier = Modifier) {
    val launch = SharedHero.pending ?: return
    val rect = SharedHero.current.value ?: return
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (rect.left - origin.x).roundToInt(),
                    (rect.top - origin.y).roundToInt(),
                )
            }
            .size(
                width = with(density) { rect.width.coerceAtLeast(1f).toDp() },
                height = with(density) { rect.height.coerceAtLeast(1f).toDp() },
            )
            .clip(RoundedCornerShape(QkRadius.medium))
            .background(launch.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = launch.label,
            color = launch.foreground,
            fontSize = with(density) { (rect.height * 0.42f).toSp() },
            fontWeight = FontWeight.Bold,
        )
    }
}
