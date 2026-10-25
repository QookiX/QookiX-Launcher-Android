use std::collections::HashMap;
use std::path::Path;
use std::sync::Mutex;
use std::sync::OnceLock;
use tokio::fs;
use anyhow::{Context, Result};
use crate::models::*;
use crate::version::MinecraftVersion;
use crate::java::{MultiRTManager, JreManager, JREValidator, get_device_arch};
use crate::natives::NativesExtractor;
use crate::jvm_launcher::JvmLauncher;

static GAME_STATUS: OnceLock<Mutex<GameStatus>> = OnceLock::new();

fn init_statics() {
    GAME_STATUS.get_or_init(|| Mutex::new(GameStatus {
        is_running: false,
        instance_id: None,
        pid: None,
        exit_code: None,
        error: None,
    }));
}

pub async fn launch_game(instance_id: &str, account: Option<&Account>) -> Result<GameStatus> {
    // ⚠️ 排查「-6 双启动」：launch_game 每次进入都在日志留痕。
    // 正常情况一次点击只有一条；出现两条 = 上层有两个触发源。
    crate::util::log_line(&format!("[launcher] === launch_game 被调用 instance={instance_id} pid={}", std::process::id()));
    crate::fsutil::validate_id(instance_id, "实例").map_err(|e| anyhow::anyhow!(e))?;
    init_statics();

    {
        let mut status = GAME_STATUS.get().unwrap().lock().unwrap();
        if status.is_running {
            return Err(anyhow::anyhow!("GAME_ALREADY_RUNNING"));
        }
        status.is_running = true;
        status.instance_id = Some(instance_id.to_string());
        status.pid = None;
        status.exit_code = None;
        status.error = None;
    }
    // 前端靠 launch://state 显示「运行中」并提供「关闭所有实例」
    crate::progress::emit_launch_state(instance_id, "running", 0, None);

    // 记录游戏启动前的工作目录，游戏退出后恢复（见下面的 chdir）

    let mut previous_dir: Option<std::path::PathBuf> = None;

    
    let launch_result = async {
        let data_dir = crate::settings::get_data_dir().await?;
        let instance_dir = crate::settings::instance_dir(instance_id).await?;

        let instance_file = instance_dir.join("instance.json");
        let content = fs::read_to_string(&instance_file).await
            .context("Failed to read instance")?;

        let instance: MinecraftProfile = serde_json::from_str(&content)
            .map_err(|e| anyhow::anyhow!("INSTANCE_CONFIG_CORRUPT: {}", e))?;

        // 空实例兜底：没装游戏文件就启动，JVM 找不到 client jar 会直接失败 ——
        // 现象是「黑屏 / 秒退」，日志里只有 LWJGL 报错，用户完全看不出是没装。
        if !crate::version::is_version_installed(&instance.mc_version).await {
            return Err(anyhow::anyhow!("INSTANCE_NOT_INSTALLED"));
        }

        let version_info = crate::version::get_version_info(&instance.mc_version).await?;

        let required_java = JREValidator::get_required_version(&version_info).await?;

        crate::progress::emit_launch_progress(
            &format!("正在检查 Java {required_java} 运行时…"),
            20.0,
        );
        let runtime = match MultiRTManager::get_compatible_runtime(required_java).await? {
            Some(r) => r,
            None => {
                // 先确认这个 Java 版本到底**下得下来**：新版 Minecraft 会要求更新的 Java
                // （例如 26.3 要求 Java 25），而镜像表里没有的版本会一路抛到界面，
                // 用户只看到 `JRE_ARCH_UNSUPPORTED: no JRE for Java 25 on arm64-v8a` 这种内部错误。
                if crate::java::resolve_download_url(required_java, get_device_arch()).is_err() {
                    let supported = crate::java::supported_java_versions()
                        .iter()
                        .map(|v| v.to_string())
                        .collect::<Vec<_>>()
                        .join(" / ");
                    return Err(anyhow::anyhow!(
                        "JAVA_VERSION_UNSUPPORTED: 该版本需要 Java {}，而启动器目前只支持 Java {}。\n\
                         可以在「设置 → 游戏内 → Java 路径」里手动指定一个已安装的 Java {}，\
                         或先在设置里改用其它游戏版本（如 1.20.x / 1.21.x）。",
                        required_java,
                        supported,
                        required_java
                    ));
                }
                tracing::info!("No compatible JRE found, downloading Java {} for {}", required_java, get_device_arch());
                // 实际下载包约 27MB（解压后 150~250MB）。以前这里写「约 100MB」，差得太远。
                crate::progress::emit_launch_progress(
                    &format!("未找到 Java {required_java}，正在下载运行时（约 27MB）…"),
                    25.0,
                );
                // JRE 下载很慢，必须把百分比报给悬浮启动卡片，否则用户以为没反应。
                // `install()` 把整体进度分成两段：下载 0..0.7、解压 0.7..1.0，
                // 这里统一映射到启动流程的 25%..65%。
                let last_pct = std::sync::Arc::new(std::sync::atomic::AtomicI32::new(-100));
                let last_pct_for_phase = last_pct.clone();
                JreManager::install(
                    required_java,
                    get_device_arch(),
                    move |p: f32| {
                        let pct = 25.0 + p as f64 * 40.0;
                        let as_int = pct as i32;
                        if as_int - last_pct.load(std::sync::atomic::Ordering::Relaxed) >= 2 {
                            last_pct.store(as_int, std::sync::atomic::Ordering::Relaxed);
                            // 文案用阶段中性的说法：这个回调在「下载中」和
                            // 「解压刚结束（install 收尾报 100%）」两个时刻都会触发，
                            // 写死「正在下载」会在解压阶段显示成假信息。
                            crate::progress::emit_launch_progress("正在准备 Java 运行时…", pct);
                        }
                    },
                    move |phase: &str| {
                        // 解压这几十秒里没有任何百分比可报，至少把文案换对，
                        // 否则用户会以为还卡在下载。
                        last_pct_for_phase.store(63, std::sync::atomic::Ordering::Relaxed);
                        crate::progress::emit_launch_progress(phase, 63.0);
                    },
                )
                .await?
            }
        };

        if !JREValidator::validate(&runtime).await? {
            return Err(anyhow::anyhow!(
                "JRE 校验失败（{}）：{} 下找不到 libjli.so/libjvm.so",
                runtime.name,
                runtime.path
            ));
        }

        let libraries_dir = Path::new(&data_dir).join("libraries");
        let natives_cache_dir = Path::new(&data_dir)
            .join("cache")
            .join("natives")
            .join(instance_id);

        crate::progress::emit_launch_progress("正在解压运行库…", 70.0);
        let natives_dir = NativesExtractor::extract(&libraries_dir, get_device_arch(), &natives_cache_dir).await?;

        // ⚠️ 2026-10-20 真机修：把 MC 自带（且已经解压到缓存里的）LWJGL 原生库删掉。
        //
        // `natives.rs::get_blacklist` 只能挡住**新解压**；之前版本解出来的
        // 官方 3.3.3 `liblwjgl*.so` 会留在 `<data>/natives` 里继续参与加载。
        // 与随包 Pojav 版（配合 `lwjgl-glfw-classes.jar` 的 3.4.0 类）混在一起，
        // 就是那句 `Incompatible Java and native library versions detected`，
        // 紧接着 `GLFW.<clinit>` → `NoClassDefFoundError` → Minecraft has crashed。
        //
        // 每次启动顺手清理一遍（都是小文件，开销可忽略）；删不掉也不影响启动。
        #[cfg(target_os = "android")]
        {
            const LWJGL_SOS: &[&str] = &[
                "liblwjgl.so",
                "liblwjgl_opengl.so",
                "liblwjgl_openal.so",
                "liblwjgl_stb.so",
                "liblwjgl_tinyfd.so",
                "liblwjgl_nanovg.so",
                "liblwjgl_shaderc.so",
                "liblwjgl_spvc.so",
                "liblwjgl_spng.so",
                "liblwjgl_vma.so",
                "liblwjgl_vulkan.so",
                "liblwjgl_freetype.so",
                "liblwjgl_sdl.so",
            ];
            let mut cleaned = 0usize;
            for name in LWJGL_SOS {
                let p = natives_dir.join(name);
                if p.exists() && std::fs::remove_file(&p).is_ok() {
                    cleaned += 1;
                }
            }
            if cleaned > 0 {
                println!("[launcher] 已清理 {cleaned} 个旧版 LWJGL 原生库（避免版本冲突）");
            }
        }

        // 安卓端真正的基础原生库（liblwjgl / libgl4es / libopenal …）随 APK 分发。
        // 新版本安卓默认不从 APK 解出 .so，这里解析出（必要时从 APK 抽取）一个
        // 能让 JVM `System.loadLibrary` 成功的目录。
        #[cfg(target_os = "android")]
        let apk_native_dir = crate::android_env::resolve_native_lib_dir(&data_dir)
            .await
            .map_err(|e| anyhow::anyhow!("安卓原生库准备失败：{e}"))?;

        // 游戏内 DNS：安卓没有 /etc/resolv.conf，JRE 补丁靠 -Dext.net.resolvPath
        // 读这份文件（不写的话 mod 版本检查之类全部 DNS 超时）。失败不挡启动。
        #[cfg(target_os = "android")]
        if let Err(e) = crate::android_env::ensure_resolv_conf(&data_dir) {
            tracing::warn!("[launcher] resolv.conf 写出失败（游戏内 DNS 将不可用）：{e}");
        }

        // MC 26.3 起清单里是 `org.lwjgl:lwjgl:3.4.3`（窗口层换成 SDL3）——
        // 这时必须换成 3.4.1 组件集，否则 org.lwjgl.sdl 整个包缺失。
        let use_lwjgl341 = needs_lwjgl341(&version_info);
        if use_lwjgl341 {
            tracing::info!("[launcher] 版本要求 LWJGL ≥3.4.1，启用 SDL3 组件集");
        }
        let vendor = load_vendor_jars(&data_dir, runtime.java_version, use_lwjgl341).await?;

        let mut classpath = build_classpath(&version_info, &data_dir, &vendor).await?;

        // ⚠️ 2026-10-19 加：把加载器 profile 的库并入 classpath。
        // 以前 classpath 只来自原版 version_info，装了 Fabric/Forge 也跑原版。
        {
            let versions_dir = Path::new(&data_dir).join("versions");
            let loader_str = loader_name(&instance.loader);
            if loader_str != "vanilla" {
                if let Some(profile) = crate::loader_install::find_loader_profile(
                    &versions_dir,
                    &loader_str,
                    &instance.mc_version,
                ) {
                    // ⚠️ 2026-10-25 时序修复：strip 必须在 classpath 合并**之前**。
                    // 原来只在下面构造 jvm/game 参数前才 strip —— 但这段代码上面
                    // 已经把 profile 的 libraries 并进 classpath 了，earlydisplay
                    // 总能混进去，gl4es 上 ElementShader link 失败
                    //（"An error occurred initializing shaders"）卡死启动画面。
                    // 幂等：已清过就什么都不做。
                    crate::loader_install::strip_earlydisplay_library(&profile);
                    let libs_dir = Path::new(&data_dir).join("libraries");
                    let (lib_paths, missing) =
                        crate::loader_install::profile_library_paths(&profile, &libs_dir);
                    if missing > 0 {
                        // 列出到底缺哪个文件：只报数字时，用户（和我们）
                        // 都没法判断是「一个都没下」还是「只差最后一个」。
                        let all = crate::loader_install::profile_library_names(&profile);
                        let have = lib_paths.len();
                        println!(
                            "[launcher] 加载器依赖缺失 {} 个（已就绪 {} / 共 {}）",
                            missing,
                            have,
                            have + missing
                        );
                        for name in all.iter().take(12) {
                            println!("[launcher]   声明: {name}");
                        }
                        crate::util::log_line(&format!(
                            "[launcher] 加载器依赖缺失 {missing} 个（已就绪 {have} / 共 {}），声明={:?}",
                            have + missing,
                            all
                        ));
                    }
                    // ⚠️ 加载器的库必须**顶掉**原版里同 group:artifact 的库。
                    //
                    // 实测（1.21.8 + Fabric 0.20.0）：
                    //   原版带 `org.ow2.asm:asm:9.6`，Fabric 带 `9.11`，
                    //   两份都在 classpath 上 → KnotClient 初始化时直接报
                    //     IllegalStateException: duplicate ASM classes found on classpath
                    //   于是加载器一启动就死，看起来还是「装了没用」。
                    //
                    // 这里的规则：只要加载器 profile 提供了某个 artifact，
                    // 原版那份就从 classpath 里剔除（版本以加载器为准，
                    // 与桌面端 forge/fabric patch 的「子覆盖父」一致）。
                    let ldr_names: Vec<String> = crate::loader_install::profile_library_names(&profile);
                    if !ldr_names.is_empty() {
                        let kept: Vec<&str> = classpath
                            .split(':')
                            .filter(|entry| {
                                // 只对 libraries 下的条目做判断；游戏本体 jar 等一律保留
                                if !entry.contains("/libraries/") {
                                    return true;
                                }
                                !ldr_names
                                    .iter()
                                    .any(|name| {
                                        crate::loader_install::path_matches_maven(entry, name)
                                    })
                            })
                            .collect();
                        classpath = kept.join(":");
                    }
                    for p in lib_paths {
                        classpath.push_str(":");
                        classpath.push_str(&p.to_string_lossy());
                    }
                } else {
                    println!(
                        "[launcher] 未找到 {} 的加载器 profile，将以原版启动（请先补全文件安装加载器）",
                        loader_str
                    );
                }
            }
        }

        let mut jvm_args = build_jvm_args(&instance, &runtime, &vendor);

        // 正版账号：启动前把 refresh_token 换成真正的 Minecraft access token。
        // 换不出来就明确失败 —— 不能拿 refresh token 冒充 access token 骗过启动流程
        // （那样游戏能起来，但一连正版服务器就 401/掉线，用户根本不知道发生了什么）。
        let ms_token = match account {
            Some(acc) if acc.account_type == crate::models::AccountType::Microsoft => Some(
                crate::accounts::minecraft_access_token(acc)
                    .await
                    .map_err(|e| anyhow::anyhow!("正版会话已过期，请重新登录微软账号：{e}"))?,
            ),
            _ => None,
        };
        let game_args = build_game_args(&instance, account, &data_dir, ms_token.as_deref())?;

        let game_dir = instance.game_dir.clone();

        // 整合包自带的 options.txt 里可能有 `fullscreen:off` 这种非法布尔值，
        // 游戏会报 "Not a boolean: off" 并丢掉该设置 —— 启动前顺手改正。
        normalize_options_txt(Path::new(&game_dir));

        // Minecraft 的 Log4j 不会自己创建 logs/ 目录，缺了会直接
        // `FileNotFoundException: logs/latest.log`（游戏仍会继续，但日志丢失）。
        for sub in [
            "logs",
            "crash-reports",
            "saves",
            "resourcepacks",
            "shaderpacks",
            "mods",
            "config",
        ] {
            let _ = fs::create_dir_all(Path::new(&game_dir).join(sub)).await;
        }

        #[cfg(target_os = "android")]
        jvm_args.extend(build_android_jvm_args(
            &instance,
            &runtime,
            &data_dir,
            &game_dir,
            &natives_dir.to_string_lossy(),
            &apk_native_dir,
            vendor.lwjgl_natives.as_deref(),
            &vendor.extra_library_dirs,
        ));

        // ── 加载器 profile 声明的参数（`arguments.jvm` / `arguments.game`）──
        //
        // ⚠️ 2026-10-19 补：之前只并了 classpath 和 mainClass，参数一条都没带。
        // 对 Forge 1.17+ / NeoForge 是致命的 —— 它们的主类是
        // `cpw.mods.bootstraplauncher.BootstrapLauncher`，必须靠 profile 里的
        //   `-p <模块路径>`（bootstraplauncher / securejarhandler / JarJarFileSystems）
        //   `--add-modules ALL-MODULE-PATH`
        //   `--add-opens java.base/...=cpw.mods.securejarhandler`
        // 才能把游戏拉起来。缺了这些，JVM 要么报 `Unrecognized option`，
        // 要么找不到主类，表现就是「加载器装了但游戏起不来」。
        //
        // 注意顺序：加载器的 JVM 参数放在 Android 那批**之后**；
        // 游戏参数**追加在原版之后**（与桌面端 forge_patch 一致 ——
        // vanilla 的 `--username` / `--gameDir` 在前，加载器的
        // `--launchTarget` / `--fml.*` 在后；FML 的参数解析会扫描全部参数）。
        let mut game_args = game_args;
        {
            let loader_str = loader_name(&instance.loader);
            if loader_str != "vanilla" {
                let versions_dir = Path::new(&data_dir).join("versions");
                if let Some(profile) = crate::loader_install::find_loader_profile(
                    &versions_dir,
                    &loader_str,
                    &instance.mc_version,
                ) {
                    // ⚠️ 2026-10-23 崩溃修复（第二次尝试，第一次方向错了）：
                    // earlydisplay 不在 `-p` 上，而是 profile `libraries` 里的一条，
                    // 由 BootstrapLauncher 按 `-DlibraryDirectory` 自己加载 →
                    // 必须先就地把 json 里的这条删掉，后面的参数解析才不会再带上它。
                    // 幂等：已经清过就什么都不做（只读一次 json）。
                    crate::loader_install::strip_earlydisplay_library(&profile);

                    let libs_dir = Path::new(&data_dir).join("libraries");
                    let ldr_args = crate::loader_install::profile_arguments(
                        &profile,
                        &libs_dir,
                        &instance.mc_version,
                        ":",
                    );
                    // 注意：这条 println 在 redirect_output **之前**，只会进 logcat。
                    // 真正要落盘的那几条在 redirect_output 之后（见下方「加载器摘要」）。
                    if !ldr_args.is_empty() {
                        crate::util::log_line(&format!(
                            "[launcher] 加载器参数：jvm {} 条 / game {} 条 / legacy {} 条",
                            ldr_args.jvm.len(),
                            ldr_args.game.len(),
                            ldr_args.legacy.len()
                        ));
                    }
                    jvm_args.extend(ldr_args.jvm);
                    // 追加在 vanilla 之后（顺序与桌面端一致）
                    game_args.extend(ldr_args.game);
                    // 旧式 profile（1.12.2 等）只有 minecraftArguments：
                    // 原版那边同样有，重复项由 Minecraft 自己忽略。
                    game_args.extend(ldr_args.legacy);
                }
            }
        }

        let mut env_map = HashMap::new();
        env_map.insert("JAVA_HOME".to_string(), runtime.path.clone());
        env_map.insert("HOME".to_string(), game_dir.clone());
        // 每实例渲染器：实例设置 → （auto 时）按 MC 版本 → （global 时）全局设置。
        // 选中的渲染器会写进启动日志，启动浮层里能直接看到「用的是哪个、为什么」。
        let mut renderer_source = String::new();
        let mut renderer = resolve_renderer(Some(&instance), &mut renderer_source);
        crate::util::log_line(&format!(
            "渲染器：{}（{}）",
            renderer_display_name(&renderer),
            renderer_source
        ));
        // 渲染器/驱动已改为插件分发，APK 里只随包 GL4ES 兜底。
        // 选中的渲染器在插件目录和随包目录都找不到时，必须回退 GL4ES ——
        // 否则 C 层会去 dlopen 一个不存在的库（空桥接表直接 SIGSEGV）。
        #[cfg(target_os = "android")]
        if !renderer_available(&data_dir, &apk_native_dir, &renderer) {
            let chosen = renderer.clone();
            renderer = "opengles2".to_string();
            let msg = format!(
                "渲染器「{}」未安装（找不到 {}），本次启动回退到 GL4ES；可在「设置 → 插件」安装",
                chosen,
                renderer_gl_lib(&chosen)
            );
            tracing::warn!("[launcher] {msg}");
            crate::util::log_line(&msg);
        }

        // 注意：**不要**设置 LD_LIBRARY_PATH。
        // 实测（1.8.9 与 1.18.2 都试过）覆盖它会让 libjvm.so 加载失败，
        // 于是 JLI 退化成 `exec bin/java`，而 Android 禁止执行应用私有目录 →
        // 报 `Permission denied / Error: trying to exec .../bin/java`，比不设更糟。
        // JDK 8 自带的原生库找不到依赖（libnio.so → libnet.so）改用
        // `preload_jre_libs` 在 JVM 启动前逐个 System.load 解决，见那里。

        // POJAV_NATIVEDIR 在 C 层被当作**库搜索路径**用（`egl_bridge.c` 的
        // `linker_ns_load` → 拼进 android_create_namespace 的 default_library_path，
        // 也就是 zink/Turnip 那条链路）。驱动/渲染器插件的目录必须排在最前，
        // 否则命名空间里只会命中 APK 里随包那份，插件等于没装。
        // 该变量只有这一处消费者，放冒号分隔的列表是它本来就支持的用法。
        let mut native_search_dirs: Vec<String> = Vec::new();
        // LWJGL 3.4.1 组件带来的 natives **必须排最前**。
        // Android 加载 .so 是按「linker 命名空间里的 soname」找的，`java.library.path`
        // 基本不参与（见 build_android_jvm_args 里的注释）——而 APK 的 jniLibs 与
        // `files/natives` 里还躺着老的 3.3.x `liblwjgl*.so`。不塞进这里的话会命中旧的：
        // 现象就是 LWJGL 直接报
        // `[LWJGL] [ERROR] Incompatible Java and native library versions detected.`
        // 随后启动中断（26.3 在全新安装的机器上实测）。
        if let Some(dir) = &vendor.lwjgl_natives {
            native_search_dirs.push(dir.to_string_lossy().to_string());
        }
        native_search_dirs.extend(
            vendor
                .extra_library_dirs
                .iter()
                .map(|p| p.to_string_lossy().to_string()),
        );
        native_search_dirs.push(natives_dir.to_string_lossy().to_string());
        env_map.insert(
            "POJAV_NATIVEDIR".to_string(),
            native_search_dirs.join(":"),
        );
        // Pojav 的 Java 层会设这些变量，libpojavexec 的 pojavInitOpenGL 直接读它们：
        // 读不到就是 NULL，`strcmp(NULL,...)` 会段错误（不是可选优化，是必须项）。
        // 2026-10-24 改名 POJAV_RENDERER → QOOKIX_RENDERER：Sodium 0.8.13 的
        // PostLaunchChecks.isUsingPojavLauncher() 只要发现 POJAV_RENDERER 这个
        // 环境变量就直接中止（PreLaunchChecks 的 LWJGL 版本串检查早已通过），
        // 所以 C 侧源码（egl_bridge.c 等 3 处）同步改读新名字。
        env_map.insert(
            "QOOKIX_RENDERER".to_string(),
            renderer_c_name(&renderer).to_string(),
        );
        // 26.3：把「游戏会加载的那份 libSDL3」的绝对路径告诉 C 层。
        // 它要在 `SDL_Init` 之前对**同一个映射**调 `SDL_SetMainReady` —— SDL 的全局状态
        // 按映射隔离，给错文件等于没调（实测表现就是
        // `Unable to initialize SDL: Application didn't initialize properly`）。
        if use_lwjgl341 {
            // 注意要在**所有**插件 libs 目录里找（列表第一个未必是组件插件那个，
            // 渲染器/驱动插件也有 libs 目录，顺序不保证）。
            let sdl3 = vendor
                .extra_library_dirs
                .iter()
                .map(|d| d.join("libSDL3.so"))
                .find(|p| p.exists());
            if let Some(path) = sdl3 {
                env_map.insert(
                    "POJAVEXEC_SDL3".to_string(),
                    path.to_string_lossy().to_string(),
                );
            }
        }
        env_map.insert("FORCE_VSYNC".to_string(), "true".to_string());
        env_map.insert("POJAV_EMUI_ITERATOR_MITIGATE".to_string(), "false".to_string());
        env_map.insert("POJAV_VSYNC_IN_ZINK".to_string(), "false".to_string());
        env_map.insert("TMPDIR".to_string(), format!("{}/cache", data_dir.trim_end_matches('/')));

        // SDL 窗口层（MC 26.3+）**自己**用 `SDL_GL_LoadLibrary` 加载 GL 实现，
        // 而 MC 随后会比对「SDL 拿到的 glGetError 指针」与 LWJGL 用的库是否一致 ——
        // 不一致就 `BackendCreationException: glGetError mismatch`（26.3 实测）。
        // 所以必须把 `SDL_OPENGL_LIBRARY` 指到与 `-Dorg.lwjgl.opengl.libname` 同一个文件。
        // 给绝对路径，避免 linker 命名空间找不到（files/natives 不在默认搜索路径里）。
        // 渲染器库的来源：插件目录优先，其次是随包解压出来的 `<data>/natives`。
        // 注意**不是** `natives_dir` —— 那是 Minecraft 运行库的解压缓存目录，
        // 里面没有渲染器库（用错就是 SDL 的 `Could not load EGL library`）。
        let gl_file = renderer_gl_lib(&renderer).to_string();
        // v2：如果「当前选中的渲染器」由某个已启用的渲染器插件提供，就优先用它
        // （用户可以单独更新/替换渲染器，不必等启动器发版）；否则退回随包内置。
        let renderer_plugin_libs = crate::plugin::renderer_libs_dir(
            &data_dir,
            renderer_plugin_key(&renderer),
        );
        let plugin_libs = renderer_plugin_libs
            .as_ref()
            .map(|p| p.to_string_lossy().to_string())
            .or_else(|| {
                vendor
                    .extra_library_dirs
                    .first()
                    .map(|p| p.to_string_lossy().to_string())
            });
        let native_dir_str = match plugin_libs {
            Some(dir) if Path::new(&dir).join(&gl_file).exists() => dir,
            // 桌面端没有「APK 内的原生库目录」这个概念，留空即可
            #[cfg(target_os = "android")]
            _ => apk_native_dir.trim_end_matches('/').to_string(),
            #[cfg(not(target_os = "android"))]
            _ => String::new(),
        };
        env_map.insert(
            "SDL_OPENGL_LIBRARY".to_string(),
            format!("{}/{}", native_dir_str, renderer_gl_lib(&renderer)),
        );
        // MobileGlues 自带 EGL（GL 4.6 → GLES 3.x），SDL 也得用同一份 EGL，
        // 否则 SDL 会去建系统 EGL 上下文、与 MG 的上下文打架。
        if renderer == "libmobileglues.so" {
            // MG 默认去 `/sdcard/MG` 读 config.json、写 glsl_cache.tmp ——
            // 安卓 11+ 的分区存储下我们**没权限访问那里**：配置读不到就用默认值
            // （`maxGlslCacheSize = 0`，等于关掉着色器翻译缓存），缓存也写不进去，
            // 于是世界里的着色器每帧重翻译一次（实测 1.18.2 只有 2 FPS / 500ms 一帧）。
            // 指到应用私有目录后配置生效、缓存可落盘（下次启动直接命中）。
            if let Some(mg_dir) = ensure_mobileglues_dir(&data_dir) {
                env_map.insert("MG_DIR_PATH".to_string(), mg_dir);
            }
            // MG 的「启动器检测」（源码 `MobileGlues-cpp/config/settings.cpp`）：
            //   if (isInPluginApp == 0 && fclVersion == 0 && zlVersion == 0
            //       && pgwVersion == 0 && !is_custom_mg_dir)
            //       → 打印 "Unsupported launcher detected, force using default config."
            //       → **强制**覆盖 9 项设置（其中 `noErrorConfig = Auto` → 最终
            //         `ignore_error = None`，也就是我们最需要的「忽略 shader 错误」被关掉）
            // 所以这四个变量**任给一个非 0** 就会被当成受支持的启动器，配置才会被尊重。
            env_map.insert("MG_PLUGIN_STATUS".to_string(), "1".to_string());
            env_map.insert("FCL_VERSION_CODE".to_string(), "1335".to_string());
            env_map.insert("ZALITH_VERSION_CODE".to_string(), "2000".to_string());
            env_map.insert("PGW_VERSION_CODE".to_string(), "1".to_string());
            let egl_path = format!("{native_dir_str}/libmobileglues.so");
            env_map.insert("SDL_EGL_LIBRARY".to_string(), egl_path.clone());
            // 插件给的就是**真实文件路径**，C 层的 `loader_dlopen` 直接用绝对路径最稳；
            // 内置那份在 APK 的 zip 路径里，还是用库名让 linker 在命名空间里找。
            env_map.insert(
                "POJAVEXEC_EGL".to_string(),
                if renderer_plugin_libs.is_some() {
                    egl_path
                } else {
                    "libmobileglues.so".to_string()
                },
            );
        }

        if renderer == "vulkan_zink" {
            // Zink = Mesa 的「OpenGL on Vulkan」。C 层自己也会 setenv GALLIUM_DRIVER=zink，
            // 这里显式再设一遍更稳；MESA_GLSL_CACHE_DIR 让着色器缓存落到应用私有目录，
            // 否则每次进游戏都要重编译一遍（很慢）。
            env_map.insert("GALLIUM_DRIVER".to_string(), "zink".to_string());
            env_map.insert("MESA_LOADER_DRIVER_OVERRIDE".to_string(), "zink".to_string());
            let cache_dir = format!("{}/cache", data_dir.trim_end_matches('/'));
            env_map.insert("MESA_GLSL_CACHE_DIR".to_string(), cache_dir.clone());
            // 下面这几项是 Pojav 原版在 zink 路径上一定会设的，移植时漏了：
            // 前三个放宽 GLSL 版本/扩展限制（Minecraft 的着色器常用高版本语法），
            // VTEST_SOCKET_NAME 是 virgl/zink 用来放测试 socket 的位置。
            env_map.insert("force_glsl_extensions_warn".to_string(), "true".to_string());
            env_map.insert("allow_higher_compat_version".to_string(), "true".to_string());
            env_map.insert(
                "allow_glsl_extension_directive_midshader".to_string(),
                "true".to_string(),
            );
            env_map.insert("VTEST_SOCKET_NAME".to_string(), format!("{cache_dir}/.virgl_test"));
            // 注意 egl_bridge.c 里是**存在性**判断（`getenv(...) == NULL` 才跳过），
            // 所以给 GL4ES 时绝不能设这个变量，否则一样会去加载 Turnip。
            env_map.insert("POJAV_LOAD_TURNIP".to_string(), "1".to_string());
            env_map.insert("LIBGL_ES".to_string(), "3".to_string());
            // OSMesa 是被 **C 层**按库名 dlopen 的（`osmesa_loader.c`），
            // 走 linker 命名空间只会命中 APK 里那份 —— 渲染器插件要想生效，
            // 得把绝对路径显式告诉它（C 层读 POJAVEXEC_OSMESA）。
            if let Some(dir) = &renderer_plugin_libs {
                let osmesa = dir.join("libOSMesa.so");
                if osmesa.exists() {
                    env_map.insert(
                        "POJAVEXEC_OSMESA".to_string(),
                        osmesa.to_string_lossy().to_string(),
                    );
                }
            }
        } else {
            // GL4ES 专有：mipmap 与「忽略 GL 错误」（后者能躲掉整合包里的无效调用）
            env_map.insert("LIBGL_MIPMAP".to_string(), "3".to_string());
            env_map.insert("LIBGL_NOERROR".to_string(), "1".to_string());
            // 1.17+ 需要 OpenGL 3.2 才能渲染主界面全景/帧缓冲，
            // ES2 下这些会静默失败（表现就是主界面背景一片灰）。
            env_map.insert("LIBGL_ES".to_string(), "3".to_string());
        }

        for (key, value) in &env_map {
            std::env::set_var(key, value);
        }

        // ⚠️ 2026-10-19 加：优先用加载器 profile 的 mainClass。
        // 原版的 `net.minecraft.client.main.Main` 会直接跑原版 MC，
        // Forge 要用 BootstrapLauncher、Fabric 要用 KnotClient ——
        // 这是「加载器真正生效」的关键一步。
        // 启动前清一遍 mods 里的残废文件：`.part` 半成品 + 尾部截断的 jar。
        // 真机事故：NeoForge 在 setup 阶段扫到打不开的 zip 就中止整个 mod 加载
        // （日志只有一句 `zip END header not found`，表现是「一个模组都不加载」）。
        if let Ok(inst_dir) = crate::settings::instance_dir(&instance_id).await {
            crate::instance_files::sanitize_mods_dir_public(&inst_dir.join("mods"));
        }

        let loader_str2 = loader_name(&instance.loader);

        // ── NeoForge / Forge 的补丁产物前置校验（2026-10-23）──────────────
        //
        // 崩溃链（真机日志闭环，已确认）：
        //   processors 没跑 → libraries 里没有打补丁的 client / client-extra.jar
        //   → FML 的 ModList 里 `minecraft`/`neoforge` 不存在
        //   → `ModSorter: Missing or unsupported mandatory dependencies`
        //   → `Optional.orElseThrow → NoSuchElementException` → 进程闪退
        //
        // 而「跑 installer」在安卓上有硬限制：**不允许 exec 应用私有目录里的 java**
        // （子进程方式直接 `启动 installer 失败`），只能用同进程 JVM ——
        // 但 JNI_CreateJavaVM 一个进程只能调一次，装完必须重启 App 才能进游戏。
        // 这套流程还没实现（见 CHANGELOG / 待办），所以这里**先明确拒绝启动**，
        // 而不是让用户对着"闪退"猜。
        if matches!(loader_str2.as_str(), "forge" | "neoforge") {
            let loader_ver = instance.loader_version.clone().unwrap_or_default();
            let patch_marker = Path::new(&data_dir)
                .join("libraries")
                .join(format!(
                    ".qookix-processors-{loader_str2}-{loader_ver}.done"
                ));
            if !patch_marker.exists() {
                let msg = format!(
                    "{loader_str2} 需要先跑官方的补丁（processors）才能加载模组，\
                     这一步在当前版本里还没打通（安卓不允许直接执行 java）。\
                     现在启动会闪退，所以先不启动。\
                     临时方案：改用 Fabric 版整合包，或在电脑上用官方启动器玩这个包。"
                );
                crate::util::log_line(&format!(
                    "[launcher] {msg}（缺少标记 {}）",
                    patch_marker.display()
                ));
                return Err(anyhow::anyhow!(msg));
            }
        }

        let main_class = if loader_str2 != "vanilla" {
            let versions_dir = Path::new(&data_dir).join("versions");
            match crate::loader_install::find_loader_profile(
                &versions_dir,
                &loader_str2,
                &instance.mc_version,
            )
            .and_then(|p| crate::loader_install::profile_main_class(&p))
            {
                Some(m) => m,
                None => {
                    // ⚠️ 真机踩过（1.21.1 NeoForge 实例，日志里只有 `profile=<未找到>`）：
                    // 这里原来**静默退回原版主类** → 游戏照常进得去、但一个模组都不加载，
                    // 用户完全看不出原因（还以为是模组装错了）。
                    // 宁可当场拒绝：用原版跑一个"NeoForge 实例"不但模组全失效，
                    // 还可能让原版去动 NeoForge 的存档。
                    let msg = format!(
                        "{loader_str2} 加载器没装成功：versions/ 下找不到它的 profile。\
                         直接启动会变成原版（所有模组失效），所以先不启动。\
                         请到实例设置里重新安装一次 {loader_str2} 加载器。"
                    );
                    crate::util::log_line(&format!(
                        "[launcher] {msg}（versions_dir={}）",
                        versions_dir.display()
                    ));
                    return Err(anyhow::anyhow!(msg));
                }
            }
        } else {
            version_info
                .main_class
                .clone()
                .unwrap_or_else(|| "net.minecraft.client.main.Main".to_string())
        };

        let jre_home = Path::new(&runtime.path);

        // 必须先预加载 JRE 内部库，否则 JVM 走到 libnio.so 时必然
        // `library "libnet.so" not found`（安卓 linker 不查 JRE 的 lib 目录）
        #[cfg(target_os = "android")]
        crate::android_env::preload_jre_libraries(jre_home);

        // 26.3 备注：试过把 libpojavexec 用 RTLD_GLOBAL 提前挂进全局符号表（想兜住
        // `GLFW.<clinit>` 里那两次 native 解析），**实测无效** —— 加载器不接受全局符号，
        // 桥函数与 LibFFI 依旧 UnsatisfiedLinkError，所以这里没有保留那段代码。

        // 悬浮启动卡片靠 launch://progress 显示步骤；这里不要用 launch://log，
        // 前端把任何 launch://log 都当作「启动成功」。
        crate::progress::emit_launch_progress(
            &format!("正在启动 JVM（Java {}）…", runtime.java_version),
            85.0,
        );

        // 安卓进程的 stdout 默认丢弃：把 fd 1/2 转到日志文件，并把新增行推给前端日志面板
        #[cfg(target_os = "android")]
        {
            let log_path = Path::new(&data_dir)
                .join("logs")
                .join(format!("launch-{instance_id}.log"));
            crate::android_env::redirect_output(&log_path)
                .with_context(|| format!("无法创建启动日志：{}", log_path.display()))?;
            crate::android_env::spawn_log_tail(log_path, instance_id.to_string());

            // 渲染器选择写进启动日志（必须在 redirect_output **之后**，否则进不了文件）：
            // 启动浮层与日志页能直接看到「这次用的是哪个渲染器、为什么是它」，
            // 渲染器健康检查也按这行判断本次实际用的渲染器。
            println!(
                "渲染器：{}（{}｜实例设置={}）",
                renderer_display_name(&renderer),
                renderer_source,
                instance.renderer.as_deref().unwrap_or("<空>")
            );

            // 窗口尺寸决定触摸坐标换算是否准确，出问题时最需要看到它
            for arg in jvm_args.iter().filter(|a| a.starts_with("-Dglfwstub")) {
                println!("[launcher] {arg}");
            }
            // ⚠️ 用 println! 而不是 tracing::info!：tracing 在本项目**没有接到
            // logcat 或启动日志文件**上，写了等于没写（真机排查时踩到）。
            // 这行是排查「classpath 只有 1 条 → MC 主类 LinkageError」的关键证据，
            // 必须落到 launch-<id>.log 里。
            println!(
                "[launcher] classpath 条目 {}，java {} @ {}",
            // （见下方 2026-10-23 的明细日志）
                classpath.matches(':').count() + 1,
                runtime.java_version,
                runtime.path
            );

            // ⚠️ 2026-10-23：把 classpath 明细 + 两个「本体」jar 的存在性落盘。
            //
            // 真机现象：FML 只从 `mods/` 扫到模组，`minecraft` / `neoforge` 全部报
            // `[MISSING]`（→ ModSorter 判依赖失败 → NoSuchElementException → 闪退）。
            // 唯一能定性的是：`1.21.1.jar` 与 `neoforge-…-universal.jar` 到底有没有
            // 进到 classpath。这两行就是为此加的，别删。
            crate::util::log_line(&format!(
                "[launcher] classpath 明细：{}",
                classpath.split(':').collect::<Vec<_>>().join(" | ")
            ));
            for p in classpath.split(':') {
                let name = p.rsplit('/').next().unwrap_or(p);
                if name.ends_with(".jar")
                    && (name.contains("1.21.1")
                        || name.contains("neoforge")
                        || name.contains("client-extra"))
                {
                    crate::util::log_line(&format!(
                        "[launcher] 关键 jar name={name} exists={} path={p}",
                        std::path::Path::new(p).is_file()
                    ));
                }
            }

            // ── 加载器摘要（写进启动日志，2026-10-19 加）──────────────────
            //
            // 排查「加载器装了还是跑原版」时，这三行就够了：
            //   · 用的是哪个加载器、有没有找到 profile
            //   · 最终 mainClass 是不是加载器的（原版是 net.minecraft.client.main.Main）
            //   · 加载器参数有没有真的并进 JVM 参数（Forge ≥1.17 靠 -p 才能起）
            println!(
                "[launcher] 加载器={} profile={} mainClass={}",
                loader_name(&instance.loader),
                crate::loader_install::find_loader_profile(
                    &std::path::Path::new(&data_dir).join("versions"),
                    &loader_name(&instance.loader),
                    &instance.mc_version,
                )
                .map(|p| p.file_name().unwrap_or_default().to_string_lossy().to_string())
                .unwrap_or_else(|| "<未找到>".to_string()),
                main_class
            );
            println!(
                "[launcher] JVM 参数 {} 条（含 -p {} 条 / --add-opens {} 条）",
                jvm_args.len(),
                jvm_args.iter().filter(|a| *a == "-p" || *a == "--module-path").count(),
                jvm_args.iter().filter(|a| a.contains("--add-opens")).count()
            );
            // 把 classpath 里的 LWJGL 条目单独打出来：官方 3.3.3 jar 必须**不在**这里，
            // 否则会和 natives 里的 Pojav 原生库版本冲突（LWJGL 那句
            // "Incompatible Java and native library versions detected"）。
            for entry in classpath.split(':') {
                if entry.to_lowercase().contains("lwjgl") {
                    println!("[launcher] classpath 含 LWJGL: {entry}");
                }
            }
            println!(
                "[launcher] version_info.libraries = {}",
                version_info
                    .libraries
                    .as_ref()
                    .map(|l| l.len().to_string())
                    .unwrap_or_else(|| "None".into())
            );
            // 把**完整** JVM 参数列表打到启动日志：排查启动崩溃时这是第一手资料，
            // 比逐条猜测哪个参数有问题快得多。
            for (i, a) in jvm_args.iter().enumerate() {
                if a.len() < 300 {
                    println!("[launcher] jvm_arg[{i}] = {a}");
                }
            }
        }

        // 把**进程的工作目录**切到实例目录。
        //
        // 为什么必须做：游戏里大量相对路径是按「当前工作目录」解析的 ——
        // Minecraft 的 log4j 配置写的是 `logs/latest.log`，安卓应用的默认工作目录是 `/`，
        // 于是日志直接报 `FileNotFoundException: logs/latest.log (No such file or directory)`，
        // 游戏自己的日志/部分资源从此写不出来。
        // 只设 `-Duser.dir` 是不够的：那只是 Java 的一个属性，不改变 OS 层的 CWD。
        // JVM 与游戏都在本进程里，所以这里 chdir 一次就够；游戏退出后再切回去。
        // JDK 8 的原生库必须提前用绝对路径加载（见 android_bridge::preload_jre_libs）。
        // 只对 Java 8 及更早版本做 —— JDK 9+ 的库是扁平的，本来就能找到，不做无谓的事。
        if runtime.java_version <= 8 {
            match crate::android_bridge::preload_jre_libs(&runtime.path) {
                Ok(n) => tracing::info!("[launcher] 预加载 JRE 原生库 {n} 个"),
                Err(e) => tracing::warn!("[launcher] 预加载 JRE 原生库失败：{e}"),
            }
        }
        previous_dir = std::env::current_dir().ok();
        if let Err(e) = std::env::set_current_dir(&game_dir) {
            tracing::warn!("切换工作目录到实例目录失败（游戏内相对路径可能异常）：{e}");
        }

        // 26.3：让启动器侧把 SDL 整合准备好（`org.libsdl.app.SDL` 胶水 + 已有 Surface）。
        // 游戏侧 SDL 建窗口时要用它；不做就是 libSDL3 里空指针崩（实测）。
        #[cfg(target_os = "android")]
        if use_lwjgl341 {
            match crate::android_bridge::enable_sdl_integration() {
                Ok(()) => tracing::info!("[launcher] 已请求启用启动器侧 SDL 整合"),
                Err(e) => tracing::warn!("[launcher] 启用启动器侧 SDL 整合失败：{e}"),
            }
        }

        // 在启动 JVM 之前在后台守候：等 JVM 起来后接管 `GL11C.nglGetString`，
        // 把 F3 里那行 `Display: … (厂商串)` 换成我们自己的（详见 gl_brand.rs）。
        // 传 game_dir 是为了让它等 MC 日志里的「原生库已装好」标记再动手 ——
        // 抢在库加载窗口里注册 native 会把 GLFW / MemoryUtil 的初始化撞坏（26.3 实测崩）。
        #[cfg(target_os = "android")]
        crate::gl_brand::spawn_installer(&game_dir);

        // ⚠️ 2026-10-19 真机诊断：这两行是判断「JLI_Launch 到底有没有跑起来」
        // 的唯一可靠依据。之前 Fabric 实例的日志里既没有 JVM 自己的输出、
        // 也没有「JVM 已退出」那一行 —— 说明进程是在 JLI_Launch 内部被直接
        // 终结的（而不是它返回了错误码）。有这两行就能区分三种情况：
        //   · 只出现「准备调用」→ 卡在 JLI_Launch 里（原生层崩溃/被 kill）
        //   · 两行都出现 + 退出码 → JVM 正常启停
        //   · 都不出现 → 根本没走到这里
        println!(
            "[launcher] 准备调用 JLI_Launch：mainClass={main_class} argc={}",
            jvm_args.len() + game_args.len() + 3
        );

        // ⚠️ 2026-10-23：把 JVM 的 stdout/stderr 落一份到 `<data>/logs/game-stderr.log`。
        //
        // 为什么必须这么做：启动是**同进程 JNI**（`JLI_Launch`），JVM 的 stderr
        // **不进** logcat 的 RustStdoutStderr。真机现象是
        //   「Launching target 'forgeclient'」之后进程直接没了 ——
        //   无 crash-report、无 Java 异常、连退出码都没有 ✗，完全查不到死因。
        // dup2 到文件后，至少能拿到 JVM 自己写的死因（hs_err / 信号 / 异常栈）。
        // ⚠️ 2026-10-23 真机修（启动遮罩停在「正在初始化渲染器…126 秒」不收起）：
        //
        // 上面 `spawn_log_tail` 盯的是 `logs/launch-<实例>.log`，前端收到任何
        // `launch://log` 就认为「启动成功」并收起遮罩。
        // 我先前把 JVM 输出重定向到了 `logs/game-stderr.log` ✗ → tail 盯的文件永远没内容
        // → 前端收不到任何 log → 遮罩不收起（游戏其实已经在主菜单了）。
        // 所以这里必须写回 **tail 正在盯的那个文件**（内容一样，能拿到 stderr）。
        let stderr_guard = redirect_output_to_file(
            &std::path::Path::new(&data_dir)
                .join("logs")
                .join(format!("launch-{}.log", instance.id)),
            &instance.id,
        );

        // 游玩时长：落**会话标记**而不是挂在 JLI_Launch 返回之后 —— 游戏
        // System.exit 会带走整个启动器进程（同进程 JNI），正常退出路径上
        // JLI_Launch 根本不返回。对账在启动器下次拉起时做
        // （playtime::reconcile_pending_session，见 jni_bridge::nativeAttach）。
        crate::playtime::mark_session_start(&instance.id).await;

        let launch_outcome = JvmLauncher::launch(
            jre_home,
            &jvm_args,
            &classpath,
            &main_class,
            &game_args,
            &game_dir,
        );

        if let Some(restore) = stderr_guard {
            restore();
        }

        match &launch_outcome {
            Ok(code) => println!("[launcher] JLI_Launch 返回，退出码 {code}"),
            Err(e) => println!("[launcher] JLI_Launch 失败：{e}"),
        }

        let exit_code = launch_outcome?;

        Ok::<i32, anyhow::Error>(exit_code)
    }.await;

    // 恢复原来的工作目录：启动器和游戏同进程，别把 CWD 留在实例目录里
    if let Some(dir) = previous_dir {
        let _ = std::env::set_current_dir(dir);
    }

    let exit_code = match launch_result {
        Ok(code) => code,
        Err(e) => {
            let mut status = GAME_STATUS.get().unwrap().lock().unwrap();
            status.is_running = false;
            status.error = Some(e.to_string());
            drop(status);
            crate::progress::emit_launch_progress(&format!("启动失败：{e}"), 100.0);
            crate::progress::emit_launch_exit();
            crate::progress::emit_launch_state(instance_id, "exited", 0, None);
            #[cfg(target_os = "android")]
            crate::android_env::stop_log_tail();
            return Err(e);
        }
    };

    {
        let mut status = GAME_STATUS.get().unwrap().lock().unwrap();
        status.is_running = false;
        status.exit_code = Some(exit_code);
    }
    #[cfg(target_os = "android")]
    crate::android_env::stop_log_tail();
    crate::progress::emit_launch_log(instance_id, "out", &format!("JVM 已退出，退出码 {exit_code}"));
    crate::progress::emit_launch_exit();
    crate::progress::emit_launch_state(instance_id, "exited", 0, Some(exit_code));

    if exit_code != 0 {
        let _ = crate::crash::report_crash(instance_id, exit_code).await;
    }

    Ok(get_game_status())
}

