//! Android 原生能力桥：屏幕方向锁定、文件选择结果（`content://`）落地等。
//!
//! 调用链：`Kotlin MainActivity` → `TauriBridge.nativeAttach()`（把 JavaVM 交给 Rust）
//! → Rust 通过 JNI 调用 `com.zhayi.qookix.MainActivity` 上的静态方法。
//! 非 Android 平台全部降级为 no-op，保证同一份代码在桌面端可编译。

/// 记录 JavaVM 与 MainActivity 的全局引用（由 `nativeAttach` 在 JNI 入口处调用）。
#[cfg(target_os = "android")]
pub fn init(vm: jni::JavaVM, env: &mut jni::JNIEnv, activity: &jni::objects::JObject) {
    android::set_vm(vm);
    android::set_activity(env, activity);

    // 应用启动就把 native 库抽取好（平时 stamp 未变时是零拷贝空跑，开销可忽略）。
    //
    // 这是「装包后第一次启动必失败」的根因：抽取原本发生在启动游戏的流程里，
    // 而 GameActivity 会更早去加载 `files/natives/libpojavexec.so` —— 第一次启动时
    // 抽取正在进行/尚未完成，ART 侧就会回退去加载 APK 里的另一份副本，
    // 两份各自的 `pojav_environ` 不共享，GL 初始化直接 SIGSEGV(fault addr 0x0)。
    // 提前在应用启动时做完，之后谁加载的都是同一份文件，竞态消失。
    std::thread::spawn(|| {
        let result = crate::util::block_on(async {
            match crate::settings::get_data_dir().await {
                Ok(dir) => crate::android_env::resolve_native_lib_dir(&dir)
                    .await
                    .map(|_| ())
                    .map_err(|e| e.to_string()),
                Err(e) => Err(e.to_string()),
            }
        });
        match result {
            Ok(()) => tracing::info!("[launcher] 应用启动阶段 natives 已就绪"),
            Err(e) => tracing::warn!("[launcher] 应用启动阶段抽取 natives 失败：{e}"),
        }
    });
}

/// 锁定 / 跟随屏幕方向。`mode`：`system` | `portrait` | `landscape`。
#[cfg(target_os = "android")]
pub fn set_orientation(mode: &str) -> Result<(), String> {
    android::call_activity(
        "setOrientation",
        "(Ljava/lang/String;)V",
        Some(mode),
    )
    .map(|_| ())
    .ok_or_else(|| "设置屏幕方向失败（原生桥未就绪）".to_string())
}

/// 把下载好的 APK 交给系统安装器（会弹「是否安装」，安卓不允许静默安装）。
///
/// 原生侧会先比对签名证书：不一致直接删包并返回原因，不会进安装器。
/// 返回值约定同 `write_text_to_uri`：空串 = 已拉起安装器，非空 = 拒绝原因。
#[cfg(target_os = "android")]
pub fn install_apk(path: &str) -> Result<(), String> {
    match android::call_activity("installApk", "(Ljava/lang/String;)Ljava/lang/String;", Some(path)) {
        Some(msg) if msg.is_empty() => Ok(()),
        Some(msg) => Err(msg),
        None => Err("无法拉起安装器（原生桥未就绪）".to_string()),
    }
}

#[cfg(not(target_os = "android"))]
pub fn install_apk(_path: &str) -> Result<(), String> {
    Err("桌面端不涉及 APK 安装".to_string())
}

/// 拉起 `:server` 独立进程（见 mod android 里的实现与「为什么不用 call_activity」的说明）。
#[cfg(target_os = "android")]
pub fn start_server_process(id: &str) -> Result<(), String> {
    android::start_server_process(id)
}

#[cfg(not(target_os = "android"))]
pub fn start_server_process(_id: &str) -> Result<(), String> {
    Err("桌面端不支持 Android 独立进程服务端".to_string())
}

/// 停掉 `:server` 独立进程。
#[cfg(target_os = "android")]
pub fn stop_server_process(id: &str) -> Result<(), String> {
    android::stop_server_process(id)
}

#[cfg(not(target_os = "android"))]
pub fn stop_server_process(_id: &str) -> Result<(), String> {
    Ok(())
}

