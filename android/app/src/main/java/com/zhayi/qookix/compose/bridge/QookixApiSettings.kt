package com.zhayi.qookix.compose.bridge

import org.json.JSONObject
import org.json.JSONArray

/**
 * 设置页第二组的 API 包装（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 * 这里放「设置」页后半部分用到的命令（游戏目录/内存/镜像/更新通道…）。
 * 命令名逐个核对自 Rust 注册表与 src/api.ts 的 SettingsView 用点。
 */

// ── 设置页第二组（命令名逐个核对自 src/api.ts 的 SettingsView 用点）──────
//
// 这一组是**补齐**：设置页界面已画好，但不少按钮原本是空壳
// （只有 `/* TODO */`）。核对旧设置页发现它用了 31 个命令，
// 而 Kotlin 侧只覆盖了 6 个 —— 缺 24 个。这里全部补上。

/** 清缓存。 */
fun QookixApi.clearCache(onResult: (Result<String>) -> Unit = {}) =
    call("clear_cache", null, onResult)

/** 清翻译缓存（可指定服务，null = 全部）。 */
fun QookixApi.clearTranslationCache(service: String?, onResult: (Result<String>) -> Unit = {}) =
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
fun QookixApi.sharedGameUsage(instanceId: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("shared_game_usage", JSONObject().put("instanceId", instanceId), onResult)

/**
 * 用系统文件管理器打开服务器的某个子目录（如 plugins）。
 * `sub` 为 null 表示服务器根目录。
 */
fun QookixApi.openHostedServerFolder(
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
fun QookixApi.detectSystemProxy(onResult: (Result<String>) -> Unit) =
    call("detect_system_proxy", null, onResult)

/** 控制按钮列表（按键页用）。 */
fun QookixApi.getControlButtons(onResult: (Result<JSONArray>) -> Unit) =
    callArr("get_control_buttons", null, onResult)

/** 游戏目录可选项。 */
fun QookixApi.getGameDirOptions(onResult: (Result<JSONArray>) -> Unit) =
    callArr("get_game_dir_options", null, onResult)

/** 当前游戏目录状态。 */
fun QookixApi.getGameDirState(onResult: (Result<JSONObject>) -> Unit) =
    callObj("get_game_dir_state", null, onResult)

/** 插件清单地址。 */
fun QookixApi.getPluginManifestUrl(onResult: (Result<String>) -> Unit) =
    call("get_plugin_manifest_url", null, onResult)

/** 从文件导入背景图（sourcePath 为刚选中的图片路径）。 */
fun QookixApi.importBackgroundImage(sourcePath: String, onResult: (Result<String>) -> Unit = {}) =
    call("import_background_image", JSONObject().put("sourcePath", sourcePath), onResult)

/** 从清单安装插件。 */
fun QookixApi.installPlugin(id: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("install_plugin", JSONObject().put("id", id), onResult)

/** 从本地文件安装插件。 */
fun QookixApi.installPluginFromFile(path: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("install_plugin_from_file", JSONObject().put("path", path), onResult)

/** 镜像源列表。 */
fun QookixApi.listMirrors(onResult: (Result<JSONArray>) -> Unit) =
    callArr("list_mirrors", null, onResult)

/** 弹「选择游戏目录」（结果用 takePickedGameDir 取）。 */
fun QookixApi.pickGameDir(onResult: (Result<String>) -> Unit = {}) =
    call("pick_game_dir", null, onResult)

/** 取走刚选的游戏目录。 */
/**
 * 取走刚选中的游戏目录（没选过时为 null）。
 *
 * ⚠️ Rust 返回 `Option<String>` —— 之前用 callObj 会报
 *「String cannot be converted to JSONObject」。
 */
fun QookixApi.takePickedGameDir(onResult: (Result<String>) -> Unit) =
    call("take_picked_game_dir", null, onResult)

/** 刷新存储统计。 */
fun QookixApi.refreshStorageStats(onResult: (Result<JSONObject>) -> Unit) =
    callObj("refresh_storage_stats", null, onResult)

/** 请求「所有文件访问」权限（弹系统页）。 */
fun QookixApi.requestAllFilesAccess(onResult: (Result<String>) -> Unit = {}) =
    call("request_all_files_access", null, onResult)

/**
 * 设置某个控制按钮是否透传（不拦截触摸）。
 *
 * ⚠️ Rust 返回的是**改完后的完整按钮列表**（`Vec<ControlButtonInfo>`，
 * controls.rs:67），不是字符串 —— 以前用 call 会得到 JSON 数组文本。
 */
fun QookixApi.setControlButtonPassthru(
    index: Int,
    enabled: Boolean,
    onResult: (Result<JSONArray>) -> Unit = {},
) = callArr(
    "set_control_button_passthru",
    JSONObject().put("index", index).put("enabled", enabled),
    onResult,
)

/** 设置游戏目录（migrate = 是否迁移现有数据）。 */
fun QookixApi.setGameDir(root: String, migrate: Boolean, onResult: (Result<String>) -> Unit = {}) =
    call("set_game_dir", JSONObject().put("root", root).put("migrate", migrate), onResult)

/** 启用/禁用插件。 */
fun QookixApi.setPluginEnabled(id: String, enabled: Boolean, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("set_plugin_enabled", JSONObject().put("id", id).put("enabled", enabled), onResult)

/** 设置插件清单地址。 */
fun QookixApi.setPluginManifestUrl(url: String, onResult: (Result<String>) -> Unit = {}) =
    call("set_plugin_manifest_url", JSONObject().put("url", url), onResult)

/** 读 Pojav 启动偏好（渲染器、分辨率比例等都在这里）。 */
fun QookixApi.getPojavPrefs(onResult: (Result<JSONObject>) -> Unit) =
    callObj("get_pojav_prefs", null, onResult)

/** 写 Pojav 启动偏好（patch 语义）。 */
fun QookixApi.setPojavPrefs(patch: JSONObject, onResult: (Result<String>) -> Unit = {}) =
    call("set_pojav_prefs", JSONObject().put("patch", patch), onResult)

/** 测镜像源延迟。 */
fun QookixApi.testMirror(base: String, onResult: (Result<JSONObject>) -> Unit) =
    callObj("test_mirror", JSONObject().put("base", base), onResult)

/** 测代理连通性。 */
fun QookixApi.testProxy(
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
fun QookixApi.testTranslateApi(
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
fun QookixApi.uninstallPlugin(id: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("uninstall_plugin", JSONObject().put("id", id), onResult)

/** 打开外部链接（桌面仓库、许可证等）。 */
fun QookixApi.openUrl(url: String, onResult: (Result<String>) -> Unit = {}) =
    call("plugin:opener|open_url", JSONObject().put("url", url), onResult)
