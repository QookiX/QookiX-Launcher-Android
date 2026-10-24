//! 安卓运行时环境适配层。
//!
//! 三件事，都是「在安卓上把桌面版 Minecraft 跑起来」绕不开的：
//! 1. 找出 APK 的原生库目录（`/data/app/~~xxx/<pkg>-yyy/lib/arm64`），
//!    把它放进 `java.library.path`，JVM 才能 `System.loadLibrary` 到
//!    Pojav 版 `liblwjgl.so` / `libgl4es_114.so` / `libopenal.so`。
//! 2. 把内嵌的 Pojav 版 LWJGL（`lwjgl-glfw-classes.jar`）释放到磁盘并塞进 classpath，
//!    用它顶掉桌面版 `org.lwjgl:*`。
//! 3. 安卓进程的 stdout/stderr 默认直接丢弃，必须重定向到文件，
//!    否则游戏日志一行都看不到（同时把新增行推给前端日志面板）。

#![cfg(target_os = "android")]

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI32, Ordering};

/// Surface 的实际像素尺寸，由 Kotlin 侧在 `surfaceChanged` 时同步过来。
///
/// 游戏窗口尺寸必须与它一致：安卓端的触摸坐标是 Surface 像素，
/// 若游戏以为窗口是 1280x720 而 Surface 实际是 2424x1080，
/// 所有点击都会换算到错误的位置（表现为「画面正常但点不动按钮」）。
static SURFACE_WIDTH: AtomicI32 = AtomicI32::new(0);
static SURFACE_HEIGHT: AtomicI32 = AtomicI32::new(0);

/// 记录 Surface 尺寸（Kotlin 侧 `GameSurfaceView.surfaceChanged` 调用）。
pub fn set_surface_size(width: i32, height: i32) {
    if width > 0 && height > 0 {
        SURFACE_WIDTH.store(width, Ordering::Relaxed);
        SURFACE_HEIGHT.store(height, Ordering::Relaxed);
    }
}

/// 取 Surface 尺寸；还没同步过时返回 None。
pub fn surface_size() -> Option<(i32, i32)> {
    let w = SURFACE_WIDTH.load(Ordering::Relaxed);
    let h = SURFACE_HEIGHT.load(Ordering::Relaxed);
    if w > 0 && h > 0 {
        Some((w, h))
    } else {
        None
    }
}

/// Pojav 版 LWJGL：官方 LWJGL 全部类 + Pojav 针对安卓重写的
/// `org.lwjgl.glfw`（纯 Java GLFW stub，配合 `liblwjgl.so` 走 Surface/EGL）。
/// 编译期内嵌进 .so，运行期释放到磁盘，避免依赖 APK assets 的读取通道。
const LWJGL_JAR: &[u8] = include_bytes!("../assets/lwjgl/lwjgl-glfw-classes.jar");
const LWJGL_JAR_NAME: &str = "lwjgl-glfw-classes.jar";

/// Caciocavallo（安卓上的 AWT 实现）。Java 17 与 Java 8 的包名不同
/// （`com.github.caciocavallosilano.cacio` / `net.java.openjdk.cacio`），
/// 与 `launch.rs` 里按 Java 版本分发的 `-Dawt.toolkit` / `-Djava.system.class.loader` 对应。
const CACIOCAVALLO_17: &[(&str, &[u8])] = &[
    (
        "cacio-shared-1.18-SNAPSHOT.jar",
        include_bytes!("../assets/cacio17/cacio-shared-1.18-SNAPSHOT.jar"),
    ),
    (
        "cacio-tta-1.18-SNAPSHOT.jar",
        include_bytes!("../assets/cacio17/cacio-tta-1.18-SNAPSHOT.jar"),
    ),
];

const CACIOCAVALLO_8: &[(&str, &[u8])] = &[
    (
        "cacio-androidnw-1.10-SNAPSHOT.jar",
        include_bytes!("../assets/cacio8/cacio-androidnw-1.10-SNAPSHOT.jar"),
    ),
    (
        "cacio-shared-1.10-SNAPSHOT.jar",
        include_bytes!("../assets/cacio8/cacio-shared-1.10-SNAPSHOT.jar"),
    ),
    (
        "ResConfHack.jar",
        include_bytes!("../assets/cacio8/ResConfHack.jar"),
    ),
];

static TAIL_ACTIVE: AtomicBool = AtomicBool::new(false);

/// APK 原生库目录。
///
/// `/proc/self/maps` 里一定能看到本进程加载的 `libqookix_lib.so`，
/// 取它的父目录即可，比走 JNI 拿 ApplicationInfo 更省事，也不受 Activity 生命周期影响。
pub fn native_lib_dir() -> Option<String> {
    let maps = std::fs::read_to_string("/proc/self/maps").ok()?;
    let mut fallback: Option<String> = None;
    for line in maps.lines() {
        let Some(path) = line.split_whitespace().last() else {
            continue;
        };
        if !path.starts_with('/') {
            continue;
        }
        for abi in ["/lib/arm64/", "/lib/arm/", "/lib/x86_64/", "/lib/x86/"] {
            if let Some(idx) = path.find(abi) {
                let dir = path[..idx + abi.len() - 1].to_string();
                // 应用自己的库在 /data/app/...（或老设备的 /data/data/...）
                if path.starts_with("/data/app/") || path.starts_with("/data/data/") {
                    return Some(dir);
                }
                fallback.get_or_insert(dir);
            }
        }
    }
    fallback
}

