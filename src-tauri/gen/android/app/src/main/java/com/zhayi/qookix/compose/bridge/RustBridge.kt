package com.zhayi.qookix.compose.bridge

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.zhayi.qookix.R
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Compose ↔ Rust 的桥。
 *
 * ── 为什么要这么绕 ────────────────────────────────────────────────────
 * 目标要求「不改 Rust」，而 Rust 的 60 个命令只通过 Tauri IPC 暴露。
 * Tauri 在 Android 上导出了 IPC 入口 `Rust.ipc(webviewId, url, message)`
 * （generated/Rust.kt:37），但它的实现是：
 *
 *     // wry-0.57.0/src/android/binding.rs:412
 *     if let Some(ipc) = IPC.lock().unwrap().get(&webview_id) { ... }
 *
 * —— **按 webview id 查表，查不到就静默什么都不做**。
 * 而这张表（以及资源加载 handler）都是 **Rust 创建 WebView 时**
 * 按它自己生成的 id 注册的（wry/src/android/mod.rs:216-232）。
 *
 * 实测结论（详见 COMPOSE-BRIDGE-FINDING.md）：
 *   Kotlin 自己 `WebView(context)` 建的实例**没有那个 id** ——
 *   资源和 IPC 都不通（日志里连一条资源请求都没有）。
 *
 * → 所以必须**复用 Rust 建的那个 WebView**。本类通过 `findTauriWebView()`
 *   在 Activity 的 View 树里找到它（现有 MainActivity 的音量键缩放
 *   就是这么拿的），然后在它上面注入转发器。
 *
 * ── 协议 ─────────────────────────────────────────────────────────────
 * 消息体（`tauri/src/ipc/protocol.rs` 的 Message 结构）：
 *   { cmd, callback, error, payload, options, __TAURI_INVOKE_KEY__ }
 * 详见 COMPOSE-IPC-PROTOCOL.md。
 */
class RustBridge(private val activity: Activity) {

    /**
     * 监听 WebView 渲染进程的**无响应/死亡**，用于「界面冻住」的自愈。
     *
     * ## 为什么不是"帧看门狗"
     * Compose 静止页面**本来就不出帧**，数帧的方案会把正常页面误判成冻结
     * （每 15 秒重建一次界面）。`onRenderProcessUnresponsive` 是系统给的准确信号。
     *
     * 现象（真机反复遇到）：进程活着、Activity 在前台、无 ANR，但帧不动、点击没反应，
     * `ps -A` 里渲染进程已消失 → 只能强杀重开。装上这个回调后会自动 `recreate()`。
     *
     * @param onDead 判定异常后的恢复动作（调用方传 `recreate`）
     */
    fun installRenderProcessWatcher(webView: android.webkit.WebView, onDead: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        runCatching {
            webView.webViewRenderProcessClient = object : android.webkit.WebViewRenderProcessClient() {
                // 注意：`onRenderProcessGone` 属于 WebViewClient，这个类只有这一个回调；
                // 渲染进程真正死掉时，系统通常先回调 `onRenderProcessUnresponsive`。
                override fun onRenderProcessUnresponsive(
                    view: android.webkit.WebView,
                    renderer: android.webkit.WebViewRenderProcess?,
                ) {
                    Log.w("QookiX-Render", "WebView 渲染进程无响应 → 自愈（重建界面）")
                    onDead()
                }

                // 这个类有两个抽象成员，必须都实现（虽然"恢复响应"这里不需要做事）
                override fun onRenderProcessResponsive(
                    view: android.webkit.WebView,
                    renderer: android.webkit.WebViewRenderProcess?,
                ) {
                    Log.i("QookiX-Render", "WebView 渲染进程恢复响应")
                }
            }
            Log.i("QookiX-Render", "已安装渲染进程监听")
        }.onFailure { Log.w("QookiX-Render", "安装渲染进程监听失败", it) }
    }


