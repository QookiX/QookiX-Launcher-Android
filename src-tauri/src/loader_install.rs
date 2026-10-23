//! 加载器（Forge / NeoForge / Fabric / Quilt / OptiFine）的**真实安装**。
//!
//! ## 为什么需要这个文件
//!
//! 在本次改动之前，`browse::install_loader` 只是把加载器的 jar **下载**到
//! `libraries/` 就结束了 —— 从来没有运行过 installer，也没有生成加载器自己的
//! 版本 profile。启动时 `launch.rs` 读的是 `instance.mc_version`（原版）的版本
//! JSON，`build_classpath` 也只用它拼 classpath，于是：
//!
//!   - 装了 Fabric 的实例，启动时跑的其实是**原版 MC**
//!   - 加载器 jar 只被 `instance_files.rs` 当作「已安装」的存在性标记
//!
//! 本模块把缺失的那段补上：
//!
//!   1. 用 Java 运行 installer jar（headless），生成加载器 profile
//!   2. 解析 profile（支持 `inheritsFrom` 继承原版）
//!   3. 启动时把加载器库并入 classpath，并以加载器的 mainClass 启动
//!
//! ## 为什么要「用 java 跑 installer」而不是自己拼 profile
//!
//! Forge / NeoForge 的 installer 不只是解压 —— 它会：
//!   - 生成带 `inheritsFrom` 的 profile JSON
//!   - 下载/校验一堆依赖库
//!   - 处理 1.12.2 及以下的特殊流程（universal jar + 补丁）
//!
//! 自己实现等于重写 Forge 的安装器，不可能与官方行为一致。
//! 所以正确做法是**真的去跑官方 installer**（这也是桌面版启动器的通用做法）。
//!
//! ## Android 上的关键约束（真机实测）
//!
//! `runtimes/<jre>/bin/java` 直接执行会报
//! `library "libjli.so" not found: needed by main executable`。
//! 必须带 `LD_LIBRARY_PATH=<jre>/lib` 才能跑起来（已在真机验证
//! `java -version` 成功输出）。见 [run_installer]。
//!
//! 另：`Command::output()` 是阻塞的，调用方必须放进
//! `tokio::task::spawn_blocking`，否则会卡住 tokio 的工作线程。

use std::path::{Path, PathBuf};
use std::process::Command;

use anyhow::{anyhow, Context, Result};

/// 加载器安装结果。
#[derive(Debug, Clone, serde::Serialize)]
pub struct LoaderInstallResult {
    /// 生成的 profile 名（如 `1.21.8-forge-52.0.0`），启动时用它作 `--version`。
    pub version_id: String,
    /// profile JSON 的路径。
    pub profile_path: String,
    /// 是否已存在（跳过安装）。
    pub already_installed: bool,
}

/// 运行一个 installer jar（headless），生成加载器 profile。
///
/// # 参数
/// - `java_bin`: `<jre>/bin/java` 绝对路径
/// - `jre_lib`: `<jre>/lib` 绝对路径（用于 `LD_LIBRARY_PATH`）
/// - `installer_jar`: installer jar 路径
/// - `work_dir`: 工作目录（installer 会在里面的 `versions/` 生成 profile）
/// - `extra_args`: 附加参数（Forge 客户端安装用 `--installClient`）
pub fn run_installer(
    java_bin: &Path,
    jre_lib: &Path,
    installer_jar: &Path,
    work_dir: &Path,
    extra_args: &[String],
) -> Result<()> {
    if !java_bin.exists() {
        return Err(anyhow!("java 可执行文件不存在：{}", java_bin.display()));
    }
    if !installer_jar.exists() {
        return Err(anyhow!("installer jar 不存在：{}", installer_jar.display()));
    }
    std::fs::create_dir_all(work_dir).ok();

    // Android 上必须显式给 libjli.so 的目录，否则报
    // "library libjli.so not found: needed by main executable"（真机实测）。
    let mut lib_path = jre_lib.to_string_lossy().to_string();
    if let Ok(existing) = std::env::var("LD_LIBRARY_PATH") {
        if !existing.is_empty() {
            lib_path = format!("{existing}:{lib_path}");
        }
    }

    let mut cmd = Command::new(java_bin);
    cmd.env("LD_LIBRARY_PATH", &lib_path)
        .current_dir(work_dir)
        .arg("-jar")
        .arg(installer_jar)
        .args(extra_args);

    let output = cmd
        .output()
        .with_context(|| format!("启动 installer 失败：{}", installer_jar.display()))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        let stdout = String::from_utf8_lossy(&output.stdout);
        return Err(anyhow!(
            "installer 退出码 {:?}\nstdout:\n{}\nstderr:\n{}",
            output.status.code(),
            truncate(&stdout, 2000),
            truncate(&stderr, 4000),
        ));
    }
    Ok(())
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}…（截断）", &s[..max])
    }
}

