package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.R
import android.util.Log
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务器详情页的数据层。
 *
 * 封装 `hosted_server_*` 这一组命令：
 *   hosted_server_runtime           → 运行状态
 *   hosted_server_address           → 对外地址（**纯字符串**）
 *   hosted_server_core_installed   → 核心是否装好
 *   install_hosted_server_core     → 装核心
 *   read_hosted_server_log         → 读日志（返回字符串数组）
 *   server_console_command         → 发控制台命令
 *   get_hosted_server_properties   → 读 server.properties 白名单项
 *   set_hosted_server_properties   → 写回（返回写入后的完整值）
 *   open_hosted_server_directory   → 用系统文件管理器打开目录
 */
class ServerDetailRepo(private val api: QookixApi, private val context: android.content.Context) {

    companion object {
        private const val TAG = "ServerDetailRepo"

        /** 房间码轮询间隔，与旧 Vue 的 `setInterval(…, 1500)` 一致。 */
        const val sharePollDelayMs = 1500L

        /**
         * 房间码最长等待时间。
         *
         * 旧 Vue 是 45000ms（`waited >= 45000`）—— 太短会在 Terracotta
         * 还没扫到广播时就报「没拿到房间码」，用户以为功能坏了。
         */
        const val sharePollTimeoutMs = 45000L

        /** 隧道请求重试间隔（旧 Vue 是 2000ms）。 */
        const val tunnelRetryDelayMs = 2000L

        /**
         * 从隧道返回的原始 JSON 里掏错误信息。
         *
         * 隧道回 `{"ok":false,"error":"…"}`；**必须检查** ——
         * 旧 Vue 就吃过这个亏：不看返回值往下走，用户只看到
         * 「请先在游戏里开放局域网世界」，永远不知道请求其实被拒了。
         * 返回 null 表示没有错误。
         */
        fun tunnelError(body: String?, refusedMsg: String = "\u96a7\u9053\u62d2\u7edd\u4e86\u8bf7\u6c42"): String? {
            val raw = body.orEmpty()
            if (raw.contains("\"ok\":true")) return null
            val err = Regex("\"error\":\"([^\"]*)\"").find(raw)?.groupValues?.getOrNull(1)
            return err?.takeIf { it.isNotBlank() } ?: refusedMsg
        }

        /** URL 编码（隧道接口的 query 参数）。 */
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /** 人读的字节数（插件列表里显示大小）。 */
        fun fmtSize(bytes: Long, unknownMsg: String = "\u672a\u77e5\u5927\u5c0f"): String = when {
            bytes <= 0 -> unknownMsg
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        }
    }

    /** 一次拉齐详情页需要的三样东西。 */
    fun load(
        id: String,
        onResult: (Result<ServerDetailUiState>) -> Unit,
    ) {
        var runtime: JSONObject? = null
        var address = ""
        var coreInstalled = true
        var failed: Throwable? = null
        var eula = false
        var pending = 4

        fun done() {
            if (--pending > 0) return
            val e = failed
            if (e != null) {
                onResult(Result.failure(e))
                return
            }
            onResult(
                Result.success(
                    ServerDetailUiState(
                        id = id,
                        running = runtime?.optBoolean("running", false) ?: false,
                        // 上次运行的结束原因：服务端起不来时这是**唯一**的线索
                        // （写在 runtime.json.exit / launch-error.txt，以前界面不显示）。
                        exitNote = runtime?.optString("exitNote")?.takeIf { it.isNotBlank() },
                        address = address,
                        coreInstalled = coreInstalled,
                        eulaAgreed = eula,
                    )
                )
            )
        }

        api.hostedServerRuntime(id) { r ->
            r.onSuccess { runtime = it }
            r.onFailure { failed = it }
            done()
        }

        api.hostedServerAddress(id) { r ->
            r.onSuccess { s ->
                // ⚠️ 2026-10-16 修：Rust 返回的是**纯字符串**（如 "192.168.1.25:25565"）。
                // 原来按 JSONObject 解析，真机上抛
                // `JSONException: Value 192.168.1.25 ... cannot be converted to JSONObject`，
                // 进而让整个 load 失败、coreInstalled 卡在默认 true ——
                // 表现就是「只有启动按钮、没有安装核心按钮」，服务器永远起不来。
                //
                // 注释里原本写着"两种都兜住"，但代码只处理了对象形式。
                address = s.trim()
            }
            r.onFailure { failed = it }
            done()
        }

        api.hostedServerCoreInstalled(id) { r ->
            r.onSuccess { coreInstalled = it }
            r.onFailure { failed = it }
            done()
        }

        // EULA 同意状态：`get_hosted_server` 返回完整 ServerConfig（含 eula 字段）。
        // 之前界面拿不到它 —— Rust 启动前会因未同意而拒绝，用户却找不到地方勾选。
        api.getHostedServer(id) { r ->
            r.onSuccess { o -> eula = o.optBoolean("eula", false) }
            r.onFailure { failed = it }
            done()
        }
    }

