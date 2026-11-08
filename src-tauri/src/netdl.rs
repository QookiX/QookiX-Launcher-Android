//! 按「候选链」下载文件 / 取文本的公共实现（JRE 运行时、启动器更新包、插件包与插件清单共用）。
//!
//! ## 为什么要有一个公共实现
//!
//! GitHub 上的东西（JRE / APK / 插件）在国内的表现不是「连不上」，而是
//! 「连上以后几十 KB/s、几分钟后掉到几 KB/s」。能换源就必须换源，
//! 而换源要做的事三处完全一样：
//!
//! 1. 按 `mirror::candidate_urls` 给出的候选链逐个试（镜像 → 官方）；
//! 2. 每源限时（建连 12s / 停顿 30s），不吃「连上不回包」的亏；
//! 3. 下完立刻校验摘要，对不上就删包换源 —— 镜像返回 HTML 登录页 / 半截文件全靠这一步挡住；
//! 4. 失败源本次会话冷却，后面几百个小文件不再反复撞同一个死镜像。
//!
//! 以前这四件事分别散在 JRE 下载和更新包下载里各写一套，插件那条连重试都没有。
//!
//! ## 与 `download.rs` 的分工
//!
//! `download.rs` 是「下载中心」那条线：任务表、取消标志、UI 进度，粒度是用户可见的单个任务。
//! 这里是「基础设施」那条线：没有任务表，只有候选链和校验，供安装流水线内部调用。

use std::collections::HashMap;
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use tokio::fs;

/// 进度回写间隔（≈ 前端刷新率）。
const PROGRESS_INTERVAL: Duration = Duration::from_millis(200);

/// 小于这个大小就不分片了：多连接握手 + 落盘开销反而不划算。
const CHUNK_MIN: u64 = 4 * 1024 * 1024;

/// 建连 / 探测阶段超时。
///
/// 镜像挂掉时的表现往往不是「拒绝连接」，而是**连上不回包**，
/// 没有超时就等于卡死（真机见过「准备运行环境…」停在 137 秒以上）。
// 2026-11-08：12s → 20s。真机上镜像只做一个 301 跳转就走了 11.7s（移动网络 + 镜像排队），
// 12s 会把「其实能通、只是慢」的源直接判死，连带更新包整条链路失败。
const CONNECT_TIMEOUT: Duration = Duration::from_secs(20);

/// 单次读数据的停顿超时（>30 秒一个字节都没有就换源）。
///
/// 不是总时长超时：38MB 的包在慢速链路上正常也要几分钟，
/// 但**吞吐掉到 0** 是 GitHub 直连的典型故障（连接不死、永远不收尾）。
const STALL_TIMEOUT: Duration = Duration::from_secs(30);

/// 每个候选源的重试次数。
const ATTEMPTS_PER_CANDIDATE: usize = 2;

/// 失败源的冷却时长。
///
/// 不用「整个会话」那么长：镜像常常只是抖一下，10 分钟足够躲开安装期的
/// 几百次下载，又不至于让用户切回网络后还得重启应用。
const FAIL_COOLDOWN: Duration = Duration::from_secs(10 * 60);

/// 期望的校验值。空串等价于「没有登记摘要」，直接放行（老插件包就这情况）。
pub enum Expected<'a> {
    None,
    Sha256(&'a str),
    Sha1(&'a str),
}

impl Expected<'_> {
    fn digest(&self) -> Option<(&'static str, &str)> {
        match self {
            Expected::None => None,
            Expected::Sha256(h) if h.is_empty() => None,
            Expected::Sha1(h) if h.is_empty() => None,
            Expected::Sha256(h) => Some(("sha256", h)),
            Expected::Sha1(h) => Some(("sha1", h)),
        }
    }
}

