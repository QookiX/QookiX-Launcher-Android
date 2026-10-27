package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkImageWithSkeleton

/**
 * 内容详情页的两种布局。
 *
 * ## 为什么宽屏要分左右两栏
 *
 * 版本列表动辄 20 条（实测一个 Fabric 模组就有上百个版本），单栏把它们
 * 排在正文前面，正文要滑很久才看得到，「翻译正文」按钮更是永远在屏幕外。
 * 横屏 / 平板（宽度 ≥ 720dp）改成：**左边下载（英雄区 + 版本），右边正文**，
 * 两栏各自滚动，正文的工具栏固定在自己那一栏顶部。
 *
 * 窄屏（手机竖屏）仍是单栏，但正文排在版本**之前** —— 用户先看介绍，
 * 想指定版本时再往下滑。
 *
 * ## 正文失败只影响正文
 *
 * 英雄区、安装按钮、版本列表都不依赖 `project_info` 请求，
 * 断网进来照样能点安装。
 */
@Composable
internal fun ContentDetailLayout(
    project: ProjectUi,
    info: ContentDetailUi?,
    bodyLoading: Boolean,
    bodyError: String?,
    bodyZh: String?,
    showTranslation: Boolean,
    translating: Boolean,
    notice: String?,
    versions: List<ContentVersionUi>,
    versionsLoading: Boolean,
    versionsError: String?,
    tasks: List<TaskUi>,
    onRetryBody: () -> Unit,
    onRetryVersions: () -> Unit,
    onInstall: (String?) -> Unit,
    onTranslateBody: () -> Unit,
    onToggleTranslation: () -> Unit,
    onReportBadTranslation: () -> Unit,
    onReportStaleTranslation: () -> Unit,
    onOpenWiki: () -> Unit,
    onCancelTask: (Long) -> Unit,
) {
    val author = info?.author?.takeIf { it.isNotBlank() } ?: project.author
    val downloads = info?.downloads ?: project.downloads
    val icon = info?.iconUrl?.takeIf { it.isNotBlank() } ?: project.iconUrl
    val zh = bodyZh?.takeIf { showTranslation }
    val body = (zh ?: info?.body).orEmpty()
    val title = stringResource(R.string.content_detail_body)

    if (LocalConfiguration.current.screenWidthDp >= 720) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = QkSpacing.md, vertical = QkSpacing.sm),
        ) {
            // ── 左：下载 ────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier.weight(0.85f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = QkSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            ) {
                item(key = "__hero__") {
                    DetailHero(
                        project = project,
                        author = author,
                        downloads = downloads,
                        icon = icon,
                        compact = true,
                        onInstall = onInstall,
                        onOpenWiki = onOpenWiki,
                    )
                }
                taskItems(tasks, onCancelTask)
                versionItems(versions, versionsLoading, versionsError, onRetryVersions, onInstall)
            }

            Box(
                modifier = Modifier
                    .padding(horizontal = QkSpacing.sm)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )

            // ── 右：正文（工具栏固定，正文自己滚）────────────────────────
            Column(modifier = Modifier.weight(1.15f).fillMaxHeight()) {
                BodyToolbar(
                    title = title,
                    translated = bodyZh != null,
                    showTranslation = showTranslation,
                    translating = translating,
                    hasBody = info?.body?.isNotBlank() == true,
                    notice = notice,
                    onTranslate = onTranslateBody,
                    onToggle = onToggleTranslation,
                    onReportBad = onReportBadTranslation,
                    onReportStale = onReportStaleTranslation,
                )
                HorizontalDivider()
                BodyContent(
                    body = body,
                    loading = bodyLoading,
                    error = bodyError,
                    onRetry = onRetryBody,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = QkSpacing.md,
                end = QkSpacing.md,
                top = QkSpacing.sm,
                bottom = QkSpacing.xl,
            ),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        ) {
            item(key = "__hero__") {
                DetailHero(
                    project = project,
                    author = author,
                    downloads = downloads,
                    icon = icon,
                    compact = false,
                    onInstall = onInstall,
                    onOpenWiki = onOpenWiki,
                )
            }
            taskItems(tasks, onCancelTask)
            // 正文在版本之前：版本可能上百条，放前面会把正文顶到很远
            item(key = "__body_header__") {
                BodyToolbar(
                    title = title,
                    translated = bodyZh != null,
                    showTranslation = showTranslation,
                    translating = translating,
                    hasBody = info?.body?.isNotBlank() == true,
                    notice = notice,
                    onTranslate = onTranslateBody,
                    onToggle = onToggleTranslation,
                    onReportBad = onReportBadTranslation,
                    onReportStale = onReportStaleTranslation,
                )
            }
            item(key = "__body__") {
                BodyInline(body = body, loading = bodyLoading, error = bodyError, onRetry = onRetryBody)
            }
            versionItems(versions, versionsLoading, versionsError, onRetryVersions, onInstall)
        }
    }
}

/** 左栏放不下两个标准内边距的按钮（会各占一行），收窄一点。 */
private val ButtonsCompactPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)

