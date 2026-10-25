package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.R
import android.util.Log
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 文件操作的后端抽象。
 *
 * ── 为什么要有这一层 ────────────────────────────────────────────────────
 * 旧界面有 **两份** 文件管理器（`FileManager.vue` 37 KB / `ServerFileManager.vue` 28 KB），
 * 是复制粘贴出来的 —— 两者操作的数据结构完全相同
 * （都是 `fsutil.rs::FsEntry`），只是底层命令前缀不同：
 *
 *   实例文件： list_instance_dir      / read_instance_file      / write_instance_file
 *   服务器文件：list_hosted_server_dir / read_hosted_server_file / write_hosted_server_file
 *
 * 复制粘贴的代价已经显现：旧注释里提到两者行为出现了细微差异。
 * 这里改成**一个页面 + 一个 FileOps 接口**，两种场景各给一个实现，
 * 页面代码只写一遍。
 */
interface FileOps {
    /** 场景名（显示用）。 */
    @get:androidx.annotation.StringRes
    val labelRes: Int
    companion object {
        internal const val TAG = "FileOps"
    }

    fun list(rel: String, onResult: (Result<List<FileEntryUi>>) -> Unit)
    fun read(rel: String, onResult: (Result<String>) -> Unit)
    fun write(rel: String, content: String, onResult: (Result<String>) -> Unit)
    fun create(rel: String, isDir: Boolean, onResult: (Result<String>) -> Unit)
    fun rename(rel: String, newName: String, onResult: (Result<String>) -> Unit)
    fun delete(rel: String, onResult: (Result<String>) -> Unit)
}

/** 实例文件操作。 */
class InstanceFileOps(private val api: QookixApi, private val instanceId: String) : FileOps {
    override val labelRes = R.string.file_ops_instance

    override fun list(rel: String, onResult: (Result<List<FileEntryUi>>) -> Unit) =
        api.callRaw("list_instance_dir", org.json.JSONObject().put("instanceId", instanceId).put("rel", rel)) { r ->
            // 诊断：把原始返回打出来。列表为空时无法区分
            //「后端说这目录就是空的」和「解析把数据丢了」——
            // 之前这一层是静默的，排查时完全没有线索。
            r.fold(
                onSuccess = { raw ->
                    Log.i(
                        FileOps.TAG,
                        "list_instance_dir(rel='$rel') 原始返回（前 300 字）：${raw.take(300)}",
                    )
                },
                onFailure = { e -> Log.w(FileOps.TAG, "list_instance_dir(rel='$rel') 失败: ${e.message}") },
            )
            onResult(r.mapCatching { parseEntriesEnvelope(it) })
        }

    override fun read(rel: String, onResult: (Result<String>) -> Unit) =
        api.readInstanceFile(instanceId, rel, onResult)

    override fun write(rel: String, content: String, onResult: (Result<String>) -> Unit) =
        api.writeInstanceFile(instanceId, rel, content, onResult)

    override fun create(rel: String, isDir: Boolean, onResult: (Result<String>) -> Unit) =
        api.createInstanceEntry(instanceId, rel, isDir, onResult)

    override fun rename(rel: String, newName: String, onResult: (Result<String>) -> Unit) =
        api.renameInstancePath(instanceId, rel, newName, onResult)

    override fun delete(rel: String, onResult: (Result<String>) -> Unit) =
        api.deleteInstancePath(instanceId, rel, onResult)
}

/** 服务器文件操作。 */
class ServerFileOps(private val api: QookixApi, private val serverId: String) : FileOps {
    override val labelRes = R.string.file_ops_server

    override fun list(rel: String, onResult: (Result<List<FileEntryUi>>) -> Unit) =
        api.callRaw("list_hosted_server_dir", org.json.JSONObject().put("id", serverId).put("rel", rel)) { r ->
            onResult(r.mapCatching { parseEntriesEnvelope(it) })
        }

    override fun read(rel: String, onResult: (Result<String>) -> Unit) =
        api.readHostedServerFile(serverId, rel, onResult)

    override fun write(rel: String, content: String, onResult: (Result<String>) -> Unit) =
        api.writeHostedServerFile(serverId, rel, content, onResult)

