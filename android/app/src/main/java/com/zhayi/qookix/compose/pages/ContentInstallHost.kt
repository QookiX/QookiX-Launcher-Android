package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.friendlyError
import com.zhayi.qookix.compose.bridge.optText
import com.zhayi.qookix.compose.bridge.installContent
import com.zhayi.qookix.compose.bridge.installServerContent
import com.zhayi.qookix.compose.bridge.installServerPlugin
import com.zhayi.qookix.compose.bridge.projectDependencies
import com.zhayi.qookix.compose.bridge.projectVersions
import com.zhayi.qookix.compose.bridge.translateModDescriptions
import com.zhayi.qookix.compose.ui.QkFeedback

/**
 * 「安装内容」的完整状态机 + 两种外壳：[InstallDialog]（弹窗）与
 * [InstallPanel]（详情页左栏常驻面板）。
 *
 * ## 为什么要单独抽出来
 *
 * 这段逻辑原本整个长在 `BrowseRoute` 里（内容中心）。详情页也要能装同一个项目，
 * 复制一份就会出现两套「版本筛选 / 依赖 / 目标实例 / 服务器」逻辑，
 * 改一处漏一处。所以收敛成这一个宿主：**调用方只负责给项目和内容类型**。
 *
 * ## 行为
 *
 * - 插件（Hangar）只能装到服务器，且不选版本（由 Hangar 自己挑）
 * - 模组装到实例 **或**「支持模组的服务器」（fabric/forge/neoforge；纯插件服 paper 不算）
 * - 资源包 / 光影只能装到实例
 * - 进来自动挑第一个非 vanilla 实例 + 该实例对应的最新正式版
 * - 换实例会按它的 MC 版本/加载器重新筛版本（筛空则回退全量）
 * - 版本列表可以按「类型（正式/测试/先行）」和「游戏版本」筛选
 */
