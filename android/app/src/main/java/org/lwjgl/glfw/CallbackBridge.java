package org.lwjgl.glfw;

import com.zhayi.qookix.nativebridge.PojavShim;
import net.kdt.pojavlaunch.*;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepadEnableHandler;

import android.content.*;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;

import org.libsdl.app.SDLActivity;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import dalvik.annotation.optimization.CriticalNative;

public class CallbackBridge {
    public static final Choreographer sChoreographer = Choreographer.getInstance();
    private static boolean isGrabbing = false;
    private static final ArrayList<GrabListener> grabListeners = new ArrayList<>();
    // Use a weak reference here to avoid possibly statically referencing a Context.
    private static @Nullable WeakReference<DirectGamepadEnableHandler> sDirectGamepadEnableHandler;
    
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;
    
    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    // deltaX / deltaY：抓取鼠标时的相对位移（SDL 路径用 delta，GLFW 路径用绝对坐标）。
    // 由 AndroidPointerCapture / InGameEventProcessor 计算后写进来。
    public static float mouseX, mouseY, deltaX, deltaY;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;
    public static boolean sGamepadDirectInput = false;
    /** SDL 的鼠标事件要的是「按键状态位掩码」，不像 GLFW 那样一次一个键。 */
    private static int sMouseButtonState = 0;
    /** 输入链路诊断日志的限流计数器（见 sendCursorPos）。 */
    private static int sDebugTick = 0;