/// 进程 mmap 到的所有 APK 路径（从 /proc/self/maps 里找）。
///
/// 新版本安卓默认 `extractNativeLibs=false`，原生库直接从 APK 里加载，
/// `nativeLibraryDir` 往往是空目录；而且 App Bundle 安装会拆成多个 apk
/// （base.apk + split_config.arm64_v8a.apk），lib 可能在任意一个里，
/// 所以这里收集全部候选，逐个尝试抽取。
/// 本进程的包名（`/proc/self/cmdline` 的第一段就是包名）。
///
/// 用来把 `apk_paths()` 里**别人的 APK 剔掉**。踩过的坑：
/// `/proc/self/maps` 里既有我们自己的 `base.apk`，也有 `com.google.android.webview` 的，
/// 而 WebView 那个通常**排在前面**；旧逻辑一旦发现 `natives/` 里三件套齐全就 `break`，
/// 于是**永远只拿 WebView 的 APK 去抽取**（它里面没有我们的 native 库），
/// 结果是「换了 native 库、`.apk-stamp` 也更新了，但设备上跑的还是旧的 .so」——
/// 表现为改了 C 代码完全没效果，排查起来极具迷惑性。
fn self_package_name() -> Option<String> {
    let cmdline = std::fs::read("/proc/self/cmdline").ok()?;
    let end = cmdline
        .iter()
        .position(|b| *b == 0)
        .unwrap_or(cmdline.len());
    let name = String::from_utf8_lossy(&cmdline[..end]).to_string();
    (!name.is_empty()).then_some(name)
}

pub fn apk_paths() -> Vec<String> {
    let mut ours: Vec<String> = Vec::new();
    let mut others: Vec<String> = Vec::new();
    let Ok(maps) = std::fs::read_to_string("/proc/self/maps") else {
        return ours;
    };
    let pkg = self_package_name();
    for line in maps.lines() {
        let Some(path) = line.split_whitespace().last() else {
            continue;
        };
        if !path.starts_with("/data/app/") || !path.ends_with(".apk") {
            continue;
        }
        if ours.iter().any(|p| p == path) || others.iter().any(|p| p == path) {
            continue;
        }
        match &pkg {
            Some(p) if path.contains(p.as_str()) => ours.push(path.to_string()),
            Some(_) => others.push(path.to_string()),
            None => ours.push(path.to_string()),
        }
    }
    // 兜底：拿不到包名时不至于完全抽不出库（宁可多扫也不能漏）。
    if ours.is_empty() {
        return others;
    }
    ours
}

/// 从 APK 里把 `lib/<abi>/*.so` 抽到 `dest_dir`（已存在且大小一致则跳过）。
///
/// 只抽「JVM 侧真正需要 loadLibrary 的库」：`libqookix_lib.so`(200MB+) 与
/// `libwebviewchromium.so`(170MB) 已经由系统从 APK 直接加载，再抄一份纯属浪费空间
/// （之前就因为把它们复制一遍把 data 分区写满，导致 libpojavexec.so 静默写入失败）。
///
/// 返回（本次新抽取数, 命中条目数, 失败的文件名列表），第三个用于严格校验。
pub async fn extract_native_libs_from_apk(
    apk: &str,
    dest_dir: &Path,
    force: bool,
) -> std::io::Result<(usize, usize, Vec<String>)> {
    let apk = apk.to_string();
    let dest = dest_dir.to_path_buf();
    let prefix = format!("lib/{}/", crate::java::get_device_arch());

    tokio::task::spawn_blocking(move || -> std::io::Result<(usize, usize, Vec<String>)> {
        use std::io::Read;

        let file = std::fs::File::open(&apk)?;
        let mut archive =
            zip::ZipArchive::new(file).map_err(|e| std::io::Error::other(e.to_string()))?;
        std::fs::create_dir_all(&dest)?;

        let mut extracted = 0usize;
        let mut seen = 0usize;
        let mut failed: Vec<String> = Vec::new();
        for index in 0..archive.len() {
            let Ok(mut entry) = archive.by_index(index) else {
                continue;
            };
            let name = entry.name().to_string();
            let Some(so_name) = name.strip_prefix(&prefix) else {
                continue;
            };
            if so_name.contains('/') || !so_name.ends_with(".so") {
                continue;
            }
            if is_system_loaded_lib(so_name) {
                continue;
            }
            seen += 1;
            let out_path = dest.join(so_name);
            if !force {
                if let Ok(meta) = out_path.metadata() {
                    if meta.len() == entry.size() {
                        continue;
                    }
                }
            }
            let mut data = Vec::with_capacity(entry.size() as usize);
            if entry.read_to_end(&mut data).is_err() {
                failed.push(so_name.to_string());
                continue;
            }
            match std::fs::write(&out_path, &data) {
                Ok(()) => extracted += 1,
                Err(e) => {
                    failed.push(format!("{so_name}({e})"));
                }
            }
        }
        Ok((extracted, seen, failed))
    })
    .await
    .map_err(|e| std::io::Error::other(e.to_string()))?
}

/// 这些库由系统直接从 APK 加载，不需要（也不该）复制到应用数据目录。
fn is_system_loaded_lib(name: &str) -> bool {
    matches!(
        name,
        // 我们自己的 Rust 核心：进程启动时已加载
        "libqookix_lib.so"
            // WebView 的 Chromium：由 WebView provider 管理
            | "libwebviewchromium.so"
            | "libwebviewchromium_loader.so"
            // **绝不能在 data 目录留副本**：SDL3 的 `JNI_OnLoad` 里缓存 JavaVM，
            // 而它只会在「Java 类加载器加载这个库」时被调用一次。
            // 进程里出现两份 libSDL3.so 时，LWJGL 从 data 目录 dlopen 的那份
            // （JNI_OnLoad 没跑过）被游戏调用，SDL 内部拿 JavaVM 得到 NULL →
            // `Failed, there is no JavaVM` → 空指针 SIGSEGV（26.3 实测 tombstone）。
            // 只留 APK 里那一份，两条路径的 realpath 相同，linker 会去重。
            | "libSDL3.so"
    ) || name.starts_with("libcrashpad")
        || name.starts_with("libmonochrome")
}

