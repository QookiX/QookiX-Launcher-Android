package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R

/**
 * 内容安装对话框（对应旧 UI `InstallDialog.vue`，1044 行）。
 *
 * ── 这是「装模组」的唯一路径 ──────────────────────────────────────────────
 * 之前我完全没有它：内容中心的卡片只能「用浏览器打开项目页」，
 * 而 `install_content` 这条命令连 API 层都没有。
 * 用户从内容中心**装不了任何模组**。
 *
 * ── 与「下载游戏」的区别 ──────────────────────────────────────────────────
 * | | install_content（本对话框）| install_game |
 * |---|---|---|
 * | 作用 | 装模组/资源包/光影/整合包 | 下载游戏本体（jar + 库 + 素材）|
 * | 目标 | 实例（插件则是服务器）| 实例 |
 */
@Composable
fun InstallDialog(
    project: ProjectUi,
    kind: String,
    isPlugin: Boolean,
    versions: List<ContentVersionUi>,
    loadingVersions: Boolean,
    selectedVersion: String?,
    instances: List<InstallTargetUi>,
    servers: List<ServerUi>,
    selectedInstanceId: String?,
    selectedServerId: String?,
    typeFilter: String,
    deps: List<DependencyUi>,
    descZh: String?,
    descLoading: Boolean,
    installing: Boolean,
    error: String?,
    onSelectVersion: (String) -> Unit,
    onSelectInstance: (String?) -> Unit,
    onSelectServer: (String?) -> Unit,
    onTypeFilterChange: (String) -> Unit,
    onOpenDependency: (DependencyUi) -> Unit,
    onTranslate: () -> Unit,
    onInstall: () -> Unit,
    /** 没有可用目标实例时，引导用户去新建一个（可选；null 表示宿主没提供，不显示按钮）。 */
    onCreateTargetInstance: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        // ⚠️ 安装中不允许关闭（2026-10-17 修，对照 CreateServerDialog 的正确写法）。
        // 原来点外部/返回键就能关，而下载其实在后台继续 ——
        // 用户关了对话框就再也找不到这个任务的进度，以为安装失败又装一次。
        onDismissRequest = { if (!installing) onDismiss() },
        title = {
            Text(
                text = project.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── 头部信息 ──────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = fmtCount(project.downloads),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(Modifier.height(6.dp))

                // 描述：译文优先（译文在上、原文在下）
                if (descLoading) {
                    Text(stringResource(R.string.install_translating), style = MaterialTheme.typography.bodySmall)
                } else if (!descZh.isNullOrBlank()) {
                    Text(descZh, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        project.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(project.description, style = MaterialTheme.typography.bodySmall)
                }

                // 翻译按钮（内置/自定义服务可用时）
                if (descZh.isNullOrBlank() && !descLoading) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onTranslate) { Text(stringResource(R.string.install_translate)) }
                }

                Spacer(Modifier.height(12.dp))

                // ── 安装目标 ──────────────────────────────────────────────
                //
                // 插件 → 服务器（`plugins/`）；模组 → 服务器（`mods/`）**或**实例；
                // 其他（资源包/光影）→ 只能实例，且只列非 vanilla 实例
                //         （原版实例没有加载器，装了模组也不会被加载）。
                //
                // 2026-10-18 改：模组现在也能装到服务器，所以服务器区块不再只对插件显示。
                val serverTargets = isPlugin || kind == "mod"
                if (serverTargets) {
                    SectionLabel(stringResource(R.string.install_target_server))
                    if (servers.isEmpty()) {
                        Text(
                            stringResource(R.string.install_no_server),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        // 同下：必须包 FlowRow，否则竖排 + width Spacer 失效
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            servers.forEach { s ->
                                FilterChip(
                                    selected = selectedServerId == s.id,
                                    onClick = { onSelectServer(s.id) },
                                    label = { Text(s.name) },
                                )
                            }
                        }
                    }
                } else {
                    SectionLabel(stringResource(R.string.install_target_instance))
                    val candidates = instances.filter { it.loader != "vanilla" }
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
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onCreateTargetInstance) {
                                Text(stringResource(R.string.install_create_fabric_instance))
                            }
                        }
                    } else {
                        // ⚠️ 必须包一层 FlowRow（2026-10-17 修）。
                        // 原来 FilterChip 和 Spacer 直接铺在 Column 里：选项竖排成一长串，
                        // 且 Spacer(width) 在 Column 里完全无效（Column 的间距走 height）。
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            candidates.forEach { i ->
                                FilterChip(
                                    selected = selectedInstanceId == i.id,
                                    onClick = { onSelectInstance(i.id) },
                                    label = { Text(i.name) },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 版本选择 ──────────────────────────────────────────────
                if (!isPlugin) {
                    SectionLabel(stringResource(R.string.install_select_version))
                    // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                     FlowRow(
                         horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                     ) {
                        FilterChip(
                            selected = typeFilter == "all",
                            onClick = { onTypeFilterChange("all") },
                            label = { Text(stringResource(R.string.install_type_all)) },
                        )
                        FilterChip(
                            selected = typeFilter == "release",
                            onClick = { onTypeFilterChange("release") },
                            label = { Text(stringResource(R.string.install_type_release)) },
                        )
                        FilterChip(
                            selected = typeFilter == "beta",
                            onClick = { onTypeFilterChange("beta") },
                            label = { Text(stringResource(R.string.install_type_beta)) },
                        )
                        FilterChip(
                            selected = typeFilter == "alpha",
                            onClick = { onTypeFilterChange("alpha") },
                            label = { Text(stringResource(R.string.install_type_alpha)) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    if (loadingVersions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.install_loading_versions), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (versions.isEmpty()) {
                        Text(
                            stringResource(R.string.install_no_versions),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 最多显示 80 条（旧 UI 同款上限，避免列表过长）
                        versions.take(80).forEach { v ->
                            Surface(
                                onClick = { onSelectVersion(v.id) },
                                shape = MaterialTheme.shapes.small,
                                color = if (selectedVersion == v.id) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = v.versionNumber,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = v.typeLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = v.gameVersions,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }

                    // ── 依赖 ──────────────────────────────────────────────
                    if (deps.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        SectionLabel(stringResource(R.string.install_dependencies))
                        deps.forEach { d ->
                            FilterChip(
                                selected = false,
                                onClick = { onOpenDependency(d) },
                                label = { Text("${d.typeLabel}: ${d.title}") },
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }

                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            // 2026-10-18：模组可以装到服务器**或**实例，二者选一即可。
            // 插件只能装服务器（Hangar 那条路不需要选版本）。
            val canInstall = if (isPlugin) {
                !selectedServerId.isNullOrBlank()
            } else {
                (!selectedServerId.isNullOrBlank() && !selectedVersion.isNullOrBlank()) ||
                    (!selectedInstanceId.isNullOrBlank() && !selectedVersion.isNullOrBlank())
            }
            Button(onClick = onInstall, enabled = canInstall && !installing) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (installing) stringResource(R.string.install_installing)
                    else stringResource(R.string.install_install),
                )
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.install_cancel)) }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(6.dp))
}

// ── UI 模型 ────────────────────────────────────────────────────────────────

/**
 * 安装目标：一个具备加载器信息的实例。
 *
 * ⚠️ 不能复用 InstancesPage 的 InstanceUi —— 它只有 id/name/subtitle，
 * **没有 loader 与 mc_version**，而版本筛选两者都要用。
 */
data class InstallTargetUi(
    val id: String,
    val name: String,
    /** 加载器：vanilla / fabric / forge / neoforge / quilt */
    val loader: String,
    /** MC 版本（Rust 字段是 version_id）。 */
    val mcVersion: String,
)

/** 一个可选版本。 */
data class ContentVersionUi(
    val id: String,
    val versionNumber: String,
    val type: String,
    val typeLabel: String,
    val gameVersions: String,
    val datePublished: String,
)

/** 一个依赖。 */
data class DependencyUi(
    val projectId: String,
    val title: String,
    val type: String,
    val typeLabel: String,
    val provider: String,
)




