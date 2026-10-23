package com.zhayi.qookix

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import org.lwjgl.glfw.CallbackBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 宿主 Activity 需要向 Rust 提供的**原生能力集合**（19 个方法）。
 *
 * ── 为什么需要这个类 ────────────────────────────────────────────────────
 * Rust 侧通过 JNI 回调宿主 Activity 上的**实例方法**：
 *
 * ```rust
 * // tauri/src/android_bridge.rs:927
 * pub fn call_activity(name: &str, sig: &str, arg: Option<&str>) -> Option<String> {
 *     let obj = activity()?;                       // ← nativeAttach 传进来的实例
 *     env.call_method(&obj, name, sig, ...)        // ← 按「方法名字符串」调用
 * }
 * ```
 *
 * 关键点（读过实现确认）：
 *   - 用的是**实例引用**，不是 `Class.forName("...MainActivity")`
 *     → 所以宿主 Activity 叫什么名字无所谓，只要有这些方法
 *   - 按**方法名字符串**查找 → 方法被 JNI 找到才会被执行
 *
 * 实测：旧 `MainActivity` 有 **19/19**，而 `ComposeMainActivity` 有 **0/19**。
 * 若直接把主入口切到 Compose，这 19 项能力会**静默失效**（不是崩溃，更难发现）：
 *
 * | 方法 | 缺失后的表现 |
 * |---|---|
 * | `hasAllFilesAccess` | 永远显示「未授权」，用户怎么点都授不了 |
 * | `isBatteryUnrestricted` | 永远显示「未加白名单」 |
 * | `wifiIpv4` | 拿不到局域网 IP（联机房间显示不出地址） |
 * | `thermalStatus` | 永远「正常」 |
 * | `pickGameDir` | 点「选择目录」没反应 |
 * | `installApk` | 应用内更新装不了 |
 * | `preloadJreLibs` | JRE 库不预载 |
 * | … | 其余同理 |
 *
 * ── 设计：一个委托类，两个 Activity 共用 ────────────────────────────────
 * 不复制 MainActivity 的 1359 行，而是把这 19 个方法收敛到本类，
 * 由 Activity 持有并在方法名上与之一致（Kotlin 的方法解析对
 * 「继承来的」和「本类定义的」一视同仁，JNI 也是）。
 *
 * 但 JNI `call_method(&obj, name, ...)` 是在**该对象上**找方法 ——
 * 委托类的同名方法必须能被 `obj` 找到。所以做法是：
 * **Activity 继承本类的能力接口，把实现转发到这里**（见宿主类的
 * `by hostCapabilities()` 转发函数）。
 *
 * 好处：
 *   - 逻辑只写一份（不复制粘贴）
 *   - 两个入口行为**完全一致**（避免旧实现那种「两份代码慢慢漂移」）
 *   - 将来彻底删掉 MainActivity 时，这些能力已经就位
 */
class HostCapabilities(private val activity: Activity) {

    companion object {
        private const val TAG = "HostCapabilities"

        /** 选游戏目录的请求码（与旧 MainActivity 保持一致）。 */
        const val REQ_PICK_GAME_DIR = 4001
        /** 选控制布局文件的请求码。 */
        const val REQ_PICK_CONTROL_LAYOUT = 4002
        /** 选 APK 安装包的请求码。 */
        const val REQ_INSTALL_APK = 4003
        /** 选图片（背景图、皮肤）的请求码。 */
        const val REQ_PICK_IMAGE = 4004

        /**
         * 通配 MIME 类型。
         *
         * ⚠️ 为什么单独定义成常量，而不是在代码里直接写字面量：
         * 通配 MIME 的字面量里含有「星号 + 斜杠 + 星号」这个三字符组合，
         * 而它恰好是**块注释的结束标记**。若该字面量出现在 KDoc 下方，
         * 会把注释提前闭合，导致后面整个类被当成注释掉 ——
         * 编译器报 "Unclosed comment"，行号却指向文件末尾，极难定位。
         * （这个坑我在同一个文件里踩了两次。）
         */
        const val MIME_ALL = "\u002A/\u002A"

        /**
         * 图片 MIME。
         *
         * 同样用转义写，避免「斜杠 + 星号」出现在源码字面量里
         * 被误认为注释结尾。
         */
        const val MIME_IMAGE = "image/\u002A"
    }

