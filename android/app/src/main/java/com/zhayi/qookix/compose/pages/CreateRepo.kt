package com.zhayi.qookix.compose.pages

import android.util.Log
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 新建实例的数据层。
 *
 * 两个命令：
 *   get_version_manifest  → 拉 Minecraft 版本清单（联网）
 *   create_instance       → 创建实例（本地）
 *
 * 返回形状（见 commands.rs::get_version_manifest）：
 * ```json
 * { "versions": [ { "id": "1.21.4", "type": "release", "releaseTime": "..." } ],
 *   "latest": { "release": "1.21.4", "snapshot": "..." } }
 * ```
 */
class CreateRepo(private val api: QookixApi) {

    companion object {
        private const val TAG = "CreateRepo"

        /**
         * 按 MC 版本挑一个**能跑**的默认渲染器。
         *
         * ── 为什么不能依赖 Rust 的「按版本自动」─────────────────────────
         *
         * Rust 侧 `renderer_pick::auto_renderer_for` 原来的规则是：
         * ```text
         *   MC >= 26 → mobileglues
         *   其余      → opengles2（GL4ES）
         * ```
         * 注释写「其余版本 GL4ES 兼容性最好（1.8.9 / 1.18.2 实测能跑）」，
         * **但这条经验在 1.21.x 上不成立**（Rust 侧已于 2026-10-26 一并修正，
         * 现在两边规则一致；这里保留是为了新建实例时直接写对默认值）。真机实测（OnePlus 8 / Android 16）：
         *
         * | MC 版本 | GL4ES | MobileGlues |
         * |---|---|---|
         * | 1.21.8 | 崩溃 | 正常进主菜单 |
         *
         * GL4ES 的崩溃是：
         * ```text
         * java.lang.IllegalStateException: Can't map buffer, opengl error 0
         *     at fmb.mapBuffer -> gcg.i -> gxb.a -> fue.c(1361)
         * ```
         * 即 1.21 用到了 `glMapBufferRange`（桌面 GL 特性），而 **GL4ES 是
         * GL->GLES 转换层，不实现缓冲区映射**。MobileGlues 走 Vulkan 后端，
         * 能提供完整桌面 GL，因此 26.x 与 1.21.x 都能跑。
         *
         * ── 为什么在 Kotlin 侧修 ────────────────────────────────────────
         * 改 Rust 的 `auto_renderer_for` 最直接，但那是硬约束区。而
         * `instances::create_instance` **本来就支持** `config["renderer"]`
         *（`instances.rs:118-119`），创建实例时显式传对的值即可，效果等价。
         *
         * ── 兜底 ────────────────────────────────────────────────────────
         * 用户之后仍可在「实例设置 -> 渲染器」改成任意值（含「跟随全局」）。
         * 这里定的只是**新建时的默认值** —— 不能让新用户一点启动就崩。
         */
        fun autoRendererFor(mcVersion: String): String {
            // ⚠️ 必须取**主.次**两段：MC 的版本是 1.21.x，只取第一段永远是 1，
            // 下面那个 `minor >= 21` 的判断就永远不命中（旧代码正是这么错的）。
            val parts = mcVersion.trim().split('.', '-', '_', ' ').filter { it.isNotEmpty() }
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return when {
                // 26.x 起 MC 换了整套着色器体系（UBO + core/* 管线），只有 MG 能跑
                major >= 26 -> "mobileglues"
                // 1.21.x 起用到 glMapBufferRange，GL4ES 不支持 -> 同样用 MG
                major == 1 && minor >= 21 -> "mobileglues"
                // 更老的版本 GL4ES 兼容性确实更好（MG 在 1.8.9 报 Invalid #version）
                else -> "opengles2"
            }
        }
    }

    /** 拉版本清单。 */
    fun loadVersions(onResult: (Result<List<VersionUi>>) -> Unit) {
        api.getVersionManifest { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parseVersions(it))) },
                onFailure = { e ->
                    Log.w(TAG, "拉版本清单失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /**
     * 创建实例。
     *
     * ⚠️ 参数用 camelCase（Tauri 2 约定）；Rust 侧是 snake_case
     * （`mc_version` / `max_memory_mb`），转换由 Tauri 自动完成。
     */
    fun create(
        name: String,
        mcVersion: String,
        loader: String,
        /**
         * 加载器版本。非 vanilla **必填** ——
         * 空着创建出来的实例加载器装不上、依赖也解析不出来，
         * 连「补全游戏文件」都没有可下手的版本（旧注释原话）。
         */
        loaderVersion: String = "",
        /**
         * 渲染器（`opengles2` / `mobileglues` / `vulkan_zink`）。
         *
         * ⚠️ 必须显式传：Rust 侧 `resolve_renderer` 在实例 `renderer` 为空时走
         * 「按版本自动」，而那个自动规则**对 1.21.x 判错了**（见 [autoRendererFor]）。
         */
        renderer: String,
        memoryMb: Int,
        onResult: (Result<String>) -> Unit,
    ) {
        // ⚠️ 名称留空时用游戏版本号兜底（2026-10-17，用户要求）。
        //
        // 以前创建按钮要求 name 非空，用户必须想个名字才能建 —— 而
        // 「新建一个 1.21.8 的实例」这种场景，名字就是版本号本身，
        // 强制输入纯属多余步骤（桌面版也是留空自动用版本号命名）。
        //
        // 注意：兜底放在**发送前**而不是 UI 层 —— 这样无论从哪个入口调用
        // （新建页 / 整合包导入 / 未来的快捷创建）都能拿到同一个行为。
        val finalName = name.trim().ifBlank { mcVersion }
        val payload = JSONObject()
            .put("name", finalName)
            .put("mcVersion", mcVersion)
            .put("loader", loader)
                .put("loaderVersion", loaderVersion)
            .put("maxMemoryMb", memoryMb)
            .put("renderer", renderer)
        api.createInstance(payload) { r ->
            r.fold(
                onSuccess = { onResult(Result.success(it)) },
                onFailure = { e ->
                    Log.w(TAG, "创建实例失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 解析版本清单。 */
    fun parseVersions(json: JSONObject): List<VersionUi> {
        return try {
            val arr: JSONArray = json.optJSONArray("versions") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                VersionUi(id = id, type = o.optString("type").ifBlank { "release" })
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析版本清单失败", e)
            emptyList()
        }
    }
}
