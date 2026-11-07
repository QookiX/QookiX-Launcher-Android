package com.zhayi.qookix.compose

import android.content.Context
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R
import org.json.JSONObject
import java.io.File

/**
 * 「上次启动失败」提示 + 启动日志外置。
 *
 * ## 为什么要有
 *
 * 2026-11-07 真机复现：1.21.11 点启动后，JVM 窗口都出来了，**2 秒后进程静默
 * 退出**（没有 tombstone、Java 侧也没抛到 logcat），`GameGuard` 又把启动器
 * 拉回来 —— 用户只看到「闪一下」，没有任何可行动的信息；我们连日志都拿不到
 * （release 包不可 `run-as`，内部 `files/logs` 完全够不着）。
 *
 * 于是分两件事：
 *  1. Rust 侧在「跑得比 [EARLY_EXIT_SECS] 还短」时写
 *     `files/last-launch-failure.json`；下次进启动器由本文件弹出提示。
 *  2. **把内部日志镜像到应用专属外部目录**（`Android/data/<pkg>/files/logs`）。
 *     Java 层用 `getExternalFilesDir` 拿路径一定有权限，`adb pull` 就能取。
 *
 * 两件都不碰启动主链路，出问题最多是「没有提示」，不会影响游戏本身。
 */

/** Rust 写下的失败标记（字段名与 `launch.rs` 的 `json!` 保持一致）。 */
internal data class LastLaunchFailure(
    val instanceId: String,
    /** MC 版本（如 1.21.11）；老标记文件里没有这个字段，可能为空。 */
    val mcVersion: String,
    val code: Int,
    val elapsedSecs: Long,
)

private const val TAG = "LaunchFailure"
private const val MARKER = "last-launch-failure.json"

internal fun readLastLaunchFailure(ctx: Context): LastLaunchFailure? {
    val f = File(ctx.filesDir, MARKER)
    if (!f.isFile) return null
    return runCatching {
        val o = JSONObject(f.readText())
        LastLaunchFailure(
            instanceId = o.optString("instanceId"),
            mcVersion = o.optString("mcVersion").takeIf { it.isNotBlank() && it != "null" }
                .orEmpty(),
            code = o.optInt("code", 0),
            elapsedSecs = o.optLong("elapsed", 0),
        )
    }.onFailure { Log.w(TAG, "解析启动失败标记失败", it) }.getOrNull()
}

internal fun clearLastLaunchFailure(ctx: Context) {
    runCatching { File(ctx.filesDir, MARKER).delete() }
}

/**
 * 把 `files/logs/` 里的启动日志复制一份到应用专属外部目录。
 *
 * 只复制文件、覆盖同名，失败就跳过 —— 这是排查用的旁路，不能影响启动。
 */
/**
 * 把 Minecraft 自己的崩溃报告也镜像到外部目录。
 *
 * 2026-11-07：MC 崩溃时会把完整报告写到
 * `files/game/<实例>/crash-reports/crash-*.txt`（里面有异常栈、模组列表、
 * 渲染器信息）—— 那才是定位问题的第一手材料。但它在**私有目录**里，
 * release 包 `run-as` 不可用，用户和我们都拿不到。
 *
 * 这里在启动时扫一遍，按「实例前 8 位-报告名」平铺到
 * `Android/data/<pkg>/files/crash-reports/`，`adb pull` 就能取。
 * 报告通常只有几十 KB，全量复制也不心疼。
 */
internal fun mirrorCrashReportsToExternal(ctx: Context) {
    runCatching {
        val gameRoot = File(ctx.filesDir, "game")
        if (!gameRoot.isDirectory) return
        val dst = ctx.getExternalFilesDir("crash-reports") ?: return
        if (!dst.isDirectory && !dst.mkdirs()) return
        gameRoot.listFiles()?.forEach { inst ->
            val dir = File(inst, "crash-reports")
            if (!dir.isDirectory) return@forEach
            dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.forEach { f ->
                val tag = inst.name.take(8)
                runCatching {
                    f.copyTo(File(dst, "$tag-${f.name}"), overwrite = true)
                }
            }
        }
    }.onFailure { Log.w(TAG, "镜像崩溃报告失败", it) }
}

/**
 * 把 `files/<子目录>/` 原样镜像到 `Android/data/<pkg>/files/<子目录>/`。
 *
 * 只复制文件、覆盖同名，单文件失败就跳过 —— 这是排查用的旁路，不能影响启动。
 * 日志和诊断都走这一份实现，别再各写一遍。
 */
private fun mirrorPrivateDirToExternal(ctx: Context, subDir: String) {
    runCatching {
        val src = File(ctx.filesDir, subDir)
        if (!src.isDirectory) return
        val dst = ctx.getExternalFilesDir(subDir) ?: return
        if (!dst.isDirectory && !dst.mkdirs()) return
        src.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0) {
                runCatching { f.copyTo(File(dst, f.name), overwrite = true) }
            }
        }
    }.onFailure { Log.w(TAG, "镜像 $subDir 到外部目录失败", it) }
}

internal fun mirrorLaunchLogsToExternal(ctx: Context) = mirrorPrivateDirToExternal(ctx, "logs")

/**
 * 把 `files/diagnostics/`（JVM 都没建起来时的失败原因）镜像到外部。
 *
 * Rust 只能写进私有目录，而 release 包不可 `run-as`；不镜像的话
 * `Android/data/<pkg>/files/diagnostics/` 永远是空的，弹窗里那句
 * 「日志与崩溃报告已保存到…」等于骗人。
 */
internal fun mirrorDiagnosticsToExternal(ctx: Context) = mirrorPrivateDirToExternal(ctx, "diagnostics")

/**
 * 挂在启动器根组合里：进来先镜像日志，再根据标记决定要不要弹「上次启动失败」。
 *
 * 关掉弹窗后删标记 —— 同一次失败只提示一次。
 */
@Composable
internal fun LaunchFailureNoticeHost() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var failure by remember { mutableStateOf<LastLaunchFailure?>(null) }

    LaunchedEffect(Unit) {
        // 复制日志 / 崩溃报告放后台线程：日志可能几 MB，别卡首帧
        Thread {
            mirrorLaunchLogsToExternal(context.applicationContext)
            mirrorCrashReportsToExternal(context.applicationContext)
            mirrorDiagnosticsToExternal(context.applicationContext)
        }.apply {
            priority = Thread.MIN_PRIORITY
        }.start()
        failure = readLastLaunchFailure(context)
    }

    val f = failure ?: return
    AlertDialog(
        onDismissRequest = {
            clearLastLaunchFailure(context)
            failure = null
        },
        title = { Text(stringResource(R.string.launch_failed_last_time_title)) },
        text = {
            Text(
                if (f.mcVersion.isNotBlank()) {
                    stringResource(
                        R.string.launch_failed_last_time_body_v,
                        f.mcVersion,
                        f.elapsedSecs,
                        f.code,
                    )
                } else {
                    stringResource(
                        R.string.launch_failed_last_time_body,
                        f.elapsedSecs,
                        f.code,
                    )
                }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                clearLastLaunchFailure(context)
                failure = null
            }) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}