/// 读 WiFi IPv4（供服务器联机地址显示）
///
/// 走 Activity 实例方法（`MainActivity.wifiIpv4`）：**不能**在 Rust 里
/// `find_class("java/net/NetworkInterface")` —— attach 上来的原生线程用系统类
/// 加载器 FindClass 会抛 Java 异常（实测 "Java exception was raised"），
/// `Class.forName` 也不可靠。让应用侧取好字符串回传是唯一稳定的做法。
#[cfg(target_os = "android")]
pub fn wifi_ipv4() -> Option<String> {
    android::call_activity_str("wifiIpv4", "()Ljava/lang/String;").filter(|s| !s.is_empty())
}

/// 系统「电池优化」是否已对本应用放行（`MainActivity.isBatteryUnrestricted`）。
///
/// false = 后台服务可能被 ROM 的省电策略掐掉（ColorOS/OnePlus 息屏一会儿就动手），
/// 表现是「开服玩一会儿服自己没了」。`foregroundServiceType=specialUse` 只挡得住
/// Android 15 的 6h/24h 硬上限，**挡不住 ROM 自己的省电策略**。
#[cfg(target_os = "android")]
pub fn is_battery_unrestricted() -> bool {
    android::call_activity_str("isBatteryUnrestricted", "()Ljava/lang/String;").as_deref() == Some("1")
}

#[cfg(not(target_os = "android"))]
pub fn is_battery_unrestricted() -> bool {
    true
}

/// 弹系统「忽略电池优化」请求页（`MainActivity.requestIgnoreBatteryOptimizations`）。
///
/// 用户点「允许」后长期有效。不引导的话用户根本不知道要去设置里放行，也就一直被掐。
#[cfg(target_os = "android")]
pub fn request_ignore_battery_optimizations() {
    let _ = android::call_activity("requestIgnoreBatteryOptimizations", "()V", None);
}

#[cfg(not(target_os = "android"))]
pub fn request_ignore_battery_optimizations() {}

/// 设备当前热状态（`MainActivity.thermalStatus`）：0 正常 … 2 中等 … 4 危急 … 6 关机。
///
/// 手机开服跑久了会被系统降频，服务器跟着变卡。把它报给界面，用户才能知道
/// 「不是服务器坏了，是手机热了」。读不到一律当 0（不打扰用户）。
#[cfg(target_os = "android")]
pub fn thermal_status() -> i32 {
    android::call_activity_str("thermalStatus", "()Ljava/lang/String;")
        .and_then(|s| s.trim().parse::<i32>().ok())
        .unwrap_or(0)
}

#[cfg(not(target_os = "android"))]
pub fn thermal_status() -> i32 {
    0
}

/// 把一个目录交给系统文件管理器打开（`MainActivity.openDirectory`，经 FileProvider 的
/// `content://` 目录 URI）。
///
/// 返回 1 = 已交给外部应用，0 = 本机没有能打开「文件夹」的应用（调用方退回内置文件管理），
/// -1 = 目录不存在。
#[cfg(target_os = "android")]
pub fn open_directory(path: &str) -> i32 {
    android::call_activity(
        "openDirectory",
        "(Ljava/lang/String;)Ljava/lang/String;",
        Some(path),
    )
    .and_then(|s| s.trim().parse::<i32>().ok())
    .unwrap_or(0)
}

#[cfg(not(target_os = "android"))]
pub fn open_directory(_path: &str) -> i32 {
    0
}

#[cfg(not(target_os = "android"))]
pub fn wifi_ipv4() -> Option<String> {
    None
}

// ==================== 游戏目录（可选：内部 / 应用专属外部 / 自定义） ====================

/// 「游戏目录」可选项：Kotlin `MainActivity.gameDirOptions` 枚举各卷后回传的 JSON 数组字符串。
#[cfg(target_os = "android")]
pub fn game_dir_options_json() -> Option<String> {
    android::call_activity_str("gameDirOptions", "()Ljava/lang/String;")
}

/// 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已授权。
#[cfg(target_os = "android")]
pub fn has_all_files_access() -> bool {
    android::call_activity_str("hasAllFilesAccess", "()Ljava/lang/String;")
        .map(|s| s.trim() == "1")
        .unwrap_or(false)
}

