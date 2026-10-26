package com.zhayi.qookix.tauri

import android.util.Log
import android.view.Surface
import com.zhayi.qookix.nativebridge.PojavShim

object TauriBridge {

    private const val TAG = "TauriBridge"
    private var nativeLoaded = false
    private var pojavLoaded = false

    init {
        nativeLoaded = loadCoreByAbsolutePath()
        // 注意：libpojavexec 不在 init 里加载。ART 必须等到 JVM 侧那份被抽出来之后，
        // 用绝对路径加载同一个文件，见 ensurePojavLoaded()。
    }

    /**
     * 按**绝对路径**加载 `libqookix_lib.so`，失败再回退 `loadLibrary`。
     *
     * ## 为什么要这么写（2026-10-25）
     *
     * 摘掉 `tauri` 依赖后，真机报：
     * `No implementation found for ...TauriBridge.nativeInvoke`
     * 但用 `llvm-nm -D` 检查 **APK 里的那份 .so**，符号明明导出了 ✗ ——
     * 说明 ART 真正加载的**不是我们构建的那份**（搜索顺序/路径问题）✗。
     *
     * 这里把 `java.library.path` 每段都试一遍并打日志：
     *   · 能定位到底加载了哪个文件 ✓
     *   · 也顺便绕开 `loadLibrary` 的搜索顺序 ✓
     */
    private fun loadCoreByAbsolutePath(): Boolean {
        val name = System.mapLibraryName("qookix_lib")
        val pathProp = System.getProperty("java.library.path").orEmpty()
        Log.i(TAG, "java.library.path = $pathProp")
        for (dir in pathProp.split(java.io.File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val f = java.io.File(dir, name)
            if (!f.isFile) continue
            try {
                System.load(f.absolutePath)
                Log.i(TAG, "Native library loaded by path: ${f.absolutePath} (${f.length()} B)")
                return true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "load 失败 ${f.absolutePath}: ${e.message}")
            }
        }
        return try {
            System.loadLibrary("qookix_lib")
            Log.i(TAG, "Native library loaded via loadLibrary")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library not available, using Kotlin fallback", e)
            false
        }
    }

    /**
     * 加载 libpojavexec（Pojav 的 JNI 核心：输入 native + Surface→EGL 桥）。
     *
     * 必须从 `<files>/natives/libpojavexec.so` 加载，即 JVM 的 java.library.path 里那一份。
     * 若走 System.loadLibrary("pojavexec")，ART 会从 APK 里加载另一个副本，
     * 两个实例各有一份 `pojav_environ` 全局变量 → ART 设置好的 pojavWindow 在 JVM 侧
     * 仍然是 NULL，GL 初始化直接 SIGSEGV(fault addr 0x0)。
     */
    @Synchronized
    private fun ensurePojavLoaded(): Boolean {
        if (pojavLoaded) return true
        val dir = PojavShim.nativeLibDir()
        val extracted = if (dir != null) java.io.File(dir, "libpojavexec.so") else null

        // 抽取可能正在进行（尤其装包后的第一次启动）：等文件出现且大小稳定再加载。
        // 已经是"老文件"（两秒前就在）时直接跳过等待。
        if (extracted != null) {
            val deadline = System.currentTimeMillis() + 15_000
            var lastSize = -1L
            var stable = 0
            while (System.currentTimeMillis() < deadline) {
                if (!extracted.isFile) {
                    stable = 0
                } else {
                    if (System.currentTimeMillis() - extracted.lastModified() > 2_000) break
                    val size = extracted.length()
                    if (size > 0 && size == lastSize) {
                        if (++stable >= 3) break // 连续 3 次（间隔 250ms）大小一致 → 抽取已完成
                    } else {
                        stable = 0
                    }
                    lastSize = size
                }
                Thread.sleep(250)
            }
        }

        return try {
            if (extracted != null && extracted.isFile) {
                System.load(extracted.absolutePath)
                Log.i(TAG, "libpojavexec loaded from ${extracted.absolutePath}")
            } else {
                // 这里**不能**回退 `System.loadLibrary("pojavexec")`（APK 里的另一份副本）：
                // 两份各自的 pojav_environ 不共享，ART 设置好的 pojavWindow 在 JVM 侧
                // 仍是 NULL，GL 初始化直接 SIGSEGV(fault addr 0x0)。宁可明确报缺库。
                Log.e(TAG, "libpojavexec 缺失：natives 未抽取成功，游戏无法启动")
                return false
            }
            pojavLoaded = true
            true
        } catch (e: Throwable) {
            pojavLoaded = false
            Log.w(TAG, "libpojavexec not available", e)
            false
        }
    }

    fun isNativeAvailable(): Boolean = nativeLoaded

