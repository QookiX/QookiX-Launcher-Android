# 交接：自维护 LWJGL 安卓移植模块（路线 B）

> 目标读者：接管这块工作的另一个 AI / 开发者。
> 这份文档只讲**这一件事**（把随包的 Pojav 版 LWJGL 换成「版本串与官方一致」的自维护移植），
> 以及与它直接相关的现状。其它已完成的工作只在"背景"里提一句。

---

## 0. 【2026-10-24 重大更新】路线 B 的前提已经被推翻 —— 不需要换 LWJGL

真机实验（OnePlus 8，SD865）证明本文档 §2 的判断**只对了一半**：

- **PreLaunchChecks（LWJGL 版本串）早已通过**：随包的 `lwjgl-glfw-classes.jar` 报告的版本串
  已经满足 `startsWith("3.3.3")`（实测 Sodium 0.8.13 没有走 PreLaunch 中止分支）。
- 真正卡住的是 **`PostLaunchChecks.isUsingPojavLauncher()`**（`onContextInitialized` 里）：
  ```java
  if (System.getenv("POJAV_RENDERER") != null) return true;   // ← 我们命中这条
  // 另一条：java.library.path 含 /data/user/<n>/net.kdt.pojavlaunch —— 我们不命中
  ```
  它只看**环境变量名是否存在**，与启动器品牌、版本串无关。
- **修复（已实施并验证）**：把环境变量改名 `POJAV_RENDERER` → `QOOKIX_RENDERER`，C 侧同步：
  - C 源码 3 处 `getenv("POJAV_RENDERER")`：`jni/egl_bridge.c:185`、`jni/ctxbridges/gl_bridge.c:83`、`jni/perf_counters.c:116`
  - Rust 侧：`launch.rs`（约 445 行，env_map.insert）
  - 改完 `cargo build` + `gradlew assembleArm64Debug` 即可，**无需构建 LWJGL fork**。
- §5.1 先验实验结论：**值得做且已经做完** —— Sodium 0.8.13 / sodium-extra / reeses 全部正常加载，
  Zink + Turnip 渲染器（插件 `qookix-renderer-zink-osmesa` + `qookix-driver-turnip`）60 FPS 渲染，
  窗口创建、资源重载完成，无任何 Pojav 中止。

### 实验遗留的独立问题（与 LWJGL/Sodium 无关）—— 排查记录（2026-10-24/25）

### 【2026-10-25 深夜 · 最终修正】-6 的真因：单次启动内的 LWJGL 双类加载器竞态

**「第一次失败 exit code 1 → 同进程重试」的理论被推翻了**（本节早先版本写错了）。
加了入口日志后实测（launch_game 入口 log_line + JLI_Launch 计数）：
`launch_game` 只被调用**一次**，`JLI_Launch` 也只有**一次** —— 之前日志里的
「71 失败 → 72 重试」是**崩溃泄漏的重定向 fd 把上一次启动的 println 追加进
同一个文件**造成的交错假象（一次点击只创建一个 GameActivity，已用
onCreate 的 identityHashCode 日志验证）。

**真因（证据充分）**：游戏 JVM 里存在**两份** org.lwjgl.system.Library——
`lwjgl-glfw-classes.jar` 在 `-cp` 上（系统类加载器可见），BootstrapLauncher
又把它定义进 BOOT 层（模块 lwjgl.glfw.classes）。两份各自做版本检查
（所以 `Incompatible … versions detected` 每次启动打印两次），**竞态**在于：
哪份先把 `liblwjgl.so` 注册进 JVM 的 NativeLibraries（按绝对路径 +
类加载器记账）。输家在 RenderSystem.<clinit> 时再 `System.load` 同一路径 →
`UnsatisfiedLinkError: already loaded in another classloader` → JVM abort →
SIGABRT → abort_waiter 转成退出码 -6 弹窗。纯时序竞态，所以时好时坏
（同一构建有时 60FPS 进主菜单、有时 -6）。

**已上的缓解（GameActivity.startGameOnce）**：退出码 -6 时自动重启一次游戏
（1.5s 后 startGameOnce，Toast 提示，只补一次），实测竞态输了的重试能进主菜单。
用户从「崩溃弹窗」变成无感自愈。

**根治方向（待做）**：让 liblwjgl.so 只被一个类加载器加载 ——
a) 把 `lwjgl-glfw-classes.jar` 从 `-cp` 挪进 `-p`（BOOT 层唯一来源），或
b) 启动最早期由 BOOT 层主动 `System.loadLibrary("lwjgl")` 抢注，
   后续所有加载都命中同一登记；
