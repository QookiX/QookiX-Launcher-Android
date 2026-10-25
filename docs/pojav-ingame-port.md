# 游戏内逻辑对齐 PojavLauncher —— 移植记录

> 范围：`src-tauri/gen/android/app`（**唯一实际构建的 Android 工程**）
> 上游：`I:\program\vibe\mc\PojavLauncher`（`v3_openjdk`，HEAD `b12ad0481`）
> 逐行依赖侦察原始报告：仓库根目录 `POJAV_CONTROL_LAYER_PORT_REPORT.md`

## 1. 结论

进入游戏之后的逻辑**已整体替换为 PojavLauncher 的实现**：
渲染面、触摸手势、虚拟控件、软键盘、游戏内菜单抽屉、控制布局编辑器全部来自
上游 `net.kdt.pojavlaunch.MainActivity` + `customcontrols` 包，逐文件照搬；
QookiX 自研的 `VirtualControlsView` / `ControlLayoutData` / `GameSurfaceView` / `InputDispatcher`
已删除。

## 2. 架构对照

| 环节 | 替换前（QookiX 自研） | 现在（= Pojav） |
|---|---|---|
| 渲染载体 | `TextureView`（`GameSurfaceView`） | `MinecraftGLSurface`：内部自建 `SurfaceView`/`TextureView`，`addView` 进 `ControlLayout` |
| 视图层级 | `FrameLayout` + 手写控件层 | `DrawerLayout → FrameLayout → ControlLayout → {DimensionTracker, MinecraftGLSurface, Touchpad, TouchCharInput, DrawerPullButton, HotbarView}` + `LoggerView` |
| 触摸 | 手写点按/长按/双指判定 | `InGUIEventProcessor` / `InGameEventProcessor` + `TapDetector` / `Left+RightClickGesture` / `Scroller` |
| 虚拟鼠标 | 自绘指针 | `Touchpad` + `AndroidPointerCapture`（Android 8+ 指针捕获） |
| 虚拟控件 | `VirtualControlsView` + 自研 JSON | `ControlLayout` + `ControlData`（Pojav 控制布局 v8，含摇杆/抽屉/滑动按钮/贯通模式） |
| 软键盘 | 无 | `TouchCharInput` + `LwjglCharSender` |
| 游戏内菜单 | `MaterialAlertDialog` 列表 | 右侧 `DrawerLayout` + `R.array.menu_ingame`（含快速设置侧栏、发送自定义键码、日志浮层） |
| 布局编辑器 | 自研拖动 | `ControlLayout` 就地编辑器 + `ActionRow` / `ControlHandleView` / `EditControlSideDialog` / `ColorSelector` |
| 返回键 | `onBackPressed` 弹对话框 | `dispatchKeyEvent` 映射为 `GLFW_KEY_ESCAPE`（+ 兜底 `OnBackPressedCallback`） |
| 陀螺仪 | 自研 | `GyroControl`（可被 `QuickSettingSideDialog` 开关/调参） |

## 3. 关键修复

### 3.1 切后台回来黑屏（真根因，与原始判断不同）

原判断是「`onStop` 摘了 `GLFW_VISIBLE` 导致不渲染」。**这个判断是错的** ——
Pojav 自己在 `MainActivity.onStop()` 里同样置 `GLFW_VISIBLE=0`。

真根因（原生插桩实测）：

```
切到后台： gl_bridge.c: No new native surface, switching to 1x1 pbuffer
           gl_bridge.c: The window has died, awaiting window change
切回前台： TauriBridge: bridge window set: Surface(...)        ← Surface 已重绑给原生
           （但渲染线程永远不知道要换窗口 → 一直画到 1×1 pbuffer → 黑屏）
```

`egl_bridge.c` 的 `nativeSetupBridgeWindow()` 靠 `br_setup_window()` 通知渲染线程换窗口，
而插桩显示该函数指针在 QookiX 的启动链路上**始终为 NULL**：

```
setupBridgeWindow: surface=0x… pojavWindow=0x… bundle=0x77e59acee0 br_setup_window=0x0
```

即 JVM 侧从未走到 `pojavInitOpenGL() → set_gl_bridge_tbl()`（它是直接按符号名调用
`gl_make_current` / `gl_swap_buffers` 的，不经过 `br_*` 函数表）。
启动时之所以能出画面，只是因为 `gl_make_current()` 恰好直接读了 `pojav_environ->pojavWindow`；
一旦切后台让旧窗口死掉，就再也没人把新窗口告诉渲染线程。

