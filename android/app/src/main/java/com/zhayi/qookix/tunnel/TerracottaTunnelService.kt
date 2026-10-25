package com.zhayi.qookix.tunnel

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.VpnService
import android.os.IBinder
import android.util.Log
import net.burningtnt.terracotta.TerracottaAndroidAPI
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 陶瓦联机（Terracotta）隧道服务 —— **必须跑在独立进程**（`:tunnel`，见 AndroidManifest）。
 *
 * ## 为什么要在独立进程里
 *
 * Terracotta 的 Android 产物是原生库 `libterracotta.so`（AGPL-3.0-or-later，
 * 但上游 README 给了例外：只要「打包未修改的二进制且**不静态/动态链接**」，
 * 或「通过 IPC 与未修改的程序交互并在界面署名」，就不被 AGPL 涵盖）。
 *
 * 我们采用例外条款里的**进程隔离**做法：`.so` 只在这个进程里 `System.loadLibrary`，
 * 主进程（启动器本体）**完全不链接**它，只通过 `127.0.0.1` 上的 HTTP 通信。
 * 这样启动器保持 GPL-3.0。署名见「设置 → 关于 → 第三方声明」。
 *
 * 顺带的好处：隧道崩溃/被杀不会带走启动器和游戏（我们刚踩过游戏 JVM 把整个进程带走的坑）。
 *
 * ## 通信协议（极简 HTTP，主进程用 fetch 调）
 *
 *   GET  /state              → 当前状态 JSON（Terracotta 的 getState 原样透传）
 *   POST /host?player=NAME   → 开始开房并扫描局域网里的「开放局域网世界」
 *   POST /guest?room=CODE&player=NAME → 加入别人的房间
 *   POST /waiting            → 回到等待状态
 *   POST /advertise?port=25565&name=我的服 → **把本机的自建服端口暴露给房间成员**（见下）
 *   POST /no-advertise       → 停止广播（关掉对外开房）
 *   POST /shutdown           → 停止隧道
 *   GET  /logs               → 最近日志（Terracotta 自己的 application.log 尾部）
 *
 * ## 自建服（专用服务器）为什么需要 `/advertise`
 *
 * Terracotta 发现「本机有台MC 服务器」的方式**不是扫端口，而是被动收组播包**
 * （官方 `src/mc/scanning.rs`：`bind(4445)` + `join_multicast(224.0.2.60)`，解析
 * `[MOTD]…[/MOTD][AD]<端口>[/AD]`，5 秒过期）。那个包**就是 Minecraft 官方「局域网世界」的
 * 发现包** —— 只有**游戏客户端**开世界时才会发，**独立服务端不会发**。
 * 所以只开房间的话会一直停在「正在寻找本机开放局域网的游戏…」（真机现象）。
 *
 * 而房主端其实只要求「有个端口在 127.0.0.1 上能回应 MC ping」（官方 `room.rs`的
 * `check_mc_conn`：发单字节 `0xFE`、要求回包首字节 `0xFF`）—— **真正的 MC 服务端天然满足**。
 *
 * 于是我们自己做这个广播：每 1.5s 把本服端口发出去，Terracotta 的扫描器发现它之后
 * 会自动建房间、生成房间码、并把该端口加进 EasyTier 的转发白名单。朋友就能连。
 * （端口不必是 25565：Terracotta 转发的是「扫描到的那个端口」。）
 */
