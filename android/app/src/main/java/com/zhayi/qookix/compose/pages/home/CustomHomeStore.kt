package com.zhayi.qookix.compose.pages.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.zhayi.qookix.R
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val READ_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION
private val configLock = Mutex()

internal data class CustomHomeConfig(
    val enabled: Boolean,
    val name: String?,
    val document: Uri?,
)

internal data class LoadedCustomHome(
    val layout: CustomHomeLayout,
    val document: Uri?,
    val variables: Map<String, String>,
)

internal class CustomHomeStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("custom_home", Context.MODE_PRIVATE)

    suspend fun config(): CustomHomeConfig = withConfigLock { readConfig() }

    suspend fun load(): LoadedCustomHome? = withConfigLock { loadConfig(readConfig()) }

    private fun loadConfig(config: CustomHomeConfig, parsed: CustomHomeLayout? = null): LoadedCustomHome? {
        if (!config.enabled) return null
        val saved = JSONObject(prefs.getString("variables", "{}").orEmpty())
        return LoadedCustomHome(
            parsed ?: parseCustomHome(source()),
            config.document,
            saved.keys().asSequence().associateWith { saved.getString(it) },
        )
    }

    suspend fun enable(enabled: Boolean): CustomHomeConfig = withConfigLock {
        save(prefs.edit().putBoolean("enabled", enabled))
        readConfig()
    }

    suspend fun import(uri: Uri): CustomHomeConfig = withConfigLock {
        val source = readDocument(uri)
        parseCustomHome(source)
        val name = documentName(uri)
        val previous = readConfig().document
        val previousOwned = prefs.getBoolean("owned_grant", false)
        val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        val retained = alreadyGranted || runCatching {
            resolver.takePersistableUriPermission(uri, READ_GRANT)
        }.isSuccess
        val owned = if (previous == uri && alreadyGranted) previousOwned else !alreadyGranted && retained
        try {
            save(prefs.edit().putString("source", source).putString("name", name)
                .putString("document", if (retained) uri.toString() else null)
                .putBoolean("owned_grant", owned).putBoolean("enabled", true).remove("variables"))
        } catch (error: IOException) {
            if (!alreadyGranted && retained) releaseGrant(uri)
            throw error
        }
        if (previousOwned && previous != null && previous != uri) releaseGrant(previous)
        readConfig()
    }

    suspend fun reload(): LoadedCustomHome? = withConfigLock {
        val config = readConfig()
        val parsed = config.document?.let { uri ->
            val source = readDocument(uri)
            val layout = parseCustomHome(source)
            save(prefs.edit().putString("source", source))
            layout
        }
        loadConfig(config, parsed)
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val source = source()
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException()
        output.use { it.write(source.toByteArray(Charsets.UTF_8)) }
    }

    suspend fun saveVariables(variables: Map<String, String>) = withConfigLock {
        save(prefs.edit().putString("variables", JSONObject(variables).toString()))
    }

    private fun readConfig() = CustomHomeConfig(
        enabled = prefs.getBoolean("enabled", false),
        name = prefs.getString("name", null),
        document = prefs.getString("document", null)?.let(Uri::parse),
    )

    private fun source(): String = prefs.getString("source", null)
        ?: app.resources.openRawResource(R.raw.custom_home_example).bufferedReader().use { it.readText() }

    private fun documentName(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.take(150) else null
        }
    }.getOrNull().orEmpty().ifBlank { "home.json" }

    private fun readDocument(uri: Uri): String {
        val input = resolver.openInputStream(uri) ?: throw IOException()
        val output = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_HOME_FILE_BYTES) throw HomeLayoutException(R.string.custom_home_too_large)
                output.write(buffer, 0, read)
            }
        }
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(output.toByteArray())).toString()
    }

    private fun save(edit: android.content.SharedPreferences.Editor) {
        val before = prefs.all
        if (edit.commit()) return
        // SharedPreferences applies an edit in memory before it knows whether writing succeeded.
        val restore = prefs.edit().clear()
        before.forEach { (key, value) ->
            when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
            }
        }
        restore.apply()
        throw IOException()
    }

    private fun releaseGrant(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, READ_GRANT) }
    }
}

private suspend fun <T> withConfigLock(action: () -> T): T = withContext(Dispatchers.IO) {
    configLock.withLock { action() }
}

internal fun customHomeError(context: Context, error: Throwable): String {
    if (error !is HomeLayoutException) return context.getString(R.string.custom_home_file_failed)
    val reason = context.getString(error.reason)
    return error.node?.let { context.getString(R.string.custom_home_problem_at, it, reason) } ?: reason
}
