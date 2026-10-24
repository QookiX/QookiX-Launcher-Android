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
         * 已迁移到 **JNI 直连**（不经 WebView）的命令白名单。
         *
         * 迁移原则：先挑「纯查询、无副作用、调用频繁」的命令做试点，
         * 每迁一个就在 Rust `commands::native_invoke` 里补一条 match。
         * 全部迁完后，`bridge.invoke` 就可以连同 WebView 一起删掉。
         */
        private val NATIVE_COMMANDS = setOf(
            // 阶段 1（试点）
            "list_instances",
            "get_settings",
            "fetch_news",
            "list_content",
            // 阶段 3 第一批（只读）
            "get_instance",
            "list_accounts",
            "get_plugins",
            "get_installed_mods",
            "mc_wiki_url",
            "get_pins",
            "list_instance_groups",
            "get_storage_stats",
            // 阶段 3 第二批（只读）
            "get_version_list",
            "get_loader_versions",
            "list_crash_logs",
            "list_skins",
            "list_servers",
            "list_world_backups",
            "playtime_stats",
            "list_instance_files",
            "list_instance_dir",
            "read_instance_log",
            // 阶段 3 第三批（写操作）
            "delete_instance",
            "kill_game",
            "cancel_install",
            "update_settings",
            "set_pins",
            "set_orientation",
            "check_renderer_health",
            // 阶段 3 第四批（生命周期 / 内容 / 文件）
            "launch_game",
            "import_modpack",
            "install_game",
            "uninstall_content",
            "identify_content",
            "toggle_content_enabled",
            "read_instance_file",
            "write_instance_file",
            "delete_instance_path",
            "rename_instance_path",
            // 阶段 3 第五批（内容中心 / 版本 / 皮肤）
            "get_version_manifest",
            "get_version_info",
            "curseforge_categories",
            "project_info",
            "project_versions",
            "project_dependencies",
            "fetch_image_data_url",
            "fetch_player_skin",
            "fetch_player_capes",
            // 阶段 3 第六批（搜索 / 杂项）
            "browse",
            "list_mirrors",
            "log_debug",
            "get_pojav_prefs",
            "set_pojav_prefs",
            "read_clipboard",
            "write_clipboard",
            "clear_cache",
            "detect_system_proxy",
            // 阶段 3 第七批（插件系统）
            "get_plugin_setup_status",
            "install_recommended_plugins",
            "dismiss_plugin_setup",
            "refresh_plugin_manifest",
            "install_plugin",
            "install_plugin_from_file",
            "uninstall_plugin",
            "set_plugin_enabled",
            "get_plugin_manifest_url",
            "set_plugin_manifest_url",
        )
    }

    // ── 通用调用工具 ──────────────────────────────────────────────────────

    /**
     * 调用一个返回 JSON 的命令。
     *
     * @param cmd 命令名（与 Rust `#[tauri::command]` 函数名一致）
     * @param payload 参数对象；null 表示无参
     */
    private fun call(
        cmd: String,
        payload: JSONObject? = null,
        onResult: (Result<String>) -> Unit,
    ) {
        val json = payload?.toString() ?: "{}"

        // ⚠️ 阶段 1（2026-10-24）：白名单内的命令**不再经过隐藏 WebView**。
        //
        // 原来的 `bridge.invoke()` 依赖 WebView 里的 `index.html` 把消息送进 Rust，
        // 那个网页/渲染进程一有问题，就是「桥尚未就绪 → 点了没反应」（真机反复遇到）。
        // 这里改走 JNI 直连 `TauriBridge.nativeInvoke` → Rust `commands::native_invoke`，
        // **完全不碰 WebView**。返回值形状与 Tauri 保持一致（裸 JSON），
        // 所以上层调用方一行都不用改。
        if (cmd in NATIVE_COMMANDS) {
            Log.i(TAG, "$cmd → JNI 直连（不经 WebView）")
            Thread {
                val r = runCatching {
                    val raw = com.zhayi.qookix.tauri.TauriBridge.nativeInvoke(cmd, json)
                    val o = JSONObject(raw)
                    if (o.optBoolean("ok")) {
                        o.get("data").toString()
                    } else {
                        throw IllegalStateException(o.optString("error", "未知错误"))
                    }
                }
                if (r.isFailure) {
                    Log.w(TAG, "$cmd 失败（native）: ${r.exceptionOrNull()?.message}")
                }
                onResult(r)
            }.start()
            return
        }

        bridge.invoke(cmd, json) { r ->
            if (r.isFailure) {
                Log.w(TAG, "$cmd 失败: ${r.exceptionOrNull()?.message}")
            }
            // ⚠️ Rust 返回 `Result<String>` 时，Tauri 会把它序列化成
            // **带引号的 JSON 字符串**（`"文本"`），而不是裸文本。
            //
            // 实测（2026-10-13）：日志 tab 显示的提示带引号
            //   "暂无日志：这个实例还没有启动过，或日志文件已被清理。"
            // 就是这里没拆引号导致的。
            //
            // 在这里统一拆掉 —— 放在 call() 而不是各个调用点，
            // 是因为所有「返回字符串」的命令都走这条路。
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
    private fun callObj(
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
    private fun callArr(
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

    // ── 内容中心（Modrinth / CurseForge）─────────────────────────────────

    /**
     * 搜索项目（内容中心）。
     *
     * ⚠️ 两条已核实的约定：
     *   1. 命令名是 `browse`（不是 `search_projects`）—— 核对自 `lib.rs` 的 invoke_handler
     *   2. 参数用 **camelCase**（Tauri 2 默认）—— 对照 `src/api.ts` 的实际调用：
     *      `{ provider, query, projectType, category, page, gameVersion, loader, sort, pageSize }`
     *      Rust 侧签名是 snake_case（`project_type`），转换由 Tauri 自动完成。
     *
     * @param provider "modrinth" 或 "curseforge"
     * @param projectType mod / modpack / resourcepack / shader
     * @param sort downloads / relevance / updated / newest
     */
    fun browse(
        provider: String,
        query: String,
        projectType: String,
        category: String = "",
        page: Int = 0,
        gameVersion: String = "",
        loader: String = "",
        sort: String = "downloads",
        pageSize: Int = 20,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        val p = JSONObject()
            .put("provider", provider)
            .put("query", query)
            .put("projectType", projectType)
            .put("category", category)
            .put("page", page)
            .put("gameVersion", gameVersion)
            .put("loader", loader)
            .put("sort", sort)
            .put("pageSize", pageSize)
        callObj("browse", p, onResult)
    }

    /** 某个项目的可用版本（安装时要选）。 */
    fun projectVersions(
        provider: String,
        projectId: String,
        mcVersion: String = "",
        loader: String = "",
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        val p = JSONObject()
            .put("provider", provider)
            .put("projectId", projectId)
            .put("mcVersion", mcVersion)
            .put("loader", loader)
        callObj("project_versions", p, onResult)
    }

    /** 取项目完整信息（描述/正文/作者/下载量等）。 */
    fun projectInfo(provider: String, projectId: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("project_info", JSONObject().put("provider", provider).put("projectId", projectId), onResult)

    /** CurseForge 分类列表（筛选用）。 */
    fun curseforgeCategories(onResult: (Result<JSONArray>) -> Unit) =
        callArr("curseforge_categories", null, onResult)

    /** 预估某 MC 版本原版下载体积（返回 {totalSize, fileCount}）。 */
    fun estimateDownload(mcVersion: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("estimate_download", JSONObject().put("mcVersion", mcVersion), onResult)

    /** 预估导入模组包体积。 */
    fun estimateImport(source: String, rawIds: List<String>, onResult: (Result<JSONObject>) -> Unit) =
        callObj("estimate_import", JSONObject().put("source", source).put("rawIds", JSONArray().also { rawIds.forEach { id -> it.put(id) } }), onResult)

    /**
     * 版本清单（Minecraft 各版本列表）。
     *
     * ⚠️ 返回的是**对象**不是数组：`{ versions: [...], latest: {...} }`
     * —— 见 `commands.rs::get_version_manifest`。
     */
    fun getVersionManifest(onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_version_manifest", null, onResult)


    /**
     * Paper 支持的所有 MC 版本（已按版本号降序，已剔除 rc/pre）。
     *
     * ⚠️ 为什么要这个而不是复用 [getVersionManifest]：
     * Paper **不支持所有 MC 版本**。比如 1.8 那条线只有 `1.8.8`（`1.8.9` 不存在，
     * 但两者客户端协议相同，1.8.9 客户端能进 1.8.8 服务器）。
     * 拿 1.8.9 去问 builds 接口只会 404，所以创建服务器时必须用这份清单限制选择。
     *
     * 见 Rust `servers.rs::list_paper_versions`（已注册在 `lib.rs:94`）。
     */
    fun listPaperVersions(onResult: (Result<List<String>>) -> Unit) =
        callArr("list_paper_versions", null) { r ->
            onResult(
                r.map { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i).takeIf { it.isNotBlank() }
                    }
                },
            )
        }
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

    // ── 服务器详情（命令名与参数逐个核对自 src/api.ts，不是猜的）────────────

    /** 服务器属性（server.properties）。 */
    /**
     * 取一台托管服务器的完整配置（`get_hosted_server`）。
     *
     * 与 [getHostedServerProperties] 的区别：那个返回的是 `server.properties`
     * 里的游戏内配置（motd/gamemode/…），这个返回启动器自己的 `ServerConfig`
     *（name / port / eula / maxMemoryMb / core …）。EULA 同意状态只能从这里读。
     */
    fun getHostedServer(id: String, onResult: (Result<JSONObject>) -> Unit = {}) =
        callObj("get_hosted_server", JSONObject().put("id", id), onResult)

    fun getHostedServerProperties(id: String, onResult: (Result<JSONObject>) -> Unit) =
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
    fun setHostedServerProperties(
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
    fun hostedServerAddress(id: String, onResult: (Result<String>) -> Unit) =
        callRaw("hosted_server_address", JSONObject().put("id", id), onResult)

    /** 服务端核心是否已安装。 */
    fun hostedServerCoreInstalled(id: String, onResult: (Result<Boolean>) -> Unit) =
        call("hosted_server_core_installed", JSONObject().put("id", id)) { r ->
            onResult(r.mapCatching { it.trim() == "true" })
        }

    /** 服务器运行状态。 */
    fun hostedServerRuntime(id: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("hosted_server_runtime", JSONObject().put("id", id), onResult)

    /** 安装服务端核心（下载 Paper 等）。 */
    fun installHostedServerCore(id: String, onResult: (Result<String>) -> Unit = {}) =
        call("install_hosted_server_core", JSONObject().put("id", id), onResult)

    /** 在系统文件管理器里打开服务器目录。 */
    fun openHostedServerDirectory(id: String, onResult: (Result<String>) -> Unit = {}) =
        call("open_hosted_server_directory", JSONObject().put("id", id), onResult)

    /** 读服务器日志。 */
    fun readHostedServerLog(id: String, onResult: (Result<String>) -> Unit) =
        call("read_hosted_server_log", JSONObject().put("id", id), onResult)

    /** 向服务器控制台发一条命令（`list` / `op <玩家>` / `stop` …）。 */
    fun serverConsoleCommand(id: String, command: String, onResult: (Result<String>) -> Unit) =
        call("server_console_command", JSONObject().put("id", id).put("command", command), onResult)

    /** 设备可用内存（MB）—— 创建服务器时算建议值用。 */
    fun deviceAvailableMemoryMb(onResult: (Result<Long>) -> Unit) =
        call("device_available_memory_mb", null) { r ->
            onResult(r.mapCatching { it.trim().trim('"').toLongOrNull() ?: 0L })
        }

    /**
     * 请求加入电池优化白名单。
     *
     * 长开服必须申请 —— 否则 ColorOS 等 ROM 会在息屏后掐掉后台服务，
     * 表现为「开服玩一会儿，服自己没了」。
     */
    fun requestBatteryUnrestricted(onResult: (Result<String>) -> Unit = {}) =
        call("request_battery_unrestricted", null, onResult)

    /** 服务器内存建议（返回 {minMemoryMb, maxMemoryMb}）。 */
    fun suggestServerMemory(onResult: (Result<JSONObject>) -> Unit) =
        callObj("suggest_server_memory", null, onResult)

    // ── 文件管理（实例 + 服务器，命令名核对自 src/api.ts）────────────────────

    /**
     * 列实例目录。
     *
     * ⚠️ 返回的是对象 `{"rel":..., "entries":[...]}`（commands.rs:1229），
     * 不是数组 —— 用 callArr 会抛 JSONException。这里统一解出 `entries`。
     */
    fun listInstanceDir(instanceId: String, rel: String, onResult: (Result<JSONArray>) -> Unit) =
        callObj("list_instance_dir", JSONObject().put("instanceId", instanceId).put("rel", rel)) { r ->
            onResult(r.mapCatching { it.optJSONArray("entries") ?: it.optJSONArray("items") ?: JSONArray() })
        }

    /** 读实例文件。 */
    fun readInstanceFile(instanceId: String, rel: String, onResult: (Result<String>) -> Unit) =
        call("read_instance_file", JSONObject().put("instanceId", instanceId).put("rel", rel), onResult)

    /** 写实例文件。 */
    fun writeInstanceFile(
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
    fun createInstanceEntry(
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
    fun renameInstancePath(
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
    fun deleteInstancePath(instanceId: String, rel: String, onResult: (Result<String>) -> Unit = {}) =
        call("delete_instance_path", JSONObject().put("instanceId", instanceId).put("rel", rel), onResult)

    /**
     * 列服务器目录。
     *
     * ⚠️ 返回的是对象 `{"rel":..., "entries":[...]}`（server_files.rs:32），
     * 不是数组 —— 用 callArr 会抛 JSONException。这里统一解出 `entries`。
     */
    fun listHostedServerDir(id: String, rel: String, onResult: (Result<JSONArray>) -> Unit) =
        callObj("list_hosted_server_dir", JSONObject().put("id", id).put("rel", rel)) { r ->
            onResult(r.mapCatching { it.optJSONArray("entries") ?: it.optJSONArray("items") ?: JSONArray() })
        }

    /** 读服务器文件。 */
    fun readHostedServerFile(id: String, rel: String, onResult: (Result<String>) -> Unit) =
        call("read_hosted_server_file", JSONObject().put("id", id).put("rel", rel), onResult)

    /** 写服务器文件。 */
    fun writeHostedServerFile(
        id: String,
        rel: String,
        content: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "write_hosted_server_file",
        JSONObject().put("id", id).put("rel", rel).put("content", content),
        onResult,
    )

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
    // ── 实例详情的各个 tab（命令名逐个核对自 src/api.ts）────────────────────

    // 内容管理（模组/资源包/光影）
    /**
     * 列出某实例某分类下已装的内容。
     *
     * ⚠️ 三个易错点（都核对过 Rust 源码）：
     *   1. **必须带 kind**（mod / resourcepack / shader）—— 之前只传 instanceId，
     *      后端收不到 kind 就拿不到目录，表现为「已装模组但列表是空的」；
     *   2. 返回的是 **Value（对象）** `{items: [...], onDisk: [...]}`，不是数组；
     *   3. 所以要用 callObj 而不是 callArr。
     */
    fun listContent(instanceId: String, kind: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj(
            "list_content",
            JSONObject().put("instanceId", instanceId).put("kind", kind),
            onResult,
        )
    /**
     * 启用/禁用某个内容项。
     *
     * ⚠️⚠️ Rust 签名要 **三个** 参数：`instance_id` / `kind` / `filename`
     *（commands.rs:1118-1123）。以前只发 `instanceId` + `rel`：
     *   · 缺 `kind`  → `missing required key kind`
     *   · `rel` 应叫 `filename` → `missing required key filename`
     * → 启用/禁用模组**永久失败**。
     *
     * `kind` 取值：`mods` / `resourcepacks` / `shaders`（与 `list_content` 一致）。
     */
    fun toggleContentEnabled(
        instanceId: String,
        kind: String,
        filename: String,
        enabled: Boolean,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "toggle_content_enabled",
        JSONObject()
            .put("instanceId", instanceId)
            .put("kind", kind)
            .put("filename", filename)
            .put("enabled", enabled),
        onResult,
    )

    /**
     * 卸载某个内容项。
     *
     * ⚠️ 同 [toggleContentEnabled]：Rust 要 `kind` + `filename`
     *（commands.rs:1099），以前发 `rel` 且没有 `kind` → 卸载永久失败。
     */
    fun uninstallContent(
        instanceId: String,
        kind: String,
        filename: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "uninstall_content",
        JSONObject().put("instanceId", instanceId).put("kind", kind).put("filename", filename),
        onResult,
    )

    /**
     * 检查已装内容的可用更新。
     *
     * ⚠️ Rust 要 `kind`（commands.rs:1079），以前漏传 → 检查更新永久失败。
     */
    fun checkUpdates(instanceId: String, kind: String, onResult: (Result<JSONArray>) -> Unit) =
        callArr("check_updates", JSONObject().put("instanceId", instanceId).put("kind", kind), onResult)

    /**
     * 应用更新（把某个内容项升级到新版）。
     *
     * ⚠️⚠️ Rust 签名要 **6 个参数**（commands.rs:1079-1086）：
     *   `apply_update(instance_id, kind, old_filename, provider, project_id, new_version_id)`
     *
     * 以前只传 `instanceId` + `rel` —— 缺少 kind/provider/project_id/new_version_id，
     * 且 `rel` 的键名应为 `oldFilename`（不是 `rel`）。
     * 结果是「点更新」必然失败，而错误只写一行 Log.w，UI 上像是没反应。
     *
     * 这 6 个值都能从「检查更新」的结果里拿到（见 browse.rs:1328-1337）：
     *   filename / projectId / latestVersionId / kind / provider
     */
    fun applyUpdate(
        instanceId: String,
        kind: String,
        oldFilename: String,
        provider: String,
        projectId: String,
        newVersionId: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "apply_update",
        JSONObject()
            .put("instanceId", instanceId)
            .put("kind", kind)
            .put("oldFilename", oldFilename)
            .put("provider", provider)
            .put("projectId", projectId)
            .put("newVersionId", newVersionId),
        onResult,
    )

    /** 识别内容（判断某个 jar 是哪个 Modrinth/CurseForge 项目）。 */
    /**
     * 异步识别「未识别的内容」（sha1 哈希查找 → 回退到名称搜索）。
     *
     * ⚠️ 两个易错点（都核对过 Rust 源码）：
     *   1. 签名是 `(instance_id, kind)` —— **必须带 kind**（mod/resourcepack/shader）；
     *   2. 返回 `Result<(), String>`（**空**，不是数组）—— 之前用 callArr 是错的。
     *
     * 识别结果**不通过返回值**给出，而是逐个文件 emit `content::identified` 事件
     * （见 browse.rs）。所以调用方要监听那个事件才能看到界面变化 ——
     * 不监听的话「识别」按钮看起来完全没效果（旧注释原话）。
     */
    fun identifyContent(instanceId: String, kind: String, onResult: (Result<String>) -> Unit = {}) =
        call(
            "identify_content",
            JSONObject().put("instanceId", instanceId).put("kind", kind),
            onResult,
        )

    // 存档与世界备份
    //
    // ⚠️ 注意：这几个命令都要 **world** 参数（不是只给 instanceId）
    // —— 核对 `commands.rs::list_world_backups(instance_id, world)` 与
    // `api.ts` 的实际调用 `listWorldBackups(instanceId, world)`。
    fun listWorldBackups(instanceId: String, world: String, onResult: (Result<JSONArray>) -> Unit) =
        callArr(
            "list_world_backups",
            JSONObject().put("instanceId", instanceId).put("world", world),
            onResult,
        )

    /** 给某个存档创建备份。 */
    fun createWorldBackup(
        instanceId: String,
        world: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "create_world_backup",
        JSONObject().put("instanceId", instanceId).put("world", world),
        onResult,
    )

    /** 从备份恢复存档（需要 world + filename）。 */
    fun restoreWorldBackup(
        instanceId: String,
        world: String,
        filename: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "restore_world_backup",
        JSONObject()
            .put("instanceId", instanceId)
            .put("world", world)
            .put("filename", filename),
        onResult,
    )

    /**
     * 列实例下的**特定子目录**（`saves` / `mods` / `resourcepacks` …）。
     *
     * ⚠️⚠️ 两个坑（2026-10-17 修）：
     *  1. **参数名是 `sub`，不是 `rel`**。Rust 签名
     *     `list_instance_files(instance_id: String, sub: String)`（commands.rs:1170），
     *     发 `rel` 会报 `missing required key sub`。
     *  2. **返回的是对象** `{"files":[...]}`（commands.rs:1176），不是数组。
     *     用 callArr 会抛 JSONException。
     *
     * 另注：`sub` 必须在 Rust 的 `SUBFOLDERS` 白名单里，否则报「非法目录」。
     */
    fun listInstanceFiles(instanceId: String, sub: String = "", onResult: (Result<JSONArray>) -> Unit) =
        callObj("list_instance_files", JSONObject().put("instanceId", instanceId).put("sub", sub)) { r ->
            onResult(r.mapCatching { it.optJSONArray("files") ?: JSONArray() })
        }

    fun deleteWorldBackup(
        instanceId: String,
        world: String,
        filename: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "delete_world_backup",
        JSONObject()
            .put("instanceId", instanceId)
            .put("world", world)
            .put("filename", filename),
        onResult,
    )


    // 按键/控制布局
    fun listControlLayouts(onResult: (Result<JSONArray>) -> Unit) =
        callArr("list_control_layouts", null, onResult)

    fun setCurrentControlLayout(name: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("set_current_control_layout", JSONObject().put("name", name), onResult)

    /**
     * 重命名布局。
     *
     * ⚠️ Rust 签名是 `rename_control_layout(name, new_name)`（controls.rs:370-373）。
     * 以前发的是 `from` / `to` —— **两个键名全错**，报
     * `missing required key name` → 重命名永久失败。
     */
    fun renameControlLayout(name: String, newName: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr(
            "rename_control_layout",
            JSONObject().put("name", name).put("newName", newName),
            onResult,
        )

    fun deleteControlLayout(name: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("delete_control_layout", JSONObject().put("name", name), onResult)

    /**
     * 复制布局到新名字。
     *
     * ⚠️ Rust 签名是 `duplicate_control_layout(name, new_name)`（controls.rs:350-353），
     * **必须两个参数**。以前只发 `name`，报
     * `missing required key newName` → 复制功能永久失败。
     */
    fun duplicateControlLayout(name: String, newName: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr(
            "duplicate_control_layout",
            JSONObject().put("name", name).put("newName", newName),
            onResult,
        )
    fun exportControlLayout(name: String, onResult: (Result<String>) -> Unit = {}) =
        call("export_control_layout", JSONObject().put("name", name), onResult)

    /**
     * 导入控制布局（弹系统文件选择器）。
     *
     * ⚠️ 命令名是 `pick_control_layout`，**不是** `import_control_layout`
     * —— 我一开始按直觉写了后者，编译期才发现没有。
     * 核对自 `src/api.ts`：`pickControlLayout → pick_control_layout ()`。
     *
     * 这条命令自己会回调宿主弹选择器，所以调用方**不需要**先弹文件选择。
     */
    fun pickControlLayout(onResult: (Result<String>) -> Unit = {}) =
        call("pick_control_layout", null, onResult)

    /** 取走导入结果（若 Rust 侧是两步式）。 */
    fun takeControlImport(onResult: (Result<String>) -> Unit = {}) =
        call("take_control_import", null, onResult)

    /** 按路径导入控制布局（已知路径时用）。 */
    fun importControlLayoutByPath(path: String, onResult: (Result<String>) -> Unit = {}) =
        call("import_control_layout_by_path", JSONObject().put("path", path), onResult)

    /** 打开 Pojav 的控制布局编辑器（原生 Activity）。 */
    fun openControlLayoutEditor(
        layout: String?,
        preview: Boolean = false,
        saveAs: String? = null,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "open_control_layout_editor",
        JSONObject()
            .put("layout", layout ?: "")
            .put("preview", preview)
            .put("saveAs", saveAs ?: ""),
        onResult,
    )

    // 整合包导出
    fun planModpackExport(
        instanceId: String,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj("plan_modpack_export", JSONObject().put("instanceId", instanceId), onResult)

    /**
     * 把实例导出成 `.mrpack` 并调起系统分享。
     *
     * ⚠️ Rust 返回 `ExportResult` **对象**（path/file_name/linked/bundled/bytes），
     * 不是字符串 —— 之前用 call 收，类型不匹配。
     */
    fun exportModpack(
        instanceId: String,
        options: JSONObject,
        onResult: (Result<JSONObject>) -> Unit = {},
    ) = callObj(
        "export_modpack",
        JSONObject().put("instanceId", instanceId).put("options", options),
        onResult,
    )

    // ── 皮肤（命令名核对自 src/api.ts）──────────────────────────────────────

    /** 本地皮肤库列表（`Vec<SkinEntry>`：name/filename/path/size/modified）。 */
    fun listSkins(onResult: (Result<JSONArray>) -> Unit) =
        callArr("list_skins", null, onResult)

    /**
     * 读皮肤图片数据。
     *
     * ⚠️ 返回的是 **data URL**（`data:image/png;base64,...`），不是路径。
     * 这对 Compose 反而方便 —— Coil 能直接吃 data URL，
     * 不需要像旧前端那样先 `convertFileSrc` 再走 asset 协议。
     */
    fun readSkinDataUrl(filename: String, onResult: (Result<String>) -> Unit) =
        call("read_skin_data_url", JSONObject().put("filename", filename), onResult)

    /** 从 data URL 保存为新皮肤。 */
    fun saveSkinFromData(
        name: String,
        dataUrl: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "save_skin_from_data",
        JSONObject().put("name", name).put("data", dataUrl),
        onResult,
    )

    /** 读某个账号的离线皮肤。 */
    fun getOfflineSkin(uuid: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_offline_skin", JSONObject().put("uuid", uuid), onResult)

    /**
     * 给离线账号应用皮肤。
     *
     * ⚠️⚠️ Rust 签名要 **3 个参数**（commands.rs:996-1000）：
     *   `apply_skin_offline(skin_data, variant, uuid)`
     *
     *   - `skin_data`：皮肤的 **base64 data URL**（`data:image/png;base64,...`）。
     *     Rust 会剥掉前缀、解码、校验 PNG magic（skins.rs:445-449）。
     *   - `variant`：`slim` / `classic`（决定模型是纤细还是经典）。
     *   - `uuid`：账号 uuid。
     *
     * 以前这里只传 `uuid` + `filename`（文件路径）—— 键名全错，
     * 报 `missing required key skinData`，皮肤**永远应用不上**，而错误只有一行 Log.w。
     *
     * 注意：`filename` 不是皮肤数据。要先用 [readSkinDataUrl] 读出 data URL 再传。
     */
    fun applySkinOffline(
        skinData: String,
        variant: String,
        uuid: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "apply_skin_offline",
        JSONObject()
            .put("skinData", skinData)
            .put("variant", variant)
            .put("uuid", uuid),
        onResult,
    )

    /**
     * 给正版账号上传皮肤。
     *
     * ⚠️⚠️ Rust 签名要 3 个参数（commands.rs:979-985）：
     *   `apply_skin_to_account(account_uuid, skin_data, variant)`
     *
     *   - `skin_data`：皮肤 base64 data URL（`data:image/png;base64,...`）。
     *     Rust 剥前缀解码并校验 PNG magic（skins.rs:412-416）。
     *   - `variant`：`slim` / `classic`。
     *   - `account_uuid`：正版账号 uuid（离线账号会被 Rust 拒绝：skins.rs:407-409）。
     *
     * 以前传的是 `uuid` + `filename` + `slim` —— 键名全错，
     * 报 `missing required key accountUuid/skinData/variant`，上传永远失败。
     *
     * 与 [applySkinOffline] 同源：`filename` 不是皮肤数据，
     * 要先用 [readSkinDataUrl] 读出 data URL 再传。
     */
    fun applySkinToAccount(
        accountUuid: String,
        skinData: String,
        variant: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "apply_skin_to_account",
        JSONObject()
            .put("accountUuid", accountUuid)
            .put("skinData", skinData)
            .put("variant", variant),
        onResult,
    )

    /** 拉取正版账号的皮肤（联网）。 */
    /**
     * 按**正版玩家名**从 Mojang 拉皮肤（返回 data_url / model / cape_data_url）。
     *
     * ⚠️ Rust 侧参数名是 `username`（玩家名），不是 `uuid` ——
     * 之前传 uuid 会报 `invalid args \`username\` for command`（真机实测）。
     */
    fun fetchPlayerSkin(username: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("fetch_player_skin", JSONObject().put("username", username), onResult)

    /**
     * 删除一张皮肤。
     *
     * ⚠️⚠️ 2026-10-16 补 —— 这条命令**一直存在**（`skins.rs:194` +
     * `commands.rs:944` + `lib.rs:289` 注册），但我此前把 UI 上的删除做成了
     * 「暂不支持在应用内删除皮肤，请到文件里手动删除」。
     *
     * 当时的注释写着「`api.ts` 里确实没有对应命令（核对过）」—— 那个结论是错的：
     * 我只看了前端 `api.ts`，没去 Rust 侧确认。**"前端没包"不等于"后端没有"。**
     */
    fun deleteSkin(filename: String, onResult: (Result<String>) -> Unit = {}) =
        call("delete_skin", JSONObject().put("filename", filename), onResult)

    /**
     * 拉取正版账号**拥有的披风列表**（联网）。
     *
     * 返回 `[{ id, name, data_url, active }]`。
     * 仅 Microsoft 账号有披风；离线账号 Rust 侧直接返回空数组。
     *
     * 注：旧 UI **没有**披风 UI（全项目搜 `cape` 只找到 useSkinRenderer 里
     * 未被调用的 `loadCape`），但 Rust 侧命令是完整可用的 ——
     * 所以这是"后端有能力、旧前端没做"的功能，按补齐处理。
     */
    fun fetchPlayerCapes(accountUuid: String, onResult: (Result<JSONArray>) -> Unit) =
        callArr("fetch_player_capes", JSONObject().put("accountUuid", accountUuid), onResult)

    /**
     * 给正版账号应用（或卸下）披风。
     *
     * @param capeId 传 null 表示卸下当前披风 —— Rust 侧参数是
     *        `Option<String>`，所以这里要显式写 `JSONObject.NULL`，
     *        不能省略键（省略会报 `invalid args`）。
     */
    fun applyCapeToAccount(
        accountUuid: String,
        capeId: String?,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "apply_cape_to_account",
        JSONObject()
            .put("accountUuid", accountUuid)
            .put("capeId", capeId?.let { it } ?: JSONObject.NULL),
        onResult,
    )

    // ── 插件（补充命令；getPlugins/getPluginSetupStatus 在文件上方已定义）──────

    /** 刷新插件清单（联网）。 */
    fun refreshPluginManifest(onResult: (Result<JSONArray>) -> Unit) =
        callArr("refresh_plugin_manifest", null, onResult)

    /** 安装推荐插件。 */
    fun installRecommendedPlugins(onResult: (Result<String>) -> Unit = {}) =
        call("install_recommended_plugins", null, onResult)

    // ── 设置页第二组（命令名逐个核对自 src/api.ts 的 SettingsView 用点）──────
    //
    // 这一组是**补齐**：设置页界面已画好，但不少按钮原本是空壳
    // （只有 `/* TODO */`）。核对旧设置页发现它用了 31 个命令，
    // 而 Kotlin 侧只覆盖了 6 个 —— 缺 24 个。这里全部补上。

    /** 清缓存。 */
    fun clearCache(onResult: (Result<String>) -> Unit = {}) =
        call("clear_cache", null, onResult)

    /** 清翻译缓存（可指定服务，null = 全部）。 */
    fun clearTranslationCache(service: String?, onResult: (Result<String>) -> Unit = {}) =
        call("clear_translation_cache", JSONObject().put("service", service ?: JSONObject.NULL), onResult)

    /**
     * 共享游戏文件占用（版本 / Java 可归属到本实例；库与素材是共享池）。
     *
     * 为什么要在「文件」页显示：游戏本体**不在实例目录里**，
     * 刚建的实例目录里只有一个 `instance.json` ——
     * 用户看到会以为「什么都没下」（旧实现注释里记过这个真机反馈）。
     *
     * 返回字段（已核对 Rust `shared_game_usage`）：
     *   mcVersion / versionBytes / javaVersion / javaBytes /
     *   librariesBytes / assetsBytes / instancesTotal
     */
    fun sharedGameUsage(instanceId: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("shared_game_usage", JSONObject().put("instanceId", instanceId), onResult)

    /**
     * 用系统文件管理器打开服务器的某个子目录（如 plugins）。
     * `sub` 为 null 表示服务器根目录。
     */
    fun openHostedServerFolder(
        id: String,
        sub: String?,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "open_hosted_server_folder",
        JSONObject().put("id", id).put("sub", sub ?: JSONObject.NULL),
        onResult,
    )

    /** 探测系统代理（返回探测结果，用于「测试代理」按钮）。 */
    /**
     * 探测系统 HTTP 代理。
     *
     * ⚠️ Rust 返回的是 **Option<String>**（代理地址字符串，或 null），
     * 不是 JSON 对象。一开始我用了 callObj，拿到的一定是解析失败。
     * 用 call() 拿裸字符串即可（call 会拆掉 JSON 引号）。
     */
    fun detectSystemProxy(onResult: (Result<String>) -> Unit) =
        call("detect_system_proxy", null, onResult)

    /** 控制按钮列表（按键页用）。 */
    fun getControlButtons(onResult: (Result<JSONArray>) -> Unit) =
        callArr("get_control_buttons", null, onResult)

    /** 游戏目录可选项。 */
    fun getGameDirOptions(onResult: (Result<JSONArray>) -> Unit) =
        callArr("get_game_dir_options", null, onResult)

    /** 当前游戏目录状态。 */
    fun getGameDirState(onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_game_dir_state", null, onResult)

    /** 插件清单地址。 */
    fun getPluginManifestUrl(onResult: (Result<String>) -> Unit) =
        call("get_plugin_manifest_url", null, onResult)

    /** 从文件导入背景图（sourcePath 为刚选中的图片路径）。 */
    fun importBackgroundImage(sourcePath: String, onResult: (Result<String>) -> Unit = {}) =
        call("import_background_image", JSONObject().put("sourcePath", sourcePath), onResult)

    /** 从清单安装插件。 */
    fun installPlugin(id: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("install_plugin", JSONObject().put("id", id), onResult)

    /** 从本地文件安装插件。 */
    fun installPluginFromFile(path: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("install_plugin_from_file", JSONObject().put("path", path), onResult)

    /** 镜像源列表。 */
    fun listMirrors(onResult: (Result<JSONArray>) -> Unit) =
        callArr("list_mirrors", null, onResult)

    /** 弹「选择游戏目录」（结果用 takePickedGameDir 取）。 */
    fun pickGameDir(onResult: (Result<String>) -> Unit = {}) =
        call("pick_game_dir", null, onResult)

    /** 取走刚选的游戏目录。 */
    /**
     * 取走刚选中的游戏目录（没选过时为 null）。
     *
     * ⚠️ Rust 返回 `Option<String>` —— 之前用 callObj 会报
     *「String cannot be converted to JSONObject」。
     */
    fun takePickedGameDir(onResult: (Result<String>) -> Unit) =
        call("take_picked_game_dir", null, onResult)

    /** 刷新存储统计。 */
    fun refreshStorageStats(onResult: (Result<JSONObject>) -> Unit) =
        callObj("refresh_storage_stats", null, onResult)

    /** 请求「所有文件访问」权限（弹系统页）。 */
    fun requestAllFilesAccess(onResult: (Result<String>) -> Unit = {}) =
        call("request_all_files_access", null, onResult)

    /**
     * 设置某个控制按钮是否透传（不拦截触摸）。
     *
     * ⚠️ Rust 返回的是**改完后的完整按钮列表**（`Vec<ControlButtonInfo>`，
     * controls.rs:67），不是字符串 —— 以前用 call 会得到 JSON 数组文本。
     */
    fun setControlButtonPassthru(
        index: Int,
        enabled: Boolean,
        onResult: (Result<JSONArray>) -> Unit = {},
    ) = callArr(
        "set_control_button_passthru",
        JSONObject().put("index", index).put("enabled", enabled),
        onResult,
    )

    /** 设置游戏目录（migrate = 是否迁移现有数据）。 */
    fun setGameDir(root: String, migrate: Boolean, onResult: (Result<String>) -> Unit = {}) =
        call("set_game_dir", JSONObject().put("root", root).put("migrate", migrate), onResult)

    /** 启用/禁用插件。 */
    fun setPluginEnabled(id: String, enabled: Boolean, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("set_plugin_enabled", JSONObject().put("id", id).put("enabled", enabled), onResult)

    /** 设置插件清单地址。 */
    fun setPluginManifestUrl(url: String, onResult: (Result<String>) -> Unit = {}) =
        call("set_plugin_manifest_url", JSONObject().put("url", url), onResult)

    /** 读 Pojav 启动偏好（渲染器、分辨率比例等都在这里）。 */
    fun getPojavPrefs(onResult: (Result<JSONObject>) -> Unit) =
        callObj("get_pojav_prefs", null, onResult)

    /** 写 Pojav 启动偏好（patch 语义）。 */
    fun setPojavPrefs(patch: JSONObject, onResult: (Result<String>) -> Unit = {}) =
        call("set_pojav_prefs", JSONObject().put("patch", patch), onResult)

    /** 测镜像源延迟。 */
    fun testMirror(base: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("test_mirror", JSONObject().put("base", base), onResult)

    /** 测代理连通性。 */
    fun testProxy(
        proxyMode: String,
        // 允许 null：非自定义模式下不需要代理地址。
        // Rust 侧 proxy: Option<String>，传 null 合法。
        proxy: String?,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "test_proxy",
        JSONObject().put("proxyMode", proxyMode).put("proxy", proxy ?: JSONObject.NULL),
        onResult,
    )

    /** 测翻译 API 是否可用。 */
    fun testTranslateApi(
        base: String,
        key: String,
        model: String,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "test_translate_api",
        JSONObject().put("base", base).put("key", key).put("model", model),
        onResult,
    )

    /** 卸载插件。 */
    fun uninstallPlugin(id: String, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("uninstall_plugin", JSONObject().put("id", id), onResult)

    /** 打开外部链接（桌面仓库、许可证等）。 */
    fun openUrl(url: String, onResult: (Result<String>) -> Unit = {}) =
        call("plugin:opener|open_url", JSONObject().put("url", url), onResult)

    // ── 应用自我更新（命令名核对自 lib.rs 的 updater::）──────────────────────

    /**
     * 检查启动器本体是否有新版本。
     *
     * 注意与 `check_updates` 区分：那个是检查**实例内的模组**更新，
     * 这个是检查**启动器应用本身**。
     */
    fun checkForUpdate(onResult: (Result<JSONObject>) -> Unit) =
        callObj("check_for_update", null, onResult)

    /**
     * 下载更新包。
     *
     * @param version 检查更新时拿到的版本号。后端会校验它和 `PENDING` 一致，
     *                防止「检查 A → 又检查 B → 下到 B 但界面显示 A」。
     *                传空串则跳过校验（回退到后端 PENDING）。
     *
     * ⚠️ Rust 侧 `updater::download_update(app, version)` 只认 `version` 参数，
     * 不再接受 `url`（2026-10-18 修）——
     * 之前传的 `{"url": ...}` 被 Tauri 静默忽略，等同于没传。
     */
    fun downloadUpdate(version: String, onResult: (Result<String>) -> Unit = {}) =
        call(
            "download_update",
            JSONObject().put("version", version),
            onResult,
        )
    /** 拉起系统安装器装已下载的 APK。 */
    fun installUpdate(path: String, onResult: (Result<String>) -> Unit = {}) =
        call("install_update", JSONObject().put("path", path), onResult)

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

    // ── 本地服务器（命令名核对自 src/api.ts）────────────────────────────────

    /**
     * 新建本地服务器。
     *
     * @param core 只能是 `paper` / `vanilla` / `fabric`
     *   —— `servers.rs` 只实现了这三条下载链路，其余会报「还在开发中」。
     * @param mcVersion 留空 = 后端挑最新稳定版
     */
    fun createHostedServer(
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
    fun updateHostedServer(
        patch: JSONObject,
        onResult: (Result<JSONObject>) -> Unit = {},
    ) = callObj("update_hosted_server", JSONObject().put("patch", patch), onResult)

    /** 启动本地服务器。 */
    fun startHostedServer(id: String, onResult: (Result<String>) -> Unit = {}) =
        call("start_hosted_server", JSONObject().put("id", id), onResult)

    /** 删除本地服务器。 */
    fun deleteHostedServer(id: String, onResult: (Result<String>) -> Unit = {}) =
        call("delete_hosted_server", JSONObject().put("id", id), onResult)

    // ── 陶瓦联机（Terracotta）──────────────────────────────────────────────
    //
    // ⚠️⚠️ 这里曾经调 4 个**在 Android 上根本不存在**的命令：
    //     terracotta_detect / terracotta_download / terracotta_launch / terracotta_stop
    // 运行时报「Command terracotta_xxx not found」，整条联机链路直接断掉。
    //
    // 它们是**桌面版**的接口（见旧前端 src/api.ts 的注释原话：
    // 「这几条是桌面版『启动 Terracotta 进程』的接口，Android 改走 :tunnel 的
    //   本地 HTTP（terracotta_ping / terracotta_request）」）。
    // 迁移时把桌面版的命令名照抄了过来，但 Android 侧 Rust 只注册了 4 个：
    //     terracotta_ping / terracotta_request / terracotta_vpn_granted / terracotta_request_vpn
    //
    // ── Android 上的正确模型 ───────────────────────────────────────────
    // 隧道跑在**独立进程** `:tunnel`（`libterracotta.so` 只在那里加载），
    // 启动器进程只通过本地 HTTP 调它：
    //   · 探活   → terracotta_ping（内部 GET /ping）
    //   · 状态   → terracotta_request("/state")
    //   · 开房   → terracotta_request("/host?player=<名>")
    //   · 加入   → terracotta_request("/guest?room=<码>&player=<名>")
    // 没有「下载组件」「启动进程」「停止进程」这三件事 —— 隧道进程的生死
    // 由 GameService / :tunnel 自己管。

    /**
     * 隧道是否就绪（= 旧桌面版的 `terracotta_detect`，Android 上的正确替代）。
     *
     * 内部走 `terracotta_ping`（Rust 会 GET `/ping` 并解析 `{"ready":true}`）。
     * 端口文件过期时 Rust 侧会顺手自愈，所以调用方可以放心地重复轮询。
     */
    fun terracottaTunnelReady(onResult: (Boolean) -> Unit) =
        terracottaTunnelPing { r ->
            onResult(r.getOrNull()?.contains("true") == true)
        }

    /**
     * 拉一次隧道状态，解析出房间码。
     *
     * 隧道 `/state` 返回形如 `{"index":1,"state":"host-scanning","room":"ABC123"}`。
     * 返回 `RoomState`：`state` 是隧道的原始状态串（`off` 表示隧道没在跑），
     * `room` 有值代表房间已建立。
     *
     * ⚠️ 之前用 `callObj` 会解析失败 —— Rust 返回的是**原始 JSON 文本**，
     * 不是对象（见 [terracottaTunnelRequest] 的注释）。
     */
    fun terracottaTunnelState(onResult: (TerracottaState) -> Unit) =
        terracottaTunnelRequest("/state") { r ->
            val raw = r.getOrNull().orEmpty()
            onResult(
                TerracottaState(
                    ok = raw.contains("\"ok\":true") || raw.contains("\"state\""),
                    state = Regex("\"state\":\"([^\"]*)\"").find(raw)
                        ?.groupValues?.getOrNull(1).orEmpty(),
                    room = Regex("\"room\":\"([^\"]*)\"").find(raw)
                        ?.groupValues?.getOrNull(1).orEmpty(),
                    error = Regex("\"error\":\"([^\"]*)\"").find(raw)
                        ?.groupValues?.getOrNull(1).orEmpty(),
                )
            )
        }

    /**
     * 开房（房主）。`player` 是显示给朋友看的名字。
     *
     * 返回隧道原样的 JSON 文本；`{"ok":true}` 才算成功。
     * 隧道会回 `{"ok":false,"error":"…"}`（例如状态不对），**必须检查** ——
     * 旧 Vue 就吃过这个亏：不看返回值为往下走，用户只看到「请先在游戏里开放
     * 局域网世界」，永远不知道请求其实被拒了。
     */
    fun terracottaHost(player: String, onResult: (Result<String>) -> Unit) =
        terracottaTunnelRequest("/host?player=" + java.net.URLEncoder.encode(player, "UTF-8"), onResult)

    /** 加入房间（客人）。 */
    fun terracottaGuest(room: String, player: String, onResult: (Result<String>) -> Unit) =
        terracottaTunnelRequest(
            "/guest?room=" + java.net.URLEncoder.encode(room, "UTF-8") +
                "&player=" + java.net.URLEncoder.encode(player, "UTF-8"),
            onResult,
        )
    /** 请求 VPN 授权（TUN 模式必须）。 */
    fun terracottaRequestVpn(onResult: (Result<String>) -> Unit = {}) =
        call("terracotta_request_vpn", null, onResult)

    /** VPN 授权是否已拿到。 */
    /**
     * VPN 授权是否已拿到。
     *
     * ⚠️ Rust 返回 `bool` —— 之前用 callObj 会报类型转换失败。
     * 用 call 收字符串后判 `"true"` 即可。
     */
    fun terracottaVpnGranted(onResult: (Result<String>) -> Unit) =
        call("terracotta_vpn_granted", null, onResult)

    /**
     * 隧道是否就绪（ping）。
     *
     * ⚠️ Rust 返回 `bool`（不是对象）。
     */
    fun terracottaTunnelPing(onResult: (Result<String>) -> Unit) =
        call("terracotta_ping", null, onResult)

    /**
     * 向隧道发请求（path 由调用方给，底层是 localhost HTTP）。
     *
     * ⚠️ Rust 返回 **Result<String>**（隧道响应的原始 JSON 文本），
     * 不是对象 —— 一开始用 callObj 必然解析失败（同 detectSystemProxy 的坑）。
     * 返回裸文本，由调用方自己 parse：错误响应形如
     * `{"ok":false,"error":"…"}`，不能在桥层吞掉。
     */
    fun terracottaTunnelRequest(path: String, onResult: (Result<String>) -> Unit) =
        call("terracotta_request", JSONObject().put("path", path), onResult)

    // ── FRP 联机房间（命令名核对自 src/api.ts 的 frpRoom* 段）──────────────
    //
    // 这是「FrpRoomsView」那个页面用的（344 行 / 9 个调用点）。
    // ⚠️ 我第一遍做页面覆盖对照时**漏了这个页面** —— 因为它的文件名
    // 不以 `View` 结尾的直觉命名（是 `FrpRoomsView`，而我在 Composition
    // 里按 `FrpRooms` 搜没命中）。是逐页列清单才发现的。
    //
    // 与陶瓦的区别：陶瓦是「独立进程 + localhost HTTP」，
    // FRP 是走 frpc 客户端把端口暴露到公网节点，两种联机方式并存。

    /**
     * 房间列表。`page` 从 1 开始（与面板 API 一致）。
     *
     * ⚠️⚠️ 这里踩过**两个**坑（2026-10-17 修）：
     *
     * **① `page` 是必需参数**（Rust `frp_room_list(page: i64)`）。曾经传 `null`：
     *   `invalid args 'page' ...: command frp_room_list missing required key page`
     *
     * **② 返回的是对象，不是数组**。Rust（`frp.rs:1043-1048`）返回
     *   `{"rooms":[...], "total":N, "page":N, "pageSize":50}`，
     *   而这里原本用 `callArr` → `JSONArray(...)` 抛
     *   `JSONException: Value {"rooms":[...]} cannot be converted to JSONArray`。
     *
     * 两个坑叠加的结果：房间列表**永远出不来**，而失败只写一行 `Log.w`，
     * UI 上表现为「还没有房间」或「读取房间失败」—— 看起来像"面板上确实没房间"。
     *
     * 现在返回完整对象，由调用方取 `rooms` 数组（还能顺便拿 `total` 做分页）。
     */
    fun frpRoomList(page: Int = 1, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_room_list", JSONObject().put("page", page), onResult)

    /** 可用节点列表。 */
    fun frpRoomNodes(onResult: (Result<JSONArray>) -> Unit) =
        callArr("frp_room_nodes", null, onResult)

    /**
     * 建房（房主）。
     *
     * ⚠️⚠️ `nodeId` 必须是 **Int**，不能是 String（2026-10-17 修）。
     *
     * Rust 签名是 `frp_room_create(name: String, node_id: i64, ...)`。
     * 之前这里收 String 并 `put("nodeId", nodeId)`，发出去是 JSON 字符串 "3"，
     * Tauri 反序列化直接失败：
     *     invalid type: string "3", expected i64
     * → **建房 100% 失败**，而错误只写一行 Log.w，UI 上像是"点了没反应"。
     *
     * 根因在调用方：`PageHost` 用 `optString("id")` 读 Rust 的 i64 节点 id，
     * 读成了字符串。现在已经改成 `optInt` 读取。
     */
    fun frpRoomCreate(
        name: String,
        nodeId: Int,
        localPort: Int,
        password: String?,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "frp_room_create",
        JSONObject()
            .put("name", name)
            .put("nodeId", nodeId)
            .put("localPort", localPort)
            .put("password", password ?: JSONObject.NULL),
        onResult,
    )

    /** 加入房间（访客）。 */
    fun frpRoomJoin(
        roomId: Int,
        password: String?,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "frp_room_join",
        JSONObject().put("roomId", roomId).put("password", password ?: JSONObject.NULL),
        onResult,
    )

    /** 离开房间。 */
    fun frpRoomLeave(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_room_leave", JSONObject().put("roomId", roomId), onResult)

    /** 关闭房间（房主）。 */
    fun frpRoomClose(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_room_close", JSONObject().put("roomId", roomId), onResult)

    /** 房主端起 frpc 暴露本地端口。 */
    fun frpRoomHostStart(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_room_host_start", JSONObject().put("roomId", roomId), onResult)

    /**
     * 访客端起 frpc，把房主代理绑到本地端口。
     *
     * 返回里的 `connect` 是给用户去游戏里连的地址（形如 `127.0.0.1:25565`）。
     */
    fun frpRoomVisitorStart(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_room_visitor_start", JSONObject().put("roomId", roomId), onResult)

    /** 停止 frpc。 */
    fun frpRoomStop(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_room_stop", JSONObject().put("roomId", roomId), onResult)

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

    // ── 实例内操作 ────────────────────────────────────────────────────────

    /** 安装游戏文件（补齐版本/库/素材）。 */
    fun installGame(instanceId: String, onResult: (Result<String>) -> Unit = {}) =
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
    fun checkInstanceFiles(instanceId: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("check_instance_files", JSONObject().put("instanceId", instanceId), onResult)

    /**
     * 补全缺失的游戏文件。
     *
     * Rust 侧复用 `install_game` 的逻辑：**逐文件校验、缺的才下**，
     * 所以重复调用是安全的（不会重下已有的）。
     * 进度同样走 `install://progress` 事件 → 下载页能看到。
     */
    fun repairInstanceFiles(instanceId: String, onResult: (Result<String>) -> Unit = {}) =
        call("repair_instance_files", JSONObject().put("instanceId", instanceId), onResult)

    /** 列出某个实例可加入的服务器（局域网发现）。 */
    fun listServers(instanceId: String, onResult: (Result<JSONArray>) -> Unit) =
        callObj("list_servers", JSONObject().put("instanceId", instanceId)) { r ->
            onResult(r.mapCatching { it.optJSONArray("servers") ?: JSONArray() })
        }

    /** ping 一个 Minecraft 服务器拿延迟与在线人数。 */
    fun pingMcServer(address: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("ping_mc_server", JSONObject().put("address", address), onResult)

    /** 读某个实例的游戏日志。 */
    fun readInstanceLog(instanceId: String, onResult: (Result<String>) -> Unit) =
        call("read_instance_log", JSONObject().put("instanceId", instanceId), onResult)

    /** 导出实例日志（打包成 zip 便于分享）。 */
    fun exportInstanceLogs(instanceId: String, onResult: (Result<String>) -> Unit = {}) =
        call("export_instance_logs", JSONObject().put("instanceId", instanceId), onResult)

    /** 导入本地文件到实例（kind = mods / resourcepacks / …）。 */
    fun importLocalFile(
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
    fun importModpack(filePath: String, onResult: (Result<String>) -> Unit = {}) =
        call("import_modpack", JSONObject().put("filePath", filePath), onResult)

    /** 导入实例图标。 */
    fun importInstanceImage(sourcePath: String, onResult: (Result<String>) -> Unit = {}) =
        call("import_instance_image", JSONObject().put("sourcePath", sourcePath), onResult)

    /** 从游戏文件里抽取图标（补给没有图标的实例）。 */
    fun extractGameIcons(instanceId: String?, onResult: (Result<JSONArray>) -> Unit = {}) =
        callArr("extract_game_icons", JSONObject().put("instanceId", instanceId ?: JSONObject.NULL), onResult)

    /** 列出某实例的崩溃日志文件名。 */
    fun listCrashLogs(instanceId: String, onResult: (Result<JSONArray>) -> Unit) =
        callArr("list_crash_logs", JSONObject().put("id", instanceId), onResult)

    /** 分析崩溃日志（给可能的原因）。 */
    fun analyzeCrashLog(
        instanceId: String,
        filename: String,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "analyze_crash_log",
        JSONObject().put("id", instanceId).put("filename", filename),
        onResult,
    )

    /** 读崩溃报告原文。 */
    fun getCrashReportContent(
        instanceId: String,
        filename: String,
        onResult: (Result<String>) -> Unit,
    ) = call("get_crash_report_content", JSONObject().put("id", instanceId).put("filename", filename), onResult)

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

    // ── 服务器插件 ────────────────────────────────────────────────────────

    fun listServerPlugins(serverId: String, onResult: (Result<JSONArray>) -> Unit) =
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
    fun installContent(
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
    fun installServerContent(
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

    fun installServerPlugin(
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

    fun deleteServerPlugin(
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
    fun hangarSearchPlugins(
        query: String,
        page: Int = 0,
        pageSize: Int = 20,
        onResult: (Result<JSONArray>) -> Unit,
    ) = callArr(
        "hangar_search_plugins",
        JSONObject().put("query", query).put("page", page).put("pageSize", pageSize),
        onResult,
    )

    // ── FRP 隧道（与 frp_room_* 不同：这个是单条隧道）──────────────────────

    fun frpLoginStart(onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_login_start", null, onResult)

    fun frpLoginPoll(session: String, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_login_poll", JSONObject().put("session", session), onResult)

    fun frpLogout(onResult: (Result<String>) -> Unit = {}) =
        call("frp_logout", null, onResult)

    /**
     * frpc 自检：返回一段说明文本（**String**，不是对象）。
     *
     * ⚠️ Rust 侧签名是 `Result<String, String>` —— 之前用 callObj 收，
     * 真机点「自检」报「Value 0.71.0（随包） of type java.lang.String
     * cannot be converted to JSONObject」。
     */
    fun frpProbe(onResult: (Result<String>) -> Unit) =
        call("frp_probe", null, onResult)

    /**
     * 本进程正在跑的隧道列表。
     *
     * ⚠️ Rust 返回的是 `Vec<Value>`（**数组**）不是对象 ——
     * 之前用 callObj 收，类型对不上（编译期就报 Int vs String）。
     */
    fun frpRunning(onResult: (Result<JSONArray>) -> Unit) =
        callArr("frp_running", null, onResult)

    fun frpStartTunnel(tunnelId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_start_tunnel", JSONObject().put("tunnelId", tunnelId), onResult)

    fun frpStopTunnel(tunnelId: Int, onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_stop_tunnel", JSONObject().put("tunnelId", tunnelId), onResult)

    fun frpTunnelLog(tunnelId: Int, onResult: (Result<String>) -> Unit) =
        call("frp_tunnel_log", JSONObject().put("tunnelId", tunnelId), onResult)

    // ── 内容 / 翻译 ───────────────────────────────────────────────────────

    /** 项目依赖列表（安装前置检查用）。 */
    fun projectDependencies(
        provider: String,
        projectId: String,
        versionId: String,
        onResult: (Result<JSONArray>) -> Unit,
    ) = callArr(
        "project_dependencies",
        JSONObject()
            .put("provider", provider)
            .put("projectId", projectId)
            .put("versionId", versionId),
        onResult,
    )

    /** 翻译模组正文。 */
    /**
     * 批量翻译模组描述（一次最多 5 个、同一平台）。
     *
     * ⚠️ 必须传 **slug** 而不是 id：翻译服务（及其缓存）以 slug 为键，
     * 传 id 会「请求成功但译文查不到」—— 表现为描述一直是英文。
     *
     * 返回：{ translations: { <slug>: <译文> }, rateLimited: bool, error: string }
     */
    fun translateModDescriptions(
        provider: String,
        slugs: List<String>,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "translate_mod_descriptions",
        JSONObject().put("provider", provider).put("slugs", org.json.JSONArray(slugs)),
        onResult,
    )

    fun translateProjectBody(
        provider: String,
        slug: String,
        translate: Boolean,
        onResult: (Result<JSONObject>) -> Unit,
    ) = callObj(
        "translate_project_body",
        JSONObject().put("provider", provider).put("slug", slug).put("translate", translate),
        onResult,
    )

    /** 上报「翻译已过期」。 */
    fun reportTranslationStale(
        provider: String,
        slug: String,
        onResult: (Result<String>) -> Unit = {},
    ) = call("report_translation_stale", JSONObject().put("provider", provider).put("slug", slug), onResult)

    /** 上报翻译质量问题。 */
    fun reportTranslationQuality(
        provider: String,
        slug: String,
        issueType: String,
        userSuggestion: String? = null,
        userComment: String? = null,
        onResult: (Result<String>) -> Unit = {},
    ) = call(
        "report_translation_quality",
        JSONObject()
            .put("provider", provider)
            .put("slug", slug)
            .put("issueType", issueType)
            .put("userSuggestion", userSuggestion ?: JSONObject.NULL)
            .put("userComment", userComment ?: JSONObject.NULL),
        onResult,
    )

    /** 查 MC 百科条目。 */
    fun mcWikiUrl(
        name: String,
        slug: String? = null,
        provider: String? = null,
        onResult: (Result<String>) -> Unit,
    ) = call(
        "mc_wiki_url",
        JSONObject()
            .put("name", name)
            .put("slug", slug ?: JSONObject.NULL)
            .put("provider", provider ?: JSONObject.NULL),
        onResult,
    )

    // ── 杂项 ──────────────────────────────────────────────────────────────

    /** 写文本文件（导出配置等）。 */
    fun saveTextFile(path: String, content: String, onResult: (Result<String>) -> Unit = {}) =
        call("save_text_file", JSONObject().put("path", path).put("content", content), onResult)

    /** 把 content:// 解析成真实路径。 */
    fun resolvePickedPath(path: String, onResult: (Result<String>) -> Unit) =
        call("resolve_picked_path", JSONObject().put("path", path), onResult)

    /** 读剪贴板（Rust 侧走宿主回调）。 */
    fun readClipboard(onResult: (Result<String>) -> Unit) =
        call("read_clipboard", null, onResult)

    /** 写剪贴板。 */
    fun writeClipboard(text: String, onResult: (Result<String>) -> Unit = {}) =
        call("write_clipboard", JSONObject().put("text", text), onResult)

    /** 前端调试日志（进 Rust 侧日志，便于排查）。 */
    fun logDebug(msg: String, onResult: (Result<String>) -> Unit = {}) =
        call("log_debug", JSONObject().put("msg", msg), onResult)

    /** 关闭插件初始化引导（用户已看过）。 */
    fun dismissPluginSetup(onResult: (Result<String>) -> Unit = {}) =
        call("dismiss_plugin_setup", null, onResult)

    /** FRP 状态。 */
    fun frpStatus(onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_status", null, onResult)

    /** FRP 隧道列表。 */
    fun frpTunnels(onResult: (Result<JSONArray>) -> Unit) =
        callArr("frp_tunnels", null, onResult)

    /**
     * FRP 面板的用户信息（邮箱、余额等）。
     *
     * 与 `frp_status` 的区别：那个只给「登没登录 / 用户名」，
     * 邮箱与余额要靠这条（旧 UI 的 FrpView 就是这么分两步拉的）。
     */
    fun frpUserInfo(onResult: (Result<JSONObject>) -> Unit) =
        callObj("frp_user_info", null, onResult)

    // ── 屏幕方向（原生能力）──────────────────────────────────────────────

    /**
     * 设置启动器界面方向。
     *
     * 注意：这个命令在**纯 Compose 界面下无意义**（旧实现用它控制 WebView
     * 的方向），保留只是为了设置页里的开关能继续工作。
     */
    fun setOrientation(mode: String, onResult: (Result<String>) -> Unit = {}) =
        call("set_orientation", JSONObject().put("mode", mode), onResult)


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

