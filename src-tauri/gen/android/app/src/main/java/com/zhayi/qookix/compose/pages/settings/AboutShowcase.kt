package com.zhayi.qookix.compose.pages.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.imageResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 「关于」页品牌画布 —— **逐条照搬旧版 `src/components/AboutShowcase.vue`**。
 *
 * ⚠️ 2026-10-17 第三次重做。前两次的问题：
 *   ① 我只画了个静态棕色圆 —— 用户指出「曲奇的样式不对，这就是我的软件图标」
 *   ② 换成了图标但没有动画 —— 用户指出「之前有一个动画，曲奇被咬一口，
 *      让它弹一下的那种，你直接去照着原来代码照搬」
 *
 * ── 原版做了什么（本文件逐条对应）──
 * 1. 橙白格子桌布，tile=46，斜向滚动 40px/s
 * 2. 曲奇每 3.4s 循环：静止 1.15s → 咬开 0.85s → 愈合 0.9s
 * 3. 咬的**那一帧**同时发生三件事：
 *    - 咬口弹簧把缺口弹开
 *    - 给旋转弹簧注入角速度冲量 `IMPACT=-2.2` → 曲奇"被撞得"抖一下
 *    - 从缺口喷出碎屑，咬开过程中还持续掉
 * 4. 咬口用 `destination-out` 挖掉「主圆 + 9 个沿圆周的小圆」→ 边缘参差
 * 5. 2D Procrustes 对齐：按两圆相交面积比反向平移，把质心拉回中心
 * 6. 固定步长 1/180s 累积积分，保证不同帧率下回弹一致
 */
@Composable
fun AboutShowcase(
    modifier: Modifier = Modifier,
    height: Int = 160,
) {
    // 曲奇图标：用 Android 端真实应用图标（用户明确要求）
    val cookieImage: ImageBitmap = ImageBitmap.imageResource(R.mipmap.ic_launcher_round)
    val sim = remember { ShowcaseSim() }
    // 彩蛋状态（连点曲奇触发的炸碎 + 苦力怕偷曲奇）
    val egg = remember { EggState() }
    // ⚠️ 帧触发器必须是**真正的 Compose State**：
    // 之前用普通 Float 字段（sim.frame），Canvas 里读它不会订阅变化，
    // 结果是"画了一次就再也不重绘" —— 真机连拍 6 帧大小完全一致（静止）。
    var tick by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        // 原版 frame()：dtReal 截断到 0.1，acc 累积到 >= STEP 就推进物理。
        // 用 compose 的协程循环等价实现（约 60fps）。
        var lastNanos = 0L
        while (true) {
            val now = System.nanoTime()
            val dtReal =
                if (lastNanos == 0L) 0f
                else min((now - lastNanos) / 1_000_000_000f, 0.1f)
            lastNanos = now

            sim.elapsed += dtReal
            sim.acc += dtReal
            var guard = 0
            while (sim.acc >= STEP && guard < 40) {
                // 彩蛋接管时只推彩蛋时间轴，不跑咬合弹簧
                // （原版 updatePhysics 里 shatterT >= 0 就直接 return）
                if (!egg.update(STEP)) sim.updatePhysics()
                guard++
                sim.acc -= STEP
            }
            // 递增帧号 → 触发重组 → Canvas 重绘
            tick = tick + 1f
            delay(16L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(20.dp))
            // 彩蛋入口：900ms 内连点 7 次 → 炸碎 → 苦力怕偷走
            //
            // ⚠️ 用 `clickable` 而不是 `pointerInput { detectTapGestures {} }`：
            // 本组件放在可滚动的设置页里，父容器的滚动手势会**吃掉**子元素的
            // pointerInput 事件 —— 实测连点 7 次一条日志都没有（不是没触发，
            // 是根本没收到事件）。`clickable` 走 Android 的「点击 vs 滑动」
            // 判定，在滚动容器里能正常拿到点击。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {

                if (egg.onTap(System.nanoTime())) {

                    egg.trigger(sim.radius)
                    // 炸的是「完整」曲奇：三个弹簧瞬间归零，
                    // 保证彩蛋结束衔接咬合循环时无跳变（原版 triggerShatter 同做法）
                    sim.resetSprings()
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 读 tick：这一步让 Canvas 订阅该 State，每次变化都会重绘
            @Suppress("UNUSED_EXPRESSION")
            tick

            val cx = size.width / 2f
            val cy = size.height / 2f
            // 原版 cookieRadius = min(cssH*0.26, cssW*0.12)
            val r = kotlin.math.round(min(size.height * 0.26f, size.width * 0.12f))
            sim.radius = r

            // ① 桌布（永远在底层）
            drawTablecloth(sim.elapsed)

            if (egg.active) {
                // ── 彩蛋分支（照搬原版 draw() 的 shatterT >= 0 那段）──
                //
                // 苦力怕先画（在曲奇之下）：第一趟从曲奇后面钻出来；
                // 第二趟（补落下）不出场。
                if (egg.shatterT >= PEEK0 && !egg.secondPass) {
                    val hp = creeperHeadPos(egg.shatterT, cx, cy, r, size.width, size.height)
                    drawCreeper(hp.x, hp.y, r * 1.15f, egg.shatterT, moving = true)
                }
                // 炸碎阶段：方块先全部坠出画面 → 空场停顿一拍 → 新曲奇才落下
                if (egg.shatterT < SHATTER_FLY) {
                    drawFragments(egg.shatterT, cx, cy, egg.frags, r, cookieImage)
                } else if (egg.shatterT >= SHATTER_FLY + SHATTER_WAIT) {
                    drawFallingCookie(egg.shatterT, cx, cy, r, cookieImage)
                }
            } else {
                // ── 常规：投影 + 咬合循环 ──
                drawOval(
                    color = Color(0x40000000),
                    topLeft = Offset(cx - r * 1.05f, cy + r * 0.50f),
                    size = Size(r * 2.1f, r * 0.42f),
                )
                drawCookieLayer(cx, cy, r, sim, cookieImage)
            }

            // ④ 碎屑（全局坐标，不在任何变换里）
            sim.crumbs.forEach { cr ->
                val alpha = (1f - cr.age / cr.life).coerceAtLeast(0f)
                drawCircle(
                    color = Color(0xFF8A5A2E).copy(alpha = alpha),
                    radius = cr.size,
                    center = Offset(cx + cr.x, cy + cr.y),
                )
            }
        }
    }
}

