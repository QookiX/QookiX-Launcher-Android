//! 实例文件完整性检查（支撑 UI 的「补全游戏文件」功能）。
//!
//! # 背景
//!
//! 遇到过两个真实问题：
//! 1. 用户的 26.3 NeoForge 实例只有 `instance.json` + `options.txt`，
//!    界面显示「这里还是空的」、下载页没任务、启动直接黑屏 —— **完全没有补救入口**。
//! 2. `loader_version` 为空时（该游戏版本没有对应版本的加载器），
//!    依赖根本解析不出来，但界面不会告诉用户「是这个原因」。
//!
//! 所以这里提供一个**纯诊断**命令：不下载任何东西，只说清楚
//! 「缺什么、为什么缺、能不能补」。

use serde_json::Value;

/// 检查结果里的一项
#[derive(Debug, Clone, serde::Serialize)]
pub struct MissingItem {
    /// 机器可读的类别：version / libraries / assets / loader / runtime
    pub kind: String,
    /// 人类可读的中文说明
    pub detail: String,
    /// 是否能通过「补全游戏文件」修好
    pub fixable: bool,
}

#[derive(Debug, Clone, serde::Serialize)]
pub struct InstanceFileReport {
    /// 5 个关键项里完整了几个（0-5）
    pub ok: usize,
    pub total: usize,
    pub missing: Vec<MissingItem>,
    /// 缺文件时给用户的一句人话建议
    pub advice: String,
    /// 实例是否具备启动条件（关键项全齐）
    pub can_launch: bool,
}

async fn data_dir() -> Result<std::path::PathBuf, String> {
    let d = crate::settings::get_data_dir()
        .await
        .map_err(|e| e.to_string())?;
    Ok(std::path::PathBuf::from(d))
}

/// 判断某个游戏版本的 json 是否已下载
fn version_json_present(dir: &std::path::Path, mc_version: &str) -> bool {
    dir.join("versions")
        .join(mc_version)
        .join("version.json")
        .exists()
        || dir
            .join("versions")
            .join(mc_version)
            .join(format!("{mc_version}.json"))
            .exists()
}

/// 数一个目录下的文件数（用于判断 assets/libraries 是否下过）
fn count_files(dir: &std::path::Path) -> usize {
    std::fs::read_dir(dir)
        .map(|rd| rd.flatten().count())
        .unwrap_or(0)
}

/// 按**资源索引**逐对象核对 assets，返回 `(缺失数, 索引里的总数)`；拿不到索引时返回 `None`。
///
/// 为什么不能只判"assets 目录里有没有东西"：26.3 的索引里有几千个对象，
/// 只下到几百个时浅判依旧报「完整」，用户要到启动游戏才崩在贴图加载
/// （`version.rs` 里记过同样的事故：5147 个对象静默漏了 199 个，报"安装完成"）。
/// 判据与下载侧一致（`version.rs` 的跳过逻辑）：**大小必须对得上**，
/// 因为进程被杀留下的半截文件同样不该算数。
fn count_missing_assets(data_dir: &std::path::Path, version_json: &std::path::Path) -> Option<(usize, usize)> {
    let text = std::fs::read_to_string(version_json).ok()?;
    let version: Value = serde_json::from_str(&text).ok()?;
    let index_id = version["assetIndex"]["id"].as_str()?.to_string();
    let index_text =
        std::fs::read_to_string(data_dir.join("assets/indexes").join(format!("{index_id}.json"))).ok()?;
    let index: Value = serde_json::from_str(&index_text).ok()?;
    let objects = index["objects"].as_object()?;

    let objects_dir = data_dir.join("assets/objects");
    let mut missing = 0usize;
    let mut total = 0usize;
    for obj in objects.values() {
        let Some(hash) = obj["hash"].as_str() else {
            continue;
        };
        if hash.len() < 2 {
            continue;
        }
        total += 1;
        let size = obj["size"].as_u64().unwrap_or(0);
        let path = objects_dir.join(&hash[..2]).join(hash);
        let ok = std::fs::metadata(&path)
            .map(|m| if size > 0 { m.len() == size } else { m.len() > 0 })
            .unwrap_or(false);
        if !ok {
            missing += 1;
        }
    }
    Some((missing, total))
}

