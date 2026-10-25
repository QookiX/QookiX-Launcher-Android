# 第三方代码来源与许可

本项目包含来自 **PojavLauncher** 的代码/二进制，遵循 **LGPL-3.0**。

- 上游：https://github.com/PojavLauncherTeam/PojavLauncher
- 许可证：GNU Lesser General Public License v3.0（见 `LICENSE-LGPL-3.0.txt`）
- 版权归 PojavLauncherTeam 及其贡献者所有；修改部分同样以 LGPL-3.0 提供。

## 具体来自 PojavLauncher 的部分

| 位置 | 内容 |
|---|---|
| `android/app/src/main/jni/` | `egl_bridge.c`、`input_bridge_v3.c`、`ctxbridges/*`、`jvm_hooks/*`、`driver_helper/*`、`utils.c`、`stdio_is.c`、`environ/*`（编译为 `libpojavexec.so`） |
| `android/app/src/main/java/org/lwjgl/glfw/CallbackBridge.java` | 输入桥（应用侧） |
| `android/app/src/main/java/net/kdt/pojavlaunch/` | `LwjglGlfwKeycode`、`EfficientAndroidLWJGLKeycode`、`GrabListener`、`CriticalNativeTest`、`Logger` |
| `android/app/src/main/java/dalvik/annotation/optimization/` | `CriticalNative` 注解桩 |
| `src-tauri/assets/lwjgl/lwjgl-glfw-classes.jar` | Pojav 版 LWJGL（含纯 Java GLFW stub） |
| `src-tauri/assets/cacio8|cacio17/*.jar` | Caciocavallo（AWT on Android） |
| `android/app/src/main/jniLibs/<abi>/*.so` | `liblwjgl*.so`、`libgl4es_114.so`、`libopenal.so`、`libfreetype.so`、`libOSMesa.so`、`libunpack200.so`、`libjnidispatch.so` |

### 我们在 `lwjgl-glfw-classes.jar` 上的修改

该 jar 里被我们**重新注入**过两个类（LWJGL 为 BSD-3-Clause，修改一并披露）：

| 类 | 我们的改动 | 补丁源码 |
|---|---|---|
| `org/lwjgl/glfw/GLFW.class` | 把「类初始化时需要 native 库」的动作全部延后到 `glfwInit()`（fork 版 `JNI_OnLoad` 会在库还没加载完时触发 `GLFW.<clinit>`，而类初始化失败不可恢复） | `scripts/lwjgl-glfw-patch/src/org/lwjgl/glfw/GLFW.java` |
| `org/lwjgl/sdl/SDLMouse.class` | 输入调试日志落盘（`files/logs/input_debug.log`） | `scripts/lwjgl-glfw-patch/src/org/lwjgl/sdl/SDLMouse.java` |

注入脚本：`scripts/patch-lwjgl-glfw.ps1`。

## 游戏内控制层（2026-09 从 PojavLauncher 原样移植，LGPL-3.0）

「进入游戏之后的逻辑」整体对齐 PojavLauncher 的 `MainActivity` 与 `customcontrols` 包。
逐文件来源如下（相对 `android/app/src/main/java/`），
除下表「改动」列标注者外均为**逐字节照搬上游**：