    /** 日志页音量键缩放的开关（Rust 调 `setLogZoomCapture` 设置）。 */
    @Volatile
    var logZoomCapture: Boolean = false
        private set

    /** 待落地的主线程动作（由 Activity 的 onResume 消费）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近一次「选游戏目录」的结果，等 Rust 来取（`readPojavPrefs` 同款模式）。 */
    @Volatile
    private var pendingGameDirPick: String? = null

    /** 最近一次「选控制布局」的结果。 */
    @Volatile
    private var pendingControlLayout: String? = null

    /** 最近一次通用文件选择的结果。 */
    @Volatile
    private var pendingPickedFile: String? = null

    // ── 1. systemProxy ────────────────────────────────────────────────────

    /**
     * 系统代理（`http://host:port` 或 `socks5://host:port`，没配返回 null）。
     *
     * ── 为什么不用 ConnectivityManager ──────────────────────────────────
     * 第一版我用了 `ConnectivityManager.getActiveNetwork()` —— 实测直接抛
     * `SecurityException: Neither user … nor current process has
     * android.permission.ACCESS_NETWORK_STATE`（本项目刻意不申请这条权限）。
     *
     * 改用 `ProxySelector`：它读的是 JVM 层的代理配置，
     * **不需要任何权限**，而且能区分 HTTP 与 SOCKS（Rust 侧要这个前缀
     * 来决定用哪种代理）。
     *
     * ── VPN 优先（这条很关键，不能丢）──────────────────────────────────
     * Clash / v2ray 的 TUN 模式已经接管整机流量，此时再叠加系统 HTTP 代理
     * 就是**双重代理**：实测表现为「网络抽风」——版本清单超时、下载半途失败，
     * 且时好时坏极难排查。VPN 在跑就直接返回 null，让请求走 TUN。
     */
    fun systemProxy(): String? {
        // VPN 已接管流量 → 不要再叠加 HTTP 代理
        if (isVpnActive()) {
            Log.i(TAG, "检测到 VPN 生效，忽略系统 HTTP 代理（避免双重代理）")
            return null
        }
        return try {
            java.net.ProxySelector.getDefault()?.let { selector ->
                val proxies = selector.select(java.net.URI("https://piston-meta.mojang.com/"))
                for (p in proxies) {
                    val addr = p.address() as? java.net.InetSocketAddress ?: continue
                    val host = addr.hostString
                    if (host.isNullOrEmpty()) continue
                    when (p.type()) {
                        java.net.Proxy.Type.HTTP -> return "http://$host:${addr.port}"
                        java.net.Proxy.Type.SOCKS -> return "socks5://$host:${addr.port}"
                        else -> {}
                    }
                }
            }
            // 兜底：框架会依据默认网络为 java.net 设置这两个属性
            val host = System.getProperty("http.proxyHost")
            if (!host.isNullOrEmpty()) {
                val port = System.getProperty("http.proxyPort")?.toIntOrNull() ?: 80
                "http://$host:$port"
            } else null
        } catch (e: Throwable) {
            Log.w(TAG, "读系统代理失败", e)
            null
        }
    }

