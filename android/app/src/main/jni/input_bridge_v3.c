/*
 * V3 input bridge implementation.
 *
 * Status:
 * - Active development
 * - Works with some bugs:
 *  + Modded versions gives broken stuff..
 *
 * 
 * - Implements glfwSetCursorPos() to handle grab camera pos correctly.
 */
 
#include <assert.h>
#include <dlfcn.h>
#include <jni.h>
#include <libgen.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <math.h>

#define TAG __FILE_NAME__
#include "log.h"
#include "utils.h"
#include "environ/environ.h"
#include "jvm_hooks/jvm_hooks.h"

#define EVENT_TYPE_CHAR 1000
#define EVENT_TYPE_CHAR_MODS 1001
#define EVENT_TYPE_CURSOR_ENTER 1002
#define EVENT_TYPE_KEY 1005
#define EVENT_TYPE_MOUSE_BUTTON 1006
#define EVENT_TYPE_SCROLL 1007

#define TRY_ATTACH_ENV(env_name, vm, error_message, then) JNIEnv* env_name;\
do {                                                                       \
    env_name = get_attached_env(vm);                                       \
    if(env_name == NULL) {                                                 \
        printf(error_message);                                             \
        then                                                               \
    }                                                                      \
} while(0)

static void registerFunctions(JNIEnv *env);

/* 取 GLFW 的类 / 方法 / 静态字段句柄（游戏 JVM 侧）。幂等。
 *
 * 两个入口都会调它，因为不同 fork 的加载时机不同：
 *   - JNI_OnLoad 的 JVM 分支：老 fork（LWJGL 3.3.x）此时 GLFW 已随 classpath 就绪，
 *     老代码就是在这里取好的 —— 少了它，游戏一进入窗口尺寸/事件泵就空指针崩
 *     （libpojavexec 里 SIGSEGV，实测 1.21.1 会直接闪退）；
 *   - `GLFW.<clinit>` 回调的 nativeInitializeGLFWNativeBridge：3.4.1 fork 的 GLFW
 *     加载得晚，JNI_OnLoad 时可能还没就绪，这里再补一次（两次都调也无害）。
 */
static void init_glfw_bridge_env(JNIEnv *vmEnv) {
    if (pojav_environ->vmGlfwClass != NULL) {
        pojav_environ->glfwThreadVmEnv = vmEnv;
        return;
    }
    jclass cls = (*vmEnv)->FindClass(vmEnv, "org/lwjgl/glfw/GLFW");
    if (cls == NULL) {
        // 类还没被加载（3.4.1 的常见情况）：清掉未决异常，等静态初始化时再补
        (*vmEnv)->ExceptionClear(vmEnv);
        LOGI("GLFW 尚未加载，稍后由 nativeInitializeGLFWNativeBridge 补齐");
        return;
    }
    pojav_environ->glfwThreadVmEnv = vmEnv;
    pojav_environ->vmGlfwClass = (*vmEnv)->NewGlobalRef(vmEnv, cls);
    pojav_environ->method_glftSetWindowAttrib = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "glfwSetWindowAttrib", "(JII)V");
    pojav_environ->method_internalWindowSizeChanged = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "internalWindowSizeChanged", "(J)V");
    pojav_environ->method_internalChangeMonitorSize = (*vmEnv)->GetStaticMethodID(vmEnv, pojav_environ->vmGlfwClass, "internalChangeMonitorSize", "(II)V");
    jfieldID field_keyDownBuffer = (*vmEnv)->GetStaticFieldID(vmEnv, pojav_environ->vmGlfwClass, "keyDownBuffer", "Ljava/nio/ByteBuffer;");
    jobject keyDownBufferJ = (*vmEnv)->GetStaticObjectField(vmEnv, pojav_environ->vmGlfwClass, field_keyDownBuffer);
    pojav_environ->keyDownBuffer = (*vmEnv)->GetDirectBufferAddress(vmEnv, keyDownBufferJ);
    jfieldID field_mouseDownBuffer = (*vmEnv)->GetStaticFieldID(vmEnv, pojav_environ->vmGlfwClass, "mouseDownBuffer", "Ljava/nio/ByteBuffer;");
    jobject mouseDownBufferJ = (*vmEnv)->GetStaticObjectField(vmEnv, pojav_environ->vmGlfwClass, field_mouseDownBuffer);
    pojav_environ->mouseDownBuffer = (*vmEnv)->GetDirectBufferAddress(vmEnv, mouseDownBufferJ);
    LOGI("GLFW native bridge 就绪（vmGlfwClass=%p）", pojav_environ->vmGlfwClass);
}

/* 实现在文件后面的 SDL 段，这里先声明：JNI_OnLoad 的游戏 VM 分支要用它。 */
static void qookix_sdl_set_main_ready(void);
static void qookix_sdl_call_jni_onload(JavaVM *vm);

/* 游戏 VM 里要显式注册的那些 JNI 实现（定义在本文件后面）。 */
JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv*, jclass, jint, jbyteArray);
JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(JNIEnv*, jclass, jboolean);
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(JNIEnv*, jclass, jboolean);
JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(JNIEnv*, jclass);
JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetAndroidDPI(JNIEnv*, jclass);
JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeNotifyLauncher(JNIEnv*, jclass, jint, jintArray);
JNIEXPORT jobject JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv*, jclass);
JNIEXPORT jobject JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv*, jclass);
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nativeInitializeGLFWNativeBridge(JNIEnv*, jclass);

