package com.zhayi.qookix.compose.bridge


import org.json.JSONArray
import org.json.JSONObject

/**
 * 皮肤 / 披风相关的 API 包装（从 `QookixApi.kt` 拆出来的第一个域）。
 *
 * ## 为什么拆
 *
 * `QookixApi.kt` 原本 2337 行、30 个分节，是评审点名的 Q2 巨文件之一。
 * 拆的办法是**按域改成 extension 函数**：
 *   · 调用点一个都不用改（`api.listSkins(...)` 照常解析到 extension）；
 *   · 不引入新类型、不改构造函数；
 *   · 每个域一个文件，后续再加命令时不会又把主文件撑大。
 *
 * 主文件只保留「通用调用工具」（`call` / `callObj` / `callArr`，
 * 它们是 `internal`，同 module 的 extension 可见）+ 尚未迁走的域。
 *
 * ## 命令名来源
 *
 * 全部核对自 Rust 注册表（`lib.rs`）与参数名，**不是猜的** ——
 * 这一节的历史坑写在每条注释里（键名错一个就是 `missing required key`，
 * 而错误在旧代码里只有一行 Log.w）。
 */

/** 本地皮肤库列表（`Vec<SkinEntry>`：name/filename/path/size/modified）。 */
fun QookixApi.listSkins(onResult: (Result<JSONArray>) -> Unit) =
    callArr("list_skins", null, onResult)

/**
 * 读皮肤图片数据。
 *
 * ⚠️ 返回的是 **data URL**（`data:image/png;base64,...`），不是路径。
 * 这对 Compose 反而方便 —— Coil 能直接吃 data URL，
 * 不需要像旧前端那样先 `convertFileSrc` 再走 asset 协议。
 */
fun QookixApi.readSkinDataUrl(filename: String, onResult: (Result<String>) -> Unit) =
    call("read_skin_data_url", JSONObject().put("filename", filename), onResult)

/** 从 data URL 保存为新皮肤。 */
fun QookixApi.saveSkinFromData(
    name: String,
    dataUrl: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "save_skin_from_data",
    JSONObject().put("name", name).put("data", dataUrl),
    onResult,
)

/** 读某个账号的离线皮肤。 */
fun QookixApi.getOfflineSkin(uuid: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("get_offline_skin", JSONObject().put("uuid", uuid), onResult)

/**
 * 给离线账号应用皮肤。
 *
 * ⚠️⚠️ Rust 签名要 **3 个参数**（commands.rs）：
 *   `apply_skin_offline(skin_data, variant, uuid)`
 *
 *   - `skin_data`：皮肤的 **base64 data URL**（`data:image/png;base64,...`）。
 *     Rust 会剥掉前缀、解码、校验 PNG magic（skins.rs）。
 *   - `variant`：`slim` / `classic`（决定模型是纤细还是经典）。
 *   - `uuid`：账号 uuid。
 *
 * 以前这里只传 `uuid` + `filename`（文件路径）—— 键名全错，
 * 报 `missing required key skinData`，皮肤**永远应用不上**，而错误只有一行 Log.w。
 *
 * 注意：`filename` 不是皮肤数据。要先用 [readSkinDataUrl] 读出 data URL 再传。
 */
fun QookixApi.applySkinOffline(
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
 * ⚠️⚠️ Rust 签名要 3 个参数（commands.rs）：
 *   `apply_skin_to_account(account_uuid, skin_data, variant)`
 *
 *   - `skin_data`：皮肤 base64 data URL（`data:image/png;base64,...`）。
 *     Rust 剥前缀解码并校验 PNG magic（skins.rs）。
 *   - `variant`：`slim` / `classic`。
 *   - `account_uuid`：正版账号 uuid（离线账号会被 Rust 拒绝）。
 *
 * 以前传的是 `uuid` + `filename` + `slim` —— 键名全错，
 * 报 `missing required key accountUuid/skinData/variant`，上传永远失败。
 *
 * 与 [applySkinOffline] 同源：`filename` 不是皮肤数据，
 * 要先用 [readSkinDataUrl] 读出 data URL 再传。
 */
fun QookixApi.applySkinToAccount(
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

/**
 * 按**正版玩家名**从 Mojang 拉皮肤（返回 data_url / model / cape_data_url）。
 *
 * ⚠️ Rust 侧参数名是 `username`（玩家名），不是 `uuid` ——
 * 之前传 uuid 会报 `invalid args \`username\` for command`（真机实测）。
 */
fun QookixApi.fetchPlayerSkin(username: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("fetch_player_skin", JSONObject().put("username", username), onResult)

/**
 * 删除一张皮肤。
 *
 * ⚠️⚠️ 2026-10-16 补 —— 这条命令**一直存在**（`skins.rs` + `lib.rs` 注册），
 * 但当时 UI 上把删除做成了「暂不支持在应用内删除皮肤，请到文件里手动删除」。
 *
 * 当时的注释写着「`api.ts` 里确实没有对应命令（核对过）」—— 那个结论是错的：
 * 只看了前端 `api.ts`，没去 Rust 侧确认。**"前端没包"不等于"后端没有"。**
 */
fun QookixApi.deleteSkin(filename: String, onResult: (Result<String>) -> Unit = {}) =
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
fun QookixApi.fetchPlayerCapes(accountUuid: String, onResult: (Result<JSONArray>) -> Unit) =
    callArr("fetch_player_capes", JSONObject().put("accountUuid", accountUuid), onResult)

/**
 * 给正版账号应用（或卸下）披风。
 *
 * @param capeId 传 null 表示卸下当前披风 —— Rust 侧参数是
 *        `Option<String>`，所以这里要显式写 `JSONObject.NULL`，
 *        不能省略键（省略会报 `invalid args`）。
 */
fun QookixApi.applyCapeToAccount(
    accountUuid: String,
    capeId: String?,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "apply_cape_to_account",
    JSONObject()
        .put("accountUuid", accountUuid)
        .put("capeId", capeId ?: JSONObject.NULL),
    onResult,
)
