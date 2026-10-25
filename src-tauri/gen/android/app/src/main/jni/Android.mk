LOCAL_PATH := $(call my-dir)
HERE_PATH := $(LOCAL_PATH)

# ============================================================================
# 16 KB 页对齐（Android 15+ 的 16 KB 页设备）
#
# NDK 默认按 4 KB 页对齐（PT_LOAD.p_align = 0x1000）。在 16 KB 页设备上
# dlopen 这样的 .so 会直接失败（`has invalid ELF alignment`）。
# 这个项目里**我们自己编译**的三个库都必须带下面这个链接参数；
# 随包分发的上游预编译库（GL4ES / Mesa / LWJGL…）仍是 4 KB，见 KNOWN_ISSUES.md。
# ============================================================================
PAGE_ALIGN_LDFLAGS := -Wl,-z,max-page-size=16384

# ============================================================================
# Pojav 版 JNI 核心（libpojavexec.so）
#
# 来自 PojavLauncher（app_pojavlauncher/src/main/jni），原样保留：
#   - egl_bridge.c / ctxbridges/*：把 Android Surface 接成 GL4ES 能用的 EGL 上下文，
#     并实现 OpenGL 函数表转发（这是安卓上跑 MC 的关键一层）
#   - input_bridge_v3.c：JNI_OnLoad 里给 org.lwjgl.glfw.CallbackBridge 注册输入相关 native
#   - jvm_hooks/*：dlopen / forkAndExec 等补丁（绕过安卓限制）
#   - driver_helper/nsbypass.c：绕过 linker namespace 直取 GPU 驱动
#
# 与 Pojav 原版的差异（有意为之）：
#   1. 去掉 exithook / linkerhook / pojavexec_awt / 假 awt_headless / 假 awt_xawt
#      这几个模块：前两个依赖 bytehook 且只做退出与权限 hook；
#      后三个是 Caciocavallo(AWT) 专用，当前 launch.rs 里 ENABLE_CACIOCAVALLO=false。
#   2. 不 import prefab/bytehook（已无使用者）。
#
# 模块名必须叫 pojavexec：Lwjglglf stub 里是按 System.loadLibrary("pojavexec") 找的。
# ============================================================================

include $(CLEAR_VARS)
LOCAL_LDLIBS := -ldl -llog -landroid

LOCAL_MODULE := pojavexec
LOCAL_SRC_FILES := \
    bigcoreaffinity.c \
    egl_bridge.c \
    ctxbridges/loader_dlopen.c \
    ctxbridges/gl_bridge.c \
    ctxbridges/osm_bridge.c \
    ctxbridges/egl_loader.c \
    ctxbridges/osmesa_loader.c \
    ctxbridges/swap_interval_no_egl.c \
    environ/environ.c \
    jvm_hooks/emui_iterator_fix_hook.c \
    jvm_hooks/java_exec_hooks.c \
    jvm_hooks/lwjgl_dlopen_hook.c \
    input_bridge_v3.c \
    jre_launcher.c \
    utils.c \
    stdio_is.c \
    perf_counters.c \
    lwjgl_libffi_shim.c \
    driver_helper/nsbypass.c

ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_CFLAGS += -DADRENO_POSSIBLE
endif
LOCAL_LDFLAGS += $(PAGE_ALIGN_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

# ============================================================================
# linkerhook：Zink + Turnip 路径的必需件。
#
# libpojavexec 的 egl_bridge.c 在 load_turnip_vulkan() 里会
#   linker_ns_dlopen("liblinkerhook.so")
# 拿不到它就**静默返回 NULL**（不打印任何东西）→ Turnip 永不加载 →
# Mesa 退回系统 Vulkan 驱动 → 在 OSMesaCreateContext 里 SIGSEGV。
# 移植时漏了这个模块，所以「渲染器切 Zink」一直是必崩。
# `-z global` 是让它的符号对 linker 可见（hook 需要被 android_dlopen_ext 调回来）。
# ============================================================================
include $(CLEAR_VARS)
LOCAL_MODULE := linkerhook
LOCAL_SRC_FILES := driver_helper/hook.c
LOCAL_LDFLAGS := -z global $(PAGE_ALIGN_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)

# ============================================================================
# libthreaddump.so — JVM TI 线程栈抓取 agent（诊断用）。
#
# 内嵌 HotSpot 的 Java 线程栈在安卓上用常规手段抓不到（SIGQUIT 被 ART 拦、
# jdwp 挂死，详见文件头注释），所以用 -agentpath 让游戏 JVM 加载它：
# 它在 127.0.0.1:12857 起服务，连上就吐 GetAllStackTraces。
# 排查「加载屏不退场」这类需要看 Render thread / KubeJS 线程栈的问题用的。
# launch.rs 里在 files/natives 发现这份 .so 时自动加 -agentpath。
# ============================================================================
include $(CLEAR_VARS)
LOCAL_MODULE := threaddump
LOCAL_SRC_FILES := thread_dump_agent.c
LOCAL_LDLIBS := -ldl -llog
LOCAL_LDFLAGS += $(PAGE_ALIGN_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)
