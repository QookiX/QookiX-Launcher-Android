package com.zhayi.qookix.compose.pages

import android.content.Context
import com.zhayi.qookix.R
import com.zhayi.qookix.compose.bridge.optText
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class QookixUser(
    val username: String,
    val nickname: String,
    val email: String,
    val emailVerified: Boolean,
    val nicknameStatus: String,
    val mcName: String?,
    val mcUuid: String?,
    val mcVerified: Boolean?,
) {
    val displayName get() = nickname.ifBlank { username }
}

internal data class QookixFriendRoom(
    val id: String,
    val code: String,
    val title: String,
    val owner: String,
    val version: String,
)

internal data class QookixFriendRooms(val rooms: List<QookixFriendRoom>, val hasMore: Boolean)

private class QookixServiceError(val code: String, val status: Int = 0) : IOException()

internal fun qookixErrorText(context: Context, error: Throwable): String =
    context.getString(
        when ((error as? QookixServiceError)?.code) {
            "invalid_credentials" -> R.string.qookix_credentials_invalid
            "invalid_two_factor_code" -> R.string.qookix_2fa_invalid
            "challenge_expired", "invalid_challenge" -> R.string.qookix_2fa_expired
            "not_logged_in" -> R.string.qookix_session_expired
            "rate_limited" -> R.string.qookix_rate_limited
            "network_error" -> R.string.qookix_network_error
            "email_not_verified", "email_unverified" -> R.string.qookix_email_unverified
            "invalid_email" -> R.string.qookix_email_invalid
            "invalid_old_password" -> R.string.qookix_old_password_invalid
            "invalid_nickname" -> R.string.qookix_nickname_invalid
            else -> R.string.qookix_request_error
        },
    )

internal class QookixAccount(context: Context) {
    private val storage = QookixAccountStorage(context.applicationContext)
    private val mutex = Mutex()
    private val currentUser = MutableStateFlow<QookixUser?>(null)
    val user = currentUser.asStateFlow()
    private var loaded = false
    private var session: Session? = null

