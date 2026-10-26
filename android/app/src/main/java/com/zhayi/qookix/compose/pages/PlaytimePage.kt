package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import com.zhayi.qookix.compose.motion.QookiX
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.compose.ui.SkeletonList

/**
 * 游戏时长统计。
 *
 * 对应旧界面 `PlaytimeCard.vue`（183 行）。
 *
 * ── 数据来源 ────────────────────────────────────────────────────────────
 * Rust 侧 `playtime_stats` 扫 accounts/instances 目录里的游玩记录，
 * 返回三块：
 *   - `totalSeconds`  总时长
 *   - `byInstance[]`  每个实例的时长（含最后游玩时间）
 *   - `byDay[]`       每天的时长（画柱状图）
 *
 * ⚠️ 字段是 **camelCase** —— `PlaytimeStats` 带
 * `#[serde(rename_all = "camelCase")]`（结构体序列化，不是 json! 手写，
 * 但这个结构体显式声明了 camelCase）。
 *
 * ── 界面（完全重做）─────────────────────────────────────────────────────
 * 旧版是一张卡片里塞全部。这里改成：
 *   ┌──────────────────────────────────────────┐
 *   │        总时长 128 小时 30 分钟            │  ← 大数字，门面感
 *   ├──────────────────────────────────────────┤
 *   │  近 30 天                                 │
 *   │  ▁▃▅█▇▅▃▁▂▄▆█▇▅▃▁▂▄▆  ← 手绘柱状图        │
 *   ├──────────────────────────────────────────┤
 *   │  按实例                                   │
 *   │  26.3      80 小时                        │
 *   │  1.20.1    48 小时                        │
 *   └──────────────────────────────────────────┘
 *
 * **为什么自己画柱状图而不引图表库**：这里只需要「一组数值 → 高度」，
 * 一二十行 Canvas 就够了；引一个图表库要为它操心 Kotlin 版本兼容
 * （本项目已经因为库的 Kotlin 元数据版本踩过坑）。
 */
@Composable
fun PlaytimePage(
    state: PlaytimeUiState,
    onRefresh: () -> Unit,
) {
    // ⚠️ 这里**不要**再 `LaunchedEffect(state) { onRefresh() }`（2026-10-26 审查 P1-12）：
    // key 是整个 state，而每次 reload 都会 `copy(loading = true)` 产生新实例 →
    // effect 重启 → 再 reload，变成永不停止的刷新循环（骨架/禁用态一直抖）。
    // 首次加载与进入页面时的刷新由 `PlaytimeRoute` 的 `LaunchedEffect(ready)` 负责，
    // 这里是纯展示层 + 手动「刷新」按钮。

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.playtime_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(onClick = onRefresh, enabled = !state.loading) {
                Text(stringResource(R.string.playtime_refresh))
            }
        }

        when {
            state.loading && state.totalSeconds == 0L ->
                Column(modifier = Modifier.padding(QkSpacing.lg)) {
                    SkeletonList(rows = 3, rowHeight = 80.dp)
                }

            // 读失败 ≠ 没玩过：`error` 之前根本没人渲染，读失败时整页显示
            // 「还没有游玩记录」+ 全都 0 分钟 —— 和「统计坏了」看起来一样。
            state.totalSeconds == 0L && state.error != null -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(QkSpacing.sm))
                    androidx.compose.material3.TextButton(onClick = onRefresh) {
                        Text(stringResource(R.string.first_run_retry))
                    }
                }
            }

            state.totalSeconds == 0L -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.playtime_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = stringResource(R.string.playtime_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.md),
            ) {
                // ── 总时长（门面）────────────────────────────────────
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(QkSpacing.xl),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.playtime_total_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.height(QkSpacing.xs))
                            // 大号等宽数字 —— 时长是本页主角，值得占满视觉重量
                            Text(
                                // 数字滚动：从 0 平滑滚到当前时长，
                // 比冷冰冰地直接显示更有"统计出来了"的仪式感
                             // 数字滚动：从 0 平滑滚到当前时长，
                             // 比冷冰冰地直接显示更有"统计出来了"的仪式感。
                             // 用 Float 版本 —— animateLongAsState 在本版本
                             // Compose 里不存在（import 直接 Unresolved），
                             // 秒数在 Float 里精度足够（< 2^24 秒 ≈ 194 天）
                             text = fmtDuration(
                                 LocalContext.current,
                                 animateFloatAsState(
                                     targetValue = state.totalSeconds.toFloat(),
                                     animationSpec = tween(
                                         durationMillis = QookiX.XLONG_MS,
                                         easing = QookiX.EaseOut,
                                     ),
                                     label = "playtimeTotal",
                                 ).value.toLong(),
                             ),
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                // ── 每日柱状图 ───────────────────────────────────────
                if (state.byDay.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(QkSpacing.md)) {
                                Text(
                                    text = stringResource(
                                        R.string.playtime_recent_days,
                                        state.byDay.size,
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(QkSpacing.sm))
                                DailyBars(state.byDay.map { it.seconds })
                                // 柱状图没有刻度：标一句单位，否则柱顶那个数是秒还是分钟只能猜
                                Spacer(Modifier.height(QkSpacing.xxs))
                                Text(
                                    text = stringResource(R.string.playtime_bars_unit),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                }

                // ── 按实例 ───────────────────────────────────────────
                if (state.byInstance.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.playtime_by_instance),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    items(state.byInstance, key = { it.id }) { inst ->
                        InstancePlaytimeRow(inst)
                    }
                }
            }
        }
    }
}

