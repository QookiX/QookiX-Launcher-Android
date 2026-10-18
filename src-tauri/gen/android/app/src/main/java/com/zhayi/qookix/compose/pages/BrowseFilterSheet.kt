package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 内容中心的**筛选面板**（底部弹出）。
 *
 * ⚠️ 2026-10-17 新增（用户反馈「筛选和搜索框占的地方太多了」）。
 *
 * 改之前：搜索框 + 两排 chips（类型 5 个、加载器 6 个）常驻在顶部，
 * 横屏可用高度只有 384dp，光这两排就吃掉 **约 200dp** —— 内容列表只剩一半，
 * 用户抱怨"看不见东西"。
 *
 * 改之后：顶部只留**一行**（搜索框 + 筛选按钮 + 排序），
 * 筛选条件点按钮才在底部面板里展开。省下约 150dp 给列表。
 *
 * 为什么用 BottomSheet 而不是 DropdownMenu：
 *   - 筛选是「多组条件 + 需要看清当前值」，不是一次性选择
 *   - 底部面板能放两排 chip 而不挤，还能放「重置」按钮
 *   - 手机单手操作友好（拇指够得到底部）
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    val sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
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

            // ── 内容类型 ─────────────────────────────────────────────
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

            // ── 加载器（插件不显示）──────────────────────────────────
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
