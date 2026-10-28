package com.zhayi.qookix.compose.lansync

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge
import com.zhayi.qookix.compose.bridge.lanConnect
import com.zhayi.qookix.compose.bridge.lanDownloadInstance
import com.zhayi.qookix.compose.bridge.lanListInstances
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkFeedback
import org.json.JSONObject

// ── UI 数据模型 ───────────────────────────────────────────────────────────

/** PC 设备信息（连接成功后显示）。 */
private data class LanDeviceInfo(val device: String, val version: String)

/** 实例列表条目。 */
private data class LanInstanceUi(
    val id: String,
    val name: String,
    val mcVersion: String,
    val loader: String,
    val sizeBytes: Long,
    val hasSaves: Boolean,
    val hasMods: Boolean,
)

/** 下载进度（来自 `lan://progress` 事件）。 */
private data class DownloadProgress(
    val phase: String,       // download / extract / done / error
    val instanceName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val message: String?,
    /** 瞬时速度（字节/秒），由 Rust 侧平滑后推过来；0 = 还没有采样。 */
    val speedBps: Long = 0,
)

// ── 入口 ───────────────────────────────────────────────────────────────────

/**
 * 局域网同步路由（由 [com.zhayi.qookix.compose.PageHost] 的 `when(dest)` 调用）。
 *
 * 流程：扫码/手动输入 → 连接 PC → 选实例 → 同步下载 → 完成。
 * 配对参数来源：deep link（`LanSyncDeepLink.pending`）或页内扫码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanSyncRoute(
    bridge: RustBridge,
    bridgeReady: Boolean,
    onBack: () -> Unit,
) {
    val api = remember(bridge) { QookixApi(bridge) }

    // Deep link 参数（onNewIntent 写入，这里消费）
    val deepLinkPending by LanSyncDeepLink.pending.collectAsStateWithLifecycle()

    // ── 状态 ──────────────────────────────────────────────────────────
    var params by remember { mutableStateOf<LanSyncParams?>(null) }
    var deviceInfo by remember { mutableStateOf<LanDeviceInfo?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var connectError by remember { mutableStateOf<String?>(null) }

    var instances = remember { mutableStateListOf<LanInstanceUi>() }
    var instancesLoading by remember { mutableStateOf(false) }
    var instancesError by remember { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var downloadDoneCount by remember { mutableStateOf(0) }
    var downloadTotalCount by remember { mutableStateOf(0) }

    var showManualInput by remember { mutableStateOf(false) }

    // ⚠️ 重试必须靠计数，不能靠「把 params 置空再设回去」：
    // LaunchedEffect 的 key 用 equals 比较，同一个 data class 实例（值相等）
    // 不会重启协程 —— 原来连接失败点「重试」根本没有任何反应。
    var connectRetry by remember { mutableStateOf(0) }
    var listRetry by remember { mutableStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current

    // ── 消费 deep link ────────────────────────────────────────────────
    LaunchedEffect(deepLinkPending) {
        if (deepLinkPending != null && params == null && !connecting) {
            params = deepLinkPending
            LanSyncDeepLink.pending.value = null // 已消费
        }
    }

    // ── 自动连接 ──────────────────────────────────────────────────────
    LaunchedEffect(params, connectRetry) {
        val p = params
        if (p != null && deviceInfo == null && !connecting && connectError == null) {
            connecting = true
            api.lanConnect(p.host, p.port, p.token) { r ->
                connecting = false
                r.onSuccess { info ->
                    deviceInfo = LanDeviceInfo(
                        info.optString("device").ifBlank { "PC" },
                        info.optString("version"),
                    )
                }
                r.onFailure { e ->
                    connectError = e.message ?: context.getString(R.string.lan_sync_connect_failed)
                }
            }
        }
    }

    // ── 连接成功后加载实例列表 ────────────────────────────────────────
    LaunchedEffect(deviceInfo, listRetry) {
        val p = params
        if (deviceInfo != null && p != null && instances.isEmpty() && !instancesLoading) {
            instancesLoading = true
            instancesError = null
            api.lanListInstances(p.host, p.port, p.token) { r ->
                instancesLoading = false
                r.onSuccess { arr ->
                    instances.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        instances.add(
                            LanInstanceUi(
                                id = obj.optString("id"),
                                name = obj.optString("name"),
                                mcVersion = obj.optString("mc_version")
                                    .ifBlank { obj.optString("mcVersion") },
                                loader = obj.optString("loader"),
                                sizeBytes = obj.optLong("size_bytes"),
                                hasSaves = obj.optBoolean("has_saves"),
                                hasMods = obj.optBoolean("has_mods"),
                            )
                        )
                    }
                }
                r.onFailure { e ->
                    instancesError = e.message ?: context.getString(R.string.lan_sync_connect_failed)
                }
            }
        }
    }

    // ── 订阅下载进度 ──────────────────────────────────────────────────
    DisposableEffect(bridge) {
        val sub = bridge.listen("lan://progress") { payload ->
            try {
                val json = JSONObject(payload)
                val phase = json.optString("phase")
                downloadProgress = DownloadProgress(
                    phase = phase,
                    instanceName = json.optString("instanceName"),
                    bytesDone = json.optLong("bytesDone"),
                    bytesTotal = json.optLong("bytesTotal"),
                    message = if (json.has("message")) json.getString("message") else null,
                    speedBps = json.optLong("speedBps"),
                )
                if (phase == "error") {
                    downloading = false
                    downloadError = downloadProgress?.message
                        ?: context.getString(R.string.lan_sync_connect_failed)
                }
            } catch (_: Exception) { /* 忽略畸形事件 */ }
        }
        onDispose { bridge.unlisten(sub) }
    }

    // ── 下载队列（顺序执行）──────────────────────────────────────────
    fun startDownload() {
        val p = params ?: return
        val queue = instances.filter { it.id in selectedIds }
        if (queue.isEmpty()) return
        downloading = true
        downloadError = null
        downloadDoneCount = 0
        downloadTotalCount = queue.size
        downloadProgress = null

        fun downloadNext(index: Int) {
            if (index >= queue.size) {
                downloading = false
                // 队列跑完要有明确反馈：否则界面只是静静回到实例列表，
                // 用户不知道到底同步成功没有
                QkFeedback.show(context.getString(R.string.lan_sync_all_done, queue.size))
                selectedIds = emptySet()
                return
            }
            val inst = queue[index]
            api.lanDownloadInstance(p.host, p.port, p.token, inst.id, inst.name) { r ->
                r.onSuccess {
                    downloadDoneCount = index + 1
                    downloadNext(index + 1)
                }
                r.onFailure { e ->
                    downloading = false
                    downloadError = e.message
                        ?: context.getString(R.string.lan_sync_connect_failed)
                }
            }
        }
        downloadNext(0)
    }

    // ── UI ────────────────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!bridgeReady) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        when {
            // 下载中
            downloading -> {
                DownloadProgressSection(
                    progress = downloadProgress,
                    doneCount = downloadDoneCount,
                    totalCount = downloadTotalCount,
                )
            }
            // 下载出错
            downloadError != null -> {
                ErrorSection(
                    message = downloadError!!,
                    onRetry = { downloadError = null; startDownload() },
                    onBack = onBack,
                )
            }
            // 连接中
            connecting -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.lan_sync_connecting))
                    }
                }
            }
            // 连接出错
            connectError != null -> {
                ErrorSection(
                    message = connectError!!,
                    onRetry = {
                        connectError = null
                        deviceInfo = null
                        connectRetry += 1
                    },
                    onBack = { params = null; connectError = null },
                )
            }
            // 已连接 → 电脑上的实例（往下拉）+ 把本机实例发上去
            deviceInfo != null -> {
                val p = params
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        InstanceListSection(
                            deviceInfo = deviceInfo!!,
                            instances = instances,
                            selectedIds = selectedIds,
                            onToggle = { id ->
                                selectedIds = if (id in selectedIds) {
                                    selectedIds - id
                                } else {
                                    selectedIds + id
                                }
                            },
                            instancesLoading = instancesLoading,
                            instancesError = instancesError,
                            onRetry = { instancesError = null; listRetry += 1 },
                            onDownload = { startDownload() },
                            onDisconnect = {
                                params = null; deviceInfo = null; instances.clear()
                                selectedIds = emptySet(); connectError = null
                            },
                        )
                    }
                    if (p != null) {
                        LanSyncUploadSection(api = api, bridge = bridge, params = p)
                    }
                }
            }
            // 未连接 → 扫码 / 手动输入
            else -> {
                if (showManualInput) {
                    ManualInputSection(
                        onConnect = { host, port, token ->
                            params = LanSyncParams(host, port, token)
                            showManualInput = false
                        },
                        onSwitchToScanner = { showManualInput = false },
                    )
                } else {
                    ScannerSection(
                        onScanned = { text ->
                            val uri = Uri.parse(text)
                            // ⚠️ 2026-10-28 修（「扫码没反应」第二层原因）：
                            // 原来只是 `handle()` 写 pending，靠
                            // `LaunchedEffect(deepLinkPending)` 在 `params == null` 时才消费。
                            // 只要之前连过一次（或连接失败留下 params），条件不成立 →
                            // 扫到码也毫无反应。现在扫码直接接管：清掉旧会话、立刻连。
                            val parsed = LanSyncDeepLink.parse(uri)
                            if (parsed != null) {
                                LanSyncDeepLink.pending.value = null
                                deviceInfo = null
                                connectError = null
                                instances.clear()
                                selectedIds = emptySet()
                                params = parsed
                                connectRetry += 1
                            } else {
                                connectError = context.getString(R.string.lan_sync_bad_qr)
                            }
                        },
                        onSwitchToManual = { showManualInput = true },
                    )
                }
            }
        }
    }
}

