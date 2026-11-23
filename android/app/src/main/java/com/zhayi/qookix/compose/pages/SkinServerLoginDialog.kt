package com.zhayi.qookix.compose.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.friendlyError
import org.json.JSONObject

private const val LITTLE_SKIN = "https://littleskin.cn/api/yggdrasil"

@Composable
internal fun SkinServerLoginDialog(
    api: QookixApi,
    onDismiss: () -> Unit,
    onAdded: (String) -> Unit,
) {
    val context = LocalContext.current
    var customServer by remember { mutableStateOf(false) }
    var customRoot by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val root = if (customServer) customRoot.trim() else LITTLE_SKIN

    fun selectServer(custom: Boolean) {
        if (customServer == custom) return
        customServer = custom
        password = ""
        error = null
    }

    fun dismiss() {
        if (loading) return
        pending?.optString("loginId")?.let { id ->
            api.call("yggdrasil_cancel_login", JSONObject().put("loginId", id)) {}
        }
        onDismiss()
    }

    fun complete(result: Result<JSONObject>) {
        loading = false
        result.fold(
            onSuccess = { response ->
                val accountId = response.optString("accountId")
                if (accountId.isNotBlank()) {
                    pending = null
                    onDismiss()
                    onAdded(accountId)
                } else {
                    pending = response
                }
            },
            onFailure = {
                pending = null
                error = friendlyError(context, it)
            },
        )
    }

    val selection = pending
    AlertDialog(
        onDismissRequest = ::dismiss,
        title = {
            Text(stringResource(
                if (selection == null) R.string.account_add_skin_server else R.string.account_skin_server_profiles,
            ))
        },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selection == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !customServer,
                            onClick = { selectServer(false) },
                            label = { Text(stringResource(R.string.account_skin_server_littleskin)) },
                            enabled = !loading,
                        )
                        FilterChip(
                            selected = customServer,
                            onClick = { selectServer(true) },
                            label = { Text(stringResource(R.string.account_skin_server_custom)) },
                            enabled = !loading,
                        )
                    }
                    if (customServer) {
                        OutlinedTextField(
                            value = customRoot,
                            onValueChange = { customRoot = it },
                            label = { Text(stringResource(R.string.account_skin_server_url)) },
                            singleLine = true,
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        )
                    }
                    OutlinedTextField(
                        value = login,
                        onValueChange = { login = it },
                        label = { Text(stringResource(R.string.account_skin_server_username)) },
                        singleLine = true,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.account_skin_server_password)) },
                        singleLine = true,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                } else {
                    val profiles = selection.getJSONArray("profiles")
                    for (index in 0 until profiles.length()) {
                        val profile = profiles.getJSONObject(index)
                        TextButton(
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                loading = true
                                val args = JSONObject()
                                    .put("loginId", selection.getString("loginId"))
                                    .put("profileId", profile.getString("id"))
                                api.callObj("yggdrasil_select_profile", args, ::complete)
                            },
                        ) { Text(profile.getString("name")) }
                    }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (selection == null) {
                TextButton(
                    enabled = !loading && root.isNotBlank() && login.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        loading = true
                        error = null
                        val args = JSONObject().put("root", root).put("login", login).put("password", password)
                        password = ""
                        api.callObj("yggdrasil_login", args, ::complete)
                    },
                ) { Text(stringResource(R.string.account_skin_server_sign_in)) }
            }
        },
        dismissButton = {
            TextButton(onClick = ::dismiss, enabled = !loading) {
                Text(stringResource(R.string.account_cancel))
            }
        },
    )
}
