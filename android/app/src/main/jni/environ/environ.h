//
// Created by maks on 24.09.2022.
//

#ifndef POJAVLAUNCHER_ENVIRON_H
#define POJAVLAUNCHER_ENVIRON_H

#include <ctxbridges/common.h>
#include <stdatomic.h>
#include <jni.h>

/* How many events can be handled at the same time */
#define EVENT_WINDOW_SIZE 8000

typedef struct {
    int type;
    int i1;
    int i2;
    int i3;
    int i4;
} GLFWInputEvent;

typedef void GLFW_invoke_Char_func(void* window, unsigned int codepoint);
typedef void GLFW_invoke_CharMods_func(void* window, unsigned int codepoint, int mods);
typedef void GLFW_invoke_CursorEnter_func(void* window, int entered);
typedef void GLFW_invoke_CursorPos_func(void* window, double xpos, double ypos);
typedef void GLFW_invoke_Key_func(void* window, int key, int scancode, int action, int mods);
typedef void GLFW_invoke_MouseButton_func(void* window, int button, int action, int mods);
typedef void GLFW_invoke_Scroll_func(void* window, double xoffset, double yoffset);

typedef struct  {
    unsigned char buttons [15];
    float axes[6];
} GLFWgamepadstate;

struct pojav_environ_s {
    struct ANativeWindow* pojavWindow;
    basic_render_window_t* mainWindowBundle;
    int config_renderer;
    bool force_vsync;
    atomic_size_t eventCounter; // Count the number of events to be pumped out
    GLFWInputEvent events[EVENT_WINDOW_SIZE];
    size_t outEventIndex; // Point to the current event that has yet to be pumped out to MC
    size_t outTargetIndex; // Point to the newt index to stop by
    size_t inEventIndex; // Point to the next event that has to be filled
    size_t inEventCount; // Count registered right before pumping OUT events. Used as a cache.
    double cursorX, cursorY, cLastX, cLastY;
    jmethodID method_accessAndroidClipboard;
    jmethodID method_onGrabStateChanged;
    jmethodID method_onDirectInputEnable;
    jmethodID method_glftSetWindowAttrib;
    jmethodID method_internalWindowSizeChanged;
    jmethodID method_internalChangeMonitorSize;
    /* SDL/新版 LWJGL 用：DPI 查询 + JVM→启动器的通知通道（见 input_bridge_v3.c） */
    jmethodID method_getAndroidDPI;
    jmethodID method_notifyLauncher;
    jclass bridgeClazz;
    jclass vmGlfwClass;
    jboolean isGrabbing;
    jbyte* keyDownBuffer;
    /**
     * keyDownBuffer 的字节数（2026-10-29 加，外部审计 B26）。
     *
     * 原来 `critical_send_key` 直接 `keyDownBuffer[max(0, key-31)] = action`，
     * 没有任何上界检查；而 key 来自**用户可导入的布局 JSON**（keycode 字段），
     * 一个越界的大数就是一次堆越界写。GLFW 自己的常量是 317 字节
     *（`GLFW.java` 里 allocateDirect(317)），合法 MENU=348 的下标就已经是
     * 317 —— 连合法键码都能踩到边界。
     * 现在在 setup 里存下真实容量，写入前逐次校验。
     */
    jsize keyDownBufferLen;
    jbyte* mouseDownBuffer;
    JavaVM* runtimeJavaVMPtr;
    JNIEnv* glfwThreadVmEnv;
    JavaVM* dalvikJavaVMPtr;
    long showingWindow;
    bool isInputReady, isCursorEntered, isUseStackQueueCall, shouldUpdateMouse;
    bool shouldUpdateMonitorSize, monitorSizeConsumed;
    int savedWidth, savedHeight;
    GLFWgamepadstate gamepadState;
#define ADD_CALLBACK_WWIN(NAME) \
    GLFW_invoke_##NAME##_func* GLFW_invoke_##NAME;
    ADD_CALLBACK_WWIN(Char);
    ADD_CALLBACK_WWIN(CharMods);
    ADD_CALLBACK_WWIN(CursorEnter);
    ADD_CALLBACK_WWIN(CursorPos);
    ADD_CALLBACK_WWIN(Key);
    ADD_CALLBACK_WWIN(MouseButton);
    ADD_CALLBACK_WWIN(Scroll);

#undef ADD_CALLBACK_WWIN
};
extern extern struct pojav_environ_s *pojav_environ;

/* 幂等确保 `pojav_environ` 已初始化，见 environ.c 的说明。 */
void qookix_environ_ensure(void);

#endif //POJAVLAUNCHER_ENVIRON_H
