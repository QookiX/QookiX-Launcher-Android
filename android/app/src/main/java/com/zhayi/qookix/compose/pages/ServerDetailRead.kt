package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.compose.bridge.optText
import org.json.JSONArray
import org.json.JSONObject

internal data class ServerDetailRuntime(
    val running: Boolean,
    val jvmUp: Boolean,
    val sleeping: Boolean,
    val wakeError: String?,
    val exitNote: String?,
    val effectiveMaxMemoryMb: Int?,
    val startedAt: Long,
)

internal fun parseDetailRuntime(o: JSONObject): ServerDetailRuntime {
    val running = o.optBoolean("running")
    return ServerDetailRuntime(
        running, o.optBoolean("jvmUp", running), o.optBoolean("sleeping"),
        o.optText("wakeError")?.takeIf(String::isNotBlank),
        o.optText("exitNote")?.takeIf(String::isNotBlank),
        o.optInt("effectiveMaxMemoryMb").takeIf { it > 0 }, o.optLong("startedAt"),
    )
}

internal fun parseDetailLogs(json: String): List<String> {
    val lines = JSONArray(json)
    return ((lines.length() - 2000).coerceAtLeast(0) until lines.length()).map(lines::getString)
}

/** Main-thread callback gate. Explicit refreshes can join the in-flight read. */
internal class DetailRead<T>(private val load: ((Result<T>) -> Unit) -> Unit) {
    private var waiting: MutableList<(Result<T>) -> Unit>? = null

    fun request(callback: ((Result<T>) -> Unit)? = null): Boolean {
        waiting?.let { callbacks ->
            if (callback != null) callbacks.add(callback)
            return false
        }
        waiting = mutableListOf<(Result<T>) -> Unit>().apply { if (callback != null) add(callback) }
        load { result ->
            val callbacks = waiting.orEmpty()
            waiting = null
            callbacks.forEach { it(result) }
        }
        return true
    }
}
