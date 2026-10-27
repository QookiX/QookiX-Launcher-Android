use futures::StreamExt;
use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::fs;
use tokio::io::AsyncWriteExt;
use tokio::sync::Mutex;
use anyhow::{Context, Result};
use sha1::{Sha1, Digest};
use uuid::Uuid;
use crate::models::*;

lazy_static::lazy_static! {
    static ref DOWNLOAD_TASKS: Arc<Mutex<HashMap<String, DownloadProgress>>> = Arc::new(Mutex::new(HashMap::new()));
    /// 进行中下载的取消标志。
    ///
    /// 以前 `cancel_download` 只是把进度条目从表里删掉 —— 下载本身照跑不误，
    /// 前端点了「取消」没有任何效果。现在下载循环会逐块检查这个标志。
    static ref CANCEL_FLAGS: Arc<Mutex<HashMap<String, Arc<AtomicBool>>>> = Arc::new(Mutex::new(HashMap::new()));
}

/// 进度回写的最小间隔。
///
/// 完全不回写就是「一直 0%，完成时直接跳 100%」；每块都回写则频繁抢
/// `DOWNLOAD_TASKS` 的锁。200ms 与前端刷新率匹配。
const PROGRESS_INTERVAL: std::time::Duration = std::time::Duration::from_millis(200);

/// 单个候选源的下载失败原因。
enum StreamFail {
    /// 用户取消 —— 不再尝试下一个源，直接整体中止。
    Cancelled,
    /// 这个源不行（网络/SHA1），可以换下一个。
    Retry(anyhow::Error),
}

pub async fn download_file(
    url: &str,
    dest: &str,
    expected_sha1: Option<String>,
) -> Result<DownloadProgress> {
    download_file_with_cancel(url, dest, expected_sha1, None).await
}

/// 分片并发下载的阈值：小于这个大小分片反而得不偿失（多连接握手 + 落盘开销）
const CHUNK_THRESHOLD: u64 = 8 * 1024 * 1024;

/// 并发数的安全范围与默认值。
///
/// 上限**对齐桌面端**（文件 32 / 分片 16）：这里原先两边都钳在 8，用户在设置里
/// 把「同时下载文件数」拖到最大也只有桌面的四分之一并发 —— 手机上下大包
/// （几百个库 + 几千个资源对象）就一直快不起来，而同样的网络桌面端明显更快。
/// 默认值也改成与桌面一致（文件 8 / 分片 4），不只是放开上限。
/// 下限 1 = 串行；真嫌吵的用户可以自己调小。
const CONCURRENCY_MIN: i32 = 1;
const FILE_CONCURRENCY_MAX: i32 = 32;
const CHUNK_CONCURRENCY_MAX: i32 = 16;
const FILE_CONCURRENCY_DEFAULT: i32 = 8;
const CHUNK_CONCURRENCY_DEFAULT: i32 = 4;

/// 「文件级并发数」—— 从设置读（`download_threads`），读不到就用 4。
///
/// 这个字段和 `download_chunk_threads` **一直存在于 `Settings` 里**（从桌面版移植时
/// 一起带过来的，`settings.rs` 的读写也都齐），但 Android 侧的下载器从来没用过它们 ——
/// 从 `version.rs` 到 `download.rs` 全是硬编码 `const LIB_CONCURRENCY: usize = 4`，
/// 界面上也没有任何可调项。于是用户看到的就是「并行下载没得调」。
pub async fn file_concurrency() -> usize {
    match crate::settings::get_settings().await {
        Ok(s) => s.download_threads.clamp(CONCURRENCY_MIN, FILE_CONCURRENCY_MAX) as usize,
        Err(_) => FILE_CONCURRENCY_DEFAULT as usize,
    }
}

/// 「单文件分片并发数」—— 从设置读（`download_chunk_threads`），读不到就用 2。
///
/// 默认比文件级并发**更低**：分片是对同一个文件的同一条链路，条数多了收益递减，
/// 而且并发的连接都在抢同一份带宽（`download_threads` 管的是「同时下几个文件」）。
pub async fn chunk_threads() -> usize {
    match crate::settings::get_settings().await {
        Ok(s) => s.download_chunk_threads.clamp(CONCURRENCY_MIN, CHUNK_CONCURRENCY_MAX) as usize,
        Err(_) => CHUNK_CONCURRENCY_DEFAULT as usize,
    }
}

