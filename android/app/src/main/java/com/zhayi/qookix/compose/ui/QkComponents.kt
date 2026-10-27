package com.zhayi.qookix.compose.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.theme.QkRadius
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.theme.qkPressable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import com.zhayi.qookix.compose.theme.QkEasing
import kotlinx.coroutines.delay

/**
 * 通用组件层（配合 `theme/Tokens.kt` 使用）。
 *
 * ## 为什么要有这一层
 *
 * 全量扫描（91 kt / 39k 行）时发现：颜色和字体已经统一，但「卡片 / 列表行 /
 * 分区标题 / 空状态 / 加载中」在几十个页面里各写一遍 —— 每个地方自己定圆角、
 * 内边距、字重，于是观感参差，改一处要翻十处。
 *
 * 这里的每个组件都**只引用 token**（`QkRadius` / `QkSpacing` / `qkPressable`），
 * 所以调整观感只需要改 token，不必动页面。
 *
 * ## 迁移约定
 * - 新代码：直接用这里的组件，不要再手写 `Surface(shape = RoundedCornerShape(...))`；
 * - 老代码：改到某个页面时顺手替换（不搞一次性大重构，避免"全 App 重排"的风险）；
 * - 需要特殊外观的：用 `modifier` 覆盖，不要在页面里重新定义圆角/边距。
 */

/**
 * 标准卡片。
 *
 * @param onClick 传了就带**按压回弹**（`qkPressable`）并可点击；不传就是纯展示容器。
 * @param contentPadding 默认 `QkSpacing.md`（16dp），需要更紧凑时传 `QkSpacing.sm`。
 */
@Composable
fun QkCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(QkSpacing.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickMod = if (onClick != null) Modifier.qkPressable { onClick() } else Modifier
    Surface(
        modifier = modifier.then(clickMod),
        shape = RoundedCornerShape(QkRadius.card),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 分区标题（带右侧可选操作）。替代各页面手写的
 * `Row { Text(title); Spacer(weight); TextButton(...) }`。
 */
@Composable
fun QkSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = QkSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * 列表行：可选前置图标 + 标题/副标题 + 可选尾部内容。
 *
 * 这是重复度最高的一个（设置项、实例行、模组行、房间行…），
 * 统一后行高/间距/字重就一致了。
 */
@Composable
fun QkListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val clickMod = if (onClick != null) Modifier.qkPressable { onClick() } else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickMod)
            .padding(horizontal = QkSpacing.md, vertical = QkSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(QkSpacing.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(QkSpacing.sm))
            trailing()
        }
    }
}

/** 空状态：一句话 + 可选补充说明。统一居中与留白。 */
@Composable
fun QkEmpty(
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(QkSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hint.isNullOrBlank()) {
            Spacer(Modifier.height(QkSpacing.xs))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 加载中（带一句说明）。页面级 loading 统一用它。 */
@Composable
fun QkBusy(
    label: String = "加载中…",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(QkSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Spacer(Modifier.height(QkSpacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表项入场：淡入 + 上浮 12dp，按位置依次错开。
 *
 * 为什么需要：`LazyColumn` 会一次性把所有可见 item 画出来，视觉上是「啪一下
 * 全出现」，没有「内容正在进来」的过程 —— 列表再好看也会显得生硬。
 * 按 index 错开 40ms 之后才有层次。
 *
 * 刻意**不用**回弹曲线：列表是高频出现的，回弹会变成噪音。这里用 [QkEasing.Standard]
 *（单调减速），只在页面转场/弹层这种「一次性」动作上用 SoftBounce/Jelly。
 *
 * @param index 在列表中的位置（算延迟用）
 * @param maxStagger 最多错开几档 —— 长列表末尾不该等几秒才出现
 */
/**
 * 列表入场是否已经播过（进程内有效）。
 *
 * 为什么要这个：`PageHost` 用 `when (dest)` 切页，每次回来 `InstancesPage`
 * 都会**重新组合**，于是整列卡片又播一遍入场 —— 既多跑一轮动画（掉帧），
 * 又显得"每次进列表都在加载"。真实需求是：**本次运行第一次看到列表时播**。
 *
 * 用普通的顶层 var 而不是 State：它只在组合期被读一次（`remember { … }`），
 * 不需要触发重组。
 */
private var introPlayed = false

@Composable
fun QkAppear(
    index: Int = 0,
    maxStagger: Int = 5,
    content: @Composable () -> Unit,
) {
    val alreadyPlayed = remember { introPlayed }
    var visible by remember { mutableStateOf(alreadyPlayed) }
    LaunchedEffect(Unit) {
        if (!alreadyPlayed) {
            // 错开 32ms：40ms 在 26 个卡片的列表上会把入场拖到 1 秒以上，
            // 而用户感知不到那么细的间隔，只会在意"列表还没出来"。
            val d = index.coerceIn(0, maxStagger) * 32L
            if (d > 0) delay(d)
            visible = true
        }
        // 无论是否播过，都标记「本进程已播」—— 滚动时新组合出来的 item
        // 会读到 true，直接显示，不会在滚动中途闪一下。
        introPlayed = true
    }
    // ⚠️ 只动 alpha，不做位移。
    //
    // 原来这里还驱动 translationY（12dp → 0）。真机反馈"切页时掉帧"——
    // 每个卡片两个动画，26 个卡片就是 52 个同时在跑，而切页本身还有一层转场。
    // 位移的收益（多一点点"浮上来"）远小于它带来的掉帧，所以只留 alpha。
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = QkEasing.Standard),
        label = "qkAppearAlpha",
    )
    Box(Modifier.graphicsLayer { this.alpha = alpha }) {
        content()
    }
}
