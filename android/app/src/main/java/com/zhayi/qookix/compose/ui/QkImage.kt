package com.zhayi.qookix.compose.ui

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.valentinilk.shimmer.shimmer

/**
 * 图片加载（基于 Coil 3）。
 *
 * ── 为什么用 Coil 而不是自己写 ──────────────────────────────────────────
 * 启动器要显示：实例图标、皮肤缩略图、新闻配图、整合包封面 ——
 * 这些都来自本地文件或网络。自己写会遇到：
 *   - 内存缓存 / 磁盘缓存（不缓存会反复解码，滚动卡顿）
 *   - 生命周期取消（页面切走还在解码，浪费）
 *   - 占位图 / 错误图 / 淡入
 * Coil 把这些都做完了，且是 Compose 原生 API。
 *
 * ── 本地文件路径 ────────────────────────────────────────────────────────
 * Rust 侧给的是**真实文件路径**（如 `/data/user/0/.../icon.png`）。
 * Coil 可以直接吃 `File` 或 `file://` URI，不需要 Tauri 的 asset 协议 ——
 * 这比旧前端（用 `convertFileSrc` 走 asset://）更直接。
 */
@Composable
fun QkImage(
    model: Any?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (model == null) {
        ImagePlaceholder(modifier, cornerRadius)
        return
    }

    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(true)   // 淡入，避免「啪」地跳出来
            .build(),
        contentDescription = contentDescription,
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        contentScale = contentScale,
    )
}

/**
 * 带 shimmer 骨架的图片。
 *
 * 加载中显示扫光骨架，而不是空白 —— 这是「界面活起来」的关键细节之一：
 * 用户知道「在加载」而不是「坏了」。
 */
@Composable
fun QkImageWithSkeleton(
    model: Any?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(model)
            .crossfade(true)
            .build(),
    )

    val state = painter.state
    Box(modifier = modifier.clip(RoundedCornerShape(cornerRadius))) {
        when (state) {
            is AsyncImagePainter.State.Loading, is AsyncImagePainter.State.Empty -> {
                SkeletonBox(Modifier.fillMaxSize())
            }
            // ⚠️ 2026-10-28（竖屏审计）：加载失败时原来走 else 分支，
            // 把一个失败的 painter 画进 Box —— 结果是一整块纯空白（新闻卡片
            // 上方那块空地就是这么来的，用户以为是布局坏了）。
            // 现在给一个静态底 + 破图图标，明确「这里没图」。
            is AsyncImagePainter.State.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            else -> {
                androidx.compose.foundation.Image(
                    painter = painter,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            }
        }
    }
}

/** 加载失败或没有图时的占位块。 */
@Composable
private fun ImagePlaceholder(modifier: Modifier, cornerRadius: Dp) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .let { it },
    ) {
        SkeletonBox(Modifier.fillMaxSize())
    }
}

/**
 * shimmer 骨架块。
 *
 * 这是加载态的统一视觉语言 —— 全应用一致，比各页面自己画灰色方块更专业。
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .shimmer()
            .let { it },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { it },
        ) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {}
        }
    }
}

/** 列表骨架（若干行占位），用于首屏加载。 */
@Composable
fun SkeletonList(
    rows: Int = 4,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 72.dp,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(QkSpacing.sm),
    ) {
        repeat(rows) {
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight),
                cornerRadius = 12.dp,
            )
        }
    }
}