/// 用 HTTP Range 分片并发下载一个大文件，返回是否成功。
///
/// 桌面版（`QookiX-Launcher/src-tauri/src/download.rs`）早就有这套：单文件开多条连接
/// 各下一段，CDN/国内镜像对单连接限速时提升非常明显（实测常见 3~5 倍）。
/// 移植要点：
///   - 先用轻量 HEAD 探 `Accept-Ranges`，不支持就老实单流（不浪费时间）；
///   - 每片用 `Range: bytes=start-end`，落到同一文件的对应偏移；
///   - 校验放在最后（并发写没法边写边算哈希），SHA1 不匹配要删文件重下；
///   - 任何一片失败 → 整个分片下载作废，调用方回退单流（有些 CDN 嘴上说支持
///     Range、实际 416/404）。
async fn try_download_chunked(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    size: u64,
    threads: usize,
    cancels: &[Arc<AtomicBool>],
) -> anyhow::Result<bool> {
    // 调用方传的是设置里的值，这里再夹一次：0 会让下面的循环一片都不下
    let threads = threads.max(1);
    // 探 Range 支持
    let head = match client.head(url).send().await {
        Ok(r) => r,
        Err(_) => return Ok(false),
    };
    let accepts = head
        .headers()
        .get(reqwest::header::ACCEPT_RANGES)
        .and_then(|v| v.to_str().ok())
        .map(|s| s.contains("bytes"))
        .unwrap_or(false);
    if !accepts {
        return Ok(false);
    }

    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).await.ok();
    }
    // 预分配文件（各分片直接按偏移写，不追加）
    let file = fs::File::create(dest).await?;
    file.set_len(size).await?;

    let chunk_size = size.div_ceil(threads as u64);
    let mut handles = Vec::new();
    for i in 0..threads {
        let start = i as u64 * chunk_size;
        if start >= size {
            break;
        }
        let end = (start + chunk_size - 1).min(size - 1);
        let client = client.clone();
        let url = url.to_string();
        let dest = dest.to_path_buf();
        let cancels = cancels.to_vec();
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
            use tokio::io::AsyncSeekExt;
            f.seek(std::io::SeekFrom::Start(start)).await?;
            while let Some(chunk) = resp.chunk().await? {
                if cancels.iter().any(|c| c.load(Ordering::Relaxed)) {
                    anyhow::bail!("已取消");
                }
                f.write_all(&chunk).await?;
            }
            Ok::<(), anyhow::Error>(())
        }));
    }

    // 任一片失败 → 整个分片下载作废，交给调用方回退单流。**但必须先把手里的任务收干净**：
    // tokio 里 drop 一个 JoinHandle 只是 detach，被丢下的分片会继续往同一个文件里写，
    // 而调用方此刻会回退 `stream_to_file`、对同一个 dest 重新 create（截断）+ 顺序写
    // → 两边交错写，落盘文件损坏（有没有被发现，取决于后面有没有 SHA1 校验）。
    // 所以失败时先把剩余分片 abort 掉，再逐个 await 等它们真正结束。
    let mut failed = false;
    let mut rest = handles.into_iter();
    while let Some(h) = rest.next() {
        if !matches!(h.await, Ok(Ok(()))) {
            failed = true;
            break;
        }
    }
    if failed {
        for h in rest {
            h.abort();
            let _ = h.await;
        }
        return Ok(false);
    }
    Ok(true)
}