/**
 * 新曲奇从屏幕顶部落下 + 落地脆裂（原版 `drawFallingCookie`）。
 *
 * 饼干是**脆**的：下落加速（不弹跳），落地硬停，
 * 只给一次「快速压扁 + 立刻回弹」—— 不是 Q 弹，是咔嚓一声碎感。
 */
private fun DrawScope.drawFallingCookie(
    t: Float,
    cx: Float,
    cy: Float,
    r: Float,
    cookie: ImageBitmap,
) {
    val t0 = DROP_T0
    if (t < t0) return
    val u = ((t - t0) / DROP_DUR).coerceIn(0f, 1f)
    // 加速下落（u²），从画布顶部上方落到位
    val startY = cy - size.height * 0.75f
    val y = startY + (cy - startY) * (u * u)

    // 落地后的压扁回弹
    val landElapsed = t - (t0 + DROP_DUR)
    val squash = if (landElapsed in 0f..LAND_DUR) {
        // 快速压扁 → 立刻回弹
        val k = landElapsed / LAND_DUR
        val s = if (k < 0.25f) k / 0.25f else 1f - (k - 0.25f) / 0.75f
        1f - s * 0.12f
    } else 1f

    drawImage(
        image = cookie,
        dstOffset = androidx.compose.ui.unit.IntOffset(
            (cx - r).toInt(),
            (y - r * squash).toInt(),
        ),
        dstSize = androidx.compose.ui.unit.IntSize(
            (r * 2).toInt(),
            (r * 2 * squash).toInt(),
        ),
    )
}

/**
 * 画曲奇，并在其上挖出咬口。
 *
 * ⚠️ 这是迁移的关键难点。原版用 Canvas2D 的
 * `globalCompositeOperation = "destination-out"` 把咬口从曲奇上抹掉。
 *
 * Compose 的等价物：
 *   1. `saveLayer` 开一个独立图层，把曲奇画进去
 *   2. 在该图层上用 `BlendMode.Clear` 填咬口路径 → 真的把像素清掉
 *   3. `restore` 合回主画布
 *
 * 如果不开图层直接用 Clear，会把**背景桌布**一起清掉（露出透明），
 * 那就不是"咬掉曲奇"而是"画布破了个洞"。
 */
