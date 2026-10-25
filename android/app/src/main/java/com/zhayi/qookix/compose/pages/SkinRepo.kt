package com.zhayi.qookix.compose.pages

import android.util.Log
import com.zhayi.qookix.compose.bridge.QookixApi
// 皮肤/披风域已拆到 QookixApiSkin.kt（extension），跨包调用要显式 import
import com.zhayi.qookix.compose.bridge.applySkinOffline
import com.zhayi.qookix.compose.bridge.applySkinToAccount
import com.zhayi.qookix.compose.bridge.fetchPlayerSkin
import com.zhayi.qookix.compose.bridge.getOfflineSkin
import com.zhayi.qookix.compose.bridge.listSkins
import com.zhayi.qookix.compose.bridge.readSkinDataUrl
import com.zhayi.qookix.compose.bridge.saveSkinFromData
import org.json.JSONArray
import org.json.JSONObject

/**
 * 皮肤页的数据层。
 *
 * ── 关于 3D 预览（重要设计决定）────────────────────────────────────────
 * 旧界面用 `skinview3d`（Three.js 封装）做 3D 预览 —— 那是 WebGL 技术，
 * Compose 里**没有等价物**。
 *
 * 三个选项与取舍：
 *   1. 嵌 `GLSurfaceView` 自己写 OpenGL ES 渲染 —— 效果一致，但要写一整套
 *      骨骼/贴图/光照代码，工作量大且容易出兼容问题
 *   2. 嵌一个小 WebView 跑 skinview3d —— 效果一致，但为了一块预览
 *      留一个 WebView 进程（而整个目标就是去掉 WebView）
 *   3. **降级成 2D 展开图**（本实现）
 *
 * 选 3 的理由：皮肤预览是**低频的展示功能**，用户要看的是「这张皮肤长什么样」，
 * 2D 展开图（正面/背面/侧面三视图）能完整表达这一点；
 * 而 3D 旋转属于锦上添花，不值得为它保留 WebView 或写一套 GL 渲染。
 *
 * 关键前提：`read_skin_data_url` 返回的是 **data URL**（base64 PNG），
 * 所以 Coil 能直接显示，不需要额外解码 —— 这让 2D 方案的成本极低。
 */
class SkinRepo(private val api: QookixApi, private val context: android.content.Context) {

    companion object {
        private const val TAG = "SkinRepo"
    }