    /**
     * 是否有 VPN 在跑。
     *
     * 判断依据：存在已建立的 tun 网卡、或 VPN 服务已授权并启动。
     * 保守起见——宁可漏判（返回 false，多走一次 HTTP 代理）
     * 也不要误判（返回 true 会丢掉用户真正需要的代理）。
     */
    private fun isVpnActive(): Boolean {
        return try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("utun")) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            false
        }
    }

    // ── 2. wifiIpv4 ───────────────────────────────────────────────────────

    /**
     * 本机 WiFi 的 IPv4 地址（局域网联机要显示给朋友）。
     *
     * ⚠️ 只认 `wlan*` 网卡 —— 这是与旧实现对齐的**有意选择**：
     * 我第一版扫描了所有非回环 IPv4，但那会命中蜂窝网卡（`rmnet*`），
     * 显示出一个**局域网里根本连不上**的地址。
     * 「局域网联机」要的必然是 WiFi 地址。
     *
     * 另外地址里可能带 `%` 后缀（IPv6 zone id 混进来），要截掉。
     */
    fun wifiIpv4(): String? {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.name.startsWith("wlan") }
                .flatMap { iface -> iface.inetAddresses.toList() }
                .mapNotNull { addr ->
                    val h = addr.hostAddress ?: return@mapNotNull null
                    if (h.contains(':')) null else h.substringBefore('%')
                }
                .firstOrNull { it.isNotEmpty() && it != "127.0.0.1" }
        } catch (e: Throwable) {
            Log.w(TAG, "读 WiFi IP 失败", e)
            null
        }
    }

    // ── 3. thermalStatus ──────────────────────────────────────────────────

    /**
     * 设备热状态：「0 正常 … 2 中等 … 4 危急 … 6 关机」。
     *
     * 用途：开服前提示「不是服务器坏了，是手机热了」。
     * 读不到一律当 0（不打扰用户）—— 与 Rust 侧的 `unwrap_or(0)` 一致。
     */
    fun thermalStatus(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
                (pm?.currentThermalStatus ?: 0).toString()
            } else "0"
        } catch (e: Throwable) {
            Log.w(TAG, "读热状态失败", e)
            "0"
        }
    }

    // ── 4. isBatteryUnrestricted ──────────────────────────────────────────

    /**
     * 系统「电池优化」是否已对本应用放行。
     *
     * 返回字符串 "1"/"0" —— Rust 侧按 `== Some("1")` 判断。
     * 长开服必须加白名单，否则 ColorOS 等 ROM 会在息屏后掐掉后台服务
     * （表现：「开服玩一会儿，服自己没了」）。
     */
    fun isBatteryUnrestricted(): String {
        return try {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isIgnoringBatteryOptimizations(activity.packageName) == true) "1" else "0"
        } catch (e: Throwable) {
            Log.w(TAG, "查电池白名单失败", e)
            "0"
        }
    }

    /** 弹系统「忽略电池优化」请求页。 */
    fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "弹电池优化请求失败，退回设置页", e)
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Throwable) {
                Log.w(TAG, "连设置页都打不开", e2)
            }
        }
    }

    // ── 5. hasAllFilesAccess / requestAllFilesAccess ──────────────────────

    /**
     * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已授权。返回 "1"/"0"。
     *
     * 复用既有的 [StorageDirs.allFilesAccessGranted] —— 那里已经处理了
     * Android 11 前后的差异（R 以下看读权限，R+ 看 isExternalStorageManager）。
     * 我自己在 HostCapabilities 里重写了一遍属于重复逻辑，已改回复用。
     */
    fun hasAllFilesAccess(): String =
        if (StorageDirs.allFilesAccessGranted()) "1" else "0"

    /** 弹「所有文件访问」授权页。 */
    fun requestAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } else {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    REQ_PICK_GAME_DIR,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "弹所有文件访问授权失败，退回总列表", e)
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Throwable) {
                Log.w(TAG, "连列表页都打不开", e2)
            }
        }
    }

    // ── 6. gameDirOptions / pickGameDir ───────────────────────────────────

    /**
     * 「游戏目录」可选项：枚举各卷后回传 JSON 数组字符串。
     *
     * Rust 侧拿它渲染「设置 → 游戏目录」的下拉列表。
     */
    fun gameDirOptions(): String {
        return try {
            val arr = JSONArray()
            // 应用私有外部目录（总是可用）
            activity.getExternalFilesDir(null)?.let { arr.put(it.absolutePath) }
            // 主共享存储
            Environment.getExternalStorageDirectory()?.let { arr.put(it.absolutePath) }
            // 可移除卷（SD 卡）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                for (f in activity.getExternalFilesDirs(null)) {
                    val p = f?.absolutePath ?: continue
                    if (arr.toString().contains(p)) continue
                    arr.put(p)
                }
            }
            arr.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "枚举游戏目录失败", e)
            "[]"
        }
    }

    /**
     * 弹目录选择器。
     *
     * 结果通过 [consumeGameDirPick] 取走 —— 与 Rust 的
     * 「发起动作 → 稍后读结果」两步式交互一致（旧实现也是这个模式）。
     */
    fun pickGameDir() {
        mainHandler.post {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    )
                }
                @Suppress("DEPRECATION")
                launchPicker(intent, REQ_PICK_GAME_DIR, "目录选择器")
            } catch (e: Throwable) {
                Log.w(TAG, "弹目录选择器失败", e)
                Toast.makeText(activity, "打不开目录选择器：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 统一的「拉起系统选择器」入口。
     *
     * ⚠️ 真机反馈（OPPO / iQOO / 澎湃，用户点好几次甚至上百次都没反应）：
     * 以前是每点一次就 `startActivityForResult` 一次 —— 连点会排队起多个选择器，
     * 系统只认最后一个或直接丢弃，表现就是"点了没反应"；而某些 ROM 在 Activity
     * 没回到前台时调用也会静默失败，什么日志都不留。
     *
     * 这里做三件事：
     *  1. **连点合并**：5 秒内的重复请求只放行第一个（挡手抖，不挡"取消后重试"）；
     *  2. **前置检查**：Activity 已结束/销毁时明确报错，不去做无效调用；
     *  3. **失败可见**：异常原样弹出来 + 写日志，不再"点了没反应"。
     */
    @Volatile
    private var pickerStartedAt: Long = 0L

    /** 连点合并窗口：只挡"手抖连点"，不挡住用户取消后重新点（5 秒后自动放行）。 */
    private val PICKER_DEBOUNCE_MS = 5_000L

    private fun launchPicker(intent: Intent, requestCode: Int, what: String) {
        val now = System.currentTimeMillis()
        if (pickerStartedAt > 0 && now - pickerStartedAt < PICKER_DEBOUNCE_MS) {
            Log.i(TAG, "刚拉起过$what（${now - pickerStartedAt}ms前），忽略重复请求")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) {
            Log.w(TAG, "Activity 已结束，无法拉起$what")
            Toast.makeText(activity, "界面已经关闭，请重新打开这一页", Toast.LENGTH_SHORT).show()
            return
        }
        pickerStartedAt = now
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, requestCode)
            Log.i(TAG, "已拉起$what（code=$requestCode）")
        } catch (e: Throwable) {
            pickerStartedAt = 0L
            Log.w(TAG, "拉起${what}失败", e)
            Toast.makeText(activity, "打不开$what：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 选择器结果回来了（无论成功/取消），清掉在途标记。 */
    private fun clearPickerInFlight() {
        pickerStartedAt = 0L
    }

    /** 取走最近一次目录选择结果（取一次即清空）。 */
    fun consumeGameDirPick(): String? {
        val v = pendingGameDirPick
        pendingGameDirPick = null
        return v
    }

    /** 由 Activity 的 onActivityResult 调用。 */
    fun onPickGameDirResult(uri: Uri?) {
        if (uri != null) {
            // ⚠️ SAF 的授权默认是**临时**的（任务/进程结束就失效）→ 换完目录、重启一次就
            // "不生效"。必须显式持久化。真机反馈三台全挂，这是主因之一。
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { Log.w(TAG, "持久化目录授权失败: $uri", it) }
        }
        val path = uri?.let { resolveTreeUri(it) }
        pendingGameDirPick = path
        if (path != null) {
            Log.i(TAG, "游戏目录已选: $path")
        } else if (uri != null) {
            Log.w(TAG, "目录 URI 无法解析成路径（provider=${uri.authority}）")
        }
    }

    /**
     * 把 SAF 的 `content://` tree URI 解析成本地真实路径。
     *
     * 为什么需要：游戏 JVM 与 Rust 的 `std::fs` 都只认**真实路径**，
     * SAF 的 URI 两边都用不了（旧实现的注释也记了这点）。
     */
    private fun resolveTreeUri(uri: Uri): String? {
        return try {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            val split = docId?.split(":") ?: return null
            if (split.size < 2) return null
            val type = split[0]
            val rel = split[1]
            when {
                type.equals("primary", ignoreCase = true) ->
                    File(Environment.getExternalStorageDirectory(), rel).absolutePath
                else -> "/storage/$type/$rel"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "解析 tree URI 失败", e)
            null
        }
    }

    // ── 7. readPojavPrefs / readClipboardText ─────────────────────────────

    /**
     * 读 Pojav 的启动偏好（JSON 字符串）。
     *
     * 读不到返回 null —— Rust 侧会退回默认值。
     */
    fun readPojavPrefs(): String? {
        return try {
            val obj = JSONObject()
            obj.put("renderer", LauncherPreferences.PREF_RENDERER ?: "")
            obj.put("ram", LauncherPreferences.PREF_RAM_ALLOCATION)
            obj.put(
                "defaultCtrl",
                LauncherPreferences.PREF_DEFAULTCTRL_PATH ?: "",
            )
            obj.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "读 Pojav 偏好失败", e)
            null
        }
    }

    /** 写系统剪贴板文本（联机地址这类「要粘到别处」的内容）。 */
    fun copyToClipboard(label: String, text: String) {
        mainHandler.post {
            try {
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(label, text))
                Toast.makeText(activity, "已复制：$text", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.w(TAG, "写剪贴板失败", e)
            }
        }
    }

    /** 读系统剪贴板文本。 */
    fun readClipboardText(): String? {
        return try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "读剪贴板失败", e)
            null
        }
    }

    // ── 8. vpnConsentGranted / ensureVpnConsent ───────────────────────────

    /**
     * VPN 授权是否已拿到。返回 "1"/"0"。
     *
     * 陶瓦联机的数据面必须走 TUN，而 TUN 要用户点同意 ——
     * 拿不到同意就「房间里只有房主」。
     */
    fun vpnConsentGranted(): String {
        return try {
            val prefs = activity.getSharedPreferences("qookix_vpn", Context.MODE_PRIVATE)
            if (prefs.getBoolean("consent_granted", false)) "1" else "0"
        } catch (e: Throwable) {
            "0"
        }
    }

    /** 弹 VPN 授权请求。 */
    fun ensureVpnConsent() {
        mainHandler.post {
            try {
                // VpnService.prepare 会返回一个待启动的 Intent（null = 已授权）
                val intent = android.net.VpnService.prepare(activity)
                if (intent != null) {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, REQ_PICK_CONTROL_LAYOUT)
                } else {
                    markVpnConsent(true)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "请求 VPN 授权失败", e)
            }
        }
    }

    /** 由 Activity 记录 VPN 授权结果。 */
    fun markVpnConsent(granted: Boolean) {
        try {
            activity.getSharedPreferences("qookix_vpn", Context.MODE_PRIVATE)
                .edit().putBoolean("consent_granted", granted).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "记录 VPN 授权失败", e)
        }
    }

    // ── 9. ensureTunnelService（联机房间）─────────────────────────────────

    /**
     * 拉起 `:tunnel` 进程的服务。
     *
     * **不能**用 `call_activity` 之外的普通 startService ——
     * 服务必须跑在独立进程（`libterracotta.so` 只在那里加载，
     * 跨进程没法把 ParcelFileDescriptor 交给 native）。
     */
    fun ensureTunnelService() {
        mainHandler.post {
            try {
                val intent = Intent().apply {
                    component = android.content.ComponentName(
                        activity, "com.zhayi.qookix.tunnel.TerracottaTunnelService"
                    )
                }
                activity.startService(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "拉起 tunnel 服务失败", e)
            }
        }
    }

    // ── 10. installApk ────────────────────────────────────────────────────

    /**
     * 安装 APK（应用内更新）。
     *
     * @param path APK 的绝对路径
     */
    fun installApk(path: String) {
        mainHandler.post {
            try {
                val file = File(path)
                if (!file.isFile) {
                    Log.w(TAG, "installApk: 文件不存在 $path")
                    return@post
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "安装 APK 失败", e)
                Toast.makeText(activity, "无法启动安装器", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 11. setLogZoomCapture ─────────────────────────────────────────────

    /** 开关「日志页音量键缩放」（Rust 在进入日志页时设置）。 */
    fun setLogZoomCapture(enabled: Boolean) {
        logZoomCapture = enabled
        Log.i(TAG, "日志音量键缩放: $enabled")
    }

    // ── 11b. 打开外部链接 / 应用详情页 ────────────────────────────────────

    /**
     * 用系统浏览器打开链接。
     *
     * ── 为什么不用 Rust 的 opener 插件 ───────────────────────────────────
     * `tauri_plugin_opener` 确实注册了，但走它要经过
     * JS → IPC → Rust → JNI 一整条链路，而宿主直接 `startActivity`
     * 只需要一次调用。对「点个链接」这种操作，链路越短越不容易出问题。
     *
     * 而且这里能顺手处理「没有浏览器」的异常（平板上常见）。
     *
     * ⚠️ 注意：上面那行分隔线**不能以斜杠星号结尾** —— 那会提前闭合
     * 这个 KDoc 块注释，导致后面所有代码被当成注释（编译器报
     * "Unclosed comment"，且行号指向文件末尾，很难定位）。
     */
    fun openUrl(url: String) {
        if (url.isBlank()) return
        mainHandler.post {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(intent)
                Log.i(TAG, "打开链接: $url")
            } catch (e: Throwable) {
                Log.w(TAG, "打开链接失败: $url", e)
                Toast.makeText(activity, "打不开链接（没有可用的浏览器？）", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── 12. importControlLayout ───────────────────────────────────────────

    /**
     * 导入控制布局文件（弹文件选择器）。
     *
     * 结果通过 [consumeControlLayoutPick] 取走。
     */
    fun importControlLayout() {
        mainHandler.post {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // ⚠️ 这里原本写的是 MIME 通配字面量（含块注释结束标记），
                    // 会提前闭合上面的 KDoc，导致后面整个类被注释掉。
                    // 改用常量，见 MIME_ALL 的说明。
                    type = MIME_ALL
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                }
                @Suppress("DEPRECATION")
                launchPicker(intent, REQ_PICK_CONTROL_LAYOUT, "布局选择器")
            } catch (e: Throwable) {
                Log.w(TAG, "弹布局选择器失败", e)
            }
        }
    }

    /** 取走最近一次控制布局导入结果。 */
    fun consumeControlLayoutPick(): String? {
        val v = pendingControlLayout
        pendingControlLayout = null
        return v
    }

    /** 由 Activity 的 onActivityResult 调用。 */
    fun onImportControlLayoutResult(uri: Uri?) {
        pendingControlLayout = uri?.toString()
    }

    // ── 12b. 通用文件选择（背景图、皮肤导入都用它）──────────────────────────

    /**
     * 弹一个通用文件选择器。
     *
     * 结果通过 [consumePickedFile] 取走 —— 与 `pickGameDir` 同一套
     * 「发起动作 → 稍后取结果」两步式，因为 Android 的文件选择是异步的，
     * JNI 调用不能阻塞等结果。
     *
     * @param mime 限定类型，例如图片用 [MIME_IMAGE]、任意文件用 [MIME_ALL]
     * @param requestCode 请求码（区分不同用途）
     */
    fun pickFile(mime: String, requestCode: Int) {
        mainHandler.post {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                }
                @Suppress("DEPRECATION")
                launchPicker(intent, requestCode, "文件选择器")
            } catch (e: Throwable) {
                Log.w(TAG, "弹文件选择器失败", e)
                Toast.makeText(activity, "打不开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 取走最近一次选中的文件路径（取一次即清空）。 */
    fun consumePickedFile(): String? {
        val v = pendingPickedFile
        pendingPickedFile = null
        return v
    }

    /** 由 Activity 的 onActivityResult 调用。 */
    fun onPickFileResult(uri: Uri?) {
        if (uri == null) {
            pendingPickedFile = null
            return
        }
        // ⚠️ 统一「复制到应用目录」，不再直接用 `_data` 解析出的 `/sdcard/...`：
        // Android 11+ 没有「所有文件访问」时，应用对 /sdcard 的**原始路径**读写会被拒
        // （真机实测：选了整合包后导入立即失败、日志里连一条 install 都没有；
        // `appops get … MANAGE_EXTERNAL_STORAGE` = No operations）。
        // ContentResolver 走的是 SAF 授权，任何 ROM 都有效 → 复制一份最稳。
        // 顺序：先复制；复制失败再退回真实路径（老行为）；最后才是 content://（Rust 读不了，仅兜底）。
        pendingPickedFile = copyToTemp(uri) ?: resolveToRealPath(uri)
        Log.i(TAG, "已选文件: ${pendingPickedFile ?: "(无法读取)"}")
    }

    /**
     * 把 `content://` 解析成真实文件路径。
     *
     * 多数情况下 `_data` 列就是真实路径；Android 10+ 上对部分 provider
     * 该列可能为空，那就拿不到 —— 调用方会退回 URI 形式。
     *
     * ⚠️ `_data` 列名用字面量而不是 `OpenableColumns.DATA`：
     * 后者在较新的 SDK 上被标为 deprecated 并从常量集中移除，
     * 直接引用会编译不过。`"_data"` 是它的实际字符串值，稳定可用。
     */
    private fun resolveToRealPath(uri: Uri): String? {
        return try {
            if (uri.scheme == "file") return uri.path
            activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex("_data")
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "解析文件路径失败: $uri", e)
            null
        }
    }

    /**
     * 把 `content://` 的内容复制到应用临时目录，返回**真实路径**。
     *
     * 为什么需要：Rust 侧所有文件操作都是 `std::fs`（只认路径），而 `_data` 列在
     * 不少 ROM 上为空（OPPO 实测）。以前解析不出就把 `content://` 原样交给 Rust，
     * 结果是静默失败 —— 用户看到的就是「选完文件没反应」。
     */
    private fun copyToTemp(uri: Uri): String? {
        return try {
            val dir = java.io.File(activity.cacheDir, "picked").apply { mkdirs() }
            val name = (activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }) ?: "picked-${System.currentTimeMillis()}"
            val out = java.io.File(dir, name.replace('/', '_'))
            val ins = activity.contentResolver.openInputStream(uri) ?: return null
            ins.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            Log.i(TAG, "已复制到临时文件: ${out.absolutePath} (${out.length()} B)")
            out.absolutePath
        } catch (e: Throwable) {
            Log.w(TAG, "复制选中文件失败: $uri", e)
            null
        }
    }

    // ── 13. preloadJreLibs ────────────────────────────────────────────────

    /**
     * 预载 JRE 动态库。
     *
     * @param dir JRE 运行目录
     * @return 加载成功的库数量（失败返回 0）
     *
     * 提前 dlopen 能让游戏启动快一些；失败不影响启动（游戏侧还会再加载）。
     */
    fun preloadJreLibs(dir: String): Int {
        return try {
            val libDir = File(dir, "lib")
            if (!libDir.isDirectory) {
                Log.w(TAG, "preloadJreLibs: 目录不存在 ${libDir.absolutePath}")
                return 0
            }
            var count = 0
            libDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.endsWith(".so")) {
                    try {
                        System.load(f.absolutePath)
                        count++
                    } catch (e: Throwable) {
                        Log.d(TAG, "跳过 ${f.name}: ${e.message}")
                    }
                }
            }
            Log.i(TAG, "预载 JRE 库: $count 个（$dir）")
            count
        } catch (e: Throwable) {
            Log.w(TAG, "预载 JRE 库失败", e)
            0
        }
    }

    // ── 14. enableSdlIntegration ──────────────────────────────────────────

    /**
     * SDL 启动器侧整合。
     *
     * SDL3 在 Android 上依赖由宿主 Application 提供的一些回调，
     * 这里只是把状态同步给 SDL（见 `org.libsdl.app.SDL`）。
     */
    fun enableSdlIntegration() {
        try {
            CallbackBridge.notifyLauncher(
                CallbackBridge.NOTIF_TYPE_SDL,
                CallbackBridge.ACTION_INIT_LAUNCHER_INTEGRATION,
            )
            Log.i(TAG, "SDL 启动器侧整合已启用")
        } catch (e: Throwable) {
            Log.w(TAG, "SDL 整合失败", e)
        }
    }
}