    /** 读日志（返回字符串数组，不是单个字符串）。 */
    fun loadLogs(id: String, onResult: (Result<List<String>>) -> Unit) {
        api.callRaw("read_hosted_server_log", JSONObject().put("id", id)) { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parseStringArray(it))) },
                onFailure = { e ->
                    Log.w(TAG, "读日志失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 读配置白名单项。 */
    fun loadProperties(id: String, onResult: (Result<List<PropertyUi>>) -> Unit) {
        api.getHostedServerProperties(id) { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parseProperties(it))) },
                onFailure = { e ->
                    Log.w(TAG, "读配置失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 发一条控制台命令。 */
    fun sendCommand(id: String, command: String, onResult: (Result<String>) -> Unit = {}) {
        api.serverConsoleCommand(id, command, onResult)
    }

    /** 启动 / 停止。 */
    fun start(id: String, onResult: (Result<String>) -> Unit = {}) {
        api.callRaw("start_hosted_server", JSONObject().put("id", id), onResult)
    }

    fun stop(id: String, onResult: (Result<String>) -> Unit = {}) {
        api.callRaw("stop_hosted_server", JSONObject().put("id", id), onResult)
    }

    /** 安装服务端核心。 */
    fun installCore(id: String, onResult: (Result<String>) -> Unit = {}) =
        api.installHostedServerCore(id, onResult)

    /** 打开目录。 */
    fun openDirectory(id: String, onResult: (Result<String>) -> Unit = {}) =
        api.openHostedServerDirectory(id, onResult)

    /**
     * 「用文件管理器打开」某个**子目录**（如 `plugins`）。
     *
     * 与 [openDirectory] 的区别：走的是 `open_hosted_server_folder`
     * （`server_files.rs:75`，签名 `(id: String, sub: Option<String>)`），
     * 它是 Android 上唯一能拿到「服务器目录里的某个子目录」的入口 ——
     * `open_hosted_server_directory` 只给根目录。
     *
     * ⚠️ 它**不弹系统文件管理器**（安卓上没有这个能力），只是把路径字符串
     * 返回给 UI。所以这里不吞返回值，交给调用方展示/复制路径，否则用户
     * 点了「打开插件目录」会以为按钮坏了。
     */
    fun openFolder(id: String, sub: String?, onResult: (Result<String>) -> Unit = {}) =
        api.openHostedServerFolder(id, sub, onResult)

    // ── 插件（PaperMC Hangar）──────────────────────────────────────────────

    /**
     * 列出已装插件。
     *
     * 对应 `commands.rs:434` 的 `list_server_plugins(server_id: String) -> Vec<Value>`，
     * 包装层（[QookixApi.listServerPlugins]）已把 `serverId` 映射好。
     * 每项形如 `{ "fileName": "EssentialsX.jar", "size": 1234567 }`（见 hangar.rs:92）。
     */
    fun loadPlugins(id: String, onResult: (Result<List<InstalledPlugin>>) -> Unit) {
        api.listServerPlugins(id) { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parsePlugins(it))) },
                onFailure = { e ->
                    Log.w(TAG, "读插件列表失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 删除一个插件。`fileName` 必须是 [InstalledPlugin.fileName] 的原值。 */
    fun deletePlugin(id: String, fileName: String, onResult: (Result<String>) -> Unit = {}) =
        api.deleteServerPlugin(id, fileName, onResult)

    /**
     * 解析插件列表。
     *
     * `size` 在 Rust 侧是 `u64`，`JSONObject.optLong` 直接取即可；
     * 万一后端只给了字符串也兜住（同 [parseProperties] 的思路，
     * 这个仓库已经踩过好几次 i64/string 的坑）。
     */
    fun parsePlugins(arr: JSONArray): List<InstalledPlugin> {
        val out = mutableListOf<InstalledPlugin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("fileName").takeIf { it.isNotBlank() } ?: continue
            val size = if (o.opt("size") is Number) o.optLong("size")
                       else o.optString("size").toLongOrNull() ?: 0L
            out.add(InstalledPlugin(fileName = name, sizeBytes = size))
        }
        return out.sortedBy { it.fileName.lowercase() }
    }

    // ── 内存 / 删除 ────────────────────────────────────────────────────────

    /**
     * 改服务器内存（`update_hosted_server`，`servers.rs:186`）。
     *
     * ⚠️ 参数名是 **snake_case**：Rust 读的是 `patch["max_memory_mb"]` /
     * `patch["min_memory_mb"]`（**不是** `maxMemoryMb`）—— 这是**命令内部字段名**，
     * 不走 Tauri 的 camelCase 参数转换（整个 patch 是一个 `serde_json::Value`）。
     * 传错名字不会报错，只会**静默不生效**，所以这里写死 snake_case。
     *
     * 值与 [ServerConfig] 的类型对齐：`u64`（`as_u64()` 读取），
     * 所以用 [Int]/[Long] 而不是 String —— 传 `"2048"` 会被 `as_u64()` 读成 None。
     *
     * 后端会 clamp（max 256–8192 / min 128–8192），以返回值刷新界面。
     */
    fun setMemory(
        id: String,
        maxMb: Int,
        minMb: Int,
        onResult: (Result<JSONObject>) -> Unit,
    ) = api.updateHostedServer(
        JSONObject()
            .put("id", id)
            .put("max_memory_mb", maxMb)
            // 最小内存 > 最大内存是 JVM 直接拒绝启动的经典错误，这里先纠正
            .put("min_memory_mb", minMb.coerceAtMost(maxMb)),
    ) { r -> onResult(r) }

    /** 删除服务器。 */
    fun deleteServer(id: String, onResult: (Result<String>) -> Unit = {}) =
        api.deleteHostedServer(id, onResult)

    // ── 分享给朋友（陶瓦联机）──────────────────────────────────────────────
    //
    // 对应旧 Vue `ServerDetailView.vue:548-609` 的 `toggleShare`。
    //
    // 为什么独立服务端不能直接点「开启房间」：
    //   Terracotta 发现「本机有台 MC 服务器」的方式**不是扫端口，而是被动收组播包**
    //   （官方 scanning.rs：bind 4445 + join_multicast 224.0.2.60，
    //    解析 `[MOTD]…[/AD]<端口>[/AD]`）。那个包只有**游戏客户端开局域网世界**
    //   时才会发，独立服务端不发 —— 所以必须由我们**主动发这个包**把端口报出去
    //   （`/advertise?port=…`），Terracotta 收到后才会建房、生成房间码，
    //   并把该端口加进转发白名单。
    //
    //   ⚠️ 顺序有讲究：**先广播、再 /host**。反了的话这次扫描就白扫了。

    /**
     * 开始分享。
     *
     * 顺序（照抄旧 Vue，每一步都不能少）：
     *   1. 探活隧道（`terracotta_ping`）—— 冷启动时隧道还没起来，
     *      直接发请求只会撞上「连接被拒」这种看不懂的错
     *   2. 确保 VPN 授权（`terracotta_request_vpn` + 轮询
     *      `terracotta_vpn_granted`）—— 没有 TUN 的症状很隐蔽：
     *      **房间码能开出来，但客人永远连不上**（房主侧日志里只有自己）
     *   3. `/advertise?port=<port>&name=<名>` —— 主动广播「本机有这个端口」
     *   4. `/host?player=<名>` —— 让 Terracotta 开房
     *   5. 轮询 `/state` 拿房间码 —— 要等 Terracotta 扫到广播才出现（几秒）
     *
     * 房间码的轮询不在这里做 —— 由 UI 层用协程轮询（[sharePollDelayMs] 间隔），
     * 这样离开页面时能随 composition 一起取消，不会像旧 Vue 那样漏掉 `clearInterval`。
     */
    fun startShare(
        id: String,
        port: Int,
        name: String,
        onProgress: (String) -> Unit = {},
        onResult: (Result<String>) -> Unit,
    ) {
        val label = name.ifBlank { context.getString(R.string.server_default_name) }
        onProgress(context.getString(R.string.stage_preparing_tunnel))

        fun advertise() {
            // ⚠️ 端口/名字都要 URL 编码：名字里带空格或中文时，
            // 不编码会让隧道侧解析 query 出错（旧 Vue 用 encodeURIComponent）
            val path = "/advertise?port=$port&name=" + enc(label)
            tunnelCall(path) { r1 ->
                r1.fold(
                    onSuccess = {
                        onProgress(context.getString(R.string.stage_opening_room))
                        // /host?player=… 与旧 Vue 一致（QookixApi.terracottaHost 里也编码了）
                        api.terracottaHost(label) { r2 ->
                            r2.fold(
                                onSuccess = { body ->
                                    val err = tunnelError(body, context.getString(R.string.tunnel_refused))
                                    if (err != null) {
                                        onResult(Result.failure(IllegalStateException(err)))
                                    } else {
                                        onProgress(context.getString(R.string.stage_waiting_code))
                                        onResult(Result.success(""))
                                    }
                                },
                                onFailure = { onResult(Result.failure(it)) },
                            )
                        }
                    },
                    onFailure = { onResult(Result.failure(it)) },
                )
            }
        }

        // 1 + 2：探活，然后要 VPN 授权
        api.terracottaTunnelReady { ready ->
            if (!ready) {
                onResult(Result.failure(IllegalStateException(
                    context.getString(R.string.tunnel_not_ready_detail)
                )))
                return@terracottaTunnelReady
            }
            ensureVpnConsent(
                onProgress = onProgress,
                onResult = { granted ->
                    if (!granted) {
                        onResult(Result.failure(IllegalStateException(
                            context.getString(R.string.vpn_not_authorized)
                        )))
                    } else {
                        advertise()
                    }
                },
            )
        }
    }

    /**
     * 停止分享。
     *
     * ⚠️ 顺序同样有讲究（旧 Vue 注释原话）：
     * **先停广播、再 /waiting**。`/waiting` 里也会顺手停广播，
     * 但先停一次能保证「立刻」不再对外宣称有台服务器。
     */
    fun stopShare(onResult: (Result<String>) -> Unit = {}) {
        tunnelCall("/no-advertise") {
            tunnelCall("/waiting") { r -> onResult(r) }
        }
    }

    /**
     * 拉一次隧道状态（房间码）。
     *
     * 用 [QookixApi.terracottaTunnelState]，它内部用正则从隧道原始 JSON 里
     * 掏 `"state"` / `"room"` / `"error"` —— 与旧 Vue `pickRoomCode` 等价
     * （旧 Vue 还兼容 `code` / `room_code` / `roomCode`，这里由包装层统一处理）。
     */
    fun pollRoom(onResult: (String) -> Unit) {
        api.terracottaTunnelState { st -> onResult(st.room) }
    }

    /**
     * 确保 VPN 授权。
     *
     * 照抄旧 Vue `ensureVpnConsent`（ServerDetailView.vue:532-546）：
     * 已授权直接返回；否则弹授权框并**最多等 30 秒**让用户去点「允许」。
     *
     * 为什么必须等：Terracotta 的 VpnService 请求只有 30 秒答复窗口，
     * 系统授权对话框要用户点一下，所以必须提前问、不能塞在那个窗口里。
     *
     * 用 [android.os.Handler] 而不是协程：这个函数会被 [startShare] 的
     * 回调链反复调用，回调链本身是主线程 Handler 驱动的，混用两套调度
     * 只会让「谁在主线程」变模糊。间隔 1 秒 × 30 次 = 30 秒，与旧 Vue 一致。
     */
    private fun ensureVpnConsent(
        onProgress: (String) -> Unit,
        onResult: (Boolean) -> Unit,
    ) {
        api.terracottaVpnGranted { r ->
            // ⚠️ Rust 返回 bool，包装层用 call() 收到的是字符串 "true"/"false"
            if (r.getOrNull()?.trim() == "true") {
                onResult(true)
                return@terracottaVpnGranted
            }
            onProgress(context.getString(R.string.vpn_permission_hint))
            api.terracottaRequestVpn { _ ->
                var waited = 0
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                fun tick() {
                    api.terracottaVpnGranted { vr ->
                        when {
                            vr.getOrNull()?.trim() == "true" -> onResult(true)
                            waited >= 30 -> onResult(false)
                            else -> {
                                waited += 1
                                handler.postDelayed({ tick() }, 1000)
                            }
                        }
                    }
                }
                handler.postDelayed({ tick() }, 1000)
            }
        }
    }

    /**
     * 调隧道接口，失败自动重试几次。
     *
     * 照抄旧 Vue `tunnelCall`（ServerDetailView.vue:509-520）：
     * 冷启动时隧道要 `loadLibrary` + 初始化 EasyTier（手机上还跑着服务器 JVM 时更慢），
     * 头几秒必然连不上。直接把这句错弹给用户（"隧道还在启动中"）只能干等，
     * 这里自己重试 **3 次（间隔 [tunnelRetryDelayMs]）**，多数情况点一下就成。
     */
    fun tunnelCall(
        path: String,
        tries: Int = 3,
        onResult: (Result<String>) -> Unit,
    ) {
        api.terracottaTunnelRequest(path) { r ->
            if (r.isSuccess || tries <= 1) {
                onResult(r)
                return@terracottaTunnelRequest
            }
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({ tunnelCall(path, tries - 1, onResult) }, tunnelRetryDelayMs)
        }
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /**
     * `read_hosted_server_log` 返回的是 **Vec<String>**，
     * 即 JSON 字符串数组 —— 不是对象、也不是单个大字符串。
     */
    fun parseStringArray(json: String): List<String> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            // 兜底：可能返回的是普通字符串（换行分隔）
            json.lines().filter { it.isNotBlank() }
        }
    }

    /**
     * 一条属性的类型规格（类型 / 候选值 / 最小值 / 最大值）。
     *
     * Kotlin 标准库只有 `Pair`/`Triple`，四项就得自己定义 ——
     * 用 data class 也比嵌套 Pair 可读。
     */
    private data class PropSpec(
        val kind: PropKind,
        val choices: List<String> = emptyList(),
        val min: Int = 0,
        val max: Int = 0,
    )

    /**
     * 解析 server.properties 白名单项。
     *
     * Rust 侧返回 `{ "<field>": <值> }` 的扁平对象（见 `get_hosted_server_properties`），
     * 值是字符串/数字/布尔混合，统一转成字符串展示。
     */
    fun parseProperties(json: JSONObject): List<PropertyUi> {
        val out = mutableListOf<PropertyUi>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = json.opt(k)?.toString() ?: continue
            val spec = specOf(k)
            out.add(
                PropertyUi(
                    key = k,
                    value = v.replace("\"", ""),
                    description = descOf(k),
                    kind = spec.kind,
                    choices = spec.choices,
                    min = spec.min,
                    max = spec.max,
                ),
            )
        }
        // 按字母序，保证每次进页面顺序一致（对象 key 顺序不保证）
        return out.sortedBy { it.key }
    }

    /**
     * 可编辑属性的类型规格 —— **逐条照抄后端 `editable_props()`**。
     *
     * 为什么前端要复刻一份：后端是**整批校验、越界整体拒绝**的
     *（`viewDistance` 只收 3–32、`maxPlayers` 只收 1–100）。
     * 前端按同样范围出控件，用户就点不到非法值，不必"填了才被拒"。
     *
     * 不在白名单里的项一律 `ReadOnly`（只展示）—— 与后端一致：
     * patch 里出现未列出的键会被忽略。
     *
     * 返回 (类型, 候选值, 最小值, 最大值)。
     */
    private fun specOf(key: String): PropSpec = when (key) {
        "onlineMode" -> PropSpec(PropKind.Bool, emptyList(), 0, 0)
        "whiteList" -> PropSpec(PropKind.Bool, emptyList(), 0, 0)
        "pvp" -> PropSpec(PropKind.Bool, emptyList(), 0, 0)
        "viewDistance" -> PropSpec(PropKind.Int, emptyList(), 3, 32)
        "simulationDistance" -> PropSpec(PropKind.Int, emptyList(), 3, 32)
        "maxPlayers" -> PropSpec(PropKind.Int, emptyList(), 1, 100)
        "difficulty" -> PropSpec(
            PropKind.Choice,
            listOf("peaceful", "easy", "normal", "hard"),
            0, 0,
        )
        "gamemode", "gameMode" -> PropSpec(
            PropKind.Choice,
            listOf("survival", "creative", "adventure", "spectator"),
            0, 0,
        )
        else -> PropSpec(PropKind.ReadOnly, emptyList(), 0, 0)
    }

    /**
     * 写回服务器属性（`set_hosted_server_properties`）。
     *
     * 传**单键** patch：后端整批校验后返回**完整的最新属性**，
     * 界面以返回值刷新 —— 这样越界被钳制时，用户能看到真正落盘的值。
     */
    fun setProperty(
        id: String,
        key: String,
        value: Any,
        onResult: (Result<List<PropertyUi>>) -> Unit,
    ) {
        api.setHostedServerProperties(id, JSONObject().put(key, value)) { r ->
            r.fold(
                onSuccess = { onResult(Result.success(parseProperties(it))) },
                onFailure = { e ->
                    Log.w(TAG, "写配置 $key 失败: ${e.message}")
                    onResult(Result.failure(e))
                },
            )
        }
    }

    /** 给常见配置项配中文说明（旧界面也有类似的提示）。 */
    private fun descOf(key: String): String = when (key) {
        "motd" -> context.getString(R.string.prop_motd)
        "difficulty" -> context.getString(R.string.prop_difficulty)
        "gamemode" -> context.getString(R.string.prop_gamemode)
        "maxPlayers", "max_players" -> context.getString(R.string.prop_max_players)
        "pvp" -> context.getString(R.string.prop_pvp)
        "onlineMode", "online_mode" -> context.getString(R.string.prop_online_mode)
        "viewDistance", "view_distance" -> context.getString(R.string.prop_view_distance)
        "simulationDistance", "simulation_distance" -> context.getString(R.string.prop_simulation_distance)
        "spawnProtection", "spawn_protection" -> context.getString(R.string.prop_spawn_protection)
        "whiteList", "white_list" -> context.getString(R.string.prop_white_list)
        "levelSeed", "level_seed" -> context.getString(R.string.prop_level_seed)
        "serverPort", "server_port" -> context.getString(R.string.prop_server_port)
        "levelName", "level_name" -> context.getString(R.string.prop_level_name)
        else -> ""
    }
}







