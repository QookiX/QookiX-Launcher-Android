package com.zhayi.qookix.compose.bridge

import org.json.JSONObject
import org.json.JSONArray

/**
 * 实例详情各 tab 的 API 包装（从 QookixApi.kt 拆出）。
 *
 * 拆法与 QookixApiSkin.kt 一致：按域改成 extension 函数，调用点不变。
 * 这里放实例详情页 tab 用到的命令（日志/崩溃/文件/内容/按键…）。
 * 命令名逐个核对自 Rust 注册表与 src/api.ts，不是猜的。
 */

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
fun QookixApi.listContent(instanceId: String, kind: String, onResult: (Result<JSONObject>) -> Unit) =
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
fun QookixApi.toggleContentEnabled(
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
fun QookixApi.uninstallContent(
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
fun QookixApi.checkUpdates(instanceId: String, kind: String, onResult: (Result<JSONArray>) -> Unit) =
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
fun QookixApi.applyUpdate(
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
fun QookixApi.identifyContent(instanceId: String, kind: String, onResult: (Result<String>) -> Unit = {}) =
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
fun QookixApi.listWorldBackups(instanceId: String, world: String, onResult: (Result<JSONArray>) -> Unit) =
    callArr(
        "list_world_backups",
        JSONObject().put("instanceId", instanceId).put("world", world),
        onResult,
    )

/** 给某个存档创建备份。 */
fun QookixApi.createWorldBackup(
    instanceId: String,
    world: String,
    onResult: (Result<String>) -> Unit = {},
) = call(
    "create_world_backup",
    JSONObject().put("instanceId", instanceId).put("world", world),
    onResult,
)

/** 从备份恢复存档（需要 world + filename）。 */
fun QookixApi.restoreWorldBackup(
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
fun QookixApi.listInstanceFiles(instanceId: String, sub: String = "", onResult: (Result<JSONArray>) -> Unit) =
    callObj("list_instance_files", JSONObject().put("instanceId", instanceId).put("sub", sub)) { r ->
        onResult(r.mapCatching { it.optJSONArray("files") ?: JSONArray() })
    }

fun QookixApi.deleteWorldBackup(
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
fun QookixApi.listControlLayouts(onResult: (Result<JSONArray>) -> Unit) =
    callArr("list_control_layouts", null, onResult)

fun QookixApi.setCurrentControlLayout(name: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("set_current_control_layout", JSONObject().put("name", name), onResult)

/**
 * 重命名布局。
 *
 * ⚠️ Rust 签名是 `rename_control_layout(name, new_name)`（controls.rs:370-373）。
 * 以前发的是 `from` / `to` —— **两个键名全错**，报
 * `missing required key name` → 重命名永久失败。
 */
fun QookixApi.renameControlLayout(name: String, newName: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr(
        "rename_control_layout",
        JSONObject().put("name", name).put("newName", newName),
        onResult,
    )

fun QookixApi.deleteControlLayout(name: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr("delete_control_layout", JSONObject().put("name", name), onResult)

/**
 * 复制布局到新名字。
 *
 * ⚠️ Rust 签名是 `duplicate_control_layout(name, new_name)`（controls.rs:350-353），
 * **必须两个参数**。以前只发 `name`，报
 * `missing required key newName` → 复制功能永久失败。
 */
fun QookixApi.duplicateControlLayout(name: String, newName: String, onResult: (Result<JSONArray>) -> Unit = {}) =
    callArr(
        "duplicate_control_layout",
        JSONObject().put("name", name).put("newName", newName),
        onResult,
    )
fun QookixApi.exportControlLayout(name: String, onResult: (Result<String>) -> Unit = {}) =
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
fun QookixApi.pickControlLayout(onResult: (Result<String>) -> Unit = {}) =
    call("pick_control_layout", null, onResult)

/** 取走导入结果（若 Rust 侧是两步式）。 */
fun QookixApi.takeControlImport(onResult: (Result<String>) -> Unit = {}) =
    call("take_control_import", null, onResult)

/** 按路径导入控制布局（已知路径时用）。 */
fun QookixApi.importControlLayoutByPath(path: String, onResult: (Result<String>) -> Unit = {}) =
    call("import_control_layout_by_path", JSONObject().put("path", path), onResult)

/** 打开 Pojav 的控制布局编辑器（原生 Activity）。 */
fun QookixApi.openControlLayoutEditor(
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
fun QookixApi.planModpackExport(
    instanceId: String,
    onResult: (Result<JSONObject>) -> Unit,
) = callObj("plan_modpack_export", JSONObject().put("instanceId", instanceId), onResult)

/**
 * 把实例导出成 `.mrpack` 并调起系统分享。
 *
 * ⚠️ Rust 返回 `ExportResult` **对象**（path/file_name/linked/bundled/bytes），
 * 不是字符串 —— 之前用 call 收，类型不匹配。
 */
fun QookixApi.exportModpack(
    instanceId: String,
    options: JSONObject,
    onResult: (Result<JSONObject>) -> Unit = {},
) = callObj(
    "export_modpack",
    JSONObject().put("instanceId", instanceId).put("options", options),
    onResult,
)
