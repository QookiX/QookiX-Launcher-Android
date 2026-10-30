//! 加载器 profile → classpath 的拼装规则。
//!
//! 从 `loader_install` 里拆出来的：那边负责「装 / 找 / 解析 profile」，
//! 这边负责「profile 里的哪些库该进 classpath」。两边混在一起时，
//! `loader_install.rs` 已经超过体量预算，而剔除规则本身还在长。

use std::path::{Path, PathBuf};

/// 收集加载器 profile 里声明的库路径（相对 `libraries/`），供 classpath 使用。
///
/// 只收存在的文件：installer 声明的依赖可能还没下载，
/// 缺失的交给「补全文件」流程，classpath 里放不存在的路径会让 JVM 静默忽略
/// 但日志难查，所以这里直接过滤掉并计数返回。
///
/// 桌面版 LWJGL（`org.lwjgl:*`）在这里就剔掉，原因见 [`is_desktop_lwjgl`]。
pub fn profile_library_paths(
    profile_dir: &Path,
    libraries_dir: &Path,
) -> (Vec<PathBuf>, usize) {
    let mut paths = Vec::new();
    let mut missing = 0usize;
    let mut skipped_lwjgl = 0usize;
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return (paths, missing);
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        return (paths, missing);
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return (paths, missing);
    };
    let Some(libs) = v.get("libraries").and_then(|l| l.as_array()) else {
        return (paths, missing);
    };
    for lib in libs {
        let name = lib.get("name").and_then(|n| n.as_str()).unwrap_or("");
        if is_desktop_lwjgl(name) {
            skipped_lwjgl += 1;
            continue;
        }
        let Some(path_rel) = lib
            .get("downloads")
            .and_then(|d| d.get("artifact"))
            .and_then(|a| a.get("path"))
            .and_then(|p| p.as_str())
        else {
            continue;
        };
        let full = libraries_dir.join(path_rel);
        if full.exists() {
            paths.push(full);
        } else {
            missing += 1;
        }
    }
    if skipped_lwjgl > 0 {
        crate::util::log_line(&format!(
            "[loader] 跳过 {skipped_lwjgl} 个加载器声明的桌面 LWJGL 条目（安卓上用随包组件）"
        ));
    }
    (paths, missing)
}

/// 读加载器 profile 里所有库的 Maven 坐标（`group:artifact:version`）。
///
/// 用途：启动时把原版 classpath 里**同名 artifact** 的旧版本剔掉，
/// 避免「同一份类的两个版本同时在 classpath 上」——
/// Fabric 会直接抛 `duplicate ASM classes found on classpath`。
///
/// 桌面 LWJGL 不进这个名单：原版那份在 `launch.rs::build_classpath` 里已经
/// 无条件跳过了，留在这里只会让剔除逻辑去匹配一个本来就没人加的条目。
pub fn profile_library_names(profile_dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return out;
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        return out;
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return out;
    };
    let Some(libs) = v.get("libraries").and_then(|l| l.as_array()) else {
        return out;
    };
    for lib in libs {
        if let Some(n) = lib.get("name").and_then(|n| n.as_str()) {
            if !n.is_empty() && !is_desktop_lwjgl(n) {
                out.push(n.to_string());
            }
        }
    }
    out
}

/// 加载器 profile 里声明的桌面 LWJGL（`org.lwjgl:lwjgl*`）。
///
/// 这些是桌面版 natives，安卓上根本跑不起来；把它们留在 classpath 上只有一种
/// 结果 —— 和随包的 LWJGL 组件（老版本的单体 jar / 26.3+ 的 3.4.1 组件集）
/// 同时存在两份 `org.lwjgl.*`：
///
/// - 26.2 + NeoForge 实测 `NoSuchMethodError: GLFW.glfwPlatformSupported(int)`：
///   MC 26.x 要的是 3.4 的 API，被加载的却是没这个方法的旧 jar（崩溃报告里
///   `Backend library: LWJGL version 3.3.3-snapshot`）。
/// - 26.1 + NeoForge 实测 `GLFW error callback has unexpectedly changed`：
///   fork 里 `glfwSetErrorCallback` 只是覆盖静态字段 `mGLFWErrorCallback`，
///   两份 `GLFW` 类各有各的静态字段，MC 26.x 的 `GLFWErrorScope` 一比对就炸。
///
/// 原版 classpath 早就在 `launch.rs::build_classpath` 里做过同样的剔除，
/// 加载器这一侧一直漏着。
fn is_desktop_lwjgl(name: &str) -> bool {
    name.starts_with("org.lwjgl:") || name.starts_with("org.lwjgl.lwjgl:")
}

/// 本次启动实际用的 LWJGL 组件（写进启动日志）。
///
/// 26.x 报 `glfwPlatformSupported` / GLFW 回调异常时，第一件事就是确认这一行：
/// 走老 jar 的分支以前完全无声，等于把结论藏起来了。
pub fn lwjgl_choice_label(use_lwjgl341: bool) -> &'static str {
    if use_lwjgl341 {
        "3.4.1 SDL3 组件集"
    } else {
        "随包单体 jar（版本清单要求 <3.4.1）"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── 桌面 LWJGL 剔除（26.2 glfwPlatformSupported / 26.1 错误回调被换）──
    #[test]
    fn desktop_lwjgl_entries_are_recognised() {
        assert!(is_desktop_lwjgl("org.lwjgl:lwjgl:3.3.3"));
        assert!(is_desktop_lwjgl("org.lwjgl:lwjgl-glfw:3.3.3"));
        assert!(is_desktop_lwjgl("org.lwjgl.lwjgl:lwjgl:2.9.4"));
        assert!(!is_desktop_lwjgl("net.neoforged:neoforge:26.2.0"));
        assert!(!is_desktop_lwjgl("cpw.mods:securejarhandler:3.0.8"));
        assert!(!is_desktop_lwjgl(""));
    }

    #[test]
    fn profile_library_names_skip_desktop_lwjgl() {
        let dir = std::env::temp_dir().join("qookix-test-profile-libs");
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let profile = dir.join("neoforge-26.2.0");
        std::fs::create_dir_all(&profile).unwrap();
        std::fs::write(
            profile.join("neoforge-26.2.0.json"),
            r#"{"libraries":[
                {"name":"org.lwjgl:lwjgl-glfw:3.3.3"},
                {"name":"org.lwjgl:lwjgl:3.3.3"},
                {"name":"net.neoforged:neoforge:26.2.0"}
            ]}"#,
        )
        .unwrap();
        let names = profile_library_names(&profile);
        assert_eq!(names, vec!["net.neoforged:neoforge:26.2.0".to_string()]);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
