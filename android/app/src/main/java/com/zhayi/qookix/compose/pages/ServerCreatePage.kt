package com.zhayi.qookix.compose.pages

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.*
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkFieldLabel
import com.zhayi.qookix.compose.ui.QkGroupTitle
import com.zhayi.qookix.compose.ui.QkListHeader
import com.zhayi.qookix.compose.ui.QkVersionRow

/**
 * 新建本地服务器 —— **独立页面**。
 *
 * ## 为什么从对话框改成页面（2026-10-18）
 *
 * 用户反馈：「服务器这里这个创建对话框不太合适吧，是不是作为一个页面会好一点，
 * 就像新建实例那里」。
 *
 * 原实现是 `Dialog` + 自定义 Surface，为了在横屏 411dp 高的屏幕上塞下 5 组控件，
 * 写了一堆 `heightIn(max = 0.50f)` 的妥协 —— 表单永远只有半个屏幕高。
 *
 * ## 版本只能从右侧选（2026-10-18 再改）
 *
 * 用户反馈：「不能容用户自己填，只能从右面选择」。
 *
 * 我第一版保留了手填输入框并写了个理由「后端清单可能滞后」——
 * 但那个理由是**我自己想的**，不是需求。手填的真实后果是：
 *   - 用户敲 `1.21.8`，Paper 那条线只有 `1.21.8` 还好，敲 `1.8.9` 就直接 404
 *   - 版本号格式错（`1.21` 而不是 `1.21.4`）在创建时才报错，白等一次网络
 *
 * 所以现在左侧只**显示**选中的版本（只读），点右侧列表才改。
 * 这和「新建实例」的做法一致。
 *
 * ## 组件复用
 *
 * 用户反馈：「你这个 UI 似乎和创建实例那里不是同一套，你不能复用吗」。
 * `CreateInstancePage.kt` 里的 `FieldLabel` / `VersionRow` 是 private，
 * 没法跨文件用，所以之前是照着重写一遍，行高字号有细微差异。
 * 现在统一走 `ui/CreatePageKit.kt` 的 `QkFieldLabel` / `QkVersionRow` / `QkListHeader`。
 */

/** 新建服务器表单状态。 */
data class ServerCreateUiState(
    val name: String = "",
    val core: String = ServerCore.Paper.id,
    /**
     * 选中的版本。
     *
     * ⚠️ **只能由右侧列表设置**，不提供手填入口（见文件头注释）。
     */
    val version: String = "",
    /** 版本清单（按核心类型不同来源也不同）。 */
    val versions: List<String> = emptyList(),
    val loadingVersions: Boolean = true,
    val versionsError: String? = null,
    val creating: Boolean = false,
    val createError: String? = null,
    internal val versionsGeneration: Long = 0,
) {
    internal val versionsRequest get() = ServerVersionsRequest(core, versionsGeneration)
    internal val canCreate get() = !creating && !loadingVersions && versionsError == null &&
        name.isNotBlank() && version.isNotBlank() && version in versions

    internal fun reloadVersions(core: String = this.core) = copy(
        core = core,
        version = "",
        versions = emptyList(),
        loadingVersions = true,
        versionsError = null,
        versionsGeneration = versionsGeneration + 1,
    )

    internal fun finishVersions(
        request: ServerVersionsRequest,
        result: Result<List<String>>,
        formatError: (Throwable) -> String,
    ): ServerCreateUiState {
        if (request != versionsRequest || !loadingVersions) return this
        return result.fold(
            onSuccess = { copy(loadingVersions = false, versions = it) },
            onFailure = { copy(loadingVersions = false, versionsError = formatError(it)) },
        )
    }
}

internal data class ServerVersionsRequest(val core: String, val generation: Long)

/**
 * 新建服务器页面。
 *
 * @param state 表单状态（由 [ServerCreateRoute] 持有）
 * @param onNameChange 名称变化
 * @param onCoreChange 核心变化（会触发重新拉版本清单）
 * @param onVersionChange **只能**由右侧列表调用
 * @param onRetryVersions 重新加载当前核心的版本清单
 * @param onBack 返回服务器列表
 * @param onCreate 提交
 */
