//! Java 运行时的下载（2026-10-30 从 `java.rs` 拆出）。
//!
//! 拆出来有两个原因：
//!
//! 1. `java.rs` 已经顶到体量预算，而这里要加 HTTP Range 分片并发；
//! 2. **分片是这次真正要解决的问题**：Java 运行时只从 GitHub Releases 拿
//!    （`AngelAuraMC/angelauramc-openjdk-build`），真机实测同一部手机、
//!    同一时刻 —— Modrinth 那条线 47MB/s，这个 27MB 的包只有 55KB/s，
//!    而且几分钟后会掉到 0.3KB/s（连接不死，吞吐趋零）。
//!
//!    把单连接拆成多条各下一段，是**不依赖第三方镜像**的唯一办法；
//!    镜像理论上更快，但外部服务可用性不受我们控制，不适合当唯一路径。

use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result};
use tokio::fs;

/// 进度回写间隔。与 `java.rs` 里的常量保持一致（200ms ≈ 前端刷新率）。
const JRE_PROGRESS_INTERVAL: Duration = Duration::from_millis(200);

/// 小于这个大小就不分片了：多连接握手 + 落盘开销反而不划算。
const CHUNK_MIN: u64 = 4 * 1024 * 1024;

/// 带重试的下载入口（原 `JreManager::download_with_retry`）。
pub async fn download_with_retry(
    url: &str,
    dest: &Path,
    expected_size: u64,
    progress: &(impl Fn(f32) + Send + Sync),
) -> Result<()> {
    let client = crate::util::http_client().await;

    let max_retries = 3;
    let mut last_error = None;

    for attempt in 1..=max_retries {
        match download_once(&client, url, dest, expected_size, progress).await {
            Ok(()) => return Ok(()),
            Err(e) => {
                tracing::warn!("JRE download attempt {} failed: {}", attempt, e);
                // 删掉半截文件：失败路径留 27MB 垃圾会一直占着存储
                let _ = fs::remove_file(dest).await;
                last_error = Some(e);
            }
        }
    }

    Err(anyhow::anyhow!(
        "JRE_DOWNLOAD_FAILED: download failed after {} retries: {}",
        max_retries,
        last_error.unwrap_or_else(|| anyhow::anyhow!("unknown error"))
    ))
}

/// 单次下载：先试分片并发，不行再回退单流（原 `JreManager::download_once`）。
async fn download_once(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    expected_size: u64,
    progress: &(impl Fn(f32) + Send + Sync),
) -> Result<()> {
    // ① 分片：先问清总大小（HEAD 拿不到就用镜像表里的 size），
    //    够大才值得拆。失败（含服务器不支持 Range）返回 false → 走 ②。
    let size = if expected_size > 0 {
        expected_size
    } else {
        client
            .head(url)
            .send()
            .await
            .ok()
            .and_then(|r| r.content_length())
            .unwrap_or(0)
    };
    if size >= CHUNK_MIN && try_chunked(client, url, dest, size, progress).await {
        progress(1.0);
        return Ok(());
    }

    // ② 单流回退（原有逻辑，保留不动的理由：有些 CDN 嘴上说支持 Range、
    //    实际返回 416/404，或者直接忽略 Range 回 200）。
    let mut resp = client
        .get(url)
        .send()
        .await
        .context("Failed to send download request")?;

    if !resp.status().is_success() {
        return Err(anyhow::anyhow!("HTTP {}", resp.status()));
    }

    // 服务器没给 Content-Length（分块传输）时，退回用镜像表里的 size：
    // 它既是进度条的分母，也是下面「下完没有」的校验值。
    let total = match resp.content_length() {
        Some(n) if n > 0 => n,
        _ => expected_size,
    };

    use tokio::io::AsyncWriteExt;
    let mut file = fs::File::create(dest)
        .await
        .with_context(|| format!("Failed to create file: {}", dest.display()))?;

    // 边收边写。以前这里是 `resp.bytes()` 把 27MB **整包先进内存**：
    // 低内存机型直接 OOM，而且进度只在收完之后更新一次。
    let mut written: u64 = 0;
    let mut last_report = std::time::Instant::now();
    loop {
        let chunk = match resp.chunk().await.context("Failed to read response body")? {
            Some(c) => c,
            None => break,
        };
        file.write_all(&chunk).await.context("Failed to write data")?;
        written += chunk.len() as u64;
        if total > 0 && last_report.elapsed() >= JRE_PROGRESS_INTERVAL {
            last_report = std::time::Instant::now();
            progress(written as f32 / total as f32);
        }
    }
    file.flush().await.context("Failed to flush file")?;
    drop(file);

    // 断了也算「成功」是以前的一个坑：残缺的 tar.xz 解压后是个残废的 JRE，
    // 而且坏目录会一直留着，重试也不会重新下载。这里按字节数拦掉。
    if total > 0 && written != total {
        return Err(anyhow::anyhow!(
            "JRE download incomplete: got {written} bytes, expected {total}"
        ));
    }
    progress(1.0);
    Ok(())
}