/** 一排柱子（纯 Canvas 绘制）。 */
@Composable
private fun DailyBars(values: List<Long>) {
    if (values.isEmpty()) return
    val maxV = values.max().coerceAtLeast(1L)

    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp),
    ) {
        val n = values.size
        // 柱宽 + 间隙按数量自适应：柱子多了要变窄，否则会挤在一起
        val gap = size.width / n * 0.25f
        val barW = (size.width - gap * (n - 1)) / n
        val barColor = androidx.compose.ui.graphics.Color(
            // 用主题色的主色调，但 Canvas 里拿不到 MaterialTheme，
            // 所以由调用方在外部传入更合适 —— 这里用固定色值保底
            0xFFD0BCFF.toInt()
        )

        values.forEachIndexed { i, v ->
            val h = (v.toFloat() / maxV) * size.height
            val x = i * (barW + gap)
            drawRoundRect(
                color = barColor,
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(barW, h.coerceAtLeast(2f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 3f),
            )
        }
    }
}

@Composable
private fun InstancePlaytimeRow(inst: InstancePlaytimeUi) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
        Row(
            modifier = Modifier.padding(QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = inst.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ago = fmtRelative(ctx, inst.lastPlayed)
                if (ago.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.playtime_last_played, ago),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = fmtDuration(LocalContext.current, inst.seconds),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 把秒数格式化成「128 小时 30 分钟」。
 *
 * 规则（与旧实现对齐）：
 *   - 不足 1 分钟 → "不到 1 分钟"
 *   - 不足 1 小时 → "45 分钟"
 *   - 超过 1 小时 → "128 小时 30 分钟"（分钟为 0 时省略）
 *
 * ⚠️ i18n：本函数不是 @Composable，拿不到 `stringResource`，
 * 所以由调用方把 [Context] 传进来（`LocalContext.current`），
 * 在函数内部用 `context.getString(...)` 取资源。
 */
fun fmtDuration(context: Context, seconds: Long): String {
    if (seconds <= 0) return context.getString(R.string.playtime_duration_zero)
    val totalMin = seconds / 60
    if (totalMin < 1) return context.getString(R.string.playtime_duration_less_minute)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0L -> context.getString(R.string.playtime_duration_minutes, m)
        m == 0L -> context.getString(R.string.playtime_duration_hours, h)
        else -> context.getString(R.string.playtime_duration_hours_minutes, h, m)
    }
}

/** 单实例时长（UI 模型）。 */
data class InstancePlaytimeUi(
    val id: String,
    val name: String,
    val seconds: Long,
    val lastPlayed: Long = 0L,
)

/** 每日时长（UI 模型）。 */
data class DailyPlaytimeUi(
    val day: Long,
    val seconds: Long,
)

/** 游玩统计页状态。 */
data class PlaytimeUiState(
    val loading: Boolean = false,
    val totalSeconds: Long = 0L,
    val byInstance: List<InstancePlaytimeUi> = emptyList(),
    val byDay: List<DailyPlaytimeUi> = emptyList(),
    val error: String? = null,
)




