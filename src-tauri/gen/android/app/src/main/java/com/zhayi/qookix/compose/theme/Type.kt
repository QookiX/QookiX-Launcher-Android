package com.zhayi.qookix.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MD3 圆角阶梯。
 *
 * 这是观感差异的第二个来源（第一个是 surface-container 明度差）。
 * QookiX 现有 CSS 用的是 10~12px 的小圆角 + 1px 描边，
 * MD3 则用**更大的圆角**配**无描边**，靠形状区分层级。
 *
 * 取值遵循 MD3 规范：
 *   extraSmall  4dp   ← 小徽标
 *   small       8dp   ← 小控件
 *   medium     12dp   ← 卡片（列表项）
 *   large      16dp   ← 大卡片
 *   extraLarge 28dp   ← 底部弹层 / 大容器（MD3 标志性大圆角）
 */
val QookiXShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 排版。
 *
 * 沿用系统默认字体（Android 上是 Roboto / 厂商替换字体），
 * 但把标题的字重收紧 —— MD3 的标题偏中等字重（Medium/SemiBold），
 * 而 Web 界面习惯用 Bold，视觉上会显得「吵」。
 */
private val Default = Typography()

val QookiXTypography = Typography(
    displayLarge = Default.displayLarge,
    displayMedium = Default.displayMedium,
    displaySmall = Default.displaySmall,

    headlineLarge = Default.headlineLarge,
    headlineMedium = Default.headlineMedium,
    headlineSmall = Default.headlineSmall.copy(fontWeight = FontWeight.SemiBold),

    // 顶栏标题：MD3 用 titleLarge，Medium 字重
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = Default.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = Default.titleSmall.copy(fontWeight = FontWeight.Medium),

    bodyLarge = Default.bodyLarge,
    bodyMedium = Default.bodyMedium,
    bodySmall = Default.bodySmall,

    labelLarge = Default.labelLarge.copy(fontWeight = FontWeight.Medium),
    // 底部导航栏文字：MD3 规定 labelMedium + Medium 字重
    labelMedium = Default.labelMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Default.labelSmall,
)