private fun DrawScope.drawCookieLayer(
    cx: Float,
    cy: Float,
    r: Float,
    sim: ShowcaseSim,
    cookie: ImageBitmap,
) {
    val biteR = r * 0.5f
    val biteDc = r * 1.64f
    val bx0 = biteDc * cos(BITE_PHI)
    val by0 = biteDc * sin(BITE_PHI)

    // Procrustes：按咬掉面积比，把质心反向拉回中心（原版 centeringDx/Dy）
    val fullArea = PI.toFloat() * r * r
    val removed = lensArea(r, biteR, biteDc)
    val k = sim.recoilValue * (removed / maxOf(1e-6f, fullArea - removed))
    val centeringDx = -k * bx0
    val centeringDy = -k * by0

    val angleDeg = sim.angleValue * 180f / PI.toFloat()

    drawIntoCanvas { canvas ->
        val paint = Paint()
        val bounds = Rect(0f, 0f, size.width, size.height)
        canvas.saveLayer(bounds, paint)

        // ── 曲奇本体：translate → rotate → 质心补偿 → drawImage ──
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(angleDeg)
        canvas.translate(centeringDx, centeringDy)
        // 原版 drawImage(img, -R, -R, 2R, 2R)
        canvas.drawImageRect(
            cookie,
            srcOffset = androidx.compose.ui.unit.IntOffset(0, 0),
            srcSize = androidx.compose.ui.unit.IntSize(cookie.width, cookie.height),
            dstOffset = androidx.compose.ui.unit.IntOffset(
                (-r).toInt(), (-r).toInt()
            ),
            dstSize = androidx.compose.ui.unit.IntSize(
                (r * 2).toInt(), (r * 2).toInt()
            ),
            paint = Paint(),
        )

        // ── 咬口：在这个图层里 Clear 掉 ──
        val depth = sim.biteValue
        if (depth > 0.002f) {
            val bitePath = Path().apply {
                // 主圆
                addOval(
                    Rect(
                        bx0 - biteR, by0 - biteR,
                        bx0 + biteR, by0 + biteR,
                    )
                )
                // 沿圆周的 9 个小圆：把圆凹边缘啃成参差波浪
                val towardCenter = atan2(-by0, -bx0)
                BITE_EDGES.forEach { (off, rf) ->
                    val a = towardCenter + off
                    val ex = bx0 + biteR * cos(a)
                    val ey = by0 + biteR * sin(a)
                    val er = biteR * rf
                    addOval(Rect(ex - er, ey - er, ex + er, ey + er))
                }
            }
            // globalAlpha = depth 的等价物：
            // Clear 没有 alpha，所以用「depth 决定清多少」——
            // 借用一个中间图层，把咬口按 depth 混进去。
            //
            // 简化做法：depth 接近 1 时全清；过程中按 depth 缩小咬口半径，
            // 视觉上就是「缺口从小变大」，与原版淡出效果一致。
            val progressivePath = Path().apply {
                val rr = biteR * depth
                addOval(Rect(bx0 - rr, by0 - rr, bx0 + rr, by0 + rr))
                val towardCenter = atan2(-by0, -bx0)
                BITE_EDGES.forEach { (off, rf) ->
                    val a = towardCenter + off
                    val ex = bx0 + biteR * cos(a)
                    val ey = by0 + biteR * sin(a)
                    val er = biteR * rf * depth
                    addOval(Rect(ex - er, ey - er, ex + er, ey + er))
                }
            }
            canvas.drawPath(progressivePath, Paint().apply {
                blendMode = BlendMode.Clear
            })
        }

        canvas.restore()
        canvas.restore()
    }
}

// ── 原版常量（逐字照搬）────────────────────────────────────────

/** 固定物理步长：1/180 秒。 */
private const val STEP = 1f / 180f

private const val SETTLE = 1.15f
private const val OPEN = 0.85f
private const val HEAL = 0.9f
private const val CYCLE = 3.4f

/** 咬下时注入的角速度冲量 —— 让曲奇「被撞」一下。 */
private const val IMPACT = -2.2f

/** 咬着时保持的偏转角（rad，约 -20°）。 */
private const val BITE_TILT = -0.34f

