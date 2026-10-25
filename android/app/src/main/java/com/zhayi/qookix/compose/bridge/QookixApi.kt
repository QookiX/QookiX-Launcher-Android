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
            // 阶段 3 第八批（联机 FRP / 房间）
            "frp_status",
            "frp_set_api_base",
            "frp_login_start",
            "frp_login_poll",
            "frp_logout",
            "frp_user_info",
            "frp_tunnels",
            "frp_start_tunnel",
            "frp_stop_tunnel",
            "frp_probe",
            "frp_room_nodes",
            "frp_room_list",
            "frp_room_detail",
            "frp_room_create",
            "frp_room_join",
            "frp_room_leave",
            "frp_room_close",
            "frp_room_host_start",
            "frp_room_visitor_start",
            "frp_room_stop",
            // 阶段 3 第九批（账号 / 皮肤 / 服务端）
            "add_account",
            "remove_account",
            "select_account",
            "login_ms_start",
            "login_ms_poll",
            "read_skin_data_url",
            "save_skin_from_data",
            "download_skin_from_url",
            "delete_skin",
            "apply_skin_to_account",
            "apply_cape_to_account",
            "apply_skin_offline",
            "get_offline_skin",
            "install_server_content",
            "install_server_plugin",
            "ping_mc_server",
            // 阶段 3 第十批（开服属性/目录 + 控制布局）
            "get_hosted_server_properties",
            "set_hosted_server_properties",
            "hosted_server_core_installed",
            "open_hosted_server_directory",
            "get_control_buttons",
            "set_control_button_passthru",
            "list_control_layouts",
            "set_current_control_layout",
            "duplicate_control_layout",
            "rename_control_layout",
            "delete_control_layout",
            "open_control_layout_editor",
            "export_control_layout",
            "pick_control_layout",
            "take_control_import",
            "import_control_layout_by_path",
            // 阶段 3 第十一批（收尾全覆盖：游戏目录 / 实例体检 / 服务端文件 / 陶瓦 / 自更新）
            "get_game_dir_state",
            "get_game_dir_options",
            "has_all_files_access",
            "request_all_files_access",
            "pick_game_dir",
            "take_picked_game_dir",
            "set_game_dir",
            "set_log_zoom_capture",
            "check_instance_files",
            "repair_instance_files",
            "shared_game_usage",
            "plan_instance_cleanup",
            "cleanup_instance_shared",
            "list_hosted_server_dir",
            "read_hosted_server_file",
            "write_hosted_server_file",
            "list_hosted_server_config_files",
            "open_hosted_server_folder",
            "inspect_server_core_manifest",
            "request_battery_unrestricted",
            "device_thermal_status",
            "terracotta_vpn_granted",
            "terracotta_request_vpn",
            "terracotta_ping",
            "terracotta_request",
            "check_for_update",
            "download_update",
            "install_update",
            // 阶段 3 第十批（存档备份 / 导出 / 更新 / 翻译 / 杂项）
            "get_download_progress",
            "plan_modpack_export",
            "export_modpack",
            "translate_mod_descriptions",
            "translate_project_body",
            "estimate_download",
            "estimate_import",
            "analyze_crash_log",
            "get_crash_report_content",
            "create_world_backup",
            "restore_world_backup",
            "delete_world_backup",
            "check_updates",
            "apply_update",
            "import_local_file",
            "save_text_file",
            "extract_game_icons",
            "resolve_picked_path",
            "export_instance_logs",
            // 阶段 3 第十一批（收尾：覆盖全部 154 条）
            "install_version",
            "update_instance",
            "get_game_status",
            "download_file",
            "cancel_download",
            "hangar_search_plugins",
            "list_server_plugins",
            "delete_server_plugin",
            "frp_running",
            "frp_tunnel_log",
            "install_mod",
            "uninstall_mod",
            "install_content",
            "report_translation_stale",
            "report_translation_quality",
            "clear_translation_cache",
            "test_translate_api",
            "create_instance_group",
            "rename_instance_group",
            "delete_instance_group",
            "reorder_instance_groups",
            "test_mirror",
            "test_proxy",
            "auto_detect_memory",
            "is_game_running",
            "refresh_storage_stats",
            "list_instance_folders",
            "create_instance_entry",
            "import_instance_image",
            "import_background_image",
            "check_renderer_health_latest",
            "create_instance",
        )
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

