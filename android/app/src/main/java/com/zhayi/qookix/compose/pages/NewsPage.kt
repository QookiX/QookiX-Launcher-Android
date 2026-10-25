package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.theme.QkSpacing

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.*

/**
 * 新闻页。
 *
 * 对应旧界面 views/NewsView.vue + stores/news.ts。
 *
 * ── 这是 Phase 1 里唯一需要后端数据的页面 ─────────────────────────────
 * 它调用 Rust 的 `fetch_news` 命令，所以**它同时是桥的验证用例**：
 * 新闻列表能显示出来 = IPC 通道成立 = Phase 2 可以按同一模式铺开。
 *
 * 旧实现的行为（保持等价）：
 *   - 进页面时若列表为空则自动加载
 *   - 有 10 分钟缓存（CACHE_TTL），重新加载按钮强制刷新
 *   - 每张卡片：图片 / 标题 / 描述 / 日期 · 作者
 *   - 点击卡片用系统浏览器打开链接
 */
@Composable
fun NewsPage(
    state: NewsUiState,
    onRefresh: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶栏右侧的「刷新」按钮（旧实现是右对齐的小按钮）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QkSpacing.md, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.height(0.dp))
                Text(stringResource(R.string.news_refresh))
            }
        }

        when {
            state.loading && state.items.isEmpty() -> {
                // 骨架屏：比转圈更「活」—— 用户能看到即将出现的内容形状
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = QkSpacing.md),
                ) {
                    com.zhayi.qookix.compose.ui.SkeletonList(rows = 4, rowHeight = 120.dp)
                }
            }

            state.items.isEmpty() -> CenterBox {
                Text(
                    text = state.error ?: stringResource(R.string.news_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
            ) {
                items(state.items, key = { it.url }) { n ->
                    NewsCard(n) { onOpen(n.url) }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun NewsCard(n: NewsItemUi, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(QkSpacing.sm)) {
            // 配图：有图才显示（用 Coil，带淡入 + 骨架）
            if (n.image.isNotBlank()) {
                com.zhayi.qookix.compose.ui.QkImageWithSkeleton(
                    model = n.image,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    cornerRadius = 10.dp,
                )
                Spacer(Modifier.height(QkSpacing.sm))
            }

            Text(
                text = n.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (n.description.isNotBlank()) {
                Spacer(Modifier.height(QkSpacing.xs))
                Text(
                    text = n.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(QkSpacing.xs))
            Text(
                text = buildString {
                    append(n.date)
                    if (n.author.isNotBlank()) append(" · ").append(n.author)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** 新闻项（UI 模型，与 Rust 返回的 JSON 对应）。 */
data class NewsItemUi(
    val title: String,
    val description: String,
    val date: String,
    val author: String,
    val url: String,
    val image: String,
)

/** 新闻页状态。 */
data class NewsUiState(
    val items: List<NewsItemUi> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 加载新闻。这是 Phase 1 里**唯一的后端调用点**，用来验证 IPC 通道。
 *
 * 走 Rust 的 `fetch_news`（对应 browse.rs 的实现，拉 Mojang 官方新闻）。
 *
 * ⚠️ i18n：本函数不是 @Composable，拿不到 `stringResource`。
 * 带 [Context] 的重载走资源（`R.string.news_load_failed`）；
 * 不带 Context 的旧签名仍保留以兼容现有调用点，失败文案回退为不带前缀的原始错误。
 * **建议调用点（`PageHost.kt:4371/4376`）改传 `LocalContext.current`。**
 */
fun loadNews(
    bridge: RustBridge,
    previous: NewsUiState = NewsUiState(),
    onState: (NewsUiState) -> Unit,
) = loadNews(null, bridge, previous, onState)

/**
 * 带 [context] 的重载：失败文案走 string 资源。
 *
 * [previous] 是当前已显示的内容：刷新时**保留**它，只在结果到达时整体替换。
 * 以前每次都从 `NewsUiState(loading = true)` 重建 —— 点一下刷新，已有新闻先被
 * 清空闪成骨架屏，失败时更是直接清空、只剩一行错误（审查里的「新闻刷新失败
 * 清空已有列表」）。
 */
fun loadNews(
    context: Context?,
    bridge: RustBridge,
    previous: NewsUiState = NewsUiState(),
    onState: (NewsUiState) -> Unit,
) {
    onState(previous.copy(loading = true, error = null))
    bridge.invoke("fetch_news") { result ->
        result.fold(
            onSuccess = { json ->
                onState(NewsUiState(items = parseNews(json), loading = false))
            },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                onState(
                    previous.copy(
                        loading = false,
                        error = context?.getString(R.string.news_load_failed, msg) ?: msg,
                    )
                )
            },
        )
    }
}
