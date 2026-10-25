//
// Created by maks on 06.01.2025.
//

#include "jvm_hooks.h"

#include <android/api-level.h>

#include "environ/environ.h"
// 帧率计数：SDL_GL_SwapWindow 代理里要调 perf_frame()（26.3 的性能面板数据源）
#include "../perf_counters.h"

#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#define TAG __FILE_NAME__
#include <log.h>

extern void* maybe_load_vulkan();

/**
 * Basically a verbatim implementation of ndlopen(), found at
 * https://github.com/PojavLauncherTeam/lwjgl3/blob/3.3.1/modules/lwjgl/core/src/generated/c/linux/org_lwjgl_system_linux_DynamicLinkLoader.c#L11
 * but with our own additions for stuff like vulkanmod.
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                     __attribute__((unused)) jclass class,
                     jlong filename_ptr,
                     jint jmode) {
    const char* filename = (const char*) filename_ptr;

    // Oveeride vulkan loading to let us load vulkan ourselves
    if(strstr(filename, "libvulkan.so") == filename) {
        printf("LWJGL linkerhook: replacing load for libvulkan.so with custom driver\n");
        return (jlong) maybe_load_vulkan();
    }

    // This hook also serves the task of mitigating a bug: the idea is that since, on Android 10 and
    // earlier, the linker doesn't really do namespace nesting.
    // It is not a problem as most of the libraries are in the launcher path, but when you try to run
    // VulkanMod which loads shaderc outside of the default jni libs directory through this method,
    // it can't load it because the path is not in the allowed paths for the anonymous namesapce.
    // This method fixes the issue by being in libpojavexec, and thus being in the classloader namespace

    int mode = (int)jmode;
    return (jlong) dlopen(filename, mode);
}

/**
 * glGetString 拦截：把 GL_VENDOR 换成我们自己的串。
 *
 * 为什么在**取值处**拦，而不是改渲染器的二进制：
 * MC 的 F3「Display: WxH (…)」那行取的就是 `glGetString(GL_VENDOR)`
 * （1.18.2 里是 `dsg.a()` → `GlStateManager._getString(7936)`，已用字节码确认），
 * 而实际报出来的是 GL4ES 那套上游署名串；那个字面量在上游二进制里查不到
 * （运行期拼装），改文件改不掉，所以在取值处拦截最确定 —— 与渲染器实现无关。
 *
 * 拦截点选 `org.lwjgl.opengl.GL11C.nglGetString`（`GL11.nglGetString` 只是转调它）：
 * 它是 JNI native，我们直接 RegisterNatives 换实现即可，**完全不动 LWJGL 的
 * 符号解析链路**。之前试过覆盖 `DynamicLinkLoader.ndlsym`，结果把 LWJGL 的函数表
 * 加载弄坏、游戏在 `Setting user` 之后静默退出（教训：能不动加载器就别动）。
 */
#define QOOKIX_GL_VENDOR_ENUM 0x1F00 /* GL_VENDOR */
static const char *const QOOKIX_GL_VENDOR = "QookiX & ptitSeb";

typedef const unsigned char *(*glGetString_fn)(unsigned int);

static glGetString_fn resolve_glGetString(void) {
    static glGetString_fn fn = NULL;
    static int tried = 0;
    if (fn != NULL) return fn;

    /* 1) 全局作用域。渲染器库多半是以 RTLD_GLOBAL 载入的，这里通常就能拿到。 */
    fn = (glGetString_fn) dlsym(RTLD_DEFAULT, "glGetString");
    if (fn != NULL) return fn;

    /* 2) 按 soname 直接认已加载的那份（RTLD_NOLOAD 不会重复加载）。
          这里列了三种渲染器的库名：GL4ES / OSMesa(zink) / MobileGlues。 */
    if (!tried) {
        tried = 1;
        const char *candidates[] = {"libgl4es_114.so", "libOSMesa.so", "libmobileglues.so", NULL};
        for (int i = 0; candidates[i] != NULL && fn == NULL; ++i) {
            void *handle = dlopen(candidates[i], RTLD_LAZY | RTLD_NOLOAD);
            if (handle == NULL) continue;
            fn = (glGetString_fn) dlsym(handle, "glGetString");
            if (fn != NULL) LOGI("已从 %s 解析到真实的 glGetString", candidates[i]);
        }
    }
    if (fn == NULL) LOGE("未能解析真实的 glGetString，本次调用原样返回 0");
    return fn;
}

/* 保留上游署名：MIT 许可要求保留版权声明，所以是「追加」而不是「抹掉」。 */
static jlong nglGetString_bugfix(__attribute__((unused)) JNIEnv *env,
                                 __attribute__((unused)) jclass class,
                                 jint name) {
    if (name == QOOKIX_GL_VENDOR_ENUM) {
        LOGI("glGetString(GL_VENDOR) -> %s", QOOKIX_GL_VENDOR);
        return (jlong) (intptr_t) QOOKIX_GL_VENDOR;
    }
    glGetString_fn fn = resolve_glGetString();
    if (fn == NULL) return 0;
    return (jlong) (intptr_t) fn((unsigned int) name);
}

