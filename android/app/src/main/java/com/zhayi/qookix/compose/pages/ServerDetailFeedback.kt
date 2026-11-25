package com.zhayi.qookix.compose.pages

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.ui.QkFeedbackOverlay
import com.zhayi.qookix.compose.ui.QkOverlayMessage

private enum class FeedbackKind { Error, Wake, Exit, Notice, Command }

@Composable
internal fun ServerDetailFeedback(
    state: ServerDetailUiState,
    onDismissNotice: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wake = state.wakeError?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.server_detail_wake_failed, it) }
    var dismissedWake by remember(state.id, wake) { mutableStateOf(false) }
    var dismissedExit by remember(state.id, state.exitNote) { mutableStateOf(false) }
    var dismissedCommand by remember(state.id, state.commandFeedback) { mutableStateOf(false) }
    val commandTitle = stringResource(R.string.server_detail_command_result)
    val feedback = state.error?.takeIf { it.isNotBlank() }?.let { FeedbackKind.Error to QkOverlayMessage(it, error = true) }
        ?: wake?.takeUnless { dismissedWake }?.let { FeedbackKind.Wake to QkOverlayMessage(it, error = true) }
        ?: state.commandFeedback?.takeIf { !dismissedCommand && it.isNotBlank() }
            ?.let { FeedbackKind.Command to QkOverlayMessage(it, state.commandFailed, extended = true, title = commandTitle) }
        ?: state.notice?.takeIf { it.isNotBlank() }?.let { FeedbackKind.Notice to QkOverlayMessage(it) }
        ?: state.exitNote?.takeIf { !dismissedExit && it.isNotBlank() && it != "已停止（退出码 0）" }
            ?.let { FeedbackKind.Exit to QkOverlayMessage(it, extended = true) }
    QkFeedbackOverlay(
        message = feedback?.let { (kind, item) -> item.copy(sequence = kind.ordinal.toLong()) },
        onDismiss = {
            when (feedback?.first) {
                FeedbackKind.Error -> onDismissError()
                FeedbackKind.Wake -> dismissedWake = true
                FeedbackKind.Exit -> dismissedExit = true
                FeedbackKind.Notice -> onDismissNotice()
                FeedbackKind.Command -> dismissedCommand = true
                null -> Unit
            }
        },
        modifier = modifier,
    )
}
