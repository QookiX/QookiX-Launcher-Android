// EGL / GLES 的包装函数**刻意保持 C 官方拼写**（eglGetDisplay、eglSwapBuffers……），
// 好处是能和 Khronos 规范、以及动态库导出的符号名一一对照 —— 所以本文件放开 snake_case 提醒。
// （真要改名就 12 个定义 + 18 处调用点一起改，纯风格收益，不值得。）
#![allow(non_snake_case)]

use std::sync::Mutex;
use std::sync::OnceLock;
use anyhow::Result;
use libloading::Library;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum RendererType {
    Gl4Es,
    Zink,
    VirglRenderer,
}

#[derive(Debug, thiserror::Error)]
pub enum RenderError {
    #[error("GL_CONTEXT_FAILED: {0}")]
    GlContextFailed(String),
    #[error("TRANSLATOR_LOAD_FAILED: {0}")]
    TranslatorLoadFailed(String),
}

#[cfg(target_os = "android")]
mod egl_ffi {
    use std::ffi::c_void;
    use std::sync::OnceLock;

    pub type EGLDisplay = *mut c_void;
    pub type EGLConfig = *mut c_void;
    pub type EGLContext = *mut c_void;
    pub type EGLSurface = *mut c_void;
    pub type EGLNativeWindowType = *mut c_void;
    pub type EGLNativeDisplayType = *mut c_void;
    pub type EGLint = i32;
    pub type EGLBoolean = i32;
    pub type EGLenum = i32;

    pub const EGL_FALSE: EGLBoolean = 0;
    pub const EGL_TRUE: EGLBoolean = 1;
    pub const EGL_DEFAULT_DISPLAY: EGLNativeDisplayType = std::ptr::null_mut();
    pub const EGL_NO_DISPLAY: EGLDisplay = std::ptr::null_mut();
    pub const EGL_NO_CONTEXT: EGLContext = std::ptr::null_mut();
    pub const EGL_NO_SURFACE: EGLSurface = std::ptr::null_mut();

    pub const EGL_RED_SIZE: EGLint = 5;
    pub const EGL_GREEN_SIZE: EGLint = 6;
    pub const EGL_BLUE_SIZE: EGLint = 7;
    pub const EGL_ALPHA_SIZE: EGLint = 8;
    pub const EGL_DEPTH_SIZE: EGLint = 17;
    pub const EGL_STENCIL_SIZE: EGLint = 18;
    pub const EGL_SURFACE_TYPE: EGLint = 0x3033;
    pub const EGL_WINDOW_BIT: EGLint = 0x0004;
    pub const EGL_RENDERABLE_TYPE: EGLint = 0x3040;
    pub const EGL_OPENGL_ES2_BIT: EGLint = 0x0004;
    pub const EGL_OPENGL_ES3_BIT: EGLint = 0x0040;
    pub const EGL_CONTEXT_CLIENT_VERSION: EGLint = 0x3098;
    pub const EGL_NONE: EGLint = 0x3038;
    pub const EGL_OPENGL_ES_API: EGLenum = 0x30A0;
    pub const EGL_PBUFFER_BIT: EGLint = 0x0001;

    // Function pointers for dynamic loading
    static mut EGL_GET_DISPLAY: Option<unsafe extern "C" fn(EGLNativeDisplayType) -> EGLDisplay> = None;
    static mut EGL_INITIALIZE: Option<unsafe extern "C" fn(EGLDisplay, *mut EGLint, *mut EGLint) -> EGLBoolean> = None;
    static mut EGL_CHOOSE_CONFIG: Option<unsafe extern "C" fn(EGLDisplay, *const EGLint, *mut EGLConfig, EGLint, *mut EGLint) -> EGLBoolean> = None;
    static mut EGL_BIND_API: Option<unsafe extern "C" fn(EGLenum) -> EGLBoolean> = None;
    static mut EGL_CREATE_CONTEXT: Option<unsafe extern "C" fn(EGLDisplay, EGLConfig, EGLContext, *const EGLint) -> EGLContext> = None;
    static mut EGL_CREATE_WINDOW_SURFACE: Option<unsafe extern "C" fn(EGLDisplay, EGLConfig, EGLNativeWindowType, *const EGLint) -> EGLSurface> = None;
    static mut EGL_MAKE_CURRENT: Option<unsafe extern "C" fn(EGLDisplay, EGLSurface, EGLSurface, EGLContext) -> EGLBoolean> = None;
    static mut EGL_DESTROY_SURFACE: Option<unsafe extern "C" fn(EGLDisplay, EGLSurface) -> EGLBoolean> = None;
    static mut EGL_DESTROY_CONTEXT: Option<unsafe extern "C" fn(EGLDisplay, EGLContext) -> EGLBoolean> = None;
    static mut EGL_TERMINATE: Option<unsafe extern "C" fn(EGLDisplay) -> EGLBoolean> = None;
    static mut EGL_SWAP_BUFFERS: Option<unsafe extern "C" fn(EGLDisplay, EGLSurface) -> EGLBoolean> = None;
    static mut EGL_GET_ERROR: Option<unsafe extern "C" fn() -> EGLint> = None;