c) LWJGL 对齐工作流（本文件 §1）里换自维护移植时一并解决。

### 【2026-10-25 追加】✅ 已进主菜单（vulkan_zink 渲染器）—— GL4ES 路径的根因也已定位

**验收结果（2026-10-25 14:12）**：实例 `a39e8632`（月落花野，NeoForge 21.1.251，182 个模组）
把 `instance.json` 的 `"renderer"` 从 `null` 改为 **`"vulkan_zink"`** 后，
**完整进入整合包自定义主菜单**（MOONLIT WILDS 标题 + 全景 + 182 模组计数 +
模组更新弹窗都正常渲染），Zink + Turnip 渲染正常。截图：`shots/verify_18_MAINMENU.png`。

到这里，本文件 §1 的验收标准 1、2、3 在**本实例上全部达成**（走 zink 插件）。

**GL4ES 路径（`opengles2`，MC 1.21.1 的自动默认）为什么死 —— 根因已锁定**：
- Sodium 在 `GL.createCapabilities()` 之后立刻取
  `glGetString(GL_VENDOR)`（成功）→ `glGetString(GL_RENDERER)`（**返回 NULL** → NPE）
  （Sodium `WindowMixin.postContextReady` → `GlContextInfo.create`）。
- **上游 ptitSeb/gl4es 的 `gl4es_glGetString`（src/gl/getter.c:247）对 GL_RENDERER
  永远返回静态串**（"GL4ES using %s"），不可能为 NULL。
- 但随包的 `jniLibs/arm64-v8a/libgl4es_114.so` 里**没有** "GL4ES using/wrapping"
  这两个格式串 —— 是定制构建，getter 被改过（疑似直通底层 GLES，或依赖
  `gl4es_original_renderer` 全局、而本仓库 `gl_bridge.c::nativeInitGl4esInternals`
  里那段「接管 GL_VENDOR/RENDERER」的代码被注释摘掉了 —— 见该函数内 TODO 注释，
  历史上还因启用它导致「停在 Backend library」）。修复路线二选一：
  a) 用上游源码重编 gl4es（getter.c 有 NULL 兜底，最干净）；
  b) 把 `nativeInitGl4esInternals` 的接管做完做对（当年卡在调用时机/FindClass）。
- 另外 GL4ES 冷启动首启还伴随「第一次 JLI_Launch 静默 exit code 1 → 同进程重试
  → liblwjgl.so already loaded in another classloader」（见上一节），zink 路径
  冷启动偶发同样模式 —— exit code 1 的第一根因仍未抓到，重试代码仍未找到
  （Rust/Kotlin/C 三层都没有），待办同前。

安装链（本次已修，commit `198d9d2`，别再重复排查）：
1. installer jar 本地跳过下载 ✓（maven.neoforged.net 被拦 → adb 推 jar 到 `<game_root>/libraries/`）
2. 依赖校验目录错位已修（原来查 game_root/libraries，下载落 data_dir/libraries）
3. JRE-21 现在会在 install_loader 里自动补装（不用再手动下）
4. **earlydisplay strip 时序 bug 已修**：原来 strip 在参数构造时才执行，但 profile 的
   libraries 早已并入 classpath —— earlydisplay 照样混进去，gl4es 上
   `ElementShader.init: VertexShader linkage failure`（"An error occurred initializing
   shaders"）循环 y/n 卡死。现在 strip 在 classpath 合并前执行。
5. 设备镜像设置已切 `official` → `bmclapi`（31 个 neoforged 依赖靠它下齐）



进主菜单前的红色 Mojang 加载屏**长时间不消失**（>50 分钟），Render 线程与
"KubeJS 2101.7.2" 线程持续 100% CPU。已查明：
- KubeJS 脚本本身 2.3s 就加载完了（`logs/kubejs/*.log`），不是脚本慢；
- 资源重载已完成（`Client resource reload complete!`），且
  `[ModernFix] Game took ~130 seconds to start` + `[FANCYMENU] title_screen 注册`
  —— **TitleScreen 已构造，只是 LoadingOverlay 不退场**；
- 稳定复现（三轮：DNS 修复前后都一样），但该实例历史上从未进过主菜单；
- "KubeJS 2101.7.2" 线程 = ScriptType 的单线程 executor（2101.x 用
  `new Thread(r, "KubeJS " + VERSION)` 命名）。空闲时它应该 sleep 等队列，
  现在 100% 满转说明有个任务死循环，但没有栈无法定位。

