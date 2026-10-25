package com.zhayi.qookix.compose.bridge

import org.json.JSONObject
import org.json.JSONArray

/**
 * 实例内容操作域（文件管理 / 导入导出）（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 * 命令名逐个核对自 Rust 注册表与 src/api.ts，不是猜的。
 */

// ── 文件管理（实例 + 服务器，命令名核对自 src/api.ts）────────────────────

/**
 * 列实例目录。
 *
 * ⚠️ 返回的是对象 `{"rel":..., "entries":[...]}`（commands.rs:1229），
 * 不是数组 —— 用 callArr 会抛 JSONException。这里统一解出 `entries`。
 */
fun QookixApi.listInstanceDir(instanceId: String, rel: String, onResult: (Result<JSONArray>) -> Unit) =
    callObj("list_instance_dir", JSONObject().put("instanceId", instanceId).put("rel", rel)) { r ->
        onResult(r.mapCatching { it.optJSONArray("entries") ?: it.optJSONArray("items") ?: JSONArray() })
    }

/** 读实例文件。 */
fun QookixApi.readInstanceFile(instanceId: String, rel: String, onResult: (Result<String>) -> Unit) =
    call("read_instance_file", JSONObject().put("instanceId", instanceId).put("rel", rel), onResult)

/** 写实例文件。 */
fun QookixApi.writeInstanceFile(
    instanceId: String,
    rel: String,
    content: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "write_instance_file",
    JSONObject().put("instanceId", instanceId).put("rel", rel).put("content", content),
    onResult,
)

/** 新建文件/目录。 */
fun QookixApi.createInstanceEntry(
    instanceId: String,
    rel: String,
    isDir: Boolean,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "create_instance_entry",
    JSONObject().put("instanceId", instanceId).put("rel", rel).put("isDir", isDir),
    onResult,
)

/** 重命名。 */
fun QookixApi.renameInstancePath(
    instanceId: String,
    rel: String,
    newName: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "rename_instance_path",
    JSONObject().put("instanceId", instanceId).put("rel", rel).put("newName", newName),
    onResult,
)

/** 删除。 */
fun QookixApi.deleteInstancePath(instanceId: String, rel: String, onResult: (Result<String>) -> Unit = {}) =
    call("delete_instance_path", JSONObject().put("instanceId", instanceId).put("rel", rel), onResult)

/**
 * 列服务器目录。
 *
 * ⚠️ 返回的是对象 `{"rel":..., "entries":[...]}`（server_files.rs:32），
 * 不是数组 —— 用 callArr 会抛 JSONException。这里统一解出 `entries`。
 */
fun QookixApi.listHostedServerDir(id: String, rel: String, onResult: (Result<JSONArray>) -> Unit) =
    callObj("list_hosted_server_dir", JSONObject().put("id", id).put("rel", rel)) { r ->
        onResult(r.mapCatching { it.optJSONArray("entries") ?: it.optJSONArray("items") ?: JSONArray() })
    }

/** 读服务器文件。 */
fun QookixApi.readHostedServerFile(id: String, rel: String, onResult: (Result<String>) -> Unit) =
    call("read_hosted_server_file", JSONObject().put("id", id).put("rel", rel), onResult)

/** 写服务器文件。 */
fun QookixApi.writeHostedServerFile(
    id: String,
    rel: String,
    content: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "write_hosted_server_file",
    JSONObject().put("id", id).put("rel", rel).put("content", content),
    onResult,
)

/** 新建服务器目录内的文件/目录（2026-10-26 补：原来这三条命令根本不存在）。 */
fun QookixApi.createHostedServerEntry(
    id: String,
    rel: String,
    isDir: Boolean,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "create_hosted_server_entry",
    JSONObject().put("id", id).put("rel", rel).put("isDir", isDir),
    onResult,
)

/** 重命名服务器目录内的文件/目录。 */
fun QookixApi.renameHostedServerPath(
    id: String,
    rel: String,
    newName: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "rename_hosted_server_path",
    JSONObject().put("id", id).put("rel", rel).put("newName", newName),
    onResult,
)

/** 删除服务器目录内的文件/目录。 */
fun QookixApi.deleteHostedServerPath(
    id: String,
    rel: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "delete_hosted_server_path",
    JSONObject().put("id", id).put("rel", rel),
    onResult,
)

// ── 实例内操作 ────────────────────────────────────────────────────────

