package com.zhayi.qookix.compose.pages.settings

import android.util.Log
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONObject

/**
 * 设置的数据层。
 *
 * 两个命令：
 *   get_settings     → 读全部设置（返回 Settings 结构）
 *   update_settings  → 传 **patch**（只含改动字段），不是全量覆盖
 *
 * ⚠️ 字段名：Rust 的 `Settings` 结构体带 `#[serde(rename_all = "camelCase")]`
 * （见 `settings.rs`），所以**读写都是 camelCase**：
 *   uiScale / themeColor / backgroundImage / showHomeDownloads ...
 */
class SettingsRepo(private val api: QookixApi) {

    companion object {
        private const val TAG = "SettingsRepo"
    }

    /** 读取设置。 */
    fun load(onResult: (Result<SettingsUiState>) -> Unit) {
        api.getSettings { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parse(it))) },
                onFailure = { e ->
                    Log.w(TAG, "读设置失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 更新一个设置项（patch 形式）。 */
    fun update(patch: SettingsPatch, onResult: (Result<String>) -> Unit = {}) {
        val (key, value) = patch.toJson()
        val payload = JSONObject().put(key, value)
        api.updateSettings(payload) { r ->
            r.onFailure { Log.w(TAG, "更新设置 $key 失败: ${it.message}") }
            onResult(r)
        }
    }

    /**
     * 读取一个设置字段，**同时兼容 camelCase 与 snake_case**。
     *
     * ⚠️⚠️ 2026-10-15 修（真机实测发现）：
     *
     * Rust 的 `Settings` 结构体**没有** `#[serde(rename_all = "camelCase")]`
     * （与 `ExportOptions` 等不同），所以 `get_settings` 返回的是
     * **snake_case**：`theme_color` / `ui_scale` / `show_news` …
     *
     * 而这里原来**全部按 camelCase 读** —— 于是每个字段都读不到，
     * 全部落到默认值。表现是：
     *   - 设置页显示的颜色/缩放与实际 settings.json 不符
     *   - 更糟的是「改一项」时会用这些**错的默认值**回传根节点，
     *     把用户真正选过的值覆盖掉（实测：选过绿色，拖一下缩放，
     *     主题被覆盖成默认粉）。
     *
     * 写方向（`update_settings_patch`）用的是 camelCase ——
     * Rust 侧对 patch 做了别名白名单（`["theme_color", "themeColor"]`），
     * 两个都认，所以写方向不用改。
     *
     * 这里两个键都试，避免以后再被命名坑一次。
     */
    private fun pick(json: JSONObject, camel: String, snake: String): String? =
        when {
            json.has(camel) -> json.optString(camel)
            json.has(snake) -> json.optString(snake)
            else -> null
        }

    private fun str(json: JSONObject, camel: String, snake: String, def: String): String =
        pick(json, camel, snake)?.takeIf { it.isNotBlank() } ?: def

    private fun bool(json: JSONObject, camel: String, snake: String, def: Boolean): Boolean {
        val v = pick(json, camel, snake) ?: return def
        return v.equals("true", ignoreCase = true) || v == "1"
    }

    private fun int(json: JSONObject, camel: String, snake: String, def: Int): Int =
        (pick(json, camel, snake)?.toIntOrNull()) ?: def

    /** 解析设置。只取本阶段用到的字段，其余保持默认。 */
    fun parse(json: JSONObject): SettingsUiState {
        return SettingsUiState(
            // 外观
            theme = str(json, "theme", "theme", "system"),
            themeColor = str(json, "themeColor", "theme_color", ""),
            // ⚠️ 上限 150（与 AppearancePane 的滑杆一致）：旧配置里若有 200，
            // 读进来会直接把布局撑坏，而且用户很难自己调回去（自锁）。
            uiScale = int(json, "uiScale", "ui_scale", 100).coerceIn(60, 150),
            sidebarWidth = int(json, "sidebarWidth", "sidebar_width", 168).coerceIn(140, 260),
            previewRenderMode = str(json, "previewRenderMode", "preview_render_mode", "compose"),
            backgroundImage = str(json, "backgroundImage", "background_image", ""),
            showHomeDownloads = bool(json, "showHomeDownloads", "show_home_downloads", true),
            showHomeRecent = bool(json, "showHomeRecent", "show_home_recent", true),
            showHomeStats = bool(json, "showHomeStats", "show_home_stats", true),
            showNews = bool(json, "showNews", "show_news", true),

            // 常规
            closeBehavior = str(json, "closeBehavior", "close_behavior", "minimize"),
            keepOpen = bool(json, "keepOpen", "keep_open", true),
            autoLaunch = bool(json, "autoLaunch", "auto_launch", false),
            orientation = str(json, "orientation", "orientation", "system"),
            mirror = str(json, "mirror", "mirror", "official"),

            // 下载
            downloadThreads = int(json, "downloadThreads", "download_threads", 4).coerceIn(1, 16),
            downloadChunkThreads = int(json, "downloadChunkThreads", "download_chunk_threads", 4)
                .coerceIn(1, 16),
            autoUpdate = bool(json, "autoUpdate", "auto_update", true),
            updateSource = str(json, "updateSource", "update_source", "github"),

            // 游戏
            memoryMode = str(json, "memoryMode", "memory_mode", "auto"),
            maxMemoryMb = int(json, "maxMemoryMb", "max_memory_mb", 2048).coerceIn(512, 16384),
            minMemoryMb = int(json, "minMemoryMb", "min_memory_mb", 512).coerceIn(128, 8192),
            jvmArgs = str(json, "jvmArgs", "jvm_args", ""),
            gameArgs = str(json, "gameArgs", "game_args", ""),
            javaPath = str(json, "javaPath", "java_path", ""),

            // 存储
            dataDir = str(json, "dataDir", "data_dir", ""),

            // ── 网络代理 ────────────────────────────────────────────────────
            //
            // ⚠️⚠️ 这两项之前**完全没解析**（2026-10-17 修）。
            //
            // 后果：ProxySection 是完整实现的，用户改成「自定义」并填好地址 →
            // patch 落盘成功（Rust 侧确实在用）→ 但重进设置页时 `state.proxyMode`
            // 还是默认的 "system" → UI **跳回「跟随系统」、地址框消失**。
            // 用户看到的是「我填的代理没保存」，实际上值一直在文件里。
            //
            // Rust 字段名：proxy_mode（String）/ proxy（Option<String>，空值时空串）。
            proxyMode = str(json, "proxyMode", "proxy_mode", "system"),
            proxyAddress = str(json, "proxyAddress", "proxy", ""),

            // ── 内容服务 ────────────────────────────────────────────────────
            //
            // ⚠️ 同理：这三项之前只写不读（2026-10-17 修）。
            // 用户填了 CurseForge Key、切了翻译服务、开了自动翻译 →
            // 重进设置页/重启后**全部显示默认值**，看起来像没保存。
            curseforgeKey = str(json, "curseforgeApiKey", "curseforge_api_key", ""),
            translateProvider = str(json, "translateProvider", "translate_provider", "default"),
            autoTranslate = bool(json, "autoTranslate", "body_translate_auto", false),
            // 自定义翻译 API（provider 选「自定义」时用）
            translateApiBase = str(json, "translateApiBase", "translate_api_base", ""),
            translateApiKey = str(json, "translateApiKey", "translate_api_key", ""),
            translateApiModel = str(json, "translateApiModel", "translate_api_model", ""),
        )
    }
}