    /**
     * 把输入链路诊断追加到 `files/logs/input_debug.log`。
     *
     * 这台设备的 logcat 会吞掉我们进程的日志（HyperOS），所以临时用文件：
     * 只在 debugInput 标志文件存在时才写，避免常驻开销。
     */
    static void inputDebugLog(String line) {
        if (!sDebugLogOn) return;
        try {
            java.io.File f = new java.io.File(
                    android.os.Environment.getDataDirectory(), "/data/com.zhayi.qookix/files/logs/input_debug.log");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write(line + "\n");
            }
        } catch (Throwable ignored) {
        }
    }
    private static final boolean sDebugLogOn = new java.io.File(
            android.os.Environment.getDataDirectory(), "/data/com.zhayi.qookix/files/logs/input_debug.enabled").exists();

    /** 给别的包（MinecraftGLSurface 等）用的诊断入口。 */
    public static void inputDebugLogPub(String line) {
        inputDebugLog(line);
    }

    public static void putMouseEventWithCoords(int button, float x, float y) {
        putMouseEventWithCoords(button, true, x, y);
        sChoreographer.postFrameCallbackDelayed(l -> putMouseEventWithCoords(button, false, x, y), 33);
    }
    
    public static void putMouseEventWithCoords(int button, boolean isDown, float x, float y /* , int dz, long nanos */) {
        sendCursorPos(x, y);
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }


    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(mouseX, mouseY);
        // SDL 路径：抓取鼠标时用的是**相对位移**（delta），没抓取时才是绝对坐标。
        // GLFW/SDL 的 MOVE 与 HOVER_MOVE 在 SDL 里等价。
        if (!MinecraftGLSurface.sdlEnabled) return;
        if (!isGrabbing) {
            if ((sDebugTick++ % 60) == 0)
                inputDebugLog("ART: sendCursorPos ABS(" + (int) x + "," + (int) y + ") grabbing=false sdl=" + MinecraftGLSurface.sdlEnabled);
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false);
        }
        else {
            if ((sDebugTick++ % 15) == 0)
                inputDebugLog("ART: sendCursorPos REL d(" + (int) deltaX + "," + (int) deltaY + ")");
            SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, deltaX, deltaY, true);
        }
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        // TODO CHECK: This may cause input issue, not receive input!
        if(keycode != 0)  nativeSendKey(keycode,scancode,isDown ? 1 : 0, modifiers);
        if(isDown && keychar != '\u0000') {
            nativeSendCharMods(keychar,modifiers);
            nativeSendChar(keychar);
        }
        if (!MinecraftGLSurface.sdlEnabled) return;
        // 键码要换算成 **Android keycode** 才能喂给 SDL（SDL 收的是 evdev/Android 键码）
        if(isDown){
            SDLActivity.onNativeKeyDown(EfficientAndroidLWJGLKeycode.getAndroidKeycode(keycode));
        } else {
            SDLActivity.onNativeKeyUp(EfficientAndroidLWJGLKeycode.getAndroidKeycode(keycode));
        }
    }

    public static void sendChar(char keychar, int modifiers){
        // 只有 EditText（软键盘）会走到这里，所以允许 emoji，不做 ISOControl 过滤
        nativeSendCharMods(keychar,modifiers);
        nativeSendChar(keychar);
        if (!MinecraftGLSurface.sdlEnabled) return;
        SDLActivity.onNativeKeyDown(EfficientAndroidLWJGLKeycode.getAndroidKeycode(keychar));
        SDLActivity.onNativeKeyUp(EfficientAndroidLWJGLKeycode.getAndroidKeycode(keychar));
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        CallbackBridge.sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        // if (isGrabbing()) DEBUG_STRING.append("MouseGrabStrace: " + android.util.Log.getStackTraceString(new Throwable()) + "\n");
        if ((sDebugTick++ % 10) == 0)
            inputDebugLog("ART: sendMouseButton btn=" + button + " down=" + isDown + " grabbing=" + isGrabbing);
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
        if (!MinecraftGLSurface.sdlEnabled) return;
        int aKey = -1;
        switch (button) {
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT:
                aKey = MotionEvent.BUTTON_PRIMARY;
                break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT:
                aKey = MotionEvent.BUTTON_SECONDARY;
                break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE:
                aKey = MotionEvent.BUTTON_TERTIARY;
                break;
            // 注意：SDL 那边 4/5 是反的（侧键前进/后退），照上游保持一致
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_5:
                aKey = MotionEvent.BUTTON_BACK;
                break;
            case LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_4:
                aKey = MotionEvent.BUTTON_FORWARD;
                break;
        }
        // SDL 要的是「当前全部按键状态位掩码」，而且这里的语义是**抬起后**的状态，
        // 所以得自己维护一份，不能直接用当前这次事件的 button。
        if (aKey != -1) {
            if (isDown) {
                sMouseButtonState |= aKey;
            } else {
                sMouseButtonState &= ~aKey;
            }
            SDLActivity.onNativeMouse(sMouseButtonState, isDown ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP, mouseX, mouseY, false);
        }
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }
    
    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
        if (!MinecraftGLSurface.sdlEnabled) return;
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, (float) xoffset, (float) yoffset, false);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        // Avoid going through the JNI each time.
        return isGrabbing;
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        switch (type) {
            case CLIPBOARD_COPY:
                PojavShim.setClipboard(copy);
                return null;

            case CLIPBOARD_PASTE:
                return PojavShim.getClipboard();

            case CLIPBOARD_OPEN:
                PojavShim.openLink(copy);
                return null;
            default: return null;
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    // SDL 集成（MC 26.3+：窗口层从 GLFW 换成 SDL3）
    //
    // 链路：游戏 JVM 里 LWJGL 的 SDL 绑定初始化 → CallbackBridge.nativeNotifyLauncher
    // （libpojavexec.so，见 jni/input_bridge_v3.c）→ 跨 VM 回调到这里 →
    // 在 dalvik 侧把 SDL3 的 Java 胶水准备好、并把 MinecraftGLSurface.sdlEnabled 置位。
    // 之后所有输入事件都会同时喂给 GLFW 路径和 SDL 路径（见各 send* 方法）。
    // ════════════════════════════════════════════════════════════════════════

    /** 通知类型：SDL 相关。 */
    public static final int NOTIF_TYPE_SDL = 0;
    /** SDL 动作：初始化启动器侧的 SDL 集成。 */
    public static final int ACTION_INIT_LAUNCHER_INTEGRATION = 0;
    /** SDL 动作：把文本框位置交给 SDL（当前未使用，保留与上游一致）。 */
    public static final int ACTION_SEND_TEXTBOX_RECT = 1;

    /**
     * JVM 侧（LWJGL/SDL）→ 启动器（dalvik 侧）的通知入口。
     * 由 `libpojavexec.so` 通过 JNI 调用（对应 nativeNotifyLauncher）。
     */
    @SuppressWarnings("unused")
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        switch (type) {
            case NOTIF_TYPE_SDL:
                if (action.length > 0 && action[0] == ACTION_INIT_LAUNCHER_INTEGRATION) {
                    try {
                        // 有一部分模组会跳过加载，这里自己 load 一次（幂等）。
                        System.loadLibrary("SDL3");
                        org.libsdl.app.SDL.setupJNI();
                        // 必须在游戏调用 SDL_Init 之前：我们不走 SDL_main，
                        // 少了这步 SDL_Init 会直接失败（26.3 实测）。
                        nativeSDLSetMainReady();
                        onDirectInputEnable();
                        MinecraftGLSurface.sdlEnabled = true;
                        // 关键：Surface 在游戏启动时就已经创建，而 SDL 直到现在才启用 ——
                        // 把已有 Surface 补给 SDL，否则它内部拿到空 surface 直接 SIGSEGV
                        // （实测 tombstone 栈全在 libSDL3.so 里，fault addr 0x0）。
                        MinecraftGLSurface.pushSurfaceToSDL();
                        if (SDLActivity.getSDLSurface() != null) {
                            SDLActivity.getSDLSurface().nativeResize(windowWidth, windowHeight);
                        }
                        Log.i("CallbackBridge", "SDL 支持已启用");
                        return true;
                    } catch (Throwable t) {
                        Log.e("CallbackBridge", "SDL 初始化失败", t);
                        return false;
                    }
                }
                break;
            default:
                break;
        }
        return false;
    }

    /** 供 fork 的 GLFW 实现 glfwGetWindowContentScale 使用（imgui-java 等会调）。 */
    @SuppressWarnings("unused")
    @Keep
    private static float getAndroidDPI() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        metrics.setToDefaults();
        // 分辨率被按 scale 缩放过，密度也要同比缩放
        return metrics.density * net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_SCALE_FACTOR;
    }

    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        } if (holdingCapslock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        } if (holdingCtrl) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        } if (holdingNumlock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        } if (holdingShift) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown){
        switch (keyCode){
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
        }
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        Log.i("CallbackBridge", "onDirectInputEnable()");
        DirectGamepadEnableHandler enableHandler = PojavShim.weakRef(sDirectGamepadEnableHandler);
        if(enableHandler != null) enableHandler.onDirectGamepadEnabled();
        sGamepadDirectInput = true;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        inputDebugLog("ART: onGrabStateChanged(" + grabbing + ")");
        sChoreographer.postFrameCallbackDelayed((time) -> {
            // If the grab re-changed, skip notify process
            if(isGrabbing != grabbing) return;

            System.out.println("Grab changed : " + grabbing);
            synchronized (grabListeners) {
                for (GrabListener g : grabListeners) g.onGrabState(grabbing);
            }

        }, 16);

    }
    public static void addGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            listener.onGrabState(isGrabbing);
            grabListeners.add(listener);
        }
    }
    public static void removeGrabListener(GrabListener listener) {
        synchronized (grabListeners) {
            grabListeners.remove(listener);
        }
    }

    public static FloatBuffer createGamepadAxisBuffer() {
        ByteBuffer axisByteBuffer = nativeCreateGamepadAxisBuffer();
        // NOTE: hardcoded order (also in jre_lwjgl3glfw CallbackBridge)
        return axisByteBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }

    public static void setDirectGamepadEnableHandler(DirectGamepadEnableHandler h) {
        sDirectGamepadEnableHandler = new WeakReference<>(h);
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    // private static native void nativeSendCursorEnter(int entered);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    public static native void nativeSetWindowAttrib(int attrib, int value);
    /**
     * 调 SDL3 的 `SDL_SetMainReady()`（实现在 libpojavexec.so）。
     * 我们不用 SDL_main，所以必须在游戏 `SDL_Init` 之前调用，否则 SDL 初始化必失败。
     */
    public static native void nativeSDLSetMainReady();
    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();
    static {
        System.loadLibrary("pojavexec");
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        sGamepadAxisBuffer = createGamepadAxisBuffer();
    }
}

