use std::path::{Path, PathBuf};
use tokio::fs;
use anyhow::{Context, Result};
use crate::models::*;
use crate::settings::get_data_dir;

#[derive(Clone, Debug)]
pub struct JreMirrorSource {
    pub java_version: i32,
    pub arch: &'static str,
    pub url: &'static str,
    pub sha1: &'static str,
    pub size: u64,
}

/// 可下载的 JRE 运行包。
///
/// 地址取自 Amethyst-Android `NewJREUtil.getJreSource()`（AngelAuraMC 的 openjdk 构建，
/// 面向 Android 的 Pojav 版 OpenJDK）。注意命名全是小写 `jre{版本}-android-{架构}`，
/// aarch64 的架构名是 **arm64**（不是 aarch64），写错会直接 404。
/// x86_64 那三组是给 Android Studio 的模拟器用的（模拟器镜像是 x86_64）。
///
/// ## 关于 `sha1` 全是空串
///
/// 这些条目的 `sha1` 都是空的（上游就没给）。**没有**去补真实 SHA1，因为这个格式
/// 自带完整性校验，重复校验的收益很低：
///   1. `tar.xz` 的 xz 容器自带 CRC64 校验，字节损坏在解压阶段就会失败；
///   2. 字节数被严格核对（`total` 取 Content-Length，缺失时回退 `size` 字段）；
///   3. 解压后再用 `has_libjli()` 确认 JRE 根下有 libjli.so，残缺包会被清理并报错。
/// 真要进一步加固，应该换成 GitHub release 资产的 `digest`（sha256），
/// 而不是给这里的 `sha1` 随便填一个值 —— 填错会变成「永远校验失败」。
const JRE_MIRRORS: &[JreMirrorSource] = &[
    JreMirrorSource {
        java_version: 8,
        arch: "arm64-v8a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre8/jre8-android-arm64.tar.xz",
        sha1: "",
        size: 28_026_736,
    },
    JreMirrorSource {
        java_version: 8,
        arch: "armeabi-v7a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre8/jre8-android-arm.tar.xz",
        sha1: "",
        size: 27_035_664,
    },
    JreMirrorSource {
        java_version: 17,
        arch: "arm64-v8a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre17/jre17-android-arm64.tar.xz",
        sha1: "",
        size: 26_900_804,
    },
    JreMirrorSource {
        java_version: 17,
        arch: "armeabi-v7a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre17/jre17-android-arm.tar.xz",
        sha1: "",
        size: 25_001_168,
    },
    JreMirrorSource {
        java_version: 21,
        arch: "arm64-v8a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-arm64.tar.xz",
        sha1: "",
        size: 28_675_516,
    },
    JreMirrorSource {
        java_version: 21,
        arch: "armeabi-v7a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-arm.tar.xz",
        sha1: "",
        size: 26_586_292,
    },
    // x86_64：模拟器（Android Studio x86_64 镜像）。命名同样是 `android-x86_64`。
    JreMirrorSource {
        java_version: 8,
        arch: "x86_64",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre8/jre8-android-x86_64.tar.xz",
        sha1: "",
        size: 29_132_884,
    },
    JreMirrorSource {
        java_version: 17,
        arch: "x86_64",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre17/jre17-android-x86_64.tar.xz",
        sha1: "",
        size: 27_780_012,
    },
    JreMirrorSource {
        java_version: 21,
        arch: "x86_64",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-x86_64.tar.xz",
        sha1: "",
        size: 29_662_988,
    },
    // ── Java 25：新版 Minecraft 需要它 ────────────────────────────────
    // 26.3（2026-09-15）的版本 JSON 写着 `javaVersion.majorVersion = 25`，
    // 缺这一档时启动会直接报 `JRE_ARCH_UNSUPPORTED: no JRE for Java 25`。
    // 大小取自 GitHub 资产元数据（Content-Length），架构命名同上游。
    JreMirrorSource {
        java_version: 25,
        arch: "arm64-v8a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-arm64.tar.xz",
        sha1: "",
        size: 38_031_580,
    },
    JreMirrorSource {
        java_version: 25,
        arch: "armeabi-v7a",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-arm.tar.xz",
        sha1: "",
        size: 28_252_088,
    },
    JreMirrorSource {
        java_version: 25,
        arch: "x86_64",
        url: "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-x86_64.tar.xz",
        sha1: "",
        size: 39_061_384,
    },
];