/// 带「所属安装任务取消标志」的下载。
///
/// 安装流水线（如 `version::install_version`）把它自己的 `TaskCtx.cancel_flag()`
/// 传进来，用户在「下载中心」点取消时，正在传的大文件也能**立刻**停下；
/// 否则要等整个阶段跑完才响应，体验上和「没反应」一样。
pub async fn download_file_with_cancel(
    url: &str,
    dest: &str,
    expected_sha1: Option<String>,
    install_cancel: Option<Arc<AtomicBool>>,
) -> Result<DownloadProgress> {
    let task_id = format!("dl_{}", Uuid::new_v4());

    // Apply the user's mirror setting to the download URL.
    // 候选顺序（镜像 → 官方 → BMCLAPI → Maven Central）见 mirror::candidate_urls。
    let settings = crate::settings::get_settings().await?;
    let mirror_base = crate::mirror::resolve_from(
        &settings.mirror,
        settings.mirror_custom.as_deref().unwrap_or(""),
    );
    let urls = crate::mirror::candidate_urls(&mirror_base, url);

    // Check if file already exists and matches SHA1
    // （空 sha1 = 没有校验和，跳过「已存在且校验通过」的快速路径，
    //   让下面按大小/续传的常规流程处理）
    if let Some(expected) = expected_sha1.as_deref().filter(|s| !s.is_empty()) {
        if let Ok(content) = fs::read(dest).await {
            let mut hasher = Sha1::new();
            hasher.update(&content);
            let actual_sha1 = hasher.finalize();
            if format!("{:x}", actual_sha1) == expected {
                return Ok(DownloadProgress {
                    task_id: task_id.clone(),
                    progress: 100.0,
                    message: "Already downloaded".to_string(),
                    total_bytes: content.len() as i64,
                    downloaded_bytes: content.len() as i64,
                });
            }
        }
    }

    // Create destination directory
    if let Some(parent) = Path::new(dest).parent() {
        fs::create_dir_all(parent).await
            .with_context(|| format!("Failed to create directory: {}", parent.display()))?;
    }

    // 注册本次下载的取消标志
    let cancel = {
        let mut flags = CANCEL_FLAGS.lock().await;
        let flag = Arc::new(AtomicBool::new(false));
        flags.insert(task_id.clone(), flag.clone());
        flag
    };

    let client = crate::util::http_client().await;
    let dest_path = Path::new(dest).to_path_buf();
    let mut last_err: Option<anyhow::Error> = None;

    // 大文件先试**分片并发**（桌面版早就有的能力，Android 之前没接）。
    // 只在「服务端支持 Range」且内容够大时用；任何一片失败都会回落到下面的单流。
    // `chunk_note` 记录实际走了哪条路（回传在 message 里，便于真机核对）。
    // 用 `String` 而不是 `&'static str`：原来那几处 `Box::leak` 每下载一次就永久泄漏
    // 一小段字符串（因为 `format!` 的产物没法变成 `&'static str`，只能 leak）。
    let mut chunk_note: String = "single(small)".to_string();
    for candidate in &urls {
        // 探「总长度 + 是否支持 Range」：用 1 字节的 Range 请求而不是 HEAD。
        // 实测 libraries.minecraft.net 等 CDN 的 **HEAD 不返回 Content-Length**（len=0），
        // 而 `Range: bytes=0-0` 的 206 响应里 `Content-Range: bytes 0-0/总长` 一定带总长。
        let (len, supports_range) = match client
            .get(candidate)
            .header(reqwest::header::RANGE, "bytes=0-0")
            .send()
            .await
        {
            Ok(r) if r.status() == reqwest::StatusCode::PARTIAL_CONTENT => {
                let total = r
                    .headers()
                    .get(reqwest::header::CONTENT_RANGE)
                    .and_then(|v| v.to_str().ok())
                    .and_then(|s| s.rsplit('/').next())
                    .and_then(|s| s.trim().parse::<u64>().ok())
                    .unwrap_or(0);
                (total, true)
            }
            Ok(r) => (r.content_length().unwrap_or(0), false),
            Err(_) => {
                chunk_note = "single(probe-failed)".to_string();
                continue;
            }
        };
        if len < CHUNK_THRESHOLD {
            // 探到「不够大」就直接走单流，**不再探测其它候选源**：这个探测是一次完整的
            // 往返（`Range: 0-0`），而库里几百个小文件每个都会走这条路，配了镜像时
            // 每个文件白付 2 次往返 —— 这是装卸阶段最不该有的一笔开销。
            // 单流阶段仍然会逐个候选源试，正确性不受影响。
            chunk_note = format!("single(len={len})");
            break;
        }
        if !supports_range {
            chunk_note = "single(no-range)".to_string();
            continue;
        }
        // 两个取消标志都要传下去（「下载中心」的取消 + 安装任务的取消）。原来这里写死 `None`，
        // 于是 >8MB 的文件一旦走分片，**取消就完全无效** —— 用户点了取消、界面显示已取消，
        // 后台却把文件下完了。
        let cancels: Vec<Arc<AtomicBool>> = [Some(cancel.clone()), install_cancel.clone()]
            .into_iter()
            .flatten()
            .collect();
        let threads = chunk_threads().await;
        match try_download_chunked(&client, candidate, &dest_path, len, threads, &cancels).await {
            Ok(true) => {
                // 分片下完必须校验：并发写没法边写边算哈希
                //
                // ⚠️ `Some("")` 等价于「没有校验和」（加载器 profile 的
                // libraries 就没有 sha1 —— 官方 Meta 不下发）。不特判的话，
                // 每个文件都会算出一个真实 SHA1 去和空串比，必然不匹配，
                // 于是**下载成功也会被删掉**，报 "SHA1 mismatch expected ",
                // 最终表现为「加载器依赖一个都下不来」。
                if let Some(expected) = expected_sha1.as_deref().filter(|s| !s.is_empty()) {
                    match fs::read(&dest_path).await {
                        Ok(content) => {
                            let mut h = Sha1::new();
                            h.update(&content);
                            if format!("{:x}", h.finalize()) != expected {
                                let _ = fs::remove_file(&dest_path).await;
                                chunk_note = "single(bad-hash)".to_string();
                                continue; // 校验不过，当作这个源失败，试下一个
                            }
                        }
                        Err(_) => {
                            let _ = fs::remove_file(&dest_path).await;
                            chunk_note = "single(read-failed)".to_string();
                            continue;
                        }
                    }
                }
                CANCEL_FLAGS.lock().await.remove(&task_id);
                return Ok(DownloadProgress {
                    task_id: task_id.clone(),
                    progress: 100.0,
                    // 原来写死 "chunked x4"，但实际并发数是 chunk_threads()（默认 2、用户可调）
                    message: format!("Downloaded (chunked x{threads})"),
                    total_bytes: len as i64,
                    downloaded_bytes: len as i64,
                });
            }
            Ok(false) => chunk_note = "single(range-unsupported)".to_string(),
            Err(_) => chunk_note = "single(chunk-error)".to_string(),
        }
    }
    let chunk_note = chunk_note.to_string();

    // 逐候选源下载：单个源失败（网络中断/超时/SHA1 不符）时尝试下一个
    for mapped_url in &urls {
        match stream_to_file(
            &client,
            mapped_url,
            &dest_path,
            expected_sha1.as_deref(),
            &task_id,
            &cancel,
            install_cancel.as_ref(),
            &chunk_note,
        )
        .await
        {
            Ok(task) => {
                CANCEL_FLAGS.lock().await.remove(&task_id);
                DOWNLOAD_TASKS
                    .lock()
                    .await
                    .insert(task_id.clone(), task.clone());
                return Ok(task);
            }
            Err(StreamFail::Cancelled) => {
                CANCEL_FLAGS.lock().await.remove(&task_id);
                // 半截文件必须删掉：留着会被后续的 SHA1 校验/「已存在就跳过」误判成完整文件
                let _ = fs::remove_file(&dest_path).await;
                let by_install = install_cancel
                    .as_ref()
                    .is_some_and(|f| f.load(Ordering::Relaxed));
                return Err(anyhow::anyhow!(if by_install {
                    "任务已取消"
                } else {
                    "下载已取消"
                }));
            }
            Err(StreamFail::Retry(e)) => {
                last_err = Some(e);
                continue;
            }
        }
    }

    CANCEL_FLAGS.lock().await.remove(&task_id);
    Err(last_err.unwrap_or_else(|| anyhow::anyhow!("Failed to download: {}", url)))
}