pub async fn kill_game() -> Result<()> {
    init_statics();

    crate::input_bridge::set_ready(false);
    crate::render_bridge::release();
    JvmLauncher::shutdown()?;

    let instance_id = {
        let mut status = GAME_STATUS.get().unwrap().lock().unwrap();
        status.is_running = false;
        status.pid = None;
        let id = status.instance_id.clone();
        drop(status);
        id
    };
    if let Some(id) = instance_id {
        crate::progress::emit_launch_state(&id, "exited", 0, None);
    }

    Ok(())
}

pub fn get_game_status() -> GameStatus {
    init_statics();
    GAME_STATUS.get().unwrap().lock().unwrap().clone()
}

/// 把本进程的 stdout/stderr 重定向到文件（追加），返回「恢复」闭包。
///
/// 游戏是**同进程 JNI** 启动的，JVM 的输出不走 logcat；真机上「启动后立刻退出」
/// 因此毫无线索（无 crash-report / 无异常 / 无退出码）。用 `dup2` 把 fd 1/2 接到
/// 文件上，跑完再恢复 —— 这是唯一能在不改启动方式的前提下拿到 JVM 死因的办法。
///
/// 只在 unix 下有效；其它平台返回 `None`（不影响启动）。
/// 修正 `options.txt` 里**非法的布尔值**（`on` / `off` → `true` / `false`）。
///
/// ## 为什么需要（真机日志）
///
/// 整合包自带的 `options.txt` 里写着 `fullscreen:off`，游戏启动时报：
///
/// ```text
/// Error parsing option value off for option Fullscreen: Not a boolean: "off"
/// ```
///
/// 这不是致命错误，但会让该选项回落到默认值（用户改过的设置被悄悄丢掉 ✓），
/// 而且日志里很难看出是"包里的文件不合法"✓。启动前顺手改掉即可。
///
/// 只动**已知的布尔键**，其它行原样保留（避免把 `key:on` 这种合法枚举误改 ✓）。
fn normalize_options_txt(game_dir: &Path) {
    const BOOL_KEYS: [&str; 8] = [
        "fullscreen",
        "invertYMouse",
        "pauseOnLostFocus",
        "showSubtitles",
        "realmsNotifications",
        "autoJump",
        "enableVsync",
        "fullscreenMode",
    ];
    let path = game_dir.join("options.txt");
    let Ok(text) = std::fs::read_to_string(&path) else {
        return; // 还没有这个文件，正常
    };
    let mut fixed = 0usize;
    let mut out = String::with_capacity(text.len());
    for line in text.lines() {
        let mut handled = false;
        for key in BOOL_KEYS {
            if let Some(rest) = line.strip_prefix(key).and_then(|r| r.strip_prefix(':')) {
                let v = rest.trim();
                let norm = match v.to_ascii_lowercase().as_str() {
                    "on" | "yes" => Some("true"),
                    "off" | "no" => Some("false"),
                    _ => None,
                };
                if let Some(n) = norm {
                    out.push_str(&format!("{key}:{n}\n"));
                } else {
                    out.push_str(line);
                    out.push('\n');
                }
                fixed += 1;
                handled = true;
                break;
            }
        }
        if !handled {
            out.push_str(line);
            out.push('\n');
        }
    }
    if fixed > 0 {
        match std::fs::write(&path, out) {
            Ok(()) => crate::util::log_line(&format!(
                "[launcher] options.txt 已规整（on/off → true/false，共 {fixed} 行）"
            )),
            Err(e) => crate::util::log_line(&format!("[launcher] options.txt 写回失败：{e}")),
        }
    }
}

