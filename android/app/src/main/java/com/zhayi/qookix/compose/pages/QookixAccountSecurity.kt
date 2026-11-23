package com.zhayi.qookix.compose.pages

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import org.json.JSONObject

@Composable
internal fun QookixSecurityPane(account: QookixAccount, actions: QookixActions) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var setup by remember { mutableStateOf<JSONObject?>(null) }
    var editPassword by remember { mutableStateOf(false) }
    var disable by remember { mutableStateOf(false) }
    val enabled = status?.optBoolean("enabled") == true
    fun reload() { actions.run { status = account.twoFactorStatus() } }
    LaunchedEffect(account) {
        actions.awaitIdle()
        reload()
    }
    QookixSettingRow(R.string.qookix_password_change, "") {
        TextButton(onClick = { actions.message = null; editPassword = true }, enabled = !actions.busy) {
            Text(stringResource(R.string.qookix_edit))
        }
    }
    QookixSettingRow(
        R.string.qookix_2fa_title,
        when {
            status == null -> stringResource(R.string.qookix_2fa_status_unknown)
            enabled -> listOfNotNull(
                stringResource(R.string.qookix_2fa_on),
                status?.optInt("recovery_remaining", -1)?.takeIf { it >= 0 }?.let {
                    stringResource(R.string.qookix_2fa_recovery_remaining, it)
                },
            ).joinToString(" · ")
            else -> stringResource(R.string.qookix_2fa_off)
        },
    ) {
        TextButton(
            onClick = {
                when {
                    status == null -> reload()
                    enabled -> { actions.message = null; disable = true }
                    else -> actions.run { setup = account.prepareTwoFactor() }
                }
            },
            enabled = !actions.busy,
        ) {
            Text(stringResource(when {
                status == null -> R.string.common_retry
                enabled -> R.string.qookix_2fa_disable
                else -> R.string.qookix_2fa_enable
            }))
        }
    }
    if (editPassword) {
        QookixFormDialog(
            R.string.qookix_password_change,
            listOf(
                QookixField(R.string.qookix_old_password, secret = true),
                QookixField(R.string.qookix_new_password, secret = true),
                QookixField(R.string.qookix_repeat_password, secret = true),
            ),
            actions, onDismiss = { editPassword = false },
            hint = R.string.qookix_password_rule, success = R.string.qookix_password_changed,
            valid = { it[0].isNotEmpty() && it[1].toByteArray(Charsets.UTF_8).size in 8..72 && it[1] == it[2] },
        ) {
            account.changePassword(it[0], it[1])
            editPassword = false
        }
    }
    setup?.let { result ->
        QookixFormDialog(
            R.string.qookix_2fa_enable,
            listOf(QookixField(R.string.qookix_2fa_code)),
            actions, onDismiss = { setup = null },
            success = R.string.qookix_2fa_on, valid = { it[0].trim().matches(Regex("[0-9]{6}")) },
            extra = { TwoFactorSetup(result) },
        ) {
            account.enableTwoFactor(it[0])
            status = JSONObject().put("enabled", true)
            setup = null
        }
    }
    if (disable) {
        QookixFormDialog(
            R.string.qookix_2fa_disable,
            listOf(QookixField(R.string.qookix_account_password, secret = true), QookixField(R.string.qookix_2fa_code)),
            actions, onDismiss = { disable = false },
            success = R.string.qookix_2fa_off, valid = { it.all(String::isNotBlank) },
        ) {
            account.disableTwoFactor(it[0], it[1])
            status = JSONObject().put("enabled", false)
            disable = false
        }
    }
}

@Composable
private fun TwoFactorSetup(setup: JSONObject) {
    val secret = setup.optString("secret")
    val uri = setup.optString("otpauth_uri")
    val qr = remember(uri) {
        if (uri.isBlank()) null else runCatching {
            val bits = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 256, 256)
            val pixels = IntArray(bits.width * bits.height) { i ->
                if (bits[i % bits.width, i / bits.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    }
    Text(stringResource(R.string.qookix_2fa_setup_hint))
    qr?.let { Image(it, stringResource(R.string.qookix_2fa_qr), Modifier.size(132.dp)) }
    if (secret.isNotBlank()) SelectionContainer {
        Text(secret, fontFamily = FontFamily.Monospace)
    }
    val codes = setup.optJSONArray("recovery_codes")
    if (codes != null && codes.length() > 0) {
        Text(stringResource(R.string.qookix_2fa_recovery_hint))
        SelectionContainer {
            Text((0 until codes.length()).joinToString("\n") { codes.optString(it) }, fontFamily = FontFamily.Monospace)
        }
    }
}

internal data class QookixField(
    @StringRes val label: Int,
    val initial: String = "",
    val email: Boolean = false,
    val secret: Boolean = false,
)

@Composable
internal fun QookixFormDialog(
    @StringRes title: Int,
    fields: List<QookixField>,
    actions: QookixActions,
    onDismiss: () -> Unit,
    valid: (List<String>) -> Boolean,
    @StringRes hint: Int? = null,
    @StringRes success: Int? = null,
    extra: @Composable () -> Unit = {},
    submit: suspend (List<String>) -> Unit,
) {
    val focus = LocalFocusManager.current
    val values = remember { mutableStateListOf(*fields.map { it.initial }.toTypedArray()) }
    val input: @Composable (Int, Modifier) -> Unit = { index, modifier ->
        val field = fields[index]
        OutlinedTextField(
            value = values[index],
            onValueChange = { values[index] = it },
            label = { Text(stringResource(field.label)) },
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                field.secret -> KeyboardType.Password
                field.email -> KeyboardType.Email
                else -> KeyboardType.Text
            }),
            modifier = modifier,
            singleLine = true,
            enabled = !actions.busy,
        )
    }
    AlertDialog(
        onDismissRequest = { if (!actions.busy) onDismiss() },
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f),
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn, usePlatformDefaultWidth = false),
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                hint?.let { Text(stringResource(it)) }
                extra()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val pairPasswordFields = fields.size == 3 && maxWidth >= 360.dp
                    Column(verticalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                        if (pairPasswordFields) {
                            input(0, Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                                input(1, Modifier.weight(1f))
                                input(2, Modifier.weight(1f))
                            }
                        } else {
                            fields.indices.forEach { input(it, Modifier.fillMaxWidth()) }
                        }
                    }
                }
                if (actions.failed) actions.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                focus.clearFocus()
                val payload = values.toList()
                actions.run(success) { submit(payload) }
            }, enabled = !actions.busy && valid(values)) { Text(stringResource(R.string.dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !actions.busy) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