/// 检查一个实例缺哪些关键文件。
///
/// **只读，不下载** —— UI 用它决定要不要显示「补全游戏文件」按钮，
/// 以及告诉用户到底缺什么（避免「点了没反应」或「下完还是黑屏」）。
#[tauri::command]
pub async fn check_instance_files(instance_id: String) -> Result<InstanceFileReport, String> {
    crate::fsutil::validate_id(&instance_id, "实例")?;
    let dir = data_dir().await?;
    let inst_dir = crate::settings::instance_dir(&instance_id)
        .await
        .map_err(|e| e.to_string())?;
    let json_path = inst_dir.join("instance.json");
    if !json_path.exists() {
        return Err("实例不存在".to_string());
    }
    let text = std::fs::read_to_string(&json_path).map_err(|e| e.to_string())?;
    let inst: Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let mc_version = inst["mc_version"]
        .as_str()
        .or_else(|| inst["mcVersion"].as_str())
        .unwrap_or("")
        .to_string();
    let loader = inst["loader"]
        .as_str()
        .unwrap_or("vanilla")
        .to_lowercase();
    let loader_version = inst["loader_version"]
        .as_str()
        .or_else(|| inst["loaderVersion"].as_str())
        .unwrap_or("")
        .to_string();

    let mut missing: Vec<MissingItem> = Vec::new();

    // 1) 版本 json
    if !version_json_present(&dir, &mc_version) {
        missing.push(MissingItem {
            kind: "version".into(),
            detail: format!("缺少 {mc_version} 的版本信息（version.json）"),
            fixable: true,
        });
    }

    // 2) libraries
    if count_files(&dir.join("libraries")) == 0 {
        missing.push(MissingItem {
            kind: "libraries".into(),
            detail: "缺少游戏依赖库（libraries）".into(),
            fixable: true,
        });
    }

    // 3) assets（游戏本体资源，占绝大部分体积）
    //    逐对象核对：只判"目录非空"会把"下了一部分"当成完整（用户启动时才崩）。
    //    几千次 `metadata` 是阻塞活，丢到 blocking 线程池里做，别占住异步运行时。
    let version_json = dir
        .join("versions")
        .join(&mc_version)
        .join(format!("{mc_version}.json"));
    let assets_probe = {
        let dir = dir.clone();
        let version_json = version_json.clone();
        tokio::task::spawn_blocking(move || count_missing_assets(&dir, &version_json))
            .await
            .ok()
            .flatten()
    };
    match assets_probe {
        Some((missing_n, total_n)) if missing_n > 0 => missing.push(MissingItem {
            kind: "assets".into(),
            detail: format!("缺少游戏素材（assets）：{missing_n}/{total_n} 个资源对象没下全"),
            fixable: true,
        }),
        Some(_) => {}
        None => {
            // 拿不到版本 JSON / 资源索引（例如版本信息没下）：退回原来的浅判，
            // 至少别漏报"完全没有"的情况
            if count_files(&dir.join("assets")) <= 1 {
                missing.push(MissingItem {
                    kind: "assets".into(),
                    detail: "缺少游戏素材（assets）".into(),
                    fixable: true,
                });
            }
        }
    }

    // 4) 加载器本体
    if loader != "vanilla" {
        if loader_version.is_empty() {
            // 这是「创建时就选不到版本」的情况，补全也救不回来 —— 单独提示
            let pretty = match loader.as_str() {
                "neoforge" => "NeoForge",
                "forge" => "Forge",
                "fabric" => "Fabric",
                other => other,
            };
            missing.push(MissingItem {
                kind: "loader".into(),
                detail: format!(
                    "{pretty} 版本号为空：{mc_version} 可能还没有对应的 {pretty} 版本，\
                     建议换一个游戏版本或先装原版"
                ),
                fixable: false,
            });
        } else {
            // 必须和 `browse::install_loader` 用同一份路径规则：
            // 早先这里查的是 `jars/<loader>-<版本>.jar`，而安装落盘在
            // `libraries/`，于是装完仍被判成「缺本体」。
            let jar = crate::browse::loader_jar_path(
                &dir.join("libraries"),
                &loader,
                &mc_version,
                &loader_version,
            );
            if !jar.exists() {
                missing.push(MissingItem {
                    kind: "loader".into(),
                    detail: format!("缺少 {loader} {loader_version} 本体"),
                    fixable: true,
                });
            }
        }
    }

    // 5) Java 运行时
    let has_jre = std::fs::read_dir(dir.join("runtimes"))
        .map(|rd| rd.flatten().next().is_some())
        .unwrap_or(false);
    if !has_jre {
        missing.push(MissingItem {
            kind: "runtime".into(),
            detail: "缺少 Java 运行环境".into(),
            fixable: true,
        });
    }

    let total = 5;
    let ok = total - missing.len();
    // 不可修的项（loader 版本号为空）会直接导致启动失败，必须在 advice 里点出来
    let has_blocker = missing.iter().any(|m| !m.fixable);
    let advice = if missing.is_empty() {
        "文件完整，可以直接启动".to_string()
    } else if has_blocker {
        let blocked: Vec<String> = missing
            .iter()
            .filter(|m| !m.fixable)
            .map(|m| m.detail.clone())
            .collect();
        format!(
            "{}。这个问题「补全文件」解决不了，需要换一个游戏版本或加载器。",
            blocked.join("；")
        )
    } else if ok == 0 {
        "实例几乎是空的，点「补全游戏文件」重新下载一遍即可".to_string()
    } else {
        format!("缺 {} 项，点「补全游戏文件」补齐即可", missing.len())
    };

    Ok(InstanceFileReport {
        ok,
        total,
        can_launch: missing.is_empty(),
        missing,
        advice,
    })
}