@Composable
fun ServerCreatePage(
    state: ServerCreateUiState,
    onNameChange: (String) -> Unit,
    onCoreChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onRetryVersions: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 用实际页面宽度（外壳可能还有侧边导航栏），并给上限，别把表单拉成通栏。
            val contentWidth = minOf(maxWidth, 1000.dp)
            val leftWidth = minOf(contentWidth * 0.48f, 360.dp)
            // 键盘很高时让操作跟着表单滚动，而不是把名称输入框顶没
            val pinActions = maxHeight >= 128.dp
            Column(
                modifier = Modifier.width(contentWidth).fillMaxHeight()
                    .padding(horizontal = QkSpacing.md),
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.md),
                ) {
                    ServerCreateForm(
                        state = state,
                        onNameChange = onNameChange,
                        onCoreChange = onCoreChange,
                        modifier = Modifier.width(leftWidth),
                        spacing = QkSpacing.xs,
                    ) {
                        if (!pinActions) ServerCreateActions(state, onBack, onCreate)
                    }
                    ServerVersionPanel(
                        state = state,
                        onVersionChange = onVersionChange,
                        onRetryVersions = onRetryVersions,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                if (pinActions) {
                    HorizontalDivider()
                    ServerCreateActions(
                        state = state,
                        onBack = onBack,
                        onCreate = onCreate,
                        modifier = Modifier.padding(vertical = QkSpacing.xs),
                        endAligned = true,
                    )
                }
            }
        }
    } else {
        // 竖屏保留原来的列宽比例与表单内滚动的操作，避免挤压版本列表。
        val screenW = com.zhayi.qookix.compose.ui.scaledScreenWidthDp().dp
        val leftWidth = if (screenW < 600.dp) screenW * 0.50f else 360.dp
        Row(modifier = Modifier.fillMaxSize()) {
            ServerCreateForm(
                state = state,
                onNameChange = onNameChange,
                onCoreChange = onCoreChange,
                modifier = Modifier.width(leftWidth),
                spacing = QkSpacing.md,
                contentPadding = Modifier.padding(start = QkSpacing.lg, end = 12.dp, bottom = 20.dp),
            ) {
                ServerCreateActions(state, onBack, onCreate)
            }
            ServerVersionPanel(
                state = state,
                onVersionChange = onVersionChange,
                onRetryVersions = onRetryVersions,
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .padding(end = QkSpacing.lg, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun ServerCreateForm(
    state: ServerCreateUiState,
    onNameChange: (String) -> Unit,
    onCoreChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    spacing: Dp = QkSpacing.md,
    contentPadding: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).then(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            enabled = !state.creating,
            label = { Text(stringResource(R.string.create_server_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )

        QkFieldLabel(stringResource(R.string.create_server_core))
        // ⚠️ FlowRow 而非 Row：竖屏下三个 chip 可能放不下。
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        ) {
            ServerCore.entries.forEach { c ->
                FilterChip(
                    selected = state.core == c.id,
                    onClick = { onCoreChange(c.id) },
                    label = { Text(stringResource(c.labelRes)) },
                    enabled = !state.creating,
                )
            }
        }
        Text(
            // 把「为什么只有三个」说清楚，避免用户以为是漏做了
            // 同 InstanceSettingsTab 的坑：core 若是空串/旧值，first{} 会抛
            // NoSuchElementException 把整页干掉。firstOrNull 兜底到第一个核心。
            text = stringResource(
                (ServerCore.entries.firstOrNull { it.id == state.core } ?: ServerCore.entries.first()).hintRes,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )

        QkFieldLabel(stringResource(R.string.create_server_version))
        // ⚠️ **只读**。版本只能从右侧列表选（见文件头注释）。
        //
        // 用纯文字而不是带框的假输入框（2026-10-18 修）：
        // 原来我做成了一个「看起来像输入框但点不动」的 Surface，
        // 用户对比实例页后指出不好看。实例页这里就是一行文字 ——
        // 选中后变成主题色，一眼看出「这是结果」。带框反而暗示"可以点"。
        Text(
            text = state.version.ifBlank {
                stringResource(R.string.create_server_version_not_selected)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.version.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        )

        state.createError?.let { err ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(QkSpacing.sm),
                )
            }
        }

        actions()
    }
}

@Composable
private fun ServerCancelButton(enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled) {
        Text(stringResource(R.string.create_server_cancel), maxLines = 1)
    }
}

@Composable
private fun ServerSubmitButton(
    enabled: Boolean,
    creating: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) {
        if (creating) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(stringResource(R.string.create_server_create), maxLines = 1)
    }
}

/**
 * 取消 / 创建。
 *
 * [endAligned] 用于横屏固定在底部的操作栏；否则用 FlowRow 让窄栏自动换行
 * （竖屏曾经把「创建服务器」四个字挤成竖排）。
 */
@Composable
private fun ServerCreateActions(
    state: ServerCreateUiState,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    endAligned: Boolean = false,
) {
    if (endAligned) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServerCancelButton(enabled = !state.creating, onClick = onBack)
            Spacer(Modifier.width(QkSpacing.xs))
            ServerSubmitButton(enabled = state.canCreate, creating = state.creating, onClick = onCreate)
        }
    } else {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        ) {
            ServerCancelButton(enabled = !state.creating, onClick = onBack)
            ServerSubmitButton(enabled = state.canCreate, creating = state.creating, onClick = onCreate)
        }
    }
}