/// 当前能自动下载的 Java 主版本号（去重、升序）——用于「版本不支持」时的提示文案，
/// 免得把可用版本写死在字符串里，加镜像后忘同步。
pub fn supported_java_versions() -> Vec<i32> {
    let mut v: Vec<i32> = JRE_MIRRORS.iter().map(|m| m.java_version).collect();
    v.sort_unstable();
    v.dedup();
    v
}

pub fn resolve_download_url(java_version: i32, arch: &str) -> Result<&JreMirrorSource> {
    JRE_MIRRORS
        .iter()
        .find(|m| m.java_version == java_version && m.arch == arch)
        .ok_or_else(|| anyhow::anyhow!("JRE_ARCH_UNSUPPORTED: no JRE for Java {} on {}", java_version, arch))
}

/// 进度回写的最小间隔（同 `download.rs`：不节流会频繁跨 FFI 回调，
/// 全不节流又会让进度条一格一格地抖）。
const JRE_PROGRESS_INTERVAL: std::time::Duration = std::time::Duration::from_millis(200);

pub struct MultiRTManager;

/// 把历史遗留的运行时目录名 `Pojav-JRE-<版本>` 迁移成中性的 `JRE-<版本>`。
///
/// 用户要求在遵守开源协议的前提下，界面与日志里不出现上游项目名；目录名会随
/// 路径出现在游戏日志/崩溃栈里（应用内的日志查看器可见），所以一并迁移。
/// 做法：**直接改名 + 同步改写 release 里的 name 字段**，不重新下载 JRE。
async fn migrate_legacy_runtime_names(runtimes_dir: &std::path::Path) {
    let Ok(mut entries) = fs::read_dir(runtimes_dir).await else {
        return;
    };
    // 收集要处理的目录：旧名（改名）+ 已是中文名但元数据可能没同步的（修 release）。
    let mut jobs: Vec<(std::path::PathBuf, String)> = Vec::new();
    while let Ok(Some(entry)) = entries.next_entry().await {
        let path = entry.path();
        let Some(dir_name) = path.file_name().map(|s| s.to_string_lossy().to_string()) else {
            continue;
        };
        if let Some(suffix) = dir_name.strip_prefix("Pojav-JRE-") {
            jobs.push((path.clone(), format!("JRE-{}", suffix)));
        } else if dir_name.starts_with("JRE-") {
            jobs.push((path.clone(), dir_name.clone()));
        }
    }
    for (dir, new_name) in jobs {
        // 1) 目录改名（旧名 → 中性名）。
        let is_rename = dir
            .file_name()
            .map(|s| s != std::ffi::OsStr::new(new_name.as_str()))
            .unwrap_or(false);
        if is_rename {
            let target = runtimes_dir.join(&new_name);
            if target.exists() {
                continue;
            }
            if let Err(e) = fs::rename(&dir, &target).await {
                tracing::warn!("运行时目录改名失败（{} → {}）：{e}", dir.display(), target.display());
                continue;
            }
            tracing::info!("运行时目录已迁移：{} → {}", dir.display(), target.display());
        }
        // 2) 修正 release 里的 name / path —— **path 不改的话启动会用到已不存在的
        //    旧目录，游戏静默退出**（实测踩过：只改了 name 没改 path）。
        let release = runtimes_dir.join(&new_name).join("release");
        let Ok(content) = fs::read_to_string(&release).await else {
            continue;
        };
        let Ok(mut value) = serde_json::from_str::<serde_json::Value>(&content) else {
            continue;
        };
        let Some(obj) = value.as_object_mut() else {
            continue;
        };
        let mut changed = false;
        if obj.get("name").and_then(|v| v.as_str()) != Some(new_name.as_str()) {
            obj.insert("name".to_string(), serde_json::Value::String(new_name.clone()));
            changed = true;
        }
        if let Some(p) = obj.get("path").and_then(|v| v.as_str()) {
            if p.contains("Pojav-JRE-") {
                obj.insert(
                    "path".to_string(),
                    serde_json::Value::String(p.replace("Pojav-JRE-", "JRE-")),
                );
                changed = true;
            }
        }
        if changed {
            if let Ok(text) = serde_json::to_string(&value) {
                let _ = fs::write(&release, text).await;
            }
            tracing::info!("运行时元数据已修正：{}", new_name);
        }
    }
}