    private data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val user: JSONObject,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("expires_at", expiresAt)
            .put("user", user)
    }

    suspend fun restore() = withSession { Unit }

    /** 返回临时的两步验证挑战，或在登录完成时返回 null。 */
    suspend fun login(login: String, password: String): String? = withSession {
        val data = request(
            "/auth/login",
            JSONObject().put("login", login.trim()).put("password", password),
        )
        if (data.optBoolean("two_factor_required")) {
            data.optText("challenge_token")?.takeIf { it.isNotBlank() }
                ?: throw QookixServiceError("invalid_response")
        } else {
            finishLogin(data)
            null
        }
    }

    suspend fun verify(challenge: String, code: String) = withSession {
        val data = request(
            "/auth/login/2fa",
            JSONObject().put("challenge_token", challenge).put("code", code.trim()),
        )
        finishLogin(data)
    }

    suspend fun refreshProfile() = withSession {
        val user = authorized("/me").optJSONObject("user") ?: throw QookixServiceError("invalid_response")
        update(requireSession().copy(user = user))
    }

    suspend fun changeNickname(nickname: String) = withSession {
        val data = authorized("/me", JSONObject().put("nickname", nickname.trim()), "PATCH")
        val user = data.optJSONObject("user") ?: authorized("/me").optJSONObject("user")
            ?: throw QookixServiceError("invalid_response")
        update(requireSession().copy(user = user))
    }

    suspend fun changeEmail(email: String) = withSession {
        authorized("/me/email/start", JSONObject().put("new_email", email.trim().lowercase()))
        Unit
    }

    suspend fun changePassword(old: String, new: String) = withSession {
        val data = authorized("/me/password", JSONObject().put("old_password", old).put("new_password", new))
        data.optJSONObject("tokens")?.let {
            val active = requireSession()
            update(sessionFrom(it, active.user, active.refreshToken))
        }
        Unit
    }

    suspend fun twoFactorStatus() = withSession { authorized("/me/2fa") }
    suspend fun prepareTwoFactor() = withSession { authorized("/me/2fa/setup", JSONObject()) }
    suspend fun enableTwoFactor(code: String) = withSession {
        authorized("/me/2fa/enable", JSONObject().put("code", code.trim()))
        Unit
    }

    suspend fun disableTwoFactor(password: String, code: String) = withSession {
        authorized("/me/2fa/disable", JSONObject().put("password", password).put("code", code.trim()))
        Unit
    }

    suspend fun cloudFavorites() = withSession { readCloudFavorites() }

    suspend fun syncFavorites(local: JSONArray): JSONArray = withSession {
        val cloud = readCloudFavorites()
        val merged = mergeFavoriteSnapshots(local, cloud)
        authorized("/me/settings", JSONObject().put("key", "favorites").put("value", merged), "PUT")
        merged
    }

    private fun readCloudFavorites(): JSONArray {
        val data = authorized("/me/settings?key=favorites")
        val items = data.optJSONArray("items") ?: throw QookixServiceError("invalid_response")
        if (items.length() == 0) return JSONArray()
        val item = items.optJSONObject(0) ?: throw QookixServiceError("invalid_response")
        if (item.has("value") && item.isNull("value")) return JSONArray()
        return item.optJSONArray("value") ?: throw QookixServiceError("invalid_response")
    }

    suspend fun logout() = withSession {
        val token = session?.accessToken
        update(null)
        if (token != null) runCatching { request("/auth/logout", JSONObject(), token) }
        Unit
    }

    suspend fun friendRooms(page: Int): QookixFriendRooms = withSession {
        val data = authorized("/rooms?scope=friends&page=$page&limit=$ROOM_PAGE_SIZE")
        val rooms = data.optJSONArray("rooms") ?: throw QookixServiceError("invalid_response")
        val active = (0 until rooms.length()).mapNotNull { index ->
            val room = rooms.optJSONObject(index) ?: return@mapNotNull null
            val code = room.optText("code").orEmpty()
            val id = room.optText("id").orEmpty()
            if (room.optString("status") != "active" || id.isBlank() || code.isBlank()) return@mapNotNull null
            val owner = room.optJSONObject("owner")
            QookixFriendRoom(
                id, code, room.optText("title").orEmpty(),
                owner?.optText("nickname").orEmpty().ifBlank { owner?.optText("username").orEmpty() },
                room.optText("game_version").orEmpty(),
            )
        }
        QookixFriendRooms(active, rooms.length() >= ROOM_PAGE_SIZE)
    }

    // 刷新令牌每次只能使用一次。读取、续期和写回在同一把锁里完成。
    private suspend fun <T> withSession(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!loaded) {
                session = storage.read()?.let { stored ->
                    val access = stored.optText("access_token").orEmpty()
                    val user = stored.optJSONObject("user")
                    if (access.isBlank() || user == null) null
                    else Session(access, stored.optString("refresh_token"), stored.optLong("expires_at"), user)
                }
                currentUser.value = session?.user?.let(::profile)
                loaded = true
            }
            block()
        }
    }

    private fun finishLogin(data: JSONObject) {
        val tokens = data.optJSONObject("tokens") ?: throw QookixServiceError("invalid_response")
        val user = data.optJSONObject("user") ?: request("/me", token = accessToken(tokens)).optJSONObject("user")
            ?: throw QookixServiceError("invalid_response")
        update(sessionFrom(tokens, user))
    }

    private fun authorized(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST"): JSONObject {
        var active = requireSession()
        if (active.expiresAt > 0 && active.expiresAt <= now() + 30) active = refresh(active)
        return try {
            request(path, body, active.accessToken, method)
        } catch (error: QookixServiceError) {
            if (!error.invalidToken()) throw error
            val renewed = refresh(active)
            try {
                request(path, body, renewed.accessToken, method)
            } catch (retryError: QookixServiceError) {
                if (retryError.invalidToken()) expire()
                throw retryError
            }
        }
    }

    private fun refresh(active: Session): Session {
        if (active.refreshToken.isBlank()) expire()
        val data = try {
            request("/auth/refresh", JSONObject().put("refresh_token", active.refreshToken))
        } catch (error: QookixServiceError) {
            if (error.invalidToken()) expire()
            throw error
        }
        val tokens = data.optJSONObject("tokens") ?: throw QookixServiceError("invalid_response")
        return sessionFrom(tokens, active.user, active.refreshToken).also(::update)
    }

    private fun expire(): Nothing {
        update(null)
        throw QookixServiceError("not_logged_in")
    }

    private fun update(next: Session?) {
        storage.write(next?.toJson())
        session = next
        currentUser.value = next?.user?.let(::profile)
    }

    private fun requireSession(): Session = session ?: throw QookixServiceError("not_logged_in")

    private fun QookixServiceError.invalidToken() = status == 401 || code in INVALID_TOKENS

    private fun sessionFrom(tokens: JSONObject, user: JSONObject, formerRefresh: String = "") = Session(
        accessToken(tokens),
        tokens.optText("refresh_token")?.takeIf { it.isNotBlank() } ?: formerRefresh,
        now() + tokens.optLong("expires_in", 7200).coerceAtLeast(0),
        user,
    )

    private fun accessToken(tokens: JSONObject): String =
        tokens.optText("access_token")?.takeIf { it.isNotBlank() } ?: throw QookixServiceError("invalid_response")

    private fun profile(user: JSONObject) = QookixUser(
        user.optText("username").orEmpty(),
        user.optText("nickname").orEmpty(),
        user.optText("email").orEmpty(),
        user.optBoolean("email_verified"),
        user.optText("nickname_status").orEmpty(),
        user.opt("mc_name") as? String,
        user.opt("mc_uuid") as? String,
        user.opt("mc_verified") as? Boolean,
    )

    private fun request(
        path: String,
        body: JSONObject? = null,
        token: String? = null,
        method: String = if (body == null) "GET" else "POST",
    ): JSONObject {
        val connection = (URL("$BASE/api/v1$path").openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 429) throw QookixServiceError("rate_limited", status)
            val ok = status in 200..299
            val stream = if (ok) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            val response = text?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (ok && response?.optBoolean("ok") == true) return response.optJSONObject("data") ?: JSONObject()
            val code = response?.optJSONObject("error")?.optText("code") ?: "invalid_response"
            throw QookixServiceError(code, status)
        } catch (error: QookixServiceError) {
            throw error
        } catch (_: IOException) {
            throw QookixServiceError("network_error")
        } finally {
            connection.disconnect()
        }
    }

    private fun now() = System.currentTimeMillis() / 1000

    private companion object {
        const val BASE = "https://service.qookix.cn"
        const val ROOM_PAGE_SIZE = 20
        val INVALID_TOKENS = setOf("token_revoked", "token_reused", "invalid_token", "unauthorized")
    }
}