/// 并发下载一组文件（文件级并发）。
///
/// 之前 `download_libraries` / 安装流程里的库循环是**串行**的：几百个 jar 一个接一个，
/// 每次都要等一次 HTTPS 往返 + SHA1 校验，手机/移动网络下装一个实例要等很久
/// （游戏资源那部分是并发的，只有库和本体是串行 —— 这块被漏掉了）。
/// 现在按 `concurrency` 并发下载，行为与串行版一致（同样校验 SHA1、同样支持取消）。
///
/// 并发数保守取 4：移动网络下再高会互相抢带宽，反而更慢。
pub async fn download_files_concurrent(
    items: &[(String, String, Option<String>)], // (url, 目标路径, sha1)
    concurrency: usize,
    install_cancel: Option<Arc<AtomicBool>>,
) -> anyhow::Result<()> {
    let n = concurrency.max(1);
    let items = items.to_vec();
    let install_cancel = install_cancel.clone();
    let failed = std::sync::atomic::AtomicUsize::new(0);
    let failed_ref = &failed;

    futures::stream::iter(items.into_iter().map(|(url, dest, sha1)| {
        let install_cancel = install_cancel.clone();
        let failed_ref = failed_ref;
        async move {
            // 取消后不再发起新请求
            if let Some(c) = &install_cancel {
                if c.load(Ordering::Relaxed) {
                    return;
                }
            }
            if let Some(parent) = Path::new(&dest).parent() {
                let _ = fs::create_dir_all(parent).await;
            }
            if let Err(e) = download_file_with_cancel(
                &url,
                &dest,
                sha1.clone(),
                install_cancel.clone(),
            )
            .await
            {
                failed_ref.fetch_add(1, Ordering::Relaxed);
                tracing::warn!("并发下载失败 {url}: {e}");
                // 同时落盘：并发下载的 warning 只走 tracing，而本项目 tracing
                // **没有接到日志文件/logcat**，等于什么都没留下。
                //（加载器依赖下载失败时，用户只看得到「装了但起不来」。）
                crate::util::log_line(&format!("[download] 失败 {url} → {e}"));
            }
        }
    }))
    .buffer_unordered(n)
    .collect::<Vec<()>>()
    .await;

    if failed.load(Ordering::Relaxed) > 0 {
        anyhow::bail!("{} 个文件下载失败", failed.load(Ordering::Relaxed));
    }
    Ok(())
}

