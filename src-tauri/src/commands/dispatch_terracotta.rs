//! 陶瓦联机域（隧道探活 / 请求）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 隧道跑在独立 :tunnel 进程，这里只有探活与 HTTP 转发。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "terracotta_vpn_granted",
    "terracotta_request_vpn",
    "terracotta_ping",
    "terracotta_request",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "terracotta_vpn_granted" => match crate::terracotta::terracotta_vpn_granted().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "terracotta_request_vpn" => match crate::terracotta::terracotta_request_vpn().await {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "terracotta_ping" => match crate::terracotta::terracotta_ping().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "terracotta_request" => {
            let Some(path) = sarg(p, &["path"]) else {
                return err("缺少 path");
            };
            match crate::terracotta::terracotta_request(path).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        // 自更新（这三条是 Tauri 时代留下的，需要 AppHandle 才能拉 release 信息）
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
