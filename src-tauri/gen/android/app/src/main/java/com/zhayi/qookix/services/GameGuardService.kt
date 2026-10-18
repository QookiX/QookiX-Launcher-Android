package com.zhayi.qookix.services

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File

/**
 * 启动器守护服务 —— **跑在独立进程**（`:guard`，见 AndroidManifest）。
 *
 * ## 为什么需要它
 *
 * 游戏的 JVM 与启动器在**同一个进程**里（AndroidManifest 注释里写了原因：输入/渲染桥
 * 依赖同进程）。而游戏内「强制关闭」会对该 JVM 调 `System.exit(0)`，JVM halt 时
 * `os::exit()` 会**结束整个进程** —— 启动器跟着一起消失，用户被送回桌面。
 *
 * 试过用 `android_set_exit_hook` 拦截，实测拦不住（bionic 调完钩子仍然 `_exit`），
 * 所以改走「善后」路线：本服务在独立进程里，主进程被带走时它还活着，
 * 检测到就把启动器重新拉起来。
 *
 * ## 为什么要标记文件
 *
 * 主进程死掉后就什么都不剩了，「游戏是否正在运行」这个状态必须留在磁盘上：
 * 启动器在游戏启动时写 `game_running.marker`，游戏正常结束/用户主动划掉时删掉。
 * 守护服务只在**标记存在且主进程确实没了**时才拉起启动器。
 */
class GameGuardService : Service() {

    private var thread: Thread? = null

    /** 主进程 pid（Service 没有 Activity 那个 intent 属性，从 onStartCommand 的参数取） */
    private var mainPid: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getIntExtra(EXTRA_MAIN_PID, -1)?.let { mainPid = it }
        thread?.interrupt()
        thread = Thread({ watchLoop() }, "qookix-guard").apply {
            isDaemon = true
            start()
        }
        // 被系统重启服务时也要接着盯
        return START_STICKY
    }

    override fun onDestroy() {
        thread?.interrupt()
        thread = null
        super.onDestroy()
    }

    private fun watchLoop() {
        while (!Thread.currentThread().isInterrupted) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
            val marker = markerFile(this)
            // 没有标记 = 没在跑游戏（或已经正常结束）→ 没什么要管的
            if (!marker.exists()) continue
            if (isProcessAlive(mainPid)) continue

            // 主进程被游戏带走了：清标记，把启动器拉回来，然后结束自己
            Log.w(TAG, "主进程 $mainPid 已消失（被游戏退出带走），重新拉起启动器")
            marker.delete()
            relaunchLauncher()
            stopSelf()
            return
        }
    }

    private fun isProcessAlive(pid: Int): Boolean {
        if (pid <= 0) return true // 拿不到 pid 就别乱动
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.any { it.pid == pid } == true
    }


    private fun relaunchLauncher() {
        // ⚠️ 必须拉起**当前的 LAUNCHER**（ComposeMainActivity）。
        //
        // 这里原来硬编码 `com.zhayi.qookix.MainActivity` —— 那是旧 Vue 时代的入口，
        // 2026-10-13 起 LAUNCHER 已改成 `compose.ComposeMainActivity`。
        //
        // 后果（实测）：游戏退出 / 启动失败后，守护服务把用户拉回**旧空壳 Activity**，
        // 界面一片空白，用户以为「启动器坏了」，只能手动杀进程重开。
        //
        // 用 LAUNCHER intent（而不是写死类名），以后再换入口也只需改 LAUNCHER_CLASS。
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(packageName, LAUNCHER_CLASS)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
        }
        // 先试直接拉起：Android 12 以前可以，12+ 在后台会被系统拦
        // （logcat: Background activity launch blocked!），所以必须配一条通知兜底。
        val launched = runCatching { startActivity(intent) }.isSuccess
        if (!launched) {
            Log.w(TAG, "直接拉起被系统拒绝，改用通知兜底")
        }
        notifyGameFinished(intent)
    }

    /** 兜底：发一条高优先级通知，用户点一下回到启动器（用户点击可绕过后台启动限制） */
    private fun notifyGameFinished(openIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "游戏结束提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "游戏退出后把启动器带回来" }
                nm.createNotificationChannel(ch)
            }
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("游戏已退出")
            .setContentText("点此返回启动器")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        val nm2 = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm2.notify(NOTIFICATION_ID, n) }
            .onFailure { Log.w(TAG, "通知发送失败", it) }
    }

    companion object {
        /** 当前启动器入口 —— 换 LAUNCHER 时只改这一处。 */
        const val LAUNCHER_CLASS = "com.zhayi.qookix.compose.ComposeMainActivity"
        private const val TAG = "GameGuard"
        private const val CHANNEL_ID = "game_guard_channel"
        private const val NOTIFICATION_ID = 4201
        const val EXTRA_MAIN_PID = "main_pid"
        const val MARKER_NAME = "game_running.marker"

        /** 标记文件放在 filesDir：独立进程与主进程共享同一份（不需要 ContentProvider） */
        fun markerFile(ctx: Context): File = File(ctx.filesDir, MARKER_NAME)

        /** 游戏启动时调用：写标记 + 起守护（独立进程） */
        fun arm(ctx: Context) {
            runCatching {
                markerFile(ctx).writeText(System.currentTimeMillis().toString())
                val pid = android.os.Process.myPid()
                ctx.startService(
                    Intent(ctx, GameGuardService::class.java).putExtra(EXTRA_MAIN_PID, pid)
                )
            }.onFailure { Log.w(TAG, "守护启动失败", it) }
        }

        /** 游戏结束 / 用户主动划掉时调用：清标记并停守护 */
        fun disarm(ctx: Context) {
            runCatching {
                markerFile(ctx).delete()
                ctx.stopService(Intent(ctx, GameGuardService::class.java))
            }.onFailure { Log.w(TAG, "守护停止失败", it) }
        }
    }
}