/// 补全（下载）缺失文件。
///
/// 两步：
/// ① **游戏文件**（version.json / libraries / assets / loader）—— 直接复用
///    `browse::install_game`，它就是「下载游戏本体」的完整实现，不另写一套下载逻辑；
/// ② **Java 运行时** —— `install_game` **不含**这一步，而 `check_instance_files` 会把
///    「缺 Java 运行环境」也算成缺失项（判据是 `runtimes/` 为空）。
///
/// 少了 ② 的后果是真机实测踩到的：补全跑完（游戏文件其实都在，一个字节都没下）、
/// 检查依旧报「已就绪 4/5 · 缺 1 项」，用户永远补不上 —— 也就是「点了补全也补不全」。
/// 实现与服务器托管那条路对齐（见 `servers::ensure_server_jre`）。
#[tauri::command]
pub async fn repair_instance_files(instance_id: String) -> Result<Value, String> {
    crate::fsutil::validate_id(&instance_id, "实例")?;
    let game = crate::browse::install_game(&instance_id)
        .await
        .map_err(|e| e.to_string())?;
    let java = ensure_instance_java(&instance_id).await?;
    Ok(serde_json::json!({ "game": game, "java": java }))
}

/// 确保该实例有可用的 Java 运行时；没有就下一个（约 27MB）。
async fn ensure_instance_java(instance_id: &str) -> Result<String, String> {
    use std::sync::atomic::Ordering;

    // ⚠️ 一个操作只能有**一个** `TaskCtx`：`TaskCtx::new` 会分配新的自增 task id
    // （`progress::next_task_id`），而前端的任务条目就是用这个 id 当 key 的。
    // 之前这里建了两个（开始一个、结束一个），前端那条任务永远等不到 done ——
    // 表现就是**进度条永久停在「正在下载 Java 运行时…」**（真机实测）。
    // 下面的 emit_install / emit_install_done 都复用同一个 ctx。
    let inst_dir = crate::settings::instance_dir(instance_id)
        .await
        .map_err(|e| e.to_string())?;
    let text = std::fs::read_to_string(inst_dir.join("instance.json")).map_err(|e| e.to_string())?;
    let inst: Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    let mc_version = inst["mc_version"]
        .as_str()
        .or_else(|| inst["mcVersion"].as_str())
        .unwrap_or("")
        .to_string();
    let name = inst["name"].as_str().unwrap_or(instance_id).to_string();
    let required = crate::servers::required_java_for_mc(&mc_version);

    // 已装的能满足就直接用（离线也能走通这条，不会无谓联网）
    if let Ok(Some(rt)) = crate::java::MultiRTManager::get_compatible_runtime(required).await {
        crate::util::log_line(&format!("Java {required} 运行时已就绪：{}", rt.path));
        return Ok(rt.path);
    }

    let arch = crate::java::get_device_arch();
    // 先确认这个版本**下得下来**：镜像表里没有对应包时要直说，
    // 不然用户会以为是自己没装。
    crate::java::resolve_download_url(required, arch).map_err(|e| {
        format!("{mc_version} 需要 Java {required}，但镜像里没有 {arch} 对应的包（{e}）")
    })?;

    crate::util::log_line(&format!(
        "实例 {instance_id} 缺 Java {required} 运行时，开始下载（架构 {arch}）"
    ));
    let ctx = crate::progress::TaskCtx::new(instance_id, &name, "Java 运行时");
    crate::progress::emit_install(
        &ctx,
        "java",
        &format!("正在下载 Java {required} 运行时（约 27MB）…"),
        0,
        1,
    );
    // 每 10% 记一行日志：JRE 下载慢，没进度用户以为卡死；记太密日志又没法看
    let last = std::sync::Arc::new(std::sync::atomic::AtomicI32::new(-100));
    let last_p = last.clone();
    let installed = crate::java::JreManager::install(
        required,
        arch,
        move |p: f32| {
            let pct = (p * 100.0).round() as i32;
            if pct - last_p.load(Ordering::Relaxed) >= 10 {
                last_p.store(pct, Ordering::Relaxed);
                crate::util::log_line(&format!("Java {required} 运行时下载中 {pct}%"));
            }
        },
        |phase| crate::util::log_line(&format!("Java 运行时：{phase}")),
    )
    .await
    .map_err(|e| format!("下载 Java {required} 运行时失败：{e}"))?;

    if !crate::java::JREValidator::validate(&installed)
        .await
        .unwrap_or(false)
    {
        return Err(format!(
            "Java {required} 解压后找不到 libjli.so（可能下载不完整），请重试或换个网络"
        ));
    }
    crate::progress::emit_install_done(&ctx, true, &format!("Java {required} 已就绪"), 1, 1);
    crate::util::log_line(&format!(
        "Java {required} 运行时安装完成：{}",
        installed.path
    ));
    Ok(installed.path)
}

