package net.kdt.pojavlaunch;

import static com.zhayi.qookix.GameActivity.touchCharInput;
import static net.kdt.pojavlaunch.utils.MCOptionUtils.getMcScale;
import static org.lwjgl.glfw.CallbackBridge.sendMouseButton;
import static org.lwjgl.glfw.CallbackBridge.windowHeight;
import static org.lwjgl.glfw.CallbackBridge.windowWidth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import net.kdt.pojavlaunch.customcontrols.ControlLayout;
import net.kdt.pojavlaunch.customcontrols.gamepad.Gamepad;
import net.kdt.pojavlaunch.customcontrols.gamepad.GamepadHandler;
import net.kdt.pojavlaunch.customcontrols.gamepad.GamepadInputDispatcher;
import net.kdt.pojavlaunch.customcontrols.gamepad.direct.DirectGamepadEnableHandler;
import net.kdt.pojavlaunch.customcontrols.mouse.AbstractTouchpad;
import net.kdt.pojavlaunch.customcontrols.mouse.AndroidPointerCapture;
import net.kdt.pojavlaunch.customcontrols.mouse.InGUIEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.InGameEventProcessor;
import net.kdt.pojavlaunch.customcontrols.mouse.TouchEventProcessor;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.MCOptionUtils;

import org.libsdl.app.SDL;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;
import org.lwjgl.glfw.CallbackBridge;

import java.io.File;

/**
 * Class dealing with showing minecraft surface and taking inputs to dispatch them to minecraft
 *
 * <p><b>实体手柄</b>：手柄的第一个按键/摇杆事件会按需创建 {@link Gamepad}，
 * 之后手柄输入被翻译成键鼠喂给游戏（抓取光标时用游戏内映射表，回菜单换成菜单映射表）。
 * 上游那半靠 JitPack 上的 {@code fr.spse.gamepad_remapper} 解析原始事件，这里换成了
 * 自己写的 {@link GamepadInputDispatcher}，不再引外部依赖。
 * 虚拟控件（含 {@code ControlJoystick} 摇杆）不受影响，两条输入路径互不干扰。
 */
public class MinecraftGLSurface extends View implements GrabListener, DirectGamepadEnableHandler {
    /* Sensitivity, adjusted according to screen size */
    private final double mSensitivityFactor = (1.4 * (1080f/ Tools.getDisplayMetrics((Activity) getContext()).heightPixels));

    /* Surface ready listener, used by the activity to launch minecraft */
    SurfaceReadyListener mSurfaceReadyListener = null;
    final Object mSurfaceReadyListenerLock = new Object();
    /* View holding the surface, either a SurfaceView or a TextureView */
    View mSurface;
    /**
     * SDL（MC 26.3+ 的窗口层）需要的真实 Android Surface。
     *
     * 用 static 是为了让 [pushSurfaceToSDL] 能在「SDL 初始化晚于 Surface 创建」时
     * 由 [org.lwjgl.glfw.CallbackBridge.notifyLauncher] 回调过来把 Surface 交出去 ——
     * 否则 SDL 拿不到 surface，会在自己内部空指针崩（实测 SIGSEGV in libSDL3.so）。
     */
    private static Surface mNativeSurface;
    /**
     * SDL 支持是否已启用。
     *
     * 判定权不在启动器：游戏侧的 LWJGL SDL 绑定在 `SDL_Init` 时会通过
     * `CallbackBridge.nativeNotifyLauncher` 回调到 dalvik 侧，由
     * [CallbackBridge.notifyLauncher] 把这里置 true（SDL3 的 Java 胶水同时初始化）。
     * 老版本（GLFW 一系）永远不会置位，所有 SDL 分支都走不到。
     */
    public static boolean sdlEnabled = false;
    /** SDLActivity 提供的手柄/鼠标事件监听，手柄事件转发用。 */
    private static View.OnGenericMotionListener motionListener = (v, event) -> false;
    private final InGameEventProcessor mIngameProcessor = new InGameEventProcessor(mSensitivityFactor);
    private final InGUIEventProcessor mInGUIProcessor = new InGUIEventProcessor();
    private TouchEventProcessor mCurrentTouchProcessor = mInGUIProcessor;
    /** 输入诊断日志限流计数。 */
    private int sTouchLogTick;
    private AndroidPointerCapture mPointerCapture;
    private boolean mLastGrabState = false;
    /** 实体手柄处理器：第一个手柄事件到来时按需创建 */
    private GamepadHandler mGamepadHandler;
    /** 手柄事件分发器（每个手柄设备一份，用它判断右摇杆报在哪些轴上） */
    private GamepadInputDispatcher mGamepadDispatcher;