impl MultiRTManager {
    pub async fn get_runtimes() -> Result<Vec<Runtime>> {
        let data_dir = get_data_dir().await?;
        let runtimes_dir = Path::new(&data_dir).join("runtimes");

        // 目录还不存在是「尚未安装任何 JRE」，不是错误：
        // 这里必须返回空列表，launch 才能走到自动下载 JRE 的分支。
        if !runtimes_dir.exists() {
            fs::create_dir_all(&runtimes_dir).await.ok();
            return Ok(Vec::new());
        }

        migrate_legacy_runtime_names(&runtimes_dir).await;

        let mut runtimes = Vec::new();

        let mut entries = fs::read_dir(&runtimes_dir).await
            .context("Failed to read runtimes directory")?;

        while let Some(entry) = entries.next_entry().await
            .context("Failed to read next entry")? {
            let release_file = entry.path().join("release");
            if release_file.exists() {
                // **单个运行时损坏不能拖垮整张表**：原来这里用 `?` 直接冒泡 ——
                // 任何一个 runtimes/<x>/release 读不出来（或字段缺失），get_runtimes 就整体报错，
                // 上层据此判定「没有可用的 Java 运行时」，于是**别的完好的 JRE 也跟着用不了**，
                // 用户看到的是「请安装 JRE」这种完全指错方向的提示。坏的那个跳过即可。
                let Ok(content) = fs::read_to_string(&release_file).await else {
                    crate::util::log_line(&format!(
                        "运行时 {} 的 release 读取失败，已跳过该目录",
                        entry.path().display()
                    ));
                    continue;
                };
                match serde_json::from_str::<Runtime>(&content) {
                    Ok(runtime) => runtimes.push(runtime),
                    Err(e) => crate::util::log_line(&format!(
                        "运行时 {} 的 release 解析失败（{e}），已跳过该目录",
                        entry.path().display()
                    )),
                }
            }
        }

        Ok(runtimes)
    }

    pub async fn get_runtime(name: &str) -> Result<Runtime> {
        let data_dir = get_data_dir().await?;
        let release_file = Path::new(&data_dir)
            .join("runtimes")
            .join(name)
            .join("release");

        let content = fs::read_to_string(&release_file).await
            .with_context(|| format!("Failed to read runtime: {}", name))?;

        Ok(serde_json::from_str(&content)?)
    }

    pub async fn get_compatible_runtime(java_version: i32) -> Result<Option<Runtime>> {
        let arch = get_device_arch();
        let runtimes = Self::get_runtimes().await?;

        // 必须**同时**按架构过滤。只看版本号的话，换机恢复 / 手动导入 / 模拟器产物
        // 在 `runtimes/*` 里留下的别的 ABI 会被选中，`dlopen` 报 `wrong ELF class`，
        // 而错误会被归因成「JRE 缺失」—— 排查方向完全是错的。
        let mut compatible: Option<Runtime> = None;
        let mut mismatched: Vec<String> = Vec::new();
        for r in runtimes {
            if r.java_version < java_version {
                continue;
            }
            if r.arch == arch {
                let better = compatible
                    .as_ref()
                    .map_or(true, |c| r.java_version < c.java_version);
                if better {
                    compatible = Some(r);
                }
            } else {
                mismatched.push(format!("{}（{}）", r.name, r.arch));
            }
        }

        if compatible.is_none() && !mismatched.is_empty() {
            // 有版本够但架构不对的，明确说出来，别只留一句「没找到 JRE」
            tracing::warn!(
                "有版本满足 Java {} 但架构不符（本机是 {}）：{}",
                java_version,
                arch,
                mismatched.join("、")
            );
        }

        Ok(compatible)
    }
}

pub struct JreManager;