// ==================== 共享文件：占用统计与清理 ====================
//
// 游戏本体（`versions/` `libraries/` `assets/` `runtimes/`）是**全局共享**的，
// 不放在实例目录里 —— 好处是多个实例共用一份，坏处是用户完全看不见它：
//   - 「文件」页只显示实例目录，刚建的实例里只有一个 `instance.json`，
//     看着像"什么都没下"（真机反馈过）；
//   - 删掉实例**不会**回收这些文件，几百 MB 会一直留着。
// 下面三个命令分别解决："占了多少" / "能不能清" / "清掉"。

/// 递归统计目录字节数（读不到就记 0，不让统计本身报错）。
fn dir_bytes(path: &std::path::Path) -> u64 {
    let Ok(rd) = std::fs::read_dir(path) else {
        return 0;
    };
    let mut total = 0u64;
    for entry in rd.flatten() {
        let Ok(meta) = entry.metadata() else { continue };
        if meta.is_dir() {
            total += dir_bytes(&entry.path());
        } else {
            total += meta.len();
        }
    }
    total
}

/// 所有实例的 `(实例 id, MC 版本)`，用于判断"这套文件还有没有别人在用"。
fn instances_versions(data_dir: &std::path::Path) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let Ok(rd) = std::fs::read_dir(crate::settings::resolve_instances_root(data_dir)) else {
        return out;
    };
    for entry in rd.flatten() {
        let id = entry.file_name().to_string_lossy().to_string();
        let Ok(text) = std::fs::read_to_string(entry.path().join("instance.json")) else {
            continue;
        };
        let Ok(v) = serde_json::from_str::<Value>(&text) else {
            continue;
        };
        let mc = v["mc_version"]
            .as_str()
            .or_else(|| v["mcVersion"].as_str())
            .unwrap_or("")
            .to_string();
        out.push((id, mc));
    }
    out
}

