use std::collections::HashMap;
use std::path::Path;
use tokio::fs;
use anyhow::{Context, Result};
use serde_json::Value;

use crate::settings::get_data_dir;

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct MinecraftVersion {
    pub id: String,
    pub r#type: String,
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub sha1: Option<String>,
    #[serde(alias = "releaseTime")]
    pub released_time: Option<String>,
    #[serde(default)]
    pub inherits_from: Option<String>,
    #[serde(alias = "assetIndex")]
    pub asset_index: Option<AssetIndex>,
    pub downloads: Option<VersionDownloads>,
    pub libraries: Option<Vec<Library>>,
    #[serde(alias = "javaVersion")]
    pub java_version: Option<JavaVersion>,
    #[serde(alias = "mainClass")]
    pub main_class: Option<String>,
    pub arguments: Option<serde_json::Value>,
    pub logging: Option<LoggingConfig>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct AssetIndex {
    pub id: String,
    pub sha1: String,
    pub size: i64,
    pub url: String,
    #[serde(alias = "totalSize")]
    pub total_size: i64,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct VersionDownloads {
    pub artifact: Option<VersionArtifact>,
    pub client: Option<VersionArtifact>,
    pub server: Option<VersionArtifact>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct VersionArtifact {
    #[serde(default)]
    pub path: String,
    pub sha1: String,
    pub size: i64,
    pub url: String,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct JavaVersion {
    #[serde(default)]
    pub component: String,
    /// 部分版本的 JSON 缺 `version` 字段（如 1.20.x 只有 component + majorVersion），因此设为可选。
    #[serde(default)]
    pub version: Option<String>,
    #[serde(alias = "majorVersion")]
    pub major_version: Option<i32>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct Library {
    pub name: String,
    pub r#type: Option<String>,
    pub downloads: Option<LibraryDownloads>,
    pub natives: Option<serde_json::Value>,
    pub rules: Option<Vec<Rule>>,
    pub extract: Option<ExtractConfig>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct LibraryDownloads {
    pub artifact: Option<LibraryArtifact>,
    pub classifiers: Option<serde_json::Value>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct LibraryArtifact {
    pub path: String,
    pub sha1: String,
    pub size: i64,
    pub url: String,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct Rule {
    pub action: String,
    pub features: Option<serde_json::Value>,
    pub os: Option<OsRule>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct OsRule {
    pub name: String,
    pub version: Option<String>,
    pub arch: Option<String>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct ExtractConfig {
    pub exclude: Vec<String>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct LoggingConfig {
    pub client: Option<ClientLogging>,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct ClientLogging {
    pub argument: String,
    pub file: LoggingFile,
    pub r#type: String,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct LoggingFile {
    pub id: String,
    pub sha1: String,
    pub size: i64,
    pub url: String,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct VersionList {
    pub versions: Vec<MinecraftVersion>,
    pub latest: LatestVersion,
}

#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct LatestVersion {
    pub release: String,
    pub snapshot: String,
}

// ==================== Public Functions ====================

pub async fn fetch_manifest() -> Result<VersionList> {
    let data_dir = get_data_dir().await?;
    let cache_file = Path::new(&data_dir).join("cache/version_list.json");

    // Check cache (24 hours)
    if let Ok(metadata) = fs::metadata(&cache_file).await {
        if let Ok(modified) = metadata.modified() {
            let elapsed = std::time::SystemTime::now()
                .duration_since(modified)
                .unwrap_or_default();
            if elapsed.as_secs() < 86400 {
                let content = fs::read_to_string(&cache_file).await?;
                return Ok(serde_json::from_str(&content)?);
            }
        }
    }

    // Fetch manifest, respecting the user's mirror setting
    let settings = crate::settings::get_settings().await?;
    let mirror_base = crate::mirror::resolve_from(
        &settings.mirror,
        settings.mirror_custom.as_deref().unwrap_or(""),
    );
    let url = crate::mirror::manifest_url(&mirror_base);

    let client = crate::util::http_client().await;
    // 把 URL 与底层 reqwest 错误一起带出来：以前只留一句 context，
    // 排查时既不知道请求的是哪个地址、也不知道是超时/TLS/DNS 哪种失败。
    //
    // ⚠️ 2026-10-20 真机修：**官方 + 内置镜像并行**，并加硬超时。
    //
    // 真机实测（OnePlus 8 / 正式签名包 / 首次启动）：
    //   `get_version_manifest` 卡满 90 秒前端超时，界面报「版本列表加载失败」，
    //   用户**根本没法创建实例**。而同一时刻设备上 curl 官方只要 1.0 秒、
    //   镜像 0.5 秒 —— 说明单请求路径会卡在 TLS/连接建立上（首启尤其明显）。
    // 这里改成两个候选并行发、各 30 秒硬超时，谁先回用谁。
    // ⚠️ 2026-10-27：必须**尊重设置**。以前无条件把 BMCLAPI 当候选并行发，
    // 用户明明选了「官方源」，先回来的却是镜像那份清单 —— 于是后续所有
    // 客户端/库/资源下载链接都是 bmclapi 的（真机表现：「我改成官方了，
    // 补全时链接还是 bmclapi」）。
    // 现在只有设置里确实选了镜像（或自定义）才把镜像作为候选。
    let base = crate::mirror::current_base();
    let mut candidates: Vec<String> = Vec::new();
    if !base.is_empty() {
        let mirrored = crate::mirror::manifest_url(&base);
        if mirrored != url {
            candidates.push(mirrored);
        }
    }
    candidates.push(url.clone());

    let per_try = std::time::Duration::from_secs(30);
    let mut tasks = Vec::new();
    for u in candidates {
        let client = client.clone();
        // 镜像对自定义 UA 返回 302 空响应（见 browse.rs 的同款注释），改中性 UA。
        let is_mirror = u.contains("bangbang93.com");
        tasks.push(Box::pin(async move {
            let mut req = client.get(&u);
            if is_mirror {
                req = req.header(reqwest::header::USER_AGENT, "Mozilla/5.0");
            }
            match tokio::time::timeout(per_try, req.send()).await {
                Ok(Ok(resp)) => match tokio::time::timeout(per_try, resp.text()).await {
                    Ok(Ok(t)) => (u, Ok(t)),
                    Ok(Err(e)) => (u.clone(), Err(format!("{u} 读取失败: {e}"))),
                    Err(_) => (u.clone(), Err(format!("{u} 读取超时"))),
                },
                Ok(Err(e)) => {
                    // 走一遍 source 链：reqwest 的 Display 只有一句
                    // 「error sending request」，真正原因（DNS / connect / TLS / 超时）
                    // 在 source 里。
                    let mut chain = vec![e.to_string()];
                    let mut src: Option<&(dyn std::error::Error + 'static)> =
                        std::error::Error::source(&e);
                    while let Some(s) = src {
                        chain.push(s.to_string());
                        src = s.source();
                    }
                    (u.clone(), Err(format!("{u} -> {}", chain.join(" | "))))
                }
                Err(_) => (u.clone(), Err(format!("{u} 连接超时"))),
            }
        }));
    }
    // ⚠️ 必须「谁先成功用谁」，不能用 `join_all` 等所有任务收尾。
    //
    // 真机实测：镜像 `bmclapi2/mc/game/version_manifest_v2.json` 会 302 到
    // `8.749333.xyz:443/download/...`，而那个下载域名**连不上** —— 请求会一直
    // 挂到 30 秒超时。`join_all` 会把官方的 0.98 秒成功也一起拖到 30 秒后，
    // 叠加首启开销就超过前端窗口，界面报「版本列表加载失败」。
    // 用 `select_all`：官方 1 秒回就立刻用，镜像慢慢失败也不影响。
    let mut pending = futures::future::select_all(tasks);
    let mut content: Option<String> = None;
    let mut errs: Vec<String> = Vec::new();
    loop {
        let (r, _idx, rest) = pending.await;
        match r {
            (u, Ok(t)) => {
                crate::util::log_line(&format!("[manifest] 拉取成功: {u}"));
                println!("[manifest] 拉取成功: {u}");
                content = Some(t);
                break;
            }
            (_, Err(e)) => errs.push(e),
        }
        if rest.is_empty() {
            break;
        }
        pending = futures::future::select_all(rest);
    }
    let content = match content {
        Some(c) => c,
        None => {
            let detail = errs.join(" | ");
            println!("[manifest] 拉取失败: {detail}");
            return Err(anyhow::anyhow!(
                "Failed to fetch version manifest: {detail}"
            ));
        }
    };
    let version_list: VersionList = match serde_json::from_str(&content) {
        Ok(v) => v,
        Err(e) => {
            // 把长度与内容前缀一起报出来：解析失败最常见的原因是
            // 代理/镜像返回了一个错误页或限流页，而不是清单本身有问题。
            return Err(anyhow::anyhow!(
                "Failed to parse version manifest: len={} err={e} head={}",
                content.len(),
                content.chars().take(160).collect::<String>()
            ));
        }
    };

    // Cache the result
    if let Some(parent) = cache_file.parent() {
        fs::create_dir_all(parent).await.ok();
    }
    fs::write(&cache_file, &content).await.ok();

    Ok(version_list)
}

/// 读取本地版本 JSON。返回 (内容, 是否成功读到)；
/// 文件不存在或读取出错时返回 (空串, false)。
async fn read_version_content(json_file: &Path) -> (String, bool) {
    match fs::read_to_string(json_file).await {
        Ok(content) => (content, true),
        Err(_) => (String::new(), false),
    }
}

/// 从清单定位版本并下载其 JSON 到 `json_file`（优先镜像，失败回退官方源）。
async fn download_version_json(
    version_id: &str,
    version_dir: &Path,
    json_file: &Path,
) -> Result<String> {
    let manifest = fetch_manifest().await?;
    let entry = manifest
        .versions
        .iter()
        .find(|v| v.id == version_id)
        .ok_or_else(|| anyhow::anyhow!("Version not found in manifest: {}", version_id))?;

    // 优先走用户配置的镜像；失败（如镜像站缺少该文件）时回退官方源
    let settings = crate::settings::get_settings().await?;
    let mirror_base = crate::mirror::resolve_from(
        &settings.mirror,
        settings.mirror_custom.as_deref().unwrap_or(""),
    );
    let urls: Vec<String> = {
        let mut list = Vec::new();
        if !mirror_base.is_empty() {
            list.push(crate::mirror::map(&mirror_base, &entry.url));
        }
        list.push(entry.url.clone());
        list
    };

    fs::create_dir_all(version_dir).await.ok();
    let client = crate::util::http_client().await;
    let mut downloaded: Option<String> = None;
    for url in &urls {
        match client.get(url).send().await {
            Ok(resp) => {
                let status = resp.status();
                // 镜像站可能用 200 返回错误页 HTML，这里必须同时校验内容确为 JSON
                if status.is_success() {
                    if let Ok(content) = resp.text().await {
                        if content.trim_start().starts_with('{') {
                            downloaded = Some(content);
                            break;
                        }
                        // 内容非法（镜像返回错误页）→ 尝试下一个源
                        continue;
                    }
                }
            }
            Err(_) => continue,
        }
    }
    let content = downloaded
        .ok_or_else(|| anyhow::anyhow!("Failed to download version JSON: {}", version_id))?;
    fs::write(json_file, &content).await
        .with_context(|| format!("Failed to write version JSON: {}", json_file.display()))?;

    Ok(content)
}

/// 该版本的游戏文件是否已安装（客户端 jar 存在且非空即视为已装）。
///
/// 用于启动前兜底：空实例（`instances/<id>` 里只有 instance.json、没有下载过任何游戏文件）
/// 直接启动的话，JVM 会因为找不到 client jar 立刻失败 ——
/// 用户看到的是「黑屏 / 秒退」，日志里只有一堆 LWJGL 报错，完全摸不着头脑。
pub async fn is_version_installed(version_id: &str) -> bool {
    let Ok(data_dir) = get_data_dir().await else {
        return false;
    };
    let jar = Path::new(&data_dir)
        .join("versions")
        .join(version_id)
        .join(format!("{version_id}.jar"));
    match tokio::fs::metadata(&jar).await {
        Ok(m) => m.len() > 0,
        Err(_) => false,
    }
}

/// 实例是否已安装：读 `instance.json` 拿版本号，再看该版本的客户端 jar 是否存在。
///
/// 安卓端的启动入口（`commands::launch_game`）只负责拉起 GameActivity，
/// 真正的 JVM 启动在 Activity 里 —— 必须在**拉起之前**就检查，否则空实例会：
/// 黑屏一下再弹回启动器（用户看到「秒退 / 黑屏」，完全不知道是没装）。
pub async fn is_instance_installed(instance_id: &str) -> bool {
    let Ok(inst_root) = crate::settings::instances_root().await else {
        return false;
    };
    let inst = inst_root.join(instance_id).join("instance.json");
    let Ok(txt) = tokio::fs::read_to_string(&inst).await else {
        return false;
    };
    let Ok(v) = serde_json::from_str::<Value>(&txt) else {
        return false;
    };
    let Some(ver) = v["mc_version"].as_str().or_else(|| v["mcVersion"].as_str()) else {
        return false;
    };
    is_version_installed(ver).await
}

/// 版本 JSON 的 `inherits_from` 链最大深度。
///
/// 真实继承链最多两三层（`1.20.1-forge-47.2.0` → `1.20.1`），8 已经非常宽松。
/// **这个上限是用来防栈溢出的**：原来是「只要发现 inherits_from 就递归」，只用
/// `inherits_from != version_id` 挡了自环 —— 一份损坏或被改坏的版本 JSON 只要造出
/// A↔B 互相继承，就会无限递归直到栈溢出崩溃。而且这条路在启动流程里，用户只会看到闪退。
const MAX_INHERIT_DEPTH: usize = 8;

pub fn get_version_info(version_id: &str) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<MinecraftVersion>> + Send + '_>> {
    get_version_info_depth(version_id, 0)
}

/// `get_version_info` 的实际实现，额外带一个继承深度（递归时 +1，见 `MAX_INHERIT_DEPTH`）。
fn get_version_info_depth(version_id: &str, depth: usize) -> std::pin::Pin<Box<dyn std::future::Future<Output = Result<MinecraftVersion>> + Send + '_>> {
    Box::pin(async move {
        if depth > MAX_INHERIT_DEPTH {
            return Err(anyhow::anyhow!(
                "版本 {version_id} 的 inherits_from 继承链过深（超过 {MAX_INHERIT_DEPTH} 层），\
                 通常是版本 JSON 损坏造成的，删掉这个版本重新装一次即可"
            ));
        }
        let data_dir = get_data_dir().await?;
        let version_dir = Path::new(&data_dir).join("versions").join(version_id);
        let json_file = version_dir.join(format!("{}.json", version_id));

        // 解析本地 JSON；若缓存文件损坏则删除并重新下载（最多重试一次）
        let (mut content, from_cache) = read_version_content(&json_file).await;
        if !from_cache {
            download_version_json(version_id, &version_dir, &json_file).await?;
            let (fresh, ok) = read_version_content(&json_file).await;
            if !ok {
                return Err(anyhow::anyhow!(
                    "Failed to download version JSON: {}",
                    version_id
                ));
            }
            content = fresh;
        }

        let mut version_info: MinecraftVersion = match serde_json::from_str(&content) {
            Ok(v) => Ok(v),
            Err(e) if from_cache => {
                // 本地缓存损坏：删除并重新下载后再次解析
                let _ = fs::remove_file(&json_file).await;
                download_version_json(version_id, &version_dir, &json_file).await?;
                let (fresh, _) = read_version_content(&json_file).await;
                serde_json::from_str(&fresh).with_context(|| {
                    format!(
                        "Failed to parse re-downloaded version JSON: {} ({})",
                        version_id, e
                    )
                })
            }
            Err(e) => Err(anyhow::anyhow!(
                "Failed to parse version JSON: {} ({:?})",
                version_id,
                e.classify()
            )),
        }?;

        // Handle inheritance
        if let Some(inherits_from) = &version_info.inherits_from {
            if inherits_from != version_id {
                // 递归时带上深度，挡住 A↔B 互相继承造成的无限递归（见 MAX_INHERIT_DEPTH）
                let parent_info = get_version_info_depth(inherits_from, depth + 1).await?;
                
                // Merge libraries (child overrides parent)
                let parent_libs = parent_info.libraries.unwrap_or_default();
                let child_libs = version_info.libraries.unwrap_or_default();
                
                let mut lib_map: HashMap<String, Library> = parent_libs
                    .into_iter()
                    .map(|lib| (lib.name.clone(), lib))
                    .collect();
                
                for lib in child_libs {
                    lib_map.insert(lib.name.clone(), lib);
                }
                
                version_info.libraries = Some(lib_map.into_values().collect());
                
                // Merge arguments
                if parent_info.arguments.is_some() && version_info.arguments.is_none() {
                    version_info.arguments = parent_info.arguments;
                }
            }
        }

        Ok(version_info)
    })
}

pub async fn install_version(version_id: &str) -> Result<()> {
    install_version_tracked(version_id, None).await
}

/// 与 [`install_version`] 相同，但把下载进度上报到「下载中心」。
/// `ctx` 为 None 时静默安装（例如后台自动补装）。
pub async fn install_version_tracked(
    version_id: &str,
    ctx: Option<&crate::progress::TaskCtx>,
) -> Result<()> {
    // ⚠️ 2026-10-20 真机排查：这里是 `install_game` 的第一步，也是最容易
    // 无声卡死的地方（拉版本清单）。之前整条链路一行日志都没有，
    // 真机上表现为「创建实例后依赖 0/7、下载页无任务」，完全无法定位。
    println!("[install] 获取版本清单: {version_id}");
    let version_info = match get_version_info(version_id).await {
        Ok(v) => {
            println!("[install] 版本清单 OK: {version_id}");
            v
        }
        Err(e) => {
            println!("[install] 版本清单失败: {version_id} → {e:#}");
            return Err(e);
        }
    };
    let data_dir = get_data_dir().await?;
    let version_dir = Path::new(&data_dir)
        .join("versions")
        .join(version_id);

    fs::create_dir_all(&version_dir).await
        .with_context(|| format!("Failed to create version directory: {}", version_dir.display()))?;

    // 先算出本体 + 依赖库的文件数与总字节，进度条才有正确的分母
    let lib_artifacts: Vec<&LibraryArtifact> = version_info
        .libraries
        .as_ref()
        .map(|libs| {
            libs.iter()
                .filter_map(|lib| lib.downloads.as_ref()?.artifact.as_ref())
                .collect()
        })
        .unwrap_or_default();
    let client_artifact = version_info
        .downloads
        .as_ref()
        .and_then(|d| d.client.as_ref());
    let index_artifact = version_info.asset_index.as_ref();
    let total_files = lib_artifacts.len()
        + usize::from(client_artifact.is_some())
        + usize::from(index_artifact.is_some());
    let total_bytes: u64 = lib_artifacts
        .iter()
        .map(|a| a.size.max(0) as u64)
        .sum::<u64>()
        + client_artifact.map(|c| c.size.max(0) as u64).unwrap_or(0)
        + index_artifact.map(|i| i.size.max(0) as u64).unwrap_or(0);

    let mut counter = crate::progress::ProgressCounter::new(ctx, "client", total_files, total_bytes);
    // 安装任务的取消标志：带进每个下载调用，前端点「取消」时大文件也能立刻停
    let install_cancel = ctx.map(|c| c.cancel_flag());

    /// 取消时要**原样传出取消语义**。
    ///
    /// 下载失败的信息一旦被 `.context("下载 xxx 失败")` 包一层，用户看到的就是
    /// 「下载失败」而不是「已取消」——主动取消却报错，非常误导。
    fn cancelled_or(
        e: anyhow::Error,
        ctx: Option<&crate::progress::TaskCtx>,
        what: &str,
    ) -> anyhow::Error {
        if let Some(c) = ctx {
            if c.cancel_flag().load(std::sync::atomic::Ordering::Relaxed) {
                return anyhow::anyhow!("任务已取消");
            }
        }
        e.context(format!("{what}失败"))
    }

    // Download client JAR
    if let Some(client) = client_artifact {
        let jar_path = version_dir.join(format!("{}.jar", version_id));
        println!("[install] 下载客户端 jar: {}", client.url);
        crate::download::download_file_with_cancel(
            &client.url,
            &jar_path.to_string_lossy(),
            Some(client.sha1.clone()),
            install_cancel.clone(),
        )
        .await
        .map_err(|e| cancelled_or(e, ctx, "下载客户端 jar"))?;
        println!("[install] 客户端 jar 完成");
        counter.tick(&format!("{}.jar", version_id), client.size.max(0) as u64);
    }

    // Download libraries
    counter.set_phase("libraries");
    if let Some(libraries) = &version_info.libraries {
        // 手机上装实例慢的主因：原来几百个库是**串行**下的（一个接一个等往返）。
        // 现在 N 并发（并发数读设置里的 `download_threads`，见 `download::file_concurrency`），
        // SHA1 校验与取消语义保持不变。
        let concurrency = crate::download::file_concurrency().await;
        let lib_dir = Path::new(&data_dir).join("libraries");
        let jobs: Vec<(String, String, Option<String>)> = libraries
            .iter()
            .filter_map(|lib| {
                let a = lib.downloads.as_ref()?.artifact.as_ref()?;
                Some((
                    a.url.clone(),
                    lib_dir.join(&a.path).to_string_lossy().to_string(),
                    Some(a.sha1.clone()),
                ))
            })
            .collect();
        println!("[install] 下载依赖库 {} 个（并发 {concurrency}）", jobs.len());
        crate::download::download_files_concurrent(&jobs, concurrency, install_cancel.clone())
            .await
            .map_err(|e| cancelled_or(e, ctx, "下载依赖库"))?;
        println!("[install] 依赖库完成");
        for lib in libraries {
            if let Some(a) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
                counter.tick(&lib.name, a.size.max(0) as u64);
            }
        }
    }

    // Download assets
    if let Some(asset_index) = &version_info.asset_index {
        let assets_dir = Path::new(&data_dir).join("assets");
        // 注意不能用 with_extension：版本号 "1.20.5" 会被截成 "1.20.json"
        let index_path = assets_dir
            .join("indexes")
            .join(format!("{}.json", asset_index.id));

        if let Some(parent) = index_path.parent() {
            fs::create_dir_all(parent).await.ok();
        }

        crate::download::download_file_with_cancel(
            &asset_index.url,
            &index_path.to_string_lossy(),
            Some(asset_index.sha1.clone()),
            install_cancel.clone(),
        )
        .await
        .map_err(|e| cancelled_or(e, ctx, "下载资源索引"))?;

        // 资源对象（音效 / 语言文件 / 贴图）：缺少它们游戏会直接崩在启动阶段。
        // 把前一段已完成的数量带进去，让「下载中心」显示一根连续的进度条。
        if let Some(c) = ctx {
            c.check_cancelled()?;
        }
        let base_files = counter.done_files();
        let base_bytes = counter.done_bytes();
        download_asset_objects(&data_dir, &index_path, ctx, base_files, base_bytes).await?;
    }

    Ok(())
}

/// 并发下载资源索引里的全部对象到 `assets/objects/<hash 前两位>/<hash>`。
/// 已存在的文件跳过；单个对象失败只记录日志，不阻断整体安装。
async fn download_asset_objects(
    data_dir: &str,
    index_path: &Path,
    ctx: Option<&crate::progress::TaskCtx>,
    base_files: usize,
    base_bytes: u64,
) -> Result<()> {
    use futures::StreamExt;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    // **这四处原来全是 `return Ok(())`（静默成功）**：索引读不到、解析失败或缺 objects
    // 字段时，安装会「成功」返回，但一个资源对象都没下 —— 用户要到启动游戏时才发现
    // 缺贴图/音效（游戏直接崩），而错误信息完全指不到真凶。
    // 调用方 `install_version` 是**刚下载并校验过索引之后**才走到这里，所以索引不可用
    // 就是真错误，必须如实抛出去。
    let raw = fs::read_to_string(index_path)
        .await
        .with_context(|| format!("读取资源索引失败：{}", index_path.display()))?;
    let idx = serde_json::from_str::<Value>(&raw)
        .with_context(|| format!("资源索引格式错误：{}", index_path.display()))?;
    let Some(objects) = idx.get("objects").and_then(|v| v.as_object()) else {
        return Err(anyhow::anyhow!(
            "资源索引缺少 objects 字段：{}",
            index_path.display()
        ));
    };

    let settings = crate::settings::get_settings().await?;
    let mirror_base = crate::mirror::resolve_from(
        &settings.mirror,
        settings.mirror_custom.as_deref().unwrap_or(""),
    );
    let client = crate::util::http_client().await;
    let objects_dir = Path::new(data_dir).join("assets").join("objects");
    // 并发数跟着设置走（`download_threads`）。这里原来写死 8 —— 于是同一个「并行下载」
    // 设置在库/本体上生效、到资源对象上却不生效，用户调了也白调。
    let concurrency = crate::download::file_concurrency().await;

    let mut total_bytes: u64 = 0;
    let mut total_files: usize = 0;
    let mut jobs: Vec<(String, std::path::PathBuf, u64)> = Vec::with_capacity(objects.len());
    for obj in objects.values() {
        let Some(hash) = obj.get("hash").and_then(|h| h.as_str()) else {
            continue;
        };
        if hash.len() < 2 {
            continue;
        }
        total_files += 1;
        let size = obj.get("size").and_then(|s| s.as_u64()).unwrap_or(0);
        total_bytes += size;
        // 前两位只是分桶目录，URL 与文件名都必须用**完整哈希**：
        //   https://resources.download.minecraft.net/<前两位>/<完整哈希>
        //   assets/objects/<前两位>/<完整哈希>
        // 这里曾用 split_at(2) 取剩余部分当第二段，等于把哈希首位截掉 → 全部 404。
        let prefix = &hash[..2];
        jobs.push((
            format!("https://resources.download.minecraft.net/{prefix}/{hash}"),
            objects_dir.join(prefix).join(hash),
            size,
        ));
    }

    let counter = crate::progress::AtomicProgress::new(
        ctx,
        "assets",
        base_files + total_files,
        base_bytes + total_bytes,
        base_files,
        base_bytes,
        300,
    );
    // 连续失败（例如代理没配对、DNS 解析不了）时及时中止并如实报错，
    // 否则会把上千个失败当成「进度」推上去，进度条走到一半不动、用户只能干等。
    let failed = AtomicUsize::new(0);
    let attempts = AtomicUsize::new(0);
    let abort = AtomicBool::new(false);

    futures::stream::iter(jobs.into_iter().map(|(url, dest, size)| {
        let client = client.clone();
        let mirror_base = mirror_base.clone();
        let counter = &counter;
        let failed = &failed;
        let attempts = &attempts;
        let abort = &abort;
        async move {
            if abort.load(Ordering::Relaxed) {
                return;
            }
            let name = url.rsplit('/').next().unwrap_or("").to_string();
            // 已存在且**大小对得上**才跳过。原来只判「非空」：进程被杀（用户手动杀后台、
            // 系统回收）留下的半截文件会被当成完整文件，之后永远不再补，
            // 直到游戏启动时缺资源崩掉 —— 库文件早就踩过一模一样的坑。
            let exists_ok = dest
                .metadata()
                .map(|m| if size > 0 { m.len() == size } else { m.len() > 0 })
                .unwrap_or(false);
            if exists_ok {
                counter.tick(&name, size);
                return;
            }
            let mapped = crate::mirror::map(&mirror_base, &url);
            let base_candidates =
                if mapped == url { vec![url.clone()] } else { vec![mapped, url.clone()] };
            // ⚠️ 2026-10-25（真机）：每个候选**再试一轮**。
            // 实测 3911 个资源里有 87 个是一次性抖动失败（`error sending request`），
            // 而这里的判据是「一个都不能少」→ 只试一次就整体中止 ✗，
            // 用户看到「87/3911 个失败，已中止」，其实重试一次基本都能成 ✓。
            let mut candidates = Vec::with_capacity(base_candidates.len() * 2);
            for _round in 0..2 {
                candidates.extend(base_candidates.iter().cloned());
            }
            let mut reasons: Vec<String> = Vec::new();
            for candidate in candidates {
                match client.get(&candidate).send().await {
                    Ok(resp) => {
                        let status = resp.status();
                        if !status.is_success() {
                            reasons.push(format!("{candidate} → HTTP {status}"));
                            continue;
                        }
                        match resp.bytes().await {
                            Ok(bytes) => {
                                // 长度必须和索引里的 size 一致才认。镜像/代理配错时常见
                                // 「HTTP 200 + 一段 HTML 错误页」：不校验的话它会被当成下载成功
                                // 落盘，下次启动又因大小不符重下 —— 用户永远补不齐。
                                if size > 0 && bytes.len() as u64 != size {
                                    reasons.push(format!(
                                        "{candidate} → 长度不符（期望 {size}，实际 {}）",
                                        bytes.len()
                                    ));
                                    continue;
                                }
                                if let Some(parent) = dest.parent() {
                                    let _ = fs::create_dir_all(parent).await;
                                }
                                if fs::write(&dest, &bytes).await.is_ok() {
                                    counter.tick(&name, bytes.len() as u64);
                                    return;
                                }
                                reasons.push(format!("{candidate} → 写文件失败"));
                            }
                            Err(e) => reasons.push(format!("{candidate} → 读取响应失败: {e}")),
                        }
                    }
                    Err(e) => reasons.push(format!("{candidate} → 请求失败: {e}")),
                }
            }
            let f = failed.fetch_add(1, Ordering::Relaxed) + 1;
            let a = attempts.fetch_add(1, Ordering::Relaxed) + 1;
            // 尝试量够大且失败过半（说明不是偶发抖动）才判定网络/代理问题并中止
            if a >= 60 && f * 2 > a {
                if !abort.swap(true, Ordering::Relaxed) {
                    crate::util::log_line(&format!(
                        "资源对象下载大面积失败（{f}/{a}），判定为网络/代理问题，已中止"
                    ));
                }
            }
            crate::util::log_line(&format!("资源对象下载失败 {url}：{}", reasons.join(" | ")));
            counter.tick(&name, 0);
        }
    }))
    .buffer_unordered(concurrency)
    .collect::<Vec<_>>()
    .await;

    let failed = failed.load(Ordering::Relaxed);
    if failed > 0 {
        crate::util::log_line(&format!(
            "资源对象下载结束：{failed}/{total_files} 个失败（共 {total_bytes} 字节）"
        ));
    }
    // 判据是「**一个都不能少**」。这里曾经写成 `failed * 3 >= total_files`
    // （失败过半才报错），后果是真机实测踩到的：26.3 的 5147 个对象静默漏了 199 个
    // （3.9%），函数照样 `Ok`，安装流程随即把实例标成已安装、报「安装完成」，
    // 用户要到启动游戏时才发现缺贴图（`panorama_1.png`）/缺 `pack.mcmeta`，
    // 游戏在 `TextureManager` 里 `NoSuchFileException` 直接崩，而错误信息完全指不到真凶。
    // 失败计数本身就等于「没落盘的对象数」，所以缺一个 = 没装完。
    // 报错不会让用户白下：上面按大小跳过，重试只补缺的那部分。
    if abort.load(Ordering::Relaxed) {
        return Err(anyhow::anyhow!(
            "资源文件下载失败（{failed}/{total_files}），已中止。请检查网络，或到「设置 → 内容服务 → 下载代理」确认已选择「系统代理」（已配置代理软件时）后重试。"
        ));
    }
    if failed > 0 {
        return Err(anyhow::anyhow!(
            "资源文件没下完：{failed}/{total_files} 个对象缺失。请检查网络，或到「设置 → 内容服务 → 下载代理」确认已选择「系统代理」（已配置代理软件时）后重试；已下好的文件会跳过，重试只补缺的那些。"
        ));
    }
    Ok(())
}
