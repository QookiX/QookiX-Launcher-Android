# ARM64 16KB 原生库重建

本目录保存7个上游仓库的固定提交、Android兼容补丁和构建入口，生成12个候选共享库（10 个主 JNI 库 + lwjgl-spng / lwjgl-vma）。仅支持arm64-v8a / Android API24；不会自动替换项目中已有的库。

```powershell
python scripts/native-16k/rebuild.py --ndk L:/Android/Sdk/ndk/27.0.12077973 --work build/native-16k-clean --prepare
```

需要Python3、Git、CMake、Ninja和NDK。源码拉取和构建只写入指定work目录；prepare要求各源码子目录尚不存在，避免覆盖维护者修改。sources.json固定源码提交，patches记录本项目改动。

产物：work/dist/arm64-v8a，manifest.json记录SHA256与每个LOAD段的对齐。构建入口要求全部LOAD段至少16384且架构为AArch64，否则失败。可用既有已准备好的源码重编译（不传prepare）。

落进dist前会用NDK的llvm-strip `--strip-debug` 去掉调试节：CMake的Release默认仍带`.debug_*`，上一轮没做这一步时10个库从12MB涨到91MB（`libspirv-cross-c-shared.so`单库42MB，其中36MB是调试信息）。`--strip-unneeded`不删调试节，必须用`--strip-debug`。

兼容处理：LWJGL3.4.1保留3.3.3旧JNI签名、Callback、NanoVG Blendish/OUI与STB旧缩放实现；旧errno接口用线程局部缓存，避免读取新版EnvData已删除字段。libffi采用3.5.2源码，Android ARM64配置以AOSP external/libffi为基础，long double设为16字节。SDL3.4.16保留旧动态API入口并补JNI声明。GL4ES采用Pojav fork，保留Android GLX入口并修正旧空实现返回类型；两个minmax参数入口延续旧库的GL_INVALID_VALUE行为。

这是本地兼容版本，不能称为未修改的上游产物。LWJGL BSD-3-Clause、SDL zlib、FreeType FTL/GPL双许可、OpenAL Soft LGPL、SPIRV-Cross Apache-2.0/MIT、GL4ES MIT；授权原文保留在各固定源码仓库中。AOSP配置出处为 https://android.googlesource.com/platform/external/libffi 。

SPNG/VMA已纳入重启：`spng.c`内嵌上游实现（只用zlib标准API，无`mz_*`），但LWJGL生成文件强制`#define SPNG_USE_MINIZ`，因此用`shim/miniz.h`转发到系统zlib；vma是header-only（`vk_mem_alloc.h`随lwjgl3），JNI为C++，Vulkan头取自NDK sysroot。

编译、导出符号和段对齐验证不能代替16KB设备上的实际游戏测试。仍未覆盖x86_64，也未重建额外的插件库和`libshaderc.so`（后者要额外拉glslang与SPIRV-Tools，且只有走Vulkan后端的版本才会加载）。

AOSP配置基线提交：`1fd5a2e1d639cd1ddf29dd0c484c123bbd850c21`。
