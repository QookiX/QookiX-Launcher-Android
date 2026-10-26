use std::ffi::CString;
use std::os::raw::c_char;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use anyhow::Result;
use libloading::Library;

#[derive(Debug, thiserror::Error)]
pub enum LaunchError {
    #[error("DlopenFailed: {0}")]
    DlopenFailed(String),
    #[error("DlsymFailed: {0}")]
    DlsymFailed(String),
    #[error("JliLaunchFailed: exit code {0}")]
    JliLaunchFailed(i32),
    #[error("JVM_CREATE_FAILED: {0}")]
    JvmCreateFailed(String),
}

/// `JLI_Launch` 的**完整 14 参**签名。
///
/// 这里曾经只声明 4 个参数 —— 那是未定义行为：AAPCS64 下 x4..x7 与栈上的后 6 个参数
/// 都是上一次调用的残值，`appclassc` 一旦为正就会按野指针遍历 `appclassv`。
/// 之所以"看起来能用"，只是因为残值恰好是 0/负数。签名与调用点必须与
/// `gen/android/app/src/main/jni/jre_launcher.c` 里的 C 包装完全一致。
type JliLaunchFunc = unsafe extern "C" fn(
    argc: i32,
    argv: *const *const c_char,
    jargc: i32,
    jargv: *const *const c_char,
    appclassc: i32,
    appclassv: *const *const c_char,
    fullversion: *const c_char,
    dotversion: *const c_char,
    pname: *const c_char,
    lname: *const c_char,
    javaargs: u8,
    cpwildcard: u8,
    javaw: u8,
    ergo: i32,
) -> i32;

struct JvmState {
    library: Option<Library>,
    /// 本次启动使用的 JRE 根目录。停游戏时要按绝对路径去 dlopen
    /// `lib/server/libjvm.so`，不能只靠裸文件名（安卓 linker 不查 JRE 的 lib 目录）。
    jre_home: Option<String>,
    exit_requested: AtomicBool,
}

impl JvmState {
    fn new() -> Self {
        Self {
            library: None,
            jre_home: None,
            exit_requested: AtomicBool::new(false),
        }
    }
}

static JVM_STATE: OnceLock<Mutex<JvmState>> = OnceLock::new();

fn init_jvm_state() {
    JVM_STATE.get_or_init(|| Mutex::new(JvmState::new()));
}

pub struct JvmLauncher;

impl JvmLauncher {
    pub fn launch(
        jre_home: &Path,
        jvm_args: &[String],
        classpath: &str,
        main_class: &str,
        game_args: &[String],
        _game_dir: &str,
    ) -> Result<i32> {
        init_jvm_state();

        let libjli_path = Self::find_libjli(jre_home)?;

        let library = unsafe { Library::new(&libjli_path) }
            .map_err(|e| LaunchError::DlopenFailed(format!("{}: {}", libjli_path.display(), e)))?;

        let jli_launch_ptr: JliLaunchFunc = unsafe {
            *library.get::<JliLaunchFunc>(b"JLI_Launch\0")
                .map_err(|e| LaunchError::DlsymFailed(format!("JLI_Launch: {}", e)))?
        };

        {
            let mut state = crate::util::cell_lock(&JVM_STATE).unwrap();
            state.library = Some(library);
            state.jre_home = Some(jre_home.to_string_lossy().to_string());
            state.exit_requested.store(false, Ordering::Release);
        }

        let mut all_args: Vec<String> = Vec::new();
        all_args.push("java".to_string());
        all_args.extend(jvm_args.iter().cloned());
        all_args.push("-cp".to_string());
        all_args.push(classpath.to_string());
        all_args.push(main_class.to_string());
        all_args.extend(game_args.iter().cloned());

        let c_strings: Vec<CString> = all_args
            .iter()
            .map(|s| {
                CString::new(s.as_str()).unwrap_or_else(|_| {
                    tracing::warn!("Argument contains NUL byte, replacing with empty: {}", s);
                    CString::new("").unwrap()
                })
            })
            .collect();

        let c_ptrs: Vec<*const c_char> = c_strings.iter().map(|s| s.as_ptr()).collect();

        let argc = c_ptrs.len() as i32;
        let argv = c_ptrs.as_ptr();

        // 一行摘要就够：排查启动问题时最需要知道"传了多少条 classpath"，
        // 而不是把 6KB 的路径全打出来（日志会变得没法读）。
        //
        // ⚠️ 用 println! 而不是 tracing::info!：tracing 在本项目**没有接到
        // logcat 或启动日志文件**上，写了等于没写（真机排查时踩到）。
        println!(
            "[launcher] JLI_Launch argc={argc} classpath 条目={}",
            classpath.matches(':').count() + 1
        );

        // 版本字符串只用于 java.version / java.fullversion 这类信息性属性，
        // 从 JRE 自带的 release 文件里取真实值（取不到时退回 "unknown"）。
        let (full_version, dot_version) = Self::read_jre_version(jre_home);
        let c_full_version = CString::new(full_version).unwrap_or_default();
        let c_dot_version = CString::new(dot_version).unwrap_or_default();
        let c_prog_name = c_strings
            .first()
            .cloned()
            .unwrap_or_else(|| CString::new("java").unwrap());

        let exit_code = unsafe {
            jli_launch_ptr(
                argc,
                argv,
                0,
                std::ptr::null(), // jargc / jargv：JAVA_ARGS，这里没有
                0,
                std::ptr::null(), // appclassc / appclassv：没有 app classpath
                c_full_version.as_ptr(),
                c_dot_version.as_ptr(),
                c_prog_name.as_ptr(),
                c_prog_name.as_ptr(),
                0, // javaargs  = JNI_FALSE
                1, // cpwildcard = JNI_TRUE（与 Pojav 的 C 包装一致；实测改 false 无效，见报告）
                0, // javaw     = JNI_FALSE
                0, // ergo      = DEFAULT_POLICY
            )
        };

        if exit_code != 0 {
            return Err(LaunchError::JliLaunchFailed(exit_code).into());
        }

        Ok(exit_code)
    }

