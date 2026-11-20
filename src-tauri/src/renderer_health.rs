//! 渲染器健康检查。
//!
//! 目的：渲染器（GL4ES / MobileGlues / Zink）对某个 MC 版本的兼容性是不对称的，
//! 选错了往往**不会给出清楚的错误提示** —— 表现可能是世界一片虚空、画面异常、
//! 着色器全黑，甚至直接崩在启动阶段（退出码非 0）。玩家很难自己判断「该换渲染器」。
//!
//! 这里做的是：游戏退出后扫一遍本次启动日志，找渲染器失败的**确定性特征**，
//! 再和「按版本推荐的渲染器」对比 —— 只有**两件事同时成立**才提示：
//!   1. 日志里确实有失败证据；
//!   2. 当前用的渲染器 ≠ 这个版本推荐的渲染器。
//!
//! 第 2 条是关键的安全阀：配置本来就正确的实例（比如 26.3 + MobileGlues）即使日志里
//! 混进了无害噪音也不会被提示换渲染器。
//!
//! 前端在玩家回到启动器后弹窗询问，确认后只改**这一个实例**的 `renderer` 字段。

use crate::models::RendererIssue;
use crate::settings::get_data_dir;

/// 某个渲染器的失败特征。
///
/// 只放「几乎只在真出问题时出现」的串，避免误报：
/// 例如不能放 "Exception"（26.x 启动时 Vulkan 后端必然失败一次再回退，属正常噪音）。
fn failure_patterns(renderer_key: &str) -> &'static [&'static str] {
    match renderer_key {
        // MobileGlues：GLSL 翻译失败 → 编译/链接报错，开着「忽略错误」时它还会
        // 「假装成功」给游戏一个空壳程序（世界虚空、贴图全黑就是这么来的）。
        "mobileglues" => &[
            "has invalid ELF alignment",
            "dlopen failed",
            "compilation failed",       // "Shader 4 compilation failed:"
            "linking failed",           // "Program 5 linking failed:"
            "Invalid #version",         // 1.8.9 的 post 着色器实测
            "undeclared identifier",    // 我们修过的 '_uniform' 那类
            "Failed to load shader",    // MC 判定「着色器无效」
            "Failed to load required shader programs",
        ],
        // GL4ES：老版本上表现最好；但 26.x 的新着色器体系它在第一步就过不去。
        "opengles2" => &[
            "has invalid ELF alignment",
            "dlopen failed",
            "Failed to load required shader programs",
            "BackendCreationException",
            "GL_INVALID_VALUE",
            "Could not initialize GL",
        ],
        // Zink + Turnip：需要 Adreno + Vulkan，缺一样就是黑屏。
        "vulkan_zink" => &[
            "has invalid ELF alignment",
            "dlopen failed",
            "VK_ERROR",
            "Failed to create Vulkan",
            "vkCreateInstance",
        ],
        _ => &[],
    }
}

/// 失败特征 → 给用户看的解释。
fn failure_reason(renderer_key: &str, pattern: &str) -> String {
    if pattern == "has invalid ELF alignment" {
        return "设备的 16KB 内存页与当前原生渲染器或依赖库（ELF 4KB 对齐）不兼容，请更新支持 16KB 页的渲染器插件".to_string();
    }
    if pattern == "dlopen failed" {
        return "原生渲染器或依赖库加载失败（dlopen failed），请检查 ABI / 16KB 页对齐或重新解压依赖库".to_string();
    }
    match (renderer_key, pattern) {
        ("mobileglues", "Invalid #version") => {
            "MobileGlues 翻译不了这个版本的着色器（日志里是 `Invalid #version`），游戏会因此画面异常甚至崩溃".to_string()
        }
        ("mobileglues", "undeclared identifier") => {
            "MobileGlues 在翻译着色器时改坏了代码（`undeclared identifier`），地形/实体可能根本画不出来"
                .to_string()
        }
        ("mobileglues", _) => {
            "MobileGlues 的着色器翻译失败了（日志里有编译/链接错误），画面可能异常或世界一片虚空"
                .to_string()
        }
        ("opengles2", "BackendCreationException") | ("opengles2", "Failed to load required shader programs") => {
            "GL4ES 跑不动这个版本的新着色器体系，游戏会无法正常渲染".to_string()
        }
        ("opengles2", _) => {
            "GL4ES 在本次启动里报了 OpenGL 错误，渲染很可能不正常".to_string()
        }
        ("vulkan_zink", _) => {
            "Zink 初始化 Vulkan 失败（需要 Adreno GPU + 可用的 Vulkan 驱动）".to_string()
        }
        _ => "本次启动日志里出现了渲染器失败的特征".to_string(),
    }
}

