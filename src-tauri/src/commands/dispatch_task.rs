//! 任务与游戏生命周期域（下载 / 安装 / 启动 / 更新）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 下载任务、安装任务、游戏启停与自更新。这些命令大多有副作用且与事件流相关。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "kill_game",
    "cancel_install",
    "launch_game",
    "install_game",
    "download_file",
    "cancel_download",
    "get_download_progress",
    "get_game_status",
    "is_game_running",
    "check_renderer_health",
    "check_renderer_health_latest",
    "check_updates",
    "apply_update",
    "check_for_update",
    "download_update",
    "install_update",
    "fetch_news",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "fetch_news" => match fetch_news().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "kill_game" => match kill_game().await {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "cancel_install" => {
            let Some(tid) = p
                .get("taskId")
                .or_else(|| p.get("task_id"))
                .and_then(|x| x.as_u64())
            else {
                return err("缺少 taskId");
            };
            match cancel_install(tid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "check_renderer_health" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match check_renderer_health(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 生命周期 + 内容操作 + 文件读写（阶段 3 第四批）──────────
        "launch_game" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match launch_game(id, sarg(p, &["world"]), sarg(p, &["server"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "install_game" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match install_game(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_download_progress" => {
            let Some(tid) = sarg(p, &["taskId", "task_id"]) else {
                return err("缺少 taskId");
            };
            match get_download_progress(tid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "check_updates" => {
            let (Some(id), Some(kind)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
            ) else {
                return err("缺少 instanceId / kind");
            };
            match check_updates(id, kind).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "apply_update" => {
            let (Some(id), Some(kind), Some(old)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
                sarg(p, &["oldFilename", "old_filename"]),
            ) else {
                return err("缺少 instanceId / kind / oldFilename");
            };
            let (Some(provider), Some(pid), Some(nvid)) = (
                sarg(p, &["provider"]),
                sarg(p, &["projectId", "project_id"]),
                sarg(p, &["newVersionId", "new_version_id"]),
            ) else {
                return err("缺少 provider / projectId / newVersionId");
            };
            match apply_update(id, kind, old, provider, pid, nvid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_game_status" => match get_game_status().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "download_file" => {
            let (Some(url), Some(dest)) = (sarg(p, &["url"]), sarg(p, &["dest"])) else {
                return err("缺少 url / dest");
            };
            match download_file(url, dest, sarg(p, &["sha1"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "cancel_download" => {
            let Some(tid) = sarg(p, &["taskId", "task_id"]) else {
                return err("缺少 taskId");
            };
            match cancel_download(tid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "is_game_running" => ok(is_game_running()),
        "check_renderer_health_latest" => match check_renderer_health_latest().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        // ── 最后一条：新建实例（14 个参数，含 renderer）─────────────
        // ⚠️ 参数必须**全部**透传：Tauri 按签名反序列化，漏掉 `renderer`
        // 会让新实例 renderer=null → 自动判给 GL4ES → 1.21.x 一启动就崩
        //（`Can't map buffer, opengl error 0`，见上面那个函数的注释）。
        "check_for_update" => match crate::updater::check_for_update().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "download_update" => match crate::updater::download_update(sarg(p, &["version"])).await {
            Ok(v) => ok(serde_json::Value::String(v)),
            Err(e) => err(e),
        },
        "install_update" => {
            let Some(path) = sarg(p, &["path"]) else {
                return err("缺少 path");
            };
            match crate::updater::install_update(path) {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        other => err(format!("native_invoke 未实现的命令：{other}")),
    }
}