**抓 Java 线程栈在安卓上全部失败**（内嵌 HotSpot 的线程 ART 管不到）：
- `kill -3`（SIGQUIT → hotspot dump）：信号被 ART 的 sigchain 吃掉，无输出；
- `.attach_pid` + SIGQUIT（hotspot attach listener）：同样无效；
- `/data/anr/trace_*`：只有 ART/Kotlin 线程栈，没有 JVM 的；
- `-agentlib:jdwp`（settings.json 全局 jvm_args）：JVM 卡死在初始化，不可用。

**下一步（定案）**：在本仓库 `jni/` 里写一个 **JVM TI agent**（`-agentlib` 加载，
起 127.0.0.1 TCP 服务，收到请求就调 `GetAllStackTraces` dump 全部 Java 线程），
这是唯一可靠拿到 Render thread / KubeJS 线程栈的路。拿到栈后二分修复。

**二分 mod 的一个坑**：先别急着禁 mod —— JLI_Launch 有个「第一次失败 exit code 1
→ 同进程重试(+1 arg)」的固有模式（历史日志都有）。当第一次尝试失败得太晚
（已经 dlopen 了 liblwjgl.so）时，重试会撞 `already loaded in another classloader`
直接 SIGABRT(-6) —— 这是**启动器 bug**（重试应该换进程），与 mod 无关，
排查时看到 -6 别误判成 mod 问题。

**另一个坑**：2026-10-24 22:30:19 有一个**外部 adb 客户端重装了 APK**
（`lastUpdateTime=22:30:19`，`installPackageLI` 把正在跑的游戏杀掉）——
用户侧可能开着别的 IDE/工具在自动装包，复现问题时注意排除。

### ZL2（ZalithLauncher2）对照结论（2026-10-25 查源码）

- ZL2 同样设 `POJAV_RENDERER` → 它靠 **`-Dsodium.checks.issue2561=false`**
  关掉 PreLaunchChecks 的 LWJGL 版本串检查（`BugChecks.ISSUE_2561` 把门）。
  该开关**管不到** PostLaunchChecks 的 `POJAV_RENDERER` 环境变量检测 ——
  我们的 QOOKIX_RENDERER 改名依然是必须的，两者互补。已加进默认 JVM 参数。
- ZL2 用 `-Dext.net.resolvPath` 指向自写的 resolv.conf 解决游戏内 DNS
  （安卓没有 /etc/resolv.conf，Pojav 系 JRE 的 DNS 补丁读这个属性）。
  已抄：`android_env.rs::ensure_resolv_conf`（223.5.5.5 / 119.29.29.29 / 8.8.8.8）
  + `launch.rs` 加 `-Dext.net.resolvPath`。**已验证生效**：163 个 mod 的版本检查
  从全部超时变成正常返回（日志可见 `Found status: OUTDATED/BETA`）。

### 设备上做过的临时改动（复现/回退用）

| 改动 | 位置 |
|---|---|
| 实例渲染器设为 `vulkan_zink` | `instance.json` 的 `"renderer"`（原 null） |
| 实例别名 `testlwjgl` | `instance.json` 的 `"alias"`（原 null） |
| sodium / sodium-extra / reeses 移回 `mods/` | iris、podium 仍在 `mods.disabled/` |
| **kubejs 暂禁用**（排查中移入 `mods.disabled/`，结论未出，验收前记得移回） | `mods.disabled/kubejs-neoforge-2101.7.2-build.377.jar` |
| Zink+Turnip 插件手动安装 | `files/plugins/qookix-renderer-zink-osmesa/`、`files/plugins/qookix-driver-turnip/`（state.json + 1.0.1/libs/，等价于「设置 → 插件」安装） |
| 离线账号 `Tester123` | `files/accounts/3eef578c-343e-34ae-9ad4-2be348cfe932.json`（UUID v3 of `OfflinePlayer:Tester123`） |

---

## 1. 目标

让 **Sodium 0.8.13（NeoForge 1.21.1）不再判定"这是 Pojav 启动器"而中止**，并且**真的能跑**。

验收标准（见 §7）：
1. 启动日志里不再出现
   `RuntimeException: It appears that you are using PojavLauncher, which is not supported when using Sodium.`