    static EGL_LIB_LOADED: OnceLock<bool> = OnceLock::new();

    fn load_egl_symbols() {
        EGL_LIB_LOADED.get_or_init(|| {
            let lib = match unsafe { libloading::Library::new("libEGL.so") } {
                Ok(l) => l,
                Err(_) => {
                    tracing::warn!("Failed to load libEGL.so");
                    return false;
                }
            };

            unsafe {
                EGL_GET_DISPLAY = lib.get(b"eglGetDisplay").ok().map(|s| *s);
                EGL_INITIALIZE = lib.get(b"eglInitialize").ok().map(|s| *s);
                EGL_CHOOSE_CONFIG = lib.get(b"eglChooseConfig").ok().map(|s| *s);
                EGL_BIND_API = lib.get(b"eglBindAPI").ok().map(|s| *s);
                EGL_CREATE_CONTEXT = lib.get(b"eglCreateContext").ok().map(|s| *s);
                EGL_CREATE_WINDOW_SURFACE = lib.get(b"eglCreateWindowSurface").ok().map(|s| *s);
                EGL_MAKE_CURRENT = lib.get(b"eglMakeCurrent").ok().map(|s| *s);
                EGL_DESTROY_SURFACE = lib.get(b"eglDestroySurface").ok().map(|s| *s);
                EGL_DESTROY_CONTEXT = lib.get(b"eglDestroyContext").ok().map(|s| *s);
                EGL_TERMINATE = lib.get(b"eglTerminate").ok().map(|s| *s);
                EGL_SWAP_BUFFERS = lib.get(b"eglSwapBuffers").ok().map(|s| *s);
                EGL_GET_ERROR = lib.get(b"eglGetError").ok().map(|s| *s);
            }

            std::mem::forget(lib);
            true
        });
    }

    /// ## 为什么这批包装是 `unsafe fn`
    ///
    /// 每个包装内部都读 `static mut` 的函数指针并调用 FFI —— 本身就不安全，
    /// 以前却声明成安全函数，于是调用处一圈 `unsafe { }` 全成了「多余的块」
    /// （一次编译 18 条警告），真正的风险点反而被藏起来了。
    /// 标成 `unsafe fn` 后：调用点现有的 `unsafe { }` 名副其实，unsafety 也如实传出去。
    pub unsafe fn eglGetDisplay(display_id: EGLNativeDisplayType) -> EGLDisplay {
        load_egl_symbols();
        EGL_GET_DISPLAY.expect("eglGetDisplay not loaded")(display_id)
    }

    pub unsafe fn eglInitialize(dpy: EGLDisplay, major: *mut EGLint, minor: *mut EGLint) -> EGLBoolean {
        load_egl_symbols();
        EGL_INITIALIZE.expect("eglInitialize not loaded")(dpy, major, minor)
    }

    pub unsafe fn eglChooseConfig(
        dpy: EGLDisplay,
        attrib_list: *const EGLint,
        configs: *mut EGLConfig,
        config_size: EGLint,
        num_config: *mut EGLint,
    ) -> EGLBoolean {
        load_egl_symbols();
        EGL_CHOOSE_CONFIG.expect("eglChooseConfig not loaded")(dpy, attrib_list, configs, config_size, num_config)
    }

    pub unsafe fn eglBindAPI(api: EGLenum) -> EGLBoolean {
        load_egl_symbols();
        EGL_BIND_API.expect("eglBindAPI not loaded")(api)
    }

    pub unsafe fn eglCreateContext(
        dpy: EGLDisplay,
        config: EGLConfig,
        share_context: EGLContext,
        attrib_list: *const EGLint,
    ) -> EGLContext {
        load_egl_symbols();
        EGL_CREATE_CONTEXT.expect("eglCreateContext not loaded")(dpy, config, share_context, attrib_list)
    }