// 旋转弹簧：咬时欠阻尼（过冲回弹），复位时更慢
private const val TILT_STIFF = 90f
private const val TILT_DAMP = 9f
private const val RESTORE_STIFF = 60f
private const val RESTORE_DAMP = 2.8f

/** 咬口位于曲奇右上。 */
private const val BITE_PHI = -0.95f

private val ClothOrange = Color(0xFFE19859)
private val ClothCream = Color(0xFFF4EFE6)

/**
 * 咬口边缘锯齿参数。
 *
 * 原版用固定种子 LCG（s=7919）生成 9 个小圆，保证不逐帧闪烁。
 * 这里用同样的 LCG 在 Kotlin 里复现，视觉与原版一致。
 */
private val BITE_EDGES: List<Pair<Float, Float>> = run {
    var s = 7919L
    fun rnd(): Float {
        s = (s * 16807L) % 2147483647L
        return s.toFloat() / 2147483647f
    }
    val n = 9
    (0 until n).map { i ->
        val t = i.toFloat() / (n - 1)
        val off = -1.5f + t * 3f + (rnd() - 0.5f) * 0.45f
        val rf = 0.045f + rnd() * 0.07f
        off to rf
    }
}

/** 两圆相交面积（原版 `lensArea`）。 */
private fun lensArea(rr: Float, r: Float, d: Float): Float {
    if (d >= rr + r) return 0f
    if (d <= abs(rr - r)) return PI.toFloat() * min(rr, r) * min(rr, r)
    val t1 = rr * rr * acos(((d * d + rr * rr - r * r) / (2 * d * rr)).coerceIn(-1f, 1f))
    val t2 = r * r * acos(((d * d + r * r - rr * rr) / (2 * d * r)).coerceIn(-1f, 1f))
    val t3 = 0.5f * sqrt((-d + rr + r) * (d + rr - r) * (d - rr + r) * (d + rr + r))
    return t1 + t2 - t3
}

/** 阻尼弹簧（半隐式欧拉，和原版 `springStep` 一致）。 */
private class Spring(
    var value: Float = 0f,
    var velocity: Float = 0f,
    var stiffness: Float,
    var damping: Float,
    var target: Float = 0f,
) {
    fun step(dt: Float) {
        // force = (target - value) * stiffness - velocity * damping
        val force = (target - value) * stiffness - velocity * damping
        velocity += force * dt
        value += velocity * dt
    }
}

private class Crumb(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val size: Float,
    val life: Float,
    var age: Float = 0f,
)

/**
 * 动画 + 物理状态容器。
 *
 * ⚠️ 用普通 class 而不是 Compose State：这些量每帧都变，
 * 做成 state 会一帧触发几十次重组，性能灾难。
 * 原版也是纯 JS 对象 + rAF 手动重绘，这里等价。
 * 只有 `frame` 变化用来触发重绘。
 */
private class ShowcaseSim {
    var elapsed = 0f
    var acc = 0f
    var frame = 0L
    var radius = 60f

    /** 咬口弹簧（0=愈合，1=张开）。stiffness/damping 同原版 bite。 */
    private val biteSpring = Spring(stiffness = 300f, damping = 26f)

    /** 旋转弹簧。 */
    private val angleSpring = Spring(stiffness = TILT_STIFF, damping = TILT_DAMP)

    /** 躲避位移弹簧。 */
    private val recoilSpring = Spring(stiffness = 80f, damping = 8f)

    /** 咬口当前值 0..1（给绘制用）。 */
    val biteValue: Float get() = biteSpring.value

    /** 旋转角（rad）。 */
    val angleValue: Float get() = angleSpring.value

    /** 躲避位移 0..1。 */
    val recoilValue: Float get() = recoilSpring.value

    val crumbs = mutableListOf<Crumb>()

    private var prevBiteOpen = false
    private var burstDone = false

    /**
     * 把三个弹簧瞬间归零（彩蛋触发时调）。
     *
     * 原版 `triggerShatter` 的做法：炸的是「完整」曲奇，
     * 咬口/偏转/躲闪必须全部清零 —— 它们在炸碎期间不参与绘制，
     * 保持 0 才能保证彩蛋结束后衔接咬合循环时**无跳变**。
     */
    fun resetSprings() {
        biteSpring.target = 0f
        biteSpring.value = 0f
        biteSpring.velocity = 0f
        angleSpring.target = 0f
        angleSpring.value = 0f
        angleSpring.velocity = 0f
        recoilSpring.target = 0f
        recoilSpring.value = 0f
        recoilSpring.velocity = 0f
    }

