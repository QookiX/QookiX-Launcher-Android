package com.zhayi.qookix.compose.bridge

import org.json.JSONObject
import org.json.JSONArray

/**
 * 内容 / 翻译域（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 * 命令名逐个核对自 Rust 注册表与 src/api.ts，不是猜的。
 */

// ── 内容中心（Modrinth / CurseForge）─────────────────────────────────

/**
 * 搜索项目（内容中心）。
 *
 * ⚠️ 两条已核实的约定：
 *   1. 命令名是 `browse`（不是 `search_projects`）—— 核对自 `lib.rs` 的 invoke_handler
 *   2. 参数用 **camelCase**（Tauri 2 默认）—— 对照 `src/api.ts` 的实际调用：
 *      `{ provider, query, projectType, category, page, gameVersion, loader, sort, pageSize }`
 *      Rust 侧签名是 snake_case（`project_type`），转换由 Tauri 自动完成。
 *
 * @param provider "modrinth" 或 "curseforge"
 * @param projectType mod / modpack / resourcepack / shader
 * @param sort downloads / relevance / updated / newest
 */
fun QookixApi.browse(
    provider: String,
    query: String,
    projectType: String,
    category: String = "",
    page: Int = 0,
    gameVersion: String = "",
    loader: String = "",
    sort: String = "downloads",
    pageSize: Int = 20,
    onResult: (Result<JSONObject>) -> Unit,
) {
    val p = JSONObject()
        .put("provider", provider)
        .put("query", query)
        .put("projectType", projectType)
        .put("category", category)
        .put("page", page)
        .put("gameVersion", gameVersion)
        .put("loader", loader)
        .put("sort", sort)
        .put("pageSize", pageSize)
    callObj("browse", p, onResult)
}

/** 某个项目的可用版本（安装时要选）。 */
fun QookixApi.projectVersions(
    provider: String,
    projectId: String,
    mcVersion: String = "",
    loader: String = "",
    onResult: (Result<JSONObject>) -> Unit,
) {
    val p = JSONObject()
        .put("provider", provider)
        .put("projectId", projectId)
        .put("mcVersion", mcVersion)
        .put("loader", loader)
    callObj("project_versions", p, onResult)
}

/** 取项目完整信息（描述/正文/作者/下载量等）。 */
fun QookixApi.projectInfo(provider: String, projectId: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("project_info", JSONObject().put("provider", provider).put("projectId", projectId), onResult)

/** CurseForge 分类列表（筛选用）。 */
fun QookixApi.curseforgeCategories(onResult: (Result<JSONArray>) -> Unit) =
    callArr("curseforge_categories", null, onResult)

/** 预估某 MC 版本原版下载体积（返回 {totalSize, fileCount}）。 */
fun QookixApi.estimateDownload(mcVersion: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("estimate_download", JSONObject().put("mcVersion", mcVersion), onResult)

/** 预估导入模组包体积。 */
fun QookixApi.estimateImport(source: String, rawIds: List<String>, onResult: (Result<JSONObject>) -> Unit) =
    callObj("estimate_import", JSONObject().put("source", source).put("rawIds", JSONArray().also { rawIds.forEach { id -> it.put(id) } }), onResult)

/**
 * 版本清单（Minecraft 各版本列表）。
 *
 * ⚠️ 返回的是**对象**不是数组：`{ versions: [...], latest: {...} }`
 * —— 见 `commands.rs::get_version_manifest`。
 */
fun QookixApi.getVersionManifest(onResult: (Result<JSONObject>) -> Unit) =
    callObj("get_version_manifest", null, onResult)


/**
 * Paper 支持的所有 MC 版本（已按版本号降序，已剔除 rc/pre）。
 *
 * ⚠️ 为什么要这个而不是复用 [getVersionManifest]：
 * Paper **不支持所有 MC 版本**。比如 1.8 那条线只有 `1.8.8`（`1.8.9` 不存在，
 * 但两者客户端协议相同，1.8.9 客户端能进 1.8.8 服务器）。
 * 拿 1.8.9 去问 builds 接口只会 404，所以创建服务器时必须用这份清单限制选择。
 *
 * 见 Rust `servers.rs::list_paper_versions`（已注册在 `lib.rs:94`）。
 */
fun QookixApi.listPaperVersions(onResult: (Result<List<String>>) -> Unit) =
    callArr("list_paper_versions", null) { r ->
        onResult(
            r.map { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i).takeIf { it.isNotBlank() }
                }
            },
        )
    }
// ── 内容 / 翻译 ───────────────────────────────────────────────────────

/** 项目依赖列表（安装前置检查用）。 */
fun QookixApi.projectDependencies(
    provider: String,
    projectId: String,
    versionId: String,
    onResult: (Result<JSONArray>) -> Unit,
) = callArr(
    "project_dependencies",
    JSONObject()
        .put("provider", provider)
        .put("projectId", projectId)
        .put("versionId", versionId),
    onResult,
)

/** 翻译模组正文。 */
/**
 * 批量翻译模组描述（一次最多 5 个、同一平台）。
 *
 * ⚠️ 必须传 **slug** 而不是 id：翻译服务（及其缓存）以 slug 为键，
 * 传 id 会「请求成功但译文查不到」—— 表现为描述一直是英文。
 *
 * 返回：{ translations: { <slug>: <译文> }, rateLimited: bool, error: string }
 */
fun QookixApi.translateModDescriptions(
    provider: String,
    slugs: List<String>,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "translate_mod_descriptions",
    JSONObject().put("provider", provider).put("slugs", org.json.JSONArray(slugs)),
    onResult,
)

fun QookixApi.translateProjectBody(
    provider: String,
    slug: String,
    translate: Boolean,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "translate_project_body",
    JSONObject().put("provider", provider).put("slug", slug).put("translate", translate),
    onResult,
)

/** 上报「翻译已过期」。 */
fun QookixApi.reportTranslationStale(
    provider: String,
    slug: String,
    onResult: (Result<String>) -> Unit = {},
) = call("report_translation_stale", JSONObject().put("provider", provider).put("slug", slug), onResult)

/** 上报翻译质量问题。 */
fun QookixApi.reportTranslationQuality(
    provider: String,
    slug: String,
    issueType: String,
    userSuggestion: String? = null,
    userComment: String? = null,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "report_translation_quality",
    JSONObject()
        .put("provider", provider)
        .put("slug", slug)
        .put("issueType", issueType)
        .put("userSuggestion", userSuggestion ?: JSONObject.NULL)
        .put("userComment", userComment ?: JSONObject.NULL),
    onResult,
)

/** 查 MC 百科条目。 */
fun QookixApi.mcWikiUrl(
    name: String,
    slug: String? = null,
    provider: String? = null,
    onResult: (Result<String>) -> Unit,
) = call(
    "mc_wiki_url",
    JSONObject()
        .put("name", name)
        .put("slug", slug ?: JSONObject.NULL)
        .put("provider", provider ?: JSONObject.NULL),
    onResult,
)
