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
#[cfg(not(test))]
use tauri::Builder;

#[cfg(not(test))]
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        // 保存 AppHandle：下载/安装/启动的进度事件都靠它广播给前端
        .setup(|app| {
            progress::set_app_handle(app.handle().clone());
            // 进程重启后把后台任务补回来：休眠中的服务器要重新挂上唤醒监听，
            // 运行中的要重新开始空闲巡检。这两件事不补都是**静默失效**
            // （界面写着「休眠中」却没人听端口），所以放在这里而不是等前端来调。
            sleep::resume_on_start();
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
    servers::list_hosted_servers,
    servers::get_hosted_server,
    servers::create_hosted_server,
    servers::update_hosted_server,
    servers::delete_hosted_server,
    servers::suggest_server_memory,
    servers::list_paper_versions,
    instance_files::check_instance_files,
    instance_files::repair_instance_files,
    // 共享游戏文件（版本/库/素材/Java）的占用统计与清理
    instance_files::shared_game_usage,
    instance_files::plan_instance_cleanup,
    instance_files::cleanup_instance_shared,
    servers::install_hosted_server_core,
    server_files::list_hosted_server_dir,
    server_files::read_hosted_server_file,
    server_files::write_hosted_server_file,
    server_files::list_hosted_server_config_files,
    server_files::open_hosted_server_folder,
    servers::start_hosted_server,
    servers::is_hosted_server_running,
    servers::read_hosted_server_log,
    server_process::inspect_server_core_manifest,
    servers::stop_hosted_server,
    servers::server_console_command,
    servers::hosted_server_runtime,
    servers::hosted_server_log,
    servers::hosted_server_address,
    servers::hosted_server_addresses,
    servers::device_available_memory_mb,
    servers::battery_unrestricted,
    servers::request_battery_unrestricted,
    servers::device_thermal_status,
    servers::open_hosted_server_directory,
    servers::hosted_server_core_installed,
    servers::get_hosted_server_properties,
    servers::set_hosted_server_properties,
    terracotta::terracotta_ping,
    terracotta::terracotta_request,
    terracotta::terracotta_vpn_granted,
    terracotta::terracotta_request_vpn,
            // Version commands
            commands::get_version_list,
            commands::get_version_manifest,
            commands::get_version_info,
            commands::install_version,
            
            // Instance commands
            commands::create_instance,
            commands::delete_instance,
            commands::list_instances,
            commands::get_instance,
            commands::update_instance,
            
            // Account commands
            commands::list_accounts,
            commands::add_account,
            commands::remove_account,
            commands::select_account,
            
            // Launch commands
            commands::launch_game,
            commands::kill_game,
            commands::get_game_status,
            
            // Download commands
            commands::download_file,
            commands::cancel_download,
            commands::get_download_progress,
            commands::cancel_install,
            commands::read_clipboard,
            commands::write_clipboard,
            
            // Settings commands
            commands::get_settings,
            commands::update_settings,

            // Plugin commands（组件/渲染器插件：下载、校验、启停）
            commands::get_plugins,
            commands::refresh_plugin_manifest,
            commands::install_plugin,
            commands::install_plugin_from_file,
            commands::uninstall_plugin,
            commands::set_plugin_enabled,
            commands::get_plugin_manifest_url,
            commands::set_plugin_manifest_url,
            // 首启准备：自动补齐渲染器/驱动/组件
            commands::get_plugin_setup_status,
            commands::install_recommended_plugins,
            commands::dismiss_plugin_setup,
            
            // Modpack commands
            commands::import_modpack,
            commands::plan_modpack_export,
            commands::export_modpack,

            // Hangar（PaperMC 插件平台）：搜索 + 装到服务器 plugins/
            commands::hangar_search_plugins,
            commands::install_server_plugin,
            commands::list_server_plugins,
            commands::delete_server_plugin,

            // FRP 隧道：面板登录 / 用户信息 / 隧道列表 / 启停 frpc
            commands::frp_status,
            commands::frp_set_api_base,
            commands::frp_login_start,
            commands::frp_login_poll,
            commands::frp_logout,
            commands::frp_user_info,
            commands::frp_tunnels,
            commands::frp_start_tunnel,
            commands::frp_stop_tunnel,
            commands::frp_running,
            commands::frp_tunnel_log,
            commands::frp_probe,

            // FRP 联机房间（XTCP）：节点 / 房间列表 / 详情 / 建房 / 进出房 / 关房
            commands::frp_room_nodes,
            commands::frp_room_list,
            commands::frp_room_detail,
            commands::frp_room_create,
            commands::frp_room_join,
            commands::frp_room_leave,
            commands::frp_room_close,
            commands::frp_room_host_start,
            commands::frp_room_visitor_start,
            commands::frp_room_stop,
            commands::install_mod,
            commands::uninstall_mod,
            commands::get_installed_mods,

            // Browse commands
            commands::browse,
            commands::curseforge_categories,
            commands::project_info,
            commands::project_versions,
            commands::project_dependencies,
            commands::get_loader_versions,
            commands::install_game,
            commands::install_content,
            commands::list_content,
            commands::mc_wiki_url,
            commands::fetch_news,

            // 内容中心翻译
            commands::translate_mod_descriptions,
            commands::translate_project_body,
            commands::report_translation_stale,
            commands::report_translation_quality,
            commands::clear_translation_cache,
            commands::test_translate_api,

            commands::estimate_download,
            commands::estimate_import,

            // Pins
            commands::get_pins,
            commands::set_pins,

            // Instance groups
            commands::list_instance_groups,
            commands::create_instance_group,
            commands::rename_instance_group,
            commands::delete_instance_group,
            commands::reorder_instance_groups,

            // Mirror
            commands::list_mirrors,
            commands::test_mirror,
            commands::test_proxy,

            // Memory & log
            commands::auto_detect_memory,
            commands::log_debug,
            commands::is_game_running,

            // Storage
            commands::get_storage_stats,
            commands::refresh_storage_stats,
            commands::clear_cache,

            // 游戏目录（内部 / 应用专属外部 / 自定义）
            game_dir::get_game_dir_state,
            game_dir::get_game_dir_options,
            game_dir::has_all_files_access,
            game_dir::request_all_files_access,
            game_dir::pick_game_dir,
            game_dir::take_picked_game_dir,
            android_bridge::set_log_zoom_capture,
            game_dir::set_game_dir,

            // Crash analysis
            commands::list_crash_logs,
            commands::analyze_crash_log,
            commands::get_crash_report_content,

            // Skins
            commands::list_skins,
            commands::read_skin_data_url,
            commands::save_skin_from_data,
            commands::download_skin_from_url,
            commands::delete_skin,
            commands::fetch_image_data_url,
            commands::fetch_player_skin,
            commands::fetch_player_capes,
            commands::apply_skin_to_account,
            commands::apply_cape_to_account,
            commands::apply_skin_offline,
            commands::get_offline_skin,

            // Multiplayer servers
            commands::list_servers,
            commands::ping_mc_server,

            // World backup
            commands::list_world_backups,
            commands::create_world_backup,
            commands::restore_world_backup,
            commands::delete_world_backup,

            // Playtime
            commands::playtime_stats,
            commands::read_instance_log,
            commands::export_instance_logs,
        commands::check_renderer_health,
        commands::check_renderer_health_latest,
            commands::get_pojav_prefs,
            commands::set_pojav_prefs,

            // Microsoft login (device code)
            commands::login_ms_start,
            commands::login_ms_poll,

            // Content management
            commands::check_updates,
            commands::apply_update,
            commands::uninstall_content,
            commands::identify_content,
            commands::import_local_file,
            commands::toggle_content_enabled,
            commands::save_text_file,
            commands::extract_game_icons,

            // Instance file manager
            commands::list_instance_folders,
            commands::list_instance_files,
            commands::list_instance_dir,
            commands::read_instance_file,
            commands::write_instance_file,
            commands::create_instance_entry,
            commands::delete_instance_path,
            commands::rename_instance_path,

            // Image import
            commands::import_instance_image,
            commands::import_background_image,

            // Native (orientation / file picker)
            commands::set_orientation,
            commands::resolve_picked_path,
            commands::detect_system_proxy,

            // 启动器自更新
            updater::check_for_update,
            updater::download_update,
            updater::install_update,

            // 控制布局的按键透传
            controls::get_control_buttons,
            controls::set_control_button_passthru,

            // 控制布局管理（列出 / 切换当前 / 复制 / 重命名 / 删除）
            controls::list_control_layouts,
            controls::set_current_control_layout,
            controls::duplicate_control_layout,
            controls::rename_control_layout,
            controls::delete_control_layout,
            controls::open_control_layout_editor,
            controls::export_control_layout,
            controls::pick_control_layout,
            controls::take_control_import,
            controls::import_control_layout_by_path,
        ])
        .run(tauri::generate_context!())
        .expect("error while running Qookix application");
}

#[cfg(test)]
pub fn run() {}
