//! 服务器文件管理 —— 复用 `fsutil` 与实例侧的既有实现。
//!
//! 这层只是**把「服务器目录」换成一个根目录**的薄包装：
//! 路径越界防护、目录列举、文本读写、扩展名判定全部委托给
//! `fsutil`（和 `commands::list_instance_dir` 等用的是同一套），
//! 不重复实现任何逻辑。
//!
//! 这几个命令之前在 `src/api.ts` 的 `UNIMPLEMENTED` 集合里（安卓端没实现，
//! invoke 返回 mock 空数组），所以「文件」页签一直是空的。

use serde_json::json;
use crate::fsutil;
use crate::servers::server_dir;

/// 与 `commands::list_instance_dir` 同构：把服务器的相对路径解析成真实路径
fn resolve_server_path(id: &str, rel: &str) -> Result<std::path::PathBuf, String> {
    let base = server_dir(id)?;
    fsutil::resolve_in_dir(&base, rel, "服务器")
}

/// 列出目录内容
pub fn list_hosted_server_dir(id: String, rel: String) -> Result<serde_json::Value, String> {
    let dir = resolve_server_path(&id, &rel)?;
    if !dir.is_dir() {
        return Err("不是一个目录".into());
    }
    let base = rel.clone();
    let entries = fsutil::list_dir(&dir, &base)?;
    Ok(json!({ "rel": rel, "entries": entries }))
}

/// 读文本文件
pub fn read_hosted_server_file(id: String, rel: String) -> Result<serde_json::Value, String> {
    let path = resolve_server_path(&id, &rel)?;
    fsutil::read_text(&path, &rel)
}

/// 写文本文件
pub fn write_hosted_server_file(
    id: String,
    rel: String,
    content: String,
) -> Result<serde_json::Value, String> {
    let path = resolve_server_path(&id, &rel)?;
    fsutil::write_text(&path, &rel, content)
}

/// 服务器根目录下的常用配置文件（设置页用）
pub fn list_hosted_server_config_files(id: String) -> Vec<serde_json::Value> {
    let Ok(dir) = server_dir(&id) else { return Vec::new() };
    ["server.properties", "eula.txt", "ops.json", "whitelist.json", "banned-players.json"]
        .iter()
        .filter_map(|name| {
            let p = dir.join(name);
            let meta = std::fs::metadata(&p).ok()?;
            Some(json!({
                "name": name,
                "rel": name,
                "size": meta.len(),
                "modified": fsutil::modified_secs(&meta),
            }))
        })
        .collect()
}

/// 「打开文件夹」在安卓上没有意义（不能弹系统文件管理器），
/// 这里返回路径给 UI 展示/复制。
pub fn open_hosted_server_folder(id: String, sub: Option<String>) -> Result<String, String> {
    let base = server_dir(&id)?;
    match sub.as_deref().filter(|s| !s.trim().is_empty()) {
        Some(s) => Ok(
            fsutil::resolve_in_dir(&base, s, "服务器")?.to_string_lossy().to_string()
        ),
        None => Ok(base.to_string_lossy().to_string()),
    }
}
