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
    // ⚠️ 房间操作（2026-10-26 审查 A-P1-1）：这 5 条在 `run` 的 match 里有实现，
    // 但曾经漏登记 → dispatch 先查 HANDLED 就返回 None → 落空成「未实现的命令」。
    // 后果是离开房间 / 关闭房间 / 房主启动 / 访客启动 / 停止全报错。
    // Kotlin 侧调用点：QookixApiMultiplayer.kt 的 frpRoomLeave/Close/HostStart/...
    "frp_room_leave",
    "frp_room_close",
    "frp_room_host_start",
    "frp_room_visitor_start",
    "frp_room_stop",
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

#[cfg(test)]
mod tests {
    use super::*;

    /// 从本文件源码里抠出 `run` 的 match 分支用到的所有 `frp_*` 命令名。
    ///
    /// 为什么不靠反射：Rust 没有运行时反射，而 `unreachable!` 守卫在 `dispatch`
    /// 里先查 HANDLED，**永远到不了**（这就是 2026-10-26 审查 A-P1-1 漏登记 5 条
    /// 房间命令却一直没人发现的根源）。用 include_str! 扫源码虽然土，但能真正锁住
    /// 「match 里有、HANDLED 里没有」这一跑测试就会红的契约。
    fn match_commands() -> Vec<&'static str> {
        const SRC: &str = include_str!("dispatch_frp.rs");
        let start = SRC.find("async fn run").expect("找不到 run");
        SRC[start..]
            .split('"')
            .skip(1)
            .step_by(2)
            .map(str::trim)
            .filter(|s| s.starts_with("frp_"))
            .collect()
    }

    #[test]
    fn every_implemented_command_is_registered() {
        let implemented = match_commands();
        assert!(!implemented.is_empty(), "没抠到命令名，扫源码的逻辑要跟着改");
        let missing: Vec<&str> = implemented
            .iter()
            .copied()
            .filter(|c| !HANDLED.contains(c))
            .collect();
        assert!(missing.is_empty(), "match 里有但 HANDLED 漏登记：{missing:?}");
    }

    #[test]
    fn handled_has_no_duplicates() {
        let mut seen = std::collections::HashSet::new();
        for cmd in HANDLED {
            assert!(seen.insert(*cmd), "HANDLED 重复登记：{cmd}");
        }
    }
}
