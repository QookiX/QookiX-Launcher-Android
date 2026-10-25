package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhayi.qookix.compose.motion.QookiX
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 下载页。
 *
 * 对应旧界面 views/DownloadsView.vue + stores/tasks.ts。
 *
 * 保持等价的行为：
 *   - 「进行中 / 已完成」两个 tab
 *   - 每项显示阶段、进度条、文件数、速度
 *   - 可取消（调 Rust 的 cancel_install）
 *   - 已完成项可清空
 *
 * 数据来源：Rust 通过 Tauri 事件推流
 *   install://progress / download://progress（见 stores/tasks.ts）
 * 阶段 2 建好事件订阅后接上；本阶段先用状态模型把 UI 做出来。
 */
@Composable
fun DownloadsPage(
    state: DownloadsUiState,
    onTabChange: (DownloadsTab) -> Unit,
    onCancel: (Long) -> Unit,
    /**
     * 清空已完成的任务。
     *
     * ⚠️ 这个回调以前**声明了但函数体里零引用**（`PageHost` 一直在传，
     * 于是「有参数、没按钮」）—— 用户现象是「已完成的任务越积越长，
     * 没法清理」。现在接在「已完成」tab 右上角的清空按钮上。
     */
    onClearFinished: () -> Unit,
) {
    // 当前 tab 的列表先取出来：「已完成」tab 顶部工具条要知道有没有东西可清
    val list = when (state.tab) {
        DownloadsTab.Active -> state.active
        DownloadsTab.Finished -> state.finished
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = if (state.tab == DownloadsTab.Active) 0 else 1) {
            Tab(
                selected = state.tab == DownloadsTab.Active,
                onClick = { onTabChange(DownloadsTab.Active) },
                text = { Text(stringResource(R.string.download_tab_active)) },
            )
            Tab(
                selected = state.tab == DownloadsTab.Finished,
                onClick = { onTabChange(DownloadsTab.Finished) },
                text = { Text(stringResource(R.string.download_tab_finished)) },
            )
        }

        // ── 「已完成」tab 的清空入口 ────────────────────────────────────
        //
        // 只在「已完成」tab 出现：进行中的任务对应的是「取消」（在卡片上），
        // 放到这里会被误点成一键清空。
        //
        // ⚠️ 它必须画在**空列表分支之外**：清空之后列表就空了，
        // 按钮若跟着列表一起消失，用户会看到「按钮没了」而以为出错。
        // 所以按钮挂在列表之上（列表为空时它依然在，只是被禁用）。
        if (state.tab == DownloadsTab.Finished) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = QkSpacing.md, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onClearFinished,
                    // 没有已完成任务时禁用（点了也没意义）
                    enabled = list.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.download_clear_finished))
                }
            }
        }

        if (list.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (state.tab == DownloadsTab.Active) stringResource(R.string.download_empty_active)
                    else stringResource(R.string.download_empty_finished),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
        ) {
            items(list, key = { it.id }) { t ->
                TaskCard(t, onCancel = { onCancel(t.id) })
            }
        }
    }
}

