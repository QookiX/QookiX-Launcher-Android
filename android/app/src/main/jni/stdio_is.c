#include <jni.h>
#include <sys/types.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>
#include <stdio.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>
#include <environ/environ.h>

#include "stdio_is.h"

//
// Created by maks on 17.02.21.
//

static volatile jobject exitTrap_ctx;
static volatile jclass exitTrap_exitClass;
static volatile jmethodID exitTrap_staticMethod;
static JavaVM *exitTrap_jvm;

static jmethodID logger_onEventLogged;
static volatile jobject logListener = NULL;
static int latestlog_fd = -1;


static bool recordBuffer(char* buf, ssize_t len) {
    if(strstr(buf, "Session ID is")) return false;
    if(latestlog_fd != -1) {
        write(latestlog_fd, buf, len);
        fdatasync(latestlog_fd);
    }
    return true;
}

/* 这里原本有一个 logger_thread()：从 pfd[0]（fd1/2 被重定向过来的管道）读，逐行写
 * latestlog.txt 并回调 Java 的 logListener。
 * 它已经被删除，原因见 Java_net_kdt_pojavlaunch_Logger_begin 里的注释：
 * Rust 侧稍后会接管 fd1/2，管道的写端会被覆盖掉，这个线程只会永久阻塞。 */
JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_begin(JNIEnv *env, __attribute((unused)) jclass clazz, jstring logPath) {
    if(latestlog_fd != -1) {
        int localfd = latestlog_fd;
        latestlog_fd = -1;
        close(localfd);
    }
    if(logger_onEventLogged == NULL) {
        jclass eventLogListener = (*env)->FindClass(env, "net/kdt/pojavlaunch/Logger$eventLogListener");
        logger_onEventLogged = (*env)->GetMethodID(env, eventLogListener, "onEventLogged", "(Ljava/lang/String;)V");
    }
    jclass ioeClass = (*env)->FindClass(env, "java/io/IOException");


    setvbuf(stdout, 0, _IOLBF, 0); // make stdout line-buffered
    setvbuf(stderr, 0, _IONBF, 0); // make stderr unbuffered

    /* 这里**不再**把 fd 1/2 重定向到自建管道。
     *
     * 历史行为是 `pipe(pfd); dup2(pfd[1], 1); dup2(pfd[1], 2);`，然后由
     * logger_thread 读 pfd[0] 逐行写 latestlog.txt 并回调 Java 的 logListener ——
     * 游戏内「日志输出」面板就是靠这个回调拿数据的。
     *
     * 但 Rust 侧启动 JVM 时还会再 `dup2` 一次：把 fd 1/2 指向
     * `logs/launch-<实例>.log`（见 android_env::redirect_output）。
     * 那次 dup2 会把管道的写端顶掉，之后**再没有任何人往 pipe 里写**，
     * logger_thread 的 read() 永久阻塞，回调一次都不触发 → 面板恒空。
     *
     * 既然两边都想要 fd 1/2，就只留一边：**重定向完全交给 Rust**
     * （它写文件，并同时通过 launch://log 推给启动器前端的日志面板）。
     * Java 侧这里只保留 latestlog_fd，让 appendToLog() 还能用；
     * 游戏内面板改为直接读 Rust 写的那个文件（见 LoggerView）。
     *
     * 「日志输出」面板里 game 的 stdout 现在来自 launch-<实例>.log。 */

    /* open latestlog.txt for writing */
    const char* logFilePath = (*env)->GetStringUTFChars(env, logPath, NULL);
    latestlog_fd = open(logFilePath, O_WRONLY | O_TRUNC);
    if(latestlog_fd == -1) {
        latestlog_fd = 0;
        /* 原来这里直接 return，把 GetStringUTFChars 的结果漏掉了（局部引用泄漏） */
        (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);
        (*env)->ThrowNew(env, ioeClass, strerror(errno));
        return;
    }
    (*env)->ReleaseStringUTFChars(env, logPath, logFilePath);

    /* 不再创建日志线程：fd 重定向已交给 Rust，这里没有管道可读。 */
}