/*
 * ===== 26.3：在**游戏 VM** 里显式注册 libpojavexec 提供的 native =====
 *
 * 起因：libpojavexec 早被 ART 侧加载过（启动器自身要用它），于是游戏 VM 里
 * `System.loadLibrary("pojavexec")` 只会「发现进程里已有」直接返回，
 * **不会把这些 native 注册到游戏侧的类加载器**。结果是 fork 的
 * `org.lwjgl.glfw.CallbackBridge` / `GLFW` 里声明的 native 全部 UnsatisfiedLinkError ——
 * 26.3 实测：`CallbackBridge.nativeGetAndroidDPI()` 让 `GLFW.<clinit>` 失败，
 * 进而 `GL.createCapabilities` → `NoClassDefFoundError: Could not initialize class GLFW`。
 *
 * 用游戏 VM 的 env 显式 RegisterNatives 一次即可。表里只放我们确实实现了的
 * （fork 还声明了 `nativeSendData`，我们没实现，就不列）。
 */
static void qookix_register_game_vm_natives(JNIEnv *env) {
    static const JNINativeMethod bridge_methods[] = {
            {"nativeClipboard", "(I[B)Ljava/lang/String;",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard},
            {"nativeSetInputReady", "(Z)Z",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady},
            {"nativeSetGrabbing", "(Z)V",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing},
            {"nativeEnableGamepadDirectInput", "()Z",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput},
            {"nativeGetAndroidDPI", "()F",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeGetAndroidDPI},
            {"nativeNotifyLauncher", "(I[I)Z",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeNotifyLauncher},
            {"nativeCreateGamepadButtonBuffer", "()Ljava/nio/ByteBuffer;",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer},
            {"nativeCreateGamepadAxisBuffer", "()Ljava/nio/ByteBuffer;",
             (void *) Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer},
    };

    jclass bridge = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if (bridge == NULL) {
        LOGE("游戏 VM 注册：找不到 org/lwjgl/glfw/CallbackBridge");
        (*env)->ExceptionClear(env);
    } else {
        jint rc = (*env)->RegisterNatives(env, bridge, (JNINativeMethod *) bridge_methods,
                                          (jint) (sizeof(bridge_methods) / sizeof(bridge_methods[0])));
        if (rc != 0) {
            LOGE("游戏 VM 注册 CallbackBridge 的 native 失败");
            (*env)->ExceptionClear(env);
        } else {
            LOGI("游戏 VM 已注册 CallbackBridge 的 %d 个 native",
                 (int) (sizeof(bridge_methods) / sizeof(bridge_methods[0])));
        }
    }

    /* GLFW 的桥函数：这一刻它可能还没被加载，那样就留给 init_glfw_bridge_env 补。 */
    jclass glfw = (*env)->FindClass(env, "org/lwjgl/glfw/GLFW");
    if (glfw == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    static const JNINativeMethod glfw_methods[] = {
            {"nativeInitializeGLFWNativeBridge", "()V",
             (void *) Java_org_lwjgl_glfw_GLFW_nativeInitializeGLFWNativeBridge},
    };
    if ((*env)->RegisterNatives(env, glfw, (JNINativeMethod *) glfw_methods, 1) == 0) {
        LOGI("游戏 VM 已注册 GLFW.nativeInitializeGLFWNativeBridge");
    } else {
        (*env)->ExceptionClear(env);
    }
}

jint JNI_OnLoad(JavaVM* vm, __attribute__((unused)) void* reserved) {
    if (pojav_environ == NULL) {
        /* 兜底：正常由 environ.c 的构造函数在 dlopen 时初始化（见那里的说明）。
           实测出现过它为 NULL 的情况 —— 那样下面第一行解引用就会空指针崩
           （tombstone: fault addr 0x0，pc 落在 JNI_OnLoad），所以先补一次。 */
        qookix_environ_ensure();
        if (pojav_environ == NULL) {
            LOGE("pojav_environ 仍为 NULL，JNI_OnLoad 提前返回（避免空指针崩溃）");
            return JNI_VERSION_1_4;
        }
    }
    if (pojav_environ->dalvikJavaVMPtr == NULL) {
        LOGI("Saving DVM environ...");
        //Save dalvik global JavaVM pointer
        pojav_environ->dalvikJavaVMPtr = vm;
        JNIEnv *dvEnv;
        (*vm)->GetEnv(vm, (void**) &dvEnv, JNI_VERSION_1_4);
        pojav_environ->bridgeClazz = (*dvEnv)->NewGlobalRef(dvEnv,(*dvEnv) ->FindClass(dvEnv,"org/lwjgl/glfw/CallbackBridge"));
        pojav_environ->method_accessAndroidClipboard = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "accessAndroidClipboard", "(ILjava/lang/String;)Ljava/lang/String;");
        pojav_environ->method_onGrabStateChanged = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "onGrabStateChanged", "(Z)V");
        pojav_environ->method_onDirectInputEnable = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "onDirectInputEnable", "()V");
        // SDL/新版 LWJGL：DPI 查询 + JVM→启动器通知（对应的 Java 方法见 CallbackBridge）
        pojav_environ->method_getAndroidDPI = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "getAndroidDPI", "()F");
        pojav_environ->method_notifyLauncher = (*dvEnv)->GetStaticMethodID(dvEnv, pojav_environ->bridgeClazz, "notifyLauncher", "(I[I)Z");
        pojav_environ->isUseStackQueueCall = JNI_FALSE;
    } else if (pojav_environ->dalvikJavaVMPtr != vm) {
        LOGI("Saving JVM environ...");
        pojav_environ->runtimeJavaVMPtr = vm;
        JNIEnv *vmEnv;
        (*vm)->GetEnv(vm, (void**) &vmEnv, JNI_VERSION_1_4);
        /* 26.3：SDL_Init 之前必须先 SetMainReady，见 qookix_sdl_set_main_ready 的说明。
           这一刻（liblwjgl 正在加载）远早于游戏初始化渲染后端，是游戏 VM 里最早的时机。 */
        qookix_sdl_call_jni_onload(vm);
        qookix_sdl_set_main_ready();
        /* 游戏 VM 的 native 必须显式注册一次，见 qookix_register_game_vm_natives 的说明。 */
        qookix_register_game_vm_natives(vmEnv);
        // 老 fork（3.3.x）此时 GLFW 已就绪，这里取好句柄；3.4.1 可能还没加载，
        // 会安静跳过、由 nativeInitializeGLFWNativeBridge 稍后补齐（见函数说明）。
        init_glfw_bridge_env(vmEnv);
        hookExec(vmEnv);
        installLwjglDlopenHook(vmEnv);
        installEMUIIteratorMititgation(vmEnv);
    }

    if(pojav_environ->dalvikJavaVMPtr == vm) {
        //perform in all DVM instances, not only during first ever set up
        JNIEnv *env;
        (*vm)->GetEnv(vm, (void**) &env, JNI_VERSION_1_4);
        registerFunctions(env);
    }
    pojav_environ->isGrabbing = JNI_FALSE;
    
    return JNI_VERSION_1_4;
}

