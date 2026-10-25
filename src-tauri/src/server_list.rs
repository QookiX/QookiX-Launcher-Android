//! 托管服务器的「在哪里」与「怎么列出来」。
//!
//! 从 `servers.rs` 拆出来的（Q2 体量棘轮：`servers.rs` 只许变小）。
//! 这里只管**定位根目录 + 扫目录读 `server.json`**；创建/启停/文件管理等
//! 仍在 `servers.rs`，通过 `crate::servers::list_servers` 复用本模块。

use crate::models::ServerConfig;
use std::path::{Path, PathBuf};

/// 服务器根目录（数据目录下 `server/`，旧版是 `servers/`）。
pub(crate) fn servers_root() -> Option<PathBuf> {
    let data = crate::settings::data_dir_sync()?;
    let root = crate::settings::resolve_servers_root(Path::new(&data));
    // 兜底：约定的 `server/` / `servers/` 都不存在，但数据根下**有别的目录**
    // 装着服务器（目录被改名/搬过家）—— 那就用它。
    //
    // 不这么做的话，用户会看到「还没有服务器」，而他的服务器好好地
    // 躺在某个我们没预料到的目录里，完全找不回来（2026-10-22 真机复现）。
    if !root.exists() {
        if let Some(found) = crate::settings::find_servers_root(Path::new(&data)) {
            println!(
                "[servers] 约定的目录 {} 不存在，改用找到的 {}",
                root.display(),
                found.display()
            );
            crate::util::log_line(&format!(
                "[servers] 约定的目录 {} 不存在，改用找到的 {}",
                root.display(),
                found.display()
            ));
            return Some(found);
        }
    }
    Some(root)
}

/// 列出全部服务器（按创建时间倒序，与桌面版一致）。
///
/// ⚠️ 2026-10-25 改签名：返回 `Result` 而不是裸 `Vec`。
/// 原来读不到目录就 `return Vec::new()`，命令层把它当「成功 + 空列表」发给界面，
/// 于是「没权限 / 目录不见了」和「一台服务器都没有」在 UI 上完全一样 ——
/// 0.2.0 上真实发生过：用户看到「还没有服务器」，以为数据被删了。
pub fn list_servers() -> Result<Vec<ServerConfig>, String> {
    let root =
        servers_root().ok_or_else(|| "数据目录不可用：无法定位应用私有目录".to_string())?;
    list_servers_in(&root)
}

/// 列表接口：读盘 + 补运行期字段（由 `servers` 再导出，命令层调用路径不变）。
pub fn list_hosted_servers() -> Result<Vec<ServerConfig>, String> {
    // 局域网地址只查一次 —— 每次都要建 socket 走一遍网卡。
    let lan_ip = crate::servers::local_wifi_ip();
    Ok(list_servers()?
        .into_iter()
        .map(|s| with_runtime(s, lan_ip.as_deref()))
        .collect())
}

/// 补上那些「不在 server.json 里、但界面要显示」的字段。
///
/// 1. `max-players`：在服务端自己的 `server.properties` 里，随用户在设置页改属性
///    变化，所以**每次返回配置时现读一次**（存进 server.json 就会和文件打架）。
/// 2. `running` / `address`：运行期状态。原来这两个字段根本不存在 ——
///    列表里的 `running` 恒为 false，卡片永远显示「启动」，正在跑的服务器
///    在列表上再也停不掉（2026-10-25 审查发现）。
///
/// 读不到就是 None / false，界面退回显示端口、显示「启动」。
pub(crate) fn with_runtime(mut s: ServerConfig, lan_ip: Option<&str>) -> ServerConfig {
    s.max_players = crate::servers::read_server_property(&s.id, "max-players")
        .and_then(|v| v.trim().parse().ok());
    // 休眠中也算「运行」—— JVM 停了但唤醒监听仍占着端口，
    // 界面必须给出停止入口（`stop_hosted_server` 对休眠态会 disarm）。
    let rt = crate::servers::server_runtime(&s.id);
    s.running = rt
        .as_ref()
        .map(|r| r.running || r.sleeping)
        .unwrap_or(false);
    s.address = if s.running {
        lan_ip.map(|ip| format!("{ip}:{}", s.port))
    } else {
        None
    };
    s
}

/// `server.json` 的内容：**运行期字段（running / address）必须先清掉**。
///
/// 这两个字段只报给界面，写进文件就会和真实状态打架：进程重启后文件里还留着
/// `running: true`，下次读出来就是「一台永远在运行、却停不掉」的服务器。
pub(crate) fn persisted_json(s: &ServerConfig) -> Result<String, serde_json::Error> {
    let mut clean = s.clone();
    clean.running = false;
    clean.address = None;
    serde_json::to_string_pretty(&clean)
}