/// 下载**单个**源：边下边写边算 SHA1，并节流回写进度。
///
/// 三个修复（对比旧实现）：
///   1. 旧实现 `response.bytes()` 把整个响应读进内存 —— `assets` 里单个文件可以几百 MB，
///      手机上直接 OOM；现在按块 `chunk()` 流式落盘。
///   2. 旧实现校验 SHA1 时又 `fs::read` 把整个文件读**第二遍**；现在哈希在写的同时累计。
///   3. 旧实现只在**完成时**写一次进度（前端永远是 0% → 100%）；现在每 200ms 回写一次。
/// 半成品文件路径：`xxx.jar` → `xxx.jar.part`。
///
/// 单独放一个文件而不是「直接写目标名」，是为了让「下了一半」永远不会被当成完整文件
/// （跳过判断、启动校验都只看目标文件存不存在）。补上 `.part` 后缀而不是替换扩展名，
/// 免得 `a.b.c` 这种名字被改得认不出来。
fn part_path(dest: &Path) -> std::path::PathBuf {
    let mut s = dest.as_os_str().to_os_string();
    s.push(".part");
    std::path::PathBuf::from(s)
}

/// 带**重试退避**的外层：同一个源最多试 3 次（间隔 200ms / 600ms）。
///
/// 移动网络下「下到一半断流」比「连不上」常见得多，而以前这里失败就直接换下一个源 ——
/// 只有一个源的场景（官方地址）等于当场失败，换源也未必更快。
/// 配合下面的 `.part` 续传，重试只补没下完的那段，代价很小。
///
/// 只对「已经下了点东西」的失败重试：一次都没下下来说明这个源根本不通（或内容不对，
/// 例如哈希不符 —— 那时 `.part` 已被删掉），重试纯属浪费时间，直接交给调用方换源。
async fn stream_to_file(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    expected_sha1: Option<&str>,
    task_id: &str,
    cancel: &Arc<AtomicBool>,
    install_cancel: Option<&Arc<AtomicBool>>,
    chunk_note: &str,
) -> std::result::Result<DownloadProgress, StreamFail> {
    let part = part_path(dest);
    let mut last: Option<StreamFail> = None;
    for attempt in 0..3u32 {
        if attempt > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(
                200 * 3u64.pow(attempt - 1),
            ))
            .await;
        }
        match stream_to_file_once(
            client,
            url,
            dest,
            expected_sha1,
            task_id,
            cancel,
            install_cancel,
            chunk_note,
        )
        .await
        {
            Ok(p) => return Ok(p),
            Err(StreamFail::Cancelled) => return Err(StreamFail::Cancelled),
            Err(e) => {
                let landed = fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);
                last = Some(e);
                if landed == 0 {
                    break;
                }
            }
        }
    }
    Err(last.unwrap_or_else(|| StreamFail::Retry(anyhow::anyhow!("Failed to download {url}"))))
}

/// 等到任一取消标志被置位（200ms 轮询）。
///
/// 为什么轮询而不是通知：取消标志是 `AtomicBool`，设置方散落在 `cancel_download`
/// 和安装任务两处，改成 notify 要动四个文件；而对「点取消」这个动作来说，
/// 200ms 的响应延迟已经完全够用。
async fn wait_cancelled(cancel: &Arc<AtomicBool>, install: Option<&Arc<AtomicBool>>) {
    loop {
        if cancel.load(Ordering::Relaxed) || install.is_some_and(|f| f.load(Ordering::Relaxed)) {
            return;
        }
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
    }
}

