//! 实例域命令分发（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! ## 拆法
//!
//! `native_invoke` 原本 1800+ 行、212 个 arm，谁都不敢动（改一条命令要在
//! 一屏外找边界）。现在每个域一个文件，导出两样东西：
//!   · [`HANDLED`]：这个域负责的命令名（路由用）
//!   · [`dispatch`]：命中才进入 [`run`]，否则返回 `None` 让主分发继续
//!
//! 为什么 `dispatch` 和 `run` 分开：arm 内部有大量 `return err(...)` 的早退
//!（缺参数、id 非法等）。`run` 返回 `String`，这些 `return` 语义与原来完全
//! 一致；如果让 arm 直接写在返回 `Option<String>` 的函数里，每个早退都要包
//! 一层 `Some(...)`，改起来容易错。

use serde_json::Value;

use super::*;

/// 本域负责的命令名。
pub(crate) const HANDLED: &[&str] = &[
    "list_instances",
    "get_instance",
    "create_instance",
    "delete_instance",
    "update_instance",
    "list_instance_groups",
    "create_instance_group",
    "rename_instance_group",
    "delete_instance_group",
    "reorder_instance_groups",
    "import_modpack",
    "plan_modpack_export",
    "export_modpack",
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
        "list_instances" => match list_instances().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_instance" => {
            let Some(id) = sarg(p, &["id", "instanceId", "instance_id"]) else {
                return err("缺少 id");
            };
            match get_instance(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "list_instance_groups" => match list_instance_groups().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "delete_instance" => {
            let Some(id) = sarg(p, &["id", "instanceId", "instance_id"]) else {
                return err("缺少 id");
            };
            match delete_instance(id).await {
                Ok(()) => {
                    // A2：实例集合变了 → 推 state://dirty，UI 重拉（见 app_state.rs）
                    crate::app_state::mark_instances_dirty();
                    ok(())
                }
                Err(e) => err(e),
            }
        }
        "import_modpack" => {
            let Some(path) = sarg(p, &["filePath", "file_path"]) else {
                return err("缺少 filePath");
            };
            match import_modpack(path).await {
                Ok(v) => {
                    // 导入整合包 = 新建（可能还带改名）实例
                    crate::app_state::mark_instances_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "plan_modpack_export" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match plan_modpack_export(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "export_modpack" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            let options = p
                .get("options")
                .cloned()
                .unwrap_or_else(|| serde_json::json!({}));
            match serde_json::from_value::<crate::modpack::ExportOptions>(options) {
                Ok(opts) => match export_modpack(id, opts).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                },
                Err(e) => err(format!("options 解析失败：{e}")),
            }
        }
        "update_instance" => {
            let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
            match update_instance(patch).await {
                Ok(v) => {
                    // A2：改名 / 换图标 / 改配置都会影响列表卡片
                    crate::app_state::mark_instances_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "create_instance_group" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            match create_instance_group(name, sarg(p, &["color"])).await {
                Ok(v) => {
                    // 分组改动要让实例列表刷新（审查 A-P2-7：这 4 条曾一处都没发
                    // dirty，UI 会一直显示旧分组，直到下次冷启动）
                    crate::app_state::mark_instances_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "rename_instance_group" => {
            let (Some(id), Some(name)) = (sarg(p, &["id"]), sarg(p, &["name"])) else {
                return err("缺少 id / name");
            };
            match rename_instance_group(id, name, sarg(p, &["color"])).await {
                Ok(v) => {
                    crate::app_state::mark_instances_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "delete_instance_group" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match delete_instance_group(id).await {
                Ok(()) => {
                    crate::app_state::mark_instances_dirty();
                    ok(())
                }
                Err(e) => err(e),
            }
        }
        "reorder_instance_groups" => {
            match reorder_instance_groups(svec(p, &["ids"])).await {
                Ok(v) => {
                    crate::app_state::mark_instances_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        "create_instance" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            let Some(mc) = sarg(p, &["mcVersion", "mc_version"]) else {
                return err("缺少 mcVersion");
            };
            let loader = sarg(p, &["loader"]).unwrap_or_default();
            let resolution: Option<Vec<i64>> = p
                .get("resolution")
                .and_then(|x| x.as_array())
                .map(|a| a.iter().filter_map(|v| v.as_i64()).collect());
            match create_instance(
                name,
                mc,
                loader,
                sarg(p, &["loaderVersion", "loader_version"]),
                sarg(p, &["javaDir", "java_dir"]),
                sarg(p, &["javaArgs", "java_args"]),
                sarg(p, &["gameArgs", "game_args"]),
                resolution,
                iarg(p, &["maxMemoryMb", "max_memory_mb"]),
                sarg(p, &["memoryMode", "memory_mode"]),
                sarg(p, &["accountId", "account_id"]),
                sarg(p, &["icon"]),
                sarg(p, &["group"]),
                sarg(p, &["renderer"]),
            )
            .await
            {
                Ok(v) => {
                    crate::app_state::mark_instances_dirty();
                    ok(v)
                }
                Err(e) => err(e),
            }
        }
        // ── 开服（hosted server）+ rcon（阶段 3 第九批）──────────────
        // 注意：这些命令定义在 `servers` 模块（lib.rs 里注册为 `servers::xxx`），
        // 不在本文件里，所以要写全路径 `crate::servers::`。
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
