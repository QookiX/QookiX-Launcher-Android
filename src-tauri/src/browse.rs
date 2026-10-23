use crate::settings;
use crate::download;
use anyhow::{anyhow, Context, Result};
use serde_json::{json, Value};
use std::collections::HashMap;

const MODRINTH_API: &str = "https://api.modrinth.com/v2";
const FORGE_API: &str = "https://files.minecraftforge.net/maven";

/// 内置的公共 CurseForge API Key（Eternal 官方示例 Key）。
/// 用户可在设置里填写自己的 Key，配置后所有 CurseForge 请求都会优先使用用户的 Key。
const CURSEFORGE_FALLBACK_KEY: &str =
    "$2a$10$Y1weArmW71Nn0Hb5Ckqk4.KfKGQfFqj1lYaVmKdH5l5Z3qQGZqYyC";

/// 生效的 CurseForge API Key：优先设置里的 `curseforge_api_key`，为空时回退内置 Key。
async fn curseforge_key() -> String {
    if let Ok(s) = crate::settings::get_settings().await {
        if let Some(k) = s.curseforge_api_key {
            let k = k.trim().to_string();
            if !k.is_empty() {
                return k;
            }
        }
    }
    CURSEFORGE_FALLBACK_KEY.to_string()
}

/// Minecraft 官方新闻搜索接口（minecraft.net 官网自用），按时间倒序取最新中文条目。
const NEWS_API: &str = "https://net-secondary.web.minecraft-services.net/api/v1.0/zh-cn/search?pageSize=24&sortType=Recent&category=News&newsOnly=true&geography=CN";

/// 官网原始数据里的 HTML 实体，直接展示会露出生标记。
fn decode_entities(s: &str) -> String {
    s.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&nbsp;", " ")
}

/// 拉取 Minecraft 官方新闻。网络异常时返回错误，由前端展示空态。
pub async fn fetch_news() -> Result<Vec<Value>> {
    let resp = crate::util::http_client()
        .await
        .get(NEWS_API)
        .timeout(std::time::Duration::from_secs(15))
        .send()
        .await
        .context("获取新闻失败")?;
    let status = resp.status();
    if !status.is_success() {
        anyhow::bail!("获取新闻失败: HTTP {status}");
    }
    let body: Value = resp.json().await.context("解析新闻失败")?;
    let items = body
        .pointer("/result/results")
        .and_then(|v| v.as_array())
        .cloned()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|e| {
            let title = e.get("title").and_then(|v| v.as_str()).unwrap_or("");
            if title.trim().is_empty() {
                return None;
            }
            Some(json!({
                "title": decode_entities(title),
                "description": decode_entities(e.get("description").and_then(|v| v.as_str()).unwrap_or("")),
                "author": e.get("author").and_then(|v| v.as_str()).unwrap_or(""),
                "time": e.get("time").and_then(|v| v.as_i64()).unwrap_or(0),
                "image": e.get("image").and_then(|v| v.as_str()).unwrap_or(""),
                "image_alt": e.get("imageAltText").and_then(|v| v.as_str()).unwrap_or(""),
                "url": e.get("url").and_then(|v| v.as_str()).unwrap_or(""),
            }))
        })
        .collect();
    Ok(items)
}

// ==================== Browse ====================

#[derive(serde::Serialize, Clone, Debug)]
pub struct BrowseResult {
    pub hits: Vec<ProjectHit>,
    pub total: i64,
    pub cf_error: Option<String>,
    pub cf_count: Option<i64>,
}

#[derive(serde::Serialize, Clone, Debug)]
pub struct ProjectHit {
    pub provider: String,
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub author: String,
    pub downloads: i64,
    pub follows: i64,
    pub icon_url: String,
    pub project_type: String,
    pub categories: Vec<String>,
    pub latest_version: String,
    pub game_versions: Vec<String>,
    pub updated: String,
    pub featured_image: String,
}

#[derive(serde::Serialize, Clone, Debug)]
pub struct ProjectInfoResult {
    pub provider: String,
    pub id: String,
    pub slug: String,
    pub title: String,
    pub description: String,
    pub body: String,
    pub author: String,
    pub downloads: i64,
    pub follows: i64,
    pub icon_url: String,
    pub project_type: String,
    pub categories: Vec<String>,
    pub versions: Vec<String>,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub date_created: String,
    pub date_modified: String,
    pub source_url: Option<String>,
    pub issues_url: Option<String>,
    pub wiki_url: Option<String>,
}

#[derive(serde::Serialize, Clone, Debug)]
pub struct ProjectVersionResult {
    pub id: String,
    pub name: String,
    pub version_number: String,
    pub version_type: String,
    pub date_published: String,
    pub game_versions: Vec<String>,
    pub loaders: Vec<String>,
    pub files: Vec<ProjectFile>,
    pub dependencies: Vec<Value>,
    pub download_url: String,
    pub filename: String,
    pub size: i64,
}

#[derive(serde::Serialize, Clone, Debug)]
pub struct ProjectFile {
    pub url: String,
    pub filename: String,
    pub size: i64,
    pub primary: bool,
    pub hashes: HashMap<String, String>,
}

#[derive(serde::Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ProjectDependency {
    /// 发到前端是 `projectId` / `dependencyType`（前端 `types.ts::ProjectDependency`
    /// 就是这么命名的）。少了 camelCase 转换时安装对话框的依赖标签与类型高亮会全丢
    /// —— 读到的是 undefined。
    pub project_id: String,
    pub title: String,
    pub slug: String,
    pub dependency_type: String,
}

pub async fn browse(
    provider: &str,
    query: &str,
    project_type: &str,
    category: &str,
    page: i32,
    game_version: &str,
    loader: &str,
    sort: &str,
    page_size: i32,
) -> Result<BrowseResult> {
    let client = crate::util::http_client().await;
    // Modrinth 的分页参数是 `offset`（不是 page），下面拼 URL 时会用到。
    let offset = page * page_size;

    if provider == "curseforge" {
        return browse_curseforge(&client, query, project_type, category, page, page_size).await;
    }

    // Modrinth
    let mut facets: Vec<Vec<String>> = Vec::new();
    if !project_type.is_empty() {
        facets.push(vec![format!("project_type:{}", project_type)]);
    }
    if !category.is_empty() {
        facets.push(vec![format!("categories:{}", category)]);
    }
    if !game_version.is_empty() {
        facets.push(vec![format!("versions:{}", game_version)]);
    }
    if !loader.is_empty() {
        facets.push(vec![format!("categories:{}", loader)]);
    }

    let facets_json = if facets.is_empty() {
        String::new()
    } else {
        let arr: Vec<Value> = facets.iter().map(|f| json!(f)).collect();
        format!("&facets={}", urlencoding::encode(&json!(arr).to_string()))
    };

    let sort_param = if sort.is_empty() || sort == "downloads" {
        "&index=downloads"
    } else if sort == "follows" {
        "&index=follows"
    } else if sort == "newest" {
        "&index=newest"
    } else if sort == "updated" {
        "&index=updated"
    } else {
        "&index=relevance"
    };

    // `offset` 不能漏：漏了的话不管翻到第几页都只返回第一页（用户实测踩到）。
    let url = format!(
        "{}/search?query={}&limit={}&offset={}{}{}",
        MODRINTH_API,
        urlencoding::encode(query),
        page_size,
        offset,
        sort_param,
        facets_json,
    );

    let response = client.get(&url).send().await
        .context("Failed to search Modrinth")?;
    let result: Value = response.json().await
        .context("Failed to parse Modrinth response")?;

    let hits_arr = result["hits"].as_array();
    let total = result["total_hits"].as_i64().unwrap_or(0);

    let mut hits = Vec::new();
    if let Some(hits_value) = hits_arr {
        for hit in hits_value {
            let icon = hit["icon_url"].as_str().unwrap_or("").to_string();
            let slug = hit["slug"].as_str().unwrap_or("").to_string();
            let project_id = hit["project_id"].as_str().unwrap_or("").to_string();
            hits.push(ProjectHit {
                provider: "modrinth".to_string(),
                id: project_id,
                slug: slug.clone(),
                title: hit["title"].as_str().unwrap_or("").to_string(),
                description: hit["description"].as_str().unwrap_or("").to_string(),
                author: hit["author"].as_str().unwrap_or("").to_string(),
                downloads: hit["downloads"].as_i64().unwrap_or(0),
                follows: hit["follows"].as_i64().unwrap_or(0),
                icon_url: icon.clone(),
                project_type: hit["project_type"].as_str().unwrap_or("").to_string(),
                categories: hit["categories"].as_array().unwrap_or(&vec![])
                    .iter().filter_map(|c| c.as_str().map(|s| s.to_string())).collect(),
                latest_version: hit["latest_version"].as_str().unwrap_or("").to_string(),
                game_versions: hit["versions"].as_array().unwrap_or(&vec![])
                    .iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect(),
                updated: hit["date_modified"].as_str().unwrap_or("").to_string(),
                featured_image: icon,
            });
        }
    }

    Ok(BrowseResult {
        hits,
        total,
        cf_error: None,
        cf_count: None,
    })
}

async fn browse_curseforge(
    client: &reqwest::Client,
    query: &str,
    project_type: &str,
    category: &str,
    page: i32,
    page_size: i32,
) -> Result<BrowseResult> {
    let game_id: i32 = 432; // Minecraft
    let class_id: i32 = match project_type {
        "mod" => 6,
        "modpack" => 4471,
        "resourcepack" => 12,
        "shader" => 6552,
        _ => 0,
    };

    let mut body = json!({
        "gameId": game_id,
        "searchFilter": query,
        "pageSize": page_size,
        "index": page * page_size,
        "sortField": 2, // Popularity
        "sortOrder": 0, // Descending
    });

    if class_id > 0 {
        body["classId"] = json!(class_id);
    }
    if !category.is_empty() {
        if let Ok(cat_id) = category.parse::<i32>() {
            body["categoryId"] = json!(cat_id);
        }
    }

    let response = client.post("https://api.curseforge.com/v1/mods/search")
        .header("x-api-key", curseforge_key().await)
        .json(&body)
        .send()
        .await;

    match response {
        Ok(resp) => {
            if !resp.status().is_success() {
                return Ok(BrowseResult {
                    hits: vec![],
                    total: 0,
                    cf_error: Some(format!("CurseForge API returned status {}", resp.status())),
                    cf_count: None,
                });
            }
            let result: Value = resp.json().await
                .context("Failed to parse CurseForge response")?;
            let data = result["data"].as_array();
            let total = result["pagination"]["totalCount"].as_i64().unwrap_or(0);

            let mut hits = Vec::new();
            if let Some(items) = data {
                for item in items {
                    let icon_url = item["logo"]["thumbnailUrl"].as_str()
                        .unwrap_or("").to_string();
                    let authors = item["authors"].as_array().unwrap_or(&vec![])
                        .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
                        .collect::<Vec<_>>().join(", ");
                    hits.push(ProjectHit {
                        provider: "curseforge".to_string(),
                        id: item["id"].as_i64().unwrap_or(0).to_string(),
                        slug: item["slug"].as_str().unwrap_or("").to_string(),
                        title: item["name"].as_str().unwrap_or("").to_string(),
                        description: item["summary"].as_str().unwrap_or("").to_string(),
                        author: authors,
                        downloads: item["downloadCount"].as_i64().unwrap_or(0),
                        follows: 0,
                        icon_url: icon_url.clone(),
                        project_type: item["classId"].as_i64().unwrap_or(0).to_string(),
                        categories: item["categories"].as_array().unwrap_or(&vec![])
                            .iter().filter_map(|c| c["name"].as_str().map(|s| s.to_string()))
                            .collect(),
                        latest_version: String::new(),
                        game_versions: vec![],
                        updated: item["dateModified"].as_str().unwrap_or("").to_string(),
                        featured_image: icon_url,
                    });
                }
            }

            Ok(BrowseResult {
                hits,
                total,
                cf_error: None,
                cf_count: Some(total),
            })
        }
        Err(e) => Ok(BrowseResult {
            hits: vec![],
            total: 0,
            cf_error: Some(format!("CurseForge request failed: {}", e)),
            cf_count: None,
        }),
    }
}

// ==================== CurseForge Categories ====================

pub async fn curseforge_categories() -> Result<Vec<Value>> {
    let client = crate::util::http_client().await;
    let response = client.get("https://api.curseforge.com/v1/categories?gameId=432")
        .header("x-api-key", curseforge_key().await)
        .send().await.context("Failed to fetch CurseForge categories")?;
    let result: Value = response.json().await.context("Failed to parse categories")?;
    Ok(result["data"].as_array().unwrap_or(&vec![]).clone())
}

// ==================== Project Info ====================