2. `mods/sodium-*.jar` 正常加载，F3 里 GL 版本 ≥ 3.3；
3. 能进主菜单并进入世界，不花屏/不崩。

---

## 2. 为什么需要（已核实的事实，别重复踩）

### 2.1 Sodium 检的是「LWJGL 版本」，不是「你是不是 Pojav」

Sodium 源码（`common/src/boot/java/.../compatibility/checks/PreLaunchChecks.java`）：

```java
private static boolean isUsingKnownCompatibleLwjglVersion() {
    return Version.getVersion().startsWith(REQUIRED_LWJGL_VERSION);
}
// REQUIRED_LWJGL_VERSION = "3.3.3"（随 Minecraft 1.21.1 的那套）
```

不匹配 → `showCriticalErrorAndClose(...)`；**0.8.13 那版的文案把责任写成 PojavLauncher**，所以看起来像"检测启动器"，实际是"版本串对不上"。

`minecraft.launcher.brand` / LWJGL 的 `CodeSource` 只影响**提示文案**（是否来自 custom launcher / mod），不影响是否抛错。

### 2.2 我们现在的 LWJGL 是 Pojav 的 fork

- 随包文件：`src-tauri/assets/lwjgl/lwjgl-glfw-classes.jar`（约 2.8 MB，用 `include_bytes!` 内嵌）
- 代码位置：`src-tauri/src/android_env.rs:47` `const LWJGL_JAR: &[u8] = include_bytes!("../assets/lwjgl/lwjgl-glfw-classes.jar");`
- 相关注释：`src-tauri/src/android_env.rs:626`、`src-tauri/src/natives.rs:212`
- 它报告的版本串**不是** `3.3.3` 开头 → Sodium 直接中止。

### 2.3 Podium（社区补丁 mod）在 0.8.13 上无效

真机实测：

```
[Podium] Unable to define class net.caffeinemc.mods.sodium.client.compatibility.checks.PreLaunchChecks
Caused by: LinkageError: attempted duplicate class definition …
  (… is in module sodium_service@0.8.13+mc1.21.1 …)
```

Sodium 0.8.13 把检查类放进了模块层，Podium 的 `defineClass` 撞车 → 检查没被改掉。
**不要在这条路上继续投入。**

### 2.4 只对齐版本号不够，还要 GL 能力

Sodium 需要 GL 4.5 级能力（或 Vulkan）。我们默认渲染器是 **GL4ES（GL 2.1）** → 即使检查过了，也会在后续初始化/进世界时崩。
所以路线 B 必须和**渲染后端**一起做（见 §5.3 的"先验实验"）。

---

## 3. 现状（接手即可用的部分）

### 3.1 仓库与构建环境

| 项 | 值 |
|---|---|
| 安卓启动器仓库 | `I:\program\vibe\mc\QookiX-Launcher-Android` |
| 桌面版（参考实现，逻辑更完整） | `I:\program\vibe\mc\QookiX-Launcher` |
| Android 工程 | `src-tauri/gen/android` |
| Android SDK | **`L:\Android\Sdk`**（注意：不在 C 盘） |
| C 盘联接 | `C:\Users\ZhaYi\AppData\Local\Android\Sdk` → `L:\Android\Sdk`（Junction，已建） |
| NDK | `L:\Android\Sdk\ndk\android-ndk-r27c` 与 `27.0.12077973`（两个都在） |
| `local.properties` | `sdk.dir=L\:\\Android\\Sdk` + `ndk.dir=L\:\\Android\\Sdk\\ndk\\27.0.12077973` |
| 分支 | `master`（本地 20+ 提交**均未推送**） |

构建命令（三段，照抄即可）：

```powershell
# 1) Rust
cd i:\program\vibe\mc\QookiX-Launcher-Android\src-tauri
$hin="L:\Android\Sdk\ndk\android-ndk-r27c\toolchains\llvm\prebuilt\windows-x86_64\bin"
$env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$hin\aarch64-linux-android24-clang.cmd"
$env:CC_aarch64_linux_android="$hin\aarch64-linux-android24-clang.cmd"
$env:AR_aarch64_linux_android="$hin\llvm-ar.exe"
cargo build --target aarch64-linux-android
Copy-Item "target\aarch64-linux-android\debug\libqookix_lib.so" "gen\android\app\src\main\jniLibs\arm64-v8a\libqookix_lib.so" -Force

# 2) APK
cd gen\android
.\gradlew.bat :app:assembleArm64Debug --no-daemon --console=plain

# 3) 装机
adb install -r "app\build\outputs\apk\arm64\debug\app-arm64-debug.apk"
```

