package com.zhayi.qookix.compose

import com.zhayi.qookix.nativebridge.PojavShim
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.zhayi.qookix.HostCapabilities
import com.zhayi.qookix.PendingLaunch
import com.zhayi.qookix.R
import com.zhayi.qookix.ServerDocsProvider
// ⚠️ 阶段 4（2026-10-25）：**不再继承 TauriActivity**。
//
// TauriActivity 的意义只有一个：创建一个隐藏 WebView 当 IPC 通道。
// 现在 154 条命令 + 全部事件都走 JNI 直连（`TauriBridge.nativeInvoke` /
// `onCoreEvent`），WebView 已无任何业务用途 ✗ —— 留着它反而带来
// 「渲染进程被杀 → 界面冻住 / 点不动」那一整类问题。
//
// 换成普通的 ComponentActivity：Compose 自己就是全部 UI，
// 不创建 WebView，也就没有"网页没加载好"这个失败模式。
import androidx.activity.ComponentActivity
import com.zhayi.qookix.GameActivity
import com.zhayi.qookix.compose.bridge.*
import com.zhayi.qookix.compose.pages.FirstRunSetupHost
import com.zhayi.qookix.compose.pages.FirstRunUiState
import com.zhayi.qookix.compose.shell.LauncherShell
import com.zhayi.qookix.compose.shell.NavDestination
import com.zhayi.qookix.compose.theme.QookiXTheme
import com.zhayi.qookix.tauri.TauriBridge
import android.content.Intent
import net.kdt.pojavlaunch.CustomControlsActivity

/**
 * 启动器主入口（LAUNCHER）。
 */
class ComposeMainActivity : ComponentActivity() {


    companion object {
        private const val TAG = "ComposeMain"
    }

    /**
     * 游戏退出后自动重启进程。
     *
     * ## 为什么必须有（2026-10-23 真机事故）
     *
     * 同一个应用进程里**只能启动一次游戏** ✗：
     *  - `JNI_CreateJavaVM` 一个进程只能成功一次；
     *  - 更直接的是原生库：第二次启动时 LWJGL 会报
     *    `UnsatisfiedLinkError: Native Library …/natives/liblwjgl.so
     *     already loaded in another classloader`
     *    （新 JVM 的 classloader 想再 dlopen 同一个 .so，被系统拒绝 ✓）。
     *
     * 表现就是：**第一次能进游戏 ✓，退出后再点启动 → 渲染线程初始化即崩 ✗**。
     * 所以游戏一退出就重启进程（先拉起同一个 Activity，再 exit）✓。
     */
    private var exitWatcher: com.zhayi.qookix.compose.bridge.RustBridge? = null

    private fun watchGameExitAndRestart() {
        runCatching {
            val b = com.zhayi.qookix.compose.bridge.RustBridge(this)
            exitWatcher = b // 持有引用，避免被回收导致订阅丢失
            b.listen("launch://exit") { restartForNextLaunch() }
        }
    }

