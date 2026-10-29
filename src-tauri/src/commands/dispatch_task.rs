//! 任务与游戏生命周期域（下载 / 安装 / 启动 / 更新）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! 下载任务、安装任务、游戏启停与自更新。这些命令大多有副作用且与事件流相关。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "kill_game",
    "cancel_install",
    "launch_game",
    "install_game",
    "download_file",
    "cancel_download",
    "get_download_progress",
    "get_game_status",
    "is_game_running",
    "check_renderer_health",
    "check_renderer_health_latest",
    "check_updates",
    "apply_update",
    "check_for_update",
    "download_update",
    "install_update",
    "fetch_news",
];

/// 下载目标是否落在「游戏目录」或「应用数据目录」之内（审查 A-P2-9）。
///
/// ⚠️ 2026-10-29（审计 A04）：**前缀判断不能替代路径规范化**。
///
/// 原来这里只做前缀匹配，并注释说「`..` 穿越在这里没有意义 —— 前缀匹配失败就
/// 直接拒绝」。实现与注释相反：`/sdcard/QookiX/../Download/probe` 以
/// `root + "/"` 开头，前缀判断**放行**，但系统实际把它解析到
/// `/sdcard/Download/probe` —— 已经在授权根之外。
///
/// 现在：先按路径组件消掉 `.` / `..`（纯词法，不碰文件系统，避免符号链接误判），
/// 消不掉（试图越过根）直接拒绝；再用**规范化后**的路径做前缀判断。
///
/// 仍然不做 canonicalize：目标文件通常还不存在，canonicalize 会失败。
fn dest_allowed(dest: &str) -> bool {
    let d = dest.replace('\\', "/");
    let mut parts: Vec<&str> = Vec::new();
    for part in d.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                // 弹不出已有段 = 想越过根，直接拒绝
                if parts.pop().is_none() {
                    return false;
                }
            }
            p => parts.push(p),
        }
    }
    // ⚠️ 不能无条件加前导 "/"：root 可能是 "C:/Users/…" 这种没有前导斜杠的
    // 路径（Windows / 测试环境），硬加会让 dest 和 root 永远匹配不上 ——
    // 等于把所有下载都拒了。这里保留 dest **原本**有没有前导分隔符。
    let had_leading = d.starts_with("/");
    let mut normalized = parts.join("/");
    if had_leading {
        normalized.insert(0, '/');
    }
    [
        crate::settings::game_root_sync(),
        crate::settings::data_dir_sync(),
    ]
    .into_iter()
    .flatten()
    .map(|r| r.replace('\\', "/").trim_end_matches('/').to_string())
    .filter(|r| !r.is_empty())
    .any(|root| normalized == root || normalized.starts_with(&format!("{root}/")))
}

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "fetch_news" => match fetch_news().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "kill_game" => match kill_game().await {
            Ok(()) => ok(()),
            Err(e) => err(e),
        },
        "cancel_install" => {
            let Some(tid) = p
                .get("taskId")
                .or_else(|| p.get("task_id"))
                .and_then(|x| x.as_u64())
            else {
                return err("缺少 taskId");
            };
            match cancel_install(tid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "check_renderer_health" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match check_renderer_health(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 生命周期 + 内容操作 + 文件读写（阶段 3 第四批）──────────
        "launch_game" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match launch_game(id, sarg(p, &["world"]), sarg(p, &["server"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "install_game" => {
            let Some(id) = sarg(p, &["instanceId", "instance_id", "id"]) else {
                return err("缺少 instanceId");
            };
            match install_game(id).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_download_progress" => {
            let Some(tid) = sarg(p, &["taskId", "task_id"]) else {
                return err("缺少 taskId");
            };
            match get_download_progress(tid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "check_updates" => {
            let (Some(id), Some(kind)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
            ) else {
                return err("缺少 instanceId / kind");
            };
            match check_updates(id, kind).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "apply_update" => {
            let (Some(id), Some(kind), Some(old)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
                sarg(p, &["oldFilename", "old_filename"]),
            ) else {
                return err("缺少 instanceId / kind / oldFilename");
            };
            let (Some(provider), Some(pid), Some(nvid)) = (
                sarg(p, &["provider"]),
                sarg(p, &["projectId", "project_id"]),
                sarg(p, &["newVersionId", "new_version_id"]),
            ) else {
                return err("缺少 provider / projectId / newVersionId");
            };
            match apply_update(id, kind, old, provider, pid, nvid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_game_status" => match get_game_status().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "download_file" => {
            let (Some(url), Some(dest)) = (sarg(p, &["url"]), sarg(p, &["dest"])) else {
                return err("缺少 url / dest");
            };
            // ⚠️ 审查 A-P2-9：url + dest 直接透传，等于给出「往应用可写范围内任意
            // 路径写任意内容」的能力（没有路径规范化，也没有根目录约束）。
            // 这条命令目前 Kotlin 侧没有调用者，但不能留着一个越权的通用下载器。
            if !dest_allowed(&dest) {
                return err("下载目标不在游戏目录或应用数据目录内");
            }
            match download_file(url, dest, sarg(p, &["sha1"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "cancel_download" => {
            let Some(tid) = sarg(p, &["taskId", "task_id"]) else {
                return err("缺少 taskId");
            };
            match cancel_download(tid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "is_game_running" => ok(is_game_running()),
        "check_renderer_health_latest" => match check_renderer_health_latest().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        // ── 最后一条：新建实例（14 个参数，含 renderer）─────────────
        // ⚠️ 参数必须**全部**透传：Tauri 按签名反序列化，漏掉 `renderer`
        // 会让新实例 renderer=null → 自动判给 GL4ES → 1.21.x 一启动就崩
        //（`Can't map buffer, opengl error 0`，见上面那个函数的注释）。
        "check_for_update" => match crate::updater::check_for_update().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "download_update" => match crate::updater::download_update(sarg(p, &["version"])).await {
            Ok(v) => ok(serde_json::Value::String(v)),
            Err(e) => err(e),
        },
        "install_update" => {
            let Some(path) = sarg(p, &["path"]) else {
                return err("缺少 path");
            };
            match crate::updater::install_update(path) {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        other => err(format!("native_invoke 未实现的命令：{other}")),
    }
}

#[cfg(test)]
mod tests {
    use super::dest_allowed;

    /// 审计 A04：".." 不能绕过「下载目标必须在授权根内」的前缀判断。
    /// /sdcard/QookiX/../Download/probe 以 root + "/" 开头，纯前缀判断会放行，
    /// 但系统实际解析到 /sdcard/Download/probe。
    #[test]
    fn dest_allowed_rejects_parent_traversal() {
        assert!(!dest_allowed("../escape.txt"));
        assert!(!dest_allowed("/sdcard/QookiX/../Download/probe"));

        if let Some(dir) = crate::settings::data_dir_sync() {
            let base = dir.replace(std::path::MAIN_SEPARATOR, "/").trim_end_matches(0x2f as char).to_string();
            if !base.is_empty() {
                assert!(dest_allowed(&format!("{base}/ok.txt")), "正常目标应放行");
                assert!(!dest_allowed(&format!("{base}/../escape.txt")), "越界目标应拒绝");
                assert!(!dest_allowed(&format!("{base}/a/../../escape.txt")));
            }
        }
    }
}