**修法**（`jni/egl_bridge.c`）：`br_setup_window` 为空时直接调用 `gl_setup_window()`，
语义完全一致。修复后日志：

```
QKPJ: setupBridgeWindow: … br_setup_window=0x0
gl_bridge.c: gl_setup_window: mainWindowBundle=0x77e59acee0 pojavWindow=0x7975836d40
gl_bridge.c: Main window bundle is not NULL, changing state
gl_bridge.c: Switching to new native surface        ← 4 ms 后画面回来
```

### 3.2 `CallbackBridge.physicalWidth/Height` 为 0 → WASD 等按钮被摆到屏幕外

`Tools.updateWindowSize()` 原实现里，`dimension_tracker` 还没测量时直接 `return`，
导致 `physicalWidth/Height` 停在 0。于是用 `${bottom}` / `${right}` / `${screen_height}`
定位的按钮（WASD、跳跃、背包、GUI、左右键、Mouse…）全被算成负坐标 ——
表现是「只有靠 `${margin}` 定位的左上角 5 个按钮可见」。

按 Pojav 原语义修正：`dimension_tracker` 未测量时**回退到屏幕尺寸**，
并且 `Tools.getDisplayMetrics()` 改用 `getRealMetrics()`（真实屏幕尺寸含系统栏）
并按 `PREF_IGNORE_NOTCH` 扣掉刘海。

### 3.3 返回键会把游戏界面退掉

`AppCompatActivity` 的默认返回处理会 `finish()` 本 Activity，留下
「JVM 还在跑、界面却没了」的进程。现在显式注册
`OnBackPressedDispatcher.addCallback(...)`（编辑模式下走 `askToExit`，
否则发 `GLFW_KEY_ESCAPE`），与 Pojav 的按键路径一致，且绝不会 finish。

### 3.4 其他

- 默认控制布局缺失 → 首次运行从 `assets/pojav_default_control.json`（= 上游 `assets/default.json`）
  写入 `<files>/controlmap/default.json`。
- `activity_basemain.xml` 里没有 `dimension_tracker` 的 id 声明会导致 aapt 报重复资源 →
  由 `pojav_styles.xml` 的 `@id/dimension_tracker` 单独声明（不再另建 `ids.xml`）。
- `Pojav` 的 `DialogSendCustomKey` 依赖 `EfficientAndroidLWJGLKeycode` 的数组顺序 →
  该文件必须整份照搬，不能简化。

## 4. 与 Pojav 的差异（有意为之）

| 差异 | 原因 |
|---|---|
| 所有 `import net.kdt.pojavlaunch.R;` → `import com.zhayi.qookix.R;` | 应用模块 namespace 是 `com.zhayi.qookix`；只有这一行改动，行为不变 |
| `Tools` / `LauncherPreferences` / `JREUtils` 用精简替身 | 上游 `Tools.java` 有 1600+ 行、牵扯整个启动器；替身只实现控制层用到的成员，语义与签名一致 |
| `ControlButton` 调 `GameActivity.switchKeyboardState()/toggleMouse()` | 上游调 `MainActivity` 的同名静态方法；QookiX 的游戏宿主是 `GameActivity` |
| `MinecraftGLSurface` 剥离实体手柄重映射 | 依赖只在 JitPack 发布的 `fr.spse.gamepad_remapper`，本机网络不可用；QookiX 也没有手柄重映射 UI。虚拟摇杆不受影响（走 `ControlJoystick` + `JoystickView`） |
| `JoystickView` / `CheckerboardDrawable` / `IOUtils` 自行实现 | 同上：无网络，无法引入 JitPack / commons-io；API 面与上游用法一致 |
| `portrait-sdp/ssp` 的 `_NNsdp` / `_NNssp` 用等值 dp/sp 生成 | 同上；只影响少数图标按钮的视觉大小 |
| `dialog_control_button_setting.xml` 里 `ExtendedTextView` → `TextView` | 同上（ExtendedView AAR 不可得），去掉两个 AAR 专有属性 |
| 控制布局导出改用 `FileProvider` | Pojav 用 `scoped.FolderProvider`（`@string/storageProviderAuthorities` 由 Gradle `resValue` 生成）；QookiX 复用已有 FileProvider（`res/xml/file_paths.xml` 的 `controlmap`），并修掉上游「连开两次 `startActivity`」的小 bug |
| `GameActivity` **不加** `android:process=":game"` | 游戏 JVM 通过 JNI 跑在应用进程内（`libqookix_lib.so` 持有 JVM 与 Surface 桥）；拆进程会让两边各持一份 `pojav_environ` / 输入队列 |
| `GameActivity` 用 `launchMode="singleTask"` | 从启动器再点「启动游戏」必须复用实例，避免两个 Activity 抢同一个 Surface/JVM |
| 未移植 `AwtCharSender` / `AWTInputBridge` 路径 | 那是 Pojav 的 Java-GUI 安装器专用；游戏内固定用 `LwjglCharSender` |
| 「强制关闭游戏」先 `TauriBridge.killGame()` 再 finish | 上游直接 `Process.killProcess()`；QookiX 需要让 Rust 侧优雅结束 JVM（落存档/日志） |

