//! GL_VENDOR 品牌串覆盖：把游戏内 F3 里的渲染器署名串换成我们自己的。
//!
//! 背景：MC 的 F3「Display: WxH (…)」那行读的就是 `glGetString(GL_VENDOR)`
//! （1.18.2 里是 `dsg.a()` → `GlStateManager._getString(7936)`，用字节码确认过），
//! 而实际报出来的是渲染器（GL4ES 一系）自带的署名串 —— 那串在随包的二进制里查不到
//! （运行期拼装出来的），改文件改不掉。所以不去动渲染器，而是在**取值处**接管：
//! 把 LWJGL 的 `org.lwjgl.opengl.GL11C.nglGetString` 换成我们的实现，
//! 只改 `GL_VENDOR`，`GL_VERSION` / `GL_RENDERER` 等原样透传。
//!
//! 为什么放在 Rust 侧、而不是 native 的 `JNI_OnLoad`：
//! `JNI_OnLoad` 里线程没有类加载器上下文，`FindClass` 会把进程搞崩（实测：游戏在
//! `Setting user` 处静默退出）。这里等 JVM 起来之后由我们自己 `AttachCurrentThread`
//! 的线程执行，`FindClass` 走系统类加载器（就是启动游戏时那串 classpath），最稳。
//!
//! 许可：目标串是**追加**（`QookiX & ptitSeb`）而不是抹掉 —— MIT 要求保留版权声明。

use std::ffi::c_void;
use std::os::raw::c_char;
use std::sync::atomic::{AtomicPtr, Ordering};
use std::time::{Duration, Instant};

use jni::sys::{jlong, JNIEnv as RawJNIEnv};
use jni::JavaVM;

/// GL_VENDOR 的枚举值（见 `jni/GL/gl.h`）。
const GL_VENDOR: i32 = 0x1F00;
/// 目标串（NUL 结尾，直接当 `const GLubyte*` 返回给 LWJGL）。
const BRAND: &[u8] = b"QookiX & ptitSeb\0";

type GlGetStringFn = unsafe extern "C" fn(u32) -> *const u8;

/// 真实的 `glGetString`（渲染器库里的那份），惰性解析一次后缓存。
static REAL_GL_GET_STRING: AtomicPtr<c_void> = AtomicPtr::new(std::ptr::null_mut());

/// 解析渲染器库里的真实 `glGetString`。
///
/// 注意：**解析不到时绝不能返回 0 给 LWJGL** —— 它会把 0 当 C 字符串指针去读，
/// 直接空指针崩（`memASCII(0)`）。所以调用方必须先确认拿到的不是 NULL。
unsafe fn resolve_real_gl_get_string() -> *mut c_void {
    let cached = REAL_GL_GET_STRING.load(Ordering::Relaxed);
    if !cached.is_null() {
        return cached;
    }
    let sym = b"glGetString\0".as_ptr() as *const c_char;

    // Never query RTLD_DEFAULT here: looking up glGetString before renderer libs finish registering
    // can resolve our own symbol or a wrong stub; only query already-loaded renderer handles.
    let mut f: *mut c_void = std::ptr::null_mut();
    for name in [
        b"libgl4es_114.so\0".as_ptr() as *const c_char,
        b"libOSMesa.so\0".as_ptr() as *const c_char,
        b"libmobileglues.so\0".as_ptr() as *const c_char,
    ] {
        let handle = libc::dlopen(name, libc::RTLD_LAZY | libc::RTLD_NOLOAD);
        if handle.is_null() {
            continue;
        }
        f = libc::dlsym(handle, sym);
        if !f.is_null() {
            break;
        }
    }

    if !f.is_null() {
        REAL_GL_GET_STRING.store(f, Ordering::Relaxed);
    }
    f
}

/// 注册给 `GL11C.nglGetString` 的实现。
extern "system" fn qookix_ngl_get_string(
    _env: *mut RawJNIEnv,
    _class: *mut c_void,
    name: i32,
) -> jlong {
    if name == GL_VENDOR {
        return BRAND.as_ptr() as jlong;
    }
    let real = unsafe { resolve_real_gl_get_string() };
    if real.is_null() {
        // 极端情况（还没拿到渲染器函数）：返回 0 会让 LWJGL 崩，所以这里退化成给一个
        // 空串的地址 —— 至少不会空指针，行为也接近「没有这个信息」。
        return b"\0".as_ptr() as jlong;
    }
    let real: GlGetStringFn = unsafe { std::mem::transmute(real) };
    unsafe { real(name as u32) as jlong }
}

/// 在后台等 JVM 起来并安装拦截（不阻塞启动流程）。
///
/// 时机是这块最容易踩的地方，分两道闸：
///
/// **闸 1：等游戏自己的原生库引导走完**（2026-09-26 加）。
/// `RegisterNatives` 会**触发目标类的静态初始化**，而 `GL11C` → `GL` →
/// `org.lwjgl.system.Library` 的静态初始化里就 `System.loadLibrary("lwjgl")`。
/// 我们抢在那时插进去，就会和 MC 主线程的库加载赛跑：在「库刚 dlopen、还没注册到
/// 类加载器」的窗口里把 GLFW / MemoryUtil 初始化掉，它们的 native 必然解析不到 ——
/// 整局直接崩（26.3 实测 `Could not initialize class org.lwjgl.system.MemoryUtil`、
/// 栈上线程是 `Thread-0`，也就是本线程）。
///
/// 判据用 MC 自己的日志（这两行都在原生库装好之后才写）：
/// 1.18/1.21 → `Setting user`；26.x → `Backend library:`。
/// 最多等 120 秒，超时也放行（那时早过了那个窗口）。
///
/// **闸 2：原来的「第 2 个 JavaVM + GL11C 找得到就装」**（见 `try_install`）。
pub fn spawn_installer(game_dir: &str) {
    let game_dir = game_dir.to_string();
    let launch_time = std::time::SystemTime::now();
    std::thread::spawn(move || {
        let start = Instant::now();
        let gate_deadline = start + Duration::from_secs(120);
        while Instant::now() < gate_deadline && !natives_ready(&game_dir, launch_time) {
            std::thread::sleep(Duration::from_millis(300));
        }
        let deadline = start + Duration::from_secs(180);
        loop {
            if Instant::now() > deadline {
                tracing::warn!("GL_VENDOR 覆盖：等待 JVM 超时，放弃安装");
                return;
            }
            match try_install() {
                Ok(true) => {
                    tracing::info!("GL_VENDOR 覆盖：已接管 GL11C.nglGetString（GL_VENDOR -> QookiX & ptitSeb）");
                    return;
                }
                Ok(false) => {}
                Err(e) => tracing::warn!("GL_VENDOR 覆盖：安装失败 {e}"),
            }
            std::thread::sleep(Duration::from_millis(300));
        }
    });
}

