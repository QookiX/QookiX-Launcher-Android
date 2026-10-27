package com.zhayi.qookix.compose.bridge


import com.zhayi.qookix.R
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin 侧的 Rust API 门面。
 *
 * ── 这一层存在的意义 ──────────────────────────────────────────────────
 * `RustBridge` 只提供最原始的 `invoke(cmd, payloadJson)`；
 * 调用点如果直接写命令名字符串，会出现三类问题：
 *   1. 命令名拼错 → 运行期才炸，且 Rust 侧只回一个泛化错误
 *   2. 参数 JSON 手拼 → 转义/类型错（尤其含中文或引号的路径）
 *   3. 返回值 JSON 手解 → 字段名写错，静默拿到默认值
 *
 * 所以这一层做三件事：**命令名常量 + 参数构造 + 返回解析**。
 * 对应旧前端 `src/api.ts` 的 178 个调用点，按页面实际需要逐步补齐
 * （不是一次性全写完 —— 没被页面用到的命令先不写，避免死代码）。
 *
 * ── 命名约定 ──────────────────────────────────────────────────────────
 * Kotlin 方法名沿用旧 api.ts 的语义名（listInstances / getSettings…），
 * 便于对照，但**不是**照抄它的实现结构 —— 这里返回的是已解析的模型。
 */
class QookixApi(private val bridge: RustBridge) {

    companion object {
        private const val TAG = "QookixApi"

        /**
         * 长任务命令的特征词。
         *
         * 命中的命令**不设超时**：安装/下载/导入/启动本来就要跑几分钟，
         * 它们靠事件推进度，不能按「命令多久返回」判死（会把正常的慢网安装
         * 误判成卡死）。其余命令一律走 [RustBridge.DEFAULT_TIMEOUT_MS]。
         *
         * 宁可漏判（长任务少了保护）也不要误判（正常安装被掐断）。
         */
        private val LONG_TASK_HINTS = listOf(
            "install", "import", "download", "launch", "modpack",
            "extract", "unpack", "upload", "backup", "restore",
            // ⚠️ 2026-10-27 真机：`repair_instance_files` 要下几百 MB、动辄几分钟。
            // 不在名单里 → 120 秒后被判超时，弹「补全失败」，而任务其实还在正常跑
            //（用户同时看到「补全失败」和 100% 进度条）。
            "repair",
        )

        /**
         * 精确名单：不命中上面的词、但确实是长任务的命令。
         *
         * ⚠️ 刻意**不**把 `update` / `copy` 当通用词：`update_instance`、
         * `update_settings` 都是毫秒级的保存，正好是最需要超时保护的那类。
         */
        private val LONG_TASK_EXACT = setOf(
            "check_updates", "check_for_update", "apply_update",
            "export_instance_logs", "export_control_layout",
        )

        /**
         * 有副作用、不能并发重入的命令（防双击 / 连点）。
         *
         * 只收「重复执行会真的出问题」的那些：重复查列表只是浪费一次 IPC，
         * 而重复 launch_game 会起两个 JVM、重复 delete 会二次删同一个目录。
         */
        private val SIDE_EFFECT_COMMANDS = setOf(
            "launch_game",
            "start_hosted_server",
            "stop_hosted_server",
            "create_instance",
            "delete_instance",
            "install_game",
            "install_content",
            "install_modpack",
            "import_modpack",
        )

        /** 正在飞的重命令（`命令|payload`）。必须放在 companion：api 会被 new 很多次。 */
        private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** 该命令允许等多久（0 = 不限时）。 */
        internal fun timeoutFor(cmd: String): Long =
            if (cmd in LONG_TASK_EXACT || LONG_TASK_HINTS.any { cmd.contains(it) }) {
                0L
            } else {
                RustBridge.DEFAULT_TIMEOUT_MS
            }
    }

    // ── 通用调用工具 ──────────────────────────────────────────────────────