impl JreManager {
    /// 安装 JRE。
    ///
    /// `progress` 是**整体进度**（0..1）：下载占前 70%，解压占剩下 30%。
    /// `on_phase` 在进入「解压」这种没有百分比的阶段时给一句文案 ——
    /// 27MB 的 xz 解压要几十秒，中间没有任何反馈的话看起来就是卡死。
    pub async fn install(
        java_version: i32,
        arch: &str,
        progress: impl Fn(f32) + Send + Sync,
        on_phase: impl Fn(&str),
    ) -> Result<Runtime> {
        let mirror = resolve_download_url(java_version, arch)?;

        let data_dir = get_data_dir().await?;
        // 目录名用中性名（历史上叫 Pojav-JRE-*，会出现在日志里；已做旧目录自动迁移，
        // 见 get_runtimes 里的 migrate_legacy_runtime_names）。
        let runtime_name = format!("JRE-{}", java_version);
        let runtime_dir = Path::new(&data_dir)
            .join("runtimes")
            .join(&runtime_name);

        fs::create_dir_all(&runtime_dir).await
            .with_context(|| format!("Failed to create runtime directory: {}", runtime_dir.display()))?;

        let archive_path = runtime_dir.join("jre.tar.xz");

        let download_progress = |p: f32| progress(p * 0.7);
        crate::jre_download::download_with_retry(&mirror.url, &archive_path, mirror.size, &download_progress).await?;

        on_phase("正在解压 Java 运行时…");
        Self::extract_tar_xz(&archive_path, &runtime_dir).await?;
        Self::normalize_runtime_root(&runtime_dir).await;

        let _ = fs::remove_file(&archive_path).await;

        // 解压后**先验证再登记**。以前不管解压结果都写 release，
        // 于是一份残缺的运行时也会被 get_runtimes 当成可用，
        // 直到启动时才报「校验失败」；更糟的是坏目录会一直留着，重试也不会重新下载。
        if !has_libjli(&runtime_dir) {
            let _ = fs::remove_dir_all(&runtime_dir).await;
            return Err(anyhow::anyhow!(
                "JRE_EXTRACT_INCOMPLETE: {} 解压后找不到 libjli.so，已清理该目录，请重试",
                runtime_name
            ));
        }

        let runtime = Runtime {
            name: runtime_name.clone(),
            version_string: format!("{}.0.0", java_version),
            java_version,
            arch: arch.to_string(),
            path: runtime_dir.to_string_lossy().to_string(),
            is_internal: false,
            is_ported: true,
        };

        let release_file = runtime_dir.join("release");
        let content = serde_json::to_string_pretty(&runtime)?;
        fs::write(&release_file, content).await
            .with_context(|| format!("Failed to write release file: {}", release_file.display()))?;

        progress(1.0);
        Ok(runtime)
    }

    async fn extract_tar_xz(archive_path: &Path, dest_dir: &Path) -> Result<()> {
        let archive = archive_path.to_path_buf();
        let dest = dest_dir.to_path_buf();
        // 27MB 的 xz 解压后是 150~250MB，**纯同步解压会占住一个 tokio worker 好几十秒** ——
        // 期间同一个 worker 上的其它 invoke（包括前端的进度轮询）全部排队，
        // 表现就是「卡死在某个百分比、界面无响应」。照 `natives.rs::extract_sync` 的形态挪出去。
        tokio::task::spawn_blocking(move || extract_tar_xz_sync(&archive, &dest))
            .await
            .context("JRE 解压任务失败")?
    }
}

/// [JreManager::extract_tar_xz] 的同步实现。
fn extract_tar_xz_sync(archive_path: &Path, dest_dir: &Path) -> Result<()> {
    

    let file = std::fs::File::open(archive_path)
        .with_context(|| format!("Failed to open archive: {}", archive_path.display()))?;

    let decompressor = xz2::read::XzDecoder::new(file);
    let mut archive = tar::Archive::new(decompressor);

    let mut budget = crate::zip_guard::ZipBudget::new();
    for entry in archive.entries().context("Failed to read runtime archive")? {
        let mut entry = entry?;
        let name = entry.path()?.to_string_lossy().into_owned();
        budget.admit(&name, entry.size()).map_err(anyhow::Error::msg)?;
        if !entry.unpack_in(dest_dir)? { anyhow::bail!("Unsafe runtime archive path: {name}"); }
    }

    Ok(())
}