pub(crate) fn redirect_output_to_file(
    path: &std::path::Path,
    instance_id: &str,
) -> Option<Box<dyn FnOnce()>> {
    #[cfg(unix)]
    {
        use std::os::unix::io::AsRawFd;

        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        // 每次启动写一个分隔头，方便区分多次运行
        let mut file = match std::fs::OpenOptions::new().create(true).append(true).open(path) {
            Ok(f) => f,
            Err(e) => {
                crate::util::log_line(&format!("[launcher] 无法写 game-stderr.log：{e}"));
                return None;
            }
        };
        use std::io::Write;
        let _ = writeln!(
            file,
            "\n===== {} 启动实例 {} =====",
            chrono::Local::now().format("%Y-%m-%d %H:%M:%S"),
            instance_id
        );
        let fd = file.as_raw_fd();
        let saved_out = unsafe { libc::dup(1) };
        let saved_err = unsafe { libc::dup(2) };
        if saved_out < 0 || saved_err < 0 {
            crate::util::log_line("[launcher] dup 失败，跳过输出重定向");
            return None;
        }
        unsafe {
            libc::dup2(fd, 1);
            libc::dup2(fd, 2);
        }
        // fd 要一直有效到恢复为止，所以不 drop（文件句柄交给闭包之外的生命周期）
        std::mem::forget(file);
        crate::util::log_line(&format!(
            "[launcher] JVM 输出已重定向到 {}",
            path.display()
        ));
        Some(Box::new(move || unsafe {
            libc::dup2(saved_out, 1);
            libc::dup2(saved_err, 2);
            libc::close(saved_out);
            libc::close(saved_err);
        }))
    }
    #[cfg(not(unix))]
    {
        let _ = (path, instance_id);
        None
    }
}