/// 候选 APK 的指纹（路径 + 大小 + mtime）：用来说明「这台设备上装的是哪一版」。
fn apk_stamp(apks: &[String]) -> String {
    let mut parts: Vec<String> = Vec::new();
    for apk in apks {
        let Ok(meta) = std::fs::metadata(apk) else {
            continue;
        };
        let mtime = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);
        parts.push(format!("{apk}:{}:{mtime}", meta.len()));
    }
    parts.join("|")
}

/// 找出「能让 JVM `System.loadLibrary` 成功」的原生库目录。
///
/// **始终以 `<data>/natives/` 为准**：Kotlin 侧 `System.load` 与 JVM 的
/// `java.library.path` 必须指向同一份文件。
///
/// 曾经这里优先返回系统给 APK 解压的库目录，结果进程里存在两份
/// `libpojavexec.so`（系统目录是新的、`<data>/natives` 是旧的），而安卓 linker
/// 按 soname 去重只认先加载的那份 —— 表现为「改了 native 代码却完全不生效」。
/// 因此改成每次都从 APK 增量抽取（按文件大小判断），统一用同一份。
pub async fn resolve_native_lib_dir(data_dir: &str) -> std::io::Result<String> {
    let required = ["liblwjgl.so", "libgl4es_114.so", "libpojavexec.so"];
    let dest = Path::new(data_dir).join("natives");
    let apks = apk_paths();

    // APK 变了就全量重抽。只比单文件大小会漏掉「大小恰好没变」的构建，
    // 而 linker 一旦加载过旧 .so 就会按 soname 复用 —— 表现为改了 native
    // 代码却不生效（排查这种问题极其费时，所以这里用指纹兜住）。
    let stamp_path = dest.join(".apk-stamp");
    let stamp = apk_stamp(&apks);
    let force = std::fs::read_to_string(&stamp_path)
        .map(|s| s.trim() != stamp)
        .unwrap_or(true);

    let mut report: Vec<String> = Vec::new();
    let mut failures: Vec<String> = Vec::new();

    for apk in &apks {
        match extract_native_libs_from_apk(apk, &dest, force).await {
            Ok((extracted, seen, failed)) => {
                report.push(format!(
                    "{}: 命中 {seen} 条，新抽取 {extracted} 条",
                    short_name(apk)
                ));
                failures.extend(failed);
            }
            Err(e) => report.push(format!("{}: 打开失败 {e}", short_name(apk))),
        }
        if required.iter().all(|name| dest.join(name).exists()) && failures.is_empty() {
            break;
        }
    }

    // 老版本曾把 libSDL3.so 抽到这里，留下的那份必须删掉：
    // 进程里一旦存在两份 libSDL3.so，游戏调用的那份没跑过 JNI_OnLoad →
    // SDL 拿不到 JavaVM → `Failed, there is no JavaVM` → SIGSEGV。
    // 现在只认 APK 里那一份（见 is_system_loaded_lib 的说明）。
    let stale_sdl = dest.join("libSDL3.so");
    if stale_sdl.exists() {
        match std::fs::remove_file(&stale_sdl) {
            Ok(()) => crate::util::log_line("已清理 data 目录里多余的 libSDL3.so（避免进程里出现两份）"),
            Err(e) => crate::util::log_line(&format!("清理多余 libSDL3.so 失败：{e}")),
        }
    }

    let missing: Vec<&str> = required
        .iter()
        .copied()
        .filter(|name| !dest.join(name).exists())
        .collect();
    if missing.is_empty() && failures.is_empty() {
        let _ = std::fs::write(&stamp_path, &stamp);
        crate::util::log_line(&format!(
            "原生库就绪：{}（{}）",
            dest.display(),
            if force { "全量重抽" } else { "增量复用" }
        ));
        return Ok(dest.to_string_lossy().to_string());
    }

    // 从 APK 抽取拿不到完整一套（罕见）：退回系统库目录，前提是那边是完整的
    if let Some(dir) = native_lib_dir() {
        if required.iter().all(|name| Path::new(&dir).join(name).exists()) {
            crate::util::log_line(&format!("改用系统原生库目录：{dir}"));
            return Ok(dir);
        }
    }

    Err(std::io::Error::new(
        std::io::ErrorKind::NotFound,
        format!(
            "原生库准备不完整：缺少 [{}]；写入失败 [{}]；明细 {}",
            missing.join("、"),
            failures.join("、"),
            report.join("；")
        ),
    ))
}

/// 只保留路径里的文件名，避免把整条 /data/app/... 塞进错误提示。
fn short_name(path: &str) -> String {
    path.rsplit('/').next().unwrap_or(path).to_string()
}

/// 从 `jre_home`（`<data>/runtimes/JRE-xx`）往上找同级的 `natives/` 目录。
fn find_natives_dir(jre_home: &Path) -> Option<std::path::PathBuf> {
    let mut cur = jre_home;
    for _ in 0..4 {
        cur = cur.parent()?;
        let cand = cur.join("natives");
        if cand.join("libc++_shared.so").exists() {
            return Some(cand);
        }
    }
    None
}

