//! 实例目录与托管服务器目录共用的文件操作助手：路径越界防护、目录列举、
//! 文本读写与系统定位。两处文件管理器的命令层统一走这里，避免各写一份。

use serde::Serialize;
use serde_json::{json, Value};
use std::path::{Component, Path, PathBuf};

/// 内置编辑器愿意加载的最大文件字节数。
pub const MAX_EDIT_BYTES: u64 = 4 * 1024 * 1024;

pub fn fmt_bytes(n: u64) -> String {
    if n >= 1024 * 1024 {
        format!("{:.1} MB", n as f64 / 1024.0 / 1024.0)
    } else if n >= 1024 {
        format!("{:.1} KB", n as f64 / 1024.0)
    } else {
        format!("{n} B")
    }
}

pub fn modified_secs(meta: &std::fs::Metadata) -> u64 {
    meta.modified()
        .ok()
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

pub fn ext_of(name: &str) -> String {
    Path::new(name)
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_lowercase()
}

#[derive(Serialize)]
pub struct FsEntry {
    pub name: String,
    pub rel: String,
    pub size: u64,
    pub modified: u64,
    pub is_dir: bool,
    pub ext: String,
}

/// 目录 ID 只允许单层名称，防止拼出 `..` 或绝对路径。
pub fn validate_id(id: &str, label: &str) -> Result<(), String> {
    if id.is_empty()
        || id == "."
        || id.contains("..")
        || id.contains('/')
        || id.contains('\\')
    {
        return Err(format!("非法{label} ID"));
    }
    Ok(())
}

/// 拒绝会拼出嵌套路径或逃出父目录的名称。
pub fn validate_name(name: &str) -> Result<(), String> {
    let t = name.trim();
    if t.is_empty() || t == "." || t == ".." {
        return Err("名称不能为空".into());
    }
    if t.contains('/') || t.contains('\\') {
        return Err("名称不能包含路径分隔符".into());
    }
    Ok(())
}

/// 把 `rel` 解析到 `dir` 之内，拒绝 `..`、绝对路径与指向外部的符号链接；
/// 目标尚不存在时（新建 / 重命名）改为逐段词法校验。`label` 用于错误文案。
pub fn resolve_in_dir(dir: &Path, rel: &str, label: &str) -> Result<PathBuf, String> {
    let root = dir
        .canonicalize()
        .map_err(|e| format!("{label}目录不可用: {e}"))?;
    let cleaned = rel.replace('\\', "/");
    let cleaned = cleaned.trim_start_matches('/');
    let target = if cleaned.is_empty() {
        root.clone()
    } else {
        root.join(cleaned)
    };
    match target.canonicalize() {
        Ok(c) => {
            if c != root && !c.starts_with(&root) {
                return Err(format!("路径超出{label}目录范围"));
            }
            Ok(c)
        }
        Err(_) => {
            let mut depth = 0i32;
            for part in Path::new(cleaned).components() {
                match part {
                    Component::Normal(_) => depth += 1,
                    Component::ParentDir => depth -= 1,
                    Component::CurDir => {}
                    other => {
                        return Err(format!("非法路径: {}", other.as_os_str().to_string_lossy()))
                    }
                }
                if depth < 0 {
                    return Err(format!("路径超出{label}目录范围"));
                }
            }
            Ok(target)
        }
    }
}

/// 列举目录内容：目录在前，同类型按名称（不区分大小写）排序。
pub fn list_dir(dir: &Path, base_rel: &str) -> Result<Vec<FsEntry>, String> {
    let base = base_rel.trim_end_matches('/').to_string();
    let mut out: Vec<FsEntry> = Vec::new();
    let rd = std::fs::read_dir(dir).map_err(|e| format!("读取目录失败: {e}"))?;
    for e in rd.flatten() {
        let meta = match e.metadata() {
            Ok(m) => m,
            Err(_) => continue,
        };
        let name = e.file_name().to_string_lossy().to_string();
        let is_dir = meta.is_dir();
        let child_rel = if base.is_empty() {
            name.clone()
        } else {
            format!("{base}/{name}")
        };
        out.push(FsEntry {
            ext: if is_dir { String::new() } else { ext_of(&name) },
            name,
            rel: child_rel,
            size: if is_dir { 0 } else { meta.len() },
            modified: modified_secs(&meta),
            is_dir,
        });
    }
    out.sort_by(|a, b| {
        b.is_dir
            .cmp(&a.is_dir)
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
    });
    Ok(out)
}

/// 读取文本文件：限制体积、拒绝二进制与非 UTF-8 内容。
pub fn read_text(path: &Path, rel: &str) -> Result<Value, String> {
    if !path.is_file() {
        return Err("不是一个文件".into());
    }
    let meta = std::fs::metadata(path).map_err(|e| format!("读取文件失败: {e}"))?;
    if meta.len() > MAX_EDIT_BYTES {
        return Err(format!(
            "文件过大（{}），内置编辑器最多支持 {}",
            fmt_bytes(meta.len()),
            fmt_bytes(MAX_EDIT_BYTES)
        ));
    }
    let bytes = std::fs::read(path).map_err(|e| format!("读取文件失败: {e}"))?;
    if bytes.iter().take(4096).any(|b| *b == 0) {
        return Err("这是二进制文件，无法在内置编辑器中打开".into());
    }
    let content = String::from_utf8(bytes)
        .map_err(|_| String::from("文件不是 UTF-8 编码，无法在内置编辑器中打开"))?;
    let meta2 = std::fs::metadata(path).ok();
    Ok(json!({
        "rel": rel,
        "content": content,
        "size": meta.len(),
        "modified": meta2.as_ref().map(modified_secs).unwrap_or(0),
    }))
}

/// 写入文本文件，必要时创建父目录。
pub fn write_text(path: &Path, rel: &str, content: String) -> Result<Value, String> {
    if path.is_dir() {
        return Err("目标是一个目录".into());
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
    }
    let len = content.len() as u64;
    write_text_atomic(path, &content).map_err(|e| format!("写入文件失败: {e}"))?;
    let meta = std::fs::metadata(path).ok();
    Ok(json!({
        "rel": rel,
        "size": meta.as_ref().map(|m| m.len()).unwrap_or(len),
        "modified": meta.as_ref().map(modified_secs).unwrap_or(0),
    }))
}

/// 原子写文本：先写**唯一命名的** tmp（pid+序号），sync 后 rename 覆盖目标。
///
/// ⚠️ 2026-10-25 全仓统一：直接 `fs::write`（open+O_TRUNC+write）在进程被杀/
/// 断电时会留下半截 JSON —— settings.json 就这么坏过，之后「读空表→合并→写回」
/// 把用户设置全部抹掉（详见 settings.rs 的长注释）。凡写 JSON 配置一律走这里。
/// tmp 用唯一名：并发写互不干扰；失败时顺手删掉 tmp，不在目录里堆垃圾。
pub fn write_text_atomic(path: &Path, content: &str) -> std::io::Result<()> {
    use std::sync::atomic::{AtomicU64, Ordering};
    static TMP_SEQ: AtomicU64 = AtomicU64::new(0);
    let seq = TMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let file_name = path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "file.json".to_string());
    let tmp_path = path.with_file_name(format!("{file_name}.tmp.{}-{seq}", std::process::id()));
    let result = (|| -> std::io::Result<()> {
        use std::io::Write;
        let mut f = std::fs::File::create(&tmp_path)?;
        f.write_all(content.as_bytes())?;
        f.sync_all().ok();
        drop(f);
        std::fs::rename(&tmp_path, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp_path);
    }
    result
}