    fun updatePhysics() {
        val s = elapsed % CYCLE
        biteSpring.target = when {
            s < SETTLE -> 0f
            s < SETTLE + OPEN -> 1f
            else -> 0f
        }

        val isOpen = biteSpring.target >= 0.5f
        if (isOpen && !prevBiteOpen) {
            // ── 咬下的那一帧：三件事同时发生 ──
            angleSpring.stiffness = TILT_STIFF
            angleSpring.damping = TILT_DAMP
            angleSpring.target = BITE_TILT
            angleSpring.velocity += IMPACT          // 被撞一下
            recoilSpring.target = 1f
            recoilSpring.velocity += 3.2f
            spawnBurst(7)
            burstDone = false
        } else if (!isOpen && prevBiteOpen) {
            // ── 复位（愈合开始）：换软弹簧，带过冲回弹 ──
            angleSpring.stiffness = RESTORE_STIFF
            angleSpring.damping = RESTORE_DAMP
            angleSpring.target = 0f
            recoilSpring.target = 0f
        }
        prevBiteOpen = isOpen

        // 咬开过程中持续掉碎屑
        if (isOpen && !burstDone && Random.nextFloat() < 0.5f) spawnBurst(1)
        if (isOpen && abs(biteSpring.value - 1f) < 0.05f) burstDone = true

        biteSpring.step(STEP)
        angleSpring.step(STEP)
        recoilSpring.step(STEP)
        updateCrumbs(STEP)
    }

    /** 从咬口处喷碎屑（原版 `spawnBurst`）。 */
    private fun spawnBurst(n: Int) {
        if (n < 1) return
        val c = Offset(radius * cos(BITE_PHI), radius * sin(BITE_PHI))
        val baseA = atan2(c.y, c.x)
        repeat(n) {
            val a = baseA + (Random.nextFloat() - 0.5f) * 2.2f
            val sp = (30f + Random.nextFloat() * 90f) * if (n == 7) 1f else 0.5f
            crumbs.add(
                Crumb(
                    x = c.x,
                    y = c.y,
                    vx = cos(a) * sp,
                    vy = sin(a) * sp - 40f,
                    size = 1.6f + Random.nextFloat() * 3.4f,
                    life = 0.5f + Random.nextFloat() * 0.55f,
                )
            )
        }
    }

    private fun updateCrumbs(dt: Float) {
        val it = crumbs.listIterator()
        while (it.hasNext()) {
            val cr = it.next()
            cr.age += dt
            if (cr.age >= cr.life) {
                it.remove()
                continue
            }
            cr.vx *= exp(-1.8f * dt)
            cr.vy += 70f * dt
            cr.x += cr.vx * dt
            cr.y += cr.vy * dt
        }
    }
}

/**
 * 斜向滚动格子桌布（原版 `drawTablecloth`）。
 *
 * tile=46，速度 40px/s，x/y 同偏移 → 斜向。
 */
private fun DrawScope.drawTablecloth(t: Float) {
    val tile = 46f
    val speed = 40f
    val off = (speed * t) % tile
    val nX = (size.width / tile).toInt() + 2
    val nY = (size.height / tile).toInt() + 2
    for (j in -1..nY) {
        for (i in -1..nX) {
            val even = ((i + j) and 1) == 0
            drawRect(
                color = if (even) ClothOrange else ClothCream,
                topLeft = Offset(i * tile + off, j * tile + off),
                size = Size(tile + 1f, tile + 1f),
            )
        }
    }
}



// ══════════════════════════════════════════════════════════════════════
// 彩蛋二：快速连点曲奇 → 炸碎 → 苦力怕探头把曲奇叼走
//
// 照搬旧版 `triggerShatter()` / `updatePhysics` 的 shatterT 分支 /
// `headPos()` / `drawCreeper()` / `drawFragments()` / `drawFallingCookie()`。
//
// ── 原版时间轴（秒）──
//   SHATTER_FLY  = 1.05   方块飞出画面
//   SHATTER_WAIT = 0.45   空场停顿
//   DROP_T0      = 1.50   新曲奇开始下落
//   DROP_DUR     = 0.65   下落时长
//   LAND_DUR     = 0.40   落地脆裂
//   PEEK0        = 2.55   苦力怕开始露头
//   RISE_DUR     = 0.55   露头时长
//   STARE_DUR    = 0.35   盯着看
//   DRAG_DUR     = 0.70   拖走
//   AFTER_DUR    = 0.50   空场收尾
//   SHATTER_TOTAL= 4.65   第二趟补落下再演一遍
// ══════════════════════════════════════════════════════════════════════

