#include <EGL/egl.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <string.h>
#include <malloc.h>
#include <stdlib.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <environ/environ.h>
#include <jvm_hooks/jvm_hooks.h>
#include "gl_bridge.h"
#include "egl_loader.h"
#include "../perf_counters.h"

#define TAG __FILE_NAME__
#include <log.h>

//
// Created by maks on 17.09.2022.
//

static __thread gl_render_window_t* currentBundle;
static EGLDisplay g_EglDisplay;

bool gl_init() {
    if(!dlsym_EGL()) return false;
    g_EglDisplay = eglGetDisplay_p(EGL_DEFAULT_DISPLAY);
    if (g_EglDisplay == EGL_NO_DISPLAY) {
        LOGE("%s", "eglGetDisplay_p(EGL_DEFAULT_DISPLAY) returned EGL_NO_DISPLAY");
        return false;
    }
    if (eglInitialize_p(g_EglDisplay, 0, 0) != EGL_TRUE) {
        LOGE("eglInitialize_p() failed: %04x", eglGetError_p());
        return false;
    }
    return true;
}

gl_render_window_t* gl_get_current() {
    return currentBundle;
}

static void gl4esi_get_display_dimensions(int* width, int* height) {
    if(currentBundle == NULL) goto zero;
    EGLSurface surface = currentBundle->surface;
    // Fetch dimensions from the EGL surface - the most reliable way
    EGLBoolean result_width = eglQuerySurface_p(g_EglDisplay, surface, EGL_WIDTH, width);
    EGLBoolean result_height = eglQuerySurface_p(g_EglDisplay, surface, EGL_HEIGHT, height);
    if(!result_width || !result_height) goto zero;
    return;

    zero:
    // No idea what to do, but feeding gl4es incorrect or non-initialized dimensions may be
    // a bad idea. Set to zero in case of errors.
    *width = 0;
    *height = 0;
}

