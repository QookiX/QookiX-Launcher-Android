# 0002. 状态单一真相源改造

- 状态：**进行中**（2026-10-25）
  - 切片 1 已落地：Rust `app_state::mark_dirty` → `state://dirty` 事件；Kotlin `CoreState`
    持有实例列表的 `StateFlow`，`PageHost` 改为只读 + 事件驱动重拉
  - 切片 2 已落地（错误契约，**未给 155 个命令改签名**）：Rust 在信封层统一给出
    `{code, retryable}`（`commands::classify_error`，网络/鉴权/找不到/读写/解析/未知），
    Kotlin `ApiError` + `friendlyError(context, throwable)` 按 `code` 出稳定文案。
    理由：逐个改 `Result<T, AppError>` 是周级改动，而 UI 真正需要的是「能区分原因」。
  - 待做：写操作状态机（`Idle → Pending → Success | Failure`，禁止先改 UI 再请求）、
    其余领域（服务器 / 任务）迁移到 `CoreState`

## 背景与现状

同一份状态存在三个副本，靠事件与「重新拉一次」同步，没有权威版本：

| 副本 | 位置 | 写入方式 |
|---|---|---|
| Rust 内存 | `settings` / `instances` / `progress` 等模块的 `RwLock` / `Mutex` / 静态表 | 命令内部 |
| 磁盘 | `settings.json` / `instance.json` / `servers.json` | 写回时 |
| Compose | `PageHost` 等处的 `remember { mutableStateOf(...) }` | **乐观更新**（先改 UI 再发请求） |

已发生的后果（都是同一根因的不同发作）：

- 「模组删除后需要切走再切回才生效」——UI 改了但列表没重新拉；
- 「切换游戏目录后设置页不刷新」——磁盘变了但 Compose 那份没变；
- 「服务端停止后仍显示正在启动」——事件丢了，两边的状态就永久分叉；
- 「失败仍提示成功」——乐观更新先落 UI，失败分支没回滚（部分已逐个修掉）。

## 提案

1. **Rust 侧**：一个 `AppState`（`Arc<RwLock<…>>`），所有写操作走
   `mutate(|s| -> AppStateDiff)`；磁盘落盘在 `mutate` 内部完成。
2. **传输**：写操作返回 `AppStateDiff`，事件通道只推 diff（不推全量）。
3. **Kotlin 侧**：一个 `StateFlow<AppSnapshot>` 镜像，UI 只读；
   **禁止乐观更新**，按钮的 `Pending` 状态由 diff 回流驱动。
4. 错误契约一并做：Rust `AppError`（`code/message/retryable`）+ Kotlin `ApiResult` 密封类，
   UI 只消费 `Success` / `Failure(code, retryable)`。

## 为什么不马上做

- 需要改动 155 个命令的签名与 UI 的每一条写路径，**单次做完的风险远大于收益**：
  这类改造一旦中断，会留下「一半读 diff、一半乐观更新」的最坏状态。
- 当前已用较低成本堵住了后果最重的部分：严格读 + 原子写（settings / instance.json /
  分组 / server.properties），损坏时**报错并保留原文件**，不再静默清空。

## 增量路径（建议顺序）

1. 先选**一个**领域试点（建议「实例列表 + 图标/名称」，它已有 `instancesReloadKey` 这种
   手工刷新机制，最容易看出改造成果）；
2. 试点里跑通 `mutate → diff → StateFlow`，其它页面维持现状；
3. 再逐个领域迁移，每迁一个就删掉该领域的「重新拉一次」调用；
4. 最后收错误契约（与 3 并行也可以，它不依赖 diff）。

## 风险

- diff 设计错误会引入新的事件泄漏 → **全局只能有一个** `onDiff`，禁止每模块各开回调。
- 迁移期间若同时加功能，回归无法定位 → 迁移 PR 不带功能改动。

## 什么条件下重新考虑优先级

- 如果「切走再切回才生效」类反馈再次出现 3 次以上，说明现状的维护成本已经超过改造风险；
- 如果要引入第二个外部贡献者，先做完第 1 步试点再谈分工（否则新人会在三个副本之间迷路）。