@Composable
fun ContentInstallHost(
    api: QookixApi,
    /** 要装的项目；null = 关闭状态（不渲染任何东西）。 */
    project: ProjectUi?,
    /** 内容类型（mod / modpack / resourcepack / shader / plugin）。 */
    kind: String,
    /** 进来时预选的版本 id（从版本列表点某个版本进来时用）。 */
    initialVersionId: String? = null,
    /** 没有可用实例时的引导（null = 不显示按钮）。 */
    onCreateTargetInstance: (() -> Unit)? = null,
    /** true = 画成常驻面板（详情页左栏），false = 弹窗。 */
    inline: Boolean = false,
    /** 详情页顶栏的安装按钮用（内联模式下由宿主接管点击）。 */
    handle: ContentInstallHandle? = null,
    modifier: Modifier = Modifier,
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
    var gameFilter by remember(start.id) { mutableStateOf("all") }
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
                    // 默认目标：只有插件才预选服务器。模组默认装实例 ——
                    // 两边都预选的话 serverId/instanceId 同时非空，
                    // 安装会静默走「服务器」分支（用户以为装进了实例）。
                    if (isPlugin) serverId = servers.firstOrNull()?.id
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
                            // ⚠️ 2026-10-29（外部审计 B06 核实成立）：
                            // 这里原来读 `version_id` —— 那是「已安装内容的版本 id」，
                            // 实例根本没有这个字段（MinecraftProfile 里叫 `mc_version`）。
                            // 结果 mcVersion 永远为空 → 「按 MC 版本筛选」对模组失效，
                            // 会给出不兼容的版本；无匹配时还会静默回退全量。
                            name = o.optText("name") ?: o.optText("version_id") ?: "",
                            loader = o.optString("loader"),
                            mcVersion = o.optText("mc_version") ?: "",
                        )
                    }
                    instanceId = instances.firstOrNull { it.loader != "vanilla" }?.id
                    loadVersions()
                }
                // 拉不到实例（比如只装了服务器）也要能把版本列出来
                r.onFailure { loadVersions() }
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

    /** 装：服务器（插件 / 模组）优先，其次实例。 */
    fun startInstall() {
        if (installing) return
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
                        onSuccess = {
                            QkFeedback.show(context.getString(R.string.install_started))
                            onDismiss()
                        },
                        onFailure = { e -> error = context.getString(R.string.install_failed, e.message) },
                    )
                }
                return
            }
            // 模组：需要版本号（插件那条路由 Hangar 自己挑版本）
            if (vid == null) {
                error = context.getString(R.string.install_pick_server_version)
                return
            }
            installing = true
            error = null
            api.installServerContent(sid!!, target.provider, target.id, vid, kind) { r ->
                installing = false
                r.fold(
                    onSuccess = {
                        QkFeedback.show(context.getString(R.string.install_started))
                        onDismiss()
                    },
                    onFailure = { e -> error = context.getString(R.string.install_failed, e.message) },
                )
            }
            return
        }
        if (iid == null || vid == null) {
            error = context.getString(R.string.install_pick_instance_version)
            return
        }
        installing = true
        error = null
        api.installContent(iid, target.provider, target.id, vid, kind) { r ->
            installing = false
            r.fold(
                onSuccess = {
                    QkFeedback.show(context.getString(R.string.install_started))
                    onDismiss()
                },
                onFailure = { e -> error = context.getString(R.string.install_failed, friendlyError(context, e)) },
            )
        }
    }

    /** 换筛选条件后，原来选中的版本可能已经不在了 —— 自动落到第一条。 */
    fun reselectIfFilteredOut(newType: String, newGame: String) {
        val next = filterVersions(versions, newType, newGame)
        // ⚠️ 2026-10-29（外部审计 B16）：筛选结果为空时原来直接 return，
        // `version` 还留着上一个可见版本 —— 列表里看不到它，但「安装」按钮
        // 仍然可用，点下去装的是一个用户以为已经筛掉的版本。
        if (next.isEmpty()) {
            version = null
            return
        }
        if (next.none { it.id == version }) version = next.first().id
    }

    val visibleVersions = filterVersions(versions, typeFilter, gameFilter)
    val gameOptions = remember(versions) { gameVersionOptions(versions) }
    val visibleServers = if (kind == "plugin") servers else servers.filter { it.supportsMods() }

    val state = InstallUiState(
        project = target,
        kind = kind,
        isPlugin = isPlugin,
        versions = visibleVersions,
        loadingVersions = loadingVersions,
        selectedVersion = version,
        instances = instances,
        servers = visibleServers,
        selectedInstanceId = instanceId,
        selectedServerId = serverId,
        typeFilter = typeFilter,
        gameVersions = gameOptions,
        gameFilter = gameFilter,
        deps = deps,
        descZh = descZh,
        descLoading = descLoading,
        installing = installing,
        error = error,
    )

    val onSelectVersion: (String) -> Unit = { v ->
        version = v
        // 换版本重拉依赖
        api.projectDependencies(target.provider, target.id, v) { dr ->
            dr.onSuccess { arr -> deps = parseDependencies(arr, target.provider, context) }
        }
    }
    // 目标二选一：不清掉另一侧的话，两个 id 同时非空 → 模组会被装进服务器目录
    val onSelectInstance: (String?) -> Unit = { id ->
        instanceId = id
        serverId = null
        loadVersions()
    }
    val onSelectServer: (String?) -> Unit = { sid ->
        serverId = sid
        instanceId = null
    }
    val onOpenDependency: (DependencyUi) -> Unit = { d ->
        // 切到那个依赖项目继续装（依赖自身也可能有前置，LaunchedEffect 会重拉）
        target = d.asProjectUi()
        error = null
        versions = emptyList()
        deps = emptyList()
        version = null
    }
    val onTranslate: () -> Unit = {
        descLoading = true
        api.translateModDescriptions(target.provider, listOf(target.id)) { r ->
            descLoading = false
            r.onSuccess { o ->
                val t = o.optJSONObject("translations")
                descZh = t?.optString(target.id)?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.translation_none)
            }
        }
    }

    // 详情页顶栏的「安装」按钮靠这个句柄驱动面板里的安装流程
    if (handle != null) {
        SideEffect {
            handle.installAction = { startInstall() }
            handle.canInstall = state.canInstall
            handle.installing = state.installing
        }
    }

    if (inline) {
        InstallPanel(
            state = state,
            onSelectVersion = onSelectVersion,
            onSelectInstance = onSelectInstance,
            onSelectServer = onSelectServer,
            onTypeFilterChange = { f ->
                typeFilter = f
                reselectIfFilteredOut(f, gameFilter)
            },
            onGameFilterChange = { g ->
                gameFilter = g
                reselectIfFilteredOut(typeFilter, g)
            },
            onOpenDependency = onOpenDependency,
            onTranslate = onTranslate,
            onCreateTargetInstance = onCreateTargetInstance,
            modifier = modifier,
        )
    } else {
        InstallDialog(
            state = state,
            onSelectVersion = onSelectVersion,
            onSelectInstance = onSelectInstance,
            onSelectServer = onSelectServer,
            onTypeFilterChange = { f ->
                typeFilter = f
                reselectIfFilteredOut(f, gameFilter)
            },
            onGameFilterChange = { g ->
                gameFilter = g
                reselectIfFilteredOut(typeFilter, g)
            },
            onOpenDependency = onOpenDependency,
            onTranslate = onTranslate,
            onInstall = { startInstall() },
            onCreateTargetInstance = onCreateTargetInstance,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 「这个服务端核心能不能加载模组」。
 *
 * 只认 fabric / forge / neoforge —— paper 是插件服，把 mods 列出来
 * 用户点了也装不上（装进 `mods/` 也不会被加载）。
 */
internal fun ServerUi.supportsMods(): Boolean {
    val c = core.trim().lowercase()
    return c == "fabric" || c == "forge" || c == "neoforge"
}

/** 版本列表 → 按类型 + 游戏版本过滤（`"all"` = 不过滤）。 */
internal fun filterVersions(
    versions: List<ContentVersionUi>,
    type: String,
    game: String,
): List<ContentVersionUi> = versions.filter { v ->
    (type == "all" || v.type == type) &&
        (game == "all" || v.supportsGame(game))
}

/** 这个版本支不支持某个游戏版本（用完整列表，不是展示用的那两个）。 */
internal fun ContentVersionUi.supportsGame(game: String): Boolean {
    val all = if (allGameVersions.isNotEmpty()) allGameVersions else gameVersions.split(",").map { it.trim() }
    return all.any { it == game }
}

/**
 * 版本列表里出现过的游戏版本，倒序（新的在前），最多 12 个。
 *
 * 选项完全来自数据本身：不同项目的 MC 版本差异很大，
 * 写死一份 1.21.x 列表只会对一半项目有效。
 */
internal fun gameVersionOptions(versions: List<ContentVersionUi>): List<String> =
    versions.asSequence()
        .flatMap { v ->
            (if (v.allGameVersions.isNotEmpty()) v.allGameVersions.asSequence()
            else v.gameVersions.split(",").asSequence())
        }
        .map { it.trim() }
        // 只留正式版本号（1.21.1 / 26.4）。快照（25w46a）和愚人节版
        // （24w14potato、23w13a_or_b、20w14infinite）在 Fabric API 的
        // game_versions 里有一大把，全列出来会把这行筛选撑成噪音。
        .filter { it.matches(STABLE_VERSION) }
        .distinct()
        .sortedWith(compareByDescending { mcVersionSortKey(it) })
        .take(10)
        .toList()

/** `1.21.1` / `26.4` 这类正式版本号。 */
private val STABLE_VERSION = Regex("^\\d+(\\.\\d+)*$")

/** `1.21.10` > `1.21.9`（按数字比较，不按字符串），非数字段原样保留。 */
internal fun mcVersionSortKey(v: String): String =
    v.split('.').joinToString(".") { part ->
        part.toIntOrNull()?.let { n -> n.toString().padStart(4, '0') } ?: part
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
        val gvAll = o.optJSONArray("game_versions")?.let { gv ->
            (0 until gv.length()).mapNotNull { gv.optString(it)?.takeIf { s -> s.isNotBlank() } }
        }.orEmpty()
        ContentVersionUi(
            id = id,
            versionNumber = o.optString("version_number").ifBlank { o.optString("name") },
            type = type,
            typeLabel = when (type) {
                "beta" -> context.getString(R.string.version_type_beta)
                "alpha" -> context.getString(R.string.version_type_alpha)
                else -> context.getString(R.string.version_type_release)
            },
            gameVersions = gvAll.takeLast(2).joinToString(", "),
            datePublished = o.optString("date_published"),
            allGameVersions = gvAll,
        )
    }
}

/** 解析 `project_dependencies` 的返回。 */
internal fun parseDependencies(
    json: org.json.JSONArray,
    provider: String,
    context: android.content.Context,
): List<DependencyUi> = (0 until json.length()).mapNotNull { i ->
    val o = json.optJSONObject(i) ?: return@mapNotNull null
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
        provider = o.optString("provider").ifBlank { provider },
    )
}
