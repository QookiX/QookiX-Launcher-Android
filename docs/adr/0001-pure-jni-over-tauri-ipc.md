# 0001. 用纯 JNI 替换 Tauri IPC + 隐藏 WebView

- 状态：已采纳（2026-10-25）
- 背景：见本文「问题」一节的实测故障

## 问题

界面在 1.7.0 换成 Jetpack Compose 之后，Rust 仍然挂在 Tauri 运行时上，于是形成一条
**必须先加载 HTML 才能通**的链路：

```
Compose 点击 → TauriBridge（JS 注入）→ 隐藏 WebView 里的 index.html → wry IPC → Rust
```

它带来三类只在真机上出现的故障：

1. **冷启动点了没反应**：`index.html` 还没加载完，`window.__TAURI_INTERNALS__` 不存在，
   命令全部静默失败；
2. **进程被系统回收后整条链断掉**：WebView 的渲染进程一挂，UI 还在但所有命令无响应；
3. **进游戏崩溃**：游戏与 WebView 同进程，游戏重建 `Surface` 时 WebView 持有的
   `AHardwareBuffer` 失效，`AHardwareBuffer_getNativeHandle` 解引用 NULL → SIGSEGV
   （tombstone 里崩在 Render thread）。

第 3 条曾经用「启动游戏前把 WebView 移出渲染管线」的补丁绕过（反射翻
`ActivityThread.mActivities`），属于典型的「用补丁掩盖架构问题」。

## 决定

删除 Tauri 运行时与 WebView，改成 **`cdylib` + 一处 JNI 入口**：

- 命令：`TauriBridge.nativeInvoke(cmd, payload) -> {"ok":true,"data":…}`，
  Rust 侧由 `commands::native_invoke` 用一条 `match cmd` 分发（155 个命令函数）；
- 事件：Rust `progress::push` → JNI 静态方法 `TauriBridge.onCoreEvent` → Kotlin 侧
  `RustBridge` 的静态订阅表；
- 构建：Gradle 不再包含 `:tauri-android` / 插件子工程，`settings.gradle` 只有 `:app`。

类名 `TauriBridge` 保留（改名会牵动几十个调用点），但里面已经没有任何 Tauri 代码。

## 备选方案与否决理由

- **uniffi**（一份 IDL 生成 Kotlin 绑定）：接口更干净、有类型安全，但要重写 155 个命令的
  导出层，且 `uniffi` 的 codegen 会给 CI 增加一步。**在「先止血」的目标下成本不划算** ——
  纯 JNI 只换了一层传输，业务函数一行没改。
- **保留 Tauri、只把 index.html 内联进 APK 加速加载**：治不了第 2、3 条（进程回收、同进程
  渲染冲突），只是让故障概率变低。

## 后果

- 好了：命令不再依赖 WebView 生命周期；进游戏前那段「摘 WebView」的反射补丁可以彻底删掉；
  冷启动少了 WebView 初始化（首屏更快）；`grep tauri` 在 Rust 侧只剩注释。
- 付出：**失去 IPC 层的脚手架**。命令表是手工维护的 `match`，
  新增命令要同时改 Rust 分发与 Kotlin 包装，没有编译期校验两边一致性
  （uniffi 本来能解决这一点，这笔账还挂着）。
- 付出：事件从「按 webview 分发」变成「进程级静态表」，要多写一点线程切换
  （`deliverEvent` 里统一切主线程）。

## 什么条件下重新考虑

- 命令数继续涨到需要强类型契约时（例如要出桌面端复用同一份绑定）→ 回到 uniffi 方案，
  届时有 `native_invoke` 的 `match` 表可以直接当迁移清单逐条核对。