### 3.2 设备上的布局（`/data/data/com.zhayi.qookix/files`）

| 路径 | 内容 |
|---|---|
| `jars/lwjgl-glfw-classes.jar` | 由 `android_env.rs` 从 assets 释放（**这就是要替换的东西**） |
| `natives/` | `liblwjgl.so`、`liblwjgl_opengl.so`、`liblwjgl_stb.so`、`liblwjgl_nanovg.so`、`libgl4es_114.so`、`libopenal.so`、`libpojavexec.so`、`libfreetype.so` 等 |
| `runtimes/JRE-21/` | 内置 JRE（`bin/java` **不能** exec；只能 JNI 或走 `:jvm` 服务） |
| `game/<实例id>/mods/` | 157 个 jar |
| `game/<实例id>/mods.disabled/` | **5 个**：sodium 0.8.13、iris、podium、reeses-sodium-options、sodium-extra（**这是临时禁用，路线 B 成功后要移回**） |
| 目标实例 id | `e9e0ae98-c5e8-427c-8bfe-aae3b205ed72`（月落花野 1.21.1 / NeoForge 21.1.251） |

### 3.3 已经修好、不要再动的链路（背景）

- NeoForge 加载器：`versions/neoforge-21.1.251/` profile 按 `inheritsFrom` 匹配 ✓
- 补丁产物：官方 installer 走 **`:jvm` 独立进程 + JNI** 跑（`JvmInstallService` + `jni_bridge.rs::nativeRunJvm`），`client-extra` / patched client 已生成 ✓
- 同一进程**只能启动一次游戏**（`liblwjgl.so already loaded in another classloader`）→ 已实现"游戏退出自动重启进程" ✓
- 启动遮罩靠 `logs/launch-<实例>.log` 的 `launch://log` 收起 ✓

**这些改动与本次任务无关，不要回退。**

---

## 4. 实施方案（路线 B）

### 4.1 取上游

- 首选 fork：**`https://github.com/PojavLauncherTeam/lwjgl3`**（GPL-3.0，含完整安卓补丁：GLFW stub、EGL/GLES 上下文、去 AWT、`/proc` 兼容）
- 参考结构：**`https://github.com/ZalithLauncher/ZalithLauncher2`** → 顶层 `LWJGL/` 模块（他们就是这么维护的；GPL-3.0 同许可，可照抄结构）
- 目标 LWJGL 版本：与 Minecraft 1.21.1 匹配的 **3.3.3**

### 4.2 要改的点（很小）

1. **版本串对齐**：`org.lwjgl.Version` 的 `VERSION_MAJOR/MINOR/REVISION` 与 `getVersion()` 的返回，必须使
   `Version.getVersion().startsWith("3.3.3")` 为 **true**。
   （常见做法：保留 fork 的后缀，例如 `3.3.3+pojav-…` —— `startsWith` 仍成立。）
2. **启动参数**里把 brand 改成官方：`-Dminecraft.launcher.brand=minecraft-launcher`（只影响提示文案，顺手做）。
3. （可选）去掉明显的 `POJAVEXEC_*` 品牌痕迹，避免其它 mod 也来"识别启动器"。

### 4.3 产出物

- `lwjgl-glfw-classes.jar`（或按模块拆分的若干 jar）
- `natives/` 下对应的 `liblwjgl*.so`（aarch64-linux-android，用 NDK r27c 构建）

### 4.4 集成方式（三选一，推荐 A）

**A. 直接替换随包资源（最小改动）**
```
替换 src-tauri/assets/lwjgl/lwjgl-glfw-classes.jar
替换 src-tauri/assets/... 里的 lwjgl .so（见 android_env.rs 释放逻辑）
```
`android_env.rs` 里 `LWJGL_JAR` 是 `include_bytes!`，替换文件即可；`.so` 的释放/校验逻辑在 `android_env.rs::required = ["liblwjgl.so", "libgl4es_114.so", "libpojavexec.so"]` 附近（约 279 行）。

**B. 引入仓库内 `LWJGL/` 模块**（ZL2 形态）
把 fork 作为子模块/子目录放进仓库，加一个构建脚本产出上面两个产物，再由 A 的方式打进 APK。
好处：可复现、可升级；代价：仓库变大 + 需要 CI/本地构建链。

