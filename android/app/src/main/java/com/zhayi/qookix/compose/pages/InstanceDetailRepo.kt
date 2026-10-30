package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.R
import android.util.Log
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * 实例详情的数据层（7 个 tab）。
 *
 * ── 返回形状逐个核实过（本项目已被"形状和直觉不符"坑过多次）────────────
 *
 * | 命令 | 返回 | 注意 |
 * |---|---|---|
 * | `list_instance_files` | `{ "files": [ … ] }` | **包了一层**，且每项用 `isDir`（camelCase，json! 手写） |
 * | `list_world_backups` | `Vec<BackupInfo>` | 需要 `world` 参数，不只是 instanceId |
 * | `list_control_layouts` | 对象数组 | 字段待运行时确认，解析时做兼容 |
 * | `list_content` | 对象数组 | 同上 |
 *
 * **规律**：凡是 `json!(...)` 宏手写的返回是 camelCase，
 * 结构体 `#[derive(Serialize)]` 序列化的默认是 snake_case。
 * 所以解析时两种都试。
 */
class InstanceDetailRepo(private val api: QookixApi, private val context: android.content.Context) {

    companion object {
        private const val TAG = "InstanceDetailRepo"
        /** `list_instance_files` 的白名单目录（见 commands.rs::SUBFOLDERS）。 */
        const val SUB_SAVES = "saves"
        const val SUB_MODS = "mods"
        const val SUB_RESOURCEPACKS = "resourcepacks"
        const val SUB_SHADERPACKS = "shaderpacks"
    }

    /** 基本信息。 */
    fun loadInfo(instanceId: String, onResult: (Result<Pair<String, String>>) -> Unit) {
        api.getInstance(instanceId) { r ->
            onResult(
                r.mapCatching { o ->
                    val name = o.optString("name").ifBlank { o.optString("version_id") }
                    val loader = o.optString("loader").ifBlank { "vanilla" }
                    // ⚠️ 2026-10-29（竖屏检查）：原来只取 `version_id`（历史字段），
                    // 而当前 Rust 侧的 MC 版本在 `mc_version` —— 于是详情页 hero
                    // 的副标题只剩「Fabric」，看不到 1.21.1。
                    //
                    // 改成和实例列表 `InstancesRepo.subtitleOf` 完全一致的规则：
                    //   · MC 版本：mc_version 优先，回退 version_id
                    //   · 加载器：有 loader_version 才拼上（原版没有版本号）
                    // 分隔符用「·」，和 hero 里「加载器 · MC 版本」的语义吻合。
                    val mcVer = o.optString("mc_version").ifBlank { o.optString("version_id") }
                    val loaderVer = o.optString("loader_version")
                    val loaderText = if (loader.equals("vanilla", true) || loaderVer.isBlank()) {
                        loaderLabel(loader)
                    } else {
                        "${loaderLabel(loader)} $loaderVer"
                    }
                    name to listOf(loaderText, mcVer)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }
            )
        }
    }

    /** 存档列表（`saves` 子目录）。 */
    fun loadWorlds(instanceId: String, onResult: (Result<List<WorldUi>>) -> Unit) {
        listSub(instanceId, SUB_SAVES) { r ->
            val worlds = r.mapCatching { files ->
                files.mapNotNull { o ->
                    // 只有目录才是存档
                    val isDir = o.optBoolean("isDir", o.optBoolean("is_dir", false))
                    if (!isDir) return@mapNotNull null
                    val name = o.optString("name")
                    if (name.isBlank()) return@mapNotNull null
                    WorldUi(
                        name = name,
                        meta = fmtBytes(o.optLong("size", 0)) + " · " +
                            fmtRelative(context, o.optLong("modified", 0)),
                    )
                }
            }
            // ⚠️ 2026-10-29（外部审计 B22，真机实测确认）：
            // `WorldUi.backups` 以前**从来没有任何写入点** —— `loadBackups()`
            // 写好了却没人调。于是：点「备份」→ Rust 真的生成了 zip（实测
            // backups/<世界>/xxx-时间戳.zip），但界面永远不显示那条备份，
            // 也没有「恢复最新备份」按钮。用户看到的就是「备份了但没反应」。
            // 这里给每个世界补拉一次备份列表，全部回来后再交付。
            val base = worlds.getOrNull()
            if (base == null || base.isEmpty()) {
                onResult(worlds)
                return@listSub
            }
            val filled = base.toMutableList()
            var remaining = base.size
            base.forEachIndexed { idx, w ->
                loadBackups(instanceId, w.name) { br ->
                    br.getOrNull()?.let { list -> filled[idx] = w.copy(backups = list) }
                    remaining -= 1
                    if (remaining == 0) onResult(Result.success(filled.toList()))
                }
            }
        }
    }

