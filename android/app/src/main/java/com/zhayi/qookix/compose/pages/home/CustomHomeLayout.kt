package com.zhayi.qookix.compose.pages.home

import android.util.JsonReader
import android.util.JsonToken
import androidx.annotation.StringRes
import com.zhayi.qookix.R
import org.json.JSONArray
import org.json.JSONObject

internal data class CustomHomeLayout(val nodes: List<HomeNode>, val variables: Map<String, String>)

internal data class HomeNode(
    val type: String,
    val fields: JSONObject,
    val children: List<HomeNode>,
    val actions: List<JSONObject>,
) {
    fun text(key: String): String = fields.opt(key) as? String ?: ""
    fun flag(key: String): Boolean = fields.opt(key) == true
    fun number(key: String): Float? = (fields.opt(key) as? Number)?.toFloat()?.takeIf { it.isFinite() }
}

internal class HomeLayoutException(@StringRes val reason: Int, val node: String? = null) : Exception()

internal const val MAX_HOME_FILE_BYTES = 512 * 1024
internal val homeBuiltIns = setOf("user", "instance", "date", "time", "homePath")
private val placeholder = Regex("""\{([\p{L}\p{N}_]+)\}""")
private val falseValues = setOf("", "0", "false", "null", "undefined", "off", "no")
private val components = setOf("card", "text", "hint", "image", "button", "buttons", "grid", "row", "list-item", "divider", "spacer")

internal fun interpolateHome(value: String, vars: Map<String, String>): String = buildString {
    var start = 0
    for (match in placeholder.findAll(value)) {
        append(value, start, minOf(match.range.first, start + MAX_HOME_FILE_BYTES - length))
        val replacement = vars[match.groupValues[1]] ?: match.value
        append(replacement, 0, minOf(replacement.length, MAX_HOME_FILE_BYTES - length))
        if (length == MAX_HOME_FILE_BYTES) return@buildString
        start = match.range.last + 1
    }
    append(value, start, minOf(value.length, start + MAX_HOME_FILE_BYTES - length))
}

internal fun HomeNode.visible(vars: Map<String, String>): Boolean {
    fun evaluate(expr: String): Boolean {
        val input = expr.trim().removeSurrounding("{", "}")
        val negate = input.startsWith('!')
        val variable = input.removePrefix("!").trim().removeSurrounding("{", "}")
        val truthy = (vars[variable] ?: variable).lowercase() !in falseValues
        return truthy != negate
    }
    val ifExpr = text("if")
    val unlessExpr = text("unless")
    return (ifExpr.isEmpty() || evaluate(ifExpr)) && (unlessExpr.isEmpty() || !evaluate(unlessExpr))
}

internal fun parseCustomHome(source: String): CustomHomeLayout {
    val root = try {
        JsonReader(cleanJsonc(source).reader()).use { reader ->
            val value = readJson(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            value
        }
    } catch (error: HomeLayoutException) {
        throw error
    } catch (_: Exception) {
        throw HomeLayoutException(R.string.custom_home_invalid_json)
    }
    val entries = when (root) {
        is JSONArray -> root
        is JSONObject -> root.optJSONArray("nodes")
        else -> null
    } ?: throw HomeLayoutException(R.string.custom_home_empty_nodes)
    if (entries.length() == 0) throw HomeLayoutException(R.string.custom_home_empty_nodes)

    var count = 0
    fun nodes(array: JSONArray, path: String): List<HomeNode> = List(array.length()) { i ->
        if (++count > 250) throw HomeLayoutException(R.string.custom_home_too_complex)
        val where = "$path[$i]"
        val obj = array.optJSONObject(i) ?: throw HomeLayoutException(R.string.custom_home_invalid_node, where)
        val type = obj.optString("type")
        val requiredField = when (type) {
            "text", "hint", "button" -> "text"
            "image" -> "src"
            "list-item" -> "title"
            else -> null
        }
        if (type !in components || (requiredField != null && obj.opt(requiredField) !is String)) {
            throw HomeLayoutException(R.string.custom_home_invalid_node, where)
        }
        val children = when (val raw = obj.opt("children")) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> nodes(raw, "$where.children")
            else -> throw HomeLayoutException(R.string.custom_home_invalid_node, where)
        }
        val actions = when (val raw = obj.opt("onClick")) {
            null, JSONObject.NULL -> emptyList()
            is JSONObject -> listOf(raw)
            is JSONArray -> {
                if (raw.length() > 64) throw HomeLayoutException(R.string.custom_home_too_complex, where)
                List(raw.length()) { index ->
                    raw.optJSONObject(index) ?: throw HomeLayoutException(R.string.custom_home_invalid_action, where)
                }
            }
            else -> throw HomeLayoutException(R.string.custom_home_invalid_action, where)
        }
        if (actions.any { it.opt("type") !is String }) {
            throw HomeLayoutException(R.string.custom_home_invalid_action, where)
        }
        HomeNode(type, obj, children, actions)
    }

    val variables = (root as? JSONObject)?.optJSONObject("variables")
    return CustomHomeLayout(
        nodes(entries, "nodes"),
        variables?.keys()?.asSequence()?.associateWith { variables.optString(it) }.orEmpty(),
    )
}

private fun readJson(reader: JsonReader, depth: Int): Any {
    if (depth > 40) throw HomeLayoutException(R.string.custom_home_too_complex)
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> JSONObject().apply {
            reader.beginObject()
            while (reader.hasNext()) put(reader.nextName(), readJson(reader, depth + 1))
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> JSONArray().apply {
            reader.beginArray()
            while (reader.hasNext()) put(readJson(reader, depth + 1))
            reader.endArray()
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDouble() }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            JSONObject.NULL
        }
        else -> throw HomeLayoutException(R.string.custom_home_invalid_json)
    }
}

/** Remove comments and trailing commas while preserving quoted strings. */
private fun cleanJsonc(source: String): String = buildString(source.length) {
    var quoted = false
    var index = if (source.startsWith('\uFEFF')) 1 else 0
    while (index < source.length) {
        val char = source[index++]
        when {
            quoted && char == '\\' && index < source.length -> append(char).append(source[index++])
            char == '"' -> { quoted = !quoted; append(char) }
            quoted -> append(char)
            char == '/' && source.getOrNull(index) == '/' -> {
                while (index < source.length && source[index] != '\n' && source[index] != '\r') index++
                append(' ')
            }
            char == '/' && source.getOrNull(index) == '*' -> {
                val end = source.indexOf("*/", index + 1)
                if (end < 0) throw HomeLayoutException(R.string.custom_home_invalid_json)
                index = end + 2
                append(' ')
            }
            char == ']' || char == '}' -> {
                val end = indexOfLast { !it.isWhitespace() }
                if (end >= 1 && this[end] == ',') {
                    val before = (end - 1 downTo 0).firstOrNull { !this[it].isWhitespace() }
                    if (before != null && this[before] !in "{[,") deleteCharAt(end)
                }
                append(char)
            }
            else -> append(char)
        }
    }
}
