//! FRP 联机域（登录 / 隧道 / 房间）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! MohuaFrp 的账号、隧道与房间操作。命令名与 api.ts 的 frp* 段一一对应。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "frp_status",
    "frp_set_api_base",
    "frp_login_start",
    "frp_login_poll",
    "frp_logout",
    "frp_user_info",
    "frp_tunnels",
    "frp_start_tunnel",
    "frp_stop_tunnel",
    "frp_probe",
    "frp_room_nodes",
    "frp_room_list",
    "frp_room_detail",
    "frp_room_create",
    "frp_room_join",
    "frp_running",
    "frp_tunnel_log",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "frp_status" => ok(frp_status().await),
        "frp_set_api_base" => {
            let Some(base) = sarg(p, &["apiBase", "api_base"]) else {
                return err("缺少 apiBase");
            };
            match frp_set_api_base(base).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "frp_login_start" => match frp_login_start().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "frp_login_poll" => {
            let Some(session) = sarg(p, &["session"]) else {
                return err("缺少 session");
            };
            match frp_login_poll(session).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "frp_logout" => match frp_logout().await {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "frp_user_info" => match frp_user_info().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "frp_tunnels" => match frp_tunnels().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "frp_start_tunnel" => {
            let Some(tid) = iarg(p, &["tunnelId", "tunnel_id", "id"]) else {
                return err("缺少 tunnelId");
            };
            match frp_start_tunnel(tid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // 同步 fn
        "frp_stop_tunnel" => {
            let Some(tid) = iarg(p, &["tunnelId", "tunnel_id", "id"]) else {
                return err("缺少 tunnelId");
            };
            match frp_stop_tunnel(tid) {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "frp_probe" => match frp_probe().await {
            Ok(v) => ok(serde_json::Value::String(v)),
            Err(e) => err(e),
        },
        "frp_room_nodes" => match frp_room_nodes().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "frp_room_list" => {
            let page = iarg(p, &["page"]).unwrap_or(1);
            match frp_room_list(page).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "frp_room_detail" => {
            let Some(rid) = iarg(p, &["roomId", "room_id", "id"]) else {
                return err("缺少 roomId");
            };
            match frp_room_detail(rid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "frp_room_create" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            let Some(node_id) = iarg(p, &["nodeId", "node_id"]) else {
                return err("缺少 nodeId");
            };
            let Some(local_port) = iarg(p, &["localPort", "local_port"]) else {
                return err("缺少 localPort");
            };
            match frp_room_create(name, node_id, local_port, sarg(p, &["password"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "frp_room_join" => {
            let Some(rid) = iarg(p, &["roomId", "room_id", "id"]) else {
                return err("缺少 roomId");
            };
            match frp_room_join(rid, sarg(p, &["password"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "frp_room_leave" | "frp_room_close" | "frp_room_host_start"
        | "frp_room_visitor_start" | "frp_room_stop" => {
            let Some(rid) = iarg(p, &["roomId", "room_id", "id"]) else {
                return err("缺少 roomId");
            };
            let r = match cmd {
                "frp_room_leave" => frp_room_leave(rid).await,
                "frp_room_close" => frp_room_close(rid).await,
                "frp_room_host_start" => frp_room_host_start(rid).await,
                "frp_room_visitor_start" => frp_room_visitor_start(rid).await,
                _ => frp_room_stop(rid).await,
            };
            match r {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 账号 / 皮肤 / 服务端（阶段 3 第九批）────────────────────
        "frp_running" => ok(frp_running()),
        "frp_tunnel_log" => {
            let Some(tid) = iarg(p, &["tunnelId", "tunnel_id", "id"]) else {
                return err("缺少 tunnelId");
            };
            match frp_tunnel_log(tid) {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
