package com.zhayi.qookix.compose.lansync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge
import com.zhayi.qookix.compose.bridge.lanUploadInstance
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkFeedback
import com.zhayi.qookix.compose.ui.QkSelectField
import com.zhayi.qookix.compose.ui.QkSelectOption
import org.json.JSONObject

/**
 * 「发送到 PC」区块（手机 → 电脑）。
 *
 * 协议见 `docs/lan-sync.md` 第 2 节：手机把实例打成 zip，
 * `POST /api/instances/upload` 给桌面端。打包规则（跳过 logs / 共享游戏文件等）
 * 和状态码解释都在 Rust 侧（`lan_sync::upload_instance`），这里只管交互：
 *
 *   选实例 → 发送 → 打包中… → 上传 x 已传/总量 → 完成 / 报错
 *
 * 进度也走 `lan://progress`，靠 `direction = "upload"` 与下载区分
 *（两条流程不会同时跑，但同一个通道里必须能分辨）。
 */
@Composable
internal fun LanSyncUploadSection(
    api: QookixApi,
    bridge: RustBridge,
    params: LanSyncParams,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 本地实例（id → 显示名）
    var instances by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    // 上传状态
    var uploading by remember { mutableStateOf(false) }
    /** pack / upload / done */
    var phase by remember { mutableStateOf<String?>(null) }
    var bytesDone by remember { mutableStateOf(0L) }
    var bytesTotal by remember { mutableStateOf(0L) }
    var packedFiles by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun loadInstances() {
        loading = true
        api.listInstances { r ->
            loading = false
            r.fold(
                onSuccess = { arr ->
                    instances = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val id = o.optString("id")
                        if (id.isBlank()) return@mapNotNull null
                        id to o.optString("name").ifBlank { id }
                    }
                    if (selectedId == null || instances.none { it.first == selectedId }) {
                        selectedId = instances.firstOrNull()?.first
                    }
                },
                onFailure = { e ->
                    error = e.message ?: context.getString(R.string.lan_sync_upload_failed)
                },
            )
        }
    }

    LaunchedEffect(params.host, params.port) { loadInstances() }

    // 上传进度（与下载共用 lan://progress，用 direction 区分）
    DisposableEffect(bridge) {
        val sub = bridge.listen("lan://progress") { payload ->
            runCatching {
                val o = JSONObject(payload)
                if (o.optString("direction") != "upload") return@runCatching
                when (o.optString("phase")) {
                    "pack" -> {
                        phase = "pack"
                        packedFiles = o.optInt("files")
                    }
                    "upload" -> {
                        phase = "upload"
                        bytesDone = o.optLong("bytesDone")
                        bytesTotal = o.optLong("bytesTotal")
                    }
                    "done" -> {
                        uploading = false
                        phase = "done"
                        QkFeedback.show(context.getString(R.string.lan_sync_upload_done))
                    }
                    "error" -> {
                        uploading = false
                        phase = null
                        error = o.optString("message")
                            .ifBlank { context.getString(R.string.lan_sync_upload_failed) }
                    }
                }
            }
        }
        onDispose { bridge.unlisten(sub) }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(QkSpacing.md)) {
            Text(
                text = stringResource(R.string.lan_sync_upload_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.lan_sync_upload_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(QkSpacing.xs))

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.lan_sync_upload_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                instances.isEmpty() -> Text(
                    stringResource(R.string.lan_sync_upload_no_instance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> {
                    val options = instances.map { (id, name) -> QkSelectOption(id, name) }
                    QkSelectField(
                        label = stringResource(R.string.lan_sync_upload_pick),
                        value = options.firstOrNull { it.key == selectedId }?.label.orEmpty(),
                        options = options,
                        onSelect = { selectedId = it },
                        enabled = !uploading,
                    )
                    Spacer(Modifier.height(QkSpacing.xs))
                    Button(
                        onClick = {
                            val id = selectedId ?: return@Button
                            uploading = true
                            phase = null
                            error = null
                            packedFiles = 0
                            bytesDone = 0
                            bytesTotal = 0
                            api.lanUploadInstance(params.host, params.port, params.token, id) { r ->
                                // 成功路径也走 lan://progress 的 done 事件；
                                // 这里只兜 RPC 本身失败（参数非法 / 没启动）
                                r.onFailure { e ->
                                    uploading = false
                                    phase = null
                                    error = e.message ?: context.getString(R.string.lan_sync_upload_failed)
                                }
                            }
                        },
                        enabled = !uploading && selectedId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.lan_sync_upload_send))
                    }

                    // 进度 / 结果
                    when (phase) {
                        "pack" -> {
                            Spacer(Modifier.height(QkSpacing.xs))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                stringResource(R.string.lan_sync_upload_packing, packedFiles),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        "upload" -> {
                            Spacer(Modifier.height(QkSpacing.xs))
                            if (bytesTotal > 0) {
                                LinearProgressIndicator(
                                    progress = {
                                        (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${humanSize(bytesDone)} / ${humanSize(bytesTotal)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        "done" -> {
                            Spacer(Modifier.height(QkSpacing.xs))
                            Text(
                                stringResource(R.string.lan_sync_upload_done),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    error?.let { msg ->
                        Spacer(Modifier.height(QkSpacing.xs))
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format("%.1f %s", size, units[unit])
}