/** 英雄区：封面 / 标题 / 作者 / 下载量 + 安装、MC 百科。 */
@Composable
private fun DetailHero(
    project: ProjectUi,
    author: String,
    downloads: Long,
    icon: String,
    compact: Boolean,
    onInstall: (String?) -> Unit,
    onOpenWiki: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        QkImageWithSkeleton(
            model = icon.takeIf { it.isNotBlank() },
            contentDescription = null,
            modifier = Modifier.size(if (compact) 64.dp else 72.dp),
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
                    text = stringResource(R.string.content_detail_downloads, fmtCount(downloads)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(QkSpacing.xs))
            // FlowRow 而不是 Row：左栏宽度有限时，「MC 百科」整个按钮换到下一行，
            // 而不是把按钮**里面的文字**折成两行（那个更难读）
            FlowRow(horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs)) {
                Button(
                    onClick = { onInstall(null) },
                    contentPadding = ButtonsCompactPadding,
                ) {
                    Text(stringResource(R.string.browse_install), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onOpenWiki,
                    contentPadding = ButtonsCompactPadding,
                ) {
                    Text(stringResource(R.string.browse_open_wiki), maxLines = 1)
                }
            }
        }
    }
}

/**
 * 正文工具栏：标题 + 翻译 / 显示原文 / 译文反馈。
 *
 * 宽屏固定在右栏顶部，正文怎么滚都还能点到；窄屏作为列表里的一项。
 */
@Composable
private fun BodyToolbar(
    title: String,
    translated: Boolean,
    showTranslation: Boolean,
    translating: Boolean,
    hasBody: Boolean,
    notice: String?,
    onTranslate: () -> Unit,
    onToggle: () -> Unit,
    onReportBad: () -> Unit,
    onReportStale: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = QkSpacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, modifier = Modifier.weight(1f))
            if (hasBody) {
                if (!translated) {
                    TextButton(onClick = onTranslate, enabled = !translating) {
                        if (translating) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (translating) stringResource(R.string.browse_body_translating)
                            else stringResource(R.string.browse_body_translate),
                        )
                    }
                } else {
                    TextButton(onClick = onToggle) {
                        Text(
                            if (showTranslation) stringResource(R.string.content_detail_show_original)
                            else stringResource(R.string.content_detail_show_translation),
                        )
                    }
                    TextButton(onClick = onReportBad) {
                        Text(stringResource(R.string.browse_body_report_bad))
                    }
                    TextButton(onClick = onReportStale) {
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

/** 宽屏正文：自己滚，工具栏不跟着走。 */
@Composable
private fun BodyContent(
    body: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        when {
            loading -> LoadingRow(
                text = stringResource(R.string.content_detail_body_loading),
                modifier = Modifier.padding(top = QkSpacing.sm),
            )

            // 正文失败**只影响这一块**：英雄区 / 版本列表 / 安装按钮照旧
            error != null -> ErrorRow(text = error, onRetry = onRetry)

            body.isBlank() -> EmptyBody()

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "__md__") {
                    Markdown(
                        content = body,
                        // 默认 transformer 是 NoOp（图片不显示），换成 Coil3 那份
                        imageTransformer = Coil3ImageTransformerImpl,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 窄屏正文：外面已经是 LazyColumn，这里不能再嵌一个同方向的滚动容器。 */
@Composable
private fun BodyInline(
    body: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    when {
        loading -> LoadingRow(text = stringResource(R.string.content_detail_body_loading))
        error != null -> ErrorRow(text = error, onRetry = onRetry)
        body.isBlank() -> EmptyBody()
        else -> Markdown(
            content = body,
            imageTransformer = Coil3ImageTransformerImpl,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 进行中的安装任务，复用下载页的卡片（阶段 / 速度 / 剩余时间都在里面）。 */
private fun LazyListScope.taskItems(tasks: List<TaskUi>, onCancelTask: (Long) -> Unit) {
    if (tasks.isEmpty()) return
    item(key = "__tasks_title__") {
        SectionTitle(stringResource(R.string.content_detail_tasks))
    }
    tasks.forEach { t ->
        item(key = "__task_${t.id}__") {
            TaskCard(t, onCancel = { onCancelTask(t.id) })
        }
    }
}

/** 版本列表（详情页只铺前 20 条；完整列表在安装对话框里）。 */
private fun LazyListScope.versionItems(
    versions: List<ContentVersionUi>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onInstall: (String?) -> Unit,
) {
    item(key = "__versions_title__") {
        SectionTitle(stringResource(R.string.content_detail_versions))
    }
    when {
        loading -> item(key = "__versions_loading__") {
            LoadingRow(text = stringResource(R.string.content_detail_versions_loading))
        }

        error != null -> item(key = "__versions_error__") {
            ErrorRow(text = error, onRetry = onRetry)
        }

        versions.isEmpty() -> item(key = "__versions_empty__") {
            Text(
                stringResource(R.string.content_detail_versions_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        else -> versions.take(20).forEach { v ->
            item(key = "__version_${v.id}__") {
                VersionRow(v, onClick = { onInstall(v.id) })
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyBody() {
    Text(
        stringResource(R.string.content_detail_body_empty),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
