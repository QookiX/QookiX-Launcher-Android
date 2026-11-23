package com.zhayi.qookix.compose.pages.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.pages.settings.parseHexColorOrNull
import com.zhayi.qookix.compose.theme.QkSpacing
import org.json.JSONObject

@Composable
internal fun HomeNodes(nodes: List<HomeNode>, vars: Map<String, String>, run: (List<JSONObject>) -> Unit) {
    nodes.forEach { node ->
        key(node) {
            if (node.visible(vars)) HomeComponent(node, vars, run)
        }
    }
}

@Composable
private fun HomeComponent(node: HomeNode, vars: Map<String, String>, run: (List<JSONObject>) -> Unit) {
    fun field(key: String) = interpolateHome(node.text(key), vars)
    val align = node.text("align")
    val width = node.number("width")?.coerceIn(1f, 2000f)?.dp
    val text = field("text")
    when (node.type) {
        "card" -> Card(Modifier.fillMaxWidth()) { HomeCardContent(node, vars, run) }
        "text" -> {
            val explicitColor = field("color")
            val color = when (explicitColor) {
                "var(--accent)" -> MaterialTheme.colorScheme.primary
                "var(--text-3)" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> parseHexColorOrNull(explicitColor)
                    ?: if (node.flag("muted")) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = boxAlignment(align)) {
                Text(
                    text = text,
                    modifier = if (width == null) Modifier.fillMaxWidth() else Modifier.widthIn(max = width),
                    color = color,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (node.flag("bold")) FontWeight.SemiBold else null,
                    fontSize = node.number("size")?.coerceIn(8f, 72f)?.sp ?: TextUnit.Unspecified,
                    textAlign = when (align) { "center" -> TextAlign.Center; "right" -> TextAlign.Right; else -> TextAlign.Left },
                )
            }
        }
        "hint" -> {
            val colors = MaterialTheme.colorScheme
            val (background, foreground) = when (node.text("theme")) {
                "red" -> colors.errorContainer to colors.onErrorContainer
                "yellow" -> colors.tertiaryContainer to colors.onTertiaryContainer
                else -> colors.secondaryContainer to colors.onSecondaryContainer
            }
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = background, contentColor = foreground) {
                Text(text, Modifier.padding(QkSpacing.md), style = MaterialTheme.typography.bodyMedium)
            }
        }
        "image" -> {
            val height = (node.number("height") ?: 176f).coerceIn(24f, 600f).dp
            Box(Modifier.fillMaxWidth(), contentAlignment = boxAlignment(align)) {
                HomeImage(field("src"), field("fallback"), (width?.let { Modifier.width(it) } ?: Modifier.fillMaxWidth()).height(height))
            }
        }
        "button" -> HomeButton(node, text, field("tooltip"), width, align) { run(node.actions) }
        "grid", "row", "buttons" -> HomeGroup(node, vars, run)
        "list-item" -> {
            val clickable = node.actions.isNotEmpty()
            Surface(
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .then(if (clickable) Modifier.clickable(role = Role.Button) { run(node.actions) } else Modifier),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(Modifier.padding(QkSpacing.md), Arrangement.spacedBy(QkSpacing.sm), Alignment.CenterVertically) {
                    val logo = field("logo")
                    if (imageModel(logo) != null) HomeImage(logo, "", Modifier.size(40.dp))
                    Column {
                        Text(field("title"), style = MaterialTheme.typography.titleSmall)
                        val info = field("info")
                        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        "divider" -> HorizontalDivider()
        "spacer" -> Spacer(Modifier.height((node.number("size") ?: 8f).coerceIn(0f, 320f).dp))
    }
}

@Composable
private fun HomeCardContent(node: HomeNode, vars: Map<String, String>, run: (List<JSONObject>) -> Unit) {
    val title = interpolateHome(node.text("title"), vars)
    val collapsible = node.flag("collapsible") && title.isNotBlank()
    var collapsed by remember(node, collapsible) { mutableStateOf(collapsible && node.flag("collapsed")) }
    Column {
        if (title.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .then(if (collapsible) Modifier.clickable(role = Role.Button) { collapsed = !collapsed } else Modifier)
                    .padding(QkSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (collapsible) Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, null)
            }
        }
        if (!collapsed && node.children.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = QkSpacing.md, end = QkSpacing.md, bottom = QkSpacing.md,
                    top = if (title.isBlank()) QkSpacing.md else 0.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
            ) { HomeNodes(node.children, vars, run) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeButton(node: HomeNode, text: String, tooltip: String, width: Dp?, align: String, onClick: () -> Unit) {
    val enabled = !node.flag("disabled")
    val content: @Composable () -> Unit = {
        val modifier = width?.let { Modifier.width(it) } ?: Modifier
        when (node.text("variant")) {
            "primary" -> Button(onClick, modifier, enabled) { Text(text) }
            "ghost" -> TextButton(onClick, modifier, enabled) { Text(text) }
            "danger" -> Button(
                onClick, modifier, enabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(text) }
            else -> FilledTonalButton(onClick, modifier, enabled) { Text(text) }
        }
    }
    Box(
        modifier = if (align.isNotBlank()) Modifier.fillMaxWidth() else Modifier,
        contentAlignment = boxAlignment(align),
    ) {
        if (tooltip.isBlank()) content() else TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
            content = content,
        )
    }
}

@Composable
private fun HomeGroup(node: HomeNode, vars: Map<String, String>, run: (List<JSONObject>) -> Unit) {
    val children = node.children.filter { it.visible(vars) }
    if (children.isEmpty()) return
    val gap = node.number("gap")?.coerceIn(0f, 40f)?.dp ?: QkSpacing.sm
    val arrangement = Arrangement.spacedBy(gap, when (node.text("align")) {
        "right" -> Alignment.End
        "center" -> Alignment.CenterHorizontally
        else -> Alignment.Start
    })
    val tracks = node.fields.optJSONArray("columns")?.let { columns ->
        List(columns.length().coerceAtMost(8)) { columns.optString(it) }
    }.orEmpty()
    val buttonColumns = node.number("columns")?.toInt()?.coerceIn(1, 8)
    if (children.all { it.type == "button" } && node.type != "grid" && buttonColumns == null) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(gap)) {
            HomeNodes(children, vars, run)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxColumns = ((maxWidth.value + gap.value) / (132f + gap.value)).toInt().coerceIn(1, 8)
        val requested = tracks.size.takeIf { it > 0 } ?: buttonColumns ?: children.size
        val columns = minOf(maxColumns, requested)
        val available = (maxWidth - gap * (columns - 1)).coerceAtLeast(0.dp)
        val widths = if (columns == tracks.size) resolveWidths(tracks, available) else List(columns) { available / columns }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            children.chunked(columns).forEach { row ->
                HomeGroupRow(row, widths, arrangement, vars, run)
            }
        }
    }
}

@Composable
private fun HomeGroupRow(
    row: List<HomeNode>,
    widths: List<Dp>,
    arrangement: Arrangement.Horizontal,
    vars: Map<String, String>,
    run: (List<JSONObject>) -> Unit,
) {
    val cards = row.indices.filter { row[it].type == "card" }
    val cells: @Composable () -> Unit = {
        row.forEachIndexed { index, child ->
            key(child) {
                Box(Modifier.width(widths[index]), contentAlignment = boxAlignment(child.text("align"))) {
                    if (child.type == "card") {
                        CompositionLocalProvider(LocalContentColor provides CardDefaults.cardColors().contentColor) {
                            HomeCardContent(child, vars, run)
                        }
                    } else {
                        HomeComponent(child, vars, run)
                    }
                }
            }
        }
    }
    if (cards.isEmpty()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = arrangement) { cells() }
        return
    }

    // Measure each cell once so nested grids and collapsible cards can set their natural height.
    val surfaces: @Composable () -> Unit = { cards.forEach { Card {} } }
    Layout(contents = listOf(surfaces, cells), modifier = Modifier.fillMaxWidth()) { (backgrounds, content), constraints ->
        val width = constraints.maxWidth
        val gaps = arrangement.spacing.roundToPx() * (row.size - 1)
        var remaining = (width - gaps).coerceAtLeast(0)
        val measuredCells = content.map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0, maxWidth = remaining, minHeight = 0)).also {
                remaining -= it.width
            }
        }
        val height = measuredCells.maxOf { it.height }
        val positions = IntArray(measuredCells.size)
        with(arrangement) {
            arrange(width, measuredCells.map { it.width }.toIntArray(), layoutDirection, positions)
        }
        val measuredSurfaces = backgrounds.mapIndexed { index, measurable ->
            val cell = measuredCells[cards[index]]
            val cardHeight = if (cards.size > 1) height else cell.height
            measurable.measure(Constraints.fixed(cell.width, cardHeight))
        }
        layout(width, height) {
            measuredSurfaces.forEachIndexed { index, placeable -> placeable.place(positions[cards[index]], 0) }
            measuredCells.forEachIndexed { index, placeable -> placeable.place(positions[index], 0) }
        }
    }
}

