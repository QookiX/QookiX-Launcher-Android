package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ComposeMainActivity
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge
import com.zhayi.qookix.compose.bridge.friendlyError
import com.zhayi.qookix.compose.bridge.mcWikiUrl
import com.zhayi.qookix.compose.bridge.optText
import com.zhayi.qookix.compose.bridge.projectInfo
import com.zhayi.qookix.compose.bridge.projectVersions
import com.zhayi.qookix.compose.bridge.reportTranslationQuality
import com.zhayi.qookix.compose.bridge.reportTranslationStale
import com.zhayi.qookix.compose.bridge.translateProjectBody

/**
 * 内容中心里「当前正在看哪一项」。
 *
 * ⚠️ 为什么不把参数塞进 [com.zhayi.qookix.compose.shell.NavDestination]：
 * 它是 enum，带不了参数。选中项与会话状态一样，放在 Activity 层最省事，
 * 也才能跨横竖屏重建存活（见 ComposeMainActivity 里 selectedServerId 的说明）。
 */
data class ContentSelection(
    val project: ProjectUi,
    /** 内容类型（mod / modpack / resourcepack / shader / plugin）。 */
    val kind: String,
) {
    companion object {
        /** rememberSaveable 用：旋转 / 进程重建后还能回到详情页。 */
        val Saver: androidx.compose.runtime.saveable.Saver<ContentSelection?, Any> =
            listSaver(
                save = { s ->
                    if (s == null) {
                        emptyList()
                    } else {
                        listOf(
                            s.project.id,
                            s.project.provider,
                            s.project.title,
                            s.project.description,
                            s.project.author,
                            s.project.iconUrl,
                            s.project.downloads,
                            s.kind,
                        )
                    }
                },
                restore = { l ->
                    if (l.isEmpty()) {
                        null
                    } else {
                        ContentSelection(
                            project = ProjectUi(
                                id = l[0] as String,
                                provider = l[1] as String,
                                title = l[2] as String,
                                description = l[3] as String,
                                author = l[4] as String,
                                iconUrl = l[5] as String,
                                downloads = l[6] as Long,
                            ),
                            kind = l[7] as String,
                        )
                    }
                },
            )
    }
}

/** `project_info` 里详情页真正要用的字段（其余忽略）。 */
data class ContentDetailUi(
    val description: String,
    val body: String,
    val slug: String,
    val author: String,
    val downloads: Long,
    val iconUrl: String,
    val categories: List<String> = emptyList(),
)

/**
 * 内容详情页的宿主：拉数据 + 组装状态。
 *
 * ## 正文与其它区域**分开加载**
 *
 * 列表页点进来时已经带着标题 / 作者 / 下载量 / 封面（[ProjectUi]），
 * 所以英雄区与安装按钮**不依赖网络**。只有正文（`project_info.body`）
 * 需要请求 —— 它失败时只把正文区变成「报错 + 重试」，
 * 安装按钮和版本列表照旧可用（断网进详情也不至于什么都干不了）。
 */
