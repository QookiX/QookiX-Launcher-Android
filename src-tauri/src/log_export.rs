//! Builds a bounded diagnostic bundle without copying files into the game directory.
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use zip::{write::FileOptions, ZipWriter};

const MAX_BYTES: u64 = 2 * 1024 * 1024;

pub(crate) fn build(data: &Path, instance: &Path, id: &str) -> anyhow::Result<PathBuf> {
    let mut sources = vec![(
        "launcher/launch.log".into(),
        data.join("logs").join(format!("launch-{id}.log")),
    )];
    sources.push((
        "launcher/last-launch-error.txt".into(),
        data.join("diagnostics/last-launch-error.txt"),
    ));
    for name in ["latest.log", "debug.log"] {
        sources.push((format!("game/{name}"), instance.join("logs").join(name)));
    }
    collect_recent(&mut sources, &instance.join("logs"), "game", 8, |n| {
        n.ends_with(".log") && n != "latest.log" && n != "debug.log"
    });
    collect_recent(
        &mut sources,
        &instance.join("crash-reports"),
        "crash-reports",
        3,
        |n| n.starts_with("crash-") && n.ends_with(".txt"),
    );
    collect_recent(&mut sources, instance, "jvm", 3, |n| {
        n.starts_with("hs_err_pid") && n.ends_with(".log")
    });

    let mut entries = sources
        .into_iter()
        .filter_map(|(name, path)| {
            let text = tail(&path, MAX_BYTES).ok()?;
            if name == "launcher/last-launch-error.txt"
                && !text
                    .lines()
                    .any(|line| line.strip_prefix("实例：") == Some(id))
            {
                return None;
            }
            (!text.trim().is_empty()).then_some((name, text))
        })
        .peekable();
    anyhow::ensure!(entries.peek().is_some(), "这个实例还没有可导出的日志");
    let output_dir = data.join("log-exports");
    fs::create_dir_all(&output_dir)?;
    let path = output_dir.join(format!("qookix-logs-{id}.zip"));
    let partial = output_dir.join(format!("{}.tmp", uuid::Uuid::new_v4()));
    let result = (|| -> anyhow::Result<()> {
        let mut zip = ZipWriter::new(File::create(&partial)?);
        let opts = FileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        for (name, text) in entries {
            zip.start_file(name, opts)?;
            zip.write_all(redact(&text).as_bytes())?;
        }
        zip.finish()?;
        fs::rename(&partial, &path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&partial);
    }
    result?;
    Ok(path)
}

fn collect_recent(
    sources: &mut Vec<(String, PathBuf)>,
    dir: &Path,
    prefix: &str,
    limit: usize,
    accepts: impl Fn(&str) -> bool,
) {
    let Ok(files) = fs::read_dir(dir) else { return };
    let mut files = files
        .filter_map(Result::ok)
        .filter_map(|entry| {
            if !entry.file_type().ok()?.is_file() {
                return None;
            }
            let name = entry.file_name().into_string().ok()?;
            if !accepts(&name) || name.contains(['/', '\\']) {
                return None;
            }
            Some((entry.metadata().ok()?.modified().ok()?, name, entry.path()))
        })
        .collect::<Vec<_>>();
    files.sort_unstable_by(|a, b| b.0.cmp(&a.0).then_with(|| a.1.cmp(&b.1)));
    sources.extend(
        files
            .into_iter()
            .take(limit)
            .map(|(_, name, path)| (format!("{prefix}/{name}"), path)),
    );
}

fn tail(path: &Path, limit: u64) -> std::io::Result<String> {
    if !fs::symlink_metadata(path)?.is_file() {
        return Err(std::io::ErrorKind::InvalidInput.into());
    }
    let mut file = File::open(path)?;
    let len = file.metadata()?.len();
    file.seek(SeekFrom::Start(len.saturating_sub(limit)))?;
    let mut bytes = Vec::new();
    file.take(limit).read_to_end(&mut bytes)?;
    let text = String::from_utf8_lossy(&bytes);
    Ok(if len > limit {
        // Drop the partial first line; a credential label may be before the cut.
        let rest = text.split_once('\n').map_or("", |(_, rest)| rest);
        format!("[仅保留末尾 {} KiB]\n{rest}", limit / 1024)
    } else {
        text.into_owned()
    })
}

fn redact(text: &str) -> String {
    static SECRET: OnceLock<regex::Regex> = OnceLock::new();
    SECRET.get_or_init(|| regex::Regex::new(
        r#"(?i)(--(?:accessToken|clientId|xuid|username|uuid)(?:\s*[,=]\s*|[ \t]+)|\b(?:access[_-]?token|refresh[_-]?token|client[_-]?secret|password|api[_-]?key)["']?[ \t]*[:=][ \t]*|\bBearer[ \t]+)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,\]}]+)"#,
    ).unwrap()).replace_all(text, "${1}[REDACTED]").into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exports_launcher_only_and_filters_other_instances() {
        let temp = tempfile::tempdir().unwrap();
        let data = temp.path();
        fs::create_dir_all(data.join("logs")).unwrap();
        fs::create_dir_all(data.join("diagnostics")).unwrap();
        fs::write(
            data.join("logs/launch-a.log"),
            "--accessToken, abc --username example\nBearer xyz\nError: stopped",
        )
        .unwrap();
        fs::write(
            data.join("diagnostics/last-launch-error.txt"),
            "实例：b\n错误：unrelated",
        )
        .unwrap();
        let path = build(data, &data.join("missing-game"), "a").unwrap();
        let mut zip = zip::ZipArchive::new(File::open(path).unwrap()).unwrap();
        assert_eq!(zip.len(), 1);
        let mut text = String::new();
        zip.by_name("launcher/launch.log")
            .unwrap()
            .read_to_string(&mut text)
            .unwrap();
        assert_eq!(
            text,
            "--accessToken, [REDACTED] --username [REDACTED]\nBearer [REDACTED]\nError: stopped"
        );
    }

    #[test]
    fn includes_crash_and_jvm_reports_without_mutating_the_instance() {
        let temp = tempfile::tempdir().unwrap();
        let game = temp.path().join("game");
        fs::create_dir_all(game.join("crash-reports")).unwrap();
        fs::write(game.join("crash-reports/crash-a.txt"), "crash").unwrap();
        fs::write(game.join("hs_err_pid2.log"), "JVM failure").unwrap();
        let path = build(temp.path(), &game, "instance").unwrap();
        let zip = zip::ZipArchive::new(File::open(path).unwrap()).unwrap();
        let mut names = zip.file_names().collect::<Vec<_>>();
        names.sort_unstable();
        assert_eq!(names, ["crash-reports/crash-a.txt", "jvm/hs_err_pid2.log"]);
        assert!(!game.join("logs").exists());
    }
}
