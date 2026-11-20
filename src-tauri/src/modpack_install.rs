//! 内容中心整合包安装与「误当模组安装」拦截。
//!
//! 从 `browse.rs` 独立拆出（保持 `browse.rs` 在文件体量预算内），提供：
//! 1. 内容中心整合包的直链解析、临时缓存下载与 `modpack::import_modpack` 导入闭环；
//! 2. 整合包扩展名与压缩包清单识别，防止 `.mrpack` / 整合包 `.zip` 落入 `mods/`。

use std::io::Read;
use std::path::{Path, PathBuf};

use anyhow::{bail, Context, Result};

const MANIFEST_READ_LIMIT: u64 = 64 * 1024;

/// 临时下载包守卫：作用域退出（含错误早退）时清理临时文件。
struct TempFileGuard(PathBuf);

impl Drop for TempFileGuard {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

/// 按文件名后缀判断是否为明确的整合包扩展名（`.mrpack`）。
pub fn is_modpack_filename(filename: &str) -> bool {
    filename.trim().to_ascii_lowercase().ends_with(".mrpack")
}

/// 判断本地归档文件是否为整合包（`.mrpack` 或含整合包清单的 `.zip`）。
pub fn is_modpack_archive(path: &Path, filename: &str) -> bool {
    if is_modpack_filename(filename) {
        return true;
    }
    if !filename.trim().to_ascii_lowercase().ends_with(".zip") {
        return false;
    }
    let Ok(file) = std::fs::File::open(path) else {
        return false;
    };
    let Ok(mut archive) = zip::ZipArchive::new(file) else {
        return false;
    };
    if archive.by_name("modrinth.index.json").is_ok() || archive.by_name("modpack.json").is_ok() {
        return true;
    }
    if let Ok(entry) = archive.by_name("manifest.json") {
        let mut raw = String::new();
        if entry
            .take(MANIFEST_READ_LIMIT)
            .read_to_string(&mut raw)
            .is_ok()
        {
            if let Ok(val) = serde_json::from_str::<serde_json::Value>(&raw) {
                let is_cf_type = val["manifestType"].as_str() == Some("minecraftModpack");
                let has_cf_shape = val["minecraft"]["version"].is_string() && val["files"].is_array();
                return is_cf_type || has_cf_shape;
            }
        }
    }
    false
}

/// 在普通内容 / 服务端内容安装前拦截 `.mrpack` 整合包文件。
pub fn reject_modpack_file(file_name: &str) -> Result<()> {
    if is_modpack_filename(file_name) {
        bail!(
            "检测到这是整合包文件（{file_name}），不能当作模组安装到现有实例，请在「整合包」分类安装或通过「新建实例 → 导入整合包」导入"
        );
    }
    Ok(())
}

/// 在本地文件导入前拦截整合包归档（返回 `Result<(), String>` 供本地导入复用）。
pub fn reject_modpack_archive(path: &Path, filename: &str) -> std::result::Result<(), String> {
    if is_modpack_archive(path, filename) {
        return Err(
            "检测到这是整合包文件，不能当作模组导入到现有实例，请前往「新建实例 → 导入整合包」导入"
                .into(),
        );
    }
    Ok(())
}

/// 从内容中心下载整合包归档并导入为新实例，完成后清理缓存并通知实例列表刷新。
pub async fn install_modpack(
    provider: &str,
    project_id: &str,
    version_id: &str,
    title: Option<&str>,
) -> Result<crate::modpack::ModpackImportResult> {
    let (file_url, file_name) = crate::browse::resolve_content_file(provider, version_id).await?;
    if file_url.is_empty() {
        if provider == "curseforge" {
            bail!("该 CurseForge 整合包作者禁止第三方直链下载，请手动下载后前往「新建实例 → 导入整合包」");
        }
        bail!("未能获取整合包下载地址");
    }
    if file_name.is_empty() || !crate::util::is_safe_filename(&file_name) {
        bail!("非法的整合包文件名: {file_name}");
    }

    let trimmed_title = title.map(str::trim).filter(|s| !s.is_empty());
    let display_name = match trimmed_title {
        Some(t) => t.to_string(),
        None => {
            let fetched = if provider == "modrinth" {
                crate::browse::project_info("modrinth", project_id)
                    .await
                    .map(|i| i.title)
                    .unwrap_or_default()
            } else {
                String::new()
            };
            if fetched.is_empty() {
                file_name.clone()
            } else {
                fetched
            }
        }
    };

    let data_dir = crate::settings::get_data_dir().await?;
    let cache_dir = PathBuf::from(data_dir).join("cache").join("modpacks");
    tokio::fs::create_dir_all(&cache_dir)
        .await
        .with_context(|| format!("创建整合包缓存目录失败: {}", cache_dir.display()))?;
    let tmp_path = cache_dir.join(format!("{}-{file_name}", uuid::Uuid::new_v4()));
    let _guard = TempFileGuard(tmp_path.clone());
    let tmp_str = tmp_path.to_string_lossy().to_string();

    let provider_label = if provider == "curseforge" {
        "CurseForge"
    } else {
        "Modrinth"
    };
    let ctx = crate::progress::TaskCtx::new(
        "",
        &display_name,
        &format!("{provider_label} 整合包：{display_name}"),
    );
    crate::progress::emit_install(
        &ctx,
        "modpack-install",
        &format!("正在下载整合包 {file_name}…"),
        0,
        1,
    );

    let download_result = crate::download::download_file_with_cancel(
        &file_url,
        &tmp_str,
        None,
        Some(ctx.cancel_flag()),
    )
    .await;
    match download_result {
        Ok(p) => {
            crate::progress::emit_download(
                &ctx,
                "content",
                1,
                1,
                &file_name,
                p.downloaded_bytes.max(0) as u64,
                p.total_bytes.max(0) as u64,
                true,
            );
            crate::progress::emit_install_done(
                &ctx,
                true,
                "整合包下载完成，开始导入…",
                1,
                1,
            );
        }
        Err(e) => {
            crate::progress::emit_install_done(
                &ctx,
                false,
                &format!("整合包下载失败：{e}"),
                0,
                1,
            );
            return Err(e);
        }
    }

    let res = crate::modpack::import_modpack(&tmp_str).await?;
    crate::app_state::mark_instances_dirty();
    Ok(res)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn make_temp_zip(name: &str, entries: &[(&str, &str)]) -> (PathBuf, TempFileGuard) {
        let path = std::env::temp_dir().join(format!("qookix-test-{}-{name}", uuid::Uuid::new_v4()));
        let file = std::fs::File::create(&path).expect("create temp archive");
        let mut zw = zip::ZipWriter::new(file);
        let opts = zip::write::FileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        for (entry_name, content) in entries {
            zw.start_file(*entry_name, opts).expect("start zip entry");
            zw.write_all(content.as_bytes()).expect("write zip entry");
        }
        zw.finish().expect("finish zip");
        let guard = TempFileGuard(path.clone());
        (path, guard)
    }

    #[test]
    fn modpack_filename_detection() {
        assert!(is_modpack_filename("pack.mrpack"));
        assert!(is_modpack_filename("  BetterMC.MRPACK "));
        assert!(!is_modpack_filename("sodium.jar"));
        assert!(!is_modpack_filename("faithful.zip"));
    }

    #[test]
    fn modpack_archive_detection_distinguishes_modpacks_and_regular_content() {
        let mrpack = Path::new("nonexistent.mrpack");
        assert!(is_modpack_archive(mrpack, "example.mrpack"));

        let (p_mr_zip, _g1) = make_temp_zip(
            "mr.zip",
            &[("modrinth.index.json", r#"{"dependencies":{"minecraft":"1.20.1"}}"#)],
        );
        assert!(is_modpack_archive(&p_mr_zip, "mr.zip"));

        let (p_cf_zip, _g2) = make_temp_zip(
            "cf.zip",
            &[(
                "manifest.json",
                r#"{"manifestType":"minecraftModpack","minecraft":{"version":"1.20.1"},"files":[]}"#,
            )],
        );
        assert!(is_modpack_archive(&p_cf_zip, "cf.zip"));

        let (p_custom_zip, _g3) = make_temp_zip("custom.zip", &[("modpack.json", "{}")]);
        assert!(is_modpack_archive(&p_custom_zip, "custom.zip"));

        let (p_unrelated_manifest, _g4) =
            make_temp_zip("unrelated.zip", &[("manifest.json", r#"{"name":"unrelated"}"#)]);
        assert!(!is_modpack_archive(&p_unrelated_manifest, "unrelated.zip"));

        let (p_rp, _g5) =
            make_temp_zip("rp.zip", &[("pack.mcmeta", r#"{"pack":{"pack_format":15}}"#)]);
        assert!(!is_modpack_archive(&p_rp, "rp.zip"));

        let (p_jar, _g6) =
            make_temp_zip("mod.jar", &[("fabric.mod.json", r#"{"id":"sodium"}"#)]);
        assert!(!is_modpack_archive(&p_jar, "mod.jar"));
    }
}
