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
| **Q2** | 巨文件 / lint 闸门 | P1 | 🟡 | 体量棘轮 ✅（`7e9cbad`，>600 行只许变小）；**拆巨文件未做**；detekt/clippy 未接 |
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
| 打包脚本重写（去掉 Tauri 时代步骤与写死路径） | ✅ | `scripts/build-android.mjs` + `npm run android` |
| `.gitignore` 放行新增公共文档 | ✅ | `/docs/*` + 放行 `adr/`、`pojav-ingame-port.md` |

---

## 分步记录（最新在上）

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