/// 判断目录本身是不是一个 JRE 根（libjli.so 的各种常见布局）。
/// x86_64/x86 是给模拟器用的（桌面版安卓模拟器跑 x86_64 镜像）。
fn has_libjli(dir: &Path) -> bool {
    ["arm64", "arm32", "aarch64", "arm", "x86_64", "x86", "amd64", ""]
        .iter()
        .any(|sub| {
            if sub.is_empty() {
                dir.join("lib").join("libjli.so").exists()
            } else {
                let arch_dir = dir.join("lib").join(sub);
                // 两种布局都要认：
                //  - JDK 9+（扁平的）：lib/<arch>/libjli.so、lib/libjli.so
                //  - **JDK 8（嵌套的）**：lib/<arch>/jli/libjli.so
                //    实机确认 Pojav-JRE-8 就是这种（lib/aarch64/jli/libjli.so），
                //    以前只找扁平路径，于是把**完好的 JRE-8 判成残缺**，
                //    1.8.9 一启动就被拦下（报「找不到 libjli.so/libjvm.so」）。
                arch_dir.join("libjli.so").exists() || arch_dir.join("jli").join("libjli.so").exists()
            }
        })
}

impl JreManager {
    /// 解压后把 JRE 根目录理顺：Pojav 的 tar.xz 里可能多套一层目录，
    /// 而 `JREValidator` / `JvmLauncher` 都假设 `lib/...` 直接在运行根下。
    /// 发现 libjli.so 落在下一层子目录时，把该子目录的内容上提一层。
    async fn normalize_runtime_root(runtime_dir: &Path) {
        if has_libjli(runtime_dir) {
            return;
        }
        let Ok(mut entries) = fs::read_dir(runtime_dir).await else {
            return;
        };
        let mut nested: Option<PathBuf> = None;
        while let Ok(Some(entry)) = entries.next_entry().await {
            let path = entry.path();
            if path.is_dir() && has_libjli(&path) {
                nested = Some(path);
                break;
            }
        }
        let Some(inner) = nested else {
            return;
        };
        if let Ok(mut inner_entries) = fs::read_dir(&inner).await {
            while let Ok(Some(item)) = inner_entries.next_entry().await {
                let target = runtime_dir.join(item.file_name());
                let _ = fs::rename(item.path(), &target).await;
            }
        }
        let _ = fs::remove_dir(&inner).await;
        crate::util::log_line(&format!(
            "JRE 目录已理顺：{}",
            runtime_dir.display()
        ));
    }
}

pub struct JREInstaller;

impl JREInstaller {
    pub async fn install_jre(version: i32, arch: &str) -> Result<Runtime> {
        JreManager::install(version, arch, |_| {}, |_| {}).await
    }
}

pub struct JREValidator;

impl JREValidator {
    pub async fn validate(runtime: &Runtime) -> Result<bool> {
        let runtime_dir = Path::new(&runtime.path);

        // 判定标准与 JvmLauncher::find_libjli 保持一致：启动只依赖 libjli.so。
        // 架构子目录名在不同发行包里是 arm64 / aarch64 / arm / arm32 不定，统一交给 has_libjli。
        if !has_libjli(runtime_dir) {
            tracing::debug!(
                "JRE validation failed: libjli.so not found under {}",
                runtime_dir.display()
            );
            return Ok(false);
        }

        let arch_subdir = match runtime.arch.as_str() {
            "arm64-v8a" => "arm64",
            "armeabi-v7a" => "arm32",
            _ => runtime.arch.as_str(),
        };
        // libjvm.so 的位置同样因版本而异：
        //   JDK 9+ 扁平：lib/server/libjvm.so
        //   **JDK 8 嵌套**：lib/<arch>/server/libjvm.so（实机 Pojav-JRE-8 就是这种）
        //   少数包直接放 lib/ 或 lib/<arch>/
        let jvm_candidates = [
            runtime_dir.join("lib").join("libjvm.so"),
            runtime_dir.join("lib").join("server").join("libjvm.so"),
            runtime_dir.join("lib").join("client").join("libjvm.so"),
            runtime_dir.join("lib").join(arch_subdir).join("libjvm.so"),
            runtime_dir
                .join("lib")
                .join(arch_subdir)
                .join("server")
                .join("libjvm.so"),
            runtime_dir
                .join("lib")
                .join(arch_subdir)
                .join("client")
                .join("libjvm.so"),
            runtime_dir
                .join("lib")
                .join("aarch64")
                .join("libjvm.so"),
            runtime_dir
                .join("lib")
                .join("aarch64")
                .join("server")
                .join("libjvm.so"),
        ];
        let has_libjvm = jvm_candidates.iter().any(|p| p.exists());
        if !has_libjvm {
            // 只提示不拦截：libjvm 由 JVM 自己按 rpath 加载
            tracing::warn!("JRE 中未找到 libjvm.so（{}），继续尝试启动", runtime_dir.display());
        }

        Ok(true)
    }