/// 在给定根目录下列服务器（抽出来是为了能单测；调用方一律走 [`list_servers`]）。
fn list_servers_in(root: &Path) -> Result<Vec<ServerConfig>, String> {
    // 目录不存在 != 出错：新装用户本来就没有服务器目录，第一次创建时才建出来。
    if !root.exists() {
        return Ok(Vec::new());
    }
    let entries = std::fs::read_dir(root).map_err(|e| {
        // 诊断（2026-10-22）：目录读不到时，把**数据根目录下有什么**打出来 ——
        // 否则只能看到「读不到目录」，根本不知道数据到底在哪。
        let data = crate::settings::data_dir_sync().unwrap_or_default();
        let listing = std::fs::read_dir(&data)
            .map(|it| {
                it.flatten()
                    .map(|e| {
                        let p = e.path();
                        let kind = if p.is_dir() { "D" } else { "F" };
                        let n = p
                            .join("server.json")
                            .is_file()
                            .then(|| " [有server.json]")
                            .unwrap_or("");
                        format!(
                            "{}{}{}",
                            kind,
                            p.file_name().unwrap_or_default().to_string_lossy(),
                            n
                        )
                    })
                    .collect::<Vec<_>>()
            })
            .unwrap_or_default();
        // 决定性诊断：递归扫两层，把**任何**含 server.json 的路径找出来。
        // 服务器目录被改名/搬家/误删，只有这样才查得到它到底在哪儿。
        let mut found: Vec<String> = Vec::new();
        if let Ok(top) = std::fs::read_dir(&data) {
            for e in top.flatten() {
                let d = e.path();
                if !d.is_dir() {
                    continue;
                }
                if d.join("server.json").is_file() {
                    found.push(d.display().to_string());
                }
                if let Ok(sub) = std::fs::read_dir(&d) {
                    for e2 in sub.flatten() {
                        let d2 = e2.path();
                        if d2.is_dir() && d2.join("server.json").is_file() {
                            found.push(d2.display().to_string());
                        }
                    }
                }
            }
        }
        let msg = format!(
            "[servers] 读不到服务器目录 {}；数据根 {} 下有：{:?}；含 server.json 的路径：{:?}；game_root={:?}",
            root.display(),
            data,
            listing,
            found,
            crate::settings::game_root_sync()
        );
        crate::util::log_line(&msg);
        println!("{}", msg);
        // 这里**不能**再返回空列表 —— 那等于把「读不到」伪装成「没有」。
        format!("读取服务器目录失败：{}（{e}）", root.display())
    })?;
    // 诊断（2026-10-22 真机复现「服务器列表整个空了」）：
    // 把目录里每个子目录、以及 server.json 读不读得出来都写进诊断日志，
    // 否则「目录存在但 server.json 损坏」和「目录就是空的」分不清。
    //
    // ⚠️ 用 `util::log_line`（落盘）而不是 `tracing::warn!` ——
    // 这个项目**没有初始化 tracing subscriber**（tracing-subscriber 装了
    // 但没 enable），tracing 的日志在真机上根本不会输出，排查时一片空白。
    let mut names: Vec<String> = Vec::new();
    let mut broken = 0usize;
    let mut out: Vec<ServerConfig> = Vec::new();
    for e in entries.flatten() {
        let p = e.path();
        if !p.is_dir() {
            continue;
        }
        let nm = p.file_name().unwrap_or_default().to_string_lossy().to_string();
        names.push(nm.clone());
        match std::fs::read_to_string(p.join("server.json")) {
            Ok(text) => match serde_json::from_str::<ServerConfig>(&text) {
                Ok(cfg) => out.push(cfg),
                Err(err) => {
                    broken += 1;
                    crate::util::log_line(&format!(
                        "[servers] {} 的 server.json 解析失败: {}",
                        nm, err
                    ));
                }
            },
            Err(err) => {
                broken += 1;
                crate::util::log_line(&format!(
                    "[servers] {} 读不到 server.json: {}",
                    nm, err
                ));
            }
        }
    }
    crate::util::log_line(&format!(
        "[servers] 根目录={} 子目录={:?} 可用={} 异常={}",
        root.display(),
        names,
        out.len(),
        broken
    ));
    // 同一份也打到 stdout —— Android 会把 stdout 收进 logcat（tag=stdout），
    // 这样 adb logcat 就能直接看到，不用去翻 app 私有目录里的日志文件。
    println!(
        "[servers] 根目录={} 子目录={:?} 可用={} 异常={}",
        root.display(),
        names,
        out.len(),
        broken
    );
    // 有子目录、但一台都没读出来：同样是「列表显示为空、其实有数据」。
    // 只有「目录真的是空的」才允许走空态，其余一律报错。
    if out.is_empty() && broken > 0 {
        return Err(format!(
            "{broken} 个服务器目录读不出来（server.json 损坏或无权访问），已全部跳过"
        ));
    }
    out.sort_by(|a, b| b.created.cmp(&a.created));
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::ServerCore;

    fn write_server(root: &Path, id: &str, json: &str) {
        let dir = root.join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("server.json"), json).unwrap();
    }

    fn valid_json(id: &str) -> String {
        format!(
            r#"{{"id":"{id}","name":"测试服","core":"paper","mc_version":"1.21.1","created":1}}"#
        )
    }

    #[test]
    fn missing_dir_is_not_an_error() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("server");
        // 新装用户：目录还没建出来 —— 这是「没有服务器」，不是「读不到」。
        assert_eq!(list_servers_in(&root).unwrap().len(), 0);
    }

    #[test]
    fn empty_dir_is_an_empty_list() {
        let tmp = tempfile::tempdir().unwrap();
        assert_eq!(list_servers_in(tmp.path()).unwrap().len(), 0);
    }

    #[test]
    fn valid_metadata_is_listed() {
        let tmp = tempfile::tempdir().unwrap();
        write_server(tmp.path(), "srv-1", &valid_json("srv-1"));
        let list = list_servers_in(tmp.path()).unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].name, "测试服");
        assert_eq!(list[0].core, ServerCore::Paper);
    }

    /// 关键回归：目录里有东西但一台都读不出来时**必须报错**。
    /// 返回空列表会让界面显示「还没有服务器」，用户以为数据被删了（0.2.0 事故）。
    #[test]
    fn broken_metadata_alone_is_an_error_not_an_empty_list() {
        let tmp = tempfile::tempdir().unwrap();
        write_server(tmp.path(), "srv-bad", "{ 这不是 JSON");
        assert!(list_servers_in(tmp.path()).is_err());
    }

    /// 运行期字段（running / address）只报给界面，绝不能写进 `server.json`：
    /// 写进去会和真实运行状态打架（进程重启后文件里还留着 running: true）。
    #[test]
    fn runtime_fields_are_not_persisted() {
        let s = ServerConfig {
            id: "srv-rt".into(),
            name: "运行期".into(),
            core: ServerCore::Paper,
            mc_version: "1.21.1".into(),
            port: 25565,
            max_memory_mb: 1024,
            min_memory_mb: 512,
            motd: "m".into(),
            eula: true,
            created: 1,
            last_started: None,
            java_path: None,
            jvm_args: None,
            stop_command: None,
            sleep_timeout_min: 30,
            sleep_hint: None,
            wake_hint: None,
            max_players: None,
            running: true,
            address: Some("192.168.1.5:25565".into()),
        };
        // 给界面的 JSON 里**要**带 running（列表靠它决定显示启动还是停止）……
        assert!(serde_json::to_string(&s).unwrap().contains("\"running\""));
        // ……但落盘的 JSON 里**不能**带（persisted_json 会先清掉）。
        let json = persisted_json(&s).unwrap();
        assert!(!json.contains("\"running\""), "running 不该落盘：{json}");
        assert!(!json.contains("\"address\""), "address 不该落盘：{json}");

        // 盘上若残留这两个键（旧版本写过 / 用户手改），读的时候必须忽略。
        let polluted = json.replacen(
            '{',
            r#"{"running":true,"address":"10.0.0.9:25565","#,
            1,
        );
        let back: ServerConfig = serde_json::from_str(&polluted).unwrap();
        assert!(!back.running, "落盘的 running 必须被忽略");
        assert!(back.address.is_none(), "落盘的 address 必须被忽略");
    }

    #[test]
    fn one_broken_server_does_not_hide_the_others() {
        let tmp = tempfile::tempdir().unwrap();
        write_server(tmp.path(), "srv-1", &valid_json("srv-1"));
        write_server(tmp.path(), "srv-bad", "{ 坏掉了");
        let list = list_servers_in(tmp.path()).unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].id, "srv-1");
    }
}