/** 安装游戏文件（补齐版本/库/素材）。 */
fun QookixApi.installGame(instanceId: String, onResult: (Result<String>) -> Unit = {}) =
    call("install_game", JSONObject().put("instanceId", instanceId), onResult)

/**
 * 检查游戏文件完整性（启动前/修按钮用）。
 *
 * 返回字段（**已核对 `src/types.ts` 的 `InstanceFileReport`**）：
 *   - `ok` / `total` —— 5 个关键项里齐了几个
 *   - `missing[]` —— 缺的项，每项有 `kind` / `detail` / `fixable`
 *   - `advice` —— 缺文件时给用户的一句人话建议
 *   - `can_launch` —— 关键项全齐，可直接启动
 *
 * ⚠️ `fixable=false` 表示「补全也修不好」（如加载器版本号为空，
 * 得换游戏版本）—— 这时不该让用户白等下载。
 */
fun QookixApi.checkInstanceFiles(instanceId: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("check_instance_files", JSONObject().put("instanceId", instanceId), onResult)

/**
 * 补全缺失的游戏文件。
 *
 * Rust 侧复用 `install_game` 的逻辑：**逐文件校验、缺的才下**，
 * 所以重复调用是安全的（不会重下已有的）。
 * 进度同样走 `install://progress` 事件 → 下载页能看到。
 */
fun QookixApi.repairInstanceFiles(instanceId: String, onResult: (Result<String>) -> Unit = {}) =
    call("repair_instance_files", JSONObject().put("instanceId", instanceId), onResult)

/** 列出某个实例可加入的服务器（局域网发现）。 */
fun QookixApi.listServers(instanceId: String, onResult: (Result<JSONArray>) -> Unit) =
    callObj("list_servers", JSONObject().put("instanceId", instanceId)) { r ->
        onResult(r.mapCatching { it.optJSONArray("servers") ?: JSONArray() })
    }

/** ping 一个 Minecraft 服务器拿延迟与在线人数。 */
fun QookixApi.pingMcServer(address: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("ping_mc_server", JSONObject().put("address", address), onResult)

/** 读某个实例的游戏日志。 */
fun QookixApi.readInstanceLog(instanceId: String, onResult: (Result<String>) -> Unit) =
    call("read_instance_log", JSONObject().put("instanceId", instanceId), onResult)

/** 导出实例日志（打包成 zip 便于分享）。 */
fun QookixApi.exportInstanceLogs(instanceId: String, onResult: (Result<String>) -> Unit = {}) =
    call("export_instance_logs", JSONObject().put("instanceId", instanceId), onResult)

/** 导入本地文件到实例（kind = mods / resourcepacks / …）。 */
fun QookixApi.importLocalFile(
    instanceId: String,
    kind: String,
    sourcePath: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "import_local_file",
    JSONObject()
        .put("instanceId", instanceId)
        .put("kind", kind)
        .put("sourcePath", sourcePath),
    onResult,
)

/** 导入整合包（.mrpack / .zip）。 */
fun QookixApi.importModpack(filePath: String, onResult: (Result<String>) -> Unit = {}) =
    call("import_modpack", JSONObject().put("filePath", filePath), onResult)

/** 导入实例图标。 */
fun QookixApi.importInstanceImage(sourcePath: String, onResult: (Result<String>) -> Unit = {}) =
    call("import_instance_image", JSONObject().put("sourcePath", sourcePath), onResult)

/** 从游戏文件里抽取图标（补给没有图标的实例）。 */
fun QookixApi.extractGameIcons(instanceId: String?, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("extract_game_icons", JSONObject().put("instanceId", instanceId ?: JSONObject.NULL), onResult)

/** 列出某实例的崩溃日志文件名。 */
fun QookixApi.listCrashLogs(instanceId: String, onResult: (Result<JSONArray>) -> Unit) =
    callArr("list_crash_logs", JSONObject().put("id", instanceId), onResult)

/** 分析崩溃日志（给可能的原因）。 */
fun QookixApi.analyzeCrashLog(
    instanceId: String,
    filename: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "analyze_crash_log",
    JSONObject().put("id", instanceId).put("filename", filename),
    onResult,
)

/** 读崩溃报告原文。 */
fun QookixApi.getCrashReportContent(
    instanceId: String,
    filename: String,
    onResult: (Result<String>) -> Unit,
) = call("get_crash_report_content", JSONObject().put("id", instanceId).put("filename", filename), onResult)