    /** 某个存档的备份列表。 */
    fun loadBackups(
        instanceId: String,
        world: String,
        onResult: (Result<List<String>>) -> Unit,
    ) {
        api.listWorldBackups(instanceId, world) { r ->
            onResult(
                r.mapCatching { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        // BackupInfo 的字段名可能是 file / filename / name
                        o.optString("filename")
                            .ifBlank { o.optString("file") }
                            .ifBlank { o.optString("name") }
                            .takeIf { it.isNotBlank() }
                    }
                }
            )
        }
    }

    /** 控制布局列表。 */
    fun loadLayouts(onResult: (Result<List<LayoutUi>>) -> Unit) {
        api.listControlLayouts { r ->
            onResult(
                r.mapCatching { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        // 可能是 {"name": "..."} 或 {"name":..., "current":true}
                        val name = o.optString("name").ifBlank { o.optString("file") }
                        if (name.isBlank()) return@mapNotNull null
                        LayoutUi(
                            name = name.removeSuffix(".json"),
                            meta = o.optString("meta"),
                            current = o.optBoolean("current", false),
                        )
                    }
                }
            )
        }
    }

    /** 已装内容（模组/资源包/光影）。 */
    fun loadContent(kind: String, instanceId: String, onResult: (Result<List<ContentUi>>) -> Unit) {
        // ⚠️ 三个易错点（都核对过 Rust 源码）：
        //   1. **必须把 kind 传给后端**（漏了 → 已装模组但列表是空的）；
        //   2. 返回是对象 `{items: [...], onDisk: [...]}`；
        //   3. **每一项是 `{record: {...}, exists: bool}`** ——
        //      record 里才是 filename/name/version 这些字段。
        //      之前直接读外层，字段全取不到，所以永远是空列表。
        api.listContent(instanceId, kind) { r ->
            onResult(
                r.mapCatching { obj ->
                    val arr = obj.optJSONArray("items") ?: org.json.JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val wrapper = arr.optJSONObject(i) ?: return@mapNotNull null
                        val rec = wrapper.optJSONObject("record") ?: wrapper
                        val filename = rec.optString("filename")
                        if (filename.isBlank()) return@mapNotNull null
                        val exists = wrapper.optBoolean("exists", true)
                        val disabled = filename.endsWith(".disabled")
                        // ⚠️ 2026-10-28（竖屏审计）：`optString` 遇到 JSON null 会返回
                        // 字面量 "null"，模组卡副标题就显示成 "null"（真机截图）。
                        // 元数据缺失的模组（手动丢进 mods/ 的 jar）全都中招。
                        val name = rec.optText("name")
                            ?: filename.removeSuffix(".disabled")
                        ContentUi(
                            rel = filename,
                            name = name.removeSuffix(".disabled"),
                            meta = buildString {
                                val v = rec.optText("version")
                                if (!v.isNullOrBlank()) append(v)
                                val src = rec.optText("source")
                                if (!src.isNullOrBlank() && src != "manual") {
                                    if (isNotEmpty()) append(" · ")
                                    append(src)
                                }
                                if (!exists) {
                                    if (isNotEmpty()) append(" · ")
                                    append(context.getString(R.string.repair_file_missing))
                                }
                                if (disabled) {
                                    if (isNotEmpty()) append(" · ")
                                    append(context.getString(R.string.repair_disabled))
                                }
                            },
                            enabled = !disabled,
                            updatable = rec.optString("project_id").isNotBlank() && exists,
                            // ── apply_update 需要的三个字段 ───────────────────
                            // Rust 的 `apply_update` 要 6 个参数，其中 provider /
                            // project_id / new_version_id 只有这里能拿到。
                            // `source` 字段（modrinth/curseforge/manual）就是 provider。
                            provider = rec.optString("source").ifBlank { "modrinth" },
                            projectId = rec.optString("project_id"),
                            versionId = rec.optString("version_id"),
                        )
                    }
                },
            )
        }
    }

    /** 启动游戏。 */
    /**
     * 启动游戏 —— **完整流程**，不是裸调 launch_game。
     *
     * 对齐旧 UI `stores/instances.ts` 的 `launch()`：
     *
     *   1. **检查文件完整性**（`check_instance_files`）
     *   2. 缺文件且**可修**（`fixable`）→ 先 `repair_instance_files` 补全
     *   3. 缺文件但**不可修** → 直接把后端给的 `advice` 抛给用户
     *      （比如加载器版本号为空，补全也修不好，得换游戏版本）
     *   4. 才真正 `launch_game`
     *   5. **错误码翻译**成中文
     *
     * ── 为什么必须有第 1~3 步 ────────────────────────────────────────────
     * 用户反馈「启动不了」的很大一部分原因在这：
     * 下载中断/不完整时裸调 launch_game 必然失败，而用户看到的是
     * 一串英文常量（`INSTANCE_NOT_INSTALLED`），既看不懂也不知道
     * 「其实补一下就能玩」。
     *
     * 旧 UI 的做法是**静默补全**：检查 → 缺就下（进度走下载页）→ 再启动。
     * 用户感觉是「点了启动，等一会儿就进游戏了」。
     *
     * @param onStage 给界面回传当前阶段文案（检查中/补全中），可为空
     */
    fun launch(
        instanceId: String,
        /** 世界名（quick-play）：给了就直接进这个世界。 */
        world: String? = null,
        onStage: (String) -> Unit = {},
        onResult: (Result<String>) -> Unit = {},
    ) {
        android.util.Log.i("QkLaunch", "repo.launch start $instanceId")
        onStage(context.getString(R.string.stage_checking_files))
        api.checkInstanceFiles(instanceId) { cr ->
            android.util.Log.i(
                "QkLaunch",
                "checkInstanceFiles: " +
                    (cr.getOrNull()?.toString() ?: cr.exceptionOrNull()?.message),
            )
            // 检查本身失败**不该挡住启动**（比如后端读不了磁盘）——
            // 交给 launch_game 去报真正的错，别在这里吞掉
            val report = cr.getOrNull()
            if (report == null || report.optBoolean("can_launch", false)) {
                doLaunch(instanceId, world, onStage, onResult)
                return@checkInstanceFiles
            }

            // 缺文件：看有没有「可修」的项
            val missing = report.optJSONArray("missing")
            val fixable = (0 until (missing?.length() ?: 0)).any { i ->
                missing?.optJSONObject(i)?.optBoolean("fixable", false) == true
            }
            if (!fixable) {
                // 一项都补不了就别让用户白等几百 MB
                val advice = report.optString("advice")
                    .ifBlank { context.getString(R.string.repair_cannot_fix) }
                onResult(Result.failure(IllegalStateException(advice)))
                return@checkInstanceFiles
            }

            onStage(context.getString(R.string.stage_repairing))
            api.repairInstanceFiles(instanceId) { rr ->
                rr.fold(
                    onSuccess = { doLaunch(instanceId, world, onStage, onResult) },
                    onFailure = { e ->
                        onResult(
                            Result.failure(
                                IllegalStateException(context.getString(R.string.repair_failed_msg, e.message)),
                            ),
                        )
                    },
                )
            }
        }
    }

    /** 真正调 launch_game，并把后端的英文错误码翻成人话。 */
    private fun doLaunch(
        instanceId: String,
        world: String?,
        onStage: (String) -> Unit,
        onResult: (Result<String>) -> Unit,
    ) {
        onStage(context.getString(R.string.stage_starting))
        // ⚠️⚠️ world 的传法（曾经错得最隐蔽的一处）：
        //
        // 这里第二参**是 world，不是 accountUuid**。
        // Rust 原文（`src-tauri/src/commands.rs:196-197`）：
        //   pub async fn launch_game(instance_id: String, world: Option<String>, server: Option<String>)
        //       -> Result<GameStatus, String>
        //
        // 签名里**根本没有 accountUuid** —— 账号由后端自己 resolve_launch_account
        // （实例 account_id → settings.selected_account → 列表第一个）决定，
        // 前端改账号只能走 selectAccount()。
        //
        // 曾经 QookixApi.launchGame 的形参叫 accountUuid，调用方塞进去的是世界名：
        // 参数名对不上 → Tauri 严格反序列化把 world **静默丢弃**（不报错），
        // 于是「进入世界」永远进主菜单。
        //
        // ⚠️ 后端限制（**未修改 Rust**，按任务要求只记录）：
        // commands.rs:198-199 的安卓分支写着
        //     // 安卓端暂不支持「快速进入世界/服务器」，参数保留以兼容前端签名
        //     let _ = (world, server);
        // 即 **Android 上 world 目前仍会被后端丢弃**。
        // 本次修复保证参数**传到位、名字对得上**；真正让安卓支持 quick-play
        // 需要改 Rust 的 GameActivity 启动链路，不在本次改动范围内。
        api.launchGame(instanceId, world) { r ->
            onResult(
                r.recoverCatching { e ->
                    // 旧 UI 把这几个码翻译成中文提示 —— 不翻译的话
                    // 用户只会看到 INSTANCE_NOT_INSTALLED 这种英文常量
                    val raw = e.message ?: ""
                    throw IllegalStateException(
                        when {
                            raw.contains("INSTANCE_NOT_INSTALLED") ->
                                context.getString(R.string.launch_err_not_installed)
                            raw.contains("GAME_ALREADY_RUNNING") ->
                                context.getString(R.string.launch_err_already_running)
                            raw.contains("INSTANCE_CONFIG_CORRUPT") ->
                                context.getString(R.string.launch_err_config_broken)
                            // 没账号时后端直接拒绝，不再拉起 GameActivity（见 commands.rs）
                            raw.contains("NO_ACCOUNT") ->
                                context.getString(R.string.no_account_hint)
                            else -> raw
                        },
                    )
                },
            )
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 调 `list_instance_files`：注意参数名是 `sub`（不是 rel），且返回包了一层 `files`。 */
    private fun listSub(
        instanceId: String,
        sub: String,
        onResult: (Result<List<JSONObject>>) -> Unit,
    ) {
        api.callRaw(
            "list_instance_files",
            JSONObject().put("instanceId", instanceId).put("sub", sub),
        ) { r ->
            onResult(
                r.mapCatching { json ->
                    val arr: JSONArray = try {
                        JSONObject(json).optJSONArray("files") ?: JSONArray()
                    } catch (e: Exception) {
                        // 兜底：万一将来改成裸数组
                        JSONArray(json)
                    }
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
            )
        }
    }

    private fun loaderLabel(loader: String): String = when (loader.lowercase()) {
        "vanilla" -> context.getString(R.string.loader_vanilla)
        "fabric" -> "Fabric"
        "forge" -> "Forge"
        "neoforge" -> "NeoForge"
        "quilt" -> "Quilt"
        "optifine" -> "OptiFine"
        else -> loader
    }
}







