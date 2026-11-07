package com.zhayi.qookix

import android.content.Context
import java.io.File

/**
 * 把本次崩溃现场复制到应用专属外部目录，并返回弹窗正文。
 *
 * 从 `GameActivity` 里拆出来的（那个 Activity 已经太长，体量闸门会拦）：
 * 这里只做「找文件 + 复制 + 组织文案」，不碰任何界面状态。
 *
 * ## 为什么把内部文件再抄一份
 *
 * 启动日志和崩溃报告都在私有目录（`/data/data/<包名>/files`），release 包
 * `run-as` 不可用，用户和我们**都取不出来**。`getExternalFilesDir` 不需要任何
 * 权限，`adb pull` 就能拿 —— 所以统一抄到
 * `Android/data/<包名>/files/diagnostics/`。
 *
 * ## 失败原因为什么单独读
 *
 * JVM 根本没建起来时不会有 `logs/launch-<实例>.log`（那个文件要等输出重定向之后
 * 才存在），唯一的线索是 Rust 写的 `diagnostics/last-launch-error.txt`。
 * 以前只复制日志和崩溃报告，于是弹窗说「已保存」而目录其实空的。
 *
 * @return 弹窗正文；连外部目录都拿不到时返回 `null`（调用方走兜底文案）。
 */
internal fun dumpGameDiagnostics(ctx: Context, instanceId: String): String? {
    return try {
        val external = ctx.getExternalFilesDir(null) ?: return null
        val target = File(external, "diagnostics")
        target.mkdirs()

        var copied = 0

        val logFile = File(ctx.filesDir, "logs/launch-$instanceId.log")
        if (logFile.isFile) {
            logFile.copyTo(File(target, logFile.name), true)
            copied++
        }

        val crashDir = File(DataDirs.instanceDir(ctx.filesDir, instanceId), "crash-reports")
        crashDir.listFiles()?.sortedByDescending { it.lastModified() }?.firstOrNull()?.let {
            it.copyTo(File(target, it.name), true)
            copied++
        }

        val reasonFile = File(ctx.filesDir, "diagnostics/last-launch-error.txt")
        val reason = if (reasonFile.isFile) {
            reasonFile.copyTo(File(target, reasonFile.name), true)
            copied++
            runCatching { reasonFile.readText() }.getOrNull()
        } else {
            null
        }

        buildString {
            if (!reason.isNullOrBlank()) {
                append(reason.trim())
                append("\n\n")
            }
            if (copied > 0) {
                append("日志与崩溃报告已保存到：\n$target")
            } else {
                append("没有可保存的日志（JVM 可能还没建起来）。\n目录：\n$target")
            }
        }
    } catch (e: Exception) {
        null
    }
}