async fn build_classpath(
    version_info: &MinecraftVersion,
    data_dir: &str,
    vendor: &VendorJars,
) -> Result<String> {
    let mut classpath = Vec::new();

    let version_jar = Path::new(data_dir)
        .join("versions")
        .join(&version_info.id)
        .join(format!("{}.jar", version_info.id));
    classpath.push(version_jar.to_string_lossy().to_string());

    if let Some(libraries) = &version_info.libraries {
        for lib in libraries {
            // 桌面版 LWJGL 在安卓上没得用（不带 arm64 natives），全部剔除，
            // 由下面的 Pojav 版顶替。
            // groupId 有**两种历史命名**：老版本（≤1.12，1.8.9）是 `org.lwjgl.lwjgl`，
            // 新版本是 `org.lwjgl` —— 只写后者时，1.8.9 的 Mojang LWJGL2 类会抢在
            // 补丁版前面进 classpath，native 符号对不上直接
            // `UnsatisfiedLinkError: DefaultSysImplementation.getPointerSize`（实测）。
            if lib.name.starts_with("org.lwjgl:") || lib.name.starts_with("org.lwjgl.lwjgl:") {
                continue;
            }
            // ⚠️ 2026-10-23 更正：earlydisplay 真正的位置是 **boot 层模块路径 `-p`**，
            // 不是 classpath。真机崩溃栈里是
            //   `MC-BOOTSTRAP/fml_earlydisplay@4.0.44/…DisplayWindow.updateModuleReads`
            // 配合启动日志 `JVM 参数 38 条（含 -p 1 条）` 就能看出来。
            // 所以**真正的过滤在 `loader_install::profile_arguments`**（它处理 `-p`）。
            // 这里保留一份过滤只是防止同一个 jar 既上模块路径又上 classpath（多一份冗余），
            // 不要指望它解决问题 —— 之前就是在这里空转，浪费了一轮排查。
            if lib.name.contains("fancymodloader:earlydisplay") {
                crate::util::log_line("[launcher] classpath 里跳过 earlydisplay（真正的过滤在 -p 上）");
                continue;
            }
            if let Some(downloads) = &lib.downloads {
                if let Some(artifact) = &downloads.artifact {
                    let lib_path = Path::new(data_dir)
                        .join("libraries")
                        .join(&artifact.path);
                    classpath.push(lib_path.to_string_lossy().to_string());
                }
            }
        }
    }

    // 安卓版 LWJGL（官方 LWJGL 全部类 + 安卓改写 glfw stub / 渲染桥）：
    // 放在最后也不会被顶掉，因为 org.lwjgl:* 已经在上面的循环里被剔除。
    // Caciocavallo 不放这里，它必须走 bootclasspath（见 build_jvm_args）。
    //
    // 两套二选一（见 load_vendor_jars）：老版本用随包的单体 GLFW jar；
    // MC 26.3+ 用 3.4.1 组件集（那把 SDL3 窗口层补上）。
    for jar in &vendor.lwjgl_jars {
        classpath.push(jar.to_string_lossy().to_string());
    }

    Ok(classpath.join(":"))
}