/// 按候选链下载到 `dest`，并在成功后校验摘要。
///
/// 候选链里的每个源最多试 [`ATTEMPTS_PER_CANDIDATE`] 次；
/// 内容不符（摘要对不上）**不再重试同一个源**，直接换下一个 ——
/// 同一面镜子给你一个登录页，第二次还是那个登录页。
pub async fn download_candidates(
    candidates: &[String],
    dest: &Path,
    expected_size: u64,
    expected: Expected<'_>,
    progress: &(impl Fn(u64, u64) + Send + Sync),
) -> Result<()> {
    if candidates.is_empty() {
        return Err(anyhow::anyhow!("没有可用的下载地址"));
    }
    let client = crate::util::http_client().await;
    let mut last_error: Option<String> = None;

    for (idx, url) in candidates.iter().enumerate() {
        let is_last = idx + 1 == candidates.len();
        // 官方地址（链尾）永远试：镜像全挂时它是唯一出路，不能被冷却挡掉
        if !is_last && cooled(url) {
            crate::util::log_line(&format!("跳过 10 分钟内失败过的下载源：{url}"));
            continue;
        }

        let mut ok = false;
        for attempt in 1..=ATTEMPTS_PER_CANDIDATE {
            match download_once(&client, url, dest, expected_size, progress).await {
                Ok(()) => match verify(dest, &expected).await {
                    Ok(()) => {
                        mark_ok(url);
                        if idx > 0 {
                            crate::util::log_line(&format!(
                                "改用备用源下载成功：#{} {url}",
                                idx + 1
                            ));
                        }
                        ok = true;
                        break;
                    }
                    Err(e) => {
                        tracing::warn!("下载内容校验失败（{url}）：{e}");
                        let _ = fs::remove_file(dest).await;
                        last_error = Some(e.to_string());
                        break;
                    }
                },
                Err(e) => {
                    tracing::warn!("下载第 {attempt} 次失败（{url}）：{e}");
                    let _ = fs::remove_file(dest).await;
                    last_error = Some(e.to_string());
                }
            }
        }
        if ok {
            return Ok(());
        }
        if !is_last {
            mark_failed(url);
        }
    }

    Err(anyhow::anyhow!(
        "所有下载源都失败：{}",
        last_error.unwrap_or_else(|| "unknown error".to_string())
    ))
}

/// 按候选链取文本（插件清单 / SHA256SUMS 这类小文件）。
///
/// 比正文校验更早挡一层：镜像返回 200 + HTML 登录页时 `text()` 不会报错，
/// 拿去做 `serde_json::from_str` 只会得到一句「格式不正确」，看不出是镜像的问题。
pub async fn fetch_text(candidates: &[String]) -> Result<String> {
    if candidates.is_empty() {
        return Err(anyhow::anyhow!("没有可用的下载地址"));
    }
    let client = crate::util::http_client().await;
    let mut last_error: Option<String> = None;

    for (idx, url) in candidates.iter().enumerate() {
        let is_last = idx + 1 == candidates.len();
        if !is_last && cooled(url) {
            continue;
        }
        let resp = match tokio::time::timeout(CONNECT_TIMEOUT, client.get(url).send()).await {
            Ok(Ok(r)) => r,
            Ok(Err(e)) => {
                last_error = Some(format!("请求失败：{e}"));
                if !is_last {
                    mark_failed(url);
                }
                continue;
            }
            Err(_) => {
                last_error = Some(format!("连接超时（{CONNECT_TIMEOUT:?}）"));
                if !is_last {
                    mark_failed(url);
                }
                continue;
            }
        };
        if !resp.status().is_success() {
            last_error = Some(format!("HTTP {}", resp.status().as_u16()));
            if !is_last {
                mark_failed(url);
            }
            continue;
        }
        if looks_like_html(&resp) {
            last_error = Some("返回的是网页而不是文件（镜像登录页？）".to_string());
            if !is_last {
                mark_failed(url);
            }
            continue;
        }
        match resp.text().await {
            Ok(text) => {
                mark_ok(url);
                return Ok(text);
            }
            Err(e) => {
                last_error = Some(format!("读取失败：{e}"));
                if !is_last {
                    mark_failed(url);
                }
            }
        }
    }

    Err(anyhow::anyhow!(
        "{}",
        last_error.unwrap_or_else(|| "unknown error".to_string())
    ))
}

/// 响应是不是一个 HTML 页面（镜像挡登录 / 限流时的典型产物）。
fn looks_like_html(resp: &reqwest::Response) -> bool {
    resp.headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .map(|ct| ct.to_ascii_lowercase().contains("text/html"))
        .unwrap_or(false)
}

/// 校验文件摘要；没有登记摘要时直接通过。
async fn verify(path: &Path, expected: &Expected<'_>) -> Result<()> {
    let Some((algo, want)) = expected.digest() else {
        return Ok(());
    };
    let actual = digest_file(path, algo).await?;
    if !actual.eq_ignore_ascii_case(want) {
        return Err(anyhow::anyhow!(
            "包内容校验失败（{algo} 不符）：期望 {want}，实际 {actual}"
        ));
    }
    Ok(())
}

