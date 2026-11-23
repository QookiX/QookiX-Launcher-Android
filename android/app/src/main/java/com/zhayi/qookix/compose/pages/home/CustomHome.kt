package com.zhayi.qookix.compose.pages.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.shell.NavDestination
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkFeedback
import com.zhayi.qookix.compose.ui.QkProgressBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun CustomHome(
    account: String?,
    instance: String?,
    launchStage: String?,
    launchProgress: Float,
    onLaunch: (String) -> Unit,
    onNavigate: (NavDestination) -> Unit,
    onOpenInstance: (String) -> Unit,
    defaultHome: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { CustomHomeStore(context) }
    var home by remember { mutableStateOf<LoadedCustomHome?>(null) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            home = store.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            QkFeedback.show(customHomeError(context, error))
        }
        ready = true
    }

    if (!ready) return
    val custom = home
    if (custom == null) {
        defaultHome()
        return
    }

    val scope = rememberCoroutineScope()
    val now by produceState(Date()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = Date()
        }
    }
    val date = remember(now) { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now) }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.ROOT).format(now) }
    val builtIns = mapOf(
        "user" to (account ?: stringResource(R.string.custom_home_guest)),
        "instance" to (instance ?: stringResource(R.string.custom_home_no_instance)),
        "date" to date,
        "time" to time,
        "homePath" to custom.document?.toString().orEmpty(),
    )
    var savedVars by remember(custom) { mutableStateOf(custom.variables) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var runningActions by remember { mutableStateOf(false) }
    val variables = custom.layout.variables + savedVars + builtIns

    fun runActions(actions: List<JSONObject>) {
        if (runningActions) return
        runningActions = true
        scope.launch {
            try {
                for (action in actions) {
                    fun field(name: String) = interpolateHome(action.optString(name), custom.layout.variables + savedVars + builtIns)
                    when (val type = action.optString("type")) {
                        "open-url" -> {
                            val link = webAddress(field("url"))
                            if (link == null) {
                                QkFeedback.show(context.getString(R.string.custom_home_invalid_link))
                            } else {
                                openHomeIntent(context, Intent(Intent.ACTION_VIEW, link))
                            }
                        }
                        "copy" -> {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.custom_home_title), field("text")))
                            QkFeedback.show(context.getString(R.string.custom_home_copied))
                        }
                        "dialog" -> dialog = field("title").ifBlank { context.getString(R.string.hint_title) } to field("content")
                        "launch" -> if (field("world").isBlank() && field("server").isBlank()) {
                            onLaunch(field("instance"))
                        } else {
                            QkFeedback.show(context.getString(R.string.custom_home_quick_play_unavailable))
                        }
                        "navigate" -> {
                            val path = field("to").trim().trimEnd('/')
                            val destination = homeDestination(path)
                            when {
                                destination != null -> onNavigate(destination)
                                path.startsWith("/instance/") -> onOpenInstance(Uri.decode(path.removePrefix("/instance/")))
                                else -> QkFeedback.show(context.getString(R.string.custom_home_route_unavailable, path))
                            }
                        }
                        "open-path" -> {
                            val document = custom.document
                            if (document != null && field("path") == document.toString()) {
                                openHomeIntent(context, Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(document, "text/plain")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                })
                            } else {
                                QkFeedback.show(context.getString(R.string.custom_home_path_unavailable))
                            }
                        }
                        "set-var" -> {
                            val name = action.optString("name")
                            if (name.isNotBlank() && name.length <= 100 && name !in homeBuiltIns &&
                                (name in savedVars || savedVars.size < 128)
                            ) {
                                val updated = savedVars + (name to field("value").take(4096))
                                store.saveVariables(updated)
                                savedVars = updated
                            }
                        }
                        "reload" -> {
                            home = store.reload()
                            QkFeedback.show(context.getString(R.string.custom_home_reloaded))
                        }
                        else -> QkFeedback.show(context.getString(R.string.custom_home_action_unavailable, type))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                QkFeedback.show(customHomeError(context, error))
            } finally {
                runningActions = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QkSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(QkSpacing.md),
    ) {
        if (launchStage != null) {
            Text(launchStage, style = MaterialTheme.typography.bodyMedium)
            QkProgressBar(launchProgress.takeIf { it > 0f })
        }
        key(custom.layout) {
            HomeNodes(custom.layout.nodes, variables, ::runActions)
        }
    }

    dialog?.let { (title, content) ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(title) },
            text = { Text(content, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.dialog_confirm)) }
            },
        )
    }
}

internal fun webAddress(value: String): Uri? = Uri.parse(value.trim()).takeIf {
    (it.scheme.equals("https", true) || it.scheme.equals("http", true)) && !it.host.isNullOrBlank()
}

private fun openHomeIntent(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        QkFeedback.show(context.getString(R.string.custom_home_open_failed))
    }
}

private fun homeDestination(path: String): NavDestination? = when (path) {
    "", "/home" -> NavDestination.Home
    "/create" -> NavDestination.CreateInstance
    else -> NavDestination.entries.firstOrNull {
        it !in setOf(NavDestination.ContentDetail, NavDestination.InstanceDetail, NavDestination.ServerDetail) && "/${it.route}" == path
    }
}
