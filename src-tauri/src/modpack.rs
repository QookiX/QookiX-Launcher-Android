use std::collections::HashMap;
use std::path::Path;
use tokio::fs;
use anyhow::{Context, Result};
use serde_json::Value;
use crate::models::*;

const MODRINTH_API: &str = "https://api.modrinth.com/api/v2";
const CURSEFORGE_API: &str = "https://api.cfaddon.com/v1";

// ==================== Modrinth API ====================

#[derive(Debug, Clone)]
pub struct ModrinthMod {
    pub slug: String,
    pub id: String,
    pub title: String,
    pub description: String,
    pub authors: Vec<String>,
    pub downloads: i64,
    pub icon_url: Option<String>,
    pub categories: Vec<String>,
    pub project_type: String,
    pub date_created: String,
    pub date_modified: String,
}

#[derive(Debug, Clone)]
pub struct ModrinthVersion {
    pub id: String,
    pub version_number: String,
    pub changelog: String,
    pub download_url: String,
    pub file_name: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub date_published: String,
}

pub async fn search_modrinth(query: &str, limit: i32, offset: i32, project_type: Option<&str>) -> Result<Vec<ModrinthMod>> {
    let client = crate::util::http_client().await;
    
    let mut url = format!("{}/search", MODRINTH_API);
    let params = format!(
        "?query={}&limit={}&offset={}&indexed=false",
        urlencoding::encode(query),
        limit,
        offset
    );
    url = format!("{}{}", url, params);
    
    if let Some(pt) = project_type {
        url = format!("{}&project_type={}", url, pt);
    }

    let response = client.get(&url).send().await
        .context("Failed to search Modrinth")?;

    let result: Value = response.json().await
        .context("Failed to parse Modrinth response")?;

    let hits = result["hits"].as_array()
        .ok_or_else(|| anyhow::anyhow!("No hits in response"))?;

    let mut mods = Vec::new();
    for hit in hits {
        mods.push(ModrinthMod {
            slug: hit["slug"].as_str().unwrap_or("").to_string(),
            id: hit["project_id"].as_str().unwrap_or("").to_string(),
            title: hit["title"].as_str().unwrap_or("").to_string(),
            description: hit["description"].as_str().unwrap_or("").to_string(),
            authors: hit["authors"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
                .collect(),
            downloads: hit["downloads"].as_i64().unwrap_or(0) as i64,
            icon_url: hit["icon_url"].as_str().map(|s| s.to_string()),
            categories: hit["categories"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|c| c.as_str().map(|s| s.to_string()))
                .collect(),
            project_type: hit["project_type"].as_str().unwrap_or("").to_string(),
            date_created: hit["date_created"].as_str().unwrap_or("").to_string(),
            date_modified: hit["date_modified"].as_str().unwrap_or("").to_string(),
        });
    }
    Ok(mods)
}

pub async fn get_modrinth_mod(mod_id: &str) -> Result<ModrinthMod> {
    let client = crate::util::http_client().await;
    let response = client.get(format!("{}/project/{}", MODRINTH_API, mod_id))
        .send().await.context("Failed to get Modrinth mod")?;
    let result: Value = response.json().await.context("Failed to parse Modrinth response")?;
    Ok(ModrinthMod {
        slug: result["slug"].as_str().unwrap_or("").to_string(),
        id: result["id"].as_str().unwrap_or("").to_string(),
        title: result["title"].as_str().unwrap_or("").to_string(),
        description: result["description"].as_str().unwrap_or("").to_string(),
        authors: result["authors"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
            .collect(),
        downloads: result["downloads"].as_i64().unwrap_or(0) as i64,
        icon_url: result["icon_url"].as_str().map(|s| s.to_string()),
        categories: result["categories"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|c| c.as_str().map(|s| s.to_string()))
            .collect(),
        project_type: result["project_type"].as_str().unwrap_or("").to_string(),
        date_created: result["date_created"].as_str().unwrap_or("").to_string(),
        date_modified: result["date_modified"].as_str().unwrap_or("").to_string(),
    })
}

pub async fn get_modrinth_versions(project_id: &str) -> Result<Vec<ModrinthVersion>> {
    let client = crate::util::http_client().await;
    let response = client.get(format!("{}/project/{}/version", MODRINTH_API, project_id))
        .send().await.context("Failed to get Modrinth versions")?;
    let result: Value = response.json().await.context("Failed to parse Modrinth versions response")?;
    let versions = result.as_array().ok_or_else(|| anyhow::anyhow!("No versions in response"))?;
    let mut mod_versions = Vec::new();
    for version in versions {
        let downloads = version["files"].as_array().and_then(|f| f.first())
            .and_then(|f| f["url"].as_str()).unwrap_or("").to_string();
        mod_versions.push(ModrinthVersion {
            id: version["id"].as_str().unwrap_or("").to_string(),
            version_number: version["version_number"].as_str().unwrap_or("").to_string(),
            changelog: version["changelog"].as_str().unwrap_or("").to_string(),
            download_url: downloads,
            file_name: version["files"].as_array().and_then(|f| f.first())
                .and_then(|f| f["filename"].as_str()).unwrap_or("").to_string(),
            game_versions: version["game_versions"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect(),
            loaders: version["loaders"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|l| l["name"].as_str().map(|s| s.to_string())).collect(),
            date_published: version["date_published"].as_str().unwrap_or("").to_string(),
        });
    }
    Ok(mod_versions)
}

// ==================== CurseForge API ====================

#[derive(Debug, Clone)]
pub struct CurseforgeMod {
    pub id: u64,
    pub name: String,
    pub summary: String,
    pub authors: Vec<String>,
    pub downloads: i64,
    pub icon_url: Option<String>,
    pub categories: Vec<String>,
    pub date_created: String,
    pub date_modified: String,
}

#[derive(Debug, Clone)]
pub struct CurseforgeFile {
    pub id: u64,
    pub file_name: String,
    pub download_url: String,
    pub game_versions: Vec<String>,
    pub release_type: String,
    pub date_created: String,
}

pub async fn search_curseforge(query: &str, game_id: u32, category_id: Option<u32>, page_size: i32, page: i32) -> Result<Vec<CurseforgeMod>> {
    let client = crate::util::http_client().await;
    let mut body = HashMap::new();
    body.insert("gameId", Value::Number(game_id.into()));
    body.insert("searchFilter", Value::String(query.to_string()));
    body.insert("pageSize", Value::Number(page_size.into()));
    body.insert("page", Value::Number(page.into()));
    body.insert("sort", Value::String("Featured".to_string()));
    if let Some(cat_id) = category_id {
        body.insert("classId", Value::Number(cat_id.into()));
    }
    let response = client.post(format!("{}/mods/search", CURSEFORGE_API)).json(&body)
        .send().await.context("Failed to search CurseForge")?;
    let result: Value = response.json().await.context("Failed to parse CurseForge response")?;
    let data = result["data"].as_array().ok_or_else(|| anyhow::anyhow!("No data in response"))?;
    let mut mods = Vec::new();
    for item in data {
        mods.push(CurseforgeMod {
            id: item["id"].as_i64().unwrap_or(0) as u64,
            name: item["name"].as_str().unwrap_or("").to_string(),
            summary: item["summary"].as_str().unwrap_or("").to_string(),
            authors: item["authors"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
                .collect(),
            downloads: item["downloadCount"].as_i64().unwrap_or(0) as i64,
            icon_url: item["logo"].as_object().and_then(|v| v["thumbnailUrl"].as_str()).map(|s| s.to_string()),
            categories: item["categories"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|c| c["name"].as_str().map(|s| s.to_string()))
                .collect(),
            date_created: item["dateCreated"].as_str().unwrap_or("").to_string(),
            date_modified: item["dateModified"].as_str().unwrap_or("").to_string(),
        });
    }
    Ok(mods)
}

pub async fn get_curseforge_mod(mod_id: u64) -> Result<CurseforgeMod> {
    let client = crate::util::http_client().await;
    let response = client.get(format!("{}/mod/{}", CURSEFORGE_API, mod_id))
        .send().await.context("Failed to get CurseForge mod")?;
    let result: Value = response.json().await.context("Failed to parse CurseForge response")?;
    Ok(CurseforgeMod {
        id: result["id"].as_i64().unwrap_or(0) as u64,
        name: result["name"].as_str().unwrap_or("").to_string(),
        summary: result["summary"].as_str().unwrap_or("").to_string(),
        authors: result["authors"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
            .collect(),
        downloads: result["downloadCount"].as_i64().unwrap_or(0) as i64,
        icon_url: result["logo"].as_object().and_then(|v| v["thumbnailUrl"].as_str()).map(|s| s.to_string()),
        categories: result["categories"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|c| c["name"].as_str().map(|s| s.to_string()))
            .collect(),
        date_created: result["dateCreated"].as_str().unwrap_or("").to_string(),
        date_modified: result["dateModified"].as_str().unwrap_or("").to_string(),
    })
}

pub async fn get_curseforge_files(mod_id: u64) -> Result<Vec<CurseforgeFile>> {
    let client = crate::util::http_client().await;
    let response = client.get(format!("{}/mod/{}/files", CURSEFORGE_API, mod_id))
        .send().await.context("Failed to get CurseForge files")?;
    let result: Value = response.json().await.context("Failed to parse CurseForge files response")?;
    let files = result["data"].as_array().ok_or_else(|| anyhow::anyhow!("No files in response"))?;
    let mut curseforge_files = Vec::new();
    for file in files {
        let download_url = file["downloadUrl"].as_str().unwrap_or("").to_string();
        curseforge_files.push(CurseforgeFile {
            id: file["id"].as_i64().unwrap_or(0) as u64,
            file_name: file["fileName"].as_str().unwrap_or("").to_string(),
            download_url: if download_url.is_empty() {
                format!("{}/mod/{}/file/{}/download", CURSEFORGE_API, mod_id, file["id"].as_i64().unwrap_or(0))
            } else { download_url },
            game_versions: file["gameVersions"].as_array().unwrap_or(&vec![])
                .iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect(),
            release_type: file["releaseType"].as_i64().unwrap_or(0).to_string(),
            date_created: file["fileDate"].as_str().unwrap_or("").to_string(),
        });
    }
    Ok(curseforge_files)
}

// ==================== Modpack Import ====================

/// 解析整合包的加载器与版本，兼容两种 manifest：
///
/// - **CurseForge**：`minecraft.modLoaders = [{"id": "forge-47.2.0", "primary": true}]`
///   —— 是**对象数组**。旧代码按字符串数组读（`loaders[0].as_str()`）永远得到 None，
///   于是**所有整合包都被识别成 Vanilla**。
/// - **Modrinth**：`dependencies = {"forge": "47.2.0"}` / `{"fabric-loader": "0.15.0"}`。
fn parse_loader(info: &Value) -> (Loader, Option<String>) {
    if let Some(list) = info["minecraft"]["modLoaders"].as_array() {
        for entry in list {
            let id = entry
                .as_str()
                .map(|s| s.to_string())
                .or_else(|| entry["id"].as_str().map(|s| s.to_string()));
            let Some(id) = id else { continue };
            if id.is_empty() {
                continue;
            }
            let lower = id.to_ascii_lowercase();
            let (loader, prefix) = if lower.starts_with("neoforge") {
                (Loader::NeoForge, "neoforge")
            } else if lower.starts_with("forge") {
                (Loader::Forge, "forge")
            } else if lower.starts_with("fabric") {
                (Loader::Fabric, "fabric")
            } else if lower.starts_with("quilt") {
                (Loader::Quilt, "quilt")
            } else {
                continue;
            };
            let version = id[prefix.len()..].trim_start_matches(['-', '_']).trim();
            return (
                loader,
                if version.is_empty() { None } else { Some(version.to_string()) },
            );
        }
    }

    if let Some(deps) = info["dependencies"].as_object() {
        for (key, value) in deps {
            let loader = match key.as_str() {
                "forge" => Loader::Forge,
                "neoforge" => Loader::NeoForge,
                "fabric-loader" => Loader::Fabric,
                "quilt-loader" => Loader::Quilt,
                _ => continue,
            };
            let version = value.as_str().unwrap_or("").trim().to_string();
            return (loader, if version.is_empty() { None } else { Some(version) });
        }
    }

    (Loader::Vanilla, None)
}
/// 整合包清单的常见文件名，**按优先级**尝试。
///
/// 以前只认 `modpack.json` —— 那只是本项目自定义的格式。真实整合包用的是模组平台的
/// 标准清单，于是「导入真实整合包必失败」：
///   - Modrinth   → `modrinth.index.json`
///   - CurseForge → `manifest.json`
const MODPACK_MANIFESTS: [&str; 3] = ["modrinth.index.json", "manifest.json", "modpack.json"];

/// 清单里一条需要联网下载的文件（Modrinth `files[]` 的形态）。
struct PackFile {
    /// 包内相对路径，同时也是落盘位置（如 `mods/xxx.jar`）
    rel_path: String,
    url: String,
    sha1: Option<String>,
}

/// 把 zip 里 `prefix` 下的条目**流式**解压到实例目录，返回文件数。
///
/// 同步实现，由 `spawn_blocking` 调用（原因见 `import_modpack` 里那段注释）。
fn extract_overrides_sync(
    zip_path: &Path,
    instance_dir: &Path,
    prefix: &str,
) -> Result<usize, String> {
    let file = std::fs::File::open(zip_path).map_err(|e| format!("打开整合包失败: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("解析整合包失败: {e}"))?;

    let mut extracted = 0usize;
    for i in 0..archive.len() {
        let mut entry = match archive.by_index(i) {
            Ok(e) => e,
            Err(_) => continue,
        };
        let entry_name = entry.name().to_string();
        let Some(rel) = entry_name.strip_prefix(prefix) else {
            continue;
        };
        let rel = rel.trim_start_matches('/');
        if rel.is_empty() {
            continue;
        }

        let dest_path = match crate::fsutil::resolve_in_dir(instance_dir, rel, "实例") {
            Ok(p) => p,
            Err(e) => {
                tracing::warn!("跳过不安全的整合包条目 {entry_name}: {e}");
                continue;
            }
        };

        if entry.is_dir() {
            let _ = std::fs::create_dir_all(&dest_path);
        } else {
            if let Some(parent) = dest_path.parent() {
                let _ = std::fs::create_dir_all(parent);
            }
            let mut out = std::fs::File::create(&dest_path)
                .map_err(|e| format!("创建文件失败 {}: {e}", dest_path.display()))?;
            std::io::copy(&mut entry, &mut out).map_err(|e| format!("写入文件失败: {e}"))?;
            extracted += 1;
        }
    }
    Ok(extracted)
}

/// 导入中途失败时的回滚守卫：未 `defuse()` 就 drop 会删掉这个目录。
///
/// 只在**结构性失败**（包坏了 / 解压炸了）时生效 —— 那时留下的目录是个打不开的半成品，
/// 只会在实例列表里多出一个点不动的条目。内容下载阶段的失败不走回滚，见 `import_modpack`。
struct IncompleteInstance {
    dir: Option<std::path::PathBuf>,
}

impl IncompleteInstance {
    fn new(dir: &std::path::Path) -> Self {
        Self { dir: Some(dir.to_path_buf()) }
    }
    /// 实例已经可用，不要再回滚
    fn defuse(&mut self) {
        self.dir = None;
    }
}

impl Drop for IncompleteInstance {
    fn drop(&mut self) {
        if let Some(dir) = self.dir.take() {
            tracing::warn!("整合包导入中断，回滚实例目录 {}", dir.display());
            let _ = std::fs::remove_dir_all(&dir);
        }
    }
}

/// 整合包导入结果。
///
/// `failed` / `skipped_no_url` 不为 0 时界面必须如实提示：只写日志的后果就是
/// 用户拿着一个缺 mod 的包去启动、进游戏才炸，而错误信息指不到真凶。
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ModpackImportResult {
    pub instance: MinecraftProfile,
    /// overrides 里解出来的文件数
    pub extracted: usize,
    /// `files[]` 下载成功的数量
    pub downloaded: usize,
    /// `files[]` 下载失败的数量
    pub failed: usize,
    /// CurseForge 之类只有 projectID/fileID、拿不到直链而跳过的数量
    pub skipped_no_url: usize,
}

pub async fn import_modpack(file_path: &str) -> Result<ModpackImportResult> {
    use std::io::Read;

    // 兜底：安卓文件选择器给的是 SAF 的 `content://` URI，对它 `File::open` 必然 ENOENT。
    // 正常路径上前端已用 `filePicker` 把内容落地成真实文件，这里只保证万一漏掉时
    // 报错是看得懂的，而不是一句 "Failed to open modpack file"。
    if file_path.starts_with("content://") {
        anyhow::bail!("整合包路径未被落地为真实文件（收到 content:// URI，请从应用内选择文件）");
    }

    let file = std::fs::File::open(file_path).context("Failed to open modpack file")?;
    let mut archive = zip::ZipArchive::new(file).context("Failed to open zip archive")?;

    // 按优先级找清单
    let mut json_content = String::new();
    let mut manifest_name = "";
    for name in MODPACK_MANIFESTS {
        let Ok(mut entry) = archive.by_name(name) else {
            continue;
        };
        if entry.read_to_string(&mut json_content).is_ok() && !json_content.trim().is_empty() {
            manifest_name = name;
            break;
        }
        json_content.clear();
    }
    if manifest_name.is_empty() {
        anyhow::bail!(
            "无法识别整合包格式：包内没有 modrinth.index.json / manifest.json / modpack.json"
        );
    }
    tracing::info!("整合包清单：{manifest_name}");

    let modpack_info: Value = serde_json::from_str(&json_content)
        .with_context(|| format!("解析 {manifest_name} 失败"))?;
    let instance_name = modpack_info["name"]
        .as_str()
        .or_else(|| modpack_info["title"].as_str())
        .unwrap_or("Modpack")
        .to_string();
    // 三种格式的字段位置都覆盖：
    //   Modrinth   → `dependencies.minecraft`
    //   CurseForge → `minecraft.version`
    //   本项目格式 → 两者之一
    let mc_version = modpack_info["minecraft"]["version"]
        .as_str()
        .or_else(|| modpack_info["dependencies"]["minecraft"].as_str())
        .unwrap_or("1.20.1")
        .to_string();
    let (loader, loader_version) = parse_loader(&modpack_info);
    // 走 `instances::create_instance` 而不是自己建目录 —— 它会**同时**写 `instance.json`。
    // 这一步以前漏了：本函数建了目录、铺了 overrides，却没写 `instance.json`，
    // 结果是「导入成功但实例列表里根本没有这个实例」= 白导一场。
    // 复用创建流程还顺带拿到创建时间、默认分辨率/内存、渲染器「自动」等一整套默认值，
    // 不必在这条路径上再抄一遍（抄出来的副本迟早和创建页不一致）。
    let instance = crate::instances::create_instance(serde_json::json!({
        "name": instance_name.clone(),
        "mcVersion": mc_version.clone(),
        "loader": serde_json::to_value(&loader).unwrap_or(serde_json::json!("vanilla")),
        "loaderVersion": loader_version,
    }))
    .await
    .context("创建实例失败")?;
    let instance_id = instance.id.clone();
    let instance_dir = std::path::PathBuf::from(&instance.game_dir);

    // 结构性失败（清单解析不了 / 包坏了 / 解压炸了）要**回滚**：留一个半成品目录
    // 只会在实例列表里多出一个打不开的条目。内容下载阶段的失败不走这里（见下面）。
    let mut rollback = IncompleteInstance::new(&instance_dir);
    // `overrides` 字段是**目录名**（默认 "overrides"），不是相对路径，所以前缀是 `{名字}/`。
    // 旧代码拼成 "overrides/overrides"，只靠后面 `starts_with("overrides/")` 兜底才歪打正着。
    let overrides_dir = modpack_info["overrides"]
        .as_str()
        .unwrap_or("overrides")
        .trim_matches('/');
    let prefix = format!(
        "{}/",
        if overrides_dir.is_empty() { "overrides" } else { overrides_dir }
    );

    // ── 解析 `files[]`（以前完全没有这一步 → 装出来是个空整合包）──────────
    // Modrinth 的条目带真实直链（`downloads[]`）；
    // CurseForge 的条目只有 projectID/fileID，换直链需要官方 API 且第三方客户端受限，
    // 那部分只能统计数量后**如实提示**，不能假装装好了。
    let mut pack_files: Vec<PackFile> = Vec::new();
    let mut no_url_files = 0usize;
    if let Some(arr) = modpack_info["files"].as_array() {
        for it in arr {
            let rel = it["path"].as_str().unwrap_or("").trim().to_string();
            if rel.is_empty() {
                continue;
            }
            let url = it["downloads"]
                .as_array()
                .and_then(|a| a.iter().find_map(|u| u.as_str()))
                .or_else(|| it["downloadUrl"].as_str())
                .unwrap_or("")
                .trim()
                .to_string();
            if url.is_empty() {
                no_url_files += 1;
                continue;
            }
            // 相对路径同样要防穿越：json 里的 path 和 zip 条目名一样可能是 `../../x`
            if crate::fsutil::resolve_in_dir(&instance_dir, &rel, "实例").is_err() {
                tracing::warn!("跳过不安全的整合包条目 {rel}");
                continue;
            }
            let sha1 = it["hashes"]["sha1"]
                .as_str()
                .map(|s| s.to_string())
                .or_else(|| it["sha1"].as_str().map(|s| s.to_string()));
            pack_files.push(PackFile {
                rel_path: rel,
                url,
                sha1,
            });
        }
    }

    // 解压 overrides。三件事：
    //   1. **zip-slip**：目标路径经 `fsutil::resolve_in_dir` 校验，条目名里的 `../..`
    //      不能再把文件写到实例目录之外（`overrides/../../settings.json`）；
    //   2. **内存**：`std::io::copy` 流式落盘，不把所有条目先 read_to_end 进 Vec
    //      （整合包动辄几百 MB，那样手机上直接 OOM）；
    //   3. **不阻塞**：整段是同步 IO，挪进 `spawn_blocking` —— 以前它占着 tokio worker，
    //      解压期间前端连进度都刷不动。
    // 这里重新打开一次 zip 而不是把 `archive` move 进闭包：闭包要求 `Send`，
    // 而重开只读一次目录头，代价可忽略。
    let ctx = crate::progress::TaskCtx::new(&instance_id, &instance_name, "整合包导入");
    crate::progress::emit_install(&ctx, "extract", "正在解压整合包内容…", 0, 1);
    let zip_path = std::path::PathBuf::from(file_path);
    let instance_dir_for_extract = instance_dir.clone();
    let prefix_for_extract = prefix.clone();
    let extracted = tokio::task::spawn_blocking(move || {
        extract_overrides_sync(&zip_path, &instance_dir_for_extract, &prefix_for_extract)
    })
    .await
    .map_err(|e| anyhow::anyhow!("解压任务失败: {e}"))?
    // `String` 不是 `StdError`，anyhow 不会自动转换，得显式包一层
    .map_err(|e| anyhow::anyhow!("{e}"))?;
    tracing::info!("整合包 overrides 解压完成：{extracted} 个文件");

    // ── 下载 `files[]` 里的远程内容（mods / resourcepacks / shaderpacks…）──
    // 走 `download::download_file` 而不是自己发请求，是为了复用镜像改写、SHA1 校验
    // 和「镜像失败回退官方源」那套逻辑。
    //
    // 失败**不再只是写日志**：数量如实报给调用方，界面据此提示「有 N 个文件没下下来」。
    // 但实例不再回滚 —— 几百 MB 的包因为最后两个文件失败就整个丢掉太亏，而且实例本身
    // 已经可用（游戏本体由 `check_files_on_launch` 在启动时补全，缺的 mod 可在内容页补装）。
    let mut downloaded = 0usize;
    let mut failed = 0usize;
    if !pack_files.is_empty() {
        let total = pack_files.len();
        // **并发下载**（这里原先是逐个 `.await` 的串行循环）：整合包动辄几十上百个内容
        // 文件，串行意味着每个都要等一次完整往返 —— 几十个文件就是几分钟白等。
        // 桌面端同一段走的就是并发（`download_many`），安卓这边落下了。
        // 并发数沿用设置里的「同时下载文件数」，进度仍按**完成文件数**报（并发下按秒刷屏没意义）。
        let concurrency = crate::download::file_concurrency().await;
        // 计数器与上下文都**自持**（Arc / clone）而不是借局部变量：借出去的引用会让
        // 这组 future 带上函数内的生命周期，`import_modpack` 的调用方（命令层）接不住
        // （实测报 `implementation of FnOnce is not general enough`）。
        let ok = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let bad = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let done = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let root = instance_dir.clone();
        let files: Vec<(String, String, Option<String>)> = pack_files
            .iter()
            .map(|pf| (pf.url.clone(), pf.rel_path.clone(), pf.sha1.clone()))
            .collect();
        use futures::StreamExt;
        futures::stream::iter(files.into_iter().map(|(url, rel, sha1)| {
            let (ok, bad, done, ctx, root) =
                (ok.clone(), bad.clone(), done.clone(), ctx.clone(), root.clone());
            async move {
                // 取消后不再发起新请求（已下好的部分保留）
                if ctx.check_cancelled().is_err() {
                    return;
                }
                let dest = root.join(&rel);
                if let Some(parent) = dest.parent() {
                    let _ = std::fs::create_dir_all(parent);
                }
                match crate::download::download_file(&url, &dest.to_string_lossy(), sha1).await {
                    Ok(_) => {
                        ok.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    }
                    Err(e) => {
                        tracing::warn!("整合包文件下载失败 {rel}: {e}");
                        // ⚠️ 2026-10-27：用户报「导入后少了 21 个模组，且没有任何提示」。
                        // 光靠 tracing 用户拿不到（导出日志里没有），所以同时写进
                        // 可导出的诊断日志 —— 只有文件名 + 原因才能判断是
                        // URL 失效、镜像抽风还是磁盘写不进去。
                        crate::util::log_line(&format!("[modpack] 下载失败 {rel}: {e}"));
                        bad.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    }
                }
                let n = done.fetch_add(1, std::sync::atomic::Ordering::Relaxed) + 1;
                if n % 3 == 0 || n == total {
                    crate::progress::emit_install(
                        &ctx,
                        "content",
                        &format!("已下载 {n}/{total} 个内容文件"),
                        n,
                        total,
                    );
                }
            }
        }))
        .buffer_unordered(concurrency)
        .collect::<Vec<()>>()
        .await;
        downloaded = ok.load(std::sync::atomic::Ordering::Relaxed);
        failed = bad.load(std::sync::atomic::Ordering::Relaxed);
        crate::progress::emit_install_done(
            &ctx,
            failed == 0,
            &format!("整合包内容：成功 {downloaded} / 失败 {failed}"),
            downloaded,
            total,
        );
        tracing::info!("整合包内容下载完成：{downloaded} 个，失败 {failed} 个");
    }
    if no_url_files > 0 {
        tracing::warn!(
            "整合包有 {no_url_files} 个文件没有下载直链（CurseForge 格式需要官方 API），已跳过"
        );
    }
    // 整包没有 `files[]`（纯 overrides 的离线包）也要把任务收尾，否则下载中心里
    // 这条任务会一直挂在「进行中」。
    if pack_files.is_empty() {
        crate::progress::emit_install_done(&ctx, true, "整合包导入完成", 1, 1);
    }

    // 把「导入来源」写回 `instance.json`（`create_instance` 不知道这些）。
    // 其余默认值沿用创建流程那一套，不再手抄一遍。
    let mut instance = instance;
    instance.source_path = Some(file_path.to_string());
    // ⚠️ 2026-10-23 改：导入完**直接把游戏装好**，不再只留一个"启动时补全"的标记。
    //
    // 原来的做法（check_files_on_launch = true）在实现上没错，但用户看到的是
    // 「游戏文件不完整（已就绪 2/5）· 缺 1.21.1 版本信息 / neoforge 本体 / Java」
    // → 第一反应是「导入失败了」，然后点「补全」，还容易再撞上补全那边的历史问题。
    // 这里复用 `repair_instance_files`（装本体 + 加载器 + Java 运行时），进度走同一条
    // install://progress，导入页的进度条能一路走到"完成"。
    instance.check_files_on_launch = Some(false);
    crate::instances::write_instance(&instance).await?;

    // 失败不掩盖"导入成功"：mod 已经落地了，这里只记日志，用户仍可点「补全」重试。
    let install_id = instance.id.clone();
    if let Err(e) = crate::instance_files::repair_instance_files(install_id.clone()).await {
        crate::util::log_line(&format!(
            "[modpack] 导入后的游戏文件安装失败（{install_id}）：{e}"
        ));
    }
    // 走到这里实例已经完整可用，不再需要回滚
    rollback.defuse();

    Ok(ModpackImportResult {
        instance,
        extracted,
        downloaded,
        failed,
        skipped_no_url: no_url_files,
    })
}

// ==================== 导出：实例 → .mrpack ====================

/// 内容类型 ↔ 实例内目录。导出只认这三类（与 `instances::kind_folder` 一致）。
const EXPORT_KINDS: [&str; 3] = ["mod", "resourcepack", "shader"];

/// 「非内容」里可选的配置类条目（目录或单文件）。
const CONFIG_EXTRAS: [&str; 4] = ["config", "defaultconfigs", "options.txt", "servers.dat"];
const SAVE_EXTRAS: [&str; 1] = ["saves"];
const SHOT_EXTRAS: [&str; 1] = ["screenshots"];

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportOptions {
    /// 存档：体积大且含个人内容，默认不带
    #[serde(default)]
    pub include_saves: bool,
    #[serde(default)]
    pub include_screenshots: bool,
    #[serde(default)]
    pub include_config: bool,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportPlanItem {
    pub label: String,
    pub count: usize,
    pub bytes: u64,
}

/// 导出前的「体检」：先让用户看清包里有什么、大概多大，再决定导不导。
/// 纯本地统计（不联网），所以点开就能立刻显示。
#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportPlan {
    /// 建议的包名（同时也作文件名）
    pub file_name: String,
    pub mc_version: String,
    pub loader: String,
    /// 能对上来源记录、导出时**只存下载链接**的内容数量
    pub linked: usize,
    /// 必须把文件本体打进包的部分
    pub bundled: Vec<ExportPlanItem>,
    pub bundled_bytes: u64,
    pub has_saves: bool,
    pub has_screenshots: bool,
    pub has_config: bool,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ExportResult {
    pub path: String,
    pub file_name: String,
    pub linked: usize,
    pub bundled: usize,
    pub bytes: u64,
}

/// 这条内容记录能不能「只存链接」：得同时有来源和版本号，才能反查出下载直链。
fn can_link(rec: &InstalledContent) -> bool {
    matches!(rec.source.as_str(), "modrinth" | "curseforge") && rec.version_id.is_some()
}

/// 目录/文件的（条目数, 字节数）；不存在就是 0。
fn path_stats(p: &Path) -> (usize, u64) {
    if p.is_file() {
        return (1, std::fs::metadata(p).map(|m| m.len()).unwrap_or(0));
    }
    let mut count = 0usize;
    let mut bytes = 0u64;
    let mut stack = vec![p.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        for e in rd.flatten() {
            let path = e.path();
            if path.is_dir() {
                stack.push(path);
            } else if path.is_file() {
                count += 1;
                bytes += std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
            }
        }
    }
    (count, bytes)
}

/// 建议的包文件名：`<实例名>-<MC 版本>-<加载器>.mrpack`。
fn export_file_name(inst: &MinecraftProfile) -> String {
    let loader = serde_json::to_value(&inst.loader)
        .ok()
        .and_then(|v| v.as_str().map(|s| s.to_string()))
        .unwrap_or_else(|| "vanilla".to_string());
    format!(
        "{}-{}-{}.mrpack",
        crate::util::safe_stem(&inst.name),
        crate::util::safe_stem(&inst.mc_version),
        loader
    )
}

pub async fn plan_modpack_export(instance_id: &str) -> Result<ExportPlan> {
    let inst = crate::instances::get_instance(instance_id).await?;
    let root = std::path::PathBuf::from(&inst.game_dir);

    let mut linked = 0usize;
    let mut bundled: Vec<ExportPlanItem> = Vec::new();
    let mut bundled_bytes = 0u64;

    for kind in EXPORT_KINDS {
        let folder = crate::instances::kind_folder(kind);
        let mut count = 0usize;
        let mut bytes = 0u64;
        for rec in crate::instances::list_content_records(instance_id, kind).await {
            let p = root.join(folder).join(&rec.filename);
            if !p.is_file() {
                continue;
            }
            if can_link(&rec) {
                linked += 1;
            } else {
                count += 1;
                bytes += std::fs::metadata(&p).map(|m| m.len()).unwrap_or(0);
            }
        }
        if count > 0 {
            bundled.push(ExportPlanItem { label: folder.to_string(), count, bytes });
            bundled_bytes += bytes;
        }
    }

    for (label, rel) in [
        ("config", "config"),
        ("config", "defaultconfigs"),
        ("config", "options.txt"),
        ("config", "servers.dat"),
        ("saves", "saves"),
        ("screenshots", "screenshots"),
    ] {
        let (count, bytes) = path_stats(&root.join(rel));
        if count > 0 {
            bundled.push(ExportPlanItem { label: label.to_string(), count, bytes });
            bundled_bytes += bytes;
        }
    }

    Ok(ExportPlan {
        file_name: export_file_name(&inst),
        mc_version: inst.mc_version.clone(),
        loader: serde_json::to_value(&inst.loader)
            .ok()
            .and_then(|v| v.as_str().map(|s| s.to_string()))
            .unwrap_or_else(|| "vanilla".to_string()),
        linked,
        bundled,
        bundled_bytes,
        has_saves: root.join("saves").is_dir(),
        has_screenshots: root.join("screenshots").is_dir(),
        has_config: root.join("config").is_dir() || root.join("options.txt").is_file(),
    })
}

/// 导出为 **`.mrpack`**（`modrinth.index.json` + `overrides/`）。
///
/// 为什么是这个格式而不是自定义包：
///   - 已登记的 mod/资源包/光影在 `files[]` 里**只存下载链接**，包能小一个数量级
///     （手机上分享几百 MB 的包不现实）；
///   - 它同时能被 Modrinth App、PojavLauncher 等第三方启动器读，导出给朋友不用附带说明；
///   - 我们自己的导入器本来就认这个格式（`MODPACK_MANIFESTS` 里第一位），**导入导出天然自洽**，
///     不用再维护一套「本项目格式」的读写两份实现。
/// 对不上来源的内容（手动塞进去的、来源记录丢了的）进 `overrides/`，否则包会缺东西。
pub async fn export_modpack(instance_id: &str, options: ExportOptions) -> Result<ExportResult> {
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    let inst = crate::instances::get_instance(instance_id).await?;
    let root = std::path::PathBuf::from(&inst.game_dir);
    let ctx = crate::progress::TaskCtx::new(instance_id, &inst.name, "导出整合包");

    // ── 1) 内容：能反查出直链的进 files[]，其余进 overrides ──────────────
    crate::progress::emit_install(&ctx, "export", "正在整理内容…", 0, 1);
    let mut index_files: Vec<Value> = Vec::new();
    let mut override_files: Vec<(std::path::PathBuf, String)> = Vec::new();
    let mut linked = 0usize;

    for kind in EXPORT_KINDS {
        let folder = crate::instances::kind_folder(kind);
        for rec in crate::instances::list_content_records(instance_id, kind).await {
            let src = root.join(folder).join(&rec.filename);
            if !src.is_file() {
                continue;
            }
            let rel = format!("{folder}/{}", rec.filename);
            let mut done = false;
            if can_link(&rec) {
                let version_id = rec.version_id.clone().unwrap_or_default();
                // 反查直链失败（下架/网络不通）就退回「打进包」，绝不能假装它还下得到
                if let Ok((url, _)) =
                    crate::browse::resolve_content_file(&rec.source, &version_id).await
                {
                    if !url.is_empty() {
                        let sha1 = crate::util::file_sha1(&src).unwrap_or_default();
                        index_files.push(serde_json::json!({
                            "path": rel,
                            "hashes": { "sha1": sha1 },
                            "downloads": [url],
                            "fileSize": std::fs::metadata(&src).map(|m| m.len()).unwrap_or(0),
                        }));
                        linked += 1;
                        done = true;
                    }
                }
            }
            if !done {
                override_files.push((src, format!("overrides/{rel}")));
            }
        }
    }

    // ── 2) 额外条目：配置 / 存档 / 截图（按用户勾选）────────────────────
    let mut extras: Vec<&str> = Vec::new();
    if options.include_config {
        extras.extend(CONFIG_EXTRAS);
    }
    if options.include_saves {
        extras.extend(SAVE_EXTRAS);
    }
    if options.include_screenshots {
        extras.extend(SHOT_EXTRAS);
    }
    let mut override_dirs: Vec<(std::path::PathBuf, String)> = Vec::new();
    for rel in extras {
        let p = root.join(rel);
        if p.is_file() {
            override_files.push((p, format!("overrides/{rel}")));
        } else if p.is_dir() {
            override_dirs.push((p, format!("overrides/{rel}")));
        }
    }

    // ── 3) 清单 ─────────────────────────────────────────────────────────
    let mut deps = serde_json::Map::new();
    deps.insert("minecraft".to_string(), serde_json::json!(inst.mc_version));
    if let Some(v) = &inst.loader_version {
        let key = match inst.loader {
            Loader::Fabric => Some("fabric-loader"),
            Loader::Quilt => Some("quilt-loader"),
            Loader::Forge => Some("forge"),
            Loader::NeoForge => Some("neoforge"),
            Loader::Vanilla => None,
        };
        if let Some(k) = key {
            deps.insert(k.to_string(), serde_json::json!(v));
        }
    }
    let index = serde_json::json!({
        "formatVersion": 1,
        "game": "minecraft",
        // 用实例 id 当版本号：导入方（含我们自己）只把它当标识，不解释内容
        "versionId": inst.id,
        "name": inst.name,
        "files": index_files,
        "dependencies": Value::Object(deps),
    });
    let index_json = serde_json::to_string_pretty(&index)?;

    // ── 4) 落盘到 `<files>/exports/`（FileProvider 已映射该目录，见 file_paths.xml）
    let data_dir = crate::settings::get_data_dir().await?;
    let exports_dir = std::path::Path::new(&data_dir).join("exports");
    fs::create_dir_all(&exports_dir).await.ok();
    // 先清掉上一次的产物：分享完就没用了，留着只会在手机上白占存储
    if let Ok(mut rd) = fs::read_dir(&exports_dir).await {
        while let Ok(Some(e)) = rd.next_entry().await {
            let _ = fs::remove_file(e.path()).await;
        }
    }
    let file_name = export_file_name(&inst);
    let zip_path = exports_dir.join(&file_name);

    crate::progress::emit_install(&ctx, "export", "正在打包…", 0, 1);
    let zip_for_task = zip_path.clone();
    let bundled = override_files.len() + override_dirs.iter().map(|(p, _)| path_stats(p).0).sum::<usize>();
    let bytes = tokio::task::spawn_blocking(move || -> Result<u64, String> {
        let f = std::fs::File::create(&zip_for_task).map_err(|e| format!("创建导出包失败: {e}"))?;
        let mut zw = zip::ZipWriter::new(f);
        let opts = zip::write::FileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        zw.start_file("modrinth.index.json", opts)
            .map_err(|e| format!("写入清单失败: {e}"))?;
        std::io::Write::write_all(&mut zw, index_json.as_bytes())
            .map_err(|e| format!("写入清单失败: {e}"))?;
        for (src, rel) in &override_files {
            crate::world_backup::write_file_to_zip(&mut zw, src, rel)?;
        }
        for (src, rel) in &override_dirs {
            crate::world_backup::write_dir_to_zip(&mut zw, src, rel)?;
        }
        zw.finish().map_err(|e| format!("收尾失败: {e}"))?;
        Ok(std::fs::metadata(&zip_for_task).map(|m| m.len()).unwrap_or(0))
    })
    .await
    .map_err(|e| anyhow::anyhow!("打包任务失败: {e}"))?
    .map_err(|e| anyhow::anyhow!("{e}"))?;

    crate::progress::emit_install_done(&ctx, true, "导出完成", 1, 1);
    tracing::info!("整合包导出完成：{}（{bytes} 字节）", zip_path.display());

    // 交给系统分享：用户既能直接发给别人，也能在分享面板里「保存到文件」
    crate::android_bridge::share_file(&zip_path.to_string_lossy(), "application/zip");

    Ok(ExportResult {
        path: zip_path.to_string_lossy().to_string(),
        file_name,
        linked,
        bundled,
        bytes,
    })
}

// ==================== Mod Installation ====================

pub async fn install_mod(instance_id: &str, mod_id: &str, mod_url: &str, file_name: Option<&str>) -> Result<InstalledContent> {
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    crate::fsutil::validate_id(mod_id, "模组").map_err(|e| anyhow::anyhow!(e))?;
    let instance_dir = crate::settings::instance_dir(instance_id).await?;
    let mods_dir = instance_dir.join("mods");
    fs::create_dir_all(&mods_dir).await.context("Failed to create mods directory")?;
    let file_name = file_name.unwrap_or(&format!("{}.jar", mod_id)).to_string();
    let dest = mods_dir.join(&file_name);
    crate::download::download_file(mod_url, &dest.to_string_lossy(), None).await
        .context("Failed to download mod")?;
    let size = std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0);
    let mut record = InstalledContent::manual(file_name.clone());
    record.project_id = Some(mod_id.to_string());
    record.size = size as i64;
    // 记录写入实例元数据
    let _ = crate::instances::add_content(instance_id, "mod", record.clone()).await;
    Ok(record)
}

pub async fn uninstall_mod(instance_id: &str, mod_id: &str) -> Result<()> {
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    let instance_dir = crate::settings::instance_dir(instance_id).await?;
    let file_path = instance_dir.join("mods").join(format!("{}.jar", mod_id));
    if file_path.exists() {
        fs::remove_file(&file_path).await.context("Failed to remove mod file")?;
    }
    Ok(())
}

pub async fn get_installed_mods(instance_id: &str) -> Result<Vec<InstalledContent>> {
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    let instance_dir = crate::settings::instance_dir(instance_id).await?;
    let mods_dir = instance_dir.join("mods");
    let mut mods = Vec::new();
    let mut entries = fs::read_dir(&mods_dir).await.context("Failed to read mods directory")?;
    while let Some(entry) = entries.next_entry().await.context("Failed to read next entry")? {
        let path = entry.path();
        if path.extension().map_or(false, |ext| ext == "jar") {
            if let Ok(metadata) = fs::metadata(&path).await {
                let file_name = path.file_name().and_then(|s| s.to_str()).unwrap_or("").to_string();
                let mut record = InstalledContent::manual(file_name.clone());
                record.size = metadata.len() as i64;
                record.installed_at = metadata.modified().ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_secs()).unwrap_or(0) as i64;
                mods.push(record);
            }
        }
    }
    Ok(mods)
}