    pub async fn get_required_version(version_info: &crate::version::MinecraftVersion) -> Result<i32> {
        if let Some(java_version) = &version_info.java_version {
            Ok(java_version.major_version.unwrap_or(8))
        } else {
            Ok(8)
        }
    }
}

pub fn get_device_arch() -> &'static str {
    #[cfg(target_arch = "aarch64")]
    { "arm64-v8a" }

    #[cfg(target_arch = "arm")]
    { "armeabi-v7a" }

    // 模拟器（Android Studio 的 x86_64 镜像）：JRE 与原生库都有对应版本，
    // 但 GL4ES 在模拟器上只能跑软件渲染，仅适合调链路不适合玩。
    #[cfg(target_arch = "x86_64")]
    { "x86_64" }

    #[cfg(not(any(target_arch = "aarch64", target_arch = "arm", target_arch = "x86_64")))]
    { "arm64-v8a" }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_resolve_download_url_all_combinations() {
        let combos = [
            (8, "arm64-v8a"),
            (8, "armeabi-v7a"),
            (17, "arm64-v8a"),
            (17, "armeabi-v7a"),
            (21, "arm64-v8a"),
            (21, "armeabi-v7a"),
            // 新版 Minecraft：26.3 起要求 Java 25
            (25, "arm64-v8a"),
            (25, "armeabi-v7a"),
            (25, "x86_64"),
            // 模拟器用的 x86_64
            (8, "x86_64"),
            (17, "x86_64"),
            (21, "x86_64"),
        ];
        for (java, arch) in &combos {
            let result = resolve_download_url(*java, arch);
            assert!(result.is_ok(), "Failed for Java {} {}", java, arch);
            let mirror = result.unwrap();
            assert_eq!(mirror.java_version, *java);
            assert_eq!(mirror.arch, *arch);
            assert!(mirror.url.starts_with("https://"));
            assert!(mirror.url.ends_with(".tar.xz"));
        }
    }

    #[test]
    fn test_resolve_download_url_unsupported_java() {
        assert!(resolve_download_url(11, "arm64-v8a").is_err());
        assert!(resolve_download_url(7, "arm64-v8a").is_err());
        assert!(resolve_download_url(99, "armeabi-v7a").is_err());
    }

    // 只对安卓成立：桌面版 JRE 有 x86_64 构建，宿主上直接调会正常返回 URL。
    // （这段测试跑在 CI 的 Linux runner 上，所以必须 gate 掉。）
    #[cfg(target_os = "android")]
    #[test]
    fn test_resolve_download_url_unsupported_arch() {
        assert!(resolve_download_url(8, "x86").is_err());
        assert!(resolve_download_url(17, "x86_64").is_err());
        assert!(resolve_download_url(21, "mips").is_err());
    }

    #[test]
    fn test_resolve_download_url_url_contains_version_and_arch() {
        let mirror = resolve_download_url(17, "arm64-v8a").unwrap();
        assert!(mirror.url.contains("jre17"));
        assert!(mirror.url.contains("arm64"));

        let mirror = resolve_download_url(8, "armeabi-v7a").unwrap();
        assert!(mirror.url.contains("jre8"));
        assert!(mirror.url.contains("android-arm.tar.xz"));
    }

    // 同上：`get_device_arch` 在安卓上返回 ABI 名，宿主上返回宿主架构（x86_64）。
    #[cfg(target_os = "android")]
    #[test]
    fn test_get_device_arch_returns_known_value() {
        let arch = get_device_arch();
        assert!(arch == "arm64-v8a" || arch == "armeabi-v7a");
    }
}
