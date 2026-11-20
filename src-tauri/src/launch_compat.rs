//! 启动兼容性纯逻辑辅助模块。
//!
//! 集中放置 Java 分档归一化、设备内存上限、Mojang 规则求值、旧版启动参数合并、
//! 版本库缺失检测与快照/预发布版判定等可单测逻辑，避免继续堆进 `launch.rs` / `instance_files.rs`。

use std::path::{Path, PathBuf};

/// 将 Minecraft 声明的 Java 主版本归一化到实际提供的 LTS / 目标档位。
///
/// 例如 MC 1.17 / 1.17.1 在 version.json 里声明 `majorVersion = 16`，而启动器只分发
/// 8 / 17 / 21 / 25 四档运行环境；同时防止 1.8.9/1.12.2/1.20.4 错选随包 Java 21。
pub(crate) fn normalize_required_java(major: i32) -> i32 {
    match major {
        ..=8 => 8,
        9..=17 => 17,
        18..=21 => 21,
        22..=25 => 25,
        other => other,
    }
}

/// 从 `/proc/meminfo` 文本解析物理内存总大小（MB）。
pub(crate) fn parse_meminfo_total_mb(meminfo: &str) -> Option<i32> {
    for line in meminfo.lines() {
        let Some(rest) = line.strip_prefix("MemTotal:") else {
            continue;
        };
        let kb: u64 = rest
            .split_whitespace()
            .next()
            .and_then(|s| s.parse::<u64>().ok())?;
        return Some((kb / 1024) as i32);
    }
    None
}

/// 读取当前设备物理内存总大小（MB）；非 Linux 或读取失败返回 `None`。
pub(crate) fn read_device_ram_mb() -> Option<i32> {
    #[cfg(target_os = "android")]
    {
        let text = std::fs::read_to_string("/proc/meminfo").ok()?;
        parse_meminfo_total_mb(&text)
    }
    #[cfg(not(target_os = "android"))]
    {
        None
    }
}

/// 按设备物理内存估算安全的 `-Xmx` 默认值与上限，并与实例/全局配置合并。
///
/// 安卓端 HotSpot 与 ART / Compose / Rust 同进程运行，盲目默认 2048M 或允许 8192M
/// 在 3–4GB 机型上会直接导致 `Could not reserve enough space` 或被 `lmkd` 秒杀。
pub(crate) fn clamp_memory_limits(
    instance_max: Option<i32>,
    global_max: Option<i32>,
    global_min: Option<i32>,
    device_total_mb: Option<i32>,
) -> (i32, i32) {
    let (default_max, ceiling) = match device_total_mb {
        Some(t) if t <= 3072 => (768, 1280),
        Some(t) if t <= 4096 => (1024, 1536),
        Some(t) if t <= 6144 => (1536, 2560),
        Some(t) if t <= 8192 => (2048, 3584),
        Some(t) => (2048, ((t * 45) / 100).clamp(3584, 6144)),
        None => (2048, 8192),
    };

    let max_mb = instance_max
        .or(global_max)
        .unwrap_or(default_max)
        .clamp(512, ceiling);
    let min_mb = global_min.unwrap_or(512).clamp(256, max_mb);
    (max_mb, min_mb)
}

/// 支持单/双引号与反斜杠转义的参数切分器，替代朴素 `split_whitespace()`。
pub(crate) fn split_shell_args(input: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quote: Option<char> = None;
    let mut escaped = false;
    let mut has_token = false;

    for ch in input.chars() {
        if escaped {
            cur.push(ch);
            escaped = false;
            has_token = true;
            continue;
        }
        match quote {
            Some('\'') => {
                if ch == '\'' {
                    quote = None;
                } else {
                    cur.push(ch);
                    has_token = true;
                }
            }
            Some('"') => {
                if ch == '"' {
                    quote = None;
                } else if ch == '\\' {
                    escaped = true;
                } else {
                    cur.push(ch);
                    has_token = true;
                }
            }
            None => {
                if ch.is_whitespace() {
                    if has_token || !cur.is_empty() {
                        out.push(std::mem::take(&mut cur));
                        has_token = false;
                    }
                } else if ch == '\'' || ch == '"' {
                    quote = Some(ch);
                    has_token = true;
                } else if ch == '\\' {
                    escaped = true;
                    has_token = true;
                } else {
                    cur.push(ch);
                    has_token = true;
                }
            }
            _ => {}
        }
    }
    if has_token || !cur.is_empty() {
        out.push(cur);
    }
    out
}

fn arch_matches(rule_arch: &str, target_arch: &str) -> bool {
    let r = rule_arch.trim().to_ascii_lowercase();
    match target_arch {
        "arm64-v8a" => matches!(r.as_str(), "arm64" | "aarch64"),
        "armeabi-v7a" => matches!(r.as_str(), "arm" | "arm32" | "armeabi-v7a"),
        "x86_64" => matches!(r.as_str(), "x86_64" | "amd64"),
        "x86" => matches!(r.as_str(), "x86" | "i386" | "i686"),
        other => r == other.to_ascii_lowercase(),
    }
}

