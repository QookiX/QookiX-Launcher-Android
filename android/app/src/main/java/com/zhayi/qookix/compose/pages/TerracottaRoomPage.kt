package com.zhayi.qookix.compose.pages

import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.theme.QkSpacing
import com.zhayi.qookix.compose.ui.QkFeedback

@Composable
internal fun TerracottaRoomPage(controls: TerracottaRoomControls) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val room = controls.state
    var roomInput by rememberSaveable { mutableStateOf("") }
    val code = roomInput.trim()
    val invalidCode = code.isNotEmpty() && !validTerracottaRoomCode(code)
    val canAct = controls.enabled && !controls.busy
    val canJoin = canAct && !room.hosting && !room.guesting && validTerracottaRoomCode(code)

    fun copy(value: String, label: Int, message: Int) {
        clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(label), value))
        QkFeedback.show(context.getString(message))
    }

    val host: @Composable () -> Unit = {
        RoomActionCard(R.string.multiplayer_host_title) {
            if (room.hosting && room.code.isNotBlank()) {
                Text(room.code, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                TextButton(onClick = {
                    copy(room.code, R.string.multiplayer_room_code_label, R.string.frp_room_code_copied)
                }) { Text(stringResource(R.string.multiplayer_copy_room_code)) }
            } else {
                Text(
                    stringResource(if (room.hosting) R.string.multiplayer_host_lan_hint else R.string.multiplayer_host_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = if (room.hosting) controls.onLeave else controls.onHost,
                enabled = canAct && !room.guesting,
            ) {
                Text(stringResource(if (room.hosting) R.string.multiplayer_tunnel_stop else R.string.multiplayer_tunnel_start))
            }
        }
    }
    val guest: @Composable () -> Unit = {
        RoomActionCard(R.string.multiplayer_guest_title) {
            when {
                room.connected -> {
                    Text(stringResource(R.string.multiplayer_guest_address_hint), style = MaterialTheme.typography.bodyMedium)
                    Text(room.address, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace))
                    TextButton(onClick = {
                        copy(room.address, R.string.multiplayer_address, R.string.multiplayer_address_copied)
                    }) { Text(stringResource(R.string.multiplayer_copy_address)) }
                }
                room.guesting -> Text(stringResource(R.string.multiplayer_guest_connecting))
                else -> {
                    OutlinedTextField(
                        value = roomInput,
                        onValueChange = { roomInput = it.take(128) },
                        label = { Text(stringResource(R.string.multiplayer_room_code)) },
                        singleLine = true,
                        isError = invalidCode,
                        supportingText = if (invalidCode) {
                            { Text(stringResource(R.string.multiplayer_room_code_invalid)) }
                        } else {
                            null
                        },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = {
                            if (canJoin) {
                                focusManager.clearFocus()
                                controls.onJoin(code)
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        val pasted = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)
                        if (pasted != null) roomInput = pasted.toString().trim().take(128)
                    }) { Text(stringResource(R.string.multiplayer_paste_room_code)) }
                }
            }
            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (room.guesting) controls.onLeave() else controls.onJoin(code)
                },
                enabled = if (room.guesting) canAct else canJoin,
            ) {
                Text(stringResource(if (room.guesting) R.string.multiplayer_leave_room else R.string.multiplayer_join_room))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(QkSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(QkSpacing.sm),
    ) {
        val message = when {
            !controls.supported -> stringResource(R.string.multiplayer_tunnel_unsupported)
            controls.message != null -> controls.message
            room.error.isNotBlank() -> room.error
            room.phase == "exception" -> stringResource(R.string.multiplayer_join_failed)
            else -> null
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (controls.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(QkSpacing.md)) {
                    Box(Modifier.weight(1f)) { host() }
                    Box(Modifier.weight(1f)) { guest() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(QkSpacing.md)) {
                    host()
                    guest()
                }
            }
        }
    }
}

@Composable
private fun RoomActionCard(title: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(QkSpacing.md),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
