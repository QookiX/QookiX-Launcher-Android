package com.zhayi.qookix.compose.bridge

import org.json.JSONObject
import org.json.JSONArray

/**
 * 系统能力域（屏幕方向 / 应用自更新 / 杂项）（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 */

// ── 应用自我更新（命令名核对自 lib.rs 的 updater::）──────────────────────

/**
 * 检查启动器本体是否有新版本。
 *
 * 注意与 `check_updates` 区分：那个是检查**实例内的模组**更新，
 * 这个是检查**启动器应用本身**。
 */
fun QookixApi.checkForUpdate(onResult: (Result<JSONObject>) -> Unit) =
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
fun QookixApi.downloadUpdate(version: String, onResult: (Result<String>) -> Unit = {}) =
    call(
        "download_update",
        JSONObject().put("version", version),
        onResult,
    )
/** 拉起系统安装器装已下载的 APK。 */
fun QookixApi.installUpdate(path: String, onResult: (Result<String>) -> Unit = {}) =
    call("install_update", JSONObject().put("path", path), onResult)

// ── 杂项 ──────────────────────────────────────────────────────────────

/** 写文本文件（导出配置等）。 */
fun QookixApi.saveTextFile(path: String, content: String, onResult: (Result<String>) -> Unit = {}) =
    call("save_text_file", JSONObject().put("path", path).put("content", content), onResult)

/** 把 content:// 解析成真实路径。 */
fun QookixApi.resolvePickedPath(path: String, onResult: (Result<String>) -> Unit) =
    call("resolve_picked_path", JSONObject().put("path", path), onResult)

/** 读剪贴板（Rust 侧走宿主回调）。 */
fun QookixApi.readClipboard(onResult: (Result<String>) -> Unit) =
    call("read_clipboard", null, onResult)

/** 写剪贴板。 */
fun QookixApi.writeClipboard(text: String, onResult: (Result<String>) -> Unit = {}) =
    call("write_clipboard", JSONObject().put("text", text), onResult)

/** 前端调试日志（进 Rust 侧日志，便于排查）。 */
fun QookixApi.logDebug(msg: String, onResult: (Result<String>) -> Unit = {}) =
    call("log_debug", JSONObject().put("msg", msg), onResult)

/** 关闭插件初始化引导（用户已看过）。 */
fun QookixApi.dismissPluginSetup(onResult: (Result<String>) -> Unit = {}) =
    call("dismiss_plugin_setup", null, onResult)

/** FRP 状态。 */
fun QookixApi.frpStatus(onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_status", null, onResult)

/** FRP 隧道列表。 */
fun QookixApi.frpTunnels(onResult: (Result<JSONArray>) -> Unit) =
    callArr("frp_tunnels", null, onResult)

/**
 * FRP 面板的用户信息（邮箱、余额等）。
 *
 * 与 `frp_status` 的区别：那个只给「登没登录 / 用户名」，
 * 邮箱与余额要靠这条（旧 UI 的 FrpView 就是这么分两步拉的）。
 */
fun QookixApi.frpUserInfo(onResult: (Result<JSONObject>) -> Unit) =
    callObj("frp_user_info", null, onResult)

// ── 屏幕方向（原生能力）──────────────────────────────────────────────

/**
 * 设置启动器界面方向。
 *
 * 注意：这个命令在**纯 Compose 界面下无意义**（旧实现用它控制 WebView
 * 的方向），保留只是为了设置页里的开关能继续工作。
 */
fun QookixApi.setOrientation(mode: String, onResult: (Result<String>) -> Unit = {}) =
    call("set_orientation", JSONObject().put("mode", mode), onResult)