    /** 本地皮肤库。 */
    fun listSkins(onResult: (Result<List<SkinEntryUi>>) -> Unit) {
        api.listSkins { r ->
            onResult(
                r.mapCatching { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val filename = o.optString("filename")
                        if (filename.isBlank()) return@mapNotNull null
                        SkinEntryUi(
                            name = o.optString("name").ifBlank { filename },
                            filename = filename,
                            size = o.optLong("size", 0L),
                            modified = o.optLong("modified", 0L),
                        )
                    }
                }
            )
        }
    }

    /** 读某张皮肤的图片（data URL）。 */
    fun loadImage(filename: String, onResult: (Result<String>) -> Unit) {
        api.readSkinDataUrl(filename) { r ->
            r.onFailure { Log.w(TAG, "读皮肤 $filename 失败: ${it.message}") }
            onResult(r)
        }
    }

    /** 读当前账号的皮肤（离线账号）。 */
    fun loadCurrentSkin(uuid: String, onResult: (Result<String?>) -> Unit) {
        api.getOfflineSkin(uuid) { r ->
            onResult(
                r.mapCatching { obj ->
                    // 可能是 { filename } 或 { data } 或空对象
                    val filename = obj.optString("filename").ifBlank { obj.optString("name") }
                    filename.takeIf { it.isNotBlank() }
                }
            )
        }
    }

    /** 给账号应用皮肤。 */
    /**
     * 按**正版玩家名**从 Mojang 拉一张皮肤下来存进皮肤库。
     *
     * 对应旧 UI `SkinView.vue` 的 `fetchPlayer()`。用途：
     * 想把朋友的皮肤拿来用、或自己正版号的皮肤在这台设备上没同步时。
     *
     * ⚠️ 正版账号的皮肤保存在**官方服务器**上，本地那份 `getOfflineSkin` 是空的 ——
     * 所以只看本地的话正版账号永远显示空白（旧注释原话）。
     */
    fun fetchPlayerSkin(
        username: String,
        onResult: (Result<PlayerSkinUi>) -> Unit,
    ) {
        api.fetchPlayerSkin(username.trim()) { r ->
            r.fold(
                onSuccess = { o ->
                    onResult(
                        Result.success(
                            PlayerSkinUi(
                                dataUrl = o.optString("data_url"),
                                model = o.optString("model").ifBlank { "default" },
                                capeDataUrl = o.optString("cape_data_url"),
                            ),
                        ),
                    )
                },
                onFailure = { e -> onResult(Result.failure(e)) },
            )
        }
    }


    /**
     * 把皮肤库里的某个文件应用到账号。
     *
     * @param uuid 账号 uuid
     * @param filename 皮肤文件名（在 `files/skins/` 下）
     * @param online 正版账号走上传，离线账号写本地
     * @param slim true = 纤细模型（slim），false = 经典（classic）
     *
     * ⚠️ 2026-10-17 修（真机发现的契约缺陷）：
     * 离线分支原来直接传 `filename`，而 Rust `apply_skin_offline` 要的是
     * **皮肤 base64 数据** + variant + uuid（commands.rs:996-1000）——
     * 于是点「应用到账号」报 `missing required key skinData`，皮肤永远应用不上。
     *
     * 现在先用 `readSkinDataUrl` 把文件读成 data URL 再传。
     */
    fun applyToAccount(
        uuid: String,
        filename: String,
        online: Boolean,
        slim: Boolean = false,
        onResult: (Result<String>) -> Unit = {},
    ) {
        // variant 两个分支都要用（Rust 存皮肤/上传皮肤都记模型）
        val variant = if (slim) "slim" else "classic"
        if (online) {
            // ⚠️ 正版上传同样要 skinData（不是 filename）+ variant + accountUuid
            // （commands.rs:979-985）。与离线分支同构。
            api.readSkinDataUrl(filename) { r ->
                r.fold(
                    onSuccess = { dataUrl ->
                        api.applySkinToAccount(uuid, dataUrl, variant, onResult)
                    },
                    onFailure = { e -> onResult(Result.failure(e)) },
                )
            }
        } else {
            // 先读文件 → data URL，再交给 Rust 解码落盘
            api.readSkinDataUrl(filename) { r ->
                r.fold(
                    onSuccess = { dataUrl ->
                        api.applySkinOffline(dataUrl, variant, uuid, onResult)
                    },
                    onFailure = { e -> onResult(Result.failure(e)) },
                )
            }
        }
    }

    /** 从文件保存（走系统文件选择器拿到 data URL 后调）。 */
    fun saveFromData(
        name: String,
        dataUrl: String,
        onResult: (Result<String>) -> Unit = {},
    ) = api.saveSkinFromData(name, dataUrl, onResult)

    /** 解析皮肤库列表的兜底（万一返回包了一层）。 */
    fun parseSkinArray(json: String): List<SkinEntryUi> {
        return try {
            val trimmed = json.trim()
            val arr: JSONArray = if (trimmed.startsWith("{")) {
                val o = JSONObject(trimmed)
                o.optJSONArray("skins") ?: o.optJSONArray("entries") ?: JSONArray()
            } else {
                JSONArray(trimmed)
            }
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val filename = o.optString("filename").ifBlank { o.optString("name") }
                if (filename.isBlank()) return@mapNotNull null
                SkinEntryUi(
                    name = o.optString("name").ifBlank { filename },
                    filename = filename,
                    size = o.optLong("size", 0L),
                    modified = o.optLong("modified", 0L),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析皮肤列表失败", e)
            emptyList()
        }
    }
}

/** 皮肤条目（对应 `skins.rs::SkinEntry`）。 */
data class SkinEntryUi(
    val name: String,
    val filename: String,
    val size: Long = 0L,
    val modified: Long = 0L,
) {
    /** 副标题：「834 B · 2 天前」（需要 Context，所以在 UI 层调用）。 */
    fun subtitle(ctx: android.content.Context): String = buildString {
        append(fmtBytes(size))
        val ago = fmtRelative(ctx, modified)
        if (ago.isNotBlank()) append(" · ").append(ago)
    }
}

/** 从 Mojang 拉到的玩家皮肤。 */
data class PlayerSkinUi(
    val dataUrl: String = "",
    val model: String = "default",
    val capeDataUrl: String = "",
)
/** 皮肤页状态。 */
data class SkinUiState(
    val loading: Boolean = false,
    val error: String? = null,
    /** 皮肤库。 */
    val skins: List<SkinEntryUi> = emptyList(),
    /** 当前选中要预览的皮肤。 */
    val selected: SkinEntryUi? = null,
    /** 选中皮肤的图片 data URL。 */
    val imageDataUrl: String = "",
    /** 当前账号（用于「应用到账号」）。 */
    val accountUuid: String = "",
    val accountName: String = "",
    val accountOnline: Boolean = false,
    /** 正在获取的玩家名（按正版名获取，显示进度用）。 */
    val fetchingPlayer: String = "",
    /** 上次获取结果的提示（成功/失败文案）。 */
    val fetchMessage: String? = null,
    /** 正在应用的皮肤文件名（显示进度用）。 */
    val applying: String = "",
)