pub async fn project_info(provider: &str, project_id: &str) -> Result<ProjectInfoResult> {
    let client = crate::util::http_client().await;

    if provider == "curseforge" {
        return project_info_curseforge(&client, project_id).await;
    }

    // Modrinth
    let response = client.get(format!("{}/project/{}", MODRINTH_API, project_id))
        .send().await.context("Failed to get Modrinth project")?;
    let result: Value = response.json().await.context("Failed to parse Modrinth project")?;

    let authors = result["authors"].as_array().unwrap_or(&vec![])
        .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
        .collect::<Vec<_>>().join(", ");

    Ok(ProjectInfoResult {
        provider: "modrinth".to_string(),
        id: result["id"].as_str().unwrap_or("").to_string(),
        slug: result["slug"].as_str().unwrap_or("").to_string(),
        title: result["title"].as_str().unwrap_or("").to_string(),
        description: result["description"].as_str().unwrap_or("").to_string(),
        body: result["body"].as_str().unwrap_or("").to_string(),
        author: authors,
        downloads: result["downloads"].as_i64().unwrap_or(0),
        follows: result["follows"].as_i64().unwrap_or(0),
        icon_url: result["icon_url"].as_str().unwrap_or("").to_string(),
        project_type: result["project_type"].as_str().unwrap_or("").to_string(),
        categories: result["categories"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|c| c.as_str().map(|s| s.to_string())).collect(),
        versions: result["versions"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect(),
        game_versions: result["game_versions"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect(),
        loaders: result["loaders"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|l| l.as_str().map(|s| s.to_string())).collect(),
        date_created: result["date_created"].as_str().unwrap_or("").to_string(),
        date_modified: result["date_modified"].as_str().unwrap_or("").to_string(),
        source_url: result["source_url"].as_str().map(|s| s.to_string()),
        issues_url: result["issues_url"].as_str().map(|s| s.to_string()),
        wiki_url: result["wiki_url"].as_str().map(|s| s.to_string()),
    })
}

async fn project_info_curseforge(client: &reqwest::Client, project_id: &str) -> Result<ProjectInfoResult> {
    let response = client.get(format!("https://api.curseforge.com/v1/mods/{}", project_id))
        .header("x-api-key", curseforge_key().await)
        .send().await.context("Failed to get CurseForge project")?;
    let result: Value = response.json().await.context("Failed to parse CurseForge project")?;
    let data = &result["data"];

    let authors = data["authors"].as_array().unwrap_or(&vec![])
        .iter().filter_map(|a| a["name"].as_str().map(|s| s.to_string()))
        .collect::<Vec<_>>().join(", ");

    let links = &data["links"];
    Ok(ProjectInfoResult {
        provider: "curseforge".to_string(),
        id: data["id"].as_i64().unwrap_or(0).to_string(),
        slug: data["slug"].as_str().unwrap_or("").to_string(),
        title: data["name"].as_str().unwrap_or("").to_string(),
        description: data["summary"].as_str().unwrap_or("").to_string(),
        body: data["description"].as_str().unwrap_or("").to_string(),
        author: authors,
        downloads: data["downloadCount"].as_i64().unwrap_or(0),
        follows: 0,
        icon_url: data["logo"]["thumbnailUrl"].as_str().unwrap_or("").to_string(),
        project_type: data["classId"].as_i64().unwrap_or(0).to_string(),
        categories: data["categories"].as_array().unwrap_or(&vec![])
            .iter().filter_map(|c| c["name"].as_str().map(|s| s.to_string())).collect(),
        versions: vec![],
        game_versions: vec![],
        loaders: vec![],
        date_created: data["dateCreated"].as_str().unwrap_or("").to_string(),
        date_modified: data["dateModified"].as_str().unwrap_or("").to_string(),
        source_url: links["sourceUrl"].as_str().map(|s| s.to_string()),
        issues_url: links["issuesUrl"].as_str().map(|s| s.to_string()),
        wiki_url: None,
    })
}

// ==================== Project Versions ====================

pub async fn project_versions(provider: &str, project_id: &str, mc_version: &str, loader: &str) -> Result<Vec<ProjectVersionResult>> {
    let client = crate::util::http_client().await;

    if provider == "curseforge" {
        return project_versions_curseforge(&client, project_id, mc_version).await;
    }

    // Modrinth
    let mut url = format!("{}/project/{}/version", MODRINTH_API, project_id);
    let mut params = Vec::new();
    if !mc_version.is_empty() {
        params.push(format!("game_versions=[\"{}\"]", mc_version));
    }
    if !loader.is_empty() {
        params.push(format!("loaders=[\"{}\"]", loader));
    }
    if !params.is_empty() {
        url = format!("{}?{}", url, params.join("&"));
    }

    let response = client.get(&url).send().await
        .context("Failed to get Modrinth versions")?;
    let result: Value = response.json().await
        .context("Failed to parse Modrinth versions")?;
    let versions_arr = result.as_array();

    let mut versions = Vec::new();
    if let Some(items) = versions_arr {
        for v in items {
            let files = v["files"].as_array();
            let primary_file = files.and_then(|f| f.iter().find(|f| f["primary"].as_bool().unwrap_or(false)))
                .or_else(|| files.and_then(|f| f.first()));

            let file_url = primary_file.and_then(|f| f["url"].as_str()).unwrap_or("").to_string();
            let filename = primary_file.and_then(|f| f["filename"].as_str()).unwrap_or("").to_string();
            let size = primary_file.and_then(|f| f["size"].as_i64()).unwrap_or(0);

            let mut hashes = HashMap::new();
            if let Some(h) = primary_file.and_then(|f| f["hashes"].as_object()) {
                for (k, val) in h {
                    if let Some(s) = val.as_str() {
                        hashes.insert(k.clone(), s.to_string());
                    }
                }
            }

            let deps: Vec<Value> = v["dependencies"].as_array().unwrap_or(&vec![])
                .iter().map(|d| d.clone()).collect();

            versions.push(ProjectVersionResult {
                id: v["id"].as_str().unwrap_or("").to_string(),
                name: v["name"].as_str().unwrap_or("").to_string(),
                version_number: v["version_number"].as_str().unwrap_or("").to_string(),
                version_type: v["version_type"].as_str().unwrap_or("").to_string(),
                date_published: v["date_published"].as_str().unwrap_or("").to_string(),
                game_versions: v["game_versions"].as_array().unwrap_or(&vec![])
                    .iter().filter_map(|gv| gv.as_str().map(|s| s.to_string())).collect(),
                loaders: v["loaders"].as_array().unwrap_or(&vec![])
                    .iter().filter_map(|l| l.as_str().map(|s| s.to_string())).collect(),
                files: vec![ProjectFile {
                    url: file_url.clone(),
                    filename: filename.clone(),
                    size,
                    primary: true,
                    hashes,
                }],
                dependencies: deps,
                download_url: file_url,
                filename,
                size,
            });
        }
    }
    Ok(versions)
}

async fn project_versions_curseforge(
    client: &reqwest::Client,
    project_id: &str,
    mc_version: &str,
) -> Result<Vec<ProjectVersionResult>> {
    let mut url = format!("https://api.curseforge.com/v1/mods/{}/files?pageSize=50", project_id);
    if !mc_version.is_empty() {
        url = format!("{}&gameVersion={}", url, urlencoding::encode(mc_version));
    }

    let response = client.get(&url)
        .header("x-api-key", curseforge_key().await)
        .send().await.context("Failed to get CurseForge files")?;
    let result: Value = response.json().await.context("Failed to parse CurseForge files")?;
    let files = result["data"].as_array();

    let mut versions = Vec::new();
    if let Some(items) = files {
        for f in items {
            let file_url = f["downloadUrl"].as_str().unwrap_or("").to_string();
            let release_type = match f["releaseType"].as_i64().unwrap_or(1) {
                1 => "release",
                2 => "beta",
                3 => "alpha",
                _ => "release",
            };
            versions.push(ProjectVersionResult {
                id: f["id"].as_i64().unwrap_or(0).to_string(),
                name: f["displayName"].as_str().unwrap_or("").to_string(),
                version_number: f["fileName"].as_str().unwrap_or("").to_string(),
                version_type: release_type.to_string(),
                date_published: f["fileDate"].as_str().unwrap_or("").to_string(),
                game_versions: f["gameVersions"].as_array().unwrap_or(&vec![])
                    .iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect(),
                loaders: vec![],
                files: vec![ProjectFile {
                    url: file_url.clone(),
                    filename: f["fileName"].as_str().unwrap_or("").to_string(),
                    size: f["fileLength"].as_i64().unwrap_or(0),
                    primary: true,
                    hashes: HashMap::new(),
                }],
                dependencies: vec![],
                download_url: file_url,
                filename: f["fileName"].as_str().unwrap_or("").to_string(),
                size: f["fileLength"].as_i64().unwrap_or(0),
            });
        }
    }
    Ok(versions)
}

// ==================== Project Dependencies ====================

pub async fn project_dependencies(
    provider: &str,
    project_id: &str,
    version_id: Option<&str>,
) -> Result<Vec<ProjectDependency>> {
    if provider == "curseforge" {
        return Ok(vec![]); // CurseForge deps handled via files
    }

    let client = crate::util::http_client().await;
    // **必须按版本查**：Modrinth 的依赖是**版本级**的 —— `/project/{id}` 上那个
    // `dependencies` 数组绝大多数项目是空的，只查它等于「依赖列表永远是空的」
    // （安装对话框里那排依赖标签从来没出现过）。前端明明传了 version_id，
    // 这里以前却用 `_version_id` 把它丢掉了。
    let url = match version_id.map(str::trim).filter(|v| !v.is_empty()) {
        Some(v) => format!("{MODRINTH_API}/project/{project_id}/version/{v}"),
        None => format!("{MODRINTH_API}/project/{project_id}"),
    };
    let response = client.get(url).send().await.context("Failed to get project version")?;
    let result: Value = response.json().await.context("Failed to parse project version")?;

    let mut deps = Vec::new();
    if let Some(relations) = result["dependencies"].as_array() {
        for dep in relations {
            let dep_id = dep["project_id"].as_str().unwrap_or("").to_string();
            if dep_id.is_empty() { continue; }
            // Fetch dependency info
            if let Ok(dep_info) = client.get(format!("{}/project/{}", MODRINTH_API, dep_id))
                .send().await {
                if let Ok(dep_val) = dep_info.json::<Value>().await {
                    deps.push(ProjectDependency {
                        project_id: dep_id,
                        title: dep_val["title"].as_str().unwrap_or("").to_string(),
                        slug: dep_val["slug"].as_str().unwrap_or("").to_string(),
                        dependency_type: dep["dependency_type"].as_str().unwrap_or("required").to_string(),
                    });
                }
            }
        }
    }
    Ok(deps)
}

// ==================== Loader Versions ====================

/// 加载器本体在共享 `libraries/` 里的落盘路径。
///
/// 「下载」与「完整性检查」必须共用这一份：两边各写一套命名时，
/// 检查会说「缺加载器本体」而安装说「已经装好了」，用户点补全永远补不完。
pub fn loader_jar_path(
    libraries_dir: &std::path::Path,
    loader: &str,
    mc_version: &str,
    loader_version: &str,
) -> std::path::PathBuf {
    match loader {
        "fabric" => libraries_dir.join(format!("fabric-loader-{loader_version}.jar")),
        "quilt" => libraries_dir.join(format!("quilt-loader-{loader_version}.jar")),
        "forge" => libraries_dir.join(format!("forge-{mc_version}-{loader_version}-installer.jar")),
        "neoforge" => libraries_dir.join(format!("neoforge-{loader_version}-installer.jar")),
        _ => libraries_dir.join(format!("{loader}-{loader_version}.jar")),
    }
}

/// 按「镜像 → 官方」取版本列表并合并去重（新的在前）。
///
/// 不能只用第一份成功的：镜像站上文件存在但内容可能陈旧
/// （Forge 的推广表就缺新游戏版本），只认镜像会一直报「没有版本」。
/// 两份并起来，缺一份还能用，陈旧一份也不会漏。
async fn fetch_list_mirrored<F>(
    client: &reqwest::Client,
    base: &str,
    url: &str,
    parse: F,
) -> Result<Vec<String>>
where
    F: Fn(&str) -> Vec<String>,
{
    let mut urls: Vec<String> = Vec::new();
    if !base.is_empty() {
        let mapped = crate::mirror::map(base, url);
        if mapped != url {
            urls.push(mapped);
        }
    }
    // ⚠️ 2026-10-20 真机修：**用户没配镜像时，也要挂上内置镜像作为候选**。
    //
    // 以前这里只在 `base`（用户选的镜像）非空时才加镜像地址，选了「官方」就
    // 只请求官方。可官方 Fabric meta 对某些 MC 版本慢得离谱 —— 真机实测
    // （OnePlus 8 / 26.3）：
    //   官方 https://meta.fabricmc.net/v2/versions/loader/26.3   → 9~30 秒，经常超时
    //   镜像 https://bmclapi2.bangbang93.com/fabric-meta/...     → 0.71 秒
    // 结果就是创建实例时「加载器版本获取失败」，用户建不出可用的实例。
    // 既然镜像只是**候选**（并行发、谁先回用谁），多挂一个纯赚。
    let mirrored_default = crate::mirror::map(crate::mirror::BMCLAPI_BASE, url);
    if !mirrored_default.is_empty() && mirrored_default != url && !urls.contains(&mirrored_default) {
        urls.push(mirrored_default);
    }
    urls.push(url.to_string());

    // ⚠️ 2026-10-20 真机修：**改串行为并行**。
    //
    // 原来是「先试镜像，失败/超时后再试官方」串行两轮。而 `util::http_client`
    // 的 connect_timeout 是 15 秒、整体 timeout 600 秒 —— 移动网络下光连接就可能
    // 吃满 15 秒，两轮串起来轻松超过界面给的 60 秒，用户看到的就是
    //   「加载器版本获取失败：命令超时：get_loader_versions」
    // （真机实测：ping meta.fabricmc.net 单程 1106ms，取版本列表必然超时）。
    //
    // 现在几个候选 URL **同时发**，谁先成功用谁；总耗时由最快的那个决定。
    // 每个请求的硬超时。
    //
    // ⚠️ 2026-10-20 真机实测：Fabric 官方 meta 对某些 MC 版本（例如 26.3）
    // 的查询**非常慢** —— 设备上 curl 直接跑：
    //   /v2/versions/loader        → 200，4.0 / 4.8 / 8.4 秒
    //   /v2/versions/loader/26.3   → **30 秒超时失败**
    // 而创建实例必须用带版本号的那个接口，所以 20 秒的窗口不够，
    // 表现为「加载器版本获取失败」→ 建不出可用的实例 → 启动闪退。
    // 放到 45 秒：比实测最坏情况（30s+）留出余量，又不至于让用户干等太久。
    let per_req = std::time::Duration::from_secs(45);
    let mut tasks = Vec::new();
    for u in urls {
        let client = client.clone();
        // ⚠️ 2026-10-20 真机实测：**BMCLAPI 对自定义 UA 直接返回 302 空响应**。
        //
        // 设备上同一 URL 的对照测试：
        //   curl -A 'QookiX-Launcher-Android' → 302，0 字节（拿不到内容）
        //   curl 不带 UA                     → 200，59308 字节（正常）
        // 而 `util::http_client()` 全局设了 `user_agent("QookiX-Launcher-Android")`，
        // 于是**所有走镜像的元数据请求都被静默拦掉** —— NeoForge 版本列表因此
        // 永远获取失败（官方源又慢到超时），用户看到的就是
        // 「加载器版本获取失败：获取 NeoForge 版本列表失败」。
        //
        // 镜像请求改用中性 UA；官方请求仍带我们自己的 UA。
        let is_mirror = u.contains("bangbang93.com");
        tasks.push(Box::pin(async move {
            let mut req = client.get(&u);
            if is_mirror {
                req = req.header(reqwest::header::USER_AGENT, "Mozilla/5.0");
            }
            match tokio::time::timeout(per_req, req.send()).await {
                Ok(Ok(r)) if r.status().is_success() => {
                    match tokio::time::timeout(per_req, r.text()).await {
                        Ok(Ok(t)) => (u, Ok(t)),
                        Ok(Err(e)) => (u.clone(), Err(format!("{u} 读取失败：{e}"))),
                        Err(_) => (u.clone(), Err(format!("{u} 读取超时"))),
                    }
                }
                Ok(Ok(r)) => (u.clone(), Err(format!("{u} 返回 {}", r.status()))),
                Ok(Err(e)) => (u.clone(), Err(format!("{u} 请求失败：{e}"))),
                Err(_) => (u.clone(), Err(format!("{u} 请求超时"))),
            }
        }));
    }
    // ⚠️ 用 `select_all` 而不是 `join_all`：**谁先成功用谁**。
    // 镜像可能 302 到一个连不上的下载域名、挂满 45 秒；`join_all` 会把官方
    // 1 秒的成功一起拖到 45 秒后，叠加首启开销就超过前端窗口，
    // 用户看到「加载器版本获取失败」（真机实测）。
    let (mut out, mut got_any, mut last): (Vec<String>, bool, String) =
        (Vec::new(), false, String::new());
    // 记录**每个**候选的失败原因：只留最后一个的话，「镜像也失败了」这种情况
    // 会被官方那条覆盖，看日志会误以为镜像压根没试过。
    let mut all_errs: Vec<String> = Vec::new();
    let mut pending = futures::future::select_all(tasks);
    loop {
        let (r, _idx, rest) = pending.await;
        match r {
            (_, Ok(t)) => {
                got_any = true;
                out.extend(parse(&t));
                break;
            }
            (_, Err(e)) => {
                last = e.clone();
                all_errs.push(e);
            }
        }
        if rest.is_empty() {
            break;
        }
        pending = futures::future::select_all(rest);
    }
    // 全都拿不到才算失败；拿到了但确实没有对应版本时返回空表，交给调用方提示「换版本」
    if !got_any && !last.is_empty() {
        let detail = if all_errs.is_empty() {
            last.clone()
        } else {
            all_errs.join(" | ")
        };
        crate::util::log_line(&format!("[meta] 版本列表拉取失败 url={url} 原因={detail}"));
        println!("[meta] 版本列表拉取失败 url={url} 原因={detail}");
        return Err(anyhow::anyhow!("{detail}"));
    }
    Ok(sorted_desc(out))
}

/// 从 Fabric / Quilt 的 meta 列表取版本号，形状都是 `[{ "loader": { "version": "…" } }, …]`
fn parse_loader_meta_versions(text: &str) -> Vec<String> {
    let Ok(value) = serde_json::from_str::<Value>(text) else {
        return Vec::new();
    };
    value
        .as_array()
        .map(|arr| {
            arr.iter()
                .filter_map(|v| v["loader"]["version"].as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default()
}

/// 从 Forge 推广表取版本号，键形如 `1.21.1-latest` / `1.21.1-recommended`
fn parse_forge_promos_versions(text: &str, mc_version: &str) -> Vec<String> {
    let Ok(value) = serde_json::from_str::<Value>(text) else {
        return Vec::new();
    };
    let Some(promos) = value["promos"].as_object() else {
        return Vec::new();
    };

    let mut out = Vec::new();
    for (key, ver) in promos {
        let Some((mc, channel)) = key.split_once('-') else { continue };
        if channel != "latest" && channel != "recommended" {
            continue;
        }
        if !mc_version.is_empty() && mc != mc_version {
            continue;
        }
        if let Some(v) = ver.as_str() {
            out.push(v.to_string());
        }
    }
    out
}

/// 版本号排序键：每段数字补成 10 位，之后直接按字典序比就等价于按版本号比。
/// （不补位的话 `21.1.9` 会被判成大于 `21.1.219`，「默认选最新」就会选到老版本。）
fn ver_key(v: &str) -> String {
    let (mut out, mut num) = (String::new(), String::new());
    for ch in v.chars() {
        if ch.is_ascii_digit() {
            num.push(ch);
            continue;
        }
        if !num.is_empty() {
            out.push_str(&format!("{num:0>10}"));
            num.clear();
        }
        out.push(ch.to_ascii_lowercase());
    }
    if !num.is_empty() {
        out.push_str(&format!("{num:0>10}"));
    }
    out
}

/// 版本号降序（新的在前）。
fn sorted_desc(mut list: Vec<String>) -> Vec<String> {
    list.sort_by_key(|v| std::cmp::Reverse(ver_key(v)));
    list.dedup();
    list
}

/// 从 `maven-metadata.xml` 抠 `<version>` 列表。格式固定，按标签扫一遍即可，
/// 只为这一处引 XML 库不划算；扫不出来返回空表，不会误判成「没有版本」以外的结论。
///
/// ⚠️ 2026-10-20 修：**必须自己排序**。
///
/// Maven 的 `maven-metadata.xml` 只是按写入顺序罗列，**并不保证升序**。
/// 真机抓到的 NeoForge 列表尾部是：
///   … 26.3.0.92, 21.1.263, 21.1.264, 26.3.0.93, 26.3.0.94, 26.3.0.95
/// 交叉混着，前端若取 `first()` / `last()` 当「最新」，拿到的版本号就是错的
/// （用户反馈「NeoForge 版本号是不是错了」）。
/// 这里统一按数字段降序排列，最新的排最前，与其它加载器接口的行为一致。
fn xml_versions(xml: &str) -> Vec<String> {
    let mut out: Vec<String> = xml
        .split("<version>")
        .skip(1)
        .filter_map(|s| s.split("</version>").next())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();
    out.sort_by(|a, b| cmp_version_desc(a, b));
    out
}

/// 按「数字段逐个比较」的方式降序：`26.3.0.95` > `26.3.0.93` > `21.1.264`。
///
/// 不能直接字符串比较（"9" > "10"），也不能整体 parse 成数字（版本是点分的）。
/// 遇到非数字段就退回字符串比较，保证不 panic、顺序稳定。
fn cmp_version_desc(a: &str, b: &str) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    let seg = |s: &str| -> Vec<u64> {
        s.split(|c: char| !c.is_ascii_digit())
            .filter(|p| !p.is_empty())
            .filter_map(|p| p.parse::<u64>().ok())
            .collect()
    };
    let (sa, sb) = (seg(a), seg(b));
    for i in 0..sa.len().max(sb.len()) {
        let x = sa.get(i).copied().unwrap_or(0);
        let y = sb.get(i).copied().unwrap_or(0);
        match y.cmp(&x) {
            Ordering::Equal => continue,
            other => return other,
        }
    }
    // 数字段完全一致（如 1.21.1 与 1.21.1-beta）时，退回字符串降序
    b.cmp(a)
}

pub async fn get_loader_versions(loader: String, mc_version: String) -> Result<Vec<String>> {
    let client = crate::util::http_client().await;
    let mirror_base = match crate::settings::get_settings().await {
        Ok(s) => crate::mirror::resolve_from(&s.mirror, s.mirror_custom.as_deref().unwrap_or("")),
        Err(_) => String::new(),
    };

    match loader.as_str() {
        // 官方按游戏版本给列表：`GET /versions/loader/{game_version}`
        // → `[{ "loader": { "version": "0.19.5" }, … }]`。
        // 之前请求的是不带游戏版本的那份列表，返回体里没有 `game_version` 字段，过滤后恒为空。
        "fabric" => {
            let url = if mc_version.is_empty() {
                "https://meta.fabricmc.net/v2/versions/loader".to_string()
            } else {
                format!("https://meta.fabricmc.net/v2/versions/loader/{mc_version}")
            };
            fetch_list_mirrored(&client, &mirror_base, &url, parse_loader_meta_versions)
                .await
                .context("获取 Fabric 版本列表失败")
        }
        // Forge 只有「每个游戏版本一条」的推广表
        "forge" => {
            let url = "https://files.minecraftforge.net/maven/net/minecraftforge/forge/promotions_slim.json";
            fetch_list_mirrored(&client, &mirror_base, url, |text| {
                parse_forge_promos_versions(text, &mc_version)
            })
            .await
            .context("获取 Forge 版本列表失败")
        }
        // NeoForge 没有版本清单接口，只能读自家 Maven 的 `maven-metadata.xml`。
        // 版本号 = 游戏版本去掉前导 `1.`：1.21.1 → 21.1.x、26.3 → 26.3.x。
        // 之前这里指向 Modrinth 上另一个项目（Fabric API），所以永远取不到版本。
        "neoforge" => {
            let url = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
            let prefix = if mc_version.is_empty() {
                String::new()
            } else {
                format!(
                    "{}.",
                    mc_version.strip_prefix("1.").unwrap_or(mc_version.as_str())
                )
            };
            fetch_list_mirrored(&client, &mirror_base, url, |text| {
                xml_versions(text)
                    .into_iter()
                    .filter(|v| prefix.is_empty() || v.starts_with(&prefix))
                    .collect()
            })
            .await
            .context("获取 NeoForge 版本列表失败")
        }
        // 官方同样按游戏版本给：`GET /v3/versions/loader/{game_version}`
        "quilt" => {
            let root = "https://meta.quiltmc.org/v3/versions/loader";
            let url = if mc_version.is_empty() {
                root.to_string()
            } else {
                format!("{root}/{mc_version}")
            };
            fetch_list_mirrored(&client, &mirror_base, &url, parse_loader_meta_versions)
                .await
                .context("获取 Quilt 版本列表失败")
        }
        _ => Ok(vec![]),
    }
}

// ==================== Install Game ====================

pub async fn install_game(instance_id: &str) -> Result<serde_json::Value> {
    // ⚠️ 2026-10-20 真机排查：这条入口日志是判断「创建实例后到底有没有自动装」
    // 的唯一可靠依据 —— 之前它一行都不打，于是「实例建出来依赖 0/7、
    // 下载页也看不到任务」时完全无从下手。
    println!("[install] install_game 开始 instance={instance_id}");
    crate::util::log_line(&format!("[install] install_game 开始 instance={instance_id}"));
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    let instance_path = settings::instance_dir(instance_id).await?;
    let instance_json = instance_path.join("instance.json");

    if !instance_json.exists() {
        println!("[install] 失败：instance.json 不存在 ({})", instance_json.display());
        return Err(anyhow::anyhow!("Instance not found"));
    }

    let content = tokio::fs::read_to_string(&instance_json).await
        .context("Failed to read instance.json")?;
    let mut instance: Value = serde_json::from_str(&content)
        .context("Failed to parse instance.json")?;

    let mc_version = instance["mc_version"]
        .as_str()
        .or_else(|| instance["mcVersion"].as_str())
        .unwrap_or("1.20.1")
        .to_string();
    let loader = instance["loader"]
        .as_str()
        .map(|s| s.to_lowercase())
        .unwrap_or_else(|| "vanilla".to_string());
    let instance_name = instance["name"].as_str().unwrap_or(instance_id).to_string();

    // 「下载中心」靠这些事件驱动；taskId 与阶段名要和前端 STAGE_LABELS 对齐
    let ctx = crate::progress::TaskCtx::new(instance_id, &instance_name, "游戏本体");
    crate::progress::emit_install(&ctx, "manifest", "正在获取版本信息…", 0, 3);

    let result: Result<serde_json::Value> = async {
        // Install version files
        crate::version::install_version_tracked(&mc_version, Some(&ctx)).await?;

        // Install loader if needed
        if loader != "vanilla" {
            let mut loader_version = instance["loader_version"].as_str().unwrap_or("").to_string();
            println!("[install] 加载器分支: loader={loader} 版本参数='{loader_version}'");
            // 老实例（以及创建时没让用户选版本的那批）`loader_version` 是空的：
            // 这里补一次「取最新」并写回 instance.json，否则加载器装不上，
            // 「补全文件」也会因为拿不到版本号而判成修不了。
            if loader_version.is_empty() {
                if let Some(latest) = get_loader_versions(loader.clone(), mc_version.clone())
                    .await
                    .unwrap_or_default()
                    .into_iter()
                    .next()
                {
                    loader_version = latest;
                    instance["loader_version"] = json!(loader_version);
                }
            }
            if !loader_version.is_empty() {
                crate::progress::emit_install(&ctx, "loader", "正在安装加载器…", 2, 3);
                println!("[install] 调用 install_loader: {loader} {mc_version} {loader_version}");
                match install_loader(&loader, &mc_version, &loader_version, &instance_path).await {
                    Ok(()) => println!("[install] install_loader 完成"),
                    Err(e) => {
                        println!("[install] install_loader 失败: {e:#}");
                        // ⚠️ 2026-10-23 真机修：这里原来直接 `return Err` ✗，**没有发终止事件**。
                        // 后果（用户实测）：
                        //   ① 顶部任务横幅永远停在「正在安装加载器…885.1/885.1 MB」✗
                        //   ② `TasksRepo` 里一直挂着一个"活跃任务" → 之后点「补全」被
                        //      `hasActiveTaskFor` 防重入判断直接跳过 → **点了没反应** ✗✗
                        crate::progress::emit_install_done(
                            &ctx,
                            false,
                            &format!("加载器安装失败：{e:#}"),
                            2,
                            3,
                        );
                        return Err(e);
                    }
                }
            } else {
                // ⚠️⚠️ 2026-10-20 真机修（阻断性缺陷）。
                //
                // 以前这里是**静默跳过**：`loader_version` 为空就什么都不装，
                // 接着往下把实例标记成 `installed = true`。真机实测后果：
                //   1. 弱网下 `get_loader_versions` 失败（meta.fabricmc.net 单程 1.1s）
                //   2. loader_version 取不到 → 跳过安装
                //   3. 实例显示「已安装」，实际一个加载器库都没有
                //   4. 启动 → `加载器依赖缺失 7 个（已就绪 0 / 共 7）` → 加载界面闪退
                // 也就是用户报的「Fabric 26.3 在加载界面会闪退」。
                //
                // 现在明确失败：宁可让用户看到「装不上」，也不能给一个注定闪退的实例。
                crate::util::log_line(&format!(
                    "[launcher] 加载器版本号为空，拒绝把 {loader} {mc_version} 标记为已安装"
                ));
                return Err(anyhow::anyhow!(
                    "拿不到 {loader} 的版本号，加载器没装成。请检查网络后重新点「补全游戏文件」"
                ));
            }
        }

        let total_bytes: i64 = calculate_dir_size(&instance_path).await;
        let file_count = count_files(&instance_path).await;

        // 标记游戏已安装，前端据此隐藏「未安装」横幅
        instance["installed"] = json!(true);
        let updated = serde_json::to_string_pretty(&instance)
            .context("Failed to serialize instance.json")?;
        tokio::fs::write(&instance_json, updated).await
            .context("Failed to write instance.json")?;

        Ok(json!({
            "instance_id": instance_id,
            "total_bytes": total_bytes,
            "file_count": file_count,
        }))
    }
    .await;

    match &result {
        Ok(_) => crate::progress::emit_install_done(&ctx, true, "安装完成", 3, 3),
        Err(e) => crate::progress::emit_install_done(&ctx, false, &format!("安装失败：{e}"), 0, 3),
    }
    result
}

/// 把加载器本体下到共享 `libraries/`。
///
/// 落盘路径必须与 [`loader_jar_path`] 一致，否则「安装说已装好、检查说还缺」。
async fn install_loader(loader: &str, mc_version: &str, loader_version: &str, instance_path: &std::path::Path) -> Result<()> {
    // 诊断日志：双通道（println 进 logcat，log_line 落文件）。
    // 安装链路长且跨网络，真机上「卡住」时必须有痕迹可查。
    crate::util::log_line(&format!(
        "[loader] install_loader 开始: loader={loader} mc={mc_version} ver={loader_version}"
    ));
    let libs_dir = instance_path.parent().unwrap().parent().unwrap().join("libraries");
    tokio::fs::create_dir_all(&libs_dir).await.ok();

    let dest = loader_jar_path(&libs_dir, loader, mc_version, loader_version);

    // ⚠️ 2026-10-19 起：jar 存在**不再**直接返回。
    // 以前只下载 jar 就当装好了，结果启动时加载器根本没生效
    //（classpath 里没有它，profile 也没生成）—— 见 loader_install.rs 的说明。
    // 现在改为：先看 profile 是否已生成，已生成才算真的装好。
    let data_dir = settings::get_data_dir().await?;
    let versions_dir = std::path::Path::new(&data_dir).join("versions");
    // ⚠️ 2026-10-19 修：原来这里是 `return Ok(())` —— 但那样 profile 已存在时
    // 依赖库永远不会被下载（新实例直接复用旧 profile），启动必然缺类。
    // 现在只跳过「生成 profile」，依赖校验无论如何都要走。
    //
    // ⚠️ 2026-10-20 再修（更严重的一条）：**「文件存在」不等于「内容可用」**。
    //
    // 真机实测：设备上留着早期版本生成的 profile（libraries 里没有 `sha1` 字段），
    // 于是每轮都走「已存在 → 跳过生成」→ 旧文件永远不被修好 →
    // `profile_libraries` 里 7 条全部 `missing field sha1` → 依赖解析 0 个 →
    // `download_libraries` 无事可做 → 启动「加载器依赖缺失 7 个」→ 闪退。
    // 这是典型的「坏缓存自锁」：问题数据本身成了「不必修复」的证据。
    //
    // 现在对 Fabric / Quilt 追加一道内容校验：能解析出至少一个库才算可用，
    // 否则当作不存在、重新生成。
    let mut profile_exists =
        crate::loader_install::find_loader_profile(&versions_dir, loader, mc_version).is_some();
    if profile_exists && matches!(loader, "fabric" | "quilt") {
        let usable = crate::loader_install::find_loader_profile(&versions_dir, loader, mc_version)
            .map(|p| !crate::loader_install::profile_libraries(&p).is_empty())
            .unwrap_or(false);
        if !usable {
            crate::util::log_line(
                "[loader] 已有 profile 的 libraries 解析为空（多半是旧版本留下的缺字段文件），重新生成",
            );
            println!("[install] profile 不可用，删除后重新生成");
            if let Some(p) = crate::loader_install::find_loader_profile(
                &versions_dir,
                loader,
                mc_version,
            ) {
                let _ = std::fs::remove_dir_all(&p);
            }
            profile_exists = false;
        }
    }
    if profile_exists {
        tracing::info!("加载器 profile 已存在，跳过生成（仍会校验依赖）");
        crate::util::log_line("[loader] profile 已存在，跳过生成，继续校验依赖");
    }
    if !dest.exists() {
        // `download_file` 内部会按镜像设置改写地址并回退官方源，这里只给官方地址
        let url = match loader {
            "fabric" => format!("https://maven.fabricmc.net/net/fabricmc/fabric-loader/{loader_version}/fabric-loader-{loader_version}.jar"),
            "quilt" => format!("https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-loader/{loader_version}/quilt-loader-{loader_version}.jar"),
            "forge" => format!("https://files.minecraftforge.net/maven/net/minecraftforge/forge/{mc_version}-{loader_version}/forge-{mc_version}-{loader_version}-installer.jar"),
            // 之前这里查的是 Modrinth 上的 Fabric API，装 NeoForge 会下一个完全无关的 jar
            "neoforge" => format!("https://maven.neoforged.net/releases/net/neoforged/neoforge/{loader_version}/neoforge-{loader_version}-installer.jar"),
            other => return Err(anyhow::anyhow!("不支持的加载器：{other}")),
        };
        download::download_file(&url, &dest.to_string_lossy(), None).await?;
        crate::util::log_line("[loader] profile 已存在，跳过生成，继续校验依赖");
    }

    // ── 真正运行 installer，生成加载器 profile ──────────────────────
    //
    // 只有 Forge / NeoForge 需要跑 installer（它们会生成带 inheritsFrom 的
    // profile）。Fabric / Quilt 的 jar 本身不是 installer，运行它只会启动
    // 一个 GUI 安装器 —— 安卓 headless 下没有意义，改为下面拼 profile。
    if !profile_exists {
        if matches!(loader, "forge" | "neoforge") {
            install_forge_like(loader, mc_version, loader_version, &dest, &versions_dir).await?;
        } else {
            // Fabric / Quilt：官方做法也是跑 installer，但它们的 jar 就是 loader 本体，
            // 需要的是「生成 profile + 下载依赖」。这里先生成 profile，
            // 依赖由启动时按 profile 的 libraries 补齐。
            write_simple_loader_profile(loader, mc_version, loader_version, &dest, &versions_dir).await?;
        }
    }

    // ── 跑一次官方 installer 的 processors 链（Forge / NeoForge）──────
    //
    // ⚠️ 2026-10-23 关键修复（NeoForge 1.21.1「一个模组都不加载 / 闪退」的根）。
    //
    // 之前这里**只**调 `install_forge_like` —— 它从 installer 里解出 version.json
    // 就完事，**从不执行 processors 链** ✗。于是 `libraries/` 里没有打过补丁的客户端、
    // 也没有 `client-extra.jar` → FML 的 ModList 里 `minecraft` / `neoforge`
    // 两个模组**根本不存在** → 每个模组的依赖都判 `[MISSING]`
    // → `ModSorter: Missing or unsupported mandatory dependencies`
    // → `NoSuchElementException` → 闪退。
    //（真机证据：所有 `Found mod file` 都来自 mods 文件夹；libraries 里找不到
    //  client-extra/neoform 产物；1.21.1.jar 一直是原版大小。）
    //
    // 桌面端是**重写整套 processors**（见 QookiX-Launcher/src-tauri/src/install.rs
    // ::run_forge_processors）；安卓这边更省事：直接跑官方 installer 的
    // `--installClient`，它自己会解包 data/、下工具、跑 processors，产出打补丁的
    // client 与 client-extra.jar（Pojav 也是这么做的 ✓）。
    //
    // ⚠️ **必须在 `if !profile_exists` 外面**：老实例的 profile 早就存在了
    //（之前"只解包"那版生成的），但 processors 从没跑过 —— 放在里面会被整段跳过 ✗
    //（2026-10-23 就因为这个白跑了一轮）。用标记文件记录"本机成功跑过 processors"。
    if matches!(loader, "forge" | "neoforge") {
        let patch_marker = std::path::Path::new(&data_dir)
            .join("libraries")
            .join(format!(".qookix-processors-{loader}-{loader_version}.done"));
        if !patch_marker.exists() {
            match run_official_installer(std::path::Path::new(&data_dir), &dest).await {
                Ok(()) => {
                    let _ = std::fs::write(&patch_marker, b"1");
                    crate::util::log_line(
                        "[loader] 官方 installer 执行完成（processors 产物已就绪）",
                    );
                }
                Err(e) => crate::util::log_line(&format!(
                    "[loader] 官方 installer 失败（{e}）—— 模组本体仍会缺失，先不写标记以便下次重试"
                )),
            }
        } else {
            crate::util::log_line("[loader] processors 产物标记已存在，跳过 installer");
        }
    }

    // ── 下载加载器 profile 里声明的依赖库 ────────────────────────────
    //
    // ⚠️ 光有 profile 不够：它声明的 asm / sponge-mixin / intermediary 等
    // 库如果没下载，启动时 classpath 里缺类，必然 NoClassDefFoundError。
    // 这里复用原版那套 download_libraries（含并发与镜像回退）。
    if let Some(profile) =
        crate::loader_install::find_loader_profile(&versions_dir, loader, mc_version)
    {
        crate::util::log_line("[loader] profile 就绪，开始解析依赖");
        let libs = crate::loader_install::profile_libraries(&profile);
        println!("[install] profile={} 依赖 {} 个", profile.display(), libs.len());
        if !libs.is_empty() {
            crate::util::log_line(&format!("[loader] 开始下载 {} 个依赖库", libs.len()));
            // ⚠️ 2026-10-19 真机修：这段原来没有超时保护。
            // 弱网下其中一个库（通常是带 `+` 号的 sponge-mixin）卡住不返回，
            // 整个「补全」就一直停在「正在安装加载器…」，用户只能取消后重来。
            // 现在给一个总超时，超时就跳过（已下到的库保留，下次补全会续上），
            // 至少让补全流程能走完、让用户看到结果。
            match tokio::time::timeout(
                std::time::Duration::from_secs(180),
                download::download_libraries(&libs, &data_dir),
            )
            .await
            {
                Ok(Ok(())) => {
                    crate::util::log_line("[loader] 依赖库下载完成");
                    println!("[install] 依赖库下载调用返回 OK");
                    // 校验：download_libraries 内部会跳过「已存在且校验通过」的文件，
                    // 但弱网下它可能报成功却有文件没落盘（重试耗尽后不再抛错）。
                    // 这里再点一遍，把真正还缺的文件列出来，用户点「补全」才有方向。
                    let (ok, left) =
                        crate::loader_install::profile_library_paths(&profile, &libs_dir);
                    if left > 0 {
                        // ⚠️ 定向重试缺失的那几个（真机实测：8 个里总有 1 个
                        // 首次下不下来，再跑一遍就成功）。并发下载时它可能
                        // 因为连接被复用/抢占而失败，单独重试往往能过。
                        crate::util::log_line(&format!(
                            "[loader] 仍有 {left} 个库没下完，定向重试…"
                        ));
                        let retry_libs: Vec<_> = libs
                            .iter()
                            .filter(|l| {
                                let Some(a) =
                                    l.downloads.as_ref().and_then(|d| d.artifact.as_ref())
                                else {
                                    return false;
                                };
                                !libs_dir.join(&a.path).exists()
                            })
                            .cloned()
                            .collect();
                        if !retry_libs.is_empty() {
                            match tokio::time::timeout(
                                std::time::Duration::from_secs(120),
                                download::download_libraries(&retry_libs, &data_dir),
                            )
                            .await
                            {
                                Ok(Ok(())) => {
                                    crate::util::log_line("[loader] 定向重试完成");
                                }
                                _ => {
                                    crate::util::log_line("[loader] 定向重试未全部成功");
                                }
                            }
                        }
                        let (ok2, left2) =
                            crate::loader_install::profile_library_paths(&profile, &libs_dir);
                        crate::util::log_line(&format!(
                            "[loader] 最终：已就绪 {} / 缺 {left2}",
                            ok2.len()
                        ));
                        if left2 > 0 {
                            // ⚠️ 2026-10-23（真机反馈「下载失败为什么没有提示」）：
                            // 这里以前**只写日志**、然后照样返回 Ok —— 于是「缺 1 个依赖库」
                            // 在界面上完全没有提示，用户只看到补全转完圈、文件依旧不完整。
                            // 现在把**缺的文件名**抛回去，前端会显示「补全文件失败：…」。
                            let missing: Vec<String> = libs
                                .iter()
                                .filter_map(|l| {
                                    l.downloads
                                        .as_ref()
                                        .and_then(|d| d.artifact.as_ref())
                                        .map(|a| (a.path.clone(), libs_dir.join(&a.path)))
                                })
                                .filter(|(_, p)| !p.exists())
                                .map(|(path, _)| path)
                                .collect();
                            return Err(anyhow::anyhow!(
                                "加载器依赖库还有 {left2} 个没下下来：{}。多半是网络/镜像问题，换节点后再点「补全游戏文件」。",
                                if missing.is_empty() {
                                    "（文件名未知）".to_string()
                                } else {
                                    missing.join("、")
                                }
                            ));
                        }
                    } else {
                        crate::util::log_line(&format!(
                            "[loader] 加载器依赖齐备（{} 个）",
                            ok.len()
                        ));
                    }
                }
                Ok(Err(e)) => {
                    crate::util::log_line(&format!("[loader] 依赖库下载失败：{e}"));
                    return Err(e).context("下载加载器依赖库失败");
                }
                Err(_) => {
                    // 超时不当成致命错误：已下载的库仍是有效缓存，
                    // 下次补全会跳过它们、只重试缺的。
                    let (ok, left) =
                        crate::loader_install::profile_library_paths(&profile, &libs_dir);
                    crate::util::log_line(&format!(
                        "[loader] 依赖库下载超时（180s）：已就绪 {} / 缺 {left}，请再点一次「补全游戏文件」",
                        ok.len()
                    ));
                }
            }
        } else {
            crate::util::log_line("[loader] profile 里没有可下载的库（libraries 为空或字段不全）");
        }
    }
    Ok(())
}

/// 在数据目录的 `runtimes/` 下找一个可用的 JRE，返回 `(bin/java, lib)`。
///
/// 安卓上不能 `exec` 应用私有目录里的二进制，但**作为子进程用 `LD_LIBRARY_PATH`
/// 拉起 java 是允许的**（`run_installer` 就是这么做的，installer 也依赖这条路）。
fn find_jre(data_dir: &std::path::Path) -> Option<(std::path::PathBuf, std::path::PathBuf)> {
    let runtimes = data_dir.join("runtimes");
    let mut homes: Vec<std::path::PathBuf> = std::fs::read_dir(&runtimes)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    // 优先较高的 JRE 名（JRE-21 > JRE-17 > JRE-8），NeoForge 需要 21
    homes.sort();
    homes.reverse();
    for home in homes {
        let bin = home.join("bin").join("java");
        if bin.is_file() {
            return Some((bin, home.join("lib")));
        }
    }
    None
}

/// 跑官方 Forge/NeoForge installer 的 `--installClient`，让它的 processors 链
/// 产出打补丁的客户端与 `client-extra.jar`（FML 的 `minecraft`/`neoforge` 模组靠它）。
///
/// ## 为什么必须做（真机事故 2026-10-23）
/// 只解包 `version.json` 不跑 processors → `libraries/` 里没有补丁产物
/// → FML 里 `minecraft`/`neoforge` 报 `[MISSING]` → ModSorter 判依赖失败
/// → `NoSuchElementException` → 闪退。
async fn run_official_installer(
    data_dir: &std::path::Path,
    installer_jar: &std::path::Path,
) -> Result<()> {
    let (java, _jre_lib) =
        find_jre(data_dir).ok_or_else(|| anyhow::anyhow!("没有可用的 Java 运行时（runtimes/）"))?;
    let jre_home = java
        .parent()
        .and_then(|p| p.parent())
        .ok_or_else(|| anyhow::anyhow!("JRE 路径异常：{}", java.display()))?;

    // Forge/NeoForge installer 要求目标目录里有 launcher_profiles.json，
    // 否则直接报 "There is no launcher_profiles.json in the target directory"。
    let lp = data_dir.join("launcher_profiles.json");
    if !lp.exists() {
        let _ = std::fs::write(&lp, r#"{"profiles":{},"settings":{},"version":3}"#);
    }

    let main_class = read_jar_main_class(installer_jar)
        .unwrap_or_else(|_| "net.neoforged.installer.Main".to_string());
    let result_file = data_dir.join("logs").join("jvm-install-result.txt");
    let _ = std::fs::remove_file(&result_file);

    // ⚠️ 预下载 installer 自己要用的库（2026-10-23 真机事故）：
    // installer 会**直连** maven.neoforged.net 下载 installertools / jarsplitter 等，
    // 真机上直接 `SocketTimeoutException: Connect timed out`（那条线路不通 ✗），
    // 然后报 `There was an error during installation`。
    // 我们的下载器有镜像回退 ✓，所以先用它把这些库填好 —— installer 发现有就跳过 ✓
    //（它日志里会打 "… exists. Checksum valid."）。
    if let Err(e) = prefill_installer_libs(data_dir, installer_jar).await {
        crate::util::log_line(&format!("[loader] 预下载 installer 依赖失败（继续尝试）：{e}"));
    }

    let payload = serde_json::json!({
        "jre": jre_home.to_string_lossy(),
        "classpath": installer_jar.to_string_lossy(),
        "mainClass": main_class,
        // AWT headless 必须显式给：installer 那个主类会去初始化 Swing，
        // 安卓上没有 X11/headless 会让 JVM 直接崩掉（ZL2 跑 processors 时也带了这个思路）。
        "jvmArgs": [
            "-Djava.awt.headless=true",
            format!("-Duser.home={}", data_dir.display()),
            format!("-Djava.io.tmpdir={}", data_dir.join("cache").display()),
        ],
        "gameArgs": ["--installClient", data_dir.to_string_lossy()],
        "workDir": data_dir.to_string_lossy(),
        "port": 0,
    });
    crate::util::log_line(&format!(
        "[loader] 交 :jvm 独立进程跑 installer：mainClass={main_class} jar={}",
        installer_jar.display()
    ));
    crate::android_bridge::start_jvm_install_service(&payload.to_string())
        .ok_or_else(|| anyhow::anyhow!("无法调用 JVM 安装服务（原生桥未就绪）"))?;

    // ⚠️ 不能等「退出码」：`JLI_Launch` 结束时是**直接 exit()**（不是 return），
    // 所以 :jvm 服务里"跑完写结果文件"那段**永远不会执行** ✗（真机就这么静默消失的）。
    // 改成按**产物**判断：installer 的 processors 会生成 client-extra.jar。
    let libs_dir = data_dir.join("libraries");
    let mut jvm_gone_rounds = 0u32;
    for _ in 0..600u32 {
        // 结果文件（如果哪天能写出来就用它，双保险）
        if let Ok(s) = std::fs::read_to_string(&result_file) {
            if let Ok(code) = s.trim().parse::<i32>() {
                return if code == 0 {
                    Ok(())
                } else {
                    Err(anyhow::anyhow!("installer 退出码 {code}"))
                };
            }
        }
        if has_client_extra(&libs_dir) {
            crate::util::log_line("[loader] installer 已产出 client-extra（processors 完成）");
            return Ok(());
        }
        // :jvm 进程已经没了、产物又没有 → 判定失败（别让 UI 干等 10 分钟）
        if !jvm_process_alive() {
            jvm_gone_rounds += 1;
            if jvm_gone_rounds >= 5 {
                return Err(anyhow::anyhow!(
                    "installer 进程已退出，但没有产出 client-extra（依赖补丁失败）"
                ));
            }
        }
        tokio::time::sleep(std::time::Duration::from_secs(1)).await;
    }
    Err(anyhow::anyhow!("installer 超过 10 分钟仍未结束"))
}

/// 把 installer `install_profile.json` 里声明的库，用**我们自己的下载器**（带镜像回退）
/// 提前下好，避免 installer 直连 maven 超时（真机 c12 那条线路不通 ✗）。
async fn prefill_installer_libs(
    data_dir: &std::path::Path,
    installer_jar: &std::path::Path,
) -> Result<()> {
    let text = read_zip_entry_string(installer_jar, "install_profile.json")
        .map_err(|e| anyhow::anyhow!("读 install_profile.json 失败：{e}"))?;
    let v: serde_json::Value =
        serde_json::from_str(&text).map_err(|e| anyhow::anyhow!("解析 install_profile 失败：{e}"))?;
    // ⚠️ 别用 `profile_libraries()`：它接受的是**目录**（内部拼 `<名>/<名>.json`），
    // 传文件进去会直接返回空 —— 2026-10-23 就是这么白跑的（日志里一条都不打）。
    // 这里直接按 `crate::version::Library` 反序列化。
    let libs: Vec<crate::version::Library> = v
        .get("libraries")
        .and_then(|l| l.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|l| serde_json::from_value::<crate::version::Library>(l.clone()).ok())
                .collect()
        })
        .unwrap_or_default();
    if libs.is_empty() {
        return Ok(());
    }
    crate::util::log_line(&format!(
        "[loader] 预下载 installer 依赖 {} 个（避开它的直连超时）",
        libs.len()
    ));
    match tokio::time::timeout(
        std::time::Duration::from_secs(300),
        crate::download::download_libraries(&libs, &data_dir.to_string_lossy()),
    )
    .await
    {
        Ok(Ok(())) => crate::util::log_line("[loader] installer 依赖预下载完成"),
        Ok(Err(e)) => crate::util::log_line(&format!("[loader] installer 依赖预下载部分失败：{e}")),
        Err(_) => crate::util::log_line("[loader] installer 依赖预下载超时（继续）"),
    }
    Ok(())
}

/// `libraries/` 下有没有 `client-extra*.jar`（NeoForm processors 的产物之一）。
fn has_client_extra(libs: &std::path::Path) -> bool {
    fn walk(dir: &std::path::Path, depth: u32) -> bool {
        if depth == 0 {
            return false;
        }
        let Ok(rd) = std::fs::read_dir(dir) else {
            return false;
        };
        for e in rd.flatten() {
            let p = e.path();
            if p.is_dir() {
                if walk(&p, depth - 1) {
                    return true;
                }
            } else if let Some(n) = p.file_name().and_then(|n| n.to_str()) {
                if n.contains("client-extra") && n.ends_with(".jar") {
                    return true;
                }
            }
        }
        false
    }
    walk(libs, 7)
}

/// `:jvm` 进程是否还活着（读 /proc，避免引入 libc 之外的依赖）。
fn jvm_process_alive() -> bool {
    let Ok(rd) = std::fs::read_dir("/proc") else {
        return true; // 读不到就当作还活着，交给超时兜底
    };
    for e in rd.flatten() {
        let name = e.file_name();
        let Some(name) = name.to_str() else { continue };
        if !name.chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let cmdline = std::fs::read_to_string(e.path().join("cmdline")).unwrap_or_default();
        if cmdline.contains("qookix") && cmdline.contains(":jvm") {
            return true;
        }
    }
    false
}

/// 读 jar 的 MANIFEST 里的 `Main-Class`（installer 的主类靠它）。
fn read_jar_main_class(jar: &std::path::Path) -> Result<String> {
    let text = read_zip_entry_string(jar, "META-INF/MANIFEST.MF")
        .map_err(|e| anyhow::anyhow!("读 {} 的 MANIFEST 失败：{e}", jar.display()))?;
    for line in text.lines() {
        if let Some(v) = line.strip_prefix("Main-Class:") {
            let v = v.trim();
            if !v.is_empty() {
                return Ok(v.to_string());
            }
        }
    }
    Err(anyhow::anyhow!(
        "MANIFEST 缺少 Main-Class：{}",
        jar.display()
    ))
}

/// 跑 Forge / NeoForge 的 installer jar（headless），生成 profile。
/// Forge / NeoForge：解压 installer jar 里的 `install_profile.json`，生成加载器 profile。
///
/// ## 为什么不用「跑 installer jar」
///
/// 桌面端（`QookiX-Launcher/src-tauri/src/install.rs::forge_patch`）的做法就是
/// **解压 install_profile.json** 而不是执行 installer：
///   - 跑 installer 需要一个可用的 `java` 进程，安卓上还要处理
///     `LD_LIBRARY_PATH` / SELinux 等一堆坑
///   - Forge 官方 installer 本质是「解压 + 按 processors 任务链操作」，
///     而 processors 链需要的工具库在安卓上很难凑齐
///   - 新版安装器（spec 1，Forge ≥1.17 / NeoForge）的 version.json 用的是
///     `inheritsFrom` 继承 vanilla，只需合并 mainClass / arguments / libraries
///
/// 本实现参照桌面端，覆盖三种安装器格式：
///   - **spec 1**（Forge ≥1.17 / NeoForge）：`install_profile.json` 无 versionInfo，
///     版本 json 在 zip 内独立的 `version.json`（由 `json` 字段给出路径）
///   - **旧版**（≤1.16）：`install_profile.json` 顶层带 `versionInfo`（完整版本 json）
///   - **spec 0**（1.12.2 等最老式）：既无 versionInfo 也无 processors，
///     forge 本体与依赖直接内嵌在 installer 的 `maven/` 目录里 → 解压到 libraries
async fn install_forge_like(
    loader: &str,
    mc_version: &str,
    loader_version: &str,
    installer_jar: &std::path::Path,
    versions_dir: &std::path::Path,
) -> Result<()> {
    // ── 1. 解出 install_profile.json ──────────────────────────────────
    let profile_text = read_zip_entry_string(installer_jar, "install_profile.json")
        .map_err(|e| anyhow!("读取 install_profile.json 失败：{e}"))?;
    // 官方 profile 里有 ${version} / ${mcVersion} 等占位符，必须先替换
    let full_ver = forge_full_ver(loader, mc_version, loader_version);
    let replaced = profile_text
        .replace("${version}", &full_ver)
        .replace("${mcVersion}", mc_version)
        .replace("${forgeVersion}", loader_version)
        .replace("${neoForgeVersion}", loader_version);
    let profile: serde_json::Value =
        serde_json::from_str(&replaced).context("解析 install_profile.json 失败")?;

    let is_legacy_spec0 = profile.get("spec").and_then(|v| v.as_i64()) == Some(0);

    // ── 2. 取出加载器的版本 json ─────────────────────────────────────
    // 新版：在 zip 内的 version.json（路径由 `json` 字段给出）
    // 旧版：install_profile.json 顶层的 versionInfo
    let version_info = if let Some(v) = profile.get("versionInfo") {
        v.clone()
    } else {
        let entry = profile
            .get("json")
            .and_then(|v| v.as_str())
            .unwrap_or("/version.json")
            .trim_start_matches('/');
        let text = read_zip_entry_string(installer_jar, entry)
            .map_err(|e| anyhow!("读取版本 json（{entry}）失败：{e}"))?;
        serde_json::from_str::<serde_json::Value>(&text).context("解析版本 json 失败")?
    };

    let loader_json_id = version_info
        .get("id")
        .and_then(|v| v.as_str())
        .unwrap_or(&full_ver)
        .to_string();

    // ── 3. 生成 profile（继承 vanilla）──────────────────────────────
    //
    // spec 1 的 version.json 是「继承 vanilla 的增量」：只有 mainClass /
    // arguments 追加项 / libraries。直接用它当版本 json 会丢掉 vanilla 的
    // arguments.jvm（含 -cp ${classpath}），Java 启动时没有 classpath。
    // 所以这里显式写 `inheritsFrom`，让 `get_version_info` 递归合并。
    let mut patched = serde_json::json!({
        "id": loader_json_id,
        "inheritsFrom": mc_version,
        "mainClass": version_info.get("mainClass"),
        "arguments": version_info.get("arguments"),
        "libraries": version_info.get("libraries"),
    });
    // ⚠️ 2026-10-23：生成 profile 时就把 `earlydisplay` 摘掉。
    //
    // 它是 NeoForge 开局那个 Swing/GL 加载窗口。安卓上它会在
    // `DisplayWindow.updateModuleReads` 里 `Class.forName` 去找游戏层的
    // `net.minecraft.client.gui.screens.LoadingOverlay` → 类加载器看不到 →
    // NoClassDefFoundError → main 线程终止 → 闪退
    //（真机 `files/logs/game-stderr.log` 抓到）。
    // 它由 BootstrapLauncher 按 libraries 加载，所以只能从 profile 的 libraries 删；
    // `-Dfml.earlyprogresswindow=false` 传了也没用（NeoForge 21.1 不认）。
    if let Some(libs) = patched
        .get_mut("libraries")
        .and_then(|l| l.as_array_mut())
    {
        let before = libs.len();
        libs.retain(|l| {
            let n = l.get("name").and_then(|n| n.as_str()).unwrap_or("");
            !n.contains("fancymodloader:earlydisplay") && !n.contains(":earlydisplay:")
        });
        if libs.len() != before {
            crate::util::log_line(&format!(
                "[loader] profile 生成时移除 {} 条 earlydisplay（安卓会导致启动崩溃）",
                before - libs.len()
            ));
        }
    }
    // 去掉 null 字段（versionInfo 里可能没有）
    if let Some(obj) = patched.as_object_mut() {
        obj.retain(|_, v| !v.is_null());
    }

    // installer 的 libraries 里，`:universal` 是 forge 本体，必须进运行时
    // classpath；其余（jopt-simple / lzma-java 等）是 processors 工具链，不能进。
    if let Some(installer_libs) = profile.get("libraries").and_then(|v| v.as_array()) {
        let mut libs = patched
            .get("libraries")
            .and_then(|v| v.as_array())
            .cloned()
            .unwrap_or_default();
        for lib in installer_libs {
            let name = lib.get("name").and_then(|n| n.as_str()).unwrap_or("");
            if name.ends_with(":universal") || name.ends_with(":client") {
                libs.push(lib.clone());
            }
        }
        if let Some(obj) = patched.as_object_mut() {
            obj.insert("libraries".to_string(), serde_json::Value::Array(libs));
        }
    }

    // spec 0：forge 本体内嵌在 installer 的 maven/ 目录，解压到 libraries
    if is_legacy_spec0 {
        let libs_root = versions_dir.parent()
            .ok_or_else(|| anyhow!("versions 目录没有父目录"))?
            .join("libraries");
        std::fs::create_dir_all(&libs_root).ok();
        if let Err(e) = extract_zip_strip(installer_jar, &libs_root, "maven/") {
            println!("[loader] 解压内嵌 maven 依赖失败：{e}");
        }
    }

    let dir = versions_dir.join(&loader_json_id);
    tokio::fs::create_dir_all(&dir).await?;
    let json_path = dir.join(format!("{loader_json_id}.json"));
    tokio::fs::write(&json_path, serde_json::to_string_pretty(&patched)?).await?;
    println!("[loader] 已写入 {loader} profile：{}", json_path.display());
    Ok(())
}

/// Forge / NeoForge 的完整版本串（installer 文件名与 maven 路径都用它）。
///
/// - Forge：`{mc_version}-{version}`（如 `1.12.2-14.23.5.2860`）；
///   传入的 version 已含前缀时不再重复拼
/// - NeoForge：maven 上 neoforge 的版本号形如 `21.8.47`，
///   loader_version 常是纯 build 号 `47` → 需要补 mc 前缀
/// Forge/NeoForge 的完整版本串。实现在 `loader_install.rs`（那里有单元测试）。
fn forge_full_ver(loader: &str, mc_version: &str, version: &str) -> String {
    crate::loader_install::forge_full_ver(loader, mc_version, version)
}
/// 读 zip 里的一个文本条目。
fn read_zip_entry_string(zip_path: &std::path::Path, entry: &str) -> Result<String> {
    let file = std::fs::File::open(zip_path)
        .map_err(|e| anyhow!("打开 {} 失败：{}", zip_path.display(), e))?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| anyhow!("{} 不是有效的 zip：{}", zip_path.display(), e))?;
    let mut e = archive
        .by_name(entry)
        .map_err(|_| anyhow!("zip 里没有条目 {entry}"))?;
    let mut buf = Vec::new();
    std::io::Read::read_to_end(&mut e, &mut buf)
        .map_err(|e| anyhow!("读取 {entry} 失败：{e}"))?;
    String::from_utf8(buf).map_err(|e| anyhow!("{entry} 不是 UTF-8 文本：{e}"))
}

/// 解压 zip 里的指定前缀目录到目标根（剥掉前缀本身）。
///
/// 桌面端用 `extract_zip_strip`，这里做一个最小实现（只服务 spec 0 的 maven/）。
fn extract_zip_strip(
    zip_path: &std::path::Path,
    dest_root: &std::path::Path,
    prefix: &str,
) -> Result<()> {
    let file = std::fs::File::open(zip_path)
        .map_err(|e| anyhow!("打开 {} 失败：{}", zip_path.display(), e))?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| anyhow!("{} 不是有效的 zip：{}", zip_path.display(), e))?;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| anyhow!("读 zip 条目失败：{e}"))?;
        let name = entry.name().to_string();
        if !name.starts_with(prefix) || entry.is_dir() {
            continue;
        }
        let rel = name.trim_start_matches(prefix);
        let dest = dest_root.join(rel);
        if let Some(p) = dest.parent() {
            std::fs::create_dir_all(p).ok();
        }
        let mut out = std::fs::File::create(&dest)
            .map_err(|e| anyhow!("创建 {} 失败：{}", dest.display(), e))?;
        std::io::copy(&mut entry, &mut out)
            .map_err(|e| anyhow!("写 {} 失败：{}", dest.display(), e))?;
    }
    Ok(())
}

