//
// Created by maks on 23.01.2025.
//

#ifndef POJAVLAUNCHER_JVM_HOOKS_H
#define POJAVLAUNCHER_JVM_HOOKS_H

#include <jni.h>

void installEMUIIteratorMititgation(JNIEnv *env);
void installLwjglDlopenHook(JNIEnv *env);

/* GL_VENDOR 品牌串拦截：把 `org.lwjgl.opengl.GL11C.nglGetString` 换成我们的实现。
   必须在 **JVM 侧、由 LWJGL 自己的 Java 调用**触发（见 lwjgl_dlopen_hook.c 的说明），
   当前挂在 `PojavRendererInit.nativeInitGl4esInternals`（gl_bridge.c）里。 */
void installGlGetStringHook(JNIEnv *env);
void hookExec(JNIEnv *env);

#endif //POJAVLAUNCHER_JVM_HOOKS_H
