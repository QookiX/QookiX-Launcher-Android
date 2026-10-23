# 交接：自维护 LWJGL 安卓移植模块（路线 B）

> 目标读者：接管这块工作的另一个 AI / 开发者。
> 这份文档只讲**这一件事**（把随包的 Pojav 版 LWJGL 换成「版本串与官方一致」的自维护移植），
> 以及与它直接相关的现状。其它已完成的工作只在"背景"里提一句。

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
