// ============================================================================
// libthreaddump.so — JVM TI 线程栈抓取 agent（诊断用，常驻无害）
//
// 为什么需要它：游戏 JVM 是内嵌 HotSpot（JNI 方式跑在应用进程里），安卓上
// 抓它的 Java 线程栈的所有常规手段全部失效：
//   - kill -3（SIGQUIT → hotspot dump）：信号被 ART 的 sigchain 拦截；
//   - .attach_pid + attach listener：同样靠 SIGQUIT，一样被拦；
//   - /data/anr/trace_*：只有 ART/Kotlin 线程，看不到 JVM 的 Render thread；
//   - jdwp：Pojav JRE 上 VM init 直接挂死。
// 所以干脆用 -agentpath 在 JVM 起来时加载本 agent：它在 127.0.0.1 起
// 一个 TCP 服务，谁连上来就把 GetAllStackTraces 的结果吐给谁。
//
// 用法（dump 抓取端）：
//   adb forward tcp:12857 tcp:12857
//   adb shell "echo d | nc 127.0.0.1 12857"   （或从 PC：nc 127.0.0.1 12857）
//
// 输出格式：每线程一段 ——
//   "Render thread" prio=5 state=RUNNABLE
//       at com.mojang.blaze3d.systems.RenderSystem.init(RenderSystem.java:48)
//       ...
// ============================================================================
#include <jni.h>
#include <jvmti.h>
#include <pthread.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <unistd.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>

static jvmtiEnv *g_jvmti = NULL;
static JavaVM *g_vm = NULL;
static int g_listen_fd = -1;

#define DEFAULT_PORT 12857
#define MAX_FRAMES 64

static void append_str(char **buf, size_t *cap, size_t *len, const char *s) {
    size_t slen = strlen(s);
    if (*len + slen + 1 > *cap) {
        *cap = (*len + slen + 1) * 2;
        *buf = realloc(*buf, *cap);
        if (!*buf) { *len = 0; *cap = 0; return; }
    }
    memcpy(*buf + *len, s, slen);
    *len += slen;
    (*buf)[*len] = '\0';
}

// 把当前所有 Java 线程的栈写进 fd。任何 JVMTI 失败都不抛出 —— 诊断工具
// 宁可少给信息也不能把游戏进程搞崩。
static void dump_all_threads(int fd) {
    jvmtiStackInfo *stack_info = NULL;
    jint thread_count = 0;
    char *buf = NULL;
    size_t cap = 0, len = 0;
    char tmp[1024];

    jvmtiError err = (*g_jvmti)->GetAllStackTraces(g_jvmti, MAX_FRAMES, &stack_info, &thread_count);
    if (err != JVMTI_ERROR_NONE) {
        snprintf(tmp, sizeof(tmp), "GetAllStackTraces failed: %d\n", err);
        append_str(&buf, &cap, &len, tmp);
        write(fd, buf, len);
        free(buf);
        return;
    }

    snprintf(tmp, sizeof(tmp), "== thread dump: %d threads ==\n", thread_count);
    append_str(&buf, &cap, &len, tmp);

    for (int i = 0; i < thread_count; i++) {
        jvmtiStackInfo *si = &stack_info[i];
        jvmtiThreadInfo info;
        if ((*g_jvmti)->GetThreadInfo(g_jvmti, si->thread, &info) != JVMTI_ERROR_NONE) {
            continue;
        }
        const char *state = "?";
        switch (si->state) {
            case JVMTI_THREAD_STATE_RUNNABLE: state = "RUNNABLE"; break;
            case JVMTI_THREAD_STATE_BLOCKED_ON_MONITOR_ENTER: state = "BLOCKED"; break;
            case JVMTI_THREAD_STATE_WAITING_INDEFINITELY: state = "WAITING"; break;
            case JVMTI_THREAD_STATE_WAITING_WITH_TIMEOUT: state = "TIMED_WAITING"; break;
            case JVMTI_THREAD_STATE_TERMINATED: state = "TERMINATED"; break;
            default:
                if (si->state & JVMTI_THREAD_STATE_SLEEPING) state = "SLEEPING";
                else if (si->state & JVMTI_THREAD_STATE_PARKED) state = "PARKED";
                else if (si->state & JVMTI_THREAD_STATE_WAITING) state = "WAITING";
                break;
        }
        snprintf(tmp, sizeof(tmp), "\n\"%s\" %s state=%s\n", info.name, info.is_daemon ? "daemon" : "", state);
        append_str(&buf, &cap, &len, tmp);

        for (int f = 0; f < si->frame_count; f++) {
            jvmtiFrameInfo *fr = &si->frame_buffer[f];
            char *cls_sig = NULL, *generic = NULL;
            char *mname = NULL, *msig = NULL, *mgeneric = NULL;
            jclass declaring = NULL;
            jint lineno = -1;

            if ((*g_jvmti)->GetMethodDeclaringClass(g_jvmti, fr->method, &declaring) == JVMTI_ERROR_NONE &&
                (*g_jvmti)->GetClassSignature(g_jvmti, declaring, &cls_sig, &generic) == JVMTI_ERROR_NONE &&
                (*g_jvmti)->GetMethodName(g_jvmti, fr->method, &mname, &msig, &mgeneric) == JVMTI_ERROR_NONE) {
                jvmtiLineNumberEntry *table = NULL;
                jint table_count = 0;
                if ((*g_jvmti)->GetLineNumberTable(g_jvmti, fr->method, &table_count, &table) == JVMTI_ERROR_NONE && table) {
                    for (jint li = 0; li < table_count; li++) {
                        if (table[li].start_location <= fr->location) {
                            lineno = table[li].line_number;
                        } else break;
                    }
                    (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)table);
                }
                snprintf(tmp, sizeof(tmp), "    at %s.%s(%s.java:%d)%s\n",
                         cls_sig[0] == 'L' ? cls_sig + 1 : cls_sig,
                         mname, mname, lineno, fr->location < 0 ? " (native)" : "");
                append_str(&buf, &cap, &len, tmp);
                if (cls_sig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)cls_sig);
                if (generic) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)generic);
                if (mname) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)mname);
                if (msig) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)msig);
                if (mgeneric) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)mgeneric);
            }
        }
        if (info.name) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)info.name);
    }

    append_str(&buf, &cap, &len, "\n== end ==\n");
    if (buf && len > 0) {
        size_t off = 0;
        while (off < len) {
            ssize_t n = write(fd, buf + off, len - off);
            if (n <= 0) break;
            off += (size_t)n;
        }
    }
    free(buf);

    if (stack_info) (*g_jvmti)->Deallocate(g_jvmti, (unsigned char *)stack_info);
}

