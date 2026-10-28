//! 局域网直传同步域命令分发（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 命令：
//!   · `lan_connect`        — 验证 token + 拿 PC 设备信息
//!   · `lan_list_instances` — 拿 PC 上的实例列表
//!   · `lan_download_instance` — 流式下载 + 解压一个实例到本地
//!
//! 进度走 `lan://progress` 事件（见 `lan_sync.rs`），不在这层返回。

use serde_json::Value;

use super::*;

/// 本域负责的命令名。
pub(crate) const HANDLED: &[&str] = &[
    "lan_connect",
    "lan_list_instances",
    "lan_download_instance",
];

/// 主分发入口：不是本域的命令返回 `None`。
pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "lan_connect" => {
            let (host, port, token) = match parse_endpoint(p) {
                Ok(v) => v,
                Err(e) => return err(e),
            };
            match crate::lan_sync::connect(&host, port, &token).await {
                Ok(info) => ok(info),
                Err(e) => err(e),
            }
        }
        "lan_list_instances" => {
            let (host, port, token) = match parse_endpoint(p) {
                Ok(v) => v,
                Err(e) => return err(e),
            };
            match crate::lan_sync::list_instances(&host, port, &token).await {
                Ok(list) => ok(list),
                Err(e) => err(e),
            }
        }
        "lan_download_instance" => {
            let (host, port, token) = match parse_endpoint(p) {
                Ok(v) => v,
                Err(e) => return err(e),
            };
            let Some(remote_id) = sarg(p, &["instanceId", "instance_id", "id", "remoteId"])
            else {
                return err("缺少 instanceId");
            };
            let instance_name =
                sarg(p, &["instanceName", "instance_name", "name"]).unwrap_or_default();
            match crate::lan_sync::download_instance(&host, port, &token, &remote_id, &instance_name)
                .await
            {
                Ok(new_id) => ok(serde_json::json!({ "instanceId": new_id })),
                Err(e) => err(e),
            }
        }
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}

/// 从 payload 里取 host / port / token 三件套。
fn parse_endpoint(p: &Value) -> Result<(String, u16, String), String> {
    let Some(host) = sarg(p, &["host"]) else {
        return Err("缺少 host".to_string());
    };
    let port = iarg(p, &["port"])
        .ok_or_else(|| "缺少 port".to_string())?
        as u16;
    let Some(token) = sarg(p, &["token"]) else {
        return Err("缺少 token".to_string());
    };
    Ok((host, port, token))
}