/// 显示名 → 键（解析启动日志里那行「渲染器：MobileGlues（…）」）。
fn key_from_display_name(name: &str) -> Option<&'static str> {
    let n = name.trim();
    if n.contains("MobileGlues") {
        Some("mobileglues")
    } else if n.contains("Zink") {
        Some("vulkan_zink")
    } else if n.contains("GL4ES") {
        Some("opengles2")
    } else {
        None
    }
}

/// 从启动日志里找本次实际用的渲染器。
///
/// [`crate::launch`] 在设置好渲染器后会写一行 `渲染器：<显示名>（<理由>）`，
/// 这是最可靠的来源（用户可能中途改过设置，按当前设置推断会张冠李戴）。
/// 老日志没有这行时回退到「按实例设置推算」。
fn detect_used_renderer(log: &str, instance: &crate::models::MinecraftProfile) -> String {
    for line in log.lines() {
        if let Some(rest) = line.split("渲染器：").nth(1) {
            let name = rest.split(['（', '(']).next().unwrap_or(rest);
            if let Some(key) = key_from_display_name(name) {
                return key.to_string();
            }
        }
    }
    let mut why = String::new();
    let internal = crate::launch::resolve_renderer(Some(instance), &mut why);
    // 内部名（libmobileglues.so / opengles2 / vulkan_zink）→ 键
    if internal.contains("mobileglues") {
        "mobileglues".to_string()
    } else if internal.contains("zink") {
        "vulkan_zink".to_string()
    } else {
        "opengles2".to_string()
    }
}

/// 扫描日志，收集失败证据（原文行，去重、去空、限长限量）。
fn collect_evidence(log: &str, renderer_key: &str) -> Vec<String> {
    let patterns = failure_patterns(renderer_key);
    let mut hits: Vec<String> = Vec::new();
    for line in log.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if patterns.iter().any(|p| trimmed.contains(p)) {
            let shown: String = trimmed.chars().take(160).collect();
            if !hits.contains(&shown) {
                hits.push(shown);
            }
            if hits.len() >= 5 {
                break;
            }
        }
    }
    hits
}

/// Detect 16KB page-size ELF alignment or dynamic library loading failures in launch logs.
pub(crate) fn detect_native_load_issue(log: &str) -> Option<&'static str> {
    if log.contains("has invalid ELF alignment") {
        Some("ELF 16KB page alignment mismatch")
    } else if log.contains("dlopen failed") {
        Some("native library dlopen failure")
    } else {
        None
    }
}

/// 检查一个实例本次启动的渲染器是否需要建议切换。
///
/// 返回 `Ok(None)` 的情况：实例不存在、日志找不到、没有失败证据、
/// 或者当前渲染器**已经是**这个版本推荐的（没什么可切的）。
pub async fn check(instance_id: &str) -> Result<Option<RendererIssue>, String> {
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| e.to_string())?;

    let instance = crate::instances::get_instance(instance_id)
        .await
        .map_err(|e| e.to_string())?;

    // 直接读**磁盘上的启动日志本身**：
    // `read_instance_log` 只给尾部一段（还可能拼上游戏自己的 latest.log），
    // 而「本次用的是哪个渲染器」那行写在日志**开头**，被截掉后就只能靠实例设置猜 ——
    // 用户改过设置时会猜错。启动日志通常只有几十 KB，整份读进来最稳。
    let data_dir = crate::settings::get_data_dir().await.map_err(|e| e.to_string())?;
    let log_path = std::path::Path::new(&data_dir)
        .join("logs")
        .join(format!("launch-{instance_id}.log"));
    let log = tokio::task::spawn_blocking(move || -> String {
        match std::fs::read(&log_path) {
            Ok(bytes) => {
                // 上限 8MB：再大就没意义了，这里只要找几行特征
                let cap = bytes.len().min(8 * 1024 * 1024);
                String::from_utf8_lossy(&bytes[..cap]).into_owned()
            }
            Err(_) => String::new(),
        }
    })
    .await
    .unwrap_or_default();
    if log.trim().is_empty() {
        return Ok(None);
    }

    let used_key = detect_used_renderer(&log, &instance);
    if let Some(native_issue) = detect_native_load_issue(&log) {
        crate::util::log_line(&format!(
            "[launcher] 原生库加载诊断：实例={} 问题={}（若为 16KB 设备需更换 16KB 对齐的 prebuilt .so/渲染器插件）",
            instance_id, native_issue
        ));
    }
    let evidence = collect_evidence(&log, &used_key);
    let recommended = crate::launch::auto_renderer_for(&instance.mc_version).to_string();
    crate::util::log_line(&format!(
        "[launcher] 渲染器健康检查：实例={} 版本={} 本次={} 推荐={} 证据={} 条",
        instance_id,
        instance.mc_version,
        used_key,
        recommended,
        evidence.len()
    ));
    if evidence.is_empty() {
        return Ok(None);
    }

    let recommended = crate::launch::auto_renderer_for(&instance.mc_version).to_string();
    // 已经用的是推荐渲染器 → 没什么可切的，别打扰用户
    if recommended == used_key {
        return Ok(None);
    }
    // 实例**当前设置**已经指向推荐渲染器 → 说明用户已经切过了，日志是历史记录，
    // 不该拿旧账再弹一次（否则每次打开启动器都会弹）。
    let mut why = String::new();
    if crate::launch::resolve_renderer(Some(&instance), &mut why) == recommended {
        return Ok(None);
    }

    // 推荐的那个渲染器本机没装（插件没装）时也不要提示 —— 提示了也切不过去
    if !renderer_installed(&recommended).await {
        return Ok(None);
    }

    let reason = failure_reason(&used_key, &evidence[0]);
    Ok(Some(RendererIssue {
        instance_id: instance_id.to_string(),
        used: used_key.clone(),
        used_name: crate::launch::renderer_display_name(&used_key).to_string(),
        recommended_name: crate::launch::renderer_display_name(&recommended).to_string(),
        recommended,
        reason,
        evidence,
        mc_version: instance.mc_version.clone(),
    }))
}

