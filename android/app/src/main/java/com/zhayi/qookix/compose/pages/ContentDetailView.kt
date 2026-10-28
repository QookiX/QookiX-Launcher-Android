package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkImageWithSkeleton

/**
 * 详情页顶栏：图标 + 资源名 + 作者。
 *
 * 原先顶栏只放资源名，图标和作者占着正文上方一大块（用户要求「图标和名字
 * 都塞进标题栏」）。图标 28dp、两行字，正好压在 TopAppBar 的高度里。
 */
@Composable
internal fun ContentDetailTopBar(
    icon: String,
    title: String,
    author: String,
    downloads: Long,
    canInstall: Boolean,
    installing: Boolean,
    onOpenWiki: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        QkImageWithSkeleton(
            model = icon.takeIf { it.isNotBlank() },
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            cornerRadius = 6.dp,
        )
        Spacer(Modifier.width(QkSpacing.xs))
        // 名字在上、作者 + 下载量在下（不跟名字横着挤一行）
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                author.takeIf { it.isNotBlank() },
                downloads.takeIf { it > 0 }?.let { stringResource(R.string.content_detail_downloads, fmtCount(it)) },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // ⚠️ 2026-10-28（竖屏审计）：411dp 竖屏下「MC 百科」四个字 + 安装按钮
        // 把标题挤得只剩一点，作者/下载量被省略号吃掉。窄屏改成图标按钮。
        val narrow = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
        if (narrow) {
            IconButton(onClick = onOpenWiki) {
                Icon(
                    imageVector = Icons.Filled.OpenInNew,
                    contentDescription = stringResource(R.string.browse_open_wiki),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            TextButton(onClick = onOpenWiki) {
                Text(stringResource(R.string.browse_open_wiki), maxLines = 1)
            }
        }
        Spacer(Modifier.width(QkSpacing.xxs))
        Button(onClick = onInstall, enabled = canInstall && !installing) {
            if (installing) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                if (installing) stringResource(R.string.install_installing)
                else stringResource(R.string.install_install),
                maxLines = 1,
            )
        }
    }
}

/**
 * 内容详情页的两种布局。
 *
 * ## 宽屏：左边「安装」，右边「正文」
 *
 * 版本列表动辄 20 条（实测一个 Fabric 模组就有上百个版本），单栏把它们
 * 排在正文前面，正文要滑很久才看得到，「翻译正文」按钮更是永远在屏幕外。
 * 横屏 / 平板（宽度 ≥ 720dp）改成两栏：**左栏 = 安装面板（目标 + 版本筛选
 * + 版本列表 + 安装按钮），右栏 = 正文**，两栏各自滚动。
 *
 * 安装面板本身是 [ContentInstallHost] 以 `inline = true` 渲染的，
 * 和内容中心卡片点「安装」弹出的对话框共用同一套状态机 ——
 * 只有一份「筛选版本 / 选目标 / 依赖」的实现。
 *
 * ## 窄屏：单栏，正文排在安装面板之后
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
    tasks: List<TaskUi>,
    /** 安装面板（由 Route 传入 ContentInstallHost(inline = true)）。 */
    installPanel: @Composable (Modifier) -> Unit,
    onRetryBody: () -> Unit,
    onTranslateBody: () -> Unit,
    onToggleTranslation: () -> Unit,
    onReportBadTranslation: () -> Unit,
    onReportStaleTranslation: () -> Unit,
    onCancelTask: (Long) -> Unit,
) {
    val zh = bodyZh?.takeIf { showTranslation }
    val body = (zh ?: info?.body).orEmpty()
    val title = stringResource(R.string.content_detail_body)

    val toolbar: @Composable () -> Unit = {
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

    if (LocalConfiguration.current.screenWidthDp >= 720) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = QkSpacing.md, vertical = QkSpacing.sm),
        ) {
            // ── 左：安装（进行中的任务贴在最上面，装完能立刻看到进度）────
            Column(modifier = Modifier.weight(0.85f).fillMaxHeight()) {
                if (tasks.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.content_detail_tasks))
                    tasks.forEach { t -> TaskCard(t, onCancel = { onCancelTask(t.id) }) }
                    Spacer(Modifier.height(QkSpacing.xs))
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    installPanel(Modifier.fillMaxSize())
                }
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
                toolbar()
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
            taskItems(tasks, onCancelTask)
            // 面板内部自带滚动，这里必须给个确定高度（否则 weight 无法分配）
            item(key = "__install__") {
                installPanel(Modifier.fillMaxWidth().height(460.dp))
            }
            item(key = "__body_header__") {
                Spacer(Modifier.height(QkSpacing.xs))
                HorizontalDivider()
                Spacer(Modifier.height(QkSpacing.xxs))
                toolbar()
            }
            item(key = "__body__") {
                BodyInline(body = body, loading = bodyLoading, error = bodyError, onRetry = onRetryBody)
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

            // 正文失败**只影响这一块**：安装面板照旧可用
            error != null -> ErrorRow(text = error, onRetry = onRetry)

            body.isBlank() -> EmptyBody()

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item(key = "__md__") {
                    Markdown(
                        content = body,
                        // 默认 transformer 是 NoOp（图片不显示），换成 Coil3 那份
                        imageTransformer = Coil3ImageTransformerImpl,
                        // ⚠️ 2026-10-28（竖屏审计）：正文开头的 H1 字号过大
                        //（Modrinth 的 body 常以 `# 项目名` 开头，一屏占掉三行）。
                        //
                        // 试过 `typography = markdownTypography(h1 = …, h2 = …)`：
                        // 编译通过、装包实测**字号没变化** —— 0.29.0 的 m3 `Markdown`
                        // 在内部对标题做了二次处理，传进去的 typography 只覆盖了
                        // 正文/列表，标题仍用库内置的 display 级字号。
                        // 这条路走不通，先不动（见 docs/portrait-audit.md 待办）。
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
private fun androidx.compose.foundation.lazy.LazyListScope.taskItems(
    tasks: List<TaskUi>,
    onCancelTask: (Long) -> Unit,
) {
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

/** 小标题（介绍 / 任务）。 */
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

/** 「失败了 + 重试」。 */
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
