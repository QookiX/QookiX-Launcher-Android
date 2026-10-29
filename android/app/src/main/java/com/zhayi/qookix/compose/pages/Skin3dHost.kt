package com.zhayi.qookix.compose.pages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import com.zhayi.qookix.compose.bridge.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import com.zhayi.qookix.R

/**
 * 皮肤 3D 预览的驱动层。
 *
 * ── 它在跟谁说话 ────────────────────────────────────────────────────────
 * 真机/实现细节见 `assets/skin3d/skin3d.js`（宿主）与 `vite.config.ts`
 * 的 `publicDir` 注释。简言之：
 *
 *   旧 UI 用 `skinview3d`（Three.js 封装）做 3D 预览 —— 可旋转、带动画、
 *   经典/纤细模型切换。Compose **没有 3D 引擎**，所以复用**那个本来就有
 *   的隐形 IPC WebView** 做离屏渲染，再用 `gl.readPixels` 把像素读回来。
 *
 * ── 为什么不用 canvas.toDataURL ─────────────────────────────────────────
 * 真机探针实测（OnePlus 8 / Android 16）：
 *     readback:empty:166       ← WebGL canvas 的 toDataURL 空白
 *     rb2d:empty:166           ← 普通 2D canvas **也**空白
 *     rbPixels:ok              ← gl.readPixels 正常
 *     rbTriangle:ok:reds=4096/4096  ← 着色器渲染结果一个像素不差
 * 所以取图只能走 readPixels，`skin3d.js` 里已实现手写 PNG 编码。
 *
 * ── 线程 ────────────────────────────────────────────────────────────────
 * `evaluateJavascript` 必须在主线程调用；回调也在主线程。
 * 本类的方法**都应在主线程调用**（Compose 侧天然满足）。
 */
class Skin3dHost(private val bridge: RustBridge, private val activity: android.app.Activity) {

    companion object {
        private const val TAG = "Skin3dHost"

        /**
         * 预览位图的目标尺寸（等比缩放到这个宽高）。
         *
         * ⚠️⚠️ 2026-10-16 从 480×600 调小 —— **这是个会杀进程的坑**。
         *
         * 回传路径是 `evaluateJavascript` 的回调，**走 binder**。
         * 480×600 的 PNG 编码成 base64 约 **200KB+**，来回两次
         *（传入皮肤 + 传回画面）就触发了 Android 的流量保护：
         *
         *   ActivityManager: Kill app due to repeated failure to freeze binder
         *   Killing (adj 900): excessive binder traffic during cached
         *
         * 表现是"一进皮肤页就退回桌面"，很容易误判成自己的代码崩溃。
         *
         * 256×320 的画面在手机上显示（预览框约 165×192dp）已经足够清晰，
         * 而 base64 体积降到约 1/4。
         *
         * ── 如果将来还要更大 ──────────────────────────────────────────
         * 不要直接调大这两个常量。正解是换取图通道：
         * 让 JS 把 PNG 写进 `__QK_BRIDGE__` 的回调，或用
         * `WebMessagePort` 传字节流 —— 都不走 evaluateJavascript 的
         * 字符串通道。当前尺寸下没必要。
         */
        private const val OUT_W = 192
        private const val OUT_H = 240

        /**
         * **拖动期间**的输出尺寸。
         *
         * 128×160 = 20480 像素，约为全尺寸（46080）的 44%。
         * PNG 编码、base64 体积、Kotlin 侧解码耗时都随像素数线性下降 ——
         * 拖动时优先跟手，松手后再用 [OUT_W]×[OUT_H] 取一张清晰的。
         */
        private const val DRAG_W = 192
        private const val DRAG_H = 240

        /** 轮询间隔（ms）。 */
        /**
 * 轮询间隔。
 *
 * ⚠️ 2026-10-16 从 50ms 降到 16ms（性能）。
 * 50ms 意味着**每帧平均多等 25ms**，在 150ms 节流下不明显，
 * 但想提到更高帧率时它就是天花板。
 * 16ms ≈ 一个 60fps 帧的时间，够密又不会空转太多。
 */
        private const val POLL_MS = 16L

        /**
         * 轮询上限次数。
         *
         * 50ms × 80 = 4 秒。正常渲染几十毫秒就好，4 秒还没结果
         * 说明是真出问题了（宿主没加载 / GL 上下文丢失），
         * 与其无限等下去，不如报错让用户看到。
         */
        private const val MAX_POLLS = 80
    }

