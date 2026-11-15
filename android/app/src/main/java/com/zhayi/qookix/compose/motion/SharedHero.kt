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

    /** 内容区左上角的窗口坐标（px），由浮层同步。估算落点要基于它，不能写死侧栏宽度。 */
    var contentOrigin: Offset = Offset.Zero

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

    /**
     * 落地（或中断）：清掉**全部**飞行状态。
     *
     * 以前只清 pending / targetReal，current / target / launchedAtNanos 会一直留着：
     * 飞行中按返回、上报落点的组件被销毁后，浮层协程被取消但状态还在，
     * 下次组合会拿着旧目标重新起飞（图标重飞 / 卡住）。
     */
    fun finish() {
        pending = null
        target = null
        targetReal = false
        current.value = null
        launchedAtNanos = 0L
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
        // ⚠️ 用**固定时长 + 缓出曲线**，不用指数趋近。
        //
        // 指数趋近（每帧乘 0.72）数学上永远到不了终点：最后几厘米要走十几帧，
        // 观感是「先冲一下，然后黏住」——用户的原话是「应该先快后慢」。
        // 现在按 220ms 归一化时间轴走 `1-(1-t)^3`（立方缓出）：起步最快、末段平滑收住，
        // 且到 t=1 一定精确落在目标上。
        val started = pending ?: return
        val start = started.from
        val t0 = withFrameNanos { it }
        // 兜底按毫秒：以前用 90/120 帧当 1.5s，隐含 60Hz；
        // 90Hz 设备上会提前 1/3 收尾，飞行看着像被截断。
        while (pending != null) {
            val now = withFrameNanos { it }
            val elapsedMs = (now - t0) / 1_000_000f
            if (elapsedMs > QkMotionPolicy.HERO_LANDING_TIMEOUT_MS && !targetReal) {
                target = target ?: current.value
                targetReal = true
            }
            if (elapsedMs > QkMotionPolicy.HERO_TOTAL_TIMEOUT_MS) {
                onArrive()
                return
            }
            val goal = target ?: continue
            val raw = (elapsedMs / QkMotionPolicy.HERO_FLIGHT_MS).coerceIn(0f, 1f)
            val e = 1f - (1f - raw) * (1f - raw) * (1f - raw)   // cubic ease-out
            current.value = Rect(
                start.left + (goal.left - start.left) * e,
                start.top + (goal.top - start.top) * e,
                start.right + (goal.right - start.right) * e,
                start.bottom + (goal.bottom - start.bottom) * e,
            )
            if (raw >= 1f && targetReal) {
                current.value = goal
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
fun estimatedLanding(density: Density): Rect = with(density) {
    // 侧栏与顶栏已经在 contentOrigin 里了：以前写死 168dp / 64dp，
    // 横屏默认收起（88dp）时会偏右 80dp，竖屏没有侧栏会偏右 168dp。
    val pagePad = 20.dp.toPx()
    val half = 48.dp.toPx() / 2
    val cx = SharedHero.contentOrigin.x + pagePad + half
    val cy = SharedHero.contentOrigin.y + pagePad + half
    Rect(cx - half, cy - half, cx + half, cy + half)
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
    val launch = SharedHero.pending
    // 估算落点要按内容区真实原点算（侧栏宽度会变、竖屏没有侧栏）
    androidx.compose.runtime.SideEffect { SharedHero.contentOrigin = origin }
    // 浮层销毁（旋转 / Activity 重建）时强制收尾：协程被取消后全局状态不能留着
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { SharedHero.finish() } }
    // ⚠️ 飞行循环必须由**浮层**驱动，不能由卡片启动：
    // 卡片在切页时被销毁，它的 `rememberCoroutineScope` 随之取消 → 循环死掉、
    // `current` 冻在原位，图标就永远卡在屏幕上（真机复现：「点实例图标不动了」）。
    // 浮层挂在 PageHost 上，跨页存活。
    androidx.compose.runtime.LaunchedEffect(launch) {
        if (launch != null) SharedHero.flyLoop { SharedHero.finish() }
    }
    if (launch == null) return
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