@Composable
fun ContentDetailRoute(
    bridge: RustBridge,
    selection: ContentSelection,
    activity: ComposeMainActivity? = null,
    /** 进行中的任务（由外层共用的 TasksRepo 快照传入，不另建订阅）。 */
    tasks: List<TaskUi> = emptyList(),
    onCancelTask: (Long) -> Unit = {},
    /** 把资源名报到顶栏（返回内容中心时由调用方清空）。 */
    onTitleChange: (String?) -> Unit = {},
    /** 没有可用实例时的引导（去新建实例）。 */
    onRequestCreateInstance: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val api = remember(bridge) { QookixApi(bridge) }
    val project = selection.project

    // ── 顶栏标题 = 资源名 ────────────────────────────────────────────────
    LaunchedEffect(project.title) { onTitleChange(project.title) }
    // 离开时清掉覆盖，否则回到内容中心标题还停在资源名
    DisposableEffect(Unit) { onDispose { onTitleChange(null) } }

    // ── 正文（失败只影响正文区）──────────────────────────────────────────
    var info by remember(project.id) { mutableStateOf<ContentDetailUi?>(null) }
    var bodyLoading by remember(project.id) { mutableStateOf(true) }
    var bodyError by remember(project.id) { mutableStateOf<String?>(null) }
    var bodyRetry by remember(project.id) { mutableStateOf(0) }

    LaunchedEffect(project.id, bodyRetry) {
        bodyLoading = true
        bodyError = null
        api.projectInfo(project.provider, project.id) { r ->
            bodyLoading = false
            r.fold(
                onSuccess = { o ->
                    // ⚠️ 不能用 optString：字段是 JSON null 时它会返回字面量 "null"
                    //（真机实测正文直接显示 null）。optText 才会给出真正的 null。
                    info = ContentDetailUi(
                        description = o.optText("description") ?: project.description,
                        body = o.optText("body").orEmpty(),
                        slug = o.optText("slug").orEmpty(),
                        author = o.optText("author") ?: project.author,
                        downloads = o.optLong("downloads", project.downloads),
                        iconUrl = o.optText("icon_url") ?: project.iconUrl,
                        categories = o.optJSONArray("categories")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i).takeIf { it.isNotBlank() }
                            }
                        }.orEmpty(),
                    )
                },
                onFailure = { e ->
                    bodyError = context.getString(
                        R.string.content_detail_body_failed,
                        friendlyError(context, e),
                    )
                },
            )
        }
    }

    // ── 版本列表 ────────────────────────────────────────────────────────
    var versions by remember(project.id) { mutableStateOf<List<ContentVersionUi>>(emptyList()) }
    var versionsLoading by remember(project.id) { mutableStateOf(true) }
    var versionsError by remember(project.id) { mutableStateOf<String?>(null) }
    var versionsRetry by remember(project.id) { mutableStateOf(0) }

    LaunchedEffect(project.id, versionsRetry) {
        versionsLoading = true
        versionsError = null
        // 不带 mc/loader：详情页展示的是「这个项目有哪些版本」，
        // 按实例筛是安装对话框的事（那里才知道目标实例）。
        api.projectVersions(project.provider, project.id, "", "") { r ->
            versionsLoading = false
            r.fold(
                onSuccess = { versions = parseContentVersions(context, it) },
                onFailure = { e ->
                    versionsError = context.getString(
                        R.string.content_detail_versions_failed,
                        friendlyError(context, e),
                    )
                },
            )
        }
    }

    // ── 正文翻译（沿用 translate_project_body，别另造一套）────────────────
    var bodyZh by remember(project.id) { mutableStateOf<String?>(null) }
    var translating by remember(project.id) { mutableStateOf(false) }
    var notice by remember(project.id) { mutableStateOf<String?>(null) }
    // 译文拿到后还能切回原文看（术语 / 代码块翻坏了可以对照）
    var showZh by remember(project.id) { mutableStateOf(true) }

    // ── 安装对话框（复用内容中心那一套状态机）────────────────────────────
    var installOpen by remember(project.id) { mutableStateOf(false) }
    var installVersion by remember(project.id) { mutableStateOf<String?>(null) }

    ContentDetailLayout(
        project = project,
        info = info,
        bodyLoading = bodyLoading,
        bodyError = bodyError,
        bodyZh = bodyZh,
        showTranslation = showZh,
        translating = translating,
        notice = notice,
        versions = versions,
        versionsLoading = versionsLoading,
        versionsError = versionsError,
        tasks = tasks,
        onRetryBody = { bodyRetry++ },
        onRetryVersions = { versionsRetry++ },
        onInstall = { versionId ->
            installVersion = versionId
            installOpen = true
        },
        onToggleTranslation = { showZh = !showZh },
        onTranslateBody = {
            val slug = info?.slug.orEmpty()
            if (slug.isBlank()) {
                notice = context.getString(R.string.browse_body_translate_empty)
            } else {
                translating = true
                notice = null
                api.translateProjectBody(project.provider, slug, true) { r ->
                    translating = false
                    r.fold(
                        onSuccess = { o ->
                            val supported = o.optBoolean("supported", false)
                            // 同上的 null 陷阱：`body` 为 JSON null 时 optString 会给出 "null"
                            val zh = o.optText("body")
                            val err = o.optText("error")
                            when {
                                zh != null -> {
                                    bodyZh = zh
                                    showZh = true
                                    notice = null
                                }
                                err != null -> notice = err
                                !supported -> notice =
                                    context.getString(R.string.browse_body_translate_unsupported)
                                else -> notice =
                                    context.getString(R.string.browse_body_translate_empty)
                            }
                        },
                        onFailure = { e ->
                            notice = context.getString(
                                R.string.browse_translate_failed,
                                e.message ?: "",
                            )
                        },
                    )
                }
            }
        },
        onReportBadTranslation = {
            api.reportTranslationQuality(project.provider, info?.slug.orEmpty(), "inaccurate") { }
            notice = context.getString(R.string.browse_body_report_thanks)
        },
        onReportStaleTranslation = {
            api.reportTranslationStale(project.provider, info?.slug.orEmpty()) { }
            notice = context.getString(R.string.browse_body_report_stale_thanks)
        },
        onOpenWiki = {
            val title = project.title
            val slug = info?.slug
            api.mcWikiUrl(title, slug?.takeIf { it.isNotBlank() }, project.provider) { r ->
                r.onSuccess { url ->
                    if (url.isNotBlank()) activity?.openExternalUrl(url)
                }
            }
        },
        onCancelTask = onCancelTask,
    )

    if (installOpen) {
        ContentInstallHost(
            api = api,
            project = project,
            kind = selection.kind,
            initialVersionId = installVersion,
            onCreateTargetInstance = onRequestCreateInstance?.let { cb ->
                {
                    installOpen = false
                    cb()
                }
            },
            onDismiss = { installOpen = false },
        )
    }
}
