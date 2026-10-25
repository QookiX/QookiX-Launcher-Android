//! 游玩时长统计：实例 `total_play_time` 累计总时长，`playtime.json`
//! 按天（东八区）累计每日时长，供统计页渲染总览与近 30 天曲线。

use crate::models::{PlaytimeByDay, PlaytimeByInstance, PlaytimeStats};
use crate::settings::get_data_dir;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use tokio::fs;

/// 记录一轮完整游戏会话：按天曲线 + 实例的 total_play_time / last_played。
///
/// ⚠️ 2026-10-25 之前 `add_daily_play_time` 是**零调用者的死代码** ——
/// 统计页的实例时长恒为 0，`last_played` 永远不更新。
///
/// ⚠️ 时长对账走「会话标记」而不是挂在 JLI_Launch 返回之后：游戏
/// `System.exit` 会带走**整个启动器进程**（同进程 JNI 架构，见
/// GameGuardService 的说明），JLI_Launch 在正常退出路径上**根本不会返回**。
/// 所以 launch.rs 在启动游戏前调 [`mark_session_start`] 落标记，
/// 启动器下次拉起时（jni_bridge::nativeAttach）调 [`reconcile_pending_session`]
/// 对账 —— 崩溃 / 被杀 / 正常退出，三种路径都能算到账。
pub async fn record_session(instance_id: &str, secs: u64) -> Result<(), String> {
    // 少于 1 分钟不计：误触 / 秒退（缺 Java 之类）会污染统计。
    if secs < 60 {
        return Ok(());
    }
    add_daily_play_time(secs).await;
    crate::instances::record_playtime(instance_id, secs)
        .await
        .map_err(|e| e.to_string())
}

/// 会话标记文件路径。
fn session_marker_path(data_dir: &str) -> PathBuf {
    Path::new(data_dir).join("playtime-session.json")
}

/// 游戏启动前落会话标记：`{"instance_id": …, "start": unix 秒}`。
/// 游戏退出会带走整个进程，这份标记就是对账的唯一依据。
pub async fn mark_session_start(instance_id: &str) {
    let Ok(data_dir) = get_data_dir().await else {
        return;
    };
    let start = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let marker = serde_json::json!({ "instance_id": instance_id, "start": start });
    if let Ok(text) = serde_json::to_string_pretty(&marker) {
        let _ = crate::fsutil::write_text_atomic(&session_marker_path(&data_dir), &text);
    }
}

/// 启动器拉起时对账：上轮游戏会话（无论正常退出 / 崩溃 / 被杀）的时长记账。
/// 由 `jni_bridge::nativeAttach` 在进程初始化时调用。
pub async fn reconcile_pending_session() {
    let Ok(data_dir) = get_data_dir().await else {
        return;
    };
    let path = session_marker_path(&data_dir);
    let Ok(text) = fs::read_to_string(&path).await else {
        return; // 没有未对账的会话
    };
    // 无论对账成败，标记都取走（读失败/损坏就当没有，避免反复重算）。
    let _ = fs::remove_file(&path).await;
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return;
    };
    let Some(instance_id) = v["instance_id"].as_str().map(|s| s.to_string()) else {
        return;
    };
    let Some(start) = v["start"].as_u64() else {
        return;
    };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    // 时钟回拨 / 长期未启动的防护：单次会话最多记 24 小时。
    let secs = now.saturating_sub(start).min(24 * 3600);
    if let Err(e) = record_session(&instance_id, secs).await {
        tracing::warn!("[playtime] 上轮会话（{instance_id}，{secs}s）记账失败：{e}");
    }
}

/// 记录新增的游玩秒数（调用方已按实际游戏会话时长折算）。
pub async fn add_daily_play_time(secs: u64) {
    if secs == 0 {
        return;
    }
    let day = today_day();
    let Ok(data_dir) = get_data_dir().await else {
        return;
    };
    let path = Path::new(&data_dir).join("playtime.json");
    let mut map: HashMap<String, u64> = fs::read_to_string(&path)
        .await
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();
    let e = map.entry(day.to_string()).or_insert(0);
    *e = e.saturating_add(secs);
    if let Ok(json) = serde_json::to_string_pretty(&map) {
        // 统计数据丢了不致命，但写坏了会归零 —— 顺手原子化。
        let _ = crate::fsutil::write_text_atomic(&path, &json);
    }
}

/// 东八区按天切分：`(unix + 8h) / 86400`
fn today_day() -> i64 {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64;
    (now + 8 * 3600) / 86400
}

/// 读取按天游玩时长（day 字符串 → 秒）
pub async fn daily_play_time() -> HashMap<String, u64> {
    let Ok(data_dir) = get_data_dir().await else {
        return HashMap::new();
    };
    let path = Path::new(&data_dir).join("playtime.json");
    fs::read_to_string(&path)
        .await
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

/// 聚合统计：按实例总时长 + 最近 30 天曲线。
pub async fn playtime_stats() -> PlaytimeStats {
    let mut by_instance: Vec<PlaytimeByInstance> = Vec::new();
    if let Ok(instances) = crate::instances::list_instances().await {
        for i in instances {
            by_instance.push(PlaytimeByInstance {
                id: i.id,
                name: i.name,
                icon: i.icon,
                seconds: i.total_play_time,
                last_played: i.last_played,
            });
        }
    }
    by_instance.sort_by(|a, b| b.seconds.cmp(&a.seconds));
    let total: i64 = by_instance.iter().map(|i| i.seconds).sum();

    let daily = daily_play_time().await;
    let today = today_day();
    let by_day: Vec<PlaytimeByDay> = (0..30)
        .rev()
        .map(|offset| {
            let day = today - offset;
            let secs = daily.get(&day.to_string()).copied().unwrap_or(0) as i64;
            PlaytimeByDay { day, seconds: secs }
        })
        .collect();

    PlaytimeStats {
        total_seconds: total,
        by_instance,
        by_day,
    }
}
