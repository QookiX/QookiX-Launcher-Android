//! 进程级「状态已变」信号（A2 的第一步，背景见 `docs/adr/0002-single-source-of-truth.md`）。
//!
//! ## 这个模块**不**做什么
//!
//! 刻意不在这里建 `AppState` 大对象：实例 / 服务器 / 任务的权威数据仍在各自模块里
//! （`instances.rs` / `servers.rs` / `progress.rs`），本模块只负责一件事 ——
//! 某个领域被写过之后，告诉 UI「你手上那份过期了」。
//!
//! 这样做的原因（避免重复造轮子）：`list_instances`、`list_hosted_servers` 等命令
//! 已经是**唯一**的数据出口，UI 收到 dirty 后重拉这些既有命令即可；
//! 如果在 Rust 侧再攒一份聚合快照，就要维护两套序列化、两份字段映射。
//!
//! ## 事件契约
//!
//! `state://dirty` → `{ "scope": "instances" | "servers" | "tasks" | "all", "revision": N }`
//!
//! - `revision` 单调递增，便于 UI 排查「到底有没有收到通知」
//! - Kotlin 侧只有一个订阅者（`CoreState`），不允许各页面各自 listen
//!   —— 每模块一个回调是评审里明确点名的坑。

use serde_json::json;
use std::sync::atomic::{AtomicU64, Ordering};

/// 全局修订号：每次 `mark_dirty` 自增。
static REVISION: AtomicU64 = AtomicU64::new(0);

/// 当前修订号（0 = 进程启动后还没有任何写操作）。
pub fn revision() -> u64 {
    REVISION.load(Ordering::Relaxed)
}

/// 标记某个领域已变更，并把 `state://dirty` 推给 Kotlin。
pub fn mark_dirty(scope: &str) {
    let rev = REVISION.fetch_add(1, Ordering::Relaxed) + 1;
    crate::progress::push(
        "state://dirty",
        json!({ "scope": scope, "revision": rev }),
    );
}

/// 实例集合或其元数据变了（创建 / 删除 / 改名 / 换图标 / 导入整合包）。
pub fn mark_instances_dirty() {
    mark_dirty("instances");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn revision_increases_monotonically() {
        let a = revision();
        mark_dirty("test");
        let b = revision();
        assert!(b > a, "mark_dirty 必须让 revision 增长（{a} → {b}）");
    }
}