/// 按安卓（Linux + 当前设备架构）求值版本库 `rules`。
pub(crate) fn rule_allows_android(rules: &[crate::version::Rule]) -> bool {
    rule_allows_target(rules, crate::java::get_device_arch())
}

pub(crate) fn rule_allows_target(rules: &[crate::version::Rule], target_arch: &str) -> bool {
    if rules.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in rules {
        if let Some(features) = &rule.features {
            if features
                .as_object()
                .is_some_and(|obj| obj.values().any(|v| v.as_bool() == Some(true)))
            {
                continue;
            }
        }
        if let Some(os) = &rule.os {
            if let Some(name) = &os.name {
                if !name.eq_ignore_ascii_case("linux") {
                    continue;
                }
            }
            if let Some(arch) = &os.arch {
                if !arch_matches(arch, target_arch) {
                    continue;
                }
            }
        }
        allowed = rule.action == "allow";
    }
    allowed
}

/// 对原始 JSON `rules` 数组求值（供加载器 profile `arguments` 使用）。
pub(crate) fn json_rules_allow_android(rules: &serde_json::Value) -> bool {
    json_rules_allow_target(rules, crate::java::get_device_arch())
}

pub(crate) fn json_rules_allow_target(rules: &serde_json::Value, target_arch: &str) -> bool {
    let Some(arr) = rules.as_array() else {
        return true;
    };
    if arr.is_empty() {
        return true;
    }
    let mut allowed = false;
    for rule in arr {
        if let Some(features) = rule.get("features").and_then(|v| v.as_object()) {
            if features.values().any(|v| v.as_bool() == Some(true)) {
                continue;
            }
        }
        if let Some(os) = rule.get("os").and_then(|v| v.as_object()) {
            if let Some(name) = os.get("name").and_then(|v| v.as_str()) {
                if !name.eq_ignore_ascii_case("linux") {
                    continue;
                }
            }
            if let Some(arch) = os.get("arch").and_then(|v| v.as_str()) {
                if !arch_matches(arch, target_arch) {
                    continue;
                }
            }
        }
        allowed = rule.get("action").and_then(|v| v.as_str()) == Some("allow");
    }
    allowed
}

/// 合并旧式 profile 的 `minecraftArguments` 附加项（仅提取 `--tweakClass` 与 `--versionType`）。
///
/// 避免把未展开的 `${auth_player_name}` 或重复的 `--username` 塞给 `joptsimple`，
/// 防止触发 `MultipleArgumentsForOptionException`。
pub(crate) fn merge_legacy_game_args(vanilla_args: &mut Vec<String>, legacy_tokens: &[String]) {
    let mut i = 0;
    while i < legacy_tokens.len() {
        let key = legacy_tokens[i].as_str();
        if key == "--tweakClass" {
            if let Some(val) = legacy_tokens.get(i + 1) {
                if !val.contains("${") {
                    vanilla_args.push("--tweakClass".to_string());
                    vanilla_args.push(val.clone());
                }
                i += 2;
                continue;
            }
        } else if key == "--versionType" {
            if let Some(val) = legacy_tokens.get(i + 1) {
                if !val.contains("${") && !vanilla_args.iter().any(|a| a == "--versionType") {
                    vanilla_args.push("--versionType".to_string());
                    vanilla_args.push(val.clone());
                }
                i += 2;
                continue;
            }
        }
        i += 1;
    }
}

fn normalize_java_home_input(raw: &str) -> Option<PathBuf> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let p = PathBuf::from(trimmed);
    if p.file_name().and_then(|n| n.to_str()) == Some("java") {
        if let Some(parent) = p.parent() {
            if parent.file_name().and_then(|n| n.to_str()) == Some("bin") {
                return parent.parent().map(|h| h.to_path_buf());
            }
        }
    }
    Some(p)
}

fn read_major_version_from_release(home: &Path) -> Option<i32> {
    let text = std::fs::read_to_string(home.join("release")).ok()?;
    for line in text.lines() {
        let Some(rest) = line.strip_prefix("JAVA_VERSION=") else {
            continue;
        };
        let ver = rest.trim().trim_matches('"');
        if let Some(after_1) = ver.strip_prefix("1.") {
            if let Some(v) = after_1.split(['.', '_', '+', '-']).next().and_then(|s| s.parse::<i32>().ok()) {
                return Some(v);
            }
        }
        if let Some(v) = ver.split(['.', '_', '+', '-']).next().and_then(|s| s.parse::<i32>().ok()) {
            return Some(v);
        }
    }
    None
}