/// 把 `<data>/natives/libc++_shared.so` 按绝对路径挂进全局符号表（RTLD_GLOBAL）。
///
/// JRE 的 `libjvm.so` / `libnio.so` 都依赖 `libc++_shared.so`，而它**只存在于 natives 目录**
/// （`runtimes/JRE-xx/lib` 下没有这一份）。安卓 linker 找依赖只认「已加载列表」里的 soname，
/// 所以必须先把它挂进来；否则会连锁 `dlopen failed: library "libc++_shared.so" not found`，
/// `libjvm.so` 加载不了 → JVM 起不来 → 游戏秒退（启动器只报得出「退出码 -6」）。
/// 典型触发时机：**装完新 APK 后的第一次启动** —— 那时 `natives/` 正在全量重抽。
///
/// 只挂 C++ 运行时，不碰 `libterracotta.so` 等（它们有自己的 `JNI_OnLoad`，不该在这里被拉起）。
fn preload_native_core(natives_dir: &Path) -> bool {
    use libloading::os::unix::{Library as UnixLibrary, RTLD_GLOBAL, RTLD_NOW};

    let core = natives_dir.join("libc++_shared.so");
    if !core.exists() {
        return false;
    }
    match unsafe { UnixLibrary::open(Some(&core), RTLD_NOW | RTLD_GLOBAL) } {
        Ok(lib) => {
            std::mem::forget(lib); // 常驻到进程结束
            true
        }
        Err(e) => {
            crate::util::log_line(&format!("挂载 libc++_shared.so 失败：{e}"));
            false
        }
    }
}

/// 预加载 JRE 自身的 `lib/*.so`。
///
/// 安卓的 linker 不会去 JRE 的 `lib/` 目录找依赖（`LD_LIBRARY_PATH` 在进程启动时
/// 就被 linker 读死了，运行期 setenv 无效），于是 JVM 加载 `lib/libnio.so` 时报
/// `library "libnet.so" not found`。把所有 JRE 库按绝对路径 dlopen 一遍，
/// 之后 JVM 内部按 soname 查找时直接命中已加载的库，问题就绕过去了。
///
/// 返回成功加载的数量；句柄故意泄漏（这些库要一直常驻到进程结束）。
pub fn preload_jre_libraries(jre_home: &Path) -> usize {
    use libloading::os::unix::{Library as UnixLibrary, RTLD_GLOBAL, RTLD_NOW};

    // libjsig 是信号链 shim，预加载会插手信号处理，跳过。
    const SKIP: [&str; 1] = ["libjsig.so"];

    let mut pending: Vec<std::path::PathBuf> = Vec::new();
    // ⚠️⚠️ 顺序有讲究：**必须先 `lib/server`，再 `lib`**（2026-10-16 修）。
    //
    // 踩过的坑（真机实测，表现是「游戏启动直接闪退、只留一行 LinkageError」）：
    //
    //   `lib/` 里有 `libjawt.so` / `libawt_headless.so` / `libawt.so`，
    //   它们 `DT_NEEDED` 了 **`libjvm.so`** —— 而 `libjvm.so` 只在 `lib/server/`。
    //
    //   原来的顺序是 `["lib", "lib/server", ...]`，于是第一轮先尝试
    //   `lib/libjawt.so` → 它的依赖 `libjvm.so` 还没进全局表 → 失败，
    //   报的却是外层那个已经存在的库：
    //       dlopen failed: library "libawt_headless.so" not found
    //          (needed by .../lib/libjawt.so in namespace clns-9)
    //   **报错信息把真正缺的东西指向了错误的方向**，非常容易误判成"缺文件"。
    //
    //   后果：AWT 相关库全部加载不上 → MC 客户端 `Main` 静态初始化碰 AWT →
    //   `ExceptionInInitializerError: null` → JVM 在异常路径上再初始化模块系统
    //   也失败（`SystemModuleFinders$SystemImage`）→ 进程退出。
    //
    //   服务端（Paper）**不碰 AWT**，所以同一个 JRE 下服务端一直正常 ——
    //   这个对比是定位问题的关键线索。
    for sub in ["lib/server", "lib", "lib/client"] {
        collect_so_files(&jre_home.join(sub), &mut pending);
    }
    pending.retain(|path| {
        let name = path.file_name().and_then(|n| n.to_str()).unwrap_or_default();
        !SKIP.contains(&name)
    });

    // 关键一步：先把 natives 里的 C++ 运行时挂进全局表，否则 libjvm.so 会因为
    // 找不到 libc++_shared.so 而整批加载失败（详见 preload_native_core 的注释）。
    let natives_ok = find_natives_dir(jre_home)
        .map(|d| preload_native_core(&d))
        .unwrap_or(false);

    let mut loaded = 0usize;
    let mut last_error = String::new();
    // 必须用 RTLD_GLOBAL：
    //  - 库之间的依赖靠「已加载列表」按 soname 命中（libjava → libjvm 这条链尤其关键），
    //    漏掉 libjvm 会级联失败，只剩几个库能加载；
    //  - JLI 之后要 `dlsym(RTLD_DEFAULT, "JVM_FindClassFromBootLoader")`，
    //    只有全局符号表里才有，否则报「A JNI error has occurred」。
    // 多轮加载：依赖未就绪的先跳过，下一轮再试（通常 2 轮收敛）。
    // 外层再套一层重试：natives 正在全量重抽时，核心库可能晚几百毫秒才变得可加载。
    let mut attempt = 0;
    loop {
        attempt += 1;
    for _ in 0..4 {
        let mut next = Vec::new();
        // 每轮清空：last_error 只在失败时赋值，不清的话早期某轮的失败会
        // 一直留在最终日志里 —— 即使那个库后来**成功加载了**。
        // 真机踩过：日志报 "剩余 0 个未就绪" 却带着 libjawt.so 的错，
        // 让人以为它失败了，白查好几轮。
        last_error.clear();
        let mut progressed = false;
        for path in pending {
            match unsafe { UnixLibrary::open(Some(&path), RTLD_NOW | RTLD_GLOBAL) } {
                Ok(lib) => {
                    // 句柄故意泄漏：这些库要常驻到进程结束
                    std::mem::forget(lib);
                    loaded += 1;
                    progressed = true;
                }
                Err(e) => {
                    last_error = format!("{}: {e}", path.display());
                    next.push(path);
                }
            }
        }
        pending = next;
        if !progressed || pending.is_empty() {
            break;
        }
    }
        if pending.is_empty() {
            break;
        }
        // 只有核心库还卡着才值得重试（多半是 natives 正在重抽）；零头失败不影响启动
        let core_stuck = pending.iter().any(|p| {
            matches!(
                p.file_name().and_then(|n| n.to_str()),
                Some("libjvm.so") | Some("libjava.so")
            )
        });
        if !core_stuck || attempt >= 3 {
            break;
        }
        crate::util::log_line(&format!(
            "JRE 核心库仍未就绪（{} 个），第 {} 次重试前先重挂 C++ 运行时",
            pending.len(),
            attempt
        ));
        std::thread::sleep(std::time::Duration::from_millis(500));
        if let Some(d) = find_natives_dir(jre_home) {
            preload_native_core(&d);
        }
    }

    // ⚠️ 只有在**真的还有未就绪的库**时才报"最后错误"。
    //
    // 之前无论 pending 是否为空都会把 last_error 打出来，而 last_error 是
    // "最后一次失败"的残留 —— 日志于是变成
    //   「剩余 0 个未就绪，最后错误：libjawt.so ...」
    // 自相矛盾，让人以为 libjawt.so 是故障，实际它后来成功了。
    let stuck = if pending.is_empty() {
        String::from("无")
    } else {
        let names: Vec<String> = pending
            .iter()
            .filter_map(|p| p.file_name().and_then(|n| n.to_str()).map(String::from))
            .collect();
        format!("{}（{}）", names.join(", "), last_error)
    };
    crate::util::log_line(&format!(
        "预加载 JRE 内部库 {loaded} 个，未就绪 {} 个：{}｜C++ 运行时：{}",
        pending.len(),
        stuck,
        if natives_ok { "已挂载" } else { "未找到" }
    ));
    loaded
}