/// HTTP Range 分片并发下载。返回 `true` = 已完成；`false` = 应回退单流。
///
/// 与 `download.rs::try_download_chunked` 同源，但那边是给「下载中心」用的
/// （进度写 `DOWNLOAD_TASKS`），这里的进度要直接喂给实例详情页，所以单独一份。
async fn try_chunked(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    size: u64,
    progress: &(impl Fn(f32) + Send + Sync),
) -> bool {
    // 先探 Accept-Ranges：不支持就别浪费时间
    let supports = client
        .head(url)
        .send()
        .await
        .ok()
        .map(|r| {
            r.headers()
                .get(reqwest::header::ACCEPT_RANGES)
                .and_then(|v| v.to_str().ok())
                .map(|s| s.contains("bytes"))
                .unwrap_or(false)
        })
        .unwrap_or(false);
    if !supports {
        return false;
    }

    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).await.ok();
    }
    // 预分配：各分片按偏移直接写，不追加
    match fs::File::create(dest).await {
        Ok(f) => {
            if f.set_len(size).await.is_err() {
                return false;
            }
        }
        Err(_) => return false,
    }

    let threads = crate::download::chunk_threads().await.max(1) as u64;
    let chunk = size.div_ceil(threads);
    let done = Arc::new(AtomicU64::new(0));

    let mut handles = Vec::new();
    for i in 0..threads {
        let start = i * chunk;
        if start >= size {
            break;
        }
        let end = (start + chunk - 1).min(size - 1);
        let client = client.clone();
        let url = url.to_string();
        let dest = dest.to_path_buf();
        let done = done.clone();
        handles.push(tokio::spawn(async move {
            let mut resp = client
                .get(&url)
                .header(reqwest::header::RANGE, format!("bytes={start}-{end}"))
                .send()
                .await?;
            if resp.status() != reqwest::StatusCode::PARTIAL_CONTENT {
                anyhow::bail!("分片请求未返回 206: {}", resp.status());
            }
            let mut f = fs::OpenOptions::new().write(true).open(&dest).await?;
            use tokio::io::{AsyncSeekExt, AsyncWriteExt};
            f.seek(std::io::SeekFrom::Start(start)).await?;
            while let Some(b) = resp.chunk().await? {
                f.write_all(&b).await?;
                done.fetch_add(b.len() as u64, Ordering::Relaxed);
            }
            f.flush().await?;
            Ok::<(), anyhow::Error>(())
        }));
    }

    // 边等边报进度；任一片失败就整体作废
    let mut failed = false;
    let mut rest = handles.into_iter();
    let mut last = std::time::Instant::now();
    while let Some(mut h) = rest.next() {
        loop {
            tokio::select! {
                r = &mut h => {
                    if !matches!(r, Ok(Ok(()))) {
                        failed = true;
                    }
                    break;
                }
                _ = tokio::time::sleep(Duration::from_millis(200)) => {
                    if last.elapsed() >= JRE_PROGRESS_INTERVAL {
                        last = std::time::Instant::now();
                        let p = (done.load(Ordering::Relaxed) as f32 / size as f32).min(1.0);
                        progress(p);
                    }
                }
            }
        }
        if failed {
            break;
        }
    }

    if failed {
        // ⚠️ 必须 abort + await：tokio 里 drop 一个 JoinHandle 只是 detach，
        // 被丢下的分片会继续往同一个文件里写，而调用方紧接着回退单流的
        // `File::create` 会截断它 —— 两边交错写就是损坏的文件。
        for h in rest {
            h.abort();
            let _ = h.await;
        }
        return false;
    }
    true
}
