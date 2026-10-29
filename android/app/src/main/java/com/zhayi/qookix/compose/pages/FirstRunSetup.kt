package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import org.json.JSONObject

/**
 * 首次运行引导：自动补齐缺失的推荐插件。
 *
 * 对应旧 UI `components/FirstRunSetup.vue`。
 *
 * ── 为什么需要 ──────────────────────────────────────────────────────────
 * 渲染器（MobileGlues）、翻译服务等**不在 APK 里**（插件分发）。
 * 首次安装的用户点启动会崩在 `libmobileglues.so 找不到` ——
 * 而他不知道要先去「设置 → 插件」装东西。
 *
 * 旧 UI 的做法：**启动器起来后检查一次**，缺就自动弹窗并开始下载 ——
 * 用户什么都不用做，装完即可玩。
 *
 * ── 交互流程（与旧 UI 一致）────────────────────────────────────────────
 *   1. 检查 `get_plugin_setup_status`
 *   2. `needed && !dismissed` → 显示弹层并**自动开始**下载
 *   3. 逐项显示进度（done/failed 标记）
 *   4. 装完重查：还缺的留下（方便重试），全齐则自动消失
 *   5. 用户可点「稍后」（dismiss —— 7 天内不再打扰）
 */
data class FirstRunUiState(
    val visible: Boolean = false,
    val checking: Boolean = false,
    val running: Boolean = false,
    val finished: Boolean = false,
    /** 缺失插件的名字列表。 */
    val planned: List<String> = emptyList(),
    val doneIds: List<String> = emptyList(),
    val failedIds: List<String> = emptyList(),
    /**
     * 失败项的具体原因，形如「MobileGlues：下载失败：…」。
     *
     * 以前只标一个红色「失败」，用户不知道是网络、校验还是解压出问题。
     */
    val failureDetails: List<String> = emptyList(),
    val totalSize: Long = 0,
    /** 清单拿不到时的说明（首启离线）。 */
    val error: String? = null,
)

/**
 * 消化 `install_recommended_plugins` 的返回值，算出「哪些装好了、哪些失败、为什么」。
 *
 * ── 为什么需要这个函数 ──────────────────────────────────────────────────
 * 后端 `SetupResult.failed` 是**字符串数组**（`plugin.rs::install_recommended`
 * 里 `failed.push(format!("{}：{e}", item.name))`）。
 * 以前调用处按**对象数组**读 `optJSONObject(j).optString("name")` ——
 * 对字符串元素永远返回 null，于是 `failedNames` 恒为空：
 * 每一项都被判成「成功」、失败被彻底吞掉、进度卡在 `0/5` 后弹窗自行消失。
 *
 * 两处调用（首启自动安装 / 「重试」按钮）必须走同一份解析，别再各写一遍。
 */
fun FirstRunUiState.afterInstall(planned: List<String>, result: Result<String>): FirstRunUiState {
    // 整批失败（清单都拉不到）：全部标失败并给出原因，别谎报「装好了」
    result.exceptionOrNull()?.let { e ->
        return copy(
            running = false,
            finished = true,
            doneIds = emptyList(),
            failedIds = planned,
            failureDetails = listOf(e.message ?: "未知错误"),
        )
    }

    val reasons = runCatching {
        val arr = JSONObject(result.getOrNull() ?: "{}").optJSONArray("failed")
        (0 until (arr?.length() ?: 0))
            .map { arr!!.optString(it) }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    // 失败项形如「名字：原因」，按前缀对回 planned 里的名字
    fun hit(name: String) = reasons.any { it == name || it.substringBefore("：") == name }
    return copy(
        running = false,
        finished = true,
        doneIds = planned.filterNot { hit(it) },
        failedIds = planned.filter { hit(it) },
        failureDetails = reasons,
    )
}

@Composable
fun FirstRunSetup(
    state: FirstRunUiState,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                // ⚠️ 2026-10-29（外部审计 B23 核实成立）：原来只画了一层半透明背景，
                // 没有任何事件拦截 —— 安装进行中用户能点到下面的界面（切 tab、
                // 点启动游戏），而这不是普通弹窗，是阻塞式的首次准备。
                // 这里吞掉落到遮罩上的点击（子元素先拿到事件，卡片里的按钮照常可用）。
                .pointerInput(Unit) { detectTapGestures { } },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .padding(QkSpacing.xl),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .padding(QkSpacing.xl)
                        // ⚠️ 2026-10-29（审计 B23）：横屏 + 失败项多的时候内容会超出
                        // 屏幕、底部的按钮被挤出可见区（而且整块不可滚动，用户没救）。
                        // 列表常有 5~8 项，这里必须能滚。
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = stringResource(R.string.first_run_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Text(
                        text = when {
                            state.error != null -> state.error
                            state.running -> stringResource(R.string.first_run_desc_running)
                            state.failedIds.isNotEmpty() ->
                                stringResource(R.string.first_run_partial, state.failedIds.size)
                            state.finished -> stringResource(R.string.first_run_done)
                            else -> stringResource(R.string.first_run_checking)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (state.planned.isNotEmpty()) {
                        Spacer(Modifier.height(QkSpacing.md))
                        // 逐项列出：装完的打勾、失败的标红、进行中的转圈
                        Column(verticalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                            state.planned.forEach { name ->
                                val st = when {
                                    state.doneIds.contains(name) -> ItemState.Done
                                    state.failedIds.contains(name) -> ItemState.Failed
                                    state.running -> ItemState.Running
                                    else -> ItemState.Pending
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    when (st) {
                                        ItemState.Done -> Text(
                                            text = "✓",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        ItemState.Failed -> Text(
                                            text = "✕",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        ItemState.Running -> CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        ItemState.Pending -> Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                    MaterialTheme.shapes.small,
                                                ),
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (st == ItemState.Failed) {
                                        Text(
                                            text = stringResource(R.string.first_run_failed),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 失败原因逐条列出（后端给的是「名字：原因」，见 afterInstall）
                    if (!state.running && state.failureDetails.isNotEmpty()) {
                        Spacer(Modifier.height(QkSpacing.sm))
                        Column(verticalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                            state.failureDetails.forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    if (state.running) {
                        Spacer(Modifier.height(QkSpacing.sm))
                        // 总进度：done 数 / 总数（由 plugin://progress 的 done 事件逐项推进）
                        val done = state.doneIds.size
                        val total = state.planned.size
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { done.toFloat() / total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(QkSpacing.xs))
                            Text(
                                text = "$done / $total",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }

                    Spacer(Modifier.height(QkSpacing.lg))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 「稍后」：dismiss 后 7 天内不再打扰（后端记 dismissed）
                        TextButton(onClick = onDismiss, enabled = !state.running) {
                            Text(
                                if (state.running) {
                                    stringResource(R.string.first_run_downloading)
                                } else {
                                    stringResource(R.string.first_run_later)
                                },
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 有失败项、或清单根本拉不到（离线）：给「重试」而不是关掉
                        if (!state.running && (state.failedIds.isNotEmpty() || state.error != null)) {
                            Button(onClick = onStart) { Text(stringResource(R.string.first_run_retry)) }
                        }
                    }
                }
            }
        }
    }
}

private enum class ItemState { Done, Failed, Running, Pending }
