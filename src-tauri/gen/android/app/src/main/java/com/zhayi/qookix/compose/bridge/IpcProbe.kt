package com.zhayi.qookix.compose.bridge

import android.util.Log

/**
 * IPC 环境来源探针。
 *
 * ── 要回答的问题（阶段 5 的前置验证）────────────────────────────────────
 * 阶段 5 的目标是删掉 WebView 加载的 Vue 前端（`src/` 那 42 个文件）。
 * 但桥依赖 `__TAURI_INTERNALS__`，所以必须先确认：
 *
 *   **这个对象是「Tauri 注入的」还是「Vue 前端脚本创建的」？**
 *
 *   - 若是 Tauri 注入（Rust 侧 `initialization_script`）→ 换成一个空白页也能工作 → 可以删前端
 *   - 若是 Vue 创建 → 删了前端就断桥 → 不能删
 *
 * ── 静态分析的结论 ────────────────────────────────────────────────────
 * 读 tauri 源码得到：
 * ```
 * // tauri/src/manager/webview.rs:182
 * all_initialization_scripts.push(main_frame_script(self.invoke_initialization_script.clone()));
 * ```
 * 这行**与加载什么页面无关** —— 它是 WebView 创建时注入的。
 * 且 `invoke_initialization_script` 是 `app.rs` 在启动时生成的一个字符串。
 *
 * `assets/index.html` 里只有 `<script type="module" src="/assets/index-*.js">`
 * （Vue 入口），没有任何创建 `__TAURI_INTERNALS__` 的代码。
 *
 * **静态分析指向「Tauri 注入」**，但这类问题必须实测 ——
 * 本项目已经多次遇到「读源码以为对、实测不一样」。
 *
 * ── 本探针的做法 ──────────────────────────────────────────────────────
 * 在真实 WebView 里检查 `window.__TAURI_INTERNALS__` 的关键字段，
 * 判断它是完整可用的 IPC 环境，还是只是前端留下的壳子。
 */
object IpcProbe {

    private const val TAG = "IpcProbe"

    /**
     * 探测脚本。
     *
     * ── 第一版判据错了（2026-10-13 实测修正）────────────────────────────
     * 我最初用「`__TAURI_INVOKE_KEY__` 有没有值」当作环境完整的标志，
     * 实测结果是 `invokeKeyLen=0`，但同一时刻 `list_instances` **正常返回 579 字节**。
     *
     * 读 tauri 源码找到原因（`scripts/ipc-protocol.js` 第 8-12 行）：
     * ```js
     * // This is declared outside the `window.__TAURI_INVOKE__` definition to prevent
     * // the key from being leaked by `window.__TAURI_INVOKE__.toString()`.
     * const __TAURI_INVOKE_KEY__ = __TEMPLATE_invoke_key__
     * ```
     * —— 这个 key 是**闭包内的局部常量**，故意不挂到 window 上（防泄漏）。
     * 所以从外部**根本读不到**，拿它当判据必然误判。
     *
     * ── 正确的判据 ────────────────────────────────────────────────────────
     * 检查那两个由注入脚本 `Object.defineProperty` 上去的函数是否存在：
     *   - `window.__TAURI_INTERNALS__.postMessage` ← ipc-protocol.js 注入
     *   - `window.__TAURI_INTERNALS__.runCallback` ← core.js 注入
     * 它们**只可能来自 Tauri 的注入脚本**，前端页面无法伪造
     * （即便伪造也没有用 —— 真正发命令走的是 Rust 侧的 IPC 表）。
     *
     * 再加上 `window.ipc.postMessage`（wry 的 Java 桥，由 `addJavascriptInterface`
     * 注入），三者齐备就说明 IPC 环境是完整的、与页面内容无关。
     */
    val PROBE_JS: String = """
        (function () {
          function has(o, k) { try { return o && typeof o[k] !== 'undefined'; } catch (e) { return false; } }
          var i = window.__TAURI_INTERNALS__;
          var out = {
            exists: !!i,
            hasIpc: has(i, 'ipc'),
            hasTransformCallback: has(i, 'transformCallback'),
            hasPostMessage: has(i, 'postMessage'),
            hasRunCallback: has(i, 'runCallback'),
            hasWryIpc: has(window.ipc, 'postMessage'),
            hasConvertFileSrc: has(i, 'convertFileSrc')
          };
          return JSON.stringify(out);
        })();
    """.trimIndent()

    /**
     * 从 JS 返回的探测结果里判断「IPC 环境是否完整」。
     *
     * @param json `PROBE_JS` 的返回值
     * @return 三元组 (是否完整, 摘要文本, 关键项计数)
     */
    fun evaluate(json: String): Triple<Boolean, String, Int> {
        return try {
            val cleaned = json.trim().removeSurrounding("\"")
                .replace("\\\"", "\"")
            val o = org.json.JSONObject(cleaned)
            val exists = o.optBoolean("exists", false)
            val hasIpc = o.optBoolean("hasIpc", false)
            val hasCb = o.optBoolean("hasTransformCallback", false)
            val hasPost = o.optBoolean("hasPostMessage", false)
            val hasRunCb = o.optBoolean("hasRunCallback", false)
            val hasWry = o.optBoolean("hasWryIpc", false)

            // 完整 = 存在 + 五个由注入脚本提供的函数都在。
            // 其中 postMessage / runCallback / window.ipc.postMessage
            // 是「Tauri 注入」的强证据（前端页面无法提供）。
            val checks = listOf(hasIpc, hasCb, hasPost, hasRunCb, hasWry)
            val okCount = checks.count { it }
            val complete = exists && okCount == checks.size

            val summary = buildString {
                append("exists=$exists ipc=$hasIpc transformCallback=$hasCb ")
                append("postMessage=$hasPost runCallback=$hasRunCb ")
                append("window.ipc=$hasWry  ")
                append("($okCount/5)")
            }
            Triple(complete, summary, okCount)
        } catch (e: Exception) {
            Log.w(TAG, "解析探测结果失败: $json", e)
            Triple(false, "parse failed: ${e.message}", 0)
        }
    }
}