/// 安卓适配 jar 的落盘路径（非安卓平台为空结构，逻辑只有一处 cfg）。
struct VendorJars {
    /// classpath 上的 LWJGL 条目。
    lwjgl_jars: Vec<std::path::PathBuf>,
    /// LWJGL 3.4.1 的 natives 目录（`-Dorg.lwjgl.librarypath`）；
    /// 走老 GLFW 单体 jar 时为 None。
    lwjgl_natives: Option<std::path::PathBuf>,
    /// 还要额外加进 `java.library.path` 的目录（插件里的 SDL3 / spirv-cross 等）。
    /// 放在**最前面**：同名库要让插件的版本先被找到（否则又会退回 APK 里那份，
    /// 出现两个 SDL3 副本那个经典坑）。
    extra_library_dirs: Vec<std::path::PathBuf>,
    cacio: Vec<std::path::PathBuf>,
}

/// 版本 JSON 要求的 LWJGL 版本是否 ≥ 3.4.1。
///
/// 判定依据是版本 JSON 自己的 `org.lwjgl:lwjgl:<ver>` 依赖（**不硬编码 MC 版本号**），
/// 与 Amethyst/Pojav 的做法一致：把版本号压成整数比较（3.4.1 → 341）。
/// 26.3 的清单里写的是 `org.lwjgl:lwjgl:3.4.3` → 343 ≥ 341 ✓。
fn needs_lwjgl341(version_info: &MinecraftVersion) -> bool {
    let Some(libs) = &version_info.libraries else {
        return false;
    };
    libs.iter()
        .find_map(|lib| {
            let rest = lib.name.strip_prefix("org.lwjgl:lwjgl:")?;
            // 只要没有 classifier 的主条目（"3.4.3"，而不是 "3.4.3:natives-linux"）
            if rest.contains(':') {
                return None;
            }
            let digits: String = rest
                .trim_start_matches(|c: char| !c.is_ascii_digit())
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .filter(|c| *c != '.')
                .collect();
            digits.parse::<u32>().ok()
        })
        .is_some_and(|v| v >= 341)
}

async fn load_vendor_jars(
    data_dir: &str,
    java_version: i32,
    use_lwjgl341: bool,
) -> Result<VendorJars> {
    #[cfg(target_os = "android")]
    {
        let vendor = crate::android_env::ensure_vendor_jars(data_dir, java_version)
            .await
            .map_err(|e| anyhow::anyhow!("释放安卓适配 jar 失败：{e}"))?;

        // 两套 LWJGL 不能同时上 classpath：混版本会出现
        // `[LWJGL] Incompatible Java and native library versions detected`，
        // 而且到底哪个类被加载取决于顺序，极难排查。所以按版本二选一。
        //
        // 注意：**渲染器 / 驱动插件的库路径与 LWJGL 版本无关** —— 老版本（1.18/1.21）
        // 一样能装 MobileGlues 渲染器插件。所以 extra_library_dirs 在两条分支里都要带上，
        // 只有 classpath 是二选一的。
        let plugin = crate::plugin::resolved_component_paths(data_dir);
        let plugin_has_classpath = !plugin.classpath.is_empty();
        let (lwjgl_jars, lwjgl_natives, extra_library_dirs) = if use_lwjgl341 {
            // 插件优先：用户可以单独更新组件（比如换一份适配新 MC 的 SDL3），
            // 不必等启动器重新发版。没装插件时回退到随包内置的那套。
            if plugin_has_classpath {
                tracing::info!(
                    "[launcher] LWJGL 3.4.1 组件来自插件：{} 个 jar",
                    plugin.classpath.len()
                );
                (plugin.classpath, plugin.lightgl_dir, plugin.library_dirs)
            } else {
                let comp = crate::android_env::ensure_lwjgl341(data_dir)
                    .await
                    .map_err(|e| anyhow::anyhow!("释放 LWJGL 3.4.1 组件失败：{e}"))?;
                (comp.jars, Some(comp.natives_dir), plugin.library_dirs)
            }
        } else {
            (vec![vendor.lwjgl], None, plugin.library_dirs)
        };

        Ok(VendorJars {
            lwjgl_jars,
            lwjgl_natives,
            extra_library_dirs,
            cacio: vendor.cacio,
        })
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (data_dir, java_version, use_lwjgl341);
        Ok(VendorJars {
            lwjgl_jars: Vec::new(),
            lwjgl_natives: None,
            extra_library_dirs: Vec::new(),
            cacio: Vec::new(),
        })
    }
}

