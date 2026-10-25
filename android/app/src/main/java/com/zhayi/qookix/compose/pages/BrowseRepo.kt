package com.zhayi.qookix.compose.pages

import android.util.Log
import androidx.annotation.StringRes
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内容中心的数据层。
 *
 * 对应旧前端 `src/api.ts` 的 `browse()` + `stores` 里的搜索结果处理。
 *
 * Rust 返回形状（见 `browse.rs` 的 `BrowseResult`）：
 * ```json
 * { "hits": [ { provider, id, slug, title, description, author,
 *               downloads, follows, icon_url, project_type, ... } ],
 *   "total": 123,
 *   "cf_error": null, "cf_count": 0 }
 * ```
 */
class BrowseRepo(private val api: QookixApi) {

    companion object {
        private const val TAG = "BrowseRepo"

        /**
         * provider 的取值。
         *
         * ⚠️ 它以前是**写死**在 `search()` 里的字面量 `"modrinth"`：
         * 老 Vue（`views/BrowseView.vue`）有一个 provider 选择器
         *（`"all" | "modrinth" | "curseforge"`），Compose 版一直没暴露。
         * 这里把它提成参数（默认仍是 modrinth，行为与之前一致），
         * 上层要加选择器时直接传即可 —— 不用再回来改数据层。
         *
         * 用 modrinth 当默认值的原因：它的 API 无需 key、结果稳定；
         * CurseForge 需要 key（内置了一个 fallback），失败时 Rust 会在
         * `cf_error` 里说明。
         */
        const val PROVIDER_DEFAULT = "modrinth"

        /**
         * 加载器筛选可选项。
         *
         * ── 为什么默认是空串 ──────────────────────────────────────────
         * 空串 = **不过滤**，对应 Rust `browse` 的 `loader: String`
         *（空值＝不加 facets）。默认不过滤，进内容中心看到的仍是全量热门，
         * 和改动之前一致。
         *
         * ── 为什么有 `vanilla` ────────────────────────────────────────
         * 老 Vue 的 LOADERS 里有它（按原版筛＝排除所有 mod loader 项目）。
         * 它和「全部」在**请求层面都是空值**（原版没有 loader facet，
         * 见下面的 loaderFacet()），区别在语义与 UI 高亮。
         */
        val LOADERS: List<LoaderOption> = listOf(
            LoaderOption("", R.string.browse_loader_all),
            LoaderOption("vanilla", R.string.browse_loader_vanilla),
            LoaderOption("fabric", R.string.browse_loader_fabric),
            LoaderOption("forge", R.string.browse_loader_forge),
            LoaderOption("neoforge", R.string.browse_loader_neoforge),
            LoaderOption("quilt", R.string.browse_loader_quilt),
        )

        /**
         * UI 上的加载器标识 → 真正传给 Rust `browse` 的 `loader` 参数。
         *
         * ⚠️ **不能直接原样透传**：Modrinth 把原版标成 `minecraft`
         *（不是 `vanilla`），而 Rust 侧是按值拼 facets 的
         *（`categories:{facets}[["categories:...","categories:<loader>"]]`，
         * 见 `browse.rs`）。直接传 `"vanilla"` 会得到 0 条结果，
         * 用户会以为「筛选坏了」。
         *
         * 另外「原版」的正确语义就是**不带 loader facet**（原版没有模组加载器），
         * 所以映射成空串 —— 即不按加载器收窄。
         */
        fun loaderFacet(uiLoader: String): String =
            if (uiLoader.equals("vanilla", ignoreCase = true)) "" else uiLoader

        /**
         * 排序方式（2026-10-17 暴露到 UI）。
         *
         * ⚠️ Rust 的 `browse` 一直支持 `sort` 参数（默认 downloads），
         * 但 Compose 侧从没传过 —— 用户只能看到「按下载量」一种顺序，
         * 想找「最近更新的」完全做不到。
         */
        val SORTS: List<SortOption> = listOf(
            SortOption("downloads", R.string.browse_sort_downloads),
            SortOption("updated", R.string.browse_sort_updated),
            SortOption("newest", R.string.browse_sort_newest),
            SortOption("follows", R.string.browse_sort_follows),
        )
    }