/// 为 Fabric / Quilt 生成带 `inheritsFrom` 的 profile。
///
/// 官方 installer 会做更多事（下载 mappings/intermediary），但那些库
/// 在启动时可以按 profile 的 libraries 字段补齐。这里先保证
/// 「有一个能被 get_version_info 读到的 profile」，让加载器能进 classpath。
/// 为 Fabric / Quilt 拉取官方 profile 并落盘。
///
/// ⚠️ 2026-10-19 修正：最初想自己拼 profile，但那会漏掉一堆必需依赖
/// （asm / sponge-mixin / intermediary …），启动时必然 `NoClassDefFoundError`。
/// Fabric 官方提供了现成的 profile 接口，直接拉下来最省事也最准确：
///
///   Fabric: https://meta.fabricmc.net/v2/versions/loader/{mc}/{loader}/profile/json
///   Quilt:  https://meta.quiltmc.org/v3/versions/loader/{mc}/{loader}/profile/json
///
/// 返回的内容已经是完整的 MinecraftVersion 结构（含 inheritsFrom / mainClass /
/// libraries / arguments），落盘到 `versions/<id>/<id>.json` 即可被
/// `get_version_info` 直接读到并递归合并原版。
async fn write_simple_loader_profile(
    loader: &str,
    mc_version: &str,
    loader_version: &str,
    _loader_jar: &std::path::Path,
    versions_dir: &std::path::Path,
) -> Result<()> {
    let url = match loader {
        "fabric" => format!(
            "https://meta.fabricmc.net/v2/versions/loader/{mc_version}/{loader_version}/profile/json"
        ),
        "quilt" => format!(
            "https://meta.quiltmc.org/v3/versions/loader/{mc_version}/{loader_version}/profile/json"
        ),
        other => return Err(anyhow!("不支持的加载器：{other}")),
    };

    // ⚠️ 2026-10-19 真机修：这里原来 `reqwest::Client::builder()` 新建了一个
    // **裸客户端** —— 不走应用统一的代理 / 镜像 / UA 配置。
    //
    // 后果：用户在「设置 → 下载代理」里配了代理、或网络只有走代理才通时，
    // 其它下载都正常，唯独加载器 profile 永远拉不下来，报「请求 fabric profile 失败」。
    // 真机实测（OnePlus 8 / Android 16）就是这个症状。
    //
    // `util::http_client()` 会读 settings.json 的 proxy_mode / proxy 并套用，
    // 是全局唯一入口；所有新代码都应该用它。
    let client = crate::util::http_client().await;

    // ⚠️ 2026-10-20 真机修：**官方 + 内置镜像并行**，别再单等官方。
    //
    // 真机实测（OnePlus 8 / 26.3）：
    //   官方 meta.fabricmc.net/.../profile/json → Connect 超时（经常拉不到）
    //   镜像 bmclapi2.bangbang93.com/fabric-meta/... → 0.7 秒
    // 只请求官方时，删除坏 profile 后**重新拉取直接失败**，等于修不好。
    let mut candidates: Vec<String> = Vec::new();
    let mirrored = crate::mirror::map(crate::mirror::BMCLAPI_BASE, &url);
    if !mirrored.is_empty() && mirrored != url {
        candidates.push(mirrored);
    }
    candidates.push(url.clone());

    let per_try = std::time::Duration::from_secs(30);
    let mut tasks = Vec::new();
    for u in candidates {
        let client = client.clone();
        // 同 `fetch_list_mirrored`：镜像对自定义 UA 返回 302 空响应，改用中性 UA。
        let is_mirror = u.contains("bangbang93.com");
        tasks.push(Box::pin(async move {
            let mut req = client.get(&u);
            if is_mirror {
                req = req.header(reqwest::header::USER_AGENT, "Mozilla/5.0");
            }
            let r = tokio::time::timeout(per_try, req.send()).await;
            match r {
                Ok(Ok(resp)) if resp.status().is_success() => {
                    match tokio::time::timeout(per_try, resp.text()).await {
                        Ok(Ok(t)) => (u, Ok(t)),
                        Ok(Err(e)) => (u.clone(), Err(format!("{u} 读取失败：{e}"))),
                        Err(_) => (u.clone(), Err(format!("{u} 读取超时"))),
                    }
                }
                Ok(Ok(resp)) => (u.clone(), Err(format!("{u} 返回 {}", resp.status()))),
                Ok(Err(e)) => (u.clone(), Err(format!("{u} 请求失败：{e}"))),
                Err(_) => (u.clone(), Err(format!("{u} 连接超时"))),
            }
        }));
    }
    // 同 `fetch_list_mirrored`：谁先成功用谁，别让镜像的慢拖住官方的快。
    let mut text: Option<String> = None;
    let mut last_err = String::new();
    let mut pending = futures::future::select_all(tasks);
    loop {
        let (r, _idx, rest) = pending.await;
        match r {
            (u, Ok(t)) => {
                crate::util::log_line(&format!("[loader] 拉取 profile 成功: {u}"));
                println!("[install] 拉取 profile 成功: {u}");
                text = Some(t);
                break;
            }
            (_, Err(e)) => last_err = e,
        }
        if rest.is_empty() {
            break;
        }
        pending = futures::future::select_all(rest);
    }
    let text = match text {
        Some(t) => t,
        None => {
            return Err(anyhow!(
                "请求 {loader} profile 失败（官方与镜像都没拉到）：{last_err}"
            ))
        }
    };
    let profile: serde_json::Value =
        serde_json::from_str(&text).context("解析 profile JSON 失败")?;

    // 用接口返回的 id（它已经按官方规则命名，比自己拼更可靠）
    let id = profile
        .get("id")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .unwrap_or_else(|| crate::loader_install::loader_version_id(loader, mc_version, loader_version));

    // Fabric/Quilt 的 profile 里，库只有 `name` + `url`，**没有** `downloads.artifact`。
    // 而我们的 `download_libraries` 只认 `downloads.artifact.path` —— 不补这一层，
    // 依赖永远不会被下载，启动时 classpath 里缺 asm/mixin 等必然 NoClassDefFoundError。
    // 这里按 Maven 坐标 `group:artifact:version` 推导路径（标准布局）。
    let mut profile = profile;
    if let Some(libs) = profile.get_mut("libraries").and_then(|l| l.as_array_mut()) {
        for lib in libs.iter_mut() {
            let name = lib.get("name").and_then(|n| n.as_str()).unwrap_or("");
            if name.is_empty() { continue; }
            let has_artifact = lib
                .get("downloads")
                .and_then(|d| d.get("artifact"))
                .is_some();
            if has_artifact { continue; }
            // group:artifact:version[:classifier]
            let parts: Vec<&str> = name.split(':').collect();
            if parts.len() < 3 { continue; }
            let (group, artifact, version) = (parts[0], parts[1], parts[2]);
            // 标准 Maven 布局：group(点→斜杠)/artifact/version/artifact-version[-classifier].jar
            // 与桌面端 models::maven_to_path 的规则一致（那里用 PathBuf，这里用字符串）。
            let classifier = if parts.len() > 3 { parts[3] } else { "" };
            let rel = if classifier.is_empty() {
                format!("{}/{}/{}/{}-{}.jar", group.replace('.', "/"), artifact, version, artifact, version)
            } else {
                format!("{}/{}/{}/{}-{}-{}.jar", group.replace('.', "/"), artifact, version, artifact, version, classifier)
            };
            let base = lib
                .get("url")
                .and_then(|u| u.as_str())
                .unwrap_or("https://maven.fabricmc.net/")
                .trim_end_matches('/')
                .to_string();
            if let Some(obj) = lib.as_object_mut() {
                obj.insert(
                    "downloads".to_string(),
                    serde_json::json!({
                        "artifact": {
                            "path": rel,
                            "url": format!("{base}/{rel}"),
                            // ⚠️ 三个字段都是**必填**（见 version.rs 的 LibraryArtifact：
                            // path / sha1 / size / url 都不是 Option，也没有 serde default）。
                            // 少了 `sha1` 会让 `serde_json::from_value::<Library>` **整体失败**，
                            // 而 profile_libraries 用 `if let Ok(..)` 吞掉了错误 ——
                            // 表现就是「依赖一个都没下载」，启动时 ClassNotFoundException。
                            // 官方 Meta profile 本来就不给 sha1，这里留空串表示「不校验」。
                            "sha1": "",
                            "size": 0,
                        }
                    }),
                );
            }
        }
    }

    let dir = versions_dir.join(&id);
    tokio::fs::create_dir_all(&dir).await?;
    let json_path = dir.join(format!("{id}.json"));
    tokio::fs::write(&json_path, serde_json::to_string_pretty(&profile)?).await?;
    println!("[loader] profile 已写入: {}", json_path.display());
    Ok(())
}