    // 2026-10-26：这三条以前直接抛 UnsupportedOperationException ——
    // 按钮照常渲染，点了没反应。Rust 侧已补齐命令，与实例侧共用 fsutil。
    override fun create(rel: String, isDir: Boolean, onResult: (Result<String>) -> Unit) =
        api.createHostedServerEntry(serverId, rel, isDir, onResult)

    override fun rename(rel: String, newName: String, onResult: (Result<String>) -> Unit) =
        api.renameHostedServerPath(serverId, rel, newName, onResult)

    override fun delete(rel: String, onResult: (Result<String>) -> Unit) =
        api.deleteHostedServerPath(serverId, rel, onResult)
}

/**
 * 解析列表命令的返回。
 *
 * ⚠️ **实测踩坑（2026-10-13）**：Rust 侧返回的**不是裸数组**，而是包了一层：
 * ```json
 * { "entries": [ { "name": "...", "rel": "...", "is_dir": true, ... } ] }
 * ```
 * 一开始按裸数组解析，结果是整个列表读不出来、错误信息里把原始 JSON
 * 全打印了出来（实测截图可见）。
 *
 * 这里两种都兼容：优先取 `entries` 字段，取不到再当裸数组解。
 */
internal fun parseEntriesEnvelope(json: String): List<FileEntryUi> {
    val trimmed = json.trim()
    return try {
        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val arr = obj.optJSONArray("entries")
                ?: obj.optJSONArray("items")
                ?: return emptyList()
            parseEntries(arr)
        } else {
            parseEntries(JSONArray(trimmed))
        }
    } catch (e: Exception) {
        Log.w("FileOps", "解析文件列表失败（原始返回前 200 字：${trimmed.take(200)}）", e)
        emptyList()
    }
}

/**
 * 解析 `FsEntry[]`。
 *
 * 字段名是 **snake_case**（`fsutil.rs::FsEntry` 没有 rename_all）：
 *   name / rel / size / modified / is_dir / ext
 */
internal fun parseEntries(json: JSONArray): List<FileEntryUi> {
    return try {
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val rel = o.optString("rel")
            if (name.isBlank() || rel.isBlank()) return@mapNotNull null
            FileEntryUi(
                name = name,
                rel = rel,
                size = o.optLong("size", 0L),
                modified = o.optLong("modified", 0L),
                isDir = o.optBoolean("is_dir", false),
                ext = o.optString("ext"),
            )
        }.sortedWith(
            // 先目录后文件，组内按名字 —— 文件管理器的通用约定
            compareByDescending<FileEntryUi> { it.isDir }
                .thenBy({ it.name.lowercase() })
        )
    } catch (e: Exception) {
        Log.w("FileOps", "解析文件列表失败", e)
        emptyList()
    }
}

/**
 * 文件编辑器。
 *
 * 保存时的写回由调用方（FileListRoute）处理 —— 这里只管编辑与校验。
 *
 * 为什么不用旧界面的 CodeEditor.vue（14 KB）：
 * 那个是基于 textarea + 高亮的实现。Compose 侧用
 * `BasicTextField` 配等宽字体已经够用（配置文件都是纯文本），
 * 高亮要引入完整语法解析器，收益不抵成本。
 */
internal fun isTextFile(ext: String): Boolean = ext.lowercase() in setOf(
    "txt", "json", "json5", "toml", "yml", "yaml", "properties", "cfg", "conf", "ini",
    "log", "md", "xml", "html", "js", "ts", "sh", "bat", "csv", "lang", "mcmeta",
    // 无扩展名的常见配置文件
    "",
)

/** 读文件时的体积上限（超过就提示用外部编辑器，避免大文件把界面卡死）。 */
internal const val MAX_EDIT_BYTES = 2 * 1024 * 1024

/** 判断是否应该提示「文件太大」。 */
internal fun tooLargeToEdit(size: Long): Boolean = size > MAX_EDIT_BYTES

/** 便捷：把 JSONObject 里的字段安全转字符串（配置值可能是数字/布尔）。 */
internal fun JSONObject.optStringAny(key: String): String =
    this.opt(key)?.toString()?.trim('"') ?: ""