fn collect_so_files(dir: &Path, out: &mut Vec<std::path::PathBuf>) {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            continue;
        }
        if path.extension().and_then(|s| s.to_str()) == Some("so") {
            out.push(path);
        }
    }
}

/// 释放到磁盘的安卓适配 jar。
pub struct VendorJars {
    /// Pojav 版 LWJGL（放 classpath）
    pub lwjgl: PathBuf,
    /// Caciocavallo（必须放 **bootclasspath**：`-Djava.system.class.loader`
    /// 指定的类要由引导加载器找到）
    pub cacio: Vec<PathBuf>,
}

/// 把内嵌的第三方 jar 释放到 `<data>/jars/`。大小一致就复用，避免每次启动都重写 2.8MB。
pub async fn ensure_vendor_jars(data_dir: &str, java_version: i32) -> std::io::Result<VendorJars> {
    let dir = Path::new(data_dir).join("jars");
    tokio::fs::create_dir_all(&dir).await?;

    let lwjgl = dir.join(LWJGL_JAR_NAME);
    write_if_needed(&lwjgl, LWJGL_JAR).await?;

    // Caciocavallo：AWT 支持，按 Java 版本选一组（包名不同）
    let cacio_set = if java_version >= 9 {
        CACIOCAVALLO_17
    } else {
        CACIOCAVALLO_8
    };
    let mut cacio = Vec::new();
    for (name, bytes) in cacio_set {
        let path = dir.join(name);
        write_if_needed(&path, bytes).await?;
        cacio.push(path);
    }

    crate::util::log_line(&format!(
        "已释放安卓适配 jar 到 {}（Pojav 版 LWJGL + Caciocavallo {} 个）",
        dir.display(),
        cacio.len()
    ));

    Ok(VendorJars { lwjgl, cacio })
}

async fn write_if_needed(path: &Path, bytes: &[u8]) -> std::io::Result<()> {
    if let Ok(meta) = tokio::fs::metadata(path).await {
        if meta.len() == bytes.len() as u64 {
            return Ok(());
        }
    }
    tokio::fs::write(path, bytes).await
}

// ═══════════════════════════════════════════════════════════════════════════
// LWJGL 3.4.1 组件（MC 26.3 起窗口层从 GLFW 换成 SDL3）
// ═══════════════════════════════════════════════════════════════════════════

/// 随包分发的 LWJGL 3.4.1 组件在 APK 里的位置。
///
/// 注意目录是 **`assets-components`**（Gradle 里声明的第二个 assets 源目录），
/// 不是 `src/main/assets` —— 后者是前端 vite 的 outDir，构建时会被整目录清空。
const LWJGL341_JARS_PREFIX: &str = "assets/components/lwjgl3/3.4.1/";
const LWJGL341_NATIVES_PREFIX: &str = "assets/components/lwjgl-3.4.1-natives/";

/// LWJGL 3.4.1 组件落盘后的路径。
pub struct LwjglComponents {
    /// 已排好加载顺序的 classpath 条目
    /// （`lwjgl.jar` → merged-modules → 其余模块；顺序不能乱，见函数内注释）。
    pub jars: Vec<PathBuf>,
    /// `-Dorg.lwjgl.librarypath` 指向它。
    pub natives_dir: PathBuf,
}

