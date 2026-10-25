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
mod hangar;
mod frp;
mod servers;
/// 服务器空闲休眠与唤醒（无人时停服省电，有人连接自动拉起）。
mod sleep;
mod server_process;
mod server_files;
mod instance_files;
mod rcon;
mod crash;
mod renderer_health;
mod storage;
/// 游戏目录设置（内部 / 应用专属外部 / 自定义）。
mod game_dir;
mod natives;
mod jvm_launcher;
mod loader_install;
mod terracotta;
mod render_bridge;
mod input_bridge;
mod browse;
mod translate;
mod pins;
mod groups;
mod mirror;
mod mcping;
mod util;
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
/// 控制布局里「按键透传」的读取与修改。
mod controls;

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