// ==================== Estimate Download ====================

pub async fn estimate_download(mc_version: &str) -> Result<serde_json::Value> {
    let version_info = crate::version::get_version_info(mc_version).await?;
    let mut total_size: i64 = 0;
    let mut file_count: i64 = 0;

    // Client JAR
    if let Some(ref downloads) = version_info.downloads {
        if let Some(ref client) = downloads.client {
            total_size += client.size as i64;
            file_count += 1;
        }
    }

    // Libraries
    if let Some(ref libraries) = version_info.libraries {
        for lib in libraries {
            if let Some(ref downloads) = lib.downloads {
                if let Some(ref artifact) = downloads.artifact {
                    total_size += artifact.size as i64;
                    file_count += 1;
                }
            }
        }
    }

    // Asset index
    if let Some(ref asset_index) = version_info.asset_index {
        total_size += asset_index.total_size as i64;
        file_count += 1;
    }

    Ok(json!({
        "download_files": file_count,
        "download_bytes": total_size,
        "assets_known": true,
    }))
}

// ==================== Estimate Import ====================

pub async fn estimate_import(_source: &str, _raw_ids: &[String]) -> Result<serde_json::Value> {
    Ok(json!({
        "import_files": 0,
        "import_bytes": 0,
    }))
}

// ==================== Install Content ====================