/* GLFW 静态初始化（`GLFW.<clinit>`）时由 JVM 侧回调。
 *
 * LWJGL 3.4.x 的 fork 把它做成一个 native 方法，而不是在 JNI_OnLoad 里一次性取好 ——
 * 因为 GLFW 类可能比 libpojavexec 晚得多才被加载。此刻 GLFW 必然已加载，
 * 取类 / 方法 / 静态 ByteBuffer 字段才安全。
 * 缺了它，游戏一进入 GL/GLFW 初始化就 `UnsatisfiedLinkError`（26.3 实测）。
 */
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nativeInitializeGLFWNativeBridge(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    if (pojav_environ == NULL || pojav_environ->runtimeJavaVMPtr == NULL) {
        LOGE("GLFW native bridge: 游戏 JVM 尚未记录，跳过初始化");
        return;
    }
    JNIEnv *vmEnv;
    (*pojav_environ->runtimeJavaVMPtr)->GetEnv(pojav_environ->runtimeJavaVMPtr, (void**) &vmEnv, JNI_VERSION_1_4);
    init_glfw_bridge_env(vmEnv);
}

#define ADD_CALLBACK_WWIN(NAME) \
JNIEXPORT jlong JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSet##NAME##Callback(JNIEnv * env, jclass cls, jlong window, jlong callbackptr) { \
    void** oldCallback = (void**) &pojav_environ->GLFW_invoke_##NAME; \
    pojav_environ->GLFW_invoke_##NAME = (GLFW_invoke_##NAME##_func*) (uintptr_t) callbackptr; \
    return (jlong) (uintptr_t) *oldCallback; \
}

ADD_CALLBACK_WWIN(Char)
ADD_CALLBACK_WWIN(CharMods)
ADD_CALLBACK_WWIN(CursorEnter)
ADD_CALLBACK_WWIN(CursorPos)
ADD_CALLBACK_WWIN(Key)
ADD_CALLBACK_WWIN(MouseButton)
ADD_CALLBACK_WWIN(Scroll)

#undef ADD_CALLBACK_WWIN

void updateMonitorSize(int width, int height) {
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalChangeMonitorSize, width, height);
}
void updateWindowSize(void* window) {
    (*pojav_environ->glfwThreadVmEnv)->CallStaticVoidMethod(pojav_environ->glfwThreadVmEnv, pojav_environ->vmGlfwClass, pojav_environ->method_internalWindowSizeChanged, (jlong)window);
}