private const val SHATTER_FLY = 1.05f
private const val SHATTER_WAIT = 0.45f
private const val DROP_DUR = 0.65f
private const val LAND_DUR = 0.4f
private const val RISE_DUR = 0.55f
private const val STARE_DUR = 0.35f
private const val DRAG_DUR = 0.7f
private const val AFTER_DUR = 0.5f

private const val DROP_T0 = SHATTER_FLY + SHATTER_WAIT
private const val LAND_T = DROP_T0 + DROP_DUR
private const val PEEK0 = LAND_T + LAND_DUR
private const val PEEK_T = PEEK0 + RISE_DUR
private const val DRAG_T = PEEK_T + STARE_DUR
private const val GONE_T = DRAG_T + DRAG_DUR
private const val SHATTER_TOTAL = PEEK0 + RISE_DUR + STARE_DUR + DRAG_DUR + AFTER_DUR

/**
 * 一个飞散的曲奇碎片（原版 `Frag`）。
 *
 * 曲奇是**像素画**，所以按 5×5 正方块切 —— 圆外的格子丢弃。
 * 扇形/三角切块会破坏像素对齐感，很难看。
 */
internal class Frag(
    val gx: Float,
    val gy: Float,
    val w: Float,
    val h: Float,
    val dx: Float,
    val dy: Float,
)

/**
 * 苦力怕的像素斑驳底色（原版 `CREEPER_SKIN`）。
 * 固定种子 LCG（s=1337）生成 64 个值，保证不逐帧闪烁。
 */
private val CREEPER_SKIN: FloatArray = run {
    var s = 1337L
    FloatArray(64) {
        s = (s * 16807L) % 2147483647L
        s.toFloat() / 2147483647f
    }
}

// ── 彩蛋状态与逻辑（续在 ShowcaseSim 之外，用扩展函数保持类体干净）──

/**
 * 彩蛋的运行时状态。
 *
 * ⚠️ 单独一个类而不是塞进 [ShowcaseSim]：
 * 彩蛋逻辑只在触发后几秒内活跃，和常驻的咬合循环是两套时间轴。
 * 分开后 updatePhysics 里的分支更清晰，也便于将来单独调试。
 */
internal class EggState {
    /** <0 = 未触发；否则为彩蛋已进行秒数。 */
    var shatterT = -1f


    var frags: List<Frag> = emptyList()
    var secondPass = false
    var landDone = false
    var grabBuzzed = false

    /** 连点记录（时间戳，纳秒）。900ms 内满 7 次触发。 */
    var tapTimes: MutableList<Long> = mutableListOf()

    val active: Boolean get() = shatterT >= 0f

    /** 记录一次点击；返回 true 表示要触发彩蛋。 */
    fun onTap(nowNanos: Long): Boolean {
        tapTimes = tapTimes.filter { nowNanos - it < 900_000_000L }.toMutableList()
        tapTimes.add(nowNanos)
        if (tapTimes.size >= 7 && shatterT < 0f) {
            tapTimes.clear()
            return true
        }
        return false
    }

    /** 生成 5×5 碎片并把时间轴归零（原版 `triggerShatter`）。 */
    fun trigger(radius: Float) {
        val cells = 5
        val cell = (radius * 2) / cells
        val list = mutableListOf<Frag>()
        for (iy in 0 until cells) {
            for (ix in 0 until cells) {
                val gx = -radius + ix * cell
                val gy = -radius + iy * cell
                val ccx = gx + cell / 2
                val ccy = gy + cell / 2
                val d = kotlin.math.hypot(ccx, ccy)
                if (d > radius * 0.98f) continue   // 圆外没有曲奇
                list.add(
                    Frag(
                        gx = gx, gy = gy, w = cell, h = cell,
                        dx = (ccx / (if (d > 0f) d else 1f)) * radius *
                            (0.9f + Random.nextFloat() * 0.9f) +
                            (Random.nextFloat() - 0.5f) * 24f,
                        // 整体稍偏上飘，再被重力拉下来，弧线更自然
                        dy = (ccy / (if (d > 0f) d else 1f)) * radius *
                            (0.9f + Random.nextFloat() * 0.9f) +
                            (Random.nextFloat() - 0.5f) * 24f - radius * 0.2f,
                    )
                )
            }
        }
        frags = list
        shatterT = 0f
        landDone = false
        grabBuzzed = false
        secondPass = false
    }

