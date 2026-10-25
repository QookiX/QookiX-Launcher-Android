package com.zhayi.qookix.compose.ui

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Android 风格的**波浪加载指示器**（2026-10-17 新增）。
 *
 * 为什么自己画而不是用 `LinearWavyProgressIndicator`：
 * Material3 的 wavy 系列（1.4+ experimental）在当前 Compose BOM 里还不能用，
 * 而它本质就是「一条正弦波在横向平移 + 描边」—— 自己 Canvas 画更可控，
 * 也不会因为升级 BOM 而炸。
 *
 * 视觉：一条波浪线持续从左向右流动，末端渐隐。
 * 这就是 Android 14+ 系统加载条那种感觉。
 *
 * 用法：
 * ```
 * QkWavyLoader()                    // 默认撑满宽度
 * QkWavyLoader(height = 6.dp)
 * QkWavyLoader(color = MaterialTheme.colorScheme.secondary)
 * ```
 */
@Composable
fun QkWavyLoader(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 8.dp,
    /** 波长（dp）—— 越小波浪越密。 */
    wavelength: Dp = 28.dp,
    /** 振幅（dp）—— 波浪高度的一半。 */
    amplitude: Dp = 3.dp,
    /** 一个完整周期的时间（毫秒）—— 越小流得越快。 */
    periodMs: Int = 900,
) {
    val transition = rememberInfiniteTransition(label = "wavy")
    // 0f → 1f 循环，作为相位偏移
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavyPhase",
    )

    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        val wl = wavelength.toPx().coerceAtLeast(1f)
        val amp = amplitude.toPx().coerceAtMost(h / 2f)
        // 相位让波形横向滚动：phase 从 0 到 1，波形正好平移一个波长
        val offset = phase * wl

        val path = Path()
        // 步长 2px：足够平滑，又不会太耗
        val step = 2f
        var x = 0f
        var first = true
        while (x <= w) {
            // 正弦波：y = midY + amp * sin(2π * (x + offset) / wl)
            val angle = (2.0 * PI * (x + offset) / wl).toFloat()
            val y = midY + amp * sin(angle)
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
            x += step
        }

        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}

/**
 * 波浪加载 + 文字说明（用于整块内容区的加载态）。
 *
 * @param text 说明文字（如「正在加载版本清单…」）
 */
@Composable
fun QkWavyLoading(
    modifier: Modifier = Modifier,
    text: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        QkWavyLoader(color = color, height = 10.dp, amplitude = 4.dp)
        if (text != null) {
            Spacer(Modifier.height(QkSpacing.sm))
            androidx.compose.material3.Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 圆形波浪加载（用于按钮内、头像位等小空间）。
 *
 * 一圈波浪沿圆周流动。比 `CircularProgressIndicator` 更"活"。
 */
@Composable
fun QkWavyRing(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.5.dp,
) {
    val transition = rememberInfiniteTransition(label = "wavyRing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavyRingPhase",
    )

    Canvas(modifier = modifier.size(size)) {
        val r = (this.size.minDimension - strokeWidth.toPx()) / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val steps = 120
        val path = Path()
        var first = true
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val angle = 2 * PI * t
            // 半径随角度做正弦扰动 —— 形成"波浪环"
            val wobble = 1f + 0.12f * sin(angle * 8 + phase).toFloat()
            val x = center.x + (r * wobble) * kotlin.math.cos(angle).toFloat()
            val y = center.y + (r * wobble) * kotlin.math.sin(angle).toFloat()
            if (first) {
                path.moveTo(x, y); first = false
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round),
        )
    }
}