    /// 从 `<jre_home>/release` 里读 `JAVA_VERSION="17.0.8"`，返回 (完整版本, 点分版本)。
    fn read_jre_version(jre_home: &Path) -> (String, String) {
        let mut full = String::from("unknown");
        if let Ok(text) = std::fs::read_to_string(jre_home.join("release")) {
            for line in text.lines() {
                if let Some(rest) = line.strip_prefix("JAVA_VERSION=") {
                    full = rest.trim().trim_matches('"').to_string();
                    break;
                }
            }
        }
        let dot = full.split('+').next().unwrap_or(full.as_str()).to_string();
        (full, dot)
    }

    fn find_libjli(jre_home: &Path) -> Result<std::path::PathBuf> {
        // 两种布局都要认（与 java.rs::has_libjli 保持一致）：
        //   JDK 9+ 扁平：lib/<arch>/libjli.so、lib/libjli.so
        //   **JDK 8 嵌套**：lib/<arch>/jli/libjli.so  ← Pojav-JRE-8 就是这种，
        //                   以前只找扁平路径，1.8.9 会直接找不到 libjli.so
        let mut candidates = Vec::new();
        for arch in ["arm64", "arm32", "aarch64", "arm", "x86_64", "x86", "amd64"] {
            candidates.push(jre_home.join("lib").join(arch).join("libjli.so"));
            candidates.push(
                jre_home
                    .join("lib")
                    .join(arch)
                    .join("jli")
                    .join("libjli.so"),
            );
        }
        candidates.push(jre_home.join("lib").join("libjli.so"));

        for candidate in &candidates {
            if candidate.exists() {
                return Ok(candidate.clone());
            }
        }

        Err(LaunchError::DlopenFailed(format!(
            "libjli.so not found in {} (checked {} candidates)",
            jre_home.display(),
            candidates.len()
        )).into())
    }

    pub fn shutdown() -> Result<()> {
        init_jvm_state();

        let jre_home = {
            let state = crate::util::cell_lock(&JVM_STATE).unwrap();
            state.exit_requested.store(true, Ordering::Release);
            state.jre_home.clone()
        };

        tracing::info!("JVM shutdown requested");

        if Self::request_jvm_exit(jre_home.as_deref()) {
            tracing::info!("已请求游戏 JVM 执行 System.exit(0)");
        } else {
            tracing::warn!("未能请求 JVM 退出（可能尚未启动，或找不到属于我们的 JavaVM）");
        }

        // 故意**不** dlclose libjli.so：JVM 还在跑 shutdown hook / 最后几帧，
        // 提前卸载会让它的后续调用跳进已释放的代码段。句柄留在 state 里，
        // 随进程退出一起释放。
        Ok(())
    }