void pojavPumpEvents(void* window) {
    if(pojav_environ->shouldUpdateMouse) {
        pojav_environ->GLFW_invoke_CursorPos(window, floor(pojav_environ->cursorX),
                                             floor(pojav_environ->cursorY));
    }
    if(pojav_environ->shouldUpdateMonitorSize) {
        updateWindowSize(window);
    }

    size_t index = pojav_environ->outEventIndex;
    size_t targetIndex = pojav_environ->outTargetIndex;

    while (targetIndex != index) {
        GLFWInputEvent event = pojav_environ->events[index];
        switch (event.type) {
            case EVENT_TYPE_CHAR:
                if(pojav_environ->GLFW_invoke_Char) pojav_environ->GLFW_invoke_Char(window, event.i1);
                break;
            case EVENT_TYPE_CHAR_MODS:
                if(pojav_environ->GLFW_invoke_CharMods) pojav_environ->GLFW_invoke_CharMods(window, event.i1, event.i2);
                break;
            case EVENT_TYPE_KEY:
                if(pojav_environ->GLFW_invoke_Key) pojav_environ->GLFW_invoke_Key(window, event.i1, event.i2, event.i3, event.i4);
                break;
            case EVENT_TYPE_MOUSE_BUTTON:
                if(pojav_environ->GLFW_invoke_MouseButton) pojav_environ->GLFW_invoke_MouseButton(window, event.i1, event.i2, event.i3);
                break;
            // 光标进出窗口：SDL 一系的输入桥会用它（旧代码漏了这条，事件被静默丢弃）
            case EVENT_TYPE_CURSOR_ENTER:
                if(pojav_environ->GLFW_invoke_CursorEnter) pojav_environ->GLFW_invoke_CursorEnter(window, event.i1);
                break;
            case EVENT_TYPE_SCROLL:
                if(pojav_environ->GLFW_invoke_Scroll) pojav_environ->GLFW_invoke_Scroll(window, event.i1, event.i2);
                break;
        }

        index++;
        if (index >= EVENT_WINDOW_SIZE)
            index -= EVENT_WINDOW_SIZE;
    }

    // The out target index is updated by the rewinder
}

/** Prepare the library for sending out callbacks to all windows */
void pojavStartPumping() {
    size_t counter = atomic_load_explicit(&pojav_environ->eventCounter, memory_order_acquire);
    size_t index = pojav_environ->outEventIndex;

    unsigned targetIndex = index + counter;
    if (targetIndex >= EVENT_WINDOW_SIZE)
        targetIndex -= EVENT_WINDOW_SIZE;

    // Only accessed by one unique thread, no need for atomic store
    pojav_environ->inEventCount = counter;
    pojav_environ->outTargetIndex = targetIndex;

    //PumpEvents is called for every window, so this logic should be there in order to correctly distribute events to all windows.
    if((pojav_environ->cLastX != pojav_environ->cursorX || pojav_environ->cLastY != pojav_environ->cursorY) && pojav_environ->GLFW_invoke_CursorPos) {
        pojav_environ->cLastX = pojav_environ->cursorX;
        pojav_environ->cLastY = pojav_environ->cursorY;
        pojav_environ->shouldUpdateMouse = true;
    }
    if(pojav_environ->shouldUpdateMonitorSize) {
        // Perform a monitor size update here to avoid doing it on every single window
        updateMonitorSize(pojav_environ->savedWidth, pojav_environ->savedHeight);
        // Mark the monitor size as consumed (since GLFW was made aware of it)
        pojav_environ->monitorSizeConsumed = true;
    }
}

/** Prepare the library for the next round of new events */
void pojavStopPumping() {
    pojav_environ->outEventIndex = pojav_environ->outTargetIndex;

    // New events may have arrived while pumping, so remove only the difference before the start and end of execution
    atomic_fetch_sub_explicit(&pojav_environ->eventCounter, pojav_environ->inEventCount, memory_order_acquire);
    // Make sure the next frame won't send mouse or monitor updates if it's unnecessary
    pojav_environ->shouldUpdateMouse = false;
    // Only reset the update flag if the monitor size was consumed by pojavStartPumping. This
    // will delay the update to next frame if it had occured between pojavStartPumping and pojavStopPumping,
    // but it's better than not having it apply at all
    if(pojav_environ->shouldUpdateMonitorSize && pojav_environ->monitorSizeConsumed) {
        pojav_environ->shouldUpdateMonitorSize = false;
        pojav_environ->monitorSizeConsumed = false;
    }

}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPos(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jobject xpos,
                                          jobject ypos) {
    *(double*)(*env)->GetDirectBufferAddress(env, xpos) = pojav_environ->cursorX;
    *(double*)(*env)->GetDirectBufferAddress(env, ypos) = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(__attribute__((unused)) jlong window, jint lengthx, jdouble* xpos, jint lengthy, jdouble* ypos) {
    *xpos = pojav_environ->cursorX;
    *ypos = pojav_environ->cursorY;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_nglfwGetCursorPosA(JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window,
                                            jdoubleArray xpos, jdoubleArray ypos) {
    (*env)->SetDoubleArrayRegion(env, xpos, 0,1, &pojav_environ->cursorX);
    (*env)->SetDoubleArrayRegion(env, ypos, 0,1, &pojav_environ->cursorY);
}

JNIEXPORT void JNICALL JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) jlong window, jdouble xpos,
                                                                         jdouble ypos) {
    pojav_environ->cLastX = pojav_environ->cursorX = xpos;
    pojav_environ->cLastY = pojav_environ->cursorY = ypos;
}

JNIEXPORT void JNICALL
Java_org_lwjgl_glfw_GLFW_glfwSetCursorPos(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, __attribute__((unused)) jlong window, jdouble xpos,
                                          jdouble ypos) {
    JavaCritical_org_lwjgl_glfw_GLFW_glfwSetCursorPos(window, xpos, ypos);
}