async fn stream_to_file_once(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    expected_sha1: Option<&str>,
    task_id: &str,
    cancel: &Arc<AtomicBool>,
    install_cancel: Option<&Arc<AtomicBool>>,
    chunk_note: &str,
) -> std::result::Result<DownloadProgress, StreamFail> {
    // 断点续传：上次被中断留下的 `.part` 接着下。手机上「切后台被杀 / 弱网断流」很常见，
    // 从头再来意味着前面下的几百 MB 全白费 —— 这是弱网体感最差的一环。
    let part = part_path(dest);
    let existing = fs::metadata(&part).await.map(|m| m.len()).unwrap_or(0);

    let mut request = client.get(url);
    if existing > 0 {
        request = request.header(reqwest::header::RANGE, format!("bytes={existing}-"));
    }
    let mut response = request
        .send()
        .await
        .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to download {url}: {e}")))?;

    // 只有服务端真的从断点接了（206）才算续传；否则（200 / 不支持 Range）从头写
    let resumed = existing > 0 && response.status() == reqwest::StatusCode::PARTIAL_CONTENT;

    let total_size = if resumed {
        response
            .headers()
            .get(reqwest::header::CONTENT_RANGE)
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.rsplit('/').next())
            .and_then(|s| s.trim().parse::<i64>().ok())
            .unwrap_or(0)
    } else {
        response.content_length().unwrap_or(0) as i64
    };

    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent)
            .await
            .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to create directory: {e}")))?;
    }

    let mut hasher = Sha1::new();
    let mut downloaded: i64 = 0;
    let mut file = if resumed {
        // 续传时哈希要从 0 开始累计，先把已有那段补进哈希器。
        // 不做校验（没给 sha1）时就只记字节数，省掉一次整文件读。
        if expected_sha1.is_some() {
            use tokio::io::AsyncReadExt;
            let mut old = fs::File::open(&part)
                .await
                .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to read {}: {e}", part.display())))?;
            let mut buf = vec![0u8; 256 * 1024];
            loop {
                let n = old
                    .read(&mut buf)
                    .await
                    .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to hash {}: {e}", part.display())))?;
                if n == 0 {
                    break;
                }
                hasher.update(&buf[..n]);
                downloaded += n as i64;
            }
        } else {
            downloaded = existing as i64;
        }
        fs::OpenOptions::new()
            .append(true)
            .open(&part)
            .await
            .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to open {}: {e}", part.display())))?
    } else {
        fs::File::create(&part)
            .await
            .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to create {}: {e}", part.display())))?
    };

    let mut last_report = std::time::Instant::now();

    loop {
        // ⚠️ 2026-10-27 真机：`chunk().await` 会**一直阻塞到有数据**。服务器把连接
        // 挂起时（弱网/镜像抽风），循环开头的取消检查永远轮不到 —— 用户点
        // 「取消」完全没反应。现象就是：下载卡在某个百分比，界面上的按钮点了全部无效。
        //
        // 改成 select!：同时等「下一块数据」和「取消轮询」，取消最多 200ms 生效。
        // 两个取消来源都要看：本次下载自己的标志（`cancel_download`），
        // 以及所属安装任务的标志（前端「下载中心」的取消按钮）。
        let chunk = tokio::select! {
            c = response.chunk() => match c {
                Ok(Some(c)) => c,
                Ok(None) => break,
                Err(e) => {
                    return Err(StreamFail::Retry(anyhow::anyhow!(
                        "Download interrupted for {url}: {e}"
                    )))
                }
            },
            _ = wait_cancelled(cancel, install_cancel) => {
                return Err(StreamFail::Cancelled)
            }
        };
        hasher.update(&chunk);
        file.write_all(&chunk)
            .await
            .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to write file: {e}")))?;
        downloaded += chunk.len() as i64;

        if last_report.elapsed() >= PROGRESS_INTERVAL {
            last_report = std::time::Instant::now();
            write_progress(task_id, total_size, downloaded).await;
        }
    }

    file.flush()
        .await
        .map_err(|e| StreamFail::Retry(anyhow::anyhow!("Failed to flush file: {e}")))?;
    drop(file);

    // 空串 sha1 = 没有校验和（加载器 profile 就是这种情况），跳过校验
    if let Some(expected) = expected_sha1.filter(|s| !s.is_empty()) {
        let actual = format!("{:x}", hasher.finalize());
        if actual != expected {
            // 删的是 `.part`：目标文件此刻还不存在（下完才改名）
            let _ = fs::remove_file(&part).await;
            return Err(StreamFail::Retry(anyhow::anyhow!(
                "SHA1 mismatch from {url}: expected {expected}, got {actual}"
            )));
        }
    }

    // 收尾：`.part` 原子改名成正式文件。
    // 于是「目标文件存在」永远等价于「这份文件是完整的」—— 跳过判断与启动校验
    // 都不必再担心把半截文件当成下好了（弱网下这正是坏档的常见来源）。
    if let Err(e) = fs::rename(&part, dest).await {
        let _ = fs::remove_file(&part).await;
        return Err(StreamFail::Retry(anyhow::anyhow!(
            "Failed to finalize {}: {e}",
            dest.display()
        )));
    }

    let progress = if total_size > 0 {
        (downloaded as f32 / total_size as f32) * 100.0
    } else {
        100.0
    };
    let task = DownloadProgress {
        task_id: task_id.to_string(),
        progress,
        // 带上「实际走了哪条路」，真机核对分片是否生效就靠这个（见 chunk_note）
        message: format!("Downloaded {downloaded} bytes [{chunk_note}]"),
        total_bytes: total_size,
        downloaded_bytes: downloaded,
    };
    write_progress_full(task_id, task.clone()).await;
    Ok(task)
}