/// 跳到系统设置页申请「所有文件访问」（Android 11+）。
#[cfg(target_os = "android")]
pub fn request_all_files_access() {
    let _ = android::call_activity("requestAllFilesAccess", "()V", None);
}

/// 让陶瓦隧道（`:tunnel`）**按需重绑**一次。探测到连不上时用，见 `terracotta.rs`。
#[cfg(target_os = "android")]
pub fn ensure_tunnel_service() {
    let _ = android::call_activity("ensureTunnelService", "()V", None);
}

/// 告诉原生「日志页在前台」，之后音量- / 音量+ 会被转成缩放事件派给 WebView。
/// 离开日志页一定要关掉，否则游戏里按音量+-会失效。
pub fn set_log_zoom_capture(on: bool) {
    #[cfg(target_os = "android")]
    {
        let _ = android::call_activity_bool("setLogZoomCapture", "(Z)V", on);
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = on;
    }
}

/// 弹「VPN（陶瓦联机）」的系统授权对话框（已授权时什么都不做）。
///
/// 必须在开房**之前**调用：Terracotta 的 VpnService 请求只有 30 秒答复窗口，
/// 系统授权对话框要用户点一下，塞在那个窗口里太容易超时。
#[cfg(target_os = "android")]
pub fn ensure_vpn_consent() {
    let _ = android::call_activity("ensureVpnConsent", "()V", None);
}

/// 是否已获得 VPN 授权。
#[cfg(target_os = "android")]
pub fn vpn_consent_granted() -> bool {
    android::call_activity_str("vpnConsentGranted", "()Ljava/lang/String;")
        .map(|s| s.trim() == "1")
        .unwrap_or(false)
}

/// 把事件推给 Kotlin（阶段 2 的 JNI 事件通道）。
///
/// Rust 侧所有业务事件（进度、状态、日志行）现在统一走这里，
/// 由 Kotlin `RustBridge.deliverEvent` 分发给页面订阅者，
/// 不再依赖隐藏 WebView 里的 JS 通道（那条断了就是「界面不动」）。
#[cfg(target_os = "android")]
pub fn push_event_to_kotlin(event: &str, json: &str) -> Option<()> {
    android::call_activity2(
        "onCoreEvent",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        event,
        json,
    )
    .map(|_| ())
}

/// 让 Kotlin 侧的 `JvmInstallService`（`:jvm` 独立进程）跑一次 JVM。
///
/// 用于 Forge/NeoForge 的 installer / processors：这些必须用 JVM 跑，但安卓禁止
/// exec 私有目录里的 java，且 `JNI_CreateJavaVM` 一个进程只能成功一次，所以统一
/// 丢给 `:jvm` 进程（见 `JvmInstallService` 的注释）。结果由服务写进
/// `<data>/logs/jvm-install-result.txt`，调用方轮询那个文件即可。
#[cfg(target_os = "android")]
pub fn start_jvm_install_service(json: &str) -> Option<()> {
    android::call_activity1(
        "startJvmInstallService",
        "(Ljava/lang/String;)Ljava/lang/String;",
        json,
    )
}

/// 导出（分享）某个目录里的日志：原生侧打成 zip 再弹系统分享。
#[cfg(target_os = "android")]
pub fn share_logs_zip(archive_name: &str, dir: &str) {
    let _ = android::call_activity2(
        "shareLogsZip",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        archive_name,
        dir,
    );
}

/// 把**已经存在的文件**交给系统分享（导出的整合包等）。
///
/// `path` 必须落在 FileProvider 映射过的目录里（见 `res/xml/file_paths.xml`），
/// 否则 `getUriForFile` 会抛 `IllegalArgumentException`。导出包正是写到映射过的
/// `<files>/exports/`，所以这里直接传绝对路径。
///
/// 为什么走分享而不是「另存为」：手机上用户要的通常是「发给别人」或「存到文件」，
/// 系统分享面板两者都覆盖（选文件管理器即可落盘），比强制先选目录少一步。
#[cfg(target_os = "android")]
pub fn share_file(path: &str, mime: &str) {
    let _ = android::call_activity2(
        "shareFile",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        path,
        mime,
    );
}

