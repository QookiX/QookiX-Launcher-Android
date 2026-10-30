/*
 * miniz 转发头（给 lwjgl-spng 用）。
 *
 * 为什么需要它：LWJGL 的生成文件
 * `spng/src/generated/c/org_lwjgl_util_spng_SPNG.c` 里写死了
 * `#define SPNG_USE_MINIZ` 再 `#include "spng.c"`，于是 spng.c 会去找
 * `<miniz.h>`。而 miniz 在 lwjgl3 里是 thirdparty submodule，我们的
 * checkout 没拉（也不该为几个符号多引一份第三方源码）。
 *
 * 能这么绕过去的原因：spng.c 里**一处 `mz_*` 都没有**，全程只用 zlib 的
 * 标准名字（`compress2` / `deflate*` / `inflate*` / `crc32` / `z_stream`）。
 * miniz 本身就是"提供 zlib 兼容 API"的单文件实现，所以把 miniz.h 指向系统
 * zlib 即可 —— 安卓 NDK 自带 libz，链接时加 `-lz` 就行。
 *
 * 只在 `lwjgl_spng` 这个 target 的 include 路径里生效（见 CMakeLists.txt），
 * 不会影响其它库。
 */
#pragma once

#include <zlib.h>
