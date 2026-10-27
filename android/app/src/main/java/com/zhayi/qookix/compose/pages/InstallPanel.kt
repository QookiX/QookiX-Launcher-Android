package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 详情页左栏的常驻安装面板。
 *
 * 和内容中心的安装对话框（[InstallDialog]）共用同一套状态与区块，
 * 区别只有两点：不套对话框壳、不带底部的安装按钮
 * （那个按钮被挪到详情页顶栏了，见 [ContentInstallHandle]）。
 *
 * 内容用 [LazyColumn] 滚：长版本列表不会一次性全组合。
 */
@Composable
fun InstallPanel(
    state: InstallUiState,
    onSelectVersion: (String) -> Unit,
    onSelectInstance: (String?) -> Unit,
    onSelectServer: (String?) -> Unit,
    onTypeFilterChange: (String) -> Unit,
    onGameFilterChange: (String) -> Unit,
    onOpenDependency: (DependencyUi) -> Unit,
    onTranslate: () -> Unit,
    onCreateTargetInstance: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        item(key = "__intro__") {
            // 下载量在详情页顶栏已经显示了，这里不重复
            InstallIntro(state = state, onTranslate = onTranslate, showDownloads = false)
        }
        item(key = "__targets__") {
            InstallTargets(
                state = state,
                onSelectInstance = onSelectInstance,
                onSelectServer = onSelectServer,
                onCreateTargetInstance = onCreateTargetInstance,
            )
        }
        item(key = "__filters__") {
            InstallFilters(
                state = state,
                onTypeFilterChange = onTypeFilterChange,
                onGameFilterChange = onGameFilterChange,
            )
        }
        // 版本逐条铺：上百个版本也只组合可见的那几条
        if (!state.isPlugin) {
            if (state.loadingVersions) {
                item(key = "__versions_loading__") {
                    InstallLoadingRow(stringResource(R.string.install_loading_versions))
                }
            } else if (state.versions.isEmpty()) {
                item(key = "__versions_empty__") {
                    Text(
                        stringResource(R.string.install_no_versions),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val shown = state.versions.take(80)
                items(shown.size, key = { i -> "__v_${shown[i].id}__" }) { i ->
                    val v = shown[i]
                    VersionRowItem(
                        v = v,
                        selected = state.selectedVersion == v.id,
                        onClick = { onSelectVersion(v.id) },
                        modifier = Modifier.padding(bottom = QkSpacing.xxs),
                    )
                }
            }
        }
        item(key = "__extras__") { InstallExtras(state = state, onOpenDependency = onOpenDependency) }
    }
}

/** 简介 + 译文 + 翻译按钮。 */
@Composable
internal fun InstallIntro(
    state: InstallUiState,
    onTranslate: () -> Unit,
    /** 详情页顶栏已经显示过下载量了，面板里不再重复一遍。 */
    showDownloads: Boolean = true,
) {
    val project = state.project
    Column {
        if (showDownloads && project.downloads > 0) {
            Text(
                text = stringResource(R.string.content_detail_downloads, fmtCount(project.downloads)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (state.descLoading) {
            Text(stringResource(R.string.install_translating), style = MaterialTheme.typography.bodySmall)
        } else if (!state.descZh.isNullOrBlank()) {
            Text(state.descZh, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(QkSpacing.xxs))
            Text(
                project.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (project.description.isNotBlank()) {
            Text(project.description, style = MaterialTheme.typography.bodySmall)
        }
        if (state.descZh.isNullOrBlank() && !state.descLoading && project.description.isNotBlank()) {
            TextButton(onClick = onTranslate) { Text(stringResource(R.string.install_translate)) }
        }
        Spacer(Modifier.height(QkSpacing.sm))
    }
}

/**
 * 安装目标。
 *
 * 插件 → 服务器（`plugins/`）；模组 → 支持模组的服务器（`mods/`）**或**实例；
 * 其他（资源包/光影）→ 只能实例，且只列非 vanilla 实例
 * （原版实例没有加载器，装了模组也不会被加载）。
 *
 * 2026-10-27：服务器只看「能装模组的核心」—— 纯插件服（paper）根本加载不了
 * mods，列出来点了也会失败。
 */
@Composable
internal fun InstallTargets(
    state: InstallUiState,
    onSelectInstance: (String?) -> Unit,
    onSelectServer: (String?) -> Unit,
    onCreateTargetInstance: (() -> Unit)?,
) {
    Column {
        if (state.serverTargets && state.servers.isNotEmpty()) {
            InstallSectionLabel(stringResource(R.string.install_target_server))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            ) {
                state.servers.forEach { s ->
                    FilterChip(
                        selected = state.selectedServerId == s.id,
                        onClick = { onSelectServer(s.id) },
                        label = { Text(s.name) },
                    )
                }
            }
            Spacer(Modifier.height(QkSpacing.xs))
        }

        if (!state.isPlugin) {
            InstallSectionLabel(stringResource(R.string.install_target_instance))
            val candidates = state.instances.filter { it.loader != "vanilla" }
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.install_no_instance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                // ⚠️ 2026-10-17 加：新用户路径断点修复。
                // 原来只有一句红字，没有任何可点的东西 —— 新用户只能退出对话框、
                // 去实例页、新建、选 Fabric、等下载、再回来重新找这个模组。
                if (onCreateTargetInstance != null) {
                    Spacer(Modifier.height(QkSpacing.xs))
                    OutlinedButton(onClick = onCreateTargetInstance) {
                        Text(stringResource(R.string.install_create_fabric_instance))
                    }
                }
            } else {
                // ⚠️ 必须包一层 FlowRow（2026-10-17 修）。
                // 原来 FilterChip 和 Spacer 直接铺在 Column 里：选项竖排成一长串，
                // 且 Spacer(width) 在 Column 里完全无效（Column 的间距走 height）。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                ) {
                    candidates.forEach { i ->
                        FilterChip(
                            selected = state.selectedInstanceId == i.id,
                            onClick = { onSelectInstance(i.id) },
                            label = { Text(i.name) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(QkSpacing.sm))
    }
}

/** 版本筛选：类型（正式/测试/先行）+ 游戏版本（来自版本数据本身）。 */
@Composable
internal fun InstallFilters(
    state: InstallUiState,
    onTypeFilterChange: (String) -> Unit,
    onGameFilterChange: (String) -> Unit,
) {
    Column {
        if (state.isPlugin) return@Column
        InstallSectionLabel(stringResource(R.string.install_select_version))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        ) {
            FilterChip(
                selected = state.typeFilter == "all",
                onClick = { onTypeFilterChange("all") },
                label = { Text(stringResource(R.string.install_type_all)) },
            )
            FilterChip(
                selected = state.typeFilter == "release",
                onClick = { onTypeFilterChange("release") },
                label = { Text(stringResource(R.string.install_type_release)) },
            )
            FilterChip(
                selected = state.typeFilter == "beta",
                onClick = { onTypeFilterChange("beta") },
                label = { Text(stringResource(R.string.install_type_beta)) },
            )
            FilterChip(
                selected = state.typeFilter == "alpha",
                onClick = { onTypeFilterChange("alpha") },
                label = { Text(stringResource(R.string.install_type_alpha)) },
            )
        }
        // 游戏版本筛选（2026-10-27 加，用户要求「按版本筛选」）：
        // 选项来自版本列表本身，不猜也不写死。
        if (state.gameVersions.isNotEmpty()) {
            Spacer(Modifier.height(QkSpacing.xxs))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            ) {
                FilterChip(
                    selected = state.gameFilter == "all",
                    onClick = { onGameFilterChange("all") },
                    label = { Text(stringResource(R.string.install_type_all)) },
                )
                state.gameVersions.forEach { gv ->
                    FilterChip(
                        selected = state.gameFilter == gv,
                        onClick = { onGameFilterChange(gv) },
                        label = { Text(gv) },
                    )
                }
            }
        }
        Spacer(Modifier.height(QkSpacing.xs))
    }
}

/** 对话框用的版本列表（面板那边逐条铺，见 [InstallPanel]）。 */
@Composable
internal fun InstallVersionList(state: InstallUiState, onSelectVersion: (String) -> Unit) {
    Column {
        if (state.isPlugin) return@Column
        if (state.loadingVersions) {
            InstallLoadingRow(stringResource(R.string.install_loading_versions))
        } else if (state.versions.isEmpty()) {
            Text(
                stringResource(R.string.install_no_versions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // 最多显示 80 条（旧 UI 同款上限，避免列表过长）
            state.versions.take(80).forEach { v ->
                VersionRowItem(
                    v = v,
                    selected = state.selectedVersion == v.id,
                    onClick = { onSelectVersion(v.id) },
                    modifier = Modifier.padding(bottom = QkSpacing.xxs),
                )
            }
        }
    }
}

/** 依赖 + 错误提示。 */
@Composable
internal fun InstallExtras(state: InstallUiState, onOpenDependency: (DependencyUi) -> Unit) {
    Column {
        if (!state.isPlugin && state.deps.isNotEmpty()) {
            Spacer(Modifier.height(QkSpacing.sm))
            InstallSectionLabel(stringResource(R.string.install_dependencies))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                state.deps.forEach { d ->
                    FilterChip(
                        selected = false,
                        onClick = { onOpenDependency(d) },
                        label = { Text("${d.typeLabel}: ${d.title}") },
                    )
                }
            }
        }
        if (state.error != null) {
            Spacer(Modifier.height(QkSpacing.sm))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(QkSpacing.sm))
    }
}