    fun isPojavBridgeAvailable(): Boolean = pojavLoaded

    /**
     * 通用命令入口（阶段 1：绕开 WebView）。
     *
     * 返回 `{"ok":true,"data":…}` 或 `{"ok":false,"error":"…"}`。
     * 现有 Tauri 的 `#[tauri::command]` 只能由隐藏 WebView 里的 `index.html`
     * 触发 —— 那条链一断就是「桥尚未就绪 / 点击没反应」。这个方法**不经过网页**，
     * 直接 JNI 调 Rust 的 `commands::native_invoke`。
     *
     * 目前 Rust 侧已实现：list_instances / get_settings / fetch_news / list_content。
     */
    external fun nativeInvoke(cmd: String, payload: String): String

    // nativeGetVersionList 已删（2026-10-26）：Rust 侧返回的是指针、这里声明的是 String，
    // 接上就崩；版本清单走 `get_version_manifest` 命令。
    external fun nativeLaunchGame(instanceId: String, accountUuid: String): Int
    external fun nativeKillGame(): Int
    external fun nativeGetGameStatus(): String
    external fun nativeSendInput(eventType: Int, data: String)
    external fun nativeSetupSurface(surface: Surface)
    external fun nativeReleaseSurface()

    /** 把 Surface 实际像素尺寸同步给 Rust：启动 JVM 时要用它当游戏窗口尺寸。 */
    external fun nativeSetSurfaceSize(width: Int, height: Int)

    /** 交给 Pojav 的 EGL 桥：保存 ANativeWindow 并初始化渲染后端（libpojavexec）。 */
    external fun nativeSetupBridgeWindow(surface: Surface)

    /**
     * 把当前进程的 JavaVM 与 Activity 实例交给 Rust，供方向锁定 / 文件选择 / 系统代理
     * 等原生能力回调 Kotlin。
     *
     * 传实例而不是类名：原生线程用 FindClass 找不到应用类，在 `-Xcheck:jni` 下会直接崩溃。
     */
    external fun nativeAttach(activity: Any)

    fun attach(activity: Any) {
        if (!nativeLoaded) return
        try {
            nativeAttach(activity)
        } catch (e: Throwable) {
            Log.w(TAG, "nativeAttach failed", e)
        }
    }

    fun launchGame(instanceId: String, accountUuid: String): Int {
        return if (nativeLoaded) {
            nativeLaunchGame(instanceId, accountUuid)
        } else {
            Log.e(TAG, "Native library not loaded, cannot launch game")
            -1
        }
    }

    fun killGame(): Boolean {
        return if (nativeLoaded) {
            nativeKillGame() == 1
        } else {
            false
        }
    }

    /** 当前游戏状态（JSON 字符串）。用于判断「JVM 是否已在后台运行」。 */
    fun gameStatus(): String {
        if (!nativeLoaded) return "{}"
        return try {
            nativeGetGameStatus()
        } catch (e: Throwable) {
            Log.w(TAG, "gameStatus failed", e)
            "{}"
        }
    }

    /** 游戏 JVM 是否还活着（决定重开界面时要不要再启动一次）。 */
    fun isGameRunning(): Boolean = gameStatus().contains("\"is_running\":true")

    fun sendInput(eventType: Int, data: String) {
        if (nativeLoaded) {
            nativeSendInput(eventType, data)
        }
    }

    fun setupSurface(surface: Surface) {
        if (nativeLoaded) {
            nativeSetupSurface(surface)
        }
    }

    fun releaseSurface() {
        if (nativeLoaded) {
            nativeReleaseSurface()
        }
    }

    /**
     * 记录 Surface 尺寸。
     *
     * 必须在启动 JVM **之前**调用：游戏窗口尺寸（`glfwstub.windowWidth/Height`）
     * 与 Surface 像素尺寸不一致时，触摸坐标会整体错位，表现为「画面正常但点不动按钮」。
     */
    fun setSurfaceSize(width: Int, height: Int) {
        if (!nativeLoaded) return
        if (width <= 0 || height <= 0) return
        try {
            nativeSetSurfaceSize(width, height)
        } catch (e: Throwable) {
            Log.w(TAG, "setSurfaceSize failed", e)
        }
    }

    /** 把 Surface 交给 Pojav 的 EGL/GL 桥（libpojavexec），游戏才有真正的绘制目标。 */
    fun setupBridgeWindow(surface: Surface) {
        if (!ensurePojavLoaded()) return
        try {
            nativeSetupBridgeWindow(surface)
            Log.i(TAG, "bridge window set: $surface")
        } catch (e: Throwable) {
            Log.w(TAG, "setupBridgeWindow failed", e)
        }
    }
}
