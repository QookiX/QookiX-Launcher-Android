package com.zhayi.qookix

import android.content.Context
import java.io.File

/**
 * 启动过程的两个标记文件。
 *
 * ## 为什么需要两个
 *
 * MC 的「退出游戏」和「崩溃」在进程层面**长得一模一样**：两者都会在 Java 里
 * 直接结束进程（`System.exit`），`JLI_Launch` 不返回，主进程内的收尾代码
 * 执行不到。只靠「进程死了」判断，正常退出也会被报成「上次启动失败」
 *（真机实测：Save and Quit to Title → Quit Game，回来照样弹窗）。
 *
 * 所以用「有没有真正跑到游戏窗口就绪」来区分：
 *
 * - `launch-in-progress.json`：Rust 在创建 JVM 前写，正常返回（极少见）时删
 * - `game-started.marker`：**启动浮层看到 READY_MARKERS 时**写（说明真的
 *   进到主菜单了），下一次启动由 Rust 清掉
 *
 * 守护进程的逻辑：主进程死了 + 只有 in-progress → 启动失败；
 * 主进程死了 + 还有 started → 用户自己退出/玩过之后崩，不报「启动失败」。
 */
internal object LaunchMarkers {

    const val IN_PROGRESS = "launch-in-progress.json"
    const val STARTED = "game-started.marker"

    /** 游戏窗口就绪（启动浮层看到 READY_MARKERS）时调用。 */
    fun markStarted(ctx: Context) {
        runCatching { File(ctx.filesDir, STARTED).createNewFile() }
    }

    fun wasStarted(ctx: Context): Boolean =
        runCatching { File(ctx.filesDir, STARTED).isFile }.getOrDefault(false)

    /** 清理两个标记（用户主动关掉、或已经处理过这次失败）。 */
    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, IN_PROGRESS).delete() }
        runCatching { File(ctx.filesDir, STARTED).delete() }
    }
}