@Composable
private fun TaskCard(t: TaskUi, onCancel: () -> Unit) {
    // 展开态（B2）：旧界面的 DownloadsView 点任务行能看分文件/分项进度，
    // 迁移时丢了 —— 下大整合包时只有一个总进度条，看不出卡在哪一步。
    var expanded by remember { mutableStateOf(false) }
    val hasDetail = t.stage.isNotBlank() || t.bytesTotal > 0 || t.fileTotal > 0 || t.stepTotal > 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(QkSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = t.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!t.finished) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.download_cancel)) }
                }
                if (hasDetail) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = stringResource(R.string.download_detail_toggle),
                        )
                    }
                }
            }

            if (t.message.isNotBlank()) {
                Spacer(Modifier.height(QkSpacing.xxs))
                Text(
                    text = t.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(QkSpacing.sm))
            // 进度条平滑跟随：事件是离散推送的（几百 ms 一次），
            // 直接绑定会一格一格跳；animateFloatAsState 让它连续滑动
            val animatedFraction by animateFloatAsState(
                targetValue = t.fraction,
                animationSpec = tween(
                    durationMillis = QookiX.MEDIUM_MS,
                    easing = QookiX.EaseOut,
                ),
                label = "dlProgress",
            )
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            )


            // ── 停滞提示 ────────────────────────────────────────────────
            //
            // Rust 侧的下载器**没有读超时** —— 连接建立后传输中断时，
            // 请求会一直挂着，重试逻辑不触发、进度条不动、也不报错。
            // 实测（2026-10-14）：26.3.jar 卡在 39.6/151.4 MB 超过 3 分钟，
            // 用户完全不知道发生了什么、也不知道能做什么。
            //
            // 前端能做的：速度归零超过阈值时明确告知 + 给出可操作建议。
            if (!t.finished && t.stalled) {
                Spacer(Modifier.height(QkSpacing.xs))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = stringResource(R.string.download_stalled_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = QkSpacing.sm, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(QkSpacing.xs))
            Row {
                Text(
                    text = t.progressText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                if (!t.finished && t.speedText.isNotBlank()) {
                    Text(
                        text = t.speedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // ── 展开明细 ────────────────────────────────────────────────
            if (expanded) {
                Spacer(Modifier.height(QkSpacing.xs))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(QkSpacing.sm),
                        verticalArrangement = Arrangement.spacedBy(QkSpacing.xxs),
                    ) {
                        if (t.stage.isNotBlank()) {
                            TaskDetailLine(
                                stringResource(R.string.download_detail_stage),
                                t.stage,
                            )
                        }
                        if (t.bytesTotal > 0) {
                            TaskDetailLine(
                                stringResource(R.string.download_detail_bytes),
                                "${fmtBytes(t.bytesDone)} / ${fmtBytes(t.bytesTotal)}",
                            )
                        }
                        if (t.fileTotal > 0) {
                            TaskDetailLine(
                                stringResource(R.string.download_detail_files),
                                "${t.fileDone} / ${t.fileTotal}",
                            )
                        }
                        if (t.stepTotal > 0) {
                            TaskDetailLine(
                                stringResource(R.string.download_detail_steps),
                                "${t.stepDone} / ${t.stepTotal}",
                            )
                        }
                        if (t.message.isNotBlank()) {
                            TaskDetailLine(
                                stringResource(R.string.download_detail_current),
                                t.message,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 展开明细里的一行「标签 + 值」。 */
@Composable
private fun TaskDetailLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

enum class DownloadsTab { Active, Finished }

/** 单个任务（UI 模型，对应旧 stores/tasks.ts 的 TaskEntry 关键字段）。 */
data class TaskUi(
    val id: Long,
    val title: String,
    val message: String,
    val fraction: Float,
    val progressText: String,
    val speedText: String,
    /**
     * 已停滞（速度归零超过阈值）。
     *
     * Rust 下载器**没有读超时** —— 连接建立后传输中断时请求一直挂着，
     * 重试逻辑不触发、进度条不动、也不报错。前端据此给用户明确提示。
     */
    val stalled: Boolean = false,
    val finished: Boolean,
    // ── 展开详情（B2）────────────────────────────────────────────────
    /** 当前阶段 id（download / verify / extract / done …）。 */
    val stage: String = "",
    /** 字节进度（该阶段不按字节时都是 0）。 */
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    /** 文件计数进度（下载阶段：已完成 / 总数）。 */
    val fileDone: Int = 0,
    val fileTotal: Int = 0,
    /** 步骤计数进度（安装阶段）。 */
    val stepDone: Int = 0,
    val stepTotal: Int = 0,
)

data class DownloadsUiState(
    val tab: DownloadsTab = DownloadsTab.Active,
    val active: List<TaskUi> = emptyList(),
    val finished: List<TaskUi> = emptyList(),
)



