package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
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
)

/**
 * 新建服务器页面。
 *
 * @param state 表单状态（由 [ServerCreateRoute] 持有）
 * @param onNameChange 名称变化
 * @param onCoreChange 核心变化（会触发重新拉版本清单）
 * @param onVersionChange **只能**由右侧列表调用
 * @param onBack 返回服务器列表
 * @param onCreate 提交
 */
@Composable
fun ServerCreatePage(
    state: ServerCreateUiState,
    onNameChange: (String) -> Unit,
    onCoreChange: (String) -> Unit,
    onVersionChange: (String) -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    // 窄屏（竖屏手机）时左栏占比小一点，保证右边版本列表还能看到几行。
    // 与 CreateInstancePage 用同一个比例/宽度 —— 两页并排看要对齐
    val leftW = if (screenW < 600.dp) (screenW * 0.42f) else 360.dp

    Row(modifier = Modifier.fillMaxSize()) {
        // ── 左：表单 ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .width(leftW)
                .verticalScroll(rememberScrollState())
                .padding(start = QkSpacing.lg, end = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.md),
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
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
                    )
                }
            }
            Text(
                // 把「为什么只有三个」说清楚，避免用户以为是漏做了
                text = stringResource(ServerCore.entries.first { it.id == state.core }.hintRes),
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            ) {
                OutlinedButton(onClick = onBack, enabled = !state.creating) {
                    Text(stringResource(R.string.create_server_cancel))
                }
                Button(
                    onClick = onCreate,
                    // 名称与版本都必填 —— 缺任何一个 Rust 都会拒绝
                    enabled = state.name.trim().isNotEmpty() &&
                        state.version.isNotBlank() &&
                        !state.creating,
                ) {
                    if (state.creating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.create_server_create))
                }
            }
        }

        // ── 右：版本列表 ────────────────────────────────────────────────
        //
        // ⚠️ 必须是 Surface 而不是裸 Column（2026-10-18 修）。
        //
        // 用户对比两页截图后指出「实例的更好看，服务器的不好看」。
        // 差异就在这一层：实例页右栏是带圆角 + surfaceContainerLow 底色的卡片，
        // 版本行浮在浅色底上，层次分明；裸 Column 则是列表直接贴在页面背景上，
        // 显得松散、没有「一块面板」的感觉。
        Surface(
            modifier = Modifier
                // weight(1f) 而非 fillMaxSize()：左边是固定宽度的表单栏，
                // fillMaxSize() 会和它抢宽度。
                .weight(1f)
                .fillMaxHeight()
                .padding(end = QkSpacing.lg, bottom = 20.dp),
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

                state.versionsError != null -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(QkSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.versionsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
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
                    // 分组标题（与实例页一致）。
                    // Paper 的清单后端已剔掉 rc/pre，全是正式版，所以只分一组。
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
                            // 右侧「正式」标签 —— 实例页每行都有，服务器页原来缺，
                            // 看起来就"不像同一套东西"
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

/**
 * 按核心类型加载版本清单。
 *
 * 放在这里而不是 Repo，是因为两条来源的返回形状不一样
 *（Paper 直接是 `Vec<String>`，Vanilla 是 version manifest 的对象），
 * 集中在一处更容易对照着看。
 */
fun loadServerVersions(
    api: QookixApi,
    core: String,
    onDone: (Result<List<String>>) -> Unit,
) {
    if (core == ServerCore.Paper.id) {
        api.listPaperVersions(onDone)
        return
    }
    // Vanilla / Fabric 都用官方 manifest 的正式版列表。
    api.getVersionManifest { r ->
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
}