#[cfg(not(target_os = "android"))]
pub fn share_file(_path: &str, _mime: &str) {}

// ── 控制布局（Pojav 按键布局）────────────────────────────────────────────
// 这些都要过 Activity：原生编辑器是 Activity、SAF 要 Activity 结果。
// 返回值用字符串（JSON），因为 JNI 侧构造对象很啰嗦。

/// 打开原生控制布局编辑器（横屏 + 游戏主题，与游戏内那个界面一致）。
#[cfg(target_os = "android")]
pub fn open_control_editor(layout: Option<&str>, preview: bool, save_as: Option<&str>) {
    // 空串 = 「用当前默认」（Kotlin 侧按 isNullOrBlank 判断），省得在 JNI 里构造 null 引用
    android::call_activity_sbs(
        "openControlEditor",
        "(Ljava/lang/String;ZLjava/lang/String;)V",
        layout.unwrap_or(""),
        preview,
        save_as.unwrap_or(""),
    );
}

/// 导出（分享）控制布局。
#[cfg(target_os = "android")]
pub fn export_control_layout(layout: &str) {
    // 模块里已有「一个字符串参数」的 helper，不用自己拼 JNI
    let _ = android::call_activity(
        "exportControlLayout",
        "(Ljava/lang/String;)V",
        Some(layout),
    );
}

/// 弹 SAF 让用户选一个控制布局文件。
#[cfg(target_os = "android")]
pub fn pick_control_layout() {
    let _ = android::call_activity("importControlLayout", "()Ljava/lang/String;", None);
}

/// 直接按**文件路径**导入控制布局（不经SAF —— SAF 打不开 Android/data）。
#[cfg(target_os = "android")]
pub fn import_control_layout_from_path(path: &str) -> Option<String> {
    // `call_activity`（单字符串参数 + 返回字符串）正好符合这个签名
    android::call_activity(
        "importControlLayoutFromPath",
        "(Ljava/lang/String;)Ljava/lang/String;",
        Some(path),
    )
}

/// 取回 SAF 选择的结果（JSON：ok/buttons/joysticks/drawers/error），没有则返回空串。
#[cfg(target_os = "android")]
pub fn take_control_import() -> Option<String> {
    let dir = crate::settings::data_dir_sync()?;
    let p = std::path::PathBuf::from(dir).join("control-import.json");
    let s = std::fs::read_to_string(&p).ok()?;
    let _ = std::fs::remove_file(&p);
    Some(s)
}

/// 弹出系统的目录选择器（SAF）。**异步** —— 结果由原生写进
/// `<files>/game-dir-pick.json`，Rust 侧用 `game_dir::take_picked_game_dir` 取回。
#[cfg(target_os = "android")]
pub fn pick_game_dir() {
    let _ = android::call_activity("pickGameDir", "()V", None);
}

/// 强制结束 `:server` 进程（RCON 停服失败时的兜底：世界可能没存盘）。
#[cfg(target_os = "android")]
pub fn force_stop_server(_id: &str) -> Result<(), String> {
    // 直接结束整个 `:server` 服务：JVM 随之消失，服也就停了。
    // 故意不发 /stop —— 那个请求内部是 System.exit，属于同一条不优雅的路。
    android::stop_server_process_any()
}

#[cfg(not(target_os = "android"))]
pub fn force_stop_server(_id: &str) -> Result<(), String> {
    Ok(())
}

/// 通知 Kotlin 撤掉前台服务的常驻通知（优雅停服成功后调用）
#[cfg(target_os = "android")]
pub fn notify_server_stopped(id: &str) -> Result<(), String> {
    android::notify_server_stopped(id)
}

#[cfg(not(target_os = "android"))]
pub fn notify_server_stopped(_id: &str) -> Result<(), String> {
    Ok(())
}

/// 拉起游戏界面（GameActivity）。游戏必须跑在带 SurfaceView 的 Activity 里，
/// 否则 GL4ES 没有 Surface 可用，GLFW 创建窗口就会失败。
#[cfg(target_os = "android")]
pub fn start_game_activity(instance_id: &str, account_uuid: &str) -> Result<(), String> {
    android::call_activity2(
        "startGameActivity",
        "(Ljava/lang/String;Ljava/lang/String;)V",
        instance_id,
        account_uuid,
    )
    .map(|_| ())
    .ok_or_else(|| "无法拉起游戏界面（原生桥未就绪）".to_string())
}