/// Loader 枚举 → 字符串（用于内容版本过滤）。
pub fn loader_str(loader: &crate::models::Loader) -> &'static str {
    match loader {
        crate::models::Loader::Vanilla => "vanilla",
        crate::models::Loader::Fabric => "fabric",
        crate::models::Loader::Quilt => "quilt",
        crate::models::Loader::Forge => "forge",
        crate::models::Loader::NeoForge => "neoforge",
    }
}

/// 把「provider + version_id」解析成真实下载直链与文件名。
///
/// `install_content`（装单个内容）与「导出为 .mrpack」都要这一步 —— 后者靠它把本地已装的
/// mod 还原成 `files[].downloads`，于是导出的整合包**只存链接、不存文件**（能小一个数量级）。
pub async fn resolve_content_file(provider: &str, version_id: &str) -> Result<(String, String)> {
    let client = crate::util::http_client().await;
    if provider == "modrinth" {
        let response = client
            .get(format!("{}/version/{}", MODRINTH_API, version_id))
            .send()
            .await
            .context("Failed to get version")?;
        let version: Value = response.json().await.context("Failed to parse version")?;
        if let Some(files) = version["files"].as_array() {
            if let Some(primary) = files
                .iter()
                .find(|f| f["primary"].as_bool().unwrap_or(false))
                .or_else(|| files.first())
            {
                return Ok((
                    primary["url"].as_str().unwrap_or("").to_string(),
                    primary["filename"].as_str().unwrap_or("").to_string(),
                ));
            }
        }
        return Ok((String::new(), String::new()));
    }
    if provider == "curseforge" {
        let response = client
            .get(format!("https://api.curseforge.com/v1/mods/files/{}", version_id))
            .header("x-api-key", curseforge_key().await)
            .send()
            .await
            .context("Failed to get CurseForge file")?;
        let result: Value = response
            .json()
            .await
            .context("Failed to parse CurseForge file")?;
        let data = &result["data"];
        return Ok((
            data["downloadUrl"].as_str().unwrap_or("").to_string(),
            data["fileName"].as_str().unwrap_or("").to_string(),
        ));
    }
    Ok((String::new(), String::new()))
}

