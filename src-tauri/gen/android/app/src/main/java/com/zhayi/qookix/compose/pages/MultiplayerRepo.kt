package com.zhayi.qookix.compose.pages

import android.util.Log
import com.zhayi.qookix.compose.bridge.QookixApi
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多人游戏的数据层（本地服务器 + 陶瓦联机）。
 *
 * 命令（已核对 `lib.rs` 注册表与 `api.ts` 调用）：
 *   list_hosted_servers  → 服务器列表
 *   start_hosted_server  { id }
 *   stop_hosted_server   { id }
 *   is_hosted_server_running { id }
 */
class MultiplayerRepo(private val api: QookixApi) {

    companion object {
        private const val TAG = "MultiplayerRepo"
    }

    /** 服务器列表。 */
    fun loadServers(onResult: (Result<List<ServerUi>>) -> Unit) {
        api.listHostedServers { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parseServers(it))) },
                onFailure = { e ->
                    Log.w(TAG, "拉服务器列表失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 启动服务器。 */
    fun start(id: String, onResult: (Result<String>) -> Unit) {
        api.callRaw("start_hosted_server", JSONObject().put("id", id), onResult)
    }

    /** 停止服务器。 */
    fun stop(id: String, onResult: (Result<String>) -> Unit) {
        api.callRaw("stop_hosted_server", JSONObject().put("id", id), onResult)
    }

    /**
     * 解析服务器列表。
     *
     * 字段对齐 `servers.rs` 的 `ServerConfig`：
     *   id / name / core / mc_version / port / ...
     * （序列化后用 snake_case，因为 Rust 结构体没加 rename_all）
     */
    fun parseServers(json: JSONArray): List<ServerUi> {
        return try {
            (0 until json.length()).mapNotNull { i ->
                val o = json.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id")
                if (id.isBlank()) return@mapNotNull null
                ServerUi(
                    id = id,
                    name = o.optString("name").ifBlank { "Server" },
                    core = o.optString("core").ifBlank { "paper" },
                    mcVersion = o.optString("mc_version").ifBlank { o.optString("mcVersion") },
                    running = o.optBoolean("running", false),
                    address = o.optString("address"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析服务器列表失败", e)
            emptyList()
        }
    }
}