    public MinecraftGLSurface(Context context) {
        this(context, null);
    }

    public MinecraftGLSurface(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        setFocusable(true);
        // 游戏侧要求手柄直连（GLFW 手柄缓冲）时回调到这里
        CallbackBridge.setDirectGamepadEnableHandler(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void setUpPointerCapture(AbstractTouchpad touchpad) {
        if(mPointerCapture != null) mPointerCapture.detach();
        mPointerCapture = new AndroidPointerCapture(touchpad, this);
    }

    /** Initialize the view and all its settings
     * @param isAlreadyRunning set to true to tell the view that the game is already running
     *                         (only updates the window without calling the start listener)
     * @param touchpad the optional cursor-emulating touchpad, used for touch event processing
     *                 when the cursor is not grabbed
     */
    /**
     * 初始化 SDL 的 Java 胶水层（MC 26.3+ 用）。
     *
     * 必须在**主线程**调用：SDL 的 Java 侧会创建 Handler，在非 looper 线程上会崩。
     * 传入的 `layout` 是父容器 —— SDLActivity 需要一个 ViewGroup 来挂 SDLSurface。
     */
    private static void setupSDL(Context ctx, Surface nativeSurface, ViewGroup layout) {
        // 先把 SDL3 载进 **当前（dalvik）VM**：SDL 的 JNI_OnLoad 在这里跑过才会
        // 缓存 JavaVM，之后游戏 JVM 里 LWJGL 再 dlopen 同一份时不会重复初始化。
        // 顺序很重要 —— 若让游戏侧先加载，SDL 后面会因为拿不到 JavaVM 空指针崩。
        try {
            System.loadLibrary("SDL3");
        } catch (Throwable t) {
            Log.w("MGLSurface", "提前加载 libSDL3.so 失败（稍后游戏侧还会再试）", t);
        }
        SDLSurface surface = new SDLSurface(ctx);
        motionListener = SDLActivity.getMotionListener();
        org.libsdl.app.SDL.initialize();
        SDL.setContext((Activity) ctx);
        SDLActivity.externalInitialize(surface, layout, nativeSurface);
        Log.i("MGLSurface", "SDL 胶水已初始化（surface=" + nativeSurface + "）");
    }

    /**
     * 把当前已经存在的 Surface 交给 SDL。
     *
     * 触发时机：游戏侧的 SDL 初始化（`SDL_Init`）通常**晚于** Surface 创建，
     * 而 Surface 回调里那句 `if (sdlEnabled)` 在当时还是 false，于是 SDL 永远
     * 拿不到 surface。SDL 初始化一建立（CallbackBridge.notifyLauncher）就由这里补交。
     */
    public static void pushSurfaceToSDL() {
        if (mNativeSurface == null || !mNativeSurface.isValid()) {
            Log.w("MGLSurface", "SDL 启用时 Surface 还不可用，等 surfaceCreated 回调再补");
            return;
        }
        SDLSurface.setNativeSurface(mNativeSurface);
        if (SDLActivity.getSDLSurface() != null) {
            SDLActivity.getSDLSurface().surfaceChanged(null, 0,
                    Tools.currentDisplayMetrics.widthPixels,
                    Tools.currentDisplayMetrics.heightPixels);
        }
        Log.i("MGLSurface", "已把 Surface 交给 SDL：" + mNativeSurface);
    }

    // ------------------------------------------------------------ 渲染自愈看门狗

    /**
     * 治「切出去再回来偶发全黑」（SDL 仍在往已死的渲染目标上画，且没人再叫它重建）。
     *
     * 判据不靠猜：原生侧每出一帧就把快照写进 `files/cache/perf.txt`
     * （{@code com.zhayi.qookix.PerfOverlayView} 也读它，无更新超 2 秒即视为过期）。
     * 于是判黑就两条同时成立：**本次游戏见过新鲜快照**（确实在出过帧）
     * 且**现在快照过期**（1.2 秒没有新帧）。
     *
     * 命中后不猜原因，直接把「回前台重绑链」重跑一遍：
     * `setupBridgeWindow`（原生侧据此 eglMakeCurrent 换到新 surface）
     * + `setNativeSurface`（SDL 拿到新 ANativeWindow）
     * + 连报两次尺寸（逼 SDL 重建 EGLSurface，尺寸没变它不重建）。
     * 最多连续重试 {@link #MAX_KICKS} 次，成功出帧后计数自动清零。
     */
    private static final long PERF_FRESH_MS = 2000L;   // 与 PerfOverlayView 的过期口径一致
    private static final int MAX_KICKS = 3;
    private static final long WATCHDOG_INTERVAL_MS = 1000L;
    private static Handler sWatchdogHandler;
    private static File sPerfFile;
    private static boolean sSawFreshFrame;
    private static int sKickCount;
    private static int sKickSeq;

    /** 游戏回前台时启动；见 {@link com.zhayi.qookix.GameActivity#onResume()}。 */
    public static void startRenderWatchdog(Context context) {
        // 注意：这里**不能**判 sdlEnabled 提前 return —— 回前台时它多半还是 false
        // （SDL 支持是游戏侧 SDL_Init 回调 notifyLauncher 才置位的）。踢不踢由 tick 里判。
        sPerfFile = new File(context.getFilesDir(), "cache/perf.txt");
        stopRenderWatchdog();
        sSawFreshFrame = false;
        sKickCount = 0;
        sWatchdogHandler = new Handler(Looper.getMainLooper());
        sWatchdogHandler.postDelayed(sWatchdogTick, WATCHDOG_INTERVAL_MS);
        Log.i("MGLSurface", "渲染自愈看门狗已启动（perf=" + sPerfFile + "）");
    }

    /** 游戏退到后台时停止（见 {@link com.zhayi.qookix.GameActivity#onPause()}）。 */
    public static void stopRenderWatchdog() {
        if (sWatchdogHandler != null) {
            sWatchdogHandler.removeCallbacksAndMessages(null);
            sWatchdogHandler = null;
        }
    }

    private static final Runnable sWatchdogTick = new Runnable() {
        @Override
        public void run() {
            final Handler handler = sWatchdogHandler;
            if (handler == null) return;
            // 只有"快照文件确实存在"时才有可靠信号 —— 26.3 走 SDL 时原生侧没有换帧计数点
            // （见 jni/perf_counters.c 注释），文件可能压根不存在；这种情况什么都不做，
            // 免得健康时瞎重绑。26.3 的兜底走"Surface 重建后重试"那条路（scheduleRebindRetries）。
            final boolean known = sPerfFile != null && sPerfFile.isFile();
            long age = known ? System.currentTimeMillis() - sPerfFile.lastModified() : Long.MAX_VALUE;
            if (!known) {
                // 无信号，保持沉默
            } else if (age < PERF_FRESH_MS) {
                sSawFreshFrame = true;   // 在出帧，健康
                sKickCount = 0;
            } else if (sSawFreshFrame && sdlEnabled) {
                if (sKickCount < MAX_KICKS) {
                    sKickCount++;
                    Log.w("MGLSurface", "疑似渲染卡黑（perf 快照 " + age + "ms 未更新），第 "
                            + sKickCount + "/" + MAX_KICKS + " 次重绑 Surface");
                    rebindSurfaceToSDL();
                } else if (sKickCount == MAX_KICKS) {
                    sKickCount++;   // 只报一次，别刷屏
                    Log.w("MGLSurface", "连续重绑 " + MAX_KICKS + " 次仍无新帧，等下次切前台再救");
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    /**
     * 重绑链本体（看门狗与切前台共用）。顺序有讲究：
     * 先让原生侧换 surface（eglMakeCurrent），再把 Surface 交给 SDL，最后逼它重建。
     */
    private static void rebindSurfaceToSDL() {
        final Surface surface = mNativeSurface;
        if (surface == null || !surface.isValid()) {
            Log.w("MGLSurface", "重绑跳过：Surface 不可用（" + surface + "）");
            return;
        }
        sKickSeq++;
        try {
            JREUtils.setupBridgeWindow(surface);
            if (SDLActivity.getSDLSurface() != null) {
                SDLSurface.setNativeSurface(surface);
                int w = Tools.currentDisplayMetrics.widthPixels;
                int h = Tools.currentDisplayMetrics.heightPixels;
                SDLActivity.getSDLSurface().surfaceChanged(null, 0, w - 2, h - 2);
                SDLActivity.getSDLSurface().surfaceChanged(null, 0, w, h);
            }
            Log.i("MGLSurface", "第 " + sKickSeq + " 次重绑完成（surface=" + surface + "）");
        } catch (Throwable t) {
            Log.w("MGLSurface", "重绑失败", t);
        }
    }

    /**
     * Surface 重建（切后台 / 锁屏 / 浮窗回来）之后的"再补两脚"。
     *
     * 现有实现在重建回调里只重绑**一次** ✗，而 SDL 重建 EGLSurface 是有竞态的：
     * 回调那一刻游戏侧的渲染桥/尺寸可能还没就绪 → 那次重绑落空 → 之后没人再叫它 →
     * 一直黑（用户报的正是这个：切出去再回来偶发全黑、且不会自己好）。
     * 这里在 0.8s / 2.5s 再各补一次。成本极低 —— 实测连踢 3 次也不会把画面搞坏。
     */
    private static void scheduleRebindRetries() {
        if (!sdlEnabled) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(MinecraftGLSurface::rebindSurfaceToSDL, 800L);
        handler.postDelayed(MinecraftGLSurface::rebindSurfaceToSDL, 2500L);
    }

    public void start(boolean isAlreadyRunning, AbstractTouchpad touchpad){
        if(Tools.isAndroid8OrHigher()) setUpPointerCapture(touchpad);
        mInGUIProcessor.setAbstractTouchpad(touchpad);
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView surfaceView = new SurfaceView(getContext());
            mSurface = surfaceView;
            mNativeSurface = surfaceView.getHolder().getSurface();
            // SDL 侧提前初始化：它拿到的是「这块 Surface + 父布局」，
            // 之后 surface 变化只需通知它（见下面的回调）。
            setupSDL(getContext(), mNativeSurface, (ViewGroup) getParent());

            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void surfaceCreated(@NonNull SurfaceHolder holder) {
                    // 启动那一刻拿到的 Surface 还是占位对象（nativeObject=0），
                    // 这里用 holder 里这份**有效**的覆盖掉，否则 SDL/EGL 拿到的是空 Surface。
                    mNativeSurface = holder.getSurface();
                    if(isCalled) {
                        JREUtils.setupBridgeWindow(mNativeSurface);
                        if (sdlEnabled) SDLSurface.setNativeSurface(mNativeSurface);
                        // 26.3 黑屏修复：SDL 只有在「尺寸变化」时才会重建 EGLSurface。
                        // 解锁后尺寸没变 → SDL 继续用已死的旧 EGL surface → 全黑。
                        // 先报一个小一档的尺寸再报真实尺寸，逼 SDL 走重建。
                        if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
                            int w = Tools.currentDisplayMetrics.widthPixels;
                            int h = Tools.currentDisplayMetrics.heightPixels;
                            SDLActivity.getSDLSurface().surfaceChanged(null, 0, w - 2, h - 2);
                            SDLActivity.getSDLSurface().surfaceChanged(null, 0, w, h);
                        }
                        refreshSize(true);
                        // 这一次重绑有竞态（游戏侧渲染桥可能还没就绪）→ 0.8s / 2.5s 再各补一次
                        scheduleRebindRetries();
                        return;
                    }
                    isCalled = true;

                    realStart(mNativeSurface);
                }

                @Override
                public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                    // 给 SDL 的尺寸必须是**真实屏幕像素**，不能用缩放后的游戏分辨率 ——
                    // 否则 SDL 会渲染到离屏（画面全黑）。
                    if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
                        SDLActivity.getSDLSurface().surfaceChanged(holder, format,
                                Tools.currentDisplayMetrics.widthPixels,
                                Tools.currentDisplayMetrics.heightPixels);
                    }
                    refreshSize();
                }

                @Override
                public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                    // SurfaceView 的 Surface 会频繁销毁重建（切后台、浮窗）。
                    // 不通知 SDL 的话它会继续持有已释放的 ANativeWindow → 崩溃。
                    if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
                        SDLActivity.getSDLSurface().surfaceDestroyed(holder);
                    }
                }
            });

