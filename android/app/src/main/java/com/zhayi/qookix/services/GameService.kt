package com.zhayi.qookix.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zhayi.qookix.R
import com.zhayi.qookix.currentPlayerName

class GameService : Service() {

    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "game_service_channel"
    private val CHANNEL_NAME = "Game Service"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 游戏起来了：写标记并起「:guard」独立进程守护。
        // 作用：游戏内「强制关闭」会让游戏 JVM 调 System.exit(0) → os::exit()
        // 把**整个进程**带走，启动器会跟着消失；守护进程负责把它拉回来。
        GameGuardService.arm(this)
        autoStartTunnelHosting()
    }

    /**
     * 游戏一起来就让陶瓦联机进入「开房扫描」状态。
     *
     * 为什么必须自动：陶瓦要把「游戏里开放局域网世界」这件事暴露给远端朋友，
     * 而那一步发生在**游戏界面里**——用户没法先回启动器点「开启房间」。
     * 所以这里在游戏启动的瞬间就进扫描态，等用户在游戏里开放世界后，
     * 房间码会自动出现在通知栏（唯一在游戏里能看到的地方）。
     */
    private fun autoStartTunnelHosting() {
        Thread {
            try {
                // 先把隧道服务拉起来。它是**按需启动**的（启动器前端第一次调 /state 时才起），
                // 而自动开房发生在游戏启动时 —— 不主动起就永远等不到端口文件，
                // 于是整段静默跳过：房间要么没有，要么缺玩家名（显示成
                // `Terracotta Anonymous Host`，真机踩过）。
                startService(
                    Intent(this, com.zhayi.qookix.tunnel.TerracottaTunnelService::class.java)
                )
                // 等隧道进程把端口文件写出来（最多等 8 秒）
                val portFile = java.io.File(filesDir, "tunnel_port")
                var waited = 0
                while (!portFile.exists() && waited < 80) {
                    Thread.sleep(100); waited++
                }
                if (!portFile.exists()) return@Thread
                val port = portFile.readText().trim().toIntOrNull() ?: return@Thread
                val player = currentPlayerName(this)
                // 端口会变，所以每次重新读；/autohost 幂等
                val conn = java.net.URL("http://127.0.0.1:$port/autohost?player=$player")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.outputStream.use { it.write("{}".toByteArray()) }
                conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                android.util.Log.i("GameService", "已自动进入开房扫描（player=$player）")
            } catch (t: Throwable) {
                android.util.Log.w("GameService", "自动开房失败（不影响游戏）", t)
            }
        }.start()
    }

    // 玩家名取法已抽到 TerracottaEnv.kt（游戏内联机面板也要用，避免两份实现）

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 通知栏点「停止」必须真的结束游戏：同进程架构下只 stopSelf() 时
            // 游戏照旧在跑（问题清单 P0-14）。Pojav 的 GameService 直接 killProcess。
            killGameAndProcess()
            return START_NOT_STICKY
        }

        // Android 12+ 对前台服务启动有严格限制（targetSdk 36 更严）：不允许时
        // startForeground() 会抛 ForegroundServiceStartNotAllowedException，
        // 而这是未捕获异常，会直接把整个进程带崩（游戏刚拉起就闪退）。
        // 这里降级成普通后台服务：通知栏不显示，但游戏照常运行。
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Throwable) {
            // 注意不能只是「吞掉异常继续 START_STICKY」：服务没能进入前台状态，
            // 系统会判定违规并直接把进程杀掉（RemoteServiceException / ANR），
            // 表现成「游戏刚拉起来就闪退」。正确做法是干脆退出，
            // 让游戏进程自己以前台 Activity 的形式活着。
            android.util.Log.w("GameService", "startForeground 被系统拒绝，结束该服务", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // 不再 START_STICKY：进程被回收后系统会把服务重新拉起（intent==null），
        // 于是重新 startForeground —— 出现一条「游戏在运行」但根本没有游戏的
        // 幽灵通知（问题清单 P0-15）。
        return START_NOT_STICKY
    }

    /**
     * 从最近任务划掉启动器：同进程的 JVM 不会随之结束，会变成「看不见但还在跑」
     * 的后台进程。这里照 Pojav 的 GameService 收尾。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        killGameAndProcess()
        super.onTaskRemoved(rootIntent)
    }

    /** 结束游戏；JVM 不在时兜底杀进程，保证不留孤儿。 */
    private fun killGameAndProcess() {
        // 用户主动结束（通知栏「停止」/ 从最近任务划掉）：这是**有意**关掉，
        // 守护服务不能又把启动器拉回来。
        GameGuardService.disarm(this)
        val killed = try {
            com.zhayi.qookix.tauri.TauriBridge.killGame()
        } catch (e: Throwable) {
            android.util.Log.w("GameService", "killGame 失败", e)
            false
        }
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
        stopSelf()
        if (!killed) {
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopForeground(true)
        } catch (_: Throwable) {
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            // 指向当前 LAUNCHER（通知点一下回启动器）
            Intent().apply {
                setClassName(packageName, "com.zhayi.qookix.compose.ComposeMainActivity")
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, GameService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_game_running))
            .setContentText(getString(R.string.notification_game_running_text))
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_stop),
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Game running notification"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_STOP = "com.zhayi.qookix.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, GameService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GameService::class.java)
            intent.action = ACTION_STOP
            context.stopService(intent)
        }
    }
}