private val trackSize = Regex("(\\d+(?:\\.\\d+)?)(fr|px|%|em|rem)", RegexOption.IGNORE_CASE)

private fun resolveWidths(tracks: List<String>, available: Dp): List<Dp> {
    val sizes = tracks.map { track ->
        val match = trackSize.matchEntire(track.trim())
        val value = match?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 2000f) ?: 1f
        when (match?.groupValues?.get(2)?.lowercase()) {
            "px" -> value to 0f
            "%" -> (available.value * value / 100f) to 0f
            "em", "rem" -> (value * 16f) to 0f
            else -> 0f to value.coerceAtLeast(0.1f)
        }
    }
    val fixed = sizes.sumOf { it.first.toDouble() }.toFloat()
    val flexible = sizes.sumOf { it.second.toDouble() }.toFloat()
    val remaining = (available.value - fixed).coerceAtLeast(0f)
    val widths = sizes.map { (size, weight) -> size + remaining * (weight / flexible.coerceAtLeast(0.1f)) }
    return if (fixed > available.value || widths.any { it < 72f }) {
        List(tracks.size) { available / tracks.size }
    } else widths.map { it.dp }
}

private fun boxAlignment(align: String): Alignment = when (align) {
    "center" -> Alignment.Center
    "right" -> Alignment.CenterEnd
    else -> Alignment.CenterStart
}

private fun imageModel(source: String): String? = if (source.startsWith("data:image/", ignoreCase = true)) {
    source
} else webAddress(source)?.toString()

@Composable
private fun HomeImage(source: String, fallback: String, modifier: Modifier) {
    val original = imageModel(source)
    val backup = imageModel(fallback)
    var failed by remember(original, backup) { mutableStateOf(false) }
    var backupFailed by remember(original, backup) { mutableStateOf(false) }
    val model = if (failed || original == null) backup else original
    if (model == null || backupFailed) {
        Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(contentAlignment = Alignment.Center) { Text(stringResource(R.string.custom_home_image_unavailable)) }
        }
    } else {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = modifier.clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Fit,
            onError = { if (model == original && !failed) failed = true else backupFailed = true },
        )
    }
}