| 位置 | 内容 | 改动 |
|---|---|---|
| `net/kdt/pojavlaunch/customcontrols/`（9 个文件） | `ControlData`、`CustomControls`、`ControlLayout`、`ControlDrawerData`、`ControlJoystickData`、`LayoutConverter`、`LayoutSanitizer`、`ControlButtonMenuListener`、`EditorExitable` | 仅把 `import net.kdt.pojavlaunch.R` 改为 `import com.zhayi.qookix.R` |
| `net/kdt/pojavlaunch/customcontrols/buttons/`（5） | `ControlButton`、`ControlDrawer`、`ControlInterface`、`ControlJoystick`、`ControlSubButton` | 同上；`ControlButton` 把 `MainActivity.*` 指向 `com.zhayi.qookix.GameActivity`，`R.attr.colorAccent` 改为 `androidx.appcompat.R.attr.colorAccent` |
| `net/kdt/pojavlaunch/customcontrols/mouse/`（15） | `Touchpad`、`AbstractTouchpad`、`AndroidPointerCapture`、`GyroControl`、`HotbarView`、`InGUIEventProcessor`、`InGameEventProcessor`、`TouchEventProcessor`、`LeftClickGesture`、`RightClickGesture`、`TapDetector`、`Scroller`、`DropGesture`、`ValidatorGesture`、`PointerTracker` | R 导入；`InGUIEventProcessor` 删掉未使用的 `SingleTapConfirm` 导入 |
| `net/kdt/pojavlaunch/customcontrols/keyboard/`（3） | `TouchCharInput`、`LwjglCharSender`、`CharacterSenderStrategy` | R 导入；`R.attr.editTextStyle` → `androidx.appcompat.R.attr.editTextStyle` |
| `net/kdt/pojavlaunch/customcontrols/handleview/`（8） | `ControlHandleView`、`ActionRow`、`ActionButtonInterface`、`AddSubButton`、`CloneButton`、`DeleteButton`、`DrawerPullButton`、`EditControlSideDialog` | R 导入 |
| `net/kdt/pojavlaunch/customcontrols/gamepad/` | `GamepadJoystick`、`direct/DirectGamepadEnableHandler`、`direct/GamepadKeycodes` | R 导入 |
| `net/kdt/pojavlaunch/colorselector/`（10） | 颜色选择器控件 | R 导入 |
| `net/kdt/pojavlaunch/MinecraftGLSurface.java` | 渲染面 + 触摸事件分发 | 剥离「实体手柄重映射」部分（依赖 JitPack 的 `fr.spse.gamepad_remapper`，本机不可得），其余照搬 |
| `net/kdt/pojavlaunch/{CustomControlsActivity,ImportControlActivity}.java` | 控制布局编辑器 / 导入 | `BaseActivity` 内联为 `AppCompatActivity`；导出改用本项目的 `FileProvider`（见下） |
| `net/kdt/pojavlaunch/utils/{MathUtils,FileUtils,MCOptionUtils,JSONUtils}.java`、`utils/interfaces/` | 工具类 | 照搬 |
| `net/kdt/pojavlaunch/{LwjglGlfwKeycode,EfficientAndroidLWJGLKeycode,GrabListener,Logger}.java` | 键码表 / Grab 监听 / 日志 | 照搬 |
| `com/kdt/` | `SideDialogView`、`LoggerView`、`DefocusableScrollView`、`CustomSeekbar`、`SimpleArrayAdapter`、`pickafile/*` | 照搬 |
| `com/ipaulpro/afilechooser/FileListAdapter.java` | 文件选择器适配器 | 照搬 |
| `res/values/pojav_*.xml`、`res/values-zh-rCN/pojav_strings.xml`、`res/layout/activity_basemain.xml` 等 | Pojav 的字符串/数组/样式/尺寸/布局/图标 | 见 [`docs/pojav-ingame-port.md`](docs/pojav-ingame-port.md) 的「资源」一节 |
| `app/src/main/assets/pojav_default_control.json` | Pojav 的默认控制布局 | 照搬 |

### 自行编写的替身（shim）与内嵌第三方库

| 文件 | 说明 |
|---|---|
| `net/kdt/pojavlaunch/Tools.java` | Pojav 同名类的精简替身（只实现控制层用到的 22 个成员），语义与原版一致 |
| `net/kdt/pojavlaunch/prefs/LauncherPreferences.java` | 精简替身（17 个字段，键名与上游一致） |
| `net/kdt/pojavlaunch/utils/JREUtils.java` | 只保留 `setupBridgeWindow`，转发到 `TauriBridge` |
| `io/github/controlwear/virtual/joystick/android/JoystickView.java` | 自行实现，API 对齐 `virtual-joystick-android`（Apache-2.0，本机无法访问 JitPack） |
| `top/defaults/checkerboarddrawable/CheckerboardDrawable.java` | 自行实现，API 对齐 `checkerboarddrawable`（Apache-2.0） |
| `org/apache/commons/io/IOUtils.java` | 自行实现 `copy`，替代 474KB 的 `ExagearApacheCommons.jar` |
| `app/libs/exp4j-0.4.9-SNAPSHOT.jar` | 来自 PojavLauncher 的 `libs/`（PojavLauncherTeam 的 exp4j fork） |

