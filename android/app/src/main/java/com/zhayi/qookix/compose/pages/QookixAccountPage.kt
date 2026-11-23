package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun QookixSignInCard(account: QookixAccount, modifier: Modifier = Modifier) {
    var loginOpen by remember { mutableStateOf(false) }
    Card(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(QkSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.qookix_account_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.qookix_account_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { loginOpen = true }) {
                Text(stringResource(R.string.qookix_account_login))
            }
        }
    }
    if (loginOpen) QookixLoginDialog(account) { loginOpen = false }
}

@Composable
private fun QookixLoginDialog(account: QookixAccount, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSubmit = !busy && if (challenge == null) login.isNotBlank() && password.isNotEmpty() else code.isNotBlank()

    fun submit() {
        if (!canSubmit) return
        focusManager.clearFocus()
        busy = true
        error = null
        scope.launch {
            try {
                val currentChallenge = challenge
                if (currentChallenge == null) {
                    challenge = account.login(login, password)
                    password = ""
                } else {
                    account.verify(currentChallenge, code)
                    challenge = null
                }
                if (challenge == null) onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                error = qookixErrorText(context, failed)
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = {
            Text(stringResource(if (challenge == null) R.string.qookix_account_title else R.string.qookix_2fa_title))
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
            ) {
                if (challenge == null) {
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = { Text(stringResource(R.string.qookix_account_username)) },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.qookix_account_password)) },
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.qookix_2fa_code)) },
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = canSubmit) {
                Text(stringResource(if (busy) R.string.qookix_account_signing_in else R.string.qookix_account_login))
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = {
                if (challenge == null) {
                    onDismiss()
                } else {
                    challenge = null
                    code = ""
                    error = null
                }
            }) {
                Text(stringResource(if (challenge == null) R.string.dialog_cancel else R.string.qookix_back_to_login))
            }
        },
    )
}
