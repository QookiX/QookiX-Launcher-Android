//! 文件管理域（实例文件 / 服务器文件 / 导入导出 / 系统选择器落地）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 读写删改、路径解析、图片导入导出。命令数量多但都是薄封装，耦合最低。
//!
//! 拆法见 `instance.rs` 的模块注释：`dispatch` 只路由，命中后进 `run`，
//! 这样 arm 里的 `return err(...)` 早退语义与原来一致。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "list_instance_files",
    "list_instance_dir",
    "read_instance_log",
    "read_instance_file",
    "write_instance_file",
    "delete_instance_path",
    "rename_instance_path",
    "list_instance_folders",
    "create_instance_entry",
    "import_instance_image",
    "import_background_image",
    "import_local_file",
    "save_text_file",
    "resolve_picked_path",
    "extract_game_icons",
    "export_instance_logs",
    "list_hosted_server_dir",
    "read_hosted_server_file",
    "write_hosted_server_file",
    "list_hosted_server_config_files",
    "open_hosted_server_folder",
    "open_hosted_server_directory",
    "create_hosted_server_entry",
    "rename_hosted_server_path",
    "delete_hosted_server_path",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "list_instance_files" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            let sub = sarg(p, &["sub"]).unwrap_or_default();
            match list_instance_files(id, sub).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "list_instance_dir" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            let rel = sarg(p, &["rel"]).unwrap_or_default();
            match list_instance_dir(id, rel).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "read_instance_log" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match read_instance_log(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 写操作（阶段 3 第三批）────────────────────────────────
        // 注意返回值：`Result<(), String>` 成功时 data 是 `null`，
        // Kotlin 侧 `o.get("data").toString()` 得到 "null"，
        // 与 Tauri 对 unit 的序列化一致 ✓。
        "read_instance_file" => {
            let (Some(id), Some(rel)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["rel"]),
            ) else {
                return err("缺少 instanceId / rel");
            };
            match read_instance_file(id, rel).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "write_instance_file" => {
            let (Some(id), Some(rel), Some(content)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["rel"]),
                sarg(p, &["content"]),
            ) else {
                return err("缺少 instanceId / rel / content");
            };
            match write_instance_file(id, rel, content).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "delete_instance_path" => {
            let (Some(id), Some(rel)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["rel"]),
            ) else {
                return err("缺少 instanceId / rel");
            };
            match delete_instance_path(id, rel).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "rename_instance_path" => {
            let (Some(id), Some(rel), Some(new_name)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["rel"]),
                sarg(p, &["newName", "new_name"]),
            ) else {
                return err("缺少 instanceId / rel / newName");
            };
            match rename_instance_path(id, rel, new_name).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 内容中心 / 版本 / 皮肤（阶段 3 第五批）──────────────────
        "import_local_file" => {
            let (Some(id), Some(kind), Some(src)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
                sarg(p, &["sourcePath", "source_path"]),
            ) else {
                return err("缺少 instanceId / kind / sourcePath");
            };
            match import_local_file(id, kind, src).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "save_text_file" => {
            let (Some(path), Some(content)) = (sarg(p, &["path"]), sarg(p, &["content"]))
            else {
                return err("缺少 path / content");
            };
            match save_text_file(path, content).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "extract_game_icons" => {
            match extract_game_icons(sarg(p, &["instanceId", "instance_id"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "resolve_picked_path" => {
            let Some(path) = sarg(p, &["path"]) else {
                return err("缺少 path");
            };
            match resolve_picked_path(path).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "export_instance_logs" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match export_instance_logs(id).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        // ── 收尾批 A（阶段 3 第十一批）──────────────────────────────
        "list_instance_folders" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match list_instance_folders(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "create_instance_entry" => {
            let (Some(id), Some(rel)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["rel"]),
            ) else {
                return err("缺少 instanceId / rel");
            };
            let is_dir = p.get("isDir").or_else(|| p.get("is_dir"))
                .and_then(|x| x.as_bool())
                .unwrap_or(false);
            match create_instance_entry(id, rel, is_dir).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "import_instance_image" => {
            let Some(src) = sarg(p, &["sourcePath", "source_path"]) else {
                return err("缺少 sourcePath");
            };
            match import_instance_image(src).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "import_background_image" => {
            let Some(src) = sarg(p, &["sourcePath", "source_path"]) else {
                return err("缺少 sourcePath");
            };
            match import_background_image(src).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "open_hosted_server_directory" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::servers::open_hosted_server_directory(id) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // 控制布局（全部 async）
        "list_hosted_server_dir" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::server_files::list_hosted_server_dir(
                id,
                sarg(p, &["rel"]).unwrap_or_default(),
            ) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "read_hosted_server_file" => {
            let (Some(id), Some(rel)) = (sarg(p, &["id"]), sarg(p, &["rel"])) else {
                return err("缺少 id / rel");
            };
            match crate::server_files::read_hosted_server_file(id, rel) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "write_hosted_server_file" => {
            let (Some(id), Some(rel), Some(content)) = (
                sarg(p, &["id"]),
                sarg(p, &["rel"]),
                sarg(p, &["content"]),
            ) else {
                return err("缺少 id / rel / content");
            };
            match crate::server_files::write_hosted_server_file(id, rel, content) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "create_hosted_server_entry" => {
            let (Some(id), Some(rel)) = (sarg(p, &["id"]), sarg(p, &["rel"])) else {
                return err("缺少 id / rel");
            };
            let is_dir = p
                .get("isDir")
                .or_else(|| p.get("is_dir"))
                .and_then(|v| v.as_bool())
                .unwrap_or(false);
            match crate::server_files::create_hosted_server_entry(id, rel, is_dir) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "rename_hosted_server_path" => {
            let (Some(id), Some(rel), Some(new_name)) = (
                sarg(p, &["id"]),
                sarg(p, &["rel"]),
                sarg(p, &["newName", "new_name"]),
            ) else {
                return err("缺少 id / rel / newName");
            };
            match crate::server_files::rename_hosted_server_path(id, rel, new_name) {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "delete_hosted_server_path" => {
            let (Some(id), Some(rel)) = (sarg(p, &["id"]), sarg(p, &["rel"])) else {
                return err("缺少 id / rel");
            };
            match crate::server_files::delete_hosted_server_path(id, rel) {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "list_hosted_server_config_files" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            ok(crate::server_files::list_hosted_server_config_files(id))
        }
        "open_hosted_server_folder" => {
            let Some(id) = sarg(p, &["id"]) else {
                return err("缺少 id");
            };
            match crate::server_files::open_hosted_server_folder(id, sarg(p, &["sub"])) {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
