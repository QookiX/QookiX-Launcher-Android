package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.friendlyError
import com.zhayi.qookix.compose.bridge.installContent
import com.zhayi.qookix.compose.bridge.installServerContent
import com.zhayi.qookix.compose.bridge.installServerPlugin
import com.zhayi.qookix.compose.bridge.projectDependencies
import com.zhayi.qookix.compose.bridge.projectVersions
import com.zhayi.qookix.compose.bridge.translateModDescriptions

/**
 * 「安装内容」的完整状态机 + 对话框（[InstallDialog]）。
 *
 * ## 为什么要单独抽出来
 *
 * 这段逻辑原本整个长在 `BrowseRoute` 里（内容中心）。详情页也要能装同一个项目，
 * 复制一份就会出现两套「版本筛选 / 依赖 / 目标实例 / 服务器」逻辑，
 * 改一处漏一处。所以收敛成这一个宿主：**调用方只负责给项目和内容类型**。
 *
 * ## 行为（与原来在 BrowseRoute 里的完全一致）
 *
 * - 插件（Hangar）只能装到服务器，且不选版本（由 Hangar 自己挑）
 * - 模组装到实例 **或** 服务器（`mods/`）
 * - 资源包 / 光影只能装到实例
 * - 进来自动挑第一个非 vanilla 实例 + 该实例对应的最新正式版
 * - 换实例会按它的 MC 版本/加载器重新筛版本（筛空则回退全量）
 */
