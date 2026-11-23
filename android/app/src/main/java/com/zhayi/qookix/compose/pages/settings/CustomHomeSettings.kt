package com.zhayi.qookix.compose.pages.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.pages.home.CustomHomeConfig
import com.zhayi.qookix.compose.pages.home.CustomHomeStore
import com.zhayi.qookix.compose.pages.home.customHomeError
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CustomHomeSettings() {
    val context = LocalContext.current
    val store = remember(context) { CustomHomeStore(context) }
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<CustomHomeConfig?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) { config = store.config() }

    fun update(success: Int? = null, work: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                work()
                if (success != null) QkFeedback.show(context.getString(success))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = customHomeError(context, failure)
            } finally {
                busy = false
            }
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) update(R.string.custom_home_imported) { config = store.import(uri) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) update(R.string.custom_home_exported) { store.export(uri) }
    }

    SettingsGroup(stringResource(R.string.custom_home_title)) {
        SettingsRow(
            title = stringResource(R.string.custom_home_enable),
            subtitle = stringResource(R.string.custom_home_enable_desc),
        ) {
            Switch(
                checked = config?.enabled == true,
                enabled = config != null && !busy,
                onCheckedChange = { enabled -> update { config = store.enable(enabled) } },
            )
        }
        val name = config?.name
        SettingsRow(
            title = stringResource(R.string.custom_home_file),
            subtitle = when {
                name == null -> stringResource(R.string.custom_home_file_example)
                config?.document == null -> stringResource(R.string.custom_home_file_import_again, name)
                else -> stringResource(R.string.custom_home_file_selected, name)
            },
            column = true,
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm)) {
                TextButton(enabled = config != null && !busy, onClick = { open.launch(arrayOf("*/*")) }) {
                    Text(stringResource(R.string.custom_home_import))
                }
                TextButton(
                    enabled = config?.document != null && !busy,
                    onClick = { update(R.string.custom_home_reloaded) { store.reload() } },
                ) { Text(stringResource(R.string.custom_home_reload)) }
                TextButton(enabled = config != null && !busy, onClick = { export.launch("home.json") }) {
                    Text(stringResource(if (name == null) R.string.custom_home_export_example else R.string.custom_home_export))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
