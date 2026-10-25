package com.zhayi.qookix

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import com.kdt.SideDialogView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 性能小窗的功能设置：字号、背景不透明度、文字颜色、显示哪些参数。
 * 改一项存一项，并立即应用到小窗上（不用重启游戏）。
 */
class PerfSettingsDialog(
    private val ctx: Context,
    parent: ViewGroup,
    private val onChanged: () -> Unit
) : SideDialogView(ctx, parent, R.layout.dialog_perf_settings) {

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PerfOverlayView.PREFS_NAME, Context.MODE_PRIVATE)

    private val colors = listOf(
        "白色" to Color.WHITE,
        "绿色" to Color.parseColor("#7CFC00"),
        "青色" to Color.parseColor("#4DD0E1"),
        "黄色" to Color.parseColor("#FFD54F"),
        "橙色" to Color.parseColor("#FF9800"),
        "红色" to Color.parseColor("#FF5252"),
        "粉色" to Color.parseColor("#FF80AB")
    )

    private lateinit var fontSeek: SeekBar
    private lateinit var alphaSeek: SeekBar
    private val checkBoxes = mutableMapOf<String, CheckBox>()

    init {
        setTitle(R.string.perf_title)
        setStartButtonListener(R.string.perf_close) { disappear(true) }
    }

    override fun onInflate() {
        fontSeek = mDialogContent.findViewById(R.id.perf_font_seek)
        alphaSeek = mDialogContent.findViewById(R.id.perf_alpha_seek)

        // 字号：0.7x ~ 1.6x
        fontSeek.progress = scaleToProgress(prefs.getFloat(PerfOverlayView.KEY_FONT_SCALE, 1f))
        fontSeek.setOnSeekBarChangeListener(onSeek { progress ->
            prefs.edit().putFloat(PerfOverlayView.KEY_FONT_SCALE, progressToScale(progress)).apply()
            onChanged()
        })

        // 背景不透明度：0 ~ 1
        alphaSeek.progress = (prefs.getFloat(PerfOverlayView.KEY_ALPHA, 0.65f) * 100).roundToInt()
        alphaSeek.setOnSeekBarChangeListener(onSeek { progress ->
            prefs.edit().putFloat(PerfOverlayView.KEY_ALPHA, progress / 100f).apply()
            onChanged()
        })

        buildColorRow()

        val items = listOf(
            R.id.perf_cb_fps to PerfOverlayView.KEY_SHOW_FPS,
            R.id.perf_cb_frame to PerfOverlayView.KEY_SHOW_FRAME,
            R.id.perf_cb_low1 to PerfOverlayView.KEY_SHOW_LOW1,
            R.id.perf_cb_max to PerfOverlayView.KEY_SHOW_MAX,
            R.id.perf_cb_cpu to PerfOverlayView.KEY_SHOW_CPU,
            R.id.perf_cb_mem to PerfOverlayView.KEY_SHOW_MEM,
            R.id.perf_cb_gpu to PerfOverlayView.KEY_SHOW_GPU,
            R.id.perf_cb_renderer to PerfOverlayView.KEY_SHOW_RENDERER,
            R.id.perf_cb_resolution to PerfOverlayView.KEY_SHOW_RESOLUTION
        )
        for ((viewId, key) in items) {
            val cb: CheckBox = mDialogContent.findViewById(viewId)
            cb.isChecked = prefs.getBoolean(key, true)
            cb.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChanged()
            }
            checkBoxes[key] = cb
        }
    }

    private fun buildColorRow() {
        val row: LinearLayout = mDialogContent.findViewById(R.id.perf_color_row)
        buildColorRowContent(row, prefs.getInt(PerfOverlayView.KEY_COLOR, Color.WHITE))
    }

    private fun buildColorRowContent(row: LinearLayout, current: Int) {
        row.removeAllViews()
        val size = dp(30)
        for ((_, color) in colors) {
            val swatch = View(ctx)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(8)
            swatch.layoutParams = lp
            swatch.background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(6).toFloat()
                if (color == current) setStroke(dp(2), Color.WHITE)
            }
            swatch.setOnClickListener {
                prefs.edit().putInt(PerfOverlayView.KEY_COLOR, color).apply()
                buildColorRowContent(row, color)
                onChanged()
            }
            row.addView(swatch)
        }
    }

    private fun onSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    /** 0.7x ~ 1.6x ↔ 0 ~ 100 的映射。 */
    private fun scaleToProgress(scale: Float): Int =
        (((scale - 0.7f) / (1.6f - 0.7f)) * 100).roundToInt().coerceIn(0, 100)

    private fun progressToScale(progress: Int): Float = 0.7f + (1.6f - 0.7f) * (progress / 100f)

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()
}
