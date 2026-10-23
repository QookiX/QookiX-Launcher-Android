package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkRadius

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zhayi.qookix.R

/**
 * 内容中心的**筛选面板**（底部弹出）。
 *
 * ⚠️ 2026-10-17 新增（用户反馈「筛选和搜索框占的地方太多了」）。
 *
 * 改之前：搜索框 + 两排 chips（类型 5 个、加载器 6 个）常驻在顶部，
 * 横屏可用高度只有 384dp，光这两排就吃掉 **约 200dp** —— 内容列表只剩一半。
 *
 * 改之后：顶部只留**一行**（搜索框 + 筛选按钮 + 排序），
 * 筛选条件点按钮才在底部面板里展开。
 *
 * ── 2026-10-18 换实现（第三次返工，这次是对的）──────────────────────
 *
 * 用户反馈「点开筛选后系统状态栏会弹出来，导致下面所有元素都动一下」，
 * 但明确要求**保留底部弹出形态**（不要改成居中对话框）。
 *
 * 三次尝试：
 *   ① 改成居中 AlertDialog → 状态栏不动了，但形态变了（用户否决）
 *   ② 给 ModalBottomSheet 传 windowInsets=0 → 当前 Material3 版本没这个参数
 *   ③ 主 Activity 沉浸式 → 主界面生效，但 ModalBottomSheet 在**独立窗口**里，
 *      那个窗口自己会把状态栏顶出来（真机截图证实：弹窗时时间电量又出现了）
 *
 * 最终方案：用 `Dialog` 自己搭底部抽屉，并让窗口**不避开系统栏**
 * （`decorFitsSystemWindows = false`）—— 窗口不再为状态栏让位，
 * 状态栏就不会被顶出来，下方布局也不会跳。
 *
 * ⚠️ 布局上有两个必须遵守的约束（都踩过崩溃）：
 *   1. 外层 `Box(fillMaxSize)` 给的是**无限高度约束**，
 *      所以里面任何 `verticalScroll` 都必须先 `heightIn(max = …)` 限高，
 *      否则抛 `IllegalStateException: Vertically scrollable component was
 *      measured with an infinity maximum height constraints`。
 *   2. 不要再套第二层 `Column(verticalScroll)` —— 一层就够，套两层同样崩。
 */
@Composable
fun BrowseFilterSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    type: String,
    onTypeChange: (String) -> Unit,
    loader: String,
    onLoaderChange: (String) -> Unit,
    /** 插件类型不显示加载器（Hangar 没有加载器维度）。 */
    hideLoader: Boolean,
) {
    if (!visible) return

    // 抽屉高度按屏幕高度的 85% 算（不能用固定 dp：横屏只有 384dp 高，
    // 写 520dp 等于全屏，看起来像整页而不是抽屉）。
    val screenHeightDp = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val sheetMaxHeight = (screenHeightDp * 0.85f).dp

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 抽屉要通栏（默认宽度会留边距）
            usePlatformDefaultWidth = false,
            // 关键：不避开系统栏，否则窗口会为状态栏让位 → 状态栏被顶出来
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 点空白处关闭（等价于 ModalBottomSheet 的遮罩）
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 限高：外层 Box 是无限约束，不限高的话里面的
                    // verticalScroll 会直接抛异常（真机实测崩溃）。
                    .heightIn(max = sheetMaxHeight),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 只有这一层 verticalScroll（两层必崩）
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = 28.dp),
                ) {
                    // ── 拖动提示条（视觉上等价于 BottomSheet 的 dragHandle）──
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 12.dp)
                            .width(32.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(QkRadius.badge),
                            ),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.browse_filter),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                onTypeChange(BrowseType.Mod.id)
                                onLoaderChange("")
                            },
                        ) { Text(stringResource(R.string.browse_reset)) }
                    }

                    Spacer(Modifier.height(12.dp))

                    // ── 内容类型 ─────────────────────────────────────────
                    Text(
                        text = stringResource(R.string.browse_content_type),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BrowseType.entries.forEach { t ->
                            FilterChip(
                                selected = type == t.id,
                                onClick = { onTypeChange(t.id) },
                                label = { Text(stringResource(t.labelRes)) },
                            )
                        }
                    }

                    // ── 加载器（插件不显示）──────────────────────────────
                    if (!hideLoader) {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = stringResource(R.string.browse_mod_loader),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            BrowseRepo.LOADERS.forEach { l ->
                                FilterChip(
                                    selected = loader == l.id,
                                    onClick = { onLoaderChange(l.id) },
                                    label = { Text(stringResource(l.labelRes)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    ),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.browse_done)) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 筛选按钮上的**激活条件数量徽标**。
 *
 * 只统计"偏离默认值"的维度：
 *   - 类型不是「模组」→ +1
 *   - 加载器不是「全部」→ +1
 *
 * 为什么不统计搜索词：搜索框本身就显示着关键词，用户看得见；
 * 而且搜索是"主动输入"不是"隐藏条件"，算进徽标会误导。
 */
@Composable
fun FilterBadge(count: Int, content: @Composable () -> Unit) {
    if (count <= 0) {
        content()
    } else {
        BadgedBox(
            badge = {
                Badge { Text(count.toString()) }
            },
        ) { content() }
    }
}

