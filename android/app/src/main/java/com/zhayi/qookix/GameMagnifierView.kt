package com.zhayi.qookix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 游戏放大镜（对齐 ZL2 / FCL 的同名功能）。
 *
 * ## 为什么需要
 *
 * 手机横屏输入时，输入法会盖住屏幕下半部分 —— 而 Minecraft 的**聊天栏就在左下**、
 * 物品栏也在底部。打字时看不到自己打的内容，只能盲打。
 *
 * ## 怎么取画面
 *
 * 走 `PixelCopy` 抓 [SurfaceView] 的 Surface（而不是整窗截图）：
 * 抓到的只有**游戏画面**，不含按键覆盖层 —— 否则放大后按键也被放大，更看不清。
 * TextureView 分支直接 `getBitmap()` 再裁剪。
 *
 * ## 交互
 *
 * - 单指拖动：平移取景（拖右边 = 看右边）
 * - 双击：在 2× / 3× / 4× 三档之间切换
 * - 右上角 ×：关闭（只关这一局，不写偏好）
 *
 * ⚠️ 抓帧是**异步回调**的（PixelCopy 必须等 GPU 那一帧就绪），
 * 所以同一时刻只允许一个请求在飞，否则会疯狂排 handler。
 */
class GameMagnifierView(context: Context) : FrameLayout(context) {

    companion object {
        /** 抓帧间隔：约 9 fps。放大镜只是辅助看清文字，不需要 60fps，省电也省 GPU。 */
        private const val CAPTURE_INTERVAL_MS = 110L
        /** 关闭按钮的点击半径（dp 换算前按像素算，够大就行）。 */
        private const val CLOSE_HIT_DP = 22f
        private val ZOOMS = floatArrayOf(2f, 3f, 4f)
    }

    /** 游戏画面所在的 View（MinecraftGLSurface 内部的 SurfaceView / TextureView）。 */
    private var renderView: View? = null

    /** 当前这一帧的 ROI 像素（尺寸随倍率变化，复用同一块内存）。 */
    private var frame: Bitmap? = null

    private var zoomIndex = 0
    private val zoom: Float get() = ZOOMS[zoomIndex]

    /** 取景中心（游戏表面坐标系，像素）。默认对着左下角 —— 聊天栏在那儿。 */
    private var centerX = 0f
    private var centerY = 0f
    private var centered = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(0xCC, 0xFF, 0xC1, 0x07)
    }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val scrimPaint = Paint().apply { color = Color.argb(0xCC, 0, 0, 0) }

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false
    private var running = false

    // ── 手势状态 ────────────────────────────────────────────────────
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTapAt = 0L
    private var dragging = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            captureOnce()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    init {
        setWillNotDraw(false)
        // 面板自己是半透明底：抓帧还没回来时不会是一块纯黑
        setBackgroundColor(Color.argb(0xB0, 0, 0, 0))
    }

    /** 绑定游戏画面所在的 View（每次显示前调一次即可，内部去重）。 */
    fun bind(target: View?) {
        if (target === renderView) return
        renderView = target
        centered = false
        frame?.recycle()
        frame = null
    }

    /** 开始抓帧（面板可见时调）。 */
    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

    /** 停止抓帧（面板隐藏 / Activity 暂停时调，否则会一直空转）。 */
    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == View.VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        frame?.recycle()
        frame = null
        super.onDetachedFromWindow()
    }

    // ── 抓帧 ────────────────────────────────────────────────────────

    private fun captureOnce() {
        if (capturing) return
        val v = renderView ?: return
        if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0) return
        if (width <= 0 || height <= 0) return

        if (!centered) {
            // 默认看左下：聊天栏的位置。横向 1/4、纵向 4/5 处。
            centerX = v.width * 0.25f
            centerY = v.height * 0.80f
            centered = true
        }

        val roiW = max(16, (width / zoom).toInt())
        val roiH = max(16, (height / zoom).toInt())
        var cx = centerX.toInt()
        var cy = centerY.toInt()
        cx = cx.coerceIn(roiW / 2, max(roiW / 2, v.width - roiW / 2))
        cy = cy.coerceIn(roiH / 2, max(roiH / 2, v.height - roiH / 2))

        val bmp = ensureFrame(roiW, roiH) ?: return
        val src = Rect(cx - roiW / 2, cy - roiH / 2, cx + roiW / 2, cy + roiH / 2)

        when (v) {
            is SurfaceView -> {
                val surface = v.holder.surface
                if (surface == null || !surface.isValid) return
                capturing = true
                try {
                    @Suppress("DEPRECATION")
                    PixelCopy.request(surface, src, bmp, { result ->
                        capturing = false
                        if (result == PixelCopy.SUCCESS && running) invalidate()
                    }, handler)
                } catch (t: Throwable) {
                    // 某些 ROM 在 Surface 刚好重建时会抛 IllegalArgument —— 跳过这一帧
                    capturing = false
                }
            }
            is TextureView -> {
                val full = try {
                    v.getBitmap(v.width, v.height)
                } catch (t: Throwable) {
                    null
                } ?: return
                try {
                    val safe = Rect(
                        max(0, src.left), max(0, src.top),
                        min(full.width, src.right), min(full.height, src.bottom),
                    )
                    if (safe.width() > 0 && safe.height() > 0) {
                        val cropped = Bitmap.createBitmap(full, safe.left, safe.top, safe.width(), safe.height())
                        val c = Canvas(bmp)
                        c.drawBitmap(cropped, null, Rect(0, 0, bmp.width, bmp.height), paint)
                        cropped.recycle()
                        invalidate()
                    }
                } finally {
                    full.recycle()
                }
            }
            else -> Unit
        }
    }

    private fun ensureFrame(w: Int, h: Int): Bitmap? {
        val cur = frame
        if (cur != null && cur.width == w && cur.height == h && !cur.isRecycled) return cur
        cur?.recycle()
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frame = it }
        } catch (t: Throwable) {
            null
        }
    }

    // ── 绘制 ────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = frame
        if (bmp != null && !bmp.isRecycled) {
            canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        }
        // 黄色描边：一眼看出这是浮层，不是游戏本体
        borderPaint.strokeWidth = 4f
        canvas.drawRect(2f, 2f, width - 2f, height - 2f, borderPaint)

        // 右上角关闭 ×
        val cx = width - dp(CLOSE_HIT_DP)
        val cy = dp(CLOSE_HIT_DP)
        val r = dp(9f)
        canvas.drawCircle(cx, cy, dp(13f), scrimPaint)
        canvas.drawLine(cx - r, cy - r, cx + r, cy + r, closePaint)
        canvas.drawLine(cx - r, cy + r, cx + r, cy - r, closePaint)
    }

    // ── 手势 ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val v = renderView
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                // 关闭按钮
                if (event.x >= width - dp(CLOSE_HIT_DP * 2) && event.y <= dp(CLOSE_HIT_DP * 2)) {
                    visibility = View.GONE
                    stop()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(event.x - downX) > dp(4f) || abs(event.y - downY) > dp(4f)) dragging = true
                if (dragging && v != null) {
                    // 手指往右拖 = 想看右边 → 取景中心往左移
                    centerX = (centerX - dx / zoom).coerceIn(0f, v.width.toFloat())
                    centerY = (centerY - dy / zoom).coerceIn(0f, v.height.toFloat())
                    centered = true
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 300L) {
                        // 双击换倍率
                        zoomIndex = (zoomIndex + 1) % ZOOMS.size
                        lastTapAt = 0L
                        invalidate()
                    } else {
                        lastTapAt = now
                    }
                }
                return true
            }
        }
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