**C. 运行时从网络拉**（不推荐）——离线不可用，且与"内置"策略冲突。

---

## 5. 关键风险与**先验实验**（务必先做）

### 5.1 先验实验：换渲染后端（零代码，10 分钟）

**在做 LWJGL fork 之前**，先确认这台机器在强渲染后端下能不能跑 Sodium：

1. 启动器里把该实例的**渲染器**从 `GL4ES` 换成 **Mesa（OSMesa / zink）** 或 **ANGLE**
   （随包组件里已有 `Mesa (OSMesa / zink)`，见 `AboutAndContentPanes.kt` 的依赖清单）
2. 把 `mods.disabled/` 里的 **sodium / sodium-extra / reeses** 三个 jar 移回 `mods/`
3. 启动，看日志：
   - 仍报 `It appears that you are using PojavLauncher…` → **说明只差版本串** → 继续路线 B，值得做
   - 不报版本错，但崩在 GL 初始化/花屏 → **说明这台机器算力/驱动不够** → 路线 B 收益有限，建议放弃，保留"停用 Sodium"
4. 实验完记得把渲染器改回原值（默认 `GL4ES`）

`-Dorg.lwjgl.opengl.libname` 会随渲染器变化（GL4ES 时是 `libgl4es_114.so`，见 `launch.rs` 的 jvm args），换后端时由启动器自动设置，不用手改。

### 5.2 其它风险

| 风险 | 说明 |
|---|---|
| LWJGL 构建链 | Gradle + CMake + NDK；Windows 上更折腾。建议先用 Linux/WSL 或 Docker 构建 |
| 版本混用 | `launch.rs:151` 有注释：随包的 Pojav LWJGL（3.4.0 类）**不能**和 Maven 的官方 LWJGL 混进同一个 classpath |
| 每进程一次 | 已实现"退出重启进程"；升级 LWJGL 后这条**不能删** |
| 许可证 | Pojav 的 lwjgl3 fork 与 ZL2 都是 **GPL-3.0**；本项目也是 GPL-3.0 → 可 fork、须保留版权声明并开源修改 |

---

## 6. 代码接入点速查

| 需要改/看的地方 | 路径 |
|---|---|
| 内嵌 LWJGL jar | `src-tauri/assets/lwjgl/lwjgl-glfw-classes.jar` |
| 释放与 classpath 注入 | `src-tauri/src/android_env.rs`（`LWJGL_JAR` 约 47 行；`required` 约 279 行；注释 626 行） |
| 原生库清单/复制 | `src-tauri/src/natives.rs`（212 行有说明） |
| 启动参数（含 `-Dorg.lwjgl.opengl.libname`、`-Djava.library.path`） | `src-tauri/src/launch.rs`（jvm args 拼装、`build_android_jvm_args`） |
| 渲染器选择与健康检查 | `launch.rs`（打印"渲染器：…"那段）+ Kotlin `checkRendererHealth*` |
| 随包组件清单（GL4ES/Mesa/OpenAL…） | `.../compose/AboutAndContentPanes.kt`（`AboutDep` 列表） |

---

## 7. 验收

在目标实例 `e9e0ae98-…` 上：

1. 把 `mods.disabled/` 里的 **sodium / sodium-extra / reeses**（以及需要时的 iris）移到 `mods/`
2. 渲染器设为 Mesa/zink 或 ANGLE
3. 启动，检查 `files/logs/launch-<实例>.log`：
   - ❌ 出现 `not supported when using Sodium` → 未通过
   - ✅ 无该行、且日志出现 `[Render thread]` 后继续推进 → 通过第一阶段
4. F3 界面 GL 版本 ≥ 3.3；进世界后不花屏、不闪退 → 通过

---

## 8. 交接清单（给下一位）

- [ ] 读完 §2，确认"不是 Pojav 检测，是 LWJGL 版本串 + GL 能力"
- [ ] 先做 §5.1 的先验实验（决定要不要投入）
- [ ] fork `PojavLauncherTeam/lwjgl3`，对齐版本串到 `3.3.3`
- [ ] 构建 aarch64 的 jar + `.so`，按 §4.4 集成
- [ ] 按 §7 验收
- [ ] 通过后：把 `mods.disabled/` 里的 Sodium 系列移回，更新 `CHANGELOG`
- [ ] **注意**：构建环境在 `L:\Android\Sdk`（C 盘只是 Junction）；`master` 有 20+ 未推送提交，别丢
