package com.zhayi.qookix.compose.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * 局域网直传同步（`lan_*`）的命令包装。
 *
 * 为什么是扩展函数而不是 `QookixApi` 的成员：那个类已经 574 行、在体量评审的
 * Q2 清单里。这三个命令是一组自洽的域（连 PC → 列实例 → 拉实例），
 * 拆到这里既不撑大原类，调用处写法也完全不变（`api.lanConnect(...)`）。
 *
 * 服务端是桌面端起的 HTTP 服务：`GET /api/info`、`GET /api/instances`、
 * `GET /api/instances/:id`（见 `src-tauri/src/lan_sync.rs`）。
 */

/** host / port / token 三件套。 */
private fun lanEndpoint(host: String, port: Int, token: String): JSONObject =
    JSONObject().put("host", host).put("port", port).put("token", token)

/** 验证 token + 拿 PC 设备信息（device / version / instance_count）。 */
fun QookixApi.lanConnect(
    host: String,
    port: Int,
    token: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj("lan_connect", lanEndpoint(host, port, token), onResult)

/** 拿 PC 上的实例列表。 */
fun QookixApi.lanListInstances(
    host: String,
    port: Int,
    token: String,
    onResult: (Result<JSONArray>) -> Unit,
) = callArr("lan_list_instances", lanEndpoint(host, port, token), onResult)

/**
 * 流式下载 + 解压一个实例到本地。
 *
 * 进度走 `lan://progress` 事件（订阅见 `lansync/LanSyncPage.kt`），
 * 不在这层返回。成功时 result 是 `{instanceId: "新实例id"}`。
 */
fun QookixApi.lanDownloadInstance(
    host: String,
    port: Int,
    token: String,
    instanceId: String,
    instanceName: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj(
    "lan_download_instance",
    lanEndpoint(host, port, token)
        .put("instanceId", instanceId)
        .put("instanceName", instanceName),
    onResult,
)