/// 共享文件占用：版本目录与 Java 运行时可归属到具体实例；`libraries/` `assets/`
/// 是跨版本共享池，只报总量（没法说"这 194MB 是某个实例的"）。
#[tauri::command]
pub async fn shared_game_usage(instance_id: String) -> Result<Value, String> {
    crate::fsutil::validate_id(&instance_id, "实例")?;
    let dir = data_dir().await?;
    let all = instances_versions(&dir);
    let mc_version = all
        .iter()
        .find(|(id, _)| id == &instance_id)
        .map(|(_, mc)| mc.clone())
        .unwrap_or_default();
    let required = crate::servers::required_java_for_mc(&mc_version);
    Ok(serde_json::json!({
        "mcVersion": mc_version,
        "versionBytes": dir_bytes(&dir.join("versions").join(&mc_version)),
        "javaVersion": required,
        "javaBytes": dir_bytes(&dir.join("runtimes").join(format!("JRE-{required}"))),
        "librariesBytes": dir_bytes(&dir.join("libraries")),
        "assetsBytes": dir_bytes(&dir.join("assets")),
        "instancesTotal": all.len(),
    }))
}

/// 删实例前先算：这套共享文件能不能顺带清掉。
/// 判据是"还有没有别的实例在用同一个 MC 版本 / 同一个 Java 大版本"。
#[tauri::command]
pub async fn plan_instance_cleanup(instance_id: String) -> Result<Value, String> {
    crate::fsutil::validate_id(&instance_id, "实例")?;
    let dir = data_dir().await?;
    let all = instances_versions(&dir);
    let mc_version = all
        .iter()
        .find(|(id, _)| id == &instance_id)
        .map(|(_, mc)| mc.clone())
        .unwrap_or_default();
    let required = crate::servers::required_java_for_mc(&mc_version);
    let others_same_version = all
        .iter()
        .filter(|(id, mc)| id != &instance_id && !mc.is_empty() && mc == &mc_version)
        .count();
    let others_same_java = all
        .iter()
        .filter(|(id, mc)| {
            id != &instance_id
                && !mc.is_empty()
                && crate::servers::required_java_for_mc(mc) == required
        })
        .count();

    let version_bytes = dir_bytes(&dir.join("versions").join(&mc_version));
    let java_bytes = dir_bytes(&dir.join("runtimes").join(format!("JRE-{required}")));
    let can_version = others_same_version == 0 && version_bytes > 0;
    let can_java = others_same_java == 0 && java_bytes > 0;
    Ok(serde_json::json!({
        "mcVersion": mc_version,
        "javaVersion": required,
        "versionBytes": version_bytes,
        "javaBytes": java_bytes,
        // 只把"确定能清的"算进可释放空间，避免给用户一个虚高的数字
        "totalBytes": (if can_version { version_bytes } else { 0 })
            + (if can_java { java_bytes } else { 0 }),
        "othersSameVersion": others_same_version,
        "othersSameJava": others_same_java,
        "canCleanVersion": can_version,
        "canCleanJava": can_java,
    }))
}

/// 清掉该实例独占的共享文件（版本目录 / 没人再用的 JRE）。
/// 只删 plan 里判定为"没人用"的那些；删不动也不报错（最多是没释放空间）。
#[tauri::command]
pub async fn cleanup_instance_shared(instance_id: String) -> Result<Value, String> {
    crate::fsutil::validate_id(&instance_id, "实例")?;
    let plan = plan_instance_cleanup(instance_id.clone()).await?;
    let dir = data_dir().await?;
    let mut freed = 0u64;

    if plan["canCleanVersion"].as_bool().unwrap_or(false) {
        let mc = plan["mcVersion"].as_str().unwrap_or("");
        if !mc.is_empty() {
            let path = dir.join("versions").join(mc);
            let n = dir_bytes(&path);
            if std::fs::remove_dir_all(&path).is_ok() {
                freed += n;
                crate::util::log_line(&format!("清理共享文件：删除版本 {mc}（{} 字节）", n));
            }
        }
    }
    if plan["canCleanJava"].as_bool().unwrap_or(false) {
        let jv = plan["javaVersion"].as_i64().unwrap_or(0);
        if jv > 0 {
            let path = dir.join("runtimes").join(format!("JRE-{jv}"));
            let n = dir_bytes(&path);
            if std::fs::remove_dir_all(&path).is_ok() {
                freed += n;
                crate::util::log_line(&format!("清理共享文件：删除 JRE-{jv}（{} 字节）", n));
            }
        }
    }
    Ok(serde_json::json!({ "freedBytes": freed }))
}
