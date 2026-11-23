package com.zhayi.qookix.compose.pages

import android.app.Activity
import android.content.Context
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.optText
import com.zhayi.qookix.compose.bridge.terracottaGuest
import com.zhayi.qookix.compose.bridge.terracottaHost
import com.zhayi.qookix.compose.bridge.terracottaTunnelRequest
import java.io.File
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

internal fun validTerracottaRoomCode(code: String): Boolean =
    code.length in 4..64 && code.all { it in '!'..'~' }

internal data class TerracottaRoomState(
    val phase: String = "",
    val code: String = "",
    val address: String = "",
    val error: String = "",
) {
    val hosting get() = phase == "scanning" || phase.startsWith("host")
    val guesting get() = phase.startsWith("guest")
    val connected get() = phase == "guest-ok" && address.isNotBlank()
}

internal data class TerracottaRoomControls(
    val state: TerracottaRoomState,
    val supported: Boolean,
    val enabled: Boolean,
    val busy: Boolean,
    val message: String?,
    val onHost: () -> Unit,
    val onJoin: (String) -> Unit,
    val onLeave: () -> Unit,
)

@Composable
internal fun rememberTerracottaRoomControls(
    api: QookixApi,
    bridgeReady: Boolean,
    visible: Boolean,
): TerracottaRoomControls {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val controller = remember(api, context) { TerracottaRoomController(context, api, scope) }
    val enabled = bridgeReady && controller.supported
    var afterConsent by remember { mutableStateOf<(() -> Unit)?>(null) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = afterConsent
        afterConsent = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
        else controller.message = context.getString(R.string.multiplayer_vpn_denied)
    }

    fun withVpn(action: () -> Unit) {
        if (!enabled || controller.busy || afterConsent != null) return
        controller.message = null
        try {
            val intent = VpnService.prepare(context)
            if (intent == null) {
                action()
            } else {
                afterConsent = action
                vpnConsent.launch(intent)
            }
        } catch (_: Exception) {
            afterConsent = null
            controller.message = context.getString(R.string.multiplayer_vpn_denied)
        }
    }

    LaunchedEffect(lifecycle, visible, enabled) {
        if (!visible || !enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                controller.refresh()
                delay(1_500)
            }
        }
    }

    return TerracottaRoomControls(
        state = controller.state,
        supported = controller.supported,
        enabled = enabled,
        busy = controller.busy || afterConsent != null,
        message = controller.message,
        onHost = { withVpn(controller::host) },
        onJoin = { code ->
            if (validTerracottaRoomCode(code)) withVpn { controller.join(code) }
            else controller.message = context.getString(R.string.multiplayer_room_code_invalid)
        },
        onLeave = { if (enabled) controller.leave() },
    )
}

private class TerracottaRoomController(
    private val context: Context,
    private val api: QookixApi,
    private val scope: CoroutineScope,
) {
    val supported = File(context.applicationInfo.nativeLibraryDir, "libterracotta.so").isFile
    var state by mutableStateOf(TerracottaRoomState())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    private var operation = 0
    private var guestCode = ""

    fun host() = changeRoom(R.string.multiplayer_host_failed) {
        val player = playerName()
        request { api.terracottaHost(player, it) }
        guestCode = ""
        state = TerracottaRoomState(phase = "host-scanning")
    }

    fun join(code: String) = changeRoom(R.string.multiplayer_join_failed) {
        val player = playerName()
        request { api.terracottaGuest(code, player, it) }
        guestCode = code
        state = TerracottaRoomState(phase = "guesting", code = code)
    }

    fun leave() = changeRoom(R.string.multiplayer_leave_failed) {
        request { api.terracottaTunnelRequest("/waiting", it) }
        guestCode = ""
        state = TerracottaRoomState(phase = "waiting")
    }

    suspend fun refresh() {
        if (busy) return
        val revision = operation
        try {
            val json = request(requireOk = false) { api.terracottaTunnelRequest("/state", it) }
            if (revision != operation || busy) return
            val phase = json.optText("state").orEmpty()
            if (phase.isBlank()) return
            val code = sequenceOf("room", "code", "room_code", "roomCode")
                .firstNotNullOfOrNull { json.optText(it)?.takeIf(String::isNotBlank) }
                ?: if (phase.startsWith("guest")) guestCode else ""
            state = TerracottaRoomState(phase, code, json.optText("url").orEmpty(), json.optText("error").orEmpty())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 暂时读不到状态时保留房间码，下一轮继续确认。
        }
    }

    private fun changeRoom(failureMessage: Int, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        operation++
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                message = (failed as? RoomRequestError)?.detail ?: context.getString(failureMessage)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun playerName(): String {
        val settings = awaitBridge<JSONObject> { api.getSettings(it) }.getOrNull()
        val selected = settings?.optText("selected_account") ?: settings?.optText("selectedAccount")
        val accounts = awaitBridge<org.json.JSONArray> { api.listAccounts(it) }.getOrNull()
        return (0 until (accounts?.length() ?: 0)).firstNotNullOfOrNull { index ->
            val account = accounts?.optJSONObject(index) ?: return@firstNotNullOfOrNull null
            if (account.optString("uuid") != selected) return@firstNotNullOfOrNull null
            (account.optText("username") ?: account.optText("name"))?.takeIf { it.isNotBlank() }
        } ?: context.getString(R.string.multiplayer_player_name)
    }

    private suspend fun request(
        requireOk: Boolean = true,
        invoke: ((Result<String>) -> Unit) -> Unit,
    ): JSONObject {
        val json = JSONObject(awaitBridge(invoke).getOrThrow())
        if ((requireOk || json.has("ok")) && !json.optBoolean("ok")) {
            throw RoomRequestError(json.optText("error")?.takeIf { it.isNotBlank() })
        }
        return json
    }

    private class RoomRequestError(val detail: String?) : Exception()
}

private suspend fun <T> awaitBridge(invoke: ((Result<T>) -> Unit) -> Unit): Result<T> =
    suspendCancellableCoroutine { continuation ->
        try {
            invoke { if (continuation.isActive) continuation.resumeWith(Result.success(it)) }
        } catch (failed: Exception) {
            if (continuation.isActive) continuation.resumeWithException(failed)
        }
    }