/// 释放 LWJGL 3.4.1 组件（jar + 该 ABI 的 natives），返回可用路径。
///
/// **为什么需要第二套 LWJGL**：MC 26.3 起窗口层由 GLFW 换成 **SDL3** ——
/// 26.3 客户端 jar 里 `org/lwjgl/glfw` 的引用数是 **0**、`org/lwjgl/sdl` 是 27 个类。
/// 而随包那份单体 `lwjgl-glfw-classes.jar`（3.4.0 时代的 GLFW fork）
/// **没有 `org.lwjgl.sdl` 包**，于是 26.3 一进窗口初始化就 NoClassDefFoundError。
///
/// 这套组件取自 AngelAuraMC 的 LWJGL fork（与项目原有那份同源，同样带
/// glfw stub / GLCapabilities / PojavRendererInit 的安卓改写，并额外有 SDL 集成）。
pub async fn ensure_lwjgl341(data_dir: &str) -> std::io::Result<LwjglComponents> {
    let root = Path::new(data_dir).join("lwjgl341");
    let jars_dir = root.join("jars");
    let natives_dir = root.join("natives");
    let apks = apk_paths();
    let arch = crate::java::get_device_arch();

    // APK 换了就全量重抽：只比大小会漏掉「大小恰好没变」的构建。
    let stamp_path = root.join(".apk-stamp");
    let stamp = apk_stamp(&apks);
    let force = std::fs::read_to_string(&stamp_path)
        .map(|s| s.trim() != stamp)
        .unwrap_or(true);

    let mut jars_seen = 0usize;
    let mut natives_seen = 0usize;
    for apk in &apks {
        jars_seen += extract_asset_files(apk, LWJGL341_JARS_PREFIX, &jars_dir, force)
            .await
            .unwrap_or(0);
        natives_seen += extract_asset_files(
            apk,
            &format!("{LWJGL341_NATIVES_PREFIX}{arch}/"),
            &natives_dir,
            force,
        )
        .await
        .unwrap_or(0);
        if jars_seen > 0 && natives_seen > 0 {
            break;
        }
    }

    // classpath 顺序：先 `lwjgl.jar`（核心：Version/MemoryUtil/system.*），
    // 再 merged-modules（**补丁类在这里**：glfw stub、GLCapabilities、
    // PojavRendererInit、SDL 集成），最后才是各模块 jar —— merged 必须排在
    // sdl.jar 之前，否则官方原版 SDLInit/SDLMouse 会盖掉补丁版，
    // 表现为「SDL 起来了但输入桥不工作」。
    const FIRST: [&str; 2] = ["lwjgl.jar", "lwjgl-3.4.1-merged-modules.jar"];
    let mut jars: Vec<PathBuf> = Vec::new();
    for name in FIRST {
        let p = jars_dir.join(name);
        if p.exists() {
            jars.push(p);
        }
    }
    if jars.is_empty() {
        return Err(std::io::Error::other(format!(
            "APK 内缺少 LWJGL 3.4.1 组件（{}）",
            jars_dir.display()
        )));
    }
    let mut rest: Vec<PathBuf> = std::fs::read_dir(&jars_dir)
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| {
            p.extension().is_some_and(|e| e == "jar")
                && p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| !FIRST.contains(&n))
        })
        .collect();
    rest.sort();
    jars.extend(rest);

    if !natives_dir.join("liblwjgl.so").exists() {
        return Err(std::io::Error::other(format!(
            "APK 内缺少 LWJGL 3.4.1 原生库（{}/，ABI={arch}）",
            natives_dir.display()
        )));
    }

    let _ = std::fs::write(&stamp_path, &stamp);
    crate::util::log_line(&format!(
        "LWJGL 3.4.1 组件就绪：{} 个 jar + {} 个原生库（{}，{}）",
        jars.len(),
        natives_seen,
        natives_dir.display(),
        if force { "全量重抽" } else { "增量复用" }
    ));

    Ok(LwjglComponents { jars, natives_dir })
}

/// 从 APK 的 assets 里抽取 `prefix` 下的**直接子文件**到 `dest`。
///
/// 与 `extract_native_libs_from_apk` 的区别：那个专抽 `lib/<abi>/*.so` 并跳过
/// 系统自加载的库；这个走 assets 通道，jar 与 .so 都能抽（LWJGL 3.4.1 的
/// natives 就是以 assets 形式随包分发的）。
async fn extract_asset_files(
    apk: &str,
    prefix: &str,
    dest: &Path,
    force: bool,
) -> std::io::Result<usize> {
    let apk = apk.to_string();
    let prefix = prefix.to_string();
    let dest = dest.to_path_buf();

    tokio::task::spawn_blocking(move || -> std::io::Result<usize> {
        use std::io::Read;

        let file = std::fs::File::open(&apk)?;
        let mut archive =
            zip::ZipArchive::new(file).map_err(|e| std::io::Error::other(e.to_string()))?;
        std::fs::create_dir_all(&dest)?;

        let mut extracted = 0usize;
        for index in 0..archive.len() {
            let Ok(mut entry) = archive.by_index(index) else {
                continue;
            };
            let name = entry.name().to_string();
            let Some(rel) = name.strip_prefix(&prefix) else {
                continue;
            };
            // 只要该前缀下的直接子文件（跳过目录项与子目录里的东西）
            if rel.is_empty() || rel.contains('/') {
                continue;
            }
            let out = dest.join(rel);
            if !force {
                if let Ok(meta) = out.metadata() {
                    if meta.len() == entry.size() {
                        continue;
                    }
                }
            }
            let mut data = Vec::with_capacity(entry.size() as usize);
            if entry.read_to_end(&mut data).is_err() {
                continue;
            }
            std::fs::write(&out, &data)?;
            extracted += 1;
        }
        Ok(extracted)
    })
    .await
    .map_err(|e| std::io::Error::other(e.to_string()))?
}

