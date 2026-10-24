use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::ffi::CString;
use std::os::raw::c_char;
use std::sync::OnceLock;

fn launch_game_blocking(instance_id: String, account_uuid: String) -> i32 {
    // 关键：这里**不能**再 `tokio::runtime::Runtime::new().unwrap()`。
    // 那是每次启动/强制关闭都新建一个线程池，而且一旦创建失败就在 JNI 边界上 panic ——
    // panic 跨 JNI 会 abort 整个进程，表现成「点启动直接闪退」，没有任何提示。
    // 直接复用 Tauri 自己的运行时，既没有这个风险，也不用反复建池。
    let result = tauri::async_runtime::block_on(async {
        let account = if account_uuid.is_empty() {
            None
        } else {
            crate::accounts::get_account_by_uuid(&account_uuid).await.ok()
        };

        crate::launch::launch_game(&instance_id, account.as_ref()).await
    });

    match result {
        Ok(status) => status.exit_code.unwrap_or(0),
        Err(e) => {
            let err_str = e.to_string();
            // 顺序要紧：JRE 运行时目录名形如 `Pojav-JRE-17`，
            // 所以任何提到运行时路径的错误都会命中 "JRE" ——
            // 先判 JVM/Dlopen/JliLaunch，否则「找不到 libjvm.so」这类错误
            // 会被报成「JRE 缺失」，用户看到的原因完全是错的。
            if err_str.contains("GAME_ALREADY_RUNNING") { -2 }
            else if err_str.contains("JVM") || err_str.contains("Dlopen") || err_str.contains("JliLaunch") { -6 }
            else if err_str.contains("JRE") { -3 }
            else if err_str.contains("NATIVE") || err_str.contains("ARCH") { -4 }
            else if err_str.contains("GL") || err_str.contains("RENDER") { -5 }
            else { -1 }
        }
    }
}

/// Kotlin 侧（MainActivity.onCreate）调用，把 JavaVM 与 Activity 实例交给 Rust。
/// 之后方向锁定、`content://` 文件落地、读取系统代理都由 Rust 回调该实例完成。
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeAttach(
    mut env: JNIEnv,
    _class: JClass,
    activity: jni::objects::JObject,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        match env.get_java_vm() {
            Ok(vm) => {
                crate::android_bridge::init(vm, &mut env, &activity);
                // ⚠️ 阶段 4（2026-10-25）：进程级初始化从 Tauri 的 `setup()` 迁到这里。
                //
                // 原来在 `lib.rs::run()` 的 `.setup(|app| ...)` 里做，但那条路要求
                // 启动 Tauri 运行时（也就是创建 WebView ✗）。现在 App 不创建 WebView，
                // `run()` 不再被调用，所以必须在这里补上 —— 否则「休眠中的服务器
                // 重新挂唤醒监听 / 运行中的重新开始空闲巡检」会**静默失效** ✗。
                //
                // 用 catch_unwind 包一层：初始化失败不能连累 Activity 启动。
                let _ = std::panic::catch_unwind(|| {
                    crate::sleep::resume_on_start();
                });
            }
            Err(e) => tracing::warn!("nativeAttach: 获取 JavaVM 失败: {e}"),
        }
    }));
}

#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeLaunchGame(
    mut env: JNIEnv,
    _class: JClass,
    instance_id: JString,
    account_uuid: JString,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let instance_id: String = env.get_string(&instance_id)
            .map(|s| s.to_str().unwrap_or("").to_string())
            .unwrap_or_default();

        let account_uuid: String = env.get_string(&account_uuid)
            .map(|s| s.to_str().unwrap_or("").to_string())
            .unwrap_or_default();

        launch_game_blocking(instance_id, account_uuid)
    }));

    result.unwrap_or(-1)
}