/// 解析用户手动指定的 Java 路径（实例级优先，全局次之），校验目录包含 `libjli.so`。
pub(crate) fn resolve_custom_java_runtime(
    instance_java: Option<&str>,
    global_java: Option<&str>,
    required_java: i32,
) -> Option<crate::models::Runtime> {
    for raw in [instance_java, global_java].into_iter().flatten() {
        let Some(home) = normalize_java_home_input(raw) else {
            continue;
        };
        if !crate::java::has_libjli(&home) {
            continue;
        }
        let major = read_major_version_from_release(&home).unwrap_or(required_java);
        return Some(crate::models::Runtime {
            name: format!("Custom-JRE-{major}"),
            version_string: format!("{major}.0.0"),
            java_version: major,
            arch: crate::java::get_device_arch().to_string(),
            path: home.to_string_lossy().to_string(),
            is_internal: false,
            is_ported: true,
        });
    }
    None
}

/// 检测设备是否具备高通 Adreno GPU（仅该 GPU 支持 Turnip 驱动）。
pub(crate) fn detect_adreno_gpu() -> bool {
    #[cfg(target_os = "android")]
    {
        Path::new("/sys/class/kgsl/kgsl-3d0").exists() || Path::new("/dev/kgsl-3d0").exists()
    }
    #[cfg(not(target_os = "android"))]
    {
        false
    }
}

/// 按依赖顺序返回 JRE 下所有存在的 `.so` 目录（JDK 8 与 JDK 9+ 布局统一兼容）。
///
/// 必须先 `*/server`（`libjvm.so`），再 `*/jli`，最后主目录与 `*/client`。
pub(crate) fn jre_lib_subdirs(jre_home: &Path) -> Vec<PathBuf> {
    const REL_DIRS: &[&str] = &[
        "lib/server",
        "lib/aarch64/server",
        "lib/arm/server",
        "lib/amd64/server",
        "lib/x86_64/server",
        "lib/arm64/server",
        "lib/aarch64/jli",
        "lib/arm/jli",
        "lib/amd64/jli",
        "lib/x86_64/jli",
        "lib/arm64/jli",
        "lib",
        "lib/aarch64",
        "lib/arm",
        "lib/amd64",
        "lib/x86_64",
        "lib/arm64",
        "lib/client",
        "lib/aarch64/client",
        "lib/arm/client",
    ];
    REL_DIRS
        .iter()
        .map(|rel| jre_home.join(rel))
        .filter(|p| p.is_dir())
        .collect()
}

/// 核对客户端 `<mc>.jar` 是否存在且非空。
pub(crate) fn version_client_jar_present(data_dir: &Path, mc_version: &str) -> bool {
    if mc_version.is_empty() {
        return false;
    }
    let jar = data_dir
        .join("versions")
        .join(mc_version)
        .join(format!("{mc_version}.jar"));
    std::fs::metadata(jar).map(|m| m.len() > 0).unwrap_or(false)
}

/// 从 `version.json` 解析并统计缺失依赖库数（供 `instance_files.rs` 检查用）。
pub(crate) fn missing_version_libraries_from_json(
    data_dir: &Path,
    version_json: &Path,
) -> Option<usize> {
    let text = std::fs::read_to_string(version_json).ok()?;
    let ver = serde_json::from_str::<crate::version::MinecraftVersion>(&text).ok()?;
    Some(count_missing_version_libraries(data_dir, &ver).1)
}

/// 统计当前版本经 Mojang `rules` 过滤后缺失的依赖库数量 `(已有, 缺失)`。
pub(crate) fn count_missing_version_libraries(
    data_dir: &Path,
    version_info: &crate::version::MinecraftVersion,
) -> (usize, usize) {
    let libs_dir = data_dir.join("libraries");
    let Some(libs) = &version_info.libraries else {
        return (0, 0);
    };
    let mut ok = 0usize;
    let mut missing = 0usize;
    for lib in libs {
        if lib.name.starts_with("org.lwjgl:") || lib.name.starts_with("org.lwjgl.lwjgl:") {
            continue;
        }
        if let Some(rules) = &lib.rules {
            if !rule_allows_android(rules) {
                continue;
            }
        }
        if let Some(art) = lib.downloads.as_ref().and_then(|d| d.artifact.as_ref()) {
            let p = libs_dir.join(&art.path);
            let exists = std::fs::metadata(p).map(|m| m.len() > 0).unwrap_or(false);
            if exists {
                ok += 1;
            } else {
                missing += 1;
            }
        }
    }
    (ok, missing)
}

/// 解析类似 `3.4.3` 的语义版本三元组并比较是否 `>= target`（避免 `"3310"` 拼串误判）。
pub(crate) fn semver_ge(ver: &str, target: (u32, u32, u32)) -> bool {
    let mut nums = ver
        .trim()
        .split(['.', '-', '_', '+'])
        .map(|s| s.parse::<u32>().ok());
    let major = nums.next().flatten().unwrap_or(0);
    let minor = nums.next().flatten().unwrap_or(0);
    let patch = nums.next().flatten().unwrap_or(0);
    (major, minor, patch) >= target
}