void sendData(int type, int i1, int i2, int i3, int i4) {
    GLFWInputEvent *event = &pojav_environ->events[pojav_environ->inEventIndex];
    event->type = type;
    event->i1 = i1;
    event->i2 = i2;
    event->i3 = i3;
    event->i4 = i4;

    if (++pojav_environ->inEventIndex >= EVENT_WINDOW_SIZE)
        pojav_environ->inEventIndex -= EVENT_WINDOW_SIZE;

    atomic_fetch_add_explicit(&pojav_environ->eventCounter, 1, memory_order_acquire);
}

void critical_set_stackqueue(jboolean use_input_stack_queue) {
    pojav_environ->isUseStackQueueCall = (int) use_input_stack_queue;
}

void noncritical_set_stackqueue(__attribute__((unused)) JNIEnv *env, __attribute__((unused)) jclass clazz, jboolean use_input_stack_queue) {
    critical_set_stackqueue(use_input_stack_queue);
}

JNIEXPORT jstring JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeClipboard(JNIEnv* env, __attribute__((unused)) jclass clazz, jint action, jbyteArray copySrc) {
#ifdef DEBUG
    LOGD("Debug: Clipboard access is going on\n", pojav_environ->isUseStackQueueCall);
#endif

    JNIEnv *dalvikEnv;
    (*pojav_environ->dalvikJavaVMPtr)->AttachCurrentThread(pojav_environ->dalvikJavaVMPtr, &dalvikEnv, NULL);
    assert(dalvikEnv != NULL);
    assert(pojav_environ->bridgeClazz != NULL);

    LOGD("Clipboard: Converting string\n");
    char *copySrcC;
    jstring copyDst = NULL;
    if (copySrc) {
        copySrcC = (char *)((*env)->GetByteArrayElements(env, copySrc, NULL));
        copyDst = (*dalvikEnv)->NewStringUTF(dalvikEnv, copySrcC);
    }

    LOGD("Clipboard: Calling 2nd\n");
    jstring pasteDst = convertStringJVM(dalvikEnv, env, (jstring) (*dalvikEnv)->CallStaticObjectMethod(dalvikEnv, pojav_environ->bridgeClazz, pojav_environ->method_accessAndroidClipboard, action, copyDst));

    if (copySrc) {
        (*dalvikEnv)->DeleteLocalRef(dalvikEnv, copyDst);
        (*env)->ReleaseByteArrayElements(env, copySrc, (jbyte *)copySrcC, 0);
    }
    (*pojav_environ->dalvikJavaVMPtr)->DetachCurrentThread(pojav_environ->dalvikJavaVMPtr);
    return pasteDst;
}

JNIEXPORT jboolean JNICALL JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(jboolean inputReady) {
#ifdef DEBUG
    LOGD("Debug: Changing input state, isReady=%d, pojav_environ->isUseStackQueueCall=%d\n", inputReady, pojav_environ->isUseStackQueueCall);
#endif
    LOGI("Input ready: %i", inputReady);
    pojav_environ->isInputReady = inputReady;
    return pojav_environ->isUseStackQueueCall;
}

JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean inputReady) {
    return JavaCritical_org_lwjgl_glfw_CallbackBridge_nativeSetInputReady(inputReady);
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetGrabbing(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jboolean grabbing) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeSetGrabbing failed!\n", return;);
    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz, pojav_environ->method_onGrabStateChanged, grabbing);
    pojav_environ->isGrabbing = grabbing;
}

JNIEXPORT jboolean JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeEnableGamepadDirectInput(__attribute__((unused)) JNIEnv *env, __attribute__((unused))  jclass clazz) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeEnableGamepadDirectInput failed!\n", return JNI_FALSE;);
    (*dvm_env)->CallStaticVoidMethod(dvm_env, pojav_environ->bridgeClazz, pojav_environ->method_onDirectInputEnable);
    return JNI_TRUE;
}

/* 供 fork 的 GLFW 实现 glfwGetWindowContentScale 使用（imgui-java 等会调）。 */
JNIEXPORT jfloat JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeGetAndroidDPI(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "getAndroidDPI failed!\n", return 0.0f;);
    jfloat result = (*dvm_env)->CallStaticFloatMethod(dvm_env, pojav_environ->bridgeClazz,
                                                      pojav_environ->method_getAndroidDPI);
    return result;
}

/* JVM 侧（LWJGL/SDL 集成）→ 启动器（dalvik 侧）的通知通道。
 * SDL 版本就是靠它在 SDL_Init 时把启动器侧的 SDL 支持打开（见 CallbackBridge.notifyLauncher）。 */
JNIEXPORT jboolean JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeNotifyLauncher(JNIEnv* env, __attribute__((unused)) jclass clazz, jint type, jintArray action) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "nativeNotifyLauncher failed!\n", return JNI_FALSE;);
    jboolean result = (*dvm_env)->CallStaticBooleanMethod(dvm_env, pojav_environ->bridgeClazz,
                                                      pojav_environ->method_notifyLauncher, type, convertIntArrayJVM(env, dvm_env, action));
    return result;
}

