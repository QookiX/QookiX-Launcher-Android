/*
 * libffi 常量垫片（LWJGL 3.4.x + MC 26.3 启动顺序问题的补丁）。
 *
 * 现象：26.3 启动时报
 *   UnsatisfiedLinkError: 'short org.lwjgl.system.libffi.LibFFI.FFI_TYPE_DOUBLE()'
 * 栈是 Library.<clinit>（正在 System.loadLibrary("lwjgl")）
 *   → org.lwjgl.glfw.GLFW.<clinit>:578 → GLFWErrorCallbackI.<clinit>
 *   → LibFFI.<clinit>:32。
 *
 * 原因：`GLFW.<clinit>` 会先 `System.loadLibrary("pojavexec")`（第 574 行，
 * 我们的 nativeInitializeGLFWNativeBridge），随后第 578 行初始化 GLFWErrorCallbackI，
 * 而它依赖 LibFFI —— 这几个 native 方法在 **liblwjgl.so** 里，但那一刻 liblwjgl.so
 * 还卡在 JNI_OnLoad 里没加载完，符号查不到。
 *
 * 做法：把 LibFFI 在类初始化阶段要用的 3 个常量函数实现到 libpojavexec（此时它已加载），
 * 让类初始化能走完；之后真正用到 FFI 时，其余 native 会从已经加载好的 liblwjgl.so 解析。
 * 取值来自 libffi 的 ffi.h：
 *   FFI_TYPE_VOID 0 / INT 1 / FLOAT 2 / DOUBLE 3 / LONGDOUBLE 4
 */
#include <jni.h>

JNIEXPORT jshort JNICALL
Java_org_lwjgl_system_libffi_LibFFI_FFI_1TYPE_1FLOAT(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return 2;
}

JNIEXPORT jshort JNICALL
Java_org_lwjgl_system_libffi_LibFFI_FFI_1TYPE_1DOUBLE(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return 3;
}

JNIEXPORT jshort JNICALL
Java_org_lwjgl_system_libffi_LibFFI_FFI_1TYPE_1LONGDOUBLE(JNIEnv* env, jclass clazz) {
    (void)env;
    (void)clazz;
    return 4;
}
