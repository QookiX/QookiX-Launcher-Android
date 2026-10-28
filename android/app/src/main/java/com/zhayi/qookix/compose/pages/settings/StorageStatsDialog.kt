package com.zhayi.qookix.compose.pages.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import org.json.JSONObject

/**
 * 「存储占用」弹窗。
 *
 * 2026-10-28 重写：原来是 `formatStorageStats` 把整个 JSON 逐 key 拼成文本
 * 塞进通用 InfoDialog —— 嵌套的 `categories` / `instances` 数组直接
 * `toString()` 打印，用户在弹窗里看到的是一屏原始 JSON
 *（`cached (缓存) : false` / `categories: [{"files":62,...}]`）。
 *
 * 现在按数据本身的结构画：总数 → 分类占比条 → 实例明细 → 服务器明细。
 * 解析是宽容的（snake/camel 都认），后端加字段不会让这里炸。
 */

/** 一个可展示的占用条目（分类 / 实例 / 服务器共用）。 */
internal data class StorageStatRow(val label: String, val size: Long, val files: Long)

/** 弹窗要用的存储统计。 */
internal data class StorageStatsUi(
    val total: Long,
    val categories: List<StorageStatRow>,
    val instances: List<StorageStatRow>,
    val servers: List<StorageStatRow>,
) {
    val isEmpty: Boolean get() = total <= 0 && categories.isEmpty()
}

/** 从 `refresh_storage_stats` / `get_storage_stats` 的返回里解析。 */
internal fun parseStorageStats(o: JSONObject): StorageStatsUi {
    fun rows(key: String): List<StorageStatRow> {
        val arr = o.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val size = e.optLong("size", e.optLong("bytes", 0))
            val label = e.optText("label")
                ?: e.optText("name")
                ?: e.optText("key")
                ?: return@mapNotNull null
            StorageStatRow(label = label, size = size, files = e.optLong("files", 0))
        }.sortedByDescending { it.size }
    }
    return StorageStatsUi(
        total = o.optLong("total", 0),
        categories = rows("categories"),
        instances = rows("instances"),
        servers = rows("servers"),
    )
}

/** `optString` 会把 JSON null 变成字面量 "null"，这里统一当没有。 */
private fun JSONObject.optText(key: String): String? {
    val v = opt(key) ?: return null
    if (v == JSONObject.NULL) return null
    return (v as? String)?.takeIf { it.isNotBlank() && it != "null" }
}

@Composable
internal fun StorageStatsDialog(
    stats: StorageStatsUi?,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_storage_usage)) },
        text = {
            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                error != null -> Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )

                stats == null || stats.isEmpty -> Text(
                    stringResource(R.string.settings_storage_no_stats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                ) {
                    // 总占用放大显示 —— 用户点进来看的就是这个数
                    Text(
                        text = humanBytes(stats.total),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        // 只写「总计」：标题已经是「存储占用」，再拼一遍纯属重复
                        text = stringResource(R.string.settings_storage_total_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (stats.categories.isNotEmpty()) {
                        Spacer(Modifier.height(QkSpacing.xs))
                        HorizontalDivider()
                        Spacer(Modifier.height(QkSpacing.xs))
                        StatsSectionLabel(stringResource(R.string.settings_storage_category_group))
                        stats.categories.forEach { row ->
                            CategoryRow(row = row, total = stats.total)
                        }
                    }

                    if (stats.instances.isNotEmpty()) {
                        Spacer(Modifier.height(QkSpacing.xs))
                        HorizontalDivider()
                        Spacer(Modifier.height(QkSpacing.xs))
                        StatsSectionLabel(
                            stringResource(
                                R.string.settings_storage_instances_group,
                                stats.instances.size,
                            ),
                        )
                        stats.instances.forEach { RowItem(it) }
                    }

                    if (stats.servers.isNotEmpty()) {
                        Spacer(Modifier.height(QkSpacing.xs))
                        HorizontalDivider()
                        Spacer(Modifier.height(QkSpacing.xs))
                        StatsSectionLabel(
                            stringResource(
                                R.string.settings_storage_servers_group,
                                stats.servers.size,
                            ),
                        )
                        stats.servers.forEach { RowItem(it) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_storage_done))
            }
        },
    )
}

@Composable
private fun StatsSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分类行：名字 + 大小 + 占总量比例的细条。 */
@Composable
private fun CategoryRow(row: StorageStatRow, total: Long) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(QkSpacing.xs))
            Text(
                text = humanBytes(row.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (total > 0 && row.size > 0) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (row.size.toFloat() / total).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** 实例 / 服务器行：名字 + 大小。 */
@Composable
private fun RowItem(row: StorageStatRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(QkSpacing.xs))
        Text(
            text = humanBytes(row.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