class TerracottaTunnelService : Service() {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var ready = false
    private var lastError: String? = null
    /** 房间码是否已经写进通知（只写一次，避免每次轮询都更新通知） */
    @Volatile
    private var roomNotified = false
    /** 房间码监听线程（见 [startRoomWatcher]） */
    private var roomWatcher: Thread? = null
    @Volatile
    private var watchingRoom = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdownTerracotta()
            stopSelf()
            return START_NOT_STICKY
        }
        // 按需重绑：`:tunnel` 进程会被 libterracotta 的原生线程一直吊着（Service 停了进程
        // 也不退出），所以完全可能出现「进程在、但 HTTP 套接字已经被关掉」的状态 ——
        // 真机踩过：`tunnel_port` 里留着旧端口，可那个端口**根本没有进程在监听**
        // （/proc/net/tcp 里查不到），于是主进程每次请求都吃「连接被拒」，
        // 界面表现就是点「开启房间」报一条看不懂的错、怎么重试都一样。
        // 这里让每次 startService 都顺手确认一下套接字，Rust 侧探测到连不上时
        // 再 startService 一次就能自愈，不必用户「完全退出应用」。
        // 套接字健康也要把端口文件补写回去：文件可能被清掉、或者是被上一代进程写的
        // （主进程就是靠这个文件找我们，它拿着一个旧端口来连必然吃连接被拒）。
        if (!running.get()) startEverything()
        else if (serverSocket?.isClosed != false) startHttpServer()
        else rewritePortFile()
        return START_STICKY
    }

    /** 把当前监听端口写回共享数据目录（幂等；套接字没起来时什么都不做）。 */
    private fun rewritePortFile() {
        try {
            val ss = serverSocket ?: return
            if (ss.isClosed) return
            java.io.File(filesDir, PORT_FILE).writeText(ss.localPort.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "写端口文件失败", t)
        }
    }

    // ── 自建服对外开房：把本服端口广播成「局域网世界」 ─────────────────────────

    /** 广播线程（每 1.5s 发一次组播包，让 Terracotta 的扫描器发现我们）。 */
    private var advThread: Thread? = null

    /**
     * 组播锁。**必须有**：Android 在省电 / doze 下会过滤组播包，不持有这个锁的话
     * Terracotta 自己的扫描器（就在同一个 App 里）**根本收不到包** —— 表现就是开房后
     * 永远停在「正在寻找本机开放局域网的游戏…」。这个锁是设备级 WiFi 过滤器、不是线程级的，
     * 所以在 `:tunnel` 进程里 acquire 就足以让同 UID 的 Terracotta 收得到包。
     */
    private var mcLock: WifiManager.MulticastLock? = null

    private fun acquireMcLock() {
        if (mcLock?.isHeld == true) return
        runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mcLock = wm.createMulticastLock("qookix-terracotta").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "获取组播锁失败（联机可能发现不了本机服务器）", it) }
    }

    private fun releaseMcLock() {
        runCatching { if (mcLock?.isHeld == true) mcLock?.release() }
        mcLock = null
    }

    /** 开始广播本机服务器端口。必须在 Terracotta 进入 `setScanning` 之后调用。 */
    private fun startAdvertise(port: Int, name: String) {
        stopAdvertise()
        if (port !in 1..65535) return
        acquireMcLock()
        // MOTD 填服务器名、AD 填我们真正的端口 —— 官方扫描器只认这两个字段
        val payload = "[MOTD]$name[/MOTD][AD]$port[/AD]"
        Log.i(TAG, "开始广播自建服: $payload")
        advThread = Thread({
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            try {
                DatagramSocket().use { sock ->
                    val group = InetAddress.getByName(MC_DISCOVERY_GROUP)
                    while (!Thread.currentThread().isInterrupted) {
                        runCatching { sock.send(DatagramPacket(bytes, bytes.size, group, MC_DISCOVERY_PORT)) }
                        Thread.sleep(1500)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "广播线程结束", t)
            }
            // 自然退出（被 interrupt）时把锁还回去，否则会一直握着
            releaseMcLock()
        }, "mc-advertise").also { it.isDaemon = true; it.start() }
    }

    /** 停掉对外开房时一并停掉广播 */
    private fun stopAdvertise() {
        advThread?.interrupt()
        advThread = null
        releaseMcLock()
    }

    /**
     * 答复Terracotta 的 VpnService 请求（**必须在 30 秒内**，否则它抛
     * `IllegalStateException` 并卡住，EasyTier 也提交不了下一次请求）。
     *
     * 这里**必须真的建隧道**，不是「拒绝」：
     *   - 拒绝（或者像以前那样不答复）→ 手机侧没有 TUN → 房间码能开出来，
     *     但**客人连不进来**（真机：电脑端加入报「连接发生错误」，房主 profiles 里只有自己）。
     *   - 建立 → EasyTier 才有数据面把入站 mesh 流量转成「连本机 25565」。
     *
     * 没拿到系统 VPN 授权时只能 reject（`establish()` 会返回 null 并抛异常），
     * 这种情况下前端应当先弹授权 —— 见 `MainActivity.ensureVpnConsent`。
     */
    private fun fulfillVpnRequest() {
        val req = runCatching { TerracottaAndroidAPI.getPendingVpnServiceRequest() }.getOrNull()
        if (req == null) {
            Log.w(TAG, "收到 VPN 回调但没有待处理请求")
            return
        }
        try {
            if (VpnService.prepare(this) != null) {
                Log.w(TAG, "尚未获得 VPN 授权 → 拒绝本次请求（房间能开，但客人连不进来）")
                runCatching { req.reject() }
                return
            }
            // Builder 必须由**正在运行**的 VpnService 实例创建，且要和 Terracotta 同进程。
            // startService 是异步的，这里最多等 5 秒（答复窗口有 30 秒，够用）。
            startService(Intent(this, TerracottaVpnService::class.java))
            var builder: VpnService.Builder? = null
            for (i in 0 until 50) {
                builder = TerracottaVpnService.newBuilder()
                if (builder != null) break
                Thread.sleep(100)
            }
            if (builder == null) {
                Log.w(TAG, "VPN 服务 5秒内没起来 → 拒绝本次请求")
                runCatching { req.reject() }
                return
            }
            // startVpnService 内部会 addAddress / addRoute / establish 并返回 fd
            vpnFd = req.startVpnService(builder)
            Log.i(TAG, "VPN/TUN 已建立（fd=${vpnFd?.fd}）")
        } catch (t: Throwable) {
            Log.e(TAG, "建立 VPN 失败", t)
            runCatching { req.reject() }
        }
    }

    /**
     * EasyTier 持有的隧道 fd。
     *
     * 官方文档要求「EasyTier 退出后必须 close」，所以**只在隧道整体关停时关**
     * —— 房间还在的时候关掉会把数据面打断（表现为客人忽然连不上）。
     */
    private var vpnFd: android.os.ParcelFileDescriptor? = null

    private fun startEverything() {
        running.set(true)
        // **预热 VPN 服务**：`VpnService.Builder` 只能由一个已运行的 VpnService 实例创建，
        // 而 `startService` 是异步的 —— 不预热的话，Terracotta 回调到达时 instance 还是 null，
        // 只能拒绝（真机日志：「VPN 服务实例还没起来 → 拒绝本次请求」，而服务在 20ms 后才创建）。
        runCatching { startService(Intent(this, TerracottaVpnService::class.java)) }
        // 加载 + 启动 Terracotta 放后台线程：initialize() 内部要初始化 EasyTier，
        // 阻塞约 1 秒，放主线程会卡住启动。
        Thread({
            try {
                System.loadLibrary("terracotta")
                val cb = TerracottaAndroidAPI.VpnServiceCallback { fulfillVpnRequest() }
                val meta = TerracottaAndroidAPI.initialize(applicationContext, cb)
                Log.i(TAG, "Terracotta 就绪: $meta")
                ready = true
            } catch (t: Throwable) {
                lastError = t.toString()
                Log.e(TAG, "Terracotta 初始化失败", t)
            }
        }, "tc-init").start()

        startHttpServer()
    }

    /**
     * 起本地 HTTP 服务（主进程唯一的通信入口）。
     *
     * 单独抽出来是为了能**只重绑套接字**：原生那套（`System.loadLibrary` + `initialize`）
     * 一个进程只能做一次，重复初始化会炸，而端口文件丢失/套接字被关这类小毛病
     * 只需要重新监听一下。
     */
    private fun startHttpServer() {
        // 本地 HTTP 服务（主进程唯一的通信入口）
        Thread({
            try {
                val ss = ServerSocket(0) // 让系统分配空闲端口
                serverSocket = ss
                // 端口写进共享数据目录，主进程（Rust）读文件即可，无需新增 JNI
                java.io.File(filesDir, PORT_FILE).writeText(ss.localPort.toString())
                Log.i(TAG, "隧道 HTTP 监听 127.0.0.1:${ss.localPort}")
                while (!ss.isClosed) {
                    val client = ss.accept()
                    Thread { handle(client) }.start()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "HTTP 服务退出", t)
            }
        }, "tc-http").start()
    }

    private fun shutdownTerracotta() {
        ready = false
        stopAdvertise()
        // EasyTier 退出后要归还隧道 fd，否则 TUN 接口泄漏
        runCatching { vpnFd?.close() }
        vpnFd = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        running.set(false)
        runCatching { stopForeground(true) }
        // **必须删掉端口文件**：套接字关了但文件还在的话，主进程会继续抱着一个
        // 「看起来很对」的死端口去连，报连接被拒。删掉之后它至少能给出
        // 「隧道服务还没启动」这种能看懂的提示。
        runCatching { java.io.File(filesDir, PORT_FILE).delete() }
    }

    /** 开房时晋升前台（带通知），保证切后台不被回收 */
    private fun promoteToForeground() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            CHANNEL_ID, "联机房间", android.app.NotificationManager.IMPORTANCE_LOW
                        )
                    )
                }
            }
            val n = android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("正在等待「对局域网开放」")
                .setContentText("在游戏里对局域网开放世界后，房间码会显示在这里")
                .setOngoing(true)
                .build()
            startForegroundCompat(n)
        }.onFailure { Log.w(TAG, "晋升前台失败（房间可能不稳定）", it) }
    }

    /**
     * 带类型的前台服务晋升（与 ServerService 同一套写法）。
     *
     * Android 14（API 34）起，不带类型调用 `startForeground` 会抛
     * `MissingForegroundServiceTypeException`：实测现象就是**通知不出现** ——
     * 房间码写在通知里，于是玩家在游戏里什么都看不到（异常被 runCatching 吞掉，
     * 只在 logcat 留一行 W/TcTunnel）。
     */
    private fun startForegroundCompat(n: android.app.Notification) {
        // 类型必须与 manifest 的 foregroundServiceType 一致（Android 14+ 校验，不匹配就抛
        // IllegalArgumentException 直接崩进程）。用 specialUse 而不是 dataSync：
        // dataSync 在 Android 15+ 有 6h/24h 硬上限，开着房被系统停掉隧道就断了。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            // Android 13 及以下不认识 specialUse，走 2 参重载由平台按 manifest 取类型
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun demoteForeground() {
        stopRoomWatcher()
        runCatching { stopForeground(true) }
    }

    /** 从状态 JSON 里抠出房间码（字段名各版本不同，挨个试） */
    private fun extractRoomCode(stateJson: String): String? {
        for (key in listOf("\"room\"", "\"code\"", "\"room_code\"", "\"roomCode\"")) {
            val i = stateJson.indexOf(key)
            if (i < 0) continue
            val colon = stateJson.indexOf(':', i + key.length)
            if (colon < 0) continue
            val rest = stateJson.substring(colon + 1).trimStart()
            if (!rest.startsWith('"')) continue
            val end = rest.indexOf('"', 1)
            if (end <= 1) continue
            val v = rest.substring(1, end)
            if (v.isNotBlank()) return v
        }
        return null
    }

    /** 把通知换成「房间码 xxx」，这样玩家在游戏里也能看到并念给朋友 */
    private fun updateNotificationWithRoom(code: String) {
        runCatching {
            val n = android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("联机房间 $code")
                .setContentText("把这个房间码发给朋友即可加入")
                .setOngoing(true)
                .build()
            startForegroundCompat(n)
            Log.i(TAG, "房间码已就绪: $code")
        }.onFailure { Log.w(TAG, "更新房间码通知失败", it) }
    }

    /**
     * 开房扫描期间自己盯着状态，一拿到房间码就写进通知栏。
     *
     * 为什么必须由本服务自己轮询：玩家在游戏里时启动器在后台，WebView 的定时器被挂起，
     * 之前唯一会调 `/state` 的 `MultiplayerView` 早就停了 —— 实测现象就是
     * 房间其实开好了（HostOk），通知栏却什么都没有，而游戏里玩家只能看通知栏。
     * 另外 Android 14 起 `startForeground` 不带类型会抛异常（通知直接不出现），
     * 那条已由 [startForegroundCompat] 修掉。
     */
    private fun startRoomWatcher() {
        if (roomWatcher != null) return
        roomNotified = false
        watchingRoom = true
        roomWatcher = Thread {
            val deadline = System.currentTimeMillis() + 30 * 60 * 1000L
            while (watchingRoom && !roomNotified && System.currentTimeMillis() < deadline) {
                try {
                    val s = TerracottaAndroidAPI.getState()
                    if (s.contains("host")) {
                        extractRoomCode(s)?.let { code ->
                            roomNotified = true
                            updateNotificationWithRoom(code)
                        }
                    }
                } catch (t: Throwable) {
                    // 还没初始化完 / EasyTier 正在起，都属正常，继续重试
                    Log.w(TAG, "读取房间状态失败（继续重试）", t)
                }
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "room-watcher"
            it.start()
        }
    }

    private fun stopRoomWatcher() {
        watchingRoom = false
        roomWatcher = null
    }

    override fun onDestroy() {
        stopRoomWatcher()
        shutdownTerracotta()
        super.onDestroy()
    }

    // ── HTTP 处理（只支持最简的 GET/POST，够用即可）──────────────────────

    private fun handle(client: Socket) {
        try {
            client.use { sock ->
                sock.soTimeout = 5000
                val input = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = input.readLine() ?: return
                // 把请求头读干净
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseQuery(target.substringAfter('?', ""))

                val body: String = try {
                    withState {
                        when {
                            path == "/state" -> {
                                val s = TerracottaAndroidAPI.getState()
                                // 状态里出现房间码就把通知更新掉（用户在游戏里只看得到通知栏）
                                if (s.contains("host") && !roomNotified) {
                                    extractRoomCode(s)?.let { code ->
                                        roomNotified = true
                                        updateNotificationWithRoom(code)
                                    }
                                }
                                s
                            }
                            path == "/host" -> {
                                // 开房时才需要「一直活着」：晋升为前台服务（带通知），
                                // 否则切后台被系统回收，房间就断了。
                                promoteToForeground()
                                startRoomWatcher()
                                TerracottaAndroidAPI.setScanning(null, query["player"])
                                "{\"ok\":true,\"state\":\"host-scanning\"}"
                            }
                            path == "/guest" -> {
                                val room = query["room"]
                                if (room.isNullOrBlank()) "{\"ok\":false,\"error\":\"缺少房间码\"}"
                                else {
                                    // 先退出自己这边的房间/扫描：正开着房时 setGuesting 会直接失败，
                                    // 而调用方（游戏内联机面板、启动器多人页）只看返回 JSON，
                                    // 表现出来的就是「点了加入房间毫无反应」—— 真机踩过。
                                    runCatching { TerracottaAndroidAPI.setWaiting() }
                                    val ok = TerracottaAndroidAPI.setGuesting(room, query["player"])
                                    "{\"ok\":$ok,\"state\":\"${if (ok) "guesting" else "failed"}\"}"
                                }
                            }
                            path == "/waiting" -> {
                                // 回到等待状态时顺手停掉自建服广播 —— 否则会一直对外宣称
                                // 「有台服务器在那个端口」，而服可能已经停了。
                                stopAdvertise()
                                TerracottaAndroidAPI.setWaiting()
                                demoteForeground()
                                "{\"ok\":true,\"state\":\"waiting\"}"
                            }
                            path == "/advertise" -> {
                                val port = query["port"]?.toIntOrNull()
                                if (port == null) "{\"ok\":false,\"error\":\"缺少端口\"}"
                                else {
                                    startAdvertise(port, query["name"]?.takeIf { it.isNotBlank() } ?: "QookiX Server")
                                    "{\"ok\":true,\"advertising\":$port}"
                                }
                            }
                            path == "/no-advertise" -> {
                                stopAdvertise()
                                "{\"ok\":true,\"advertising\":null}"
                            }
                            path == "/autohost" -> {
                                // 游戏启动时调用：直接进开房扫描。这样用户**不用回启动器点按钮**
                                // —— 在游戏里「开放局域网世界」即可，房间码会出现在通知栏。
                                val name = query["player"]
                                promoteToForeground()
                                startRoomWatcher()
                                TerracottaAndroidAPI.setScanning(name, name)
                                "{\"ok\":true,\"state\":\"host-scanning\"}"
                            }
                            path == "/logs" -> tailLogs()
                            path == "/ping" -> "{\"ok\":true,\"ready\":$ready}"
                            else -> "{\"ok\":false,\"error\":\"未知接口 $method $path\"}"
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "处理 $path 失败", t)
                    "{\"ok\":false,\"error\":${jsonEscape(t.toString())}}"
                }

                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                sock.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\n".toByteArray())
                    write("Content-Type: application/json; charset=utf-8\r\n".toByteArray())
                    write("Content-Length: ${bytes.size}\r\n".toByteArray())
                    write("Connection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Terracotta 未就绪时的兜底：直接报错而不是让调用方等 */
    private inline fun <T> withState(block: () -> T): T {
        if (!ready) {
            Thread.sleep(300) // 给 initialize 一点时间
        }
        if (!ready) throw IllegalStateException("Terracotta 还在启动中，请稍候重试")
        return block()
    }

    private fun tailLogs(): String {
        val f = java.io.File(filesDir, "net.burningtnt.terracotta/application.log")
        if (!f.exists()) return "{\"ok\":true,\"logs\":\"\"}"
        val text = try {
            f.readText()
        } catch (t: Throwable) {
            ""
        }
        val tail = if (text.length > 4000) text.takeLast(4000) else text
        return "{\"ok\":true,\"logs\":${jsonEscape(tail)}}"
    }

    private fun parseQuery(q: String): Map<String, String> =
        q.split('&').filter { it.contains('=') }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=')
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }

    companion object {
        private const val TAG = "TcTunnel"
        private const val CHANNEL_ID = "tunnel_channel"
        private const val NOTIFICATION_ID = 4202

        /** 动作常量（供主进程用 Intent 触发） */
        const val ACTION_STOP = "com.zhayi.qookix.tunnel.STOP"

        /** 隧道 HTTP 端口文件（放在 filesDir，主进程 Rust 读它拿端口） */
        const val PORT_FILE = "tunnel_port"

        /**
         * Minecraft 官方的「局域网世界」发现包目的地。Terracotta 的扫描器就是
         * 监听这里（官方 `scanning.rs`：bind 4445 + join_multicast 224.0.2.60）。
         */
        const val MC_DISCOVERY_GROUP = "224.0.2.60"
        const val MC_DISCOVERY_PORT = 4445
    }
}