@Composable
private fun ServerVersionPanel(
    state: ServerCreateUiState,
    onVersionChange: (String) -> Unit,
    onRetryVersions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 必须是 Surface 而不是裸 Column（2026-10-18 修）。
    // 用户对比两页截图后指出「实例的更好看，服务器的不好看」：
    // 右侧版本行需要浮在一块带圆角的浅色面板上，而不是直接贴在页面背景。
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            QkListHeader(
                title = stringResource(R.string.create_server_version_list_title),
                trailing = if (state.versions.isNotEmpty()) {
                    stringResource(R.string.create_server_version_count, state.versions.size)
                } else {
                    null
                },
            )

            when {
                state.loadingVersions -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }

                state.versionsError != null -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(QkSpacing.lg),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.versionsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetryVersions) {
                        Text(stringResource(R.string.common_retry))
                    }
                }

                state.versions.isEmpty() -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.create_server_version_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // 分组标题（与实例页一致）。Paper 的清单后端已剔掉 rc/pre，只分一组。
                    item(key = "hdr_release") {
                        QkGroupTitle(
                            stringResource(R.string.create_server_group_release),
                            state.versions.size,
                        )
                    }
                    items(state.versions, key = { it }) { v ->
                        QkVersionRow(
                            version = v,
                            selected = state.version == v,
                            trailing = if (state.core == ServerCore.Paper.id) {
                                stringResource(R.string.create_server_version_release)
                            } else {
                                null
                            },
                            onClick = { onVersionChange(v) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 服务端核心。
 *
 * ⚠️ **只能列后端实现了的**。`servers.rs` 里只有这三条下载链路，
 * 其余核心（Spigot / Bukkit / Purpur / Folia…）会直接返回「还在开发中」。
 */
enum class ServerCore(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    Paper(
        id = "paper",
        labelRes = R.string.create_server_core_paper,
        hintRes = R.string.create_server_core_paper_hint,
    ),
    Vanilla(
        id = "vanilla",
        labelRes = R.string.create_server_core_vanilla,
        hintRes = R.string.create_server_core_vanilla_hint,
    ),
    Fabric(
        id = "fabric",
        labelRes = R.string.create_server_core_fabric,
        hintRes = R.string.create_server_core_fabric_hint,
    ),
}

/** Paper 和 Fabric 各用后端支持清单；只有原版使用 Mojang 的正式版清单。 */
internal fun loadServerVersions(
    api: QookixApi,
    core: String,
    onDone: (Result<List<String>>) -> Unit,
) = when (core) {
    ServerCore.Paper.id -> api.listPaperVersions(onDone)
    ServerCore.Fabric.id -> api.callArr("list_fabric_server_versions", null) { result ->
        onDone(result.map { versions ->
            (0 until versions.length()).mapNotNull { i ->
                (versions.opt(i) as? String)?.takeIf { it.isNotBlank() }
            }.distinct()
        })
    }
    ServerCore.Vanilla.id -> api.getVersionManifest { r ->
        onDone(
            r.mapCatching { obj ->
                val arr = obj.optJSONArray("versions")
                    ?: return@mapCatching emptyList<String>()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    // 正式版才适合开服；快照不稳定
                    if (o.optString("type") != "release") return@mapNotNull null
                    o.optString("id").takeIf { it.isNotBlank() }
                }
            },
        )
    }
    else -> onDone(Result.failure(IllegalArgumentException("Unsupported server core: $core")))
}
