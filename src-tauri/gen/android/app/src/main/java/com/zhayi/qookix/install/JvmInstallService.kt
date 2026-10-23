package com.zhayi.qookix.install

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在**独立进程**里跑一个 JVM（`android:process=":jvm"`），用于 Forge / NeoForge 的
 * installer / processors。
 *
 * ## 为什么必须独立进程（2026-10-23，照 ZL2 / Pojav 的做法）
 *
 * - 安卓**禁止 exec 应用私有目录里的 java**：「`Command::new(bin/java)`」实测直接失败
 *   （真机日志 `启动 installer 失败`），只能走 JNI `JNI_CreateJavaVM`。
 * - 而 `JNI_CreateJavaVM` **一个进程只能成功一次**，主进程还要用它启动 Minecraft。
 * - 所以照 ZL2 的形态：JVM 放在 `:jvm` 进程的这个 Service 里，跑完把退出码
 *   经 UDP 回环送回主进程，然后 `stopSelf()` + 杀掉本进程（否则下次起不来）。
 *
 * ## 与 Rust 的接口
 *
 * `nativeRunJvm(...)` 在 `jni_bridge.rs` 里，内部复用 `jvm_launcher::JvmLauncher`
 * （和启动游戏同一条 JLI 通道）。列表参数用 `\u001F`（Unit Separator）拼接。
 */
class JvmInstallService : Service() {

    companion object {
        private const val TAG = "QookixJvmSvc"
        private const val CHANNEL_ID = "qookix_jvm_install"
        private const val NOTI_ID = 0x4A56

        const val EXTRA_JRE = "jre"
        const val EXTRA_CLASSPATH = "classpath"
        const val EXTRA_MAIN_CLASS = "mainClass"
        const val EXTRA_JVM_ARGS = "jvmArgs"
        const val EXTRA_GAME_ARGS = "gameArgs"
        const val EXTRA_WORK_DIR = "workDir"
        const val EXTRA_PORT = "port"

        /** Rust 侧：在该进程里起 JVM，返回退出码。 */
        @JvmStatic
        external fun nativeRunJvm(
            jreHome: String,
            classpath: String,
            mainClass: String,
            jvmArgs: String,
            gameArgs: String,
            workDir: String,
        ): Int

        /** 把列表拼成 Rust 侧能切开的字符串（Unit Separator）。 */
        fun joinArgs(items: List<String>): String = items.joinToString("\u001F")

        private val started = AtomicInteger(0)

        /**
         * 启动服务。返回 false 表示已经有一个在跑（避免并发起两个 JVM）。
         */
        fun start(
            context: Context,
            jreHome: String,
            classpath: String,
            mainClass: String,
            jvmArgs: List<String>,
            gameArgs: List<String>,
            workDir: String,
            port: Int,
        ): Boolean {
            if (started.get() == 1) {
                Log.i(TAG, "已有 JVM 任务在跑，忽略本次请求")
                return false
            }
            val intent = Intent(context, JvmInstallService::class.java).apply {
                putExtra(EXTRA_JRE, jreHome)
                putExtra(EXTRA_CLASSPATH, classpath)
                putExtra(EXTRA_MAIN_CLASS, mainClass)
                putExtra(EXTRA_JVM_ARGS, joinArgs(jvmArgs))
                putExtra(EXTRA_GAME_ARGS, joinArgs(gameArgs))
                putExtra(EXTRA_WORK_DIR, workDir)
                putExtra(EXTRA_PORT, port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        System.loadLibrary("qookix_lib")
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        started.set(1)
        startForeground(NOTI_ID, buildNotification("正在准备运行环境…"))

        val jre = intent.getStringExtra(EXTRA_JRE) ?: ""
        val cp = intent.getStringExtra(EXTRA_CLASSPATH) ?: ""
        val mc = intent.getStringExtra(EXTRA_MAIN_CLASS) ?: ""
        val jvmArgs = intent.getStringExtra(EXTRA_JVM_ARGS) ?: ""
        val gameArgs = intent.getStringExtra(EXTRA_GAME_ARGS) ?: ""
        val workDir = intent.getStringExtra(EXTRA_WORK_DIR) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, 0)

        Thread {
            val code = try {
                nativeRunJvm(jre, cp, mc, jvmArgs, gameArgs, workDir)
            } catch (t: Throwable) {
                Log.e(TAG, "nativeRunJvm 抛异常", t)
                -1
            }
            Log.i(TAG, "JVM 结束，退出码 $code")
            sendCode(port, code)
            started.set(0)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        // ⚠️ 必须杀掉本进程：JNI_CreateJavaVM 一个进程只能成功一次，
        // 留着这个进程下次就再也起不来 JVM（ZL2 的 JvmService 也是这么做的）。
        Thread {
            Thread.sleep(300)
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }

    private fun sendCode(port: Int, code: Int) {
        if (port <= 0) return
        try {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port))
                val data = code.toString().toByteArray()
                socket.send(DatagramPacket(data, data.size))
            }
        } catch (e: Throwable) {
            Log.w(TAG, "回传退出码失败", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "运行环境安装", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("QookiX")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }
}