    pub unsafe fn eglCreateWindowSurface(
        dpy: EGLDisplay,
        config: EGLConfig,
        win: EGLNativeWindowType,
        attrib_list: *const EGLint,
    ) -> EGLSurface {
        load_egl_symbols();
        EGL_CREATE_WINDOW_SURFACE.expect("eglCreateWindowSurface not loaded")(dpy, config, win, attrib_list)
    }

    pub unsafe fn eglMakeCurrent(
        dpy: EGLDisplay,
        draw: EGLSurface,
        read: EGLSurface,
        ctx: EGLContext,
    ) -> EGLBoolean {
        load_egl_symbols();
        EGL_MAKE_CURRENT.expect("eglMakeCurrent not loaded")(dpy, draw, read, ctx)
    }

    pub unsafe fn eglDestroySurface(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean {
        load_egl_symbols();
        EGL_DESTROY_SURFACE.expect("eglDestroySurface not loaded")(dpy, surface)
    }

    pub unsafe fn eglDestroyContext(dpy: EGLDisplay, ctx: EGLContext) -> EGLBoolean {
        load_egl_symbols();
        EGL_DESTROY_CONTEXT.expect("eglDestroyContext not loaded")(dpy, ctx)
    }

    pub unsafe fn eglTerminate(dpy: EGLDisplay) -> EGLBoolean {
        load_egl_symbols();
        EGL_TERMINATE.expect("eglTerminate not loaded")(dpy)
    }

    pub unsafe fn eglSwapBuffers(dpy: EGLDisplay, surface: EGLSurface) -> EGLBoolean {
        load_egl_symbols();
        EGL_SWAP_BUFFERS.expect("eglSwapBuffers not loaded")(dpy, surface)
    }

    pub unsafe fn eglGetError() -> EGLint {
        load_egl_symbols();
        EGL_GET_ERROR.expect("eglGetError not loaded")()
    }
}

#[cfg(target_os = "android")]
pub mod egl {
    use super::egl_ffi::*;
    use anyhow::Result;
    use std::ffi::c_void;
    use std::time::Instant;

    pub struct EglContext {
        display: EGLDisplay,
        config: EGLConfig,
        context: EGLContext,
        surface: EGLSurface,
        native_window: *mut c_void,
        gles_version: i32,
    }

    impl EglContext {
        pub fn new(native_window: *mut c_void, prefer_gles3: bool) -> Result<Self> {
            if native_window.is_null() {
                return Err(super::RenderError::GlContextFailed(
                    "native window is null".to_string(),
                ).into());
            }

            let t0 = Instant::now();

            let display = unsafe { eglGetDisplay(EGL_DEFAULT_DISPLAY) };
            if display == EGL_NO_DISPLAY {
                return Err(super::RenderError::GlContextFailed(
                    "eglGetDisplay returned EGL_NO_DISPLAY".to_string(),
                ).into());
            }
            tracing::info!("eglGetDisplay ok: {:?}", t0.elapsed());

            let t1 = Instant::now();
            let mut major: EGLint = 0;
            let mut minor: EGLint = 0;
            let ok = unsafe { eglInitialize(display, &mut major, &mut minor) };
            if ok != EGL_TRUE {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglInitialize failed: error {}", unsafe { eglGetError() }),
                ).into());
            }
            tracing::info!("eglInitialize ok: EGL {}.{} {:?}", major, minor, t1.elapsed());

            let (config, gles_version) = Self::choose_config(display, prefer_gles3)?;

