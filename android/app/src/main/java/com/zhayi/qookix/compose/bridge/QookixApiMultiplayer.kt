package com.zhayi.qookix.compose.bridge

import org.json.JSONObject
import org.json.JSONArray

/**
 * 联机域（陶瓦 / FRP 房间 / FRP 隧道）（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 * 命令名逐个核对自 Rust 注册表与 src/api.ts，不是猜的。
 */

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
fun QookixApi.frpRoomList(page: Int = 1, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_room_list", JSONObject().put("page", page), onResult)

/** 可用节点列表。 */
fun QookixApi.frpRoomNodes(onResult: (Result<JSONArray>) -> Unit) =
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
fun QookixApi.frpRoomCreate(
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
fun QookixApi.frpRoomJoin(
    roomId: Int,
    password: String?,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "frp_room_join",
    JSONObject().put("roomId", roomId).put("password", password ?: JSONObject.NULL),
    onResult,
)

/** 离开房间。 */
fun QookixApi.frpRoomLeave(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_room_leave", JSONObject().put("roomId", roomId), onResult)

/** 关闭房间（房主）。 */
fun QookixApi.frpRoomClose(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_room_close", JSONObject().put("roomId", roomId), onResult)

/** 房主端起 frpc 暴露本地端口。 */
fun QookixApi.frpRoomHostStart(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_room_host_start", JSONObject().put("roomId", roomId), onResult)

/**
 * 访客端起 frpc，把房主代理绑到本地端口。
 *
 * 返回里的 `connect` 是给用户去游戏里连的地址（形如 `127.0.0.1:25565`）。
 */
fun QookixApi.frpRoomVisitorStart(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_room_visitor_start", JSONObject().put("roomId", roomId), onResult)

/** 停止 frpc。 */
fun QookixApi.frpRoomStop(roomId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_room_stop", JSONObject().put("roomId", roomId), onResult)

// ── FRP 隧道（与 frp_room_* 不同：这个是单条隧道）──────────────────────

fun QookixApi.frpLoginStart(onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_login_start", null, onResult)

fun QookixApi.frpLoginPoll(session: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_login_poll", JSONObject().put("session", session), onResult)

fun QookixApi.frpLogout(onResult: (Result<String>) -> Unit = {}) =
    call("frp_logout", null, onResult)

/**
 * frpc 自检：返回一段说明文本（**String**，不是对象）。
 *
 * ⚠️ Rust 侧签名是 `Result<String, String>` —— 之前用 callObj 收，
 * 真机点「自检」报「Value 0.71.0（随包） of type java.lang.String
 * cannot be converted to JSONObject」。
 */
fun QookixApi.frpProbe(onResult: (Result<String>) -> Unit) =
    call("frp_probe", null, onResult)

/**
 * 本进程正在跑的隧道列表。
 *
 * ⚠️ Rust 返回的是 `Vec<Value>`（**数组**）不是对象 ——
 * 之前用 callObj 收，类型对不上（编译期就报 Int vs String）。
 */
fun QookixApi.frpRunning(onResult: (Result<JSONArray>) -> Unit) =
    callArr("frp_running", null, onResult)

fun QookixApi.frpStartTunnel(tunnelId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_start_tunnel", JSONObject().put("tunnelId", tunnelId), onResult)

fun QookixApi.frpStopTunnel(tunnelId: Int, onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_stop_tunnel", JSONObject().put("tunnelId", tunnelId), onResult)

fun QookixApi.frpTunnelLog(tunnelId: Int, onResult: (Result<String>) -> Unit) =
    call("frp_tunnel_log", JSONObject().put("tunnelId", tunnelId), onResult)


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
fun QookixApi.terracottaTunnelReady(onResult: (Boolean) -> Unit) =
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
fun QookixApi.terracottaTunnelState(onResult: (TerracottaState) -> Unit) =
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
fun QookixApi.terracottaHost(player: String, onResult: (Result<String>) -> Unit) =
    terracottaTunnelRequest("/host?player=" + java.net.URLEncoder.encode(player, "UTF-8"), onResult)

/** 加入房间（客人）。 */
fun QookixApi.terracottaGuest(room: String, player: String, onResult: (Result<String>) -> Unit) =
    terracottaTunnelRequest(
        "/guest?room=" + java.net.URLEncoder.encode(room, "UTF-8") +
            "&player=" + java.net.URLEncoder.encode(player, "UTF-8"),
        onResult,
    )
/** 请求 VPN 授权（TUN 模式必须）。 */
fun QookixApi.terracottaRequestVpn(onResult: (Result<String>) -> Unit = {}) =
    call("terracotta_request_vpn", null, onResult)

/** VPN 授权是否已拿到。 */
/**
 * VPN 授权是否已拿到。
 *
 * ⚠️ Rust 返回 `bool` —— 之前用 callObj 会报类型转换失败。
 * 用 call 收字符串后判 `"true"` 即可。
 */
fun QookixApi.terracottaVpnGranted(onResult: (Result<String>) -> Unit) =
    call("terracotta_vpn_granted", null, onResult)

/**
 * 隧道是否就绪（ping）。
 *
 * ⚠️ Rust 返回 `bool`（不是对象）。
 */
fun QookixApi.terracottaTunnelPing(onResult: (Result<String>) -> Unit) =
    call("terracotta_ping", null, onResult)

/**
 * 向隧道发请求（path 由调用方给，底层是 localhost HTTP）。
 *
 * ⚠️ Rust 返回 **Result<String>**（隧道响应的原始 JSON 文本），
 * 不是对象 —— 一开始用 callObj 必然解析失败（同 detectSystemProxy 的坑）。
 * 返回裸文本，由调用方自己 parse：错误响应形如
 * `{"ok":false,"error":"…"}`，不能在桥层吞掉。
 */
fun QookixApi.terracottaTunnelRequest(path: String, onResult: (Result<String>) -> Unit) =
    call("terracotta_request", JSONObject().put("path", path), onResult)