@Composable
fun ContentInstallHost(
    api: QookixApi,
    /** 要装的项目；null = 关闭状态（不渲染任何东西）。 */
    project: ProjectUi?,
    /** 内容类型（mod / modpack / resourcepack / shader / plugin）。 */
    kind: String,
    /** 进来时预选的版本 id（从详情页版本列表点某个版本进来时用）。 */
    initialVersionId: String? = null,
    /** 没有可用实例时的引导（null = 不显示按钮）。 */
    onCreateTargetInstance: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val start = project ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    val isPlugin = kind == "plugin"

    // 当前要装的项目：点「依赖」时换成那个依赖项目继续装（与原对话框一致）
    var target by remember(start.id) { mutableStateOf(start) }

    // 状态全部以入口项目 id 为 key：关掉再打开时整块重置
    var versions by remember(start.id) { mutableStateOf<List<ContentVersionUi>>(emptyList()) }
    var loadingVersions by remember(start.id) { mutableStateOf(false) }
    var version by remember(start.id) { mutableStateOf<String?>(null) }
    var instanceId by remember(start.id) { mutableStateOf<String?>(null) }
    var serverId by remember(start.id) { mutableStateOf<String?>(null) }
    var typeFilter by remember(start.id) { mutableStateOf("all") }
    var deps by remember(start.id) { mutableStateOf<List<DependencyUi>>(emptyList()) }
    var descZh by remember(start.id) { mutableStateOf<String?>(null) }
    var descLoading by remember(start.id) { mutableStateOf(false) }
    var installing by remember(start.id) { mutableStateOf(false) }
    var error by remember(start.id) { mutableStateOf<String?>(null) }
    // 版本请求序号：并发只认最后一次，避免「选好了又跳回默认」
    var versionsSeq by remember(start.id) { mutableStateOf(0) }
    // 目标候选（实例 / 服务器列表）
    var instances by remember(start.id) { mutableStateOf<List<InstallTargetUi>>(emptyList()) }
    var servers by remember(start.id) { mutableStateOf<List<ServerUi>>(emptyList()) }

    /** 预选版本优先，否则最新正式版，再否则第一条。 */
    fun pickVersion(list: List<ContentVersionUi>): String? {
        val want = initialVersionId
        if (want != null) {
            list.firstOrNull { it.id == want }?.let { return it.id }
        }
        return list.firstOrNull { it.type == "release" }?.id ?: list.firstOrNull()?.id
    }

    /** 拉版本列表（按目标实例筛选，无结果回退全量）。 */
    fun loadVersions() {
        val seq = ++versionsSeq
        loadingVersions = true
        version = null
        deps = emptyList()
        val inst = instances.firstOrNull { it.id == instanceId }
        val mc = inst?.mcVersion ?: ""
        val loader = inst?.loader?.takeUnless { it == "vanilla" } ?: ""
        api.projectVersions(target.provider, target.id, mc, loader) { r ->
            if (seq != versionsSeq) return@projectVersions
            r.fold(
                onSuccess = { json ->
                    var list = parseContentVersions(context, json)
                    // 上游按实例筛选无结果时，回退拉全部版本供选择
                    if (list.isEmpty() && (mc.isNotBlank() || loader.isNotBlank())) {
                        api.projectVersions(target.provider, target.id, "", "") { r2 ->
                            if (seq != versionsSeq) return@projectVersions
                            r2.onSuccess { all ->
                                list = parseContentVersions(context, all)
                                versions = list
                                version = pickVersion(list)
                            }
                            loadingVersions = false
                        }
                        return@fold
                    }
                    versions = list
                    version = pickVersion(list)
                    loadingVersions = false
                    // 拉依赖
                    version?.let { vid ->
                        api.projectDependencies(target.provider, target.id, vid) { dr ->
                            dr.onSuccess { arr -> deps = parseDependencies(arr, target.provider, context) }
                        }
                    }
                },
                onFailure = { e ->
                    loadingVersions = false
                    error = context.getString(R.string.version_load_failed, e.message)
                },
            )
        }
    }

    // 打开时：拉目标候选 + 简介译文（与原对话框一致）
    LaunchedEffect(target.id, kind) {
        error = null
        if (isPlugin || kind == "mod") {
            api.listHostedServers { r ->
                r.onSuccess { arr ->
                    // 复用 MultiplayerRepo 的解析（不要另抄一份）
                    servers = MultiplayerRepo.parseServers(arr)
                    serverId = servers.firstOrNull()?.id
                }
            }
        }
        if (!isPlugin) {
            api.listInstances { r ->
                r.onSuccess { arr ->
                    instances = (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        InstallTargetUi(
                            id = o.optString("id"),
                            name = o.optString("name").ifBlank { o.optString("version_id") },
                            loader = o.optString("loader"),
                            mcVersion = o.optString("version_id"),
                        )
                    }
                    instanceId = instances.firstOrNull { it.loader != "vanilla" }?.id
                    loadVersions()
                }
            }
        }
        descLoading = true
        api.translateModDescriptions(target.provider, listOf(target.id)) { r ->
            descLoading = false
            r.onSuccess { o ->
                val t = o.optJSONObject("translations")
                descZh = t?.optString(target.id)?.takeIf { it.isNotBlank() }
            }
        }
    }

    InstallDialog(
        project = target,
        kind = kind,
        isPlugin = isPlugin,
        versions = versions.filter { typeFilter == "all" || it.type == typeFilter },
        loadingVersions = loadingVersions,
        selectedVersion = version,
        instances = instances,
        servers = servers,
        selectedInstanceId = instanceId,
        selectedServerId = serverId,
        typeFilter = typeFilter,
        deps = deps,
        descZh = descZh,
        descLoading = descLoading,
        installing = installing,
        error = error,
        onSelectVersion = { v ->
            version = v
            // 换版本重拉依赖
            api.projectDependencies(target.provider, target.id, v) { dr ->
                dr.onSuccess { arr -> deps = parseDependencies(arr, target.provider, context) }
            }
        },
        // 目标二选一：不清掉另一侧的话，两个 id 同时非空 → 模组会被装进服务器目录
        onSelectInstance = { id ->
            instanceId = id
            serverId = null
            loadVersions()
        },
        onSelectServer = { serverId = it; instanceId = null },
        onTypeFilterChange = { typeFilter = it },
        onOpenDependency = { d ->
            // 切到那个依赖项目继续装（依赖自身也可能有前置，LaunchedEffect 会重拉）
            target = d.asProjectUi()
            error = null
            versions = emptyList()
            deps = emptyList()
            version = null
        },
        onTranslate = {
            descLoading = true
            api.translateModDescriptions(target.provider, listOf(target.id)) { r ->
                descLoading = false
                r.onSuccess { o ->
                    val t = o.optJSONObject("translations")
                    descZh = t?.optString(target.id)?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.translation_none)
                }
            }
        },
        onCreateTargetInstance = onCreateTargetInstance,
        onInstall = {
            if (installing) return@InstallDialog
            val vid = version
            val iid = instanceId
            val sid = serverId
            val wantsServer = sid != null && (isPlugin || kind == "mod")
            if (wantsServer) {
                if (isPlugin) {
                    installing = true
                    error = null
                    api.installServerPlugin(sid!!, target.id, target.title) { r ->
                        installing = false
                        r.fold(
                            onSuccess = { onDismiss() },
                            onFailure = { e -> error = context.getString(R.string.install_failed, e.message) },
                        )
                    }
                    return@InstallDialog
                }
                // 模组：需要版本号（插件那条路由 Hangar 自己挑版本）
                if (vid == null) {
                    error = context.getString(R.string.install_pick_server_version)
                    return@InstallDialog
                }
                installing = true
                error = null
                api.installServerContent(sid!!, target.provider, target.id, vid, kind) { r ->
                    installing = false
                    r.fold(
                        onSuccess = { onDismiss() },
                        onFailure = { e -> error = context.getString(R.string.install_failed, e.message) },
                    )
                }
                return@InstallDialog
            }
            if (iid == null || vid == null) {
                error = context.getString(R.string.install_pick_instance_version)
                return@InstallDialog
            }
            installing = true
            error = null
            api.installContent(iid, target.provider, target.id, vid, kind) { r ->
                installing = false
                r.fold(
                    onSuccess = { onDismiss() },
                    onFailure = { e -> error = context.getString(R.string.install_failed, friendlyError(context, e)) },
                )
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * 依赖跳转：把依赖当成新的待装项目。
 *
 * 依赖项只有 id / 标题 / 平台，其余字段留空 —— [InstallDialog] 只用
 * title / author / downloads / description 做展示，空值不会出错。
 */
internal fun DependencyUi.asProjectUi(): ProjectUi = ProjectUi(
    id = projectId,
    provider = provider,
    title = title,
    description = "",
    author = "",
    iconUrl = "",
    downloads = 0,
)

/** 解析 `project_versions` 的返回（原来内联在 BrowseRoute 里，两处都要用）。 */
internal fun parseContentVersions(
    context: android.content.Context,
    json: org.json.JSONObject,
): List<ContentVersionUi> {
    val arr = json.optJSONArray("versions") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val id = o.optString("id")
        if (id.isBlank()) return@mapNotNull null
        val type = o.optString("version_type")
            .ifBlank { o.optString("release_type") }
        ContentVersionUi(
            id = id,
            versionNumber = o.optString("version_number").ifBlank { o.optString("name") },
            type = type,
            typeLabel = when (type) {
                "beta" -> context.getString(R.string.version_type_beta)
                "alpha" -> context.getString(R.string.version_type_alpha)
                else -> context.getString(R.string.version_type_release)
            },
            gameVersions = o.optJSONArray("game_versions")?.let { gv ->
                (0 until gv.length()).mapNotNull { gv.optString(it) }
                    .takeLast(2).joinToString(", ")
            }.orEmpty(),
            datePublished = o.optString("date_published"),
        )
    }
}

/** 解析 `project_dependencies` 的返回。 */
internal fun parseDependencies(
    arr: org.json.JSONArray,
    defaultProvider: String,
    context: android.content.Context,
): List<DependencyUi> = (0 until arr.length()).mapNotNull { i ->
    val o = arr.optJSONObject(i) ?: return@mapNotNull null
    val dt = o.optString("dependencyType").ifBlank { o.optString("dependency_type") }
    DependencyUi(
        projectId = o.optString("projectId").ifBlank { o.optString("project_id") },
        title = o.optString("title").ifBlank { o.optString("name") },
        type = dt,
        typeLabel = when (dt) {
            "required" -> context.getString(R.string.dep_required)
            "optional" -> context.getString(R.string.dep_optional)
            "incompatible" -> context.getString(R.string.dep_incompatible)
            "embedded" -> context.getString(R.string.dep_embedded)
            else -> dt
        },
        provider = o.optString("provider").ifBlank { defaultProvider },
    )
}
