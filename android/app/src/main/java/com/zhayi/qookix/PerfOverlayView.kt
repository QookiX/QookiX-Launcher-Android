package com.zhayi.qookix

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 游戏内性能小窗：可拖动、可收起，显示哪些参数和外观都在「功能设置」里调。
 */
class PerfOverlayView(context: Context) : LinearLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val snapshotFile = File(context.getFilesDir(), "cache/perf.txt")

    private val mainText = TextView(context)
    private val detailText = TextView(context)
    private val background_ = GradientDrawable()

    private var collapsed = prefs.getBoolean(KEY_COLLAPSED, false)
    private var running = false
    private var lastCpuSample: LongArray? = null

    init {
        orientation = VERTICAL
        background = background_
        mainText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        detailText.typeface = Typeface.MONOSPACE
        addView(mainText)
        addView(detailText)
        applyStyle()
        setupDrag()
        restorePosition()
    }

    /** 外观与显示项都从偏好读，设置面板改完调它立即生效。 */
    fun applyStyle() {
        val pad = dp(6)
        setPadding(pad, pad, pad, pad)
        background_.setColor(Color.argb((prefs.getFloat(KEY_ALPHA, 0.65f) * 255).roundToInt(), 0, 0, 0))
        background_.cornerRadius = dp(8).toFloat()
        val color = prefs.getInt(KEY_COLOR, Color.WHITE)
        val scale = prefs.getFloat(KEY_FONT_SCALE, 1f)
        mainText.setTextColor(color)
        mainText.textSize = 14f * scale
        detailText.setTextColor((color and 0x00FFFFFF) or 0xD9000000.toInt())
        detailText.textSize = 11f * scale
        collapsed = prefs.getBoolean(KEY_COLLAPSED, collapsed)
        detailText.visibility = if (collapsed) GONE else VISIBLE
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 游戏 SurfaceView 会重新加进 ControlLayout，把小窗重新顶到最上，否则触摸会被截走
        bringToFront()
        running = true
        handler.postDelayed(::tick, 200)
    }

    override fun onDetachedFromWindow() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun setupDrag() {
        var downX = 0f; var downY = 0f
        var startTx = 0f; var startTy = 0f
        var moved = false
        val slop = dp(6).toFloat()

        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startTx = v.translationX; startTy = v.translationY
                    moved = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        moveWithinParent(v, startTx + dx, startTy + dy)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePosition(v)
                    } else {
                        collapsed = !collapsed
                        prefs.edit().putBoolean(KEY_COLLAPSED, collapsed).apply()
                        applyStyle()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun moveWithinParent(v: View, x: Float, y: Float) {
        val p = v.parent as? ViewGroup ?: return
        v.translationX = x.coerceIn(0f, (p.width - v.width).coerceAtLeast(0).toFloat())
        v.translationY = y.coerceIn(0f, (p.height - v.height).coerceAtLeast(0).toFloat())
    }

    private fun restorePosition() {
        translationX = prefs.getFloat(KEY_X, dp(12).toFloat())
        translationY = prefs.getFloat(KEY_Y, dp(120).toFloat())
        post {
            if (parent is ViewGroup) moveWithinParent(this, translationX, translationY)
        }
    }

    private fun savePosition(v: View) {
        prefs.edit().putFloat(KEY_X, v.translationX).putFloat(KEY_Y, v.translationY).apply()
    }

    private fun tick() {
        if (!running) return
        refresh()
        handler.postDelayed(::tick, REFRESH_MS)
    }

    private fun refresh() {
        val fpsLine = StringBuilder()
        val detail = StringBuilder()

        // ── 帧率相关（读渲染侧写的快照）──
        val snap = readSnapshot()
        if (snap != null) {
            val (fps, avgMs, low1, maxMs, renderer) = snap
            if (show(KEY_SHOW_FPS)) fpsLine.append(if (fps > 0) "$fps FPS" else "--")
            if (show(KEY_SHOW_FRAME) && avgMs > 0) {
                detail.line(String.format(Locale.US, "%.1f ms", avgMs))
            }
            if (show(KEY_SHOW_LOW1) && low1 > 0) detail.line("1% low $low1")
            if (show(KEY_SHOW_MAX) && maxMs > 0) {
                detail.line(String.format(Locale.US, "最差 %.0f ms", maxMs))
            }
            if (show(KEY_SHOW_RENDERER) && renderer.isNotEmpty()) detail.line(renderer)
        } else if (show(KEY_SHOW_FPS)) {
            fpsLine.append("--")
        }

        if (!collapsed) {
            val cpuSample = readCpuSample()
            var cpuPercent = -1
            // **两个都要判空**。readCpuSample() 在 /proc/self/stat 读失败或解析失败时返回 null，
            // 而 lastCpuSample 在游戏跑起来之后基本都非 null —— 原来写成
            // `lastCpuSample?.let { ... cpuSample!! ... }`，于是「上一轮采样成功 + 本轮失败」
            // 就是 NPE。这段跑在主线程 tick 里，NPE = 直接把游戏崩掉。
            val last = lastCpuSample
            if (cpuSample != null && last != null) {
                val dTotal = (cpuSample[0] + cpuSample[1]) - (last[0] + last[1])
                val dMs = cpuSample[2] - last[2]
                val ticksPerMs = getClkTck() / 1000.0
                if (dMs > 0 && ticksPerMs > 0) cpuPercent = (dTotal / (dMs * ticksPerMs) * 100.0).roundToInt()
            }
            lastCpuSample = cpuSample

            if (show(KEY_SHOW_CPU) && cpuPercent >= 0) detail.line("CPU $cpuPercent%")
            if (show(KEY_SHOW_MEM)) {
                val memKb = readVmRss()
                if (memKb > 0) detail.line(String.format(Locale.US, "内存 %.1fG", memKb / 1048576.0))
            }
            if (show(KEY_SHOW_GPU)) readGpuText()?.let { detail.line(it) }
            if (show(KEY_SHOW_RESOLUTION)) {
                val p = parent as? ViewGroup
                if (p != null && p.width > 0) detail.line("${p.width}×${p.height}")
            }
        }

        mainText.text = fpsLine
        detailText.text = detail
    }

    /** 快照太旧就当游戏没运行；返回 null。 */
    private fun readSnapshot(): PerfSnapshot? = try {
        val fresh = snapshotFile.isFile &&
            System.currentTimeMillis() - snapshotFile.lastModified() < SNAPSHOT_STALE_MS
        if (fresh) {
            val parts = readFile(snapshotFile.absolutePath).trim().split(";")
            PerfSnapshot(
                fps = parts.getOrNull(0)?.toIntOrNull() ?: 0,
                avgMs = parts.getOrNull(1)?.toFloatOrNull() ?: 0f,
                low1 = parts.getOrNull(2)?.toIntOrNull() ?: 0,
                maxMs = parts.getOrNull(3)?.toFloatOrNull() ?: 0f,
                renderer = prettyRenderer(parts.getOrNull(4) ?: "")
            )
        } else null
    } catch (_: Throwable) {
        null
    }

    private data class PerfSnapshot(
        val fps: Int, val avgMs: Float, val low1: Int, val maxMs: Float, val renderer: String
    )

    private fun prettyRenderer(cName: String): String = when (cName) {
        "vulkan_zink" -> "Zink"
        "opengles_mobileglues" -> "MobileGlues"
        "opengles2" -> "GL4ES"
        else -> cName
    }

    private fun show(key: String) = prefs.getBoolean(key, true)

    private fun readCpuSample(): LongArray? = try {
        val stat = readFile("/proc/self/stat")
        val close = stat.lastIndexOf(')')
        val rest = stat.substring(close + 2).split(" ")
        longArrayOf(rest[11].toLong(), rest[12].toLong(), System.currentTimeMillis())
    } catch (_: Throwable) {
        null
    }

    private fun getClkTck(): Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    } catch (_: Throwable) {
        100L
    }

    private fun readVmRss(): Long = try {
        readFile("/proc/self/status").lineSequence()
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.filter { it.isDigit() }?.toLong() ?: -1
    } catch (_: Throwable) {
        -1
    }

    private fun readGpuText(): String? = try {
        val busy = readFile("/sys/class/kgsl/kgsl3d0/gpubusy").trim().split("\\s+".toRegex())
        val busyV = busy[0].toLong()
        val totalV = busy[1].toLong()
        if (totalV > 0) {
            val freq = try {
                String.format(
                    Locale.US, " %.0fMHz",
                    readFile("/sys/class/kgsl/kgsl3d0/gpuclk").trim().toLong() / 1000.0
                )
            } catch (_: Throwable) {
                ""
            }
            String.format(Locale.US, "GPU %d%%%s", busyV * 100 / totalV, freq)
        } else null
    } catch (_: Throwable) {
        null
    }

    private fun StringBuilder.line(s: String) = apply {
        if (isNotEmpty()) append('\n')
        append(s)
    }

    private fun readFile(path: String): String =
        File(path).inputStream().use { it.readBytes().toString(Charsets.UTF_8) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val PREFS_NAME = "perf_overlay"
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_COLLAPSED = "collapsed"
        const val KEY_ENABLED = "enabled"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_ALPHA = "alpha"
        const val KEY_COLOR = "color"
        const val KEY_SHOW_FPS = "show_fps"
        const val KEY_SHOW_FRAME = "show_frame"
        const val KEY_SHOW_LOW1 = "show_low1"
        const val KEY_SHOW_MAX = "show_max"
        const val KEY_SHOW_CPU = "show_cpu"
        const val KEY_SHOW_MEM = "show_mem"
        const val KEY_SHOW_GPU = "show_gpu"
        const val KEY_SHOW_RENDERER = "show_renderer"
        const val KEY_SHOW_RESOLUTION = "show_resolution"

        private const val REFRESH_MS = 500L
        private const val SNAPSHOT_STALE_MS = 2000L
    }
}
