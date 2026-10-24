package com.zhayi.qookix.compose.bridge

import android.app.Activity
import android.util.Log
import android.webkit.WebView
import com.zhayi.qookix.tauri.TauriBridge
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Compose ↔ Rust 的命令/事件桥（**纯 JNI**）。
 *
 * ## 历史与现状（2026-10-25）
 *
 * 原来这里是「隐藏 WebView + Tauri IPC」的通道：命令要经 `index.html` 里的 JS
 * 送进 Rust，事件要经 wry 的 `emit` 回传。那个网页/渲染进程一挂，整个 App 就
 * 「点了没反应、界面冻住」—— 这是过去几个月最顽固的一类故障 ✗。
 *
 * 现在 WebView 与 tauri 都已从工程里移除：
 *   · 命令 → `TauriBridge.nativeInvoke(cmd, payload)`（JNI 直连 Rust `commands::native_invoke`）
 *   · 事件 → Rust `progress::push` → `TauriBridge.onCoreEvent` → [deliverEvent]
 *
 * 所以本类不再持有任何 WebView 引用，只剩：调用、订阅、就绪探测。
 * 旧的 `findTauriWebView` / `attemptInject` / `IpcProbe` 全部删除
 *（保留 `findTauriWebView` 的空实现，只是为了 `Skin3dHost` 编译通过 ✓）。
 */
class RustBridge(@Suppress("unused") private val activity: Activity?) {

    /** 是否已确认 JNI 通道可用。 */
    val isReady: Boolean get() = ready

    private var ready = false

    /** 皮肤 3D 帧接收端（3D 预览已随 WebView 一起退场，这里仅保留 API）。 */
    private var skinFrameSink: ((String) -> Unit)? = null

    fun setSkinFrameSink(sink: ((String) -> Unit)?) {
        skinFrameSink = sink
    }

    /**
     * 探测 JNI 通道并回调 [onReady]。
     *
     * `maxAttempts` 保留是为了兼容旧调用点（部分页面按「重试 N 次」写法调用）。
     */
    fun start(maxAttempts: Int = 60, onReady: () -> Unit) {
        if (ready) {
            onReady()
            return
        }
        if (jniPing()) {
            markReady(onReady)
            return
        }
        // Rust 侧初始化可能稍晚（首启装插件等），轮询几次再放弃
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var tries = 0
        val task = object : Runnable {
            override fun run() {
                if (jniPing()) {
                    markReady(onReady)
                    return
                }
                if (++tries >= maxAttempts) {
                    Log.w(TAG, "JNI 通道在 $maxAttempts 次尝试后仍不可用")
                    return
                }
                main.postDelayed(this, 300)
            }
        }
        main.postDelayed(task, 300)
    }

    private fun markReady(onReady: () -> Unit) {
        ready = true
        Log.i(TAG, "JNI 直连可用（无需 WebView）→ 桥就绪")
        onReady()
    }

    /** 用一条最轻的命令探测 JNI 通道。 */
    private fun jniPing(): Boolean = try {
        TauriBridge.nativeInvoke("get_pins", "{}").contains("\"ok\":true")
    } catch (t: Throwable) {
        Log.w(TAG, "JNI 探测失败", t)
        false
    }

    /**
     * 调用一条 Rust 命令。
     *
     * **必须在后台线程执行**（真机 ANR 事故 2026-10-25）：部分命令要读磁盘/网络，
     * 而很多调用点原本就在主线程。这里统一丢到工作线程，结果回主线程。
     *
     * 返回值的形状与旧 Tauri 通道一致：裸 JSON（字符串结果带引号），
     * 所以上层调用方一行都不用改 ✓。
     */
    fun invoke(cmd: String, payload: String = "{}", onResult: (Result<String>) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val result = runCatching {
                val raw = TauriBridge.nativeInvoke(cmd, payload)
                val o = JSONObject(raw)
                if (o.optBoolean("ok")) {
                    o.get("data").toString()
                } else {
                    throw IllegalStateException(o.optString("error", "命令执行失败"))
                }
            }
            if (result.isFailure) {
                Log.w(TAG, "$cmd 失败：${result.exceptionOrNull()?.message}")
            }
            main.post { onResult(result) }
        }.start()
    }

    /** 订阅 Rust 事件（走 JNI 静态表，见 companion 的 [deliverEvent]）。 */
    fun listen(event: String, onPayload: (String) -> Unit) {
        globalHandlers
            .getOrPut(event) { java.util.Collections.synchronizedList(mutableListOf()) }
            .add(onPayload)
    }

    fun unlisten(event: String) {
        globalHandlers.remove(event)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun unusedActivityHolder() = activity

    /** 兼容旧调用点：做一次最轻的往返，用于诊断。 */
    fun selfTest(onResult: (Result<String>) -> Unit) {
        invoke("get_settings", "{}", onResult)
    }

    fun destroy() {
        ready = false
        globalHandlers.clear()
    }

    companion object {
        private const val TAG = "RustBridge"

        /**
         * 事件订阅表（静态）：Rust 经 JNI 调 `TauriBridge.onCoreEvent`，
         * 那条路没有 Kotlin 实例上下文，所以订阅必须落在全局表里。
         */
        private val globalHandlers =
            ConcurrentHashMap<String, MutableList<(String) -> Unit>>()

        /**
         * 兼容旧调用点（`Skin3dHost` 在**类名**上调用）：WebView 已经不存在，
         * 永远返回 null —— 3D 皮肤预览据此降级为 2D 提示 ✓。
         */
        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun findTauriWebView(activity: Activity): WebView? = null

        /** Rust 经 JNI 推事件进来 → 切主线程分发给所有订阅者。 */
        @JvmStatic
        fun deliverEvent(event: String, payload: String) {
            val list = globalHandlers[event]?.toList() ?: return
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            for (handler in list) {
                main.post { runCatching { handler(payload) } }
            }
        }
    }
}