pub async fn install_content(
    instance_id: &str,
    provider: &str,
    project_id: &str,
    version_id: &str,
    kind: &str,
) -> Result<Value> {
    let instance_path = settings::instance_dir(instance_id).await?;
    let folder = crate::instances::kind_folder(kind);
    let content_dir = instance_path.join(folder);
    tokio::fs::create_dir_all(&content_dir).await.ok();

    let (file_url, file_name) = resolve_content_file(provider, version_id).await?;
    // 「项目名」只用于展示（内容列表里那行标题）；CurseForge 那条路原本就不查它
    let project_title = if provider == "modrinth" {
        project_info("modrinth", project_id)
            .await
            .map(|i| i.title)
            .unwrap_or_default()
    } else {
        String::new()
    };

    if file_url.is_empty() || file_name.is_empty() {
        return Err(anyhow::anyhow!("Could not find file to download"));
    }
    if !crate::util::is_safe_filename(&file_name) {
        return Err(anyhow::anyhow!("非法的文件名: {file_name}"));
    }

    // 上报到「下载中心」：内容下载同样是长任务，用户需要看到进度
    let instance_name = crate::instances::get_instance(instance_id)
        .await
        .map(|i| i.name)
        .unwrap_or_else(|_| instance_id.to_string());
    let provider_label = if provider == "curseforge" { "CurseForge" } else { "Modrinth" };
    let ctx = crate::progress::TaskCtx::new(
        instance_id,
        &instance_name,
        &format!(
            "{provider_label}：{}",
            if project_title.is_empty() { file_name.as_str() } else { project_title.as_str() }
        ),
    );
    crate::progress::emit_install(&ctx, "content", &format!("正在下载 {file_name}…"), 0, 1);

    let dest = content_dir.join(&file_name);
    let download_result = download::download_file(&file_url, &dest.to_string_lossy(), None).await;
    match &download_result {
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
            crate::progress::emit_install_done(&ctx, true, "安装完成", 1, 1);
        }
        Err(e) => {
            crate::progress::emit_install_done(&ctx, false, &format!("安装失败：{e}"), 0, 1);
        }
    }
    download_result?;

    // 登记内容记录
    let size = std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0);
    let mut rec = crate::models::InstalledContent::manual(file_name.clone());
    rec.source = provider.to_string();
    rec.project_id = Some(project_id.to_string());
    rec.version_id = Some(version_id.to_string());
    rec.name = Some(if project_title.is_empty() { file_name.clone() } else { project_title });
    rec.size = size as i64;
    crate::util::fill_content_from_jar(&mut rec, &dest);
    let _ = crate::instances::add_content(instance_id, kind, rec).await;

    Ok(json!({
        "ok": true,
        "filename": file_name,
    }))
}

// ==================== List Content ====================

pub async fn list_content(instance_id: &str, kind: &str) -> Result<serde_json::Value> {
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    let instance_path = settings::instance_dir(instance_id).await?;
    let content_dir = instance_path.join(crate::instances::kind_folder(kind));

    let mut records = crate::instances::list_content_records(instance_id, kind).await;
    let mut on_disk: Vec<String> = Vec::new();
    if content_dir.exists() {
        if let Ok(entries) = std::fs::read_dir(&content_dir) {
            for entry in entries.flatten() {
                if let Some(fname) = entry.file_name().to_str() {
                    if entry.path().is_file() {
                        on_disk.push(fname.to_string());
                    }
                }
            }
        }
    }

    // auto-register files that exist on disk but have no record
    let ext = if kind == "mod" { ".jar" } else { ".zip" };
    let mut new_records: Vec<crate::models::InstalledContent> = Vec::new();
    for fname in &on_disk {
        if !fname.ends_with(ext) {
            continue;
        }
        if records.iter().any(|r: &crate::models::InstalledContent| r.filename == *fname) {
            continue;
        }
        let size = std::fs::metadata(content_dir.join(fname)).map(|m| m.len()).unwrap_or(0);
        let mut rec = crate::models::InstalledContent::manual(fname.clone());
        rec.size = size as i64;
        crate::util::fill_content_from_jar(&mut rec, &content_dir.join(fname));
        new_records.push(rec);
    }
    if !new_records.is_empty() {
        let _ = crate::instances::add_content_batch(instance_id, kind, new_records.clone()).await;
        records.extend(new_records);
    }

    // refresh metadata for existing records whose files are present
    let mut updated = false;
    for rec in &mut records {
        let abs = content_dir.join(&rec.filename);
        if abs.is_file() {
            let before = rec.clone();
            crate::util::fill_content_from_jar(rec, &abs);
            if rec.name != before.name || rec.description != before.description || rec.icon != before.icon {
                updated = true;
            }
        }
    }
    if updated {
        let _ = crate::instances::add_content_batch(instance_id, kind, records.clone()).await;
    }

    let items: Vec<Value> = records
        .iter()
        .map(|r| {
            let exists = content_dir.join(&r.filename).is_file()
                || content_dir.join(format!("{}.disabled", r.filename)).is_file();
            let mut rec_val = serde_json::to_value(r).unwrap_or(Value::Null);
            if let Some(obj) = rec_val.as_object_mut() {
                obj.insert("cn_name".to_string(), Value::Null);
            }
            json!({ "record": rec_val, "exists": exists })
        })
        .collect();
    Ok(json!({ "items": items, "onDisk": on_disk }))
}