/// Kotlin → Rust 的**通用命令入口**（阶段 1：绕开 WebView）。
///
/// 与 `nativeLaunchGame` 等专用方法不同，这里走一条通用 JSON 通道：
/// `nativeInvoke(cmd, payload) -> {"ok":true,"data":…} | {"ok":false,"error":"…"}`。
///
/// 已经实现的命令见 `commands.rs::native_invoke`（目前 4 个试点）。
/// 这样 Compose 侧不依赖隐藏 WebView 里的 `index.html` 也能调 Rust ——
/// 那条链一断就是「桥尚未就绪 / 点了没反应」（真机反复遇到）。
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeInvoke(
    mut env: JNIEnv,
    _class: JClass,
    cmd: JString,
    payload: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let cmd: String = env
            .get_string(&cmd)
            .map(|s| s.to_str().unwrap_or("").to_string())
            .unwrap_or_default();
        let payload: String = env
            .get_string(&payload)
            .map(|s| s.to_str().unwrap_or("").to_string())
            .unwrap_or_default();
        crate::commands::native_invoke(&cmd, &payload)
    }))
    .unwrap_or_else(|_| r#"{"ok":false,"error":"nativeInvoke panic"}"#.to_string());

    env.new_string(result)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Kotlin 侧 `JvmInstallService`（**独立进程 `:jvm`**）调用：在该进程里用 JNI 起一个
/// JVM 跑任意 `classpath + mainClass`（Forge/NeoForge 的 installer 与 processors 靠它）。
///
/// ## 为什么必须另开进程（2026-10-23，照 ZL2/Pojav 的做法）
///
/// ① 安卓**禁止 exec 应用私有目录里的 `java`** → `Command::new(bin/java)` 直接失败
///    （真机日志：`启动 installer 失败`），所以只能走 `JNI_CreateJavaVM`。
/// ② 但 `JNI_CreateJavaVM` **一个进程只能成功一次** —— 主进程要用它启动 MC，
///    所以安装期的 JVM 必须放在**另一个进程**里（ZL2 用 `android:process=":jvm"`
///    的 Service，退出码经 UDP 回环送回主进程）。
///
/// 参数里带列表的用 `\u{1f}`（Unit Separator）分隔，避免路径里的空格/冒号干扰。
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_install_JvmInstallService_nativeRunJvm(
    mut env: JNIEnv,
    _class: JClass,
    jre_home: JString,
    classpath: JString,
    main_class: JString,
    jvm_args: JString,
    game_args: JString,
    work_dir: JString,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let read = |env: &mut JNIEnv, js: &JString| -> String {
            env.get_string(js)
                .map(|v| v.to_str().unwrap_or("").to_string())
                .unwrap_or_default()
        };
        let jre = read(&mut env, &jre_home);
        let cp = read(&mut env, &classpath);
        let mc = read(&mut env, &main_class);
        let ja = read(&mut env, &jvm_args);
        let ga = read(&mut env, &game_args);
        let wd = read(&mut env, &work_dir);
        let split = |raw: &str| -> Vec<String> {
            raw.split('\u{1f}')
                .filter(|x| !x.is_empty())
                .map(|x| x.to_string())
                .collect()
        };
        crate::util::log_line(&format!(
            "[jvm-service] 启动 JVM：mainClass={mc} classpath 条目={} jre={jre}",
            cp.split(':').count()
        ));
        // ⚠️ 必须把 JVM 的输出落盘：`JLI_Launch` 结束时是**直接 exit()**，
        // 进程消失后 logcat 里什么都留不下（真机就因此完全看不到 installer 为什么退）。
        // 复用启动游戏那套 dup2 重定向（同一份实现，launch.rs）。
        let log_path = std::path::Path::new(&wd)
            .join("logs")
            .join("jvm-install-stderr.log");
        let _guard = crate::launch::redirect_output_to_file(&log_path, "jvm-install");

        // ⚠️ JRE 的原生库必须**自己预加载**（真机事故 2026-10-23）：
        //   UnsatisfiedLinkError: …/lib/libnio.so: dlopen failed:
        //     library "libnet.so" not found: needed by …/libnio.so
        // 启动游戏那条路（launch.rs）在 JLI_Launch 前做了同样两件事，这里当初漏了 ✗。
        // 安卓的 dlopen 不走系统库搜索路径，必须按依赖顺序 System.load + 给 LD_LIBRARY_PATH。
        let jre_path = std::path::Path::new(&jre);
        let lib_dir = jre_path.join("lib");
        let mut lp = lib_dir.to_string_lossy().to_string();
        if let Ok(old) = std::env::var("LD_LIBRARY_PATH") {
            if !old.is_empty() {
                lp = format!("{old}:{lp}");
            }
        }
        std::env::set_var("LD_LIBRARY_PATH", &lp);
        let n = crate::android_env::preload_jre_libraries(jre_path);
        crate::util::log_line(&format!(
            "[jvm-service] 预加载 JRE 内部库 {n} 个，LD_LIBRARY_PATH={lp}"
        ));
        match crate::jvm_launcher::JvmLauncher::launch(
            std::path::Path::new(&jre),
            &split(&ja),
            &cp,
            &mc,
            &split(&ga),
            &wd,
        ) {
            Ok(code) => {
                crate::util::log_line(&format!("[jvm-service] JVM 退出，退出码 {code}"));
                code
            }
            Err(e) => {
                crate::util::log_line(&format!("[jvm-service] JVM 启动失败：{e}"));
                -1
            }
        }
    }));
    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeKillGame(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // 这里**不能** unwrap：Runtime 创建失败时的 panic 会被 catch_unwind 吞成 0，
        // 调用方就分不清「没杀掉」和「自己崩了」，于是游戏留在后台继续跑（P0-16）。
        let Ok(runtime) = tokio::runtime::Runtime::new() else {
            tracing::error!("创建 tokio runtime 失败，kill_game 中止");
            return -1;
        };
        match runtime.block_on(crate::launch::kill_game()) {
            Ok(()) => 1,
            Err(e) => {
                tracing::warn!("kill_game 失败：{e}");
                0
            }
        }
    }));

    result.unwrap_or(-1)
}

