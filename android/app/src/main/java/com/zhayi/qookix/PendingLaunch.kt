package com.zhayi.qookix

import android.content.Context

/**
 * 「结束当前游戏 → 重启启动器 → 自动启动用户刚点的那版」的待启动记录。
 *
 * ## 为什么必须落盘
 *
 * 一个进程里只能存在一个游戏 JVM：Rust 侧 `launch.rs` 对第二次启动直接返回
 * `GAME_ALREADY_RUNNING`，收尾走 `JvmLauncher::shutdown()`（`System.exit(0)`，
 * 会把**整个进程**带走）。所以「换版本」= 结束当前游戏 + 让进程重启 + 起来后
 * 把用户点的那版接上 —— 进程会随游戏一起没，记录只能先写进 SharedPreferences。
 *
 * ## 谁写谁读
 *
 * 写：[MainActivity.relaunchIntoInstance]（用户点了「结束并启动」）。
 * 读：[MainActivity.onCreate] —— 无论启动器是被 `GameGuardService` 自动拉回来、
 * 用户点「游戏已退出」通知、还是自己重新打开，都会在这里把上次没做完的启动接上。
 */
object PendingLaunch {
    private const val PREFS = "qookix_pending_launch"
    private const val KEY_INSTANCE = "instance_id"
    private const val KEY_ACCOUNT = "account_uuid"

    fun save(context: Context, instanceId: String, accountUuid: String) {
        prefs(context).edit()
            .putString(KEY_INSTANCE, instanceId)
            .putString(KEY_ACCOUNT, accountUuid)
            .apply()
    }

    /** 取出并**立刻清空**（只生效一次，否则以后每次开启动器都会自动进游戏）。 */
    fun consume(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val id = p.getString(KEY_INSTANCE, null) ?: return null
        val uuid = p.getString(KEY_ACCOUNT, null).orEmpty()
        p.edit().remove(KEY_INSTANCE).remove(KEY_ACCOUNT).apply()
        return id to uuid
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_INSTANCE).remove(KEY_ACCOUNT).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