            let t3 = Instant::now();
            let bind_ok = unsafe { eglBindAPI(EGL_OPENGL_ES_API) };
            if bind_ok != EGL_TRUE {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglBindAPI failed: error {}", unsafe { eglGetError() }),
                ).into());
            }

            let ctx_attribs: [EGLint; 3] = [EGL_CONTEXT_CLIENT_VERSION, gles_version, EGL_NONE];
            let context = unsafe { eglCreateContext(display, config, EGL_NO_CONTEXT, ctx_attribs.as_ptr()) };
            if context == EGL_NO_CONTEXT {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglCreateContext failed: error {}", unsafe { eglGetError() }),
                ).into());
            }
            tracing::info!("eglCreateContext ok (GLES{}): {:?}", gles_version, t3.elapsed());

            let t4 = Instant::now();
            let surface = unsafe { eglCreateWindowSurface(display, config, native_window as EGLNativeWindowType, std::ptr::null()) };
            if surface == EGL_NO_SURFACE {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglCreateWindowSurface failed: error {}", unsafe { eglGetError() }),
                ).into());
            }
            tracing::info!("eglCreateWindowSurface ok: {:?}", t4.elapsed());

            let t5 = Instant::now();
            let make_ok = unsafe { eglMakeCurrent(display, surface, surface, context) };
            if make_ok != EGL_TRUE {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglMakeCurrent failed: error {}", unsafe { eglGetError() }),
                ).into());
            }
            tracing::info!("eglMakeCurrent ok: {:?}", t5.elapsed());

            Ok(EglContext {
                display,
                config,
                context,
                surface,
                native_window,
                gles_version,
            })
        }

        fn choose_config(display: EGLDisplay, prefer_gles3: bool) -> Result<(EGLConfig, i32)> {
            let attribs_gles3: [EGLint; 15] = [
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_DEPTH_SIZE, 24,
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_NONE,
            ];
            let attribs_gles2: [EGLint; 15] = [
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL_RED_SIZE, 8,
                EGL_GREEN_SIZE, 8,
                EGL_BLUE_SIZE, 8,
                EGL_ALPHA_SIZE, 8,
                EGL_DEPTH_SIZE, 24,
                EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                EGL_NONE,
            ];

            let configs_to_try: &[( &[EGLint], i32)] = if prefer_gles3 {
                &[(attribs_gles3.as_slice(), 3), (attribs_gles2.as_slice(), 2)]
            } else {
                &[(attribs_gles2.as_slice(), 2)]
            };

            for (attribs, gles_ver) in configs_to_try {
                let mut config: EGLConfig = std::ptr::null_mut();
                let mut num_config: EGLint = 0;
                let ok = unsafe { eglChooseConfig(display, attribs.as_ptr(), &mut config, 1, &mut num_config) };
                if ok == EGL_TRUE && num_config > 0 {
                    tracing::info!("eglChooseConfig ok: GLES{}, {} configs", gles_ver, num_config);
                    return Ok((config, *gles_ver));
                }
                tracing::warn!("eglChooseConfig failed for GLES{}: error {}", gles_ver, unsafe { eglGetError() });
            }

            Err(super::RenderError::GlContextFailed(
                "No suitable EGL config found for GLES 3.0 or 2.0".to_string(),
            ).into())
        }

        pub fn make_current(&self) -> Result<()> {
            let ok = unsafe { eglMakeCurrent(self.display, self.surface, self.surface, self.context) };
            if ok != EGL_TRUE {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglMakeCurrent failed: error {}", unsafe { eglGetError() }),
                ).into());
            }
            Ok(())
        }

        pub fn swap_buffers(&self) -> Result<()> {
            let ok = unsafe { eglSwapBuffers(self.display, self.surface) };
            if ok != EGL_TRUE {
                return Err(super::RenderError::GlContextFailed(
                    format!("eglSwapBuffers failed: error {}", unsafe { eglGetError() }),
                ).into());
            }
            Ok(())
        }

        pub fn release(&mut self) {
            unsafe {
                eglMakeCurrent(self.display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                if self.surface != EGL_NO_SURFACE {
                    eglDestroySurface(self.display, self.surface);
                    self.surface = EGL_NO_SURFACE;
                }
                if self.context != EGL_NO_CONTEXT {
                    eglDestroyContext(self.display, self.context);
                    self.context = EGL_NO_CONTEXT;
                }
                if self.display != EGL_NO_DISPLAY {
                    eglTerminate(self.display);
                    self.display = EGL_NO_DISPLAY;
                }
            }
            self.native_window = std::ptr::null_mut();
            tracing::info!("EGL context released");
        }

        pub fn gles_version(&self) -> i32 {
            self.gles_version
        }
    }

    impl Drop for EglContext {
        fn drop(&mut self) {
            self.release();
        }
    }
}

pub struct RenderBridge {
    translator: Option<Library>,
    renderer_type: RendererType,
    #[cfg(target_os = "android")]
    egl_context: Option<egl::EglContext>,
}

unsafe impl Send for RenderBridge {}

impl RenderBridge {
    pub fn new() -> Self {
        Self {
            translator: None,
            renderer_type: RendererType::Gl4Es,
            #[cfg(target_os = "android")]
            egl_context: None,
        }
    }

