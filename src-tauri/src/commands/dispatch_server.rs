//! 服务器域（托管服务器的配置 / 启停 / 控制台 / 插件）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 开服相关的命令。注意「陶瓦联机」不在这里（在 player 域之后单独拆）。
//!
//! 拆法见 `instance.rs` 的模块注释：`dispatch` 只路由，命中后进 `run`，
//! 这样 arm 里的 `return err(...)` 早退语义与原来一致。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "list_hosted_servers",
    "get_hosted_server",
    "create_hosted_server",
    "update_hosted_server",
    "delete_hosted_server",
    "suggest_server_memory",
    "list_paper_versions",
    "install_hosted_server_core",
    "start_hosted_server",
    "stop_hosted_server",
    "is_hosted_server_running",
    "read_hosted_server_log",
    "server_console_command",
    "hosted_server_runtime",
    "hosted_server_log",
    "hosted_server_address",
    "hosted_server_addresses",
    "get_hosted_server_properties",
    "set_hosted_server_properties",
    "hosted_server_core_installed",
    "inspect_server_core_manifest",
    "install_server_content",
    "install_server_plugin",
    "list_server_plugins",
    "delete_server_plugin",
    "hangar_search_plugins",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "install_server_content" => {
            let (Some(sid), Some(provider), Some(pid), Some(vid), Some(kind)) = (
                sarg(p, &["serverId", "server_id"]),
                sarg(p, &["provider"]),
                sarg(p, &["projectId", "project_id"]),
                sarg(p, &["versionId", "version_id"]),
                sarg(p, &["kind"]),
            ) else {
                return err("缺少 serverId / provider / projectId / versionId / kind");
            };
            match install_server_content(sid, provider, pid, vid, kind).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "install_server_plugin" => {
            let (Some(sid), Some(slug), Some(pname)) = (
                sarg(p, &["serverId", "server_id"]),
                sarg(p, &["slug"]),
                sarg(p, &["projectName", "project_name"]),
            ) else {
                return err("缺少 serverId / slug / projectName");
            };
            match install_server_plugin(sid, slug, pname).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "hangar_search_plugins" => {
            let Some(query) = sarg(p, &["query"]) else {
                return err("缺少 query");
            };
            let page = iarg(p, &["page"]).unwrap_or(0) as u32;
            let size = iarg(p, &["pageSize", "page_size"]).unwrap_or(20) as u32;
            match hangar_search_plugins(query, Some(page), Some(size)).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "list_server_plugins" => {
            let Some(sid) = sarg(p, &["serverId", "server_id"]) else {
                return err("缺少 serverId");
            };
            match list_server_plugins(sid) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "delete_server_plugin" => {
            let (Some(sid), Some(fname)) = (
                sarg(p, &["serverId", "server_id"]),
                sarg(p, &["fileName", "file_name", "filename"]),
            ) else {
                return err("缺少 serverId / fileName");
            };
            match delete_server_plugin(sid, fname) {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        // ⚠️ 不能用 ok(...) 包住：list_hosted_servers 现在会报错（读不到目录 /
        // server.json 全坏），包成 ok 就又变回「成功 + 空列表」了。
        "list_hosted_servers" => match crate::servers::list_hosted_servers() {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_hosted_server" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::servers::get_hosted_server(id) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "create_hosted_server" => {
            let (Some(name), Some(core), Some(mc)) = (
                sarg(p, &["name"]),
                sarg(p, &["core"]),
                sarg(p, &["mcVersion", "mc_version"]),
            ) else {
                return err("缺少 name / core / mcVersion");
            };
            match crate::servers::create_hosted_server(name, core, mc) {
                Ok(v) => {
                    crate::app_state::mark_servers_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "update_hosted_server" => {
            let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
            match crate::servers::update_hosted_server(patch) {
                Ok(v) => {
                    // 改名 / 改端口后要让服务器列表刷新（审查 A-P2-7：原来漏发 dirty）
                    crate::app_state::mark_servers_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "delete_hosted_server" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::servers::delete_hosted_server(id) {
                Ok(()) => {
                    crate::app_state::mark_servers_dirty();
                    ok(())
                }
                Err(e) => err(e),
            }
        }
        "suggest_server_memory" => ok(crate::servers::suggest_server_memory()),
        "list_paper_versions" => match crate::servers::list_paper_versions().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        // 下面几条返回 Result<String,_>，按 Tauri 形状再包一层字符串 ✓
        "install_hosted_server_core" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::servers::install_hosted_server_core(id).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "start_hosted_server" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            let stage_id = id.clone();
            match crate::servers::start_hosted_server(id).await {
                Ok(v) => {
                    // 启停会改 "running" 字段，列表要跟着变
                    crate::app_state::mark_servers_dirty();
                    // 起来了 → 收掉「正在启动…」（异常退出时由 server_process 那边清）
                    crate::progress::emit_server_stage(&stage_id, "");
                    ok(v)
                }
                Err(e) => {
                    // 失败也要收尾，否则界面永远停在「正在启动服务端进程…」
                    crate::progress::emit_server_stage(&stage_id, "");
                    err(e)
                }
            }
        }
        "stop_hosted_server" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::servers::stop_hosted_server(id).await {
                Ok(v) => {
                    crate::app_state::mark_servers_dirty();
                    ok(serde_json::Value::String(v))
                }
                Err(e) => err(e),
            }
        }
        "is_hosted_server_running" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(crate::servers::is_hosted_server_running(id))
        }
        "read_hosted_server_log" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(crate::servers::read_hosted_server_log(id))
        }
        "server_console_command" => {
            let (Some(id), Some(command)) = (sarg(p, &["id"]), sarg(p, &["command"]))
            else {
                return err("缺少 id / command");
            };
            match crate::servers::server_console_command(id, command) {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "hosted_server_runtime" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(crate::servers::hosted_server_runtime(id))
        }
        "hosted_server_log" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            let lines = iarg(p, &["lines"]).map(|n| n as usize);
            match crate::servers::hosted_server_log(id, lines) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "hosted_server_address" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(crate::servers::hosted_server_address(id))
        }
        "hosted_server_addresses" => ok(crate::servers::hosted_server_addresses()),
        "get_hosted_server_properties" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::servers::get_hosted_server_properties(id) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "set_hosted_server_properties" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
            match crate::servers::set_hosted_server_properties(id, patch) {
                Ok(v) => {
                    // 改端口 / MOTD 等属性后同样要刷新列表（审查 A-P2-7）
                    crate::app_state::mark_servers_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "hosted_server_core_installed" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(crate::servers::hosted_server_core_installed(id))
        }
        "inspect_server_core_manifest" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(serde_json::Value::String(
                crate::server_process::inspect_server_core_manifest(id),
            ))
        }
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
