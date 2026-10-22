package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import com.zhayi.qookix.compose.pages.settings.Renderer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.zhayi.qookix.R

/**
 * 实例「设置」tab。
 *
 * 对应旧界面 `components/instance/SettingsTab.vue`（820 行）。
 *
 * ── 这个 tab 的价值 ────────────────────────────────────────────────────
 * 全局设置只能给**一套**内存/参数，而不同整合包需求差别很大：
 *   - 轻量整合包 2 GB 够，重型整合包要 6 GB
 *   - 有的整合包必须用特定 JVM 参数（如 G1GC 调优）
 *   - 有的需要绑特定账号（正版/离线分开）
 *
 * 所以**实例级覆盖**是刚需，不是锦上添花。
 *
 * ── ⚠️ 顺带修掉旧前端的一个 Bug ────────────────────────────────────────
 * 旧 `api.ts` 的 `updateInstance` 发的是命令 `update_instance_settings`，
 * 但 Rust 注册的是 **`update_instance`** —— 那条命令根本不存在。
 *
 * 也就是说**旧界面的「保存」是静默失效的**（Tauri 找不到命令会 reject，
 * 旧代码把它吞在 catch 里，用户以为改好了）。
 *
 * Compose 侧用正确的命令名，并在保存失败时明确报错。
 */