    pub fn load_translator(&mut self, renderer: RendererType) -> Result<()> {
        let candidates = match renderer {
            RendererType::Gl4Es => vec!["libgl4es_114.so", "libgl4es_115.so"],
            RendererType::Zink => vec!["libOSMesa.so", "libzink.so"],
            RendererType::VirglRenderer => vec!["libvirglrenderer.so"],
        };

        for lib_name in &candidates {
            match unsafe { Library::new(lib_name) } {
                Ok(lib) => {
                    self.translator = Some(lib);
                    self.renderer_type = renderer;
                    tracing::info!("Loaded GL translator: {}", lib_name);
                    return Ok(());
                }
                Err(e) => {
                    tracing::warn!("Failed to load {}: {}", lib_name, e);
                }
            }
        }

        if renderer != RendererType::Gl4Es {
            tracing::warn!("Falling back to GL4ES");
            return self.load_translator(RendererType::Gl4Es);
        }

        Err(RenderError::TranslatorLoadFailed(format!(
            "No translator available (tried: {:?})",
            candidates
        )).into())
    }

    #[cfg(target_os = "android")]
    pub fn setup_surface(
        &mut self,
        native_window: *mut std::ffi::c_void,
        renderer: RendererType,
    ) -> Result<()> {
        let ctx = egl::EglContext::new(native_window, true)?;
        self.egl_context = Some(ctx);
        self.load_translator(renderer)?;
        tracing::info!("Render surface setup complete");
        Ok(())
    }

    #[cfg(target_os = "android")]
    pub fn swap_buffers(&self) -> Result<()> {
        if let Some(ref ctx) = self.egl_context {
            ctx.swap_buffers()
        } else {
            Err(RenderError::GlContextFailed("no EGL context".to_string()).into())
        }
    }

    pub fn renderer_type(&self) -> RendererType {
        self.renderer_type
    }

    pub fn release(&mut self) {
        #[cfg(target_os = "android")]
        {
            if let Some(mut ctx) = self.egl_context.take() {
                ctx.release();
            }
        }
        if let Some(_lib) = self.translator.take() {
            tracing::info!("Released GL translator");
        }
    }
}

static RENDER_BRIDGE: OnceLock<Mutex<RenderBridge>> = OnceLock::new();

pub fn init() {
    RENDER_BRIDGE.get_or_init(|| Mutex::new(RenderBridge::new()));
}

#[cfg(target_os = "android")]
pub fn setup_surface(native_window: *mut std::ffi::c_void) -> Result<()> {
    init();
    let mut bridge = crate::util::cell_lock(&RENDER_BRIDGE).unwrap();
    bridge.setup_surface(native_window, RendererType::Gl4Es)
}

#[cfg(target_os = "android")]
pub fn swap_buffers() -> Result<()> {
    if let Some(bridge) = RENDER_BRIDGE.get() {
        let bridge = crate::util::lock_recover(bridge);
        bridge.swap_buffers()
    } else {
        Err(RenderError::GlContextFailed("render bridge not initialized".to_string()).into())
    }
}

pub fn release() {
    if let Some(bridge) = RENDER_BRIDGE.get() {
        let mut bridge = crate::util::lock_recover(bridge);
        bridge.release();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_render_bridge_new_and_release() {
        let bridge = RenderBridge::new();
        assert_eq!(bridge.renderer_type(), RendererType::Gl4Es);
        assert!(bridge.translator.is_none());
    }

    #[test]
    fn test_render_bridge_release_clears_translator() {
        let mut bridge = RenderBridge::new();
        bridge.release();
        assert!(bridge.translator.is_none());
    }

    #[test]
    fn test_render_error_display() {
        let err = RenderError::GlContextFailed("test error".to_string());
        assert!(err.to_string().contains("GL_CONTEXT_FAILED"));
        assert!(err.to_string().contains("test error"));

        let err = RenderError::TranslatorLoadFailed("no lib".to_string());
        assert!(err.to_string().contains("TRANSLATOR_LOAD_FAILED"));
        assert!(err.to_string().contains("no lib"));
    }

    #[test]
    fn test_renderer_type_enum() {
        assert_eq!(RendererType::Gl4Es, RendererType::Gl4Es);
        assert_ne!(RendererType::Gl4Es, RendererType::Zink);
    }


    #[test]
    fn test_render_bridge_release_no_crash() {
        let mut bridge = RenderBridge::new();
        bridge.release();
        bridge.release();
    }
}