// ==================== Content Management ====================

/// 卸载内容：删除磁盘文件（含 .disabled 变体）并移除记录。
pub async fn uninstall_content(instance_id: &str, kind: &str, filename: &str) -> Result<(), String> {
    crate::fsutil::validate_id(instance_id, "实例")?;
    crate::fsutil::validate_name(filename)?;
    if !crate::util::is_safe_filename(filename) {
        return Err("非法文件名".into());
    }
    let dir = settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())?
        .join(crate::instances::kind_folder(kind));
    let _ = std::fs::remove_file(dir.join(filename));
    let _ = std::fs::remove_file(dir.join(format!("{filename}.disabled")));
    crate::instances::remove_content(instance_id, kind, filename).await
}

/// 启用/禁用内容（重命名 .disabled）。
pub async fn toggle_content_enabled(
    instance_id: &str,
    kind: &str,
    filename: &str,
    enabled: bool,
) -> Result<(), String> {
    crate::instances::set_content_enabled(instance_id, kind, filename, enabled).await
}

/// 导入本地文件到实例内容目录，并登记为手动内容。
pub async fn import_local_file(
    instance_id: &str,
    kind: &str,
    source_path: &str,
) -> Result<Value, String> {
    let source = std::path::Path::new(source_path);
    let filename = source
        .file_name()
        .ok_or_else(|| "无效的文件路径".to_string())?
        .to_string_lossy()
        .to_string();
    if !crate::util::is_safe_filename(&filename) {
        return Err("非法的文件名".into());
    }
    let dest = settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())?
        .join(crate::instances::kind_folder(kind))
        .join(&filename);
    // 复制 jar + 解出图标/元数据全是同步 IO（模组包几十 MB），挪出 tokio worker
    let source_owned = source.to_path_buf();
    let dest_owned = dest.clone();
    let kind_owned = kind.to_string();
    let record = tokio::task::spawn_blocking(move || -> Result<crate::models::InstalledContent, String> {
        std::fs::create_dir_all(dest_owned.parent().unwrap()).map_err(|e| e.to_string())?;
        std::fs::copy(&source_owned, &dest_owned).map_err(|e| format!("复制文件失败: {e}"))?;
        let size = std::fs::metadata(&dest_owned).map(|m| m.len()).unwrap_or(0);
        let mut record = crate::models::InstalledContent::manual(filename.clone());
        record.size = size as i64;
        if let Some(icon) = crate::util::extract_archive_icon(&dest_owned, &kind_owned) {
            record.icon = Some(icon);
        }
        crate::util::fill_content_from_jar(&mut record, &dest_owned);
        Ok(record)
    })
    .await
    .map_err(|e| format!("复制任务失败: {e}"))??;
    crate::instances::add_content(instance_id, kind, record).await?;
    Ok(json!({ "ok": true }))
}

/// 检查 Modrinth 内容更新。返回每个有新版的可更新项。
pub async fn check_updates(instance_id: &str, kind: &str) -> Result<Vec<Value>, String> {
    crate::fsutil::validate_id(instance_id, "实例")?;
    let instance = crate::instances::get_instance(instance_id).await.map_err(|e| e.to_string())?;
    let records = crate::instances::list_content_records(instance_id, kind).await;
    let loader = loader_str(&instance.loader);
    let mut updates = Vec::new();
    for item in records {
        if item.source != "modrinth" {
            continue;
        }
        let (Some(project_id), Some(_current)) = (item.project_id.as_deref(), item.version_id.as_deref()) else {
            continue;
        };
        let Ok(versions) = project_versions("modrinth", project_id, &instance.mc_version, loader).await else {
            continue;
        };
        if let Some(latest) = versions.first() {
            let latest_id = latest.id.clone();
            if !latest_id.is_empty() && latest_id != item.version_id.as_deref().unwrap_or("") {
                updates.push(json!({
                    "filename": item.filename,
                    "projectId": project_id,
                    "currentVersion": item.version,
                    "latestVersion": latest.version_number,
                    "latestVersionId": latest_id,
                    "projectTitle": item.name,
                    "kind": kind,
                    "provider": "modrinth",
                }));
            }
        }
    }
    Ok(updates)
}

/// 把「更新完成」的结果报给前端。
///
/// `ContentTab.vue` 监听 `content://update-finished`，用它弹成功/失败提示并重新拉列表 ——
/// 但这个事件**后端此前从来不发**（监听器一直挂着空等）。表现就是：用户点了「更新」之后
/// 界面毫无反馈，必须手动退出再进这个页签才知道到底成没成。
fn emit_update_finished(filename: &str, result: &Result<Value>) {
    let Some(app) = crate::progress::app() else { return };
    use tauri::Emitter;
    let payload = match result {
        Ok(_) => serde_json::json!({ "filename": filename, "ok": true }),
        Err(e) => serde_json::json!({ "filename": filename, "ok": false, "error": e.to_string() }),
    };
    let _ = app.emit("content://update-finished", payload);
}

/// 应用更新：后台下载新版本并移除旧文件，返回 `{ queued: true }`。
pub async fn apply_update(
    instance_id: &str,
    kind: &str,
    old_filename: &str,
    provider: &str,
    project_id: &str,
    new_version_id: &str,
) -> Result<Value, String> {
    // 先安装新版本（同名文件会覆盖；不同名则额外下载）
    let result = install_content(instance_id, provider, project_id, new_version_id, kind).await;
    // 成败都要报给前端（失败时把原因一起带上，前端会显示）
    emit_update_finished(old_filename, &result);
    let result = result.map_err(|e| e.to_string())?;
    // 移除旧文件 + 记录（若与新版文件名不同）
    let dir = settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())?
        .join(crate::instances::kind_folder(kind));
    if crate::util::is_safe_filename(old_filename) {
        let _ = std::fs::remove_file(dir.join(old_filename));
        let _ = std::fs::remove_file(dir.join(format!("{old_filename}.disabled")));
    }
    let _ = crate::instances::remove_content(instance_id, kind, old_filename).await;
    Ok(result)
}

/// 异步识别未识别内容：先 sha1 哈希查找，回退到名称搜索。
pub async fn identify_content(instance_id: &str, kind: &str) -> Result<(), String> {
    crate::fsutil::validate_id(instance_id, "实例")?;
    let dir = settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())?
        .join(crate::instances::kind_folder(kind));
    let records = crate::instances::list_content_records(instance_id, kind).await;
    let to_identify: Vec<(String, String)> = records
        .iter()
        .filter(|r| r.project_id.is_none() && dir.join(&r.filename).is_file())
        .filter_map(|r| {
            let path = dir.join(&r.filename);
            crate::util::file_sha1(&path).map(|h| (r.filename.clone(), h))
        })
        .collect();
    if to_identify.is_empty() {
        return Ok(());
    }

    let project_type = match kind {
        "shader" => "shader",
        "resourcepack" => "resourcepack",
        _ => "mod",
    };
    let client = crate::util::http_client().await;

    // 每识别成功一个文件，就把这一行推给前端。
    // `ContentTab.vue` 监听 `content::identified`，收到后直接就地填 name / icon / authors 等 ——
    // 这个事件此前同样**从不发送**，所以「识别」按钮在界面上等于没有可见效果（只能整页重刷）。
    // pass1（hash 命中）和 pass2（名称搜索）都要用，所以收在一处，避免两边各写一遍。
    let emit_identified = |rec: &crate::models::InstalledContent| {
        let Some(app) = crate::progress::app() else { return };
        use tauri::Emitter;
        let _ = app.emit(
            "content::identified",
            serde_json::json!({
                "instanceId": instance_id,
                "kind": kind,
                "filename": rec.filename,
                "source": rec.source,
                "projectId": rec.project_id.clone().unwrap_or_default(),
                "versionId": rec.version_id.clone().unwrap_or_default(),
                "slug": rec.slug,
                "name": rec.name,
                "description": rec.description,
                "icon": rec.icon,
                "authors": rec.authors,
            }),
        );
    };

    // ---- pass 1: hash lookup ----
    let hashes: Vec<String> = to_identify.iter().map(|(_, h)| h.clone()).collect();
    let resolved = resolve_by_hashes(&client, &hashes).await;
    let mut resolved_files: std::collections::HashSet<String> = std::collections::HashSet::new();
    for (filename, hash) in &to_identify {
        if let Some((pid, vid)) = resolved.get(hash) {
            let info = match project_info("modrinth", pid).await {
                Ok(i) => i,
                Err(_) => continue,
            };
            let slug = if info.slug.is_empty() { None } else { Some(info.slug.clone()) };
            let name = if info.title.is_empty() { None } else { Some(info.title.clone()) };
            let desc = if info.description.is_empty() { None } else { Some(info.description.clone()) };
            let icon = if info.icon_url.is_empty() { None } else { Some(info.icon_url.clone()) };
            let mut rec = crate::instances::list_content_records(instance_id, kind).await
                .into_iter()
                .find(|r| r.filename == *filename);
            if let Some(rec) = rec.as_mut() {
                rec.source = "modrinth".into();
                rec.project_id = Some(pid.clone());
                rec.version_id = Some(vid.clone());
                rec.slug = slug.clone();
                if let Some(n) = name.clone() { rec.name = Some(n); }
                if let Some(d) = desc.clone() { rec.description = Some(d); }
                if let Some(ic) = icon.clone() { rec.icon = Some(ic); }
                let _ = crate::instances::add_content_batch(instance_id, kind, vec![rec.clone()]).await;
                emit_identified(rec);
            }
            resolved_files.insert(filename.clone());
            eprintln!("[identify] {filename} → {pid}@{vid}");
        }
    }

    // ---- pass 2: name search fallback ----
    for (filename, _) in &to_identify {
        if resolved_files.contains(filename.as_str()) {
            continue;
        }
        let query = extract_search_query(filename);
        if query.is_empty() {
            continue;
        }
        let Ok(search_result) = browse("modrinth", &query, project_type, "", 0, "", "", "relevance", 1).await else {
            continue;
        };
        if let Some(hit) = search_result.hits.first() {
            let pid = hit.id.clone();
            if pid.is_empty() {
                continue;
            }
            let mut rec = crate::instances::list_content_records(instance_id, kind).await
                .into_iter()
                .find(|r| r.filename == *filename);
            if let Some(rec) = rec.as_mut() {
                rec.source = "modrinth".into();
                rec.project_id = Some(pid.clone());
                rec.slug = if hit.slug.is_empty() { None } else { Some(hit.slug.clone()) };
                if !hit.title.is_empty() { rec.name = Some(hit.title.clone()); }
                if !hit.description.is_empty() { rec.description = Some(hit.description.clone()); }
                if !hit.icon_url.is_empty() { rec.icon = Some(hit.icon_url.clone()); }
                if !hit.author.is_empty() { rec.authors = Some(vec![hit.author.clone()]); }
                let _ = crate::instances::add_content_batch(instance_id, kind, vec![rec.clone()]).await;
                emit_identified(rec);
            }
            eprintln!("[identify] {filename} → {pid} (name search)");
        }
    }
    Ok(())
}

/// 从文件名提取搜索词（去掉扩展名与版本后缀）。
fn extract_search_query(filename: &str) -> String {
    let stem = filename.trim_end_matches(".zip").trim_end_matches(".jar");
    let parts: Vec<&str> = stem.split(|c: char| c == '_' || c == '-' || c == '+').collect();
    let mut keep: Vec<&str> = Vec::new();
    for p in &parts {
        if p.is_empty() {
            continue;
        }
        if p.chars().next().map(|c| c.is_ascii_digit()).unwrap_or(false) {
            break;
        }
        if p.len() <= 2 && p.chars().all(|c| c.is_ascii_digit() || c == '.') {
            break;
        }
        keep.push(p);
    }
    keep.join(" ")
}

/// 用 sha1 批量解析 Modrinth 项目/版本。返回 sha1 → (project_id, version_id)。
async fn resolve_by_hashes(
    client: &reqwest::Client,
    hashes: &[String],
) -> std::collections::HashMap<String, (String, String)> {
    let mut out = std::collections::HashMap::new();
    if hashes.is_empty() {
        return out;
    }
    let body = json!({ "hashes": hashes, "algorithm": "sha1" });
    let Ok(resp) = client.post(format!("{}/version_files", MODRINTH_API)).json(&body).send().await else {
        return out;
    };
    let Ok(map) = resp.json::<Value>().await else {
        return out;
    };
    if let Some(obj) = map.as_object() {
        for (hash, ver) in obj {
            let pid = ver.get("project_id").and_then(|v| v.as_str()).unwrap_or("");
            let vid = ver.get("id").and_then(|v| v.as_str()).unwrap_or("");
            if !pid.is_empty() && !vid.is_empty() {
                out.insert(hash.clone(), (pid.to_string(), vid.to_string()));
            }
        }
    }
    out
}

// ==================== MC Wiki URL ====================

pub async fn mc_wiki_url(name: &str, slug: Option<&str>, _provider: Option<&str>) -> Result<String> {
    let slug = slug.unwrap_or(name);
    Ok(format!("https://minecraft.wiki/w/{}", urlencoding::encode(slug)))
}

// ==================== Helpers ====================

fn calculate_dir_size_sync(path: &std::path::Path) -> i64 {
    let mut total: i64 = 0;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            if let Ok(meta) = std::fs::metadata(entry.path()) {
                if meta.is_file() {
                    total += meta.len() as i64;
                } else if meta.is_dir() {
                    total += calculate_dir_size_sync(&entry.path());
                }
            }
        }
    }
    total
}

fn count_files_sync(path: &std::path::Path) -> i64 {
    let mut count: i64 = 0;
    if let Ok(entries) = std::fs::read_dir(path) {
        for entry in entries.flatten() {
            if let Ok(meta) = std::fs::metadata(entry.path()) {
                if meta.is_file() {
                    count += 1;
                } else if meta.is_dir() {
                    count += count_files_sync(&entry.path());
                }
            }
        }
    }
    count
}

async fn calculate_dir_size(path: &std::path::Path) -> i64 {
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || calculate_dir_size_sync(&path))
        .await
        .unwrap_or(0)
}

async fn count_files(path: &std::path::Path) -> i64 {
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || count_files_sync(&path))
        .await
        .unwrap_or(0)
}

// ==================== Game Icons ====================

#[derive(serde::Serialize, Clone)]
pub struct GameIcon {
    pub name: String,
    pub label: String,
    pub path: String,
}

