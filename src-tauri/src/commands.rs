use crate::models::*;
use crate::settings;
use crate::instances;
use crate::accounts;
use crate::version;
use crate::download;
use crate::launch;
use crate::modpack;
use crate::hangar;
use crate::frp;
use crate::browse;
use crate::{pins, groups, mirror, storage, crash, skins, servers, world_backup, playtime, util, fsutil};
use serde_json::{json, Value};
// ==================== Version Commands ====================
pub async fn get_version_list() -> Result<crate::version::VersionList, String> {
    version::fetch_manifest().await.map_err(|e| e.to_string())
}

/// 版本清单（前端 CreateInstanceView 使用），返回与前端 `getVersionManifest` 期望一致的字段名。
pub async fn get_version_manifest() -> Result<Value, String> {
    let list = version::fetch_manifest().await.map_err(|e| e.to_string())?;
    Ok(json!({
        "versions": list.versions.iter().map(|v| json!({
            "id": v.id,
            "type": v.r#type,
            "releaseTime": v.released_time,
        })).collect::<Vec<_>>(),
        "latest": {
            "release": list.latest.release,
            "snapshot": list.latest.snapshot,
        },
    }))
}
pub async fn get_version_info(version_id: String) -> Result<crate::version::MinecraftVersion, String> {
    version::get_version_info(&version_id).await.map_err(|e| e.to_string())
}
pub async fn install_version(version_id: String) -> Result<(), String> {
    // 带上 TaskCtx。以前这里是 `version::install_version(...)`（内部传 ctx = None），
    // 于是从「创建实例」或安装对话框发起的版本安装在「下载中心」里**完全没有进度**，
    // 也没有可取消的任务 id。
    //
    // `instance_id` / `instance_name` 故意留空：前端用 `if (p.instanceId)` 做守卫，
    // 空串就不会渲染「目标实例：…」那行（否则会显示一个点不动的死链接）。
    let ctx = crate::progress::TaskCtx::new("", "", &format!("游戏本体 {version_id}"));
    let result = version::install_version_tracked(&version_id, Some(&ctx)).await;
    match &result {
        Ok(()) => crate::progress::emit_install_done(&ctx, true, "游戏本体安装完成", 0, 0),
        Err(e) => {
            let msg = e.to_string();
            // 主动取消不该显示成「安装失败」
            let text = if msg.contains("已取消") {
                "已取消".to_string()
            } else {
                format!("安装失败：{msg}")
            };
            crate::progress::emit_install_done(&ctx, false, &text, 0, 0);
        }
    }
    // 收尾：把取消标志摘掉，避免长会话里累积
    ctx.release();
    result.map_err(|e| e.to_string())
}

// ==================== Instance Commands ====================
pub async fn create_instance(
    name: String,
    mc_version: String,
    loader: String,
    loader_version: Option<String>,
    java_dir: Option<String>,
    java_args: Option<String>,
    game_args: Option<String>,
    resolution: Option<Vec<i64>>,
    max_memory_mb: Option<i64>,
    memory_mode: Option<String>,
    account_id: Option<String>,
    icon: Option<String>,
    group: Option<String>,
    // ⚠️⚠️ 必须声明（2026-10-16 修，阻断性缺陷）。
    //
    // Tauri 命令的参数是**按签名反序列化**的：签名里没写的字段会被**静默丢弃** ——
    // 不报错、不警告。而下面那个 `serde_json::json!` 又是**手工重建** config 的，
    // 于是 `instances.rs:119` 的 `config["renderer"]` 永远拿不到值 ——
    // 尽管那边的注释写着「创建实例时也允许直接指定」。
    //
    // 后果（真机实测）：新建实例的 `renderer` 恒为 null →
    // `launch::resolve_renderer` 走「按版本自动」→ MC 1.21.x 被判给 **GL4ES** →
    // GL4ES 不实现 `glMapBufferRange` → 游戏一点启动就崩：
    //     IllegalStateException: Can't map buffer, opengl error 0
    // 这是**每个新用户都会撞上**的路径。
    renderer: Option<String>,
) -> Result<MinecraftProfile, String> {
    let config = serde_json::json!({
        "name": name,
        "mcVersion": mc_version,
        "loader": loader,
        "loaderVersion": loader_version.unwrap_or_default(),
        "javaDir": java_dir.unwrap_or_default(),
        "javaArgs": java_args.unwrap_or_default(),
        "gameArgs": game_args.unwrap_or_default(),
        "resolution": resolution.unwrap_or_default(),
        "maxMemoryMb": max_memory_mb,
        "memoryMode": memory_mode,
        "accountId": account_id,
        "icon": icon,
        "group": group,
        // 前端（CreateRepo.autoRendererFor）按 MC 版本算好再传，避开上面那个误判
        "renderer": renderer,
    });
    instances::create_instance(config).await.map_err(|e| e.to_string())
}
pub async fn delete_instance(id: String) -> Result<(), String> {
    instances::delete_instance(&id, false).await.map_err(|e| e.to_string())
}

// ── JNI 直连入口（阶段 1：绕开 WebView）──────────────────────────────
//
// ## 为什么需要
//
// 现在所有 `#[tauri::command]` 只能由 **Tauri 的 JS IPC** 触发，而那条链必须
// 先加载隐藏 WebView 里的 `index.html`。一旦这个网页/渲染进程出问题，
// 全部功能立刻变成「点了没反应」（真机反复遇到，日志里只有 `桥尚未就绪`）。
//
// 这里提供一个**与 Tauri 无关**的 JSON 入口：
//   `native_invoke(cmd, payload) -> {"ok":true,"data":…} | {"ok":false,"error":"…"}`
// 由 Kotlin 通过 JNI 直接调用（见 `jni_bridge.rs` 的 `nativeInvoke`）。
//
// 阶段 1 只接 4 个命令做试点（实例列表 / 设置 / 内容列表 / 新闻）；
// 验证通之后，再按模块把全部 58 个命令迁过来，最终删掉 Tauri。
/// 从 JSON payload 里取第一个命中的字符串参数（兼容 camelCase 与 snake_case）。
///
/// Tauri 侧参数名是 camelCase（`instanceId`），Rust 形参是 snake_case
/// （`instance_id`）；Kotlin 各处写法不统一，所以两种都认。
fn sarg(v: &serde_json::Value, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| v.get(*k).and_then(|x| x.as_str()).map(|s| s.to_string()))
}

/// 从 JSON payload 里取第一个命中的整数参数（兼容 camelCase / snake_case）。
fn iarg(v: &serde_json::Value, keys: &[&str]) -> Option<i64> {
    keys.iter()
        .find_map(|k| v.get(*k).and_then(|x| x.as_i64()))
}