/// 检查**最近一次**启动的实例。
///
/// 用于「用户回到启动器」这类**没有退出事件**的场景：游戏线程死了、JVM 还挂着
/// （实测 1.8.9 + MobileGlues 就是这样，画面白屏卡在 GameActivity），
/// `launch://state` 的 `exited` 永远不会发 —— 只能靠用户手动关掉游戏回到启动器后，
/// 再按「最近的启动日志」去查。
///
/// 只认一小时内动过的日志，免得冷启动时翻出几天前的旧账。
pub async fn check_latest() -> Result<Option<RendererIssue>, String> {
    let data_dir = get_data_dir().await.map_err(|e| e.to_string())?;
    let dir = std::path::Path::new(&data_dir).join("logs");
    let Ok(entries) = std::fs::read_dir(&dir) else {
        return Ok(None);
    };

    let mut newest: Option<(String, std::time::SystemTime)> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
            continue;
        };
        if !(name.starts_with("launch-") && name.ends_with(".log")) {
            continue;
        }
        let Ok(modified) = entry.metadata().and_then(|m| m.modified()) else {
            continue;
        };
        if modified
            .elapsed()
            .map(|d| d.as_secs() > 3600)
            .unwrap_or(true)
        {
            continue;
        }
        if newest.as_ref().map(|(_, t)| modified > *t).unwrap_or(true) {
            newest = Some((name.to_string(), modified));
        }
    }

    let Some((name, _)) = newest else {
        return Ok(None);
    };
    let instance_id = name
        .trim_start_matches("launch-")
        .trim_end_matches(".log")
        .to_string();
    if instance_id.is_empty() {
        return Ok(None);
    }
    check(&instance_id).await
}

/// 推荐渲染器在本机是否可用。
///
/// 两处来源，和 `launch::renderer_available` 的判断口径保持一致：
///  - 渲染器插件目录（`files/plugins/qookix-renderer-*/<版本>/natives/`）；
///  - 随包 natives（`files/natives/`，GL4ES 一直在 APK 里；MG/Zink 走插件）。
///
/// 查不到就当没装 —— 宁可少提示一次，也不要提示一个切不过去的选项。
async fn renderer_installed(renderer_key: &str) -> bool {
    let internal = crate::launch::normalize_renderer(renderer_key);
    let (lib, plugin_key) = match internal.as_str() {
        "vulkan_zink" => ("libOSMesa.so", "vulkan_zink"),
        "libmobileglues.so" => ("libmobileglues.so", "mobileglues"),
        _ => ("libgl4es_114.so", "opengles2"),
    };
    let Ok(data_dir) = get_data_dir().await else {
        return false;
    };
    if let Some(dir) = crate::plugin::renderer_libs_dir(&data_dir, plugin_key) {
        if dir.join(lib).exists() {
            return true;
        }
    }
    // 随包 natives 的落地目录（launch 里同样以 `files/natives` 为兜底路径）
    std::path::Path::new(&data_dir).join("natives").join(lib).exists()
}