/// 安卓系统版本号（`ro.build.version.release`），用于 `-Dos.version=Android-<release>`。
/// 部分模组/加载器靠它识别运行环境，拿不到时退回 "Android"。
pub fn android_release() -> String {
    let mut buf = [0 as std::os::raw::c_char; 128];
    let value = unsafe {
        __system_property_get(
            c"ro.build.version.release".as_ptr(),
            buf.as_mut_ptr(),
        )
    };
    if value <= 0 {
        return "Android".to_string();
    }
    let bytes: Vec<u8> = buf
        .iter()
        .take_while(|c| **c != 0)
        .map(|c| *c as u8)
        .collect();
    format!("Android-{}", String::from_utf8_lossy(&bytes))
}

extern "C" {
    fn __system_property_get(name: *const std::os::raw::c_char, value: *mut std::os::raw::c_char) -> std::os::raw::c_int;
}

/// 写出 `<data>/resolv.conf`（游戏内 DNS 用的）。
///
/// 安卓没有 `/etc/resolv.conf`，Pojav 系 JRE 的 DNS 补丁靠 `-Dext.net.resolvPath`
/// （见 `launch.rs`）指到一个我们自己写的文件。不写的话游戏内所有域名解析失败 ——
/// 之前 163 个 mod 的版本检查全部 `HTTP connect timed out` 就是它。
///
/// DNS 服务器怎么选抄 ZalithLauncher2：系统 DNS 在安卓侧拿不到（要 ConnectivityManager），
/// 退而求其次直接给对国内友好的公共 DNS（本项目用户以国内为主）；解析不了也只影响
/// 联网功能，不影响进游戏。
pub fn ensure_resolv_conf(data_dir: &str) -> std::io::Result<()> {
    const CONTENT: &str = "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n";
    let path = Path::new(data_dir).join("resolv.conf");
    if let Ok(existing) = std::fs::read_to_string(&path) {
        if existing == CONTENT {
            return Ok(());
        }
    }
    std::fs::write(&path, CONTENT)
}

/// 把 fd 1 / fd 2 重定向到日志文件。
/// 安卓上应用进程的 stdout 默认进 /dev/null，不重定向等于完全没有游戏日志。
pub fn redirect_output(log_path: &Path) -> std::io::Result<()> {
    if let Some(parent) = log_path.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let file = std::fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(log_path)?;
    let fd = {
        use std::os::unix::io::AsRawFd;
        file.as_raw_fd()
    };
    unsafe {
        libc::dup2(fd, libc::STDOUT_FILENO);
        libc::dup2(fd, libc::STDERR_FILENO);
    }
    // fd 已复制到 1/2，这里不能让它被关闭
    std::mem::forget(file);
    Ok(())
}

/// 启动日志里出现这些措辞，说明游戏**可能**已经死了。
///
/// 光命中关键字还不够，必须再满足「日志停滞」才会真的结束游戏 —— 见
/// [`FATAL_STALL_SECS`]。原因是一次实测事故：1.8.9 启动时会输出
/// `kc: Invalid shaders/program/entity_sobel.fsh: Couldn't compile fragment program…`，
/// 看着像致命错误，其实 MC 自己会 catch 掉（那条 shader 非必需），
/// **游戏照常进主菜单** —— 早先只按关键字就杀，结果每局都在 Mojang 图标后「自己退出」。
///
/// 判定原则：命中关键字 **且** 日志停滞（见 [`FATAL_STALL_SECS`]）才结束游戏。
///
/// **不要加 `Invalid shaders/`**：1.8.9 启动时会输出
/// `kc: Invalid shaders/program/entity_sobel.fsh: Couldn't compile fragment program…`，
/// 看着致命，其实 MC 自己会吞掉、照常进主菜单；而游戏到主菜单后就**不再输出日志**
/// （安静期），「停滞」判据照样会命中 —— 结果两局都变成「进到 Mojang 图标就自己退出」。
/// 宁可漏（那种卡死局面可以用抽屉里的「强制关闭」处理），不可误杀。
///
/// 三者都是明确致命：
///  - `Failed to load required shader programs`：必需着色器加载失败；
///  - `Exception in thread "main"`：主线程挂了；
///  - `A fatal error has been detected`：JVM 自身崩溃（hs_err）。
const FATAL_LAUNCH_MARKERS: &[&str] = &[
    "Failed to load required shader programs",
    "Exception in thread \"main\"",
    "A fatal error has been detected",
];

/// 命中关键字后，日志**停滞**这么久才认定「确实卡死了」，然后才结束游戏。
/// 游戏若还在继续输出日志，就说明它活着，绝不能动它。
const FATAL_STALL_SECS: u64 = 10;

/// 只在启动后这段时间内做上面的「快判」：世界跑起来之后的报错交给
/// 「回到启动器」那条检查路径，不在这里越权动手杀游戏。
const FATAL_WINDOW_SECS: u64 = 300;

/// 由私有日志路径推出「应用专属外部目录」里的镜像路径。
///
/// 输入形如 `/data/data/<pkg>/files/logs/launch-x.log`，
/// 输出形如 `/storage/emulated/0/Android/data/<pkg>/files/logs/launch-x.log`。
///
/// 为什么不用 `/sdcard/...` 直接拼：`/sdcard` 在部分机型/多用户下不是主卷，
/// 而且包名写死会在升级改包名时留下孤儿目录（用户反馈「卸载后残留空文件夹」）。
/// 这里只做纯字符串推导 —— 外部目录就是私有目录在 `/storage/emulated/0`
/// 那一侧的镜像，系统会在卸载时连同 `Android/data/<pkg>` 一起清掉。
fn mirror_log_path(log_path: &Path, instance_id: &str) -> Option<PathBuf> {
    let s = log_path.to_string_lossy();
    let rest = s.strip_prefix("/data/data/")?;
    let mut it = rest.splitn(2, '/');
    let pkg = it.next()?;
    let tail = it.next()?;
    if pkg.is_empty() || tail.is_empty() {
        return None;
    }
    Some(
        Path::new("/storage/emulated/0/Android/data")
            .join(pkg)
            .join(tail)
            .with_file_name(format!("launch-{instance_id}.log")),
    )
}