gl_render_window_t* gl_init_context(gl_render_window_t *share) {
    gl_render_window_t* bundle = malloc(sizeof(gl_render_window_t));
    if (bundle == NULL) return NULL;
    memset(bundle, 0, sizeof(gl_render_window_t));

    static const EGLint cfg_depth24[] = {
        EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    static const EGLint cfg_depth16[] = {
        EGL_BLUE_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_RED_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16, EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    static const EGLint cfg_rgb565[] = {
        EGL_BLUE_SIZE, 5, EGL_GREEN_SIZE, 6, EGL_RED_SIZE, 5,
        EGL_DEPTH_SIZE, 16, EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    static const EGLint cfg_min[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE
    };
    const EGLint *candidates[] = { cfg_depth24, cfg_depth16, cfg_rgb565, cfg_min };
    EGLint num_configs = 0;
    const EGLint *chosen_attrs = NULL;
    for (int i = 0; i < 4; ++i) {
        EGLint count = 0;
        if (eglChooseConfig_p(g_EglDisplay, candidates[i], NULL, 0, &count) == EGL_TRUE && count > 0) {
            chosen_attrs = candidates[i];
            num_configs = count;
            break;
        }
    }
    if (chosen_attrs == NULL || num_configs == 0) {
        LOGE("eglChooseConfig_p() failed across all fallback profiles: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }

    eglChooseConfig_p(g_EglDisplay, chosen_attrs, &bundle->config, 1, &num_configs);
    eglGetConfigAttrib_p(g_EglDisplay, bundle->config, EGL_NATIVE_VISUAL_ID, &bundle->format);

    {
        const char *renderer = getenv("QOOKIX_RENDERER");
        EGLBoolean bindResult;
        if (renderer != NULL && strncmp(renderer, "opengles3_desktopgl", 19) == 0) {
            printf("EGLBridge: Binding to desktop OpenGL\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_API);
        } else {
            printf("EGLBridge: Binding to OpenGL ES\n");
            bindResult = eglBindAPI_p(EGL_OPENGL_ES_API);
        }
        if (!bindResult) printf("EGLBridge: bind failed: %p\n", (void *)(uintptr_t)eglGetError_p());
    }

    const char *libgl_es_env = getenv("LIBGL_ES");
    int libgl_es = libgl_es_env != NULL ? (int) strtol(libgl_es_env, NULL, 0) : 2;
    if(libgl_es < 0 || libgl_es > INT16_MAX) libgl_es = 2;
    EGLContext shared_ctx = share == NULL ? EGL_NO_CONTEXT : share->context;
    const EGLint egl_context_attributes[] = { EGL_CONTEXT_CLIENT_VERSION, libgl_es, EGL_NONE };
    bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, shared_ctx, egl_context_attributes);
    if (bundle->context == EGL_NO_CONTEXT && libgl_es >= 3) {
        LOGW("eglCreateContext_p() failed for ES %d (%04x), retrying ES 2", libgl_es, eglGetError_p());
        const EGLint es2_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
        bundle->context = eglCreateContext_p(g_EglDisplay, bundle->config, shared_ctx, es2_attrs);
    }

    if (bundle->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext_p() finished with error: %04x", eglGetError_p());
        free(bundle);
        return NULL;
    }
    return bundle;
}

void gl_swap_surface(gl_render_window_t* bundle) {
    if(bundle->nativeSurface != NULL) {
        ANativeWindow_release(bundle->nativeSurface);
    }
    if(bundle->surface != NULL) eglDestroySurface_p(g_EglDisplay, bundle->surface);
    if(bundle->newNativeSurface != NULL) {
        LOGI("Switching to new native surface");
        bundle->nativeSurface = bundle->newNativeSurface;
        bundle->newNativeSurface = NULL;
        ANativeWindow_acquire(bundle->nativeSurface);
        ANativeWindow_setBuffersGeometry(bundle->nativeSurface, 0, 0, bundle->format);
        bundle->surface = eglCreateWindowSurface_p(g_EglDisplay, bundle->config, bundle->nativeSurface, NULL);
    }else{
        LOGI("No new native surface, switching to 1x1 pbuffer");
        bundle->nativeSurface = NULL;
        const EGLint pbuffer_attrs[] = {EGL_WIDTH, 1 , EGL_HEIGHT, 1, EGL_NONE};
        bundle->surface = eglCreatePbufferSurface_p(g_EglDisplay, bundle->config, pbuffer_attrs);
    }
    //eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context);
}

void gl_make_current(gl_render_window_t* bundle) {

    if(bundle == NULL) {
        if(eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)) {
            currentBundle = NULL;
        }
        return;
    }
    bool hasSetMainWindow = false;
    if(pojav_environ->mainWindowBundle == NULL) {
        pojav_environ->mainWindowBundle = (basic_render_window_t*)bundle;
        LOGI("Main window bundle is now %p", pojav_environ->mainWindowBundle);
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
        hasSetMainWindow = true;
    }
    LOGI("Making current, surface=%p, nativeSurface=%p, newNativeSurface=%p", bundle->surface, bundle->nativeSurface, bundle->newNativeSurface);
    if(bundle->surface == NULL) { //it likely will be on the first run
        gl_swap_surface(bundle);
    }
    if(eglMakeCurrent_p(g_EglDisplay, bundle->surface, bundle->surface, bundle->context)) {
        currentBundle = bundle;
    }else {
        if(hasSetMainWindow) {
            pojav_environ->mainWindowBundle->newNativeSurface = NULL;
            gl_swap_surface((gl_render_window_t*)pojav_environ->mainWindowBundle);
            pojav_environ->mainWindowBundle = NULL;
        }
        LOGE("eglMakeCurrent returned with error: %04x", eglGetError_p());
    }

}

void gl_swap_buffers() {
    /* currentBundle 只在 gl_make_current() 里 makeCurrent 成功之后才被赋值。
     * 渲染线程若在「还没 makeCurrent / makeCurrent 失败」的窗口期被调到换帧
     * （SDL watchdog 重绑、窗口销毁时序都会造成这种窗口期），下一行就是
     * NULL->state → SIGSEGV。换帧在没 bundle 时本就无事可做，直接返回。 */
    if(currentBundle == NULL) return;
    if(currentBundle->state == STATE_RENDERER_NEW_WINDOW) {
        eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); //detach everything to destroy the old EGLSurface
        gl_swap_surface(currentBundle);
        eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
        currentBundle->state = STATE_RENDERER_ALIVE;
    }
    if(currentBundle->surface != NULL) {
        if(!eglSwapBuffers_p(g_EglDisplay, currentBundle->surface) && eglGetError_p() == EGL_BAD_SURFACE) {
            eglMakeCurrent_p(g_EglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            currentBundle->newNativeSurface = NULL;
            gl_swap_surface(currentBundle);
            eglMakeCurrent_p(g_EglDisplay, currentBundle->surface, currentBundle->surface, currentBundle->context);
            LOGI("The window has died, awaiting window change");
        }
        perf_frame(); /* 性能面板的帧计数（含 MobileGlues：EGL 就来自 MG 那份） */
    }

}

void gl_setup_window() {
    if(pojav_environ->mainWindowBundle != NULL) {
        LOGI("gl_setup_window: mainWindowBundle=%p pojavWindow=%p currentBundle=%p",
             pojav_environ->mainWindowBundle, pojav_environ->pojavWindow, currentBundle);
        LOGI("Main window bundle is not NULL, changing state");
        pojav_environ->mainWindowBundle->state = STATE_RENDERER_NEW_WINDOW;
        pojav_environ->mainWindowBundle->newNativeSurface = pojav_environ->pojavWindow;
    }
}

void gl_swap_interval(int swapInterval) {
    if(pojav_environ->force_vsync) swapInterval = 1;

    eglSwapInterval_p(g_EglDisplay, swapInterval);
}

JNIEXPORT void JNICALL
Java_org_lwjgl_opengl_PojavRendererInit_nativeInitGl4esInternals(JNIEnv *env, jclass clazz,
                                                            jobject function_provider) {
    LOGI("GL4ES internals initializing...");
    jclass funcProviderClass = (*env)->GetObjectClass(env, function_provider);
    jmethodID method_getFunctionAddress = (*env)->GetMethodID(env, funcProviderClass, "getFunctionAddress", "(Ljava/lang/CharSequence;)J");
#define GETSYM(N) ((*env)->CallLongMethod(env, function_provider, method_getFunctionAddress, (*env)->NewStringUTF(env, N)));

    void (*set_getmainfbsize)(void (*new_getMainFBSize)(int* width, int* height)) = (void*)GETSYM("set_getmainfbsize");
    if(set_getmainfbsize != NULL) {
        LOGI("GL4ES internals initialized dimension callback");
        set_getmainfbsize(gl4esi_get_display_dimensions);
        /* 顺手接管 GL_VENDOR：MC 的 F3「Display: WxH (…)」取的就是 glGetString(GL_VENDOR)。
           这里是 JVM 侧、由 LWJGL 的 Java 类调用进来，FindClass 才有正确的类加载器上下文
           （放在 JNI_OnLoad 里会崩，见 lwjgl_dlopen_hook.c 的说明）。
           TODO: 这一版实测启动会停在 `Backend library`，先摘掉调用保证游戏可用，
           等定位清楚（是调用时机还是 FindClass 本身）再启用。 */
        (void) env;
    }

#undef GETSYM
}
