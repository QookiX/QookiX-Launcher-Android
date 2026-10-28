package com.zhayi.qookix.compose.pages

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.optText
import org.json.JSONObject

/**
 * 实例详情「设置」tab 的编辑状态（值 / 载入 / 保存）。
 *
 * ── 为什么要单独一个类（2026-10-26 修）──────────────────────────────
 * 以前这四件状态直接写在 `PageHost` 里，且**从来没有读过真实配置**：
 * `settings` 永远是 [InstanceSettingsUiState] 的默认值（2048MB、renderer=auto、
 * 别名空、检查文件开），而保存时又把**全部**字段打包成 patch 发出去。
 * 结果就是「改一项保存 = 整个实例配置被默认值覆盖」：用户只把内存从 4096
 * 调到 3072，渲染器和 jvm 参数跟着被重置成默认值。
 *
 * 现在进「设置」tab 时先 `get_instance` 读一次真实值再上屏；保存语义不变
 * （patch 全量提交，但提交的是**读回来的值**，不再是无中生有）。
 */
@Stable
class InstanceSettingsController(
    private val instanceId: String,
    private val api: QookixApi,
    private val context: Context,
) {
    var value by mutableStateOf(InstanceSettingsUiState())
        private set
    var saving by mutableStateOf(false)
        private set
    /** 保存成功提示（`null` = 不显示）。 */
    var hint by mutableStateOf<String?>(null)
    /** 载入 / 保存失败（`null` = 不显示）。 */
    var error by mutableStateOf<String?>(null)
    private var loaded = false

    /**
     * 读一次真实配置。失败时 `loaded` 保持 false —— 下次再进这个 tab 会重试，
     * 而不是拿默认值去覆盖用户真正的配置。
     */
    fun ensureLoaded() {
        if (loaded) return
        api.getInstance(instanceId) { r ->
            r.fold(
                onSuccess = { o ->
                    value = InstanceSettingsUiState(
                        // 缺省/为 null 时用与 Rust 侧一致的语义：
                        // memory_mode 空 = global，renderer 空 = auto，
                        // check_files 缺省 = 开启（只有显式 false 才是关）。
                        memoryMode = o.optString("memory_mode").ifBlank { "global" },
                        maxMemoryMb = o.optInt("max_memory_mb").takeIf { it > 0 } ?: 2048,
                        // ⚠️ 不能 optString：字段为 JSON null 时它返回字面量 "null"，
                        // 输入框里就会显示 "null"（真机竖屏审计发现）。
                        jvmArgs = o.optText("jvm_args").orEmpty(),
                        gameArgs = o.optText("game_args").orEmpty(),
                        alias = o.optText("alias").orEmpty(),
                        checkFilesOnLaunch = o.optBoolean("check_files_on_launch", true),
                        renderer = o.optString("renderer").ifBlank { "auto" },
                    )
                    loaded = true
                    error = null
                },
                onFailure = { e ->
                    error = context.getString(
                        R.string.instance_settings_load_failed,
                        e.message ?: "",
                    )
                },
            )
        }
    }

    /** 本地乐观更新（不打 IPC）；保存时一次性提交。 */
    fun applyPatch(patch: InstanceSettingsPatch) {
        value = when (patch) {
            is InstanceSettingsPatch.MemoryMode -> value.copy(memoryMode = patch.value, dirty = true)
            is InstanceSettingsPatch.MaxMemory -> value.copy(maxMemoryMb = patch.value, dirty = true)
            is InstanceSettingsPatch.JvmArgs -> value.copy(jvmArgs = patch.value, dirty = true)
            is InstanceSettingsPatch.GameArgs -> value.copy(gameArgs = patch.value, dirty = true)
            is InstanceSettingsPatch.Alias -> value.copy(alias = patch.value, dirty = true)
            is InstanceSettingsPatch.CheckFiles -> value.copy(checkFilesOnLaunch = patch.value, dirty = true)
            is InstanceSettingsPatch.Renderer -> value.copy(renderer = patch.value, dirty = true)
        }
        hint = null
        error = null
    }

    /** 提交。命令名是 `update_instance`（不是 update_instance_settings）。 */
    fun save() {
        if (saving) return
        saving = true
        error = null
        val s = value
        val patch = JSONObject()
            .put("id", instanceId)
            // patch 语义：Rust 只认白名单里的键，所以有值的字段都要带上。
            .put("memory_mode", s.memoryMode)
            .put("max_memory_mb", s.maxMemoryMb)
            .put("jvm_args", s.jvmArgs)
            .put("game_args", s.gameArgs)
            .put("alias", s.alias.trim())
            .put("check_files_on_launch", s.checkFilesOnLaunch)
            // 渲染器不加的话保存时会被忽略，用户以为切了渲染器。
            .put("renderer", s.renderer)
        api.updateInstance(patch) { r ->
            saving = false
            r.fold(
                onSuccess = {
                    value = value.copy(dirty = false)
                    hint = context.getString(R.string.saved)
                },
                onFailure = { e ->
                    error = context.getString(R.string.settings_save_failed, e.message)
                },
            )
        }
    }
}

/**
 * 记住一个 [InstanceSettingsController]，并在进入「设置」tab 时读真实配置。
 *
 * 只在**没有未保存改动**时读：用户正在编辑时切走再切回，不能把输入冲掉。
 */
@Composable
fun rememberInstanceSettings(
    instanceId: String,
    tab: InstanceTab,
    api: QookixApi,
    context: Context,
): InstanceSettingsController {
    val ctl = remember(instanceId) { InstanceSettingsController(instanceId, api, context) }
    LaunchedEffect(instanceId, tab) {
        if (tab == InstanceTab.Settings && !ctl.value.dirty) ctl.ensureLoaded()
    }
    return ctl
}
