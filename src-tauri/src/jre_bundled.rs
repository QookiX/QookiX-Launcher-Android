//! 随 APK 内置的 Java 运行时（2026-10-30）。
//!
//! ## 为什么要内置
//!
//! Java 运行时只从 GitHub Releases 拿，而那条线在国内被限得很死：真机实测
//! 同一部手机、同一时刻，Modrinth 那边 47 MB/s，这个 27 MB 的包只有 55 KB/s，
//! 几分钟后还会掉到 0.3 KB/s —— 连着一个多小时都下不完，用户看到的是
//! 「正在下载 Java 运行时」卡十分钟以上。
//!
//! 参考实现（Zalith Launcher 2）的做法是**根本不下载**：把运行时按
//! `assets/runtimes/jre-XX/{universal,bin-<arch>}.tar.xz` 打进 APK，安装后解压
//! （`UnpackJreTask`）。代价是它的 arm64 包有 172 MB。
//!
//! 这里只内置 **Java 21**（覆盖面最大的一档：MC 1.20.5 ~ 26.x 都用它），
//! 8 / 17 继续走下载 —— 为它们各加 27 MB 不值得。APK 因此从 48 MB 涨到约 75 MB，
//! 比 ZL2 仍然小 100 MB，但「进游戏前干等十分钟」这个坑直接消失。
//!
//! ## 失败必须静默回退
//!
//! 内置包损坏、APK 是 split（assets 不在第一个包里）、用户装的是 x86_64 版本……
//! 任何一条不成立都只是「这个优化用不上」，**不能让它挡住下载那条正常路径**，
//! 所以本模块一律返回 `Option`，`None` 就交给 `ensure_instance_java` 继续走网络。

use std::path::Path;

/// 内置的运行时版本。只做 21。
const BUNDLED_JAVA: i32 = 21;

/// 内置的 ABI。只打包了 arm64 的那一份（真机全是它）。
const BUNDLED_ARCH: &str = "arm64-v8a";

/// APK assets 里的目录：`android/app/src/main/assets/jre/jre-21/`。
const ASSET_DIR: &str = "jre/jre-21/";

/// 包名（与 CI 里下载的文件名一致）。
const ARCHIVE_NAME: &str = "jre21-android-arm64.tar.xz";

/// 尝试用内置包装好 `required` 版本的运行时，返回 `bin/java` 所在目录。
///
/// `None` = 内置包用不上（版本不对 / ABI 不对 / APK 里没有 / 解压失败），
/// 调用方应继续走下载。
pub async fn try_install(required: i32) -> Option<String> {
    if required != BUNDLED_JAVA || crate::java::get_device_arch() != BUNDLED_ARCH {
        return None;
    }

    let data_dir = crate::settings::data_dir_sync()?;
    let cache = Path::new(&data_dir).join("tmp").join("jre-bundled");

    // split APK 时 assets 只在 base 包里，而 `apk_paths()` 返回的顺序不保证，
    // 所以逐个试，第一个真正抽到包的就是。
    let mut archive = None;
    for apk in crate::android_env::apk_paths() {
        if crate::android_env::extract_asset_files(&apk, ASSET_DIR, &cache, true)
            .await
            .is_err()
        {
            continue;
        }
        let candidate = cache.join(ARCHIVE_NAME);
        if candidate.is_file() {
            archive = Some(candidate);
            break;
        }
    }
    let archive = archive?;

    crate::util::log_line("[jre] 使用随 APK 内置的 Java 21 运行时（跳过下载）");
    match crate::java::JreManager::install_from_archive(BUNDLED_JAVA, BUNDLED_ARCH, &archive).await {
        Ok(rt) => {
            // 临时目录只留一份副本，装完就清掉（用户可能反复删运行时）
            let _ = std::fs::remove_dir_all(&cache);
            Some(rt.path)
        }
        Err(e) => {
            // 内置包坏了不能挡住下载
            crate::util::log_line(&format!("[jre] 内置运行时安装失败，回退下载：{e}"));
            let _ = std::fs::remove_dir_all(&cache);
            None
        }
    }
}