const GAME_TEXTURES: &[(&str, &str, &[&str])] = &[
    ("diamond", "钻石", &["assets/minecraft/textures/item/diamond.png", "assets/minecraft/textures/items/diamond.png"]),
    ("emerald", "绿宝石", &["assets/minecraft/textures/item/emerald.png", "assets/minecraft/textures/items/emerald.png"]),
    ("iron_ingot", "铁锭", &["assets/minecraft/textures/item/iron_ingot.png", "assets/minecraft/textures/items/iron_ingot.png"]),
    ("gold_ingot", "金锭", &["assets/minecraft/textures/item/gold_ingot.png", "assets/minecraft/textures/items/gold_ingot.png"]),
    ("netherite_ingot", "下界合金锭", &["assets/minecraft/textures/item/netherite_ingot.png", "assets/minecraft/textures/items/netherite_ingot.png"]),
    ("iron_sword", "铁剑", &["assets/minecraft/textures/item/iron_sword.png", "assets/minecraft/textures/items/iron_sword.png"]),
    ("diamond_sword", "钻石剑", &["assets/minecraft/textures/item/diamond_sword.png", "assets/minecraft/textures/items/diamond_sword.png"]),
    ("netherite_sword", "下界合金剑", &["assets/minecraft/textures/item/netherite_sword.png", "assets/minecraft/textures/items/netherite_sword.png"]),
    ("bow", "弓", &["assets/minecraft/textures/item/bow.png", "assets/minecraft/textures/items/bow.png"]),
    ("shield", "盾牌", &["assets/minecraft/textures/item/shield.png", "assets/minecraft/textures/items/shield.png"]),
    ("apple", "苹果", &["assets/minecraft/textures/item/apple.png", "assets/minecraft/textures/items/apple.png"]),
    ("golden_apple", "金苹果", &["assets/minecraft/textures/item/golden_apple.png", "assets/minecraft/textures/items/golden_apple.png"]),
    ("clock", "时钟", &["assets/minecraft/textures/item/clock_0.png", "assets/minecraft/textures/items/clock_0.png"]),
    ("compass", "指南针", &["assets/minecraft/textures/item/compass_0.png", "assets/minecraft/textures/items/compass_0.png"]),
    ("map", "地图", &["assets/minecraft/textures/item/map.png", "assets/minecraft/textures/items/map.png"]),
    ("bucket", "桶", &["assets/minecraft/textures/item/bucket.png", "assets/minecraft/textures/items/bucket.png"]),
    ("fishing_rod", "钓鱼竿", &["assets/minecraft/textures/item/fishing_rod.png", "assets/minecraft/textures/items/fishing_rod.png"]),
    ("shears", "剪刀", &["assets/minecraft/textures/item/shears.png", "assets/minecraft/textures/items/shears.png"]),
    ("flint_and_steel", "打火石", &["assets/minecraft/textures/item/flint_and_steel.png", "assets/minecraft/textures/items/flint_and_steel.png"]),
    ("ender_eye", "末影之眼", &["assets/minecraft/textures/item/ender_eye.png", "assets/minecraft/textures/items/ender_eye.png"]),
    ("ender_pearl", "末影珍珠", &["assets/minecraft/textures/item/ender_pearl.png", "assets/minecraft/textures/items/ender_pearl.png"]),
    ("firework_rocket", "烟花火箭", &["assets/minecraft/textures/item/firework_rocket.png", "assets/minecraft/textures/items/firework_rocket.png"]),
    ("book", "书", &["assets/minecraft/textures/item/book.png", "assets/minecraft/textures/items/book.png"]),
    ("enchanted_book", "附魔书", &["assets/minecraft/textures/item/enchanted_book.png", "assets/minecraft/textures/items/enchanted_book.png"]),
    ("totem_of_undying", "不死图腾", &["assets/minecraft/textures/item/totem_of_undying.png", "assets/minecraft/textures/items/totem_of_undying.png"]),
    ("nether_star", "下界之星", &["assets/minecraft/textures/item/nether_star.png", "assets/minecraft/textures/items/nether_star.png"]),
    ("blaze_rod", "烈焰棒", &["assets/minecraft/textures/item/blaze_rod.png", "assets/minecraft/textures/items/blaze_rod.png"]),
    ("experience_bottle", "经验瓶", &["assets/minecraft/textures/item/experience_bottle.png", "assets/minecraft/textures/items/experience_bottle.png"]),
    ("grass_block", "草方块", &["assets/minecraft/textures/block/grass_block_top.png", "assets/minecraft/textures/blocks/grass_top.png"]),
    ("stone", "石头", &["assets/minecraft/textures/block/stone.png", "assets/minecraft/textures/blocks/stone.png"]),
    ("diamond_block", "钻石块", &["assets/minecraft/textures/block/diamond_block.png", "assets/minecraft/textures/blocks/diamond_block.png"]),
    ("gold_block", "金块", &["assets/minecraft/textures/block/gold_block.png", "assets/minecraft/textures/blocks/gold_block.png"]),
    ("iron_block", "铁块", &["assets/minecraft/textures/block/iron_block.png", "assets/minecraft/textures/blocks/iron_block.png"]),
    ("emerald_block", "绿宝石块", &["assets/minecraft/textures/block/emerald_block.png", "assets/minecraft/textures/blocks/emerald_block.png"]),
    ("netherite_block", "下界合金块", &["assets/minecraft/textures/block/netherite_block.png", "assets/minecraft/textures/blocks/netherite_block.png"]),
    ("crafting_table", "工作台", &["assets/minecraft/textures/block/crafting_table_front.png", "assets/minecraft/textures/blocks/crafting_table_front.png"]),
    ("furnace", "熔炉", &["assets/minecraft/textures/block/furnace_front.png", "assets/minecraft/textures/blocks/furnace_front.png"]),
    ("beacon", "信标", &["assets/minecraft/textures/block/beacon.png", "assets/minecraft/textures/blocks/beacon.png"]),
    ("enchanting_table", "附魔台", &["assets/minecraft/textures/block/enchanting_table_front.png", "assets/minecraft/textures/blocks/enchanting_table_front.png"]),
    ("anvil", "铁砧", &["assets/minecraft/textures/block/anvil.png", "assets/minecraft/textures/blocks/anvil.png"]),
    ("tnt", "TNT", &["assets/minecraft/textures/block/tnt.png", "assets/minecraft/textures/blocks/tnt.png"]),
    ("obsidian", "黑曜石", &["assets/minecraft/textures/block/obsidian.png", "assets/minecraft/textures/blocks/obsidian.png"]),
    ("glowstone", "荧石", &["assets/minecraft/textures/block/glowstone.png", "assets/minecraft/textures/blocks/glowstone.png"]),
    ("bookshelf", "书架", &["assets/minecraft/textures/block/bookshelf.png", "assets/minecraft/textures/blocks/bookshelf.png"]),
    ("pumpkin", "南瓜", &["assets/minecraft/textures/block/pumpkin_front.png", "assets/minecraft/textures/blocks/pumpkin_front.png"]),
    ("cake", "蛋糕", &["assets/minecraft/textures/block/cake_top.png", "assets/minecraft/textures/blocks/cake_top.png"]),
    ("sponge", "海绵", &["assets/minecraft/textures/block/sponge.png", "assets/minecraft/textures/blocks/sponge.png"]),
    ("ice", "冰", &["assets/minecraft/textures/block/ice.png", "assets/minecraft/textures/blocks/ice.png"]),
    ("netherrack", "下界岩", &["assets/minecraft/textures/block/netherrack.png", "assets/minecraft/textures/blocks/netherrack.png"]),
    ("redstone", "红石粉", &["assets/minecraft/textures/item/redstone.png", "assets/minecraft/textures/items/redstone.png"]),
    ("gunpowder", "火药", &["assets/minecraft/textures/item/gunpowder.png", "assets/minecraft/textures/items/gunpowder.png"]),
    ("slime_ball", "粘液球", &["assets/minecraft/textures/item/slime_ball.png", "assets/minecraft/textures/items/slime_ball.png"]),
    ("bone", "骨头", &["assets/minecraft/textures/item/bone.png", "assets/minecraft/textures/items/bone.png"]),
    ("stick", "木棍", &["assets/minecraft/textures/item/stick.png", "assets/minecraft/textures/items/stick.png"]),
    ("coal", "煤炭", &["assets/minecraft/textures/item/coal.png", "assets/minecraft/textures/items/coal.png"]),
    ("wheat", "小麦", &["assets/minecraft/textures/item/wheat.png", "assets/minecraft/textures/items/wheat.png"]),
    ("carrot", "胡萝卜", &["assets/minecraft/textures/item/carrot.png", "assets/minecraft/textures/items/carrot.png"]),
    ("potato", "马铃薯", &["assets/minecraft/textures/item/potato.png", "assets/minecraft/textures/items/potato.png"]),
];

/// 从已安装版本的 client.jar 中提取一套 Minecraft 材质作为实例图标。
pub async fn extract_game_icons(instance_id: Option<String>) -> Result<Vec<GameIcon>, String> {
    use std::io::Read;
    let data_dir = settings::get_data_dir().await.map_err(|e| e.to_string())?;
    let versions_dir = std::path::PathBuf::from(&data_dir).join("versions");
    let mut client_jar: Option<std::path::PathBuf> = None;

    if let Some(id) = &instance_id {
        let jar = versions_dir.join(id).join(format!("{}.jar", id));
        if jar.exists() {
            client_jar = Some(jar);
        }
        if client_jar.is_none() {
            if let Ok(inst) = crate::instances::get_instance(id).await {
                let jar = versions_dir.join(&inst.mc_version).join(format!("{}.jar", inst.mc_version));
                if jar.exists() {
                    client_jar = Some(jar);
                }
            }
        }
    }
    if client_jar.is_none() {
        if let Ok(entries) = std::fs::read_dir(&versions_dir) {
            for e in entries.flatten() {
                let dir = e.path();
                if !dir.is_dir() { continue; }
                let name = e.file_name().to_string_lossy().to_string();
                let jar = dir.join(format!("{}.jar", name));
                if jar.exists() {
                    client_jar = Some(jar);
                    break;
                }
            }
        }
    }
    let jar_path = client_jar.ok_or_else(|| "未找到已安装的游戏版本，请先安装游戏后再设置图标".to_string())?;

    let icons_dir = std::path::PathBuf::from(&data_dir).join("game-icons");
    // client.jar 动辄几十 MB，打开 + 逐个解压全是同步 IO，挪出 tokio worker
    let icons = tokio::task::spawn_blocking(move || -> Result<Vec<GameIcon>, String> {
        let jar_path = std::path::Path::new(&jar_path);
        std::fs::create_dir_all(&icons_dir).map_err(|e| format!("创建图标目录失败: {e}"))?;

        let file =
            std::fs::File::open(jar_path).map_err(|e| format!("打开 client.jar 失败: {e}"))?;
        let mut archive =
            zip::ZipArchive::new(file).map_err(|e| format!("解析 client.jar 失败: {e}"))?;

        let mut icons = Vec::new();
        for (key, label, candidates) in GAME_TEXTURES {
            for cand in *candidates {
                if let Ok(mut entry) = archive.by_name(cand) {
                    if entry.is_dir() {
                        continue;
                    }
                    let mut buf = Vec::new();
                    if Read::read_to_end(&mut entry, &mut buf).is_err() {
                        continue;
                    }
                    if buf.len() < 8 {
                        continue;
                    }
                    let out_path = icons_dir.join(format!("{}.png", key));
                    if std::fs::write(&out_path, &buf).is_err() {
                        continue;
                    }
                    icons.push(GameIcon {
                        name: key.to_string(),
                        label: label.to_string(),
                        path: out_path.to_string_lossy().to_string(),
                    });
                    break;
                }
            }
        }
        if icons.is_empty() {
            return Err("未能从游戏文件中提取任何图标素材".into());
        }
        Ok(icons)
    })
    .await
    .map_err(|e| format!("提取图标任务失败: {e}"))??;

    Ok(icons)
}

/// 写文本内容到文件（用于日志导出等）。
///
/// 安卓上「另存为」对话框 `save()` 返回的是 SAF 的 `content://` URI 而不是路径，
/// 对 URI 直接 `fs::write` 会失败 —— 那种情况必须走原生的 ContentResolver 写回。
pub fn save_text_file(path: &str, content: &str) -> Result<(), String> {
    if path.starts_with("content://") {
        return crate::android_bridge::write_text_to_uri(path, content);
    }
    let p = std::path::Path::new(path);
    if let Some(parent) = p.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
    }
    std::fs::write(p, content).map_err(|e| format!("写入文件失败: {e}"))
}

// ==================== 服务器内容安装（2026-10-18 新增）====================

/// 把内容装到**托管服务器**的对应目录。
///
/// 与 [install_content] 的区别只有一处：目标目录不是实例目录，
/// 而是 `<servers>/<server_id>/<folder>/`。
///
/// ## 为什么需要它
///
/// 用户问「我有一个 fabric 的服务器，能直接在内容中心选一个 mod 装到服务器吗」。
/// 查证：**不能** —— `install_content` 的签名里只有 `instance_id`。
/// UI 上却对所有内容类型都显示「安装到服务器」区块，选了也不生效，
/// 最终被「请选择实例与版本」挡回来。这是误导，不是功能。
///
/// ## 只允许服务端能用的类型
///
/// 只有 `mod` / `plugin` 能装到服务器：
///   - `resourcepack` / `shader` 是**纯客户端**的东西，服务端没这两个目录
///   - `modpack` 是整合包，要整体解压，不走这条路
///
/// ⚠️ 还有个拦不住的限制：**客户端专属 mod（Sodium、光影加载器这类）
/// 装到服务端会让服务端启动崩溃**。这里只能按 kind 拦，
/// 拦不了「这个 mod 是否支持 dedicated server」——
/// UI 层应当用 Modrinth 的 `server_side` 字段提示用户。
pub async fn install_content_to_server(
    server_id: &str,
    provider: &str,
    project_id: &str,
    version_id: &str,
    kind: &str,
) -> Result<Value> {
    let folder = match kind {
        "mod" => "mods",
        "plugin" => "plugins",
        other => {
            return Err(anyhow::anyhow!(
                "「{other}」不能安装到服务器，只有模组和插件可以"
            ))
        }
    };

    let server_path = crate::servers::server_dir(server_id).map_err(|e| anyhow::anyhow!(e))?;
    let content_dir = server_path.join(folder);
    tokio::fs::create_dir_all(&content_dir).await.ok();

    let (file_url, file_name) = resolve_content_file(provider, version_id).await?;
    let project_title = if provider == "modrinth" {
        project_info("modrinth", project_id)
            .await
            .map(|i| i.title)
            .unwrap_or_default()
    } else {
        String::new()
    };

    if file_url.is_empty() || file_name.is_empty() {
        return Err(anyhow::anyhow!("Could not find file to download"));
    }
    if !crate::util::is_safe_filename(&file_name) {
        return Err(anyhow::anyhow!("非法的文件名: {file_name}"));
    }

    // 任务中心里要能区分「装到哪个服务器」
    let server_name = crate::servers::list_servers()
        .into_iter()
        .find(|s| s.id == server_id)
        .map(|s| s.name)
        .unwrap_or_else(|| server_id.to_string());
    let provider_label = if provider == "curseforge" { "CurseForge" } else { "Modrinth" };
    let ctx = crate::progress::TaskCtx::new(
        server_id,
        &server_name,
        &format!(
            "{provider_label}（服务器）：{}",
            if project_title.is_empty() { file_name.as_str() } else { project_title.as_str() }
        ),
    );
    crate::progress::emit_install(&ctx, "content", &format!("正在下载 {file_name}…"), 0, 1);

    let dest = content_dir.join(&file_name);
    let download_result = download::download_file(&file_url, &dest.to_string_lossy(), None).await;
    match &download_result {
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
            crate::progress::emit_install_done(&ctx, true, "安装完成", 1, 1);
        }
        Err(e) => {
            crate::progress::emit_install_done(&ctx, false, &format!("安装失败：{e}"), 0, 1);
        }
    }
    download_result?;

    // 服务器没有「内容记录」这套元数据（那是实例的 instance.json 才有的），
    // 所以这里不登记 —— 服务器页的文件列表直接读磁盘。
    let size = std::fs::metadata(&dest).map(|m| m.len()).unwrap_or(0);

    Ok(json!({
        "ok": true,
        "filename": file_name,
        "size": size,
    }))
}