async fn digest_file(path: &Path, algo: &str) -> Result<String> {
    use sha1::Digest as _;
    use tokio::io::AsyncReadExt;

    let mut file = fs::File::open(path)
        .await
        .with_context(|| format!("打开下载结果失败：{}", path.display()))?;
    let mut buf = vec![0u8; 64 * 1024];

    if algo == "sha1" {
        let mut hasher = sha1::Sha1::new();
        loop {
            let n = file.read(&mut buf).await.context("读取下载结果失败")?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        return Ok(format!("{:x}", hasher.finalize()));
    }

    let mut hasher = sha2::Sha256::new();
    loop {
        let n = file.read(&mut buf).await.context("读取下载结果失败")?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

/// 单次下载：先试分片并发，不行再回退单流。
async fn download_once(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    expected_size: u64,
    progress: &(impl Fn(u64, u64) + Send + Sync),
) -> Result<()> {
    // ① 分片：先问清总大小（HEAD 拿不到就用调用方给的 size），够大才值得拆。
    let size = if expected_size > 0 {
        expected_size
    } else {
        match tokio::time::timeout(CONNECT_TIMEOUT, client.head(url).send()).await {
            Ok(Ok(r)) => r.content_length().unwrap_or(0),
            _ => 0,
        }
    };
    if size >= CHUNK_MIN && try_chunked(client, url, dest, size, progress).await {
        progress(size, size);
        return Ok(());
    }

    // ② 单流回退：有些 CDN 嘴上说支持 Range、实际返回 416/404，或者直接忽略 Range 回 200。
    let mut resp = tokio::time::timeout(CONNECT_TIMEOUT, client.get(url).send())
        .await
        .map_err(|_| anyhow::anyhow!("连接超时（{CONNECT_TIMEOUT:?}）"))?
        .context("Failed to send download request")?;

    if !resp.status().is_success() {
        return Err(anyhow::anyhow!("HTTP {}", resp.status()));
    }
    // 镜像返回网页时状态码也是 200，靠这里与摘要校验双保险
    if looks_like_html(&resp) {
        return Err(anyhow::anyhow!("返回的是网页而不是文件（镜像登录页？）"));
    }

    let total = match resp.content_length() {
        Some(n) if n > 0 => n,
        _ => expected_size,
    };

    use tokio::io::AsyncWriteExt;
    let mut file = fs::File::create(dest)
        .await
        .with_context(|| format!("Failed to create file: {}", dest.display()))?;

    let mut written: u64 = 0;
    let mut last_report = std::time::Instant::now();
    loop {
        let chunk = match tokio::time::timeout(STALL_TIMEOUT, resp.chunk()).await {
            Ok(r) => r.context("Failed to read response body")?,
            Err(_) => {
                return Err(anyhow::anyhow!(
                    "下载停顿超过 {STALL_TIMEOUT:?}（已收到 {written} 字节）"
                ))
            }
        };
        let Some(chunk) = chunk else { break };
        file.write_all(&chunk).await.context("Failed to write data")?;
        written += chunk.len() as u64;
        if last_report.elapsed() >= PROGRESS_INTERVAL {
            last_report = std::time::Instant::now();
            progress(written, total.max(written));
        }
    }
    file.flush().await.context("Failed to flush file")?;
    drop(file);

    // 断了也算「成功」是以前的一个坑：残缺的包解压后是个残废运行时 / 装不上的 APK
    if total > 0 && written != total {
        return Err(anyhow::anyhow!(
            "下载不完整：已收 {written} 字节，期望 {total}"
        ));
    }
    progress(written, total.max(written));
    Ok(())
}

/// HTTP Range 分片并发下载。返回 `true` = 已完成；`false` = 应回退单流。
async fn try_chunked(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    size: u64,
    progress: &(impl Fn(u64, u64) + Send + Sync),
) -> bool {
    // 先探 Accept-Ranges：不支持就别浪费时间（探测本身也要有超时）
    let supports = tokio::time::timeout(CONNECT_TIMEOUT, client.head(url).send())
        .await
        .ok()
        .and_then(|r| r.ok())
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
    let done = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));

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
            let mut resp = tokio::time::timeout(
                CONNECT_TIMEOUT,
                client
                    .get(&url)
                    .header(reqwest::header::RANGE, format!("bytes={start}-{end}"))
                    .send(),
            )
            .await
            .map_err(|_| anyhow::anyhow!("分片连接超时: bytes={start}-{end}"))??;
            if resp.status() != reqwest::StatusCode::PARTIAL_CONTENT {
                anyhow::bail!("分片请求未返回 206: {}", resp.status());
            }
            if looks_like_html(&resp) {
                anyhow::bail!("分片返回的是网页（镜像登录页？）");
            }
            let mut f = fs::OpenOptions::new().write(true).open(&dest).await?;
            use tokio::io::{AsyncSeekExt, AsyncWriteExt};
            f.seek(std::io::SeekFrom::Start(start)).await?;
            let mut got = 0u64;
            loop {
                let next = tokio::time::timeout(STALL_TIMEOUT, resp.chunk())
                    .await
                    .map_err(|_| anyhow::anyhow!("分片停顿超过 {STALL_TIMEOUT:?}: bytes={start}-{end}"))??;
                let Some(b) = next else { break };
                got += b.len() as u64;
                f.write_all(&b).await?;
                done.fetch_add(b.len() as u64, std::sync::atomic::Ordering::Relaxed);
            }
            // 206 后提前 EOF 时，预分配的尾部全是 0，不校验就会被当成成功
            if got != end - start + 1 {
                anyhow::bail!("分片长度不符: bytes={start}-{end} 实际 {got}");
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
                    if last.elapsed() >= PROGRESS_INTERVAL {
                        last = std::time::Instant::now();
                        progress(
                            done.load(std::sync::atomic::Ordering::Relaxed),
                            size,
                        );
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

// ═══════════════════════════════════════════════════════════════════════════
// 失败源冷却（本次会话级别）
// ═══════════════════════════════════════════════════════════════════════════

static FAILED_SOURCES: OnceLock<Mutex<HashMap<String, Instant>>> = OnceLock::new();

fn failed_sources() -> &'static Mutex<HashMap<String, Instant>> {
    FAILED_SOURCES.get_or_init(|| Mutex::new(HashMap::new()))
}

/// 取 URL 里的「源」标识（`scheme://host`）。
///
/// 冷却按源而不是按完整 URL：镜像前缀式代理是 `https://ghfast.top/<原始地址>`，
/// 同一面镜子上不同文件是不同的完整 URL，但失败原因是**同一个**（这面镜子挂了）。
fn source_key(url: &str) -> String {
    let (scheme, rest) = match url.split_once("://") {
        Some((s, r)) => (s.to_ascii_lowercase(), r),
        None => (String::new(), url),
    };
    let host = rest.split('/').next().unwrap_or(rest).to_ascii_lowercase();
    format!("{scheme}://{host}")
}

fn cooled(url: &str) -> bool {
    let key = source_key(url);
    let mut guard = crate::util::lock_recover(failed_sources());
    match guard.get(&key) {
        Some(at) => {
            if at.elapsed() < FAIL_COOLDOWN {
                true
            } else {
                guard.remove(&key);
                false
            }
        }
        None => false,
    }
}

fn mark_failed(url: &str) {
    crate::util::lock_recover(failed_sources()).insert(source_key(url), Instant::now());
}

fn mark_ok(url: &str) {
    crate::util::lock_recover(failed_sources()).remove(&source_key(url));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_key_ignores_path_and_port_case() {
        assert_eq!(
            source_key("https://ghfast.top/https://github.com/a/b.apk"),
            "https://ghfast.top"
        );
        assert_eq!(source_key("https://GitHub.com/a/b.apk"), "https://github.com");
        // 没有 scheme 的输入不该 panic（配置里手填的裸地址）
        assert_eq!(source_key("github.com/a"), "://github.com");
    }

    #[test]
    fn cooldown_is_per_source() {
        mark_failed("https://mirror.example/https://github.com/x");
        assert!(cooled("https://mirror.example/https://github.com/y"));
        assert!(!cooled("https://github.com/y"));
        mark_ok("https://mirror.example/https://github.com/x");
        assert!(!cooled("https://mirror.example/https://github.com/x"));
    }

    #[test]
    fn empty_digest_means_no_check() {
        assert!(Expected::Sha256("").digest().is_none());
        assert!(Expected::None.digest().is_none());
        assert_eq!(Expected::Sha1("abc").digest(), Some(("sha1", "abc")));
    }
}
