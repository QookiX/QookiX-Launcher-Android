//! 账号与皮肤域（登录 / 皮肤 / 披风）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 微软设备码登录、离线账号、皮肤与披风的应用。
//!
//! 拆法见 `instance.rs` 的模块注释：`dispatch` 只路由，命中后进 `run`，
//! 这样 arm 里的 `return err(...)` 早退语义与原来一致。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "list_accounts",
    "add_account",
    "remove_account",
    "select_account",
    "login_ms_start",
    "login_ms_poll",
    "list_skins",
    "read_skin_data_url",
    "save_skin_from_data",
    "download_skin_from_url",
    "delete_skin",
    "apply_skin_to_account",
    "apply_cape_to_account",
    "apply_skin_offline",
    "get_offline_skin",
    "fetch_player_skin",
    "fetch_player_capes",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "list_accounts" => match list_accounts().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "list_skins" => match list_skins().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "fetch_player_skin" => {
            let Some(name) = sarg(p, &["username"]) else {
                return err("缺少 username");
            };
            match fetch_player_skin(name).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "fetch_player_capes" => {
            let Some(uuid) = sarg(p, &["accountUuid", "account_uuid"]) else {
                return err("缺少 accountUuid");
            };
            match fetch_player_capes(uuid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 搜索 / 杂项（阶段 3 第六批）────────────────────────────
        "add_account" => {
            let Some(username) = sarg(p, &["username"]) else {
                return err("缺少 username");
            };
            match add_account(
                username,
                sarg(p, &["accountType", "account_type"]),
                sarg(p, &["refreshToken", "refresh_token"]),
            )
            .await
            {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "remove_account" => {
            let Some(uuid) = sarg(p, &["uuid"]) else {
                return err("缺少 uuid");
            };
            match remove_account(uuid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "select_account" => {
            let Some(uuid) = sarg(p, &["uuid"]) else {
                return err("缺少 uuid");
            };
            match select_account(uuid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "login_ms_start" => match login_ms_start().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "login_ms_poll" => match login_ms_poll().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        // 返回字符串的命令按 Tauri 形状再包一层
        "read_skin_data_url" => {
            let Some(fname) = sarg(p, &["filename"]) else {
                return err("缺少 filename");
            };
            match read_skin_data_url(fname).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "save_skin_from_data" => {
            let (Some(name), Some(data)) = (sarg(p, &["name"]), sarg(p, &["data"])) else {
                return err("缺少 name / data");
            };
            match save_skin_from_data(name, data).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "download_skin_from_url" => {
            let (Some(name), Some(url)) = (sarg(p, &["name"]), sarg(p, &["url"])) else {
                return err("缺少 name / url");
            };
            match download_skin_from_url(name, url).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "delete_skin" => {
            let Some(fname) = sarg(p, &["filename"]) else {
                return err("缺少 filename");
            };
            match delete_skin(fname).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "apply_skin_to_account" => {
            let (Some(uuid), Some(data), Some(variant)) = (
                sarg(p, &["accountUuid", "account_uuid"]),
                sarg(p, &["skinData", "skin_data"]),
                sarg(p, &["variant"]),
            ) else {
                return err("缺少 accountUuid / skinData / variant");
            };
            match apply_skin_to_account(uuid, data, variant).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "apply_cape_to_account" => {
            let Some(uuid) = sarg(p, &["accountUuid", "account_uuid"]) else {
                return err("缺少 accountUuid");
            };
            match apply_cape_to_account(uuid, sarg(p, &["capeId", "cape_id"])).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "apply_skin_offline" => {
            let (Some(data), Some(variant), Some(uuid)) = (
                sarg(p, &["skinData", "skin_data"]),
                sarg(p, &["variant"]),
                sarg(p, &["uuid"]),
            ) else {
                return err("缺少 skinData / variant / uuid");
            };
            match apply_skin_offline(data, variant, uuid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "get_offline_skin" => {
            let Some(uuid) = sarg(p, &["uuid"]) else {
                return err("缺少 uuid");
            };
            match get_offline_skin(uuid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
