package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.zhayi.qookix.R

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