> 上述 Pojav 来源文件仍以 **LGPL-3.0** 提供；修改（R 包名、shim 接线、剥离手柄重映射）同样以 LGPL-3.0 提供。
> 自行编写的替身类（`Tools`/`LauncherPreferences`/`JREUtils`/`JoystickView`/`CheckerboardDrawable`/`IOUtils`）
> 是为了在没有网络、无法引入上游依赖的环境下复现相同行为而重写的，不包含上游源码。

## 我们自己编写的部分

`src-tauri/src/**`（Rust 核心、启动器逻辑）、
`android/app/src/main/java/com/zhayi/qookix/**`（Compose 界面、GameActivity、虚拟控件、服务、JNI 桥接层）
等，不在此许可覆盖范围内 ——
它们通过**动态库**（`libpojavexec.so`）与上述 LGPL 组件交互，符合 LGPL-3.0 的「允许替换库」要求。

## LGPL-3.0 的自定义义务（我们已遵守）

1. 保留版权与许可声明（本文件）；
2. 修改过的 LGPL 部分仍然以 LGPL-3.0 提供（本仓库公开这些源码）；
3. 允许用户替换该库：`libpojavexec.so` 以独立 `.so` 形式随 APK 分发，可被替换后重新打包。

## 渲染后端（本次新增，用于「设置 → 游戏内 → 渲染器」）

为了让渲染器能在 GL4ES 之外提供 **Zink + Turnip**，本仓库新增 3 个**预编译原生库**，
取自 PojavLauncher 上游发布包中 `app_pojavlauncher/src/main/jniLibs/arm64-v8a/` 下的同名文件
（未修改二进制，来源与许可如下）：

| 库 | 来源 | 许可 | 与 GPL-3.0 的兼容性 |
|---|---|---|---|
| `libOSMesa.so` | Mesa 3D（OSMesa + Zink + gallium） | **MIT** | ✔ 宽松许可，可并入 |
| `libvulkan_freedreno.so` | Mesa 3D（Turnip，Adreno 的 Vulkan 驱动） | **MIT** | ✔ |
| `libVkLayer_khronos_timeline_semaphore.so` | Khronos Vulkan-ExtensionLayer | **Apache-2.0** | ✔（Apache-2.0 可并入 GPL-3.0） |

随 PojavLauncher 一并取得的既有原生库（此前已在用，这里补全许可信息）：

| 库 | 来源 | 许可 |
|---|---|---|
| `libgl4es_114.so` | GL4ES（ptitSeb） | MIT |
| `liblwjgl*.so` | LWJGL 3 | BSD-3-Clause |
| `libopenal.so` | OpenAL Soft | LGPL（上游为 LGPL-2.0-or-later，**上架前建议再核对其 COPYING**） |
| `libfreetype.so` | FreeType | FTL 或 GPL-2.0（双许可） |
| `libjnidispatch.so` | JNA | LGPL-2.1-or-later 或 Apache-2.0（双许可） |
| `libunpack200.so` | OpenJDK（JRE 内工具） | GPL-2.0 with Classpath Exception |

> 注意：上表中的上游预编译库多数仍是 **4 KB 页对齐**（`PT_LOAD.p_align = 0x1000`），
> Android 15+ 的 16 KB 页设备上可能无法 dlopen。**逐库的实测对齐情况**见
> [`KNOWN_ISSUES.md`](KNOWN_ISSUES.md) 的「16 KB 页设备」一节。
>
> 本项目自建的原生库都已按 16 KB 编译：`libqookix_lib.so`（Rust，
> `-Wl,-z,max-page-size=16384`，CI 会校验 PT_LOAD 对齐）与
> `libpojavexec.so` / `liblinkerhook.so` / `libthreaddump.so`（`Android.mk` 中的 `PAGE_ALIGN_LDFLAGS`）。

## MobileGlues 渲染器（LGPL-2.1，**分发的是修改版**）

