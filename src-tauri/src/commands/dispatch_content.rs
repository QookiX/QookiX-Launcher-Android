//! 内容中心域（搜索 / 项目详情 / 安装 / 翻译）（从 `commands.rs` 的 `native_invoke` 拆出）。
//!
//! Modrinth / CurseForge 的搜索与项目操作，以及翻译相关的调用。
//!
//! 拆法见 `instance.rs`：`dispatch` 只路由，命中后进 `run`。

use serde_json::Value;

use super::*;

pub(crate) const HANDLED: &[&str] = &[
    "list_content",
    "identify_content",
    "toggle_content_enabled",
    "uninstall_content",
    "project_info",
    "project_versions",
    "project_dependencies",
    "curseforge_categories",
    "browse",
    "translate_mod_descriptions",
    "translate_project_body",
    "report_translation_stale",
    "report_translation_quality",
    "clear_translation_cache",
    "test_translate_api",
    "install_content",
    "install_mod",
    "uninstall_mod",
    "mc_wiki_url",
    "fetch_image_data_url",
    "estimate_download",
    "estimate_import",
    "install_version",
    "get_version_list",
    "get_loader_versions",
    "get_version_manifest",
    "get_version_info",
];

pub(crate) async fn dispatch(cmd: &str, p: &Value) -> Option<String> {
    if !HANDLED.contains(&cmd) {
        return None;
    }
    Some(run(cmd, p).await)
}