/** 把 `GL11C.nglGetString` 换成我们的实现（GL_VENDOR 走品牌串，其余透传）。
 *  **不要在 JNI_OnLoad 里调用**：那时线程没有类加载器上下文，FindClass 会把进程搞崩
 *  （实测：游戏在 `Setting user` 处静默退出）。改由 JVM 侧的 `PojavRendererInit`
 *  native 调用 —— 那是 LWJGL 的类调用过来的，同包同加载器，FindClass 正常。 */
void installGlGetStringHook(JNIEnv *env) {
    jclass gl11c = (*env)->FindClass(env, "org/lwjgl/opengl/GL11C");
    if (gl11c == NULL) {
        LOGE("找不到 org/lwjgl/opengl/GL11C，跳过 GL_VENDOR 品牌串拦截");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod methods[] = {
            {"nglGetString", "(I)J", &nglGetString_bugfix}
    };
    if ((*env)->RegisterNatives(env, gl11c, methods, 1) != 0) {
        LOGE("注册 GL11C.nglGetString 失败");
        (*env)->ExceptionClear(env);
        return;
    }
    LOGI("已接管 GL11C.nglGetString（GL_VENDOR -> %s）", QOOKIX_GL_VENDOR);
}

/* ===================== 26.3：SDL 窗口复用 =====================
 *
 * MC 26.3 的 renderpearl（`GlDevice`）流程是：
 *   1) 建一个窗口 → 2) `SDL_GL_CreateContext(那个窗口)` →
 *   3) 再建一个「Minecraft - RenderPearl OpenGL Hidden Utility Window」。
 * 第 3 步必然失败：这句 `Android only supports one window` 就在 **libSDL3.so** 里
 * （SDL 的安卓后端只允许一个窗口）→ `BackendCreationException: Failed to create window
 * for OpenGL after creating context` → 后端创建失败、游戏崩。
 *
 * FCL 的解法叫「窗口复用」：不让它建第二个，直接把已有的窗口还给它（它们还修过
 * 「复用窗口被提前销毁」）。落点就是这里：LWJGL 的函数地址全部来自
 * `DynamicLinkLoader.ndlsym`，在那个出口把窗口相关的 SDL 函数换成代理，
 * 其余符号原样透传 —— 也就是 FCL 说的「解析出口改用代理」。
 *
 * 注意：代理内部要拿到**真实的** SDL 函数，用 libc 的 `dlsym`（我们的 hook 在 JVM
 * native 方法这一层，不影响 libc 的 dlsym），优先用 `POJAVEXEC_SDL3` 给的绝对路径。
 */
typedef void *(*sdl_create_window_fn)(const char *, int, int, unsigned);
typedef void *(*sdl_create_window_props_fn)(unsigned);
typedef void (*sdl_destroy_window_fn)(void *);

static void *s_reused_sdl_window = NULL;

static void *resolve_sdl_symbol(const char *name) {
    const char *path = getenv("POJAVEXEC_SDL3");
    void *handle = NULL;
    if (path != NULL && path[0] != '\0') {
        handle = dlopen(path, RTLD_LAZY);
    }
    if (handle == NULL) {
        handle = dlopen("libSDL3.so", RTLD_LAZY | RTLD_NOLOAD);
    }
    if (handle != NULL) {
        void *sym = dlsym(handle, name);
        if (sym != NULL) return sym;
    }
    /* 兜底：进程里可能已经有一份 SDL3（例如启动器侧加载的），直接在全局符号里找。 */
    void *sym = dlsym(RTLD_DEFAULT, name);
    if (sym == NULL) {
        LOGE("SDL 代理：拿不到 %s（libSDL3 既没按路径加载、全局也没有）", name);
    }
    return sym;
}

static void *qookix_SDL_CreateWindow(const char *title, int w, int h, unsigned flags) {
    if (s_reused_sdl_window != NULL) {
        LOGI("SDL 窗口复用：跳过建第二个窗口（\"%s\"），返回已有窗口 %p",
             title != NULL ? title : "?", s_reused_sdl_window);
        return s_reused_sdl_window;
    }
    sdl_create_window_fn real = (sdl_create_window_fn) resolve_sdl_symbol("SDL_CreateWindow");
    if (real == NULL) {
        LOGE("SDL 代理：找不到真正的 SDL_CreateWindow");
        return NULL;
    }
    s_reused_sdl_window = real(title, w, h, flags);
    LOGI("SDL 建窗（第一个，%dx%d）：%p", w, h, s_reused_sdl_window);
    return s_reused_sdl_window;
}

static void *qookix_SDL_CreateWindowWithProperties(unsigned props) {
    if (s_reused_sdl_window != NULL) {
        LOGI("SDL 窗口复用：跳过建第二个窗口（带属性版），返回已有窗口 %p", s_reused_sdl_window);
        return s_reused_sdl_window;
    }
    sdl_create_window_props_fn real =
            (sdl_create_window_props_fn) resolve_sdl_symbol("SDL_CreateWindowWithProperties");
    if (real == NULL) {
        /* 老一点的 SDL3 没有这个入口，退回普通版（能拿到属性差异也不大） */
        return qookix_SDL_CreateWindow("Minecraft", 320, 480, 0);
    }
    s_reused_sdl_window = real(props);
    LOGI("SDL 建窗（第一个，属性版）：%p", s_reused_sdl_window);
    return s_reused_sdl_window;
}

static void qookix_SDL_DestroyWindow(void *window) {
    if (window != NULL && window == s_reused_sdl_window) {
        LOGI("SDL 窗口复用：忽略对复用窗口的销毁请求（销毁它会把启动器的窗口一起带走）");
        return;
    }
    sdl_destroy_window_fn real = (sdl_destroy_window_fn) resolve_sdl_symbol("SDL_DestroyWindow");
    if (real != NULL) {
        real(window);
    }
}

/* ===================== 26.3：帧率计数点 =====================
 *
 * 性能面板的帧率来自 `perf_counters.c`，而它只在两个地方被喂数据：
 *   - `gl_bridge.c`：EGL swap 之后（GL4ES / MobileGlues 路径）
 *   - `osm_bridge.c`：`ANativeWindow_unlockAndPost` 之后（Zink 路径）
 * **26.3 都不走** —— 它用 SDL3 自建窗口自绘，换帧发生在 libSDL3 内部，
 * 于是 `perf.txt` 里 fps 恒为 0，面板上「帧率」勾了也只显示 `--`，
 * 而 CPU/内存/分辨率（Java 侧自己读的）一切正常 —— 用户看到的就是
 * 「只有 CPU、内存和分辨率能显示」。
 *
 * 修法：MC 26.3 的 SDL 符号也是从 `DynamicLinkLoader.ndlsym` 拿的（LWJGL 的 SDL 绑定），
 * 就在下面那个已经存在的出口把 **`SDL_GL_SwapWindow`** 换成代理 ——
 * 它是 SDL 的换帧函数，每帧必经，位置正好等于 gl_bridge 里的 EGL swap。
 * 其余符号照旧透传，不影响窗口复用那套。
 */
typedef int (*sdl_gl_swap_window_fn)(void *);

static int qookix_SDL_GL_SwapWindow(void *window) {
    static sdl_gl_swap_window_fn real = NULL;
    static int logged = 0;
    if (real == NULL) {
        real = (sdl_gl_swap_window_fn) resolve_sdl_symbol("SDL_GL_SwapWindow");
        if (real == NULL) {
            /* 拿不到真函数就别拦：宁可没有帧率，也不能让游戏画不出来 */
            LOGE("SDL 代理：找不到真正的 SDL_GL_SwapWindow，帧率计数点未生效");
            return 0;
        }
    }
    if (!logged) {
        logged = 1;
        LOGI("SDL 代理：SDL_GL_SwapWindow 已接管（26.3 帧率计数点生效）");
    }
    int ret = real(window);
    perf_frame(); /* 性能面板的帧计数（26.3 / SDL 路径） */
    return ret;
}

/** `DynamicLinkLoader.ndlsym` 的替身：只换 SDL 窗口相关的那几个，其余透传。 */
static jlong ndlsym_bugfix(__attribute__((unused)) JNIEnv *env,
                           __attribute__((unused)) jclass class,
                           jlong handle,
                           jlong name_ptr) {
    const char *name = (const char *) (intptr_t) name_ptr;
    if (name != NULL) {
        if (strcmp(name, "SDL_CreateWindow") == 0) {
            LOGI("SDL 代理：SDL_CreateWindow 换成窗口复用版");
            return (jlong) (intptr_t) qookix_SDL_CreateWindow;
        }
        if (strcmp(name, "SDL_CreateWindowWithProperties") == 0) {
            LOGI("SDL 代理：SDL_CreateWindowWithProperties 换成窗口复用版");
            return (jlong) (intptr_t) qookix_SDL_CreateWindowWithProperties;
        }
        if (strcmp(name, "SDL_DestroyWindow") == 0) {
            LOGI("SDL 代理：SDL_DestroyWindow 换成忽略销毁版");
            return (jlong) (intptr_t) qookix_SDL_DestroyWindow;
        }
        // 帧率计数点：SDL 每帧换帧的必经函数（26.3 的性能面板靠它拿 FPS）
        if (strcmp(name, "SDL_GL_SwapWindow") == 0) {
            return (jlong) (intptr_t) qookix_SDL_GL_SwapWindow;
        }
    }
    return (jlong) (intptr_t) dlsym((void *) (intptr_t) handle, name);
}

/**
 * Install the LWJGL dlopen hook. This allows us to mitigate linker bugs and add custom library overrides.
 */
void installLwjglDlopenHook(JNIEnv *env) {
    LOGI("Installing LWJGL dlopen() hook");
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if(dynamicLinkLoader == NULL) {
        LOGE("Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    // ndlsym 也一起接管：只在出口给 SDL 窗口相关函数换代理，别的照原样解析（26.3 需要）。
    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix},
            {"ndlsym",  "(JJ)J", &ndlsym_bugfix}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 2) != 0) {
        LOGE("Failed to register the hooked method");
        (*env)->ExceptionClear(env);
    }
}