    /**
     * 调用一个返回 JSON 的命令。
     *
     * @param cmd 命令名（与 Rust `#[tauri::command]` 函数名一致）
     * @param payload 参数对象；null 表示无参
     */
    // ⚠️ 可见性从 private 放宽到 internal：按域拆出去的 extension 文件
    //（如 `QookixApiSkin.kt`）要调这几个工具。仍不出 module。
    internal fun call(
        cmd: String,
        payload: JSONObject? = null,
        onResult: (Result<String>) -> Unit,
    ) {
        // 统一走 RustBridge.invoke：它已经在后台线程执行 JNI 直连，并把失败信封
        // 解析成 ApiError（结构化 code / retryable）。
        //
        // 2026-10-25：原来这里有一条「白名单走 JNI、其余走 bridge」的分叉。
        // 两条路现在完全等价（bridge 也是纯 JNI），但白名单那条**没有解析 ApiError**，
        // 于是大多数命令的错误仍退化成 IllegalStateException —— A2 的错误契约
        // 在这种路径下形同虚设。删掉分叉后，所有命令共用同一套解析。
        val body = payload?.toString() ?: "{}"
        // 有副作用的命令：同一条还在飞时忽略重复点击。
        // 双击「启动游戏」会发两次 launch_game —— 第二次要么报错、要么起两个 JVM；
        // 键里带 payload，不同实例/参数互不影响。
        val key = if (cmd in SIDE_EFFECT_COMMANDS) "$cmd|$body" else null
        if (key != null && !inFlight.add(key)) {
            Log.w(TAG, "忽略重复调用：$cmd（上一次还没返回）")
            return
        }
        bridge.invoke(cmd, body, timeoutFor(cmd)) { r ->
            if (key != null) inFlight.remove(key)
            onResult(r.mapCatching { unwrapJsonString(it) })
        }
    }

    /**
     * 把 JSON 字符串字面量拆成裸文本。
     *
     * - `"文本"` → `文本`（去掉首尾引号并反转义）
     * - 已经是裸文本 / 是对象或数组 → 原样返回
     *
     * 为什么要判类型而不是无脑 `trim('"')`：
     * 返回内容本身可能以引号开头或结尾（比如日志里就有引号），
     * 无脑 trim 会把用户的真实内容也削掉。
     */
    private fun unwrapJsonString(raw: String): String {
        val t = raw.trim()
        if (t.length < 2 || !t.startsWith("\"") || !t.endsWith("\"")) return raw
        return try {
            // 用 JSONArray 包一层再取 —— 比自己写反转义可靠
            org.json.JSONArray("[$t]").optString(0, raw)
        } catch (e: Exception) {
            raw
        }
    }

    /** 调用并解析成 JSONObject。 */
    internal fun callObj(
        cmd: String,
        payload: JSONObject? = null,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        call(cmd, payload) { r ->
            onResult(r.mapCatching { s ->
                // ⚠️ 2026-10-16 修：后端返回 JSON `null` 时，桥会把字符串 "null"
                // 递过来，`JSONObject("null")` 会抛
                // `JSONException: Value null ... cannot be converted to JSONObject`。
                // 真机表现：服务器未运行时 `hosted_server_runtime` 返回 null，
                // 整个详情页 load 失败 → coreInstalled 卡在默认 true →
                // 「安装核心」按钮消失，用户无法装核心。
                // 统一把 null 当成空对象（调用方都用 optXxx 取值，安全）。
                if (s.isBlank() || s.trim() == "null") JSONObject() else JSONObject(s)
            })
        }
    }

    /** 调用并解析成 JSONArray。 */
    internal fun callArr(
        cmd: String,
        payload: JSONObject? = null,
        onResult: (Result<JSONArray>) -> Unit,
    ) {
        call(cmd, payload) { r ->
            onResult(r.mapCatching { JSONArray(it) })
        }
    }

    /**
     * 原始调用（给仓库层用）。
     *
     * 有些命令的返回形状不固定（或不需要解析），仓库层直接用这个，
     * 避免为每个命令都在这里加一个包装方法。
     */
    fun callRaw(
        cmd: String,
        payload: JSONObject? = null,
        onResult: (Result<String>) -> Unit,
    ) = call(cmd, payload, onResult)

    // ── 实例 ──────────────────────────────────────────────────────────────

    /** 实例列表。 */
    fun listInstances(onResult: (Result<JSONArray>) -> Unit) = callArr("list_instances", null, onResult)

