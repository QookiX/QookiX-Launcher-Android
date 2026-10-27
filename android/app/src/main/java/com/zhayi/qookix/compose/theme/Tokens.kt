package com.zhayi.qookix.compose.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.motion.QookiX

/**
 * QookiX 设计 token（间距 / 圆角 / 动效）。
 *
 * ## 为什么要有这个文件
 *
 * 之前的现状（2026-10-23 全量扫描 91 个 kt / 39k 行）：
 *   · 颜色**统一得很好** —— `Color(0x…)` 只出现在 `Theme.kt` 与展示页；
 *   · 圆角却散成 **10 个取值**（2/4/6/8/10/12/16/20/28/999）；
 *   · 间距散成 **6 个取值**（10/12/14/16/20/24，其中 14dp 用了 24 次）；
 *   · 动效没有入口，各页面状态不一。
 *
 * 结果就是"改个 UI 难受"：想统一调大圆角，得翻遍所有页面。
 * 现在改成**所有页面只引用这里的常量**，一处改、全 App 生效。
 *
 * ## 用法约定（新代码必须遵守）
 *   · 间距：`Modifier.padding(QkSpacing.md)`，不要再写 `16.dp`；
 *   · 圆角：`RoundedCornerShape(QkRadius.card)`，不要让 `MaterialTheme.shapes`
 *     之外的地方出现裸数字；
 *   · 可点击卡片的按压反馈：`Modifier.qkPressable { … }`。
 *
 * 旧代码里的裸数字会按"最接近"的档位在迁移时替换（14dp → 12 或 16）。
 */
object QkSpacing {
    /** 4dp —— 图标与文字之间那种极近的距离 */
    val xxs: Dp = 4.dp

    /** 8dp —— 挨着的两个小元素 */
    val xs: Dp = 8.dp

    /** 12dp —— 卡片内部的行间距 */
    val sm: Dp = 12.dp

    /** 16dp —— **默认**：页面左右边距、卡片内边距 */
    val md: Dp = 16.dp

    /** 20dp —— 分区之间 */
    val lg: Dp = 20.dp

    /** 24dp —— 大块之间（页面顶部/分组） */
    val xl: Dp = 24.dp

    /** 32dp —— 空状态、大留白 */
    val xxl: Dp = 32.dp
}

object QkRadius {
    /** 4dp —— 小徽标、标签 */
    val badge: Dp = 4.dp

    /** 12dp —— 小控件（输入框里的小块、图片缩略图） */
    val small: Dp = 12.dp

    /** 16dp —— 列表项、输入框、按钮（常规） */
    val medium: Dp = 16.dp

    /** 20dp —— 大卡片 */
    val large: Dp = 20.dp

    /**
     * 28dp —— 卡片（QookiX 的主用值）。
     *
     * ⚠️ 2026-10-27：20 → 28。这是**整套观感升级的起点** ——
     * MD3 baseline 的卡片是 12–16dp，看起来"很规矩但没性格"；
     * M3 Expressive 把形状本身当表达手段，主体卡片直接用 28dp。
     *
     * 因为所有页面都走 `MaterialTheme.shapes`，改这一处全站生效。
     */
    val card: Dp = 28.dp

    /** 32dp —— 底部弹层 / 大容器 */
    val sheet: Dp = 32.dp

    /** 999dp —— 胶囊（按钮、chips、Tab 指示条） */
    val pill: Dp = 999.dp
}

/**
 * 可点击且有按压回弹的 modifier。
 *
 * 替代裸 `Modifier.clickable {}`：后者只有 ripple、按下去"没有实体感"。
 * 用法：`Card(modifier = Modifier.qkPressable { onClick() }) { … }`
 *
 * 注意：内部用 `composed {}` 是因为要 `remember` 交互源；它不影响重组性能
 * （`clickable`/`graphicsLayer` 都在同一层）。
 */
fun Modifier.qkPressable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) QookiX.PRESS_SCALE else 1f,
        animationSpec = QookiX.pressSpec(),
        label = "qkPress",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = androidx.compose.foundation.LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}
