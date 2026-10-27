package com.zhayi.qookix.compose.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.motion.QookiX
import com.zhayi.qookix.compose.theme.QkRadius
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 占位层：加载骨架 + 错误条。
 *
 * ## 为什么需要
 *
 * 「加载中」现在各页面写法不一：有的转圈、有的直接空白、有的先画空列表再跳。
 * 空白是最糟的 —— 用户分不清「在加载」和「没有数据」。
 *
 * 骨架屏的好处是**占位即预告**：先画出内容的形状（灰块），数据到了原地替换，
 * 视觉上不发生布局跳动。
 *
 * 错误条同理：以前有些页面把错误塞进一句 Snackbar（几秒就消失），
 * 用户回头再看就不知道「刚才为什么失败」。
 */

/**
 * 骨架块：圆角灰条 + 呼吸动画。
 *
 * @param width 不传则撑满可用宽度
 */
@Composable
fun QkSkeleton(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    width: Dp? = null,
) {
    val transition = rememberInfiniteTransition(label = "qkSkeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = QookiX.EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "qkSkeletonAlpha",
    )
    Box(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .height(height)
            .clip(RoundedCornerShape(QkRadius.small))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
    )
}

/**
 * 卡片骨架：一个图标块 + 两行文字，形状与 [QkCard] 一致。
 *
 * 用于列表/卡片区首次加载 —— 比一个居中的转圈更"稳"，因为它先把版式占住。
 */
@Composable
fun QkCardSkeleton(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(QkRadius.card),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(QkSpacing.md), verticalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
            QkSkeleton(height = 48.dp, width = 48.dp)
            QkSkeleton(height = 18.dp)
            QkSkeleton(height = 12.dp, width = 120.dp)
        }
    }
}

/**
 * 错误条：图标 + 原因 + 可选重试。
 *
 * 与「空状态」的区别：空态是**正常结果**（没有内容就该空着），
 * 错误条是**异常**，必须给出原因和出路（重试/去设置）。
 */
@Composable
fun QkErrorBar(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(QkRadius.medium),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = QkSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(QkSpacing.xs))
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.width(QkSpacing.xs))
                QkButton(
                    text = actionText,
                    onClick = onAction,
                    tone = QkButtonTone.Text,
                    modifier = Modifier.height(36.dp),
                )
            }
        }
    }
}