「设置 → 游戏内 → 渲染器」里的 MobileGlues 由上游编译而来，但**我们改过它的源码**，
因此按 LGPL-2.1 的要求一并公开修改后的源码：

| 项 | 值 |
|---|---|
| 上游 | https://github.com/MobileGL-Dev/MobileGlues（基线提交 `97558a6`） |
| 许可证 | GNU Lesser General Public License v2.1 |
| 我们的修改 | `patches/mobileglues/0001-glsl-uniform-word-boundary.patch`（`glsl_for_es.cpp`，+19 −1：`uniform` 关键字改为整词匹配，否则 26.x 的地形着色器会被改写成非法语句） |
| 修改说明与复现构建步骤 | `patches/mobileglues/README.md` |
| 分发形式 | 「渲染器插件」zip（本仓库 `plugins` Release）→ 用户按需下载，**不随 APK 分发** |

`libmobileglues_info_getter.so` 是上游同一次构建的配套库，未作修改。

LGPL 义务的履行方式与其他 LGPL 组件一致：**以独立 `.so` 形式提供**（用户可自行替换后重新打包），
且修改后的源码（基线提交 + 上述补丁）公开在本仓库 `patches/mobileglues/`。

> 同类组件还有 Mesa（`libOSMesa.so`、`libvulkan_freedreno.so`，MIT）、
> Khronos 时间线信号量层（Apache-2.0）与 GL4ES / LWJGL / OpenAL 等，
> 均以「渲染器 / 驱动插件」形式按需分发，未随 APK 打包。

## 本项目的许可与「LGPL 并入 GPL」声明

本项目以 **GPL-3.0** 发布，其中包含 **LGPL-3.0** 的 PojavLauncher 代码（游戏内控制层）
以及上表的第三方原生库。合并是**明确允许**的：

- **LGPL-3.0 → GPL-3.0**：LGPL-3.0 第 2 条允许把「基于库的作品」按 GPL-3.0 传递；
- **MIT / BSD-3-Clause / Apache-2.0**：均为宽松许可，可并入 GPL-3.0 作品，只需保留其版权与许可声明（即本文件）；
- 因此整个 APK 可以合法地以 **GPL-3.0** 分发。

为满足 LGPL-3.0 的义务，我们：
1. 在本文件保留全部版权与许可声明；
2. 以 LGPL-3.0 提供 Pojav 部分的源码（本仓库公开这些文件）；
3. **允许替换 LGPL 组件**：`libpojavexec.so` 与上表各原生库都以独立 `.so` 形式随 APK 分发，
   用户可自行替换后重新打包。

## 陶瓦联机（Terracotta）

- **项目**：Terracotta（陶瓦联机）
- **作者 / 版权**：Copyright © burningtnt
- **仓库**：https://github.com/burningtnt/Terracotta
- **许可证**：GNU Affero General Public License v3.0 or later，**并附带上游 README 声明的例外条款**
- **例外原文（上游 README「许可」章节）**：
  > 作为特例，如果您的程序通过以下方式利用本作品，则相应的行为不会导致您的作品被 AGPL 协议涵盖：
  > 1. 您的程序通过打包的方式包含本作品未经修改的二进制形式，而没有静态或动态地链接到本作品；或
  > 2. 您的程序通过本作品提供的进程间通信接口（如 HTTP API）与未经修改的本作品应用程序进行交互，
  >    且在您的程序用户界面明显处标识了本作品的版权信息。
- **集成方式（我们采用第 2 条）**：
  - Android 版把上游发布的**未经修改**的 `terracotta-<版本>-android-arm64v8a.so`
    原样打包进 `jniLibs/arm64-v8a/libterracotta.so`；
  - 该库**仅在独立的 `:tunnel` 进程中加载**（`TerracottaTunnelService.kt`），
    启动器主进程**不加载、不链接**它，只通过 `127.0.0.1` 上的 HTTP 接口通信
    （见 `src-tauri/src/terracotta.rs`）；
  - 界面署名见「多人游戏 → 陶瓦联机」卡片底部，以及本页。
- **涉及版本**：v0.4.2（内含 EasyTier v2.5.0-terracotta.2）