    private fun restartForNextLaunch() {
        Log.i(TAG, "游戏已退出 → 重启应用进程（JVM / 原生库只能在一个进程里用一次）")
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let { i ->
                i.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
                startActivity(i)
            }
        }.onFailure { Log.w(TAG, "拉起自身失败", it) }
        // 给新界面一点启动时间再退出旧进程
        android.os.Handler(mainLooper).postDelayed({ Runtime.getRuntime().exit(0) }, 800)
    }

    private var composeOverlayAttached = false
    private var composeView: android.view.View? = null
    private val caps by lazy { HostCapabilities(this) }

    /**
     * Rust 侧通过 `android_bridge` 反射调用（单参数 JSON，返回 "ok" / "busy" / "error:…"）。
     *
     * 为什么走 Activity 方法：Rust 拿不到 Context，但 `android_bridge` 一直握着本 Activity
     * （`call_activity_str`），这是既有通道，不用新造一套。
     */
    fun startJvmInstallService(json: String): String {
        return try {
            val o = org.json.JSONObject(json)
            val args = o.optJSONArray("jvmArgs")
            val gargs = o.optJSONArray("gameArgs")
            fun list(a: org.json.JSONArray?): List<String> =
                if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
            val ok = com.zhayi.qookix.install.JvmInstallService.start(
                context = this,
                jreHome = o.optString("jre"),
                classpath = o.optString("classpath"),
                mainClass = o.optString("mainClass"),
                jvmArgs = list(args),
                gameArgs = list(gargs),
                workDir = o.optString("workDir"),
                port = o.optInt("port", 0),
            )
            if (ok) "ok" else "busy"
        } catch (t: Throwable) {
            Log.w(TAG, "启动 JVM 安装服务失败", t)
            "error:${t.message}"
        }
    }

    /**
     * Rust 侧事件经 **JNI 直连**进来（阶段 2，2026-10-24）。
     *
     * `progress::push()` 优先调这里（`android_bridge::push_event_to_kotlin`，
     * 方法名/签名必须与那侧一致）；JNI 不可用才回落到 Tauri 的 WebView 通道。
     * 事件进 `RustBridge` 的静态订阅表，再由它切主线程分发。
     */
    fun onCoreEvent(event: String, payload: String) {
        com.zhayi.qookix.compose.bridge.RustBridge.deliverEvent(event, payload)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TauriBridge.attach(this)

        // 游戏退出后重启进程（否则第二次启动必崩，见 watchGameExitAndRestart 的注释）
        watchGameExitAndRestart()

        // ── 阶段 1 自检：确认「不经 WebView 的 JNI 命令通道」可用 ──────────
        // 只在调试期留一行日志；通道验证通过后，会把各页面逐个切到这条路上。
        Thread {
            runCatching {
                val r = TauriBridge.nativeInvoke("list_instances", "{}")
                Log.i(TAG, "[Core] nativeInvoke(list_instances) → ${r.take(200)}")
                val s = TauriBridge.nativeInvoke("get_settings", "{}")
                Log.i(TAG, "[Core] nativeInvoke(get_settings) → ${s.take(160)}")
            }.onFailure { Log.w(TAG, "[Core] nativeInvoke 失败", it) }
        }.start()

        // ⚠️ 2026-10-23（真机反馈「崩过一次后再点启动游戏没反应」）：
        // `game_running.marker` 是「游戏正在运行」的标记，正常退出时由
        // GameGuardService 清掉。但游戏是**同进程 JNI** 启动的 —— JVM 崩溃时
        // 收尾代码根本没机会跑，标记就留在盘上，于是启动器一直以为游戏还在跑，
        // 「启动游戏」被静默拦掉（用户看到的就是"点了没反应"）。
        //
        // 不变量：本进程是**新建的** ⇒ 不可能有游戏在跑（JVM 就在本进程里）。
        // 所以冷启动时无条件清掉它；真有残留的运行中状态，Rust 侧的
        // `get_game_status().is_running`（内存态）才是判据。
        runCatching {
            val marker = com.zhayi.qookix.services.GameGuardService.markerFile(this)
            if (marker.exists() && marker.delete()) {
                Log.i(TAG, "清理上次异常退出残留的游戏运行标记")
            }
        }.onFailure { Log.w(TAG, "清理运行标记失败", it) }

        // ⚠️⚠️ 必须在这里初始化 Pojav 的平台上下文（2026-10-16 修，游戏启动崩溃的根因）。
        //
        // ── 崩溃现象 ───────────────────────────────────────────────────
        // 点「启动游戏」→ GL 初始化阶段 SIGSEGV，tombstone：
        //     #07 libandroid.so (AHardwareBuffer_getNativeHandle+24)
        //     #08 libpojavexec.so
        //     fault addr 0x28（NULL + 偏移）
        //
        // ── 因果链（每一步都用真机日志证实过）───────────────────────────
        //   ① 桌面入口从 `MainActivity` 改成了本类（2026-10-13）
        //   ② 但 `PojavShim.init(this)` **只在 MainActivity 里调用**，没跟着搬
        //   ③ → `PojavShim.appContext == null`
        //   ④ → `nativeLibDir()` 返回 null → 找不到 `libpojavexec.so`
        //   ⑤ → `ensurePojavLoaded()` 报
        //        「libpojavexec 缺失：natives 未抽取成功，游戏无法启动」
        //   ⑥ → `setupBridgeWindow()` **静默 return**
        //        （它开头是 `if (!ensurePojavLoaded()) return`）
        //   ⑦ → 原生侧 `pojav_environ->pojavWindow` 保持 **NULL**
        //   ⑧ → `egl_bridge.c:218` 的 `ANativeWindow_setBuffersGeometry(NULL, …)`
        //        在 `pojavInit()` 里**没有任何空指针保护** → 解引用 → SIGSEGV
        //
        // 这是 Vue→Compose 迁移时漏掉的一步：入口 Activity 换了，
        // 但它承担的**平台初始化职责**没有一起搬过来。
        //
        // 注：`TauriBridge.attach` 管的是 Rust 回调，`PojavShim.init` 管的是
        // Pojav 输入桥（`CallbackBridge` 的剪贴板 / native 库目录），两者都要有。
        PojavShim.init(this)
        // ⚠️⚠️ 启动时必须应用已保存的「界面方向」（2026-10-17 修，真机实测）。
        //
        // 缺陷：`setOrientation` 只有定义、**启动路径里从来没调用过** ——
        // 只有用户在设置里点一下才生效，重启后又回到清单文件里的横屏锁定。
        // 实测：settings.json 写的是 `"orientation":"system"`（跟随系统），
        // 但 dumpsys 显示 `requestedOrientation=SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，
        // 用户切竖屏后重启又变横屏，看起来像"设置没保存"。
        //
        // MainActivity 里有 `applyStoredOrientation()`（onCreate 调用），
        // 但实际运行的是 ComposeMainActivity，它没搬这个调用。
        applyStoredOrientation()
        // ⚠️⚠️ 加载 Pojav 偏好到 LauncherPreferences 静态字段（2026-10-25 修）。
        //
        // 读端（HostCapabilities.readPojavPrefs → get_pojav_prefs，以及 launch.rs
        // 启动时选渲染器）读的全是 `LauncherPreferences` 的**静态字段**，而原来
        // 只有 GameActivity / CustomControlsActivity 的 onCreate 会从
        // SharedPreferences 加载它们 —— 启动器进程里永远是默认值。后果：
        //   · 设置页「当前渲染器」永远显示 GL4ES（默认值），改了重启又变回去；
        //   · launch.rs 读到的渲染器也永远是默认 —— 全局渲染器设置从未生效过。
        // 这里与 GameActivity.onCreate:367 对齐：指向同一份偏好文件并加载。
        LauncherPreferences.DEFAULT_PREF =
            getSharedPreferences("launcher_preferences", MODE_PRIVATE)
        LauncherPreferences.loadPreferences(this)
        // ── 以下三项在 MainActivity 里就有，迁移时漏了（2026-10-17 系统核对补回）──
        //
        // 1) 通知权限：Android 13+ 不申请就没有通知栏权限 →
        //    「游戏正在运行」的常驻通知、下载进度、服务器状态全都看不到。
        requestNotificationPermission()
        // 2) 清理上次残留的游戏任务：异常退出后系统可能留下一个空的 GameActivity 任务，
        //    下一次启动会先显示空白页（用户以为卡死了）。
        removeStaleGameTask()
        // 3) 预热陶瓦联机隧道服务：提前把 Service 拉起来，
        //    用户点「开启联机」时不用再等冷启动（否则要等好几秒才出房间码）。
        runCatching { ensureTunnelService() }
        PendingLaunch.consume(this)?.let { (instanceId, accountUuid) ->
            Log.i(TAG, "消费待启动记录: $instanceId")
            startGameActivity(instanceId, accountUuid)
        }
        // ⚠️ 2026-10-18 加：整个 App 隐藏状态栏（沉浸式）。
        //
        // 起因：用户反馈「内容中心点开筛选后，系统状态栏会弹出来，
        // 导致它下面所有元素都动一下」。
        //
        // 根因是 `ModalBottomSheet` 在独立 Dialog 窗口里，该窗口默认请求
        // 系统栏 inset —— 于是状态栏被顶出来、内容区高度重算、布局跳动。
        // 当前 Material3 版本的 ModalBottomSheet 没有 `windowInsets` 参数可关。
        //
        // 用户选了「整个 App 都不要状态栏」的方案：状态栏本来就不显示，
        // 弹窗时自然不会「弹出来」，跳动问题从根上消失。
        //
        // 注意：`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 让用户从顶部边缘
        // 下滑时仍能临时看到状态栏（看时间/电量），松手自动隐藏 ——
        // 完全不给看会很别扭。
        enableImmersiveMode()
        window?.decorView?.post { attachComposeOverlay(0) }
        Log.i(TAG, "onCreate 完成")
        // 3 秒后把视图树打出来（诊断用）
        window?.decorView?.postDelayed({ dumpTree() }, 3000)
        window?.decorView?.postDelayed({ dumpTree() }, 8000)
    }

    /** 诊断：把 decorView 的完整视图树打印出来。 */
    private fun dumpTree() {
        val root = window?.decorView ?: return
        val sb = StringBuilder("View tree:\n")
        fun walk(v: View, depth: Int) {
            val pad = "  ".repeat(depth)
            val vis = when (v.visibility) {
                View.VISIBLE -> "V"; View.INVISIBLE -> "I"; else -> "G"
            }
            sb.append("$pad${v.javaClass.simpleName} vis=$vis ${v.width}x${v.height} alpha=${v.alpha}\n")
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        Log.i(TAG, sb.toString())
    }

    /**
     * 隐藏状态栏（沉浸式）。
     *
     * 保留 `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`：用户从屏幕顶部边缘
     * 下滑时状态栏会临时浮出来（看时间/电量/通知），几秒后自动隐藏。
     * 完全屏蔽掉会很别扭 —— 用户连现在几点都看不到。
     *
     * 导航栏（底部手势条）**不隐藏**：那是系统手势区，藏了会影响返回手势。
     */
    private fun enableImmersiveMode() {
        runCatching {
            val w = window ?: return
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            val controller = androidx.core.view.WindowInsetsControllerCompat(
                w,
                w.decorView,
            )
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 只藏状态栏（顶部），导航栏保留
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            Log.i(TAG, "沉浸式：状态栏已隐藏")
        }.onFailure { Log.w(TAG, "设置沉浸式失败", it) }
    }

    /**
     * ⚠️ 关键：让 Rust 创建的 WebView **不可见但仍可用**。
     *
     * Tauri/wry 启动时会创建一个 WebView，它承载**全部 IPC**：
     * Rust 的每个 `invoke` 都是通过往这个 WebView 注入脚本、
     * 再读回调实现的。而它是**加在 content view 之上**的 ——
     * 不管它，Compose 界面就被完全盖住（实机验证 2026-10-14：
     * dump 里 2 个 WebView 节点，屏幕全是旧 Vue 界面）。
     *
     * ⚠️⚠️ 但**绝不能用 `visibility = GONE`** ——
     * GONE 的 View 不参与渲染，WebView **不会执行 JavaScript**，
     * 于是注入脚本跑不了、桥永远不就绪：所有命令都报「桥尚未就绪」。
     *（实测踩到：皮肤页「获取皮肤」失败、实例列表拉不到。）
     *
     * 正解：**alpha = 0f**。它仍然参与渲染（JS 照常跑），
     * 只是完全透明看不见 —— Compose 在它上层，用户只看得到 Compose。
     */
    // ⚠️ 阶段 4（2026-10-25）：`onWebViewCreate` 已删除。
    // 不再创建 WebView，所以"把 WebView 设透明 + 装渲染进程看门狗"这套
    // 兜底逻辑一起退场 —— 没有网页，就没有"网页挂了界面冻住"这件事。

    private fun attachComposeOverlay(attempt: Int) {
        if (composeOverlayAttached) return
        runOnUiThread {
            try {
                // ⚠️ 必须把 ComposeView 加到 **wry 放 WebView 的那个容器**里。
                //
                // 踩过的两个坑（实机验证 2026-10-14）：
                //   1. `setContent()` —— wry 稍后自己 setContentView，把 Compose 顶掉；
                //   2. `addContentView()` —— 加的是 Activity 的 content view，
                //      同样被覆盖（视图树里根本找不到 ComposeView）。
                //
                // 正解：遍历 decorView 找到装了 RustWebView 的容器，
                // 直接把 ComposeView addView 进去 —— 与 WebView 同层，
                // 我们后加所以必然在上层。
                // 阶段 4（2026-10-25）：工程里已经没有 wry 容器了 —— 直接 setContentView。
                //
                // 旧写法会先「找容器 → 找不到就每 100ms 重试，最多 10 次」✗，
                // 现在那条路径永远走空，白白让冷启动慢 1 秒 ✗（用户体验上是
                // 「点了图标要愣一下才出界面」）。这里直接走纯原生分支 ✓。
                val host: ViewGroup? = null
                val cv = androidx.compose.ui.platform.ComposeView(this).apply {
                    // 「界面冻住」自愈：WebView 渲染进程死亡/无响应时重建界面（不用强杀）。
        //
        // ⚠️ 钩在 `onWebViewCreate` 上（见下面那个 override），**不要**靠
        // `findTauriWebView` 遍历视图树 —— 实测它在 3/8/15 秒都返回 null
        // （Tauri 的 WebView 不在这个 Activity 的视图树里），于是回调永远装不上。
        setContent {
                        QookiXTheme {
                            // ⚠️ 必须 remember：RustBridge 内部有 `ready` 状态，
                            // 而 `start()` 只在首次 LaunchedEffect 里跑一次。
                            // 不 remember 的话每次重组都新建实例 —— ready 永远是 false，
                            // 所有命令都报「桥尚未就绪」（实测踩到：皮肤页获取失败、
                            // 实例列表拉不到，但日志里明明写着「注入结果: ok」）。
                            val bridge = androidx.compose.runtime.remember {
                                RustBridge(this@ComposeMainActivity)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // 隐藏状态栏后内容会顶到屏幕最上沿，
                                    // 加这层内边距让标题栏不被刘海/挖孔挡住；
                                    // 状态栏临时浮出时也不会压住标题。
                                    .statusBarsPadding(),
                            ) {
                                LauncherRoot(
                                    bridge = bridge,
                                    activity = this@ComposeMainActivity,
                                )
                            }
                        }
                    }
                }
                composeView = cv
                if (host != null) {
                    host.addView(
                        cv,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    android.util.Log.i(
                        TAG,
                        "Compose 已挂到 wry 容器（${host.javaClass.simpleName}，第 $attempt 次）",
                    )
                    // 记下容器与 WebView，供「3D 预览提层」用（见 setSkinPreviewVisible）
                    overlayHost = host
                    tauriWebView = findWebViewIn(host)
                } else {
                    // 阶段 4：纯原生模式 —— 没有 WebView，Compose 就是唯一 UI
                    setContentView(cv)
                    android.util.Log.i(TAG, "Compose 已 setContentView（纯原生模式，无 WebView）")
                    overlayHost = null
                    tauriWebView = null
                }
                composeOverlayAttached = true
            } catch (e: Exception) {
                android.util.Log.w(TAG, "attachComposeOverlay 失败（attempt=$attempt）", e)
                if (attempt < 20) {
                    window?.decorView?.postDelayed({ attachComposeOverlay(attempt + 1) }, 100)
                }
            }
        }
    }

    // ── 「3D 预览直接渲染」模式的提层控制 ──────────────────────────────
    //
    // ── 背景：为什么需要它 ────────────────────────────────────────────
    // 皮肤页的 3D 预览原本走"离屏渲染 → readPixels → PNG → base64 →
    // 过 binder → Kotlin 解码"这条搬运链，实测只有 22–25fps，
    // 瓶颈 100% 在搬运，不在渲染。
    //
    // 而实测那个 WebView 的 rAF 循环**本来就能跑 90fps**
    //（`WebView 渲染能力: rAF 136 帧 / 1506ms = 90.3fps visibility=visible`）——
    // `alpha = 0f` 并不会让 Chromium 节流。也就是说：
    // **只要让它显示出来，就能拿到 ~60fps 以上，且不用再搬一个像素。**
    //
    // ── 做法 ────────────────────────────────────────────────────────
    // ComposeView 与 WebView 原本是同一个容器里的兄弟节点，ComposeView
    // 后加、所以在上面 —— 这就是 WebView 看不见的原因。
    //
    // 这里把 WebView **removeView 再 addView 回同一容器**，
    // 于是它排到了 ComposeView 之后（= 在上面）。
    // 平时用 1×1 + alpha=0 保持隐形（IPC 照常），
    // 只有皮肤页选了"直接渲染"时才展开到预览区大小、alpha=1。
    //
    // ⚠️ 风险：这个 WebView 承载全部 153 个 Rust 命令的 IPC。
    // wry 的 IPC 是**按 webview id 查表**（binding.rs），
    // 挪动视图树理论上不影响 id，但这是推测 —— 所以我同时保留了
    // "离屏回传"那条路作为默认，用户/我可以随时切回去。
    private var overlayHost: ViewGroup? = null
    private var tauriWebView: android.webkit.WebView? = null
    private var webViewRaisedForPreview = false

    /**
     * 皮肤 3D 预览框在宿主（overlayHost）里的矩形：x / y / w / h。
     *
     * null = 没有提层（webview 模式没开），触摸全部走正常流程。
     */
    private var skinPreviewRect: IntArray? = null

    /**
     * 当前这条手势该给谁：true = 给 WebView（预览框内），false = 给 Compose。
     *
     * ⚠️ 必须按 **DOWN** 决定整条手势的归属 ——
     * 如果按每一帧的落点判断，手指从预览框里划出去的那一刻
     * 手势就换了目标（WebView 收到一半、Compose 收到一半），
     * 两边都会表现为「卡住 / 乱动」。
     */
    private var gestureToWebView = false

    /**
     * 提层期间把「预览框以外」的触摸还回 Compose。
     *
     * ⚠️ 为什么需要（2026-10-22 真机复现）：
     * 修「WebView 突破图层」时把 WebView 改成**铺满整个宿主**，
     * 由 JS 把 canvas 定位到预览框位置 —— 这样画面不会盖住别的控件。
     *
     * 但代价是：铺满的 WebView 会**吃掉全屏的触摸** ——
     * 页面滚不动、侧边导航栏点不动（实测：滑动落在模型上，页面纹丝不动）。
     *
     * Android 没有「View 只接收某块区域的触摸」这种能力
     * （`touchDelegate` 只能扩大、不能缩小命中区）。
     * 所以只能在这里拦：触摸点不在预览框矩形里 → 不让 WebView 收到，
     * 交回 ComposeView 正常处理。
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val rect = skinPreviewRect
        val wv = tauriWebView
        if (rect != null && wv != null && wv.visibility == android.view.View.VISIBLE) {
            val host = overlayHost
            val px = ev.x
            val py = ev.y
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                // 按下时决定这条手势的归属
                gestureToWebView = px >= rect[0] && px <= rect[0] + rect[2] &&
                    py >= rect[1] && py <= rect[1] + rect[3]
            }
            if (!gestureToWebView) {
                // 框外的触摸：**绝不能**让 WebView 拿到（它是兄弟里最后添加的，
                // super.dispatchTouchEvent 会优先分发给它）。
                // 所以直接把事件转交给 ComposeView 处理，并**拦截掉**，
                // 不再 fall through 给 super。
                val cv = composeView
                if (cv != null && host != null) {
                    val loc = IntArray(2)
                    cv.getLocationInWindow(loc)
                    val off = IntArray(2)
                    host.getLocationInWindow(off)
                    val moved = android.view.MotionEvent.obtain(ev)
                    moved.setLocation(px - (loc[0] - off[0]), py - (loc[1] - off[1]))
                    cv.dispatchTouchEvent(moved)
                    moved.recycle()
                }
                return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 让 wry 的 WebView 在 ComposeView **之上**显示（或收回隐形）。
     *
     * @param visible true = 提到最上层并展开；false = 还原成 1×1 alpha=0
     * @param x/y/w/h 预览区在容器里的位置与大小（仅 visible=true 时用）
     * @return 是否操作成功（失败时调用方应退回离屏模式）
     */
    fun setSkinPreviewVisible(visible: Boolean, x: Int, y: Int, w: Int, h: Int): Boolean {
        val host = overlayHost ?: return false
        val wv = tauriWebView ?: return false
        return try {
            runOnUiThread {
                if (visible) {
                    // ⚠️⚠️ 2026-10-22 重做（真机复现：「WebView 突破图层」）。
                    //
                    // ── 原做法（错在哪）──────────────────────────────────
                    // 原来把 WebView **缩成预览框大小**、再挪到预览框的位置。
                    // 问题：WebView 与 ComposeView 是**同一个父容器里的兄弟**，
                    // 我们后 addView → 它永远画在 Compose **之上**。
                    // 于是页面滚动、预览框露出一部分时，WebView 就把
                    // 标题栏「皮肤」压住了 —— 这正是用户报的「突破图层」。
                    //
                    // Android 只按**父容器**裁剪子 View，不按 Compose 的层级裁剪，
                    // 所以「把 WebView 挪到框的位置」这件事本身就不可能不越界。
                    //
                    // ── 新做法（正确）────────────────────────────────────
                    // WebView **铺满整个 host**、位置恒为 (0,0)，
                    // 由 JS 把 canvas 定位到预览框在 WebView 内的偏移。
                    //
                    // 这样：
                    //   1. WebView 永远是全屏的，不存在「挪到某个位置」→ 不会跑偏；
                    //   2. canvas 只有预览框那么大，其余透明 → 不会盖住标题栏；
                    //   3. 预览框滚出屏幕 → canvas 移出 WebView 边界，
                    //      WebView 自己的裁剪就把画面切掉 → 自然消失。
                    //
                    // 坐标换算：Compose 给的是 window 坐标，WebView 内是
                    // host 坐标 —— 减掉 host 在 window 里的偏移。
                    val off = IntArray(2)
                    host.getLocationInWindow(off)
                    val localX = x - off[0]
                    val localY = y - off[1]
                    val hostW = host.width
                    val hostH = host.height

                    // 铺满 host（尺寸变了才重挂，避免频繁 remove/add 引起闪烁）
                    val needRemount = !webViewRaisedForPreview ||
                        wv.width != hostW || wv.height != hostH
                    if (needRemount) {
                        (wv.parent as? ViewGroup)?.removeView(wv)
                        host.addView(
                            wv,
                            ViewGroup.LayoutParams(
                                if (hostW > 0) hostW else ViewGroup.LayoutParams.MATCH_PARENT,
                                if (hostH > 0) hostH else ViewGroup.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    }
                    wv.x = 0f
                    wv.y = 0f
                    if (wv.alpha != 1f) {
                        wv.alpha = 1f
                        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                    val first = !webViewRaisedForPreview
                    webViewRaisedForPreview = true
                    if (first || needRemount) {
                        // 通知页面：canvas 移到屏内 + 启动自驱动渲染循环。
                        // 尺寸用**物理像素**传，JS 侧按 devicePixelRatio 换算成 CSS。
                        wv.evaluateJavascript(
                            "window.QkSkin3D && window.QkSkin3D.setDirectDisplay(true, $w, $h, $localX, $localY)"
                        ) { r ->
                            android.util.Log.i(TAG, "3D 预览：setDirectDisplay -> $r")
                        }
                    } else {
                        // 只挪 canvas 的位置（尺寸没变）—— 滚动时每帧都要发。
                        wv.evaluateJavascript(
                            "window.QkSkin3D && window.QkSkin3D.setDirectDisplay(true, $w, $h, $localX, $localY)"
                        ) { r ->
                            android.util.Log.i(TAG, "3D 预览：setDirectDisplay(移动) -> $r")
                        }
                    }
                    android.util.Log.i(
                        TAG,
                        "3D 预览：canvas ${w}x$h @($localX,$localY)（宿主 ${hostW}x$hostH）"
                    )
                    // 记住预览框在宿主里的矩形 —— dispatchTouchEvent 用它
                    // 判断「这次触摸该不该给 WebView」。
                    skinPreviewRect = intArrayOf(localX, localY, w, h)
                } else if (webViewRaisedForPreview) {
                    webViewRaisedForPreview = false
                    // 先让页面停掉渲染循环、把 canvas 挪回屏幕外，
                    // 再收回 WebView —— 否则它会继续以 60fps 白跑（耗电）。
                    wv.evaluateJavascript(
                        "window.QkSkin3D && window.QkSkin3D.setDirectDisplay(false, 0, 0)"
                    ) { }
                    wv.alpha = 0f
                    wv.x = 0f
                    wv.y = 0f
                    skinPreviewRect = null
                    wv.layoutParams = ViewGroup.LayoutParams(1, 1)
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    host.addView(wv, ViewGroup.LayoutParams(1, 1))
                    android.util.Log.i(TAG, "3D 预览：WebView 已收回隐形")
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "setSkinPreviewVisible 失败", e)
            false
        }
    }

    /** 在容器里找第一个 WebView。 */
    /** 3D 预览渲染方式在 SharedPreferences 里的键。 */
    private val KEY_PREVIEW_MODE = "preview_render_mode"
    /** 内容中心紧凑模式（布尔）。 */
    private val KEY_BROWSE_COMPACT = "browse_compact"

    /** Android 侧自己的小配置（不经过 Rust 的 settings.json）。 */
    internal fun prefs() = getSharedPreferences("qookix_ui", MODE_PRIVATE)

    /**
     * 内容中心的**紧凑模式**开关（2026-10-17 加，用户要求「记住上次选择」）。
     *
     * 为什么存 SharedPreferences 而不是 settings.json：
     * Rust 的 `Settings` 是强类型结构体，没有这个字段 ——
     * 写进去也会被静默丢弃（和 previewRenderMode 同一个坑）。
     */
    fun browseCompactPref(): Boolean = prefs().getBoolean(KEY_BROWSE_COMPACT, false)

    /** 写入内容中心紧凑模式。 */
    fun setBrowseCompactPref(compact: Boolean) {
        prefs().edit().putBoolean(KEY_BROWSE_COMPACT, compact).apply()
    }

    /** 读取 3D 预览渲染方式：compose（省电，默认）/ webview（流畅）。 */
    fun previewRenderModePref(): String =
        prefs().getString(KEY_PREVIEW_MODE, "compose") ?: "compose"

    /** 写入 3D 预览渲染方式（设置页切换时调）。 */
    fun setPreviewRenderModePref(mode: String) {
        prefs().edit().putString(KEY_PREVIEW_MODE, mode).apply()
    }

    private fun findWebViewIn(root: ViewGroup): android.webkit.WebView? {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is android.webkit.WebView) return c
            if (c is ViewGroup) findWebViewIn(c)?.let { return it }
        }
        return null
    }

    /** 找到 wry 用来装 WebView 的 ViewGroup（即 RustWebView 的父容器）。 */
    private fun findWebViewContainer(root: View?): ViewGroup? {
        if (root == null || root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is android.webkit.WebView) return root
            findWebViewContainer(child)?.let { return it }
        }
        return null
    }

    // ══ 回调注册 ══
    private var backHandler: (() -> Boolean)? = null
    private var logZoomListener: ((Int) -> Unit)? = null

    fun setComposeBackHandler(handler: (() -> Boolean)?) { backHandler = handler }
    fun setLogZoomListener(listener: ((Int) -> Unit)?) { logZoomListener = listener }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // ⚠️ 没有监听者时必须把音量键让回系统（2026-10-26 审查 P1-20）：
        // 原来无条件 `return true`，而 `logZoomListener` 全仓**没有注册点** ——
        // 结果是启动器里音量键彻底失灵（按了没声音也不调节）。
        val listener = logZoomListener
        if (listener != null &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            listener(if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 1 else -1)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != android.app.Activity.RESULT_OK) {
            // 用户取消了选择：把「另存为」的回调丢掉，否则它会一直
            // 持有那个页面的 lambda（页面早就不在了）。
            if (requestCode == REQ_CREATE_TEXT_DOC) pendingSaveCallback = null
            return
        }
        when (requestCode) {
            REQ_PICK_IMAGE -> {
                caps.onPickFileResult(data?.data)
                dispatchPickResult()
            }
            REQ_PICK_FILE -> {
                caps.onPickFileResult(data?.data)
                var path = caps.consumePickedFile()
                // ⚠️ 2026-10-18 补（修「整合包导入没反应」）：
                // 现代 DocumentsProvider 的 query("_data") 基本返回 null，
                // onPickFileResult 只能存下 content:// URI —— 而 Rust 的
                // import_modpack 对 URI 直接 bail。这里把 URI 内容拷到
                // cacheDir 成真实文件，Rust 的 std::fs 才读得到。
                if (path != null && path.startsWith("content://")) {
                    path = landUriToCache(android.net.Uri.parse(path))
                }
                val cb = pendingFileCallback
                pendingFileCallback = null
                cb?.invoke(path)
            }
            REQ_CREATE_TEXT_DOC -> {
                // D5a「另存为」：把 SAF 的 content:// URI 回调给调用方，
                // 内容由调用方经 Rust save_text_file → writeTextToUri 写入。
                // 取消选择（data == null）时回调 null，调用方静默收场。
                val cb = pendingSaveCallback
                pendingSaveCallback = null
                cb?.invoke(data?.data?.toString())
            }
            HostCapabilities.REQ_PICK_CONTROL_LAYOUT -> {
                // ⚠️ 2026-10-18 补（修「按键导入选完文件没反应」）：
                // Compose 迁移时这个分支整个丢了 —— 用户选完文件后结果没人接收，
                // 前端等不到 control-import.json，界面永远停在原地。
                // 旧 MainActivity 的 reqPickControl 流程照搬到这里。
                val json = if (data?.data != null) {
                    importControlLayoutFromUri(data.data!!)
                } else {
                    org.json.JSONObject().put("ok", false).put("error", "已取消").toString()
                }
                runCatching {
                    java.io.File(filesDir, "control-import.json").writeText(json)
                }.onFailure { Log.w("ComposeMain", "写 control-import.json 失败", it) }
            }
            else -> caps.onPickFileResult(data?.data)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (backHandler?.invoke() == true) return
        super.onBackPressed()
    }

    // ══ 能力方法 ══
    fun hasAllFilesAccess(): String = caps.hasAllFilesAccess()
    fun requestAllFilesAccess() = caps.requestAllFilesAccess()
    fun isBatteryUnrestricted(): String = caps.isBatteryUnrestricted()
    fun requestIgnoreBatteryOptimizations() = caps.requestIgnoreBatteryOptimizations()
    fun wifiIpv4(): String? = caps.wifiIpv4()
    fun thermalStatus(): String = caps.thermalStatus()
    fun systemProxy(): String? = caps.systemProxy()
    fun readPojavPrefs(): String? = caps.readPojavPrefs()
    fun readClipboardText(): String? = caps.readClipboardText()

    // ── 「另存为」（D5a，2026-10-22 加）──────────────────────────────────
    //
    // Rust 的 save_text_file 对 content:// URI 走 android_bridge::write_text_to_uri，
    // 它 JNI 反射调用的是 **ACTIVITY 指针指向的 Activity** —— 本项目里那是
    // ComposeMainActivity（TauriBridge.attach(this)），不是旧 MainActivity。
    // 所以这个方法必须在这里存在，否则「日志另存为」永远报
    // 「原生桥不可用，无法写入所选位置」。
    //
    // 返回约定（同旧 MainActivity）：成功返回空串；失败返回错误信息。
    fun writeTextToUri(uriOrPath: String, content: String): String {
        return try {
            if (!uriOrPath.startsWith("content://")) {
                val f = java.io.File(uriOrPath)
                f.parentFile?.mkdirs()
                f.writeText(content)
                return ""
            }
            val uri = android.net.Uri.parse(uriOrPath)
            // 模式必须是 "wt"（write + truncate）：只写 "w" 时部分 provider
            // 会在原内容后面追加，导出的日志会变成「旧 + 新」两份。
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: return getString(R.string.app_save_failed_no_stream)
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

    /** 「另存为」结果回调（createTextDocument 用）。 */
    private var pendingSaveCallback: ((String?) -> Unit)? = null
    private val REQ_CREATE_TEXT_DOC = 1003

    /**
     * 弹系统的「新建文档」选择器（ACTION_CREATE_DOCUMENT，text/plain）。
     *
     * 为什么不用分享：分享只能存到别的 App；用户要的是「存成 .txt 文件」。
     * 返回的是 SAF 的 content:// URI —— 由 [writeTextToUri]（经 Rust 的
     * save_text_file）写回。
     */
    fun createTextDocument(suggestedName: String, onResult: (String?) -> Unit) {
        pendingSaveCallback = onResult
        runCatching {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, suggestedName)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_CREATE_TEXT_DOC)
        }.onFailure {
            Log.w(TAG, "弹另存为选择器失败", it)
            pendingSaveCallback = null
        }
    }
    fun gameDirOptions(): String = caps.gameDirOptions()
    fun pickGameDir() = caps.pickGameDir()
    fun vpnConsentGranted(): String = caps.vpnConsentGranted()
    fun ensureVpnConsent() = caps.ensureVpnConsent()
    fun ensureTunnelService() = caps.ensureTunnelService()
    fun installApk(path: String): String = caps.installApk(path)
    fun setLogZoomCapture(enabled: Boolean) = caps.setLogZoomCapture(enabled)
    fun preloadJreLibs(dir: String): Int = caps.preloadJreLibs(dir)
    fun enableSdlIntegration() = caps.enableSdlIntegration()
    fun openExternalUrl(url: String) = caps.openUrl(url)
    fun copyToClipboard(label: String, text: String) = caps.copyToClipboard(label, text)
    fun consumeGameDirPick(): String? = caps.consumeGameDirPick()

    /**
     * 打开原生控制布局编辑器（Rust `android_bridge::open_control_editor` 经 JNI 调用）。
     *
     * ⚠️ 2026-10-18 修「点铅笔必崩」：Rust 的 JNI 全局引用存的是**实际前台 Activity**
     *（Compose 版是本类），但 `openControlEditor` 一直只写在旧 `MainActivity.kt` 里 ——
     * 方法解析失败 → NoSuchMethodError → SIGABRT 整个进程。
     * 之前用户描述成「连点几次卡死」，实际是点一次就崩。
     */
    fun openControlEditor(layout: String?, preview: Boolean, saveAs: String?) {
        val self = this@ComposeMainActivity
        runOnUiThread {
            // 防连点闸门：1.2 秒内的重复点击直接丢弃
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastControlEditorLaunch < 1200L) {
                android.util.Log.i("ComposeMain", "openControlEditor: 忽略重复点击")
                return@runOnUiThread
            }
            lastControlEditorLaunch = now
            runCatching {
                val i = Intent(self, CustomControlsActivity::class.java)
                if (!layout.isNullOrBlank()) i.putExtra("layout", layout)
                i.putExtra("preview", preview)
                if (!saveAs.isNullOrBlank()) i.putExtra("saveAs", saveAs)
                i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                self.startActivity(i)
            }.onFailure { e ->
                android.util.Log.w("ComposeMain", "打开控制布局编辑器失败", e)
                android.widget.Toast.makeText(self, "无法打开编辑器", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    private var lastControlEditorLaunch = 0L

    fun importControlLayout(): String? {
        caps.importControlLayout()
        return null
    }

    fun pickImage(onResult: (String?) -> Unit) {
        pendingImageCallback = onResult
        caps.pickFile(HostCapabilities.MIME_IMAGE, REQ_PICK_IMAGE)
    }

    /**
     * 按 MIME 选文件并把**真实路径**回调给调用方。
     *
     * 与 pickImage 同一套两步式机制（HostCapabilities.pickFile +
     * consumePickedFile），只是 MIME 可指定 —— 导入整合包要收
     * `.mrpack` / `.zip`，不能限定成图片。
     */
    fun pickFileByMime(mime: String, onResult: (String?) -> Unit) {
        pendingFileCallback = onResult
        caps.pickFile(mime, REQ_PICK_FILE)
    }

    private var pendingFileCallback: ((String?) -> Unit)? = null
    private val REQ_PICK_FILE = 1002

    private fun dispatchPickResult() {
        val path = caps.consumePickedFile()
        val cb = pendingImageCallback
        pendingImageCallback = null
        cb?.invoke(path)
    }

    private var pendingImageCallback: ((String?) -> Unit)? = null
    private val REQ_PICK_IMAGE = 1001

    // ══ Rust call_activity 依赖的方法 ══
    fun startGameActivity(instanceId: String, accountUuid: String) {
        runOnUiThread {
            val runningId = runningInstanceId()
            if (runningId != null && runningId != instanceId) {
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

    private fun runningInstanceId(): String? = try {
        if (!TauriBridge.isGameRunning()) null
        else org.json.JSONObject(TauriBridge.gameStatus()).optString("instance_id").ifEmpty { null }
    } catch (e: Throwable) { null }

    private fun confirmSwitchInstance(instanceId: String, accountUuid: String) {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.QookixAlertDialog)
            .setTitle(getString(R.string.app_switch_instance_title))
            .setMessage(getString(R.string.app_switch_instance_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.app_switch_instance_confirm)) { _, _ ->
                PendingLaunch.save(this, instanceId, accountUuid)
                Thread { runCatching { TauriBridge.killGame() } }.start()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
                }, 5000L)
            }
            .show()
    }

    /**
     * 读取 settings.json 的 `orientation` 并应用。
     * 与 MainActivity.applyStoredOrientation 同逻辑 —— 那边启动时就调了，这里之前漏了。
     */
    private fun applyStoredOrientation() {
        val mode = try {
            val f = java.io.File(filesDir, "settings.json")
            if (f.exists()) org.json.JSONObject(f.readText()).optString("orientation", "landscape") else "landscape"
        } catch (e: Exception) {
            Log.w(TAG, "读 orientation 失败，按横屏处理", e)
            "landscape"
        }
        Log.i(TAG, "applyStoredOrientation: $mode")
        setOrientation(mode)
    }

    /** 申请通知权限（Android 13+）。迁移遗漏，见 onCreate 说明。 */
    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        } catch (e: Exception) {
            Log.w(TAG, "申请通知权限失败", e)
        }
    }

    /** 清理上次残留的 GameActivity 任务（异常退出会留下空白任务）。迁移遗漏。 */
    private fun removeStaleGameTask() {
        try {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
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

    fun setOrientation(mode: String) {
        val requested = when (mode) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER
        }
        runOnUiThread { if (requestedOrientation != requested) requestedOrientation = requested }
    }

    fun writeClipboardText(text: String): String = try {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            ?: return getString(R.string.app_clipboard_unavailable)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("QookiX", text))
        ""
    } catch (e: Throwable) { e.message ?: e.toString() }

    fun openDirectory(path: String): String {
        val base = java.io.File(filesDir, ServerDocsProvider.DIR_NAME).canonicalFile
        val target = runCatching { java.io.File(path).canonicalFile }.getOrNull() ?: return "-1"
        if (target != base && !target.path.startsWith(base.path + java.io.File.separator)) return "-1"
        if (!target.isDirectory) return "-1"
        val rel = target.toRelativeString(base).trim('/')
        val docId = if (rel.isEmpty() || rel == ".") ServerDocsProvider.ROOT_ID
                    else ServerDocsProvider.DOC_PREFIX + rel
        val uri = android.provider.DocumentsContract.buildDocumentUri("$packageName.serverdocs", docId)
        return runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            "1"
        }.getOrDefault("0")
    }

    fun shareFile(path: String, mimeType: String) {
        runOnUiThread {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", java.io.File(path))
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = mimeType.ifBlank { "*/*" }
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.app_share)))
            }.onFailure { Log.w(TAG, "分享文件失败", it) }
        }
    }

    fun shareLogsZip(archiveName: String, srcDir: String) {
        runOnUiThread {
            runCatching {
                val out = java.io.File(cacheDir, archiveName)
                java.util.zip.ZipOutputStream(out.outputStream().buffered()).use { zos ->
                    java.io.File(srcDir).listFiles()?.forEach { f ->
                        if (f.isFile) {
                            zos.putNextEntry(java.util.zip.ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
                shareFile(out.absolutePath, "application/zip")
            }.onFailure { Log.w(TAG, "打包日志失败", it) }
        }
    }

    fun resolvePickedUri(uriOrPath: String): String? {
        if (!uriOrPath.startsWith("content://")) return uriOrPath
        return runCatching {
            val uri = android.net.Uri.parse(uriOrPath)
            contentResolver.openInputStream(uri)?.use { input ->
                val name = queryDisplayName(uri) ?: "picked_${System.currentTimeMillis()}"
                val dst = java.io.File(cacheDir, name)
                dst.outputStream().use { input.copyTo(it) }
                dst.absolutePath
            }
        }.onFailure { Log.w(TAG, "解析选中的文件失败", it) }.getOrNull()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
    }.getOrNull()

    fun writePojavPrefs(json: String) {
        runCatching {
            val obj = org.json.JSONObject(json)
            // 键类型与 LauncherPreferences.loadPreferences（81-102 行）一一对应：
            // int 档 / bool 档，其余（renderer、defaultCtrl）按字符串。
            val intKeys = setOf(
                "buttonscale", "mousescale", "mousespeed", "timeLongPressTrigger",
                "resolutionRatio", "gyroSensitivity", "gamepad_deadzone_scale",
            )
            val boolKeys = setOf(
                "ignoreNotch", "disableGestures", "disableDoubleTap", "mouse_start",
                "alternate_surface", "enableGyro", "gyroInvertX", "gyroInvertY",
                "buttonAllCaps", "sustained_performance",
            )
            val prefs = getSharedPreferences("launcher_preferences", MODE_PRIVATE)
            val editor = prefs.edit()
            for (key in obj.keys()) {
                val v = obj.get(key)
                if (v == org.json.JSONObject.NULL) continue
                try {
                    when {
                        key in intKeys -> {
                            val n = v.toString().toIntOrNull()
                            if (n != null) editor.putInt(key, n)
                        }
                        key in boolKeys -> editor.putBoolean(
                            key,
                            when (v) {
                                is Boolean -> v
                                is Number -> v.toInt() != 0
                                else -> v.toString() == "true" || v.toString() == "1"
                            },
                        )
                        else -> editor.putString(key, v.toString())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "写 Pojav 偏好 $key 失败", e)
                }
            }
            // commit（同步）而非 apply：必须先落盘，下面的静态字段重载才有意义。
            if (!editor.commit()) Log.w(TAG, "Pojav 偏好 commit 失败")
            // ⚠️ 关键一步：此前这里写的是 filesDir/pojav_prefs.json —— 一个全仓库
            // 没有任何读者的死文件；读端（readPojavPrefs / launch.rs 渲染器选择）
            // 读的是 LauncherPreferences 的静态字段，而那只在 GameActivity 等
            // 界面 onCreate 时才从 SharedPreferences 加载。于是「设置页改渲染器
            // → 显示保存成功 → 重启又变回去」，正是用户报的「某些设置每次开启
            // 就被重置」。这里写完立即重载静态字段：当场生效，重启后也从真存储读回。
            // 重载前必须先把 DEFAULT_PREF 指到同一份偏好文件（GameActivity.onCreate
            // 也这么做，见 GameActivity.kt:367）：loadPreferences 本体不负责赋值，
            // 它只读已就位的 DEFAULT_PREF，为 null 时会直接放弃。
            LauncherPreferences.DEFAULT_PREF =
                getSharedPreferences("launcher_preferences", MODE_PRIVATE)
            LauncherPreferences.loadPreferences(this)
        }.onFailure { Log.w(TAG, "写 Pojav 偏好失败", it) }
    }

    fun importControlLayoutFromPath(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return org.json.JSONObject()
            .put("ok", false).put("error", getString(R.string.app_import_layout_empty_path)).toString()
        return runCatching {
            val f = java.io.File(p)
            if (!f.isFile) throw java.io.IOException(getString(R.string.app_import_layout_not_found))
            if (f.length() > 8 * 1024 * 1024) throw java.io.IOException(getString(R.string.app_import_layout_too_large))
            val text = f.readText(Charsets.UTF_8)
            val j = org.json.JSONObject(text)
            if (!j.has("version") || !j.has("mControlDataList")) throw java.io.IOException(getString(R.string.app_import_layout_invalid))
            val tmp = java.io.File(filesDir, "controlmap/TMP_IMPORT_FILE.json")
            tmp.parentFile?.mkdirs()
            tmp.parentFile?.listFiles()?.forEach { old ->
                if (old.isFile && old.name.startsWith("TMP_IMPORT_FILE") &&
                    old.name.endsWith(".json") && old.absolutePath != tmp.absolutePath) old.delete()
            }
            tmp.writeText(text)
            org.json.JSONObject().put("ok", true).put("name", "TMP_IMPORT_FILE")
                .put("buttons", j.optJSONArray("mControlDataList")?.length() ?: 0)
                .put("joysticks", j.optJSONArray("mJoystickDataList")?.length() ?: 0)
                .put("drawers", j.optJSONArray("mDrawerDataList")?.length() ?: 0).toString()
        }.getOrElse {
            Log.w(TAG, "从路径导入按键布局失败", it)
            org.json.JSONObject().put("ok", false)
                .put("error", it.message ?: getString(R.string.app_import_layout_failed)).toString()
        }
    }

    /**
     * 从 SAF URI 读取按键布局并校验（结果 JSON 直接返回给 onActivityResult 写盘）。
     *
     * 与 [importControlLayoutFromPath] 同一套校验（version + mControlDataList），
     * 只是数据源从文件路径换成 contentResolver 流。
     */
    fun importControlLayoutFromUri(uri: android.net.Uri): String {
        return runCatching {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(java.nio.charset.StandardCharsets.UTF_8)
            } ?: throw java.io.IOException(getString(R.string.app_import_layout_failed))
            val j = org.json.JSONObject(text)
            if (!j.has("version") || !j.has("mControlDataList")) {
                throw java.io.IOException(getString(R.string.app_import_layout_invalid))
            }
            val tmp = java.io.File(filesDir, "controlmap/TMP_IMPORT_FILE.json")
            tmp.parentFile?.mkdirs()
            tmp.parentFile?.listFiles()?.forEach { old ->
                if (old.isFile && old.name.startsWith("TMP_IMPORT_FILE") &&
                    old.name.endsWith(".json") && old.absolutePath != tmp.absolutePath) old.delete()
            }
            tmp.writeText(text)
            org.json.JSONObject().put("ok", true).put("name", "TMP_IMPORT_FILE")
                .put("buttons", j.optJSONArray("mControlDataList")?.length() ?: 0)
                .put("joysticks", j.optJSONArray("mJoystickDataList")?.length() ?: 0)
                .put("drawers", j.optJSONArray("mDrawerDataList")?.length() ?: 0).toString()
        }.getOrElse {
            Log.w(TAG, "从 URI 导入按键布局失败", it)
            org.json.JSONObject().put("ok", false)
                .put("error", it.message ?: getString(R.string.app_import_layout_failed)).toString()
        }
    }
    /**
     * 把 SAF 的 content:// URI 落地为 cacheDir 下的真实文件，返回路径。
     *
     * 为什么需要（2026-10-18）：Rust 侧 import_modpack 等命令用 `std::fs` 读文件，
     * 对 content:// URI 必然失败；而 `query("_data")` 在现代 DocumentsProvider 上
     * 基本拿不到真实路径。唯一稳妥的办法是应用自己把流拷出来。
     *
     * 文件名优先用 DISPLAY_NAME（保留扩展名，Rust 侧可能按扩展名判断格式）。
     */
    fun landUriToCache(uri: android.net.Uri): String? {
        return runCatching {
            val name = queryDisplayName(uri) ?: "picked_file"
            val safe = name.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
            val dst = java.io.File(cacheDir, "import_$safe")
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            Log.i("ComposeMain", "已落地所选文件: ${dst.absolutePath} (${dst.length()} bytes)")
            dst.absolutePath
        }.onFailure { Log.w("ComposeMain", "落地所选文件失败", it) }.getOrNull()
    }
    fun exportControlLayout(layout: String) {
        val f = java.io.File(filesDir, "controlmap/$layout.json")
        if (!f.isFile) { Log.w(TAG, "导出控制布局失败：布局不存在 $layout"); return }
        shareFile(f.absolutePath, "application/json")
    }
}

/**
 * Compose 壳的根。
 */
@androidx.compose.runtime.Composable
private fun LauncherRoot(bridge: RustBridge, activity: ComposeMainActivity) {
    // ── 外观设置（主题 / 主题色）────────────────────────────────────────
    //
    // ⚠️⚠️ 2026-10-15 修：用户反馈「点色块没变化 + 改深浅模式没反应」。
    //
    // 根因：原来 `QookiXTheme { }` 是**裸调用**，既没传 darkTheme 也没传
    // themeColor；而且全局设置状态只存在于 `SettingsRoute` 内部
    // （进设置页才加载），主题在外面根本感知不到。
    //
    // 修法：在根节点**单独拉一次外观相关的设置**（只有两个字段，
    // 走同一个 get_settings），把它喂给 QookiXTheme。
    // 不去复用 SettingsRoute 的 state —— 那会把"进设置页才加载"
    // 变成"必须进过设置页主题才生效"。
    var themeMode by remember { mutableStateOf("system") }
    var themeColor by remember { mutableStateOf("") }
    // ⚠️ 界面缩放同样"存了但没用"（2026-10-15 一起修）：
    // 原来 `uiScale` 只在设置页显示数字，没有任何地方把它变成实际尺寸。
    var uiScale by remember { mutableStateOf(100) }
    // 侧边栏宽度（dp）。设置页拖动滑杆时回传到这里，驱动 LauncherShell。
    var sidebarWidth by remember { mutableStateOf(168) }
    // 3D 预览渲染方式：compose（省电，默认）/ webview（流畅）
    // 顶栏自定义内容（内容中心的搜索工具栏）。null = 用普通标题。
    var toolbarContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var previewRenderMode by remember { mutableStateOf("compose") }

    LaunchedEffect(bridge) {
        // 桥就绪前 get_settings 会失败，用重试等到可用为止
        repeat(60) {
            val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                bridge.invoke("get_settings", "{}") { r ->
                    r.fold(
                        onSuccess = { raw ->
                            runCatching {
                                val o = org.json.JSONObject(raw)
                                themeMode = o.optString("theme", "system").ifBlank { "system" }
                                // ⚠️ Rust 的 Settings 没有 rename_all，实际键是
                                // **snake_case**（theme_color / ui_scale）。
                                // 两个都试，避免以后被命名坑。
                                themeColor = o.optString("theme_color", "")
                                    .ifBlank { o.optString("themeColor", "") }
                                uiScale = o.optString("ui_scale", "")
                                    .ifBlank { o.optString("uiScale", "") }
                                    .toIntOrNull() ?: 100
                                uiScale = uiScale.coerceIn(60, 150)

                                // 侧边栏宽度（2026-10-17 用户可调）
                                sidebarWidth = o.optString("sidebar_width", "")
                                    .ifBlank { o.optString("sidebarWidth", "") }
                                    .toIntOrNull() ?: 168
                                sidebarWidth = sidebarWidth.coerceIn(140, 260)

                                // ── 启动时套用已保存的 3D 预览渲染方式 ──────
                                //
                                // ⚠️⚠️ 2026-10-16 关键：**不能存在 Rust 的 settings 里**。
                                //
                                // 踩过的坑：把 previewRenderMode 写进 settings.json 后，
                                // 文件里确实有 `"previewRenderMode": "webview"`，但重启后读不回来 ——
                                // 因为 `get_settings()` 反序列化到**强类型的 `Settings` 结构体**
                                //（models.rs L355），里面没有这个字段，于是被静默丢弃。
                                // 而改 Rust 是硬约束（0 改动）。
                                //
                                // 改用 SharedPreferences：这是**纯 Android 前端的渲染策略**，
                                // 本来就不该混进跨端共享的 settings.json。
                                previewRenderMode = activity.previewRenderModePref()

                                // ── 启动时套用已保存的屏幕方向 ──────────────
                                //
                                // ⚠️ 2026-10-15 补：只在设置页改方向时下发是不够的 ——
                                // 应用重启后 Android 会回到 manifest 的默认方向
                                //（fullSensor），用户存的 `orientation` 就白设了。
                                // 实测：设成竖屏 → 重启 → 又变回横屏。
                                //
                                // 这里在读到设置后立即下发一次，保证"重启也保持"。
                                val o2 = o.optString("orientation", "system")
                                    .ifBlank { "system" }
                                if (o2.isNotBlank() && o2 != "system") {
                                    bridge.invoke(
                                        "set_orientation",
                                        org.json.JSONObject().put("mode", o2).toString(),
                                    ) { }
                                }
                            }
                            cont.resumeWith(Result.success(true))
                        },
                        onFailure = { cont.resumeWith(Result.success(false)) },
                    )
                }
            }
            if (ok) return@LaunchedEffect
            kotlinx.coroutines.delay(500)
        }
    }

    // 三态换算：system 跟随系统，其余强制
    val darkTheme = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }

    // ── 界面缩放：改写 LocalDensity ──────────────────────────────────
    //
    // 只改 density（不改 fontScale）：density 影响 dp→px 的换算，
    // 于是 padding / 尺寸 / 圆角一起缩放，是"整体密度"该有的行为。
    // 如果同时放大 fontScale，字会比控件涨得更快，比例会失真。
    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = androidx.compose.runtime.remember(baseDensity, uiScale) {
        androidx.compose.ui.unit.Density(
            density = baseDensity.density * (uiScale / 100f),
            fontScale = baseDensity.fontScale,
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaledDensity,
    ) {
        QookiXTheme(
            darkTheme = darkTheme,
            themeColor = themeColor,
            // 用户显式选了主题色时，静态派生色板优先（见 QookiXTheme 的注释）
            dynamicColor = themeColor.isBlank(),
        ) {
        androidx.compose.material3.Surface(
            modifier = androidx.compose.ui.Modifier.fillMaxSize(),
            color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        ) {
            var current by remember { mutableStateOf(NavDestination.Home) }
    // 选中的实例 / 服务器 id —— **必须放在这一层**（2026-10-17）。
    // LauncherShell 在横屏用侧栏、竖屏用底部导航，是两个不同的组合分支；
    // 旋转时 PageHost 子树会被销毁重建，放在里面的 remember 全丢 ——
    // 结果就是「详情页转竖屏自动退回列表」。放到 Activity 层才能跨形态存活。
    var selectedServerId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
        // 顶栏标题覆盖（文件浏览这类「非 NavDestination 的下钻层」用）
        var titleOverride by remember { mutableStateOf<String?>(null) }
        // PageHost 注册的「内部返回处理器」（文件浏览这类栈外下钻层用）
        var innerBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
            var bridgeReady by remember { mutableStateOf(false) }
            val backStack = remember { androidx.compose.runtime.mutableStateListOf<NavDestination>() }

            // 二级页的「上一层」。顶栏返回键与系统返回键**共用这张表** ——
            // 原来是两套逻辑（顶栏回「更多」、系统返回却直接回首页），同一个页面
            // 两种返回结果，用户会以为界面错乱。
            fun goUp(): Boolean {
                val parent = when (current) {
                    NavDestination.CreateInstance, NavDestination.InstanceDetail -> NavDestination.Instances
                    NavDestination.ServerDetail, NavDestination.ServerCreate,
                    NavDestination.Frp, NavDestination.FrpRooms -> NavDestination.Multiplayer
                    NavDestination.Skins, NavDestination.News,
                    NavDestination.Playtime, NavDestination.Settings -> NavDestination.More
                    NavDestination.Account -> NavDestination.Home
                    else -> null
                } ?: return false
                current = parent
                backStack.clear()
                return true
            }

            androidx.compose.runtime.DisposableEffect(Unit) {
                activity.setComposeBackHandler {
                    when {
                        backStack.isNotEmpty() -> { current = backStack.removeAt(backStack.lastIndex); true }
                        // 与顶栏返回键同一张表（以前这里一律回首页，跳过了「更多」）
                        else -> goUp()
                    }
                }
                onDispose { activity.setComposeBackHandler(null) }
            }

            LaunchedEffect(Unit) { bridge.start { bridgeReady = true } }

            fun navigate(dest: NavDestination) {
                if (dest in NavDestination.bottomBar) { current = dest; backStack.clear() }
                else { if (current !in NavDestination.bottomBar) backStack.add(current); current = dest }
            }

            // 首启「准备运行环境」：检查 + 自动补齐 + 逐项进度 + 失败可见。
            // 接线收在 FirstRunSetupHost 里（本文件已顶到体量预算）。
            FirstRunSetupHost(bridge = bridge, bridgeReady = bridgeReady)

            LauncherShell(
                current = current,
                onNavigate = { navigate(it) },
                onOpenAccount = { navigate(NavDestination.Account) },
                // 文件浏览等「栈外下钻层」用这个覆盖标题；没覆盖时不传，
                // 让 LauncherShell 用它自己的 titleFor(current) 默认值。
                title = titleOverride,
                // 内容中心把搜索/筛选/排序工具栏注册到这里 —— 和标题同一行
                titleContent = toolbarContent,
                sidebarWidthDp = sidebarWidth,
                //
                // 以前只有一级页面（首页/实例/内容/多人/下载/更多）有顶栏，
                // 二级页面各自在内容区自绘标题栏 —— 结果「新建实例」页
                // 出现两个标题（用户截图指出）。现在统一由顶栏提供。
                //
                // 返回目标按页面决定：
                //   新建实例 → 实例列表
                //   实例详情 → 实例列表
                //   服务器详情 → 多人
                //   其余二级页 → 各自的上层
                onBack = ({
                    // 先问 PageHost 的「内部返回处理器」（文件浏览这类栈外下钻层），
                    // 它返回 true 表示已消费；否则按统一的上层表回退。
                    if (innerBack?.invoke() != true) goUp()
                }).takeIf {
                    current !in NavDestination.bottomBar
                },
                // ⚠️ 2026-10-18 加：实例页的「新建实例」按钮从内容区移到顶栏，
                // 和标题、账号图标同一行 —— 省掉原来占掉的一整行（约 56dp）。
                actions = {
                    if (current == NavDestination.Instances) {
                        FilledTonalButton(
                            onClick = { navigate(NavDestination.CreateInstance) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 14.dp,
                                vertical = 6.dp,
                            ),
                        ) {
                            Icon(
                                androidx.compose.material.icons.Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.instances_new),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                },
            ) {
                PageHost(
                    dest = current,
                    bridge = bridge,
                    bridgeReady = bridgeReady,
                    onNavigate = { navigate(it) },
                    // 文件浏览这类「非 NavDestination 的下钻层」用它上报真实标题
                    onTitleChange = { titleOverride = it },
                    onBackHandlerChanged = { innerBack = it },
                    onSidebarWidthChanged = { sidebarWidth = it },
                    // 内容中心把搜索/筛选/排序工具栏注册到顶栏（2026-10-17）
                    onRegisterToolbar = { toolbarContent = it },
                    selectedServerId = selectedServerId,
                    onSelectedServerIdChange = { selectedServerId = it },
                    selectedInstanceId = selectedInstanceId,
                    onSelectedInstanceIdChange = { selectedInstanceId = it },
                    activity = activity,
                    // 3D 预览渲染方式（外观页可切）。不传的话 PageHost
                    // 会一直用默认值 "compose"，「流畅」模式就永远不生效
                    //（踩过：设置副标题变了但提层日志一条没有）。
                    previewRenderMode = previewRenderMode,
                    // 设置页改了主题/主题色 → 立刻回传给根节点，无需重启
                    onAppearanceChanged = { mode, color, scale, preview ->
                        themeMode = mode
                        themeColor = color
                        uiScale = scale
                        previewRenderMode = preview
                    },
                )
            }

        }
        }
    }
}