## 5. 资源

从上游 `res/` 拷入 app 模块（改名避免与现有文件重名）：
`pojav_strings.xml`（+ `values-zh-rCN/`）、`pojav_arrays.xml`（`menu_ingame` /
`menu_customcontrol` / `menu_customcontrol_customactivity`）、`pojav_styles.xml`
（`DimensionTracker` / `ThickDivider`）、`pojav_integers.xml`、
`pojav_colors.xml`、`pojav_attributes.xml`、`pojav_dimens.xml`、`pojav_dimens_sdp.xml`（生成）、
布局 `activity_basemain` / `activity_custom_controls` / `activity_import_control` /
`dialog_side_dialog` / `dialog_quick_setting` / `dialog_control_button_setting` /
`dialog_color_selector` / `view_logger` / `dialog_expendable_list_view` /
`item_centered_textview(_large)` / `item_simple_list_1`，以及被它们引用的 drawable。
`app/src/main/assets/pojav_default_control.json` = 上游 `assets/default.json`。

新增依赖（均已在本地 Gradle 缓存中）：`androidx.drawerlayout`、`androidx.constraintlayout`、
`com.google.code.gson`、`androidx.preference`（上游 `styles.xml` 引用其 Preference 主题）、
本地 jar `exp4j-0.4.9-SNAPSHOT.jar`。

## 6. 真机验证（OnePlus 8 / arm64-v8a / Android 16）

| # | 项目 | 结果 |
|---|---|---|
| P0 | 切后台（HOME）→ 最近任务回来 | ✅ 画面正常恢复（修 3.1 前为黑屏） |
| P1 | MOUSE 键 → 虚拟鼠标 | ✅ 指针出现在屏幕中央；拖动后跟随移动 |
| P2 | KEYBOARD 键 → 软键盘 | ✅ `mServedView=…keyboard.TouchCharInput`、`mIsInputViewShown=true`，Gboard 拉起 |
| P3 | 顶部齿轮 → 右侧抽屉 | ✅ 强制关闭 / 日志输出 / 发送自定义键码 / 快速设置 / 自定义控制布局（中文来自 zh-rCN） |
| P3 | 快速设置侧栏 | ✅ 分辨率缩放 / 陀螺仪 / 鼠标速度 / 禁用手势 / 长按触发 + 取消回滚 |
| P3 | 发送自定义键码 | ✅ 弹出 `EfficientAndroidLWJGLKeycode.generateKeyName()` 列表 |
| P4 | 默认控制布局 | ✅ DEBUG/CHAT/KEYBOARD/TAB/3RD、MOUSE、左下 WASD+Shift+Jump+Inv+GUI+左右键，位置与上游一致 |
| P4 | 就地编辑器 | ✅ 添加按键 / 添加组合键 / 添加摇杆 / 加载 / 保存 / 选择默认 / 退出编辑器 |
| P4 | 添加摇杆 | ✅ 摇杆出现在屏幕中央并可渲染 |
| P4 | 退出编辑器 | ✅ 弹出「您确定要退出？否/是」，确认后丢弃改动并重载布局 |
| — | 返回键 | ✅ 留在游戏内（发 Esc），不再 finish 界面 |

证据截图：仓库根目录 `verify_pojav_01..21_*.png`；日志：`log_startup.txt` / `log_resume.txt`。

## 7. 界面换肤（对齐 QookiX 设计语言）

