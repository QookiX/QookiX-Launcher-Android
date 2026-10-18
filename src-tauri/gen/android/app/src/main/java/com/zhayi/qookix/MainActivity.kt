package com.zhayi.qookix

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.AlertDialog
import android.content.Intent
import net.kdt.pojavlaunch.CustomControlsActivity
import org.lwjgl.glfw.CallbackBridge
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import com.zhayi.qookix.nativebridge.PojavShim
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import com.zhayi.qookix.tauri.TauriBridge
import org.json.JSONObject
import java.io.File

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    // 把 JavaVM 与本实例交给 Rust：之后方向锁定 / 文件选择落地 / 系统代理都由 Rust 回调本类
    TauriBridge.attach(this)
    // 给 Pojav 输入桥（org.lwjgl.glfw.CallbackBridge）准备剪贴板等平台能力
    PojavShim.init(this)

    // 内置默认控制布局（`<files>/controlmap/default.json`）必须在启动器阶段就落盘：
    // ① 它原先只在游戏启动时写，从没进过游戏的用户打开「按键」页会看到一个空列表，
    //    点「编辑布局」更会直接报 `NoSuchFileException`；
    // ② 只在文件缺失 / 皮肤版本变了时写一次（见 ControlLayoutSeed），代价可以忽略。
    ControlLayoutSeed.ensure(this)
    // 顺手清掉导入布局的中转文件（`controlmap/TMP_IMPORT*.json`）。它原先只在导入时写、
    // **没有任何地方删**，于是越积越多；而「按键」页的列表把它过滤掉（用户看不到、
    // 也就删不掉），原生编辑器那个选文件的对话框又照单全收 —— 用户就在那里看到一堆
    // 删不掉的「TMP_IMPORT_FILE 副本」。
    sweepImportTempFiles()

    // 升级后必须让 WebView 换一份前端：它的 HTTP 缓存会把旧 bundle 一直喂回来，
    // 表现就是"装完新版 APK，界面还是老样子"（2026-10-05 实测踩到 ✗）。
    clearWebViewCacheOnUpgrade()

    // 上一次「结束当前游戏 → 换版本」留下的待启动：进程是被守护服务自动拉回来、
    // 被用户点「游戏已退出」通知、还是用户自己重开，都会走到这里 —— 把用户当时
    // 点的那一版接上（consume 即清空，只生效一次）。
    PendingLaunch.consume(this)?.let { (pendingId, pendingUuid) ->
      Log.i(TAG, "接上次未完成的启动：实例 $pendingId")
      Handler(Looper.getMainLooper()).postDelayed({
        // 先清掉"上一个游戏"残留的任务记录，否则新 Intent 会被投给被系统恢复出来的旧实例，
        // 结果启动的还是上一次那个版本（实测踩过 ✗，见 removeStaleGameTask 注释）。
        removeStaleGameTask()
        runCatching { GameActivity.start(this, pendingId, pendingUuid) }
          .onFailure { Log.e(TAG, "接续启动失败，已清空记录", it) }
      }, 600L)
    }

    // 上次游戏留下的「空任务」：GameActivity 带 documentLaunchMode="intoExisting"，
    // 任务记录**不随进程消失**，用户在多任务里点那张卡片就会把游戏再启动一遍
    // （用户反馈的 bug）。游戏确实没在跑时才清 —— 否则会把「切回后台运行中的游戏」也断掉。
    // 延后一点再判：Rust 侧的游戏状态在 onStart 之后才稳。
    Handler(Looper.getMainLooper()).postDelayed({
      val running = runCatching { TauriBridge.isGameRunning() }.getOrDefault(false)
      if (!running) removeStaleGameTask()
    }, 800L)

    applyStoredOrientation()
    requestNotificationPermission()
    // 启动陶瓦联机隧道（独立进程 :tunnel，.so 只在那里加载，主进程不链接）
    startTunnelService()
    disableWebViewZoom()
    setupBackNavigation()
  }

  /**
   * 关掉 WebView 的双指缩放。
   *
   * 为什么非做不可：启动器界面是按「可用高度」精确排版的（标题栏 + 内容 + 底部导航，
   * 列表内部自己滚）。一旦被双指放大，界面就会超出屏幕、按钮点不到，而且缩不回去。
   *
   * 为什么不能只靠 index.html 里的 `user-scalable=no`：wry 生成的 WebView 没有打开
   * `useWideViewPort`，这种情况下 WebView 并不采纳页面里的 viewport meta；
   * 真正决定能不能捏合的是 WebView 自己的 `settings.supportZoom`（默认 true）。
   *
   * 为什么写在这里而不是 RustWebView.kt：那个文件是 wry 自动生成的
   * （文件头写着 DO NOT MODIFY），每次构建都可能被覆盖；MainActivity 是我们自己维护的。
   * WebView 由 Rust 在 onCreate 之后创建，所以要重试查找。
   */
  /**
   * 返回键 / 返回手势。
   *
   * 为什么必须自己接：wry 自带的返回处理被 Tauri 模板显式关掉了
   * （`TauriActivity.handleBackNavigation = false`），项目此前也没注册回调 ——
   * 于是任何页面按返回都落到 Activity 默认行为 `finish()`，**整个启动器被关掉**。
   *
   * 顺序（同 Pojav `LauncherActivity.onBackPressed` 的「先退一层再退到底」）：
   *  1. 先问前端：派发可取消的 `qk-back`，前端做应用内后退时 `preventDefault()`；
   *  2. 前端没处理 → WebView 历史能退就 `goBack()`；
   *  3. 都不行 → 才 `finish()`。
   *
   * 不要改 `TauriActivity.kt`：那是生成物（文件头写着 DO NOT MODIFY）。
   */
  private fun setupBackNavigation() {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        Log.i(TAG, "返回键：dispatcher 通路")
        handleBack()
      }
    })
  }

  /**
   * 旧返回键通路（`onBackPressed`）。
   *
   * Android 13+ 走预测返回时框架**不再调用**它，只有声明了
   * `android:enableOnBackInvokedCallback="true"` 之后 dispatcher 才接管；
   * 这里保留一份兜底，两条通路最终都汇集到 [handleBack]。
   */
  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {
    Log.i(TAG, "返回键：onBackPressed 通路")
    handleBack()
  }

  private var lastBackHandledAt = 0L

  /**
   * 「音量键当缩放键」的开关。**默认关** —— 音量键在别处（游戏里、系统界面）必须照常工作，
   * 所以只有日志页在前端调`set_log_zoom_capture(true)` 之后才拦截。
   *
   * 为什么必须走原生：Android 的音量键**不会**派发成 WebView 的 keydown，
   * 前端 `addEventListener('keydown')` 永远收不到。
   */
  private var logZoomCapture = false

  fun setLogZoomCapture(on: Boolean) {
    logZoomCapture = on
  }

  /**
   * 音量-缩小 / 音量+放大日志字号。走 `qk-log-zoom` 自定义事件，
   * 和返回键那套 `evaluateJavascript` 是同一个思路。
   *
   * 注意要 `super.onKeyDown(...)`：**不能**返回 true 吃掉事件，否则系统音量条不弹、
   * 免打扰/媒体键行为也会乱。这里是「事件继续走，同时通知前端缩放」。
   */
  override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
    if (logZoomCapture && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
      val delta = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
      val wv = findWebView(window.decorView)
      if (wv != null) {
        wv.evaluateJavascript("window.dispatchEvent(new CustomEvent('qk-log-zoom',{detail:{delta:$delta}}))") { }
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  /** 返回键的**唯一**处理入口（两条通路汇到这里，做 300ms 去重防止处理两次）。 */
  private fun handleBack() {
    val now = System.currentTimeMillis()
    if (now - lastBackHandledAt < 300) return
    lastBackHandledAt = now

    val wv = findWebView(window.decorView)
    Log.i(TAG, "返回键：webview=${wv != null}")
    if (wv == null) {
      finish()
      return
    }
    // 派发一个**可取消**的 qk-back：前端关掉弹层、或做应用内后退时会
    // preventDefault()，表示「我已经处理了」。
    wv.evaluateJavascript(
      "(function(){try{var e=new CustomEvent('qk-back',{cancelable:true});" +
        "window.dispatchEvent(e);return e.defaultPrevented?'handled':'pass';}" +
        "catch(err){return 'pass';}})()"
    ) { result ->
      Log.i(TAG, "返回键：前端结果=$result canGoBack=${wv.canGoBack()}")
      if (result != null && result.contains("handled")) return@evaluateJavascript
      if (wv.canGoBack()) wv.goBack() else finish()
    }
  }

  private fun disableWebViewZoom(attempt: Int = 0) {
    val wv = findWebView(window.decorView)
    if (wv == null) {
      if (attempt < 50) window.decorView.postDelayed({ disableWebViewZoom(attempt + 1) }, 100)
      return
    }
    try {
      // 注意：supportZoom 在 SDK 里只有 supportZoom() 取值器，Kotlin 不合成属性，
      // 必须按方法调用 setSupportZoom(...)；另外两个才能当属性赋值。
      wv.settings.setSupportZoom(false)
      wv.settings.builtInZoomControls = false
      wv.settings.displayZoomControls = false
      // 跟随系统字体放大会把按固定字号排版的界面撑破（标题栏/底栏溢出）。
      // 锁到 100%：界面缩放由应用自己的「界面缩放」设置负责。
      wv.settings.textZoom = 100
      // 前端资源全部来自 APK 内置 assets（tauri:// 本地协议），没有联网价值；
      // 而 WebView 会把 `tauri://localhost/index.html` 及带 hash 的 chunk 缓存下来，
      // 覆盖安装后仍然吐旧界面（实测：新关于页不出现、加载的是旧 chunk 文件名）。
      // 全部走网络加载语义（LOAD_NO_CACHE）就能根治这类「更新了却看不到」的问题。
      wv.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
      Log.i(TAG, "已关闭 WebView 缩放，并禁用资源缓存（LOAD_NO_CACHE）")
    } catch (e: Exception) {
      Log.w(TAG, "关闭 WebView 缩放失败", e)
    }
  }

  /**
   * 读取移植过来的 Pojav 控制层设置。
   *
   * 为什么需要这座桥：Pojav 的那些设置（按钮大小、鼠标速度、**渲染分辨率缩放**、
   * 忽略刘海、长按判定、陀螺仪、禁用手势、备选渲染表面…）全部是
   * `LauncherPreferences` 从 `SharedPreferences("launcher_preferences")` 读的，
   * 而 QookiX 自己的设置存在 `files/settings.json`（Rust 侧）—— 两边不通，
   * 于是这些项永远停在默认值，用户根本没得调。
   * 这里把整份 SharedPreferences 以 JSON 读出来给前端做界面。
   */
  /**
   * 预加载 JRE 自带的原生库（主要给 JDK 8 用）。
   *
   * 为什么需要：Android 的 linker 只按**命名空间里的路径**解析裸文件名，
   * 而应用私有目录（files/runtimes/...）不在其中。JDK 8 的原生库互相依赖用的是
   * 裸名字（libnio.so → libnet.so），于是 JVM 刚跑 main 就
   * `UnsatisfiedLinkError: library "libnet.so" not found`。
   * 而 bionic 在解析 DT_NEEDED 时会**先在已加载的库里按 soname 匹配**，
   * 所以这里用 System.load(绝对路径) 把它们提前加载进来即可。
   *
   * 库之间有依赖，顺序无法预知，因此多轮尝试：一轮里没有任何新库加载成功就停。
   * 加载失败**不抛异常**（依赖未就绪很正常，下一轮再试）。
   */
  fun preloadJreLibs(jreHome: String): Int {
    val dirs = mutableListOf<java.io.File>()
    for (arch in arrayOf("aarch64", "arm64", "arm", "arm32", "x86_64", "x86")) {
      dirs.add(java.io.File(jreHome, "lib/$arch"))
      dirs.add(java.io.File(jreHome, "lib/$arch/jli"))
      dirs.add(java.io.File(jreHome, "lib/$arch/server"))
    }
    dirs.add(java.io.File(jreHome, "lib"))
    dirs.add(java.io.File(jreHome, "lib/server"))
    dirs.add(java.io.File(jreHome, "lib/jli"))

    val pending = mutableListOf<java.io.File>()
    for (d in dirs) {
      d.listFiles { f -> f.isFile && f.name.endsWith(".so") }?.let { pending.addAll(it) }
    }
    // jar 里可能带同名库，去重
    val uniq = pending.distinctBy { it.absolutePath }

    var loaded = 0
    var progress = true
    var rounds = 0
    val still = uniq.toMutableList()
    while (progress && rounds < 8) {
      progress = false
      rounds++
      val it = still.iterator()
      while (it.hasNext()) {
        val f = it.next()
        try {
          System.load(f.absolutePath)
          loaded++
          it.remove()
          progress = true
        } catch (e: Throwable) {
          // 依赖还没加载，下一轮再试
        }
      }
    }
    Log.i(TAG, "预加载 JRE 原生库：成功 $loaded 个，仍失败 ${still.size} 个（$rounds 轮）")
    return loaded
  }
  fun readPojavPrefs(): String {
    return try {
      val sp = getSharedPreferences(PREFS_POJAV, MODE_PRIVATE)
      val obj = JSONObject()
      for ((k, v) in sp.all) obj.put(k, v)
      obj.toString()
    } catch (e: Exception) {
      Log.w(TAG, "读取 Pojav 设置失败", e)
      "{}"
    }
  }

  /**
   * 写入 Pojav 设置并**立刻刷新内存里的静态字段**。
   *
   * 只写 SharedPreferences 是不够的：`LauncherPreferences` 在启动时读一次就缓存成
   * 静态字段（`PREF_SCALE_FACTOR` 等），游戏内各处读的都是缓存值。
   * 写完必须再调一次 `loadPreferences`，否则要等下次冷启动才生效。
   */
  fun writePojavPrefs(json: String) {
    try {
      val sp = getSharedPreferences(PREFS_POJAV, MODE_PRIVATE)
      val obj = JSONObject(json)
      val editor = sp.edit()
      for (key in obj.keys()) {
        when (val v = obj.get(key)) {
          is Boolean -> editor.putBoolean(key, v)
          is Int -> editor.putInt(key, v)
          is Long -> editor.putLong(key, v)
          is Double -> editor.putFloat(key, v.toFloat())
          is String -> editor.putString(key, v)
          else -> {}
        }
      }
      editor.apply()
      // loadPreferences 只认 `DEFAULT_PREF`，启动器进程里它通常还是 null ——
      // 不先赋值的话 loadPreferences 会直接返回（日志里那句
      // 「DEFAULT_PREF 为空，使用默认值」就是这个），静态字段根本没刷新。
      LauncherPreferences.DEFAULT_PREF = sp
      LauncherPreferences.loadPreferences(this)
      Log.i(TAG, "已写入 Pojav 设置：${obj.keys().asSequence().joinToString(",")}")
    } catch (e: Exception) {
      Log.w(TAG, "写入 Pojav 设置失败", e)
    }
  }

  private fun findWebView(v: View): WebView? {
    if (v is WebView) return v
    if (v is ViewGroup) {
      for (i in 0 until v.childCount) {
        findWebView(v.getChildAt(i))?.let { return it }
      }
    }
    return null
  }

  /**
   * Android 13+ 前台服务的通知需要用户授权才会显示。不申请的话，游戏运行时
   * 通知栏里那条「停止」通知根本看不到，用户就没有正常途径结束游戏。
   */
  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
    try {
      requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    } catch (e: Exception) {
      Log.w(TAG, "申请通知权限失败", e)
    }
  }

  /**
   * 拉起系统安装器安装更新包（由 Rust 的 install_update 命令调用）。
   *
   * 用 FileProvider 把 `<files>/updates` 里下载好的 apk 暴露成 content:// —— 安卓 7 以后
   * 不允许把 file:// 路径交给别的应用（会 FileUriExposedException）。
   * 系统会弹「是否安装」确认框，装完是覆盖安装（签名不一致会被系统拒绝）。
   */
  fun installApk(path: String) {
    runOnUiThread {
      try {
        val file = File(path)
        if (!file.isFile) {
          Toast.makeText(this, "安装包不存在：$path", Toast.LENGTH_SHORT).show()
          return@runOnUiThread
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
          this,
          "$packageName.fileprovider",
          file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "application/vnd.android.package-archive")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
      } catch (e: Exception) {
        Log.e(TAG, "拉起安装器失败", e)
        Toast.makeText(this, "拉起安装器失败：${e.message}", Toast.LENGTH_SHORT).show()
      }
    }
  }

  /** 拉起游戏界面（由 Rust 的 launch_game 命令调用）。
   *
   * 游戏必须跑在 [GameActivity] 里：它挂着 GameSurfaceView，GL4ES 才有真正的 Surface 可画。
   * 真正的 JVM 启动由 GameActivity → TauriBridge.launchGame 触发。
   */
  fun startGameActivity(instanceId: String, accountUuid: String) {
    runOnUiThread {
      // 已有游戏在跑、而且用户点的是**另一个实例** → 不能直接拉起：
      // `singleTask` 会把旧实例拉到前台、Rust 也会拒第二次启动（见 PendingLaunch 注释）。
      // 所以先问用户「结束当前的、换成这个吗」。
      val runningId = runningInstanceId()
      if (runningId != null && runningId != instanceId) {
        Log.i(TAG, "当前跑的是 $runningId，用户点了 $instanceId → 需要先结束再换")
        confirmSwitchInstance(instanceId, accountUuid)
        return@runOnUiThread
      }
      try {
        GameActivity.start(this, instanceId, accountUuid)
      } catch (e: Exception) {
        Log.e(TAG, "启动游戏界面失败", e)
      }
    }
  }

  /**
   * 清掉残留的"游戏任务"记录。
   *
   * `GameActivity` 带 `documentLaunchMode="intoExisting"`，任务记录**不随进程消失**：
   * 进程被游戏退出带走后，system_server 仍记着这个任务，下次会把旧实例恢复出来。
   * 于是「换版本」时新 Intent 会被投给那个旧实例（`singleTask` 复用、且没人重写
   * `onNewIntent`）→ 启动的还是上一次那版（2026-10-05 实测踩到 ✗）。
   * `getAppTasks()` 只返回本应用自己的任务，不需要任何权限。
   */
  private fun removeStaleGameTask() {
    try {
      val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
      am.appTasks?.forEach { task ->
        val cmp = task.taskInfo?.baseIntent?.component
        if (cmp?.className == GameActivity::class.java.name) {
          Log.i(TAG, "清理残留的游戏任务：taskId=${task.taskInfo?.taskId}")
          task.finishAndRemoveTask()
        }
      }
    } catch (e: Throwable) {
      Log.w(TAG, "清理残留游戏任务失败（不影响启动）", e)
    }
  }

  /**
   * 版本变化时清掉 WebView 缓存并重新加载。
   *
   * 为什么必须做：前端资源走 `http://tauri.localhost`，WebView 会像普通网页一样缓存它们 ——
   * 装了新 APK 之后仍可能一直吃旧 bundle，用户看到的是**旧界面** ✗（2026-10-05 实测：
   * 新代码确实进了 APK（哈希一致 ✓），界面却还是老样子 ✗）。
   * WebView 是 Rust 侧稍后创建的，所以延后一点找到它再「清缓存 + reload」。
   */
  private fun clearWebViewCacheOnUpgrade() {
    val prefs = getSharedPreferences("qookix_webview", Context.MODE_PRIVATE)
    // 用 PackageManager 取版本，不依赖 BuildConfig（它所在的包名跟着 namespace 走，容易踩 ✗）
    val current = runCatching {
      val info = packageManager.getPackageInfo(packageName, 0)
      "${info.versionName}+${info.longVersionCode}"
    }.getOrElse { "unknown" }
    // key 带 _v2：2026-10-05 换过一次清理策略（只清 HTTP 缓存不够，还要绕一次缓存重载），
    // 改 key 让**所有**已装用户都强制走一次新逻辑，而不是只有刚好升级的那批。
    if (prefs.getString("last_version_v2", null) == current) return
    prefs.edit().putString("last_version_v2", current).apply()
    Log.i(TAG, "检测到版本变化（$current），清 WebView 缓存并绕过缓存重载")
    window.decorView.postDelayed({
      val web = findWebView(window.decorView)
      if (web == null) {
        Log.w(TAG, "没找到 WebView，跳过清缓存")
        return@postDelayed
      }
      runCatching { web.clearCache(true) }
        .onFailure { Log.w(TAG, "清 WebView 缓存失败", it) }
      // 关键：仅 clearCache 不够 —— WebView 仍可能把**旧的入口 HTML** 从缓存喂回来，
      // 而它引用的旧 chunk 又还在包里，于是界面永远停在旧版 ✗（2026-10-05 实测 ✓）。
      // 所以这次 reload 明确设成"不走缓存"，读完再恢复默认，免得影响日常加载速度。
      val settings = web.settings
      val previousMode = settings.cacheMode
      runCatching { settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE }
      runCatching { web.reload() }
      window.decorView.postDelayed({
        runCatching { settings.cacheMode = previousMode }
      }, 5000L)
    }, 1500L)
  }

  /** 当前正在运行的实例 id；没在跑 / 查不到都返回 null（查不到时按"没在跑"处理，别拦用户）。 */
  private fun runningInstanceId(): String? = try {
    if (!TauriBridge.isGameRunning()) null
    else JSONObject(TauriBridge.gameStatus()).optString("instance_id").ifEmpty { null }
  } catch (e: Throwable) {
    Log.w(TAG, "查询当前实例失败，按未运行处理", e)
    null
  }

  /**
   * 「结束当前游戏并换成用户点的那版」确认框。
   *
   * 为什么必须重启进程：一个进程只能有一个游戏 JVM（见 [PendingLaunch] 注释）。
   * 这里只做三件事：记下待启动 → 优雅结束游戏（走 Rust，会落存档/日志）→ 让进程被带走。
   * `GameGuardService` 会把启动器拉回来（后台拉起被系统拦时发「游戏已退出」通知兜底），
   * [onCreate] 再消费那条记录，自动进入用户点的那一版。
   */
  private fun confirmSwitchInstance(instanceId: String, accountUuid: String) {
    AlertDialog.Builder(this, R.style.QookixAlertDialog)
      .setTitle("需要先结束当前游戏")
      .setMessage("一个进程里只能跑一个游戏，已经有一个版本在运行了。\n\n要结束它并启动你刚点的这个版本吗？")
      .setNegativeButton(android.R.string.cancel, null)
      .setPositiveButton("结束并启动") { _, _ -> relaunchIntoInstance(instanceId, accountUuid) }
      .show()
  }

  private fun relaunchIntoInstance(instanceId: String, accountUuid: String) {
    PendingLaunch.save(this, instanceId, accountUuid)
    // 故意**不**调 GameGuardService.disarm：就是要它把启动器拉回来。
    Thread {
      runCatching { TauriBridge.killGame() }
        .onFailure { Log.w(TAG, "结束当前游戏失败", it) }
    }.start()
    // 兜底：JVM 若没把进程带走，用户会停在"点了按钮却没反应"的界面上，5 秒后自己收尾
    // （守护服务同样会拉回启动器，待启动记录也还在，重开也能接上）。
    Handler(Looper.getMainLooper()).postDelayed({
      Log.w(TAG, "结束游戏后进程仍在，主动收尾（守护服务会拉回启动器）")
      runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }, 5000L)
  }

  /** 26.3+ 的 SDL 窗口层需要启动器侧的整合（由 Rust 在启动前调用）。
   *
   * 为什么必须做：SDL3 在安卓上要靠 Java 胶水（org.libsdl.app.SDL）拿 Surface 与 IME 等，
   * 这些事情得由启动器侧准备好。参考实现 Amethyst-Android 是在 hook 住的
   * `SDL_InitSubSystem` 里发这条通知，我们没装那个 hook，就在起游戏前主动发一次
   * （Amethyst 的 MainActivity 也是这么干的）。
   *
   * 少了它，游戏侧 SDL 建窗口时拿不到 Surface → 空指针崩，tombstone 全在 libSDL3.so 里。
   */
  fun enableSdlIntegration() {
    runOnUiThread {
      try {
        val ok = CallbackBridge.notifyLauncher(
          CallbackBridge.NOTIF_TYPE_SDL,
          CallbackBridge.ACTION_INIT_LAUNCHER_INTEGRATION,
        )
        Log.i(TAG, "SDL 启动器侧整合：$ok")
      } catch (e: Throwable) {
        Log.e(TAG, "SDL 启动器侧整合失败", e)
      }
    }
  }

  /** 启动时读取 settings.json 里的方向设置并应用，让上次的选择立刻生效。 */
      /**
     * 启动陶瓦联机隧道服务（独立进程 `:tunnel`）。
     *
     * 为什么不直接在这里加载 `libterracotta.so`：Terracotta 是 AGPL-3.0，
     * 但其 README 给了例外——「打包未修改二进制而不链接」或「IPC 交互 + 界面署名」
     * 均不被 AGPL 涵盖。把 .so 放进独立进程、主进程只走 localhost HTTP，
     * 就满足例外条件，启动器可保持 GPL-3.0。详见 TerracottaTunnelService 的注释。
     */
    private fun startTunnelService() {
        try {
            val intent = Intent(this, com.zhayi.qookix.tunnel.TerracottaTunnelService::class.java)
            // 注意：这里必须用 startService 而不是 startForegroundService ——
            // startForegroundService 启动后若 5 秒内不调 startForeground，系统会直接杀进程
            // （实测隧道进程秒死）。开房时由服务自己晋升前台（带通知），那时才需要前台身份。
            startService(intent)
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "隧道服务启动失败", e)
        }
    }

    /**
     * Rust 侧（`terracotta_ping` / `terracotta_request`）探测到隧道连不上时调它，
     * 让隧道**自愈**，而不是让用户「完全退出应用后重试」。
     *
     * 靠的是 `TerracottaTunnelService.onStartCommand` 的「按需重绑」：进程可能还活着
     * （libterracotta 的原生线程吊着），但 HTTP 套接字已经关了；再 startService 一次
     * 就会重新监听并重写端口文件 ✓。
     */
    fun ensureTunnelService() {
      runOnUiThread { startTunnelService() }
    }

    /**
     * 确保已获得「VPN（陶瓦联机）」的系统授权，必要时弹授权对话框。
     *
     * 为什么必须**提前**要：Terracotta 的 VpnService 请求只有 30 秒答复窗口
     * （超时会抛 IllegalStateException 并把 EasyTier 卡住），而系统授权对话框
     * 要用户点一下 —— 塞在那个窗口里太脆。所以开房之前先问一次。
     *
     * 授权一次后长期有效（`VpnService.prepare` 返回 null 即已授权）。
     */
    fun ensureVpnConsent() {
      runOnUiThread {
        val need = runCatching { android.net.VpnService.prepare(this) }.getOrNull()
        if (need == null) {
          android.util.Log.i("MainActivity", "VPN 已授权")
          return@runOnUiThread
        }
        android.util.Log.i("MainActivity", "请求 VPN 授权")
        runCatching { startActivityForResult(need, REQ_VPN_CONSENT) }
      }
    }

    /** 是否已获得 VPN 授权（"1"/"0"）。 */
    fun vpnConsentGranted(): String =
      if (runCatching { android.net.VpnService.prepare(this) == null }.getOrDefault(false)) "1" else "0"

    // ── 电池优化豁免 ──────────────────────────────────────────────────

    /**
     * 系统「电池优化」是否已对本应用放行（"1"/"0"）。
     *
     * 服务器跑在 `:server` 前台服务里，但 ColorOS / OnePlus 的省电策略会在息屏一段时间后
     * 把**不在白名单**的应用的后台服务掐掉 —— 表现就是「开服玩一会儿，服自己没了、
     * 通知也消失了」。`foregroundServiceType=specialUse` 只是绕开了 Android 15 的
     * 6h/24h 硬上限，**挡不住 ROM 自己的省电策略**。
     */
    fun isBatteryUnrestricted(): String {
      val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
          ?: return "0"
      val ok = runCatching { pm.isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
      return if (ok) "1" else "0"
    }

    /**
     * 弹系统的「忽略电池优化」请求页（用户点「允许」后长期生效）。
     *
     * 少数 ROM 没有这个页面（抛 ActivityNotFoundException），退一步打开应用详情页 ——
     * 那里也能手动放行，只是多一步。总比什么都不做、让服务被悄悄掐掉强。
     */
    fun requestIgnoreBatteryOptimizations() {
      runOnUiThread {
        val pkg = "package:$packageName"
        val launched = runCatching {
          startActivity(
            android.content.Intent(
              android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
              android.net.Uri.parse(pkg),
            )
          )
        }.isSuccess
        if (!launched) {
          runCatching {
            startActivity(
              android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse(pkg),
              )
            )
          }
        }
      }
    }

    // ── 设备热状态 ──────────────────────────────────────────────────────

    /**
     * 当前设备热状态（`PowerManager.THERMAL_STATUS_*`，0=正常、1=轻微、2=中等、
     * 3=严重、4=危急、5=紧急、6=关机）。读不到或系统太老都返回 "0"。
     *
     * 手机开服跑久了会被系统降频，表现是「服务器突然变卡」。读它只是为了让界面能说
     * 一句「不是服务器坏了，是手机热了」。竞品 Anvil-MC 用的是
     * `OnThermalStatusChangedListener`（监听回调）；我们按需问一次就够，
     * 不用管回调与界面的生命周期。
     *
     * `getCurrentThermalStatus` 是 API 29 才有的，低版本直接返回 "0"。
     */
    fun thermalStatus(): String {
      if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return "0"
      val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
          ?: return "0"
      return runCatching { pm.currentThermalStatus }.getOrDefault(0).toString()
    }

    // ── 打开服务器文件目录 ──────────────────────────────────────────────

    /**
     * 把服务器文件目录交给系统文件管理器打开。
     *
     * 走 `ServerDocsProvider`（DocumentsProvider）的 `content://` 文档 URI —— 只有它能
     * 让系统文件管理器真正**列出目录内容**（FileProvider 只能给单个文件造 URI，把目录
     * 交给它时系统文件应用只会打开自己的首页，实测看不到我们的目录）。
     *
     * `path` 是绝对路径，这里换算成 provider 的 docId（相对 `files/servers/` 的路径）。
     *
     * 返回："1" 已交给外部应用；"0" 没有能打开文件夹的应用；"-1" 路径不在暴露范围内。
     */
    fun openDirectory(path: String): String {
      val base = java.io.File(filesDir, ServerDocsProvider.DIR_NAME).canonicalFile
      val target = runCatching { java.io.File(path).canonicalFile }.getOrNull() ?: return "-1"
      if (target != base && !target.path.startsWith(base.path + java.io.File.separator)) return "-1"
      if (!target.isDirectory) return "-1"
      val rel = target.toRelativeString(base).trim('/')
      val docId = if (rel.isEmpty() || rel == ".") ServerDocsProvider.ROOT_ID
      else ServerDocsProvider.DOC_PREFIX + rel
      val uri = android.provider.DocumentsContract
        .buildDocumentUri("$packageName.serverdocs", docId)
      return runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
          setDataAndType(uri, "vnd.android.document/directory")
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        "1"
      }.getOrDefault("0")
    }

    // ── 导出实例日志 ────────────────────────────────────────────────────

  /**
   * 把实例的日志目录打包成 zip 并分享。
   *
   * 为什么必须打 zip：日志目录里是 `latest.log` + 一堆 `*.log.gz`，一次分享一个文件
   * 最省事（对方解开就能看）。zip 放 cacheDir，FileProvider 的 `cache-path` 已经映射过了。
   */
  fun shareLogsZip(archiveName: String, srcDir: String) {
    runOnUiThread {
      runCatching {
        val files = java.io.File(srcDir).listFiles()?.filter { it.isFile }
          ?.sortedByDescending { it.lastModified() }
        if (files.isNullOrEmpty()) throw java.io.IOException("没有可导出的日志文件")
        val out = java.io.File(cacheDir, archiveName)
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(out)).use { zos ->
          // 文件名可能重复（同一天多次轮转），前面补序号区分
          files.forEachIndexed { i, f ->
            zos.putNextEntry(java.util.zip.ZipEntry(String.format("%03d_%s", i, f.name)))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
          }
        }
        shareFile(out.absolutePath, "application/zip")
      }.onFailure {
        Log.w(TAG, "导出日志失败", it)
      }
    }
  }

  /**
   * 把一个**已存在的文件**交给系统分享（`ACTION_SEND` + FileProvider）。
   *
   * 这是全局唯一的分享出口：日志（`shareLogsZip`）、控制布局（`exportControlLayout`）、
   * 导出的整合包都走它。以前每处各抄一遍 Intent + FileProvider + 权限 flag，
   * 改一处漏两处（历史上就漏过 `FLAG_GRANT_READ_URI_PERMISSION`）。
   *
   * `path` 必须落在 `res/xml/file_paths.xml` 映射过的目录里，否则 `getUriForFile`
   * 会抛 `IllegalArgumentException` —— 调用方负责把产物写到那些目录
   * （`<files>/exports/`（整合包）、`<files>/controlmap/`、`cacheDir`（日志））。
   */
  fun shareFile(path: String, mimeType: String) {
    runOnUiThread {
      runCatching {
        val f = java.io.File(path)
        if (!f.isFile) throw java.io.IOException("文件不存在：$path")
        val uri = androidx.core.content.FileProvider.getUriForFile(
          this, "$packageName.fileprovider", f
        )
        val send = Intent(Intent.ACTION_SEND)
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.type = mimeType
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
      }.onFailure {
        Log.w(TAG, "分享文件失败：$path", it)
        runCatching {
          android.widget.Toast.makeText(this, it.message ?: "分享失败", android.widget.Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  // ── 控制布局（Pojav 按键布局）：启动器里直接编辑 / 导入 / 导出 ──────────

    /**
     * 打开原生控制布局编辑器（横屏 + 游戏主题，和游戏里那个界面一模一样）。
     *
     * @param layout 要编辑的布局名（`<files>/controlmap/` 下、不带 .json）；空 = 当前默认
     * @param preview 只读预览（导入时先给用户看一眼）
     * @param saveAs 预览确认后另存成的名字（仅预览模式）
     */
    fun openControlEditor(layout: String?, preview: Boolean, saveAs: String?) {
      runOnUiThread {
        runCatching {
          val i = Intent(this, CustomControlsActivity::class.java)
          if (!layout.isNullOrBlank()) i.putExtra("layout", layout)
          i.putExtra("preview", preview)
          if (!saveAs.isNullOrBlank()) i.putExtra("saveAs", saveAs)
          startActivity(i)
        }.onFailure { android.util.Log.w("MainActivity", "打开控制布局编辑器失败", it) }
      }
    }

    /**
     * 导出控制布局：走系统分享（FileProvider 已经映射了 `<files>/controlmap/`，
     * 见 res/xml/file_paths.xml）。
     *
     * 用户要的是「把布局发给别人」，所以分享比「存到某个目录」更顺手；
     * 需要落到具体目录时在分享目标里选文件管理器即可。
     */
    fun exportControlLayout(layout: String) {
      val f = java.io.File(filesDir, "controlmap/$layout.json")
      if (!f.isFile) {
        android.util.Log.w("MainActivity", "导出控制布局失败：布局不存在 $layout")
        return
      }
      shareFile(f.absolutePath, "application/json")
    }

    /**
     * 导入控制布局：SAF 选一个 json → 校验 → 写成 `<controlmap>/IMPORT_TMP.json`。
     *
     * 校验沿用 `ImportControlActivity` 的标准（必须有 `version` 与 `mControlDataList`）——
     * 不校验的话，坏文件会进到游戏里才炸。返回统计 JSON 字符串给前端弹预览用。
     */
    fun importControlLayout(): String {
      // **为什么用 GET_CONTENT 而不是 OPEN_DOCUMENT**：
      // 系统选择器（DocumentsUI）只认 OPEN_DOCUMENT，而 MT 管理器这类第三方文件管理器
      // 注册的是老式的 `ACTION_GET_CONTENT + OPENABLE` —— 实测
      // `cmd package query-activities -a android.intent.action.GET_CONTENT -t application/json`
      // 里**有** `bin.mt.plus`，但 `OPEN_DOCUMENT` 那条**没有**它。
      // 也就是说：发 OPEN_DOCUMENT → 用户永远看不到 MT；发 GET_CONTENT 就能把它列出来。
      // 再套一层 `createChooser` → 弹出的就是「选择打开方式」那样一个应用列表。
      val i = Intent(Intent.ACTION_GET_CONTENT)
      i.addCategory(Intent.CATEGORY_OPENABLE)
      i.type = "application/json"
      // 传 null 而不是省略 EXTRA_STREAM：`createChooser` 遇到部分应用会往 intent 里塞
      // 一个默认的 clipData，导致目标应用以为用户已经选好了文件。给 null 明确表示「还没选」。
      i.putExtra(Intent.EXTRA_STREAM, null as Parcelable?)
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      reqPickControl = true
      val picked = Intent.createChooser(i, getString(R.string.import_control_label))
      try {
        startActivityForResult(picked, reqPickFile)
      } catch (t: Throwable) {
        // 极端情况下没有应用能处理（不会有，但别崩）
        reqPickControl = false
        android.widget.Toast.makeText(this, "没有可用的文件选择器", android.widget.Toast.LENGTH_SHORT).show()
      }
      return ""
    }

    /**
     * 清掉导入布局用的中转文件（`TMP_IMPORT*.json`）。
     *
     * 这些是 [acceptLayoutText] / `ImportControlActivity` 写出来的**临时**数据，导入流程
     * 结束后没有任何地方删除：App 的布局列表按 `startsWith("TMP_IMPORT")` 把它们过滤掉
     * （用户看不到 → 也就没有删除入口），而原生编辑器那个文件对话框会全部列出来，
     * 于是出现「一堆删不掉的怪文件」。
     *
     * ⚠️ 只能在**启动器**路径调用：`CustomControlsActivity`（预览导入结果时）也会调
     * `ControlLayoutSeed.ensure`，在那边扫会把预览正要读的那个文件删掉。
     */
    private fun sweepImportTempFiles() {
      runCatching {
        java.io.File(filesDir, "controlmap").listFiles()?.forEach { f ->
          if (f.isFile && f.name.startsWith(TMP_IMPORT_NAME) && f.name.endsWith(".json")) {
            if (f.delete()) Log.i(TAG, "清理导入中转文件 ${f.name}")
          }
        }
      }
    }

    /** 看门狗用的日志标签（不用类里那个 TAG：它在别的作用域里） */
    private val UI_WATCH_TAG = "QookiX-UI"

    // ── WebView 看门狗 ─────────────────────────────────────────────────────
    //
    // 真机反复遇到「界面帧不动、点什么都没反应，但进程活着、系统也不报 ANR」：
    // `ps` 里 WebView 的渲染进程已经没了（主线程没卡所以没有 ANR），用户只能强杀重开。
    // 这里每 15 秒问 WebView 一句；5 秒都没回调就判定它挂了 → 记日志 + 自动重建界面
    // （等价于用户手动强杀重开，只是自动的，而且会留下日志供排查）。
    private var uiWatchdog: android.os.Handler? = null
    private var uiQuestioned = true

    override fun onWebViewCreate(webView: android.webkit.WebView) {
      super.onWebViewCreate(webView)
      installUiWatchdog(webView)
    }

    private fun installUiWatchdog(webView: android.webkit.WebView) {
      if (uiWatchdog != null) return
      val handler = android.os.Handler(android.os.Looper.getMainLooper())
      uiWatchdog = handler
      val tick = object : Runnable {
        override fun run() {
          uiQuestioned = false
          runCatching { webView.evaluateJavascript("1") { uiQuestioned = true } }
            .onFailure { uiQuestioned = false }
          handler.postDelayed({
            if (uiQuestioned || isFinishing || isDestroyed) {
              handler.postDelayed(this, 15_000L)
            } else {
              Log.w(UI_WATCH_TAG, "WebView 无响应（渲染进程可能已退出），自动重建界面")
              runCatching { recreate() }.onFailure {
                Log.e(UI_WATCH_TAG, "重建界面失败：${it.message}")
              }
            }
          }, 5_000L)
        }
      }
      handler.postDelayed(tick, 15_000L)
    }

    /** 与 [readPickedControlLayout] 共用的校验 + 落盘；返回统计 JSON。 */
    private fun acceptLayoutText(text: String): String {
      return runCatching {
        val j = org.json.JSONObject(text)
        if (!j.has("version") || !j.has("mControlDataList")) {
          throw java.io.IOException("这不是有效的控制布局文件")
        }
        val tmp = java.io.File(filesDir, "controlmap/$TMP_IMPORT_NAME.json")
        tmp.parentFile?.mkdirs()
        // 先清掉上一次的残留（含历史遗留的「副本」变体），否则每导入一次就多一个文件
        tmp.parentFile?.listFiles()?.forEach { f ->
          if (
            f.isFile &&
            f.name.startsWith(TMP_IMPORT_NAME) &&
            f.name.endsWith(".json") &&
            f.absolutePath != tmp.absolutePath
          ) {
            f.delete()
          }
        }
        tmp.writeText(text)
          org.json.JSONObject()
            .put("ok", true)
            .put("name", TMP_IMPORT_NAME)
            .put("buttons", j.optJSONArray("mControlDataList")?.length() ?: 0)
            .put("joysticks", j.optJSONArray("mJoystickDataList")?.length() ?: 0)
            .put("drawers", j.optJSONArray("mDrawerDataList")?.length() ?: 0)
            .toString()
        }.getOrElse {
          org.json.JSONObject().put("ok", false).put("error", it.message ?: "导入失败").toString()
        }
      }

      /** 读取用户在 SAF 里选的布局文件；返回 `{ok, buttons, joysticks, drawers, name}`。 */
      private fun readPickedControlLayout(uri: android.net.Uri): String {
        return runCatching {
          val text = contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(java.nio.charset.StandardCharsets.UTF_8)
          } ?: throw java.io.IOException("读不到所选文件")
          acceptLayoutText(text)
        }.getOrElse {
          org.json.JSONObject().put("ok", false).put("error", it.message ?: "导入失败").toString()
        }
      }

      /**
       * 直接从**文件路径**导入（不经SAF）。
       *
       * 为什么需要这条：SAF（系统选择器）打不开 `Android/data` 等目录，而 MT 管理器有 root 权限能拿到
       * 那些文件的绝对路径 —— 用户在 MT 里复制路径粘过来，我们凭「所有文件访问」直接读就行。
       * 技术上**没法**让 MT 代替我们弹选择器（MT 没这个接口，系统只认 SAF）。
       */
      fun importControlLayoutFromPath(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) {
          return org.json.JSONObject().put("ok", false).put("error", "路径为空").toString()
        }
        return runCatching {
          val f = java.io.File(p)
          if (!f.isFile) throw java.io.IOException("找不到这个文件")
          if (f.length() > 8 * 1024 * 1024) throw java.io.IOException("文件太大了（超过 8MB）")
          acceptLayoutText(f.readText(Charsets.UTF_8))
        }.getOrElse {
          org.json.JSONObject().put("ok", false).put("error", it.message ?: "导入失败").toString()
        }
      }

    private fun applyStoredOrientation() {
    try {
      val file = File(filesDir, "settings.json")
      if (!file.isFile) return
      val mode = JSONObject(file.readText()).optString("orientation", "landscape")
      setOrientation(mode)
    } catch (e: Exception) {
      Log.w(TAG, "读取屏幕方向设置失败", e)
    }
  }

  // ==================== 游戏目录（内部 / 应用专属外部 / 自定义） ====================

  private val reqPickGameDir = 0x51A7

  /** 「导入控制布局」用的 SAF 请求码（与选游戏目录那个区分开）。 */
  private val reqPickFile = 0x51A8

  /** 本次 SAF 是为了导入控制布局（onActivityResult 里据此分支）。 */
  private var reqPickControl = false

  /** 导入用的临时布局文件名（`TMP_IMPORT` 前缀会被布局列表跳过，见 controls.rs）。 */
  private val TMP_IMPORT_NAME = "TMP_IMPORT_FILE"

  /** 「游戏目录」可选项（JSON 数组），见 [StorageDirs]。 */
  fun gameDirOptions(): String = runCatching { StorageDirs.optionsJson(this) }.getOrDefault("[]")

  /** 「所有文件访问」是否已授权（"1"/"0"）。 */
  fun hasAllFilesAccess(): String = if (StorageDirs.allFilesAccessGranted()) "1" else "0"

  /** 跳到系统的「所有文件访问」设置页（Android 11+ 才需要）。 */
  fun requestAllFilesAccess() {
    runOnUiThread {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@runOnUiThread
      val direct = Intent(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:$packageName")
      )
      if (runCatching { startActivity(direct) }.isFailure) {
        // 少数 ROM 只认总设置页
        runCatching {
          startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
      }
    }
  }

  /**
   * 弹出系统的目录选择器（SAF）。
   *
   * **它是异步的** —— 这里只发起请求，结果在 [onActivityResult] 里处理，解析出的
   * 真实路径写进 `<files>/game-dir-pick.json`，前端轮询 Rust 命令取回。
   * 不把 URI 直接交给 Rust / 游戏的原因：两者都只认真实路径，`content://` 用不了。
   */
  fun pickGameDir() {
    runOnUiThread {
      val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
      }
      if (runCatching { startActivityForResult(intent, reqPickGameDir) }.isFailure) {
        writeGameDirPick("", "无法打开系统目录选择器")
      }
    }
  }

  /** SAF 的 tree URI → 真实路径。映射是**约定俗成**（非官方）：`primary:` 是内置存储，其余是卷 ID。 */
  private fun treeUriToRealPath(treeUri: Uri): String? {
    val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri) ?: return null
    val sep = docId.indexOf(':')
    if (sep < 0) return null
    val volumeId = docId.substring(0, sep)
    val rel = docId.substring(sep + 1).trim('/')
    val root = if (volumeId.equals("primary", ignoreCase = true)) {
      Environment.getExternalStorageDirectory()
    } else {
      File("/storage/$volumeId")
    }
    return if (rel.isEmpty()) root.absolutePath else File(root, rel).absolutePath
  }

  private fun writeGameDirPick(path: String, error: String) {
    runCatching {
      File(filesDir, "game-dir-pick.json")
        .writeText(JSONObject().put("path", path).put("error", error).toString())
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    // 「VPN 授权」对话框的结果（陶瓦联机开房前会弹，见 ensureVpnConsent）
    if (requestCode == REQ_VPN_CONSENT) {
      android.util.Log.i(
        "MainActivity",
        "VPN 授权结果: " + if (resultCode == RESULT_OK) "已允许" else "已拒绝"
      )
      return
    }
    if (requestCode != reqPickGameDir) {
      // 控制布局导入（SAF 选单个 json）→ 校验后写 IMPORT_TMP.json，前端轮询取结果
      if (requestCode == reqPickFile && reqPickControl) {
        reqPickControl = false
        val json = if (resultCode == RESULT_OK && data?.data != null) {
          readPickedControlLayout(data.data!!)
        } else {
          org.json.JSONObject().put("ok", false).put("error", "已取消").toString()
        }
        runCatching { java.io.File(filesDir, "control-import.json").writeText(json) }
      }
      return
    }
    val treeUri = data?.data
    if (resultCode != RESULT_OK || treeUri == null) {
      writeGameDirPick("", "")   // 用户取消
      return
    }
    runCatching {
      contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      )
    }
    val real = treeUriToRealPath(treeUri)
    if (real == null) {
      writeGameDirPick("", "无法把所选目录转换成文件路径")
      return
    }
    if (!File(real).canWrite()) {
      // Android 11+ 未授予「所有文件访问」时，公共目录写不进去
      // （SAF 授权只对 ContentResolver 生效，原始路径仍被 scoped storage 拦）。
      writeGameDirPick("", "该目录无法写入，请先授予「所有文件访问」权限")
      return
    }
    writeGameDirPick(real, "")
  }

  /** 锁定 / 跟随屏幕方向。可在任意线程调用。 */
  fun setOrientation(mode: String) {
    val requested = when (mode) {
      "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
      "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
      // 跟随系统用 USER：清单里写的是 sensorLandscape，
      // UNSPECIFIED 会继续沿用清单值而无法真正“跟随系统”。
      else -> ActivityInfo.SCREEN_ORIENTATION_USER
    }
    runOnUiThread {
      if (requestedOrientation != requested) {
        requestedOrientation = requested
      }
    }
  }

  /**
   * 把文件选择器返回的 `content://` URI 复制到应用缓存目录并返回真实路径。
   * Rust 侧的所有文件命令都只认普通路径，无法直接读取 content URI。
   */
  fun resolvePickedUri(uriOrPath: String): String? {
    if (!uriOrPath.startsWith("content://")) {
      return uriOrPath
    }
    return try {
      val uri = Uri.parse(uriOrPath)

      // 文件名完全由外部 provider 提供（DISPLAY_NAME），**必须净化**：
      // 形如 "../../files/settings.json" 的会被 `File(dir, name)` 规约到应用私有
      // 目录并覆盖 settings.json（里面存着账号 token / 代理设置）—— 路径穿越漏洞。
      val raw = queryDisplayName(uri) ?: "picked"
      val safeName = sanitizeFileName(raw)
      if (safeName.isEmpty()) {
        Log.e(TAG, "拒绝空文件名: $raw")
        return null
      }

      // 落在 <files>/picked/ 而不是 cacheDir：cache 会被系统回收，导入后引用成死链。
      val dir = File(filesDir, "picked").apply { mkdirs() }
      val dest = File(dir, "${System.currentTimeMillis()}_$safeName")

      // 二次校验：规约后的路径必须仍在目标目录内
      val canonicalDir = dir.canonicalPath + File.separator
      if (!dest.canonicalPath.startsWith(canonicalDir)) {
        Log.e(TAG, "拒绝可疑文件名（路径穿越）: $raw")
        return null
      }

      contentResolver.openInputStream(uri).use { input ->
        if (input == null) return null
        dest.outputStream().use { output -> input.copyTo(output) }
      }
      dest.absolutePath
    } catch (e: Exception) {
      Log.e(TAG, "解析所选文件失败: $uriOrPath", e)
      null
    }
  }

  /**
   * 读取系统剪贴板文本。
   *
   * 为什么必须走原生：代码编辑器的「粘贴」在 WebView 里两条路都是死的 ——
   *   ① `navigator.clipboard.readText()` 需要 `clipboard-read` 权限，wry 没有授予 → 抛错；
   *   ② `document.execCommand("paste")` 在 Chromium 里按设计**永远返回 false**。
   * 于是点了「粘贴」既不报错也不插入内容。这里用 ClipboardManager 直接读。
   *
   * 返回 null 表示剪贴板为空或读取失败 —— 调用方应据此提示用户，
   * 而不是像以前那样静默什么都不发生。
   */
  fun readClipboardText(): String? {
    return try {
      val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
      if (cm == null || !cm.hasPrimaryClip()) return null
      val clip = cm.primaryClip ?: return null
      if (clip.itemCount == 0) return null
      clip.getItemAt(0).coerceToText(this)?.toString()
    } catch (e: Throwable) {
      // Android 10+ 只有前台应用能读剪贴板；极少数机型/时机下仍会抛。
      Log.w(TAG, "读取剪贴板失败", e)
      null
    }
  }

  /**
   * 写入系统剪贴板。与 [readClipboardText] 对称。
   *
   * 实测 WebView 连 `clipboard-write` 权限也没授予：
   * `navigator.clipboard.writeText()` 抛 `NotAllowedError: Write permission denied`。
   * 所以编辑器的「复制」以前只能靠 `execCommand("copy")` 兜底 —— 那条路依赖用户手势，
   * 从工具栏点击时勉强能成，但不该把唯一入口押在兜底上。
   *
   * 返回约定：成功返回**空串**，失败返回错误信息（同 `writeTextToUri`）。
   */
  fun writeClipboardText(text: String): String {
    return try {
      val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return "系统剪贴板不可用"
      cm.setPrimaryClip(ClipData.newPlainText("QookiX", text))
      ""
    } catch (e: Throwable) {
      Log.w(TAG, "写入剪贴板失败", e)
      e.message ?: e.toString()
    }
  }

  /**
   * 把文本写回「另存为」选中的目标。
   *
   * 为什么必须走原生：安卓的文件选择器（`ACTION_CREATE_DOCUMENT`）返回的是 SAF 的
   * `content://` URI，不是文件路径 —— Rust 侧对它做 `fs::write` 必然失败，
   * 表现就是「导出日志/配置点了没反应或直接报错」。写入只能由 ContentResolver 完成。
   *
   * 返回约定：成功返回**空串**；失败返回错误信息（给用户看，所以不带堆栈）。
   */
  fun writeTextToUri(uriOrPath: String, content: String): String {
    return try {
      if (!uriOrPath.startsWith("content://")) {
        val f = File(uriOrPath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return ""
      }
      val uri = Uri.parse(uriOrPath)
      // 模式必须是 "wt"（write + truncate）：只写 "w" 时部分 provider 会在原内容
      // 后面追加，导出的日志会变成「旧内容 + 新内容」两份。
      val out = contentResolver.openOutputStream(uri, "wt")
        ?: return "无法打开目标文件（系统未授予写权限？）"
      out.use { os ->
        os.write(content.toByteArray(Charsets.UTF_8))
        os.flush()
      }
      ""
    } catch (e: Throwable) {
      Log.w(TAG, "写入所选位置失败: $uriOrPath", e)
      e.message ?: e.toString()
    }
  }

  /** 只保留纯文件名：去掉任何目录部分，非法字符替换为 `_`。 */
  private fun sanitizeFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    return base
      .map { ch -> if (ch.isLetterOrDigit() || ch in "._- ()[]（）") ch else '_' }
      .joinToString("")
      .take(120)
  }

  private fun queryDisplayName(uri: Uri): String? {
    return try {
      contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (!cursor.moveToFirst()) return@use null
          val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (idx >= 0) cursor.getString(idx) else null
        }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * 返回当前系统（含 VPN / Wi-Fi）下发的 HTTP 代理，形如 `http://127.0.0.1:7890`。
   *
   * 原生 socket 不会自动走系统代理，只有把这里拿到的地址显式交给 HTTP 客户端，
   * 设置里的「系统代理」才和 WebView 的联网行为一致（Clash / v2ray 等 TUN 环境下尤其关键）。
   */
  fun systemProxy(): String? {
    // VPN（Clash / v2ray 的 TUN 模式）已经接管整机流量，再叠加系统 HTTP 代理
    // 就是**双重代理**：实测表现为「网络抽风」——版本清单超时、下载半途失败，
    // 而且时好时坏极难排查（用户设备上就是这个状态）。
    // VPN 在跑就直接返回 null，让请求走 TUN。
    if (isVpnActive()) {
      Log.i(TAG, "检测到 VPN 生效，忽略系统 HTTP 代理（避免双重代理）")
      return null
    }
    try {
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
    } catch (e: Exception) {
      Log.w(TAG, "读取系统代理失败", e)
    }
    // 兜底：框架会依据默认网络为 java.net 设置这两个属性
    val host = System.getProperty("http.proxyHost")
    if (!host.isNullOrEmpty()) {
      val port = System.getProperty("http.proxyPort")?.toIntOrNull() ?: 80
      return "http://$host:$port"
    }
    return null
  }

  /** 当前是否有 VPN 生效（含 Clash 的 TUN 模式）。 */
  private fun isVpnActive(): Boolean {
    return try {
      val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
      var found = false
      cm.activeNetwork?.let { n ->
        if (cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
          found = true
        }
      }
      if (!found) {
        // VPN 有时不是 activeNetwork，扫一遍全部网络兜底
        found = cm.allNetworks.any { n ->
          cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
      }
      found
    } catch (e: Throwable) {
      Log.w(TAG, "检测 VPN 状态失败", e)
      false
    }
  }

  private companion object {
    const val TAG = "MainActivity"

    /** Pojav 控制层读的那份 SharedPreferences 文件名（LauncherPreferences 里定的）。 */
    const val PREFS_POJAV = "launcher_preferences"

    /** 「VPN 授权」对话框的请求码（见 ensureVpnConsent）。 */
    const val REQ_VPN_CONSENT = 4201
  }
    /**
     * WiFi 网卡的 IPv4 地址（服务器联机地址用）。
     *
     * 必须在 Kotlin 侧取：Rust 从原生线程 `FindClass("java/net/NetworkInterface")`
     * 会抛 Java 异常（系统类加载器上下文看不到），所以走 Activity 实例方法。
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
        } catch (e: Exception) {
            null
        }
    }
}