/// 按 MC 版本号（含 `YYwWWa` 快照、`26.x` 与 `-pre`/`-rc`）推断所需的 Java 档位。
pub(crate) fn required_java_for_mc_str(mc_version: &str) -> i32 {
    let v = mc_version.trim();
    if v.is_empty() {
        return 8;
    }
    // 传统每周快照：`24w14a` / `25w02a` / `26w01a`
    if let Some((yy_s, rest)) = v.split_once('w') {
        if yy_s.len() == 2 && yy_s.chars().all(|c| c.is_ascii_digit()) {
            if let (Ok(yy), Ok(ww)) = (
                yy_s.parse::<u32>(),
                rest.chars().take_while(|c| c.is_ascii_digit()).collect::<String>().parse::<u32>(),
            ) {
                return if yy >= 26 {
                    25
                } else if yy > 24 || (yy == 24 && ww >= 14) {
                    21
                } else if yy > 21 || (yy == 21 && ww >= 19) {
                    17
                } else {
                    8
                };
            }
        }
    }

    let core = v.split(['-', '_', ' ']).next().unwrap_or(v);
    let mut it = core.split('.').map(|p| p.parse::<i32>().unwrap_or(0));
    let major = it.next().unwrap_or(1);
    let minor = it.next().unwrap_or(0);
    let patch = it.next().unwrap_or(0);
    if major >= 26 || major > 1 {
        return 25;
    }
    if minor >= 21 {
        21
    } else if minor == 20 {
        if patch >= 5 { 21 } else { 17 }
    } else if minor >= 17 {
        17
    } else {
        8
    }
}

/// 清空上一局启动遗留到当前进程的渲染器 / 钩子环境变量，避免切换渲染器或版本后串味。
pub(crate) fn clear_stale_launch_env() {
    const VARS: &[&str] = &[
        "LIBGL_ES",
        "LIBGL_MIPMAP",
        "LIBGL_NOERROR",
        "LIBGL_DRIVERS_PATH",
        "MG_DIR_PATH",
        "MG_PLUGIN_STATUS",
        "FCL_VERSION_CODE",
        "ZALITH_VERSION_CODE",
        "PGW_VERSION_CODE",
        "GALLIUM_DRIVER",
        "MESA_LOADER_DRIVER_OVERRIDE",
        "MESA_GLSL_CACHE_DIR",
        "force_glsl_extensions_warn",
        "allow_higher_compat_version",
        "allow_glsl_extension_directive_midshader",
        "VTEST_SOCKET_NAME",
        "POJAV_RENDERER",
        "QOOKIX_RENDERER",
        "POJAV_LOAD_TURNIP",
        "POJAV_EMUI_ITERATOR_MITIGATE",
        "POJAV_VSYNC_IN_ZINK",
        "LD_LIBRARY_PATH",
        "SDL_VIDEODRIVER",
        "POJAV_NATIVEDIR",
        "VULKAN_DRIVER",
        "SDL_OPENGL_LIBRARY",
        "SDL_EGL_LIBRARY",
        "POJAVEXEC_EGL",
        "POJAVEXEC_OSMESA",
        "POJAVEXEC_SDL3",
        "FORCE_VSYNC",
    ];
    for var in VARS {
        std::env::remove_var(var);
    }
}

/// 按最终选定的渲染器写入隔离后的环境变量（绝不混入 GL4ES / MobileGlues / Zink 彼此的参数）。
pub(crate) fn apply_renderer_env(
    renderer: &str,
    data_dir: &str,
    native_dir_str: &str,
    renderer_plugin_libs: Option<&PathBuf>,
    mg_dir: Option<String>,
    env_map: &mut std::collections::HashMap<String, String>,
) {
    match renderer {
        "libmobileglues.so" | "mobileglues" => {
            if let Some(dir) = mg_dir {
                env_map.insert("MG_DIR_PATH".to_string(), dir);
            }
            env_map.insert("MG_PLUGIN_STATUS".to_string(), "1".to_string());
            env_map.insert("FCL_VERSION_CODE".to_string(), "1335".to_string());
            env_map.insert("ZALITH_VERSION_CODE".to_string(), "2000".to_string());
            env_map.insert("PGW_VERSION_CODE".to_string(), "1".to_string());
            let egl_path = format!("{native_dir_str}/libmobileglues.so");
            env_map.insert("SDL_EGL_LIBRARY".to_string(), egl_path.clone());
            env_map.insert(
                "POJAVEXEC_EGL".to_string(),
                if renderer_plugin_libs.is_some() {
                    egl_path
                } else {
                    "libmobileglues.so".to_string()
                },
            );
        }
        "vulkan_zink" => {
            env_map.insert("GALLIUM_DRIVER".to_string(), "zink".to_string());
            env_map.insert("MESA_LOADER_DRIVER_OVERRIDE".to_string(), "zink".to_string());
            let cache_dir = format!("{}/cache", data_dir.trim_end_matches('/'));
            env_map.insert("MESA_GLSL_CACHE_DIR".to_string(), cache_dir.clone());
            env_map.insert("force_glsl_extensions_warn".to_string(), "true".to_string());
            env_map.insert("allow_higher_compat_version".to_string(), "true".to_string());
            env_map.insert(
                "allow_glsl_extension_directive_midshader".to_string(),
                "true".to_string(),
            );
            env_map.insert("VTEST_SOCKET_NAME".to_string(), format!("{cache_dir}/.virgl_test"));
            if detect_adreno_gpu() {
                env_map.insert("POJAV_LOAD_TURNIP".to_string(), "1".to_string());
            }
            env_map.insert("LIBGL_ES".to_string(), "3".to_string());
            if let Some(dir) = renderer_plugin_libs {
                let osmesa = dir.join("libOSMesa.so");
                if osmesa.exists() {
                    env_map.insert(
                        "POJAVEXEC_OSMESA".to_string(),
                        osmesa.to_string_lossy().to_string(),
                    );
                }
            }
        }
        _ => {
            env_map.insert("LIBGL_MIPMAP".to_string(), "3".to_string());
            env_map.insert("LIBGL_NOERROR".to_string(), "1".to_string());
            env_map.insert("LIBGL_ES".to_string(), "3".to_string());
        }
    }
}

