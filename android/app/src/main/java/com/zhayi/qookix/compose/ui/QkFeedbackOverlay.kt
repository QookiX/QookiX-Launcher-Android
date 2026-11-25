package com.zhayi.qookix.compose.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.motion.QkMotionPolicy
import kotlinx.coroutines.delay

internal data class QkOverlayMessage(
    val text: String,
    val error: Boolean = false,
    val extended: Boolean = false,
    val sequence: Long = 0,
    val title: String? = null,
)

@Composable
internal fun QkGlobalFeedbackHost(modifier: Modifier = Modifier) {
    val notice by QkFeedback.message.collectAsState()
    QkFeedbackOverlay(
        message = notice?.let { QkOverlayMessage(it.text, it.error, sequence = it.seq) },
        onDismiss = { QkFeedback.clear(it.sequence) },
        modifier = modifier,
    )
}

/** Place at the top end of a Box, above content without taking its space. */
@Composable
internal fun QkFeedbackOverlay(
    message: QkOverlayMessage?,
    onDismiss: (QkOverlayMessage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var details by remember { mutableStateOf<QkOverlayMessage?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        val item = message ?: return@LaunchedEffect
        delay(if (item.error || item.extended) 8000L else 3000L)
        dismiss(item)
    }
    val animate = QkMotionPolicy.animationsEnabled
    AnimatedContent(
        targetState = message,
        modifier = modifier.padding(top = 8.dp, end = 12.dp, start = 12.dp).widthIn(max = 340.dp),
        contentAlignment = Alignment.TopEnd,
        transitionSpec = {
            if (animate) {
                (fadeIn(tween(150)) + slideInHorizontally(tween(180)) { it / 7 }) togetherWith
                    (fadeOut(tween(120)) + slideOutHorizontally(tween(140)) { it / 8 })
            } else EnterTransition.None togetherWith ExitTransition.None
        },
        label = "qkFeedback",
    ) { item ->
        if (item != null) {
            val active = item == message
            val colors = MaterialTheme.colorScheme
            Surface(
                modifier = Modifier.semantics { if (active) liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.medium,
                color = if (item.error) colors.errorContainer else colors.inverseSurface,
                contentColor = if (item.error) colors.onErrorContainer else colors.inverseOnSurface,
                shadowElevation = 4.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.text.removePrefix("java.lang.IllegalStateException: "),
                        modifier = Modifier.weight(1f, fill = false).clickable(enabled = active) { details = item }
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(enabled = active, onClick = { dismiss(item) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
            }
        }
    }
    details?.let { item ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = item.title?.let { title -> { Text(title) } },
            text = { Text(item.text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { details = null }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}