    /**
     * 自己建的离屏 WebView（懒加载）。
     *
     * ## 为什么不再借 Tauri 的
     *
     * 原来这里写的是 `RustBridge.findTauriWebView(activity)` —— 那是 Tauri 的
     * 隐形 IPC WebView。但 UI 已经全换成纯 Compose，Tauri 的 Activity/WebView
     * 根本不存在（`findTauriWebView` 现在只剩空实现），于是皮肤页永远报
     * 「WebView 不可用」。
     *
     * ## 为什么要 attach 到窗口
     *
     * WebGL 需要真实的 Surface 才能渲染。**完全离屏**（不 attach）的 WebView
     * 拿不到 GL 上下文，`gl.readPixels` 会返回全零。所以这里建一个 1×1、
     * alpha=0 的 WebView 挂到 decorView 上 —— 看不见，但有 surface。
     */
    private var cachedWv: WebView? = null

    private val webView: WebView? get() = ensureWebView()

    private fun ensureWebView(): WebView? {
        cachedWv?.let { return it }
        return try {
            // ⚠️ 触摸必须**穿透**：这个 WebView 会铺满整屏（为了 60fps 直接显示），
            // 如果它消费触摸，整个界面都点不动了。返回 false 后 decorView 会
            // 继续把事件分发给下层的 ComposeView。
            val wv = object : WebView(activity) {
                override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
            }
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // ⚠️ 走 file:///android_asset 加载，这三项必须开，
                // 否则页面里的 <script src> 会被静默拦掉（表现为 QkSkin3D undefined）。
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = true
            }
            wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 铺满整屏、完全透明：画面位置由 JS 里 canvas 的 transform 决定
            // （见 skin3d.js 的 setDirectDisplay 注释 —— 这样只有 canvas 那一块
            //  有内容，其余透明，不会盖住任何控件）。
            wv.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            (activity.window?.decorView as? android.view.ViewGroup)?.addView(wv)
            wv.loadUrl("file:///android_asset/skin3d/index.html")
            cachedWv = wv
            Log.i(TAG, "已创建全屏透明 WebView，加载 skin3d/index.html")
            wv
        } catch (e: Throwable) {
            Log.w(TAG, "WebView 创建失败", e)
            null
        }
    }

    /**
     * 「直接显示」模式：让 three.js 自己以 60fps 渲染到屏幕上的指定矩形。
     *
     * ## 为什么这是唯一能到 60fps 的路径
     *
     * 默认（compose 模式）是「渲染 → readPixels → PNG → base64 → Kotlin 解码 →
     * Compose 画图」，每一个环节都是固定成本，单帧 40ms 左右，**天花板就在 25fps**，
     * 再怎么优化语法也到不了 60。
     *
     * 直接显示模式里画面**完全不经过 Kotlin**：WebView 自己合成，
     * rAF 驱动 → 60fps。
     *
     * @param visible 是否显示（false = 把 canvas 藏起来、恢复成纯透明）
     * @param x/y/w/h 预览框在**窗口**里的像素矩形
     */
    fun setDirectDisplay(visible: Boolean, x: Int, y: Int, w: Int, h: Int, onResult: (Boolean) -> Unit = {}) {
        val wv = webView
        if (wv == null) {
            onResult(false)
            return
        }
        // 记住最新请求：宿主可能还没加载完（真机日志：WebView 创建后 30ms 就调了
        // setDirectDisplay(false)，那时 QkSkin3D 还是 undefined → 直接失败，
        // 而预览框的位置之后不会再上报，结果「直接显示」永远没生效）。
        lastDirectRect = if (visible) intArrayOf(x, y, w, h) else null
        if (!hostReady) {
            probe { ready ->
                if (ready) replayDirectDisplay()
                onResult(ready)
            }
            return
        }
        applyDirectDisplay(visible, x, y, w, h, onResult)
    }

    /** 最近一次「直接显示」的请求矩形（null = 不显示）。宿主就绪后补发用。 */
    private var lastDirectRect: IntArray? = null

    private fun replayDirectDisplay() {
        val r = lastDirectRect ?: return
        applyDirectDisplay(true, r[0], r[1], r[2], r[3]) {}
    }

    private fun applyDirectDisplay(
        visible: Boolean,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        onResult: (Boolean) -> Unit,
    ) {
        val wv = cachedWv ?: run { onResult(false); return }
        val js = if (visible) {
            "window.QkSkin3D ? window.QkSkin3D.setDirectDisplay(true, $w, $h, $x, $y) : false"
        } else {
            "window.QkSkin3D ? window.QkSkin3D.setDirectDisplay(false, 0, 0, 0, 0) : false"
        }
        wv.evaluateJavascript(js) { r ->
            // ⚠️ 成功时 JS 返回的是**状态串**（"ok canvas=… dpr=… vp=…"），不是 "true"。
            // 按 == "true" 判断会永远认为失败。
            val ok = r?.contains("ok") == true
            if (ok) {
                Log.i(TAG, "setDirectDisplay($visible): ${r?.take(140)}")
            } else {
                Log.w(TAG, "setDirectDisplay($visible) 失败：$r")
            }
            onResult(ok)
        }
    }

    /** 取字符串资源（本类不是 Composable，只能走 Activity）。 */
    private fun getString(resId: Int, vararg args: Any): String = activity.getString(resId, *args)

    /**
     * 当前是否可用（WebView 存在 **且** 宿主脚本已加载）。
     *
     * ⚠️ 不能只看 WebView —— 脚本可能还没跑完。
     * 页面自检日志里的 `skin3dType=object` 是判据。
     */
    private var hostReady = false

    init {
        // 注册"推送帧"接收器 —— 见 [pendingFrame] 与
        // `RustBridge.NativeInbox.onSkinFrame` 的注释。
        // 用推送替代轮询，每帧省约 27ms。
        bridge.setSkinFrameSink { dataUrl ->
            // 与轮询共用同一个闸门（见 claimFrame）—— 两条路径可能并发，
            // 必须原子地"取走回调"，否则会解码两遍、回调两次。
            val cb = claimFrame() ?: return@setSkinFrameSink
            dispatchDecode(dataUrl, cb)
        }
    }

    /**
     * 解码用的后台作用域。
     *
     * 3D 画面以 data URL（base64 PNG）回传，解码是纯 CPU 工作，
     * **不能放主线程** —— 放主线程会让应用 ANR 被杀，
     * 详见 [dispatchDecode] 的注释。
     *
     * 用 SupervisorJob：一次解码失败不该连累后续的渲染请求。
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 探测宿主是否就绪（异步）。
     *
     * ⚠️ 必须**重试**：WebView 是自己新建的，`loadUrl` 之后页面要几十~几百 ms
     * 才真正加载完（skinview3d.bundle.js 有 466KB）。第一次 `evaluateJavascript`
     * 多半得到 "undefined"，直接判定失败的话皮肤页永远是「不可用」。
     *
     * 100ms 一次、最多 20 次（2 秒）。真出问题（脚本报错/资源缺失）也会在
     * 2 秒后明确失败，不会无限转。
     */
    fun probe(onResult: (Boolean) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult(false)
            return
        }
        probeWithRetry(wv, attempt = 0, onResult)
    }

    private fun probeWithRetry(wv: WebView, attempt: Int, onResult: (Boolean) -> Unit) {
        wv.evaluateJavascript("(typeof window.QkSkin3D)") { r ->
            val ok = r?.trim('"') == "object"
            if (ok) {
                hostReady = true
                onResult(true)
                return@evaluateJavascript
            }
            if (attempt < 20) {
                wv.postDelayed({ probeWithRetry(wv, attempt + 1, onResult) }, 100)
            } else {
                hostReady = false
                Log.w(TAG, "QkSkin3D 就绪超时（返回 $r），检查 assets/skin3d 是否打包进 APK")
                onResult(false)
            }
        }
    }

    /**
     * 初始化 3D 视图。
     *
     * @param width/height 离屏渲染尺寸（越大越清晰，但 readPixels + PNG 编码越慢）
     */
    private fun init(onResult: (Boolean) -> Unit) {
        eval("window.QkSkin3D && window.QkSkin3D.init()") { r ->
            onResult(r == "true")
        }
    }

    /**
     * 加载一张皮肤并返回渲染好的位图。
     *
     * @param dataUrl 皮肤 PNG 的 data URL
     * @param slim    true = 纤细模型（Alex），false = 经典（Steve）
     */
    fun loadSkin(
        dataUrl: String,
        slim: Boolean,
        onResult: (Result<Bitmap>) -> Unit,
    ) {
        val wv = webView
        if (wv == null) {
            onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_webview_unavailable))))
            return
        }
        // ⚠️ 先等宿主就绪。
        //
        // WebView 是新建的，`skinview3d.bundle.js` 466KB，加载 + 执行要几百 ms。
        // 真机日志实测：`init()` 返回 false 之后 **1ms** 才打印「宿主已就绪」——
        // init 跑在 `var SK = window.skinview3d` 之前，SK 还是 undefined。
        //
        // probe 内部会重试（100ms × 20），就绪后递归进来一次（那时 hostReady
        // 已为 true，直接走下面的正常流程）。
        if (!hostReady) {
            probe { ready ->
                if (ready) {
                    loadSkin(dataUrl, slim, onResult)
                } else {
                    onResult(
                        Result.failure(
                            IllegalStateException(getString(R.string.skin_3d_init_failed)),
                        ),
                    )
                }
            }
            return
        }
        // data URL 里有大量 base64，用 JSONObject.quote 安全转义
        val quoted = JSONObject.quote(dataUrl)
        val model = if (slim) "slim" else "default"
        // ⚠️ 加载是**异步**的（皮肤纹理要解码 + 上传 GPU），而
        // `evaluateJavascript` **只支持同步返回值** —— 直接让 JS 返回
        // Promise 会拿到 `{}`（真机实测：自检日志打印的就是 `页面自检: {}`）。
        //
        // 所以 JS 把结果写进 `window.__QK_SKIN3D_RESULT`，这里**轮询**取回。
        // 轮询间隔 50ms、上限 4 秒（正常几十毫秒就绪，超时说明真出问题了）。
        val startJs = """
            (function(){
              if (!window.QkSkin3D) { window.__QK_SKIN3D_RESULT = 'nohost'; return false; }
              if (!window.QkSkin3D.init()) { window.__QK_SKIN3D_RESULT = 'noinit'; return false; }
              return window.QkSkin3D.load($quoted, '$model');
            })()
        """.trimIndent()

        pendingFrame = onResult
        wv.evaluateJavascript(startJs) { r ->
            if (r == "false") {
                // 宿主没就绪：直接读它写下的原因
                wv.evaluateJavascript("window.__QK_SKIN3D_RESULT || 'unknown'") { err ->
                    val msg = decodeJsString(err)?.takeIf { it.isNotBlank() && it != "unknown" }
                        ?: getString(R.string.skin_3d_init_failed)
                    Log.w(TAG, "3D 宿主未就绪：$msg")
                    onResult(
                        Result.failure(
                            IllegalStateException(getString(R.string.skin_3d_not_ready, msg)),
                        ),
                    )
                }
                return@evaluateJavascript
            }
            pollResult(0, wv, onResult)
        }
    }

    /**
     * 轮询 `window.__QK_SKIN3D_RESULT` 直到有值。
     *
     * 见 [loadSkin] 里关于"为什么必须轮询"的注释。
     */
    private fun pollResult(
        attempt: Int,
        wvOverride: WebView? = null,
        onResult: (Result<Bitmap>) -> Unit,
    ) {
        val wv = wvOverride ?: webView ?: run {
            onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_webview_unavailable))))
            return
        }
        if (attempt > MAX_POLLS) {
            Log.w(TAG, "等待 3D 渲染超时（${MAX_POLLS * POLL_MS}ms）")
            onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_timeout))))
            return
        }
        wv.postDelayed({
            wv.evaluateJavascript("window.__QK_SKIN3D_RESULT || ''") { raw ->
                val url = decodeJsString(raw)
                when {
                    url.isNullOrBlank() -> pollResult(attempt + 1, wv, onResult)
                    url.startsWith("data:image/") -> {
                        // 推送通道可能已经把这一帧交出去了 —— 只消费一次，
                        // 否则会解码两遍（甚至回调两次导致状态错乱）。
                        val cb = claimFrame() ?: return@evaluateJavascript
                        dispatchDecode(url, cb)
                    }
                    else -> {
                        // 错误也要走闸门 —— 推送通道可能已经把同一份错误
                        // 交出去了，不认领就会回调两次。
                        val cb = claimFrame() ?: return@evaluateJavascript
                        Log.w(TAG, "3D 渲染失败：${url.take(140)}")
                        cb(Result.failure(IllegalStateException(url)))
                    }
                }
            }
        }, POLL_MS)
    }

    /** 只重渲染（不重新加载皮肤）—— 视角/动画/模型变化后调。 */
    fun snapshot(onResult: (Result<Bitmap>) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_webview_unavailable))))
            return
        }
        // ── 取帧：**推送优先**，轮询兜底（性能）────────────────────────
        //
        // 演进过程（每一步都有真机数据支撑）：
        //   ① 手写 PNG（stored 块不压缩）→ 每帧 **427KB**  ── 卡
        //   ② `pixelRatio: 1`            → read 44ms → 17ms
        //   ③ `CompressionStream` 真压缩 → 427KB → 8KB
        //   ④ 渲染尺寸=输出尺寸 192×240   → JS 侧 total 30ms → 15ms
        //   ⑤ **推送替代轮询**（这一步）  → 省掉每帧 ~27ms 的往返
        //
        // ⑤ 的理由：原来"发起 + 隔 POLL_MS 读结果"要两次 binder 往返，
        // 实测每帧多花 ~27ms（帧总耗时 42ms，而 JS 只用了 15ms）。
        // 现在 JS 渲染完直接调 `QookiXNative.onSkinFrame()` 推过来。
        //
        // 兜底：若推送接口不可用（老 WebView / 注入失败），
        // JS 会退回写全局变量，`pollResult` 仍然能取到 —— 功能不会丢。
        pendingFrame = onResult
        wv.evaluateJavascript(
            "window.QkSkin3D ? window.QkSkin3D.snapshotPush($OUT_W, $OUT_H) : 'nohost'"
        ) { r ->
            if (r == "false") {
                pendingFrame = null
                wv.evaluateJavascript("window.__QK_SKIN3D_RESULT || 'unknown'") { err ->
                    val msg = decodeJsString(err)?.takeIf { it.isNotBlank() && it != "unknown" }
                        ?: getString(R.string.skin_3d_init_failed)
                    Log.w(TAG, "snapshotPush 未受理：$msg")
                    onResult(Result.failure(IllegalStateException(msg)))
                }
                return@evaluateJavascript
            }
            // 推送可能已经在回调前就到了；没到则启动轮询兜底
            if (pendingFrame != null) pollResult(0, wv, onResult)
        }
    }

    /**
     * 拖动专用：**旋转 + 取帧一次完成**。
     *
     * 2026-10-29 加（用户报「转起来很卡」）。
     *
     * 原来 [orbit] 和 [snapshot] 是两次独立调用：
     *   eval(orbit) → 回调 → eval(snapshotPush) → 推送
     * 两次 binder 往返 + 两次 JS 调度，拖动时每一帧都要付这个代价。
     *
     * 合并后只剩「发出 → 推送回来」，并且用**更小的输出尺寸**
     * （[DRAG_W]×[DRAG_H]）—— PNG 编码、base64、Kotlin 解码都随像素数线性下降。
     * 松手后再用全尺寸取一帧（[snapshot]）补清晰度。
     */
    /** 拖动帧是否还在飞（用于丢弃积压请求，见 [orbitSnap]）。 */
    private var orbitBusy = false

    fun orbitSnap(dx: Float, dy: Float, onResult: (Result<Bitmap>) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_webview_unavailable))))
            return
        }
        // ⚠️⚠️ 上一帧还没回来就**丢弃**这次（2026-10-29，用户报「转起来很卡」）。
        //
        // 拖动时手指的 MOVE 事件（约 60-120Hz）远快于「渲染一帧」的成本
        //（readPixels + PNG + base64 + 解码，几十 ms）。不丢的话请求会排队，
        // 队列越来越长，越拖越慢 —— 这就是「卡」的主要来源。
        //
        // 丢弃是安全的：下一次 MOVE 会带着**累积位移**再来一次，
        // 视角不会丢（delta 是累加后提交的）。
        if (orbitBusy) return
        orbitBusy = true
        // 所有出口都要复位，否则一次失败会让拖动永久失效
        val wrapped: (Result<Bitmap>) -> Unit = { r ->
            orbitBusy = false
            onResult(r)
        }
        if (!hostReady) {
            probe { ready ->
                if (ready) {
                    orbitBusy = false
                    orbitSnap(dx, dy, onResult)
                } else {
                    orbitBusy = false
                    onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_init_failed))))
                }
            }
            return
        }
        pendingFrame = wrapped
        wv.evaluateJavascript(
            "window.QkSkin3D ? window.QkSkin3D.orbitSnap($dx, $dy, $DRAG_W, $DRAG_H) : 'nohost'"
        ) { r ->
            if (r == "false" || r == "\"nohost\"") {
                pendingFrame = null
                orbitBusy = false
                Log.w(TAG, "orbitSnap 未受理：$r")
                wrapped(Result.failure(IllegalStateException(getString(R.string.skin_3d_init_failed))))
                return@evaluateJavascript
            }
            if (pendingFrame != null) pollResult(0, wv, wrapped)
        }
    }

    /**
     * 等待中的帧回调。
     *
     * 由 [snapshot] / [orbitSnap] 设置，被 `QookiXNative.onSkinFrame`（推送）
     * **或** [pollResult]（兜底）消费 —— 二者只会生效一个。
     */
    private var pendingFrame: ((Result<Bitmap>) -> Unit)? = null

    /**
     * 领取等待中的帧回调（单次闸门）。
     *
     * ⚠️ 为什么需要：一帧有**两条到达路径** ——
     *   ① 推送：JS 渲染完直接调 `QookiXNative.onSkinFrame`
     *   ② 轮询：`pollResult` 读 `window.__QK_SKIN3D_RESULT`
     *
     * JS 侧两条都填（不能只填一条 —— 之前只填推送，而 loadSkin 那条路
     * 是轮询的，结果两边都没收到，卡在「正在渲染 3D…」）。
     * 所以 Kotlin 侧必须保证**只消费一个**，否则会解码两遍、
     * 回调两次导致状态错乱。
     *
     * 用 synchronized：推送来自 JNI 线程、轮询来自主线程，会并发。
     */
    private fun claimFrame(): ((Result<Bitmap>) -> Unit)? = synchronized(this) {
        val cb = pendingFrame
        pendingFrame = null
        cb
    }

    /**
     * 在**后台线程**解码 data URL，再把结果发回主线程。
     *
     * ⚠️⚠️ 2026-10-16 修 —— 这一步不加会**让应用 ANR 被杀**。
     *
     * `evaluateJavascript` 的回调跑在**主线程**。之前在这里直接做
     * `Base64.decode` + `BitmapFactory.decodeByteArray`，几百 KB 的数据
     * 足以让主线程阻塞到超时：
     *
     *   InputDispatcher: Window ... ComposeMainActivity is unresponsive
     *   WindowManager:   ANR in Window{...ComposeMainActivity}.
     *                    Reason: Input dispatching timed out
     *   ActivityTaskManager: Activity destroy timeout
     *
     * 表现是"一进皮肤页就退回桌面"，很容易误判成渲染崩溃。
     *
     * 解码本身是纯 CPU 工作、无共享状态，放后台线程没有副作用；
     * 只有最终的 `onResult` 需要回到主线程（Compose 状态更新要求）。
     */
    private fun dispatchDecode(url: String?, onResult: (Result<Bitmap>) -> Unit) {
        // 诊断：实际回传体积 —— 压缩有没有生效看这一行
        if (url != null && url.startsWith("data:image/")) {
            Log.i(TAG, "回传 ${url.length / 1024}KB（${if (url.length < 60_000) "已压缩" else "未压缩"}）")
        }
        if (url == null || !url.startsWith("data:image/")) {
            onResult(Result.failure(IllegalStateException(getString(R.string.skin_3d_failed))))
            return
        }
        ioScope.launch {
            val t0 = android.os.SystemClock.uptimeMillis()
            val bmp = withContext(Dispatchers.Default) { decodeDataUrl(url) }
            val decodeMs = android.os.SystemClock.uptimeMillis() - t0
            if (decodeMs > 25) Log.i(TAG, "Kotlin 解码 ${decodeMs}ms（${url.length / 1024}KB）")
            withContext(Dispatchers.Main) {
                if (bmp != null) onResult(Result.success(bmp))
                else onResult(
                    Result.failure(IllegalStateException(getString(R.string.skin_3d_decode_failed))),
                )
            }
        }
    }

    /** 切换动画：idle / walk / run / none */
    fun setAnimation(name: String, onResult: (Boolean) -> Unit = {}) {
        eval("window.QkSkin3D && window.QkSkin3D.setAnimation('$name')") { r ->
            onResult(r == "true")
        }
    }

    /** 切换模型：default（经典）/ slim（纤细） */
    fun setModel(slim: Boolean, onResult: (Boolean) -> Unit = {}) {
        val m = if (slim) "slim" else "default"
        eval("window.QkSkin3D && window.QkSkin3D.setModel('$m')") { r ->
            onResult(r == "true")
        }
    }

    /**
     * 拖拽旋转。
     *
     * @param dx 水平位移（像素，正数 = 向右拖）
     * @param dy 垂直位移（像素，正数 = 向下拖）
     */
    fun orbit(dx: Float, dy: Float, onResult: (Boolean) -> Unit = {}) {
        eval("window.QkSkin3D && window.QkSkin3D.orbit($dx, $dy)") { r ->
            onResult(r == "true")
        }
    }

    /**
     * 自动旋转开关。
     *
     * ⚠️⚠️ 2026-10-16 修 —— **skinview3d 自带的 autoRotate 在这个环境里不转**。
     *
     * 它内部依赖 `requestAnimationFrame` 循环：
     * ```js
     * draw() {
     *   let e = this.clock.getDelta();
     *   this.autoRotate && (playerWrapper.rotation.y += e * speed);
     *   this.render();
     *   this.animationID = requestAnimationFrame(() => this.draw());
     * }
     * ```
     * 但我们跑在一个**不可见的 WebView** 里 —— 实测 rAF 不推进，
     * 真机自检 `rotY=0.000`（开了 autoRotate 也纹丝不动）。
     *
     * 所以改成**自己接管**：Kotlin 侧定时调 [stepAutoRotate] + [snapshot]。
     * 不依赖浏览器帧回调，也不依赖 skinview3d 的 clock。
     */
    fun setAutoRotate(on: Boolean, onResult: (Boolean) -> Unit = {}) {
        val v = if (on) "true" else "false"
        eval("window.QkSkin3D && window.QkSkin3D.setAutoRotate($v)") { r ->
            onResult(r == "true")
        }
    }

    /**
     * 推进一步自动旋转（取帧前调用）。
     *
     * @param deg 本次转过的角度（度）；默认 6° ≈ 6.7fps 下约 2.5 秒转一圈
     */
    fun stepAutoRotate(deg: Float = 6f) {
        eval("window.QkSkin3D && window.QkSkin3D.stepAutoRotate($deg)") { }
    }

    /**
     * 读出最近一帧的分段耗时（性能诊断）。
     *
     * 卡顿时用它定位瓶颈在哪：渲染 / 读取 / 编码。
     * 只读一个短字符串，开销可忽略。
     */
    fun logPerf() {
        eval("window.QkSkin3D ? window.QkSkin3D.perf() : 'nohost'") { r ->
            Log.i(TAG, "帧耗时: $r")
        }
    }

    /**
     * 加载/卸下披风。
     *
     * @param dataUrl 披风 PNG 的 data URL；null = 卸下
     */
    fun loadCape(dataUrl: String?, onResult: (Boolean) -> Unit = {}) {
        if (dataUrl == null) {
            eval("window.QkSkin3D && window.QkSkin3D.loadCape(null)") { r ->
                onResult(r == "true")
            }
            return
        }
        val quoted = JSONObject.quote(dataUrl)
        eval("window.QkSkin3D && window.QkSkin3D.loadCape($quoted)") { r ->
            onResult(r == "true")
        }
    }

    /** 设置视角（度）。 */
    fun setView(yawDeg: Float, pitchDeg: Float, onResult: (Boolean) -> Unit = {}) {
        eval("window.QkSkin3D && window.QkSkin3D.setView($yawDeg, $pitchDeg, 0.9)") { r ->
            onResult(r == "true")
        }
    }

    /** 重置视角。 */
    fun reset(onResult: (Boolean) -> Unit = {}) {
        eval("window.QkSkin3D && window.QkSkin3D.reset()") { r ->
            onResult(r == "true")
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private fun eval(js: String, onResult: (String?) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult(null)
            return
        }
        wv.evaluateJavascript(js) { r -> onResult(r) }
    }

    /**
     * `evaluateJavascript` 回调给的是 **JSON 编码的字符串**
     * （带引号 + 转义），这里还原成原始字符串。
     *
     * 直接用 `JSONArray` 包一层再取，比自己写反转义可靠 ——
     * data URL 里可能有 `\/`、`+`、`=` 等需要正确处理的字符。
     */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try {
            org.json.JSONArray("[$raw]").optString(0, "")
        } catch (e: Exception) {
            Log.w(TAG, "解析 JS 返回失败", e)
            null
        }
    }

    /** 解 `data:image/png;base64,xxx` → Bitmap。 */
    private fun decodeDataUrl(url: String): Bitmap? {
        return try {
            val comma = url.indexOf(',')
            if (comma < 0) return null
            val b64 = url.substring(comma + 1)
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "data URL 解码失败", e)
            null
        }
    }
}