/* 越权调用 SDL3 的 `SDL_SetMainReady`。
 *
 * SDL3 规定：**不走 SDL_main 的应用**（我们用 Java 的 main）必须在 `SDL_Init` 前
 * 调用它，否则 SDL_Init 会以
 * `Unable to initialize SDL: Application didn't initialize properly, did you include SDL_main.h`
 * 失败 —— MC 26.3 一进 `RenderSystem.initBackendSystem` 就卡在这。
 *
 * **关键：必须对「游戏真正用的那一份」调用。** SDL 的全局状态是按「加载的映射」隔离的：
 * ART 侧从 APK 里 loadLibrary("SDL3") 是一份，游戏 VM 里 LWJGL 从组件插件目录加载的是
 * **另一份**（26.3 实测：`Loaded from java.library.path: …/qookix-components-lwjgl341/…/libs/libSDL3.so`）。
 * 只对 ART 那份调 SetMainReady，游戏侧 SDL_Init 照样报上面那句错。
 *
 * 所以这里优先用 `POJAVEXEC_SDL3`（启动器给的**绝对路径**，即 LWJGL 会加载的同一文件）：
 * 同一个文件 → 同一个映射 → 调用才有效。没有该变量时退回按库名 RTLD_NOLOAD 的老行为。
 *
 * 调用时机：`JNI_OnLoad` 的游戏 VM 分支（那时 liblwjgl 正在加载，远早于 SDL_Init）。
 */
static void qookix_sdl_set_main_ready(void) {
    const char *override_path = getenv("POJAVEXEC_SDL3");
    void *handle = NULL;
    if (override_path != NULL && override_path[0] != '\0') {
        handle = dlopen(override_path, RTLD_LAZY);
        if (handle == NULL) {
            LOGE("SDL_SetMainReady: dlopen(%s) 失败: %s", override_path, dlerror());
        }
    }
    if (handle == NULL) {
        /* 退回老行为：库已由 Java 侧 System.loadLibrary("SDL3") 加载，按名字取句柄。 */
        handle = dlopen("libSDL3.so", RTLD_NOLOAD | RTLD_LAZY);
    }
    if (handle == NULL) {
        LOGI("SDL_SetMainReady: libSDL3.so 尚未加载，跳过");
        return;
    }
    void (*set_main_ready)(void) = (void (*)(void)) dlsym(handle, "SDL_SetMainReady");
    if (set_main_ready == NULL) {
        LOGE("SDL_SetMainReady: 找不到 SDL_SetMainReady");
        return;
    }
    set_main_ready();
    LOGI("SDL_SetMainReady 已调用（%s）", (override_path != NULL && override_path[0] != '\0') ? override_path : "libSDL3.so");
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSDLSetMainReady(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz) {
    qookix_sdl_set_main_ready();
}

/* 替 SDL3 调一次它的 `JNI_OnLoad`。
 *
 * 为什么需要：SDL3 的安卓后端把 `JavaVM` 记在 `JNI_OnLoad` 里（SDL 的常规做法）。
 * 但我们是 **LWJGL 加载 SDL3**（`org.lwjgl.librarypath` 那套 dlopen），JVM 的
 * `System.loadLibrary` 流程不参与，于是 `JNI_OnLoad` 没人调 → SDL 后来自己去找时
 * 直接报 `E/SDL: Failed, there is no JavaVM`，紧接着在建窗口时空指针崩
 * （26.3 实测：tombstone 全在 libSDL3.so，PC 落在 dynapi 跳转表那条链上）。
 *
 * 我们本来就按绝对路径 dlopen 过同一份库（见 qookix_sdl_set_main_ready），
 * 在这里补一次调用即可 —— 同一个文件、同一个映射，SDL 拿到的是游戏 VM。
 */
static void qookix_sdl_call_jni_onload(JavaVM *vm) {
    const char *path = getenv("POJAVEXEC_SDL3");
    if (path == NULL || path[0] == '\0') {
        return; /* 非 26.3 路径 */
    }
    void *handle = dlopen(path, RTLD_LAZY);
    if (handle == NULL) {
        LOGE("SDL3 JNI_OnLoad: dlopen(%s) 失败: %s", path, dlerror());
        return;
    }
    jint (*on_load)(JavaVM *, void *) = (jint (*)(JavaVM *, void *)) dlsym(handle, "JNI_OnLoad");
    if (on_load == NULL) {
        LOGE("SDL3 JNI_OnLoad: 库里没有该符号（版本不符？）");
        return;
    }
    jint version = on_load(vm, NULL);
    LOGI("已替 SDL3 调用 JNI_OnLoad（返回 0x%x）", (unsigned) version);

    /* 诊断：SDL 之后要用 FindClass + RegisterNatives 绑它自己的 Java 胶水类
       （logcat 里那几条 `Failed to register methods of org/libsdl/app/SDLActivity`）。
       这里用同一个线程先探一下，判断是「类在游戏 VM 里根本不可见」还是「SDL 那边的
       线程/上下文不对」。 */
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_4) == JNI_OK && env != NULL) {
        jclass probe = (*env)->FindClass(env, "org/libsdl/app/SDLActivity");
        if (probe != NULL) {
            LOGI("诊断：游戏 VM 里能找到 org/libsdl/app/SDLActivity ✓");
            (*env)->DeleteLocalRef(env, probe);
        } else {
            jthrowable ex = (*env)->ExceptionOccurred(env);
            LOGE("诊断：游戏 VM 里找不到 org/libsdl/app/SDLActivity（%p）", (void *) ex);
            (*env)->ExceptionClear(env);
        }
    }
}