            ((ViewGroup)getParent()).addView(surfaceView);
        }else{
            TextureView textureView = new TextureView(getContext());
            textureView.setOpaque(true);
            textureView.setAlpha(1.0f);
            mSurface = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private boolean isCalled = isAlreadyRunning;
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    mNativeSurface = new Surface(surface);
                    if(isCalled) {
                        // 重建（锁屏/切后台回来）：SDL 胶水已经初始化过，**绝不能**再跑
                        // setupSDL —— 那会再建一个 SDLSurface 塞进布局、重置 SDL 状态，
                        // 游戏侧还持着旧的那套 → 画面全黑。这里只需把**新的 Surface**
                        // 交给 SDL：setNativeSurface 内部会走 surfaceCreated，
                        // 再补一个 surfaceChanged 让 SDL 用新 ANativeWindow 重建渲染目标
                        // （尺寸没变时 onSurfaceTextureSizeChanged 不会回调，必须主动补）。
                        JREUtils.setupBridgeWindow(mNativeSurface);
                        if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
                            SDLSurface.setNativeSurface(mNativeSurface);
                            SDLActivity.getSDLSurface().surfaceChanged(null, 0,
                                    Tools.currentDisplayMetrics.widthPixels,
                                    Tools.currentDisplayMetrics.heightPixels);
                        }
                        refreshSize(true);
                        return;
                    }
                    setupSDL(getContext(), mNativeSurface, (ViewGroup) getParent());
                    isCalled = true;

                    realStart(mNativeSurface);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                    if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
                        SDLActivity.getSDLSurface().surfaceChanged(null, 0,
                                Tools.currentDisplayMetrics.widthPixels,
                                Tools.currentDisplayMetrics.heightPixels);
                    }
                    refreshSize();
                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
                        SDLActivity.getSDLSurface().surfaceDestroyed(null);
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });

            ((ViewGroup)getParent()).addView(textureView);
        }


    }

    /**
     * The touch event for both grabbed an non-grabbed mouse state on the touch screen
     * Does not cover the virtual mouse touchpad
     */
    @Override
    @SuppressWarnings("accessibility")
    public boolean onTouchEvent(MotionEvent e) {
        // Kinda need to send this back to the layout
        if(((ControlLayout)getParent()).getModifiable()) return false;
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE)
            org.lwjgl.glfw.CallbackBridge.inputDebugLogPub("GLSurface.onTouchEvent action=" + e.getActionMasked()
                    + " grabbing=" + CallbackBridge.isGrabbing()
                    + " processor=" + (mCurrentTouchProcessor == mIngameProcessor ? "INGAME" : "INGUI"));

        // Looking for a mouse to handle, won't have an effect if no mouse exists.
        for (int i = 0; i < e.getPointerCount(); i++) {
            int toolType = e.getToolType(i);
            if(toolType == MotionEvent.TOOL_TYPE_MOUSE) {
                if(Tools.isAndroid8OrHigher() &&
                        mPointerCapture != null) {
                    mPointerCapture.handleAutomaticCapture();
                    return true;
                }
            }else if(toolType != MotionEvent.TOOL_TYPE_STYLUS) continue;

            // Mouse found
            if(CallbackBridge.isGrabbing()) return false;
            CallbackBridge.sendCursorPos(   e.getX(i) * LauncherPreferences.PREF_SCALE_FACTOR, e.getY(i) * LauncherPreferences.PREF_SCALE_FACTOR);
            return true; //mouse event handled successfully
        }
        if (mIngameProcessor == null || mInGUIProcessor == null) return true;
        return mCurrentTouchProcessor.processTouchEvent(e);
    }

    /**
     * The event for mouse/joystick movements
     */
    @SuppressLint("NewApi")
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // 手柄的摇杆 / 扳机 / 十字键轴：先于鼠标判定，手柄事件里没有鼠标指针
        if (Gamepad.isGamepadEvent(event)) {
            if (mGamepadHandler == null) createGamepad(this, event.getDevice());
            mGamepadDispatcher.handleMotionEvent(event, mGamepadHandler);
            return true;
        }

        int mouseCursorIndex = -1;

        for(int i = 0; i < event.getPointerCount(); i++) {
            if(event.getToolType(i) != MotionEvent.TOOL_TYPE_MOUSE && event.getToolType(i) != MotionEvent.TOOL_TYPE_STYLUS ) continue;
            // Mouse found
            mouseCursorIndex = i;
            break;
        }
        if(mouseCursorIndex == -1) return false; // we cant consoom that, theres no mice!

        // Make sure we grabbed the mouse if necessary
        updateGrabState(CallbackBridge.isGrabbing());

        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_MOVE:
                CallbackBridge.mouseX = (event.getX(mouseCursorIndex) * LauncherPreferences.PREF_SCALE_FACTOR);
                CallbackBridge.mouseY = (event.getY(mouseCursorIndex) * LauncherPreferences.PREF_SCALE_FACTOR);
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
                return true;
            case MotionEvent.ACTION_SCROLL:
                CallbackBridge.sendScroll(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL));
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
                return sendMouseButtonUnconverted(event.getActionButton(),true);
            case MotionEvent.ACTION_BUTTON_RELEASE:
                return sendMouseButtonUnconverted(event.getActionButton(),false);
            default:
                return false;
        }
    }

    /** The event for keyboard/ gamepad button inputs */
    public boolean processKeyEvent(KeyEvent event) {
        //Log.i("KeyEvent", event.toString());

        //Filtering useless events by order of probability
        int eventKeycode = event.getKeyCode();
        if(eventKeycode == KeyEvent.KEYCODE_UNKNOWN) return true;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_DOWN) return false;
        if(eventKeycode == KeyEvent.KEYCODE_VOLUME_UP) return false;
        if(event.getRepeatCount() != 0) return true;
        int action = event.getAction();
        if(action == KeyEvent.ACTION_MULTIPLE) return true;
        // Ignore the cancelled up events. They occur when the user switches layouts.
        // In accordance with https://developer.android.com/reference/android/view/KeyEvent#FLAG_CANCELED
        if(action == KeyEvent.ACTION_UP &&
                (event.getFlags() & KeyEvent.FLAG_CANCELED) != 0) return true;

        //Sometimes, key events comes from SOME keys of the software keyboard
        //Even weirder, is is unknown why a key or another is selected to trigger a keyEvent
        if((event.getFlags() & KeyEvent.FLAG_SOFT_KEYBOARD) == KeyEvent.FLAG_SOFT_KEYBOARD){
            if(eventKeycode == KeyEvent.KEYCODE_ENTER) return true; //We already listen to it.
            touchCharInput.dispatchKeyEvent(event);
            return true;
        }

        //Sometimes, key events may come from the mouse
        if(event.getDevice() != null
                && ( (event.getSource() & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                ||   (event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)  ){

            if(eventKeycode == KeyEvent.KEYCODE_BACK){
                sendMouseButton(LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }

        // 实体手柄按键：必须在键盘映射之前拦下（KEYCODE_BUTTON_* 不该走键盘表）
        if (Gamepad.isGamepadEvent(event)) {
            if (mGamepadHandler == null) createGamepad(this, event.getDevice());
            mGamepadDispatcher.handleKeyEvent(event, mGamepadHandler);
            return true;
        }

        int index = EfficientAndroidLWJGLKeycode.getIndexByKey(eventKeycode);
        if(EfficientAndroidLWJGLKeycode.containsIndex(index)) {
            EfficientAndroidLWJGLKeycode.execKey(event, index);
            return true;
        }

        // Some events will be generated an infinite number of times when no consumed
        return (event.getFlags() & KeyEvent.FLAG_FALLBACK) == KeyEvent.FLAG_FALLBACK;
    }

    /** 手柄（准确说是它的第一个事件）到来时才创建；direct 模式切换后会被重建。 */
    private void createGamepad(View contextView, InputDevice inputDevice) {
        // 打一行日志：用户反馈「手柄没反应」时，先看这里有没有接上
        Log.i("MGLSurface", "手柄已接入：" + (inputDevice == null ? "(未知设备)" : inputDevice.getName()));
        mGamepadDispatcher = new GamepadInputDispatcher(inputDevice);
        mGamepadHandler = new Gamepad(contextView, inputDevice, true);
    }

    /**
     * 游戏侧要求「手柄直连」（把事件塞进 GLFW 的手柄缓冲，让游戏当成真手柄读）时回调。
     *
     * 我们没实现直连写入，所以这里只把现有手柄摘掉并置空 —— 下一个手柄事件会重建一个
     * 继续走键鼠模拟。比什么都不做要好：至少手柄还能操作游戏。
     */
    @Override
    public void onDirectGamepadEnabled() {
        post(() -> {
            if (mGamepadHandler instanceof Gamepad) {
                ((Gamepad) mGamepadHandler).removeSelf();
            }
            mGamepadHandler = null;
            mGamepadDispatcher = null;
        });
    }

    /** Convert the mouse button, then send it
     * @return Whether the event was processed
     */
    public static boolean sendMouseButtonUnconverted(int button, boolean status) {
        int glfwButton = -256;
        switch (button) {
            case MotionEvent.BUTTON_PRIMARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT;
                break;
            case MotionEvent.BUTTON_TERTIARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_MIDDLE;
                break;
            case MotionEvent.BUTTON_SECONDARY:
                glfwButton = LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT;
                break;
        }
        if(glfwButton == -256) return false;
        sendMouseButton(glfwButton, status);
        return true;
    }

    /** Called when the size need to be set at any point during the surface lifecycle **/
    public void refreshSize(){
        refreshSize(false);
    }

    /** Same as refreshSize, but allows you to force an immediate size update **/
    public void refreshSize(boolean immediate) {
        if(isInLayout() && !immediate) {
            post(this::refreshSize);
            return;
        }
        // Use the width and height of the View instead of display dimensions to avoid
        // getting squiched/stretched due to inconsistencies between the layout and
        // screen dimensions.
        int newWidth = Tools.getDisplayFriendlyRes(getWidth(), LauncherPreferences.PREF_SCALE_FACTOR);
        int newHeight = Tools.getDisplayFriendlyRes(getHeight(), LauncherPreferences.PREF_SCALE_FACTOR);
        if (newHeight < 1 || newWidth < 1) {
            Log.e("MGLSurface", String.format("Impossible resolution : %dx%d", newWidth, newHeight));
            return;
        }
        windowWidth = newWidth;
        windowHeight = newHeight;
        if(mSurface == null){
            Log.w("MGLSurface", "Attempt to refresh size on null surface");
            return;
        }
        if(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE){
            SurfaceView view = (SurfaceView) mSurface;
            if(view.getHolder() != null){
                view.getHolder().setFixedSize(windowWidth, windowHeight);
            }
        }else{
            TextureView view = (TextureView)mSurface;
            if(view.getSurfaceTexture() != null){
                view.getSurfaceTexture().setDefaultBufferSize(windowWidth, windowHeight);
            }
        }

        // SDL 侧也要知道新尺寸（它自己维护一套 surface 尺寸）
        if (sdlEnabled && SDLActivity.getSDLSurface() != null) {
            SDLActivity.getSDLSurface().nativeResize(windowWidth, windowHeight);
        }
        CallbackBridge.sendUpdateWindowSize(windowWidth, windowHeight);

    }

    private void realStart(Surface surface){
        // Initial size set. Request immedate refresh, otherwise the initial width and height for the game
        // may be broken/unknown.
        refreshSize(true);

        //Load Minecraft options:
        MCOptionUtils.set("fullscreen", "off");
        MCOptionUtils.set("overrideWidth", String.valueOf(windowWidth));
        MCOptionUtils.set("overrideHeight", String.valueOf(windowHeight));
        MCOptionUtils.save();
        getMcScale();

        JREUtils.setupBridgeWindow(surface);

        new Thread(() -> {
            try {
                // Wait until the listener is attached
                synchronized(mSurfaceReadyListenerLock) {
                    if(mSurfaceReadyListener == null) mSurfaceReadyListenerLock.wait();
                }

                mSurfaceReadyListener.isReady();
            } catch (Throwable e) {
                Tools.showError(getContext(), e, true);
            }
        }, "JVM Main thread").start();
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        post(()->updateGrabState(isGrabbing));
    }

    private TouchEventProcessor pickEventProcessor(boolean isGrabbing) {
        return isGrabbing ? mIngameProcessor : mInGUIProcessor;
    }

    private void updateGrabState(boolean isGrabbing) {
        if(mLastGrabState != isGrabbing) {
            mCurrentTouchProcessor.cancelPendingActions();
            mCurrentTouchProcessor = pickEventProcessor(isGrabbing);
            mLastGrabState = isGrabbing;
        }
    }

    /** A small interface called when the listener is ready for the first time */
    public interface SurfaceReadyListener {
        void isReady();
    }

    public void setSurfaceReadyListener(SurfaceReadyListener listener){
        synchronized (mSurfaceReadyListenerLock) {
            mSurfaceReadyListener = listener;
            mSurfaceReadyListenerLock.notifyAll();
        }
    }
}
