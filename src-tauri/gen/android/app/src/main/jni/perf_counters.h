#ifndef POJAVLAUNCHER_PERF_COUNTERS_H
#define POJAVLAUNCHER_PERF_COUNTERS_H

/*
 * 帧计数（性能面板的数据源）。
 *
 * 只做一件事：在「交换缓冲」处记一个单调时间戳，累积在环形缓冲里。
 * 三条渲染路径各有一处调用点：
 *   - GL4ES / MobileGlues → ctxbridges/gl_bridge.c  gl_swap_buffers()
 *   - Zink (OSMesa)       → ctxbridges/osm_bridge.c  osm_swap_buffers()
 *   - 26.3（SDL 窗口层）  → jvm_hooks/lwjgl_dlopen_hook.c 的 ndlsym 代理里拦
 *                            **SDL_GL_SwapWindow** 后调用（见下面那段说明）
 *
 * ⚠️ 注意「MobileGlues」这一项只适用于**旧版 MC**（走 LWJGL 窗口层，Pojav 自己的
 * gl_swap_buffers 会被调到）。**26.3 + MG 是没有计数点的** —— 26.3 的窗口层换成了
 * SDL3，换帧由 SDL 内部完成，Pojav 那个 `gl_swap_buffers()` 压根不被调用；
 * MG 只是提供 GL/EGL 实现，不是换帧的调用方。这就是「26.3 原版 + MG 上性能面板
 * 只有 CPU/内存/分辨率、FPS 停在 0」的原因（2026-10-08 用户反馈）。
 * 所以 SDL 路径单独加了一个计数点：MC 26.3 的 SDL 符号也是从
 * `DynamicLinkLoader.ndlsym` 拿的（LWJGL 的 SDL 绑定），在那个出口把
 * `SDL_GL_SwapWindow` 换成代理、调完真函数后 `perf_frame()` 即可 ——
 * 与 EGL 实现是谁无关（MG / GL4ES / 系统 EGL 都适用）。
 *
 * 每帧开销：一次 clock_gettime + 一次数组写入，可忽略。
 */

/** 每帧调用一次（渲染线程）。 */
void perf_frame(void);

/**
 * 汇总最近约 2 秒的数据，返回形如 "fps;avgMs;low1Fps;maxMs;renderer" 的静态字符串：
 *   fps     最近 1 秒的帧数
 *   avgMs   窗口内平均帧间隔（毫秒，1 位小数）
 *   low1Fps 按「最差 1% 帧间隔」折算的帧数（1% low）
 *   maxMs   窗口内最差的一帧（毫秒）
 *   renderer QOOKIX_RENDERER 的原值（原 POJAV_RENDERER，改名避开 Sodium PostLaunchChecks 的 Pojav 检测）（C 层逻辑名），Java 侧负责转成显示名
 * 返回的是静态缓冲，调用方不必释放，但不要长期持有。
 */
const char* perf_snapshot(void);

#endif /* POJAVLAUNCHER_PERF_COUNTERS_H */