    /** 单个实例详情。 */
    fun getInstance(id: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_instance", JSONObject().put("id", id), onResult)

    /** 删除实例。 */
    fun deleteInstance(id: String, onResult: (Result<String>) -> Unit) =
        call("delete_instance", JSONObject().put("id", id), onResult)

    /** 实例分组。 */
    fun listInstanceGroups(onResult: (Result<JSONArray>) -> Unit) =
        callArr("list_instance_groups", null, onResult)

    /**
     * 创建实例。
     *
     * 参数形状对齐 `src-tauri/src/commands.rs::create_instance`：
     *   name / mc_version / loader / loader_version / java_dir / java_args /
     *   game_args / resolution / max_memory_mb / memory_mode / account_id / icon / group
     * Tauri 2 用 camelCase 传参，所以这里用 mcVersion / maxMemoryMb。
     */
    fun createInstance(payload: JSONObject, onResult: (Result<String>) -> Unit) =
        call("create_instance", payload, onResult)

    // ── 设置 ──────────────────────────────────────────────────────────────

    /** 读取设置。 */
    fun getSettings(onResult: (Result<JSONObject>) -> Unit) = callObj("get_settings", null, onResult)

    /**
     * 更新设置（只发改动的字段）。
     *
     * ⚠️⚠️ 必须**包一层 `patch`** —— Rust 侧签名是
     * `update_settings(patch: Value)`，Tauri 按参数名取值。
     *
     * 之前直接把 patch 对象当 payload 发（顶层就是 `{"theme_color":"#..."}`），
     * 后端取不到 `patch` 键，报：
     *   `invalid args \`patch\` for command \`update_settings\`:
     *    command update_settings missing required key patch`
     * 结果是**所有设置项都改不动**，而且只有 WARN 级日志，界面上看不出来
     *（乐观更新已经把 UI 改好了，用户以为成功了）。
     *
     * 命令名也注意：Android 后端是 `update_settings`（不是旧前端的 `set_settings`）。
     */
    fun updateSettings(patch: JSONObject, onResult: (Result<String>) -> Unit) =
        call("update_settings", JSONObject().put("patch", patch), onResult)

    // ── 账号 ──────────────────────────────────────────────────────────────

    /** 账号列表。 */
    fun listAccounts(onResult: (Result<JSONArray>) -> Unit) = callArr("list_accounts", null, onResult)

    /** 离线登录（新增离线账号）。 */
    fun addAccount(username: String, onResult: (Result<String>) -> Unit) =
        call("add_account", JSONObject().put("username", username), onResult)

    /**
     * 切换「当前账号」。
     *
     * ⚠️⚠️ 这个命令**必须**调，不能只改界面上的高亮。
     *
     * Rust 侧 `commands.rs::resolve_launch_account` 决定启动用哪个账号，
     * 优先级是：实例自己的 account_id → **settings 里的 `selected_account`** →
     * 账号列表第一个。也就是说：**只有 settings.selected_account 变了，
     * 启动游戏才会真的换账号**。
     *
     * 之前 Compose 侧切账号只改局部变量 `currentUuid`（全树零处写
     * `selected_account`），于是出现最恶劣的一种现象：
     * 账号页高亮条移动了、看起来切成功了，**点启动用的还是旧账号**。
     *
     * 命令名是 `select_account`（已核对 lib.rs 的 invoke_handler, line 146），
     * 签名：`pub async fn select_account(uuid: String) -> Result<(), String>`
     * —— 参数名就是 `uuid`（camelCase 一致），类型 String。
     */
    fun selectAccount(uuid: String, onResult: (Result<String>) -> Unit = {}) =
        call("select_account", JSONObject().put("uuid", uuid), onResult)

    /** 移除账号。 */
    fun removeAccount(uuid: String, onResult: (Result<String>) -> Unit) =
        call("remove_account", JSONObject().put("uuid", uuid), onResult)

    // ── 启动 / 游戏 ───────────────────────────────────────────────────────

    /**
     * 启动游戏。
     *
     * 后端命令名是 `launch_game`（旧前端 api.ts 把 `launch_instance` 映射过来）。
     *
     * ⚠️⚠️ 参数名**必须**和 Rust 签名逐字对齐，Rust 是按签名严格反序列化的：
     * 签名里没声明的字段会被**静默丢弃**（不报错），传错位置就等于没传。
     *
     * Rust 原文（`src-tauri/src/commands.rs:196-197`）：
     * ```rust
     * pub async fn launch_game(instance_id: String, world: Option<String>, server: Option<String>)
     *     -> Result<GameStatus, String>
     * ```
     *
     * 所以：
     *   - 参数是 `world` / `server`，**根本没有 `accountUuid`**。
     *     账号由后端自己 `resolve_launch_account` 决定
     *     （实例 account_id → settings.selected_account → 列表第一个），
     *     前端只能通过 `selectAccount()` 间接影响它。
     *   - `world` 要传存档名（quick-play）。
     *
     * 历史 bug：第二参曾传成 `accountUuid`，而调用方塞进去的其实是世界名 ——
     * 名字对不上，世界名被当成账号 uuid 静默丢弃，于是「进入世界」进不去。
     */
    fun launchGame(instanceId: String, world: String?, onResult: (Result<String>) -> Unit) {
        val p = JSONObject().put("instanceId", instanceId)
        if (!world.isNullOrBlank()) p.put("world", world)
        call("launch_game", p, onResult)
    }

    /** 强制关闭游戏。 */
    fun killGame(onResult: (Result<String>) -> Unit) = call("kill_game", null, onResult)

    /** 游戏是否在运行。 */
    fun isGameRunning(onResult: (Result<Boolean>) -> Unit) =
        call("is_game_running", null) { r -> onResult(r.mapCatching { it.trim() == "true" }) }

    // ── 新闻 ──────────────────────────────────────────────────────────────

    /** 官方新闻。 */
    fun fetchNews(onResult: (Result<JSONArray>) -> Unit) = callArr("fetch_news", null, onResult)

    // ── 任务 ──────────────────────────────────────────────────────────────

    /** 取消一个下载/安装任务。 */
    fun cancelInstall(taskId: Long, onResult: (Result<String>) -> Unit) =
        call("cancel_install", JSONObject().put("taskId", taskId), onResult)

    // ── 插件 ──────────────────────────────────────────────────────────────

    /** 已装插件列表。 */
    fun getPlugins(onResult: (Result<JSONArray>) -> Unit) = callArr("get_plugins", null, onResult)

    /** 插件初始化状态。 */
    fun getPluginSetupStatus(onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_plugin_setup_status", null, onResult)

    // ── 存储 / 统计 ───────────────────────────────────────────────────────

    /** 游玩时长统计。 */
    fun playtimeStats(onResult: (Result<JSONObject>) -> Unit) =
        callObj("playtime_stats", null, onResult)

    /** 存储占用统计。命令名是 `get_storage_stats`（已核对注册表）。 */
    fun storageStats(onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_storage_stats", null, onResult)

    // ── 多人 / 联机 ───────────────────────────────────────────────────────

    /** 本地服务器列表。 */
    fun listHostedServers(onResult: (Result<JSONArray>) -> Unit) =
        callArr("list_hosted_servers", null, onResult)

    // ── 实例设置（命令名核对自 Rust 的 lib.rs 注册表）──────────────────────
    //
    // ⚠️ 命令名是 **`update_instance`**，不是 `update_instance_settings`。
    //
    // 实测发现（2026-10-13）：旧前端的 `api.ts` 里写的是
    // `updateInstance: (patch) => invoke("update_instance_settings", { patch })`
    // —— **Rust 里根本没有这条命令**（注册的是 `update_instance`）。
    //
    // 也就是说旧界面的「实例设置 → 保存」是**静默失效**的
    // （Tauri 找不到命令会 reject，但旧代码把它吞在 catch 里）。
    // Compose 侧用正确的名字。
    //
    // Rust 接受的 patch 字段（name / alias / icon / group / accountId /
    // java_path / jvm_args / game_args / max_memory_mb / memory_mode /
    // resolution / renderer / check_files_on_launch）。
    fun updateInstance(patch: JSONObject, onResult: (Result<JSONObject>) -> Unit) =
        callObj("update_instance", JSONObject().put("patch", patch), onResult)
    // ── 插件（补充命令；getPlugins/getPluginSetupStatus 在文件上方已定义）──────

    /** 刷新插件清单（联网）。 */
    fun refreshPluginManifest(onResult: (Result<JSONArray>) -> Unit) =
        callArr("refresh_plugin_manifest", null, onResult)

    /** 安装推荐插件。 */
    fun installRecommendedPlugins(onResult: (Result<String>) -> Unit = {}) =
        call("install_recommended_plugins", null, onResult)

    // ── 账号（命令名核对自 src/api.ts 的 accounts 段）────────────────────────
    //
    // ⚠️ 注意两个「看起来该叫别的名字」的命令：
    //   loginOffline  → 命令是 **add_account**（不是 login_offline）
    //   logoutAccount → 命令是 **remove_account**（不是 logout_account）
    // 这两个容易按函数名去猜命令名，实际不一样。

    /** 离线登录（本质是「添加一个离线账号」）。 */
    fun loginOffline(username: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("add_account", JSONObject().put("username", username), onResult)

    /**
     * 开始正版（微软）登录：拿设备码，让用户去浏览器输入。
     *
     * 返回 `{ userCode, verificationUri, expiresIn }`。
     * 这是**设备码流程**（device code flow）—— 比在手机上弹网页登录更稳，
     * 而且不需要内嵌 WebView。
     */
    fun loginMsStart(onResult: (Result<JSONObject>) -> Unit) =
        callObj("login_ms_start", null, onResult)

    /**
     * 轮询正版登录结果。
     *
     * 用户在浏览器里完成授权后，这条才会成功返回账号。
     * 在失败返回里区分「还没授权」与「真失败」由调用方判断
     * （通常靠错误文本或反复轮询）。
     */
    fun loginMsPoll(onResult: (Result<JSONObject>) -> Unit) =
        callObj("login_ms_poll", null, onResult)

    /** 退出登录 / 删除账号。命令名是 `remove_account`，不是 `logout_account`。 */
    fun logoutAccount(uuid: String, onResult: (Result<String>) -> Unit = {}) =
        call("remove_account", JSONObject().put("uuid", uuid), onResult)

    // ── 覆盖审计补齐（第三批）──────────────────────────────────────────────
    //
    // 这一批是**逐条对照审计**发现的：把前端用到的 178 个 API 函数
    // 与 Rust 实际注册的 153 个命令做交集，再减去 Kotlin 已覆盖的，
    // 得到 45 个「Rust 有、前端在用、但 Kotlin 没有」的真缺口。
    //
    // 审计方法比"搜一搜"可靠 —— 它同时排除了两类假缺口：
    //   - 前端函数名与命令名不一致的（如 launchInstance → launch_game，早已覆盖）
    //   - 命令在 Rust 侧根本不存在的（如 set_settings，真名是 update_settings）

    // ── 账号/实例分组 ─────────────────────────────────────────────────────

    fun createInstanceGroup(
        name: String,
        color: String?,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "create_instance_group",
        JSONObject().put("name", name).put("color", color ?: JSONObject.NULL),
        onResult,
    )

    fun deleteInstanceGroup(id: String, onResult: (Result<String>) -> Unit = {}) =
        call("delete_instance_group", JSONObject().put("id", id), onResult)

    fun renameInstanceGroup(
        id: String,
        name: String,
        color: String?,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "rename_instance_group",
        JSONObject().put("id", id).put("name", name).put("color", color ?: JSONObject.NULL),
        onResult,
    )

    /** 重排分组顺序（ids 按目标顺序）。 */
    fun reorderInstanceGroups(ids: List<String>, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("reorder_instance_groups", JSONObject().put("ids", JSONArray(ids)), onResult)

    /** 固定项（首页置顶的实例）。 */
    fun getPins(onResult: (Result<JSONArray>) -> Unit) = callArr("get_pins", null, onResult)

    fun setPins(items: JSONArray, onResult: (Result<String>) -> Unit = {}) =
        call("set_pins", JSONObject().put("items", items), onResult)

    // ── 加载器 / 渲染器 ───────────────────────────────────────────────────

    /** 某个加载器在某个 MC 版本下有哪些版本可选。 */
    fun getLoaderVersions(
        loader: String,
        mcVersion: String,
        onResult: (Result<JSONArray>) -> Unit,
    ) = callArr(
        "get_loader_versions",
        JSONObject().put("loader", loader).put("mcVersion", mcVersion),
        onResult,
    )

    /** 自动探测建议的内存分配（按设备内存算）。 */
    fun autoDetectMemory(onResult: (Result<JSONObject>) -> Unit) =
        callObj("auto_detect_memory", null, onResult)

    /** 检查某实例的渲染器健康度。 */
    fun checkRendererHealth(instanceId: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("check_renderer_health", JSONObject().put("instanceId", instanceId), onResult)

    /** 检查最新渲染器设置的健康度（不指定实例）。 */
    fun checkRendererHealthLatest(onResult: (Result<JSONObject>) -> Unit) =
        callObj("check_renderer_health_latest", null, onResult)


}

/**
 * 陶瓦隧道的状态快照（`/state` 的解析结果）。
 *
 * 隧道返回形如 `{"index":1,"state":"host-scanning","room":"ABC123"}`。
 * `state` 的取值包括 `off`（隧道没在跑）、`host-scanning`（已开房、等游戏里
 * 开放局域网世界）、`hosting`（房间已建立）、`guesting`（已加入别人的房间）。
 */
data class TerracottaState(
val ok: Boolean,
val state: String,
val room: String,
val error: String,
) {
/** 隧道进程是否在跑。 */
val running: Boolean get() = state.isNotEmpty() && state != "off"
}


/**
 * 把 Rust 侧返回的**英文技术错误**转成用户能看懂的中文。
 *
 * ⚠️ 为什么需要这个（2026-10-17 真机实测）：
 *
 * Rust 的错误大多是 `anyhow::Context` 的英文串，例如断网时内容中心直接显示：
 *
 *     搜索失败: "Failed to search Modrinth"
 *
 * 用户看到这行会困惑：是我搜的词有问题？还是软件坏了？
 * 而这实际上是**网络不通** —— 一个用户自己能解决的问题。
 *
 * 约束：Rust 侧零改动（本项目的硬要求），所以只能在 UI 层翻译。
 * 这里按**关键词匹配**（不区分大小写）：命中就给一句能指导操作的中文，
 * 没命中就保留原文（避免把真正的未知错误掩盖成含糊的套话）。
 *
 * 注意：**不要**把没有把握匹配的错误也改写成套话 —— 那会让排障更难。
 */
/**
 * 读一个**可缺省**的字符串字段，缺省/JSON null 都返回 null。
 *
 * ⚠️ 为什么需要（2026-10-26 真机实测）：Rust 的 `Option<String>` 序列化成
 * JSON `null`，而 `org.json` 的 `optString(key)` 对 JSON null 返回的是**字面量
 * `"null"`**（不是空串）—— 于是 `optString("error").takeIf { it.isNotBlank() }`
 * 会把 `"null"` 当成真实错误内容显示给用户（首启弹窗曾因此把副标题渲染成 null）。
 */
fun JSONObject.optText(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

/**
 * 同 [friendlyError]，但**走 Android 字符串资源**（2026-10-17 i18n）。
 *
 * ⚠️ 为什么保留旧的无 Context 版本：它有 27 处调用点，其中不少在
 * 纯数据层 / 顶层函数里拿不到 Context。旧版继续可用（只是文案硬编码），
 * 能拿到 Context 的地方改成调这个重载。
 */
fun friendlyError(context: android.content.Context, raw: String?): String {
val s = raw?.trim().orEmpty()
if (s.isEmpty()) return context.getString(R.string.error_unknown)
val low = s.lowercase()
return when {
    "failed to search modrinth" in low ||
        "failed to search curseforge" in low ||
        "error sending request" in low ||
        "dns error" in low ||
        "connection refused" in low ||
        "connection reset" in low ||
        "timed out" in low || "timeout" in low ||
        "network is unreachable" in low ||
        "no address associated" in low ->
        context.getString(R.string.error_network)

    "failed to fetch" in low || "status 5" in low ->
        context.getString(R.string.error_server_unavailable)
    "status 404" in low ->
        context.getString(R.string.error_not_found)
    "status 429" in low || "rate limit" in low ->
        context.getString(R.string.error_rate_limited)

    "permission denied" in low || "eacces" in low ->
        context.getString(R.string.error_permission_denied)
    "no space left" in low || "enospc" in low ->
        context.getString(R.string.error_no_space)
    "no such file" in low || "not found" in low ->
        context.getString(R.string.error_file_missing)

    "missing required key" in low || "invalid args" in low ->
        context.getString(R.string.error_invalid_call)

    else -> s
}
}

