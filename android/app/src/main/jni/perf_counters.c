#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "perf_counters.h"

/*
 * 帧计数（性能面板的数据源）。
 *
 * **为什么走文件而不是 JNI**：进程里同时存在两份 libpojavexec ——
 *   1. JVM 侧 System.loadLibrary("pojavexec") 按 java.library.path 加载
 *      `files/natives/libpojavexec.so`（渲染桥在这里，gl_swap_buffers 会调 perf_frame）；
 *   2. APK 内还挂着一份（路径/加载器不同，静态变量各有一套）。
 * 面板（app 侧类）的 JNI 解析会绑到第 2 份，读到恒 0 的计数器
 * （2026-09-23 实测：同 pid，perf_frame 与 nativeSnapshot 的 &s_count 相差 ~1.2GB）。
 * 所以计数器每 500ms 把快照写到 $TMPDIR/perf.txt，面板直接读文件 ——
 * TMPDIR 与 Java 的 cacheDir 是同一个目录，跨实例天然成立。
 *
 * 26.3 的 SDL 路径换帧发生在 SDL 内部（Pojav 的 gl_swap_buffers 不被调用），
 * 计数点补在 `jvm_hooks/lwjgl_dlopen_hook.c` 的 ndlsym 代理里 ——
 * 拦 `SDL_GL_SwapWindow`，调完真函数再 perf_frame()。详见 perf_counters.h。
 */

#define PERF_MAX_FRAMES 512
#define PERF_WINDOW_MS 2000
#define PERF_WRITE_INTERVAL_MS 500

/*
 * 这个环形缓冲由**两个线程**同时访问：
 *   - `perf_frame()`：渲染线程 / SDL 换帧线程，**每帧**写一次
 *     （调用点见 gl_bridge.c、osm_bridge.c、lwjgl_dlopen_hook.c 的 SDL_GL_SwapWindow 代理）；
 *   - `perf_writer_thread`：每 500ms 调 `perf_snapshot()` 读一遍。
 * 原来这里是三个裸静态变量、没有任何同步 —— head / count 会被撕裂读（读到只更新了一半的值），
 * 表现就是 FPS 偶发跳变或蹦出莫名其妙的坏值。用一把小锁护住。
 */
static pthread_mutex_t s_lock = PTHREAD_MUTEX_INITIALIZER;
static long long s_stamps[PERF_MAX_FRAMES];
static int s_count; /* 已写入的样本数（≤ PERF_MAX_FRAMES） */
static int s_head;  /* 下一个写入位置 */
/** 累计帧数（不受 512 环形缓冲限制）。审计 B29：writer 靠它判断有没有新帧。 */
static long long s_total_frames;

static void* perf_writer_thread(void* arg);

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

void perf_frame(void) {
    static int writer_started;
    if (!writer_started) {
        writer_started = 1;
        pthread_t tid;
        if (pthread_create(&tid, NULL, perf_writer_thread, NULL) == 0)
            pthread_detach(tid);
    }
    pthread_mutex_lock(&s_lock);
    s_stamps[s_head] = now_ms();
    s_head = (s_head + 1) % PERF_MAX_FRAMES;
    if (s_count < PERF_MAX_FRAMES) s_count++;
    s_total_frames++;
    pthread_mutex_unlock(&s_lock);
}

static int cmp_float_desc(const void* a, const void* b) {
    float fa = *(const float*)a, fb = *(const float*)b;
    return (fb > fa) - (fb < fa); /* 大的在前（最差帧间隔排最前） */
}

const char* perf_snapshot(void) {
    static char buf[160];
    static const char* renderer = NULL;

    long long now = now_ms();
    long long cutoff_2s = now - PERF_WINDOW_MS;
    long long cutoff_1s = now - 1000;

    float gaps[PERF_MAX_FRAMES];
    int n_gaps = 0;
    int fps = 0;

    /* 取一份快照：**持锁只做拷贝**，统计放到锁外算 ——
     * 这样渲染线程（perf_frame）最多只被阻塞一次拷贝的时间，不会因为面板而掉帧。 */
    long long stamps[PERF_MAX_FRAMES];
    int count;
    pthread_mutex_lock(&s_lock);
    count = s_count;
    {
        int start = (s_head - s_count + PERF_MAX_FRAMES) % PERF_MAX_FRAMES;
        for (int i = 0; i < count; i++) {
            stamps[i] = s_stamps[(start + i) % PERF_MAX_FRAMES];
        }
    }
    pthread_mutex_unlock(&s_lock);

    long long prev = 0;
    for (int i = 0; i < count; i++) {
        long long t = stamps[i];
        if (t < cutoff_2s) {
            prev = 0; /* 窗口外的帧不参与间隔统计 */
            continue;
        }
        if (t >= cutoff_1s) fps++;
        if (prev != 0) {
            float gap = (float)(t - prev);
            /* 掐头：切后台回来 / 窗口重建会产生几百 ms 以上的假间隔，不能算进帧时间 */
            if (gap > 0.f && gap < 1000.f) gaps[n_gaps++] = gap;
        }
        prev = t;
    }

    if (renderer == NULL) {
        const char* env = getenv("QOOKIX_RENDERER");
        renderer = (env != NULL && env[0] != '\0') ? env : "?";
    }

    if (n_gaps == 0) {
        snprintf(buf, sizeof(buf), "0;0;0;0;%s", renderer);
        return buf;
    }

    float sum = 0.f;
    float max_gap = 0.f;
    for (int i = 0; i < n_gaps; i++) {
        sum += gaps[i];
        if (gaps[i] > max_gap) max_gap = gaps[i];
    }
    float avg = sum / n_gaps;

    /* 1% low：最差的 1% 帧间隔的平均值折算成帧数（至少取 1 帧） */
    qsort(gaps, n_gaps, sizeof(float), cmp_float_desc);
    int worst = n_gaps / 100;
    if (worst < 1) worst = 1;
    float worst_sum = 0.f;
    for (int i = 0; i < worst; i++) worst_sum += gaps[i];
    float low1 = worst_sum / worst;

    snprintf(buf, sizeof(buf), "%d;%.1f;%.0f;%.1f;%s", fps, avg,
             low1 > 0.f ? 1000.f / low1 : 0.f, max_gap, renderer);
    return buf;
}
static void* perf_writer_thread(void* arg) {
    (void)arg;
    const char* tmp = getenv("TMPDIR");
    if (tmp == NULL || tmp[0] == '\0') return NULL;

    char path[512], part[532];
    snprintf(path, sizeof(path), "%s/perf.txt", tmp);
    snprintf(part, sizeof(part), "%s.tmp", path);

    long long last_written_total = -1;
    for (;;) {
        usleep(PERF_WRITE_INTERVAL_MS * 1000);
        pthread_mutex_lock(&s_lock);
        long long total = s_total_frames;
        pthread_mutex_unlock(&s_lock);
        /* ⚠️ 2026-10-29（外部审计 B29 核实成立）：原来无条件每 500ms 写一次 ——
           即使渲染线程完全卡死、一帧都没出，perf.txt 的 mtime 照样新鲜。
           Java 侧的渲染自愈看门狗正是靠 mtime 判断「还在出帧」，于是永远认为
           健康，真卡黑时反而不会重绑 Surface。改成**没有新帧就不写**。 */
        if (total == last_written_total) continue;
        last_written_total = total;
        FILE* f = fopen(part, "w");
        if (f == NULL) continue;
        fputs(perf_snapshot(), f);
        fclose(f);
        /* rename 保证读侧看到的要么是旧快照、要么是完整新快照 */
        rename(part, path);
    }
    return NULL;
}
