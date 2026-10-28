package com.zhayi.qookix.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 全 App 统一的 **tab 栏**。
 *
 * ⚠️ 2026-10-17 新增。用户指出：「内容存档按键设置文件日志崩溃这些，
 * 跟设置页的常规插件外观下载，我感觉你用的不是同一套，你这是写的两套」，
 * 以及「服务器的控制台文件插件配置只有四个，在横屏上右边会空刷一下，
 * 全部 UI 都要做响应式布局」。
 *
 * ── 为什么需要这个组件 ──────────────────────────────────────────────
 * MD3 的 `TabRow` 是**等分**的（4 个 tab 就各占 1/4，填满宽度），
 * 但 tab 多时每个太窄、文字被截；
 * `ScrollableTabRow` 是**内容宽度**的（每个 tab 按文字宽度），
 * 但 tab 少时右边留一大片空白。
 *
 * 两者都不是「自适应」。所以这里按**估算总宽度**二选一：
 *   - 每个 tab 至少需要 [minTabWidth]（默认 88dp）才舒服
 *   - `tabCount * minTabWidth <= 可用宽度` → 用 `TabRow` 等分填满
 *   - 否则 → 用 `ScrollableTabRow` 横滚（窄屏放不下时）
 *
 * ── 三处调用点 ────────────────────────────────────────────────────
 * 设置页（8 tab）/ 实例详情（7 tab）/ 服务器详情（4 tab），
 * 现在走同一份实现，样式与行为完全一致。
 *
 * @param labels tab 文字，顺序即显示顺序
 * @param selectedIndex 当前选中索引
 * @param onSelect 点击回调
 */
@Composable
fun QkTabRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 每个 tab 的舒适最小宽度；低于这个值就横滚而非挤压。
     *
     * 2026-10-28 从 88 收到 76：411dp 竖屏下 88 只放得下 4.5 个 tab
     *（设置页有 8 个），76 能放 5.4 个，配合下面的渐隐提示够用了。
     */
    minTabWidth: Int = 76,
) {
    if (labels.isEmpty()) return

    // ⚠️ 用 BoxWithConstraints 拿**实际可用宽度**，而不是 screenWidthDp：
    // 竖屏 + 侧边栏场景下，tab 栏的可用宽度比屏幕窄得多
    //（左边还有 168dp 导航栏），用屏幕宽度会误判成「放得下」。
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val availableDp = maxWidth.value
        val neededDp = labels.size * minTabWidth

        // 容器色统一（三处页面用同一个颜色，避免"又是两套"的观感）
        val container = MaterialTheme.colorScheme.surfaceContainerLow

        if (neededDp <= availableDp) {
            // ── 放得下：等分填满，不留右侧空白 ──
            TabRow(
                selectedTabIndex = selectedIndex,
                containerColor = container,
            ) {
                labels.forEachIndexed { i, label ->
                    QkTab(
                        selected = i == selectedIndex,
                        onClick = { onSelect(i) },
                        label = label,
                    )
                }
            }
        } else {
            // ── 放不下：横滚（不挤压文字）──
            // ⚠️ ScrollableTabRow 没有 minTabWidth 参数（那是 TabRow 的）。
            // 它的每个 tab 宽度由内容决定，这里用固定宽度的 Box 包一层，
            // 保证每个 tab 至少 minTabWidth，避免长标签把短标签挤扁。
            //
            // ⚠️ 2026-10-28（竖屏审计）：8 个 tab 在 411dp 下只能看到 4 个多，
            // 而横滚**没有任何视觉提示** —— 用户根本不知道右边还有「存储」「关于」。
            //
            // 右侧加一层渐隐提示「右边还有」。M3 的 ScrollableTabRow 不暴露
            // scrollState（没法判断是否滚到头），所以这层常显 —— 代价是滚到最右
            // 仍有一点点渐隐，换来的是「一眼能看出还能滑」，值得。
            Box {
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 8.dp,
                    containerColor = container,
                ) {
                    labels.forEachIndexed { i, label ->
                        Box(modifier = Modifier.width(minTabWidth.dp)) {
                            QkTab(
                                selected = i == selectedIndex,
                                onClick = { onSelect(i) },
                                label = label,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(28.dp)
                        // ⚠️ 不能用 fillMaxHeight：这层在 Column 里拿到的是
                        // 「剩余全部高度」的无界约束，会把整条 tab 栏撑成一屏高，
                        // 下面的内容区被挤出去（实测表现：所有 tab 内容全空白）。
                        // Tab 的固定高度是 48dp，照它来。
                        .height(48.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(container.copy(alpha = 0f), container),
                            ),
                        ),
                )
            }
        }
    }
}

/** 单个 tab（纯文字，与三处页面的既有观感一致）。 */
@Composable
private fun QkTab(selected: Boolean, onClick: () -> Unit, label: String) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

