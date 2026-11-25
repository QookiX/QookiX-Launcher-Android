//! 下载落盘的最后一步：**先写同目录独立临时文件，校验通过后再原子替换**。
//!
//! 拆出来的原因（2026-11-24 审查 H7）：`download.rs` 已顶到体量预算，而这块
//! 逻辑必须是**纯同步、可单测**的 —— 网络层没法在 Windows 单测里跑，但
//! 「临时文件发布 / 失败不覆盖旧文件」完全可以用本地文件验证。
//!
//! 三条硬约束：
//!   1. 临时文件与目标文件**同目录**（跨卷 rename 会失败，且 Android 上不同卷
//!      可能根本不可写）；名字带唯一后缀，多个下载并发时不会撞名。
//!   2. 只有长度/摘要校验都通过才 rename 顶替目标；任何一步失败只删临时文件，
//!      **目标位置的旧文件（旧 JAR/旧版本）原样保留**。
//!   3. rename 在 Unix/Windows 上都是「替换目标」语义，成功后不会留下半截文件。

use std::path::{Path, PathBuf};

/// 给目标文件生成同目录的唯一临时名（`xxx.jar` → `xxx.jar.part-<uuid>`）。
pub fn temp_path_for(dest: &Path) -> PathBuf {
    let file_name = dest
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "download".to_string());
    let dir = dest.parent().filter(|p| !p.as_os_str().is_empty());
    let name = format!("{file_name}.part-{}", uuid::Uuid::new_v4());
    match dir {
        Some(d) => d.join(name),
        None => PathBuf::from(name),
    }
}

/// 失败清理：删掉临时文件；目标文件不动。返回临时文件是否真的不在了。
///
/// 删除失败（例如仍被并发写句柄占着）不算致命错误 —— 调用方已经把这次的
/// 下载判为失败，最多留一个垃圾文件，绝不能因此再去碰目标文件。
pub fn cleanup_temp(temp: &Path) -> bool {
    match std::fs::remove_file(temp) {
        Ok(()) => true,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => true,
        Err(_) => false,
    }
}

