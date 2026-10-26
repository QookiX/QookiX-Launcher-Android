//! 构建脚本：Android 目标需要显式链接的系统库 + 16KB 页对齐。
//!
//! ⚠️ 2026-10-26 真机事故（OnePlus 8 / Android 16）：
//! CI 产出的 `libqookix_lib.so` 里 `ANativeWindow_fromSurface` 是**未定义符号**，
//! 而且 `NEEDED` 里只有 `libdl/libm/libc` —— 没有 `libandroid.so`。设备上一
//! `dlopen` 就失败：
//! ```text
//! dlopen failed: cannot locate symbol "ANativeWindow_fromSurface" referenced by libqookix_lib.so
//! ```
//! Kotlin 侧只看到 `No implementation found for ... nativeInvoke`，完全指不到真凶：
//! 整个 App 所有命令都失效，但界面照常渲染，看起来像"后端没响应"。
//!
//! 根因：这些链接参数以前只写在本机的 `src-tauri/.cargo/config.toml` 里，
//! 而**那个文件是 gitignore 的**（不进仓库）→ CI 从来没用上 → 发出去的包在
//! Android 15/16 上起不来，本地却一切正常（works on my machine）。
//!
//! 现在写进 build.rs：本地与 CI 走同一份链接参数。

fn main() {
    let target = std::env::var("TARGET").unwrap_or_default();
    if !target.contains("android") {
        return;
    }
    println!("cargo:rerun-if-changed=build.rs");

    // jni_bridge 里直接用了 ANativeWindow_* / EGL / GLES，日志走 liblog
    for lib in ["android", "log", "EGL", "GLESv2", "OpenSLES"] {
        println!("cargo:rustc-link-lib=dylib={lib}");
    }

    // Android 15+ 的 16KB 页设备：按 4KB 对齐的 .so 装不上/加载不了
    println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
}
