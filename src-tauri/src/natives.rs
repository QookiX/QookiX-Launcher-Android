use std::path::{Path, PathBuf};
use tokio::fs;
use anyhow::{Context, Result};
use zip::ZipArchive;
use crc32fast::Hasher;

#[derive(Debug, thiserror::Error)]
pub enum ExtractError {
    #[error("LWJGL_JAR_MISSING: {0}")]
    LwjglJarMissing(String),
    #[error("NATIVE_EXTRACT_FAILED: {0}")]
    NativeExtractFailed(String),
    #[error("ARCH_NOT_SUPPORTED: {0}")]
    ArchNotSupported(String),
}

pub struct NativesExtractor;

impl NativesExtractor {
    pub async fn extract(
        libraries_dir: &Path,
        target_arch: &str,
        cache_dir: &Path,
    ) -> Result<PathBuf> {
        let arch_prefix = match target_arch {
            "arm64-v8a" => "jni/aarch64/",
            "armeabi-v7a" => "jni/arm/",
            "x86_64" => "jni/x86_64/",
            "x86" => "jni/x86/",
            _ => return Err(ExtractError::ArchNotSupported(target_arch.to_string()).into()),
        };

        fs::create_dir_all(cache_dir).await
            .context("Failed to create cache directory")?;

        let libraries_dir = libraries_dir.to_path_buf();
        let cache_dir_clone = cache_dir.to_path_buf();
        let arch_prefix = arch_prefix.to_string();

        let extracted = tokio::task::spawn_blocking(move || {
            Self::extract_sync(&libraries_dir, &arch_prefix, &cache_dir_clone)
        }).await
        .context("Failed to run extract task")?;

        let (count, has_so) = extracted?;

        if count == 0 && !has_so {
            // 这里不再直接失败：安卓端的基础原生库（liblwjgl / libgl4es …）是随 APK 的
            // jniLibs 一起装进来的，只有额外依赖（少数模组）才需要从 jar 里补。
            // 是否真的具备启动条件，由 `launch.rs` 校验 APK 原生库目录来判定。
            crate::util::log_line(&format!(
                "libraries 中没有 {} 的 .so（jni/{}），将只使用 APK 自带原生库",
                target_arch,
                match target_arch {
                    "arm64-v8a" => "aarch64",
                    "armeabi-v7a" => "arm",
                    other => other,
                }
            ));
        }

        tracing::info!("Extracted {} native libraries for {}", count, target_arch);

        Ok(cache_dir.to_path_buf())
    }

    fn extract_sync(
        libraries_dir: &Path,
        arch_prefix: &str,
        cache_dir: &Path,
    ) -> Result<(usize, bool)> {
        let blacklist = Self::get_blacklist();
        let mut extracted_count = 0;

        // Mojang 的依赖库是 `libraries/org/lwjgl/.../xxx.jar` 这种嵌套结构，
        // 必须递归遍历，否则一个 jar 都扫不到（曾因此报 ARCH_NOT_SUPPORTED）。
        let mut jars: Vec<PathBuf> = Vec::new();
        Self::collect_jars(libraries_dir, &mut jars, 0);

        let mut budget = crate::zip_guard::ZipBudget::new();
        for path in jars {
            let file = match std::fs::File::open(&path) {
                Ok(f) => f,
                Err(_) => continue,
            };

            let mut archive = match ZipArchive::new(file) {
                Ok(a) => a,
                Err(_) => continue,
            };

            for i in 0..archive.len() {
                let mut zip_file = match archive.by_index(i) {
                    Ok(f) => f,
                    Err(_) => continue,
                };

                let name = zip_file.name().to_string();
                budget.admit(&name, zip_file.size()).map_err(anyhow::Error::msg)?;

                if !name.starts_with(arch_prefix) || !name.ends_with(".so") {
                    continue;
                }

                let so_name = name.rsplit('/').next().unwrap_or(&name).to_string();

                if blacklist.contains(&so_name.as_str()) {
                    continue;
                }

                let dest_path = cache_dir.join(&so_name);

                if dest_path.exists() {
                    if Self::verify_crc32(&dest_path, zip_file.crc32()) {
                        continue;
                    }
                }

                let tmp = dest_path.with_extension("so.part");
                let result = (|| -> Result<()> {
                    let mut out = std::fs::File::create(&tmp)?;
                    budget.copy_entry(&mut zip_file, &mut out).map_err(anyhow::Error::msg)?;
                    drop(out);
                    std::fs::rename(&tmp, &dest_path)?;
                    Ok(())
                })();
                if result.is_err() { let _ = std::fs::remove_file(&tmp); }
                result?;

                extracted_count += 1;
            }
        }

        let has_so = Self::has_any_so_sync(cache_dir);

        Ok((extracted_count, has_so))
    }