/// 计算文件 SHA1（流式读，避免把几百 MB 一次性读进内存）。
fn sha1_of_file(path: &Path) -> std::io::Result<String> {
    use sha1::{Digest, Sha1};
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

/// 把已下完的临时文件发布成最终文件。
///
/// 顺序：长度校验 → SHA1 校验（有摘要时）→ 原子 rename。
/// **任何一步失败都只删临时文件并返回 Err**，`dest` 上的旧文件保持不变。
/// `expect_len` 为 `None` 表示不做长度校验（某些源拿不到 Content-Length）。
pub fn publish_temp(
    temp: &Path,
    dest: &Path,
    expect_len: Option<u64>,
    expected_sha1: Option<&str>,
) -> std::io::Result<()> {
    let fail = |msg: String| {
        cleanup_temp(temp);
        Err(std::io::Error::new(std::io::ErrorKind::InvalidData, msg))
    };
    let meta = match std::fs::metadata(temp) {
        Ok(m) => m,
        Err(e) => return fail(format!("临时文件不可用（{}）: {e}", temp.display())),
    };
    if let Some(want) = expect_len {
        if meta.len() != want {
            return fail(format!(
                "下载长度不符：期望 {want} 字节，实际 {} 字节",
                meta.len()
            ));
        }
    }
    if let Some(expected) = expected_sha1.filter(|s| !s.is_empty()) {
        let actual = match sha1_of_file(temp) {
            Ok(s) => s,
            Err(e) => return fail(format!("校验摘要失败（{}）: {e}", temp.display())),
        };
        if !actual.eq_ignore_ascii_case(expected) {
            return fail(format!("SHA1 不符：期望 {expected}，实际 {actual}"));
        }
    }
    if let Err(e) = std::fs::rename(temp, dest) {
        return fail(format!(
            "发布失败（{} → {}）: {e}",
            temp.display(),
            dest.display()
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp_root(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("qk-dl-pub-{}-{tag}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn temp_is_sibling_and_unique() {
        let dir = tmp_root("name");
        let dest = dir.join("plugin.jar");
        let a = temp_path_for(&dest);
        let b = temp_path_for(&dest);
        assert_ne!(a, b, "两次下载不能共用临时文件名");
        assert_eq!(a.parent(), dest.parent(), "临时文件必须与目标同目录");
        assert!(a.file_name().unwrap().to_string_lossy().starts_with("plugin.jar.part-"));
        assert!(!a.exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn publish_replaces_old_file_only_after_checks() {
        let dir = tmp_root("publish");
        let dest = dir.join("server.jar");
        std::fs::write(&dest, b"old-version").unwrap();
        let temp = temp_path_for(&dest);
        std::fs::write(&temp, b"new-version").unwrap();
        publish_temp(&temp, &dest, Some(11), None).unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"new-version");
        assert!(!temp.exists(), "成功发布后临时文件应已被 rename 消耗");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn length_mismatch_keeps_old_file() {
        let dir = tmp_root("len");
        let dest = dir.join("plugin.jar");
        std::fs::write(&dest, b"healthy-old-jar").unwrap();
        let temp = temp_path_for(&dest);
        std::fs::write(&temp, b"truncated").unwrap();
        let err = publish_temp(&temp, &dest, Some(999), None).unwrap_err();
        assert!(err.to_string().contains("长度不符"), "{}", err);
        // 旧的可用文件必须原样保留，临时文件必须清掉
        assert_eq!(std::fs::read(&dest).unwrap(), b"healthy-old-jar");
        assert!(!temp.exists(), "失败后必须清理临时文件");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn sha1_mismatch_keeps_old_file() {
        let dir = tmp_root("sha");
        let dest = dir.join("plugin.jar");
        std::fs::write(&dest, b"old").unwrap();
        let temp = temp_path_for(&dest);
        std::fs::write(&temp, b"not-the-right-bytes").unwrap();
        let err = publish_temp(&temp, &dest, None, Some("0000000000000000000000000000000000000000"))
            .unwrap_err();
        assert!(err.to_string().contains("SHA1"), "{}", err);
        assert_eq!(std::fs::read(&dest).unwrap(), b"old");
        assert!(!temp.exists());
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn sha1_match_publishes() {
        let dir = tmp_root("shaok");
        let dest = dir.join("plugin.jar");
        let temp = temp_path_for(&dest);
        std::fs::write(&temp, b"abc").unwrap();
        // SHA1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        publish_temp(
            &temp,
            &dest,
            Some(3),
            Some("a9993e364706816aba3e25717850c26c9cd0d89d"),
        )
        .unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"abc");
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn cleanup_temp_removes_partial_and_leaves_dest() {
        let dir = tmp_root("cleanup");
        let dest = dir.join("old.jar");
        std::fs::write(&dest, b"old-jar").unwrap();
        let temp = temp_path_for(&dest);
        std::fs::write(&temp, b"half-written-jar").unwrap();
        assert!(cleanup_temp(&temp));
        assert!(!temp.exists());
        assert_eq!(std::fs::read(&dest).unwrap(), b"old-jar");
        // 幂等：文件已经不存在时再次清理也算成功
        assert!(cleanup_temp(&temp));
        std::fs::remove_dir_all(&dir).ok();
    }

    #[test]
    fn missing_temp_is_error_and_dest_untouched() {
        let dir = tmp_root("missing");
        let dest = dir.join("old.jar");
        std::fs::write(&dest, b"old-jar").unwrap();
        let temp = temp_path_for(&dest);
        assert!(publish_temp(&temp, &dest, Some(1), None).is_err());
        assert_eq!(std::fs::read(&dest).unwrap(), b"old-jar");
        std::fs::remove_dir_all(&dir).ok();
    }
}
