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
internal fun mirrorLaunchLogsToExternal(ctx: Context) {
    runCatching {
        val src = File(ctx.filesDir, "logs")
        if (!src.isDirectory) return
        val dst = ctx.getExternalFilesDir("logs") ?: return
        if (!dst.isDirectory && !dst.mkdirs()) return
        src.listFiles()?.forEach { f ->
            if (f.isFile && f.length() > 0) {
                runCatching { f.copyTo(File(dst, f.name), overwrite = true) }
            }
        }
    }.onFailure { Log.w(TAG, "镜像启动日志失败", it) }
}

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
        // 复制日志放后台线程：日志文件可能几 MB，别卡首帧
        Thread { mirrorLaunchLogsToExternal(context.applicationContext) }.apply {
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
