// 本仓库有不少「能力已实现、但还没接到界面/流程上」的入口：JRE 安装器（`java.rs::install_jre`
// 等着开服时自动补 Java）、旧平台 API 客户端（`modpack.rs` 里自己那套 Modrinth/CurseForge，
// 现在实际走 `browse.rs`）、描述 Mojang 版本清单 schema 的结构体（`models.rs`）、
// 以及一些留作兜底的错误枚举变体。
//
// 它们是**有意保留**的（不是忘了删），所以这里统一放开 dead_code ——
// 之前每次编译 30 多条「never used / never read」纯属噪声，真正的编译错误反而被埋掉了。
// 哪个能力接上线了，就把对应项的 `#[allow(dead_code)]` 去掉（文件内已逐处标注）。
// 未使用的 import / 变量仍然照常报警 —— 那类是真该清掉的。
#![allow(dead_code)]

mod commands;
mod models;
mod settings;
mod instances;
mod version;
mod launch;
mod launch_gen;
/// GL_VENDOR 品牌串覆盖（F3 里那行 `Display: … (厂商串)`）。
///
/// 只对安卓有效：靠 `dlopen`/`dlsym` 拿渲染器里的 `glGetString`，
/// 桌面端（以及宿主上的单测）没有这条链，整块不参与编译。
#[cfg(target_os = "android")]
mod gl_brand;
mod download;
mod java;
mod accounts;
mod modpack;
/// ZIP 解压预算（zip bomb 防护，审计 B13）。
mod zip_guard;
mod hangar;
mod frp;
mod servers;
/// 服务器根目录定位与列表读取（从 `servers` 拆出，Q2 体量棘轮）。
mod server_list;
/// 服务器空闲休眠与唤醒（无人时停服省电，有人连接自动拉起）。
mod sleep;
mod server_process;
mod server_files;
mod instance_files;
mod rcon;
mod crash;
mod renderer_health;
/// 按 MC 版本挑默认渲染器（1.21.x 的 GL4ES 崩溃判断）。
mod renderer_pick;
mod storage;
/// 游戏目录设置（内部 / 应用专属外部 / 自定义）。
mod game_dir;
mod natives;
mod jvm_launcher;
mod loader_install;
mod loader_classpath;
mod terracotta;
mod render_bridge;
// input_bridge 已删除（2026-10-26 审查 B-P1-3）：那 521 行是「Rust 版输入桥」，
// 生产链路一处没接（init/set_ready/pump 全零调用），真实输入走 C 层
// input_bridge_v3.c。留着只会让人以为改了它就能生效。
mod browse;
mod translate;
mod pins;
mod groups;
mod mirror;
mod mcping;
mod util;
mod launch_log;
mod skins;
mod world_backup;
mod playtime;
mod fsutil;
mod progress;
/// 进程级「状态已变」信号（A2 第一步）。不是状态容器，只是失效通知。
mod app_state;
/// 错误的稳定分类与失败信封（A2 错误契约）。`code` 的定义只在这里。
mod error_code;
/// 插件系统（v1 组件插件；渲染器/驱动沿用同一套机制）。
mod plugin;
/// 启动器自更新（查 GitHub Release → 下 APK → 交给系统安装器）。
mod updater;
mod updater_version;
/// 控制布局里「按键透传」的读取与修改。
mod controls;
mod control_import;
mod jre_download;
/// 局域网直传同步：安卓端 HTTP 客户端（连桌面端起的 HTTP 服务拉实例）。
mod lan_sync;
mod lan_sync_upload;

#[cfg(target_os = "android")]
mod jni_bridge;
mod android_bridge;
mod android_env;

// 只在 `run()` 里用到，而 `run()` 是 `#[cfg(not(test))]` —— 不加同样的 cfg，
// 编译测试目标时这里会报「unused import」。
// ⚠️ 阶段 4（2026-10-25）：不再启动 Tauri 运行时（App 已无 WebView）。
// 命令走 `TauriBridge.nativeInvoke`，事件走 `onCoreEvent`，
// 进程级初始化在 `jni_bridge::nativeAttach`。
#[cfg(not(test))]
pub fn run() {}

#[cfg(test)]
pub fn run() {}