/// 加载器 profile 的命名规则（与桌面版 / 官方 installer 输出对齐）。
pub fn loader_version_id(loader: &str, mc_version: &str, loader_version: &str) -> String {
    match loader {
        "forge" => format!("{mc_version}-forge-{loader_version}"),
        "neoforge" => format!("{mc_version}-neoforge-{loader_version}"),
        // Fabric / Quilt 官方 installer 生成的 id 形如 `fabric-loader-0.16.0-1.21.8`
        "fabric" => format!("fabric-loader-{loader_version}-{mc_version}"),
        "quilt" => format!("quilt-loader-{loader_version}-{mc_version}"),
        // OptiFine 官方 installer 生成 `OptiFine_1.12.2_HD_U_G5`
        "optifine" => format!("OptiFine_{mc_version}_{loader_version}"),
        other => format!("{other}-{loader_version}-{mc_version}"),
    }
}

/// 加载器 profile 里期望的 mainClass（用于校验安装是否真的生成了）。
pub fn expected_main_class(loader: &str) -> Option<&'static str> {
    match loader {
        "forge" => Some("cpw.mods.bootstraplauncher.BootstrapLauncher"),
        "neoforge" => Some("cpw.mods.bootstraplauncher.BootstrapLauncher"),
        "fabric" => Some("net.fabricmc.loader.impl.launch.knot.KnotClient"),
        "quilt" => Some("org.quiltmc.loader.impl.launch.knot.KnotClient"),
        "optifine" => None,
        _ => None,
    }
}

/// 在 `versions/` 下查找已生成的加载器 profile 目录。
///
/// ⚠️ 2026-10-22 真机修（1.21.1 NeoForge：游戏能进、**一个模组都不加载**）：
/// 原来要求「目录名同时包含 loader 与 mc_version」。但 NeoForge 的 profile id 是
/// `neoforge-21.1.251` —— **不含 `1.21.1`** ✗，于是永远匹配不上：
///   `[launcher] 加载器=neoforge profile=<未找到> mainClass=net.minecraft.client.main.Main`
/// 结果静默退回原版主类，用户以为是模组装错了。
///
/// 现在改成：目录名含 loader 是必要条件；「是不是这个游戏的」优先看 profile 里的
/// `inheritsFrom`（权威依据），目录名带 mc_version 只作为兜底 —— 官方 installer 的
/// 命名本来就不可控，不能拿它当唯一判据。
pub fn find_loader_profile(
    versions_dir: &Path,
    loader: &str,
    mc_version: &str,
) -> Option<PathBuf> {
    let entries = std::fs::read_dir(versions_dir).ok()?;
    let needle = match loader {
        "fabric" => "fabric-loader".to_string(),
        "quilt" => "quilt-loader".to_string(),
        _ => loader.to_string(),
    };
    let mut best: Option<PathBuf> = None;
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let name = path.file_name()?.to_string_lossy().to_string();
        if !name.contains(&needle) {
            continue;
        }
        let json = path.join(format!("{name}.json"));
        if !json.is_file() {
            continue;
        }
        // 目录名命中就直接认（老行为）；否则读 profile 看 inheritsFrom。
        let by_name = name.contains(mc_version);
        let by_inherit = || -> bool {
            std::fs::read_to_string(&json)
                .ok()
                .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
                .and_then(|v| {
                    v.get("inheritsFrom")
                        .and_then(|i| i.as_str())
                        .map(|s| s == mc_version)
                })
                .unwrap_or(false)
        };
        if by_name || by_inherit() {
            best = Some(path);
        }
    }
    best
}

/// 读取加载器 profile 的 mainClass。
///
/// 启动时用它替代原版的 `net.minecraft.client.main.Main` ——
/// 这是「加载器真正生效」的关键一步（否则跑的还是原版）。
pub fn profile_main_class(profile_dir: &Path) -> Option<String> {
    let name = profile_dir.file_name()?.to_string_lossy().to_string();
    let json = profile_dir.join(format!("{name}.json"));
    let text = std::fs::read_to_string(&json).ok()?;
    let v: serde_json::Value = serde_json::from_str(&text).ok()?;
    v.get("mainClass")
        .and_then(|m| m.as_str())
        .map(|s| s.to_string())
}

/// 收集加载器 profile 里声明的库路径（相对 `libraries/`），供 classpath 使用。
///
/// 只收存在的文件：installer 声明的依赖可能还没下载，
/// 缺失的交给「补全文件」流程，classpath 里放不存在的路径会让 JVM 静默忽略
/// 但日志难查，所以这里直接过滤掉并计数返回。
pub fn profile_library_paths(
    profile_dir: &Path,
    libraries_dir: &Path,
) -> (Vec<PathBuf>, usize) {
    let mut paths = Vec::new();
    let mut missing = 0usize;
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return (paths, missing);
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        return (paths, missing);
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return (paths, missing);
    };
    let Some(libs) = v.get("libraries").and_then(|l| l.as_array()) else {
        return (paths, missing);
    };
    for lib in libs {
        let Some(path_rel) = lib
            .get("downloads")
            .and_then(|d| d.get("artifact"))
            .and_then(|a| a.get("path"))
            .and_then(|p| p.as_str())
        else {
            continue;
        };
        let full = libraries_dir.join(path_rel);
        if full.exists() {
            paths.push(full);
        } else {
            missing += 1;
        }
    }
    (paths, missing)
}

