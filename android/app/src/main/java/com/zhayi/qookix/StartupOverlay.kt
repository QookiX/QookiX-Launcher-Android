package com.zhayi.qookix

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 游戏启动阶段（黑屏那几十秒）的可见反馈浮层。
 *
 * 为什么要有它：从点「启动」到 Mojang 红白图标出现之间，游戏面一直是全黑的 ——
 * 用户分不清是**在正常加载**还是**已经卡死/崩了**。这里把启动日志实时显示出来，
 * 配合「阶段文案 + 已用时 + 进度条」，一眼就能看出还在动。
 *
 * 数据来源与 [com.kdt.LoggerView] 相同：Rust 侧把 fd 1/2 重定向到的
 * `files/logs/launch-<实例>.log`（每次启动都会截断重建）。这里只读该文件的**增量**
 * 并保留最后若干行（不整篇塞进 TextView）。
 *
 * 什么时候自己让位（隐藏）：
 *  · 日志里出现「游戏窗口已经起来了」的标志（见 [READY_MARKERS]）；
 *  · 用户点按（不想看就点掉）；
 *  · 兜底超时 [HARD_HIDE_AFTER_MS]（万一是没见过的版本、标志没命中，
 *    也不能一直盖着游戏）。
 * 日志里出现错误关键字时**不会**自动隐藏，而是把阶段文案标红 —— 卡死/崩溃现场要留着。
 */
class StartupOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        /** 轮询间隔：给人看的，250ms 足够流畅，也不费电。 */
        private const val POLL_MS = 250L
        /** 底部保留的日志行数。 */
        private const val TAIL_LINES = 9
        /** 兜底隐藏时间：这个时长还没命中标志就认为标志不适用，别盖住游戏。 */
        private const val HARD_HIDE_AFTER_MS = 150_000L
        /** 单次最多读 64KB，避免日志暴涨时一次性卡住主线程。 */
        private const val MAX_READ_BYTES = 64 * 1024L

        /**
         * 「游戏窗口/首帧已就绪」的标志。
         *
         * 各版本的措辞不同，所以列一组：MC 会在创建完窗口、GL 上下文可用后打印它们，
         * 时间点正好在 Mojang 图标出现前后 —— 这时浮层就该让位。
         */
        private val READY_MARKERS = listOf(
            "Setting user:",              // 1.6~1.12 及大部分版本
            "Backend library:",           // 1.13+
            "Using graphics backend",     // 26.x（SDL 后端）
            "Reloading ResourceManager",
            "Sound engine started",
        )

        /**
         * 出错关键字：命中后不再自动隐藏，并把阶段文案标红。
         *
         * 记得别写 "Exception" —— 26.x 启动时 Vulkan 后端必然失败一次再回退 OpenGL
         * （`BackendCreationException` 是被游戏自己 catch 的正常噪音），
         * 拿它当失败会在每次启动都误报。
         */
        private val FAILURE_MARKERS = listOf(
            "Segmentation fault", "SIGSEGV", "UnsatisfiedLinkError",
            "Could not find or load main class", "Exception in thread \"",
            "A fatal error has been detected",
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    private val titleView: TextView
    private val stageView: TextView
    private val logView: TextView
    private val hintView: TextView
    private val progress: ProgressBar

    private var logFile: File? = null
    /** 已消费到的**字节**偏移（不是字符数；中文日志按字节算才不会错位）。 */
    private var offset = 0L
    private val tail = ArrayDeque<String>()
    /** 用于阶段/标志判定的近期文本（限长，防止无限增长）。 */
    private val seen = StringBuilder()
    private var startedAt = 0L
    private var running = false
    private var failed = false

    /** 浮层隐藏时回调（GameActivity 用来释放引用）。 */
    var onDismiss: (() -> Unit)? = null

    private val poller = object : Runnable {
        override fun run() {
            if (!running) return
            readIncrement()
            refresh()
            handler.postDelayed(this, POLL_MS)
        }
    }

    init {
        setBackgroundColor(Color.rgb(8, 11, 15))
        isClickable = true
        setOnClickListener { dismiss("用户点按隐藏") }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        titleView = TextView(context).apply {
            text = context.getString(R.string.startup_title)
            setTextColor(Color.rgb(236, 244, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        column.addView(titleView)

        stageView = TextView(context).apply {
            text = context.getString(R.string.startup_stage_env)
            setTextColor(Color.rgb(150, 172, 196))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        }
        column.addView(stageView)

        progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        column.addView(progress)

        logView = TextView(context).apply {
            setTextColor(Color.rgb(122, 141, 163))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.08f)
        }
        addView(logView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(14), dp(14), dp(14), dp(34))
        })

        hintView = TextView(context).apply {
            text = context.getString(R.string.startup_hint)
            setTextColor(Color.rgb(86, 102, 122))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER
        }
        addView(hintView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
            setMargins(0, 0, 0, dp(12))
        })
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 开始跟随日志。
     *
     * @param logFile Rust 写的启动日志（`files/logs/launch-<实例>.log`）。
     */
    fun start(logFile: File) {
        this.logFile = logFile
        // 起始偏移的讲究：Rust 侧是在启动流程里 `truncate(true)` 重建这个日志的，
        // 而浮层往往比那次截断更早开始读 —— 如果无脑从 0 读，会先把**上一局**的日志
        // 显示出来（实测：上一局的 crashpad 报错整屏），随后截断发生、偏移越界，
        // 就再也读不到新内容了。
        // 所以：文件看起来是旧的（3 秒内没被写过）就从**当前末尾**开始跟，
        // 之后一旦发现文件被截断（长度 < 偏移）再回到 0 从头读。
        val stale = logFile.isFile && System.currentTimeMillis() - logFile.lastModified() > 3_000
        offset = if (stale) logFile.length() else 0L
        tail.clear()
        seen.setLength(0)
        failed = false
        startedAt = SystemClock.uptimeMillis()
        running = true
        alpha = 1f
        visibility = VISIBLE
        stageView.setTextColor(Color.rgb(150, 172, 196))
        stageView.text = context.getString(R.string.startup_stage_env)
        logView.text = ""
        handler.removeCallbacks(poller)
        handler.post(poller)
    }

    /** 停止跟随（Activity 销毁时务必调用，避免 Handler 泄漏）。 */
    fun stop() {
        running = false
        handler.removeCallbacks(poller)
    }

    /** 直接隐藏（例如发现游戏其实早就在后台跑着，不需要这个浮层）。 */
    fun hideNow() {
        stop()
        visibility = GONE
        onDismiss?.invoke()
    }

    private fun dismiss(reason: String) {
        if (!running && visibility != VISIBLE) return
        stop()
        android.util.Log.i("StartupOverlay", "隐藏启动浮层：$reason")
        animate().alpha(0f).setDuration(220).withEndAction {
            visibility = GONE
            onDismiss?.invoke()
        }.start()
    }

    /** 读自 [offset] 起的新增内容，按完整行入队（半行留到下一轮）。 */
    private fun readIncrement() {
        val file = logFile ?: return
        if (!file.isFile) return
        try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                if (length < offset) {
                    // 文件被截断重建（Rust 每次启动都 truncate）→ 丢掉旧内容从头读
                    offset = 0
                    tail.clear()
                    seen.setLength(0)
                }
                if (length <= offset) return
                raf.seek(offset)
                val want = minOf(length - offset, MAX_READ_BYTES).toInt()
                val buf = ByteArray(want)
                val n = raf.read(buf)
                if (n <= 0) return

                var lastNewline = -1
                for (i in n - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        lastNewline = i
                        break
                    }
                }
                if (lastNewline < 0) return
                offset += lastNewline + 1

                val text = String(buf, 0, lastNewline, Charsets.UTF_8)
                if (seen.length < 16_384) seen.append(text)
                text.split('\n').forEach { line ->
                    val trimmed = line.trimEnd()
                    if (trimmed.isNotEmpty()) {
                        tail.addLast(trimmed)
                        while (tail.size > TAIL_LINES) tail.removeFirst()
                    }
                }
            }
        } catch (_: IOException) {
            // 文件正在写入/刚被截断，下一轮再看
        }
    }

    /** 刷新阶段文案、计时与日志尾部；顺带判定完成/失败。 */
    private fun refresh() {
        val elapsed = (SystemClock.uptimeMillis() - startedAt) / 1000
        val text = seen.toString()

        val failureHit = FAILURE_MARKERS.any { text.contains(it) }
        if (failureHit) failed = true

        val stageRes = if (failed) R.string.startup_failed else stageFor(text)
        val elapsedText = context.getString(R.string.startup_elapsed, elapsed)
        stageView.text = "${context.getString(stageRes)} · $elapsedText"
        stageView.setTextColor(
            if (failed) Color.rgb(255, 138, 128) else Color.rgb(150, 172, 196)
        )

        logView.text = tail.joinToString("\n")

        if (!failed) {
            if (READY_MARKERS.any { text.contains(it) }) {
                dismiss("检测到游戏窗口已就绪")
                return
            }
            if (elapsed * 1000 > HARD_HIDE_AFTER_MS) {
                dismiss("超时兜底")
            }
        }
    }

    /** 从日志内容推断当前阶段（取第一个命中的，越靠后越"深"）。 */
    private fun stageFor(text: String): Int = when {
        text.contains("Preparing spawn area") -> R.string.startup_stage_spawn
        text.contains("Reloading ResourceManager") || text.contains("Sound engine") ->
            R.string.startup_stage_resources
        text.contains("Setting user") || text.contains("Backend library") ||
            text.contains("Using graphics backend") -> R.string.startup_stage_game
        text.contains("gl4es") || text.contains("MobileGlues") || text.contains("EGL") ||
            text.contains("GLES") || text.contains("Initialising") -> R.string.startup_stage_renderer
        text.contains("[LWJGL]") -> R.string.startup_stage_window
        else -> R.string.startup_stage_env
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
