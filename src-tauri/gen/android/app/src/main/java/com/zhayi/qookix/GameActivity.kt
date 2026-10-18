package com.zhayi.qookix

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.kdt.LoggerView
import com.zhayi.qookix.nativebridge.PojavShim
import com.zhayi.qookix.control.MenuEntry
import com.zhayi.qookix.control.QookixMenuAdapter
import com.zhayi.qookix.services.GameService
import com.zhayi.qookix.tauri.TauriBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kdt.pojavlaunch.EfficientAndroidLWJGLKeycode
import net.kdt.pojavlaunch.Logger
import net.kdt.pojavlaunch.LwjglGlfwKeycode
import net.kdt.pojavlaunch.MinecraftGLSurface
import net.kdt.pojavlaunch.Tools
import org.libsdl.app.SDLActivity
import net.kdt.pojavlaunch.customcontrols.ControlButtonMenuListener
import net.kdt.pojavlaunch.customcontrols.ControlData
import net.kdt.pojavlaunch.customcontrols.ControlDrawerData
import net.kdt.pojavlaunch.customcontrols.ControlJoystickData
import net.kdt.pojavlaunch.customcontrols.ControlLayout
import net.kdt.pojavlaunch.customcontrols.CustomControls
import net.kdt.pojavlaunch.customcontrols.EditorExitable
import net.kdt.pojavlaunch.customcontrols.handleview.DrawerPullButton
import net.kdt.pojavlaunch.customcontrols.keyboard.LwjglCharSender
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput
import net.kdt.pojavlaunch.customcontrols.mouse.GyroControl
import net.kdt.pojavlaunch.customcontrols.mouse.HotbarView
import net.kdt.pojavlaunch.customcontrols.mouse.Touchpad
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.prefs.QuickSettingSideDialog
import net.kdt.pojavlaunch.utils.MCOptionUtils
import org.lwjgl.glfw.CallbackBridge
import java.io.File
import java.io.IOException

/**
 * 游戏运行界面 —— **进入游戏之后的逻辑完全照 PojavLauncher 的 `MainActivity`**。
 *
 * 视图层级、事件分发、抽屉菜单、生命周期处理全部沿用 Pojav 的实现（见
 * `res/layout/activity_basemain.xml` 与上游 `net.kdt.pojavlaunch.MainActivity`），
 * 与 QookiX 自研版本的关键差异：
 *
 * | 环节 | 旧的自研实现 | 现在（= Pojav） |
 * |---|---|---|
 * | 渲染载体 | `TextureView`（`GameSurfaceView`） | `MinecraftGLSurface`：内部自建 `SurfaceView`/`TextureView` 并加进 `ControlLayout` |
 * | 触摸 | 手写手势（点按/长按/双指） | `InGUIEventProcessor` / `InGameEventProcessor` + `Touchpad` + `AndroidPointerCapture` |
 * | 虚拟控件 | `VirtualControlsView` + 自研 JSON | `ControlLayout` + `ControlData`（Pojav 控制布局 v8 格式，支持摇杆/抽屉/导入导出） |
 * | 软键盘 | 无 | `TouchCharInput` + `LwjglCharSender` |
 * | 游戏内菜单 | `MaterialAlertDialog` 列表 | 右侧 `DrawerLayout` + `menu_ingame`（含快速设置侧栏与就地编辑器） |
 * | 窗口尺寸 | 只在 Surface 创建时同步一次 | `refreshSize()`：创建时 + 尺寸变化 + 回前台 500ms 后重算 |
 *
 * 本类同时充当 Pojav 里 `MainActivity` 的静态宿主角色：
 * `touchCharInput` / `switchKeyboardState()` / `toggleMouse()` 由控制层的
 * `ControlButton`、`MinecraftGLSurface` 直接调用。
 */
class GameActivity : AppCompatActivity(), ControlButtonMenuListener, EditorExitable {

