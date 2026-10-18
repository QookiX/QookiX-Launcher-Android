package com.zhayi.qookix.compose.pages

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import com.zhayi.qookix.compose.bridge.RustBridge
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

    private val webView: WebView? get() = RustBridge.findTauriWebView(activity)

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

    /** 探测宿主是否就绪（异步）。 */
    fun probe(onResult: (Boolean) -> Unit) {
        val wv = webView
        if (wv == null) {
            onResult(false)
            return
        }
        wv.evaluateJavascript("(typeof window.QkSkin3D)") { r ->
            val ok = r?.trim('"') == "object"
            hostReady = ok
            if (!ok) Log.w(TAG, "QkSkin3D 尚未就绪（返回 $r）")
            onResult(ok)
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
     * 等待中的帧回调。
     *
     * 由 [snapshot] 设置，被 `QookiXNative.onSkinFrame`（推送）
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











