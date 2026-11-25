use super::{download_files_reporting, file_concurrency};
use anyhow::Result;
use sha1::{Digest, Sha1};
use std::path::Path;

pub async fn download_libraries(libraries: &[crate::version::Library], data_dir: &str) -> Result<()> {
    download_libraries_tracked(libraries, data_dir, None).await
}

pub async fn download_libraries_tracked(
    libraries: &[crate::version::Library],
    data_dir: &str,
    ctx: Option<&crate::progress::TaskCtx>,
) -> Result<()> {
    let libraries_dir = Path::new(data_dir).join("libraries");
    let mut total = 0;
    let jobs: Vec<_> = library_artifacts(libraries)
        .filter_map(|a| {
            total += 1;
            let path = libraries_dir.join(&a.path);
            if path.exists() && file_sha1_matches(&path, &a.sha1) {
                return None;
            }
            Some((a.url.clone(), path.to_string_lossy().to_string(), Some(a.sha1.clone())))
        })
        .collect();

    let mut done = total - jobs.len();
    if let Some(ctx) = ctx {
        ctx.check_cancelled()?;
        emit_loader_libraries(ctx, done, total);
    }
    let result = download_files_reporting(
        &jobs,
        file_concurrency().await,
        ctx.map(crate::progress::TaskCtx::cancel_flag),
        || {
            done += 1;
            if let Some(ctx) = ctx {
                emit_loader_libraries(ctx, done, total);
            }
        },
    )
    .await;
    if let Some(ctx) = ctx {
        ctx.check_cancelled()?;
    }
    result
}

fn library_artifacts(
    libraries: &[crate::version::Library],
) -> impl Iterator<Item = &crate::version::LibraryArtifact> {
    libraries.iter().filter_map(|lib| lib.downloads.as_ref()?.artifact.as_ref())
}

/// End-of-retry snapshot against the original artifact set, not the retry subset.
pub fn report_loader_libraries(
    ctx: &crate::progress::TaskCtx,
    libraries: &[crate::version::Library],
    libraries_dir: &Path,
) {
    let (done, total) = ready_library_count(libraries, libraries_dir);
    emit_loader_libraries(ctx, done, total);
}

fn ready_library_count(libraries: &[crate::version::Library], dir: &Path) -> (usize, usize) {
    library_artifacts(libraries).fold((0, 0), |(done, total), a| {
        (done + usize::from(file_sha1_matches(&dir.join(&a.path), &a.sha1)), total + 1)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn progress_counts_verified_and_existing_artifacts_only() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("valid.jar"), b"abc").unwrap();
        std::fs::write(dir.path().join("bad.jar"), b"wrong").unwrap();
        let sha = "a9993e364706816aba3e25717850c26c9cd0d89d";
        let artifact = |name: &str, sha1: &str| serde_json::json!({
            "name": name, "downloads": {"artifact": {"path": name, "sha1": sha1, "url": "", "size": 3}}
        });
        let libraries: Vec<crate::version::Library> = serde_json::from_value(serde_json::json!([
            artifact("valid.jar", sha), artifact("bad.jar", sha), artifact("missing.jar", ""), {"name": "no artifact"}
        ])).unwrap();
        assert_eq!(ready_library_count(&libraries, dir.path()), (1, 3));
        std::fs::write(dir.path().join("bad.jar"), b"abc").unwrap();
        assert_eq!(ready_library_count(&libraries, dir.path()), (2, 3));
    }
}

fn emit_loader_libraries(ctx: &crate::progress::TaskCtx, done: usize, total: usize) {
    if total != 0 {
        crate::progress::emit_install(ctx, "loader-libraries", "正在下载加载器依赖库…", done, total);
    }
}

/// 本地文件是否与期望的 SHA1 一致（用于「已存在就跳过」之前的完整性校验）。
///
/// 读不到文件时返回 false（当作需要重下）。只在校验库文件时调用，
/// 文件不大（几 MB），整file 读进来算哈希足够快。
fn file_sha1_matches(path: &Path, expected_sha1: &str) -> bool {
    // 没有校验和时，「匹配」按 true 处理（文件存在即可）——
    // 否则加载器依赖会因为 sha1 为空而被反复判成「已损坏」重下。
    if expected_sha1.is_empty() {
        return path.exists();
    }
    let Ok(content) = std::fs::read(path) else {
        return false;
    };
    let mut hasher = Sha1::new();
    hasher.update(&content);
    format!("{:x}", hasher.finalize()) == expected_sha1
}