#[cfg(not(target_os = "android"))]
pub fn start_game_activity(_instance_id: &str, _account_uuid: &str) -> Result<(), String> {
    Err("桌面端不需要 GameActivity".to_string())
}

/// 把文件选择器返回的 `content://` URI 落地成应用私有目录下的真实路径。
/// 已经是普通路径时原样返回。
#[cfg(target_os = "android")]
pub fn resolve_picked_path(uri_or_path: &str) -> Result<String, String> {
    android::call_activity(
        "resolvePickedUri",
        "(Ljava/lang/String;)Ljava/lang/String;",
        Some(uri_or_path),
    )
    .ok_or_else(|| "无法读取所选文件".to_string())
}

/// 把文本写回「另存为」选中的目标。
///
/// 安卓的文件选择器（`ACTION_CREATE_DOCUMENT`）返回的是 SAF 的 `content://` URI，
/// 不是路径 —— Rust 侧对 URI 做 `fs::write` 必然失败，导出日志/配置就一直报错。
/// 写入必须由 ContentResolver 完成，这里转交给原生。
///
/// 返回值约定：原生侧成功返回**空串**，失败返回错误信息，桥不可用返回 None。
#[cfg(target_os = "android")]
pub fn write_text_to_uri(uri_or_path: &str, content: &str) -> Result<(), String> {
    match android::call_activity_str2(
        "writeTextToUri",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        uri_or_path,
        content,
    ) {
        Some(msg) if msg.is_empty() => Ok(()),
        Some(msg) => Err(msg),
        None => Err("原生桥不可用，无法写入所选位置".to_string()),
    }
}

#[cfg(not(target_os = "android"))]
pub fn write_text_to_uri(uri_or_path: &str, content: &str) -> Result<(), String> {
    let p = std::path::Path::new(uri_or_path);
    if let Some(parent) = p.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    std::fs::write(p, content).map_err(|e| format!("写入文件失败: {e}"))
}

/// 读取系统剪贴板文本。
///
/// 代码编辑器的「粘贴」在 WebView 里没有可用路径：`navigator.clipboard.readText()`
/// 需要 wry 未授予的 `clipboard-read` 权限，而 `document.execCommand("paste")`
/// 在 Chromium 里按设计永远失败。只能由原生读。
#[cfg(target_os = "android")]
pub fn read_clipboard() -> Result<String, String> {
    android::call_activity("readClipboardText", "()Ljava/lang/String;", None)
        .ok_or_else(|| "剪贴板为空或读取失败".to_string())
}

/// 桌面端浏览器有完整的 Clipboard API，不需要也不应该走原生。
#[cfg(not(target_os = "android"))]
pub fn read_clipboard() -> Result<String, String> {
    Err("当前平台请使用系统粘贴".to_string())
}

/// 写入系统剪贴板。
///
/// 同样是因为 WebView 没给 `clipboard-write` 权限（`writeText` 抛 NotAllowedError）。
/// 返回约定同 `write_text_to_uri`：原生侧成功返回**空串**。
#[cfg(target_os = "android")]
pub fn write_clipboard(text: &str) -> Result<(), String> {
    match android::call_activity(
        "writeClipboardText",
        "(Ljava/lang/String;)Ljava/lang/String;",
        Some(text),
    ) {
        Some(msg) if msg.is_empty() => Ok(()),
        Some(msg) => Err(msg),
        // 桥不可用时 `call_activity` 返回 None
        None => Err("写入剪贴板失败（原生桥未就绪）".to_string()),
    }
}

#[cfg(not(target_os = "android"))]
pub fn write_clipboard(_text: &str) -> Result<(), String> {
    Err("当前平台请使用系统复制".to_string())
}