    /// 请求游戏 JVM 自行退出（跑 shutdown hook，Minecraft 才有机会存档 / 关世界）。
    ///
    /// 这里有三个踩过的坑，缺一不可：
    ///
    /// 1. **只从 `libjvm.so` / `libjli.so` 取 VM**。绝不能查 `libart.so` —— 它同样导出
    ///    `JNI_GetCreatedJavaVMs`，返回的是**安卓应用自身**的 VM；对它调 `DestroyJavaVM`
    ///    等于把整个应用的运行时拆掉（原来的实现就是先试 libart，表现是「点强制关闭，
    ///    应用直接挂 / 黑屏」）。
    /// 2. 用 `System.exit(0)` 而不是 `DestroyJavaVM`：后者会一直等到所有非守护线程结束，
    ///    而 Minecraft 的渲染线程不会主动退出 → 卡死。
    /// 3. `jni::JavaVM` 句柄用完必须 `mem::forget`：它的 `Drop` 会调用 `DestroyJavaVM`。
    fn request_jvm_exit(jre_home: Option<&str>) -> bool {
        let mut candidates: Vec<std::path::PathBuf> = Vec::new();
        if let Some(home) = jre_home {
            let home = Path::new(home);
            candidates.push(home.join("lib").join("server").join("libjvm.so"));
            candidates.push(home.join("lib").join("libjli.so"));
            candidates.push(home.join("lib").join("arm64").join("libjli.so"));
        }

        let mut attempts: Vec<String> = candidates
            .iter()
            .map(|p| p.to_string_lossy().to_string())
            .collect();
        // 兜底：库已经在进程里了，裸文件名通常也能命中（安卓 linker 会先查已加载库）
        attempts.push("libjvm.so".to_string());
        attempts.push("libjli.so".to_string());

        for name in attempts {
            let lib = unsafe { Library::new(&name) };
            let lib = match lib {
                Ok(l) => l,
                Err(_) => continue,
            };

            let get_vms = unsafe {
                lib.get::<unsafe extern "C" fn(
                    *mut *mut std::ffi::c_void,
                    jni::sys::jsize,
                    *mut jni::sys::jsize,
                ) -> jni::sys::jint>(b"JNI_GetCreatedJavaVMs\0")
            };
            let Ok(get_vms) = get_vms else { continue };

            let mut vm_ptr: *mut std::ffi::c_void = std::ptr::null_mut();
            let mut n_vms: jni::sys::jsize = 0;
            if unsafe { get_vms(&mut vm_ptr, 1, &mut n_vms) } != 0 || n_vms <= 0 || vm_ptr.is_null() {
                continue;
            }

            let vm = match unsafe { jni::JavaVM::from_raw(vm_ptr.cast()) } {
                Ok(vm) => vm,
                Err(e) => {
                    tracing::warn!("{name}: JavaVM::from_raw 失败：{e}");
                    continue;
                }
            };

            let ok = match vm.attach_current_thread() {
                Ok(mut env) => match env.call_static_method(
                    "java/lang/System",
                    "exit",
                    "(I)V",
                    &[jni::objects::JValue::Int(0)],
                ) {
                    Ok(_) => {
                        tracing::info!("{name}: 已对该 VM 调用 System.exit(0)");
                        true
                    }
                    Err(e) => {
                        tracing::warn!("{name}: 调用 System.exit 失败：{e}");
                        false
                    }
                },
                Err(e) => {
                    tracing::warn!("{name}: attach_current_thread 失败：{e}");
                    false
                }
            };

            // 绝不能 Drop：那会 DestroyJavaVM
            std::mem::forget(vm);
            if ok {
                return true;
            }
        }
        false
    }
    pub fn is_exit_requested() -> bool {
        if let Some(state) = JVM_STATE.get() {
            let state = crate::util::lock_recover(state);
            state.exit_requested.load(Ordering::Acquire)
        } else {
            false
        }
    }
}

