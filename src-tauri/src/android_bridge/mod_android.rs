//! java.* / MainActivity 的 JNI 调用（从 android_bridge.rs 拆出的 android 子模块）。
//!
//! 拆分原因：android_bridge.rs 已顶到体量预算（scripts/check-file-size.mjs），
//! 而这一整块是「怎么调 Android 侧」的实现细节，与上面的命令封装泾渭分明。

use jni::objects::{JObject, JString, JValue};
use jni::{JavaVM, JNIEnv};
    use std::sync::atomic::{AtomicPtr, Ordering};
    use std::sync::Mutex;

    /// 应用进程的 JavaVM（进程内唯一，且生命周期与进程一致，无需释放）。
    static JAVA_VM: AtomicPtr<std::ffi::c_void> = AtomicPtr::new(std::ptr::null_mut());
    /// MainActivity 实例的全局引用（由 `nativeAttach` 在 JNI 入口处创建）。
    ///
    /// 这里存实例而不是类名：从原生线程 `FindClass` 用的是系统类加载器，看不到应用类，
    /// 在开启 `-Xcheck:jni` 的设备上会直接 abort 掉进程。实例方法调用则完全避开这个问题。
    ///
    /// ⚠️ 2026-10-26 审查 B-P2-5：原来存裸指针并 `mem::forget`，而 Activity 每次
    /// 因配置变化重建都会再 `nativeAttach` 一次 → 旧的 GlobalRef 永不释放，整棵
    /// View 树被钉死。改成存 `GlobalRef` 本身：`*guard = Some(new)` 时旧值 drop，
    /// GlobalRef 的 Drop 会自己 DeleteGlobalRef。
    static ACTIVITY: Mutex<Option<jni::objects::GlobalRef>> = Mutex::new(None);

    pub fn set_vm(vm: JavaVM) {
        JAVA_VM.store(
            vm.get_java_vm_pointer() as *mut std::ffi::c_void,
            Ordering::Release,
        );
    }

    pub fn set_activity(env: &mut JNIEnv, activity: &JObject) {
        match env.new_global_ref(activity) {
            Ok(global) => {
                // 旧引用在这里被替换并释放
                *crate::util::lock_recover(&ACTIVITY) = Some(global);
                // 桥就绪前缓存的事件（进度 / 启动状态）现在补发（审查 B-P1-4）
                crate::progress::flush_pending_events();
            }
            // 静默吞掉会让「所有原生能力失效」变成无迹可查的谜案
            Err(e) => tracing::error!("new_global_ref(activity) 失败：{e}"),
        }
    }

    fn java_vm() -> Option<JavaVM> {
        let ptr = JAVA_VM.load(Ordering::Acquire) as *mut jni::sys::JavaVM;
        if ptr.is_null() {
            return None;
        }
        unsafe { JavaVM::from_raw(ptr).ok() }
    }

    fn activity() -> Option<JObject<'static>> {
        let guard = crate::util::lock_recover(&ACTIVITY);
        let g = guard.as_ref()?;
        // GlobalRef 保证生命周期到进程结束，重新包一个 JObject 视图即可
        Some(unsafe { JObject::from_raw(g.as_obj().as_raw()) })
    }

    /// 清掉 `env` 上**挂起的 Java 异常**。
    ///
    /// ⚠️ 为什么必须有（2026-10-26 审查 B-P0-1，全案最高优先级）：
    /// jni-rs 0.21 的 `call_method` 在 Java 侧抛异常时返回 `Err(JavaException)`，
    /// 但**不会清除异常** —— 挂起的异常留在 env 上，**下一次 JNI 调用即违反规范**，
    /// CheckJNI 下直接 SIGABRT（本文件 :737 的事故注释就是这么来的：真机主进程崩溃）。
    ///
    /// 本项目大量使用 `.ok()?` 吞错误（18 处），每处都是一个雷；所以统一在
    /// 每个 JNI 入口**返回前**调这个函数收口，而不是在每个 `.ok()?` 后面补。
    fn clear_pending_exception(env: &mut jni::JNIEnv, what: &str) {
        match env.exception_check() {
            Ok(true) => {
                // describe 会把 Java 侧堆栈打到 logcat，排障必需；失败就算了
                let _ = env.exception_describe();
                let _ = env.exception_clear();
                tracing::warn!("JNI 调用 {what} 抛了异常，已清除（否则下一次 JNI 调用会崩）");
            }
            _ => {}
        }
    }

    /// 在一个**局部引用帧**里跑一段 JNI 调用，返回前一次性释放这一批局部引用。
    ///
    /// ⚠️ 为什么必须有（2026-10-26 审查 B-P1-2）：这些调用几乎全跑在
    /// `attach_current_thread_as_daemon` 的**常驻线程**上（事件推送、日志 tail），
    /// 而局部引用只在线程**退出**时释放 —— 线程永不退出，于是每个事件
    /// 两次 `new_string` 就一直累积，长会话必然 `local reference table overflow` abort。
    /// 全仓此前零 `with_local_frame` / `delete_local_ref`。
    fn with_frame<T>(
        env: &mut jni::JNIEnv,
        f: impl FnOnce(&mut jni::JNIEnv) -> Option<T>,
    ) -> Option<T> {
        env.with_local_frame(16, |e| Ok::<_, jni::errors::Error>(f(e)))
            .ok()
            .flatten()
    }

    /// 一个字符串参数的版本（`startJvmInstallService(json)` 用）。
    ///
    /// 只负责"叫一声"：真正的结果由 `:jvm` 服务写进
    /// `<data>/logs/jvm-install-result.txt`，Rust 侧轮询那个文件（比回传值可靠 ✗）。
    pub fn call_activity1(name: &str, sig: &str, arg: &str) -> Option<()> {
        let vm = java_vm()?;
        let mut env = vm.attach_current_thread_as_daemon().ok()?;
        let r = with_frame(&mut env, |env| {
            let obj = activity()?;
            let a: JString = env.new_string(arg).ok()?;
            env.call_method(&obj, name, sig, &[JValue::Object(&a)]).ok()?;
            Some(())
        });
        clear_pending_exception(&mut env, name);
        r
    }

    /// 两个字符串参数的版本（例如 startGameActivity(instanceId, accountUuid)）。
    pub fn call_activity2(name: &str, sig: &str, first: &str, second: &str) -> Option<String> {
        let vm = java_vm()?;
        let mut env = vm.attach_current_thread_as_daemon().ok()?;
        // 注意：这是事件上行通道（push_event_to_kotlin）的本体，
        // 每帧都可能走这里 —— 异常必须清掉（B-P0-1），局部引用必须成帧释放（B-P1-2）
        let r = with_frame(&mut env, |env| {
            let obj = activity()?;
            let arg1: JString = env.new_string(first).ok()?;
            let arg2: JString = env.new_string(second).ok()?;
            env.call_method(
                &obj,
                name,
                sig,
                &[JValue::Object(&arg1), JValue::Object(&arg2)],
            )
            .ok()?;
            Some(String::new())
        });
        clear_pending_exception(&mut env, name);
        r
    }

    /// 拉起 `:server` 独立进程里的 ServerService。
    ///
    /// ## 为什么不走 `call_activity`
    ///
    /// 那条路要求 MainActivity 上有对应的 Kotlin 方法（`setOrientation` 等），
    /// 但本项目的 MainActivity 是个空壳（只有 onCreate），**那些方法根本不存在** ——
    /// `call_method` 会抛 NoSuchMethodError，被 `call_activity` 静默吞掉变成
    /// 「原生桥未就绪」。这里改用 **Android 框架自带**的
    /// `Context.startForegroundService(Intent)`：它是 API 26 起就有的系统方法，
    /// 任何 Context（含 Activity）都有，不依赖我们自己的桥接代码是否写对。
    ///
    /// Intent 在 JNI 侧手工构造（组件名写死 `com.zhayi.qookix.services.ServerService`），
    /// 免得 Kotlin 侧再引入一个必须同步维护的入口。
    #[cfg(target_os = "android")]
    pub fn start_server_process(id: &str) -> Result<(), String> {
        use jni::objects::JValue;

        let vm = java_vm().ok_or_else(|| "原生桥未就绪（JavaVM 未初始化）".to_string())?;
        let mut env = vm
            .attach_current_thread_as_daemon()
            .map_err(|e| e.to_string())?;
        let ctx = activity().ok_or_else(|| "原生桥未就绪（Activity 未 attach）".to_string())?;

        let pkg = env
            .new_string("com.zhayi.qookix")
            .map_err(|e| e.to_string())?;
        let cls = env
            .new_string("com.zhayi.qookix.services.ServerService")
            .map_err(|e| e.to_string())?;

        let cn_cls = env
            .find_class("android/content/ComponentName")
            .map_err(|e| e.to_string())?;
        let cn = env
            .new_object(
                &cn_cls,
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&pkg), JValue::Object(&cls)],
            )
            .map_err(|e| e.to_string())?;

        let intent_cls = env
            .find_class("android/content/Intent")
            .map_err(|e| e.to_string())?;
        let intent = env
            .new_object(&intent_cls, "()V", &[])
            .map_err(|e| e.to_string())?;
        env.call_method(
            &intent,
            "setComponent",
            "(Landroid/content/ComponentName;)Landroid/content/Intent;",
            &[JValue::Object(&cn)],
        )
        .map_err(|e| format!("setComponent 失败: {e}"))?;

        let key = env.new_string("server_id").map_err(|e| e.to_string())?;
        let val = env.new_string(id).map_err(|e| e.to_string())?;
        env.call_method(
            &intent,
            "putExtra",
            "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
            &[JValue::Object(&key), JValue::Object(&val)],
        )
        .map_err(|e| format!("putExtra 失败: {e}"))?;

        // API 26+ 走 startForegroundService（ServerService 会在 5 秒内 startForeground）
        // 低于 26 用 startService 兜底（项目 minSdk 已在 24+）
        let started = if android_sdk_int() >= 26 {
            env.call_method(
                &ctx,
                "startForegroundService",
                "(Landroid/content/Intent;)Landroid/content/ComponentName;",
                &[JValue::Object(&intent)],
            )
        } else {
            env.call_method(
                &ctx,
                "startService",
                "(Landroid/content/Intent;)Landroid/content/ComponentName;",
                &[JValue::Object(&intent)],
            )
        };
        match started {
            Ok(_) => Ok(()),
            // Android 12+ 后台启动前台服务会抛 IllegalStateException，
            // 报友好一点：说明得回前台
            Err(e) => {
                // ⚠️ 必须先清异常再返回（审查 B-P0-1）：挂起异常会毒死这条
                // tokio worker 后续的每一次 JNI 调用 → CheckJNI 下 SIGABRT
                clear_pending_exception(&mut env, "startForegroundService");
                Err(format!("拉起服务端进程失败: {e}"))
            }
        }
    }

    /// 停掉 `:server` 进程（连带 JVM 与服务器一起结束）。
    ///
    /// 这里**必须复用 `server_service_intent`**（2 参 `putExtra`）。原来是手写的
    /// `putExtra(String,String,String)` —— `Intent` 上根本没有这个重载，会抛
    /// `NoSuchMethodError`；错误又被 `.ok()` 吞掉，**挂起的异常留在 JNI env 上**，
    /// 下一次 JNI 调用（`call_method` 里的 `GetObjectClass`）就让整个应用 SIGABRT。
    /// 真机实测：调用「停服 + 用补丁 jar 重启」的流程，主进程直接崩掉。
    pub fn stop_server_process(id: &str) -> Result<(), String> {
        use jni::objects::JValue;
        let vm = java_vm().ok_or_else(|| "原生桥未就绪（JavaVM 未初始化）".to_string())?;
        let mut env = vm
            .attach_current_thread_as_daemon()
            .map_err(|e| e.to_string())?;
        let ctx = activity().ok_or_else(|| "原生桥未就绪（Activity 未 attach）".to_string())?;

        let intent = server_service_intent(&mut env, id)?;
        let r = env
            .call_method(
                &ctx,
                "stopService",
                "(Landroid/content/Intent;)Z",
                &[JValue::Object(&intent)],
            )
            .map_err(|e| e.to_string())
            .map(|_| ());
        clear_pending_exception(&mut env, "stopService");
        r
    }

    /// 构造指向 ServerService 的 Intent
    ///
    /// 返回 `JObject<'local>`（借自传入 env）。**不要**谎报成 `'static`：
    /// JNI local ref 的生命周期受 env 约束，编译期会放行、运行时留下悬垂引用。
    fn server_service_intent<'local>(
        env: &mut jni::JNIEnv<'local>,
        id: &str,
    ) -> Result<jni::objects::JObject<'local>, String> {
        use jni::objects::JValue;
        let pkg = env.new_string("com.zhayi.qookix").map_err(|e| e.to_string())?;
        let cls = env
            .new_string("com.zhayi.qookix.services.ServerService")
            .map_err(|e| e.to_string())?;
        let cn_cls = env
            .find_class("android/content/ComponentName")
            .map_err(|e| e.to_string())?;
        let cn = env
            .new_object(
                &cn_cls,
                "(Ljava/lang/String;Ljava/lang/String;)V",
                &[JValue::Object(&pkg), JValue::Object(&cls)],
            )
            .map_err(|e| e.to_string())?;
        let intent_cls = env
            .find_class("android/content/Intent")
            .map_err(|e| e.to_string())?;
        let intent = env
            .new_object(&intent_cls, "()V", &[])
            .map_err(|e| e.to_string())?;
        env.call_method(
            &intent,
            "setComponent",
            "(Landroid/content/ComponentName;)Landroid/content/Intent;",
            &[JValue::Object(&cn)],
        )
        .map_err(|e| e.to_string())?;
        let key = env.new_string("server_id").map_err(|e| e.to_string())?;
        let val = env.new_string(id).map_err(|e| e.to_string())?;
        env.call_method(
            &intent,
            "putExtra",
            "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;",
            &[JValue::Object(&key), JValue::Object(&val)],
        )
        .map_err(|e| e.to_string())?;
        Ok(intent)
    }

    /// 结束 `:server` 进程（不区分服务器，兜底强杀用）
    pub fn stop_server_process_any() -> Result<(), String> {
        use jni::objects::JValue;
        let vm = java_vm().ok_or_else(|| "原生桥未就绪".to_string())?;
        let mut env = vm
            .attach_current_thread_as_daemon()
            .map_err(|e| e.to_string())?;
        let ctx = activity().ok_or_else(|| "原生桥未就绪（Activity 未 attach）".to_string())?;
        let intent = server_service_intent(&mut env, "")?;
        let r = env
            .call_method(
                &ctx,
                "stopService",
                "(Landroid/content/Intent;)Z",
                &[JValue::Object(&intent)],
            )
            .map_err(|e| e.to_string())
            .map(|_| ());
        clear_pending_exception(&mut env, "stopService");
        r
    }

    /// 让 Kotlin 把常驻通知撤掉（优雅停服后调用，否则通知会一直挂在下拉栏）
    pub fn notify_server_stopped(id: &str) -> Result<(), String> {
        use jni::objects::JValue;
        let vm = java_vm().ok_or_else(|| "原生桥未就绪".to_string())?;
        let mut env = vm
            .attach_current_thread_as_daemon()
            .map_err(|e| e.to_string())?;
        // 用广播通知 :server 进程撤通知（跨进程，直接调方法不行）
        let ctx = activity().ok_or_else(|| "原生桥未就绪（Activity 未 attach）".to_string())?;
        let intent = server_service_intent(&mut env, id)?;
        // JString → JObject 转换：JValue::Object 需要 &JObject，这里用局部变量延长生命周期
        let action = env
            .new_string("com.zhayi.qookix.SERVER_STOPPED")
            .map_err(|e| e.to_string())?;
        let action_obj: jni::objects::JObject = action.into();
        env.call_method(
            &intent,
            "setAction",
            "(Ljava/lang/String;)Landroid/content/Intent;",
            &[JValue::Object(&action_obj)],
        )
        .ok();
        let r = env
            .call_method(
                &ctx,
                "sendBroadcast",
                "(Landroid/content/Intent;)V",
                &[JValue::Object(&intent)],
            )
            .map_err(|e| e.to_string())
            .map(|_| ());
        clear_pending_exception(&mut env, "sendBroadcast");
        r
    }

    /// 读 `android.os.Build$VERSION.SDK_INT`（用于选 startService / startForegroundService）
    fn android_sdk_int() -> i32 {
        let Some(vm) = java_vm() else { return 0 };
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return 0;
        };
        let r = (|| {
            let vcls = env.find_class("android/os/Build$VERSION").ok()?;
            match env.get_static_field(&vcls, "SDK_INT", "I").ok()? {
                jni::objects::JValueOwned::Int(v) => Some(v),
                _ => None,
            }
        })();
        // 读不到就是 0，而 0 会被当成「低于 26」→ 走 startService → Android 8+
        // 后台必失败，且报错文案完全指错方向（审查 B-P2-10）。至少留条日志。
        if r.is_none() {
            tracing::warn!("读不到 Build.VERSION.SDK_INT，按 0 处理（会影响 startForegroundService 选择）");
        }
        clear_pending_exception(&mut env, "Build$VERSION.SDK_INT");
        r.unwrap_or(0)
    }

    /// **无参数、取回字符串结果**的版本。
    ///
    /// 用于「应用侧自己算好再回传」的场景（如 MainActivity.wifiIpv4 读网卡 IP）。
    /// 之前只有 `call_activity_str2`（两参数），这类需求只能自己拼 JNI，
    /// 而在原生线程里 FindClass 又拿不到系统类 —— 走实例方法是唯一稳的路。
    pub fn call_activity_str(name: &str, sig: &str) -> Option<String> {
        let vm = java_vm()?;
        let mut env = vm.attach_current_thread_as_daemon().ok()?;
        let r = with_frame(&mut env, |env| {
            let obj = activity()?;
            let result = env.call_method(&obj, name, sig, &[]).ok()?;
            match result {
                jni::objects::JValueOwned::Object(ret) => {
                    if ret.is_null() {
                        return None;
                    }
                    let jstr = jni::objects::JString::from(ret);
                    env.get_string(&jstr)
                        .ok()
                        .map(|s| s.to_string_lossy().into_owned())
                }
                jni::objects::JValueOwned::Int(v) => Some(v.to_string()),
                _ => None,
            }
        });
        clear_pending_exception(&mut env, name);
        r
    }


    /// 两个字符串参数、**并取回字符串结果**的版本。
    ///
    /// 与 `call_activity2` 的区别：那个是「调用完就算成功」的（`startGameActivity`
    /// 那种不需要回执），这里要拿回执才能知道写文件到底成没成。
    pub fn call_activity_str2(name: &str, sig: &str, first: &str, second: &str) -> Option<String> {
        let vm = java_vm()?;
        let mut env = vm.attach_current_thread_as_daemon().ok()?;
        let r = with_frame(&mut env, |env| {
            let obj = activity()?;
            let arg1: JString = env.new_string(first).ok()?;
            let arg2: JString = env.new_string(second).ok()?;
            let result = env
                .call_method(
                    &obj,
                    name,
                    sig,
                    &[JValue::Object(&arg1), JValue::Object(&arg2)],
                )
                .ok()?;

            match result {
                jni::objects::JValueOwned::Object(ret) => {
                    if ret.is_null() {
                        return None;
                    }
                    let jstr = JString::from(ret);
                    env.get_string(&jstr)
                        .ok()
                        .map(|s| s.to_string_lossy().into_owned())
                }
                _ => Some(String::new()),
            }
        });
        clear_pending_exception(&mut env, name);
        r
    }

    /// 调 MainActivity 上的 `(String, boolean, String)` 方法（无返回值）。
    ///
    /// 控制布局编辑器要传三个参数（编辑哪一份 / 是否只读预览 / 另存成什么），
    /// 现有的 `call_activity` 只支持「一个字符串」或「无参」，所以加这一个。
    /// 空串代表「不传」（Kotlin 侧按 `isNullOrBlank()` 判断）。
    pub fn call_activity_sbs(name: &str, sig: &str, first: &str, flag: bool, third: &str) {
        let Some(vm) = java_vm() else { return };
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        if let Some(obj) = activity() {
            if let (Ok(a), Ok(b)) = (env.new_string(first), env.new_string(third)) {
                let _ = env.call_method(
                    &obj,
                    name,
                    sig,
                    &[
                        JValue::Object(&a),
                        JValue::Bool(u8::from(flag)),
                        JValue::Object(&b),
                    ],
                );
            }
        }
        clear_pending_exception(&mut env, name);
    }

    /// **单个 boolean 参数**的版本。
    ///
    /// `call_activity` 只认字符串参数（`Option<&str>`），`call_activity_sbs` 又是
    /// 「字符串+布尔+字符串」三参的特例，都套不上这种「就一个开关」的调用
    /// （如 `setLogZoomCapture(Z)V`），所以单独加一个。
    pub fn call_activity_bool(name: &str, sig: &str, flag: bool) {
        let Some(vm) = java_vm() else { return };
        let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
            return;
        };
        if let Some(obj) = activity() {
            let _ = env.call_method(&obj, name, sig, &[JValue::Bool(u8::from(flag))]);
        }
        clear_pending_exception(&mut env, name);
    }

    /// 调用 MainActivity 上的实例方法并返回字符串结果（返回 null 时得到 None）。
    pub fn call_activity(name: &str, sig: &str, arg: Option<&str>) -> Option<String> {
        let vm = java_vm()?;
        let mut env = vm.attach_current_thread_as_daemon().ok()?;

        let r = with_frame(&mut env, |env| {
            let obj = activity()?;
            let result = match arg {
                Some(value) => {
                    let jvalue: JString = env.new_string(value).ok()?;
                    env.call_method(&obj, name, sig, &[JValue::Object(&jvalue)])
                        .ok()?
                }
                None => env.call_method(&obj, name, sig, &[]).ok()?,
            };

            match result {
                jni::objects::JValueOwned::Object(ret) => {
                    if ret.is_null() {
                        return None;
                    }
                    let jstr = JString::from(ret);
                    env.get_string(&jstr)
                        .ok()
                        .map(|s| s.to_string_lossy().into_owned())
                }
                // void / 基本类型返回：调用成功即可，用空串表示「成功但没有返回值」
                _ => Some(String::new()),
            }
        });
        clear_pending_exception(&mut env, name);
        r
    }