/// 把加载器 profile 的 libraries 解析成 [crate::version::Library]。
///
/// `download_libraries` 只接受这个结构，而 profile 是原始 JSON ——
/// 这里做一次转换，让加载器依赖也能走同一套下载（含并发与镜像回退）。
pub fn profile_libraries(profile_dir: &Path) -> Vec<crate::version::Library> {
    let mut out = Vec::new();
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        println!("[install] profile_libraries: 取不到目录名");
        return out;
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        println!("[install] profile_libraries: 读不到 {}", json.display());
        return out;
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        println!("[install] profile_libraries: JSON 解析失败 {}", json.display());
        return out;
    };
    let Some(libs) = v.get("libraries").and_then(|l| l.as_array()) else {
        // ⚠️ 2026-10-20：这条分支以前完全无声。真机上「依赖 0 个」的
        // 两种原因（libraries 字段缺失 vs 每个条目解析失败）必须能区分开，
        // 否则只能靠猜。
        println!(
            "[install] profile_libraries: libraries 字段缺失或不是数组（{}）",
            json.display()
        );
        return out;
    };
    let libs_len = libs.len();
    println!("[install] profile_libraries: libraries 共 {libs_len} 条");
    for lib in libs {
        match serde_json::from_value::<crate::version::Library>(lib.clone()) {
            Ok(parsed) => out.push(parsed),
            Err(e) => {
                // ⚠️ 不能静默跳过：LibraryArtifact 的 path/sha1/size/url **都是必填**，
                // 少一个字段这里就会解析失败。曾经的后果是「依赖一个都没下，
                // 启动报 ClassNotFoundException」，而日志里什么都没有。
                let name = lib
                    .get("name")
                    .and_then(|n| n.as_str())
                    .unwrap_or("<无 name>");
                crate::util::log_line(&format!(
                    "[loader] 库条目解析失败（{name}）：{e}"
                ));
                // 双通道：`log_line` 只落文件，真机上根本取不到。
                // 2026-10-20 实测 `profile_libraries` 返回 0 个时，
                // 就是因为所有条目都解析失败而**没有任何可见痕迹**。
                println!("[install] 库条目解析失败（{name}）：{e}");
            }
        }
    }
    if !out.is_empty() || libs_len == 0 {
        crate::util::log_line(&format!(
            "[loader] profile 库解析：成功 {} / 共 {}",
            out.len(),
            libs_len
        ));
    }
    out
}

/// 读加载器 profile 里所有库的 Maven 坐标（`group:artifact:version`）。
///
/// 用途：启动时把原版 classpath 里**同名 artifact** 的旧版本剔掉，
/// 避免「同一份类的两个版本同时在 classpath 上」——
/// Fabric 会直接抛 `duplicate ASM classes found on classpath`。
pub fn profile_library_names(profile_dir: &Path) -> Vec<String> {
    let mut out = Vec::new();
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return out;
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        return out;
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return out;
    };
    let Some(libs) = v.get("libraries").and_then(|l| l.as_array()) else {
        return out;
    };
    for lib in libs {
        if let Some(n) = lib.get("name").and_then(|n| n.as_str()) {
            if !n.is_empty() {
                out.push(n.to_string());
            }
        }
    }
    out
}

/// classpath 里的一条路径是否对应某个 Maven 坐标的 artifact。
///
/// 只比 `group(斜杠化)/artifact/` 这一段 —— 版本号不参与，
/// 因为目的正是「同一个 artifact 的不同版本要顶掉」。
pub fn path_matches_maven(path: &str, maven: &str) -> bool {
    let parts: Vec<&str> = maven.split(':').collect();
    if parts.len() < 2 {
        return false;
    }
    let group = parts[0].replace('.', "/");
    let artifact = parts[1];
    // 归一化路径分隔符（Windows 上也可能是反斜杠）
    let p = path.replace('\\', "/");
    p.contains(&format!("/{group}/{artifact}/"))
}

pub(crate) fn forge_full_ver(loader: &str, mc_version: &str, version: &str) -> String {
    let version = version.trim();
    if version.is_empty() {
        return String::new();
    }
    if loader == "neoforge" {
        if version.contains('.') {
            return version.to_string();
        }
        let mc_prefix = mc_version.strip_prefix("1.").unwrap_or(mc_version);
        return format!("{mc_prefix}.{version}");
    }
    let prefix = format!("{mc_version}-");
    if version.starts_with(&prefix) {
        version.to_string()
    } else {
        format!("{prefix}{version}")
    }
}

/// 加载器 profile 声明的参数（已做完占位符替换）。
#[derive(Debug, Default, Clone)]
pub struct LoaderArguments {
    /// `arguments.jvm`（JVM 侧参数，如 `-p` / `--add-opens` / `-DignoreList`）
    pub jvm: Vec<String>,
    /// `arguments.game`（游戏侧参数，如 `--fml.neoForgeVersion` / `--launchTarget`）
    pub game: Vec<String>,
    /// 旧式 profile 用的 `minecraftArguments`（空格分隔的单串），已拆好
    pub legacy: Vec<String>,
}

impl LoaderArguments {
    pub fn is_empty(&self) -> bool {
        self.jvm.is_empty() && self.game.is_empty() && self.legacy.is_empty()
    }
}