_Noreturn void nominal_exit(int code, bool is_signal) {
    JNIEnv *env;
    jint errorCode = (*exitTrap_jvm)->GetEnv(exitTrap_jvm, (void**)&env, JNI_VERSION_1_6);
    if(errorCode == JNI_EDETACHED) {
        errorCode = (*exitTrap_jvm)->AttachCurrentThread(exitTrap_jvm, &env, NULL);
    }
    if(errorCode != JNI_OK) {
        // Step on a landmine and die, since we can't invoke the Dalvik exit without attaching to
        // Dalvik.
        // I mean, if Zygote can do that, why can't I?
        killpg(getpgrp(), SIGTERM);
    }
    if(code != 0) {
        // Exit code 0 is pretty established as "eh it's fine"
        // so only open the GUI if the code is != 0
        (*env)->CallStaticVoidMethod(env, exitTrap_exitClass, exitTrap_staticMethod, exitTrap_ctx, code, is_signal);
    }
    // Delete the reference, not gonna need 'em later anyway
    (*env)->DeleteGlobalRef(env, exitTrap_ctx);
    (*env)->DeleteGlobalRef(env, exitTrap_exitClass);

    // A hat trick, if you will
    // Call the Android System.exit() to perform Android's shutdown hooks and do a
    // fully clean exit.
    // After doing this, either of these will happen:
    // 1. Runtime calls exit() for real and it will be handled by ByteHook's recurse handler
    // and redirected back to the OS
    // 2. Zygote sends SIGTERM (no handling necessary, the process perishes)
    // 3. A different thread calls exit() and the hook will go through the exit_tripped path
    jclass systemClass = (*env)->FindClass(env,"java/lang/System");
    jmethodID exitMethod = (*env)->GetStaticMethodID(env, systemClass, "exit", "(I)V");
    (*env)->CallStaticVoidMethod(env, systemClass, exitMethod, 0);
    // System.exit() should not ever return, but the compiler doesn't know about that
    // so put a while loop here
    while(1) {}
}

JNIEXPORT void JNICALL Java_net_kdt_pojavlaunch_Logger_appendToLog(JNIEnv *env, __attribute((unused)) jclass clazz, jstring text) {
    jsize appendStringLength = (*env)->GetStringUTFLength(env, text);
    char newChars[appendStringLength+2];
    (*env)->GetStringUTFRegion(env, text, 0, (*env)->GetStringLength(env, text), newChars);
    newChars[appendStringLength] = '\n';
    newChars[appendStringLength+1] = 0;
    if(recordBuffer(newChars, appendStringLength+1) && logListener != NULL) {
        (*env)->CallVoidMethod(env, logListener, logger_onEventLogged, text);
    }
}

JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_Logger_setLogListener(JNIEnv *env, __attribute((unused)) jclass clazz, jobject log_listener) {
    jobject logListenerLocal = logListener;
    if(log_listener == NULL) {
        logListener = NULL;
    }else{
        logListener = (*env)->NewGlobalRef(env, log_listener);
    }
    if(logListenerLocal != NULL) (*env)->DeleteGlobalRef(env, logListenerLocal);
}


JNIEXPORT void JNICALL
Java_net_kdt_pojavlaunch_utils_JREUtils_setupExitMethod(JNIEnv *env, jclass clazz,
                                                        jobject context) {
    exitTrap_ctx = (*env)->NewGlobalRef(env,context);
    (*env)->GetJavaVM(env,&exitTrap_jvm);
    exitTrap_exitClass = (*env)->NewGlobalRef(env,(*env)->FindClass(env,"net/kdt/pojavlaunch/ExitActivity"));
    exitTrap_staticMethod = (*env)->GetStaticMethodID(env,exitTrap_exitClass,"showExitMessage","(Landroid/content/Context;IZ)V");
}