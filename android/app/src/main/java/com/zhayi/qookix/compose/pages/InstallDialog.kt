package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing

/**
 * 内容安装对话框（内容中心卡片上的「安装」）。
 *
 * ── 这是「装模组」的唯一路径 ──────────────────────────────────────────────
 * 之前完全没有它：内容中心的卡片只能「用浏览器打开项目页」，
 * 而 `install_content` 这条命令连 API 层都没有。
 * 用户从内容中心**装不了任何模组**。
 *
 * ── 与「下载游戏」的区别 ──────────────────────────────────────────────────
 * | | install_content（本对话框）| install_game |
 * |---|---|---|
 * | 作用 | 装模组/资源包/光影/整合包 | 下载游戏本体（jar + 库 + 素材）|
 * | 目标 | 实例（插件则是服务器）| 实例 |
 *
 * 主体区块（目标 / 筛选 / 版本 / 依赖）都在 `InstallPanel.kt` 里，
 * 详情页左栏的常驻面板用的是同一批 composable。
 */
@Composable
fun InstallDialog(
    state: InstallUiState,
    onSelectVersion: (String) -> Unit,
    onSelectInstance: (String?) -> Unit,
    onSelectServer: (String?) -> Unit,
    onTypeFilterChange: (String) -> Unit,
    onGameFilterChange: (String) -> Unit,
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
        onDismissRequest = { if (!state.installing) onDismiss() },
        title = {
            Column {
                Text(
                    text = state.project.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (state.project.author.isNotBlank()) {
                    Text(
                        text = state.project.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                InstallIntro(state = state, onTranslate = onTranslate)
                InstallTargets(
                    state = state,
                    onSelectInstance = onSelectInstance,
                    onSelectServer = onSelectServer,
                    onCreateTargetInstance = onCreateTargetInstance,
                )
                InstallFilters(
                    state = state,
                    onTypeFilterChange = onTypeFilterChange,
                    onGameFilterChange = onGameFilterChange,
                )
                InstallVersionList(state = state, onSelectVersion = onSelectVersion)
                InstallExtras(state = state, onOpenDependency = onOpenDependency)
            }
        },
        confirmButton = { InstallButton(state = state, onInstall = onInstall) },
        dismissButton = {
            // 安装中必须和 onDismissRequest 一样禁用：下载在后台继续，
            // 关掉对话框用户就找不到这个任务的进度了（只拦了外部点击，按钮漏了）
            OutlinedButton(onClick = onDismiss, enabled = !state.installing) {
                Text(stringResource(R.string.install_cancel))
            }
        },
    )
}

/** 安装按钮（对话框的 confirmButton；详情页顶栏用的是 [ContentInstallHandle]）。 */
@Composable
fun InstallButton(state: InstallUiState, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onInstall,
        enabled = state.canInstall && !state.installing,
        modifier = modifier,
    ) {
        if (state.installing) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (state.installing) stringResource(R.string.install_installing)
            else stringResource(R.string.install_install),
        )
    }
}

/** 一条可选版本。 */
@Composable
internal fun VersionRowItem(
    v: ContentVersionUi,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = QkSpacing.sm, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = v.versionNumber,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
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
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun InstallLoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun InstallSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(QkSpacing.xs))
}