/// MC 的日志里是否已出现「原生库装好」的标记（见 `spawn_installer` 的闸 1）。
fn natives_ready(game_dir: &str, launch_time: std::time::SystemTime) -> bool {
    const MARKERS: [&str; 2] = ["Setting user", "Backend library:"];
    let path = std::path::Path::new(game_dir)
        .join("logs")
        .join("latest.log");
    let modified_after_launch = std::fs::metadata(&path)
        .and_then(|m| m.modified())
        .is_ok_and(|mtime| mtime >= launch_time);
    if !modified_after_launch {
        return false;
    }
    let Ok(text) = std::fs::read_to_string(&path) else {
        return false;
    };
    MARKERS.iter().any(|m| text.contains(m))
}

/// 尝试安装一次。返回 `Ok(true)` 表示装好了。
///
/// **只在看到第 2 个 JavaVM 之后才动手**：进程里第 1 个是安卓自身的 ART VM，
/// 第 2 个才是我们启动的游戏 JVM。过早去 `FindClass` 会把 LWJGL 的类提前加载进
/// ART（那边连 classpath 都没有，纯属添乱），所以先用 VM 数量把时机卡住。
fn try_install() -> Result<bool, String> {
    let vms = created_java_vms();
    if vms.len() < 2 {
        return Ok(false);
    }
    for vm in vms {
        // 逐个试：ART 那个找不到 LWJGL 的类（返回 false），游戏 JVM 能找到。
        let result = (|| -> Result<bool, String> {
            let mut env = match vm.attach_current_thread() {
                Ok(env) => env,
                Err(_) => return Ok(false),
            };
            attach_hook(&mut env)
        })();
        // 绝不能 Drop：JavaVM 的 Drop 会 DestroyJavaVM（游戏就没了）。
        std::mem::forget(vm);
        if let Ok(true) = result {
            return Ok(true);
        }
    }
    Ok(false)
}

fn attach_hook(env: &mut jni::JNIEnv) -> Result<bool, String> {
    let class = match env.find_class("org/lwjgl/opengl/GL11C") {
        Ok(c) => c,
        Err(_) => {
            // 清掉异常：这是"还没轮到游戏 JVM"的正常情况。
            let _ = env.exception_clear();
            return Ok(false);
        }
    };

    let methods = [jni::NativeMethod {
        name: "nglGetString".into(),
        sig: "(I)J".into(),
        fn_ptr: qookix_ngl_get_string as *mut c_void,
    }];
    match env.register_native_methods(class, &methods) {
        Ok(()) => Ok(true),
        Err(e) => Err(format!("RegisterNatives 失败：{e}")),
    }
}

/// 取当前进程里所有已创建的 JavaVM（ART 的 + 我们启动的游戏 JVM）。
///
/// **必须把每个候选库都问一遍，不能第一个成功就 break**：`JNI_GetCreatedJavaVMs`
/// 在 ART（`libart.so`）和 JRE（`libjvm.so`）里是**两套互不可见的注册表** ——
/// 只问 ART 的话永远只能看到安卓自身那个 VM，也就永远等不到"第 2 个 VM"。
fn created_java_vms() -> Vec<JavaVM> {
    let mut out: Vec<JavaVM> = Vec::new();
    let mut seen: Vec<*mut c_void> = Vec::new();
    let candidates = [
        "libart.so",   // 安卓自带的 ART VM
        "libjvm.so",   // JRE：我们启动的游戏 JVM 登记在这里
        "libjli.so",
        "libnativehelper.so",
    ];
    for name in candidates {
        let candidate = unsafe { libloading::Library::new(name) };
        let Ok(lib) = candidate else { continue };
        let get_vms = unsafe {
            lib.get::<unsafe extern "C" fn(*mut *mut c_void, jni::sys::jsize, *mut jni::sys::jsize) -> jni::sys::jint>(
                b"JNI_GetCreatedJavaVMs\0",
            )
        };
        let Ok(get_vms) = get_vms else { continue };

        let mut ptrs: [*mut c_void; 8] = [std::ptr::null_mut(); 8];
        let mut n: jni::sys::jsize = 0;
        if unsafe { get_vms(ptrs.as_mut_ptr(), 8, &mut n) } != 0 || n <= 0 {
            continue;
        }
        for ptr in ptrs.iter().take(n as usize) {
            if ptr.is_null() || seen.contains(ptr) {
                continue;
            }
            if let Ok(vm) = unsafe { JavaVM::from_raw((*ptr).cast()) } {
                seen.push(*ptr);
                out.push(vm);
            }
        }
    }
    out
}
