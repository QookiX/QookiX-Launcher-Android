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

    companion object {
        private const val TAG = "RustBridge"
        private const val JS_INTERFACE_NAME = "QookiXNative"
        /** 与 tauri.conf.json 的 app.windows[0].label 一致。 */
        private const val WEBVIEW_ID = "main"

        /**
         * 从 Activity 的 View 树里找 Rust 创建的那个 WebView。
         *
         * 与 MainActivity.findWebView 同源做法：Rust 的 WebView 由 wry 在
         * onCreate 之后加进 decorView，所以要递归找（且可能要重试 —— 见 start()）。
         */
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
     * @param maxAttempts 最大重试次数（每次间隔 100~200ms）
     * @param onReady 注入成功后的回调（主线程）
     */
    fun start(maxAttempts: Int = 60, onReady: () -> Unit) {
        if (ready) {
            onReady()
            return
        }
        attemptInject(0, maxAttempts, onReady)
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

                        activity.runOnUiThread { onReady() }
                    } else if (attempt < max) {
                        // Tauri 的初始化脚本可能还没跑完，稍后重试
                        wv.postDelayed({
                            attemptInject(attempt + 1, max, onReady)
                        }, 200)
                    }
                }
            }, 300)
        }
    }

    /**
     * 调用一个 Rust 命令。
     *
     * @param cmd 命令名（与 `#[tauri::command]` 的函数名一致，如 "fetch_news"）
     * @param payload JSON 字符串形式的参数；无参传 "{}"
     */
    fun invoke(cmd: String, payload: String = "{}", onResult: (Result<String>) -> Unit) {
        val wv = findTauriWebView(activity)
        if (wv == null || !ready) {
            onResult(Result.failure(IllegalStateException(activity.getString(R.string.bridge_not_ready))))
            return
        }
        val id = nextId++
        pending[id] = onResult

        val js = "window.__QK_BRIDGE__ && window.__QK_BRIDGE__.invoke(" +
            "${JSONObject.quote(cmd)}, ${JSONObject.quote(payload)}, $id);"
        wv.evaluateJavascript(js) { }
    }

    fun destroy() {
        pending.clear()
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
                internals.ipc({
                  cmd: cmd,
                  callback: cbId,
                  error: errId,
                  payload: payload,
                  options: undefined,
                  __TAURI_INVOKE_KEY__: invokeKey
                });
              } catch (e) {
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


