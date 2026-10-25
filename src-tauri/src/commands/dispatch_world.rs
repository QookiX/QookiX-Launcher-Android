//! 存档与诊断域（世界备份 / 崩溃分析 / 实例体检）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 世界备份增删查、崩溃日志分析与实例文件体检/清理。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "list_world_backups",
    "create_world_backup",
    "restore_world_backup",
    "delete_world_backup",
    "list_crash_logs",
    "analyze_crash_log",
    "get_crash_report_content",
    "playtime_stats",
    "check_instance_files",
    "repair_instance_files",
    "plan_instance_cleanup",
    "cleanup_instance_shared",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "list_crash_logs" => {
            let Some(id) = sarg(p, &["id", "instanceId", "instance_id"]) else {
                return err("缺少 id");
            };
            match list_crash_logs(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "list_world_backups" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            let Some(world) = sarg(p, &["world"]) else {
                return err("缺少 world");
            };
            match list_world_backups(id, world).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "playtime_stats" => match playtime_stats().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "analyze_crash_log" => {
            let (Some(id), Some(fname)) =
                (sarg(p, &["id"]), sarg(p, &["filename"]))
            else {
                return err("缺少 id / filename");
            };
            match analyze_crash_log(id, fname).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_crash_report_content" => {
            let (Some(id), Some(fname)) =
                (sarg(p, &["id"]), sarg(p, &["filename"]))
            else {
                return err("缺少 id / filename");
            };
            match get_crash_report_content(id, fname).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "create_world_backup" => {
            let (Some(id), Some(world)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["world"]),
            ) else {
                return err("缺少 instanceId / world");
            };
            match create_world_backup(id, world).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "restore_world_backup" => {
            let (Some(id), Some(world), Some(fname)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["world"]),
                sarg(p, &["filename"]),
            ) else {
                return err("缺少 instanceId / world / filename");
            };
            match restore_world_backup(id, world, fname).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "delete_world_backup" => {
            let (Some(id), Some(world), Some(fname)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["world"]),
                sarg(p, &["filename"]),
            ) else {
                return err("缺少 instanceId / world / filename");
            };
            match delete_world_backup(id, world, fname).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "check_instance_files" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match crate::instance_files::check_instance_files(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "repair_instance_files" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match crate::instance_files::repair_instance_files(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "plan_instance_cleanup" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match crate::instance_files::plan_instance_cleanup(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "cleanup_instance_shared" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match crate::instance_files::cleanup_instance_shared(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // 服务端文件
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