/// 读加载器 profile 声明的参数，并把占位符换成真实路径。
///
/// ## 为什么必须做（这是「加载器装了也起不来」的最后一环）
///
/// Forge 1.17+ / NeoForge 用的是 `cpw.mods.bootstraplauncher.BootstrapLauncher`，
/// 它**完全依赖** profile 里的这些 JVM 参数才能启动：
///   - `-p <模块路径>`：把 bootstraplauncher / securejarhandler / JarJarFileSystems
///     三个 jar 挂进模块路径，主类才找得到
///   - `--add-modules ALL-MODULE-PATH`
///   - `--add-opens java.base/...=cpw.mods.securejarhandler` 若干条
///   - `-DignoreList=` / `-DlibraryDirectory=`（它自己去扫库目录）
///
/// 这些参数里带 `${library_directory}`、`${classpath_separator}`、
/// `${version_name}` 三种占位符，不替换的话 JVM 直接报
/// 「Unrecognized option: -p`」或找不到模块。
///
/// 之前的实现只并了 classpath 和 mainClass，参数一条都没带 ——
/// 所以 Forge/NeoForge 实例即使 profile 生成了也起不来。
/// 把 `net.neoforged.fancymodloader:earlydisplay` 从 profile json 的 `libraries`
/// 里删掉（**就地改写**该 json），返回是否真的改了。
///
/// ## 为什么必须从 libraries 删（而不是从 `-p` 删）
///
/// 真机核对过：profile 的 `-p` 里**只有** bootstrapLauncher / securejarhandler /
/// asm×5 / JarJarFileSystems ✗ 没有 earlydisplay；earlydisplay 是 `libraries` 里的
/// 一条（`net.neoforged.fancymodloader:earlydisplay:4.0.44`），由
/// `BootstrapLauncher` 按 `-DlibraryDirectory` 自己扫进 boot 层 ✓。
/// 所以我先前那版「过滤 `-p`」是空转 ✗（日志里没有任何剔除记录，崩溃栈也完全没变）。
///
/// 删掉后：`ImmediateWindowHandler` 的 ServiceLoader 找不到 provider →
/// 不创建加载窗口 → `acceptGameLayer` 走空分支 → 不再碰
/// `DisplayWindow.updateModuleReads`（那里会 `Class.forName` 游戏层类 → 闪退）。
/// 安卓上那个加载窗口本来也看不见（Compose 界面盖在上面）。
pub fn strip_earlydisplay_library(profile_dir: &Path) -> bool {
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return false;
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        return false;
    };
    let Ok(mut v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return false;
    };
    let Some(libs) = v.get_mut("libraries").and_then(|l| l.as_array_mut()) else {
        return false;
    };
    let before = libs.len();
    libs.retain(|l| {
        let n = l.get("name").and_then(|n| n.as_str()).unwrap_or("");
        !n.contains("fancymodloader:earlydisplay") && !n.contains(":earlydisplay:")
    });
    if libs.len() == before {
        return false;
    }
    let removed = before - libs.len();
    match serde_json::to_string_pretty(&v) {
        Ok(out) => match std::fs::write(&json, out) {
            Ok(()) => {
                crate::util::log_line(&format!(
                    "[loader] 已从 profile {} 的 libraries 中移除 {removed} 条 earlydisplay（安卓上会导致启动崩溃）",
                    json.display()
                ));
                true
            }
            Err(e) => {
                crate::util::log_line(&format!("[loader] 改写 profile 失败（{e}），保留原样"));
                false
            }
        },
        Err(e) => {
            crate::util::log_line(&format!("[loader] 序列化 profile 失败（{e}），保留原样"));
            false
        }
    }
}

/// 从模块路径字符串里剔除**路径含 `needle`** 的分段，其余顺序保持不变。
///
/// 用途：把 `net.neoforged.fancymodloader:earlydisplay` 从 `-p` 里摘掉 ——
/// 它是 NeoForge 开局那个 Swing/GL 加载窗口，在安卓上会直接把游戏打死（见
/// `profile_arguments` 里的长注释）。模块路径是用平台分隔符（安卓上是 `:`）
/// 串起来的一整串，所以要按分隔符切分、逐段过滤、再拼回去。
pub fn strip_module(module_path: &str, needle: &str, sep: &str) -> String {
    module_path
        .split(sep)
        .filter(|seg| !seg.is_empty() && !seg.contains(needle))
        .collect::<Vec<_>>()
        .join(sep)
}

