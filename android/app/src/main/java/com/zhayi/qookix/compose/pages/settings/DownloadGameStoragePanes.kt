package com.zhayi.qookix.compose.pages.settings

import com.zhayi.qookix.compose.theme.QkSpacing

import com.zhayi.qookix.compose.bridge.*

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import com.zhayi.qookix.compose.pages.settings.Renderer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 「下载」设置子页。
 *
 * 覆盖字段：download_threads / download_chunk_threads / auto_update / update_source
 */
@Composable
fun DownloadPane(
    state: SettingsUiState,
    onChange: (SettingsPatch) -> Unit,
    // 检查更新（由外层接线 —— 需要 QookixApi，这里拿不到）。
    // 传 null 表示宿主没提供，按钮变禁用态而不是「点了没反应」。
    onCheckUpdate: (() -> Unit)? = null,
    updateResult: String? = null,
    onDismissUpdateResult: (() -> Unit)? = null,
    // 镜像测速（可选，没传就不显示入口）
    onTestMirrors: (() -> Unit)? = null,
    mirrorTesting: Boolean = false,
    // null = 用本地化文案兜底（默认参数里不能调 stringResource）
    mirrorHint: String? = null,
    /** 结果弹层的标题（更新检查与镜像测速共用同一个弹层）。 */
    updateResultTitle: String? = null,
    // 网络代理（可选；没传就不显示该区）
    proxyMode: String = "system",
    proxyAddress: String = "",
    proxyDetecting: Boolean = false,
    proxyTesting: Boolean = false,
    proxyResult: String? = null,
    proxyError: String? = null,
    onProxyModeChange: ((String) -> Unit)? = null,
    onProxyAddressChange: ((String) -> Unit)? = null,
    onProxyDetect: (() -> Unit)? = null,
    onProxyTest: (() -> Unit)? = null,
) {
    // 默认值依赖字符串资源，不能写成 `= stringResource(...)` 的参数默认值
    //（默认参数里的 composable 调用有编译器限制），所以用 null/空串 + 兜底。
    SettingsPane(title = stringResource(R.string.settings_download_title)) {
        SettingsGroup(stringResource(R.string.settings_download_concurrency_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_download_parallel_files),
                subtitle = stringResource(R.string.settings_download_parallel_files_desc),
            ) {
                Text(
                    text = "${state.downloadThreads}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 4.dp)) {
                Slider(
                    value = state.downloadThreads.toFloat(),
                    onValueChange = { onChange(SettingsPatch.DownloadThreads(it.toInt())) },
                    valueRange = 1f..16f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsRow(
                title = stringResource(R.string.settings_download_chunk_threads),
                subtitle = stringResource(R.string.settings_download_chunk_threads_desc),
            ) {
                Text(
                    text = "${state.downloadChunkThreads}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 4.dp)) {
                Slider(
                    value = state.downloadChunkThreads.toFloat(),
                    onValueChange = { onChange(SettingsPatch.DownloadChunkThreads(it.toInt())) },
                    valueRange = 1f..16f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsRow(
                title = stringResource(R.string.settings_download_network_hint),
                subtitle = stringResource(R.string.settings_download_network_hint_desc),
                showDivider = false,
            ) {}
        }

        SettingsGroup(stringResource(R.string.settings_download_update_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_download_auto_update),
                subtitle = stringResource(R.string.settings_download_auto_update_desc),
            ) {
                Switch(
                    checked = state.autoUpdate,
                    onCheckedChange = { onChange(SettingsPatch.AutoUpdate(it)) },
                )
            }

            SettingsRow(
                title = stringResource(R.string.settings_download_update_source),
                subtitle = stringResource(R.string.settings_download_update_source_desc),
            column = true,
            ) {
                // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                 FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                 ) {
                    UpdateSource.entries.forEach { u ->
                        FilterChip(
                            selected = state.updateSource == u.id,
                            onClick = { onChange(SettingsPatch.UpdateSource(u.id)) },
                            label = { Text(stringResource(u.labelRes)) },
                        )
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_download_check_now),
                subtitle = stringResource(R.string.settings_download_check_now_desc),
                showDivider = false,
            ) {
                TextButton(
                    onClick = { onCheckUpdate?.invoke() },
                    enabled = onCheckUpdate != null,
                ) {
                    Text(stringResource(R.string.settings_download_check))
                }
            }
        }

        // ── 下载源测速 ────────────────────────────────────────────────
        //
        // 为什么需要：国内直连 GitHub / Mojang 经常很慢，
        // 换镜像能快十倍。但**不同镜像在不同网络下表现不同** ——
        // 与其让用户盲目试，不如直接测出耗时。
        // ── 网络代理 ──────────────────────────────────────────────────
        //
        // 放在「下载」页：代理影响全部网络请求，而下载是最常受影响的场景。
        if (onProxyModeChange != null) {
            ProxySection(
                mode = proxyMode,
                address = proxyAddress,
                detecting = proxyDetecting,
                testing = proxyTesting,
                result = proxyResult,
                error = proxyError,
                onModeChange = onProxyModeChange,
                onAddressChange = onProxyAddressChange ?: {},
                onDetect = onProxyDetect ?: {},
                onTest = onProxyTest ?: {},
            )
        }

        SettingsGroup(stringResource(R.string.settings_download_mirror_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_download_mirror_test),
                subtitle = mirrorHint
                    ?: stringResource(R.string.settings_download_mirror_hint),
                showDivider = false,
            ) {
                TextButton(
                    onClick = { onTestMirrors?.invoke() },
                    enabled = onTestMirrors != null && !mirrorTesting,
                ) {
                    Text(
                        if (mirrorTesting) stringResource(R.string.settings_download_testing)
                        else stringResource(R.string.settings_download_start_test)
                    )
                }
            }
        }
    }

    // 结果弹层（更新检查与镜像测速共用，靠标题区分）
    updateResult?.let { msg ->
        InfoDialog(
            title = updateResultTitle
                ?: stringResource(R.string.settings_download_update_result_title),
            body = msg,
            onDismiss = { onDismissUpdateResult?.invoke() },
        )
    }
}

/** 更新源。 */
enum class UpdateSource(val id: String, @StringRes val labelRes: Int) {
    Github("github", R.string.settings_download_source_github),
    Mirror("mirror", R.string.settings_download_source_mirror),
}

/**
 * 「游戏」设置子页。
 *
 * 覆盖字段：max_memory_mb / min_memory_mb / memory_mode / jvm_args / game_args / java_path
 */
@Composable
fun GamePane(
    state: SettingsUiState,
    onChange: (SettingsPatch) -> Unit,
    /** 弹一次「设备内存探测」并把建议值填进来。 */
    onDetectMemory: (() -> Unit)? = null,
    /** 探测结果（外层拉好后传入）。 */
    memoryInfo: MemoryInfoUi? = null,
    onDismissMemoryInfo: (() -> Unit)? = null,
    /** 当前渲染器键（opengles2 / mobileglues / vulkan_zink）。 */
    renderer: String = "opengles2",
    onRendererChange: ((String) -> Unit)? = null,
) {
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }

    SettingsPane(title = stringResource(R.string.settings_game_title)) {
        // ── 渲染器 ────────────────────────────────────────────────────
        //
        // ⚠️ 这一项直接决定「游戏能不能启动」。
        //
        // 实测（2026-10-13）：实例建好、文件下完，点启动却崩在
        //   UnsatisfiedLinkError: Failed to locate library: libmobileglues.so
        // 因为 Rust 的「按版本自动」规则对 MC 26.3 判定用 MobileGlues，
        // 而那个库不在 APK 里（已改为插件分发，APK 只随包 GL4ES 兜底）。
        //
        // 旧 Vue 界面有渲染器设置，我先前漏做 → 用户无从切换 → 启动必失败。
        if (onRendererChange != null) {
            SettingsGroup(stringResource(R.string.settings_game_renderer_group)) {
                SettingsRow(
                    title = stringResource(R.string.settings_game_current_renderer),
                    subtitle = Renderer.entries.firstOrNull { it.id == renderer }
                        ?.let { stringResource(it.hintRes) }
                        ?: stringResource(R.string.settings_game_renderer_wrong_hint),
                ) {
                    Text(
                        text = Renderer.entries.firstOrNull { it.id == renderer }
                            ?.let { stringResource(it.labelRes) } ?: renderer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier
                        .padding(horizontal = QkSpacing.md, vertical = 4.dp)
                        // ⚠️ 必须能横向滚动（2026-10-16 修，用户报的 bug：
                        // 「渲染器只看到 GL4ES 和 MobileGlues，Vulkan 不见了」）。
                        //
                        // 三个 chip（GL4ES / MobileGlues / Vulkan (Zink)）在
                        // 竖屏下总宽超过可用宽度，没有 horizontalScroll 时
                        // 最后一个会被挤出屏幕 —— 而且**不报错、不换行**，
                        // 表现就是"选项凭空少了一个"，很难归因。
                        //
                        // 这是本项目第 9 次踩同一类坑（详见各 Pane 的同类注释）。
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                ) {
                    Renderer.entries.forEach { r ->
                        FilterChip(
                            selected = renderer == r.id,
                            onClick = { onRendererChange(r.id) },
                            label = { Text(stringResource(r.labelRes)) },
                        )
                    }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_game_memory_group)) {
            // ── 设备内存探测 ──────────────────────────────────────────────
            //
            // 这个功能解决的是**最高频的困惑**：「我该给游戏分多少内存？」
            // 旧界面只能让用户自己猜，猜小了卡顿、猜大了被系统回收。
            //
            // Rust 侧 `auto_detect_memory` 读 /proc/meminfo 算出
            // 推荐上下限（它比前端更清楚设备实际可用内存）。
            if (onDetectMemory != null) {
                SettingsRow(
                    title = stringResource(R.string.settings_game_memory_suggestion),
                    // 原来这里是 MemoryInfoUi.summary（非 Composable 的 getter，
                    // 拿不到资源），改成在 Composable 里拼。
                    subtitle = memoryInfo?.let {
                        stringResource(
                            R.string.settings_game_memory_summary,
                            it.totalMb,
                            it.availableMb,
                            it.maxMb,
                        )
                    } ?: stringResource(R.string.settings_game_memory_detect_desc),
                ) {
                    TextButton(onClick = onDetectMemory) {
                        Text(stringResource(R.string.settings_game_detect))
                    }
                }

                // 探测结果卡片：把「总量/可用/建议」摆出来，
                // 让用户理解为什么是这个数，而不是盲点一个按钮
                memoryInfo?.let { info ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = QkSpacing.md, vertical = 6.dp),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(QkSpacing.md)) {
                            Text(
                                text = stringResource(R.string.settings_game_device_memory),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(Modifier.height(QkSpacing.xs))
                            MemoryLine(
                                stringResource(R.string.settings_game_total_memory),
                                "${info.totalMb} MB",
                            )
                            MemoryLine(
                                stringResource(R.string.settings_game_used_memory),
                                "${info.usedMb} MB",
                            )
                            MemoryLine(
                                stringResource(R.string.settings_game_available_memory),
                                "${info.availableMb} MB",
                            )
                            Spacer(Modifier.height(QkSpacing.xs))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm),
                            ) {
                                FilledTonalButton(onClick = {
                                    onChange(SettingsPatch.MaxMemory(info.maxMb))
                                    onChange(SettingsPatch.MinMemory(info.minMb))
                                    onDismissMemoryInfo?.invoke()
                                }) {
                                    Text(
                                        stringResource(
                                            R.string.settings_game_apply_suggestion,
                                            info.maxMb,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_game_alloc_mode),
                subtitle = stringResource(R.string.settings_game_alloc_mode_desc),
                column = true,
            ) {
                // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                 FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                 ) {
                    MemoryMode.entries.forEach { m ->
                        FilterChip(
                            selected = state.memoryMode == m.id,
                            onClick = { onChange(SettingsPatch.MemoryMode(m.id)) },
                            label = { Text(stringResource(m.labelRes)) },
                        )
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_game_max_memory),
                subtitle = stringResource(R.string.settings_game_max_memory_desc),
            ) {
                Text(
                    text = "${state.maxMemoryMb} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 4.dp)) {
                Slider(
                    value = state.maxMemoryMb.toFloat(),
                    onValueChange = { onChange(SettingsPatch.MaxMemory(it.toInt())) },
                    valueRange = 512f..8192f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SettingsRow(
                title = stringResource(R.string.settings_game_min_memory),
                subtitle = stringResource(R.string.settings_game_min_memory_desc),
                showDivider = false,
            ) {
                Text(
                    text = "${state.minMemoryMb} MB",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGroup(stringResource(R.string.settings_game_launch_args_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_game_jvm_args),
                subtitle = state.jvmArgs.ifBlank {
                    stringResource(R.string.settings_game_use_default)
                },
            ) {
                TextButton(onClick = { editTarget = EditTarget.JvmArgs }) {
                    Text(stringResource(R.string.settings_game_edit))
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_game_game_args),
                subtitle = state.gameArgs.ifBlank {
                    stringResource(R.string.settings_game_use_default)
                },
            ) {
                TextButton(onClick = { editTarget = EditTarget.GameArgs }) {
                    Text(stringResource(R.string.settings_game_edit))
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_game_java_path),
                subtitle = state.javaPath.ifBlank {
                    stringResource(R.string.settings_game_use_builtin_runtime)
                },
                showDivider = false,
            ) {
                TextButton(onClick = { editTarget = EditTarget.JavaPath }) {
                    Text(stringResource(R.string.settings_game_select))
                }
            }
        }
    }

    // ── 参数编辑对话框 ────────────────────────────────────────────────────
    //
    // 这三个字段都是「文本 patch」—— setSettings 的 patch 语义天然支持，
    // 所以不需要新命令，直接走 SettingsPatch。
    when (editTarget) {
        EditTarget.JvmArgs -> TextInputDialog(
            title = stringResource(R.string.settings_game_jvm_args),
            initial = state.jvmArgs,
            label = stringResource(R.string.settings_game_jvm_args),
            multiline = true,
            helperText = stringResource(R.string.settings_game_jvm_help),
            onDismiss = { editTarget = null },
            onConfirm = { v ->
                editTarget = null
                onChange(SettingsPatch.JvmArgs(v))
            },
        )

        EditTarget.GameArgs -> TextInputDialog(
            title = stringResource(R.string.settings_game_game_args),
            initial = state.gameArgs,
            label = stringResource(R.string.settings_game_game_args),
            multiline = true,
            helperText = stringResource(R.string.settings_game_game_help),
            onDismiss = { editTarget = null },
            onConfirm = { v ->
                editTarget = null
                onChange(SettingsPatch.GameArgs(v))
            },
        )

        EditTarget.JavaPath -> TextInputDialog(
            title = stringResource(R.string.settings_game_java_path),
            initial = state.javaPath,
            label = stringResource(R.string.settings_game_java_path_label),
            helperText = stringResource(R.string.settings_game_java_help),
            onDismiss = { editTarget = null },
            onConfirm = { v ->
                editTarget = null
                onChange(SettingsPatch.JavaPath(v))
            },
        )

        null -> Unit
    }
}

/** 「游戏」子页里可以就地编辑的字段。 */
private enum class EditTarget { JvmArgs, GameArgs, JavaPath }

/** 内存信息的一行（「总内存  6144 MB」）。 */
@Composable
private fun MemoryLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** 设备内存探测结果（对应 `auto_detect_memory` 的返回）。 */
data class MemoryInfoUi(
    val totalMb: Int,
    val usedMb: Int,
    val availableMb: Int,
    /** 建议的 JVM 上限。 */
    val maxMb: Int,
    /** 建议的 JVM 预分配。 */
    val minMb: Int,
)
/** 内存分配模式。 */
enum class MemoryMode(val id: String, @StringRes val labelRes: Int) {
    Auto("auto", R.string.settings_game_memory_mode_auto),
    Custom("custom", R.string.settings_game_memory_mode_custom),
}

/**
 * 可选游戏目录（对应 `get_game_dir_options` 的一条）。
 *
 * @param kind `internal` / `external`
 * @param freeBytes 剩余空间（字节）
 * @param removable 是否可插拔（SD 卡）
 */
data class GameDirOptionUi(
    val kind: String,
    val label: String,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
    val removable: Boolean,
)

/** 字节数转人类可读（GB / MB）。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(java.util.Locale.US, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(java.util.Locale.US, "%.0f MB", mb)
    return "$bytes B"
}

/** 「存储」设置子页。 */
@Composable
fun StoragePane(
    state: SettingsUiState,
    onChange: (SettingsPatch) -> Unit,
    bridge: com.zhayi.qookix.compose.bridge.RustBridge? = null,
    /** 外部改了设置后通知宿主重拉（切换游戏目录用）。 */
    onReloadSettings: () -> Unit = {},
) {
    val api = remember(bridge) {
        bridge?.let { com.zhayi.qookix.compose.bridge.QookixApi(it) }
    }
    // 回调（非 Composable）里要用字符串资源，先取 Context。
    val context = LocalContext.current

    // 弹层状态
    var showStats by remember { mutableStateOf(false) }
    var statsText by remember { mutableStateOf("") }
    var statsLoading by remember { mutableStateOf(false) }
    var statsError by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 选择游戏目录（B4）：SAF 选择器是**异步**的 —— `pick_game_dir` 只是
    // 发起请求，用户选完后结果写进 `<files>/game-dir-pick.json`，前端必须
    // 轮询 `take_picked_game_dir` 取回，再 `set_game_dir` 落地。
    // 以前只发 `pick_game_dir` 就当完事 → 选完目录界面毫无反应。
    var pickSeq by remember { mutableStateOf(0) }
    var dirMessage by remember { mutableStateOf<String?>(null) }

    // 游戏目录状态与可选目录。
    //
    // `get_game_dir_state` 返回实际生效的数据根、实例目录根，以及
    // Android 11+「所有文件访问」有没有授权 —— 这些光看 settings 里的
    // dataDir 是看不出来的（比如用户授权被系统撤销了）。
    //
    // `get_game_dir_options` 列出可选目录（内部存储 + 各卷的应用专属外部目录）
    // 及其剩余空间 —— 让用户知道装 SD 卡还够不够。
    var gameDirRoot by remember { mutableStateOf("") }
    var gameDirInstances by remember { mutableStateOf("") }
    var allFilesAccess by remember { mutableStateOf(false) }
    var gameDirOptions by remember { mutableStateOf<List<GameDirOptionUi>>(emptyList()) }

    LaunchedEffect(api, pickSeq) {
        val a = api ?: return@LaunchedEffect
        a.getGameDirState { r ->
            r.onSuccess { o ->
                gameDirRoot = o.optString("root")
                gameDirInstances = o.optString("instances_dir")
                allFilesAccess = o.optBoolean("all_files_access", false)
            }
        }
        a.getGameDirOptions { r ->
            r.onSuccess { arr ->
                gameDirOptions = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    GameDirOptionUi(
                        kind = o.optString("kind"),
                        label = o.optString("label"),
                        path = o.optString("path"),
                        freeBytes = o.optLong("free"),
                        totalBytes = o.optLong("total"),
                        removable = o.optBoolean("removable", false),
                    )
                }
            }
        }
    }

    LaunchedEffect(pickSeq) {
        if (pickSeq == 0 || api == null) return@LaunchedEffect
        // 用户可能在人系统弹窗里停留很久；给 2 分钟窗口，选完就立刻返回
        val deadline = System.currentTimeMillis() + 120_000L
        var handled = false
        while (!handled && System.currentTimeMillis() < deadline) {
            delay(500)
            val picked = suspendCancellableCoroutine<String?> { cont ->
                api.takePickedGameDir { r ->
                    val v = r.getOrNull()
                    // Rust 返回 Option<String>：没选过/已取消 都是 "null"
                    if (cont.isActive) {
                        cont.resume(v?.takeIf { it.isNotBlank() && it != "null" })
                    }
                }
            }
            if (picked != null) {
                handled = true
                api.setGameDir(picked, true) { sr ->
                    sr.fold(
                        onSuccess = {
                            dirMessage = context.getString(R.string.settings_game_dir_changed)
                            onReloadSettings()
                        },
                        onFailure = { e ->
                            dirMessage = context.getString(
                                R.string.settings_game_dir_change_failed,
                                friendlyError(context, e),
                            )
                        },
                    )
                }
            }
        }
    }

    SettingsPane(title = stringResource(R.string.settings_storage_title)) {
        SettingsGroup(stringResource(R.string.settings_storage_location_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_storage_game_dir),
                subtitle = state.dataDir.ifBlank {
                    stringResource(R.string.settings_storage_default_dir)
                },
                showDivider = false,
            ) {
                TextButton(onClick = {
                    // 发起系统目录选择器；结果由上面的轮询取回并落地
                    dirMessage = null
                    api?.pickGameDir { }
                    pickSeq++
                }) {
                    Text(stringResource(R.string.settings_game_select))
                }
            }
        }

        // ⚠️ 2026-10-25（真机事故）：默认目录在**应用私有空间**（/data/data/…），
        // 卸载 App 或"清除数据"会把实例、模组、**存档**一起删掉 ✗。
        // 那次一次 `adb uninstall` 就清空了 157 个模组和全部存档。
        // 所以在用默认目录时明确警示，并引导换到外部存储。
        if (state.dataDir.isBlank()) {
            Text(
                text = stringResource(R.string.settings_storage_default_dir_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 6.dp),
            )
        }

        // 选择结果提示（成功/失败）
        dirMessage?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 4.dp),
            )
        }

        // 实际生效的数据根 + 「所有文件访问」授权状态
        if (gameDirRoot.isNotBlank()) {
            SettingsGroup(stringResource(R.string.settings_storage_effective_group)) {
                SettingsRow(
                    title = stringResource(R.string.settings_storage_effective_root),
                    subtitle = gameDirRoot,
                ) {
                }
                SettingsRow(
                    title = stringResource(R.string.settings_storage_instances_dir),
                    subtitle = gameDirInstances.ifBlank { gameDirRoot },
                ) {
                }
                SettingsRow(
                    title = stringResource(R.string.settings_storage_all_files_access),
                    subtitle = stringResource(
                        if (allFilesAccess) {
                            R.string.settings_storage_all_files_access_granted
                        } else {
                            R.string.settings_storage_all_files_access_missing
                        }
                    ),
                    showDivider = false,
                ) {
                }
            }
        }

        // 可选目录（内部存储 / SD 卡等）+ 剩余空间
        if (gameDirOptions.isNotEmpty()) {
            SettingsGroup(stringResource(R.string.settings_storage_options_group)) {
                gameDirOptions.forEachIndexed { idx, opt ->
                    SettingsRow(
                        title = opt.label.ifBlank { opt.path },
                        subtitle = buildString {
                            append(opt.path)
                            if (opt.freeBytes > 0L) {
                                append("\n")
                                append(
                                    context.getString(
                                        R.string.settings_storage_free_space,
                                        formatBytes(opt.freeBytes),
                                        formatBytes(opt.totalBytes),
                                    )
                                )
                            }
                            if (opt.removable) {
                                append("\n")
                                append(context.getString(R.string.settings_storage_removable))
                            }
                        },
                        showDivider = idx != gameDirOptions.lastIndex,
                    ) {
                        if (opt.path.isNotBlank() && opt.path != gameDirRoot) {
                            TextButton(onClick = {
                                api?.setGameDir(opt.path, true) { r ->
                                    r.fold(
                                        onSuccess = {
                                            dirMessage = context.getString(R.string.settings_game_dir_changed)
                                            onReloadSettings()
                                        },
                                        onFailure = { e ->
                                            dirMessage = context.getString(
                                                R.string.settings_game_dir_change_failed,
                                                friendlyError(context, e),
                                            )
                                        },
                                    )
                                }
                            }) {
                                Text(stringResource(R.string.settings_game_use))
                            }
                        }
                    }
                }
            }
        }

        SettingsGroup(stringResource(R.string.settings_storage_cleanup_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_storage_shared_files),
                subtitle = stringResource(R.string.settings_storage_shared_files_desc),
            ) {
                TextButton(onClick = {
                    showStats = true
                    statsLoading = true
                    statsError = null
                    statsText = ""
                    // ⚠️ 用 refresh_storage_stats（强制重扫），不是 get_storage_stats。
                    //
                    // 实测踩到（2026-10-13）：存储统计显示 instance_count=0，
                    // 但设备上明明有实例。查 storage-cache.json 发现
                    // 「查看」读的是**缓存**，而缓存生成于实例创建之前。
                    //
                    // Rust 侧两个命令的分工：
                    //   get_storage_stats      → 读缓存（快，可能过期）
                    //   refresh_storage_stats  → 重新扫描（慢，但准确）
                    //
                    // 「查看」是用户主动要看准确数据，必须走 refresh。
                    api?.refreshStorageStats { r ->
                        statsLoading = false
                        r.fold(
                            onSuccess = { statsText = formatStorageStats(it, context) },
                            onFailure = {
                                statsError = context.getString(
                                    R.string.settings_storage_read_failed,
                                    friendlyError(context, it),
                                )
                            },
                        )
                    }
                }) {
                    Text(stringResource(R.string.settings_storage_view))
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_storage_cache),
                subtitle = stringResource(R.string.settings_storage_cache_desc),
                showDivider = false,
            ) {
                TextButton(onClick = { confirmClear = true }) {
                    Text(stringResource(R.string.settings_storage_clear))
                }
            }
        }

        SettingsGroup(stringResource(R.string.settings_storage_stats_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_storage_usage),
                subtitle = state.storageSummary.ifBlank {
                    stringResource(R.string.settings_storage_tap_for_details)
                },
                showDivider = false,
            ) {
                TextButton(onClick = {
                    showStats = true
                    statsLoading = true
                    statsError = null
                    statsText = ""
                    api?.refreshStorageStats { r ->
                        statsLoading = false
                        r.fold(
                            onSuccess = { statsText = formatStorageStats(it, context) },
                            onFailure = {
                                statsError = context.getString(
                                    R.string.settings_storage_read_failed,
                                    friendlyError(context, it),
                                )
                            },
                        )
                    }
                }) {
                    Text(stringResource(R.string.settings_storage_details))
                }
            }
        }
    }

    if (showStats) {
        InfoDialog(
            title = stringResource(R.string.settings_storage_usage),
            body = statsText,
            loading = statsLoading,
            error = statsError,
            onDismiss = { showStats = false },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.settings_storage_clear_cache_title),
            message = stringResource(R.string.settings_storage_clear_cache_message),
            confirmText = stringResource(R.string.settings_storage_clear),
            danger = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                api?.clearCache { r ->
                    r.fold(
                        onSuccess = {
                            toast = context.getString(R.string.settings_storage_cache_cleared)
                        },
                        onFailure = { e ->
                            toast = context.getString(
                                R.string.settings_storage_clear_failed,
                                e.message,
                            )
                        },
                    )
                }
            },
        )
    }

    // 操作结果提示（用一个轻量的对话框，避免引入 SnackbarHost 的接线）
    toast?.let { msg ->
        InfoDialog(
            title = stringResource(R.string.settings_storage_done),
            body = msg,
            onDismiss = { toast = null },
        )
    }
}

/**
 * 把存储统计 JSON 格式化成可读文本。
 *
 * 字段名不固定（Rust 侧手写 json! 是 camelCase，结构体是 snake_case），
 * 所以做**语义识别**而不是硬编码 key：凡是名字里含 total/size/bytes 的
 * 数值字段都按字节格式化。这样新增字段也不会显示成裸数字。
 */
private fun formatStorageStats(json: org.json.JSONObject, context: android.content.Context): String {
    val sb = StringBuilder()
    json.keys().asSequence().sorted().forEach { k ->
        val v = json.opt(k)
        val label = when {
            k.contains("total", true) ->
                context.getString(R.string.settings_storage_stat_total, k)
            k.contains("used", true) ->
                context.getString(R.string.settings_storage_stat_used, k)
            k.contains("free", true) || k.contains("avail", true) ->
                context.getString(R.string.settings_storage_stat_free, k)
            k.contains("cache", true) ->
                context.getString(R.string.settings_storage_stat_cache, k)
            else -> k
        }
        val shown = when {
            v is Number && (
                k.contains("size", true) || k.contains("bytes", true) ||
                    k.contains("total", true) || k.contains("used", true) ||
                    k.contains("free", true) || k.contains("avail", true) ||
                    k.contains("cache", true)
                ) -> humanBytes(v.toLong())
            v is org.json.JSONObject -> "\n" + formatJsonForDisplay(v, 1)
            else -> v?.toString() ?: "—"
        }
        sb.append(label).append(": ").append(shown).append('\n')
    }
    return sb.toString().trimEnd()
        .ifBlank { context.getString(R.string.settings_storage_no_stats) }
}
















