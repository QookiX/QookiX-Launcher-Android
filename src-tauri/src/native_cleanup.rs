use std::path::Path;

/// 清掉 native 目录里「APK 里已经没有」的 .so 残留，返回删除个数。
///
/// ## 为什么需要
///
/// 从 APK 抽取原生库时只会**覆盖同名文件**，不会删除目录里的其它文件。
/// 于是旧版本启动器留下的、而新 APK 里已经没有的那些 .so（各种 LWJGL 模块）
/// 会一直留在 `<data>/natives` 里参与 `System.loadLibrary`。
///
/// 这些残留是**用旧 NDK 编译**的，会引用 `__emutls_get_address` —— Android 16
/// 已经移除该符号，于是 `dlopen` 直接失败：
///
/// ```text
/// UnsatisfiedLinkError: liblwjgl_stb.so: dlopen failed:
///   cannot locate symbol "__emutls_get_address"
/// ```
///
/// 表现就是「清了数据、重装了启动器，某些版本还是崩」—— 因为清的是游戏数据，
/// 启动器自己的 `files/natives/` 里的残留还在。
///
/// ## 注意
///
/// 只在 APK 指纹变化时调用（见 `resolve_native_lib_dir` 的 `force`）。
/// `libSDL3.so` / `libqookix_lib.so` 这些「绝不能留副本」的库删掉副本正好，
/// 运行时用的是 APK 里那一份。
pub fn clear_stale_native_libs(dir: &Path) -> usize {
    let mut removed = 0usize;
    let Ok(rd) = std::fs::read_dir(dir) else {
        return 0;
    };
    for entry in rd.flatten() {
        let path = entry.path();
        if path.extension().and_then(|x| x.to_str()) == Some("so")
            && std::fs::remove_file(&path).is_ok()
        {
            removed += 1;
        }
    }
    removed
}