/// 读取某个实例的启动日志（**磁盘上的那份**）。
///
/// 前端日志面板平时显示的是内存里收到的 `launch://log` 事件流；可游戏一旦崩溃，
/// 整个进程会被带走，重启后内存里的日志就没了 —— 于是「日志」页永远显示
/// 「暂无日志」，偏偏在最需要它的时候。<br>
/// 这里直接读磁盘：优先启动器那份（包含启动步骤与早期报错，例如
/// `JRE 校验失败`、`找不到 libjli.so` 这类在游戏启动前就失败的情况），
/// 如果游戏真的跑起来过，再附上 Minecraft 自己的 `logs/latest.log`。
/// 日志最多读多少字节（从**尾部**读）。
///
/// 以前是 `read_to_string` 整个读进来 —— `latest.log` 上到几百 MB 在手机上很常见，
/// 一次就把内存打满。崩溃现场都在最后，前面几百 MB 的早期输出没有读的必要。
const LOG_TAIL_MAX: u64 = 2 * 1024 * 1024;

/// 只读文件**尾部**最多 `max_bytes`，截断时在开头标注。
fn read_tail(path: &Path, max_bytes: u64) -> std::io::Result<String> {
    use std::io::{Read, Seek, SeekFrom};
    let mut f = std::fs::File::open(path)?;
    let len = f.metadata()?.len();
    let truncated = len > max_bytes;
    if truncated {
        f.seek(SeekFrom::Start(len - max_bytes))?;
    }
    let mut buf = Vec::new();
    f.read_to_end(&mut buf)?;
    let mut text = String::from_utf8_lossy(&buf).into_owned();
    if truncated {
        // 截断点多半落在某一行中间，把那半行丢掉
        if let Some(idx) = text.find('\n') {
            text.drain(..=idx);
        }
        text = format!("…（文件较大，仅显示最后 {} KB）\n{text}", max_bytes / 1024);
    }
    Ok(text)
}

pub async fn read_instance_log(instance_id: &str) -> anyhow::Result<String> {
    let data_dir = crate::settings::get_data_dir().await?;
    let id = instance_id.to_string();
    // 纯同步文件 IO、而且可能读几百 MB —— 必须挪出 tokio worker
    tokio::task::spawn_blocking(move || read_instance_log_sync(&id, &data_dir))
        .await
        .context("日志读取任务失败")?
}

/// **导出（分享）实例日志**。
///
/// 为什么需要：日志目录在游戏目录里，默认是 `/storage/emulated/0/Android/data/<包名>/…`，
/// 而 **Android 11+ 单独屏蔽了 `Android/data`** —— 文件管理器（哪怕有「所有文件访问」）进不去、
/// 电脑 MTP 也看不到。实测表现就是 MT 报「该文件被设置了私有权限」，玩家根本没法把日志发出来。
/// 打包 zip 后走系统分享（FileProvider 的 `cache-path`）是唯一稳定的出路。
pub async fn export_instance_logs(instance_id: &str) -> anyhow::Result<()> {
    let root = crate::settings::instances_root_sync().ok_or_else(|| {
        anyhow::anyhow!("实例目录还没准备好，先启动一次游戏")
    })?;
    let dir = std::path::Path::new(&root).join(instance_id).join("logs");
    if !dir.is_dir() {
        anyhow::bail!("这个实例还没有日志");
    }
    // 归档名带版本名，方便对方一眼看出是哪台设备的日志
    let tag = crate::settings::instances_root_sync()
        .and_then(|r| {
            std::fs::read_to_string(
                std::path::Path::new(&r).join(instance_id).join("instance.json"),
            )
            .ok()
        })
        .and_then(|t| serde_json::from_str::<serde_json::Value>(&t).ok())
        .and_then(|v| v.get("version_name").and_then(|x| x.as_str()).map(String::from))
        .unwrap_or_else(|| "minecraft".to_string());
    let archive = format!("qookix-logs-{}-{}.zip", sanitize(&tag), instance_id);
    // 系统分享面板只有安卓有；桌面端（以及跑单测的宿主目标）没有这一步
    #[cfg(target_os = "android")]
    crate::android_bridge::share_logs_zip(&archive, &dir.to_string_lossy());
    #[cfg(not(target_os = "android"))]
    let _ = &archive;
    Ok(())
}

/// 文件名里不能有路径分隔符等
fn sanitize(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '.' || c == '-' { c } else { '_' })
        .collect()
}

fn read_instance_log_sync(instance_id: &str, data_dir: &str) -> anyhow::Result<String> {
    let launcher_log = Path::new(data_dir)
        .join("logs")
        .join(format!("launch-{instance_id}.log"));
    // 实例根可能被用户改到外部（见 settings::game_root），这里走同步版。
    let game_log = match crate::settings::instances_root_sync() {
        Some(root) => Path::new(&root)
            .join(instance_id)
            .join("logs")
            .join("latest.log"),
        None => std::path::PathBuf::new(),
    };

    let mut out = String::new();
    let mut found = false;

    if let Ok(text) = read_tail(&launcher_log, LOG_TAIL_MAX) {
        out.push_str(&format!("===== 启动器日志（{}）=====\n", launcher_log.display()));
        out.push_str(&text);
        found = true;
    }
    if let Ok(text) = read_tail(&game_log, LOG_TAIL_MAX) {
        if found {
            out.push_str("\n\n");
        }
        out.push_str(&format!("===== 游戏日志（{}）=====\n", game_log.display()));
        out.push_str(&text);
        found = true;
    }

    if !found {
        out.push_str("暂无日志：这个实例还没有启动过，或日志文件已被清理。");
    }
    Ok(out)
}
/// 全局渲染后端：`opengles2`（GL4ES，默认）或 `vulkan_zink`（Zink + Turnip）。
///
/// 这个设置和「按钮大小 / 鼠标速度」那些放一起，存在安卓侧 SharedPreferences，
/// 由「设置 → 游戏内 → 渲染器」写入，这里经原生桥读出来。
///
/// 任何一步失败都回退到 GL4ES —— 那条路径在所有设备上都能跑，
/// 而 Zink 需要 Adreno + Vulkan，选错就是黑屏，所以宁可保守。
fn renderer_setting() -> String {
    let Ok(json) = crate::android_bridge::read_pojav_prefs() else {
        return "opengles2".to_string();
    };
    let Ok(v) = serde_json::from_str::<serde_json::Value>(&json) else {
        return "opengles2".to_string();
    };
    normalize_renderer(
        v.get("renderer").and_then(|r| r.as_str()).unwrap_or("opengles2"),
    )
}

/// 把渲染器「键」规范化成内部用的后端名。
///
/// MobileGlues 返回的是**库文件名**：`libpojavexec` 对未知渲染器名会按 `.so` dlopen，
/// 这也是 FCL/Zalith 接入 MG 的同一机制（渲染器列表直接给 .so 文件名）。
pub(crate) fn normalize_renderer(key: &str) -> String {
    match key {
        "vulkan_zink" => "vulkan_zink".to_string(),
        "mobileglues" | "libmobileglues.so" => "libmobileglues.so".to_string(),
        _ => "opengles2".to_string(),
    }
}

/// 按 MC 版本挑默认渲染器（版本解析与规则见 `renderer_pick`）。
/// 这里再导出，是为了让 `renderer_health` 等调用方路径不变。
pub(crate) use crate::renderer_pick::auto_renderer_for;

/// 本次启动实际使用的渲染器（内部后端名）。
///
/// 优先级：**实例设置 → 全局设置**。
/// - 实例是具体键（`opengles2` / `mobileglues` / `vulkan_zink`）→ 用它；
/// - 实例是 `auto`（或缺省）→ 按 MC 版本推断，见 [`auto_renderer_for`]；
/// - 实例是 `global` → 读「设置 → 游戏内 → 渲染器」。
///
/// `source` 回填成一句人话，用于写进启动日志（用户能在启动浮层里看到为什么是它）。
pub(crate) fn resolve_renderer(
    instance: Option<&crate::models::MinecraftProfile>,
    source: &mut String,
) -> String {
    let key = instance
        .and_then(|i| i.renderer.as_deref())
        .unwrap_or("auto");
    let mc_version = instance.map(|i| i.mc_version.clone()).unwrap_or_default();

    match key {
        // 跟随全局
        "global" => {
            *source = "本实例设置为「跟随全局」".to_string();
            renderer_setting()
        }
        // 按版本自动
        "auto" | "" => {
            let picked = auto_renderer_for(&mc_version);
            *source = format!(
                "按版本自动（MC {} → {}）",
                if mc_version.is_empty() { "未知" } else { mc_version.as_str() },
                renderer_display_name(picked)
            );
            normalize_renderer(picked)
        }
        // 显式指定
        other => {
            *source = "本实例指定".to_string();
            normalize_renderer(other)
        }
    }
}

/// 给日志用的渲染器显示名。
pub(crate) fn renderer_display_name(internal: &str) -> &'static str {
    match internal {
        "libmobileglues.so" | "mobileglues" => "MobileGlues",
        "vulkan_zink" => "Zink + Turnip",
        _ => "GL4ES",
    }
}
/// 渲染后端 → 它对应的 OpenGL 实现库文件名。
///
/// 只有一处真相：`-Dorg.lwjgl.opengl.libname`（LWJGL 侧）与
/// `SDL_OPENGL_LIBRARY`（SDL 窗口层侧）**必须指向同一个库**，
/// 否则 MC 26.3 会以 `BackendCreationException: glGetError mismatch` 拒绝 OpenGL 后端。
fn renderer_gl_lib(renderer: &str) -> &'static str {
    match renderer {
        // Zink：Mesa 的「OpenGL on Vulkan」
        "vulkan_zink" => "libOSMesa.so",
        // MobileGlues：闭源第三方装载器给的库名直接就是文件名
        "libmobileglues.so" => "libmobileglues.so",
        // GL4ES（默认）：OpenGL → GLES 翻译层，兼容性最好
        _ => "libgl4es_114.so",
    }
}

/// 渲染器库是否可得：随包目录（APK 解出的 natives）或渲染器插件目录任一存在即可。
#[cfg(target_os = "android")]
fn renderer_available(data_dir: &str, bundled_dir: &str, renderer: &str) -> bool {
    let gl_file = renderer_gl_lib(renderer);
    if Path::new(bundled_dir).join(gl_file).exists() {
        return true;
    }
    crate::plugin::renderer_libs_dir(data_dir, renderer_plugin_key(renderer))
        .map(|d| d.join(gl_file).exists())
        .unwrap_or(false)
}

