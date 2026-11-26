package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.friendlyError
import com.zhayi.qookix.compose.bridge.updateHostedServer
import org.json.JSONObject
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.zhayi.qookix.R

/** Owns the rename draft and write lifecycle, separate from server polling. */
@Composable
internal fun rememberServerRenameAction(
    api: QookixApi,
    serverId: String,
    serverName: String,
    configLoaded: Boolean,
    isActive: () -> Boolean,
    onSaved: (String) -> Unit,
): (() -> Unit)? {
    val ctx = LocalContext.current
    var draft by rememberSaveable(serverId) { mutableStateOf<String?>(null) }
    var pending by remember(serverId) { mutableStateOf(false) }
    var error by remember(serverId) { mutableStateOf<String?>(null) }
    val requestedName = draft?.trim().orEmpty()
    val canSave = configLoaded && requestedName.isNotBlank() && requestedName != serverName
    draft?.let { name ->
        ServerRenameDialog(
            draft = name,
            pending = pending,
            error = error,
            canSave = canSave,
            onChange = { draft = it; error = null },
            onDismiss = { if (!pending) draft = null },
            onSave = save@{
                if (!canSave || pending) return@save
                pending = true
                error = null
                api.updateHostedServer(JSONObject().put("id", serverId).put("name", requestedName)) { result ->
                    result.onSuccess { config ->
                        val savedName = config.optString("name", requestedName)
                        if (TunnelSharing.ownerServerId == serverId) TunnelSharing.ownerServerName = savedName
                        if (isActive()) {
                            draft = null
                            onSaved(savedName)
                        }
                    }.onFailure {
                        if (isActive()) error = ctx.getString(R.string.server_detail_rename_failed, friendlyError(ctx, it))
                    }
                    if (isActive()) pending = false
                }
            },
        )
    }
    return if (configLoaded) ({ draft = serverName; error = null }) else null
}

@Composable
internal fun ServerRenameDialog(
    draft: String,
    pending: Boolean,
    error: String?,
    canSave: Boolean,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val supportingText: (@Composable () -> Unit)? = if (error == null) null else {
        { Text(error) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_detail_rename)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !pending,
                label = { Text(stringResource(R.string.create_server_name)) },
                supportingText = supportingText,
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave && !pending) onSave() }),
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = canSave && !pending) {
                Text(stringResource(if (pending) R.string.server_detail_name_saving else R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !pending) {
                Text(stringResource(R.string.server_detail_cancel))
            }
        },
    )
}