/// 在多个候选目录中按顺序查找已安装的原生工具库（如 `libthreaddump.so`）。
pub(crate) fn find_agent_path(dirs: &[&str], filename: &str) -> Option<PathBuf> {
    for dir in dirs {
        if dir.trim().is_empty() {
            continue;
        }
        let candidate = Path::new(dir).join(filename);
        if candidate.is_file() {
            return Some(candidate);
        }
    }
    None
}

/// 将非空目录按序去重压入列表，保证优先目录顺序不被打乱。
pub(crate) fn push_unique_dir(dirs: &mut Vec<String>, dir: &str) {
    let trimmed = dir.trim();
    if !trimmed.is_empty() && !dirs.iter().any(|existing| existing == trimmed) {
        dirs.push(trimmed.to_string());
    }
}

/// 解析 JRE 目录名或 release 文件的主版本号（如 `JRE-25` -> 25，`JRE-8` -> 8），用于数值降序排列。
pub(crate) fn parse_jre_version_rank(home: &Path) -> u32 {
    if let Some(name) = home.file_name().and_then(|n| n.to_str()) {
        let mut digits = String::new();
        for ch in name.chars().rev() {
            if ch.is_ascii_digit() {
                digits.push(ch);
            } else if !digits.is_empty() {
                break;
            }
        }
        if !digits.is_empty() {
            let rev: String = digits.chars().rev().collect();
            if let Ok(n) = rev.parse::<u32>() {
                return n;
            }
        }
    }
    read_major_version_from_release(home)
        .filter(|&v| v > 0)
        .map(|v| v as u32)
        .unwrap_or(0)
}

/// 为缺少 `downloads.artifact` 的 Maven 坐标库条目补齐标准 artifact（兼容旧版 Forge `versionInfo`）。
pub(crate) fn patch_maven_library_downloads(lib: &mut serde_json::Value) {
    if lib
        .get("downloads")
        .and_then(|d| d.get("artifact"))
        .is_some_and(|a| !a.is_null())
    {
        return;
    }
    let Some(name) = lib.get("name").and_then(|n| n.as_str()).map(str::to_string) else {
        return;
    };
    let (coord, ext) = match name.split_once('@') {
        Some((c, e)) if !e.is_empty() => (c, e),
        _ => (name.as_str(), "jar"),
    };
    let parts: Vec<&str> = coord.split(':').collect();
    if parts.len() < 3 {
        return;
    }
    let (group, artifact, version) = (parts[0], parts[1], parts[2]);
    let classifier = parts.get(3).copied().filter(|s| !s.is_empty());
    let file = match classifier {
        Some(c) => format!("{artifact}-{version}-{c}.{ext}"),
        None => format!("{artifact}-{version}.{ext}"),
    };
    let rel_path = format!("{}/{artifact}/{version}/{file}", group.replace('.', "/"));
    let default_repo = if group.starts_with("net.minecraftforge") {
        "https://maven.minecraftforge.net/"
    } else if group.starts_with("net.neoforged") {
        "https://maven.neoforged.net/releases/"
    } else {
        "https://libraries.minecraft.net/"
    };
    let base = lib
        .get("url")
        .and_then(|u| u.as_str())
        .filter(|u| !u.trim().is_empty())
        .unwrap_or(default_repo);
    let url = format!("{}/{}", base.trim_end_matches('/'), rel_path);
    let artifact_obj = serde_json::json!({
        "path": rel_path,
        "sha1": "",
        "size": 0,
        "url": url,
    });
    if let Some(obj) = lib.as_object_mut() {
        let dl = obj
            .entry("downloads")
            .or_insert_with(|| serde_json::json!({}));
        if let Some(dl_obj) = dl.as_object_mut() {
            dl_obj.insert("artifact".to_string(), artifact_obj);
        }
    }
}