/// 给 MobileGlues 准备可写的配置目录，返回给 `MG_DIR_PATH` 用的路径。
///
/// 目录里那份 `config.json` 只在**不存在**时写：以后用户自己调过参数不会被覆盖。
fn ensure_mobileglues_dir(data_dir: &str) -> Option<String> {
    let dir = std::path::Path::new(data_dir).join("mobileglues");
    if let Err(e) = std::fs::create_dir_all(&dir) {
        tracing::warn!("[launcher] 创建 MobileGlues 目录失败：{e}");
        return None;
    }

    let config = dir.join("config.json");
    if !config.exists() {
        // 关键项：
        //  - maxGlslCacheSize（0 = 不缓存，世界里边走边翻译着色器，帧率会崩）
        //  - **enableNoError = 1**：这才是 MG 的「忽略 shader/program 错误」开关
        //    （源码里是 `config_get_int("enableNoError")` → `NoErrorConfig` 0..3，
        //      1 = Level1 → `IgnoreErrorLevel::Partial`、2 = Level2 → Full；
        //      日志里打印的 `ignoreError` 只是**最终值**，不是配置键）。
        //    MG 官方 V1.3.5 的 release note 写明「运行 MC 26.3-snapshot-3 及以后必须启用它」：
        //    不开的话 MC 26.3 的地形着色器会因 MG 的 GLSL 翻译问题
        //    （实测 `'_uniform' : undeclared identifier`）编译失败，MC 直接判定
        //    「Failed to load required shader programs」崩掉。
        const CONFIG: &str = r#"{
  "maxGlslCacheSize": 32,
  "enableExtTimerQuery": true,
  "enableExtDirectStateAccess": true,
  "enableExtComputeShader": false,
  "enableAngle": 0,
  "enableNoError": 2,
  "angleDepthClearFixMode": 0,
  "customGLVersion": "4.0.0",
  "fsr1Setting": 0,
  "hideMGEnvLevel": 0
}
"#;
        if let Err(e) = std::fs::write(&config, CONFIG) {
            tracing::warn!("[launcher] 写入 MobileGlues 配置失败：{e}");
        }
    } else if let Err(e) = upgrade_mobileglues_ignore_error(&config) {
        tracing::warn!("[launcher] 升级 MobileGlues 配置失败：{e}");
    }

    Some(dir.to_string_lossy().to_string())
}

/// 老配置里没有 `enableNoError`（= 不忽略任何错误）—— 26.3 必须开启才不崩，
/// 所以这里就地升级（保留用户其它设置）。
fn upgrade_mobileglues_ignore_error(config: &std::path::Path) -> Result<(), String> {
    let text = std::fs::read_to_string(config).map_err(|e| e.to_string())?;
    let mut value: serde_json::Value = serde_json::from_str(&text).map_err(|e| e.to_string())?;
    // MG 的开关是 `enableNoError`（0..3；1=Partial、2=Full）。
    // 日志里那个 `ignoreError = %i` 是**最终值**，不是配置键 —— 这点踩过坑。
    let current = value
        .get("enableNoError")
        .and_then(|v| v.as_i64())
        .unwrap_or(0);
    if current >= 2 {
        return Ok(());
    }
    value["enableNoError"] = serde_json::json!(2);
    std::fs::write(
        config,
        serde_json::to_string_pretty(&value).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    tracing::info!("[launcher] 已把 MobileGlues 的 enableNoError 升到 2（26.3 需要）");
    Ok(())
}

/// 渲染后端 → **C 层**（libpojavexec）认的逻辑名。
///
/// C 层是 `strncmp("opengles", renderer, 8)` 选桥接表：GL4ES 与 MobileGlues
/// 都必须以 `opengles` 开头。之前给 MG 直接传 `.so` 文件名 `libmobileglues.so`，
/// 结果**一个分支都不匹配**、`config_renderer` 保持 0，`br_init()` 在空桥接表上
/// 直接崩（1.18.2 + MG 实测）。
///
/// 注意这和 `-Dorg.lwjgl.opengl.libname`（真实文件名）是两回事，不能混用。
fn renderer_c_name(renderer: &str) -> &'static str {
    match renderer {
        "vulkan_zink" => "vulkan_zink",
        "libmobileglues.so" => "opengles_mobileglues",
        _ => "opengles2",
    }
}

/// 渲染后端 → 插件清单里 `renderers` 用的键。
///
/// 用**设置页的取值**当键（`opengles2` / `mobileglues` / `vulkan_zink`），而不是
/// `.so` 文件名 —— 这样清单作者不用关心我们内部把 MG 映射成哪个文件名。
fn renderer_plugin_key(renderer: &str) -> &'static str {
    match renderer {
        "vulkan_zink" => "vulkan_zink",
        "libmobileglues.so" => "mobileglues",
        _ => "opengles2",
    }
}

/// 从设置文件里取整数项（兼容 snake_case 与 camelCase 两种键名）。
fn raw_i64(raw: &std::collections::HashMap<String, serde_json::Value>, keys: &[&str]) -> Option<i64> {
    keys.iter()
        .find_map(|k| raw.get(*k).and_then(|v| v.as_i64()))
}

/// 从设置文件里取文本项（兼容两种键名）。
fn raw_text(
    raw: &std::collections::HashMap<String, serde_json::Value>,
    keys: &[&str],
) -> Option<String> {
    keys.iter()
        .find_map(|k| raw.get(*k).and_then(|v| v.as_str()).map(|s| s.to_string()))
}

/// 解析实际使用的 `-Xmx` / `-Xms`（单位 MB）。
///
/// 修的是两个真问题：
/// 1. 以前只读 `instance.max_memory_mb.unwrap_or(2048)`，**设置界面里配的全局内存
///    完全不生效** —— 每个实例都固定 2048M；
/// 2. 没有任何范围校验：`0` / 负数会拼出 `-Xmx0M`，`min > max` 会让 JVM 直接拒绝启动。
fn resolve_memory_limits(
    instance: &MinecraftProfile,
    raw: &std::collections::HashMap<String, serde_json::Value>,
) -> (i32, i32) {
    // 手机上的实际上限；再高只会换来 OOM 和系统杀进程
    const HARD_MAX_MB: i32 = 8192;
    const HARD_MIN_MB: i32 = 256;

    let max_mb = instance
        .max_memory_mb
        .or_else(|| raw_i64(raw, &["max_memory_mb", "maxMemoryMb"]).map(|v| v as i32))
        .unwrap_or(2048)
        .clamp(512, HARD_MAX_MB);

    let min_mb = raw_i64(raw, &["min_memory_mb", "minMemoryMb"])
        .map(|v| v as i32)
        .unwrap_or(512)
        .clamp(HARD_MIN_MB, max_mb);

    (max_mb, min_mb)
}
fn build_jvm_args(instance: &MinecraftProfile, runtime: &Runtime, vendor: &VendorJars) -> Vec<String> {
    let mut args = Vec::new();

    // 设置文件只读一次（load_raw_settings_sync 是同步磁盘读，别在热路径里反复调）
    let raw = crate::settings::load_raw_settings_sync();

    let (max_mem, min_mem) = resolve_memory_limits(instance, &raw);
    args.push(format!("-Xmx{}M", max_mem));
    args.push(format!("-Xms{}M", min_mem));

    // 设置界面里的「JVM 参数」以前从来没被读过 —— 改了没有任何效果。
    if let Some(global_jvm_args) = raw_text(&raw, &["jvm_args", "jvmArgs"]) {
        if !global_jvm_args.trim().is_empty() {
            args.extend(global_jvm_args.split_whitespace().map(|s| s.to_string()));
        }
    }

    // Caciocavallo（安卓上的 AWT 实现）。
    //
    // 现状：**关闭**。Pojav 版 JRE 的 lib 目录里只有 libawt_headless.so，
    // 没有 libawt_xawt.so；而 `-Djava.awt.headless=false` 会让 java.awt.Toolkit
    // 的静态初始化去加载 libawt_xawt.so 并直接 UnsatisfiedLinkError。
    // 原版 Minecraft 不需要 AWT 界面（LWJGL 自带窗口），保持 headless 即可；
    // 将来若换用带 xawt 的 JRE，把这里改成 true 就能启用（参数表已照抄 Pojav）。
    const ENABLE_CACIOCAVALLO: bool = false;

    let (width, height) = instance.resolution.unwrap_or((854, 480));
    if !ENABLE_CACIOCAVALLO {
        args.push("-Djava.awt.headless=true".to_string());
        if let Some(custom_args) = &instance.java_args {
            args.extend(custom_args.split_whitespace().map(|s| s.to_string()));
        }
        return args;
    }

    if runtime.java_version == 8 {
        args.push("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit".to_string());
        args.push(
            "-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment".to_string(),
        );
    } else {
        args.push("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit".to_string());
        args.push(
            "-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment"
                .to_string(),
        );
        args.push(
            "-Djava.system.class.loader=com.github.caciocavallosilano.cacio.ctc.CTCPreloadClassLoader"
                .to_string(),
        );
        for flag in [
            "--add-exports=java.desktop/java.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
        ] {
            args.push(flag.to_string());
        }
    }

    args.push("-Djava.awt.headless=false".to_string());
    args.push(format!("-Dcacio.managed.screensize={width}x{height}"));
    args.push("-Dcacio.font.fontmanager=sun.awt.X11FontManager".to_string());
    args.push("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler".to_string());
    args.push("-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel".to_string());

    // 关键：Caciocavallo 必须挂在引导类路径上。
    // `-Djava.system.class.loader` 指定的 CTCPreloadClassLoader 由引导加载器解析，
    // 只放进 -cp 会报 ClassNotFoundException / JVM_FindClassFromBootLoader。
    if !vendor.cacio.is_empty() {
        let joined = vendor
            .cacio
            .iter()
            .map(|p| p.to_string_lossy().to_string())
            .collect::<Vec<_>>()
            .join(":");
        let kind = if runtime.java_version == 8 { "p" } else { "a" };
        args.push(format!("-Xbootclasspath/{kind}:{joined}"));
    }

    if let Some(custom_args) = &instance.java_args {
        args.extend(custom_args.split_whitespace().map(|s| s.to_string()));
    }

    args
}