jboolean critical_send_char(jchar codepoint) {
    if (pojav_environ->GLFW_invoke_Char && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR, codepoint, 0, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Char((void*) pojav_environ->showingWindow, (unsigned int) codepoint);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint) {
    return critical_send_char(codepoint);
}

jboolean critical_send_char_mods(jchar codepoint, jint mods) {
    if (pojav_environ->GLFW_invoke_CharMods && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_CHAR_MODS, (int) codepoint, mods, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_CharMods((void*) pojav_environ->showingWindow, codepoint, mods);
        }
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

jboolean noncritical_send_char_mods(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jchar codepoint, jint mods) {
    return critical_send_char_mods(codepoint, mods);
}
/*
JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSendCursorEnter(JNIEnv* env, jclass clazz, jint entered) {
    if (pojav_environ->GLFW_invoke_CursorEnter && pojav_environ->isInputReady) {
        pojav_environ->GLFW_invoke_CursorEnter(pojav_environ->showingWindow, entered);
    }
}
*/

void critical_send_cursor_pos(jfloat x, jfloat y) {
    if (pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady) {
#ifdef DEBUG
        LOGD("pojav_environ->GLFW_invoke_CursorPos && pojav_environ->isInputReady \n");
#endif
        if (!pojav_environ->isCursorEntered) {
            if (pojav_environ->GLFW_invoke_CursorEnter) {
                pojav_environ->isCursorEntered = true;
                if (pojav_environ->isUseStackQueueCall) {
                    sendData(EVENT_TYPE_CURSOR_ENTER, 1, 0, 0, 0);
                } else {
                    pojav_environ->GLFW_invoke_CursorEnter((void*) pojav_environ->showingWindow, 1);
                }
            } else if (pojav_environ->isGrabbing) {
                // Some Minecraft versions does not use GLFWCursorEnterCallback
                // This is a smart check, as Minecraft will not in grab mode if already not.
                pojav_environ->isCursorEntered = true;
            }
        }

        if (!pojav_environ->isUseStackQueueCall) {
            pojav_environ->GLFW_invoke_CursorPos((void*) pojav_environ->showingWindow, (double) (x), (double) (y));
        } else {
            pojav_environ->cursorX = x;
            pojav_environ->cursorY = y;
        }
    }
}

void noncritical_send_cursor_pos(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz,  jfloat x, jfloat y) {
    critical_send_cursor_pos(x, y);
}
#define max(a,b) \
   ({ __typeof__ (a) _a = (a); \
       __typeof__ (b) _b = (b); \
     _a > _b ? _a : _b; })
void critical_send_key(jint key, jint scancode, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_Key && pojav_environ->isInputReady) {
        pojav_environ->keyDownBuffer[max(0, key-31)] = (jbyte) action;
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_KEY, key, scancode, action, mods);
        } else {
            pojav_environ->GLFW_invoke_Key((void*) pojav_environ->showingWindow, key, scancode, action, mods);
        }
    }
}
void noncritical_send_key(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint key, jint scancode, jint action, jint mods) {
    critical_send_key(key, scancode, action, mods);
}

void critical_send_mouse_button(jint button, jint action, jint mods) {
    if (pojav_environ->GLFW_invoke_MouseButton && pojav_environ->isInputReady) {
        pojav_environ->mouseDownBuffer[max(0, button)] = (jbyte) action;
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_MOUSE_BUTTON, button, action, mods, 0);
        } else {
            pojav_environ->GLFW_invoke_MouseButton((void*) pojav_environ->showingWindow, button, action, mods);
        }
    }
}

void noncritical_send_mouse_button(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint button, jint action, jint mods) {
    critical_send_mouse_button(button, action, mods);
}

void critical_send_screen_size(jint width, jint height) {
    pojav_environ->savedWidth = width;
    pojav_environ->savedHeight = height;
    // Even if there was call to pojavStartPumping that consumed the size, this call
    // might happen right after it (or right before pojavStopPumping)
    // So unmark the size as "consumed"
    pojav_environ->monitorSizeConsumed = false;
    pojav_environ->shouldUpdateMonitorSize = true;
    // Don't use the direct updates  for screen dimensions.
    // This is done to ensure that we have predictable conditions to correctly call
    // updateMonitorSize() and updateWindowSize() while on the render thread with an attached
    // JNIEnv.
}

void noncritical_send_screen_size(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint width, jint height) {
    critical_send_screen_size(width, height);
}

void critical_send_scroll(jdouble xoffset, jdouble yoffset) {
    if (pojav_environ->GLFW_invoke_Scroll && pojav_environ->isInputReady) {
        if (pojav_environ->isUseStackQueueCall) {
            sendData(EVENT_TYPE_SCROLL, (int)xoffset, (int)yoffset, 0, 0);
        } else {
            pojav_environ->GLFW_invoke_Scroll((void*) pojav_environ->showingWindow, (double) xoffset, (double) yoffset);
        }
    }
}

void noncritical_send_scroll(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jdouble xoffset, jdouble yoffset) {
    critical_send_scroll(xoffset, yoffset);
}


JNIEXPORT void JNICALL Java_org_lwjgl_glfw_GLFW_nglfwSetShowingWindow(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jlong window) {
    pojav_environ->showingWindow = (jlong) window;
}