/// 判断 versions 子目录名是否属于指定加载器（避免 `forge` 误匹配 `neoforge`）。
pub(crate) fn dir_matches_loader(name: &str, loader: &str) -> bool {
    match loader {
        "fabric" => name.contains("fabric-loader"),
        "quilt" => name.contains("quilt-loader"),
        "neoforge" => name.contains("neoforge"),
        "forge" => name.contains("forge") && !name.contains("neoforge"),
        other => name.contains(other),
    }
}

/// 按词边界匹配 `mc_version`（避免 `1.21` 误命中 `1.21.1-forge-...`）。
pub(crate) fn name_matches_mc_exact(name: &str, mc_version: &str) -> bool {
    if mc_version.is_empty() {
        return false;
    }
    let mut start = 0usize;
    while let Some(pos) = name[start..].find(mc_version) {
        let abs = start + pos;
        let before_ok = abs == 0
            || !name[..abs]
                .chars()
                .next_back()
                .is_some_and(|c| c.is_ascii_alphanumeric() || c == '.');
        let after_idx = abs + mc_version.len();
        let after_ok = after_idx == name.len()
            || !name[after_idx..]
                .chars()
                .next()
                .is_some_and(|c| c.is_ascii_alphanumeric() || c == '.');
        if before_ok && after_ok {
            return true;
        }
        start = abs + 1;
    }
    false
}