async fn run(cmd: &str, p: &Value) -> String {
    match cmd {
        "list_content" => {
            let v: serde_json::Value = match serde_json::from_str(&p.to_string()) {
                Ok(v) => v,
                Err(e) => return err(format!("payload 不是合法 JSON：{e}")),
            };
            let Some(instance_id) = v.get("instanceId").and_then(|x| x.as_str()) else {
                return err("缺少 instanceId");
            };
            let kind = v.get("kind").and_then(|x| x.as_str()).unwrap_or("mods");
            match list_content(instance_id.to_string(), kind.to_string()).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // ── 只读命令（阶段 3 第一批）──────────────────────────────
        // 这些命令都是纯查询，迁过来以后「切换页面看到空列表」这类问题
        // 就彻底不依赖 WebView 了。
        "mc_wiki_url" => {
            let Some(name) = sarg(p, &["name"]) else {
                return err("缺少 name");
            };
            match mc_wiki_url(name, sarg(p, &["slug"]), sarg(p, &["provider"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "get_version_list" => match get_version_list().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_loader_versions" => {
            let Some(loader) = sarg(p, &["loader"]) else {
                return err("缺少 loader");
            };
            let Some(mc) = sarg(p, &["mcVersion", "mc_version"]) else {
                return err("缺少 mcVersion");
            };
            match get_loader_versions(loader, mc).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "uninstall_content" => {
            let (Some(id), Some(kind), Some(file)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
                sarg(p, &["filename"]),
            ) else {
                return err("缺少 instanceId / kind / filename");
            };
            match uninstall_content(id, kind, file).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "identify_content" => {
            let (Some(id), Some(kind)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
            ) else {
                return err("缺少 instanceId / kind");
            };
            match identify_content(id, kind).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "toggle_content_enabled" => {
            let (Some(id), Some(kind), Some(file)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["kind"]),
                sarg(p, &["filename"]),
            ) else {
                return err("缺少 instanceId / kind / filename");
            };
            // ⚠️ 缺字段不能默认 false（审查 A-P2-5）：那等于「漏传一个参数就
            // 把 mod 禁用掉」，而调用方想表达的是相反的意思
            let Some(enabled) = p.get("enabled").and_then(|x| x.as_bool()) else {
                return err("缺少 enabled");
            };
            match toggle_content_enabled(id, kind, file, enabled).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "get_version_manifest" => match get_version_manifest().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "get_version_info" => {
            let Some(vid) = sarg(p, &["versionId", "version_id", "id"]) else {
                return err("缺少 versionId");
            };
            match get_version_info(vid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "curseforge_categories" => match curseforge_categories().await {
            Ok(v) => ok(v),
            Err(e) => err(e),
        },
        "project_info" => {
            let (Some(provider), Some(pid)) =
                (sarg(p, &["provider"]), sarg(p, &["projectId", "project_id"]))
            else {
                return err("缺少 provider / projectId");
            };
            match project_info(provider, pid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "project_versions" => {
            let (Some(provider), Some(pid)) =
                (sarg(p, &["provider"]), sarg(p, &["projectId", "project_id"]))
            else {
                return err("缺少 provider / projectId");
            };
            let mc = sarg(p, &["mcVersion", "mc_version"]).unwrap_or_default();
            let loader = sarg(p, &["loader"]).unwrap_or_default();
            match project_versions(provider, pid, mc, loader).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "project_dependencies" => {
            let (Some(provider), Some(pid)) =
                (sarg(p, &["provider"]), sarg(p, &["projectId", "project_id"]))
            else {
                return err("缺少 provider / projectId");
            };
            let vid = sarg(p, &["versionId", "version_id"]);
            match project_dependencies(provider, pid, vid).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        // 返回 `Result<String, String>` 的命令要按 Tauri 的形状**再包一层字符串**，
        // 否则 Kotlin 侧的「去引号」逻辑与旧通道不一致（多行文本/URL）。
        "fetch_image_data_url" => {
            let Some(url) = sarg(p, &["url"]) else {
                return err("缺少 url");
            };
            match fetch_image_data_url(url).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "browse" => {
            let num = |keys: &[&str]| -> Option<i64> {
                keys.iter()
                    .find_map(|k| p.get(*k).and_then(|x| x.as_i64()))
            };
            let (Some(provider), Some(project_type)) =
                (sarg(p, &["provider"]), sarg(p, &["projectType", "project_type"]))
            else {
                return err("缺少 provider / projectType");
            };
            let query = sarg(p, &["query"]).unwrap_or_default();
            let category = sarg(p, &["category"]).unwrap_or_default();
            let page = num(&["page"]).unwrap_or(1) as i32;
            let game_version = sarg(p, &["gameVersion", "game_version"]).unwrap_or_default();
            let loader = sarg(p, &["loader"]).unwrap_or_default();
            let sort = sarg(p, &["sort"]).unwrap_or_default();
            let page_size = num(&["pageSize", "page_size"]).unwrap_or(20) as i32;
            match browse(
                provider,
                query,
                project_type,
                category,
                page,
                game_version,
                loader,
                sort,
                page_size,
            )
            .await
            {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "translate_mod_descriptions" => {
            let Some(provider) = sarg(p, &["provider"]) else {
                return err("缺少 provider");
            };
            match translate_mod_descriptions(provider, svec(p, &["slugs"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "translate_project_body" => {
            let (Some(provider), Some(slug)) = (sarg(p, &["provider"]), sarg(p, &["slug"]))
            else {
                return err("缺少 provider / slug");
            };
            let translate = p.get("translate").and_then(|x| x.as_bool()).unwrap_or(true);
            match translate_project_body(provider, slug, translate).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "estimate_download" => {
            let Some(mc) = sarg(p, &["mcVersion", "mc_version"]) else {
                return err("缺少 mcVersion");
            };
            match estimate_download(mc).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "estimate_import" => {
            let Some(source) = sarg(p, &["source"]) else {
                return err("缺少 source");
            };
            match estimate_import(source, svec(p, &["rawIds", "raw_ids"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "install_version" => {
            let Some(vid) = sarg(p, &["versionId", "version_id", "id"]) else {
                return err("缺少 versionId");
            };
            match install_version(vid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "install_mod" => {
            let (Some(id), Some(mid), Some(url)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["modId", "mod_id"]),
                sarg(p, &["modUrl", "mod_url"]),
            ) else {
                return err("缺少 instanceId / modId / modUrl");
            };
            match install_mod(id, mid, url).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "uninstall_mod" => {
            let (Some(id), Some(mid)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["modId", "mod_id"]),
            ) else {
                return err("缺少 instanceId / modId");
            };
            match uninstall_mod(id, mid).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        "install_content" => {
            let (Some(id), Some(provider), Some(pid), Some(vid), Some(kind)) = (
                sarg(p, &["instanceId", "instance_id", "id"]),
                sarg(p, &["provider"]),
                sarg(p, &["projectId", "project_id"]),
                sarg(p, &["versionId", "version_id"]),
                sarg(p, &["kind"]),
            ) else {
                return err("缺少 instanceId / provider / projectId / versionId / kind");
            };
            match install_content(id, provider, pid, vid, kind).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "report_translation_stale" => {
            let (Some(provider), Some(slug)) = (sarg(p, &["provider"]), sarg(p, &["slug"]))
            else {
                return err("缺少 provider / slug");
            };
            match report_translation_stale(provider, slug).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        "report_translation_quality" => {
            let (Some(provider), Some(slug), Some(issue)) = (
                sarg(p, &["provider"]),
                sarg(p, &["slug"]),
                sarg(p, &["issueType", "issue_type"]),
            ) else {
                return err("缺少 provider / slug / issueType");
            };
            match report_translation_quality(
                provider,
                slug,
                issue,
                sarg(p, &["userSuggestion", "user_suggestion"]),
                sarg(p, &["userComment", "user_comment"]),
            )
            .await
            {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            }
        }
        // ── 收尾批 B（阶段 3 第十一批）──────────────────────────────
        "clear_translation_cache" => {
            match clear_translation_cache(sarg(p, &["service"])).await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            }
        }
        "test_translate_api" => {
            let (Some(base), Some(key), Some(model)) = (
                sarg(p, &["base"]),
                sarg(p, &["key"]),
                sarg(p, &["model"]),
            ) else {
                return err("缺少 base / key / model");
            };
            match test_translate_api(base, key, model).await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            }
        }
        _ => unreachable!("HANDLED 与 match 不一致：{cmd}"),
    }
}