/// 读取当前**系统代理**（Android 上是 VPN / Wi-Fi 下发的 HTTP 代理，例如 Clash 的 127.0.0.1:7890）。
/// 返回形如 `http://127.0.0.1:7890` / `socks5://127.0.0.1:1080`；没有代理时返回 None。
///
/// 原生 socket 不会自动走系统代理，只有显式套用这个地址，`proxy_mode = "system"`
/// 才会和 WebView 的联网行为一致。
#[cfg(target_os = "android")]
pub fn system_proxy() -> Option<String> {
    android::call_activity("systemProxy", "()Ljava/lang/String;", None)
        .filter(|s| !s.trim().is_empty())
}

/// 预加载 JRE 自带的原生库（JDK 8 必需）。
///
/// 见 Kotlin 侧 `preloadJreLibs` 的说明：Android 的 linker 只按命名空间路径解析
/// 裸文件名，JDK 8 的库之间靠裸名字互相依赖（libnio.so → libnet.so），
/// 必须在 JVM 启动前用绝对路径先加载进来。
#[cfg(target_os = "android")]
/// 让启动器侧启用 SDL 整合（MC 26.3+ 的 SDL 窗口层必需）。
///
/// 详情见 Kotlin 侧 `MainActivity.enableSdlIntegration()`：SDL3 在安卓上依赖
/// `org.libsdl.app.SDL` 那套 Java 胶水拿 Surface，必须由启动器侧准备好。
pub fn enable_sdl_integration() -> Result<(), String> {
    android::call_activity("enableSdlIntegration", "()V", None)
        .map(|_| ())
        .ok_or_else(|| "启用 SDL 整合失败（原生桥未就绪）".to_string())
}

#[cfg(not(target_os = "android"))]
pub fn enable_sdl_integration() -> Result<(), String> {
    Ok(())
}

#[cfg(target_os = "android")]
pub fn preload_jre_libs(jre_home: &str) -> Result<i32, String> {
    android::call_activity("preloadJreLibs", "(Ljava/lang/String;)I", Some(jre_home))
        .and_then(|s| s.trim().parse::<i32>().ok())
        .ok_or_else(|| "预加载 JRE 原生库失败（原生桥未就绪）".to_string())
}

#[cfg(not(target_os = "android"))]
pub fn preload_jre_libs(_jre_home: &str) -> Result<i32, String> {
    Ok(0)
}
/// 读取移植过来的 Pojav 控制层设置（整份 SharedPreferences，JSON 字符串）。
///
/// 这些设置（分辨率缩放 / 按钮大小 / 鼠标速度 / 忽略刘海 / 长按判定 / 陀螺仪…）
/// 由 `LauncherPreferences` 从安卓侧读取，QookiX 的 settings.json 管不到它们，
/// 所以必须过这座桥。
#[cfg(target_os = "android")]
pub fn read_pojav_prefs() -> Result<String, String> {
    android::call_activity("readPojavPrefs", "()Ljava/lang/String;", None)
        .ok_or_else(|| "读取游戏内设置失败（原生桥未就绪）".to_string())
}

/// 写入 Pojav 设置（JSON）。Kotlin 侧写完会重载 LauncherPreferences，
/// 所以不需要重启应用即可生效。
#[cfg(target_os = "android")]
pub fn write_pojav_prefs(json: &str) -> Result<(), String> {
    android::call_activity(
        "writePojavPrefs",
        "(Ljava/lang/String;)V",
        Some(json),
    )
    .map(|_| ())
    .ok_or_else(|| "保存游戏内设置失败（原生桥未就绪）".to_string())
}

#[cfg(not(target_os = "android"))]
pub fn read_pojav_prefs() -> Result<String, String> {
    Ok("{}".to_string())
}

#[cfg(not(target_os = "android"))]
pub fn write_pojav_prefs(_json: &str) -> Result<(), String> {
    Ok(())
}

#[cfg(not(target_os = "android"))]
pub fn set_orientation(_mode: &str) -> Result<(), String> {
    Ok(())
}

#[cfg(not(target_os = "android"))]
pub fn resolve_picked_path(uri_or_path: &str) -> Result<String, String> {
    Ok(uri_or_path.to_string())
}

#[cfg(not(target_os = "android"))]
pub fn system_proxy() -> Option<String> {
    None
}

#[cfg(target_os = "android")]
#[path = "android_bridge/mod_android.rs"]
mod android;