    /**
     * 游戏启动前把 wry 的 WebView 移出渲染管线。
     *
     * 见 `onCreate` 里的详细注释：游戏与 WebView 同进程，游戏重建图形表面时
     * WebView 持有的 `AHardwareBuffer` 会失效，其 native 层解引用 NULL 直接
     * SIGSEGV（崩溃在 Render thread）。
     *
     * 这里做三件事，缺一不可：
     *   1. `removeView` —— 从视图树摘掉，合成器不再为它分配缓冲
     *   2. `alpha = 0f`  —— 即使被重新挂上也保持不可见
     *   3. `onPause` + `pauseTimers` —— 停掉 Chromium 的渲染/定时器
     *
     * 失败不致命（拿不到 WebView 时直接跳过），但**不能抛异常** ——
     * 它在 onCreate 的关键路径上，崩了游戏就起不来。
     */
    private fun detachTauriWebViewForGame() {
        try {
            // ⚠️ 不能在 `window.decorView` 里找！
            //
            // wry 的 WebView 挂在 **ComposeMainActivity 的窗口**里，而 GameActivity
            // 有自己的窗口 —— 两个 Activity 的视图树彼此独立。
            // 实测（真机）：用 `window.decorView` 找永远是 null，日志打
            //   「启动游戏：未找到 WebView（跳过摘除）」
            // 于是崩溃照旧。
            //
            // 两个 Activity 在**同一个进程**里，所以直接遍历进程内的 Activity
            // 栈去拿启动器那个窗口。
            val wv = findWebViewAcrossActivities() ?: run {
                android.util.Log.i(TAG, "启动游戏：未找到 WebView（跳过摘除）")
                return
            }
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.alpha = 0f
            wv.onPause()
            try { wv.pauseTimers() } catch (e: Throwable) { /* 非致命 */ }
            android.util.Log.i(TAG, "启动游戏：WebView 已移出渲染管线（防 AHardwareBuffer 崩溃）")
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "启动游戏：摘除 WebView 失败（不影响游戏）", t)
        }
    }

    /**
     * 遍历**进程内所有 Activity** 的窗口去找 WebView。
     *
     * 用反射拿 `ActivityThread.mActivities`（同进程内的 Activity 记录），
     * 逐个翻 decorView。反射失败时退回只查自己的窗口（至少不会崩）。
     */
    private fun findWebViewAcrossActivities(): android.webkit.WebView? {
        // 先查自己（理论上没有，但保底）
        findWebView(window?.decorView)?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getMethod("currentActivityThread").invoke(null)
            val f = atClass.getDeclaredField("mActivities")
            f.isAccessible = true
            val map = f.get(currentAT) as? Map<*, *> ?: return null
            for (rec in map.values) {
                if (rec == null) continue
                val actField = rec.javaClass.getDeclaredField("activity")
                actField.isAccessible = true
                val act = actField.get(rec) as? android.app.Activity ?: continue
                findWebView(act.window?.decorView)?.let { return it }
            }
            null
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "跨 Activity 查找 WebView 失败", t)
            null
        }
    }

    /** 在视图树里找第一个 WebView。 */
    private fun findWebView(root: android.view.View?): android.webkit.WebView? {
        if (root !is android.view.ViewGroup) return null
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c is android.webkit.WebView) return c
            findWebView(c)?.let { return it }
        }
        return null
    }

    companion object {
        private const val EXTRA_INSTANCE_ID = "instance_id"
        private const val EXTRA_ACCOUNT_UUID = "account_uuid"

        /**
         * SharedPreferences 名，与 Pojav 一致，偏好文件可直接沿用。
         *
         * **必须与 `MainActivity.PREFS_POJAV` 完全一致**（都是 `launcher_preferences`）。
         * 这里曾经写的是 `pojav_prefs`，与启动器设置页写的文件**不是同一个** ——
         * 后果是设置页里「渲染分辨率缩放 / 按钮大小 / 鼠标速度 / 渲染器…」写进去的值
         * 游戏侧完全读不到（`LauncherPreferences` 从本 Activity 指定的文件加载），
         * 表现为「改了不生效」；反过来游戏内快速设置改的值启动器也看不到（不同步）。
         */
        private const val PREF_NAME = "launcher_preferences"

        /** 历史遗留的偏好文件名，仅在迁移时读取，见 [migrateLegacyPrefs]。 */
        private const val LEGACY_PREF_NAME = "pojav_prefs"

        private const val TAG = "QookiXGame"

        // 内置默认布局的写入与「皮肤版本」标记已经抽到 [`ControlLayoutSeed`] ——
        // 启动器（MainActivity）与编辑器（CustomControlsActivity）也要用它，
        // 不能只在游戏启动时才把 default.json 落盘（否则启动器点「编辑布局」会报文件不存在）。

        /** `R.array.menu_ingame` 八项对应的图标（顺序必须与数组一致）。 */
        private val GAME_MENU_ICONS = intArrayOf(
            R.drawable.ic_qk_power,     // 强制关闭
            R.drawable.ic_qk_log,       // 日志输出
            R.drawable.ic_qk_key,       // 发送自定义键码
            R.drawable.ic_qk_sliders,   // 快速设置
            R.drawable.ic_qk_gamepad,   // 自定义控制布局
            R.drawable.ic_qk_camera,    // 截图
            R.drawable.ic_qk_speed,     // 性能面板
            R.drawable.ic_qk_share,     // 联机（陶瓦）
        )

        /** 性能面板的二级菜单：启用/停用、功能设置、返回。 */
        private val PERF_MENU_ICONS = intArrayOf(
            R.drawable.ic_qk_speed,     // 显示/隐藏
            R.drawable.ic_qk_sliders,   // 功能设置
            R.drawable.ic_qk_close,     // 返回
        )

        /** `R.array.menu_customcontrol` 七项对应的图标（顺序必须与数组一致）。 */
        private val EDITOR_MENU_ICONS = intArrayOf(
            R.drawable.ic_qk_plus,      // 添加按键
            R.drawable.ic_qk_plus,      // 添加组合键
            R.drawable.ic_qk_plus,      // 添加摇杆
            R.drawable.ic_qk_folder,    // 加载
            R.drawable.ic_qk_save,      // 保存
            R.drawable.ic_qk_star,      // 选择默认控制布局
            R.drawable.ic_qk_close,     // 退出编辑器
        )

        /** 控制层持有它（`MinecraftGLSurface.processKeyEvent` 转发软键盘事件用）。 */
        @JvmField
        var touchCharInput: TouchCharInput? = null

        /** 控制层持有它（`MainActivity.toggleMouse` 的等价物）。 */
        @JvmField
        var touchpad: Touchpad? = null

        /** Pojav `MainActivity.switchKeyboardState()` 的等价物。 */
        @JvmStatic
        fun switchKeyboardState() {
            touchCharInput?.switchKeyboardState()
        }

        /** Pojav `MainActivity.toggleMouse(Context)` 的等价物。 */
        @JvmStatic
        fun toggleMouse(ctx: Context) {
            // 游戏抓着鼠标时虚拟鼠标没有意义（Pojav 同样直接 return）
            if (CallbackBridge.isGrabbing()) return
            val showing = touchpad?.switchState() ?: false
            Toast.makeText(
                ctx,
                if (showing) R.string.control_mouseon else R.string.control_mouseoff,
                Toast.LENGTH_SHORT
            ).show()
        }

        fun start(context: Context, instanceId: String, accountUuid: String) {
            markLaunchRequest(context, instanceId)
            val intent = Intent(context, GameActivity::class.java).apply {
                putExtra(EXTRA_INSTANCE_ID, instanceId)
                putExtra(EXTRA_ACCOUNT_UUID, accountUuid)
                // NEW_DOCUMENT + intoRecents：游戏在多任务里占一张**独立卡片**，
                // 启动器自己的卡片仍在 —— 玩家可以随时切回启动器（页面/状态原样保留），
                // 再从多任务切回游戏。之前两张界面挤在同一个任务里，
                // 多任务里只剩游戏一张卡，启动器「消失」了（用户反馈）。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
            }
            context.startActivity(intent)
        }

        // ── 「这次是用户主动点的启动」这张凭条 ────────────────────────────────
        //
        // 为什么需要：`GameActivity` 用 `documentLaunchMode="intoExisting"` + 独立
        // taskAffinity，它的**任务记录不随进程消失**。游戏退出后那张卡片仍留在多任务里，
        // 用户点它 → 系统拿**原始 Intent**（里面的 instanceId 还在）把界面恢复出来 →
        // 界面以为「用户要开这一版」，把游戏又启动了一遍（用户反馈：退出后点一下卡片，
        // 游戏自己又开了一次）。
        //
        // 恢复出来的 Intent 与「用户刚点的启动」在参数上完全一样，只能靠**有没有一张刚写的
        // 凭条**区分。所有正常启动都走 `start()`，所以凭条写在这里；读到即清（只生效一次），
        // 再配一个时间窗，免得残留的旧凭条把很久以后的卡片点击也放行。
        private const val GATE_PREF = "qookix_launch_gate"
        private const val GATE_ID = "instance_id"
        private const val GATE_AT = "requested_at"
        private const val GATE_TTL_MS = 2 * 60 * 1000L

        private fun markLaunchRequest(ctx: Context, instanceId: String) {
            runCatching {
                ctx.getSharedPreferences(GATE_PREF, Context.MODE_PRIVATE).edit()
                    .putString(GATE_ID, instanceId)
                    .putLong(GATE_AT, System.currentTimeMillis())
                    .apply()
            }
        }

        /** 有凭条 = 用户刚点了启动；**读到就清**，所以只有第一次 onCreate 能拿到它。 */
        private fun consumeLaunchRequest(ctx: Context, instanceId: String): Boolean {
            val sp = ctx.getSharedPreferences(GATE_PREF, Context.MODE_PRIVATE)
            val id = sp.getString(GATE_ID, null)
            val at = sp.getLong(GATE_AT, 0L)
            sp.edit().clear().apply()
            return id == instanceId && at > 0L &&
                System.currentTimeMillis() - at < GATE_TTL_MS
        }
    }

    private var instanceId: String = ""
    private var accountUuid: String = ""

    private lateinit var mControlLayout: ControlLayout
    private lateinit var minecraftGLView: MinecraftGLSurface
    private lateinit var loggerView: LoggerView
    private lateinit var drawerLayout: DrawerLayout
    /** 抽屉里真正承载列表的 ListView（id 沿用 Pojav 的 main_navigation_view）。 */
    private lateinit var navDrawer: ListView
    /** 抽屉容器：它才是 DrawerLayout 的直接子 View，openDrawer() 必须传它。 */
    private lateinit var navContainer: LinearLayout
    private lateinit var menuTitle: TextView
    private lateinit var menuSubtitle: TextView
    private lateinit var mDrawerPullButton: DrawerPullButton
    private lateinit var mHotbarView: HotbarView
    private var mGyroControl: GyroControl? = null
    private var mQuickSettingSideDialog: QuickSettingSideDialog? = null
    private var mPerfOverlay: PerfOverlayView? = null
    private var mPerfSettingsDialog: PerfSettingsDialog? = null
    /** 游戏内「联机（陶瓦）」面板，见 [TerracottaPanel]。 */
    private var mTerracottaPanel: TerracottaPanel? = null
    /** 启动阶段（黑屏那几十秒）的日志浮层，见 [StartupOverlay]。 */
    private var startupOverlay: StartupOverlay? = null
    private var perfMenuAdapter: QookixMenuAdapter? = null
    private var perfMenuListener: AdapterView.OnItemClickListener? = null

    private lateinit var gameMenuAdapter: QookixMenuAdapter
    private lateinit var gameActionClickListener: AdapterView.OnItemClickListener
    private var editorMenuAdapter: QookixMenuAdapter? = null
    private var editorMenuListener: AdapterView.OnItemClickListener? = null

    private var isInEditor = false
    private var gameStarted = false

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: ""
        accountUuid = intent.getStringExtra(EXTRA_ACCOUNT_UUID) ?: ""

        // 从多任务里点回来的旧卡片**不是**「用户要开这一版」：JVM 早就没了，照常走下去
        // 就会把游戏再启动一遍（见 companion 里的说明）。这里只认「刚点的启动」那张凭条，
        // 没有凭条且确实没有在跑的游戏 → 收掉这个界面并把启动器带回来。
        val fromUserTap = consumeLaunchRequest(this, instanceId)
        val gameAlive = try {
            TauriBridge.isGameRunning()
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "查询游戏状态失败，按未运行处理", e)
            false
        }
        if (!fromUserTap && !gameAlive) {
            android.util.Log.i(TAG, "从多任务恢复的旧卡片（实例 $instanceId）→ 不再启动游戏，收掉界面")
            startActivity(
                // 指向当前 LAUNCHER
                Intent().apply {
                    setClassName(packageName, "com.zhayi.qookix.compose.ComposeMainActivity")
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                }
            )
            finishAndRemoveTask()
            return
        }

        // 偏好：控制层（ControlData / Touchpad / QuickSettingSideDialog）直接读这些静态字段
        migrateLegacyPrefs()
        LauncherPreferences.DEFAULT_PREF = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        LauncherPreferences.loadPreferences(this)

        // ⚠️⚠️ 游戏启动前**必须**把 wry 的 WebView 彻底移出渲染管线（2026-10-16 修）。
        //
        // ── 崩溃现象（真机 tombstone 实测）─────────────────────────────
        //   pid: …  name: Render thread  >>> com.zhayi.qookix <<<
        //   signal 11 (SIGSEGV), fault addr 0x28, Cause: null pointer dereference
        //   backtrace:
        //     #07 AHardwareBuffer_getNativeHandle+24  /system/lib64/libandroid.so
        //     #08 libpojavexec.so
        //     #01/#02 libwebviewchromium.so
        //     #03/#04 libjvm.so
        //
        // ── 原因 ───────────────────────────────────────────────────────
        // 游戏和 WebView **在同一个进程里**。游戏起来时要重建图形表面
        //（`MinecraftGLSurface` 自建 SurfaceView），而 WebView 的合成器仍持有
        // 旧的 `AHardwareBuffer` —— handle 被回收后 `getNativeHandle` 返回 NULL，
        // WebView 的 native 层解引用它 → SIGSEGV。
        //
        // 表现：点「启动游戏」→ 黑一下 → 退回启动器，Java 侧**没有任何异常**，
        // 只有 tombstone 里能看到真相，极难归因。
        //
        // ── 修法 ───────────────────────────────────────────────────────
        // game 期间把 WebView 从视图树摘掉（`removeView`）+ `alpha=0`，
        // 并让它暂停一切绘制。游戏退出后由启动器重新挂回（见
        // `ComposeMainActivity.attachComposeOverlay` 的重试逻辑）。
        // 同 ComposeMainActivity：GameActivity 也可能成为进程内的**首个** Activity
        //（例如从多任务直接切回游戏），此时必须补上 Pojav 的平台上下文，
        // 否则 nativeLibDir() 返回 null → libpojavexec 加载失败 →
        // pojavWindow 为 NULL → egl_bridge.c 的 ANativeWindow_setBuffersGeometry 崩溃。
        PojavShim.init(this)

        detachTauriWebViewForGame()

        // 沉浸式全屏必须在 setContentView 之前：View 尺寸在这里定下来，
        // 而游戏窗口尺寸随后由 MinecraftGLSurface.refreshSize() 取 View 尺寸算出。
        applyImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 游戏目录（options.txt 读写用）。⚠️ 必须走 DataDirs（2026-10-17 目录改名）：\n        // 原来硬编码 instances/ —— 用户把游戏目录改到外部、或目录改名后会指向不存在的路径。
        val gameDir = DataDirs.instanceDir(filesDir, instanceId)
        Tools.DIR_GAME_NEW = gameDir.absolutePath
        MCOptionUtils.load(gameDir.absolutePath)

        // 尽早拉起前台服务，避免切后台被 lowmemorykiller 杀掉
        GameService.start(this)

        setContentView(R.layout.activity_basemain)
        bindValues()

        // ── 控制层接线（照 Pojav MainActivity.onCreate）──────────────────────
        CallbackBridge.addGrabListener(touchpad)
        CallbackBridge.addGrabListener(minecraftGLView)

        mGyroControl = GyroControl(this)

        // TextureView 上设置透明窗口背景会渲染成一片白，Pojav 同款判断
        if (LauncherPreferences.PREF_USE_ALTERNATE_SURFACE) {
            window.setBackgroundDrawable(null)
        } else {
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            window.setSustainedPerformanceMode(LauncherPreferences.PREF_SUSTAINED_PERFORMANCE)
        }

        // 性能面板的二级菜单（抽屉里点「性能面板」后切换过去）
        perfMenuAdapter = QookixMenuAdapter(this, perfMenuEntries())
        perfMenuListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> {
                    togglePerfPanel()
                    perfMenuAdapter?.submit(perfMenuEntries())
                }
                1 -> openPerfSettings()
                2 -> backToGameMenu()
            }
        }

        // 就地控制编辑器（抽屉里点「自定义控制布局」后切换过去）
        editorMenuAdapter = QookixMenuAdapter(
            this,
            entriesOf(R.array.menu_customcontrol, EDITOR_MENU_ICONS)
        )
        editorMenuListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> mControlLayout.addControlButton(ControlData("New"))
                1 -> mControlLayout.addDrawer(ControlDrawerData())
                2 -> mControlLayout.addJoystickButton(ControlJoystickData())
                3 -> mControlLayout.openLoadDialog()
                4 -> mControlLayout.openSaveDialog(this)
                5 -> mControlLayout.openSetDefaultDialog()
                6 -> mControlLayout.openExitDialog(this)
            }
        }

        // 快捷栏缩放跟随游戏内 GUI Scale 选项
        MCOptionUtils.addMCOptionListener { MCOptionUtils.getMcScale() }
        mControlLayout.setModifiable(false)

        // ── 启动日志浮层 ────────────────────────────────────────────────────
        // 加在 content_frame 上：和 ControlLayout 同级但绘制在其上方，
        // 抽屉是 DrawerLayout 的直接子级，仍然盖得住它。
        // 存在的意义：从点启动到 Mojang 图标出现之间画面全黑，用户分不清
        // 「在加载」还是「卡死了」—— 这个浮层把启动日志实时显示出来。
        try {
            val contentFrame = findViewById<FrameLayout>(R.id.content_frame)
            startupOverlay = StartupOverlay(this).also { overlay ->
                overlay.visibility = View.GONE
                overlay.onDismiss = { startupOverlay = null }
                contentFrame.addView(
                    overlay,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "启动日志浮层创建失败（不影响游戏）", e)
            startupOverlay = null
        }

        // 上次退出时开着性能面板就自动恢复
        if (getSharedPreferences(PerfOverlayView.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PerfOverlayView.KEY_ENABLED, false)
        ) {
            mControlLayout.post { togglePerfPanel() }
        }

        mControlLayout.setMenuListener(this)
        mDrawerPullButton.setOnClickListener { onClickedMenu() }
        // 只允许通过「下拉按钮 / 菜单键」打开抽屉：否则屏幕边缘滑动会误触
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // 返回键兜底：**绝不 finish 这个界面**。
        //
        // Pojav 只在 dispatchKeyEvent 里把返回键映射成 GLFW_KEY_ESCAPE；如果那条路径
        // 因为任何原因没消费掉事件（软键盘 enabled 状态、预测式返回手势、对话框刚关闭…），
        // AppCompatActivity 的默认实现就会 finish 掉本 Activity ——
        // 留下「游戏 JVM 还在跑、界面却没了」的进程，多任务里看不到它，
        // 再点启动又会被判成「已在运行」。这里显式接管，行为与 Pojav 一致（发 Esc）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isInEditor) {
                    // **逐层退出**：颜色选择器 → 属性面板 → 才问「要不要退出编辑器」。
                    //
                    // 原来这里直接 `askToExit()`，于是用户在属性面板里点进「背景颜色 /
                    // 边框颜色」那层颜色选择器后，按返回键看到的是「您确定要离开？」，
                    // 一按就把整个编辑器退掉 —— 主观感受就是「点进去退不出来了」
                    // （2026-10-08 用户反馈）。
                    // 游戏内这条和 `CustomControlsActivity` 那条是**两处独立代码**，
                    // 两处都要逐层关（同一个 `ControlLayout.collapseEditLayers()`）。
                    if (mControlLayout.collapseEditLayers()) return
                    mControlLayout.askToExit(this@GameActivity)
                    return
                }
                CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE.toInt())
            }
        })

        // latestlog.txt 只留给 Logger.appendToLog（未找到调用方，但保留以免上游代码初始化失败）。
        // 注意：**这里不再重定向 fd1/2** —— 那种做法会与 Rust 的 redirect_output 争抢 fd，
        // 结果两边都不完整（游戏内日志面板恒空）。fd 重定向统一由 Rust 负责，
        // 写进 files/logs/launch-<实例>.log，游戏内面板直接跟随该文件。
        try {
            val logFile = File(filesDir, "logs/latestlog.txt")
            logFile.parentFile?.mkdirs()
            if (!logFile.exists() && !logFile.createNewFile()) {
                throw IOException("Failed to create a new log file")
            }
            Logger.begin(logFile.absolutePath)
        } catch (e: Throwable) {
            android.util.Log.w("GameActivity", "日志文件初始化失败，游戏内日志面板将为空", e)
        }
        touchCharInput?.setCharacterSender(LwjglCharSender())

        // 1.13+ 必须开输入队列模式，且要在游戏启动前（Pojav: isInputStackCall = versionInfo.arguments != null）
        try {
            CallbackBridge.nativeSetUseInputStackQueue(true)
        } catch (e: Throwable) {
            android.util.Log.w("GameActivity", "nativeSetUseInputStackQueue 失败", e)
        }

        // ── 游戏内菜单（右侧抽屉）────────────────────────────────────────────
        gameMenuAdapter = QookixMenuAdapter(
            this,
            entriesOf(R.array.menu_ingame, GAME_MENU_ICONS)
        )
        gameActionClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> dialogForceClose()
                1 -> openLogOutput()
                2 -> dialogSendCustomKey()
                3 -> openQuickSettings()
                4 -> openCustomControls()
                5 -> takeScreenshot()
                // post：点击回调之后抽屉会被关掉，二级菜单要等那之后再开
                6 -> mControlLayout.post { openPerfMenu() }
                7 -> openTerracottaPanel()
            }
            drawerLayout.closeDrawers()
        }
        navDrawer.adapter = gameMenuAdapter
        navDrawer.onItemClickListener = gameActionClickListener
        drawerLayout.closeDrawers()

        // ── Surface 就绪 → 启动 JVM ─────────────────────────────────────────
        minecraftGLView.setSurfaceReadyListener {
            // 虚拟鼠标按偏好预开启（Pojav: 启动前 setup virtual mouse）
            if (LauncherPreferences.PREF_VIRTUAL_MOUSE_START) {
                touchpad?.post { touchpad?.switchState() }
            }
            // 只有「这次真的要起一个新的游戏进程」才显示启动浮层；
            // 复用后台还在跑的 JVM 时（alreadyRunning）直接不显示。
            val freshStart = try {
                !TauriBridge.isGameRunning()
            } catch (e: Throwable) {
                android.util.Log.w(TAG, "查询游戏状态失败，按新启动处理", e)
                true
            }
            startupOverlay?.let { overlay ->
                if (freshStart) {
                    overlay.start(File(filesDir, "logs/launch-$instanceId.log"))
                } else {
                    overlay.hideNow()
                }
            }
            startGameOnce()
        }

        // isAlreadyRunning：JVM 可能还在后台跑（用户之前退到启动器但没杀进程），
        // 这时只重绑 Surface，不再启动一次。Pojav 用 GameService.LocalBinder.isActive 判断，
        // QookiX 直接问 Rust 侧的游戏状态。
        val alreadyRunning = TauriBridge.isGameRunning()
        touchpad?.let { minecraftGLView.start(alreadyRunning, it) }
            ?: run {
                android.util.Log.e("GameActivity", "touchpad 未绑定，无法启动渲染面")
                finishAndRemoveTask()
            }
    }

    /** 视图绑定（Pojav `MainActivity.bindValues` 的等价物，id 完全沿用上游布局）。 */
    private fun bindValues() {
        mControlLayout = findViewById(R.id.main_control_layout)
        minecraftGLView = findViewById(R.id.main_game_render_view)
        touchpad = findViewById(R.id.main_touchpad)
        drawerLayout = findViewById(R.id.main_drawer_options)
        navDrawer = findViewById(R.id.main_navigation_view)
        navContainer = findViewById(R.id.main_nav_container)
        menuTitle = findViewById(R.id.qk_menu_title)
        menuSubtitle = findViewById(R.id.qk_menu_subtitle)
        loggerView = findViewById(R.id.mainLoggerView)
        // 游戏内「日志输出」面板跟随 **Rust** 写的启动日志。
        // 不要再改回 `Logger.setLogListener` —— 那套走 fd1/2 自建管道，会被 Rust 的
        // redirect_output 顶掉写端，回调永远不触发（面板恒空）。详见 LoggerView 的类注释。
        loggerView.setLogFile(File(filesDir, "logs/launch-$instanceId.log"))
        touchCharInput = findViewById(R.id.mainTouchCharInput)
        mDrawerPullButton = findViewById(R.id.drawer_button)
        mHotbarView = findViewById(R.id.hotbar_view)
    }

    /**
     * 把 Pojav 的字符串数组与 QookiX 的图标一一配对。
     *
     * 文案仍取上游数组（`menu_ingame` / `menu_customcontrol`，含 zh-rCN 翻译），
     * 这样点击回调的 position 语义与 Pojav 完全一致。
     */
    private fun entriesOf(arrayRes: Int, icons: IntArray): List<MenuEntry> =
        QookixMenuAdapter.entriesOf(this, arrayRes, icons)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 布局完成后再取显示度量，否则刘海/窗口尺寸都是错的
        LauncherPreferences.computeNotchSize(this)
        mControlLayout.post {
            Tools.getDisplayMetrics(this)
            loadControls()
        }
    }

    /**
     * 加载控制布局。默认走 `LauncherPreferences.PREF_DEFAULTCTRL_PATH`
     * （首次运行 / 皮肤版本升级时由 [ControlLayoutSeed.ensure] 写入内置布局）；
     * 失败则回落默认布局，绝不因为一个坏 JSON 就起不来游戏。
     */
    private fun loadControls() {
        ControlLayoutSeed.ensure(this)
        try {
            mControlLayout.loadLayout(LauncherPreferences.PREF_DEFAULTCTRL_PATH)
        } catch (e: IOException) {
            android.util.Log.w("GameActivity", "控制布局加载失败，改用默认布局", e)
            try {
                mControlLayout.loadLayout(Tools.CTRLDEF_FILE)
            } catch (io: Throwable) {
                Tools.showError(this, io)
            }
        } catch (th: Throwable) {
            Tools.showError(this, th)
        }
        // 布局自带「菜单」按钮时就不需要下拉按钮（Pojav 同款）
        mDrawerPullButton.visibility =
            if (mControlLayout.hasMenuButton()) View.GONE else View.VISIBLE
        mControlLayout.toggleControlVisible()
    }

    // ------------------------------------------------------------------ 游戏启动

    private fun startGameOnce() {
        if (gameStarted) return
        gameStarted = true

        if (TauriBridge.isGameRunning()) {
            android.util.Log.i("GameActivity", "JVM 已在运行，仅重新绑定 Surface")
            return
        }

        // Rust 侧启动 JVM 时用这个尺寸当 glfwstub 的游戏窗口尺寸，
        // 与 refreshSize() 写进 CallbackBridge 的值必须一致，否则触摸坐标整体错位。
        TauriBridge.setSurfaceSize(CallbackBridge.windowWidth, CallbackBridge.windowHeight)

        lifecycleScope.launch {
            val exitCode = withContext(Dispatchers.IO) {
                TauriBridge.launchGame(instanceId, accountUuid)
            }
            // 游戏正常结束 → **连任务一起收掉**。只 finish() 的话那张卡片还留在多任务里，
            // 用户点一下就又开一局（实测 bug：退出后点旧卡片，游戏自己重开）。
            if (exitCode != 0) showCrashDialog(exitCode) else finishAndRemoveTask()
        }
    }

    // ------------------------------------------------------------------ 游戏内菜单

    /** 抽屉入口：Pojav `MainActivity.onClickedMenu()`（容器才是 DrawerLayout 的抽屉 View）。 */
    override fun onClickedMenu() {
        drawerLayout.openDrawer(navContainer)
        navContainer.requestLayout()
    }

    /**
     * 游戏内截图。
     *
     * 为什么需要：手机上没有 F2，抽屉里也没有入口 —— 于是 `<实例目录>/screenshots/`
     * 永远是空的，实例详情里的「截图」tab 永远没东西看。
     * 这里等价于按一下 F2：Minecraft 自己的截图逻辑会把图写进 screenshots/。
     *
     * 走 `CallbackBridge.sendKeyPress` 而不是自己去抓帧/写文件，是为了复用游戏内
     * 已经绑定的截图键位（玩家改过键位也照样有效），也不用关心渲染后端是 GL4ES 还是 Vulkan。
     */
    private fun takeScreenshot() {
        try {
            // GLFW_KEY_F2 在 LwjglGlfwKeycode 里是 short，sendKeyPress 收 int
            CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_F2.toInt())
            Toast.makeText(
                this,
                "已触发截图（保存在实例目录的 screenshots 文件夹）",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Throwable) {
            android.util.Log.w("GameActivity", "触发截图失败", e)
            Toast.makeText(this, "截图失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openLogOutput() {
        loggerView.visibility = View.VISIBLE
    }

    /** Pojav `MainActivity.dialogSendCustomKey()`：把按键表列出来直接发。 */
    private fun dialogSendCustomKey() {
        AlertDialog.Builder(this, R.style.QookixAlertDialog)
            .setTitle(R.string.control_customkey)
            .setItems(EfficientAndroidLWJGLKeycode.generateKeyName()) { _, position ->
                EfficientAndroidLWJGLKeycode.execKeyIndex(position)
            }
            .show()
    }

    /** Pojav `MainActivity.openQuickSettings()`：右侧滑出的快速设置面板。 */
    private fun openQuickSettings() {
        if (mQuickSettingSideDialog == null) {
            mQuickSettingSideDialog = object : QuickSettingSideDialog(this, mControlLayout) {
                override fun onResolutionChanged() {
                    minecraftGLView.refreshSize()
                    mHotbarView.onResolutionChanged()
                }

                override fun onGyroStateChanged() {
                    mGyroControl?.updateOrientation()
                    if (LauncherPreferences.PREF_ENABLE_GYRO) mGyroControl?.enable()
                    else mGyroControl?.disable()
                }
            }
        }
        mQuickSettingSideDialog?.appear(true)
    }

    /** 性能小窗：可拖动、可收起，只占自己那一小块。 */
    private fun togglePerfPanel() {
        val overlay = mPerfOverlay ?: PerfOverlayView(this).also { mPerfOverlay = it }
        if (overlay.parent != null) {
            mControlLayout.removeView(overlay)
        } else {
            // 必须显式给 WRAP_CONTENT：默认布局参数会把小窗撑满整个
            // ControlLayout，背景的半透明黑就成了全屏遮罩，触摸也全被它吃掉
            mControlLayout.addView(
                overlay,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            overlay.bringToFront()
            overlay.applyStyle()
        }
        // 记住开关状态，下次进游戏自动恢复
        getSharedPreferences(PerfOverlayView.PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PerfOverlayView.KEY_ENABLED, overlay.parent != null)
            .apply()
    }

    private fun perfMenuEntries(): List<MenuEntry> {
        val showing = mPerfOverlay?.parent != null
        return listOf(
            MenuEntry(
                getString(if (showing) R.string.perf_hide else R.string.perf_show),
                R.drawable.ic_qk_speed
            ),
            MenuEntry(getString(R.string.perf_settings), R.drawable.ic_qk_sliders),
            MenuEntry(getString(R.string.perf_back), R.drawable.ic_qk_close)
        )
    }

    private fun openPerfMenu() {
        perfMenuAdapter?.submit(perfMenuEntries())
        navDrawer.adapter = perfMenuAdapter
        navDrawer.onItemClickListener = perfMenuListener
        menuTitle.setText(R.string.perf_title)
        menuSubtitle.setText(R.string.qk_menu_subtitle_hint)
        mDrawerPullButton.visibility = View.VISIBLE
        drawerLayout.openDrawer(navContainer)
    }

    private fun backToGameMenu() {
        navDrawer.adapter = gameMenuAdapter
        navDrawer.onItemClickListener = gameActionClickListener
        menuTitle.setText(R.string.qk_menu_title_ingame)
        menuSubtitle.setText(R.string.qk_menu_subtitle_hint)
        mDrawerPullButton.visibility =
            if (mControlLayout.hasMenuButton()) View.GONE else View.VISIBLE
    }

    /**
     * 游戏内联机面板：状态 / 房间码 / 加入朋友 / 开始-停止。
     *
     * 玩家在游戏里时启动器在后台，之前只能靠通知栏看房间码、想加入朋友还得退出游戏，
     * 所以把这块搬到游戏内（实现见 [TerracottaPanel]）。
     */
    private fun openTerracottaPanel() {
        if (mTerracottaPanel == null) {
            mTerracottaPanel = TerracottaPanel(this, mControlLayout, filesDir)
        }
        drawerLayout.closeDrawers()
        mControlLayout.post { mTerracottaPanel?.appear(true) }
    }

    private fun openPerfSettings() {
        if (mPerfSettingsDialog == null) {
            mPerfSettingsDialog = PerfSettingsDialog(this, mControlLayout) {
                mPerfOverlay?.applyStyle()
            }
        }
        drawerLayout.closeDrawers()
        mControlLayout.post { mPerfSettingsDialog?.appear(true) }
    }

    /** Pojav `MainActivity.openCustomControls()`：把抽屉换成布局编辑器菜单。 */
    private fun openCustomControls() {
        val adapter = editorMenuAdapter ?: return
        val listener = editorMenuListener ?: return
        mControlLayout.setModifiable(true)
        navDrawer.adapter = adapter
        navDrawer.onItemClickListener = listener
        menuTitle.setText(R.string.qk_menu_title_editor)
        menuSubtitle.setText(R.string.qk_menu_subtitle_editor)
        mDrawerPullButton.visibility = View.VISIBLE
        isInEditor = true
    }

    /** Pojav `MainActivity.exitEditor()`：丢弃编辑内容并重新加载布局。 */
    override fun exitEditor() {
        try {
            mControlLayout.loadLayout(null as CustomControls?)
            mControlLayout.setModifiable(false)
            System.gc()
            // 那份布局可能已经不在了（用户在启动器里删过 / 改过名）→ 退回内置默认，
            // 别在游戏里弹一个「文件不存在」的报错框挡住界面。
            val want = LauncherPreferences.PREF_DEFAULTCTRL_PATH
            mControlLayout.loadLayout(
                if (want != null && File(want).isFile) want else Tools.CTRLDEF_FILE
            )
        } catch (e: Throwable) {
            Tools.showError(this, e)
        }
        navDrawer.adapter = gameMenuAdapter
        navDrawer.onItemClickListener = gameActionClickListener
        menuTitle.setText(R.string.qk_menu_title_ingame)
        menuSubtitle.setText(R.string.qk_menu_subtitle_hint)
        mDrawerPullButton.visibility =
            if (mControlLayout.hasMenuButton()) View.GONE else View.VISIBLE
        isInEditor = false
    }

    /** 「强制关闭游戏」：先让 Rust 优雅结束 JVM（要落存档），再退界面。 */
    private fun dialogForceClose() {
        AlertDialog.Builder(this, R.style.QookixAlertDialog)
            .setMessage(R.string.mcn_exit_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { TauriBridge.killGame() }
                    // 同 startGameOnce：退出就收掉任务卡片，别让它变成「点一下又开一局」
                    finishAndRemoveTask()
                }
            }
            .show()
    }

    // ------------------------------------------------------------------ 按键

    /**
     * 照 Pojav `MainActivity.dispatchKeyEvent`：
     * 编辑模式下返回键 = 询问是否退出编辑；否则交给 [MinecraftGLSurface.processKeyEvent]，
     * 未被消费的返回键映射成 `GLFW_KEY_ESCAPE` 送给游戏（游戏内表现为打开暂停菜单）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isInEditor) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    // **逐层退出**，与上面 `onBackPressedDispatcher` 回调同一套逻辑。
                    //
                    // 这条是**物理返回键**的通路：它会 `return true` 把事件吃掉，
                    // 根本走不到 dispatcher —— 所以两处必须都改，否则「手势返回能逐层退、
                    // 按返回键却直接弹『确定要离开』并退掉整个编辑器」。
                    // （2026-10-08 用户反馈：在颜色面板里按返回键退不出来。）
                    if (!mControlLayout.collapseEditLayers()) mControlLayout.askToExit(this)
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val handleEvent = minecraftGLView.processKeyEvent(event)
        if (!handleEvent) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && touchCharInput?.isEnabled != true) {
                if (event.action != KeyEvent.ACTION_UP) return true
                CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE.toInt())
                return true
            }
        }
        return handleEvent
    }

    // ------------------------------------------------------------------ 生命周期（GLFW 窗口属性）

    /**
     * 切后台 / 锁屏 / 切应用时把 GLFW 窗口标记为「不可见」。
     *
     * 注意：这与 Pojav 完全一致 —— Pojav 在 `onStop` 里**同样**置 `GLFW_VISIBLE=0`。
     * 黑屏的真正原因不在这个 flag，而在渲染面：
     * 回前台时必须拿到**新的** Android Surface 并重新 `setupBridgeWindow`，
     * 原生侧才会做 `eglMakeCurrent` 切换（`gl_bridge.c` 的 `gl_swap_surface`）。
     * QookiX 旧实现用 TextureView 且回前台从不重绑，于是 EGLSurface 一直指向死掉的窗口。
     * 现在由 [MinecraftGLSurface] 的 SurfaceView 路径覆盖（`surfaceCreated` 里重绑）。
     */
    override fun onStart() {
        super.onStart()
        setWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 1)
    }

    override fun onStop() {
        setWindowAttrib(LwjglGlfwKeycode.GLFW_VISIBLE, 0)
        super.onStop()
    }

    /**
     * 复用实例时系统把新 Intent 交到这里。
     *
     * 场景：启动器「换版本」后进程重启，系统可能把旧任务恢复出来 → 新 Intent 落在这个
     * 被复用的实例上（本类原先没有重写这个方法，于是目标实例被悄悄忽略 ✗，实测踩过）。
     * 这里采信新的目标实例：本进程**还没起 JVM** 就按新实例重来；已经在跑则只记日志
     * （一个进程只能有一个游戏 JVM，换版本由启动器侧的「先结束再启动」负责）。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        if (newId == instanceId) return
        android.util.Log.i("GameActivity", "复用实例上收到新目标实例：$instanceId → $newId")
        setIntent(intent)
        instanceId = newId
        accountUuid = intent.getStringExtra(EXTRA_ACCOUNT_UUID).orEmpty()
        val running = try {
            TauriBridge.isGameRunning()
        } catch (e: Throwable) {
            android.util.Log.w("GameActivity", "查询游戏状态失败，按在运行处理", e)
            true
        }
        if (!running) {
            gameStarted = false   // 让 startGameOnce() 用新实例再跑一遍
            // recreate() 会重走 onCreate，那里要凭条才肯启动 —— 这是用户明确点的换版本，
            // 所以补一张新凭条（否则会被当成「多任务里点回来的旧卡片」直接收掉）。
            markLaunchRequest(this, newId)
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        if (LauncherPreferences.PREF_ENABLE_GYRO) mGyroControl?.enable()
        // SDL（26.3+）是嵌入使用的：surface 销毁时它把渲染线程暂停了（PAUSED），
        // 回到前台必须显式恢复，否则就是"进程活着但画面全黑"（锁屏/切任务回来的黑屏）。
        if (MinecraftGLSurface.sdlEnabled) SDLActivity.notifyAppResume()
        // 自愈看门狗：SDL 有时会继续往已死的渲染目标上画（表现=切回来全黑且不自恢复）。
        // 它盯着原生侧的出帧快照，发现"曾经出过帧但现在 1.2 秒没动静"就重绑一次 Surface。
        MinecraftGLSurface.startRenderWatchdog(this)
        setWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 1)
    }

    override fun onPause() {
        mGyroControl?.disable()
        // 游戏还抓着鼠标时先发一个 Esc：让它释放鼠标并进入暂停菜单，
        // 同时单机存档顺带自动暂停。缺了这一步，回来时游戏状态是乱的。
        if (CallbackBridge.isGrabbing()) {
            CallbackBridge.sendKeyPress(LwjglGlfwKeycode.GLFW_KEY_ESCAPE.toInt())
        }
        mQuickSettingSideDialog?.cancel()
        // 同步把 SDL 渲染线程暂停（surface 马上要销毁，别让它继续画）。
        if (MinecraftGLSurface.sdlEnabled) SDLActivity.notifyAppPause()
        MinecraftGLSurface.stopRenderWatchdog()
        setWindowAttrib(LwjglGlfwKeycode.GLFW_HOVERED, 0)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从别的窗口（对话框/分屏切回）拿回焦点时，SDL 状态机也要恢复——
        // 它的暂停条件里包含失去焦点。
        if (hasFocus && MinecraftGLSurface.sdlEnabled) SDLActivity.notifyAppResume()
    }

    override fun onPostResume() {
        super.onPostResume()
        // 从后台/其他界面回来时系统栏可能已被恢复，重新应用沉浸式
        applyImmersiveMode()
        // Pojav 同款：延迟重算一次窗口尺寸。切前台后 View 尺寸可能刚变化，
        // 不重算的话画面会被拉伸或者停在旧分辨率（表现为「回来一片黑」）。
        mControlLayout.postDelayed({ minecraftGLView.refreshSize() }, 500)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mGyroControl?.updateOrientation()
        mControlLayout.requestLayout()
        mControlLayout.post {
            minecraftGLView.refreshSize()
            Tools.updateWindowSize(this)
            mControlLayout.refreshControlButtonPositions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 浮层里的 Handler 必须停掉，否则泄漏 Activity
        try {
            startupOverlay?.stop()
        } catch (_: Throwable) {
        }
        startupOverlay = null
        // 联机面板的线程池同理（面板每次 openTerracottaPanel 复用同一个实例）
        try {
            mTerracottaPanel?.stop()
        } catch (_: Throwable) {
        }
        mTerracottaPanel = null
        try {
            CallbackBridge.removeGrabListener(touchpad)
            CallbackBridge.removeGrabListener(minecraftGLView)
        } catch (_: Throwable) {
        }
        GameService.stop(this)
        touchCharInput = null
        touchpad = null
    }

    private fun setWindowAttrib(attrib: Int, value: Int) {
        try {
            // 原生实现在 libpojavexec.so：把属性写进 JVM 侧 GLFW 的 windowAttribs。
            // 游戏还没显示窗口时它是空操作（pojav_environ->showingWindow == 0）。
            CallbackBridge.nativeSetWindowAttrib(attrib, value)
        } catch (_: Throwable) {
            // 原生库还没加载时忽略（比如游戏还没启动）
        }
    }

    // ------------------------------------------------------------------ 沉浸式全屏

    private val immersiveFlags = (
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

    /**
     * 进入沉浸式全屏，与 PojavLauncher 的 `Tools.setFullscreen` 一致。
     *
     * 为什么要注册 `OnSystemUiVisibilityChangeListener` 反复设置：用户从底部/顶部边缘
     * 滑出系统栏后，系统会**清掉**这些 flag，不重新设置就永久退回「有导航条」状态。
     * Pojav 正是靠这个监听器恢复的。
     *
     * 刘海/挖孔照抄 Pojav 的 `Tools.ignoreNotch`：`SHORT_EDGES` 让内容铺满挖孔区域，
     * 否则横屏时那一条会被裁掉（表现为屏幕一侧的黑边）。
     */
    /**
     * 把历史遗留的 [LEGACY_PREF_NAME] 偏好合并进 [PREF_NAME]。
     *
     * 早期版本游戏侧读的是 `pojav_prefs`、启动器设置页写的是 `launcher_preferences`，
     * 两个文件互不相见（见 [PREF_NAME] 的说明）。统一文件名后，首次运行把老文件里
     * **新文件还没有** 的键搬过来 —— 只补缺、不覆盖，用户之前在设置页里的选择优先。
     */
    private fun migrateLegacyPrefs() {
        val legacy = getSharedPreferences(LEGACY_PREF_NAME, MODE_PRIVATE)
        if (legacy.all.isEmpty()) return
        val target = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        val editor = target.edit()
        var moved = 0
        for ((key, value) in legacy.all) {
            if (target.contains(key)) continue
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                else -> {}
            }
            moved++
        }
        if (moved > 0) {
            editor.apply()
            Log.i(TAG, "已从 $LEGACY_PREF_NAME 合并 $moved 项偏好到 $PREF_NAME")
        }
    }

    private fun applyImmersiveMode() {
        // 刘海按用户的「忽略刘海」开关**双向分派**（问题清单 P0-13）：
        // 开 → SHORT_EDGES（内容铺满挖孔区）；关 → NEVER（不与挖孔区重叠）。
        // 之前无论开关都写死 SHORT_EDGES，而窗口尺寸那边又按「刘海不算」扣宽，
        // 导致坐标系与可视区不一致。
        // 键名与设置页写入的 `ignoreNotch` 一致（历史上这里写的是 `ignore_notch`，
        // 与启动器设置页的驼峰键对不上 → 开关一直无效）。同时兼容读取老键名。
        val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val ignoreNotch = prefs.getBoolean(
            "ignoreNotch",
            prefs.getBoolean("ignore_notch", false)
        )

        // API 30+ 用 WindowInsetsControllerCompat：`systemUiVisibility` 自 API 30 起废弃，
        // Android 15+ 强制 edge-to-edge 后不再可靠（问题清单 P1-26）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode = if (ignoreNotch) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }
            window.attributes = attrs
        }

        // Android 10+ 的「系统栏对比度保护」：手势导航时系统会在底部导航条区域
        // 叠一层半透明黑，游戏里就表现为「底部一条黑的把画面盖住」。关掉它。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        // API 30 以下由旧的 flag 体系兜底（30+ 已交给上面的 controller）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val decor = window.decorView
            decor.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    decor.systemUiVisibility = immersiveFlags
                }
            }
            decor.systemUiVisibility = immersiveFlags
        }
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
    }

    // ------------------------------------------------------------------ 崩溃诊断

    private fun showCrashDialog(exitCode: Int) {
        val saved = dumpDiagnostics()
        AlertDialog.Builder(this, R.style.QookixAlertDialog)
            .setTitle("游戏异常退出（退出码 $exitCode）")
            .setMessage(
                saved
                    ?: "无法读取日志，可用 adb logcat 查看（游戏崩溃时会保存崩溃报告到实例目录 crash-reports/）"
            )
            .setCancelable(false)
            .setPositiveButton("知道了") { _, _ -> finishAndRemoveTask() }
            .show()
    }

    /**
     * 把游戏日志与崩溃报告复制到外部私有目录
     * （`Android/data/com.zhayi.qookix/files/diagnostics/`），
     * 这样不用 adb 也能用文件管理器取出来发给开发者。
     */
    private fun dumpDiagnostics(): String? {
        return try {
            val external = getExternalFilesDir(null) ?: return null
            val target = File(external, "diagnostics")
            target.mkdirs()

            val logFile = File(filesDir, "logs/launch-$instanceId.log")
            if (logFile.isFile) logFile.copyTo(File(target, "launch-$instanceId.log"), true)

            val crashDir = File(DataDirs.instanceDir(filesDir, instanceId), "crash-reports")
            crashDir.listFiles()?.sortedByDescending { it.lastModified() }?.firstOrNull()?.let {
                it.copyTo(File(target, it.name), true)
            }

            "日志与崩溃报告已保存到：\n$target"
        } catch (e: Exception) {
            null
        }
    }
}

    // ══════════════════════════════════════════════════════════════════════
    // SDL 消息框桥接（Rust 的 nativeLaunchGame 会调它）
    // ══════════════════════════════════════════════════════════════════════
    //
    // ⚠️ 这个方法**必须存在**，否则游戏启动直接 abort（2026-10-13 实测）：
    //
    //   NoSuchMethodError: no non-static method
    //     "Lcom/zhayi/qookix/GameActivity;.messageboxShowMessageBox(
    //        ILjava/lang/String;Ljava/lang/String;[I[I[Ljava/lang/String;[I)I"
    //   at TauriBridge.nativeLaunchGame(Native method)
    //
    // ── 为什么缺 ──────────────────────────────────────────────────────────
    // 它是 **SDL 的约定**：`SDLActivity`（Pojav 的 SDL 基类）里有实现，
    // SDL 的 native 代码按这个名字回调它。但本项目的 `GameActivity`
    // 继承的是 `AppCompatActivity`（不是 `SDLActivity`），
    // 所以这个名字在 GameActivity 上不存在。
    //
    // Rust 侧的 nativeLaunchGame 会走这条路（SDL3 集成），于是
    // JNI 抛 NoSuchMethodError → 未处理异常传播回 JNI → ART 直接 abort。
    //
    // ── 实现取舍 ──────────────────────────────────────────────────────────
    // 这里**不弹真正的对话框**：启动阶段的「消息框」通常是 SDL 的错误提示，
    // 而此刻游戏还没起来，弹窗会阻塞在 UI 线程上（SDL 原实现是
    // `messageboxSelection.wait()` 阻塞等待）。
    //
    // 我们只把内容记进日志并返回默认按钮 id —— 保证：
    //   1. 方法存在 → 不再 NoSuchMethodError
    //   2. 不阻塞 → 启动流程继续
    //   3. 信息不丢 → 日志里能查到
    //
    // 如果将来需要真实弹窗（比如 SDL 报"显卡不支持"），
    // 可以在这里改成 AlertDialog + CountDownLatch。
    fun messageboxShowMessageBox(
        flags: Int,
        title: String?,
        message: String?,
        buttonFlags: IntArray?,
        buttonIds: IntArray?,
        buttonTexts: Array<String>?,
        colors: IntArray?,
    ): Int {
        android.util.Log.i(
            "GameActivity",
            "SDL messagebox: title=$title message=$message " +
                "buttons=${buttonTexts?.joinToString("/")}",
        )
        // 返回第一个按钮的 id（没给就返回 -1，与 SDL 的默认语义一致）
        return buttonIds?.firstOrNull() ?: -1
    }




