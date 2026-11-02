package com.zhayi.qookix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.PopupMenu
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 游戏放大镜（对齐 ZL2 / FCL）。
 *
 * ## 为什么需要
 *
 * 横屏输入时输入法盖住屏幕下半部分，而聊天栏在左下、物品栏在底部 ——
 * 打字时看不到自己打的内容。
 *
 * ## 取画面
 *
 * `PixelCopy` 抓 [SurfaceView] 的 Surface（**不是整窗截图**），抓到的只有游戏画面，
 * 不含按键覆盖层；[TextureView] 分支走 `getBitmap()` 再裁剪。
 *
 * ## 外观
 *
 * 2026-11-02 按用户反馈重做：圆角面板、深色半透明底（和游戏内工具栏同一套视觉）、
 * 右上角两个圆形小按钮（⋮ 菜单 / × 关闭），去掉了原来的土黄描边。
 *
 * ## 交互
 *
 * - 单指拖动：平移取景
 * - 双击：2× / 3× / 4× 循环
 * - ⋮：「输入时自动显示」开关、倍率、恢复默认取景
 * - ×：只关这一局
 */
class GameMagnifierView(context: Context) : FrameLayout(context) {

    companion object {
        /** 偏好文件名：和 GameActivity 共用。 */
        const val PREFS = "game_magnifier"

        /** 「输入法弹出时自动显示」。默认开。 */
        const val KEY_ON_IME = "on_ime"

        /** 上次用的倍率（记下来，下次进来沿用）。 */
        const val KEY_ZOOM = "zoom"

        /**
         * 上次的取景中心，存成**相对画面尺寸的比例**（0~1）。
         *
         * 存比例而不是像素：换分辨率 / 转屏后仍然指向同一块画面内容。
         * -1 表示还没手动调过，走默认位置（屏幕最左下角的聊天输入框）。
         */
        const val KEY_CX = "center_x"
        const val KEY_CY = "center_y"

        private const val CAPTURE_INTERVAL_MS = 110L
        private val ZOOMS = floatArrayOf(2f, 3f, 4f)

        private const val CORNER_DP = 14f

        // ⋮ 菜单的条目 id
        private const val MENU_ID_AUTO = 1
        private const val MENU_ID_RESET = 2
        private const val MENU_ID_ZOOM_BASE = 100
    }

    /** 用户关掉「自动显示」时通知外面把面板收起来。 */
    var onAutoShowDisabled: (() -> Unit)? = null

    /** 菜单关掉后回调：让外面重新看一眼输入法还在不在。 */
    var onMenuDismissed: (() -> Unit)? = null

    /**
     * ⋮ 菜单是否开着。
     *
     * ⚠️ `PopupMenu` 会**抢焦点**，输入法随之后退 → IME insets 归零 →
     * 外面就会把面板收起来，连带菜单一起消失（第一版实测就是这样：
     * 点 ⋮ 面板直接没了）。
     * 菜单开着期间外面不能收面板，等菜单关掉再重新判断。
     */
    var isMenuOpen = false
        private set

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var renderView: View? = null
    private var frame: Bitmap? = null
    private var zoomIndex = prefs.getInt(KEY_ZOOM, 0).coerceIn(0, ZOOMS.size - 1)
    private val zoom: Float get() = ZOOMS[zoomIndex]

    /** 取景中心（游戏表面坐标系，像素）。默认左下 —— 聊天栏在那儿。 */
    private var centerX = 0f
    private var centerY = 0f
    private var centered = false

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val clipRect = RectF()

    private val handler = Handler(Looper.getMainLooper())
    private var capturing = false
    private var running = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTapAt = 0L
    private var dragging = false

    private lateinit var moreButton: ImageButton
    private lateinit var closeButton: ImageButton

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            captureOnce()
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    init {
        // 圆角深底：跟游戏内工具栏的观感一致（半透明黑 + 极淡描边），不再是黄框
        background = GradientDrawable().apply {
            cornerRadius = dp(CORNER_DP)
            setColor(Color.argb(0xB3, 0x12, 0x12, 0x12))
            setStroke(dp(1f).toInt(), Color.argb(0x33, 0xFF, 0xFF, 0xFF))
        }
        clipToOutline = true
        buildButtons()
    }