@Composable
fun InstanceSettingsTab(
    state: InstanceSettingsUiState,
    onChange: (InstanceSettingsPatch) -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    savedHint: String?,
    error: String?,
    onDismissHint: () -> Unit,
    // ── 导出整合包 ────────────────────────────────────────────────────
    /** 导出体检结果（点「导出」时拉取）。 */
    exportPlan: ExportPlanUi? = null,
    exportPlanLoading: Boolean = false,
    exporting: Boolean = false,
    exportHint: String? = null,
    /** 触发导出（参数是三项开关）。 */
    onExport: (includeConfig: Boolean, includeSaves: Boolean, includeShots: Boolean) -> Unit = { _, _, _ -> }
) {
    // 导出选项的本地状态（三项开关，默认照搬旧 UI：配置带上、存档/截图不带）
    var exportConfig by remember { mutableStateOf(true) }
    var exportSaves by remember { mutableStateOf(false) }
    var exportShots by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 内存 ──────────────────────────────────────────────────────
        SettingsCard(stringResource(R.string.instance_settings_card_memory)) {
            FieldLabel(stringResource(R.string.instance_settings_memory_mode))
            // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
             FlowRow(
                 horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
             ) {
                MemoryMode2.entries.forEach { m ->
                    FilterChip(
                        selected = state.memoryMode == m.id,
                        onClick = { onChange(InstanceSettingsPatch.MemoryMode(m.id)) },
                        label = { Text(stringResource(m.labelRes)) },
                    )
                }
            }
            Text(
                text = stringResource(MemoryMode2.entries.first { it.id == state.memoryMode }.hintRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            // 只有「手动」时才显示滑杆 —— 跟随全局/自动时调它没有意义
            if (state.memoryMode == "custom") {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.instance_settings_memory_limit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${state.maxMemoryMb} MB",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = state.maxMemoryMb.toFloat(),
                    onValueChange = { onChange(InstanceSettingsPatch.MaxMemory(it.toInt())) },
                    valueRange = 512f..8192f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ── 启动参数 ──────────────────────────────────────────────────
        // ── 渲染器 ────────────────────────────────────────────────────
        //
        // ⚠️ 这一项直接决定「游戏能不能启动」（2026-10-13 实测定位）。
        //
        // Rust 的 `resolve_renderer` **只认实例的 `renderer` 字段**：
        //   - 空 / "auto" → 按 MC 版本自动挑
        //   · **MC 26.3 会被自动挑成 MobileGlues**
        //   - "global"    → 读 Pojav 偏好里的全局值
        //   - 其它具体键  → 就用它
        //
        // 而 MobileGlues **不在 APK 里**（改插件分发了，APK 只随包 GL4ES），
        // 于是必然崩在 `UnsatisfiedLinkError: libmobileglues.so`。
        //
        // 旧 Vue 的实例设置里有这个三态选择，我先前漏做 ——
        SettingsCard(stringResource(R.string.instance_settings_card_renderer)) {
            // ⚠️ 横屏下这页内容很高（内存 + 渲染器 + 参数 + 别名 + 其他），
            // 所以把「模式」与「具体渲染器」压成**一组 chips 单行**：
            //   [按版本自动] [跟随全局] [GL4ES] [MobileGlues] [Vulkan]
            //
            // 之前是两行（先选模式、再选渲染器），在 384dp 可用高度下
            // 第二行会被挤出屏幕 —— 用户根本看不到 GL4ES 这个选项。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RendererMode.entries.forEach { m ->
                    FilterChip(
                        selected = state.renderer == m.id,
                        onClick = { onChange(InstanceSettingsPatch.Renderer(m.id)) },
                        label = { Text(stringResource(m.labelRes)) },
                    )
                }
                // 具体渲染器直接并排 —— 点它即切到「手动指定」态，
                // 不用先选模式再选值（少一步，且不会看不到）
                Renderer.entries.forEach { r ->
                    FilterChip(
                        selected = state.renderer == r.id,
                        onClick = { onChange(InstanceSettingsPatch.Renderer(r.id)) },
                        label = { Text(stringResource(r.labelRes)) },
                    )
                }
            }
            Text(
                text = when (state.renderer) {
                    RendererMode.Auto.id, "" ->
                        stringResource(R.string.instance_settings_renderer_hint_auto)
                    RendererMode.Global.id -> stringResource(R.string.instance_settings_renderer_hint_global)
                    else -> stringResource(R.string.instance_settings_renderer_hint_custom)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        SettingsCard(stringResource(R.string.instance_settings_card_launch_args)) {
            OutlinedTextField(
                value = state.jvmArgs,
                onValueChange = { onChange(InstanceSettingsPatch.JvmArgs(it)) },
                label = { Text(stringResource(R.string.instance_settings_jvm_args)) },
                placeholder = { Text(stringResource(R.string.instance_settings_follow_global_hint)) },
                singleLine = false,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.gameArgs,
                onValueChange = { onChange(InstanceSettingsPatch.GameArgs(it)) },
                label = { Text(stringResource(R.string.instance_settings_game_args)) },
                placeholder = { Text(stringResource(R.string.instance_settings_follow_global_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── 别名（qookix:// 协议）─────────────────────────────────────
        SettingsCard(stringResource(R.string.instance_settings_card_alias)) {
            OutlinedTextField(
                value = state.alias,
                onValueChange = { onChange(InstanceSettingsPatch.Alias(it)) },
                label = { Text(stringResource(R.string.instance_settings_alias_label)) },
                placeholder = { Text(stringResource(R.string.instance_settings_alias_placeholder)) },
                singleLine = true,
                supportingText = {
                    Text(
                        // 说清楚这个字段有什么用 —— 否则用户不知道填来干嘛
                        text = stringResource(R.string.instance_settings_alias_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
        }

        // ── 启动前检查 ────────────────────────────────────────────────
        SettingsCard(stringResource(R.string.instance_settings_card_misc)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.instance_settings_check_files),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.instance_settings_check_files_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Switch(
                    checked = state.checkFilesOnLaunch,
                    onCheckedChange = { onChange(InstanceSettingsPatch.CheckFiles(it)) },
                )
            }
        }


        // ── 导出整合包（对应旧 UI SettingsTab.vue 的导出区）────────────
        //
        // 流程：先 `plan_modpack_export` 做**纯本地体检**（点开即有，不联网），
        // 把「能只存下载链接的」与「必须打包文件本体的」分开统计；
        // 用户勾选要带的内容（存档/截图/配置）后再 `export_modpack`。
        //
        // 三项开关的默认值照搬旧 UI：**配置带上、存档/截图不带** ——
        // 后两者体积大且含个人内容，要让用户显式勾。
        SettingsCard(stringResource(R.string.instance_settings_card_export)) {
            when {
                exportPlanLoading -> Text(
                    stringResource(R.string.instance_settings_export_planning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exportPlan == null -> Text(
                    stringResource(R.string.instance_settings_export_no_plan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val p = exportPlan!!
                    Text(
                        text = "${p.fileName} · MC ${p.mcVersion} · ${p.loader}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.instance_settings_export_summary,
                            p.linked,
                            p.bundledCount,
                            fmtBytes(p.bundledBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            ExportToggle(stringResource(R.string.instance_settings_export_include_config), exportConfig) { exportConfig = it }
            ExportToggle(
                stringResource(R.string.instance_settings_export_include_saves),
                exportSaves,
                enabled = exportPlan?.hasSaves ?: false,
            ) { exportSaves = it }
            ExportToggle(
                stringResource(R.string.instance_settings_export_include_screenshots),
                exportShots,
                enabled = exportPlan?.hasScreenshots ?: false,
            ) { exportShots = it }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onExport(exportConfig, exportSaves, exportShots) },
                enabled = !exporting,
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (exporting) stringResource(R.string.instance_settings_exporting)
                    else stringResource(R.string.instance_settings_export),
                )
            }

            exportHint?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // ── 保存 ──────────────────────────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onSave, enabled = !saving) {
                Text(
                    if (saving) stringResource(R.string.instance_settings_saving)
                    else stringResource(R.string.instance_settings_save),
                )
            }
            if (state.dirty && !saving) {
                Text(
                    text = stringResource(R.string.instance_settings_dirty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        savedHint?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissHint) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        }
    }
}

/** 一行「开关 + 标签」（导出选项）。 */
@Composable
private fun ExportToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.outline,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** 导出体检结果（对应 Rust `plan_modpack_export`）。 */
data class ExportPlanUi(
    val fileName: String = "",
    val mcVersion: String = "",
    val loader: String = "",
    /** 能只存下载链接的项数。 */
    val linked: Int = 0,
    /** 需要打包文件本体的总项数。 */
    val bundledCount: Int = 0,
    val bundledBytes: Long = 0,
    val hasSaves: Boolean = false,
    val hasScreenshots: Boolean = false,
    val hasConfig: Boolean = false,
)

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 实例级内存分配模式。
 *
 * 与全局的三态不同 —— 这里多了「跟随全局」：
 *   - global：用全局设置的值（多数用户）
 *   - auto：按设备内存自动算（不管全局怎么设）
 *   - custom：这个实例单独指定
 */
enum class MemoryMode2(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    Global(
        "global",
        R.string.instance_settings_memory_mode_global,
        R.string.instance_settings_memory_mode_global_hint,
    ),
    Auto(
        "auto",
        R.string.instance_settings_memory_mode_auto,
        R.string.instance_settings_memory_mode_auto_hint,
    ),
    Custom(
        "custom",
        R.string.instance_settings_memory_mode_custom,
        R.string.instance_settings_memory_mode_custom_hint,
    ),
}

/** 实例设置的修改项。 */
sealed class InstanceSettingsPatch {
    data class MemoryMode(val value: String) : InstanceSettingsPatch()
    data class MaxMemory(val value: Int) : InstanceSettingsPatch()
    data class JvmArgs(val value: String) : InstanceSettingsPatch()
    data class GameArgs(val value: String) : InstanceSettingsPatch()
    data class Alias(val value: String) : InstanceSettingsPatch()
    data class CheckFiles(val value: Boolean) : InstanceSettingsPatch()
    /** 渲染器：auto（按版本自动）/ global（跟随全局）/ opengles2 等具体键。 */
    data class Renderer(val value: String) : InstanceSettingsPatch()

    /**
     * 转成 Rust `update_instance` 期望的 patch 字段。
     *
     * ⚠️ 字段名以 **Rust 侧的 `pick(&patch, &[...])` 白名单**为准，
     * 不是照抄前端 —— Rust 同时接受 camelCase 与 snake_case
     * （如 `maxMemoryMb` / `max_memory_mb`），这里统一用 snake_case。
     */
    fun toJson(): Pair<String, Any> = when (this) {
        is MemoryMode -> "memory_mode" to value
        is MaxMemory -> "max_memory_mb" to value
        is JvmArgs -> "jvm_args" to value
        is GameArgs -> "game_args" to value
        is Alias -> "alias" to value
            is CheckFiles -> "check_files_on_launch" to value
            // 渲染器字段名是 `renderer`（Rust 的 pick 白名单里有）
            is Renderer -> "renderer" to value
    }
}

/**
 * 实例级渲染器模式。
 *
 * ⚠️ 键值必须与 Rust `resolve_renderer` 的 match 分支对齐：
 *   - `auto`   → 按 MC 版本自动（**MC 26.3 会挑 MobileGlues，而它不在包里**）
 *   - `global` → 读 Pojav 偏好的全局渲染器
 *   - 其它     → 当成具体渲染器键（opengles2 / mobileglues / vulkan_zink）
 */
enum class RendererMode(
    val id: String,
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
) {
    Auto(
        id = "auto",
        labelRes = R.string.instance_settings_renderer_mode_auto,
        hintRes = R.string.instance_settings_renderer_mode_auto_hint,
    ),
    Global(
        id = "global",
        labelRes = R.string.instance_settings_renderer_mode_global,
        hintRes = R.string.instance_settings_renderer_mode_global_hint,
    ),
    Custom(
        id = "opengles2",
        labelRes = R.string.instance_settings_renderer_mode_custom,
        hintRes = R.string.instance_settings_renderer_mode_custom_hint,
    ),
}

/** 实例设置页状态。 */
data class InstanceSettingsUiState(
    val memoryMode: String = "global",
    val maxMemoryMb: Int = 2048,
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val alias: String = "",
    val checkFilesOnLaunch: Boolean = true,
    /** 渲染器键（auto / global / opengles2 / mobileglues / vulkan_zink）。 */
    val renderer: String = "auto",
    /** 有未保存的改动（用于显示提示与决定是否可离开）。 */
    val dirty: Boolean = false,
)