/// 从 JSON payload 里取字符串数组（例如 `slugs` / `rawIds`）。
fn svec(v: &serde_json::Value, keys: &[&str]) -> Vec<String> {
    keys.iter()
        .find_map(|k| v.get(*k).and_then(|x| x.as_array()))
        .map(|arr| {
            arr.iter()
                .filter_map(|x| x.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default()
}

pub fn native_invoke(cmd: &str, payload: &str) -> String {
    fn ok<T: serde::Serialize>(v: T) -> String {
        match serde_json::to_value(v) {
            Ok(v) => serde_json::json!({ "ok": true, "data": v }).to_string(),
            Err(e) => serde_json::json!({ "ok": false, "error": format!("序列化失败：{e}") }).to_string(),
        }
    }
    fn err(e: impl std::fmt::Display) -> String {
        serde_json::json!({ "ok": false, "error": e.to_string() }).to_string()
    }

    // ⚠️ 2026-10-25 诊断：导入卡死时用来判断命令到底有没有进 Rust。
    crate::util::log_line(&format!("[native] 进入 {cmd}（{}B）", payload.len()));

    // 参数按需取：解析失败就当空对象（有些命令无参）
    let p: serde_json::Value =
        serde_json::from_str(payload).unwrap_or_else(|_| serde_json::json!({}));

    let rt = match tokio::runtime::Runtime::new() {
        Ok(rt) => rt,
        Err(e) => return err(format!("创建运行时失败：{e}")),
    };
    rt.block_on(async move {
        match cmd {
            "list_instances" => match list_instances().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_settings" => match get_settings().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "fetch_news" => match fetch_news().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "list_content" => {
                let v: serde_json::Value = match serde_json::from_str(payload) {
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
            "get_instance" => {
                let Some(id) = sarg(&p, &["id", "instanceId", "instance_id"]) else {
                    return err("缺少 id");
                };
                match get_instance(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_accounts" => match list_accounts().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_plugins" => match get_plugins().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_installed_mods" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match get_installed_mods(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "mc_wiki_url" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                match mc_wiki_url(name, sarg(&p, &["slug"]), sarg(&p, &["provider"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "get_pins" => ok(get_pins().await),
            "list_instance_groups" => match list_instance_groups().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_storage_stats" => match get_storage_stats().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            // ── 只读命令（阶段 3 第二批）──────────────────────────────
            "get_version_list" => match get_version_list().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_loader_versions" => {
                let Some(loader) = sarg(&p, &["loader"]) else {
                    return err("缺少 loader");
                };
                let Some(mc) = sarg(&p, &["mcVersion", "mc_version"]) else {
                    return err("缺少 mcVersion");
                };
                match get_loader_versions(loader, mc).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_crash_logs" => {
                let Some(id) = sarg(&p, &["id", "instanceId", "instance_id"]) else {
                    return err("缺少 id");
                };
                match list_crash_logs(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_skins" => match list_skins().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "list_servers" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match list_servers(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_world_backups" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                let Some(world) = sarg(&p, &["world"]) else {
                    return err("缺少 world");
                };
                match list_world_backups(id, world).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "playtime_stats" => match playtime_stats().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "list_instance_files" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                let sub = sarg(&p, &["sub"]).unwrap_or_default();
                match list_instance_files(id, sub).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_instance_dir" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                let rel = sarg(&p, &["rel"]).unwrap_or_default();
                match list_instance_dir(id, rel).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "read_instance_log" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match read_instance_log(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // ── 写操作（阶段 3 第三批）────────────────────────────────
            // 注意返回值：`Result<(), String>` 成功时 data 是 `null`，
            // Kotlin 侧 `o.get("data").toString()` 得到 "null"，
            // 与 Tauri 对 unit 的序列化一致 ✓。
            "delete_instance" => {
                let Some(id) = sarg(&p, &["id", "instanceId", "instance_id"]) else {
                    return err("缺少 id");
                };
                match delete_instance(id).await {
                    Ok(()) => {
                        // A2：实例集合变了 → 推 state://dirty，UI 重拉（见 app_state.rs）
                        crate::app_state::mark_instances_dirty();
                        ok(())
                    }
                    Err(e) => err(e),
                }
            }
            "kill_game" => match kill_game().await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            },
            "cancel_install" => {
                let Some(tid) = p
                    .get("taskId")
                    .or_else(|| p.get("task_id"))
                    .and_then(|x| x.as_u64())
                else {
                    return err("缺少 taskId");
                };
                match cancel_install(tid).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "update_settings" => {
                // patch 是 snake_case 的增量对象（与 Tauri 侧同形）
                let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
                match update_settings(patch).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "set_pins" => {
                let items = p
                    .get("items")
                    .cloned()
                    .unwrap_or_else(|| serde_json::Value::Array(Vec::new()));
                match serde_json::from_value::<Vec<PinItem>>(items) {
                    Ok(items) => match set_pins(items).await {
                        Ok(()) => ok(()),
                        Err(e) => err(e),
                    },
                    Err(e) => err(format!("items 解析失败：{e}")),
                }
            }
            "set_orientation" => {
                let Some(mode) = sarg(&p, &["mode"]) else {
                    return err("缺少 mode");
                };
                match set_orientation(mode).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "check_renderer_health" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match check_renderer_health(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // ── 生命周期 + 内容操作 + 文件读写（阶段 3 第四批）──────────
            "launch_game" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match launch_game(id, sarg(&p, &["world"]), sarg(&p, &["server"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "import_modpack" => {
                let Some(path) = sarg(&p, &["filePath", "file_path"]) else {
                    return err("缺少 filePath");
                };
                match import_modpack(path).await {
                    Ok(v) => {
                        // 导入整合包 = 新建（可能还带改名）实例
                        crate::app_state::mark_instances_dirty();
                        ok(v)
                    }
                    Err(e) => err(e),
                }
            }
            "install_game" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match install_game(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "uninstall_content" => {
                let (Some(id), Some(kind), Some(file)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["kind"]),
                    sarg(&p, &["filename"]),
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
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["kind"]),
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
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["kind"]),
                    sarg(&p, &["filename"]),
                ) else {
                    return err("缺少 instanceId / kind / filename");
                };
                let enabled = p
                    .get("enabled")
                    .and_then(|x| x.as_bool())
                    .unwrap_or(false);
                match toggle_content_enabled(id, kind, file, enabled).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "read_instance_file" => {
                let (Some(id), Some(rel)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["rel"]),
                ) else {
                    return err("缺少 instanceId / rel");
                };
                match read_instance_file(id, rel).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "write_instance_file" => {
                let (Some(id), Some(rel), Some(content)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["rel"]),
                    sarg(&p, &["content"]),
                ) else {
                    return err("缺少 instanceId / rel / content");
                };
                match write_instance_file(id, rel, content).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "delete_instance_path" => {
                let (Some(id), Some(rel)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["rel"]),
                ) else {
                    return err("缺少 instanceId / rel");
                };
                match delete_instance_path(id, rel).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "rename_instance_path" => {
                let (Some(id), Some(rel), Some(new_name)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["rel"]),
                    sarg(&p, &["newName", "new_name"]),
                ) else {
                    return err("缺少 instanceId / rel / newName");
                };
                match rename_instance_path(id, rel, new_name).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // ── 内容中心 / 版本 / 皮肤（阶段 3 第五批）──────────────────
            "get_version_manifest" => match get_version_manifest().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_version_info" => {
                let Some(vid) = sarg(&p, &["versionId", "version_id", "id"]) else {
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
                    (sarg(&p, &["provider"]), sarg(&p, &["projectId", "project_id"]))
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
                    (sarg(&p, &["provider"]), sarg(&p, &["projectId", "project_id"]))
                else {
                    return err("缺少 provider / projectId");
                };
                let mc = sarg(&p, &["mcVersion", "mc_version"]).unwrap_or_default();
                let loader = sarg(&p, &["loader"]).unwrap_or_default();
                match project_versions(provider, pid, mc, loader).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "project_dependencies" => {
                let (Some(provider), Some(pid)) =
                    (sarg(&p, &["provider"]), sarg(&p, &["projectId", "project_id"]))
                else {
                    return err("缺少 provider / projectId");
                };
                let vid = sarg(&p, &["versionId", "version_id"]);
                match project_dependencies(provider, pid, vid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // 返回 `Result<String, String>` 的命令要按 Tauri 的形状**再包一层字符串**，
            // 否则 Kotlin 侧的「去引号」逻辑与旧通道不一致（多行文本/URL）。
            "fetch_image_data_url" => {
                let Some(url) = sarg(&p, &["url"]) else {
                    return err("缺少 url");
                };
                match fetch_image_data_url(url).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "fetch_player_skin" => {
                let Some(name) = sarg(&p, &["username"]) else {
                    return err("缺少 username");
                };
                match fetch_player_skin(name).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "fetch_player_capes" => {
                let Some(uuid) = sarg(&p, &["accountUuid", "account_uuid"]) else {
                    return err("缺少 accountUuid");
                };
                match fetch_player_capes(uuid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // ── 搜索 / 杂项（阶段 3 第六批）────────────────────────────
            "browse" => {
                let num = |keys: &[&str]| -> Option<i64> {
                    keys.iter()
                        .find_map(|k| p.get(*k).and_then(|x| x.as_i64()))
                };
                let (Some(provider), Some(project_type)) =
                    (sarg(&p, &["provider"]), sarg(&p, &["projectType", "project_type"]))
                else {
                    return err("缺少 provider / projectType");
                };
                let query = sarg(&p, &["query"]).unwrap_or_default();
                let category = sarg(&p, &["category"]).unwrap_or_default();
                let page = num(&["page"]).unwrap_or(1) as i32;
                let game_version = sarg(&p, &["gameVersion", "game_version"]).unwrap_or_default();
                let loader = sarg(&p, &["loader"]).unwrap_or_default();
                let sort = sarg(&p, &["sort"]).unwrap_or_default();
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
            "list_mirrors" => ok(list_mirrors()),
            "log_debug" => {
                log_debug(sarg(&p, &["msg", "message"]).unwrap_or_default());
                ok(())
            }
            "get_pojav_prefs" => match get_pojav_prefs() {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "set_pojav_prefs" => {
                let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
                match set_pojav_prefs(patch) {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "read_clipboard" => match read_clipboard().await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            },
            "write_clipboard" => {
                let Some(text) = sarg(&p, &["text"]) else {
                    return err("缺少 text");
                };
                match write_clipboard(text).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "clear_cache" => match clear_cache().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            // 注意：这条是 async（返回 Option<String>），要 await
            "detect_system_proxy" => ok(detect_system_proxy().await),
            // ── 插件系统（阶段 3 第七批）───────────────────────────────
            "get_plugin_setup_status" => match get_plugin_setup_status().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "install_recommended_plugins" => match install_recommended_plugins().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "dismiss_plugin_setup" => match dismiss_plugin_setup().await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            },
            "refresh_plugin_manifest" => match refresh_plugin_manifest().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "install_plugin" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match install_plugin(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "install_plugin_from_file" => {
                let Some(path) = sarg(&p, &["path", "filePath", "file_path"]) else {
                    return err("缺少 path");
                };
                match install_plugin_from_file(path).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "uninstall_plugin" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match uninstall_plugin(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "set_plugin_enabled" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                let enabled = p.get("enabled").and_then(|x| x.as_bool()).unwrap_or(false);
                match set_plugin_enabled(id, enabled).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // 这两条返回 Result<String,_>，按 Tauri 形状再包一层字符串
            "get_plugin_manifest_url" => match get_plugin_manifest_url().await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            },
            "set_plugin_manifest_url" => {
                let Some(url) = sarg(&p, &["url"]) else {
                    return err("缺少 url");
                };
                match set_plugin_manifest_url(url).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            // ── 联机（FRP 隧道 + XTCP 房间，阶段 3 第八批）─────────────
            "frp_status" => ok(frp_status().await),
            "frp_set_api_base" => {
                let Some(base) = sarg(&p, &["apiBase", "api_base"]) else {
                    return err("缺少 apiBase");
                };
                match frp_set_api_base(base).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "frp_login_start" => match frp_login_start().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "frp_login_poll" => {
                let Some(session) = sarg(&p, &["session"]) else {
                    return err("缺少 session");
                };
                match frp_login_poll(session).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "frp_logout" => match frp_logout().await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            },
            "frp_user_info" => match frp_user_info().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "frp_tunnels" => match frp_tunnels().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "frp_start_tunnel" => {
                let Some(tid) = iarg(&p, &["tunnelId", "tunnel_id", "id"]) else {
                    return err("缺少 tunnelId");
                };
                match frp_start_tunnel(tid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // 同步 fn
            "frp_stop_tunnel" => {
                let Some(tid) = iarg(&p, &["tunnelId", "tunnel_id", "id"]) else {
                    return err("缺少 tunnelId");
                };
                match frp_stop_tunnel(tid) {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "frp_probe" => match frp_probe().await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            },
            "frp_room_nodes" => match frp_room_nodes().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "frp_room_list" => {
                let page = iarg(&p, &["page"]).unwrap_or(1);
                match frp_room_list(page).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "frp_room_detail" => {
                let Some(rid) = iarg(&p, &["roomId", "room_id", "id"]) else {
                    return err("缺少 roomId");
                };
                match frp_room_detail(rid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "frp_room_create" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                let Some(node_id) = iarg(&p, &["nodeId", "node_id"]) else {
                    return err("缺少 nodeId");
                };
                let Some(local_port) = iarg(&p, &["localPort", "local_port"]) else {
                    return err("缺少 localPort");
                };
                match frp_room_create(name, node_id, local_port, sarg(&p, &["password"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "frp_room_join" => {
                let Some(rid) = iarg(&p, &["roomId", "room_id", "id"]) else {
                    return err("缺少 roomId");
                };
                match frp_room_join(rid, sarg(&p, &["password"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "frp_room_leave" | "frp_room_close" | "frp_room_host_start"
            | "frp_room_visitor_start" | "frp_room_stop" => {
                let Some(rid) = iarg(&p, &["roomId", "room_id", "id"]) else {
                    return err("缺少 roomId");
                };
                let r = match cmd {
                    "frp_room_leave" => frp_room_leave(rid).await,
                    "frp_room_close" => frp_room_close(rid).await,
                    "frp_room_host_start" => frp_room_host_start(rid).await,
                    "frp_room_visitor_start" => frp_room_visitor_start(rid).await,
                    _ => frp_room_stop(rid).await,
                };
                match r {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // ── 账号 / 皮肤 / 服务端（阶段 3 第九批）────────────────────
            "add_account" => {
                let Some(username) = sarg(&p, &["username"]) else {
                    return err("缺少 username");
                };
                match add_account(
                    username,
                    sarg(&p, &["accountType", "account_type"]),
                    sarg(&p, &["refreshToken", "refresh_token"]),
                )
                .await
                {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "remove_account" => {
                let Some(uuid) = sarg(&p, &["uuid"]) else {
                    return err("缺少 uuid");
                };
                match remove_account(uuid).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "select_account" => {
                let Some(uuid) = sarg(&p, &["uuid"]) else {
                    return err("缺少 uuid");
                };
                match select_account(uuid).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "login_ms_start" => match login_ms_start().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "login_ms_poll" => match login_ms_poll().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            // 返回字符串的命令按 Tauri 形状再包一层
            "read_skin_data_url" => {
                let Some(fname) = sarg(&p, &["filename"]) else {
                    return err("缺少 filename");
                };
                match read_skin_data_url(fname).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "save_skin_from_data" => {
                let (Some(name), Some(data)) = (sarg(&p, &["name"]), sarg(&p, &["data"])) else {
                    return err("缺少 name / data");
                };
                match save_skin_from_data(name, data).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "download_skin_from_url" => {
                let (Some(name), Some(url)) = (sarg(&p, &["name"]), sarg(&p, &["url"])) else {
                    return err("缺少 name / url");
                };
                match download_skin_from_url(name, url).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "delete_skin" => {
                let Some(fname) = sarg(&p, &["filename"]) else {
                    return err("缺少 filename");
                };
                match delete_skin(fname).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "apply_skin_to_account" => {
                let (Some(uuid), Some(data), Some(variant)) = (
                    sarg(&p, &["accountUuid", "account_uuid"]),
                    sarg(&p, &["skinData", "skin_data"]),
                    sarg(&p, &["variant"]),
                ) else {
                    return err("缺少 accountUuid / skinData / variant");
                };
                match apply_skin_to_account(uuid, data, variant).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "apply_cape_to_account" => {
                let Some(uuid) = sarg(&p, &["accountUuid", "account_uuid"]) else {
                    return err("缺少 accountUuid");
                };
                match apply_cape_to_account(uuid, sarg(&p, &["capeId", "cape_id"])).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "apply_skin_offline" => {
                let (Some(data), Some(variant), Some(uuid)) = (
                    sarg(&p, &["skinData", "skin_data"]),
                    sarg(&p, &["variant"]),
                    sarg(&p, &["uuid"]),
                ) else {
                    return err("缺少 skinData / variant / uuid");
                };
                match apply_skin_offline(data, variant, uuid).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "get_offline_skin" => {
                let Some(uuid) = sarg(&p, &["uuid"]) else {
                    return err("缺少 uuid");
                };
                match get_offline_skin(uuid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "install_server_content" => {
                let (Some(sid), Some(provider), Some(pid), Some(vid), Some(kind)) = (
                    sarg(&p, &["serverId", "server_id"]),
                    sarg(&p, &["provider"]),
                    sarg(&p, &["projectId", "project_id"]),
                    sarg(&p, &["versionId", "version_id"]),
                    sarg(&p, &["kind"]),
                ) else {
                    return err("缺少 serverId / provider / projectId / versionId / kind");
                };
                match install_server_content(sid, provider, pid, vid, kind).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "install_server_plugin" => {
                let (Some(sid), Some(slug), Some(pname)) = (
                    sarg(&p, &["serverId", "server_id"]),
                    sarg(&p, &["slug"]),
                    sarg(&p, &["projectName", "project_name"]),
                ) else {
                    return err("缺少 serverId / slug / projectName");
                };
                match install_server_plugin(sid, slug, pname).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "ping_mc_server" => {
                let Some(addr) = sarg(&p, &["address"]) else {
                    return err("缺少 address");
                };
                ok(ping_mc_server(addr).await)
            }
            // ── 存档备份 / 导出 / 更新 / 翻译 / 杂项（阶段 3 第十批）────
            "get_download_progress" => {
                let Some(tid) = sarg(&p, &["taskId", "task_id"]) else {
                    return err("缺少 taskId");
                };
                match get_download_progress(tid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "plan_modpack_export" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match plan_modpack_export(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "export_modpack" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                let options = p
                    .get("options")
                    .cloned()
                    .unwrap_or_else(|| serde_json::json!({}));
                match serde_json::from_value::<crate::modpack::ExportOptions>(options) {
                    Ok(opts) => match export_modpack(id, opts).await {
                        Ok(v) => ok(v),
                        Err(e) => err(e),
                    },
                    Err(e) => err(format!("options 解析失败：{e}")),
                }
            }
            "translate_mod_descriptions" => {
                let Some(provider) = sarg(&p, &["provider"]) else {
                    return err("缺少 provider");
                };
                match translate_mod_descriptions(provider, svec(&p, &["slugs"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "translate_project_body" => {
                let (Some(provider), Some(slug)) = (sarg(&p, &["provider"]), sarg(&p, &["slug"]))
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
                let Some(mc) = sarg(&p, &["mcVersion", "mc_version"]) else {
                    return err("缺少 mcVersion");
                };
                match estimate_download(mc).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "estimate_import" => {
                let Some(source) = sarg(&p, &["source"]) else {
                    return err("缺少 source");
                };
                match estimate_import(source, svec(&p, &["rawIds", "raw_ids"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "analyze_crash_log" => {
                let (Some(id), Some(fname)) =
                    (sarg(&p, &["id"]), sarg(&p, &["filename"]))
                else {
                    return err("缺少 id / filename");
                };
                match analyze_crash_log(id, fname).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "get_crash_report_content" => {
                let (Some(id), Some(fname)) =
                    (sarg(&p, &["id"]), sarg(&p, &["filename"]))
                else {
                    return err("缺少 id / filename");
                };
                match get_crash_report_content(id, fname).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "create_world_backup" => {
                let (Some(id), Some(world)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["world"]),
                ) else {
                    return err("缺少 instanceId / world");
                };
                match create_world_backup(id, world).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "restore_world_backup" => {
                let (Some(id), Some(world), Some(fname)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["world"]),
                    sarg(&p, &["filename"]),
                ) else {
                    return err("缺少 instanceId / world / filename");
                };
                match restore_world_backup(id, world, fname).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "delete_world_backup" => {
                let (Some(id), Some(world), Some(fname)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["world"]),
                    sarg(&p, &["filename"]),
                ) else {
                    return err("缺少 instanceId / world / filename");
                };
                match delete_world_backup(id, world, fname).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "check_updates" => {
                let (Some(id), Some(kind)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["kind"]),
                ) else {
                    return err("缺少 instanceId / kind");
                };
                match check_updates(id, kind).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "apply_update" => {
                let (Some(id), Some(kind), Some(old)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["kind"]),
                    sarg(&p, &["oldFilename", "old_filename"]),
                ) else {
                    return err("缺少 instanceId / kind / oldFilename");
                };
                let (Some(provider), Some(pid), Some(nvid)) = (
                    sarg(&p, &["provider"]),
                    sarg(&p, &["projectId", "project_id"]),
                    sarg(&p, &["newVersionId", "new_version_id"]),
                ) else {
                    return err("缺少 provider / projectId / newVersionId");
                };
                match apply_update(id, kind, old, provider, pid, nvid).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "import_local_file" => {
                let (Some(id), Some(kind), Some(src)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["kind"]),
                    sarg(&p, &["sourcePath", "source_path"]),
                ) else {
                    return err("缺少 instanceId / kind / sourcePath");
                };
                match import_local_file(id, kind, src).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "save_text_file" => {
                let (Some(path), Some(content)) = (sarg(&p, &["path"]), sarg(&p, &["content"]))
                else {
                    return err("缺少 path / content");
                };
                match save_text_file(path, content).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "extract_game_icons" => {
                match extract_game_icons(sarg(&p, &["instanceId", "instance_id"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "resolve_picked_path" => {
                let Some(path) = sarg(&p, &["path"]) else {
                    return err("缺少 path");
                };
                match resolve_picked_path(path).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "export_instance_logs" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match export_instance_logs(id).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            // ── 收尾批 A（阶段 3 第十一批）──────────────────────────────
            "install_version" => {
                let Some(vid) = sarg(&p, &["versionId", "version_id", "id"]) else {
                    return err("缺少 versionId");
                };
                match install_version(vid).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "update_instance" => {
                let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
                match update_instance(patch).await {
                    Ok(v) => {
                        // A2：改名 / 换图标 / 改配置都会影响列表卡片
                        crate::app_state::mark_instances_dirty();
                        ok(v)
                    }
                    Err(e) => err(e),
                }
            }
            "get_game_status" => match get_game_status().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "download_file" => {
                let (Some(url), Some(dest)) = (sarg(&p, &["url"]), sarg(&p, &["dest"])) else {
                    return err("缺少 url / dest");
                };
                match download_file(url, dest, sarg(&p, &["sha1"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "cancel_download" => {
                let Some(tid) = sarg(&p, &["taskId", "task_id"]) else {
                    return err("缺少 taskId");
                };
                match cancel_download(tid).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "hangar_search_plugins" => {
                let Some(query) = sarg(&p, &["query"]) else {
                    return err("缺少 query");
                };
                let page = iarg(&p, &["page"]).unwrap_or(0) as u32;
                let size = iarg(&p, &["pageSize", "page_size"]).unwrap_or(20) as u32;
                match hangar_search_plugins(query, Some(page), Some(size)).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_server_plugins" => {
                let Some(sid) = sarg(&p, &["serverId", "server_id"]) else {
                    return err("缺少 serverId");
                };
                match list_server_plugins(sid) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "delete_server_plugin" => {
                let (Some(sid), Some(fname)) = (
                    sarg(&p, &["serverId", "server_id"]),
                    sarg(&p, &["fileName", "file_name", "filename"]),
                ) else {
                    return err("缺少 serverId / fileName");
                };
                match delete_server_plugin(sid, fname) {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "frp_running" => ok(frp_running()),
            "frp_tunnel_log" => {
                let Some(tid) = iarg(&p, &["tunnelId", "tunnel_id", "id"]) else {
                    return err("缺少 tunnelId");
                };
                match frp_tunnel_log(tid) {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "install_mod" => {
                let (Some(id), Some(mid), Some(url)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["modId", "mod_id"]),
                    sarg(&p, &["modUrl", "mod_url"]),
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
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["modId", "mod_id"]),
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
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["provider"]),
                    sarg(&p, &["projectId", "project_id"]),
                    sarg(&p, &["versionId", "version_id"]),
                    sarg(&p, &["kind"]),
                ) else {
                    return err("缺少 instanceId / provider / projectId / versionId / kind");
                };
                match install_content(id, provider, pid, vid, kind).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "report_translation_stale" => {
                let (Some(provider), Some(slug)) = (sarg(&p, &["provider"]), sarg(&p, &["slug"]))
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
                    sarg(&p, &["provider"]),
                    sarg(&p, &["slug"]),
                    sarg(&p, &["issueType", "issue_type"]),
                ) else {
                    return err("缺少 provider / slug / issueType");
                };
                match report_translation_quality(
                    provider,
                    slug,
                    issue,
                    sarg(&p, &["userSuggestion", "user_suggestion"]),
                    sarg(&p, &["userComment", "user_comment"]),
                )
                .await
                {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            // ── 收尾批 B（阶段 3 第十一批）──────────────────────────────
            "clear_translation_cache" => {
                match clear_translation_cache(sarg(&p, &["service"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "test_translate_api" => {
                let (Some(base), Some(key), Some(model)) = (
                    sarg(&p, &["base"]),
                    sarg(&p, &["key"]),
                    sarg(&p, &["model"]),
                ) else {
                    return err("缺少 base / key / model");
                };
                match test_translate_api(base, key, model).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "create_instance_group" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                match create_instance_group(name, sarg(&p, &["color"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "rename_instance_group" => {
                let (Some(id), Some(name)) = (sarg(&p, &["id"]), sarg(&p, &["name"])) else {
                    return err("缺少 id / name");
                };
                match rename_instance_group(id, name, sarg(&p, &["color"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "delete_instance_group" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match delete_instance_group(id).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "reorder_instance_groups" => {
                match reorder_instance_groups(svec(&p, &["ids"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "test_mirror" => {
                let base = sarg(&p, &["base"]).unwrap_or_default();
                match test_mirror(base).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "test_proxy" => {
                let mode = sarg(&p, &["proxyMode", "proxy_mode"]).unwrap_or_default();
                match test_proxy(mode, sarg(&p, &["proxy"])).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "auto_detect_memory" => match auto_detect_memory() {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "is_game_running" => ok(is_game_running()),
            "refresh_storage_stats" => match refresh_storage_stats().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "list_instance_folders" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match list_instance_folders(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "create_instance_entry" => {
                let (Some(id), Some(rel)) = (
                    sarg(&p, &["instanceId", "instance_id", "id"]),
                    sarg(&p, &["rel"]),
                ) else {
                    return err("缺少 instanceId / rel");
                };
                let is_dir = p.get("isDir").or_else(|| p.get("is_dir"))
                    .and_then(|x| x.as_bool())
                    .unwrap_or(false);
                match create_instance_entry(id, rel, is_dir).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "import_instance_image" => {
                let Some(src) = sarg(&p, &["sourcePath", "source_path"]) else {
                    return err("缺少 sourcePath");
                };
                match import_instance_image(src).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "import_background_image" => {
                let Some(src) = sarg(&p, &["sourcePath", "source_path"]) else {
                    return err("缺少 sourcePath");
                };
                match import_background_image(src).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "check_renderer_health_latest" => match check_renderer_health_latest().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            // ── 最后一条：新建实例（14 个参数，含 renderer）─────────────
            // ⚠️ 参数必须**全部**透传：Tauri 按签名反序列化，漏掉 `renderer`
            // 会让新实例 renderer=null → 自动判给 GL4ES → 1.21.x 一启动就崩
            //（`Can't map buffer, opengl error 0`，见上面那个函数的注释）。
            "create_instance" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                let Some(mc) = sarg(&p, &["mcVersion", "mc_version"]) else {
                    return err("缺少 mcVersion");
                };
                let loader = sarg(&p, &["loader"]).unwrap_or_default();
                let resolution: Option<Vec<i64>> = p
                    .get("resolution")
                    .and_then(|x| x.as_array())
                    .map(|a| a.iter().filter_map(|v| v.as_i64()).collect());
                match create_instance(
                    name,
                    mc,
                    loader,
                    sarg(&p, &["loaderVersion", "loader_version"]),
                    sarg(&p, &["javaDir", "java_dir"]),
                    sarg(&p, &["javaArgs", "java_args"]),
                    sarg(&p, &["gameArgs", "game_args"]),
                    resolution,
                    iarg(&p, &["maxMemoryMb", "max_memory_mb"]),
                    sarg(&p, &["memoryMode", "memory_mode"]),
                    sarg(&p, &["accountId", "account_id"]),
                    sarg(&p, &["icon"]),
                    sarg(&p, &["group"]),
                    sarg(&p, &["renderer"]),
                )
                .await
                {
                    Ok(v) => {
                        crate::app_state::mark_instances_dirty();
                        ok(v)
                    }
                    Err(e) => err(e),
                }
            }
            // ── 开服（hosted server）+ rcon（阶段 3 第九批）──────────────
            // 注意：这些命令定义在 `servers` 模块（lib.rs 里注册为 `servers::xxx`），
            // 不在本文件里，所以要写全路径 `crate::servers::`。
            "list_hosted_servers" => ok(crate::servers::list_hosted_servers()),
            "get_hosted_server" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::get_hosted_server(id) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "create_hosted_server" => {
                let (Some(name), Some(core), Some(mc)) = (
                    sarg(&p, &["name"]),
                    sarg(&p, &["core"]),
                    sarg(&p, &["mcVersion", "mc_version"]),
                ) else {
                    return err("缺少 name / core / mcVersion");
                };
                match crate::servers::create_hosted_server(name, core, mc) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "update_hosted_server" => {
                let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
                match crate::servers::update_hosted_server(patch) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "delete_hosted_server" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::delete_hosted_server(id) {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "suggest_server_memory" => ok(crate::servers::suggest_server_memory()),
            "list_paper_versions" => match crate::servers::list_paper_versions().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            // 下面几条返回 Result<String,_>，按 Tauri 形状再包一层字符串 ✓
            "install_hosted_server_core" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::install_hosted_server_core(id).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "start_hosted_server" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::start_hosted_server(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "stop_hosted_server" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::stop_hosted_server(id).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "is_hosted_server_running" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(crate::servers::is_hosted_server_running(id))
            }
            "read_hosted_server_log" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(crate::servers::read_hosted_server_log(id))
            }
            "server_console_command" => {
                let (Some(id), Some(command)) = (sarg(&p, &["id"]), sarg(&p, &["command"]))
                else {
                    return err("缺少 id / command");
                };
                match crate::servers::server_console_command(id, command) {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "hosted_server_runtime" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(crate::servers::hosted_server_runtime(id))
            }
            "hosted_server_log" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                let lines = iarg(&p, &["lines"]).map(|n| n as usize);
                match crate::servers::hosted_server_log(id, lines) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "hosted_server_address" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(crate::servers::hosted_server_address(id))
            }
            "hosted_server_addresses" => ok(crate::servers::hosted_server_addresses()),
            "device_available_memory_mb" => ok(crate::servers::device_available_memory_mb()),
            "battery_unrestricted" => ok(crate::servers::battery_unrestricted()),
            // ── 开服属性 / 目录 + 控制布局（阶段 3 第十批）──────────────
            "get_hosted_server_properties" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::get_hosted_server_properties(id) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "set_hosted_server_properties" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                let patch = p.get("patch").cloned().unwrap_or_else(|| p.clone());
                match crate::servers::set_hosted_server_properties(id, patch) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "hosted_server_core_installed" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(crate::servers::hosted_server_core_installed(id))
            }
            "open_hosted_server_directory" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::servers::open_hosted_server_directory(id) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // 控制布局（全部 async）
            "get_control_buttons" => match crate::controls::get_control_buttons().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "set_control_button_passthru" => {
                let (Some(index), Some(enabled)) = (
                    iarg(&p, &["index"]),
                    p.get("enabled").and_then(|x| x.as_bool()),
                ) else {
                    return err("缺少 index / enabled");
                };
                match crate::controls::set_control_button_passthru(index as usize, enabled).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_control_layouts" => match crate::controls::list_control_layouts().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "set_current_control_layout" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                match crate::controls::set_current_control_layout(name).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "duplicate_control_layout" => {
                let (Some(name), Some(new_name)) =
                    (sarg(&p, &["name"]), sarg(&p, &["newName", "new_name"]))
                else {
                    return err("缺少 name / newName");
                };
                match crate::controls::duplicate_control_layout(name, new_name).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "rename_control_layout" => {
                let (Some(name), Some(new_name)) =
                    (sarg(&p, &["name"]), sarg(&p, &["newName", "new_name"]))
                else {
                    return err("缺少 name / newName");
                };
                match crate::controls::rename_control_layout(name, new_name).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "delete_control_layout" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                match crate::controls::delete_control_layout(name).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "open_control_layout_editor" => {
                let preview = p.get("preview").and_then(|x| x.as_bool()).unwrap_or(false);
                match crate::controls::open_control_layout_editor(
                    sarg(&p, &["layout"]),
                    preview,
                    sarg(&p, &["saveAs", "save_as"]),
                )
                .await
                {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "export_control_layout" => {
                let Some(name) = sarg(&p, &["name"]) else {
                    return err("缺少 name");
                };
                match crate::controls::export_control_layout(name).await {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            "pick_control_layout" => match crate::controls::pick_control_layout().await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            },
            "take_control_import" => match crate::controls::take_control_import().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "import_control_layout_by_path" => {
                let Some(path) = sarg(&p, &["path", "filePath", "file_path"]) else {
                    return err("缺少 path");
                };
                match crate::controls::import_control_layout_by_path(path).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // ── 收尾全覆盖（阶段 3 第十一批）──────────────────────────
            // 游戏目录 / 文件访问
            "get_game_dir_state" => match crate::game_dir::get_game_dir_state().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "get_game_dir_options" => ok(crate::game_dir::get_game_dir_options()),
            "has_all_files_access" => ok(crate::game_dir::has_all_files_access()),
            "request_all_files_access" => {
                crate::game_dir::request_all_files_access();
                ok(())
            }
            "pick_game_dir" => match crate::game_dir::pick_game_dir() {
                Ok(()) => ok(()),
                Err(e) => err(e),
            },
            "take_picked_game_dir" => match crate::game_dir::take_picked_game_dir().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "set_game_dir" => {
                let migrate = p.get("migrate").and_then(|x| x.as_bool()).unwrap_or(false);
                match crate::game_dir::set_game_dir(sarg(&p, &["root"]), migrate).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "set_log_zoom_capture" => {
                let on = p.get("on").and_then(|x| x.as_bool()).unwrap_or(false);
                crate::android_bridge::set_log_zoom_capture(on);
                ok(())
            }
            // 实例文件体检 / 清理
            "check_instance_files" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match crate::instance_files::check_instance_files(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "repair_instance_files" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match crate::instance_files::repair_instance_files(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "shared_game_usage" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match crate::instance_files::shared_game_usage(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "plan_instance_cleanup" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match crate::instance_files::plan_instance_cleanup(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "cleanup_instance_shared" => {
                let Some(id) = sarg(&p, &["instanceId", "instance_id", "id"]) else {
                    return err("缺少 instanceId");
                };
                match crate::instance_files::cleanup_instance_shared(id).await {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            // 服务端文件
            "list_hosted_server_dir" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::server_files::list_hosted_server_dir(
                    id,
                    sarg(&p, &["rel"]).unwrap_or_default(),
                ) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "read_hosted_server_file" => {
                let (Some(id), Some(rel)) = (sarg(&p, &["id"]), sarg(&p, &["rel"])) else {
                    return err("缺少 id / rel");
                };
                match crate::server_files::read_hosted_server_file(id, rel) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "write_hosted_server_file" => {
                let (Some(id), Some(rel), Some(content)) = (
                    sarg(&p, &["id"]),
                    sarg(&p, &["rel"]),
                    sarg(&p, &["content"]),
                ) else {
                    return err("缺少 id / rel / content");
                };
                match crate::server_files::write_hosted_server_file(id, rel, content) {
                    Ok(v) => ok(v),
                    Err(e) => err(e),
                }
            }
            "list_hosted_server_config_files" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(crate::server_files::list_hosted_server_config_files(id))
            }
            "open_hosted_server_folder" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                match crate::server_files::open_hosted_server_folder(id, sarg(&p, &["sub"])) {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            "inspect_server_core_manifest" => {
                let Some(id) = sarg(&p, &["id"]) else {
                    return err("缺少 id");
                };
                ok(serde_json::Value::String(
                    crate::server_process::inspect_server_core_manifest(id),
                ))
            }
            "request_battery_unrestricted" => {
                crate::servers::request_battery_unrestricted();
                ok(())
            }
            "device_thermal_status" => ok(crate::servers::device_thermal_status()),
            // 陶瓦联机（terracotta）
            "terracotta_vpn_granted" => match crate::terracotta::terracotta_vpn_granted().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "terracotta_request_vpn" => match crate::terracotta::terracotta_request_vpn().await {
                Ok(()) => ok(()),
                Err(e) => err(e),
            },
            "terracotta_ping" => match crate::terracotta::terracotta_ping().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "terracotta_request" => {
                let Some(path) = sarg(&p, &["path"]) else {
                    return err("缺少 path");
                };
                match crate::terracotta::terracotta_request(path).await {
                    Ok(v) => ok(serde_json::Value::String(v)),
                    Err(e) => err(e),
                }
            }
            // 自更新（这三条是 Tauri 时代留下的，需要 AppHandle 才能拉 release 信息）
            "check_for_update" => match crate::updater::check_for_update().await {
                Ok(v) => ok(v),
                Err(e) => err(e),
            },
            "download_update" => match crate::updater::download_update(sarg(&p, &["version"])).await {
                Ok(v) => ok(serde_json::Value::String(v)),
                Err(e) => err(e),
            },
            "install_update" => {
                let Some(path) = sarg(&p, &["path"]) else {
                    return err("缺少 path");
                };
                match crate::updater::install_update(path) {
                    Ok(()) => ok(()),
                    Err(e) => err(e),
                }
            }
            other => err(format!("native_invoke 未实现的命令：{other}")),
        }
    })
}
pub async fn list_instances() -> Result<Vec<MinecraftProfile>, String> {
    instances::list_instances().await.map_err(|e| e.to_string())
}
pub async fn get_instance(id: String) -> Result<MinecraftProfile, String> {
    instances::get_instance(&id).await.map_err(|e| e.to_string())
}
pub async fn update_instance(patch: Value) -> Result<MinecraftProfile, String> {
    instances::update_instance(patch).await.map_err(|e| e.to_string())
}

// ==================== Account Commands ====================
pub async fn list_accounts() -> Result<Vec<Account>, String> {
    accounts::list_accounts().await.map_err(|e| e.to_string())
}
pub async fn add_account(username: String, account_type: Option<String>, refresh_token: Option<String>) -> Result<Account, String> {
    accounts::add_account(&username, account_type.as_deref().unwrap_or("offline"), refresh_token).await.map_err(|e| e.to_string())
}
pub async fn remove_account(uuid: String) -> Result<(), String> {
    accounts::remove_account(&uuid).await.map_err(|e| e.to_string())
}
pub async fn select_account(uuid: String) -> Result<(), String> {
    accounts::select_account(&uuid).await.map_err(|e| e.to_string())
}

// ==================== Launch Commands ====================

/// 解析本次启动要使用的账号：
/// 实例指定的账号 → 全局当前账号 → 账号列表里的第一个 → 无账号（离线 Steve）。
async fn resolve_launch_account(instance_id: &str) -> Option<Account> {
    let accounts = accounts::list_accounts().await.unwrap_or_default();
    if accounts.is_empty() {
        return None;
    }
    let instance_account = instances::get_instance(instance_id)
        .await
        .ok()
        .and_then(|i| i.account_id);
    if let Some(id) = instance_account {
        if let Some(acc) = accounts.iter().find(|a| a.uuid == id) {
            return Some(acc.clone());
        }
    }
    let selected = settings::get_settings()
        .await
        .ok()
        .and_then(|s| s.selected_account);
    if let Some(id) = selected {
        if let Some(acc) = accounts.iter().find(|a| a.uuid == id) {
            return Some(acc.clone());
        }
    }
    accounts.into_iter().next()
}
pub async fn launch_game(instance_id: String, world: Option<String>, server: Option<String>) -> Result<GameStatus, String> {
    // 安卓端暂不支持「快速进入世界/服务器」，参数保留以兼容前端签名
    let _ = (world, server);

    // 安卓：游戏必须跑在带 SurfaceView 的 GameActivity 里（GL4ES 需要真正的 Surface）。
    // 这里只负责把界面拉起来；真正的 JVM 启动由 GameActivity → TauriBridge.launchGame 触发。
    #[cfg(target_os = "android")]
    {
        // 拉起 GameActivity 之前先确认游戏文件已安装。
        // 这里**必须**查：安卓端的 JVM 启动发生在 Activity 里，
        // 校验放在 launch::launch_game 里已经太晚 —— 用户会看到
        // 「黑屏一下然后弹回启动器」，完全不知道是没装游戏文件。
        if !crate::version::is_instance_installed(&instance_id).await {
            return Err("INSTANCE_NOT_INSTALLED".to_string());
        }

        let account_uuid = resolve_launch_account(&instance_id)
            .await
            .map(|a| a.uuid)
            .unwrap_or_default();
        crate::android_bridge::start_game_activity(&instance_id, &account_uuid)?;
        return Ok(launch::get_game_status());
    }

    #[cfg(not(target_os = "android"))]
    {
        let account = resolve_launch_account(&instance_id).await;
        launch::launch_game(&instance_id, account.as_ref())
            .await
            .map_err(|e| e.to_string())
    }
}
pub async fn kill_game() -> Result<(), String> {
    launch::kill_game().await.map_err(|e| e.to_string())
}
pub async fn get_game_status() -> Result<GameStatus, String> {
    Ok(launch::get_game_status())
}

// ==================== Download Commands ====================
pub async fn download_file(url: String, dest: String, sha1: Option<String>) -> Result<DownloadProgress, String> {
    download::download_file(&url, &dest, sha1).await.map_err(|e| e.to_string())
}
pub async fn cancel_download(task_id: String) -> Result<(), String> {
    download::cancel_download(&task_id).await.map_err(|e| e.to_string())
}

/// 读取系统剪贴板（代码编辑器的「粘贴」用）。
///
/// WebView 侧拿不到 `clipboard-read` 权限、`execCommand("paste")` 又必然失败，
/// 所以这件事只能交给原生。
pub async fn read_clipboard() -> Result<String, String> {
    crate::android_bridge::read_clipboard()
}

/// 写入系统剪贴板（代码编辑器的「复制」用）。
///
/// WebView 连 `clipboard-write` 权限也没有：`navigator.clipboard.writeText()`
/// 实测抛 `NotAllowedError`，只能靠 `execCommand("copy")` 的兜底。
pub async fn write_clipboard(text: String) -> Result<(), String> {
    crate::android_bridge::write_clipboard(&text)
}

/// 取消一个安装 / 下载任务（「下载中心」的取消按钮）。
///
/// 与 `cancel_download` 的区别：那个按**单次文件下载**的内部 id（`dl_<uuid>`，
/// 前端根本拿不到）；这个按 `TaskCtx.task_id`，也就是前端 `TaskEntry.id`，
/// 会真的让整个任务停下（包括正在传输的大文件）。
pub async fn cancel_install(task_id: u64) -> Result<(), String> {
    if crate::progress::cancel_install(task_id) {
        Ok(())
    } else {
        Err("任务已结束或不存在".into())
    }
}
pub async fn get_download_progress(task_id: String) -> Result<DownloadProgress, String> {
    download::get_progress(&task_id).await.map_err(|e| e.to_string())
}

// ==================== Settings Commands ====================
pub async fn get_settings() -> Result<Settings, String> {
    settings::get_settings().await.map_err(|e| e.to_string())
}
pub async fn update_settings(patch: Value) -> Result<Settings, String> {
    settings::update_settings_patch(patch).await.map_err(|e| e.to_string())?;
    settings::get_settings().await.map_err(|e| e.to_string())
}

// ==================== Plugin Commands ====================
//
// 统一约定：每个命令都返回**最新的插件列表**，前端拿到直接替换即可 ——
// 少一次「装完再查一遍」的往返，也不会出现「安装成功但列表还是旧的」这种状态错位。

async fn plugin_data_dir() -> Result<String, String> {
    settings::get_data_dir().await.map_err(|e| e.to_string())
}
pub async fn get_plugins() -> Result<Vec<crate::plugin::PluginInfo>, String> {
    Ok(crate::plugin::list(&plugin_data_dir().await?))
}

/// 首启准备状态：还缺哪些渲染器/驱动/组件、一共要下多少。
pub async fn get_plugin_setup_status() -> Result<crate::plugin::SetupStatus, String> {
    Ok(crate::plugin::setup_status(&plugin_data_dir().await?).await)
}

/// 补齐首启要装的插件（逐个装，逐个报 `plugin://progress`）。
pub async fn install_recommended_plugins() -> Result<crate::plugin::SetupResult, String> {
    crate::plugin::install_recommended(&plugin_data_dir().await?).await
}

/// 用户点了「稍后」：记下来，别再每次启动都弹。
pub async fn dismiss_plugin_setup() -> Result<(), String> {
    crate::plugin::set_setup_dismissed(&plugin_data_dir().await?, true)
}

/// 强制联网刷新清单（用户点「刷新」时用；平时列表只读缓存，避免进设置就卡一下）。
pub async fn refresh_plugin_manifest() -> Result<Vec<crate::plugin::PluginInfo>, String> {
    let data_dir = plugin_data_dir().await?;
    crate::plugin::fetch_manifest(&data_dir, true).await?;
    Ok(crate::plugin::list(&data_dir))
}
pub async fn install_plugin(id: String) -> Result<Vec<crate::plugin::PluginInfo>, String> {
    let data_dir = plugin_data_dir().await?;
    crate::plugin::install(&data_dir, &id).await?;
    Ok(crate::plugin::list(&data_dir))
}

/// 从本地 zip 安装（离线/内网/测试用；包内需带 `plugin.json`）。
pub async fn install_plugin_from_file(path: String) -> Result<Vec<crate::plugin::PluginInfo>, String> {
    let data_dir = plugin_data_dir().await?;
    crate::plugin::install_from_zip(&data_dir, &path).await?;
    Ok(crate::plugin::list(&data_dir))
}
pub async fn uninstall_plugin(id: String) -> Result<Vec<crate::plugin::PluginInfo>, String> {
    let data_dir = plugin_data_dir().await?;
    crate::plugin::uninstall(&data_dir, &id)?;
    Ok(crate::plugin::list(&data_dir))
}
pub async fn set_plugin_enabled(
    id: String,
    enabled: bool,
) -> Result<Vec<crate::plugin::PluginInfo>, String> {
    let data_dir = plugin_data_dir().await?;
    crate::plugin::set_enabled(&data_dir, &id, enabled)?;
    Ok(crate::plugin::list(&data_dir))
}
pub async fn get_plugin_manifest_url() -> Result<String, String> {
    Ok(crate::plugin::manifest_url(&plugin_data_dir().await?))
}
pub async fn set_plugin_manifest_url(url: String) -> Result<String, String> {
    let data_dir = plugin_data_dir().await?;
    crate::plugin::set_manifest_url(&data_dir, &url)?;
    Ok(crate::plugin::manifest_url(&data_dir))
}

// ==================== Modpack Commands ====================
pub async fn import_modpack(file_path: String) -> Result<modpack::ModpackImportResult, String> {
    modpack::import_modpack(&file_path).await.map_err(|e| e.to_string())
}

/// 导出前的体检（纯本地统计，不联网）
pub async fn plan_modpack_export(instance_id: String) -> Result<modpack::ExportPlan, String> {
    modpack::plan_modpack_export(&instance_id).await.map_err(|e| e.to_string())
}

/// 把实例导出成 `.mrpack` 并调起系统分享
pub async fn export_modpack(
    instance_id: String,
    options: modpack::ExportOptions,
) -> Result<modpack::ExportResult, String> {
    modpack::export_modpack(&instance_id, options).await.map_err(|e| e.to_string())
}

// ==================== Hangar（PaperMC 插件平台）Commands ====================
pub async fn hangar_search_plugins(
    query: String,
    page: Option<u32>,
    page_size: Option<u32>,
) -> Result<Vec<hangar::HangarPlugin>, String> {
    hangar::search(&query, page.unwrap_or(0), page_size.unwrap_or(20))
        .await
        .map_err(|e| e.to_string())
}

/// 把插件下到 `<服务器>/plugins/`。版本自动挑「声明支持该服务器 MC 版本」的最新版。
pub async fn install_server_plugin(
    server_id: String,
    slug: String,
    project_name: String,
) -> Result<Value, String> {
    hangar::install_plugin(&server_id, &slug, &project_name)
        .await
        .map_err(|e| e.to_string())
}
pub fn list_server_plugins(server_id: String) -> Result<Vec<Value>, String> {
    hangar::list_plugins(&server_id).map_err(|e| e.to_string())
}
pub fn delete_server_plugin(server_id: String, file_name: String) -> Result<(), String> {
    hangar::delete_plugin(&server_id, &file_name).map_err(|e| e.to_string())
}

// ==================== FRP 隧道（面板登录 / 隧道进程）====================

/// 面板状态：地址、是否已登录、用户名、frpc 是否随包就绪
pub async fn frp_status() -> Value {
    frp::status().await
}
pub async fn frp_set_api_base(api_base: String) -> Result<Value, String> {
    frp::set_api_base(api_base).await.map_err(|e| e.to_string())
}
pub async fn frp_login_start() -> Result<Value, String> {
    frp::login_start().await.map_err(|e| e.to_string())
}
pub async fn frp_login_poll(session: String) -> Result<Value, String> {
    frp::login_poll(session).await.map_err(|e| e.to_string())
}
pub async fn frp_logout() -> Result<(), String> {
    frp::logout().await.map_err(|e| e.to_string())
}
pub async fn frp_user_info() -> Result<Value, String> {
    frp::user_info().await.map_err(|e| e.to_string())
}
pub async fn frp_tunnels() -> Result<Vec<frp::TunnelInfo>, String> {
    frp::tunnels().await.map_err(|e| e.to_string())
}

/// 启动一条隧道（已经把配置写好、frpc 起来了才算成功）
pub async fn frp_start_tunnel(tunnel_id: i64) -> Result<Value, String> {
    frp::start_tunnel(tunnel_id).await.map_err(|e| e.to_string())
}
pub fn frp_stop_tunnel(tunnel_id: i64) -> Result<(), String> {
    frp::stop_tunnel(tunnel_id).map_err(|e| e.to_string())
}
pub fn frp_running() -> Vec<Value> {
    frp::running_list()
}
pub fn frp_tunnel_log(tunnel_id: i64) -> Result<String, String> {
    frp::tunnel_log(tunnel_id).map_err(|e| e.to_string())
}

/// 自检：执行随包 frpc 的 `--version`。同时验证「这台设备允许不允许执行 APK 里的
/// 原生库目录」—— 安卓 10+ 有 W^X 限制，这是整个功能的前提。
pub async fn frp_probe() -> Result<String, String> {
    frp::probe().await.map_err(|e| e.to_string())
}

// ── XTCP 联机房间（房主-访问者模式，双方都要跑 frpc）────────────────────

/// 建房可选的节点
pub async fn frp_room_nodes() -> Result<Vec<frp::RoomNode>, String> {
    frp::room_nodes().await.map_err(|e| e.to_string())
}
pub async fn frp_room_list(page: i64) -> Result<Value, String> {
    frp::room_list(page).await.map_err(|e| e.to_string())
}
pub async fn frp_room_detail(room_id: i64) -> Result<Value, String> {
    frp::room_detail(room_id).await.map_err(|e| e.to_string())
}

/// 建房（面板会返回房主端 frpc 配置与审核状态）
pub async fn frp_room_create(
    name: String,
    node_id: i64,
    local_port: i64,
    password: Option<String>,
) -> Result<Value, String> {
    frp::room_create(name, node_id, local_port, password)
        .await
        .map_err(|e| e.to_string())
}
pub async fn frp_room_join(room_id: i64, password: Option<String>) -> Result<Value, String> {
    frp::room_join(room_id, password)
        .await
        .map_err(|e| e.to_string())
}
pub async fn frp_room_leave(room_id: i64) -> Result<Value, String> {
    frp::room_leave(room_id).await.map_err(|e| e.to_string())
}
pub async fn frp_room_close(room_id: i64) -> Result<Value, String> {
    frp::room_close(room_id).await.map_err(|e| e.to_string())
}

/// 房主端：起 frpc 把本地端口经 XTCP 暴露出去
pub async fn frp_room_host_start(room_id: i64) -> Result<Value, String> {
    frp::room_host_start(room_id).await.map_err(|e| e.to_string())
}

/// 访问端：起 frpc 把房主的代理绑到本地端口（返回值里有 `connect`）
pub async fn frp_room_visitor_start(room_id: i64) -> Result<Value, String> {
    frp::room_visitor_start(room_id)
        .await
        .map_err(|e| e.to_string())
}
pub async fn frp_room_stop(room_id: i64) -> Result<Value, String> {
    frp::room_stop(room_id).await.map_err(|e| e.to_string())
}
pub async fn install_mod(instance_id: String, mod_id: String, mod_url: String) -> Result<InstalledContent, String> {
    modpack::install_mod(&instance_id, &mod_id, &mod_url, None).await.map_err(|e| e.to_string())
}
pub async fn uninstall_mod(instance_id: String, mod_id: String) -> Result<(), String> {
    modpack::uninstall_mod(&instance_id, &mod_id).await.map_err(|e| e.to_string())
}
pub async fn get_installed_mods(instance_id: String) -> Result<Vec<InstalledContent>, String> {
    modpack::get_installed_mods(&instance_id).await.map_err(|e| e.to_string())
}

// ==================== Browse Commands ====================
pub async fn browse(
    provider: String,
    query: String,
    project_type: String,
    category: String,
    page: i32,
    game_version: String,
    loader: String,
    sort: String,
    page_size: i32,
) -> Result<browse::BrowseResult, String> {
    browse::browse(&provider, &query, &project_type, &category, page, &game_version, &loader, &sort, page_size).await.map_err(|e| e.to_string())
}
pub async fn curseforge_categories() -> Result<Value, String> {
    let categories = browse::curseforge_categories().await.map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "categories": categories,
    }))
}
pub async fn project_info(provider: String, project_id: String) -> Result<browse::ProjectInfoResult, String> {
    browse::project_info(&provider, &project_id).await.map_err(|e| e.to_string())
}

// ==================== Translate Commands ====================
//
// 翻译配置存在 settings.json 里（translate_provider / translate_api_base 等），
// 后端每次调用现读，改设置不必重启；成功结果按服务来源分别落缓存。
pub async fn translate_mod_descriptions(provider: String, slugs: Vec<String>) -> Result<Value, String> {
    crate::translate::translate_descriptions(&provider, slugs).await
}

/// `translate=false` 只取原文，不调翻译服务。
pub async fn translate_project_body(provider: String, slug: String, translate: bool) -> Result<Value, String> {
    crate::translate::translate_body(&provider, &slug, translate).await
}
pub async fn report_translation_stale(provider: String, slug: String) -> Result<String, String> {
    crate::translate::report_stale(&provider, &slug).await
}
pub async fn report_translation_quality(
    provider: String,
    slug: String,
    issue_type: String,
    user_suggestion: Option<String>,
    user_comment: Option<String>,
) -> Result<String, String> {
    crate::translate::report_quality(&provider, &slug, &issue_type, user_suggestion, user_comment).await
}

/// `service` 为空 = 全部清空。
pub async fn clear_translation_cache(service: Option<String>) -> Result<u64, String> {
    crate::translate::clear_cache(service).await
}
pub async fn test_translate_api(base: String, key: String, model: String) -> Result<(), String> {
    crate::translate::test_api(base, key, model).await
}
pub async fn project_versions(provider: String, project_id: String, mc_version: String, loader: String) -> Result<Value, String> {
    let versions = browse::project_versions(&provider, &project_id, &mc_version, &loader).await.map_err(|e| e.to_string())?;
    Ok(serde_json::json!({
        "provider": provider,
        "versions": versions,
    }))
}
pub async fn project_dependencies(provider: String, project_id: String, version_id: Option<String>) -> Result<Vec<browse::ProjectDependency>, String> {
    browse::project_dependencies(&provider, &project_id, version_id.as_deref())
        .await
        .map_err(|e| e.to_string())
}
pub async fn get_loader_versions(loader: String, mc_version: String) -> Result<Vec<String>, String> {
    browse::get_loader_versions(loader, mc_version).await.map_err(|e| e.to_string())
}
pub async fn install_game(instance_id: String) -> Result<Value, String> {
    browse::install_game(&instance_id).await.map_err(|e| e.to_string())
}
pub async fn install_content(
    instance_id: String,
    provider: String,
    project_id: String,
    version_id: String,
    kind: String,
) -> Result<Value, String> {
    browse::install_content(&instance_id, &provider, &project_id, &version_id, &kind)
        .await
        .map_err(|e| e.to_string())
}

/// 把内容装到托管服务器（模组 -> mods/，插件 -> plugins/）。
///
/// 见 `browse::install_content_to_server` 的说明（2026-10-18 新增）。
pub async fn install_server_content(
    server_id: String,
    provider: String,
    project_id: String,
    version_id: String,
    kind: String,
) -> Result<Value, String> {
    browse::install_content_to_server(&server_id, &provider, &project_id, &version_id, &kind)
        .await
        .map_err(|e| e.to_string())
}
pub async fn list_content(instance_id: String, kind: String) -> Result<Value, String> {
    browse::list_content(&instance_id, &kind).await.map_err(|e| e.to_string())
}
pub async fn mc_wiki_url(name: String, slug: Option<String>, provider: Option<String>) -> Result<String, String> {
    browse::mc_wiki_url(&name, slug.as_deref(), provider.as_deref()).await.map_err(|e| e.to_string())
}
pub async fn fetch_news() -> Result<Vec<Value>, String> {
    browse::fetch_news().await.map_err(|e| e.to_string())
}
pub async fn estimate_download(mc_version: String) -> Result<Value, String> {
    browse::estimate_download(&mc_version).await.map_err(|e| e.to_string())
}
pub async fn estimate_import(source: String, raw_ids: Vec<String>) -> Result<Value, String> {
    browse::estimate_import(&source, &raw_ids).await.map_err(|e| e.to_string())
}

// ==================== Pins (固定项) ====================
pub async fn get_pins() -> Vec<PinItem> {
    pins::load_pins().await
}
pub async fn set_pins(items: Vec<PinItem>) -> Result<(), String> {
    pins::save_pins(&items).await
}

// ==================== Instance Groups (实例分组) ====================
pub async fn list_instance_groups() -> Result<Vec<InstanceGroup>, String> {
    groups::list_groups().await
}
pub async fn create_instance_group(
    name: String,
    color: Option<String>,
) -> Result<InstanceGroup, String> {
    groups::create_group(name, color).await
}
pub async fn rename_instance_group(
    id: String,
    name: String,
    color: Option<String>,
) -> Result<InstanceGroup, String> {
    groups::rename_group(id, name, color).await
}
pub async fn delete_instance_group(id: String) -> Result<(), String> {
    groups::delete_group(id).await
}
pub async fn reorder_instance_groups(ids: Vec<String>) -> Result<Vec<InstanceGroup>, String> {
    groups::reorder_groups(ids).await
}

// ==================== Mirror (下载镜像) ====================
pub fn list_mirrors() -> Value {
    mirror::presets()
}

/// 测试镜像源连通性并返回首字节耗时（毫秒）。`base` 为空串表示测试官方源。
pub async fn test_mirror(base: String) -> Result<Value, String> {
    let base = base.trim().trim_end_matches('/').to_string();
    let url = if base.is_empty() {
        mirror::OFFICIAL_MANIFEST.to_string()
    } else {
        format!("{base}/mc/game/version_manifest_v2.json")
    };
    let start = std::time::Instant::now();
    let resp = crate::util::http_client()
        .await
        .get(&url)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(|e| format!("连接失败: {e}"))?;
    let ms = start.elapsed().as_millis() as u64;
    let status = resp.status();
    if !status.is_success() {
        return Err(format!("HTTP {status}"));
    }
    Ok(json!({ "ok": true, "ms": ms, "url": url }))
}

/// 测试下载代理配置是否可用，返回首字节耗时（毫秒）。
/// `proxy` 仅在 `proxy_mode == "custom"` 时生效。
pub async fn test_proxy(proxy_mode: String, proxy: Option<String>) -> Result<Value, String> {
    let mut builder = reqwest::Client::builder();
    if proxy_mode == "custom" {
        let addr = proxy.as_deref().unwrap_or("").trim();
        if addr.is_empty() {
            return Err("请先填写代理地址".to_string());
        }
        let p = reqwest::Proxy::all(addr).map_err(|_| {
            "代理地址格式不正确，应类似 http://127.0.0.1:7890 或 socks5://127.0.0.1:1080".to_string()
        })?;
        builder = builder.proxy(p);
    }
    let client = builder
        .build()
        .map_err(|e| format!("构建 HTTP 客户端失败: {e}"))?;
    let url = mirror::OFFICIAL_MANIFEST.to_string();
    let start = std::time::Instant::now();
    let resp = client
        .get(&url)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(|e| format!("连接失败: {e}"))?;
    let ms = start.elapsed().as_millis() as u64;
    let status = resp.status();
    if !status.is_success() {
        return Err(format!("HTTP {status}"));
    }
    Ok(json!({ "ok": true, "ms": ms, "url": url }))
}

// ==================== Memory & Log (内存检测 / 诊断日志) ====================

/// 读取 /proc/meminfo 检测系统内存（Android/Linux），并给出推荐 JVM 内存。
pub fn auto_detect_memory() -> Result<Value, String> {
    let meminfo = std::fs::read_to_string("/proc/meminfo")
        .map_err(|e| format!("无法读取系统内存信息: {e}"))?;
    let mut total_kb = 0u64;
    let mut available_kb = 0u64;
    for line in meminfo.lines() {
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total_kb = parse_meminfo_kb(rest);
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            available_kb = parse_meminfo_kb(rest);
        }
    }
    if total_kb == 0 {
        return Err("无法解析系统内存信息".into());
    }
    let total_mb = total_kb / 1024;
    let available_mb = if available_kb > 0 {
        available_kb / 1024
    } else {
        total_mb
    };
    let used_mb = total_mb.saturating_sub(available_mb);
    let (max_mb, min_mb) = recommended_memory_mb(available_mb);
    Ok(json!({
        "total_mb": total_mb,
        "used_mb": used_mb,
        "available_mb": available_mb,
        "max_mb": max_mb,
        "min_mb": min_mb
    }))
}

fn parse_meminfo_kb(s: &str) -> u64 {
    s.split_whitespace()
        .next()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0)
}

/// 与桌面端一致的内存推荐策略：可用内存 40% 起（最低 2 GB），封顶 8 GB。
fn recommended_memory_mb(available_mb: u64) -> (u32, u32) {
    let base = (available_mb * 40 / 100).max(2048);
    let cap = (available_mb * 3 / 4).max(512);
    let max = base.min(cap).min(8192).max(512);
    let min = (max / 4).max(256);
    (max as u32, min as u32)
}

/// 临时诊断命令：把前端的关键步骤写进诊断日志（log_debug）。
pub fn log_debug(msg: String) {
    util::log_line(&msg);
}

/// 游戏是否在运行（布尔值，供前端直接判断）。
pub fn is_game_running() -> bool {
    launch::get_game_status().is_running
}

// ==================== Storage (存储统计) ====================
pub async fn get_storage_stats() -> Result<StorageStats, String> {
    Ok(storage::get_storage_stats().await)
}
pub async fn refresh_storage_stats() -> Result<StorageStats, String> {
    Ok(storage::refresh_storage_stats().await)
}
pub async fn clear_cache() -> Result<CacheClearResult, String> {
    storage::clear_cache().await
}

// ==================== Crash (崩溃分析) ====================
pub async fn list_crash_logs(id: String) -> Result<Vec<CrashLogEntry>, String> {
    crash::list_crash_logs(&id).await
}
pub async fn analyze_crash_log(id: String, filename: String) -> Result<CrashDiagnosis, String> {
    crash::analyze_report(&id, &filename).await
}
pub async fn get_crash_report_content(id: String, filename: String) -> Result<String, String> {
    crash::get_report_content(&id, &filename).await
}

// ==================== Skins (皮肤中心) ====================
pub async fn list_skins() -> Result<Vec<skins::SkinEntry>, String> {
    skins::list_skins().await
}
pub async fn read_skin_data_url(filename: String) -> Result<String, String> {
    skins::read_skin_data_url(filename).await
}
pub async fn save_skin_from_data(name: String, data: String) -> Result<skins::SkinEntry, String> {
    skins::save_skin_from_data(name, data).await
}
pub async fn download_skin_from_url(name: String, url: String) -> Result<skins::SkinEntry, String> {
    skins::download_skin_from_url(name, url).await
}
pub async fn delete_skin(filename: String) -> Result<(), String> {
    skins::delete_skin(filename).await
}
pub async fn fetch_image_data_url(url: String) -> Result<String, String> {
    skins::fetch_image_data_url(url).await
}
pub async fn fetch_player_skin(username: String) -> Result<skins::PlayerSkinResult, String> {
    skins::fetch_player_skin(username).await
}
pub async fn fetch_player_capes(account_uuid: String) -> Result<Vec<skins::CapeInfo>, String> {
    skins::fetch_player_capes(account_uuid).await
}
pub async fn apply_skin_to_account(
    account_uuid: String,
    skin_data: String,
    variant: String,
) -> Result<(), String> {
    skins::apply_skin_to_account(account_uuid, skin_data, variant).await
}
pub async fn apply_cape_to_account(
    account_uuid: String,
    cape_id: Option<String>,
) -> Result<(), String> {
    skins::apply_cape_to_account(account_uuid, cape_id).await
}
pub async fn apply_skin_offline(
    skin_data: String,
    variant: String,
    uuid: String,
) -> Result<(), String> {
    skins::apply_skin_offline(skin_data, variant, uuid).await
}
pub async fn get_offline_skin(uuid: String) -> Result<Option<Value>, String> {
    skins::get_offline_skin(uuid).await
}

// ==================== Servers (多人服务器) ====================
pub async fn list_servers(instance_id: String) -> Result<Value, String> {
    let list = servers::list_remote_servers(&instance_id).await?;
    Ok(json!({ "servers": list }))
}
pub async fn ping_mc_server(address: String) -> ServerStatus {
    servers::ping_remote_server(&address).await
}

// ==================== World Backup (世界存档备份) ====================
pub async fn list_world_backups(instance_id: String, world: String) -> Result<Vec<BackupInfo>, String> {
    Ok(world_backup::list_backups(&instance_id, &world).await)
}
pub async fn create_world_backup(instance_id: String, world: String) -> Result<BackupInfo, String> {
    world_backup::create_backup(&instance_id, &world).await
}
pub async fn restore_world_backup(
    instance_id: String,
    world: String,
    filename: String,
) -> Result<(), String> {
    world_backup::restore_backup(&instance_id, &world, &filename).await
}
pub async fn delete_world_backup(
    instance_id: String,
    world: String,
    filename: String,
) -> Result<(), String> {
    world_backup::delete_backup(&instance_id, &world, &filename).await
}

// ==================== Playtime (游玩时长) ====================
pub async fn playtime_stats() -> Result<PlaytimeStats, String> {
    Ok(playtime::playtime_stats().await)
}

// ==================== Microsoft 账号登录 (设备码) ====================
pub async fn login_ms_start() -> Result<Value, String> {
    accounts::ms_start().await.map_err(|e| e.to_string())
}
pub async fn login_ms_poll() -> Result<Account, String> {
    accounts::ms_poll().await.map_err(|e| e.to_string())
}

// ==================== Content Management (内容管理) ====================
pub async fn check_updates(instance_id: String, kind: String) -> Result<Vec<Value>, String> {
    browse::check_updates(&instance_id, &kind).await
}
pub async fn apply_update(
    instance_id: String,
    kind: String,
    old_filename: String,
    provider: String,
    project_id: String,
    new_version_id: String,
) -> Result<Value, String> {
    browse::apply_update(
        &instance_id,
        &kind,
        &old_filename,
        &provider,
        &project_id,
        &new_version_id,
    )
    .await
}
pub async fn uninstall_content(instance_id: String, kind: String, filename: String) -> Result<(), String> {
    browse::uninstall_content(&instance_id, &kind, &filename).await
}
pub async fn identify_content(instance_id: String, kind: String) -> Result<(), String> {
    browse::identify_content(&instance_id, &kind).await
}
pub async fn import_local_file(
    instance_id: String,
    kind: String,
    source_path: String,
) -> Result<Value, String> {
    browse::import_local_file(&instance_id, &kind, &source_path).await
}
pub async fn toggle_content_enabled(
    instance_id: String,
    kind: String,
    filename: String,
    enabled: bool,
) -> Result<(), String> {
    browse::toggle_content_enabled(&instance_id, &kind, &filename, enabled).await
}
pub async fn save_text_file(path: String, content: String) -> Result<(), String> {
    browse::save_text_file(&path, &content)
}
pub async fn extract_game_icons(instance_id: Option<String>) -> Result<Vec<browse::GameIcon>, String> {
    browse::extract_game_icons(instance_id).await
}

// ==================== Instance File Manager (实例文件管理器) ====================

/// 已知的实例子目录（文件管理器常用入口）。
const SUBFOLDERS: [&str; 9] = [
    "mods", "shaderpacks", "resourcepacks", "saves", "screenshots", "config", "logs", "natives", "icons",
];

/// 实例目录根：`settings::instances_root()/<id>`（用户可把游戏目录改到外部）。
/// 先校验实例 ID 防路径穿越。
async fn instance_root(instance_id: &str) -> Result<std::path::PathBuf, String> {
    fsutil::validate_id(instance_id, "实例")?;
    settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())
}

/// 把相对路径约束在实例目录内，拒绝 `..` / 绝对路径 / 越界符号链接。
async fn resolve_instance_path(instance_id: &str, rel: &str) -> Result<std::path::PathBuf, String> {
    let root = instance_root(instance_id).await?;
    fsutil::resolve_in_dir(&root, rel, "实例")
}
pub async fn list_instance_folders(instance_id: String) -> Result<Value, String> {
    let dir = instance_root(&instance_id).await?;
    let folders: Vec<Value> = SUBFOLDERS
        .iter()
        .map(|f| json!({ "name": f, "exists": dir.join(f).is_dir() }))
        .collect();
    Ok(json!({ "folders": folders }))
}
pub async fn list_instance_files(instance_id: String, sub: String) -> Result<Value, String> {
    if !SUBFOLDERS.contains(&sub.as_str()) {
        return Err("非法目录".into());
    }
    let dir = instance_root(&instance_id).await?.join(&sub);
    if !dir.exists() {
        return Ok(json!({ "files": [] }));
    }
    let files = tokio::task::spawn_blocking(move || {
        let mut files: Vec<Value> = Vec::new();
        for entry in std::fs::read_dir(&dir).map_err(|e| e.to_string())? {
            let e = entry.map_err(|e| e.to_string())?;
            let meta = e.metadata().map_err(|e| e.to_string())?;
            let path = e.path();
            let mut icon: Option<String> = None;
            if sub == "saves" && meta.is_dir() {
                let icon_path = path.join("icon.png");
                if icon_path.is_file() {
                    icon = Some(icon_path.to_string_lossy().to_string());
                }
            }
            files.push(json!({
                "name": e.file_name().to_string_lossy().to_string(),
                "path": path.to_string_lossy().to_string(),
                "size": meta.len(),
                "modified": meta.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0),
                "isDir": meta.is_dir(),
                "icon": icon,
            }));
        }
        files.sort_by(|a, b| {
            let da = a.get("isDir").and_then(|v| v.as_bool()).unwrap_or(false);
            let db = b.get("isDir").and_then(|v| v.as_bool()).unwrap_or(false);
            db.cmp(&da).then_with(|| {
                a.get("name")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_lowercase()
                    .cmp(&b.get("name").and_then(|v| v.as_str()).unwrap_or("").to_lowercase())
            })
        });
        Ok::<Vec<Value>, String>(files)
    })
    .await
    .map_err(|e| e.to_string())??;
    Ok(json!({ "files": files }))
}
pub async fn list_instance_dir(instance_id: String, rel: String) -> Result<Value, String> {
    let dir = resolve_instance_path(&instance_id, &rel).await?;
    if !dir.is_dir() {
        return Err("不是一个目录".into());
    }
    let base = rel.clone();
    let entries =
        tokio::task::spawn_blocking(move || fsutil::list_dir(&dir, &base))
            .await
            .map_err(|e| e.to_string())??;
    Ok(json!({ "rel": rel, "entries": entries }))
}
pub async fn read_instance_file(instance_id: String, rel: String) -> Result<Value, String> {
    let path = resolve_instance_path(&instance_id, &rel).await?;
    fsutil::read_text(&path, &rel)
}
pub async fn write_instance_file(
    instance_id: String,
    rel: String,
    content: String,
) -> Result<Value, String> {
    let path = resolve_instance_path(&instance_id, &rel).await?;
    fsutil::write_text(&path, &rel, content)
}
pub async fn create_instance_entry(
    instance_id: String,
    rel: String,
    is_dir: bool,
) -> Result<Value, String> {
    let last = rel.rsplit('/').next().unwrap_or("");
    fsutil::validate_name(last)?;
    let path = resolve_instance_path(&instance_id, &rel).await?;
    if path.exists() {
        return Err("已存在同名的文件或文件夹".into());
    }
    if is_dir {
        std::fs::create_dir_all(&path).map_err(|e| format!("创建文件夹失败: {e}"))?;
    } else {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
        }
        std::fs::write(&path, "").map_err(|e| format!("创建文件失败: {e}"))?;
    }
    Ok(json!({ "rel": rel, "is_dir": is_dir }))
}
pub async fn delete_instance_path(instance_id: String, rel: String) -> Result<(), String> {
    if rel.trim().is_empty() {
        return Err("不能删除实例根目录".into());
    }
    let path = resolve_instance_path(&instance_id, &rel).await?;
    if !path.exists() {
        return Err("文件或文件夹不存在".into());
    }
    if path.is_dir() {
        std::fs::remove_dir_all(&path).map_err(|e| format!("删除文件夹失败: {e}"))?;
    } else {
        std::fs::remove_file(&path).map_err(|e| format!("删除文件失败: {e}"))?;
    }
    Ok(())
}
pub async fn rename_instance_path(
    instance_id: String,
    rel: String,
    new_name: String,
) -> Result<Value, String> {
    if rel.trim().is_empty() {
        return Err("不能重命名实例根目录".into());
    }
    fsutil::validate_name(&new_name)?;
    let path = resolve_instance_path(&instance_id, &rel).await?;
    if !path.exists() {
        return Err("文件或文件夹不存在".into());
    }
    let parent = path.parent().ok_or("无法重命名该路径")?;
    let target = parent.join(new_name.trim());
    if target.exists() {
        return Err("已存在同名的文件或文件夹".into());
    }
    std::fs::rename(&path, &target).map_err(|e| format!("重命名失败: {e}"))?;
    let parent_rel = match rel.rfind('/') {
        Some(i) => rel[..i].to_string(),
        None => String::new(),
    };
    let new_rel = if parent_rel.is_empty() {
        new_name.trim().to_string()
    } else {
        format!("{}/{}", parent_rel, new_name.trim())
    };
    Ok(json!({ "rel": new_rel, "name": new_name.trim().to_string() }))
}

// ==================== 图片导入（实例图标 / 背景图） ====================

/// 把用户选择的图片复制到 `<data_dir>/<dir_name>/` 下（uuid 命名），返回绝对路径。
async fn import_image_into(
    source_path: &str,
    dir_name: &str,
    image_exts: &[&str],
    err_prefix: &str,
) -> Result<String, String> {
    let source = std::path::Path::new(source_path);
    let ext = source
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_else(|| "png".into());
    if !image_exts.contains(&ext.as_str()) {
        return Err("不支持的图片格式".into());
    }
    let data_dir = settings::get_data_dir().await.map_err(|e| e.to_string())?;
    let dir = std::path::PathBuf::from(&data_dir).join(dir_name);
    let src = source.to_path_buf();
    let prefix = err_prefix.to_string();
    let prefix_outer = err_prefix.to_string();
    // 背景图可能有几 MB，同步 copy 会占住 tokio worker（同 worker 的 invoke 全排队）
    tokio::task::spawn_blocking(move || -> Result<String, String> {
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let dest = dir.join(format!("{}.{}", uuid::Uuid::new_v4().simple(), ext));
        std::fs::copy(&src, &dest).map_err(|e| format!("{prefix}失败: {e}"))?;
        Ok(dest.to_string_lossy().to_string())
    })
    .await
    .map_err(|e| format!("{prefix_outer}失败: {e}"))?
}
pub async fn import_instance_image(source_path: String) -> Result<String, String> {
    import_image_into(
        &source_path,
        "icons",
        &["png", "jpg", "jpeg", "gif", "webp", "bmp", "ico"],
        "复制图片",
    )
    .await
}
pub async fn import_background_image(source_path: String) -> Result<String, String> {
    import_image_into(
        &source_path,
        "backgrounds",
        &["png", "jpg", "jpeg", "gif", "webp", "bmp"],
        "复制背景图片",
    )
    .await
}

// ==================== 原生能力（屏幕方向 / 文件选择） ====================

/// 设置启动器界面方向：`system`（跟随系统）/ `portrait`（竖屏）/ `landscape`（横屏）。
pub async fn set_orientation(mode: String) -> Result<(), String> {
    crate::android_bridge::set_orientation(&mode)
}

/// 把系统文件选择器返回的 `content://` URI 落地到应用私有目录，返回可被文件命令直接读取的路径。
/// 传入的已经是普通路径时原样返回（桌面端即为此情况）。
pub async fn resolve_picked_path(path: String) -> Result<String, String> {
    crate::android_bridge::resolve_picked_path(&path)
}

/// 检测系统（含 VPN）下发的 HTTP 代理地址，供设置页展示与提示。
/// 原生请求不会自动走系统代理，代理软件开着却选了「直连」时下载会大面积失败。
pub async fn detect_system_proxy() -> Option<String> {
    crate::android_bridge::system_proxy()
}
// ==================== 游戏内设置（Pojav 控制层） ====================

/// 读取移植过来的 Pojav 控制层设置。
///
/// 按钮大小 / 鼠标速度 / **渲染分辨率缩放** / 忽略刘海 / 长按判定 / 陀螺仪 /
/// 禁用手势 / 备选渲染表面……这些是 `LauncherPreferences` 从安卓
/// `SharedPreferences("launcher_preferences")` 读的，QookiX 的 `settings.json` 管不到，
/// 所以必须经原生桥读写。
pub fn get_pojav_prefs() -> Result<serde_json::Value, String> {
    let json = crate::android_bridge::read_pojav_prefs()?;
    Ok(serde_json::from_str(&json).unwrap_or_else(|_| serde_json::json!({})))
}

/// 写入 Pojav 设置（增量：只传要改的键）。写完后原生侧会重载
/// `LauncherPreferences`，因此不需要重启应用即可生效。
pub fn set_pojav_prefs(patch: serde_json::Value) -> Result<(), String> {
    let Some(obj) = patch.as_object() else {
        return Err("参数必须是对象".into());
    };
    if obj.is_empty() {
        return Ok(());
    }
    // 只允许写已知的键，避免前端手滑把无关的 SharedPreferences 写坏
    let allowed = [
        // 渲染器：opengles2（GL4ES）/ mobileglues（MobileGlues）/ vulkan_zink（Zink + Turnip）。launch.rs 会读它。
        "renderer",
        "resolutionRatio", "buttonscale", "mousescale", "mousespeed",
        "timeLongPressTrigger", "ignoreNotch", "mouse_start", "disableGestures",
        "disableDoubleTap", "alternate_surface", "sustained_performance",
        "enableGyro", "gyroSensitivity", "gyroInvertX", "gyroInvertY",
        "gamepad_deadzone_scale", "buttonAllCaps",
    ];
    let mut filtered = serde_json::Map::new();
    for (k, v) in obj {
        if allowed.contains(&k.as_str()) {
            filtered.insert(k.clone(), v.clone());
        }
    }
    if filtered.is_empty() {
        return Err("没有可写入的设置项".into());
    }
    crate::android_bridge::write_pojav_prefs(&serde_json::Value::Object(filtered).to_string())
}
/// 读取实例日志（磁盘版）。见 `launch::read_instance_log` 的说明：
/// 内存里的实时日志会随进程崩溃一起消失，必须能回退到文件。
pub async fn read_instance_log(instance_id: String) -> Result<String, String> {
    crate::launch::read_instance_log(&instance_id)
        .await
        .map_err(|e| e.to_string())
}

/// 导出（分享）实例日志：打 zip 后走系统分享。
///
/// 日志目录默认在 `Android/data/<包名>/…` 下，**Android 11+ 屏蔽了 Android/data**，
/// 文件管理器和电脑 MTP 都取不到（MT 会报「该文件被设置了私有权限」），分享是唯一出路。
pub async fn export_instance_logs(instance_id: String) -> Result<(), String> {
    crate::launch::export_instance_logs(&instance_id)
        .await
        .map_err(|e| e.to_string())
}

/// 渲染器健康检查：本次启动日志里有渲染器失败证据、且当前渲染器不是该版本推荐的那个时，
/// 返回一份「建议切换」的说明（前端在玩家回到启动器后弹窗询问）。
///
/// 只读、只建议 —— 切换动作由前端调 `update_instance`（**只改这一个实例**）完成。
pub async fn check_renderer_health(
    instance_id: String,
) -> Result<Option<crate::models::RendererIssue>, String> {
    crate::renderer_health::check(&instance_id).await
}

/// 同上，但对象是「最近一次启动过的实例」（按 `logs/launch-*.log` 的修改时间挑）。
///
/// 玩家「回到启动器」时用这个：有些渲染器故障会让游戏线程死掉、JVM 却还挂着，
/// 根本不会有正常的退出事件可听。
pub async fn check_renderer_health_latest(
) -> Result<Option<crate::models::RendererIssue>, String> {
    crate::renderer_health::check_latest().await
}
