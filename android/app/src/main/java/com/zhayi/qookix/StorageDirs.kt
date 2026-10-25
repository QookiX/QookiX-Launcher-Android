package com.zhayi.qookix

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 「游戏目录」可选项：内部私有目录 + 各卷上的**应用专属外部目录**。
 *
 * 只枚举本应用自己的目录（`getExternalFilesDir(s)`），所以**零权限**，而且都是
 * **真实路径** —— 游戏 JVM（`--gameDir` / `-Duser.home`）和 Rust 的 `std::fs`
 * 都能直接用（SAF 的 `content://` URI 两边都用不了）。
 *
 * 任意目录（如 `Documents/minecraft`、SD 卡任意目录）不在这里：那需要
 * SAF 选择 + 「所有文件访问」权限，见 [allFilesAccessGranted] / 相关 Activity 方法。
 *
 * 注意：`Android/data/<pkg>/` 在 Android 11+ 被系统屏蔽 —— 手机文件管理器
 * （即使有「所有文件访问」）与电脑 MTP 都进不去，只有 adb 能看。
 */
object StorageDirs {

    /**
     * 内部私有目录：`/data/data/<pkg>/files`。
     *
     * 用 `canonicalPath` 而不是 `filesDir.absolutePath` —— 后者是 `/data/user/0/<pkg>/files`，
     * 与 Rust 侧 `get_data_dir()` 返回的 `/data/data/<pkg>/files` **字符串不同**
     * （同一目录，前者是指向后的符号链接），前端按字符串比对「当前生效目录」时会认不出来。
     */
    fun internalDir(ctx: Context): File =
        File(runCatching { ctx.filesDir.canonicalPath }.getOrDefault(ctx.filesDir.absolutePath))

    /**
     * 各卷的应用专属外部目录：`[0]` 是内置存储，后面是 SD 卡等。
     * 未挂载的卷 `getExternalFilesDirs` 会返回 null 项，这里过滤掉（并以挂载状态兜底）。
     */
    fun externalDirs(ctx: Context): List<File> =
        (ctx.getExternalFilesDirs(null) ?: emptyArray())
            .filterNotNull()
            .filter { runCatching {
                Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            }.getOrDefault(false) }

    /** 可用空间（字节）。取不到时返回 0。 */
    fun freeBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)

    /** 总空间（字节）。取不到时返回 0。 */
    fun totalBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).totalBytes }.getOrDefault(0L)

    /** 是否为可插拔卷（SD 卡）。 */
    @Suppress("DEPRECATION")
    fun isRemovable(dir: File): Boolean =
        runCatching { Environment.isExternalStorageRemovable(dir) }.getOrDefault(false)

    /** 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已授权。Android 11 以下恒为 true。 */
    fun allFilesAccessGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    /**
     * 可选项清单（JSON 数组字符串，Rust 解析后下发前端）。
     *
     * 每项：`kind`（internal/external）、`label`、`path`（**游戏数据根**，`instances/`
     * 会建在其下）、`free`、`total`、`removable`。
     */
    fun optionsJson(ctx: Context): String {
        val arr = JSONArray()
        val internal = internalDir(ctx)
        arr.put(
            JSONObject().apply {
                put("kind", "internal")
                put("label", "内部存储")
                put("path", internal.absolutePath)
                put("free", freeBytes(internal))
                put("total", totalBytes(internal))
                put("removable", false)
            }
        )
        externalDirs(ctx).forEachIndexed { index, dir ->
            arr.put(
                JSONObject().apply {
                    put("kind", "external")
                    put("label", if (index == 0) "内置存储（应用专属）" else "外置存储（应用专属）")
                    put("path", dir.absolutePath)
                    put("free", freeBytes(dir))
                    put("total", totalBytes(dir))
                    put("removable", isRemovable(dir))
                }
            )
        }
        return arr.toString()
    }
}
