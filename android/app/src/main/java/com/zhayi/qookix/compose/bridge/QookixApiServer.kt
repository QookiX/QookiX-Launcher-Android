package com.zhayi.qookix.compose.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务器域（详情 / 本地服务器 / 插件）（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 * 命令名逐个核对自 Rust 注册表与 src/api.ts，不是猜的。
 */

// ── 服务器详情（命令名与参数逐个核对自 src/api.ts，不是猜的）────────────

/** 服务器属性（server.properties）。 */
/**
 * 取一台托管服务器的完整配置（`get_hosted_server`）。
 *
 * 与 [getHostedServerProperties] 的区别：那个返回的是 `server.properties`
 * 里的游戏内配置（motd/gamemode/…），这个返回启动器自己的 `ServerConfig`
 *（name / port / eula / maxMemoryMb / core …）。EULA 同意状态只能从这里读。
 */
fun QookixApi.getHostedServer(id: String, onResult: (Result<JSONObject>) -> Unit = {}) =
    callObj("get_hosted_server", JSONObject().put("id", id), onResult)

fun QookixApi.getHostedServerProperties(id: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("get_hosted_server_properties", JSONObject().put("id", id), onResult)

/**
 * 写回服务器属性（`server.properties` 白名单项）。
 *
 * ⚠️ 返回的是**完整的最新属性对象**（不是空串）：
 * Rust 侧会整批校验、越界钳制（视距 3–32、人数 1–100），
 * 然后把落盘后的结果回给前端 —— 界面必须以这个返回值为准刷新，
 * 否则用户填了超范围的值，界面显示的和文件里的会不一致。
 *
 * 之前用 call 收字符串是错的（类型不匹配，写配置永久失败）。
 */
fun QookixApi.setHostedServerProperties(
    id: String,
    patch: JSONObject,
    onResult: (Result<JSONObject>) -> Unit = {},
) = callObj(
    "set_hosted_server_properties",
    JSONObject().put("id", id).put("patch", patch),
    onResult,
)

/** 服务器对外地址（局域网 IP + 端口）。 */
/**
 * 服务器地址。
 *
 * ⚠️⚠️ 2026-10-16 修（真机实测的严重 bug）：
 * Rust 的 `hosted_server_address` 返回的是一个**纯字符串**
 *（`Some(format!("{ip}:{port}"))`，如 `"192.168.1.25:25565"`），
 * 而这里原来用 `callObj` 按 **JSONObject** 解析 →
 * `JSONException: Value 192.168.1.25 ... cannot be converted to JSONObject`。
 *
 * 后果**不只是地址显示不出来**：`ServerDetailRepo.load` 会把三个子请求
 * 的失败汇总成 `Result.failure`，于是整个 load 失败 →
 * `coreInstalled` 永远停在默认值 `true` → 界面显示「启动」而不是
 * 「安装核心」→ **用户完全没有途径安装服务端核心**，服务器永远起不来。
 *
 * 改成 `callRaw`（字符串），调用方按字符串处理。
 */
fun QookixApi.hostedServerAddress(id: String, onResult: (Result<String>) -> Unit) =
    callRaw("hosted_server_address", JSONObject().put("id", id), onResult)

/** 服务端核心是否已安装。 */
fun QookixApi.hostedServerCoreInstalled(id: String, onResult: (Result<Boolean>) -> Unit) =
    call("hosted_server_core_installed", JSONObject().put("id", id)) { r ->
        onResult(r.mapCatching { it.trim() == "true" })
    }

/** 服务器运行状态。 */
fun QookixApi.hostedServerRuntime(id: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("hosted_server_runtime", JSONObject().put("id", id), onResult)

/** 安装服务端核心（下载 Paper 等）。 */
fun QookixApi.installHostedServerCore(id: String, onResult: (Result<String>) -> Unit = {}) =
    call("install_hosted_server_core", JSONObject().put("id", id), onResult)

/** 在系统文件管理器里打开服务器目录。 */
fun QookixApi.openHostedServerDirectory(id: String, onResult: (Result<String>) -> Unit = {}) =
    call("open_hosted_server_directory", JSONObject().put("id", id), onResult)

/** 读服务器日志。 */
fun QookixApi.readHostedServerLog(id: String, onResult: (Result<String>) -> Unit) =
    call("read_hosted_server_log", JSONObject().put("id", id), onResult)

/** 向服务器控制台发一条命令（`list` / `op <玩家>` / `stop` …）。 */
fun QookixApi.serverConsoleCommand(id: String, command: String, onResult: (Result<String>) -> Unit) =
    call("server_console_command", JSONObject().put("id", id).put("command", command), onResult)

/** 设备可用内存（MB）—— 创建服务器时算建议值用。 */
fun QookixApi.deviceAvailableMemoryMb(onResult: (Result<Long>) -> Unit) =
    call("device_available_memory_mb", null) { r ->
        onResult(r.mapCatching { it.trim().trim('"').toLongOrNull() ?: 0L })
    }

/**
 * 请求加入电池优化白名单。
 *
 * 长开服必须申请 —— 否则 ColorOS 等 ROM 会在息屏后掐掉后台服务，
 * 表现为「开服玩一会儿，服自己没了」。
 */
fun QookixApi.requestBatteryUnrestricted(onResult: (Result<String>) -> Unit = {}) =
    call("request_battery_unrestricted", null, onResult)

/** 服务器内存建议（返回 {minMemoryMb, maxMemoryMb}）。 */
fun QookixApi.suggestServerMemory(onResult: (Result<JSONObject>) -> Unit) =
    callObj("suggest_server_memory", null, onResult)

// ── 本地服务器（命令名核对自 src/api.ts）────────────────────────────────

/**
 * 新建本地服务器。
 *
 * @param core 只能是 `paper` / `vanilla` / `fabric`
 *   —— `servers.rs` 只实现了这三条下载链路，其余会报「还在开发中」。
 * @param mcVersion 留空 = 后端挑最新稳定版
 */
fun QookixApi.createHostedServer(
    name: String,
    core: String,
    mcVersion: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "create_hosted_server",
    JSONObject()
        .put("name", name)
        .put("core", core)
        .put("mcVersion", mcVersion),
    onResult,
)

/**
 * 改服务器的 server.json 字段（`update_hosted_server`）。
 *
 * ⚠️⚠️ 这是「**补的包装**」，之前**完全不存在** —— 所以 Compose 侧
 * 既改不了内存、也开不了 EULA。
 *
 * Rust 签名是 `update_hosted_server(patch: serde_json::Value)`
 * （`servers.rs:186`）—— 整个 patch 是一个**裸 JSON 对象**，
 * **不走** Tauri 的 camelCase 参数名转换。所以调用方放进 patch 的键
 * 必须**逐字**等于 Rust 里 `patch.get("…")` 读的名字：
 *
 *   id / name / port / motd / eula /
 *   max_memory_mb / min_memory_mb / jvm_args /
 *   sleep_timeout_min / sleep_hint / wake_hint
 *
 * （全是 **snake_case**；写成 `maxMemoryMb` 不会有任何报错，
 *   只会**静默不生效**。）
 *
 * 数值字段 Rust 用 `as_u64()` 读 —— **必须是 JSON 数字，不能是字符串**，
 * 否则返回 None 被当成「没传这个键」。
 *
 * 返回写入后的完整 [ServerConfig]（含 `maxPlayers` 等运行期字段）。
 */
fun QookixApi.updateHostedServer(
    patch: JSONObject,
    onResult: (Result<JSONObject>) -> Unit = {},
) = callObj("update_hosted_server", JSONObject().put("patch", patch), onResult)

/** 启动本地服务器。 */
fun QookixApi.startHostedServer(id: String, onResult: (Result<String>) -> Unit = {}) =
    call("start_hosted_server", JSONObject().put("id", id), onResult)

/** 删除本地服务器。 */
fun QookixApi.deleteHostedServer(id: String, onResult: (Result<String>) -> Unit = {}) =
    call("delete_hosted_server", JSONObject().put("id", id), onResult)


// ── 服务器插件 ────────────────────────────────────────────────────────

fun QookixApi.listServerPlugins(serverId: String, onResult: (Result<JSONArray>) -> Unit) =
    callArr("list_server_plugins", JSONObject().put("serverId", serverId), onResult)

/**
 * 安装内容到实例（模组 / 资源包 / 光影 / 整合包）。
 *
 * ⚠️ 这是「装模组」的**唯一路径** ——
 * 与 `installGame`（下载游戏本体）是两条独立流程。
 * 之前这一条**完全缺失**：API 层没这个方法，UI 也没有安装对话框，
 * 内容中心的卡片只能"用浏览器打开项目页"。
 *
 * 返回：{ ok: bool, filename?: string, mods?: number }
 */
fun QookixApi.installContent(
    instanceId: String,
    provider: String,
    projectId: String,
    versionId: String,
    kind: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "install_content",
    JSONObject()
        .put("instanceId", instanceId)
        .put("provider", provider)
        .put("projectId", projectId)
        .put("versionId", versionId)
        .put("kind", kind),
    onResult,
)

/**
 * 把内容装到**托管服务器**（模组 -> `mods/`，插件 -> `plugins/`）。
 *
 * ⚠️ 与 [installContent] 的区别只有目标目录（2026-10-18 新增）。
 * 后端见 `browse::install_content_to_server`。
 *
 * 只接受 `kind` = `mod` / `plugin`；资源包、光影、整合包会被后端拒绝
 *（服务器没有那两个目录）。
 */
fun QookixApi.installServerContent(
    serverId: String,
    provider: String,
    projectId: String,
    versionId: String,
    kind: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "install_server_content",
    JSONObject()
        .put("serverId", serverId)
        .put("provider", provider)
        .put("projectId", projectId)
        .put("versionId", versionId)
        .put("kind", kind),
    onResult,
)

fun QookixApi.installServerPlugin(
    serverId: String,
    slug: String,
    projectName: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "install_server_plugin",
    JSONObject()
        .put("serverId", serverId)
        .put("slug", slug)
        .put("projectName", projectName),
    onResult,
)

fun QookixApi.deleteServerPlugin(
    serverId: String,
    fileName: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "delete_server_plugin",
    JSONObject().put("serverId", serverId).put("fileName", fileName),
    onResult,
)

/** 在 Hangar（Paper 插件平台）搜索插件。 */
/**
 * 搜索服务器插件（PaperMC Hangar 平台）。
 *
 * 与 Modrinth/CurseForge 不同：Hangar 没有 project→version→files 那套模型，
 * 而且**目标是服务器而不是实例**（插件装进服务器的 plugins 目录）。
 */
fun QookixApi.hangarSearchPlugins(
    query: String,
    page: Int = 0,
    pageSize: Int = 20,
    onResult: (Result<JSONArray>) -> Unit,
) = callArr(
    "hangar_search_plugins",
    JSONObject().put("query", query).put("page", page).put("pageSize", pageSize),
    onResult,
)