static void *server_thread(void *arg) {
    (void)arg;
    for (;;) {
        struct sockaddr_in peer;
        socklen_t plen = sizeof(peer);
        int fd = accept(g_listen_fd, (struct sockaddr *)&peer, &plen);
        if (fd < 0) {
            if (errno == EINTR) continue;
            sleep(1);
            continue;
        }
        // 读掉请求行（不解析内容），然后给全量线程栈
        char req[256];
        ssize_t n = read(fd, req, sizeof(req) - 1);
        (void)n;
        dump_all_threads(fd);
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
    return NULL;
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM *vm, char *options, void *reserved) {
    (void)reserved;
    g_vm = vm;
    int port = DEFAULT_PORT;
    if (options && *options) {
        // 支持 -agentpath:...=port=xxxx
        const char *p = strstr(options, "port=");
        if (p) port = atoi(p + 5);
    }

    if ((*vm)->GetEnv(vm, (void **)&g_jvmti, JVMTI_VERSION_1_2) != JNI_OK) {
        return JNI_ERR;
    }

    jvmtiCapabilities caps;
    memset(&caps, 0, sizeof(caps));
    caps.can_get_line_numbers = 1;
    caps.can_get_source_file_name = 1;
    caps.can_tag_objects = 1;
    (*g_jvmti)->AddCapabilities(g_jvmti, &caps);

    g_listen_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (g_listen_fd < 0) return JNI_OK; // 起不来也别连累游戏

    int one = 1;
    setsockopt(g_listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr.sin_port = htons(port);
    if (bind(g_listen_fd, (struct sockaddr *)&addr, sizeof(addr)) != 0 || listen(g_listen_fd, 4) != 0) {
        close(g_listen_fd);
        g_listen_fd = -1;
        return JNI_OK;
    }

    pthread_t tid;
    if (pthread_create(&tid, NULL, server_thread, NULL) != 0) {
        close(g_listen_fd);
        g_listen_fd = -1;
        return JNI_OK;
    }
    pthread_detach(tid);
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM *vm) {
    (void)vm;
    if (g_listen_fd >= 0) { close(g_listen_fd); g_listen_fd = -1; }
}
