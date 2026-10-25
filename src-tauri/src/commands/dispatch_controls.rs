//! 控制布局域（按键 / 摇杆 / 布局导入导出）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! Pojav 控制层的布局数据与编辑器入口。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "get_control_buttons",
    "set_control_button_passthru",
    "list_control_layouts",
    "set_current_control_layout",
    "duplicate_control_layout",
    "rename_control_layout",
    "delete_control_layout",
    "open_control_layout_editor",
    "export_control_layout",
    "pick_control_layout",
    "take_control_import",
    "import_control_layout_by_path",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "get_control_buttons" => match crate::controls::get_control_buttons().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "set_control_button_passthru" => {
            let (Some(index), Some(enabled)) = (
                iarg(p, &["index"]),
                p.get("enabled").and_then(|x| x.as_bool()),
            ) else {
                return err("缺少 index / enabled");
            };
            match crate::controls::set_control_button_passthru(index as usize, enabled).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "list_control_layouts" => match crate::controls::list_control_layouts().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "set_current_control_layout" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            match crate::controls::set_current_control_layout(name).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "duplicate_control_layout" => {
            let (Some(name), Some(new_name)) =
                (sarg(p, &["name"]), sarg(p, &["newName", "new_name"]))
            else {
                return err("缺少 name / newName");
            };
            match crate::controls::duplicate_control_layout(name, new_name).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "rename_control_layout" => {
            let (Some(name), Some(new_name)) =
                (sarg(p, &["name"]), sarg(p, &["newName", "new_name"]))
            else {
                return err("缺少 name / newName");
            };
            match crate::controls::rename_control_layout(name, new_name).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "delete_control_layout" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            match crate::controls::delete_control_layout(name).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "open_control_layout_editor" => {
            let preview = p.get("preview").and_then(|x| x.as_bool()).unwrap_or(false);
            match crate::controls::open_control_layout_editor(
                sarg(p, &["layout"]),
                preview,
                sarg(p, &["saveAs", "save_as"]),
            )
            .await
            {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "export_control_layout" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            match crate::controls::export_control_layout(name).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "pick_control_layout" => match crate::controls::pick_control_layout().await {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "take_control_import" => match crate::controls::take_control_import().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "import_control_layout_by_path" => {
            let Some(path) = sarg(p, &["path", "filePath", "file_path"]) else {
                return err("缺少 path");
            };
            match crate::controls::import_control_layout_by_path(path).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 收尾全覆盖（阶段 3 第十一批）──────────────────────────
        // 游戏目录 / 文件访问
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
