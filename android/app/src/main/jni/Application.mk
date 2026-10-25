# NDK_TOOLCHAIN_VERSION := 4.9
APP_PLATFORM := android-21
APP_STL := system
# arm64-v8a：真机；x86_64：Android Studio 的模拟器镜像。
APP_ABI := arm64-v8a x86_64

# ── 16 KB 页对齐 ──────────────────────────────────────────────────────
# Android 15+ 出现 16 KB 内存页的设备，NDK 默认按 4 KB 对齐编译出来的 .so
# 在这些设备上 dlopen 会失败；Google Play 对 targetSdk ≥ 35 也要求 16 KB 对齐。
# 这里对所有 ndkBuild 模块统一加上。注意本机（OnePlus 8）是 4 KB 页，测不出来。
APP_LDFLAGS := -Wl,-z,max-page-size=16384
