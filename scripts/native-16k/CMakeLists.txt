cmake_minimum_required(VERSION 3.16)
project(qookix_lwjgl_bindings C CXX ASM)
set(LW "${NATIVE_SOURCE_ROOT}/lwjgl3/modules/lwjgl")
set(CMAKE_C_STANDARD 11)
add_compile_definitions(LWJGL_LINUX LWJGL_arm64 _GNU_SOURCE)
set(FFI "${NATIVE_SOURCE_ROOT}/libffi")
add_library(ffi STATIC ${FFI}/src/closures.c ${FFI}/src/debug.c ${FFI}/src/tramp.c ${FFI}/src/java_raw_api.c ${FFI}/src/prep_cif.c ${FFI}/src/raw_api.c ${FFI}/src/types.c ${FFI}/src/aarch64/ffi.c ${FFI}/src/aarch64/sysv.S)
set_target_properties(ffi PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(ffi PUBLIC "${FFI}/include" "${FFI}/src/aarch64")
file(GLOB CORE "${LW}/core/src/main/c/*.c" "${LW}/core/src/generated/c/*.c" "${LW}/core/src/generated/c/linux/*.c" "${LW}/core/src/main/c/linux/liburing/*.c")
add_library(lwjgl_core SHARED ${CORE})
set_target_properties(lwjgl_core PROPERTIES OUTPUT_NAME lwjgl)
target_include_directories(lwjgl_core PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/core/src/main/c/linux/liburing" "${LW}/core/src/main/c/linux/liburing/include")
target_link_libraries(lwjgl_core PRIVATE ffi dl m log)
target_link_options(lwjgl_core PRIVATE "-Wl,-z,max-page-size=16384")
file(GLOB S_opengl "${LW}/opengl/src/generated/c/*.c")
list(FILTER S_opengl EXCLUDE REGEX "WGL")
add_library(lwjgl_opengl SHARED ${S_opengl} ${EXTRA_opengl})
target_include_directories(lwjgl_opengl PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/opengl/src/main/c" "${LW}/stb/src/main/c")
target_link_libraries(lwjgl_opengl PRIVATE lwjgl_core dl m log)
target_link_options(lwjgl_opengl PRIVATE "-Wl,-z,max-page-size=16384")
file(GLOB S_nanovg "${LW}/nanovg/src/generated/c/*.c")
add_library(lwjgl_nanovg SHARED ${S_nanovg} ${EXTRA_nanovg})
target_include_directories(lwjgl_nanovg PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/nanovg/src/main/c" "${LW}/stb/src/main/c")
target_link_libraries(lwjgl_nanovg PRIVATE lwjgl_core dl m log)
target_link_options(lwjgl_nanovg PRIVATE "-Wl,-z,max-page-size=16384")
file(GLOB S_stb "${LW}/stb/src/generated/c/*.c")
add_library(lwjgl_stb SHARED ${S_stb} ${EXTRA_stb})
target_include_directories(lwjgl_stb PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/stb/src/main/c" "${LW}/stb/src/main/c")
target_link_libraries(lwjgl_stb PRIVATE lwjgl_core dl m log)
target_link_options(lwjgl_stb PRIVATE "-Wl,-z,max-page-size=16384")
file(GLOB S_tinyfd "${LW}/tinyfd/src/generated/c/*.c")
file(GLOB EXTRA_tinyfd "${LW}/tinyfd/src/main/c/*.c")
add_library(lwjgl_tinyfd SHARED ${S_tinyfd} ${EXTRA_tinyfd})
target_include_directories(lwjgl_tinyfd PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/tinyfd/src/main/c" "${LW}/stb/src/main/c")
target_link_libraries(lwjgl_tinyfd PRIVATE lwjgl_core dl m log)
target_link_options(lwjgl_tinyfd PRIVATE "-Wl,-z,max-page-size=16384")

# spng：LWJGL 把上游 libspng 实现内嵌在 modules/lwjgl/spng/src/main/c/spng.c，
# 只依赖系统 zlib，不额外拉源码。这两份是随包组件里长期未对齐的那批之一。
# 只编 generated：其中的 SPNG.c 已经 `#include "spng.c"`，再把 main/c 的 spng.c
# 单独编一次会出现 duplicate symbol（spng_decode_scanline 等）。
file(GLOB S_spng "${LW}/spng/src/generated/c/*.c")
add_library(lwjgl_spng SHARED ${S_spng})
# shim/ 提供 miniz.h → zlib 的转发（生成文件强制 SPNG_USE_MINIZ，见 shim/miniz.h 注释）
target_include_directories(lwjgl_spng PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/spng/src/main/c" "${CMAKE_CURRENT_SOURCE_DIR}/shim")
# spng.c 把 size_t 版的 malloc 回调塞进 zlib 的 alloc_func（uInt 参数）：
# 这是它按 miniz 写的假设，clang 15+ 默认把该警告当错误。32 位截断只在单次
# 分配 >4GB 时才可能出问题，PNG 场景不成立，这里明确放宽。
target_compile_options(lwjgl_spng PRIVATE "-Wno-incompatible-function-pointer-types")
target_link_libraries(lwjgl_spng PRIVATE lwjgl_core z dl m log)
target_link_options(lwjgl_spng PRIVATE "-Wl,-z,max-page-size=16384")

# vma：Vulkan Memory Allocator，header-only（vk_mem_alloc.h 随 lwjgl3 一起，
# 735KB 的 VMA_IMPLEMENTATION），JNI 层是 C++；Vulkan 头来自 NDK sysroot。
# 静态/动态 Vulkan 函数表都关掉（VMA_*_VULKAN_FUNCTIONS 0），运行时由 Java 传入。
file(GLOB S_vma "${LW}/vma/src/generated/c/*.cpp")
add_library(lwjgl_vma SHARED ${S_vma})
target_include_directories(lwjgl_vma PRIVATE "${LW}/core/src/main/c" "${LW}/core/src/main/c/linux" "${LW}/vma/src/main/c")
target_link_libraries(lwjgl_vma PRIVATE lwjgl_core dl m log)
target_link_options(lwjgl_vma PRIVATE "-Wl,-z,max-page-size=16384")