#[cfg(target_os = "android")]
pub fn setup_signal_handler() {
    use nix::sys::signal::{self, Signal};

    extern "C" fn handle_sigabrt(_sig: nix::libc::c_int) {
        tracing::error!("JVM SIGABRT received - JVM crashed");
    }

    unsafe {
        let _ = signal::signal(Signal::SIGABRT, signal::SigHandler::Handler(handle_sigabrt));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_find_libjli_in_arm64_subdir() {
        let dir = tempdir().unwrap();
        let lib_dir = dir.path().join("lib").join("arm64");
        std::fs::create_dir_all(&lib_dir).unwrap();
        std::fs::write(lib_dir.join("libjli.so"), b"fake").unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_ok());
        assert!(result.unwrap().to_string_lossy().contains("arm64"));
    }

    #[test]
    fn test_find_libjli_in_arm32_subdir() {
        let dir = tempdir().unwrap();
        let lib_dir = dir.path().join("lib").join("arm32");
        std::fs::create_dir_all(&lib_dir).unwrap();
        std::fs::write(lib_dir.join("libjli.so"), b"fake").unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_ok());
        assert!(result.unwrap().to_string_lossy().contains("arm32"));
    }

    #[test]
    fn test_find_libjli_in_aarch64_subdir() {
        let dir = tempdir().unwrap();
        let lib_dir = dir.path().join("lib").join("aarch64");
        std::fs::create_dir_all(&lib_dir).unwrap();
        std::fs::write(lib_dir.join("libjli.so"), b"fake").unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_ok());
    }

    #[test]
    fn test_find_libjli_directly_in_lib() {
        let dir = tempdir().unwrap();
        let lib_dir = dir.path().join("lib");
        std::fs::create_dir_all(&lib_dir).unwrap();
        std::fs::write(lib_dir.join("libjli.so"), b"fake").unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_ok());
    }

    #[test]
    fn test_find_libjli_not_found() {
        let dir = tempdir().unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_err());
        let err = result.unwrap_err().to_string();
        assert!(err.contains("libjli.so not found"));
    }

    #[test]
    fn test_find_libjli_prefers_arm64_over_arm32() {
        let dir = tempdir().unwrap();
        let arm64_dir = dir.path().join("lib").join("arm64");
        let arm32_dir = dir.path().join("lib").join("arm32");
        std::fs::create_dir_all(&arm64_dir).unwrap();
        std::fs::create_dir_all(&arm32_dir).unwrap();
        std::fs::write(arm64_dir.join("libjli.so"), b"arm64").unwrap();
        std::fs::write(arm32_dir.join("libjli.so"), b"arm32").unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_ok());
        assert!(result.unwrap().to_string_lossy().contains("arm64"));
    }

    #[test]
    fn test_launch_error_display() {
        let err = LaunchError::DlopenFailed("test path".to_string());
        assert!(err.to_string().contains("DlopenFailed"));
        assert!(err.to_string().contains("test path"));

        let err = LaunchError::DlsymFailed("JLI_Launch".to_string());
        assert!(err.to_string().contains("DlsymFailed"));

        let err = LaunchError::JliLaunchFailed(1);
        assert!(err.to_string().contains("exit code 1"));

        let err = LaunchError::JvmCreateFailed("reason".to_string());
        assert!(err.to_string().contains("JVM_CREATE_FAILED"));
    }

    #[test]
    fn test_shutdown_no_crash_when_no_jvm() {
        init_jvm_state();
        let result = JvmLauncher::shutdown();
        assert!(result.is_ok());
    }

    #[test]
    fn test_exit_requested_flag() {
        init_jvm_state();
        assert!(!JvmLauncher::is_exit_requested());
        JvmLauncher::shutdown().unwrap();
        assert!(JvmLauncher::is_exit_requested());
    }

    // 这里原来有个 `test_try_destroy_jvm_no_crash`，调用 `JvmLauncher::try_destroy_jvm()` ——
    // 那个方法早已改名成 `shutdown()`（上面 `test_shutdown_no_crash_when_no_jvm` 覆盖的就是它），
    // 于是 `JvmLauncher` 上根本找不到这个方法：**整个测试目标编译不过（E0599）**，
    // 也就是 `cargo test` 一直是坏的。功能已被上面那个测试覆盖，直接删掉这个残留。

    #[test]
    fn test_find_libjli_path_format() {
        let dir = tempfile::tempdir().unwrap();
        let lib_dir = dir.path().join("lib").join("arm64");
        std::fs::create_dir_all(&lib_dir).unwrap();
        std::fs::write(lib_dir.join("libjli.so"), b"fake").unwrap();

        let result = JvmLauncher::find_libjli(dir.path());
        assert!(result.is_ok());
        let path = result.unwrap();
        assert!(path.to_string_lossy().contains("libjli.so"));
    }

    #[test]
    fn test_jvm_state_send() {
        fn assert_send<T: Send>() {}
        assert_send::<JvmState>();
    }

    #[test]
    fn test_jvm_state_sync() {
        fn assert_sync<T: Sync>() {}
        assert_sync::<JvmState>();
    }
}