/// 后台跟随日志文件，把新增行通过 `launch://log` 推给前端日志面板，
/// 顺带做「游戏已死」的快判（见 [`FATAL_LAUNCH_MARKERS`]）。
pub fn spawn_log_tail(log_path: PathBuf, instance_id: String) {
    TAIL_ACTIVE.store(true, Ordering::SeqCst);
    std::thread::spawn(move || {
        use std::io::{BufRead, BufReader, Seek, SeekFrom};

        // ⚠️ 2026-10-20：把启动日志**同步镜像一份到外部存储**。
        //
        // JVM 的 stdout/stderr 被 dup2 到 /data/data/.../files/logs/ 下，
        // 那是应用私有目录 —— `run-as` 对 release 包不可用（not debuggable），
        // `adb pull` 也拿不到。排查「启动失败但日志页只显示前 200 行」时
        // （比如 LWJGL 的异常链被挤在深处）完全够不着。
        //
        // ⚠️ 2026-10-20 修：**不要硬编码 `/sdcard/Android/data/<包名>`**。
        // 那条路径贴在 `/sdcard` 根上，卸载后系统清不掉，用户用 MT 管理器
        // 会看到一堆空文件夹（真机反馈「卸载后文件清不干净」）。
        // 改成从 `log_path` 反推：`/data/data/<pkg>/files/logs/x.log`
        //   → `/storage/emulated/0/Android/data/<pkg>/files/logs/x.log`
        // 这样它落在系统托管的「应用专属外部目录」里，卸载时随包一起清。
        let mirror = mirror_log_path(&log_path, &instance_id);
        let mut mirror_file = mirror.as_ref().and_then(|p| {
            if let Some(dir) = p.parent() {
                std::fs::create_dir_all(dir).ok();
            }
            let _ = std::fs::write(p, "");
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(p)
                .ok()
        });

        let started = std::time::Instant::now();
        let mut fatal_handled = false;
        // 命中的致命关键字（等日志停滞再动手）
        let mut suspect: Option<&'static str> = None;
        // 最近一次读到新日志的时刻 —— 只要它还在往前走，就说明游戏活着
        let mut last_line_at = std::time::Instant::now();
        let mut pos: u64 = 0;
        while TAIL_ACTIVE.load(Ordering::SeqCst) {
            if let Ok(mut file) = std::fs::File::open(&log_path) {
                if file.seek(SeekFrom::Start(pos)).is_ok() {
                    let mut reader = BufReader::new(file);
                    let mut line = String::new();
                    loop {
                        line.clear();
                        match reader.read_line(&mut line) {
                            Ok(0) => break,
                            Ok(n) => {
                                pos += n as u64;
                                let text = line.trim_end_matches(['\n', '\r']);
                                if !text.is_empty() {
                                    last_line_at = std::time::Instant::now();
                                    // ⚠️ 2026-10-20：同时写一份进 logcat。
                                    //
                                    // JVM 的 stdout/stderr 被 dup2 到了启动日志文件，
                                    // logcat 里一行都看不到 —— 排查「启动失败但日志页
                                    // 只显示前 200 行」这类问题（比如 LWJGL 的
                                    // `Incompatible Java and native library versions`
                                    // 被挤在异常链深处）时完全够不着。
                                    // 走 RustStdoutStderr 标签，用户用 `adb logcat` 也能取。
                                    // 注意：fd 1/2 已被 dup2 到日志文件，所以 println!
                                    // 其实**也进文件**、到不了 logcat。真正可取的只有镜像。
                                    if let Some(f) = mirror_file.as_mut() {
                                        use std::io::Write as _;
                                        let _ = writeln!(f, "{text}");
                                        let _ = f.flush();
                                    }
                                    crate::progress::emit_launch_log(&instance_id, "out", text);
                                    if !fatal_handled
                                        && suspect.is_none()
                                        && started.elapsed().as_secs() < FATAL_WINDOW_SECS
                                    {
                                        if let Some(hit) = FATAL_LAUNCH_MARKERS
                                            .iter()
                                            .find(|m| text.contains(**m))
                                        {
                                            // 先记下，等日志停滞再确认（见 FATAL_STALL_SECS）
                                            tracing::warn!(
                                                "[launcher] 启动日志出现可疑致命错误（{hit}），等待确认是否卡死"
                                            );
                                            suspect = Some(hit);
                                        }
                                    }
                                }
                            }
                            Err(_) => break,
                        }
                    }
                }
            }

            // 二次确认：命中关键字之后日志再也不动了，才认定游戏确实死了
            if let Some(hit) = suspect {
                if last_line_at.elapsed().as_secs() >= FATAL_STALL_SECS {
                    fatal_handled = true;
                    suspect = None;
                    let msg = format!(
                        "[launcher] 启动日志已停滞 {FATAL_STALL_SECS} 秒且出现致命错误（{hit}），游戏已无法继续，正在结束它以便回到启动器"
                    );
                    tracing::warn!("{msg}");
                    crate::util::log_line(&msg);
                    crate::progress::emit_launch_log(&instance_id, "out", &msg);
                    // kill_game 是 async：这里在普通线程里，借用 Tauri 的全局运行时
                    tauri::async_runtime::spawn(async {
                        let _ = crate::launch::kill_game().await;
                    });
                }
            }

            std::thread::sleep(std::time::Duration::from_millis(150));
        }
    });
}

/// 停止日志跟随（游戏退出后调用）。
pub fn stop_log_tail() {
    TAIL_ACTIVE.store(false, Ordering::SeqCst);
}