async fn write_progress(task_id: &str, total: i64, done: i64) {
    let progress = if total > 0 {
        (done as f32 / total as f32) * 100.0
    } else {
        0.0
    };
    write_progress_full(
        task_id,
        DownloadProgress {
            task_id: task_id.to_string(),
            progress,
            message: format!("Downloaded {done} bytes"),
            total_bytes: total,
            downloaded_bytes: done,
        },
    )
    .await;
}

async fn write_progress_full(task_id: &str, task: DownloadProgress) {
    DOWNLOAD_TASKS
        .lock()
        .await
        .insert(task_id.to_string(), task);
}

/// 取消下载：把标志置 true，下载循环下一块就会停下并删掉半截文件。
pub async fn cancel_download(task_id: &str) -> Result<()> {
    let flag = CANCEL_FLAGS.lock().await.get(task_id).cloned();
    match flag {
        Some(f) => {
            f.store(true, Ordering::Relaxed);
            Ok(())
        }
        // 找不到就说明已经结束（或 id 不对）——告诉调用方，别让前端静默以为取消了
        None => Err(anyhow::anyhow!("没有进行中的下载任务: {task_id}")),
    }
}

pub async fn get_progress(task_id: &str) -> Result<DownloadProgress> {
    let tasks = DOWNLOAD_TASKS.lock().await;
    tasks.get(task_id)
        .cloned()
        .ok_or_else(|| anyhow::anyhow!("Task not found: {}", task_id))
}

pub async fn download_libraries(
    libraries: &[crate::version::Library],
    data_dir: &str
) -> Result<()> {
    let libraries_dir = Path::new(data_dir).join("libraries");

    // 串行 → N 并发（并发数读设置，见 file_concurrency）
    let jobs: Vec<(String, String, Option<String>)> = libraries
        .iter()
        .filter_map(|lib| {
            let a = lib.downloads.as_ref()?.artifact.as_ref()?;
            let path = libraries_dir.join(&a.path);
            // 已存在**且 SHA1 一致**才跳过。
            //
            // 这里曾经只判 `path.exists()`，于是**半截 jar 会永远留着**：实测
            // `log4j-api-2.17.0.jar` 只有 196128 字节（应为 301776），
            // 现象是 1.18.2 一启动就 `NoClassDefFoundError: org/apache/logging/log4j/Logger`，
            // 而且点「安装游戏」也修不好 —— 因为文件「存在」，直接跳过了。
            if path.exists() && file_sha1_matches(&path, &a.sha1) {
                return None;
            }
            Some((a.url.clone(), path.to_string_lossy().to_string(), Some(a.sha1.clone())))
        })
        .collect();

    download_files_concurrent(&jobs, file_concurrency().await, None).await
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


