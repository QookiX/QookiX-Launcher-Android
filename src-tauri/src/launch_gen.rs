//! 启动代际：让「新一轮启动」能把旧的那条准备流水线踢下车（审计 B11）。
//!
//! 之前 `launch.rs::launch_game` 全程没有任何取消机制：Activity 重建后 Compose 侧
//! 的 `launchingId` 丢了、用户再点一次，就会出现**两条准备流水线并行**，
//! 一起往 `libraries` / `assets` 里写 —— 结果是进游戏崩、或者文件被写坏。
//! `kill_game()` 也管不到还在准备中的那条（它只杀已起来的 JVM 进程）。
//!
//! 这里用「代际号」而不是把 `CancellationToken` 串进 940 行的函数：
//! 每个启动流程领一个号，关键节点回来看号还是不是自己的，不是就自觉退出。

use std::sync::atomic::{AtomicU64, Ordering};

static GEN: AtomicU64 = AtomicU64::new(0);

/// 一次启动流程的凭证。
#[derive(Debug, Clone, Copy)]
pub struct LaunchGen(u64);

/// 领一个新代际（进入 `launch_game` 时调）。旧流程的号立刻失效。
pub fn begin() -> LaunchGen {
    LaunchGen(GEN.fetch_add(1, Ordering::SeqCst) + 1)
}

/// 关键节点自检：号变了说明有更新的启动请求、或者被 `kill_game` 取消了。
pub fn ensure(g: &LaunchGen) -> anyhow::Result<()> {
    if GEN.load(Ordering::SeqCst) != g.0 {
        anyhow::bail!("启动已取消：有更新的启动请求或游戏已被停止");
    }
    Ok(())
}

/// 取消当前启动（`kill_game` 里调），让还在准备阶段的流水线尽快退出。
pub fn cancel_current() {
    GEN.fetch_add(1, Ordering::SeqCst);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn newer_begin_invalidates_previous() {
        let old = begin();
        assert!(ensure(&old).is_ok());
        let new = begin();
        assert!(ensure(&old).is_err(), "旧代际必须失效");
        assert!(ensure(&new).is_ok());
    }

    #[test]
    fn cancel_invalidates_current() {
        let g = begin();
        cancel_current();
        assert!(ensure(&g).is_err());
    }
}