JNIEXPORT void JNICALL Java_org_lwjgl_glfw_CallbackBridge_nativeSetWindowAttrib(__attribute__((unused)) JNIEnv* env, __attribute__((unused)) jclass clazz, jint attrib, jint value) {
    // Check for stack queue no longer necessary here as the JVM crash's origin is resolved
    if (!pojav_environ->showingWindow) {
        // If the window is not shown, there is nothing to do yet.
        return;
    }

    // We cannot use pojav_environ->runtimeJNIEnvPtr_JRE here because that environment is attached
    // on the thread that loaded pojavexec (which is the thread that first references the GLFW class)
    // But this method is only called from the Android UI thread

    // Technically the better solution would be to have a permanently attached env pointer stored
    // in environ for the Android UI thread but this is the only place that uses it
    // (very rarely, only in lifecycle callbacks) so i dont care

    TRY_ATTACH_ENV(jvm_env, pojav_environ->runtimeJavaVMPtr, "nativeSetWindowAttrib failed: %i", return;);

    (*jvm_env)->CallStaticVoidMethod(
            jvm_env, pojav_environ->vmGlfwClass,
            pojav_environ->method_glftSetWindowAttrib,
            (jlong) pojav_environ->showingWindow, attrib, value
    );

    // Attaching every time is annoying, so stick the attachment to the Android GUI thread around
}
const static JNINativeMethod critical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", critical_set_stackqueue},
        {"nativeSendChar", "(C)Z", critical_send_char},
        {"nativeSendCharMods", "(CI)Z", critical_send_char_mods},
        {"nativeSendKey", "(IIII)V", critical_send_key},
        {"nativeSendCursorPos", "(FF)V", critical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", critical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", critical_send_scroll},
        {"nativeSendScreenSize", "(II)V", critical_send_screen_size}
};

const static JNINativeMethod noncritical_fcns[] = {
        {"nativeSetUseInputStackQueue", "(Z)V", noncritical_set_stackqueue},
        {"nativeSendChar", "(C)Z", noncritical_send_char},
        {"nativeSendCharMods", "(CI)Z", noncritical_send_char_mods},
        {"nativeSendKey", "(IIII)V", noncritical_send_key},
        {"nativeSendCursorPos", "(FF)V", noncritical_send_cursor_pos},
        {"nativeSendMouseButton", "(III)V", noncritical_send_mouse_button},
        {"nativeSendScroll", "(DD)V", noncritical_send_scroll},
        {"nativeSendScreenSize", "(II)V", noncritical_send_screen_size}
};


static bool criticalNativeAvailable;

void dvm_testCriticalNative(void* arg0, void* arg1, void* arg2, void* arg3) {
    if(arg0 != 0 && arg2 == 0 && arg3 == 0) {
        criticalNativeAvailable = false;
    }else if (arg0 == 0 && arg1 == 0){
        criticalNativeAvailable = true;
    }else {
        criticalNativeAvailable = false; // just to be safe
    }
}

static bool tryCriticalNative(JNIEnv *env) {
    static const JNINativeMethod testJNIMethod[] = {
            { "testCriticalNative", "(II)V", dvm_testCriticalNative}
    };
    jclass criticalNativeTest = (*env)->FindClass(env, "net/kdt/pojavlaunch/CriticalNativeTest");
    if(criticalNativeTest == NULL) {
        LOGD("No CriticalNativeTest class found !");
        (*env)->ExceptionClear(env);
        return false;
    }
    jmethodID criticalNativeTestMethod = (*env)->GetStaticMethodID(env, criticalNativeTest, "invokeTest", "()V");
    (*env)->RegisterNatives(env, criticalNativeTest, testJNIMethod, 1);
    (*env)->CallStaticVoidMethod(env, criticalNativeTest, criticalNativeTestMethod);
    (*env)->UnregisterNatives(env, criticalNativeTest);
    return criticalNativeAvailable;
}

static void registerFunctions(JNIEnv *env) {
    bool use_critical_cc = tryCriticalNative(env);
    jclass bridge_class = (*env)->FindClass(env, "org/lwjgl/glfw/CallbackBridge");
    if(use_critical_cc) {
        LOGI("CriticalNative is available. Enjoy the 4.6x times faster input!");
    }else{
        LOGI("CriticalNative is not available. Upgrade, maybe?");
    }
    (*env)->RegisterNatives(env,
                            bridge_class,
                            use_critical_cc ? critical_fcns : noncritical_fcns,
                            sizeof(critical_fcns)/sizeof(critical_fcns[0]));
}

JNIEXPORT jlong JNICALL
Java_org_lwjgl_glfw_GLFW_internalGetGamepadDataPointer(JNIEnv *env, jclass clazz) {
    return (jlong) &pojav_environ->gamepadState;
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadButtonBuffer(JNIEnv *env, jclass clazz) {
    return (*env)->NewDirectByteBuffer(env, &pojav_environ->gamepadState.buttons, sizeof(pojav_environ->gamepadState.buttons));
}

JNIEXPORT jobject JNICALL
Java_org_lwjgl_glfw_CallbackBridge_nativeCreateGamepadAxisBuffer(JNIEnv *env, jclass clazz) {
    return (*env)->NewDirectByteBuffer(env, &pojav_environ->gamepadState.axes, sizeof(pojav_environ->gamepadState.axes));
}