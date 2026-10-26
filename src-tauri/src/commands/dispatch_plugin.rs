//! 插件域（清单 / 安装 / 启停）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 插件系统：清单刷新、推荐安装、单件装卸与启用开关。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "get_plugins",
    "get_installed_mods",
    "get_plugin_setup_status",
    "install_recommended_plugins",
    "dismiss_plugin_setup",
    "refresh_plugin_manifest",
    "install_plugin",
    "install_plugin_from_file",
    "uninstall_plugin",
    "set_plugin_enabled",
    "get_plugin_manifest_url",
    "set_plugin_manifest_url",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "get_plugins" => match get_plugins().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_installed_mods" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match get_installed_mods(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_plugin_setup_status" => match get_plugin_setup_status().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "install_recommended_plugins" => match install_recommended_plugins().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "dismiss_plugin_setup" => match dismiss_plugin_setup().await {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "refresh_plugin_manifest" => match refresh_plugin_manifest().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "install_plugin" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match install_plugin(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "install_plugin_from_file" => {
            let Some(path) = sarg(p, &["path", "filePath", "file_path"]) else {
                return err("缺少 path");
            };
            match install_plugin_from_file(path).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "uninstall_plugin" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match uninstall_plugin(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "set_plugin_enabled" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            // 缺字段不能默认 false（审查 A-P2-5）：会静默把插件禁用
            let Some(enabled) = p.get("enabled").and_then(|x| x.as_bool()) else {
                return err("缺少 enabled");
            };
            match set_plugin_enabled(id, enabled).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // 这两条返回 Result<String,_>，按 Tauri 形状再包一层字符串
        "get_plugin_manifest_url" => match get_plugin_manifest_url().await {
            Ok(v) => ok(serde_json::Value::String(v)),
            Err(e) => err(e),
        },
        "set_plugin_manifest_url" => {
            let Some(url) = sarg(p, &["url"]) else {
                return err("缺少 url");
            };
            match set_plugin_manifest_url(url).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        // ── 联机（FRP 隧道 + XTCP 房间，阶段 3 第八批）─────────────
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