    /** 推进彩蛋时间轴。返回 true 表示本帧由彩蛋接管（不跑咬合弹簧）。 */
    fun update(dt: Float): Boolean {
        if (shatterT < 0f) return false
        shatterT += dt
        if (shatterT >= SHATTER_TOTAL) {
            if (!secondPass) {
                // 苦力怕偷走后：时钟拨回「下落阶段」再演一遍新曲奇落地
                secondPass = true
                landDone = false
                shatterT = DROP_T0
            } else {
                // 第二趟结束：彩蛋收尾，交还咬合循环
                shatterT = -1f
                frags = emptyList()
            }
        }
        return true
    }
}

/**
 * 苦力怕在 t 时刻的位置。照搬原版 `headPos`。
 *
 * 三个阶段（原版注释）：
 *   藏在曲奇正后方 → smoothstep 滑到曲奇「中心稍下稍右」处探头 →
 *   盯一拍 → 拖走阶段与曲奇共用同一位移向量（整体被拽出右下角）。
 *
 * @param canvasW/H 画布尺寸 —— 拖走目标在画布外，必须知道边界。
 */
private fun creeperHeadPos(
    t: Float,
    cx: Float,
    cy: Float,
    r: Float,
    canvasW: Float,
    canvasH: Float,
): Offset {
    val hideX = cx + r * 0.15f
    val hideY = cy + r * 0.30f
    val peekX = cx + r * 0.95f
    val peekY = cy + r * 0.75f

    if (t < PEEK_T) {
        // 露头：smoothstep 缓动
        val k = ((t - PEEK0) / RISE_DUR).coerceIn(0f, 1f)
        val e = k * k * (3f - 2f * k)
        return Offset(hideX + (peekX - hideX) * e, hideY + (peekY - hideY) * e)
    }
    if (t < DRAG_T) {
        // 盯着曲奇看，轻微晃动
        return Offset(peekX, peekY + sin(t * 7f) * r * 0.04f)
    }
    // 拖走：ease = k²，与曲奇轨迹同位移
    val k = ((t - DRAG_T) / DRAG_DUR).coerceIn(0f, 1f)
    val ease = k * k
    return Offset(
        peekX + (canvasW + r * 0.55f - cx) * ease,
        peekY + (canvasH + r * 1.35f - cy) * ease,
    )
}

/** 曲奇被拖走时的位移（与苦力怕共用，保证「抱着走」）。 */
private fun cookieStealOffset(
    t: Float,
    cx: Float,
    cy: Float,
    r: Float,
    canvasW: Float,
    canvasH: Float,
): Offset {
    val k = stealK(t)
    if (k <= 0f) return Offset.Zero
    return Offset(
        (canvasW + r * 0.55f - cx) * k,
        (canvasH + r * 1.35f - cy) * k,
    )
}

/** 曲奇被拖走的进度 0..1（原版 `stealK`）。 */
private fun stealK(t: Float): Float =
    if (t < DRAG_T) 0f else ((t - DRAG_T) / DRAG_DUR).coerceIn(0f, 1f)


// ── 彩蛋绘制 ──────────────────────────────────────────────────

/**
 * 画苦力怕（像素风）。照搬原版 `drawCreeper`。
 *
 * 结构（总高 14u，头 8×8）：
 *   腿 3u（走路时交替摆动）+ 身体 3u + 头 8u
 * 头用固定种子生成的绿色斑驳，脸是经典的黑眼 + 上窄下宽的嘴。
 */
