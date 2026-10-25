package net.kdt.pojavlaunch.utils;

import android.view.Surface;

import com.zhayi.qookix.tauri.TauriBridge;

/**
 * Pojav {@code net.kdt.pojavlaunch.utils.JREUtils} 的桥接层（只含控制层需要的方法）。
 *
 * <p>Pojav 原版这个类是 900 多行，负责挑 JRE、拼 JVM 参数、chdir/dlopen 等；
 * QookiX 的 JRE 与 JVM 启动全在 Rust 侧（{@code jvm_launcher.rs} / {@code launch.rs}），
 * 这里只保留「把 Android Surface 交给原生 EGL 桥」这一个入口。
 *
 * <p>原生实现在 {@code libpojavexec.so}（源码 {@code jni/egl_bridge.c}）：
 * <pre>
 *   pojav_environ-&gt;pojavWindow = ANativeWindow_fromSurface(env, surface);
 *   if (br_setup_window != NULL) br_setup_window();
 * </pre>
 * 它是 {@code gl_bridge.c} 里 {@code gl_setup_window()} 的唯一调用者 ——
 * 不调用它，GL4ES 的 EGL surface 切换永远不会发生（画面卡死/黑屏）。
 *
 * <p><b>与 Pojav 原版的差异</b>：原版方法声明为 {@code native setupBridgeWindow(Object)}，
 * 由 JNI 按 {@code Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow} 绑定；
 * QookiX 为了和自研 Rust 桥统一命名，把这个 C 函数改名成了
 * {@code Java_com_zhayi_qookix_tauri_TauriBridge_nativeSetupBridgeWindow}。
 * 故此处改为转发到 {@link TauriBridge}，语义与调用时机完全一致。
 */
public class JREUtils {

    private JREUtils() {
    }

    /**
     * 绑定渲染窗口。每次拿到**新的** Android Surface 都必须调用一次
     * （包括从后台回到前台、SurfaceView 重建时）。
     */
    public static void setupBridgeWindow(Object surface) {
        if (!(surface instanceof Surface)) return;
        TauriBridge.INSTANCE.setupBridgeWindow((Surface) surface);
    }

    /**
     * 释放渲染窗口。
     *
     * <p>Pojav 自己在任何地方都没调用过它（上游遗留），QookiX 同样保留不用：
     * 原生侧是在下一个 Surface 到来时（{@code gl_swap_surface}）先销毁旧的 EGLSurface，
     * 没有新 Surface 时退化成 1×1 pbuffer，而不是释放 ANativeWindow。
     */
    public static native void releaseBridgeWindow();
}
