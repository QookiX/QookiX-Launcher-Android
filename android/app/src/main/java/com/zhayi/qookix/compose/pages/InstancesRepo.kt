package com.zhayi.qookix.compose.pages

import android.content.Context
import android.util.Log
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 实例列表的数据层。
 *
 * 数据来源：Rust 的 `list_instances` 命令（本地磁盘，不依赖网络 ——
 * 所以它同时是桥的自检命令，见 RustBridge.selfTest）。
 *
 * 字段对应 `src/types.ts` 的 `Instance`：
 *   id / name / version_id / loader / last_played / playtime_ms ...
 */
object InstancesRepo {

    private const val TAG = "InstancesRepo"

    /**
     * 拉取实例列表。
     *
     * @param onResult 主线程回调。失败时给 Err（网络无关，通常是桥未就绪）
     */
    fun load(bridge: RustBridge, onResult: (Result<List<InstanceUi>>) -> Unit) {
        load(null, bridge, onResult)
    }

    /**
     * 带 [context] 的重载：副标题里的「原版」走 string 资源。
     *
     * 旧的无 context 版本保留兼容（`PageHost.kt:266` 的调用点暂时没改）。
     */
    fun load(
        context: Context?,
        bridge: RustBridge,
        onResult: (Result<List<InstanceUi>>) -> Unit,
    ) {
        bridge.invoke("list_instances", "{}") { r ->
            r.fold(
                onSuccess = { json -> onResult(Result.success(parse(context, json))) },
                onFailure = { e ->
                    Log.w(TAG, "list_instances 失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 解析 Rust 返回的实例数组（无 context：加载器名回退为英文原文）。 */
    fun parse(json: String): List<InstanceUi> = parse(null, json)

    /** 解析 Rust 返回的实例数组。 */
    fun parse(context: Context?, json: String): List<InstanceUi> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val name = o.optString("name").ifBlank { o.optString("version_id") }
                if (id.isBlank() && name.isBlank()) return@mapNotNull null

                InstanceUi(
                    id = id,
                    name = name,
                    subtitle = subtitleOf(context, o),
                    colorHex = o.optString("icon_color").ifBlank { null },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析实例列表失败", e)
            emptyList()
        }
    }

    /**
     * 副标题：与旧界面 `InstanceCard.vue` 的信息层级一致 ——
     * 「加载器 + 版本」，例如「Fabric 1.20.1」「原版 1.8.9」。
     *
     * ⚠️⚠️ 2026-10-17 修（真机发现）：版本号**从来没显示过**。
     *
     * 根因：这里读的是 `version_id`，而 Rust 的 `MinecraftProfile`
     * （models.rs:8）字段名是 **`mc_version`** —— 键名不存在，
     * `optString` 返回空串，于是 `version` 恒为 null，
     * 副标题永远只有「原版」「Fabric」这几个字，看不出是哪个 MC 版本。
     *
     * 实例卡片上「rrr / 原版」「26.3 / 原版」这种只显示加载器的情况，
     * 就是这个键名错位造成的（用户截图指出）。
     *
     * 兜底：老数据里若只有 `version_id`（早期版本写过），也一并认。
     */
    private fun subtitleOf(context: Context?, o: JSONObject): String {
        val loader = o.optString("loader").ifBlank { null }
        // 优先 mc_version（Rust 当前字段），回退 version_id（历史字段）
        val version = o.optString("mc_version")
            .ifBlank { o.optString("version_id") }
            .ifBlank { null }
        // 加载器版本（Fabric/Forge 有，原版没有）—— 有就一起显示，
        // 否则用户看不出同一个 MC 版本下装的是哪版加载器。
        val loaderVer = o.optString("loader_version").ifBlank { null }
        // ⚠️ i18n：「原版」是唯一需要翻译的加载器名，走 string 资源；
        // Fabric / Forge / NeoForge / Quilt / OptiFine 都是产品名，不翻译。
        val vanillaLabel = context?.getString(R.string.instances_loader_vanilla) ?: "Vanilla"
        val loaderLabel = when (loader?.lowercase()) {
            null -> null
            "vanilla" -> vanillaLabel
            "fabric" -> "Fabric"
            "forge" -> "Forge"
            "neoforge" -> "NeoForge"
            "quilt" -> "Quilt"
            "optifine" -> "OptiFine"
            else -> loader
        }
        val loaderFull = when {
            loaderLabel == null -> null
            // 原版不显示加载器版本（它没有版本号，硬拼会变成「原版 」）
            loaderVer == null || loader?.lowercase() == "vanilla" -> loaderLabel
            else -> "$loaderLabel $loaderVer"
        }
        // 实例名里已经带版本号时（整合包常被自动命名成「xxx 1.21.1」）不再重复一遍，
        // 否则卡片上会出现「月落花野（1.21.1）3.1.2 / NeoForge 21.1.251 1.21.1」。
        val name = o.optString("name")
        val versionPart = version?.takeUnless { name.contains(it) }
        return listOfNotNull(loaderFull, versionPart).joinToString(" ")
    }
}