/// 核对本次目标 `{mc_version}` / `{loader_version}` 的 processors 客户端产物是否真实存在。
pub(crate) fn target_patched_artifact_exists(
    libs_dir: &Path,
    mc_version: &str,
    loader: &str,
    loader_version: &str,
) -> bool {
    let dirs: Vec<PathBuf> = match loader {
        "neoforge" => vec![
            libs_dir.join("net/neoforged/neoforge").join(loader_version),
            libs_dir.join("net/minecraft/client"),
        ],
        _ => {
            let full = if !mc_version.is_empty() && !loader_version.starts_with(mc_version) {
                format!("{mc_version}-{loader_version}")
            } else {
                loader_version.to_string()
            };
            vec![
                libs_dir.join("net/minecraftforge/forge").join(full),
                libs_dir.join("net/minecraft/client"),
            ]
        }
    };
    fn scan(dir: &Path, depth: u32, mc_version: &str, loader_version: &str) -> bool {
        if depth == 0 {
            return false;
        }
        let Ok(rd) = std::fs::read_dir(dir) else {
            return false;
        };
        for entry in rd.flatten() {
            let p = entry.path();
            if p.is_dir() {
                if scan(&p, depth - 1, mc_version, loader_version) {
                    return true;
                }
            } else if let Some(name) = p.file_name().and_then(|n| n.to_str()) {
                let is_patched_jar = name.ends_with(".jar")
                    && (name.contains("client-extra") || name.ends_with("-client.jar") || name.ends_with("-srg.jar"))
                    && (name.contains(mc_version)
                        || (!loader_version.is_empty() && name.contains(loader_version)));
                if is_patched_jar && std::fs::metadata(&p).is_ok_and(|m| m.len() > 0) {
                    return true;
                }
            }
        }
        false
    }
    dirs.iter()
        .any(|d| scan(d, 4, mc_version, loader_version))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::version::{OsRule, Rule};

    #[test]
    fn normalize_java_tiers() {
        assert_eq!(normalize_required_java(8), 8);
        assert_eq!(normalize_required_java(16), 17);
        assert_eq!(normalize_required_java(17), 17);
        assert_eq!(normalize_required_java(21), 21);
        assert_eq!(normalize_required_java(25), 25);
    }

    #[test]
    fn meminfo_and_clamp_respects_low_ram_devices() {
        let info = "MemTotal:        3840000 kB\nMemFree: 1234 kB\n";
        assert_eq!(parse_meminfo_total_mb(info), Some(3750));
        assert_eq!(clamp_memory_limits(None, None, None, Some(3750)), (1024, 512));
        assert_eq!(clamp_memory_limits(Some(4096), None, None, Some(3750)), (1536, 512));
        assert_eq!(clamp_memory_limits(None, Some(2048), Some(3072), Some(8000)), (2048, 2048));
        assert_eq!(clamp_memory_limits(None, None, None, None), (2048, 512));
    }

    #[test]
    fn split_shell_args_preserves_quotes_and_escapes() {
        let parsed = split_shell_args(r#"-DFoo="hello world" '-DBar=a b' -DBaz=x\ y"#);
        assert_eq!(parsed, vec!["-DFoo=hello world", "-DBar=a b", "-DBaz=x y"]);
    }

    #[test]
    fn rules_filter_macos_windows_features_and_arch() {
        let osx_only = vec![Rule {
            action: "allow".into(),
            features: None,
            os: Some(OsRule { name: Some("osx".into()), version: None, arch: None }),
        }];
        assert!(!rule_allows_target(&osx_only, "arm64-v8a"));

        let arch_only = vec![Rule {
            action: "allow".into(),
            features: None,
            os: Some(OsRule { name: None, version: None, arch: Some("x86".into()) }),
        }];
        assert!(!rule_allows_target(&arch_only, "arm64-v8a"));
        assert!(rule_allows_target(&arch_only, "x86"));

        let linux_arm64 = vec![Rule {
            action: "allow".into(),
            features: None,
            os: Some(OsRule { name: Some("linux".into()), version: None, arch: Some("aarch64".into()) }),
        }];
        assert!(rule_allows_target(&linux_arm64, "arm64-v8a"));
    }

    #[test]
    fn merge_legacy_args_only_adds_tweaks_and_avoids_duplicates() {
        let mut vanilla = vec!["--username".into(), "Steve".into()];
        let legacy = vec![
            "--username".into(),
            "${auth_player_name}".into(),
            "--tweakClass".into(),
            "net.minecraftforge.fml.common.launcher.FMLTweaker".into(),
            "--versionType".into(),
            "Forge".into(),
        ];
        merge_legacy_game_args(&mut vanilla, &legacy);
        assert_eq!(
            vanilla,
            vec![
                "--username",
                "Steve",
                "--tweakClass",
                "net.minecraftforge.fml.common.launcher.FMLTweaker",
                "--versionType",
                "Forge",
            ]
        );
    }

    #[test]
    fn resolve_custom_java_accepts_bin_java_or_home() {
        let tmp = std::env::temp_dir().join("qookix-compat-custom-java");
        let _ = std::fs::remove_dir_all(&tmp);
        let jli_dir = tmp.join("lib").join("aarch64").join("jli");
        let bin_dir = tmp.join("bin");
        std::fs::create_dir_all(&jli_dir).unwrap();
        std::fs::create_dir_all(&bin_dir).unwrap();
        std::fs::write(jli_dir.join("libjli.so"), b"elf").unwrap();
        std::fs::write(tmp.join("release"), "JAVA_VERSION=\"1.8.0_412\"\n").unwrap();

        let java_bin = bin_dir.join("java").to_string_lossy().to_string();
        let rt = resolve_custom_java_runtime(Some(&java_bin), None, 8).unwrap();
        assert_eq!(rt.java_version, 8);
        assert_eq!(PathBuf::from(rt.path), tmp);
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn jre_subdirs_orders_server_before_lib() {
        let tmp = std::env::temp_dir().join("qookix-compat-jre-subdirs");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(tmp.join("lib/aarch64/server")).unwrap();
        std::fs::create_dir_all(tmp.join("lib/aarch64/jli")).unwrap();
        std::fs::create_dir_all(tmp.join("lib/aarch64")).unwrap();
        let dirs = jre_lib_subdirs(&tmp);
        assert!(dirs[0].ends_with("server"));
        assert!(dirs[1].ends_with("jli"));
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn semver_and_mc_java_snapshots() {
        assert!(semver_ge("3.4.3", (3, 4, 1)));
        assert!(semver_ge("3.4.1", (3, 4, 1)));
        assert!(!semver_ge("3.3.10", (3, 4, 1)));

        assert_eq!(required_java_for_mc_str("1.17-pre1"), 17);
        assert_eq!(required_java_for_mc_str("1.20.5-pre1"), 21);
        assert_eq!(required_java_for_mc_str("24w14a"), 21);
        assert_eq!(required_java_for_mc_str("25w02a"), 21);
        assert_eq!(required_java_for_mc_str("26.3-snapshot-3"), 25);
        assert_eq!(required_java_for_mc_str("1.12.2"), 8);
    }

    #[test]
    fn renderer_env_isolates_mobileglues_zink_and_gl4es() {
        let mut mg_env = std::collections::HashMap::new();
        apply_renderer_env(
            "libmobileglues.so",
            "/data/files",
            "/data/files/natives",
            None,
            Some("/data/files/mobileglues".into()),
            &mut mg_env,
        );
        assert_eq!(mg_env.get("MG_PLUGIN_STATUS").map(String::as_str), Some("1"));
        assert!(!mg_env.contains_key("LIBGL_MIPMAP"));
        assert!(!mg_env.contains_key("LIBGL_ES"));
        assert!(!mg_env.contains_key("POJAV_LOAD_TURNIP"));

        let mut zink_env = std::collections::HashMap::new();
        apply_renderer_env("vulkan_zink", "/data/files", "/data/files/natives", None, None, &mut zink_env);
        assert_eq!(zink_env.get("GALLIUM_DRIVER").map(String::as_str), Some("zink"));
        assert!(!zink_env.contains_key("LIBGL_MIPMAP"));
        assert!(!zink_env.contains_key("POJAV_LOAD_TURNIP"));

        let mut gl4es_env = std::collections::HashMap::new();
        apply_renderer_env("opengles2", "/data/files", "/data/files/natives", None, None, &mut gl4es_env);
        assert_eq!(gl4es_env.get("LIBGL_MIPMAP").map(String::as_str), Some("3"));
        assert!(!gl4es_env.contains_key("MG_PLUGIN_STATUS"));
    }

    #[test]
    fn find_agent_and_unique_dir_work() {
        let tmp = std::env::temp_dir().join("qookix-compat-agent");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();
        let f = tmp.join("libthreaddump.so");
        std::fs::write(&f, b"so").unwrap();
        let hit = find_agent_path(&["", "/nonexistent/dir", &tmp.to_string_lossy()], "libthreaddump.so");
        assert_eq!(hit, Some(f));
        let mut dirs = Vec::new();
        push_unique_dir(&mut dirs, "/a");
        push_unique_dir(&mut dirs, "/b");
        push_unique_dir(&mut dirs, "/a");
        assert_eq!(dirs, vec!["/a", "/b"]);
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn jre_rank_and_maven_artifact_synthesis() {
        let mut names = vec![PathBuf::from("JRE-8"), PathBuf::from("JRE-25"), PathBuf::from("JRE-17"), PathBuf::from("JRE-21")];
        names.sort_by(|a, b| parse_jre_version_rank(b).cmp(&parse_jre_version_rank(a)));
        assert_eq!(
            names.iter().map(|p| p.file_name().unwrap().to_str().unwrap()).collect::<Vec<_>>(),
            vec!["JRE-25", "JRE-21", "JRE-17", "JRE-8"]
        );

        let mut lib = serde_json::json!({
            "name": "net.minecraftforge:forge:1.12.2-14.23.5.2860:universal",
            "url": "https://maven.minecraftforge.net/"
        });
        patch_maven_library_downloads(&mut lib);
        assert_eq!(
            lib["downloads"]["artifact"]["path"],
            "net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-universal.jar"
        );
        assert_eq!(
            lib["downloads"]["artifact"]["url"],
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.12.2-14.23.5.2860/forge-1.12.2-14.23.5.2860-universal.jar"
        );
    }

    #[test]
    fn exact_loader_lookup_and_profile_rules_work() {
        let tmp = std::env::temp_dir().join("qookix-compat-loader-exact");
        let _ = std::fs::remove_dir_all(&tmp);
        std::fs::create_dir_all(&tmp).unwrap();

        for (dir, inh, body) in [
            ("neoforge-21.1.50", "1.21.1", "{}"),
            (
                "1.21.1-forge-52.0.1",
                "1.21.1",
                r#"{"inheritsFrom":"1.21.1","arguments":{"jvm":[{"rules":[{"action":"allow","os":{"name":"osx"}}],"value":"-XstartOnFirstThread"},{"rules":[{"action":"allow","os":{"name":"linux"}}],"value":"-DLINUX_OK"}],"game":[]}}"#,
            ),
            ("1.12.2-forge-14.23.5.2860", "1.12.2", r#"{"inheritsFrom":"1.12.2","minecraftArguments":"--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker"}"#),
        ] {
            let p = tmp.join(dir);
            std::fs::create_dir_all(&p).unwrap();
            let txt = if body == "{}" { format!(r#"{{"inheritsFrom":"{inh}"}}"#) } else { body.to_string() };
            std::fs::write(p.join(format!("{dir}.json")), txt).unwrap();
        }

        let forge_hit = crate::loader_install::find_loader_profile_exact(&tmp, "forge", "1.21.1", Some("52.0.1")).unwrap();
        assert_eq!(forge_hit.file_name().unwrap().to_string_lossy(), "1.21.1-forge-52.0.1");
        assert!(!crate::loader_install::profile_is_legacy(&forge_hit));
        let args = crate::loader_install::profile_arguments(&forge_hit, &tmp.join("libs"), "1.21.1", ":");
        assert_eq!(args.jvm, vec!["-DLINUX_OK"]);

        let neo = crate::loader_install::find_loader_profile_exact(&tmp, "neoforge", "1.21.1", None).unwrap();
        assert_eq!(neo.file_name().unwrap().to_string_lossy(), "neoforge-21.1.50");
        assert!(crate::loader_install::find_loader_profile_exact(&tmp, "forge", "1.21", None).is_none());

        let legacy = crate::loader_install::find_loader_profile_exact(&tmp, "forge", "1.12.2", None).unwrap();
        assert!(crate::loader_install::profile_is_legacy(&legacy));
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
