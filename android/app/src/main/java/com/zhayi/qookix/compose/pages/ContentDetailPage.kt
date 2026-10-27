package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ComposeMainActivity
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge
import com.zhayi.qookix.compose.bridge.friendlyError
import com.zhayi.qookix.compose.bridge.mcWikiUrl
import com.zhayi.qookix.compose.bridge.projectInfo
import com.zhayi.qookix.compose.bridge.projectVersions
import com.zhayi.qookix.compose.bridge.reportTranslationQuality
import com.zhayi.qookix.compose.bridge.reportTranslationStale
import com.zhayi.qookix.compose.bridge.translateProjectBody
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkImageWithSkeleton

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
                    info = ContentDetailUi(
                        description = o.optString("description").ifBlank { project.description },
                        body = o.optString("body"),
                        slug = o.optString("slug"),
                        author = o.optString("author").ifBlank { project.author },
                        downloads = o.optLong("downloads", project.downloads),
                        iconUrl = o.optString("icon_url").ifBlank { project.iconUrl },
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

    // ── 安装对话框（复用内容中心那一套状态机）────────────────────────────
    var installOpen by remember(project.id) { mutableStateOf(false) }
    var installVersion by remember(project.id) { mutableStateOf<String?>(null) }

    ContentDetailPage(
        project = project,
        info = info,
        bodyLoading = bodyLoading,
        bodyError = bodyError,
        bodyZh = bodyZh,
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
                            val zh = o.optString("body").takeIf { it.isNotBlank() }
                            val err = o.optString("error").takeIf { it.isNotBlank() }
                            when {
                                zh != null -> bodyZh = zh
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

/**
 * 详情页本体（纯展示，无副作用）。
 *
 * 布局：英雄区（封面/标题/作者/下载量/安装）→ 进行中任务 → 版本列表 → 正文。
 * 整页一个 [LazyColumn]：正文动辄上万字，一次性组合会卡首帧。
 */
@Composable
fun ContentDetailPage(
    project: ProjectUi,
    info: ContentDetailUi?,
    bodyLoading: Boolean,
    bodyError: String?,
    bodyZh: String?,
    translating: Boolean,
    notice: String?,
    versions: List<ContentVersionUi>,
    versionsLoading: Boolean,
    versionsError: String?,
    tasks: List<TaskUi>,
    onRetryBody: () -> Unit,
    onRetryVersions: () -> Unit,
    /** 传版本 id（null = 不预选，由安装对话框挑最新正式版）。 */
    onInstall: (String?) -> Unit,
    onTranslateBody: () -> Unit,
    onReportBadTranslation: () -> Unit,
    onReportStaleTranslation: () -> Unit,
    onOpenWiki: () -> Unit,
    onCancelTask: (Long) -> Unit,
) {
    val author = info?.author?.takeIf { it.isNotBlank() } ?: project.author
    val downloads = info?.downloads ?: project.downloads
    val icon = info?.iconUrl?.takeIf { it.isNotBlank() } ?: project.iconUrl
    val body = (bodyZh ?: info?.body).orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = QkSpacing.md,
            end = QkSpacing.md,
            top = QkSpacing.sm,
            bottom = QkSpacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
    ) {
        // ── 英雄区 ────────────────────────────────────────────────────
        item(key = "__hero__") {
            Row(verticalAlignment = Alignment.Top) {
                QkImageWithSkeleton(
                    model = icon.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    cornerRadius = 12.dp,
                )
                Spacer(Modifier.width(QkSpacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (author.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.content_detail_author, author),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (downloads > 0) {
                        Text(
                            text = stringResource(
                                R.string.content_detail_downloads,
                                fmtCount(downloads),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Spacer(Modifier.height(QkSpacing.xs))
                    Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                        Button(onClick = { onInstall(null) }) {
                            Text(stringResource(R.string.browse_install))
                        }
                        OutlinedButton(onClick = onOpenWiki) {
                            Text(stringResource(R.string.browse_open_wiki))
                        }
                    }
                }
            }
        }

        // ── 进行中任务（安装进度）──────────────────────────────────────
        //
        // 直接复用下载页的 TaskCard：它有阶段文案、速度、剩余时间，
        // 自己再画一条进度条只会是第二套实现。
        if (tasks.isNotEmpty()) {
            item(key = "__tasks_title__") {
                SectionTitle(stringResource(R.string.content_detail_tasks))
            }
            tasks.forEach { t ->
                item(key = "__task_${t.id}__") {
                    TaskCard(t, onCancel = { onCancelTask(t.id) })
                }
            }
        }

        // ── 版本列表 ──────────────────────────────────────────────────
        item(key = "__versions_title__") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.content_detail_versions))
            }
        }
        when {
            versionsLoading -> item(key = "__versions_loading__") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.content_detail_versions_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            versionsError != null -> item(key = "__versions_error__") {
                ErrorRow(text = versionsError, onRetry = onRetryVersions)
            }

            versions.isEmpty() -> item(key = "__versions_empty__") {
                Text(
                    stringResource(R.string.content_detail_versions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                // 只铺前 20 条：项目动辄上百个版本，全铺会让首帧很重；
                // 真要别的版本，安装对话框里还有完整列表。
                versions.take(20).forEach { v ->
                    item(key = "__version_${v.id}__") {
                        VersionRow(v, onClick = { onInstall(v.id) })
                    }
                }
            }
        }

        // ── 正文 ──────────────────────────────────────────────────────
        item(key = "__body_header__") {
            Column {
                Spacer(Modifier.height(QkSpacing.xs))
                HorizontalDivider()
                Spacer(Modifier.height(QkSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(
                        stringResource(R.string.content_detail_body),
                        modifier = Modifier.weight(1f),
                    )
                    if (info != null && body.isNotBlank()) {
                        if (bodyZh == null) {
                            TextButton(onClick = onTranslateBody, enabled = !translating) {
                                if (translating) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(
                                    if (translating) stringResource(R.string.browse_body_translating)
                                    else stringResource(R.string.browse_body_translate),
                                )
                            }
                        } else {
                            TextButton(onClick = onReportBadTranslation) {
                                Text(stringResource(R.string.browse_body_report_bad))
                            }
                            TextButton(onClick = onReportStaleTranslation) {
                                Text(stringResource(R.string.browse_body_report_stale))
                            }
                        }
                    }
                }
                notice?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "__body__") {
            when {
                bodyLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.content_detail_body_loading),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // 正文失败**只影响这一块**：上面英雄区/版本列表/安装按钮照旧
                bodyError != null -> ErrorRow(text = bodyError, onRetry = onRetryBody)

                body.isBlank() -> Text(
                    stringResource(R.string.content_detail_body_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Markdown(
                    content = body,
                    // 默认 transformer 是 NoOp（图片不显示），换成 Coil3 那份
                    imageTransformer = Coil3ImageTransformerImpl,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 小标题（版本 / 介绍 / 任务）。 */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/** 一条版本（点一下 = 用这个版本装）。 */
@Composable
private fun VersionRow(v: ContentVersionUi, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = QkSpacing.sm, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = v.versionNumber,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = v.typeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = v.gameVersions,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 「失败了 + 重试」，正文区和版本区共用。 */
@Composable
private fun ErrorRow(text: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(QkSpacing.xxs))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.common_retry)) }
        }
    }
}