/// 安卓端必须追加的 JVM 参数（对照 PojavLauncher 的 `JREUtils.getJavaArgs`）。
/// 这些参数少一个都可能表现为「JVM 起来了但游戏立刻崩」，所以尽量照抄：
/// 关键几项是 `java.library.path`（找到 liblwjgl/libgl4es）、`org.lwjgl.opengl.libname`
/// （走 GL4ES 把 OpenGL 翻成 GLES）、以及 `glfwstub.*`（安卓窗口尺寸）。
#[cfg(target_os = "android")]
fn build_android_jvm_args(
    instance: &MinecraftProfile,
    runtime: &Runtime,
    data_dir: &str,
    game_dir: &str,
    natives_dir: &str,
    native_lib_dir: &str,
    lwjgl_natives: Option<&std::path::Path>,
    extra_library_dirs: &[std::path::PathBuf],
) -> Vec<String> {
    let mut args = Vec::new();

    // 顺序即优先级：
    // 1. LWJGL 3.4.1 的原生库目录（有的话）**必须最前** —— `System.loadLibrary("lwjgl"
    //    / "openal" / …)` 找的是 `java.library.path`，而 APK 与 `files/natives` 里
    //    还躺着老的 3.3.x 版本。被旧库抢先的后果是 LWJGL 直接报
    //    `Incompatible Java and native library versions detected`，随后 LibFFI/freetype
    //    一连串 `UnsatisfiedLinkError`（26.3 实测）。
    // 2. 插件目录：同名库（SDL3 / 渲染器）要优先于内置那份。
    let mut library_parts: Vec<String> = Vec::new();
    if let Some(dir) = lwjgl_natives {
        library_parts.push(dir.to_string_lossy().to_string());
    }
    library_parts.extend(
        extra_library_dirs
            .iter()
            .map(|p| p.to_string_lossy().to_string()),
    );
    library_parts.push(natives_dir.to_string());
    if !native_lib_dir.is_empty() {
        library_parts.push(native_lib_dir.to_string());
    }
    args.push(format!("-Djava.library.path={}", library_parts.join(":")));

    // LWJGL 3.4.1 组件集（MC 26.3+）：它带来的 liblwjgl.so / liblwjgl_opengl.so 是
    // **3.4.1 版**，而 APK 的 jniLibs 里那份 liblwjgl.so 是老 GLFW 版 ——
    // 两者符号不同，混用就是 LWJGL 那句
    // `Incompatible Java and native library versions detected`。
    // 用 `org.lwjgl.librarypath` 单独把 LWJGL 的库搜索路径指过去，
    // 老路径（None）时行为完全不变。
    if let Some(dir) = lwjgl_natives {
        args.push(format!("-Dorg.lwjgl.librarypath={}", dir.display()));
    } else if !native_lib_dir.is_empty() {
        // ⚠️ 2026-10-20 真机修：**老 LWJGL 路径也要显式指定**。
        //
        // 之前这里什么都不设，LWJGL 就去 `java.library.path` 里挨个找
        // `liblwjgl.so` —— 而那个列表里还躺着 `natives_dir`（MC 官方 3.3.3 的
        // 原生库解压缓存）。一旦先命中 3.3.3，与随包 Pojav 版 jar（3.4.0 的类）
        // 对不上，就是真机实测到的：
        //   [LWJGL] [ERROR] Incompatible Java and native library versions detected.
        //   GLFW.<clinit> → NoClassDefFoundError → Minecraft has crashed!
        // 26.3 之所以没事，正是因为它走了上面那条 `Some(dir)` 分支。
        //
        // `native_lib_dir` 是 APK 里那份与 jar 配套的 Pojav 版 so，指死它即可。
        args.push(format!("-Dorg.lwjgl.librarypath={native_lib_dir}"));
    }

    // JNA：随包那份 jniLibs/libjnidispatch.so 曾是 6.1.6（老版本用的），而 MC 26.3 的
    // jna.jar 要求 native 版本 7.0.4 —— `com.sun.jna.Version` 里 VERSION=5.17.0 是 jar
    // 自己的版本，VERSION_NATIVE=7.0.4 才是它要的 native。混用会直接
    //   java.lang.Error: There is an incompatible JNA native library installed
    //   Expected: 7.0.4 / Found: 6.1.6
    // 于是 oshi 全线失败（崩溃报告里 processor / memory / Graphics card 全是 ~~ERROR~~）。
    // 现在 jniLibs 里换成了 JNA 5.17.0 的 Android native（与 `src-tauri/jna/<abi>/`
    // 服务端那份同一来源，内含 7.0.4），所以这里照旧把 JNA 指向 `files/natives` 即可。
    // 曾试过 `-Djna.nosys=true` 让 JNA 用 jar 自带的那份：更糟 —— 我们给 JVM 设了
    // `-Dos.name=Linux`，JNA 会按桌面 Linux 挑 glibc 版本，报
    // `dlopen failed: library "libc.so.6" not found`。别再走那条路。
    args.push(format!("-Djna.boot.library.path={native_lib_dir}"));
    args.push(format!("-Djava.home={}", runtime.path));
    args.push(format!(
        "-Djava.io.tmpdir={}/cache",
        data_dir.trim_end_matches('/')
    ));
    args.push(format!("-Duser.home={game_dir}"));
    args.push(format!("-Duser.dir={game_dir}"));
    args.push("-Dos.name=Linux".to_string());
    args.push(format!(
        "-Dos.version={}",
        crate::android_env::android_release()
    ));

    // 渲染后端决定 LWJGL 去加载哪个 OpenGL 实现（与 env 里的 SDL_OPENGL_LIBRARY 同源，
    // 见 renderer_gl_lib）。
    //
    // 必须和主流程用**同一套解析**：这里若直接读全局设置，实例级选择就形同虚设
    // —— 实测表现是「实例设了 GL4ES，游戏还是加载 libmobileglues.so」。
    let mut _renderer_why = String::new();
    let renderer = resolve_renderer(Some(instance), &mut _renderer_why);
    let gl_libname = renderer_gl_lib(&renderer);
    args.push(format!("-Dorg.lwjgl.opengl.libname={gl_libname}"));
    args.push("-Dorg.lwjgl.vulkan.libname=libvulkan.so".to_string());
    // freetype 必须跟**这一套 LWJGL 的 Java 类**配套：`native_lib_dir`（APK 里抽出来的
    // `files/natives`）躺着的是随包老版 `libfreetype.so`，而 3.4.1 组件集里另有一份配套的。
    // 指错就会重现本函数开头那个坑 —— `Incompatible Java and native library versions
    // detected`（那一条注释讲的是 `java.library.path` 的顺序，而这个属性**绕过**了它）。
    // 组件集里没有这份时退回老路径，老版本（无 3.4.1 natives）行为完全不变。
    let component_freetype = lwjgl_natives.and_then(|dir| {
        let p = dir.join("libfreetype.so");
        p.exists().then_some(p)
    });
    if let Some(p) = component_freetype {
        args.push(format!("-Dorg.lwjgl.freetype.libname={}", p.display()));
    } else if !native_lib_dir.is_empty() {
        args.push(format!(
            "-Dorg.lwjgl.freetype.libname={native_lib_dir}/libfreetype.so"
        ));
    }

    // MC 26.3 的 NativeLibrariesBootstrap 会在启动时强加载 spvc（着色器翻译）。
    // LWJGL 的 spvc 模块默认按 `libspirv-cross.so` 找 —— 我们随包的是
    // spirv-cross 官方 c-shared 构建（`libspirv-cross-c-shared.so`），所以要把
    // 库名显式告诉它，否则启动就 `UnsatisfiedLinkError: Loading library spvc`。
    if lwjgl_natives.is_some() {
        args.push("-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared".to_string());
    }

    // 安卓 GLFW stub 的窗口尺寸**必须等于 Surface 的实际像素尺寸**。
    // 触摸坐标是 Surface 像素：若游戏以为窗口是 1280x720 而 Surface 是 2424x1080，
    // 所有点击都会被换算到错误位置（画面正常但按钮点不动）。
    // Surface 尺寸由 GameActivity 在 surfaceChanged 时同步过来；
    // 尚未同步（例如桌面端调用路径）时退回实例配置的分辨率。
    let (width, height) = crate::android_env::surface_size()
        .or(instance.resolution)
        .unwrap_or((1280, 720));
    args.push(format!("-Dglfwstub.windowWidth={width}"));
    args.push(format!("-Dglfwstub.windowHeight={height}"));
    args.push("-Dglfwstub.initEgl=false".to_string());

    // 线程栈抓取 agent（诊断用，见 jni/thread_dump_agent.c）：natives 里有就加载。
    // 它只在 127.0.0.1:12857 起服务，连上才 dump，平时零开销。
    let agent_path = format!("{natives_dir}/libthreaddump.so");
    if std::path::Path::new(&agent_path).exists() {
        args.push(format!("-agentpath:{agent_path}"));
    }

    args.push("-Dlog4j2.formatMsgNoLookups=true".to_string());
    args.push("-Dfml.earlyprogresswindow=false".to_string());
    args.push("-Dloader.disable_forked_guis=true".to_string());
    // 安卓没有 jspawnhelper，POSIX_SPAWN 会让 ProcessBuilder 直接失败
    args.push("-Djdk.lang.Process.launchMechanism=FORK".to_string());
    // Sodium 的 PreLaunchChecks（LWJGL 版本串）由该开关把门（BugChecks.ISSUE_2561）。
    // 2026-10-24 实测：随包 Pojav LWJGL 的版本串已满足 3.3.3，本来就能过；
    // 设 false 是抄 ZalithLauncher2 的做法 —— 用户装 LWJGL 3.4.1 组件包玩 1.21.1 时
    // 版本串对不上，这条能保住 Sodium 不中止。（PostLaunch 的 POJAV_RENDERER 检测
    // 不受它管，那个靠 QOOKIX_RENDERER 改名解决，见 env_map。）
    args.push("-Dsodium.checks.issue2561=false".to_string());
    // Pojav 系 JRE 的 DNS 补丁读该属性找 resolv.conf（安卓没有 /etc/resolv.conf）。
    // 不设的话游戏内所有 DNS 解析超时 —— 之前日志里 163 个 mod 的版本检查全部
    // HTTP connect timed out 就是这个原因。
    args.push(format!(
        "-Dext.net.resolvPath={}/resolv.conf",
        data_dir.trim_end_matches('/')
    ));
    args.push(format!(
        "-XX:ActiveProcessorCount={}",
        std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(4)
    ));

    args
}

/// 资源索引的 id **不等于**游戏版本号。
///
/// 例如 **1.18.2 的资源索引 id 就是 `1.18`**（Mojang 在同一小版本系列里复用同一份索引），
/// 启动器下载到的文件也是 `assets/indexes/1.18.json`。
/// 而以前这里直接传 `instance.mc_version`（"1.18.2"），游戏于是去找
/// `assets/indexes/1.18.2.json` —— 永远找不到（日志里那三条报错的来源）。
/// 正确做法是读版本 JSON 里的 `assetIndex.id`。
/// 把 [crate::models::Loader] 转成后端通用的小写名（"vanilla" / "fabric" …）。
///
/// `browse.rs` 里已有同义的 `loader_to_str`，但它不是 pub；
/// 这里启动路径只需要一个轻量映射，避免为了复用到处改可见性。
fn loader_name(loader: &crate::models::Loader) -> String {
    match loader {
        crate::models::Loader::Vanilla => "vanilla".to_string(),
        crate::models::Loader::Fabric => "fabric".to_string(),
        crate::models::Loader::Quilt => "quilt".to_string(),
        crate::models::Loader::Forge => "forge".to_string(),
        crate::models::Loader::NeoForge => "neoforge".to_string(),
    }
}

fn asset_index_id(instance: &MinecraftProfile, data_dir: &str) -> String {
    let json_file = Path::new(data_dir)
        .join("versions")
        .join(&instance.mc_version)
        .join(format!("{}.json", instance.mc_version));
    if let Ok(text) = std::fs::read_to_string(&json_file) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(&text) {
            if let Some(id) = v
                .get("assetIndex")
                .and_then(|a| a.get("id"))
                .and_then(|s| s.as_str())
            {
                return id.to_string();
            }
        }
    }
    // 读不到版本 JSON 就退回版本号：至少不会比修复前更差
    instance.mc_version.clone()
}
fn build_game_args(
    instance: &MinecraftProfile,
    account: Option<&Account>,
    data_dir: &str,
    ms_access_token: Option<&str>,
) -> Result<Vec<String>> {
    let mut args = Vec::new();

    let (username, uuid, access_token) = match account {
        Some(acc) => {
            let token = match acc.account_type {
                // 正版：必须是**换来的** Minecraft access token。
                // 以前这里是 `acc.msa_refresh_token`，等于把刷新令牌当访问令牌用 ——
                // 游戏能起来，但一连正版服务器就 401/掉线，用户完全看不出原因。
                crate::models::AccountType::Microsoft => ms_access_token
                    .filter(|t| !t.is_empty())
                    .ok_or_else(|| {
                        anyhow::anyhow!("正版会话已过期，请重新登录微软账号后再启动")
                    })?
                    .to_string(),
                // 离线账号没有正版会话，通行做法就是填 `0`
                _ => "0".to_string(),
            };
            (acc.username.clone(), acc.uuid.clone(), token)
        }
        None => ("Steve".to_string(), "00000000-0000-0000-0000-000000000000".to_string(), "0".to_string()),
    };

    args.push("--username".to_string());
    args.push(username);
    args.push("--uuid".to_string());
    args.push(uuid);
    args.push("--accessToken".to_string());
    args.push(access_token);
    args.push("--version".to_string());
    args.push(instance.mc_version.clone());
    args.push("--gameDir".to_string());
    args.push(instance.game_dir.clone());
    // --assetsDir 必须是**绝对路径**。以前传的是相对路径 "assets"，
    // 而游戏进程的工作目录并不是数据目录（安卓应用的工作目录是 "/"），
    // 于是游戏永远找不到资源 —— 日志里那几条
    // `Can't find the resource index file: assets/indexes/1.18.2.json` 就是这个原因。
    let assets_dir = Path::new(data_dir).join("assets");
    args.push("--assetsDir".to_string());
    args.push(assets_dir.to_string_lossy().to_string());
    args.push("--assetIndex".to_string());
    args.push(asset_index_id(instance, data_dir));

    if let Some((w, h)) = instance.resolution {
        args.push("--width".to_string());
        args.push(w.to_string());
        args.push("--height".to_string());
        args.push(h.to_string());
    }

    if let Some(custom_args) = &instance.game_args {
        args.extend(custom_args.split_whitespace().map(|s| s.to_string()));
    }

    // 全局「游戏参数」同样从前端设置里有，但以前没人读。
    let raw = crate::settings::load_raw_settings_sync();
    if let Some(global_game_args) = raw_text(&raw, &["game_args", "gameArgs"]) {
        if !global_game_args.trim().is_empty() {
            args.extend(global_game_args.split_whitespace().map(|s| s.to_string()));
        }
    }

    Ok(args)
}