pub fn profile_arguments(
    profile_dir: &Path,
    libraries_dir: &Path,
    mc_version: &str,
    classpath_separator: &str,
) -> LoaderArguments {
    let mut out = LoaderArguments::default();
    let Some(name) = profile_dir.file_name().map(|n| n.to_string_lossy().to_string()) else {
        return out;
    };
    let json = profile_dir.join(format!("{name}.json"));
    let Ok(text) = std::fs::read_to_string(&json) else {
        return out;
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) else {
        return out;
    };

    let libs_str = libraries_dir.to_string_lossy().to_string();
    // 占位符替换：顺序无所谓，但 `classpath_separator` 与 `library_directory`
    // 必须都换掉，否则 `-p` 那串会留下 `${...}` 字面量。
    let subst = |s: &str| -> String {
        s.replace("${library_directory}", &libs_str)
            .replace("${classpath_separator}", classpath_separator)
            .replace("${version_name}", mc_version)
    };

    // 收集一个参数节点的字符串（可能是裸字符串，也可能是 {rules, value} 对象）
    fn collect(node: &serde_json::Value, subst: &dyn Fn(&str) -> String, out: &mut Vec<String>) {
        match node {
            serde_json::Value::String(s) => out.push(subst(s)),
            serde_json::Value::Object(o) => {
                // 只处理 value 是字符串或字符串数组的情形；rules 在安卓上按
                // 「非 Windows」求值，绝大多数加载器参数没有 rules，忽略即可。
                if let Some(v) = o.get("value") {
                    match v {
                        serde_json::Value::String(s) => out.push(subst(s)),
                        serde_json::Value::Array(a) => {
                            for item in a {
                                if let Some(s) = item.as_str() {
                                    out.push(subst(s));
                                }
                            }
                        }
                        _ => {}
                    }
                }
            }
            _ => {}
        }
    }

    if let Some(args) = v.get("arguments") {
        if let Some(jvm) = args.get("jvm").and_then(|a| a.as_array()) {
            for node in jvm {
                collect(node, &subst, &mut out.jvm);
            }
        }
        if let Some(game) = args.get("game").and_then(|a| a.as_array()) {
            for node in game {
                collect(node, &subst, &mut out.game);
            }
        }
    }

    // 旧式（1.12.2 等）只有 minecraftArguments 单串。
    //
    // ⚠️ 这里**只**替换加载器自己那三个占位符；`${auth_player_name}` /
    // `${game_directory}` / `${assets_root}` 这些是**原版**的参数变量，
    // 由 `launch.rs::build_game_args` 统一替换。两边分工不要搞混：
    //   - 本函数：把加载器 profile 里的路径类占位符落地
    //   - build_game_args：把账号/路径/版本类的原版变量落地
    if let Some(legacy) = v.get("minecraftArguments").and_then(|a| a.as_str()) {
        for token in legacy.split_whitespace() {
            out.legacy.push(subst(token));
        }
    }

    // ⚠️ 2026-10-23 真机崩溃修复：把 earlydisplay 从**模块路径** `-p` 里摘掉。
    //
    // 崩溃（NeoForge 1.21.1，`files/logs/game-stderr.log` 抓到）：
    //   NoClassDefFoundError: net/minecraft/client/gui/screens/LoadingOverlay
    //     at fml_earlydisplay@4.0.44/…DisplayWindow.updateModuleReads(DisplayWindow.java:605)
    //     at fml_loader@4.0.44/…ImmediateWindowHandler.acceptGameLayer(ImmediateWindowHandler.java:94)
    //     at …FMLLoader.beforeStart(FMLLoader.java:166)
    // 也就是 earlydisplay 在 boot 层用 `Class.forName` 找**游戏层**的 MC 类，
    // 类加载器看不到 → 抛错 → main 线程终止 → JVM 退出（同进程，等于闪退）。
    //
    // 为什么不能靠开关：`-Dfml.earlyprogresswindow=false` **一直有传**
    // （启动日志 jvm_arg[19] 可见），NeoForge 21.1.251 **不认**它 ——
    // stderr 里照样 `Loading ImmediateWindowProvider fmlearlywindow`。
    //
    // 为什么必须在 `-p` 上删、而不是 classpath：崩溃栈里的
    // `MC-BOOTSTRAP/fml_earlydisplay@4.0.44/…` 说明它在 boot 层**模块路径**上，
    // 之前的 classpath 过滤是空转（那段代码在 launch.rs::build_classpath）。
    // 摘掉后 ServiceLoader 找不到 provider → 不创建加载窗口 → `acceptGameLayer`
    // 走空分支，不再碰 `updateModuleReads`。安卓上本来也看不见那个窗口。
    let mut filtered: Vec<String> = Vec::with_capacity(out.jvm.len());
    let mut idx = 0usize;
    while idx < out.jvm.len() {
        let cur = out.jvm[idx].clone();
        // 形态一：`-p <path>` / `--module-path <path>`（分两项）
        if cur == "-p" || cur == "--module-path" {
            if let Some(path) = out.jvm.get(idx + 1) {
                let kept = strip_module(path, "earlydisplay", classpath_separator);
                if kept.trim().is_empty() {
                    crate::util::log_line(&format!(
                        "[loader] 模块路径里只剩 earlydisplay，已整条移除（{cur}）"
                    ));
                } else if kept != *path {
                    crate::util::log_line(&format!(
                        "[loader] 从 {cur} 模块路径中剔除 earlydisplay（{} → {}）",
                        path, kept
                    ));
                    filtered.push(cur);
                    filtered.push(kept);
                } else {
                    filtered.push(cur);
                    filtered.push(path.clone());
                }
                idx += 2;
                continue;
            }
        }
        // 形态二：`-p=...` / `--module-path=...`（内联）
        for prefix in ["-p=", "--module-path="] {
            if let Some(path) = cur.strip_prefix(prefix) {
                let kept = strip_module(path, "earlydisplay", classpath_separator);
                if kept.trim().is_empty() {
                    crate::util::log_line(&format!(
                        "[loader] 模块路径里只剩 earlydisplay，已整条移除（{prefix}）"
                    ));
                } else if kept != path {
                    crate::util::log_line(&format!(
                        "[loader] 从 {prefix} 模块路径中剔除 earlydisplay"
                    ));
                    filtered.push(format!("{prefix}{kept}"));
                } else {
                    filtered.push(cur.clone());
                }
                idx += 1;
                continue;
            }
        }
        filtered.push(cur);
        idx += 1;
    }
    out.jvm = filtered;

    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    // ── strip_module（把 earlydisplay 从 -p 里摘掉，否则 NeoForge 闪退）──
    #[test]
    fn strip_module_removes_only_the_earlydisplay_segment() {
        // 中间那段被摘掉，其余顺序不变
        assert_eq!(
            strip_module("/libs/a.jar:/libs/earlydisplay-4.0.44.jar:/libs/b.jar", "earlydisplay", ":"),
            "/libs/a.jar:/libs/b.jar"
        );
        // 不含该段 → 原样返回
        let untouched = "/libs/a.jar:/libs/b.jar";
        assert_eq!(strip_module(untouched, "earlydisplay", ":"), untouched);
        // 只剩它 → 空串（调用方据此整条移除 -p，避免留下空模块路径）
        assert_eq!(
            strip_module("/libs/earlydisplay-4.0.44.jar", "earlydisplay", ":"),
            ""
        );
        // maven 目录形态（真机上就是这个路径）
        assert_eq!(
            strip_module(
                "/data/data/x/libraries/net/neoforged/fancymodloader/earlydisplay/4.0.44/earlydisplay-4.0.44.jar:/data/data/x/libraries/a.jar",
                "earlydisplay",
                ":"
            ),
            "/data/data/x/libraries/a.jar"
        );
    }

    // ── loader_version_id ────────────────────────────────────────────
    #[test]
    fn version_id_matches_official_installer_output() {
        // 这些名字必须与官方 installer 生成的 profile id 一致，
        // 否则「安装说装好了，检查说还缺」（两边命名不同）。
        assert_eq!(
            loader_version_id("forge", "1.12.2", "14.23.5.2860"),
            "1.12.2-forge-14.23.5.2860"
        );
        assert_eq!(
            loader_version_id("neoforge", "1.21.8", "21.8.47"),
            "1.21.8-neoforge-21.8.47"
        );
        assert_eq!(
            loader_version_id("fabric", "1.21.8", "0.16.0"),
            "fabric-loader-0.16.0-1.21.8"
        );
        assert_eq!(
            loader_version_id("quilt", "1.21.8", "0.26.1"),
            "quilt-loader-0.26.1-1.21.8"
        );
    }

    // ── forge_full_ver（installer 文件名与 maven 路径都用它）─────────
    #[test]
    fn forge_full_ver_does_not_double_prefix() {
        // version 已含 mc 前缀时不能再拼，否则 maven 路径变成
        // 1.19.1-1.19.1-42.0.5 → 404
        assert_eq!(forge_full_ver("forge", "1.19.1", "42.0.5"), "1.19.1-42.0.5");
        assert_eq!(
            forge_full_ver("forge", "1.19.1", "1.19.1-42.0.5"),
            "1.19.1-42.0.5"
        );
        assert_eq!(
            forge_full_ver("forge", "1.12.2", "14.23.5.2860"),
            "1.12.2-14.23.5.2860"
        );
    }

    #[test]
    fn neoforge_full_ver_adds_mc_prefix_for_bare_build() {
        // NeoForge maven 上是 21.8.47 这种，而 loader_version 常是纯 build 号
        assert_eq!(forge_full_ver("neoforge", "1.21.8", "47"), "21.8.47");
        assert_eq!(forge_full_ver("neoforge", "1.21.8", "21.8.47"), "21.8.47");
    }

    // ── expected_main_class ──────────────────────────────────────────
    #[test]
    fn expected_main_class_is_loader_specific() {
        // Forge/Fabric 必须用自己的入口，用 vanilla 的 Main 等于没装加载器
        assert_eq!(
            expected_main_class("forge"),
            Some("cpw.mods.bootstraplauncher.BootstrapLauncher")
        );
        assert_eq!(
            expected_main_class("fabric"),
            Some("net.fabricmc.loader.impl.launch.knot.KnotClient")
        );
        assert_eq!(expected_main_class("optifine"), None);
    }

    // ── find_loader_profile ──────────────────────────────────────────
    #[test]
    fn find_loader_profile_matches_by_loader_and_mc_version() {
        let tmp = std::env::temp_dir().join("qookix-loader-test-profile");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();

        // 造三个目录：目标 fabric、同名但版本不符、无关的 vanilla
        for name in ["fabric-loader-0.16.0-1.21.8", "fabric-loader-0.16.0-1.19.4", "1.21.8"] {
            let d = tmp.join(name);
            fs::create_dir_all(&d).unwrap();
            fs::write(d.join(format!("{name}.json")), "{}").unwrap();
        }

        let hit = find_loader_profile(&tmp, "fabric", "1.21.8");
        assert!(hit.is_some(), "应找到 fabric 1.21.8 的 profile");
        let hit = hit.unwrap();
        assert!(
            hit.file_name().unwrap().to_string_lossy().contains("1.21.8"),
            "命中项必须同时含 loader 名与游戏版本"
        );

        // 没有 profile 时应返回 None
        assert!(find_loader_profile(&tmp, "forge", "1.21.8").is_none());

        let _ = fs::remove_dir_all(&tmp);
    }

    // ── profile_main_class ───────────────────────────────────────────
    #[test]
    fn profile_main_class_reads_from_json() {
        let tmp = std::env::temp_dir().join("qookix-loader-test-mainclass");
        let _ = fs::remove_dir_all(&tmp);
        let d = tmp.join("1.12.2-forge-14.23.5.2860");
        fs::create_dir_all(&d).unwrap();
        fs::write(
            d.join("1.12.2-forge-14.23.5.2860.json"),
            r#"{"id":"x","mainClass":"net.minecraft.launchwrapper.Launch"}"#,
        )
        .unwrap();

        assert_eq!(
            profile_main_class(&d).as_deref(),
            Some("net.minecraft.launchwrapper.Launch"),
            "1.12.2 Forge 用的是 launchwrapper，不是 BootstrapLauncher"
        );

        let _ = fs::remove_dir_all(&tmp);
    }

    // ── profile_library_paths ────────────────────────────────────────
    #[test]
    fn profile_library_paths_filters_missing_and_counts() {
        let tmp = std::env::temp_dir().join("qookix-loader-test-libs");
        let _ = fs::remove_dir_all(&tmp);
        let profile = tmp.join("fabric-loader-0.16.0-1.21.8");
        let libs = tmp.join("libraries");
        fs::create_dir_all(&profile).unwrap();
        fs::create_dir_all(&libs).unwrap();

        // 造一个真实存在的库、一个不存在的库
        let real = libs.join("real.jar");
        fs::write(&real, "x").unwrap();
        fs::write(
            profile.join("fabric-loader-0.16.0-1.21.8.json"),
            r#"{"libraries":[
                {"downloads":{"artifact":{"path":"real.jar"}}},
                {"downloads":{"artifact":{"path":"missing.jar"}}}
            ]}"#,
        )
        .unwrap();

        let (paths, missing) = profile_library_paths(&profile, &libs);
        assert_eq!(paths.len(), 1, "只收存在的文件");
        assert_eq!(missing, 1, "缺的计数返回，供界面提示先补全");

        let _ = fs::remove_dir_all(&tmp);
    }

    // ── find_loader_profile：NeoForge 的目录名不含 mc 版本 ────────────
    #[test]
    fn find_loader_profile_matches_neoforge_by_inherits_from() {
        let tmp = std::env::temp_dir().join("qookix-loader-test-find-neo");
        let _ = fs::remove_dir_all(&tmp);
        // 真机实际形态（1.21.1 + NeoForge 21.1.251）：目录名只有 neoforge-21.1.251，
        // **不含 1.21.1** —— 老逻辑按名字匹配因此永远找不到，游戏退回原版、模组全失效。
        let dir = tmp.join("neoforge-21.1.251");
        fs::create_dir_all(&dir).unwrap();
        fs::write(
            dir.join("neoforge-21.1.251.json"),
            r#"{"id":"neoforge-21.1.251","inheritsFrom":"1.21.1",
                "mainClass":"cpw.mods.bootstraplauncher.BootstrapLauncher"}"#,
        )
        .unwrap();
        let found =
            find_loader_profile(&tmp, "neoforge", "1.21.1").expect("应按 inheritsFrom 认出来");
        assert_eq!(found, dir);
        let mc = profile_main_class(&found).unwrap();
        assert!(mc.contains("BootstrapLauncher"), "mainClass 不该退回原版：{mc}");

        // 反例：inheritsFrom 是别的版本时不能误认
        fs::write(
            dir.join("neoforge-21.1.251.json"),
            r#"{"id":"neoforge-21.1.251","inheritsFrom":"1.20.1"}"#,
        )
        .unwrap();
        assert!(
            find_loader_profile(&tmp, "neoforge", "1.21.1").is_none(),
            "别的游戏版本的 profile 不能被当成这个版本的"
        );
        let _ = fs::remove_dir_all(&tmp);
    }

    // ── run_installer（前置校验，不真的跑 java）──────────────────────
    #[test]
    fn run_installer_rejects_missing_inputs() {
        let tmp = std::env::temp_dir().join("qookix-loader-test-installer");
        let _ = fs::remove_dir_all(&tmp);
        fs::create_dir_all(&tmp).unwrap();
        let java = tmp.join("java");
        let jar = tmp.join("installer.jar");

        // 两者都不存在 → 应报错，而不是 panic 或静默成功
        let r = run_installer(&java, &tmp, &jar, &tmp, &[]);
        assert!(r.is_err(), "java 不存在时必须报错");

        // java 存在但 jar 不存在 → 仍应报错
        fs::write(&java, "x").unwrap();
        let r2 = run_installer(&java, &tmp, &jar, &tmp, &[]);
        assert!(r2.is_err(), "installer jar 不存在时必须报错");

        let _ = fs::remove_dir_all(&tmp);
    }

    // ── profile_arguments（占位符替换 + 参数收集）──────────────────
    #[test]
    fn profile_arguments_substitutes_placeholders() {
        // 用真实的 NeoForge 21.8.47 片段（BootstrapLauncher 必需的那几条）
        let tmp = std::env::temp_dir().join("qookix-loader-test-args");
        let _ = fs::remove_dir_all(&tmp);
        let profile = tmp.join("neoforge-21.8.47");
        fs::create_dir_all(&profile).unwrap();
        fs::write(
            profile.join("neoforge-21.8.47.json"),
            r#"{"arguments":{"jvm":[
                "-DignoreList=client-extra,${version_name}.jar",
                "-DlibraryDirectory=${library_directory}",
                "-p",
                "${library_directory}/a/bootstraplauncher.jar${classpath_separator}${library_directory}/b/securejarhandler.jar",
                "--add-modules","ALL-MODULE-PATH"
            ],"game":["--launchTarget","neoforgeclient"]}}"#,
        )
        .unwrap();

        let libs = tmp.join("libraries");
        let args = profile_arguments(&profile, &libs, "1.21.8", ":");
        let libs_str = libs.to_string_lossy().to_string();

        // 占位符必须全部替换掉，一个不剩
        for a in args.jvm.iter().chain(args.game.iter()) {
            assert!(!a.contains("${"), "残留占位符: {a}");
        }
        assert_eq!(
            args.jvm[0], "-DignoreList=client-extra,1.21.8.jar",
            "version_name 应替换为游戏版本"
        );
        assert_eq!(
            args.jvm[1],
            format!("-DlibraryDirectory={libs_str}"),
            "library_directory 应替换为本地库目录"
        );
        assert!(args.jvm[2] == "-p" || args.jvm[2] == "--module-path");
        assert!(
            args.jvm[3].contains(&libs_str) && args.jvm[3].contains(':'),
            "-p 的值应展开为本地绝对路径并用 : 连接"
        );
        assert_eq!(args.jvm[4], "--add-modules");
        assert_eq!(args.jvm[5], "ALL-MODULE-PATH");
        assert_eq!(args.game, vec!["--launchTarget", "neoforgeclient"]);

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn profile_arguments_keeps_spaces_inside_one_arg() {
        // Fabric 的 `-DFabricMcEmu= net.minecraft.client.main.Main `（值里带空格）
        // **必须整条保留为一个 argv 元素**，不能按空格拆开 —— 拆开后 JVM 会把
        // `net.minecraft.client.main.Main` 当成主类参数，启动直接失败。
        let tmp = std::env::temp_dir().join("qookix-loader-test-args-space");
        let _ = fs::remove_dir_all(&tmp);
        let profile = tmp.join("fabric-loader-0.16.0-1.21.8");
        fs::create_dir_all(&profile).unwrap();
        fs::write(
            profile.join("fabric-loader-0.16.0-1.21.8.json"),
            r#"{"arguments":{"jvm":["-DFabricMcEmu= net.minecraft.client.main.Main "],"game":[]}}"#,
        )
        .unwrap();

        let args = profile_arguments(&profile, &tmp.join("libraries"), "1.21.8", ":");
        assert_eq!(args.jvm.len(), 1, "带空格的参数不能被拆成多个");
        assert!(args.jvm[0].starts_with("-DFabricMcEmu="));
        assert!(args.jvm[0].contains("net.minecraft.client.main.Main"));
        assert!(args.game.is_empty());

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn profile_arguments_handles_legacy_minecraft_arguments() {
        // 1.12.2 那种旧式 profile：只有 minecraftArguments 单串
        let tmp = std::env::temp_dir().join("qookix-loader-test-args-legacy");
        let _ = fs::remove_dir_all(&tmp);
        let profile = tmp.join("1.12.2-forge-14.23.5.2860");
        fs::create_dir_all(&profile).unwrap();
        fs::write(
            profile.join("1.12.2-forge-14.23.5.2860.json"),
            r#"{"minecraftArguments":"--username ${auth_player_name} --gameDir ${game_directory}"}"#,
        )
        .unwrap();

        let args = profile_arguments(&profile, &tmp.join("libraries"), "1.12.2", ":");
        // `--username ${auth_player_name} --gameDir ${game_directory}` 按空格拆成 4 个
        assert_eq!(args.legacy.len(), 4, "按空格拆成 4 个 token");
        assert_eq!(args.legacy[0], "--username");
        assert_eq!(args.legacy[2], "--gameDir");
        // 原版变量（auth_player_name 等）**不**在这里替换，交给 build_game_args
        assert!(args.legacy[1].contains("${auth_player_name}"));

        let _ = fs::remove_dir_all(&tmp);
    }

    #[test]
    fn profile_arguments_returns_empty_for_profile_without_args() {
        // 没有 arguments / minecraftArguments 时不能 panic
        let tmp = std::env::temp_dir().join("qookix-loader-test-args-none");
        let _ = fs::remove_dir_all(&tmp);
        let profile = tmp.join("bare-profile");
        fs::create_dir_all(&profile).unwrap();
        fs::write(profile.join("bare-profile.json"), r#"{"id":"x"}"#).unwrap();

        let args = profile_arguments(&profile, &tmp.join("libraries"), "1.21.8", ":");
        assert!(args.is_empty());

        let _ = fs::remove_dir_all(&tmp);
    }
}
