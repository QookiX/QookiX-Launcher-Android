# 改造进度台账

> 对照文档：`I:\Download\QookiX-Android-架构评审与改造方案.md`（基线 v1.8.0 / 10-22）
>
> **用法**：每完成一步，在下面「分步记录」**最上面**加一条，并更新「总览」里对应状态。
> 想快速看「还剩什么」，直接跳到 [还没做的](#还没做的)。
>
> 图例：✅ 完成 ｜ 🟡 部分完成 ｜ ⬜ 未开始 ｜ ⏸ 已写代码、等真机验证

最后更新：2026-10-25 ｜ 最新提交：`9a9e902` ｜ 未推送提交：11 个（`wip/a3-done` 起）

---

## 总览（对照评审 14 项）

| 编号 | 项目 | 优先级 | 状态 | 证据 / 剩余 |
|---|---|---|---|---|
| **A1** | 隐藏 WebView 单点 | P0 | ✅ | 纯 JNI 命令通道；无 `tauri` 依赖、无 WebView 创建、APK 内无 `index.html`；残留清理见 `fb7f54d` |
| **S1** | 自更新签名校验 | P0 | ✅ | `installApk` 先比对签名证书 SHA-256，不一致删包并返回原因；`9a96730` |
| **S2** | 硬编码 CurseForge Key | P0 | ✅ | 常量已删；未配置时返回「去设置填 Key 或改用 Modrinth」；`9a96730` |
| **Q1** | 零测试 / CI 不跑测试 | P0 | ✅ | Rust 63 个单测进 CI；Kotlin 10 个单测真跑；冷启动冒烟测试（CI 只编译）；`b28d9ec` `7e0b289` |
| **A2** | 状态单一真相源 | P0 | ✅ | 三切片：脏事件 + `CoreState`（`a02425f`）、错误契约（`c87a559`）、写状态机 + 服务器/任务域（`a5e4476`）；边界写进 ADR-0002 |
| **A3** | 核心代码在 `gen/android` + 双 app 模块 | P1 | ✅ | 迁到根 `android/`，删掉根目录死工程；`9a9e902` |
| **S3** | 16KB 页对齐 | P1 | 🟡 | 自建 4 个库已 0x4000 + CI 校验 PT_LOAD（`b28d9ec`）；**上游 10 个预编译 .so 仍 4KB**（要重建上游） |
| **Q2** | 巨文件 / lint 闸门 | P1 | 🟡 | 体量棘轮 ✅（`7e9cbad`，>600 行只许变小）；**拆分进行中**：`QookixApi` 2337→**574**（11 个域已拆完，已进健康线）、`PageHost` 5726→5260；detekt/clippy 未接 |
| **Q3** | 吞异常 / 错误契约 | P1 | 🟡 | 统一 `code`/`retryable` + `ApiError` ✅（`c87a559`）；**`let _ =` 类吞错点未清** |
| **A4** | Rust 跨端复用 | P2 | ✅ | 随 A1 完成（tauri 依赖已删）；本仓库不再有桌面壳 |
| **Q4** | 发布节奏 | P2 | ⬜ | 需流程约定（RC 分支 / soak / 双通道），不是代码 |
| **D1** | 文档失效引用 + 本地路径 | P2 | ✅ | `e222462`（`I:\...` 已删、死引用改指 KNOWN_ISSUES、文档移到 `docs/`） |
| **D2** | KNOWN_ISSUES / issue 模板 / Beta | P2 | ✅ | `e222462`；兼容性矩阵只有骨架 🟡（缺真机数据） |
| **D3** | bus factor / ADR | P2 | ✅ | `docs/adr/` 目录 + 0001（纯 JNI 决策）+ 0002（状态改造，进行中→已落地） |

**附加项（评审没列，但这轮补的）**

| 项 | 状态 | 证据 |
|---|---|---|
| 文件体量棘轮（可执行的 Q2 替代） | ✅ | `scripts/check-file-size.mjs` + baseline，245 文件 |
| CI 16KB 对齐校验（不能只靠本地 config） | ✅ | `release.yml`「校验 16KB 页对齐」 |
| 打包脚本重写（去掉 Tauri 时代步骤与写死路径） | ✅ | `scripts/build-android.mjs` + `npm run android`；**端到端验证**：cargo 20:36:16 → jniLibs 20:36:16 → APK 20:37:16 |
| `.gitignore` 放行新增公共文档 | ✅ | `/docs/*` + 放行 `adr/`、`pojav-ingame-port.md` |

---

## 分步记录（最新在上）

### 2026-10-27 · PageHost 第二刀：新闻/占位/重命名/文件浏览
- 4 个自成一体的小页面搬到 SimpleRoutes.kt：NewsRoute(27)、PlaceholderPage(31)、RenameDialog(42)、FileBrowserRoute(77)
- PageHost.kt **5260 → 5083**（-177）；累计 5726 → 5083（-643）
- 这回脚本按**函数名 + 括号计数**定位，一次编译通过 —— 上一刀靠 grep 行号切片的教训生效了
- 验证：compileArm64ReleaseKotlin / ssembleArm64Release / 单测 10 / 闸门 278 文件 0 超预算 ✅
- commit 待补

### 2026-10-27 · Q2 转向 PageHost：拆出 FRP 两页
- `PageHost.kt` **5726 → 5260 行**（-466）；新文件 `FrpRoutes.kt`（647 行）
- 搬走 `FrpRoute`（隧道页）+ `FrpRoomsRoute`（房间页）：两者只依赖 `bridge` 与入参、各自持有 `FrpUiState` / `FrpRoomsUiState`，与主机无共享变量 → 纯搬运，`private` → `internal`
- **两次踩同一个坑（都记下来）**：
  1. 块边界按 grep 的旧行号切 → 把下一个函数的 KDoc 一起搬走，报错却是 `Unresolved reference 'FileTarget'`（级联误导）。改用**括号计数**定位函数结束
  2. 替换串里的 `$1` 被 PowerShell 吃掉 → 函数名整个丢失（`internal fun (`）。这是第二次踩，**以后 node -e 里的替换串一律字符串拼接，不用 `$1`**
- 验证：`compileArm64ReleaseKotlin` ✅、`assembleArm64Release` ✅、单测 10 ✅、闸门 277 文件 0 超预算
- commit `待补`

### 2026-10-25 · `commands.rs` 全量按域拆分（199 个命令 → 12 个文件）
- `commands.rs` **3130 → 1365**（-56%）；`native_invoke` 的 199 个 arm 全部迁出
- 12 个域文件（全部 < 400 行）：instance 200、account 192、content 372、controls 138、files 281、frp 172、plugin 118、server 275、settings 203、task 180、terracotta 51、world 159
- 文件统一命名 `dispatch_<域>.rs`（**踩坑**：一开始叫 `settings.rs`/`frp.rs`，与 `crate::settings`/`crate::frp` 撞名，E0255）
- 三个迁移后遗症（都靠编译器发现）：`payload` 变量在子模块里不存在（改用 `p.to_string()`）、原 `other => err(未实现)` 兜底被搬进 task 域、主 match 因此缺 `_` 分支
- 验证：`cargo test --lib` 63 ✅、`cargo check --target aarch64-linux-android` ✅、闸门 ✅（预算收到 1365）
- commit `待补`

### 2026-10-25 · Q2 转向 Rust：`commands.rs` 实例域拆出
- `commands.rs` **3131 → 2987**：`native_invoke` 里 13 个实例相关 arm 迁到 `commands/instance.rs`（200 行）
- 拆法（后续每域复用）：每域一个文件，导出 `HANDLED`（命令名清单）+ `dispatch(cmd, p) -> Option<String>`；`dispatch` 只路由，命中后进 `run(cmd, p) -> String`
  - **两段式的理由**：arm 里有大量 `return err(...)` 早退，`run` 返回 `String` 时语义不变；若直接写在返回 `Option<String>` 的函数里，每个早退都得包 `Some(...)`
- `ok` / `err` 从 `native_invoke` 内部提升为模块级 `pub(crate)`，供子模块复用
- 机械改写只有两处：去掉一级缩进、`&p` → `p`（子模块参数已是 `&Value`）
- 验证：`cargo test --lib` 63 ✅、`cargo check --target aarch64-linux-android` ✅、闸门 ✅
- commit `待补`

### 2026-10-25 · 版本号重置 0.0.1（重开序列）
- `versionCode` 10800 → **199**，`versionName` → `0.0.1`，三处文件同步
- 编码规则（`scripts/update-version.mjs`）：`AA BB CC DD` = major|minor|patch|通道号段
  - 01–19 alpha / 20–49 beta / **50–98 rc** / **99 正式版**
  - 正式版占 99 是必需的：否则 rc 的 code 比正式版大，用户装完 rc 就升不上正式版
- 两条护栏：默认禁止 versionCode 变小（确实要重开序列用 `--reset`）；`--dry` 只预览
- ⚠️ 老用户（1.8.0）**无法覆盖安装**，必须先卸载（当前只有 2 个用户，已确认接受）
- commit `2edd723`（号段细节随后一次提交）

### 2026-10-25 · 交接文档（0.2.0 发版）
- 交接文档：`docs/handoff-2026-10-25-release-0.2.0.md`（本地文档，`docs/` 不入库）
  —— 含现状、30+ 提交对照、真机验证结果、发版命令、玩家说明模板、回滚点、踩坑清单
- 本轮真机最重要的发现：**「所有文件访问」权限实际没生效**（系统设置显示「已允许」，
  但 app 读写 `/sdcard/QookiX` 被拒：`Operation not permitted`；`appops set` 无效，
  要在手机系统设置里手动开）→ 玩家反馈的「实例没了」很可能是这个原因、不是卸载
- 新增仪器化测试 `GameDirRecoveryTest`：把「指回外部目录 → 实例回来」变成可执行的回归
  （`set_game_dir` + `list_instances` 断言，migrate=false 不搬数据）
- 未推送提交 53 个；发版步骤见交接文档
- commit `cb3e8d7`

### 2026-10-26 · 交接文档（0.2.1-beta 线）
- 交接文档：`docs/handoff-2026-10-26-beta-handoff.md`（本地文档，`docs/` 不入库）
  —— 含现状、17 个提交对照、真机验证记录、发版命令、回滚点、10 条踩坑清单
- **本轮最重要的发现（比 UI 修复都严重）**：**CI 发出去的 APK 在 Android 15/16 上
  整个 App 不可用**。真机（OnePlus 8 / Android 16 / SDK 36）装机后所有命令失效，
  Kotlin 侧只看到 `No implementation found for nativeInvoke`；logcat 里真凶是
  `dlopen failed: cannot locate symbol "ANativeWindow_fromSurface"`
  —— 根因是 `-landroid` 等链接参数只写在**本机** `src-tauri/.cargo/config.toml` 里，
  而那个文件被 gitignore，CI 从来没用上（works on my machine）
  → 修复 `28cddb1`：新增 `src-tauri/build.rs` 把链接参数写进仓库
  （**beta1 / beta2 都带这个缺陷，下一版必须带上修复**）
- 0.2.0 → HEAD 共 17 个提交：12 条高危 UI 修复（列表/实例/设置/编辑器/渲染器/桥层）
  + 实例列表与游玩统计的「读失败可见」+ 全局轻提示（`QkFeedback`）
- 体量：`servers.rs` 2438→2288、`PageHost.kt` 5866→5735、`launch.rs` 2055→2030
- 真机已验：修复版 native 加载成功、服务器空态与实例空态文案正确
- 真机未验：旧实例找回（`/sdcard/QookiX/instances` 里 4 个实例还在）、
  16KB 页对齐（第三方 lwjgl/SDL3/openal 未对齐）、首次运行插件安装静默失败
- 顺带修正上一条记录：在这台 OnePlus 8 上 `adb shell appops set <pkg>
  MANAGE_EXTERNAL_STORAGE allow` **是生效的**（返回 allow），
  「appops 无效」可能是那台 ColorOS 机器的特性而非普遍现象
- commit `28cddb1`

### 2026-10-25 · Q2 收尾：删白名单分叉（**发现并修了 A2 的真 bug**）
- `QookixApi.kt` **755 → 507**（累计 2337 → 507，**-78%**），进入 600 行健康线
- 删 `NATIVE_COMMANDS`（214 行白名单）+ `call()` 里的双路径分叉
- **顺带修了 A2 的核心 bug**：`ApiError`（结构化 code/retryable）我加在 `RustBridge.invoke` 里，但 `call()` 的白名单分支自己起线程、自己抛 `IllegalStateException` —— 白名单覆盖的绝大多数命令**根本拿不到结构化错误**，错误契约在实际路径上形同虚设
- 现在两条路合并为一条（bridge 也是纯 JNI + 后台线程 + 主线程回调），`call` 只剩一行委托
- 影响面：所有命令的错误从 `IllegalStateException` 变成 `ApiError`（Exception 子类，`message` 语义不变；已 grep 确认无调用点依赖旧类型）
- 验证：`assembleArm64Release` ✅、单测 10 ✅、仪器化编译 ✅、闸门 ✅
- commit `待补`

### 2026-10-25 · Q2 第四、五刀：内容 / 实例操作 / 系统能力
- `QookixApiContent.kt`（202）：内容中心 + 内容/翻译
- `QookixApiInstanceOps.kt`（194）：文件管理 + 实例内操作
- `QookixApiSystem.kt`（98）：屏幕方向 + 杂项 + 应用自更新
- 陶瓦那节（含「Android 上的正确模型」97 行）并入 `QookixApiMultiplayer.kt`
- `QookixApi.kt` **1311 → 755**（累计 2337 → 755，**-1582 / -68%**）
- 踩坑：分节的「最后一节」区间按 `L.length-1` 算，把类结束 `}` 和尾部 `TerracottaState`/`friendlyError` 一起切走了（编译报 `Missing '}'` + `Unresolved reference 'R'`）。修法：以第一个顶层 `}` 为界，把尾部声明迁回。**这类脚本必须先断言「切走的块里没有类结束符」**
- 验证：`assembleArm64Release` ✅、单测 10 ✅、闸门 ✅
- commit `待补`

### 2026-10-25 · Q2 第三刀：服务器域 + 联机域
- 迁 6 个分节、415 行：`QookixApiServer.kt`（服务器详情 100 + 本地服务器 58 + 服务器插件 102）、`QookixApiMultiplayer.kt`（陶瓦 11 + FRP 房间 99 + FRP 隧道 38）
- `QookixApi.kt` **1724 → 1311**（累计 2337 → 1311，**-1026 / -44%**）
- 方法改进：不再手算行号，改成**按分节注释自动切分**（解析 `// ── xxx ───` 边界 + 关键词选块），以后每刀只改关键词
- 踩坑：「服务器插件」分节标题没有括号说明，用带括号的关键词匹配不到 → 静默漏了 101 行；补迁后新文件缺 `JSONArray` import（编译器指出）
- 验证：`assembleArm64Release` ✅、单测 10 ✅、闸门 ✅
- commit `待补`

### 2026-10-25 · Q2 第二刀：实例详情 tab + 设置页第二组
- 再迁两个域：`QookixApiInstanceTabs.kt`（303 行）、`QookixApiSettings.kt`（194 行）
- **关键决策：调用方改用包级 star import**（`import ...bridge.*`）—— 跨包 extension 必须逐个 import，而 `PageHost` 里上百处 `api.xxx()`；一次性换成 star 后，后续每拆一个域都不用再碰调用方
- 顺带把 16 个文件里冗余的具体 bridge import 收敛进 star（净减约 20 行）
- `QookixApi.kt` **2192 → 1724 行**（累计 2337 → 1724，-613）；`PageHost` 也顺带 -7
- 踩坑：在 PowerShell 双引号里跑 `node -e` 做 `replace`，替换串里的 `$1` 被 shell 吃掉，把两个新文件的 `package` 行删了 —— 报错是「imports are only allowed in the beginning of file」。以后替换串一律用 node 字符串拼接，不走 shell 插值
- 验证：`assembleArm64Release` ✅、单测 10 ✅、仪器化编译 ✅、闸门 ✅
- commit `待补`

### 2026-10-25 · Q2 第一刀：拆 `QookixApi` 的皮肤域
- 皮肤/披风 10 个方法迁到 `QookixApiSkin.kt`（extension 函数，调用点不用改）
- `QookixApi.kt` **2337 → 2192 行**（-145）；新文件 168 行（健康线内，不受棘轮约束）
- 代价：`call`/`callObj`/`callArr` 从 private 放宽到 internal；跨包调用点要补 import（`SkinRepo.kt` +7、`PageHost.kt` +4）
- 验证：`compileArm64ReleaseKotlin` ✅、体量闸门 ✅（PageHost +4 已显式记账，QookixApi 收紧到 2192）
- commit `faf561e`

### 2026-10-25 · 出包链路验证（真机验证的前置）
- `node scripts/build-android.mjs arm64` 端到端跑通：cargo 编出 160MB debug `.so` → 拷进 jniLibs → gradle 出 APK（114MB），三个产物时间戳链一致（20:36:16 → 20:36:16 → 20:37:16）
- 顺手清掉两处误导：`BuildTask` 那句 `use 'cargo tauri android dev'`（该命令已不存在）改成说明 Rust 由脚本/CI 负责；删掉没人读的 `org.gradle.project.tauriSkipRustBuild` 属性
- **产出**：`android/app/build/outputs/apk/arm64/debug/app-arm64-debug.apk` 已含 A2/S1/S2 全部新代码，可直接装真机
- commit `8d271d8`

### 2026-10-25 · A3 工程转正
- 删掉根目录那套独立且已死的 Gradle 工程（`app/` 8 个 .kt 是旧 stub，无人引用）
- `src-tauri/gen/android` → 根 `android/`；同步改 12 处引用（CI/gitignore/README/文档/脚本/`rootDirRel`）
- 重写 `scripts/build-android.mjs`（cargo → 拷 .so → gradle，路径从 `ANDROID_HOME` 推导）
- 验证：`assembleArm64Release` ✅、`testArm64DebugUnitTest` 10 passed、`assembleArm64DebugAndroidTest` ✅、`cargo test --lib` 63 passed、体量闸门 ✅；APK 内 `libqookix_lib.so` 在、`index.html` 不在
- commit `9a9e902`

### 2026-10-25 · A2 切片 3（写状态机 + 服务器/任务域）
- `CoreState.beginWrite/endWrite` + `pending`：防重复提交、卡片转圈
- 实例删除**去掉最后一处乐观更新**（原 `removeLocally`），失败给错误条
- `CoreState.refresh()` 串行化（并发拉列表会乱序覆盖）
- 服务器域：Rust 推 `servers` 脏事件，多人页按 `serversRevision` 重拉；删掉安装对话框里重复的服务器 JSON 解析
- 任务域：`TasksRepo.state`（StateFlow），`onChange` 降为兼容参数
- 验证：`cargo test --lib` 63、`testArm64DebugUnitTest` 10、`assembleArm64Release` ✅
- commit `a5e4476`（例外记录 `3b9acde`）

### 2026-10-25 · 闸门规则修正
- 体量棘轮加健康线：**≤600 行不设棘轮**，只对巨文件逐行记账
- commit `7e9cbad`

### 2026-10-25 · A2 切片 2（错误契约）
- Rust 新增 `error_code.rs`：`classify_error`（网络/鉴权/找不到/读写/解析/未知）+ 失败信封加 `code`/`retryable`
- Kotlin 新增 `ApiError` + `Errors.kt`；`friendlyError(ctx, throwable)` 按 `code` 出文案
- 30+ 处调用点从传 `e.message` 改为传异常
- 验证：Rust 63（+7 用例）、Kotlin 10（+5 用例）
- commit `c87a559`

### 2026-10-25 · A2 切片 1（单一状态源）
- Rust `app_state::mark_dirty` → `state://dirty {scope, revision}`（create/delete/update/import 四处打点）
- Kotlin `CoreState`（实例列表唯一 `StateFlow`）；`PageHost` 改只读 + 事件驱动重拉
- 顺带：安装失败的提示冒泡（原空回调）、`gl_brand` 加 android cfg
- commit `a02425f`

### 2026-10-25 · 测试与 CI 闸门
- `gate` job：`cargo test --lib` + 体量闸门，不过不打包
- `build` job：Kotlin 单测真跑、仪器化测试只编译、16KB 对齐校验
- 修好宿主编译（16 处 Android-only 代码缺 cfg）+ 一个**过期测试**（`loader_install` 断言写于追加 `${primary_jar_name}` 之前）
- 16KB：`Android.mk` 给自己编的三个库加 `max-page-size=16384`，实测 0x4000
- 纳入此前未跟踪的 `jni/jvmti.h`、`jni/thread_dump_agent.c`
- commit `b28d9ec`

### 2026-10-25 · 测试源码
- `app/src/test`：`FmtTest`（5）；`app/src/androidTest`：`LauncherSmokeTest`（冷启动 + JNI 通道 + 信封校验）
- commit `7e0b289`

### 2026-10-25 · P0 安全两项
- S1：签名校验（`PackageInfo.signatures` / `signingInfo`，兼容 API 24-27），签名不符删包
- S2：移除硬编码 CurseForge Key，改可读错误
- commit `9a96730`

### 2026-10-25 · 文档补齐
- `KNOWN_ISSUES.md`（16KB 逐库实测表 / CurseForge Key / 陶瓦+VPN / 渲染器白屏 / ROM 省电）
- issue 模板 + `config.yml`；ADR 目录 + 0001/0002；NOTICES 修正；README Beta 声明
- commit `e222462`

### 2026-10-25 · A1 残留清理
- 删 `bootstrap-public/`、`gen/schemas/`、`build-web-assets.mjs`、`proguard-tauri.pro`
- `GameActivity` 删掉反射找 WebView 的死代码（81 行）
- README 技术栈改为「Rust 静态库 + 纯 JNI」
- commit `fb7f54d`

---

## 还没做的

### P0
无。（A2 的**真机验证**仍挂着，见下。）

### P1
| 项 | 剩余 | 备注 |
|---|---|---|
| **Q2 拆巨文件** | `PageHost` 5869、`commands.rs` 3130、`browse.rs` 2973、`servers.rs` 2438、`QookixApi` 2337、`launch.rs` 2046 行 | 已有棘轮保护（只许变小），拆分按模块逐步做 |
| **S3 上游 .so** | 10 个仍是 4KB：`gl4es_114`、`SDL3`、`lwjgl×5`、`openal`、`freetype`、`spirv-cross` | 需要重建上游；本仓库只保证自建库 |
| **Q3 尾巴** | 清 `let _ =` 等吞错点；写操作 Pending 的 UI 覆盖（目前只接了实例删除） | 改名/换图标/分组仍无转圈 |
| **detekt / clippy** | 未接（现只有体量棘轮） | 接 baseline 约 1 天 |

### P2
- **Q4 发布节奏**（RC 分支 / soak / 双通道）
- **兼容性矩阵**只有表头，缺真机数据
- **卫生项**：`ComposeMainActivity` 里 WebView 死代码（`tauriWebView` 恒 null，但挂在触摸分发上）、`Skin3dHost.kt` 整类可删、用户 CurseForge Key 明文存 `settings.json`（建议 Keystore 加密）、`libmobileglues.so.bak-unpatched`（6.1MB，不打进 APK）

---

## 需要真机验证的（代码已就绪，未跑）

出包前注意：`tauriSkipRustBuild=true`，本地产物需要先
`node scripts/build-android.mjs arm64 --install`（会自动编 Rust 并拷 `.so`）。

- [ ] **A2**：删实例 → 卡片转圈 → 列表自动消失；断网删 → 顶部红字
- [ ] **A2**：新建/删除服务器、启停 → 退回多人页列表自己变（不用退出重进）
- [ ] **A2**：下载页与首页任务进度同时更新
- [ ] **S1**：把 APK 换成不同签名的包（或改包名重签）→ 安装时被拒且提示原因
- [ ] **S2**：CurseForge 源未配 Key → 提示去设置填 / 改 Modrinth
- [ ] **Q3**：断网点搜索/检查更新 → 显示「网络连接失败」而非英文原文
- [ ] **S3**：16KB 页设备（若有）→ 渲染器加载不报 ELF alignment 错

---

## 回滚点

| tag | 含义 |
|---|---|
| `wip/p0-a2-slice1` | A2 切片 1 完成（实例列表单一状态源） |
| `wip/p0-a2-slice2` | A2 切片 2 完成（错误契约） |
| `wip/p0-a2-done` | A2 全部落地 |
| `wip/pre-a3` | A3 动手前（可整体回退目录迁移） |
| `wip/a3-done` | A3 完成 |

回滚单步用 `git revert <sha>`；整批回到 A3 之前用 `git reset --hard wip/pre-a3`。