    /** 一个加载器筛选项（UI 标识 + 展示名资源）。 */
    data class LoaderOption(val id: String, @StringRes val labelRes: Int)

    /** 一个排序选项（sort 参数值 + 展示名资源）。 */
    data class SortOption(val id: String, @StringRes val labelRes: Int)

    /**
     * 搜索。
     *
     * @param query 关键词（空串 = 按下载量排序的热门列表，与旧界面一致）
     * @param projectType mod / modpack / resourcepack / shader / plugin
     * @param page 从 0 开始（与 Rust 侧 `page: i32` 一致）
     * @param loader 加载器筛选（Fabric/Forge/…）；空串 = 不过滤。
     *        `"vanilla"` 会被 [loaderFacet] 映射成空串（原版没有 loader facet）。
     * @param provider 内容来源。默认 [PROVIDER_DEFAULT]（modrinth）。
     *        对 `projectType == "plugin"` 无效 —— 插件是 Hangar 独立数据源。
     */
    fun search(
        query: String,
        projectType: String,
        page: Int = 0,
        loader: String = "",
        /** 排序方式（downloads / updated / newest / follows），见 [SORTS]。 */
        sort: String = "downloads",
        provider: String = PROVIDER_DEFAULT,
        onResult: (Result<List<ProjectUi>>) -> Unit,
    ) {
        // 「插件」是一条**独立数据源**：PaperMC 的 Hangar 平台没有 Modrinth/CurseForge
        // 的 project→version→files 那套模型，接口形态也不同（见 src-tauri/src/hangar.rs）。
        // 这里把它的结果**映射成同样的卡片结构**，列表与卡片组件照旧复用。
        //
        // Hangar 没有加载器维度（它的 facet 是 platform/version，不是 fabric/forge），
        // 所以 loader 对它不生效 —— 这点在 UI 上也没有暴露加载器 chip（见 BrowsePage）。
        if (projectType == "plugin") {
            api.hangarSearchPlugins(query, page, 20) { r ->
                r.fold(
                    onSuccess = { onResult(Result.success(parseHangar(it))) },
                    onFailure = { e ->
                        Log.w(TAG, "Hangar 搜索失败: ${e.message}")
                        onResult(Result.failure(e))
                    },
                )
            }
            return
        }

        val facet = loaderFacet(loader)
        Log.i(TAG, "browse provider=$provider type=$projectType page=$page loader='$facet'")

        api.browse(
            provider = provider,
            query = query,
            projectType = projectType,
            page = page,
            loader = facet,
            sort = sort,
            pageSize = 20,
        ) { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parse(it))) },
                onFailure = { e ->
                    Log.w(TAG, "搜索失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /**
     * 解析 Hangar 的搜索结果。
     *
     * 字段与 Modrinth 不同（`owner` 而非 `author`、`name` 而非 `title`、
     * `icon` 而非 `icon_url`），这里做一次映射让上层无感。
     */
    fun parseHangar(arr: JSONArray): List<ProjectUi> {
        return try {
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("slug")
                val title = o.optString("name").ifBlank { slug }
                if (title.isBlank()) return@mapNotNull null
                ProjectUi(
                    id = slug,
                    provider = "hangar",
                    title = title,
                    description = o.optString("description"),
                    author = o.optString("owner"),
                    iconUrl = o.optString("icon"),
                    downloads = o.optLong("downloads", 0L),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析 Hangar 结果失败", e)
            emptyList()
        }
    }

    /** 解析 BrowseResult.hits。 */
    fun parse(json: JSONObject): List<ProjectUi> {
        return try {
            val hits = json.optJSONArray("hits") ?: return emptyList()
            (0 until hits.length()).mapNotNull { i ->
                val o = hits.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                val title = o.optString("title").ifBlank { o.optString("slug") }
                if (title.isBlank()) return@mapNotNull null
                ProjectUi(
                    id = id,
                    provider = o.optString("provider").ifBlank { "modrinth" },
                    title = title,
                    description = o.optString("description"),
                    author = o.optString("author"),
                    iconUrl = o.optString("icon_url"),
                    downloads = o.optLong("downloads", 0L),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析搜索结果失败", e)
            emptyList()
        }
    }
}