    /// 递归收集目录下所有 .jar（限深度，避免误入超深目录）。
    fn collect_jars(dir: &Path, out: &mut Vec<PathBuf>, depth: usize) {
        if depth > 8 {
            return;
        }
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                Self::collect_jars(&path, out, depth + 1);
            } else if path.extension().and_then(|s| s.to_str()) == Some("jar") {
                out.push(path);
            }
        }
    }

    pub async fn verify(cache_dir: &Path) -> Result<bool> {
        if !cache_dir.exists() {
            return Ok(false);
        }

        let mut entries = fs::read_dir(cache_dir).await
            .context("Failed to read cache directory")?;

        while let Some(entry) = entries.next_entry().await
            .context("Failed to read next entry")? {
            let path = entry.path();
            if path.extension().and_then(|s| s.to_str()) == Some("so") {
                let metadata = fs::metadata(&path).await
                    .with_context(|| format!("Failed to read metadata for {}", path.display()))?;
                if metadata.len() == 0 {
                    return Ok(false);
                }
            }
        }

        Ok(true)
    }

    fn verify_crc32(file_path: &Path, expected_crc: u32) -> bool {
        let data = match std::fs::read(file_path) {
            Ok(d) => d,
            Err(_) => return false,
        };

        let mut hasher = Hasher::new();
        hasher.update(&data);
        let actual_crc = hasher.finalize();

        actual_crc == expected_crc
    }

    fn has_any_so_sync(dir: &Path) -> bool {
        if let Ok(entries) = std::fs::read_dir(dir) {
            for entry in entries {
                if let Ok(entry) = entry {
                    if entry.path().extension().and_then(|s| s.to_str()) == Some("so") {
                        return true;
                    }
                }
            }
        }
        false
    }

    fn get_blacklist() -> Vec<&'static str> {
        vec![
            "libgl4es_114.so",
            "libgl4es_115.so",
            "libOSMesa.so",
            "libvirglrenderer.so",
            "libzink.so",
            "libfmod.so",
            "libfmodstudio.so",
            // ⚠️ 2026-10-20 真机修：**不要解压 Minecraft 自带的 LWJGL 原生库**。
            //
            // 安卓端的 LWJGL 走 Pojav 定制版：`assets/lwjgl/lwjgl-glfw-classes.jar`
            // 里的类是 3.4.0，配套的 `liblwjgl*.so` 随 APK 的 jniLibs 分发。
            // 而 MC 的 `libraries/org/lwjgl/**/natives-linux` 里是**官方 3.3.3**。
            //
            // 两边同时进 `java.library.path` 时，先命中的那份决定结果。实测
            // （OnePlus 8 / 1.21.8 + Fabric）解压出来的 3.3.3 抢先，于是：
            //   [LWJGL] [ERROR] Incompatible Java and native library versions detected.
            //   ... GLFW.<clinit> -> NoClassDefFoundError
            //   Minecraft has crashed!
            // 而 26.3 因为走 3.4.1 组件集（native 目录排在 path 最前）没暴露。
            "liblwjgl.so",
            "liblwjgl_opengl.so",
            "liblwjgl_openal.so",
            "liblwjgl_stb.so",
            "liblwjgl_tinyfd.so",
            "liblwjgl_nanovg.so",
            "liblwjgl_shaderc.so",
            "liblwjgl_spvc.so",
            "liblwjgl_spng.so",
            "liblwjgl_vma.so",
            "liblwjgl_vulkan.so",
            "liblwjgl_freetype.so",
            "liblwjgl_sdl.so",
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;

    fn create_test_jar(jar_path: &Path, entries: &[(&str, &[u8])]) {
        let file = std::fs::File::create(jar_path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        let options = zip::write::FileOptions::default();
        for (name, data) in entries {
            zip.start_file(*name, options).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }

    #[tokio::test]
    async fn test_extract_finds_so_for_arm64() {
        let lib_dir = tempdir().unwrap();
        let cache_dir = tempdir().unwrap();

        let jar_path = lib_dir.path().join("test-lwjgl.jar");
        create_test_jar(&jar_path, &[
            ("jni/aarch64/libtest.so", b"fake arm64 so"),
            ("jni/arm/libtest.so", b"fake arm32 so"),
            ("some/class.class", b"class data"),
        ]);

        let result = NativesExtractor::extract(
            lib_dir.path(),
            "arm64-v8a",
            cache_dir.path(),
        ).await;

        assert!(result.is_ok());
        let so_file = cache_dir.path().join("libtest.so");
        assert!(so_file.exists());
        let content = std::fs::read(&so_file).unwrap();
        assert_eq!(content, b"fake arm64 so");
    }

    #[tokio::test]
    async fn test_extract_finds_so_for_arm32() {
        let lib_dir = tempdir().unwrap();
        let cache_dir = tempdir().unwrap();

        let jar_path = lib_dir.path().join("test-lwjgl.jar");
        create_test_jar(&jar_path, &[
            ("jni/aarch64/libtest.so", b"fake arm64 so"),
            ("jni/arm/libtest.so", b"fake arm32 so"),
        ]);

        let result = NativesExtractor::extract(
            lib_dir.path(),
            "armeabi-v7a",
            cache_dir.path(),
        ).await;

        assert!(result.is_ok());
        let so_file = cache_dir.path().join("libtest.so");
        assert!(so_file.exists());
        let content = std::fs::read(&so_file).unwrap();
        assert_eq!(content, b"fake arm32 so");
    }

    #[tokio::test]
    async fn test_extract_skips_blacklisted_so() {
        let lib_dir = tempdir().unwrap();
        let cache_dir = tempdir().unwrap();

        let jar_path = lib_dir.path().join("test.jar");
        create_test_jar(&jar_path, &[
            ("jni/aarch64/libgl4es_114.so", b"blacklisted"),
            ("jni/aarch64/libreal.so", b"real so"),
        ]);

        let result = NativesExtractor::extract(
            lib_dir.path(),
            "arm64-v8a",
            cache_dir.path(),
        ).await;

        assert!(result.is_ok());
        assert!(!cache_dir.path().join("libgl4es_114.so").exists());
        assert!(cache_dir.path().join("libreal.so").exists());
    }

    #[tokio::test]
    async fn test_extract_cache_skip_via_crc32() {
        let lib_dir = tempdir().unwrap();
        let cache_dir = tempdir().unwrap();

        let jar_path = lib_dir.path().join("test.jar");
        let so_data = b"test so content";
        create_test_jar(&jar_path, &[("jni/aarch64/libcached.so", so_data)]);

        NativesExtractor::extract(
            lib_dir.path(),
            "arm64-v8a",
            cache_dir.path(),
        ).await.unwrap();

        let so_file = cache_dir.path().join("libcached.so");
        let mtime_before = std::fs::metadata(&so_file).unwrap().modified().unwrap();

        std::thread::sleep(std::time::Duration::from_millis(50));

        NativesExtractor::extract(
            lib_dir.path(),
            "arm64-v8a",
            cache_dir.path(),
        ).await.unwrap();

        let mtime_after = std::fs::metadata(&so_file).unwrap().modified().unwrap();
        assert_eq!(mtime_before, mtime_after, "File should not be rewritten if CRC matches");
    }

    #[tokio::test]
    async fn test_extract_unsupported_arch() {
        let lib_dir = tempdir().unwrap();
        let cache_dir = tempdir().unwrap();

        let result = NativesExtractor::extract(
            lib_dir.path(),
            "mips",
            cache_dir.path(),
        ).await;

        assert!(result.is_err());
        let err = result.unwrap_err().to_string();
        assert!(err.contains("ARCH"));
    }

    #[tokio::test]
    async fn test_extract_no_so_files_is_not_fatal() {
        // jar 里没有 .so 不再算失败：安卓的基础原生库由 APK 的 jniLibs 提供
        let lib_dir = tempdir().unwrap();
        let cache_dir = tempdir().unwrap();

        let jar_path = lib_dir.path().join("empty.jar");
        create_test_jar(&jar_path, &[("some/class.class", b"data")]);

        let result = NativesExtractor::extract(
            lib_dir.path(),
            "arm64-v8a",
            cache_dir.path(),
        ).await;

        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_verify_returns_false_for_nonexistent_dir() {
        let result = NativesExtractor::verify(Path::new("/nonexistent/path/that/does/not/exist")).await;
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[tokio::test]
    async fn test_verify_returns_true_for_dir_with_so() {
        let cache_dir = tempdir().unwrap();
        std::fs::write(cache_dir.path().join("libtest.so"), b"valid so content").unwrap();

        let result = NativesExtractor::verify(cache_dir.path()).await;
        assert!(result.is_ok());
        assert!(result.unwrap());
    }

    #[tokio::test]
    async fn test_verify_returns_false_for_empty_so() {
        let cache_dir = tempdir().unwrap();
        std::fs::write(cache_dir.path().join("libempty.so"), b"").unwrap();

        let result = NativesExtractor::verify(cache_dir.path()).await;
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[test]
    fn test_verify_crc32_matches() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("test.so");
        let data = b"hello world";
        std::fs::write(&file_path, data).unwrap();

        let mut hasher = Hasher::new();
        hasher.update(data);
        let expected_crc = hasher.finalize();

        assert!(NativesExtractor::verify_crc32(&file_path, expected_crc));
    }

    #[test]
    fn test_verify_crc32_mismatch() {
        let dir = tempdir().unwrap();
        let file_path = dir.path().join("test.so");
        std::fs::write(&file_path, b"hello world").unwrap();

        assert!(!NativesExtractor::verify_crc32(&file_path, 999999));
    }
}
