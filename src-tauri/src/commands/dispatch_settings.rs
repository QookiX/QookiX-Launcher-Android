//! 设置与系统域（设置项 / 存储 / 镜像 / 游戏目录 / 系统能力）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 设置读写、存储统计、镜像与代理测试、游戏目录切换、剪贴板/屏幕方向等原生能力。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "get_settings",
    "update_settings",
    "get_pins",
    "set_pins",
    "get_storage_stats",
    "refresh_storage_stats",
    "clear_cache",
    "log_debug",
    "read_clipboard",
    "write_clipboard",
    "set_orientation",
    "detect_system_proxy",
    "test_proxy",
    "test_mirror",
    "list_mirrors",
    "auto_detect_memory",
    "device_available_memory_mb",
    "battery_unrestricted",
    "request_battery_unrestricted",
    "device_thermal_status",
    "get_game_dir_state",
    "get_game_dir_options",
    "has_all_files_access",
    "request_all_files_access",
    "pick_game_dir",
    "take_picked_game_dir",
    "set_game_dir",
    "set_log_zoom_capture",
    "get_pojav_prefs",
    "set_pojav_prefs",
    "shared_game_usage",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "get_settings" => match get_settings().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_pins" => ok(get_pins().await),
        "get_storage_stats" => match get_storage_stats().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        // ── 只读命令（阶段 3 第二批）──────────────────────────────
        "update_settings" => {
            // patch 是 snake_case 的增量对象（与 Tauri 侧同形）
            let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
            match update_settings(patch).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "set_pins" => {
            // ⚠️ 缺字段不能当空数组（2026-10-26 审查 A-P1-2）：空数组会**清空全部
            // 固定项**并写盘，等于一个参数笔误就静默删数据。
            let Some(items) = p.get("items").cloned() else {
                return err("缺少 items");
            };
            match serde_json::from_value::<Vec<PinItem>>(items) {
                Ok(items) => match set_pins(items).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                },
                Err(e) => err(format!("items 解析失败：{e}")),
            }
        }
        "set_orientation" => {
            let Some(mode) = sarg(p, &["mode"]) else {
                return err("缺少 mode");
            };
            match set_orientation(mode).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "list_mirrors" => ok(list_mirrors()),
        "log_debug" => {
            log_debug(sarg(p, &["msg", "message"]).unwrap_or_default());
            ok(())
        }
        "get_pojav_prefs" => match get_pojav_prefs() {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "set_pojav_prefs" => {
            let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
            match set_pojav_prefs(patch) {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "read_clipboard" => match read_clipboard().await {
            Ok(v) => ok(serde_json::Value::String(v)),
            Err(e) => err(e),
        },
        "write_clipboard" => {
            let Some(text) = sarg(p, &["text"]) else {
                return err("缺少 text");
            };
            match write_clipboard(text).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "clear_cache" => match clear_cache().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        // 注意：这条是 async（返回 Option<String>），要 await
        "detect_system_proxy" => ok(detect_system_proxy().await),
        // ── 插件系统（阶段 3 第七批）───────────────────────────────
        "test_mirror" => {
            let base = sarg(p, &["base"]).unwrap_or_default();
            match test_mirror(base).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "test_proxy" => {
            let mode = sarg(p, &["proxyMode", "proxy_mode"]).unwrap_or_default();
            match test_proxy(mode, sarg(p, &["proxy"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "auto_detect_memory" => match auto_detect_memory() {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "refresh_storage_stats" => match refresh_storage_stats().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "device_available_memory_mb" => ok(crate::servers::device_available_memory_mb()),
        "battery_unrestricted" => ok(crate::servers::battery_unrestricted()),
        // ── 开服属性 / 目录 + 控制布局（阶段 3 第十批）──────────────
        "get_game_dir_state" => match crate::game_dir::get_game_dir_state().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_game_dir_options" => ok(crate::game_dir::get_game_dir_options()),
        "has_all_files_access" => ok(crate::game_dir::has_all_files_access()),
        "request_all_files_access" => {
            crate::game_dir::request_all_files_access();
            ok(())
        }
        "pick_game_dir" => match crate::game_dir::pick_game_dir() {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "take_picked_game_dir" => match crate::game_dir::take_picked_game_dir().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "set_game_dir" => {
            let migrate = p.get("migrate").and_then(|x| x.as_bool()).unwrap_or(false);
            match crate::game_dir::set_game_dir(sarg(p, &["root"]), migrate).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "set_log_zoom_capture" => {
            let on = p.get("on").and_then(|x| x.as_bool()).unwrap_or(false);
            crate::android_bridge::set_log_zoom_capture(on);
            ok(())
        }
        // 实例文件体检 / 清理
        "shared_game_usage" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match crate::instance_files::shared_game_usage(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "request_battery_unrestricted" => {
            crate::servers::request_battery_unrestricted();
            ok(())
        }
        "device_thermal_status" => ok(crate::servers::device_thermal_status()),
        // 陶瓦联机（terracotta）
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
