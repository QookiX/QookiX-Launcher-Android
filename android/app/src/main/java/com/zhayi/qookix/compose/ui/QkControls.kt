package com.zhayi.qookix.compose.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.theme.qkPressable

/**
 * 按钮层。
 *
 * ## 为什么不直接用 Material3 的 `Button`
 *
 * M3 `<Button>` 的默认形态是 **20dp 圆角 + ripple**，而我们要的是：
 * - **pill 形**（Expressive 的形状语言，全圆）
 * - **按压缩放回弹**（`qkPressable`，spring 曲线）而不是纯 ripple
 * - 统一高度（现在项目里 40 / 44 / 48dp 混着写，并排时高低不齐）
 *
 * 所以这里用 `Surface + qkPressable` 自己搭，样式完全受 token 控制。
 *
 * ## 用法
 *
 * ```kotlin
 * QkButton("启动游戏", onClick = …, tone = QkButtonTone.Filled)
 * QkButton("取消", onClick = …, tone = QkButtonTone.Text)
 * QkButton("安装", onClick = …, loading = true)   // 转圈 + 自动禁用
 * ```
 */

/** 按钮色调。对应 MD3 的四种容器层级。 */
enum class QkButtonTone {
    /** 主色实心 —— 一屏只该有一个（主操作）。 */
    Filled,

    /** 主色容器 —— 次级主操作，比 Filled 柔和。 */
    Tonal,

    /** 描边 —— 并列选项。 */
    Outlined,

    /** 纯文字 —— 最低优先级（取消、跳过）。 */
    Text,
}

/**
 * 标准按钮：pill 形状 + spring 按压。
 *
 * @param loading 转圈并自动禁用（防止重复点击）
 */
@Composable
fun QkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: QkButtonTone = QkButtonTone.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interactive = enabled && !loading

    val container = when (tone) {
        QkButtonTone.Filled -> colors.primary
        QkButtonTone.Tonal -> colors.primaryContainer
        QkButtonTone.Outlined, QkButtonTone.Text -> Color.Transparent
    }
    val content = when (tone) {
        QkButtonTone.Filled -> colors.onPrimary
        QkButtonTone.Tonal -> colors.onPrimaryContainer
        QkButtonTone.Outlined, QkButtonTone.Text -> colors.primary
    }
    // 禁用态统一降透明度（而不是换灰），和 Expressive 的「同一个色系、只是退后」一致
    val tint = if (interactive) content else content.copy(alpha = 0.38f)

    Surface(
        modifier = modifier
            .height(48.dp)
            .then(if (interactive) Modifier.qkPressable { onClick() } else Modifier),
        shape = CircleShape,
        color = if (interactive) container else container.copy(alpha = 0.38f),
        contentColor = tint,
        border = if (tone == QkButtonTone.Outlined) {
            BorderStroke(1.dp, if (interactive) colors.outline else colors.outline.copy(alpha = 0.38f))
        } else {
            null
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = QkSpacing.xl),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = tint,
                )
            } else {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(QkSpacing.xs))
                }
                Text(text = text, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * 图标按钮：40dp 圆形，可选选中态（导航、工具栏用）。
 *
 * 比 M3 的 `IconButton` 多两件事：**圆形选中底** 和 **spring 按压**。
 */
@Composable
fun QkIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .size(40.dp)
            .then(if (enabled) Modifier.qkPressable { onClick() } else Modifier),
        shape = CircleShape,
        color = if (selected) colors.primaryContainer else Color.Transparent,
        contentColor = when {
            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        },
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}