    private fun buildButtons() {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        moreButton = makeIconButton(R.drawable.ic_qk_more, "放大镜设置")
        moreButton.setOnClickListener { showMenu(it) }
        closeButton = makeIconButton(R.drawable.ic_qk_close, "关闭放大镜")
        closeButton.setOnClickListener { dismissThisSession() }
        bar.addView(moreButton)
        bar.addView(closeButton)
        addView(
            bar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(6f).toInt()
                marginEnd = dp(6f).toInt()
            },
        )
    }

    private fun makeIconButton(iconRes: Int, desc: String): ImageButton =
        ImageButton(context).apply {
            setImageResource(iconRes)
            contentDescription = desc
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(0x59, 0, 0, 0))
            }
            val pad = dp(7f).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(30f).toInt(), dp(30f).toInt()).apply {
                marginStart = dp(4f).toInt()
            }
        }

    // ── 菜单 ────────────────────────────────────────────────────────

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, MENU_ID_AUTO, 0, "输入时自动显示").apply {
            isCheckable = true
            isChecked = prefs.getBoolean(KEY_ON_IME, true)
        }
        val zoomGroup = 1
        // 单选组：点一个自动取消其他
        popup.menu.setGroupCheckable(zoomGroup, true, true)
        ZOOMS.forEachIndexed { i, z ->
            popup.menu.add(zoomGroup, MENU_ID_ZOOM_BASE + i, 10 + i, "放大 ${z.toInt()}×").apply {
                isCheckable = true
                isChecked = i == zoomIndex
            }
        }
        popup.menu.add(0, MENU_ID_RESET, 20, "恢复默认取景")
        isMenuOpen = true
        popup.setOnDismissListener {
            isMenuOpen = false
            // 菜单期间输入法可能已经退了，交给外面重新判断是否该收面板
            onMenuDismissed?.invoke()
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ID_AUTO -> {
                    val next = !item.isChecked
                    item.isChecked = next
                    prefs.edit().putBoolean(KEY_ON_IME, next).apply()
                    // 关掉之后本局也别再弹
                    if (!next) onAutoShowDisabled?.invoke()
                    true
                }
                MENU_ID_RESET -> {
                    // 清掉记住的位置，回到默认的「最左下角聊天框」
                    prefs.edit().remove(KEY_CX).remove(KEY_CY).apply()
                    centered = false
                    invalidate()
                    true
                }
                else -> {
                    val idx = item.itemId - MENU_ID_ZOOM_BASE
                    if (idx in ZOOMS.indices) {
                        zoomIndex = idx
                        prefs.edit().putInt(KEY_ZOOM, idx).apply()
                        frame?.recycle()
                        frame = null
                        invalidate()
                        true
                    } else {
                        false
                    }
                }
            }
        }
        popup.show()
    }

    private fun dismissThisSession() {
        visibility = View.GONE
        stop()
    }

    // ── 绑定 / 生命周期 ─────────────────────────────────────────────

    fun bind(target: View?) {
        if (target === renderView) return
        renderView = target
        centered = false
        frame?.recycle()
        frame = null
    }

    fun start() {
        if (running) return
        running = true
        handler.post(tick)
    }

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

        val roiW = max(16, (width / zoom).toInt())
        val roiH = max(16, (height / zoom).toInt())

        if (!centered) {
            val fx = prefs.getFloat(KEY_CX, -1f)
            val fy = prefs.getFloat(KEY_CY, -1f)
            if (fx >= 0f && fy >= 0f) {
                // 上次手动调过 → 原样恢复
                centerX = fx * v.width
                centerY = fy * v.height
            } else {
                // 默认：屏幕**最左下角**对齐（聊天输入框就在那儿）。
                // 纵向让 ROI 的底边贴住画面底边 —— 之前取 0.80 高度，
                // 结果输入框露不出来，用户还得往下滑一下。
                centerX = min(v.width.toFloat() / 2f, v.width * 0.18f)
                    .coerceAtLeast(roiW / 2f)
                centerY = v.height - roiH / 2f
            }
            centered = true
        }
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
                        Canvas(bmp).drawBitmap(cropped, null, Rect(0, 0, bmp.width, bmp.height), paint)
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

    /** 把当前取景中心存成比例（换分辨率 / 转屏后仍指向同一块内容）。 */
    private fun saveCenter() {
        val v = renderView ?: return
        if (v.width <= 0 || v.height <= 0) return
        prefs.edit()
            .putFloat(KEY_CX, (centerX / v.width).coerceIn(0f, 1f))
            .putFloat(KEY_CY, (centerY / v.height).coerceIn(0f, 1f))
            .apply()
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
        if (bmp == null || bmp.isRecycled) return
        // 圆角裁剪：让画面跟着面板一起圆，不再出现直角
        val radius = dp(CORNER_DP)
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawBitmap(bmp, null, clipRect, paint)
        canvas.restoreToCount(save)
    }

    // ── 手势（面板空白处）────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val v = renderView ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (abs(event.x - downX) > dp(4f) || abs(event.y - downY) > dp(4f)) dragging = true
                if (dragging) {
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
                // 松手就把取景位置存下来：下次弹键盘还停在这儿
                if (dragging) saveCenter()
                if (!dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt < 300L) {
                        zoomIndex = (zoomIndex + 1) % ZOOMS.size
                        prefs.edit().putInt(KEY_ZOOM, zoomIndex).apply()
                        lastTapAt = 0L
                        frame?.recycle()
                        frame = null
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