// ── 扫码区 ───────────────────────────────────────────────────────────────

@Composable
private fun ScannerSection(
    onScanned: (String) -> Unit,
    onSwitchToManual: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ⚠️ 2026-10-28（真机逐页检查）：这里原来还有一行 headlineSmall 的
        // 「局域网同步」大标题 —— 而共用顶栏已经写着「QookiX 局域网同步」，
        // 同一屏出现两次页面名。删掉它，说明文字直接跟在顶栏下面。
        // （下面「手动输入」的大标题保留：那是切换后的子状态标题，不是页面名。）
        Text(
            stringResource(R.string.lan_sync_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp)),
        ) {
            LanSyncScanner(onScanned = onScanned)
        }
        OutlinedButton(onClick = onSwitchToManual, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.lan_sync_manual_input))
        }
    }
}

// ── 手动输入区 ───────────────────────────────────────────────────────────

@Composable
private fun ManualInputSection(
    onConnect: (host: String, port: Int, token: String) -> Unit,
    onSwitchToScanner: () -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8765") }
    var token by remember { mutableStateOf("") }

    Text(
        stringResource(R.string.lan_sync_manual_input),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.lan_sync_host)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = port,
        onValueChange = { port = it.filter { c -> c.isDigit() } },
        label = { Text(stringResource(R.string.lan_sync_port)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(stringResource(R.string.lan_sync_token)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Button(
        onClick = { onConnect(host.trim(), port.toIntOrNull() ?: 8765, token.trim()) },
        modifier = Modifier.fillMaxWidth(),
        enabled = host.isNotBlank() && token.isNotBlank(),
    ) {
        Text(stringResource(R.string.lan_sync_connect))
    }
    TextButton(onClick = onSwitchToScanner, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.lan_sync_scan))
    }
}

// ── 实例列表区 ───────────────────────────────────────────────────────────

@Composable
private fun InstanceListSection(
    deviceInfo: LanDeviceInfo,
    instances: List<LanInstanceUi>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    instancesLoading: Boolean,
    instancesError: String?,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.lan_sync_connected, deviceInfo.device),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (deviceInfo.version.isNotBlank()) {
                    Text(
                        "QookiX ${deviceInfo.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onDisconnect) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.lan_sync_reconnect))
            }
        }

        Text(
            stringResource(R.string.lan_sync_select),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            instancesLoading -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            instancesError != null -> {
                ErrorSection(message = instancesError, onRetry = onRetry, onBack = onDisconnect)
            }
            instances.isEmpty() -> {
                Text(
                    stringResource(R.string.lan_sync_no_instances),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(instances) { inst ->
                        InstanceCard(
                            instance = inst,
                            selected = inst.id in selectedIds,
                            onToggle = { onToggle(inst.id) },
                        )
                    }
                }
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedIds.isNotEmpty(),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.lan_sync_download))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstanceCard(
    instance: LanInstanceUi,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "${instance.mcVersion} · ${instance.loader}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val badges = mutableListOf<String>()
                if (instance.hasSaves) badges += stringResource(R.string.lan_sync_has_saves)
                if (instance.hasMods) badges += stringResource(R.string.lan_sync_has_mods)
                if (instance.sizeBytes > 0) badges += formatBytes(instance.sizeBytes)
                if (badges.isNotEmpty()) {
                    Text(
                        badges.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── 下载进度区 ───────────────────────────────────────────────────────────

@Composable
private fun DownloadProgressSection(
    progress: DownloadProgress?,
    doneCount: Int,
    totalCount: Int,
) {
    Text(
        if (totalCount > 1) {
            stringResource(R.string.lan_sync_progress_count, doneCount + 1, totalCount)
        } else {
            stringResource(R.string.lan_sync_downloading, progress?.instanceName ?: "")
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )

    val phase = progress?.phase
    when (phase) {
        "download" -> {
            val total = progress.bytesTotal
            val done = progress.bytesDone
            val speed = progress.speedBps

            if (total > 0) {
                val ratio = (done.toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 一行：已传 / 总量  ←→  百分比
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${formatBytes(done)} / ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${(ratio * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // PC 端没给 Content-Length（chunked 传输）—— 算不出百分比，
                // 但至少要让用户看到"在动、有多少、多快"，别只给一个无限转的条。
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "已传输 ${formatBytes(done)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 速度 + 剩余时间（total 未知时只显示速度）
            if (speed > 0) {
                val eta = if (total > done && total > 0) {
                    val secs = (total - done) / speed
                    " · 剩余 ${formatEta(secs)}"
                } else {
                    ""
                }
                Text(
                    "${formatBytes(speed)}/s$eta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        "extract" -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.lan_sync_extracting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        "done" -> {
            Text(
                stringResource(R.string.lan_sync_done),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        else -> {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

// ── 错误区 ───────────────────────────────────────────────────────────────

@Composable
private fun ErrorSection(
    message: String,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        stringResource(R.string.lan_sync_connect_failed),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
    )
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_retry))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.common_back))
        }
    }
}

// ── 工具 ─────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String {
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

/** 剩余时间的口语化写法：45 秒 / 3 分 20 秒 / 1 时 05 分。 */
private fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> "即将完成"
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 时 ${(seconds % 3600) / 60} 分"
}