Pojav 原版 UI 是「方形半透明黑按钮 + 纯文本 ListView 抽屉 + 手绘黑色半圆拉手」的复古风格。
在不改任何交互逻辑的前提下，把游戏内所有界面统一到启动器的设计语言
（取自 `src/styles.css` / `src/theme.ts`，强调色取用户当前生效的紫色 `#7C3AED`）：

| 元素 | 改动 |
|---|---|
| 调色板 | 新增 `values/qk_game_colors.xml`：强调色 `#7C3AED`（含 20%/40% 透明档）、底色 `#0B0D12/#10131A/#151924`、描边 `#14FFFFFF`、文字三级 `#F2F3F7/#C6C8D2/#8B8E9C` |
| `Theme.qookix_game` | 补齐 `colorPrimary/colorSecondary/colorSurface/colorOnSurface/colorBackgroundFloating/colorControlActivated/textColorPrimary…` 与 `alertDialogTheme` |
| **`values-night/themes.xml`** | **删掉其中的 `Theme.qookix_game`**：DayNight 的夜版会整体覆盖同名 style，之前它把配色/对话框主题全部抵消（表现为 SeekBar 变回 Material 青色、对话框变浅色） |
| 控制按钮 | 内置布局 `<files>/controlmap/default.json` 换成圆角（`cornerRadius` 34/46）、深色半透明填充 + 细描边；键盘/控件/鼠标用强调色底+强调色描边；标签改中文并修掉 `⬛`（跳跃）渲染成空框的缺陷 |
| 按钮文字 | `ControlButton` 标签随按钮尺寸缩放（短边 42%，夹 11–17sp）、正文色改用 `qk_text_1`、加淡投影；`PREF_BUTTON_ALL_CAPS` 默认关闭 |
| 抽屉菜单 | `activity_basemain.xml` 抽屉改成贴右边缘的圆角「纸片」+ 标题/副标题 + 分隔线；列表项换成 `item_qk_menu.xml`（圆角涟漪 + 强调色图标），新增 `QookixMenuAdapter` |
| 图标 | 新增 11 个自绘矢量图标（`ic_qk_*.xml`）；文案仍取 Pojav 数组，保证点击 position 语义不变 |
| 拉手 | `DrawerPullButton.onDraw` 改为强调色描边胶囊 + 居中齿轮（原来是贴上缘的黑色半圆 + 33% 透明齿轮） |
| 侧栏面板 | `background_control_editor` 改圆角 18dp + 表面色 + 描边；标题/标签/数值/开关/按钮全部上样式；SeekBar 与 Switch 显式 tint（不依赖主题解析） |
| 对话框 | 新增 `QookixAlertDialog`（深色圆角 + 描边）+ `QookixDialogButton`；按钮栏需设 `buttonBar*ButtonStyle`，只设 `buttonStyle` 不生效 |
| 日志浮层 | 顶栏改表面色、标题白粗体、关闭按钮强调色并缩小 |

**顺带修掉的真实缺陷**

1. **侧栏只滑入一小条**：`SideDialogView.appear()` 用同一帧里还没测量完的
   `mScrollView.getWidth()` 当滑入终点，算出「几乎仍在屏幕外」的位置
   （上游同样存在，表现为文字被整列截断）。改为 `post()` 到下一次布局后取宽度，
   并在未测量时退回布局声明的 `@dimen/_280sdp`。
2. **`⬛`（跳跃键）显示为空框**：该字符在默认字体里没有字形，已改成中文标签。

**升级语义**：内置布局的皮肤版本记录在 `<files>/controlmap/.qk-control-style`，
版本变化时只覆盖内置的 `default.json` 一次；玩家自己另存的控制布局不受影响。

## 8. 后续可做

1. 实体手柄重映射（需要 `fr.spse.gamepad_remapper`；有网络后直接加 JitPack 依赖即可恢复上游代码）。
2. `android:process=":game"`（Pojav 用它隔离游戏进程；需先把 Rust 侧的 JVM/Surface 桥搬到该进程）。
3. 用真正的 `portrait-sdp/ssp` 替换生成的等值尺寸，视觉更贴近上游。
4. `libpojavexec.so` 目前随 APK 分发并释放到 `<files>/natives`；Android 未来版本会拒绝加载可写目录的 `.so`
   （logcat 已有警告 `Attempt to load writable file`），届时应改为只读路径 + 校验。
