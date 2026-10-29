package com.zhayi.qookix.services

import com.zhayi.qookix.DataDirs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 服务端运行服务，**跑在 `:server` 独立进程**（见 AndroidManifest 的 `android:process`）。
 *
 * ## 为什么要独立进程
 *
 * 服务端是另一个 JVM，内存几十上百 MB。放在主进程的话：
 * - 服务端把系统 OOM 判定打到整个 App，用户在 UI 里操作也会被系统杀掉，服务器一起没了；
 * - 服务端 JVM 崩溃会直接带走 UI（表现是「开服后应用闪退」）。
 *
 * 独立进程后主进程始终活着，UI 能读到退出原因并提示用户。
 *
 * ## 通信方式
 *
 * 不用 AIDL（要额外编译），走 **文件 + localhost HTTP**：
 * - 主进程把启动参数写进 `files/servers/{id}/launch.json`，再 startForegroundService 本服务；
 * - 本服务读 launch.json → 调 JNI `nativeStart`（Rust 侧起 JVM + IPC HTTP）→ 立即返回；
 * - 主进程靠 `runtime.json`（IPC 端口 + token）发 `/stop`、查 `/status`。
 */
class ServerService : Service() {

    companion object {
        private const val TAG = "QookiXServer"
        private const val CHANNEL_ID = "qookix_server"
        private const val NOTIF_ID = 4201

        /** 正在运行的服 id（:server 进程内；进程被杀即失效，正好是我们要的语义） */
        @Volatile
        var currentServerId: String? = null
            private set

        private val starting = AtomicBoolean(false)

        /**
         * 供 Rust 侧测试/调试用；正式路径是 Rust 直接 JNI 构造 Intent 调
         * `Context.startForegroundService`（见 android_bridge.rs 的说明——
         * 那条 call_activity 桥要求的 Kotlin 方法其实并不存在）。
         */
        fun start(context: Context, serverId: String) {
            currentServerId = serverId
            val intent = Intent(context, ServerService::class.java).apply {
                putExtra(EXTRA_ID, serverId)
                setComponent(
                    android.content.ComponentName(
                        context.packageName,
                        ServerService::class.java.name
                    )
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 主进程侧兜底：整个 :server 进程干掉（IPC 停服失败时用，世界可能没存盘） */
        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
            currentServerId = null
        }

        const val EXTRA_ID = "server_id"
        /** 主进程在优雅停服成功后广播这个 action，让 :server 进程撤掉常驻通知 */
        const val ACTION_STOPPED = "com.zhayi.qookix.SERVER_STOPPED"

        /**
         * 外部（Rust JNI）入口。
         *
         * **必须在 :server 进程里调用**——这里会建第二个 JVM，和主进程的游戏 VM 隔离。
         * 立即返回，启动在 Rust 的后台线程里进行。
         *
         * 放在 companion 里才能加 @JvmStatic：JNI 是按 `静态方法` 找符号的，
         * 实例方法没有 `Java_..._nativeStart` 这种导出名。
         */
        @JvmStatic
        private external fun nativeStart(launchJsonPath: String, ipcPort: Int): Int

        @JvmStatic
        private external fun nativeIsRunning(): Int

        init {
            // 与 TauriBridge 一致：Rust 侧产物叫 libqookix_lib.so
            // （写 "qookix" 会 UnsatisfiedLinkError，而且这个异常发生在
            //  class 初始化时，onStartCommand 根本进不去 → 服务静默不启动）
            System.loadLibrary("qookix_lib")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 优雅停服成功后，主进程会发这个广播让我们撤掉常驻通知。
     *
     * 不用静态 BroadcastReceiver（那会多一个常驻组件），直接在服务里动态注册：
     * 服务活着才需要收通知，进程都没了通知早就随进程消失了。
     */
    /** 停服广播的接收器。**必须留引用**，否则没法反注册（见 onDestroy）。 */
    private var stopReceiver: android.content.BroadcastReceiver? = null

    private fun registerStopReceiver() {
        if (stopReceiver != null) return // 已经注册过就别重复注册
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_STOPPED) {
                    Log.i(TAG, "收到停服通知，撤掉常驻通知")
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(NOTIF_ID)
                    stopForeground(true)
                    stopSelf()
                    // 见 killProcessAfterStop 的说明：必须连进程一起结束
                    killProcessAfterStop(800L)
                }
            }
        }
        val filter = android.content.IntentFilter(ACTION_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ⚠️ 2026-10-29（外部审计 B03 核实成立）：原来写的是 RECEIVER_EXPORTED，
            // 而 ACTION_STOPPED 只由**本应用**主进程里的 Rust 侧 `sendBroadcast` 发出
            //（android_bridge/mod_android.rs::notify_server_stopped）。
            // EXPORTED 等于把「停服 + 杀进程」这个动作开放给本机任意应用 ——
            // 任何应用发一个同名 action 就能打断正在运行的服务器（可能跳过存档）。
            // 改成 NOT_EXPORTED：同 uid 的跨进程广播照样收得到。
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        stopReceiver = receiver
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerStopReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverId = intent?.getStringExtra(EXTRA_ID)
        if (serverId.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 有新的启动请求了：先撤掉「停服后杀进程」的待执行任务，
        // 否则用户在停服后一两秒内重新启动时，新进程会被它顺手杀掉。
        cancelPendingKill()

        // 立刻发通知：Android 8+ 要求 startForegroundService 后 5 秒内 startForeground，
        // 否则系统直接 ANR 掉这个服务。
        startForegroundCompat(buildNotification(serverId, "正在启动…"))

        if (!starting.compareAndSet(false, true)) {
            // 已经有一次启动在进行，重复请求直接忽略（防连点）
            return START_STICKY
        }

        val dir = DataDirs.serverDir(filesDir, serverId)
        val launchJson = File(dir, "launch.json")
        if (!launchJson.exists()) {
            Log.e(TAG, "launch.json 不存在: $launchJson")
            stopping("启动参数丢失")
            return START_NOT_STICKY
        }

        // IPC 端口传 0：由 Rust 侧 bind(0) 让系统分配，再回写 runtime.json。
        // 这里**不能**先用 ServerSocket 探一个「空闲」端口再关掉传给 Rust ——
        // 关闭后的端口处于 TIME_WAIT，而 Rust 的 TcpListener::bind 不带 SO_REUSEADDR，
        // 会直接 Address already in use，IPC 线程静默死掉（状态查询与停服全失效）。
        val ipcPort = 0

        // 立刻把 runtime.json 写出来，好让主进程能 stop/查询
        // （JVM 还在启动中，status 里 jvm=0，UI 显示「启动中」）
        runCatching {
            val token = org.json.JSONObject(launchJson.readText()).getString("token")
            File(dir, "runtime.json").writeText(
                """{"id":"$serverId","pid":${android.os.Process.myPid()},"ipcPort":$ipcPort,"token":"$token","startedAt":${System.currentTimeMillis()}}"""
            )
        }

        val rc = nativeStart(launchJson.absolutePath, ipcPort)
        if (rc != 0) {
            Log.e(TAG, "nativeStart 失败 rc=$rc")
            stopping("启动失败（$rc）")
            return START_NOT_STICKY
        }

        // 通知改成「运行中」
        notify(buildNotification(serverId, "运行中 · 端口见设置"))
        // 不自动重启：服务端要用户显式启动（START_STICKY 会在进程被回收后偷偷复活，耗电且莫名其妙）
        return START_NOT_STICKY
    }

    private fun pickIpcPort(serverId: String): Int {
        // 20000 + hash%10000，避开 25565(游戏端口) 和常见端口
        val base = 20000 + (serverId.hashCode().and(0x7fff) % 10000)
        var port = base
        while (port <= 29999) {
            val free = try {
                java.net.ServerSocket(port).use { true }
            } catch (e: Exception) {
                false
            }
            if (free) return port
            port++
        }
        return base
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "服务器",
                NotificationManager.IMPORTANCE_LOW // 别打扰：服务器在后台跑不该发提示音
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(id: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
                Intent().apply {
                    // 指向当前 LAUNCHER（通知点一下回启动器）
                    setClassName(packageName, "com.zhayi.qookix.compose.ComposeMainActivity")
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("openServer", id)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = com.zhayi.qookix.qkNotificationBuilder(this, CHANNEL_ID)
            .setContentTitle("Minecraft 服务器")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .setOngoing(true)
        return b.build()
    }

    private fun notify(n: Notification) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, n)
    }

    private fun startForegroundCompat(n: Notification) {
        // 传的类型**必须和 manifest 的 foregroundServiceType 一致**：Android 14+ 会校验
        // 「运行时类型是不是 manifest 声明类型的子集」，不是就抛 IllegalArgumentException
        // （未捕获 → 直接崩进程）。所以下面两处（manifest + 这里）必须同时改。
        //
        // 为什么用 specialUse 而不是 dataSync：dataSync 在 Android 15+ 有「6 小时 / 24 小时」
        // 的硬上限，超时后系统会停掉这个前台服务 —— 对「长期挂着的开服」是致命的
        // （表现就是玩一会儿服务器就没了）。specialUse 没有这个上限，正合这里。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // Android 13 及以下根本不认识 specialUse 这个类型，走 2 参重载 ——
            // 由平台自己按 manifest 取类型，不会出现「运行时类型不匹配」。
            startForeground(NOTIF_ID, n)
        }
    }

    /**
     * 停服后**彻底结束 :server 进程**。
     *
     * ## 为什么必须这么做（2026-10-19 修「服务端只能启动一次」）
     *
     * `nativeStart` 最终调 `JvmLauncher::launch` → `JLI_Launch`，而
     * **JLI_Launch 在同一个进程里只能成功创建一次 JVM**。
     *
     * 停服时如果只 `stopSelf()`（撤通知），`:server` 进程往往还活着
     * （前台服务停了，进程要等系统回收）。用户再点「启动」时：
     *   - `startForegroundService` 把新 Service 实例拉起来
     *   - `nativeStart` 第二次调 `JLI_Launch` → **必然失败**
     *   - 失败信息只写进 launch-error.txt，界面看不到
     * 表现就是「关服后再也起不来，连日志都不输出」。
     *
     * 所以停服路径必须把进程一起干掉，让下次启动是**全新进程**。
     */
    private fun killProcessAfterStop(delayMs: Long = 1500L) {
        // ⚠️ 必须**可取消**：停服与「立刻又点启动」之间只有一两秒。
        // 如果延迟里的 killProcess 无条件执行，用户手快再点一次时，
        // 新起的服务会在这个延迟到点时被顺手杀掉 —— 表现又是一次「点了没反应」。
        // 因此 onStartCommand 一旦发现新的启动请求，就把这个待杀任务撤销。
        pendingKill?.let { runCatching { killHandler.removeCallbacks(it) } }
        val r = Runnable {
            Log.i(TAG, "结束 :server 进程（下次启动需要全新进程才能再建 JVM）")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        pendingKill = r
        killHandler.postDelayed(r, delayMs)
    }

    private val killHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingKill: Runnable? = null

    /** 收到新的启动请求：撤销「停服后杀进程」的待执行任务。 */
    private fun cancelPendingKill() {
        pendingKill?.let {
            runCatching { killHandler.removeCallbacks(it) }
            pendingKill = null
            Log.i(TAG, "收到新的启动请求，已撤销待执行的进程结束任务")
        }
    }

    private fun stopping(reason: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        stopForeground(true)
        stopSelf()
        starting.set(false)
        currentServerId = null
        Log.i(TAG, "服务停止：$reason")
    }

    override fun onDestroy() {
        Log.i(TAG, "ServerService 销毁")
        // 动态注册的 receiver **必须反注册**：不反注册的话，服务销毁后系统仍然持有它，
        // 就等于把 Service 实例（及其 Context）一起留在内存里泄漏掉，
        // 而且 ACTION_STOPPED 广播还会打到已经销毁的服务上。
        stopReceiver?.let {
            runCatching { unregisterReceiver(it) }
            stopReceiver = null
        }
        starting.set(false)
        currentServerId = null
        // ⚠️ 服务销毁后**必须把进程也结束掉**，否则下一次启动会在
        // 同一个进程里第二次调 `JLI_Launch` —— 那是必然失败的，
        // 表现为「关服后再也起不来、连日志都不输出」（2026-10-19 修）。
        //
        // 这条兜底覆盖 `force_stop_server`（`stopService`）那条路：
        // 它不广播 ACTION_STOPPED，只走 onDestroy。
        killProcessAfterStop(1200L)
        super.onDestroy()
    }
}