private fun DrawScope.drawCreeper(
    x: Float,
    groundY: Float,
    size: Float,
    walkT: Float,
    moving: Boolean,
) {
    val u = size / 8f
    val bob = if (moving) abs(sin(walkT * 8f)) * u * 0.6f else 0f
    val swing = if (moving) sin(walkT * 8f) * u * 1.3f else 0f

    // 落地投影：先铺柔和椭圆，苦力怕才不是「浮」在桌布上
    drawOval(
        color = Color(0x33000000),
        topLeft = Offset(x - size * 0.55f, groundY + u * 0.2f - u * 0.9f),
        size = Size(size * 1.1f, u * 1.8f),
    )

    val top = groundY - 14f * u - bob
    val hx = x - 4f * u

    // 腿：两对，走路时交替摆动
    val legColor = Color(0xFF3C7F35)
    drawRect(legColor, Offset(hx + u + swing, top + 11f * u), Size(2f * u, 3f * u))
    drawRect(legColor, Offset(hx + 5f * u - swing, top + 11f * u), Size(2f * u, 3f * u))

    // 身体：比头窄一档
    drawRect(Color(0xFF478F3D), Offset(hx + 2f * u, top + 8f * u), Size(4f * u, 3f * u))

    // 头：三档绿的随机斑驳（低噪点，避免「脏」感）
    val skin = listOf(Color(0xFF3F8A37), Color(0xFF54AC47), Color(0xFF68C25A))
    for (r in 0 until 8) {
        for (c in 0 until 8) {
            val v = CREEPER_SKIN[r * 8 + c]
            val col = when {
                v < 0.28f -> skin[0]
                v < 0.85f -> skin[1]
                else -> skin[2]
            }
            drawRect(col, Offset(hx + c * u, top + r * u), Size(u + 0.5f, u + 0.5f))
        }
    }

    // 经典脸：双眼 + 上窄下宽的嘴（一直裂到头底缘）
    val face = Color(0xFF0D120E)
    drawRect(face, Offset(hx + u, top + 2f * u), Size(2f * u, 2f * u))
    drawRect(face, Offset(hx + 5f * u, top + 2f * u), Size(2f * u, 2f * u))
    drawRect(face, Offset(hx + 3f * u, top + 4f * u), Size(2f * u, 4f * u))
    drawRect(face, Offset(hx + 2f * u, top + 5f * u), Size(u, 3f * u))
    drawRect(face, Offset(hx + 5f * u, top + 5f * u), Size(u, 3f * u))

    // 深色描边：在浅色桌布上勾出轮廓，避免一团绿糊成背景
    drawRect(
        color = Color(0x80121A10),
        topLeft = Offset(hx, top),
        size = Size(8f * u, 8f * u),
        style = Stroke(width = 1f),
    )
}

/**
 * 画飞散的曲奇碎片（原版 `drawFragments`）。
 *
 * easeOutCubic 向外炸开，同时受重力下坠；
 * 不旋转（旋转会破坏像素的对齐感）。
 */
private fun DrawScope.drawFragments(t: Float, cx: Float, cy: Float, frags: List<Frag>, r: Float, cookie: ImageBitmap) {
    val u = (t / SHATTER_FLY).coerceIn(0f, 1f)
    val e = 1f - (1f - u) * (1f - u) * (1f - u)   // easeOutCubic
    val gravity = 480f * t * t                     // 重力下坠
    val alpha = ((SHATTER_FLY - t) / 0.35f + 1f).coerceIn(0f, 1f)

    frags.forEach { fg ->
        val px = cx + fg.gx + fg.dx * e
        val py = cy + fg.gy + fg.dy * e + gravity
        // 碎片从图标上取对应区域，保证颜色正确
        drawImage(
            image = cookie,
            srcOffset = androidx.compose.ui.unit.IntOffset(
                ((fg.gx + r) / (r * 2) * cookie.width).toInt().coerceIn(0, cookie.width - 1),
                ((fg.gy + r) / (r * 2) * cookie.height).toInt().coerceIn(0, cookie.height - 1),
            ),
            srcSize = androidx.compose.ui.unit.IntSize(
                (fg.w / (r * 2) * cookie.width).toInt().coerceAtLeast(1),
                (fg.h / (r * 2) * cookie.height).toInt().coerceAtLeast(1),
            ),
            dstOffset = androidx.compose.ui.unit.IntOffset(px.toInt(), py.toInt()),
            dstSize = androidx.compose.ui.unit.IntSize(fg.w.toInt(), fg.h.toInt()),
            alpha = alpha,
        )
    }
}