#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeGetGameStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let status = crate::launch::get_game_status();
        serde_json::to_string(&status).unwrap_or_default()
    }));

    let json_str = result.unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_str)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeSendInput(
    mut env: JNIEnv,
    _class: JClass,
    event_type: jint,
    data: JString,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let data: String = env.get_string(&data)
            .map(|s| s.to_str().unwrap_or("").to_string())
            .unwrap_or_default();

        let event = match event_type {
            1005 => {
                let parts: Vec<&str> = data.split(',').collect();
                if parts.len() >= 4 {
                    let key_code: i32 = parts[0].parse().unwrap_or(0);
                    let scancode: i32 = parts[1].parse().unwrap_or(0);
                    let action: i32 = parts[2].parse().unwrap_or(0);
                    let mods: i32 = parts[3].parse().unwrap_or(0);
                    Some(crate::input_bridge::InputEvent::Key { key_code, scancode, action, mods })
                } else if parts.len() >= 2 {
                    let key_code: i32 = parts[0].parse().unwrap_or(0);
                    let action = if parts[1] == "1" { 1 } else { 0 };
                    Some(crate::input_bridge::InputEvent::Key { key_code, scancode: 0, action, mods: 0 })
                } else { None }
            }
            1006 => {
                let parts: Vec<&str> = data.split(',').collect();
                if parts.len() >= 3 {
                    let button: i32 = parts[0].parse().unwrap_or(0);
                    let action: i32 = parts[1].parse().unwrap_or(0);
                    let mods: i32 = parts[2].parse().unwrap_or(0);
                    Some(crate::input_bridge::InputEvent::MouseButton { button, action, mods })
                } else if parts.len() >= 2 {
                    let button: i32 = parts[0].parse().unwrap_or(0);
                    let action = if parts[1] == "1" { 1 } else { 0 };
                    Some(crate::input_bridge::InputEvent::MouseButton { button, action, mods: 0 })
                } else { None }
            }
            1003 => {
                let parts: Vec<&str> = data.split(',').collect();
                if parts.len() >= 2 {
                    let x: f64 = parts[0].parse().unwrap_or(0.0);
                    let y: f64 = parts[1].parse().unwrap_or(0.0);
                    Some(crate::input_bridge::InputEvent::CursorPos { x, y })
                } else { None }
            }
            1007 => {
                let parts: Vec<&str> = data.split(',').collect();
                if parts.len() >= 2 {
                    let x: f64 = parts[0].parse().unwrap_or(0.0);
                    let y: f64 = parts[1].parse().unwrap_or(0.0);
                    Some(crate::input_bridge::InputEvent::Scroll { x, y })
                } else { None }
            }
            _ => None,
        };

        if let Some(event) = event {
            let _ = crate::input_bridge::send_event(event);
        }
    }));
}

#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeSetupSurface(
    env: JNIEnv,
    _class: JClass,
    surface: jni::sys::jobject,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        extern "C" {
            fn ANativeWindow_fromSurface(
                env: *mut jni::sys::JNIEnv,
                surface: jni::sys::jobject,
            ) -> *mut std::ffi::c_void;
            fn ANativeWindow_acquire(window: *mut std::ffi::c_void);
        }

        let env_ptr = env.get_raw();
        let window = unsafe { ANativeWindow_fromSurface(env_ptr, surface) };
        if window.is_null() {
            tracing::error!("GL_CONTEXT_FAILED: ANativeWindow_fromSurface returned null");
            return;
        }
        unsafe { ANativeWindow_acquire(window); }

        match crate::render_bridge::setup_surface(window) {
            Ok(()) => tracing::info!("Surface setup complete"),
            Err(e) => tracing::error!("Surface setup failed: {}", e),
        }
    }));
}

#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeReleaseSurface(
    _env: JNIEnv,
    _class: JClass,
) {
    let _ = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        crate::render_bridge::release();
        tracing::info!("Surface released");
    }));
}

/// 记录 Surface 实际尺寸。必须由 GameActivity 在 Surface 就绪时调用：
/// 游戏窗口尺寸要与它一致，否则触摸坐标换算会整体偏移（点不中按钮）。
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeSetSurfaceSize(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
) {
    crate::android_env::set_surface_size(width, height);
}

/// 版本清单的旧 JNI 入口（真实数据走 `get_version_manifest` 命令，这里目前无调用者）。
/// Kotlin 侧的声明是**非空** `String`（TauriBridge.kt），返回 `null` 会让调用点抛 NPE ——
/// 改为返回一个常驻空串，占位也安全。
#[no_mangle]
pub extern "C" fn Java_com_zhayi_qookix_tauri_TauriBridge_nativeGetVersionList(
    _env: JNIEnv,
    _class: JClass,
) -> *const c_char {
    static EMPTY: OnceLock<CString> = OnceLock::new();
    EMPTY.get_or_init(CString::default).as_ptr()
}