    companion object {
        private const val TAG = "RustBridge"
        private const val JS_INTERFACE_NAME = "QookiXNative"
        /** 与 tauri.conf.json 的 app.windows[0].label 一致。 */
        private const val WEBVIEW_ID = "main"

        /**
         * **静态**事件订阅表（阶段 2，2026-10-24）。
         *
         * Rust 侧 `progress::push()` 现在优先经 JNI 调
         * `TauriBridge.onCoreEvent(event, json)` → [deliverEvent]。
         * 这条路没有 Kotlin 实例上下文（Rust 手上只有 Activity），
         * 所以订阅必须落在**静态表**里；实例表 `eventHandlers` 留给旧的
         * WebView 通道 —— 两套并行，迁移期走哪条通道都能收到。
         */
        private val globalHandlers =
            java.util.concurrent.ConcurrentHashMap<String, MutableList<(String) -> Unit>>()

        /**
         * Rust 经 JNI 推事件进来 → 分发给所有订阅者（统一切到主线程）。
         *
         * 用显式 for 循环而不是嵌套 `forEach { h -> ... post { h(...) } }`：
         * 后者在 Kotlin 里出现过 `Unresolved reference 'h'`（嵌套 lambda 捕获问题）。
         */
        @JvmStatic
        fun deliverEvent(event: String, payload: String) {
            // 每种事件名只打一次日志：用来确认「事件真的走 JNI 通道进来了」
            // （旧通道是 WebView → JS → onEvent，出问题时完全没痕迹）。
            if (loggedEvents.add(event)) {
                android.util.Log.i(TAG, "JNI 事件通道路由成功：$event（订阅者 ${globalHandlers[event]?.size ?: 0} 个）")
            }
            val list = globalHandlers[event]?.toList() ?: return
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            for (handler in list) {
                main.post { runCatching { handler(payload) } }
            }
        }

        /** 已打过日志的事件名（避免每条进度都刷屏）。 */
        private val loggedEvents = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /**
         * 从 Activity 的 View 树里找 Rust 创建的那个 WebView。
         *
         * 与 MainActivity.findWebView 同源做法：Rust 的 WebView 由 wry 在
         * onCreate 之后加进 decorView，所以要递归找（且可能要重试 —— 见 start()）。
         */
            /**
     * 监听 WebView 渲染进程的**死亡 / 无响应**，用于「界面冻住」的自愈。
     *
     * ## 为什么是它，而不是"帧看门狗"
     *
     * 真机反复遇到：进程活着、Activity 在前台、logcat 无 ANR，但界面帧不再更新、
     * 点击全无反应（`ps -A` 里渲染进程已消失）→ 只能强杀重开。
     *
     * 我先想过用 Choreographer 数帧：**推演后否了** —— Compose 静止页面本来就不出帧，
     * 15 秒没帧会被误判成冻结，反而每 15 秒重建一次界面。
     * `onRenderProcessUnresponsive` / `onRenderProcessGone` 是系统给的**准确**信号，
     * 只在渲染进程真的出问题时触发。
     *
     * @param onDead 判定异常后的恢复动作（调用方一般传 `recreate`）
     */
// installRenderProcessWatcher 现在在 class RustBridge 内部（需要 activity 与 WebView 查找）。

fun findTauriWebView(activity: Activity): WebView? {
            val root = activity.window?.decorView ?: return null
            return search(root)
        }

        private fun search(v: View): WebView? {
            if (v is WebView) return v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    search(v.getChildAt(i))?.let { return it }
                }
            }
            return null
        }
    }

    private val pending = ConcurrentHashMap<Int, (Result<String>) -> Unit>()

    /** 桥就绪前收到的调用（就绪后统一补发，见 [invoke] 的说明）。 */
    private class PendingCall(
        val cmd: String,
        val payload: String,
        val onResult: (Result<String>) -> Unit,
    )

    /**
     * 桥未就绪时排队的调用。
     *
     * ⚠️ 必须有容量上限：如果桥永远不就绪（页面加载失败等），
     * 无限制排队会一直吃内存且用户仍看不到错误。
     * 超出上限时把最早的几条判失败，避免静默堆积。
     */
    private val invokeQueue = java.util.Collections.synchronizedList(
        java.util.ArrayList<PendingCall>(),
    )
    private val maxQueued = 256

    /** 桥刚就绪时 `__QK_BRIDGE__` 可能还没提交完成，单条命令最多重试几次。 */
    private val maxInvokeRetry = 4

    /**
     * 3D 帧推送的接收端（由 Skin3dHost 注册）。
     *
     * 见 `NativeInbox.onSkinFrame` 的注释：用推送替代轮询，
     * 每帧省掉约 27ms 的往返开销。
     */
    @Volatile
    private var skinFrameSink: ((String) -> Unit)? = null

    /** 注册 3D 帧接收器（传 null 取消）。 */
    fun setSkinFrameSink(sink: ((String) -> Unit)?) {
        skinFrameSink = sink
    }

    /**
     * 事件处理器表：事件名 → 处理器列表。
     *
     * 一个事件名可以有多个订阅者（例如 `install://progress` 同时被
     * 下载页与首页「进行中」订阅），所以用列表而不是单值。
     */
    private val eventHandlers = ConcurrentHashMap<String, MutableList<(String) -> Unit>>()

    /** 已向 Tauri 注册过的事件名（避免重复注册）。 */
    private val subscribedEvents = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var nextId = 1
    private var ready = false
        set(value) {
            field = value
            // 记录"曾经就绪过"：冷启动阶段 WebView 还没建好，不能算桥死。
            if (value) everReady = true
        }

    /** 桥是否曾经就绪过（用于区分"冷启动未就绪"与"用着用着死掉"）。 */
    private var everReady = false

    /** 连续"WebView 不见了"的命中次数。 */
    private var deadBridgeHits = 0

    /** 上次自动重建的时间，用于避免连续重建。 */
    private var lastRecoverAt = 0L

    /**
     * WebView 已经找不到时的自愈（⚠️ 2026-10-23 真机事故）。
     *
     * 现象：游戏崩溃回到启动器后，**内容页变空、点「启动游戏」没反应、其它页面也空**，
     * logcat 里每条命令都是 `桥尚未就绪`。原因不是桥没初始化 —— 而是
     * **WebView（及其渲染进程）被那次崩溃带走了**，`findTauriWebView` 返回 null，
     * 于是 `invokeInternal` 在第一步就把每条命令判失败，且永远不会自愈
     * （只能强杀应用重开）。
     *
     * 这里计数：连续 5 条命令都找不到 WebView → 重建 Activity，让 Tauri 重新建 WebView。
     * 只在 `everReady == true` 时生效，避免冷启动阶段误判；20 秒内最多重建一次。
     */
    private fun noteDeadBridge() {
        if (!everReady) return
        deadBridgeHits++
        // 2 条就重建：原来 5 条意味着用户要先白点 5 下才有反应（真机反馈"点什么都没反应"）。
        if (deadBridgeHits < 2) return
        val now = System.currentTimeMillis()
        if (now - lastRecoverAt < 20_000L) return
        lastRecoverAt = now
        deadBridgeHits = 0
        Log.w(TAG, "WebView 已消失（渲染进程退出？），自动重建界面以恢复 IPC")
        activity.runOnUiThread {
            runCatching { activity.recreate() }
                .onFailure { Log.e(TAG, "重建界面失败：${it.message}") }
        }
    }
    private var injected = false

    val isReady: Boolean get() = ready

    /**
     * 等 Rust 把 WebView 建出来，然后注入转发器。
     *
     * 为什么要轮询：Tauri 的 WebView 由 Rust 在 onCreate 之后创建，
     * 时机不保证。MainActivity 里同样的做法（disableWebViewZoom）也用了重试。
     *
     * 注意参数顺序：`onReady` 放最后，这样调用点能写成
     * `bridge.start { ready = true }` 的尾随 lambda 形式。
     *
     * @param maxAttempts 最大重试次数（间隔 400ms 起、逐步加长，总计约 40 秒）
     * @param onReady 注入成功后的回调（主线程）
     */
    fun start(maxAttempts: Int = 60, onReady: () -> Unit) {
        if (ready) {
            onReady()
            return
        }

        // ⚠️ 阶段 4（2026-10-25）：**先探测 JNI 直连**。
        //
        // 154 条命令与全部事件都已走 JNI（`TauriBridge.nativeInvoke` /
        // `onCoreEvent`），不再需要 WebView。如果 JNI 通，就直接判定就绪，
        // 绝不能再等那个**永远不会出现**的 WebView ——
        // 否则 UI 会永远卡在加载态（真机实测：`等不到 Rust 创建的 WebView（已重试 60 次）`
        // 之后界面一直不加载数据 ✗）。
        if (jniPing()) {
            Log.i(TAG, "JNI 直连可用（无需 WebView）→ 桥就绪")
            ready = true
            onReady()
            return
        }

        attemptInject(0, maxAttempts, onReady)
    }

    /** 用一条最轻的命令探测 JNI 通道是否可用。 */
    private fun jniPing(): Boolean = try {
        val raw = com.zhayi.qookix.tauri.TauriBridge.nativeInvoke("get_pins", "{}")
        raw.contains("\"ok\":true")
    } catch (t: Throwable) {
        Log.w(TAG, "JNI 探测失败", t)
        false
    }

    private fun attemptInject(attempt: Int, max: Int, onReady: () -> Unit) {
        val wv = findTauriWebView(activity)
        if (wv == null) {
            if (attempt < max) {
                activity.window?.decorView?.postDelayed({
                    attemptInject(attempt + 1, max, onReady)
                }, 100)
            } else {
                Log.w(TAG, "等不到 Rust 创建的 WebView（已重试 $max 次）")
            }
            return
        }

        if (!injected) {
            wv.addJavascriptInterface(NativeInbox(), JS_INTERFACE_NAME)
            // 页面可能已经加载完了（Rust 建得早），直接注入即可；
            // 若还在加载中，onPageFinished 之前注入也能生效，因为
            // __TAURI_INTERNALS__ 是由 document-start 脚本建的。
            wv.postDelayed({
                wv.evaluateJavascript(INJECT_JS) { result ->
                    val r = result?.trim('"')
                    Log.i(TAG, "注入结果: $r")
                    if (r == "ok" || r == "already") {
                        injected = true
                        ready = true
                        // 桥真正可用了：把就绪前排队的那批调用补发出去
                        //（页面初始化时并发发出的 list_instances / get_settings 等）
                        flushQueue()

                        // ── 探针：这个 WebView 支持 WebGL 吗？────────────────
                        //
                        // 为什么要在桥就绪后立刻查：
                        //   Rust 建的这个 WebView 一直只用来跑 IPC 注入脚本，
                        //   从没承担过渲染任务。而皮肤页的 3D 预览
                        //   （旧 UI 用 skinview3d）需要 WebGL ——
                        //   如果这里不支持，方案就要换（改用 GLSurfaceView 自己渲染）。
                        //
                        //   代价极低（一次 evaluateJavascript），
                        //   但能避免"照着方案做了一半才发现跑不起来"。
                        wv.postDelayed({
                            wv.evaluateJavascript(WEBGL_PROBE_JS) { probe ->
                                Log.i(TAG, "WebGL 探针: $probe")
                            }
                        }, 200)

                        // 诊断：确认页面加载的是哪一份 index.html + 3D 管线是否可用。
                        //
                        // ⚠️ 用**数组 join** 而不是逗号表达式 ——
                        // JS 的 `(a, b, c)` 只返回最后一个值、前几项不求值。
                        //
                        // ⚠️ selfTest 是异步的（皮肤纹理要上传 GPU），而
                        // evaluateJavascript **不支持 Promise 返回值**（会拿到 `{}`）。
                        // 所以它把结果写进 `__QK_SKIN3D_SELFTEST`，这里轮询取回。
                        wv.postDelayed({
                            wv.evaluateJavascript(
                                "(function(){" +
                                "var h=document.documentElement.outerHTML||'';" +
                                "var m=[];" +
                                "m.push('htmlLen='+h.length);" +
                                "m.push('scripts='+document.scripts.length);" +
                                "m.push('skin3dType='+(typeof window.QkSkin3D));" +
                                "m.push('hasSkinScript='+(h.indexOf('skin3d/')>=0));" +
                                "try{ if(window.QkSkin3D){" +
                                "  m.push('init='+window.QkSkin3D.init());" +
                                "  window.QkSkin3D.selfTest();" +
                                "  m.push('self='+window.__QK_SKIN3D_SELFTEST);" +
                                // 旋转接口的可用性：有接口 ≠ 调用有效。
                                // orbit 返回布尔（controls 是否存在且在旋转），
                                // 这里把结果一并报出来，省一次单独验证。
                                "  m.push('orbitApi='+(typeof window.QkSkin3D.orbit));" +
                                "  m.push('autoApi='+(typeof window.QkSkin3D.setAutoRotate));" +
                                "  m.push('capeApi='+(typeof window.QkSkin3D.loadCape));" +
                                "  m.push('perf='+window.QkSkin3D.probeCompression());" +
                                "} }catch(e){ m.push('err='+e.message); }" +
                                "return m.join(' ');" +
                                "})()"
                            ) { diag ->
                                Log.i(TAG, "页面自检: $diag")
                                // 异步结果稍后再读一次
                                wv.postDelayed({
                                    wv.evaluateJavascript(
                                        "window.__QK_SKIN3D_SELFTEST || 'pending'"
                                    ) { late ->
                                        Log.i(TAG, "页面自检(异步): $late")
                                        // selfTest 已经把皮肤加载好了（它是异步的，
                                        // 这里已经等够时间）—— 现在验证旋转**真的改变画面**。
                                        // "接口存在" ≠ "调用有效"（setView 那次就是反例）。
                                        wv.postDelayed({
                                            wv.evaluateJavascript(
                                                "window.QkSkin3D ? window.QkSkin3D.verifyOrbit() : 'nohost'"
                                            ) { orb ->
                                                Log.i(TAG, "旋转自检: $orb")
                                                // 自动旋转同理：光有 API 不算数，
                                                // 要确认模型角度真的会变。
                                                wv.postDelayed({
                                                    wv.evaluateJavascript(
                                                        "window.QkSkin3D ? window.QkSkin3D.verifyAutoRotate() : 'nohost'"
                                                    ) { ar ->
                                                        Log.i(TAG, "自动旋转自检: $ar")
                                                    }
                                                }, 600)
                                            }
                                        }, 600)
                                    }
                                }, 1200)

                                // 测这个 WebView 的真实渲染帧率。
                                // 在决定"要不要让它可见"之前先量数据 ——
                                // "可见后能 60fps" 不能靠推断（踩过太多次）。
                                wv.postDelayed({
                                    wv.evaluateJavascript(
                                        "window.QkSkin3D ? window.QkSkin3D.measureFps(1500) : 'nohost'"
                                    ) { _ ->
                                        wv.postDelayed({
                                            wv.evaluateJavascript(
                                                "window.__QK_SKIN3D_FPS || 'pending'"
                                            ) { fps ->
                                                Log.i(TAG, "WebView 渲染能力: $fps")
                                            }
                                        }, 2000)
                                    }
                                }, 2600)
                            }
                        }, 400)

                        activity.runOnUiThread {
                            Log.i(TAG, "桥就绪 → 通知 Compose（bridgeReady=true）")
                            onReady()
                        }
                    } else if (attempt < max) {
                        // Tauri 的初始化脚本可能还没跑完，稍后重试。
                        //
                        // ⚠️ 2026-10-19：间隔从 200ms 放宽到 400ms、并让步数
                        // 随时间增长。冷启动（尤其模拟器 / 低端机）上
                        // `__TAURI_INTERNALS__` 出现过十几秒才就绪的情况，
                        // 原来固定 200ms × 60 次 ≈ 12 秒，刚好卡在边缘 ——
                        // 超时后**静默放弃**，桥永远不就绪，所有命令都报
                        // 「桥尚未就绪」，用户看到的是「点什么都没反应」。
                        val delay = 400L + (attempt.toLong() * 50L)
                        wv.postDelayed({
                            attemptInject(attempt + 1, max, onReady)
                        }, delay)
                    } else {
                        // 重试用尽：必须留一条明确的日志，否则这种失败
                        // 在真机上完全无迹可寻（只有「点了没反应」）。
                        Log.e(
                            TAG,
                            "桥注入失败：$max 次重试后 __TAURI_INTERNALS__ 仍未就绪" +
                                "（最后一次结果=$r）。IPC 全部不可用。",
                        )
                    }
                }
            }, 300)
        }
    }

    /**
     * 把桥就绪前排队的调用补发出去。
     *
     * ⚠️ 不能「设完 ready 立刻发」：实测 `INJECT_JS` 返回 ok 的**同一毫秒**里，
     * `window.__QK_BRIDGE__` 仍然求值成 null（V8 对该脚本还没提交完成），
     * 于是补发的第一批命令又全部失败。
     *
     * 这里改成**先用 JS 轮询确认 `__QK_BRIDGE__` 真的存在**，再补发。
     * 轮询本身极轻（一个 typeof 判断），失败则 50ms 后继续，最多 20 次（1 秒）。
     */
    private fun flushQueue() {
        val snapshot: List<PendingCall>
        synchronized(invokeQueue) {
            snapshot = ArrayList(invokeQueue)
            invokeQueue.clear()
        }
        if (snapshot.isEmpty()) return
        val wv = findTauriWebView(activity)
        if (wv == null) {
            snapshot.forEach {
                it.onResult(
                    Result.failure(
                        IllegalStateException(activity.getString(R.string.bridge_not_ready)),
                    ),
                )
            }
            return
        }
        Log.i(TAG, "桥已就绪，等待 __QK_BRIDGE__ 生效后补发 ${snapshot.size} 条排队命令")
        waitBridgeThenFlush(wv, snapshot, 0)
    }

    private fun waitBridgeThenFlush(
        wv: android.webkit.WebView,
        calls: List<PendingCall>,
        attempt: Int,
    ) {
        wv.evaluateJavascript(
            "(typeof window.__QK_BRIDGE__ === 'object' && " +
                "typeof window.__QK_BRIDGE__.invoke === 'function') ? 'yes' : 'no'",
        ) { res ->
            val ok = res?.trim('"') == "yes"
            if (ok) {
                Log.i(TAG, "桥可调用，补发 ${calls.size} 条命令")
                calls.forEach { invoke(it.cmd, it.payload, it.onResult) }
                return@evaluateJavascript
            }
            if (attempt >= 20) {
                Log.e(TAG, "__QK_BRIDGE__ 1 秒内未生效，${calls.size} 条命令判失败")
                calls.forEach {
                    it.onResult(
                        Result.failure(
                            IllegalStateException(
                                activity.getString(R.string.bridge_not_ready),
                            ),
                        ),
                    )
                }
                return@evaluateJavascript
            }
            wv.postDelayed({ waitBridgeThenFlush(wv, calls, attempt + 1) }, 50)
        }
    }

    /**
     * 调用一个 Rust 命令。
     *
     * @param cmd 命令名（与 `#[tauri::command]` 的函数名一致，如 "fetch_news"）
     * @param payload JSON 字符串形式的参数；无参传 "{}"
     */
    fun invoke(cmd: String, payload: String = "{}", onResult: (Result<String>) -> Unit) {
        invokeInternal(cmd, payload, 0, onResult)
    }

    private fun invokeInternal(
        cmd: String,
        payload: String,
        retryCount: Int,
        onResult: (Result<String>) -> Unit,
    ) {
        val wv = findTauriWebView(activity)
        if (wv == null) {
            // ⚠️ 这里是「用着用着突然点什么都没反应」的真正入口：
            // WebView（含渲染进程）被游戏崩溃带走后，每条命令都在这里被判失败。
            // 先尝试自愈（连续命中会重建界面），再如实返回失败。
            noteDeadBridge()
            onResult(Result.failure(IllegalStateException(activity.getString(R.string.bridge_not_ready))))
            return
        }
        // 桥还没就绪：先排队，等 start() 的 onReady 之后统一补发。
        //
        // ⚠️ 原来这里**立刻**回「桥尚未就绪」。而页面一挂载就会并发发出一堆
        // 初始化请求（list_instances / fetch_news / get_settings…），它们全赶在
        // `__QK_BRIDGE__` 生效前的几十毫秒里 —— 实测一次冷启动有 60+ 条这样白白
        // 失败，界面上表现为「实例列表空、新闻空、设置读不出来」，且不会自愈。
        if (!ready) {
            invokeQueue.add(PendingCall(cmd, payload, onResult))
            if (invokeQueue.size == 1) {
                Log.i(TAG, "桥未就绪，命令进入队列：$cmd")
            }
            // 上限保护：桥永远不就绪时不能让队列无限涨
            while (invokeQueue.size > maxQueued) {
                val dropped = invokeQueue.removeAt(0)
                Log.w(TAG, "桥未就绪且队列已满，丢弃：${dropped.cmd}")
                dropped.onResult(
                    Result.failure(
                        IllegalStateException(activity.getString(R.string.bridge_not_ready)),
                    ),
                )
            }
            return
        }

        val id = nextId++
        pending[id] = onResult

        // ⚠️⚠️ 这一条是整块代码里最容易写错的地方（踩了很久）：
        //
        // `__QK_BRIDGE__.invoke(...)` 是**异步**的 —— 它把结果通过
        // `QookiXNative.onResult(id, ...)` 回推给 Kotlin，函数本身**没有返回值**
        // （JS 里是 undefined，`evaluateJavascript` 收到的就是字符串 "null"）。
        //
        // 所以**绝不能用求值结果判断桥是否可用**：那会把每一次正常调用
        // 都误判成「桥不可用」，进而重试 → 同一个回调 resume 两次 →
        //   IllegalStateException: Already resumed, but proposed with update true
        //（实测崩在 ComposeMainActivity.kt:933）。
        //
        // 这里的 JS 只做两件事：确认 `__QK_BRIDGE__` 存在、调用 invoke；
        // 返回值固定为 'sent' / 'no-bridge'，**不表示命令成功与否**。
        val js = "window.__QK_BRIDGE__ ? " +
            "(window.__QK_BRIDGE__.invoke(" +
            "${JSONObject.quote(cmd)}, ${JSONObject.quote(payload)}, $id), 'sent') : 'no-bridge';"

        wv.evaluateJavascript(js) { result ->
            val r = result?.trim('"')
            if (r == "sent") {
                // 正常发出：等 NativeInbox 回调。这里只起一个看门狗，
                // 防止「JS 侧静默失败」导致 pending 永久泄漏、UI 永久转圈。
                //
                // ⚠️ 超时要按命令区分：`install_recommended_plugins` /
                // `install_plugin` / 完整度检查这类命令**本身就要下载几十 MB**，
                // 统一 8 秒会把「正在正常下载」错判成「桥坏了」。
                val timeoutMs = when {
                    // 插件安装：要联网下包，给 5 分钟
                    cmd.contains("plugin", ignoreCase = true) -> 300_000L
                    // ⚠️ 2026-10-20 真机修：**安装 / 补全不设超时**（0 = 永不超时）。
                    //
                    // 这类命令的耗时完全由网络和包大小决定，给多少都可能不够：
                    // 实测 OnePlus 8 上「创建 Fabric 26.3 实例」光下 114 个原版库
                    // 就花了 6 分 9 秒，再叠加拉版本清单 43 秒、客户端 jar、加载器
                    // 7 个库，600 秒照样打满 —— 于是前端报
                    //   「自动安装实例 xxx 失败: 命令超时：install_game」
                    // 而 Rust 侧还在正常下载，用户重试又从头来，永远装不完。
                    //
                    // 这类命令**本来就有自己的进度事件**（install://progress），
                    // 用户能看进度、能取消，不需要靠「前端超时」兜底。
                    // 取消/失败的信号由任务系统负责，比盲猜一个秒数可靠。
                    cmd.contains("install", ignoreCase = true) ||
                        cmd.contains("repair", ignoreCase = true) ||
                        // ⚠️ 2026-10-23 真机修：`import_modpack` 的命令名里**没有** "install"，
                        // 于是掉进了默认 8 秒 —— 实测日志：
                        //   「命令超时无响应：import_modpack（8s）」
                        //   「import_modpack 失败: 命令超时」
                        // 表现就是导入整合包时界面立刻退回、一个文件都没装（用户以为是没反应）。
                        // 导入/导出/下载都耗时且**自带进度事件**，和 install 一样不设超时。
                        cmd.contains("import", ignoreCase = true) ||
                        cmd.contains("modpack", ignoreCase = true) ||
                        cmd.contains("export", ignoreCase = true) ||
                        cmd.contains("download", ignoreCase = true) -> 0L
                    // 完整性检查只遍历本地文件，2 分钟足够
                    cmd.contains("check", ignoreCase = true) -> 120_000L
                    // ⚠️ 2026-10-20 修：**凡是联网拉元数据的命令，8 秒根本不够**。
                    //
                    // 实测（OnePlus 8 / 移动网络）：新建实例页选 Fabric 时报
                    //   「加载器版本获取失败：命令超时：get_loader_versions」
                    // 因为 `get_loader_versions` 不在上面任何一条规则里，掉进了
                    // 默认 8 秒；而它要请求 meta.fabricmc.net（可能还带镜像回退与
                    // 多次重试），弱网下十几秒很常见。用户看到的就是「选不了加载器
                    // 版本」，直接卡在创建实例这一步。
                    //
                    // 同理还有版本清单、加载器列表、整合包元数据这些纯查询命令。
                    //
                    // 90 秒的来历（真机实测）：Fabric 官方 meta 查某些 MC 版本
                    // （26.3）单次就要 30 秒以上，Rust 侧每个候选 URL 允许 45 秒。
                    // 这里必须比它**明显宽**，否则前端先超时、Rust 还在跑，
                    // 用户看到「获取失败」但后台其实成功了。
                    cmd.contains("versions", ignoreCase = true) ||
                        cmd.contains("loader", ignoreCase = true) ||
                        cmd.contains("manifest", ignoreCase = true) ||
                        cmd.contains("meta", ignoreCase = true) ||
                        cmd.contains("news", ignoreCase = true) ||
                        cmd.contains("search", ignoreCase = true) ||
                        cmd.contains("resolve", ignoreCase = true) -> 90_000L
                    else -> 8_000L
                }
                // timeoutMs <= 0 表示「不设超时」：安装/补全这类命令的耗时完全
                // 由网络决定，靠前端猜秒数只会误伤（见上面的注释）。
                // 注意不能真的 `postDelayed(0)` —— 那会立刻触发超时。
                if (timeoutMs > 0L) {
                    wv.postDelayed({
                        val cb = pending.remove(id) ?: return@postDelayed
                        Log.w(TAG, "命令超时无响应：$cmd（${timeoutMs / 1000}s）")
                        activity.runOnUiThread {
                            cb(Result.failure(IllegalStateException("命令超时：$cmd")))
                        }
                    }, timeoutMs)
                }
                return@evaluateJavascript
            }
            // 只有 'no-bridge' 才是真的没注入上：清掉 pending，短暂重试
            if (!pending.containsKey(id)) return@evaluateJavascript
            pending.remove(id)
            if (retryCount < maxInvokeRetry) {
                Log.i(TAG, "桥未注入（$cmd），第 ${retryCount + 1} 次重试")
                wv.postDelayed({
                    invokeInternal(cmd, payload, retryCount + 1, onResult)
                }, 200L * (retryCount + 1))
            } else {
                Log.w(TAG, "桥不可用（重试用尽）：$cmd，求值结果 $r")
                activity.runOnUiThread {
                    onResult(
                        Result.failure(
                            IllegalStateException(activity.getString(R.string.bridge_not_ready)),
                        ),
                    )
                }
            }
        }
    }

    fun destroy() {
        pending.clear()
        // 排队的调用也一并清掉：桥已销毁，它们不可能再有结果
        synchronized(invokeQueue) { invokeQueue.clear() }
        ready = false
        injected = false
    }

    /**
     * 自检：调一个**不依赖网络**的命令，验证通道是否真的通。
     *
     * 为什么需要它：Phase 1 用 `fetch_news` 验证桥，但那需要联网；
     * 网络不通时（实测该机 DNS 被劫持到 198.18.0.15）拿到的失败
     * 分不清是「桥坏了」还是「网断了」。`list_instances` 读本地磁盘，
     * 是干净判据。
     *
     * @return 通过时返回命令的原始 JSON
     */
    fun selfTest(onResult: (Result<String>) -> Unit) {
        invoke("list_instances", "{}") { r ->
            r.fold(
                onSuccess = {
                    Log.i(TAG, "自检通过：list_instances 返回 ${it.length} 字节")
                    onResult(Result.success(it))
                },
                onFailure = {
                    Log.w(TAG, "自检失败：${it.message}")
                    onResult(Result.failure(it))
                },
            )
        }
    }

    /**
     * 探测 IPC 环境是否完整（阶段 5 的前置验证）。
     *
     * 回答「`__TAURI_INTERNALS__` 是 Tauri 注入的还是 Vue 前端创建的」——
     * 这决定阶段 5 能不能删掉 Vue 前端。详见 [IpcProbe] 的类注释。
     */
    fun probeIpcEnvironment(onResult: (complete: Boolean, summary: String) -> Unit = { _, _ -> }) {
        val wv = findTauriWebView(activity)
        if (wv == null) {
            onResult(false, "WebView not found")
            return
        }
        wv.evaluateJavascript(IpcProbe.PROBE_JS) { raw ->
            val (complete, summary, keyLen) = IpcProbe.evaluate(raw ?: "")
            Log.i(TAG, "IPC 环境探测：$summary")
            if (complete) {
                Log.i(TAG, "→ IPC 环境完整（invokeKey 长度 $keyLen）")
                Log.i(TAG, "→ 结论：__TAURI_INTERNALS__ 由 Tauri 注入，换空白页也能用")
            } else {
                Log.w(TAG, "→ IPC 环境不完整，前端可能参与了构建")
            }
            onResult(complete, summary)
        }
    }

    /** JS 侧把 Rust 的回包送进来。 */
    private inner class NativeInbox {
        @JavascriptInterface
        fun onResult(id: Int, ok: Boolean, data: String) {
            val cb = pending.remove(id) ?: return
            activity.runOnUiThread {
                cb(if (ok) Result.success(data) else Result.failure(RuntimeException(data)))
            }
        }

        /**
         * 3D 皮肤预览的**帧推送**通道。
         *
         * ── 为什么加这个（性能）────────────────────────────────────────────
         * 原来取一帧要这样：
         *   evaluateJavascript(snapshotAsync)          ← binder 往返 1
         *   等 POLL_MS(16ms) 后
         *   evaluateJavascript(读 __QK_SKIN3D_RESULT)  ← binder 往返 2
         *   若还没好，再来一轮……
         *
         * 实测每帧多花 **~27ms**（帧总耗时 42ms，而 JS 侧只用了 15ms），
         * 全是轮询与往返的开销。
         *
         * 改成 **JS 主动推**：渲染完直接调这个接口把 data URL 递过来，
         * 零轮询、零额外往返。
         *
         * ⚠️ 它挂在已有的 `QookiXNative` 接口上 —— 不新增 WebView、
         * 不改 Rust 命令表（保持 0 Rust 改动）。
         */
        @JavascriptInterface
        fun onSkinFrame(dataUrl: String) {
            val cb = skinFrameSink ?: return
            activity.runOnUiThread { cb(dataUrl) }
        }

        /**
         * 事件推送入口。
         *
         * Rust 侧 emit 事件时，Tauri 会 eval 调用我们注册的 handler，
         * 那个 handler 在 JS 里把 payload 转发到这里。
         *
         * @param event 事件名（如 "install://progress"）
         * @param payload 事件的 payload 字段（JSON 字符串）
         */
        @JavascriptInterface
        fun onEvent(event: String, payload: String) {
            val handlers = eventHandlers[event] ?: return
            activity.runOnUiThread {
                handlers.toList().forEach { h ->
                    runCatching { h(payload) }
                        .onFailure { Log.w(TAG, "事件 $event 的处理器抛异常", it) }
                }
            }
        }

        @JavascriptInterface
        fun log(msg: String) {
            Log.i(TAG, "[js] $msg")
        }
    }

    /**
     * 订阅一个 Tauri 事件。
     *
     * ── 机制（与 invoke 同一套 IPC）──────────────────────────────────────
     * 前端 `listen()` 走的是 `plugin:event|listen` 命令：
     *     invoke('plugin:event|listen', {
     *         event,                                    // 事件名
     *         target: { kind: 'Any' },                  // 监听范围
     *         handler: transformCallback(handler)       // 回调 id
     *     })
     * 我们照这个形状调一次即可。Rust emit 时会把 payload 交给那个回调，
     * 我们在 JS 侧的回调里转成 `QookiXNative.onEvent(...)`。
     *
     * ⚠️ 与 `invoke` 的差别：`listen` 的返回值是 eventId（用于 unlisten），
     * 而**事件本身会多次推送**，所以不能像 invoke 那样用完即弃 ——
     * 回调要一直留在 Tauri 的注册表里。这里用「一次性注册、长期持有」的做法：
     * 每个事件名只注册一次，多个 Kotlin 处理器共用。
     *
     * @param event 事件名，如 "install://progress"
     * @param onPayload 每次收到事件时的回调（主线程），参数是 payload 的 JSON
     */
    fun listen(event: String, onPayload: (String) -> Unit) {
        eventHandlers.getOrPut(event) { mutableListOf() }.add(onPayload)
        // 阶段 2：同时注册到**静态表** —— Rust 现在优先走 JNI 推事件
        // （`progress::push` → `TauriBridge.onCoreEvent` → `deliverEvent`），
        // 那条路只能看到静态表；旧的 WebView 通道继续用实例表。两套并存，不重不漏
        // （Rust 侧 JNI 成功就直接 return，不会再走 Tauri emit）。
        globalHandlers
            .getOrPut(event) { java.util.Collections.synchronizedList(mutableListOf<(String) -> Unit>()) }
            .add(onPayload)

        // 每个事件名只向 Tauri 注册一次
        if (subscribedEvents.contains(event)) return
        subscribedEvents.add(event)

        val wv = findTauriWebView(activity)
        if (wv == null || !ready) {
            Log.w(TAG, "listen($event)：桥未就绪，稍后重试")
            subscribedEvents.remove(event)
            return
        }

        val js = "window.__QK_BRIDGE__ && window.__QK_BRIDGE__.listen(${JSONObject.quote(event)});"
        wv.evaluateJavascript(js) { r ->
            Log.i(TAG, "订阅 $event → $r")
        }
    }

    /** 取消某个事件的所有 Kotlin 处理器（不向 Tauri unlisten，留给进程结束）。 */
    fun unlisten(event: String) {
        eventHandlers.remove(event)
        // 静态表也要清，否则页面销毁后 JNI 事件仍会投递给已废弃的订阅者
        globalHandlers.remove(event)
    }

    /** 主动 emit 一个事件（自检或 Compose → WebView 通知用）。 */
    fun emit(event: String, payloadJson: String = "null") {
        val wv = findTauriWebView(activity) ?: return
        val js = "window.__QK_BRIDGE__ && window.__QK_BRIDGE__.emit(" +
            "${JSONObject.quote(event)}, ${JSONObject.quote(payloadJson)});"
        wv.evaluateJavascript(js) { }
    }

    /**
     * 事件通道自检：订阅一个测试事件 → emit 它 → 看是否收到。
     *
     * 为什么需要：`install://progress` 这类事件只在**真的下载/安装**时才发，
     * 而当前网络环境下无法发起下载。用自造事件可以独立验证
     * 「订阅注册 → Rust 派发 → JS 转发 → Kotlin 收到」整条回路。
     *
     * @param onResult true = 收到回环事件
     */
    fun selfTestEvents(onResult: (Boolean) -> Unit) {
        val testEvent = "qookix://bridge-selftest"
        var done = false

        listen(testEvent) { payload ->
            if (done) return@listen
            done = true
            Log.i(TAG, "事件自检通过：收到 $testEvent，payload=$payload")
            onResult(true)
        }

        // 稍等片刻让订阅注册到 Tauri，再 emit
        activity.window?.decorView?.postDelayed({
            emit(testEvent, "\"hello\"")
            // 超时兜底
            activity.window?.decorView?.postDelayed({
                if (!done) {
                    Log.w(TAG, "事件自检失败：未收到 $testEvent")
                    onResult(false)
                }
            }, 1500)
        }, 400)
    }

    /**
     * 【调试用】发一个假的下载进度事件。
     *
     * 用途：在没有网络（发不起真下载）的环境下验证
     * 「事件 → TasksRepo → 下载页 UI」整条渲染链路。
     *
     * 只在 debug 构建 + 显式传 intent extra 时被调用，正常使用不会触发。
     * 见 ComposeMainActivity 的 `qk_debug_progress` extra。
     */
    fun debugEmitProgress(taskId: Long, fraction: Double) {
        val total = 100_000_000L
        val done = (total * fraction).toLong()
        val payload = """{"taskId":$taskId,"phase":"download","done":${(fraction * 50).toInt()},"total":50,"bytesDone":$done,"bytesTotal":$total,"current":"libs/client.jar","ok":true}"""
        emit("download://progress", payload)
        Log.i(TAG, "debugEmitProgress: taskId=$taskId fraction=$fraction")
    }

    /**
     * 注入的转发器：把 Kotlin 的调用转成 Tauri 的 ipc()，再把结果转回来。
     */
    /**
     * WebGL 能力探针。
     *
     * 返回形如 `"webgl2:ok webgl1:ok renderer:Adreno (TM) 640 readback:ok"`。
     * 用途见 `attemptInject` 里的调用点注释。
     *
     * `readback` 那一项很关键：3D 预览要把 WebGL 画的东西**取回 Kotlin 侧**
     * 显示在 Compose 里。若 `preserveDrawingBuffer` 不可用，
     * `toDataURL` 会拿到空白 —— 那就得换别的取图方案。
     */
    private val WEBGL_PROBE_JS = """
        (function () {
          function tryCtx(name) {
            try {
              var c = document.createElement('canvas');
              var gl = c.getContext(name);
              return gl ? 'ok' : 'no';
            } catch (e) { return 'err'; }
          }
          var w2 = tryCtx('webgl2');
          var w1 = tryCtx('webgl');
          var rend = '';
          var readback = 'no';
          var rb2d = 'no';
          try {
            var c = document.createElement('canvas');
            c.width = 8; c.height = 8;
            var gl = c.getContext('webgl', { preserveDrawingBuffer: true });
            if (gl) {
              var dbg = gl.getExtension('WEBGL_debug_renderer_info');
              rend = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)
                         : gl.getParameter(gl.RENDERER);
              gl.clearColor(1, 0, 0, 1);
              gl.clear(gl.COLOR_BUFFER_BIT);
              var url = c.toDataURL('image/png');
              // 有数据时长度远大于空图（空 canvas 约 ~200 字符）
              readback = (url && url.length > 500) ? 'ok' : 'empty:' + (url ? url.length : 0);
            }
          } catch (e) { readback = 'err'; }

          // 对照组：普通 2D canvas 的 toDataURL 能不能用？
          // 如果能，说明是"WebGL 特有问题"而不是"toDataURL 被禁用"。
          try {
            var c2 = document.createElement('canvas');
            c2.width = 8; c2.height = 8;
            var ctx = c2.getContext('2d');
            ctx.fillStyle = '#ff0000';
            ctx.fillRect(0, 0, 8, 8);
            var u2 = c2.toDataURL('image/png');
            rb2d = (u2 && u2.length > 500) ? 'ok' : 'empty:' + (u2 ? u2.length : 0);
          } catch (e) { rb2d = 'err'; }

          // 对照组 2：把 canvas **挂进 document** 再读。
          // 离屏（未 attach）的 canvas 在某些 WebView 版本上
          // toDataURL 会返回空白 —— 这决定了 3D 预览的 canvas 要不要真挂上去。
          var rbAttached = 'no';
          try {
            var c3 = document.createElement('canvas');
            c3.width = 8; c3.height = 8;
            c3.style.position = 'fixed';
            c3.style.left = '-9999px';
            c3.style.top = '0';
            document.body.appendChild(c3);
            var ctx3 = c3.getContext('2d');
            ctx3.fillStyle = '#00ff00';
            ctx3.fillRect(0, 0, 8, 8);
            var u3 = c3.toDataURL('image/png');
            rbAttached = (u3 && u3.length > 500) ? 'ok' : 'empty:' + (u3 ? u3.length : 0);
            document.body.removeChild(c3);
          } catch (e) { rbAttached = 'err'; }

          // 对照组 3：gl.readPixels —— 绕开 toDataURL 直接读像素。
          // 若这条能通，3D 预览可以走 readPixels → base64 回传。
          var rbPixels = 'no';
          try {
            var c4 = document.createElement('canvas');
            c4.width = 8; c4.height = 8;
            var gl4 = c4.getContext('webgl', { preserveDrawingBuffer: true });
            if (gl4) {
              gl4.clearColor(0, 0, 1, 1);
              gl4.clear(gl4.COLOR_BUFFER_BIT);
              var px = new Uint8Array(8 * 8 * 4);
              gl4.readPixels(0, 0, 8, 8, gl4.RGBA, gl4.UNSIGNED_BYTE, px);
              // 第 4 个字节是 alpha，第一个是 R。蓝色应该是 0,0,255,255
              var nonzero = 0;
              for (var i = 0; i < px.length; i++) { if (px[i] !== 0) nonzero++; }
              rbPixels = 'ok:nonzero=' + nonzero;
            } else { rbPixels = 'nogl'; }
          } catch (e) { rbPixels = 'err'; }

          // 对照组 4：**真实 3D 渲染**能不能被 readPixels 拿到？
          // 前面只验证了 clear 纯色。这里画一个用顶点着色器输出的三角形，
          // 确认"着色器渲染管线"的结果也能读回 —— 这才是 skinview3d 的实际路径。
          var rbTriangle = 'no';
          try {
            var c5 = document.createElement('canvas');
            c5.width = 64; c5.height = 64;
            var gl5 = c5.getContext('webgl', { preserveDrawingBuffer: true });
            if (gl5) {
              var vs = gl5.createShader(gl5.VERTEX_SHADER);
              gl5.shaderSource(vs, 'attribute vec2 p; void main(){ gl_Position = vec4(p,0.0,1.0); }');
              gl5.compileShader(vs);
              var fs = gl5.createShader(gl5.FRAGMENT_SHADER);
              gl5.shaderSource(fs, 'precision mediump float; void main(){ gl_FragColor = vec4(1.0,0.0,0.0,1.0); }');
              gl5.compileShader(fs);
              var prog = gl5.createProgram();
              gl5.attachShader(prog, vs);
              gl5.attachShader(prog, fs);
              gl5.linkProgram(prog);
              gl5.useProgram(prog);
              // 覆盖全屏的两个三角形
              var buf = gl5.createBuffer();
              gl5.bindBuffer(gl5.ARRAY_BUFFER, buf);
              gl5.bufferData(gl5.ARRAY_BUFFER, new Float32Array([
                -1,-1,  1,-1,  -1,1,
                -1,1,   1,-1,   1,1
              ]), gl5.STATIC_DRAW);
              var loc = gl5.getAttribLocation(prog, 'p');
              gl5.enableVertexAttribArray(loc);
              gl5.vertexAttribPointer(loc, 2, gl5.FLOAT, false, 0, 0);
              gl5.clearColor(0, 0, 0, 1);
              gl5.clear(gl5.COLOR_BUFFER_BIT);
              gl5.drawArrays(gl5.TRIANGLES, 0, 6);
              var px5 = new Uint8Array(64 * 64 * 4);
              gl5.readPixels(0, 0, 64, 64, gl5.RGBA, gl5.UNSIGNED_BYTE, px5);
              // 统计"红色像素"个数：R>200 且 G<50
              var reds = 0;
              for (var i = 0; i < px5.length; i += 4) {
                if (px5[i] > 200 && px5[i+1] < 50) reds++;
              }
              rbTriangle = 'ok:reds=' + reds + '/4096';
            } else { rbTriangle = 'nogl'; }
          } catch (e) { rbTriangle = 'err:' + (e && e.message ? e.message : e); }

          return 'webgl2:' + w2 + ' webgl1:' + w1 +
                 ' renderer:' + rend + ' readback:' + readback +
                 ' rb2d:' + rb2d + ' rbAttached:' + rbAttached +
                 ' rbPixels:' + rbPixels + ' rbTriangle:' + rbTriangle;
        })();
    """.trimIndent()

    private val INJECT_JS = """
        (function () {
          if (window.__QK_BRIDGE__) return 'already';

          var internals = window.__TAURI_INTERNALS__;
          if (!internals) return 'no-internals';

          var invokeKey = '';
          try {
            invokeKey = internals.__TAURI_INVOKE_KEY__
              || (internals.metadata && internals.metadata.currentInvokeKey)
              || '';
          } catch (e) {}

          window.__QK_BRIDGE__ = {
            invoke: function (cmd, payloadJson, id) {
              var payload = {};
              try { payload = JSON.parse(payloadJson || '{}'); } catch (e) {}

              var settled = false;
              var done = function (ok, data) {
                if (settled) return;
                settled = true;
                try {
                  window.$JS_INTERFACE_NAME.onResult(
                    id, ok,
                    JSON.stringify(data === undefined ? null : data)
                  );
                } catch (e) {}
              };

              try {
                var cbId = internals.transformCallback(function (r) { done(true, r); }, true);
                var errId = internals.transformCallback(function (e) { done(false, e); }, true);
                try {
                  internals.ipc({
                    cmd: cmd,
                    callback: cbId,
                    error: errId,
                    payload: payload,
                    options: undefined,
                    __TAURI_INVOKE_KEY__: invokeKey
                  });
                } catch (ipcErr) {
                  try {
                    window.__JS_INTERFACE_NAME.log(
                      'ipc(' + cmd + ') 抛异常: ' + String(ipcErr)
                    );
                  } catch (_) {}
                  done(false, String(ipcErr));
                }
              } catch (e) {
                try {
                  window.__JS_INTERFACE_NAME.log('invoke(' + cmd + ') 准备异常: ' + String(e));
                } catch (_) {}
                done(false, String(e));
              }
            },

            /**
             * 订阅 Tauri 事件。
             *
             * 走 `plugin:event|listen` 命令（与前端 @tauri-apps/api 的
             * listen() 同一条路）。关键是 handler 必须是一个**长期存在**的
             * 回调 —— 事件会多次推送，所以这里不像 invoke 那样用完即弃。
             */
            listen: function (event) {
              try {
                // handler 收到的是 Event 对象 { event, id, payload }
                var handlerId = internals.transformCallback(function (ev) {
                  try {
                    var payload = ev && (ev.payload !== undefined ? ev.payload : ev);
                    window.$JS_INTERFACE_NAME.onEvent(
                      event,
                      JSON.stringify(payload === undefined ? null : payload)
                    );
                  } catch (e) {}
                }, false);   // once = false —— 事件要持续接收

                internals.ipc({
                  cmd: 'plugin:event|listen',
                  callback: internals.transformCallback(function () {}, true),
                  error: internals.transformCallback(function (e) {
                    try { window.$JS_INTERFACE_NAME.log('listen ' + event + ' 失败: ' + e); } catch (_) {}
                  }, true),
                  payload: {
                    event: event,
                    target: { kind: 'Any' },
                    handler: handlerId
                  },
                  options: undefined,
                  __TAURI_INVOKE_KEY__: invokeKey
                });
                return 'ok';
              } catch (e) {
                return String(e);
              }
            },

            /**
             * 主动 emit 一个事件。
             *
             * 用途：
             *   1. 自检 —— emit 一个测试事件，验证「订阅 → 派发 → Kotlin 收到」
             *      整条回路（不依赖网络，也不依赖真的发起一次下载）
             *   2. 将来 Compose 侧要通知 WebView 侧的逻辑时也能用
             */
            emit: function (event, payloadJson) {
              try {
                var payload = null;
                try { payload = JSON.parse(payloadJson || 'null'); } catch (e) {}
                internals.ipc({
                  cmd: 'plugin:event|emit',
                  callback: internals.transformCallback(function () {}, true),
                  error: internals.transformCallback(function () {}, true),
                  payload: { event: event, payload: payload },
                  options: undefined,
                  __TAURI_INVOKE_KEY__: invokeKey
                });
                return 'ok';
              } catch (e) {
                return String(e);
              }
            }
          };

          return 'ok';
        })();
    """.trimIndent()
}


