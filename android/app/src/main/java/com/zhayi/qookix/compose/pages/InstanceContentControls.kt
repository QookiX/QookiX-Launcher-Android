package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing

@Composable
internal fun InstanceContentToolbar(
    state: InstanceDetailUiState,
    onKindChange: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onIdentify: () -> Unit,
    identifying: Boolean,
    onImport: (() -> Unit)?,
) {
    val filterScroll = rememberScrollState()
    val importLabel = stringResource(R.string.instance_detail_import_file)
    val updateLabel = stringResource(R.string.instance_detail_check_updates)
    val identifyLabel = stringResource(R.string.instance_detail_identify)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = QkSpacing.lg, vertical = QkSpacing.xxs),
    ) {
        // Reserve space for filters first. Long labels and large text use icon buttons sooner.
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val showLabels = maxWidth >= 640.dp * fontScale
        val useOverflow = maxWidth < 384.dp * fontScale
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.xxs),
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(filterScroll),
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            ) {
                state.contentKinds.forEach { kind ->
                    val selected = state.contentKind == kind.id
                    // The selected category's stored count can lag local content changes.
                    val count = if (selected) maxOf(kind.count, state.content.size) else kind.count
                    FilterChip(
                        selected = selected,
                        onClick = { onKindChange(kind.id) },
                        label = {
                            Text(
                                stringResource(
                                    R.string.instance_detail_kind_label_count,
                                    stringResource(kind.labelRes),
                                    count,
                                ),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            if (onImport != null) {
                // Local import stays directly accessible at every width.
                ContentToolbarAction(importLabel, Icons.Filled.Upload, showLabels, onImport)
            }
            if (useOverflow) {
                var overflowOpen by remember { mutableStateOf(false) }
                val moreLabel = stringResource(R.string.file_more)
                // Keep identification progress visible even when its action is in the menu.
                val overflowLabel = if (identifying) "$moreLabel · $identifyLabel" else moreLabel
                Box {
                    ContentToolbarAction(
                        label = overflowLabel,
                        icon = Icons.Filled.MoreVert,
                        showLabel = false,
                        onClick = { overflowOpen = true },
                        busy = identifying,
                    )
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(updateLabel) },
                            leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                            onClick = {
                                overflowOpen = false
                                onCheckUpdates()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(identifyLabel) },
                            leadingIcon = { ContentActionIcon(Icons.Filled.Search, busy = identifying) },
                            enabled = !identifying,
                            onClick = {
                                overflowOpen = false
                                onIdentify()
                            },
                        )
                    }
                }
            } else {
                ContentToolbarAction(updateLabel, Icons.Filled.Refresh, showLabels, onCheckUpdates)
                ContentToolbarAction(
                    identifyLabel,
                    Icons.Filled.Search,
                    showLabels,
                    onIdentify,
                    enabled = !identifying,
                    busy = identifying,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentToolbarAction(
    label: String,
    icon: ImageVector,
    showLabel: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    if (showLabel) {
        TextButton(onClick = onClick, enabled = enabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) ContentActionIcon(icon, busy = true)
                Text(label, maxLines = 1)
            }
        }
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onClick, enabled = enabled) {
                ContentActionIcon(icon, busy, label)
            }
        }
    }
}

@Composable
private fun ContentActionIcon(icon: ImageVector, busy: Boolean, label: String? = null) {
    if (busy) {
        Box(
            modifier = Modifier.size(20.dp).semantics {
                if (label != null) contentDescription = label
            },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    } else {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstanceContentEmpty() {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QkSpacing.lg, vertical = QkSpacing.xxs),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = QkSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Inventory2,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.xxs),
            ) {
                Text(
                    stringResource(R.string.instance_detail_no_content_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.instance_detail_no_content_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
