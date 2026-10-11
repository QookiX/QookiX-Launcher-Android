import { t as $t } from "./i18n";
import { invoke as rawInvoke } from "@tauri-apps/api/core";
import { trackStart, trackEnd, trackError } from "./loadingBar";
import type { PinItem } from "./stores/pins";
import type {
  Account,
  CacheClearResult,
  ContentItem,
  CrashDiagnosis,
  FsEntry,
  Instance,
  InstanceFileReport,
  InstanceGroup,
  MirrorPreset,
  MirrorTestResult,
  NewsItem,
  ProjectDependency,
  ProjectHit,
  ProjectVersion,
  RendererIssue,
  ServerConfig,
  ServerEntry,
  ServerProperties,
  ServerRuntime,
  ServerStatus,
  Settings,
  StorageStats,
  TerracottaInfo,
  TerracottaLaunch,
  UpdateInfo,
  PlaytimeStats,
  WorldBackupInfo,
  PluginInfo,
  PluginSetupResult,
  PluginSetupStatus,
  ControlButtonInfo,
  ControlImportResult,
  ControlLayoutInfo,
  GameDirOption,
  GameDirState,
} from "./types";

// ---------------------------------------------------------------------------
// Android 兼容层：命令名映射
// Windows 前端调用的命令名与 Android Rust 后端的命令名不同，
// 这里做一层翻译，让上层 Vue 代码无需任何改动。
// ---------------------------------------------------------------------------
const CMD_MAP: Record<string, string> = {
  // settings
  set_settings: "update_settings",
  // instances
  get_instance_info: "get_instance",
  update_instance_settings: "update_instance",
  // launch
  launch_instance: "launch_game",
  stop_game: "kill_game",
  // accounts
  login_offline: "add_account",
  logout_account: "remove_account",
};

/**
 * Android 后端**没有实现**的命令 —— 一份表同时说清「哪些没有」和「读还是写」。
 *
 * 以前拆成 `UNIMPLEMENTED` / `UNIMPLEMENTED_WRITE` / `UNIMPLEMENTED_DEFAULTS` 三份，
 * 结果互相矛盾：hosted 系列（create / update / delete / install / start / stop_hosted_server、
 * write_hosted_server_file）**早已在后端实现**，却还挂在写名单里 —— 而拦截只认第一份，
 * 于是名单形同虚设、默认值表里还留着一堆永远用不到的死条目（2026-10-06 审计）。
 * 现在只有这一份真源：
 *   "read"  → 给安全默认值（别让调用点抛 TypeError、整页崩）
 *   "write" → 直接 reject（后端什么都没做，UI 绝不能弹「已保存 / 已启动」）
 */
const UNIMPLEMENTED: Record<string, "read" | "write"> = {
  // 导入类：Android 上确实没有对应实现
  scan_minecraft_import: "read",
  import_minecraft_folder: "write",
  import_instance_pack: "write",
  // 陶瓦联机：这几条是**桌面版**「启动 Terracotta 进程」的接口，
  // Android 改走 :tunnel 的本地 HTTP（terracotta_ping / terracotta_request）。
  terracotta_detect: "read",
  terracotta_status: "read",
  terracotta_download: "write",
  terracotta_launch: "write",
  terracotta_stop: "write",
  terracotta_create_room: "write",
  terracotta_join_room: "write",
  terracotta_leave: "write",
};

/** 未实现命令的默认返回值（**只有只读命令**才允许给安全默认值） */
const UNIMPLEMENTED_DEFAULTS: Record<string, unknown> = {
  // 调用点把它当「扫描结果对象」用，给 undefined 最接近「什么都没扫到」
  scan_minecraft_import: undefined,
  // 这两条调用点会直接读字段，给 undefined 会抛 TypeError
  terracotta_detect: null,
  terracotta_status: null,
};

/**
 * 包装 tauri invoke：
 * 1. 命令名映射（Windows → Android）
 * 2. 未实现命令的安全降级
 * 3. 顶部加载条（trackStart/trackEnd）
 */
function invoke<T>(
  cmd: string,
  args?: Record<string, unknown>,
  opts?: { silent?: boolean }
): Promise<T> {
  const silent = opts?.silent ?? false;

  // 映射命令名
  const mappedCmd = CMD_MAP[cmd] ?? cmd;

  // 未实现的命令（不触发加载条）
  const missing = UNIMPLEMENTED[mappedCmd];
  if (missing) {
    console.warn(`[api] 命令 "${cmd}" (→ "${mappedCmd}") 在 Android 上未实现`);
    if (missing === "write") {
      // 写操作必须显式失败：否则 UI 会「假成功」（问题清单 P0-4 / P0-10）
      return Promise.reject(
        new Error($t("api.not-implemented", { p1: cmd }))
      );
    }
    return Promise.resolve(UNIMPLEMENTED_DEFAULTS[mappedCmd] as T);
  }

  if (!silent) trackStart();
  return rawInvoke<T>(mappedCmd, args).then(
    (res) => {
      if (!silent) trackEnd();
      return res;
    },
    (err) => {
      if (!silent) {
        trackError();
        trackEnd();
      }
      console.error(`[api] 命令 "${cmd}" (→ "${mappedCmd}") 调用失败:`, err);
      throw err;
    },
  );
}

export const api = {
  // plugins（组件/渲染器插件：查看、下载安装、启停、卸载）
  // 每个写操作都返回最新列表，前端直接替换即可，省一次往返也避免状态错位。
  getPlugins: () => invoke<PluginInfo[]>("get_plugins", undefined, { silent: true }),
  refreshPluginManifest: () => invoke<PluginInfo[]>("refresh_plugin_manifest"),
  // 静默刷新：启动时自动检查插件更新用，失败不弹加载条/提示
  refreshPluginManifestSilent: () =>
    invoke<PluginInfo[]>("refresh_plugin_manifest", undefined, { silent: true }),
  installPlugin: (id: string) => invoke<PluginInfo[]>("install_plugin", { id }),
  installPluginFromFile: (path: string) =>
    invoke<PluginInfo[]>("install_plugin_from_file", { path }),
  uninstallPlugin: (id: string) => invoke<PluginInfo[]>("uninstall_plugin", { id }),
  setPluginEnabled: (id: string, enabled: boolean) =>
    invoke<PluginInfo[]>("set_plugin_enabled", { id, enabled }),
  getPluginManifestUrl: () =>
    invoke<string>("get_plugin_manifest_url", undefined, { silent: true }),
  setPluginManifestUrl: (url: string) =>
    invoke<string>("set_plugin_manifest_url", { url }),

  // 首启准备：渲染器/驱动/组件都是插件，新装的要一次性补齐
  getPluginSetupStatus: () =>
    invoke<PluginSetupStatus>("get_plugin_setup_status", undefined, { silent: true }),
  installRecommendedPlugins: () => invoke<PluginSetupResult>("install_recommended_plugins"),
  dismissPluginSetup: () => invoke<void>("dismiss_plugin_setup"),

  // 控制布局：按键透传（按住这个键时拖动也能转视角）
  getControlButtons: () =>
    invoke<ControlButtonInfo[]>("get_control_buttons", undefined, { silent: true }),
  setControlButtonPassthru: (index: number, enabled: boolean) =>
    invoke<ControlButtonInfo[]>("set_control_button_passthru", { index, enabled }),

  // 控制布局：管理（布局全局共享，不区分实例；改动类命令都返回最新列表）
  listControlLayouts: () =>
    invoke<ControlLayoutInfo[]>("list_control_layouts", undefined, { silent: true }),
  setCurrentControlLayout: (name: string) =>
    invoke<ControlLayoutInfo[]>("set_current_control_layout", { name }),
  duplicateControlLayout: (name: string, newName: string) =>
    invoke<ControlLayoutInfo[]>("duplicate_control_layout", { name, newName }),
  renameControlLayout: (name: string, newName: string) =>
    invoke<ControlLayoutInfo[]>("rename_control_layout", { name, newName }),
  deleteControlLayout: (name: string) =>
    invoke<ControlLayoutInfo[]>("delete_control_layout", { name }),

  // settings & java
  getSettings: () => invoke<Settings>("get_settings"),
  setSettings: (patch: Record<string, unknown>) => invoke<Settings>("set_settings", { patch }),

  // pinned items (首页 / 侧边栏)
  getPins: () => invoke<PinItem[]>("get_pins"),
  setPins: (items: PinItem[]) => invoke<void>("set_pins", { items }),
  listMirrors: () => invoke<MirrorPreset[]>("list_mirrors", undefined, { silent: true }),
  testMirror: (base: string) => invoke<MirrorTestResult>("test_mirror", { base }, { silent: true }),
  testProxy: (proxyMode: string, proxy: string | null) =>
    invoke<MirrorTestResult>("test_proxy", { proxyMode, proxy }),
  autoDetectMemory: () =>
    invoke<{ total_mb: number; used_mb: number; available_mb: number; max_mb: number; min_mb: number }>("auto_detect_memory"),

  // versions
  getVersionManifest: () =>
    invoke<{
      versions: { id: string; type: string; releaseTime: string }[];
      latest: { release: string; snapshot: string };
    }>("get_version_manifest"),
  getLoaderVersions: (loader: string, mc_version: string) =>
    invoke<string[]>("get_loader_versions", { loader, mcVersion: mc_version }),

  // instances
  listInstances: () => invoke<Instance[]>("list_instances"),
  getInstance: (id: string) => invoke<Instance>("get_instance_info", { id }),
  createInstance: (name: string, mc_version: string, loader: string, loader_version: string | null) =>
    invoke<Instance>("create_instance", { name, mcVersion: mc_version, loader, loaderVersion: loader_version }),
  updateInstance: (patch: Record<string, unknown>) => invoke<Instance>("update_instance_settings", { patch }),
  deleteInstance: (id: string) => invoke<void>("delete_instance", { id }),

  // instance groups
  listGroups: () => invoke<InstanceGroup[]>("list_instance_groups"),
  createGroup: (name: string, color?: string | null) =>
    invoke<InstanceGroup>("create_instance_group", { name, color: color ?? null }),
  renameGroup: (id: string, name: string, color?: string | null) =>
    invoke<InstanceGroup>("rename_instance_group", { id, name, color: color ?? null }),
  deleteGroup: (id: string) => invoke<void>("delete_instance_group", { id }),
  reorderGroups: (ids: string[]) => invoke<InstanceGroup[]>("reorder_instance_groups", { ids }),
  installGame: (instanceId: string) =>
    invoke<{ instance_id: string; total_bytes: number; file_count: number }>("install_game", { instanceId }, { silent: true }),

  /**
   * 诊断实例缺哪些游戏文件（**只读，不下载**）。
   *
   * 检查 version.json / libraries / assets / 加载器本体 / Java 运行时共 5 项。
   * `missing[].fixable === false` 的项（例如加载器版本号为空）补全也救不回来，
   * 这种情况要在界面上说清楚「得换游戏版本」，而不是给一个点了没用的按钮。
   */
  checkInstanceFiles: (instanceId: string) =>
    invoke<InstanceFileReport>("check_instance_files", { instanceId }),

  /**
   * 补全缺失的游戏文件。
   *
   * 后端直接复用 `install_game`（逐文件校验、缺的才下），所以重复点也安全；
   * 下载进度走「下载中心」的任务卡片，不在这里另做一套进度 UI。
   */
  repairInstanceFiles: (instanceId: string) =>
    invoke<{ instance_id: string; total_bytes: number; file_count: number }>(
      "repair_instance_files",
      { instanceId },
      { silent: true }
    ),

  /**
   * 取消进行中的安装/下载任务。
   *
   * `taskId` 就是「下载中心」里那张卡片的 id（后端 `TaskCtx.task_id`）。
   * 以前 `cancel_install` 被列在 UNIMPLEMENTED 里，前端点了不会有任何后端动作。
   */
  cancelInstall: (taskId: number) => invoke<void>("cancel_install", { taskId }),

  /**
   * 读取系统剪贴板（代码编辑器「粘贴」用）。
   *
   * 安卓 WebView 拿不到 `clipboard-read` 权限，`execCommand("paste")` 也必然失败，
   * 只能由原生 ClipboardManager 读。
   */
  readClipboard: () => invoke<string>("read_clipboard"),

  /** 写入系统剪贴板（同上，WebView 没有 clipboard-write 权限）。 */
  writeClipboard: (text: string) => invoke<void>("write_clipboard", { text }),
  launchInstance: (instanceId: string, world?: string, server?: string) =>
    invoke<{ pid: number; command: string[] }>(
      "launch_instance",
      {
        instanceId,
        world: world ?? null,
        server: server ?? null,
      },
      { silent: true }
    ),
  stopGame: () => invoke<void>("stop_game"),
  isGameRunning: () => invoke<boolean>("is_game_running"),
  listInstanceFolders: (instanceId: string) =>
    invoke<{ folders: { name: string; exists: boolean }[] }>("list_instance_folders", { instanceId }),
  listInstanceFiles: (instanceId: string, sub: string) =>
    invoke<{
      files: {
        name: string;
        size: number;
        modified: number;
        isDir: boolean;
        path: string;
        icon: string | null;
      }[];
    }>("list_instance_files", { instanceId, sub }),
  importModpack: (filePath: string) =>
    invoke<{
      instance: Instance;
      extracted: number;
      downloaded: number;
      failed: number;
      skippedNoUrl: number;
    }>("import_modpack", { filePath }),
  /**
   * 导出前的体检：纯本地统计（不联网），界面据此显示「包里有什么 / 大概多大」。
   * `linked` = 只存下载链接的项目数，`bundled` = 必须打进包的部分。
   */
  planModpackExport: (instanceId: string) =>
    invoke<{
      fileName: string;
      mcVersion: string;
      loader: string;
      linked: number;
      bundled: { label: string; count: number; bytes: number }[];
      bundledBytes: number;
      hasSaves: boolean;
      hasScreenshots: boolean;
      hasConfig: boolean;
    }>("plan_modpack_export", { instanceId }),
  /** 导出成 .mrpack 并调起系统分享（后端把产物写到 `<files>/exports/`） */
  exportModpack: (
    instanceId: string,
    options: { includeSaves: boolean; includeScreenshots: boolean; includeConfig: boolean },
  ) =>
    invoke<{ path: string; fileName: string; linked: number; bundled: number; bytes: number }>(
      "export_modpack",
      { instanceId, options },
    ),

  /**
   * 服务端插件：数据来自 PaperMC 的插件平台 Hangar（公开接口，无需 API key）。
   * 安装由后端按「服务器的 MC 版本」挑兼容版本，直接落到 `<服务器>/plugins/`。
   */
  hangarSearchPlugins: (query: string, page = 0, pageSize = 20) =>
    invoke<
      {
        slug: string;
        owner: string;
        name: string;
        description: string;
        downloads: number;
        icon: string | null;
        versions: string[];
      }[]
    >("hangar_search_plugins", { query, page, pageSize }),
  /**
   * 把插件装到服务器的 `plugins/`。
   *
   * `installed: false` + `externalUrl` = 该插件**没托管在 Hangar**（只有外部下载页，
   * 例如 EssentialsX 指向 GitHub releases）：这时不是失败，而是要把地址交给用户，
   * 由界面打开浏览器让他自己下（见 `utils/pluginInstall.ts` 的统一处理）。
   */
  installServerPlugin: (serverId: string, slug: string, projectName: string) =>
    invoke<{
      installed: boolean;
      externalUrl?: string | null;
      fileName?: string;
      version?: string;
      compatVerified?: boolean;
    }>("install_server_plugin", { serverId, slug, projectName }),
  listServerPlugins: (serverId: string) =>
    invoke<{ fileName: string; size: number }[]>("list_server_plugins", { serverId }),
  deleteServerPlugin: (serverId: string, fileName: string) =>
    invoke<void>("delete_server_plugin", { serverId, fileName }),

  // ── FRP 隧道（内网穿透）──────────────────────────────────────────────
  // 只做三件事：设备码登录 → 用户信息 / 隧道列表 → 启停隧道进程。
  // 建隧道与联机房间都在面板网页上做。`device_code` 留在后端，前端只会看到用户码。
  frpStatus: () =>
    invoke<{
      apiBase: string;
      loggedIn: boolean;
      username: string;
      expiresAt: number;
      frpc: { ready: boolean; detail: string };
    }>("frp_status", undefined, { silent: true }),
  frpSetApiBase: (apiBase: string) => invoke<void>("frp_set_api_base", { apiBase }),
  frpLoginStart: () =>
    invoke<{ session: string; userCode: string; verificationUri: string; expiresIn: number; interval: number }>(
      "frp_login_start",
    ),
  /** 一次轮询（后端会先等 interval 秒）；`status` 为 pending 时继续轮 */
  frpLoginPoll: (session: string) =>
    invoke<{ status: "pending" | "authorized" | "expired" | "denied"; username?: string; message?: string }>(
      "frp_login_poll",
      { session },
    ),
  frpLogout: () => invoke<void>("frp_logout"),
  frpUserInfo: () =>
    invoke<Record<string, unknown>>("frp_user_info", undefined, { silent: true }),
  frpTunnels: () =>
    invoke<
      {
        id: number;
        name: string;
        protocol: string;
        localIp: string;
        localPort: number;
        remotePort: number;
        customDomain: string;
        status: string;
        nodeName: string;
        nodeHostname: string;
        nodePort: number;
        bandwidthLimit: number;
        expireTime: number;
        packageName: string;
        address: string;
      }[]
    >("frp_tunnels", undefined, { silent: true }),
  frpStartTunnel: (tunnelId: number) =>
    invoke<{ name: string; address: string; alreadyRunning: boolean }>("frp_start_tunnel", { tunnelId }),
  frpStopTunnel: (tunnelId: number) => invoke<void>("frp_stop_tunnel", { tunnelId }),
  frpRunning: () =>
    invoke<
      {
        tunnelId: number;
        name: string;
        address: string;
        startedAt: number;
        alive: boolean;
        exit?: string;
        logTail?: string;
      }[]
    >("frp_running", undefined, { silent: true }),
  frpTunnelLog: (tunnelId: number) => invoke<string>("frp_tunnel_log", { tunnelId }),
  /** 自检：跑一次随包 frpc 的 --version（同时验证设备是否允许执行 APK 里的二进制） */
  frpProbe: () => invoke<string>("frp_probe"),

  // ── XTCP 联机房间 ────────────────────────────────────────────────────
  // 房间是「房主-访问者」模式：**双方都要跑 frpc**（和上面 TCP 直连不同 ——
  // 那种只有房主跑，客人用任意客户端连 `节点:远端端口`）。
  // 房主跑一条 xtcp 代理把本地端口暴露出去，访问者跑一条 xtcp 访问器绑到本地端口。
  /** 建房可选的节点 */
  frpRoomNodes: () =>
    invoke<{ id: number; name: string; description: string; hostname: string; online: boolean }[]>(
      "frp_room_nodes",
      undefined,
      { silent: true },
    ),
  frpRoomList: (page = 1) =>
    invoke<{
      rooms: {
        id: number;
        name: string;
        gameName: string;
        status: string;
        maxPlayers: number;
        hostUid: number;
        hostName: string;
        hasPassword: boolean;
        description: string;
        /** `pending` = 审核中，别人搜不到 */
        reviewStatus: string;
        reviewReason?: string | null;
        createdAt: string;
        playerCount: number;
        isHost: boolean;
      }[];
      total: number;
      page: number;
      pageSize: number;
    }>("frp_room_list", { page }, { silent: true }),
  frpRoomDetail: (roomId: number) =>
    invoke<Record<string, unknown>>("frp_room_detail", { roomId }, { silent: true }),
  /** 建房；面板会返回房主端 frpc 配置与审核状态 */
  frpRoomCreate: (name: string, nodeId: number, localPort: number, password?: string) =>
    invoke<{ room_id?: number; tunnel_id?: number; review_status?: string; host_config?: string }>(
      "frp_room_create",
      { name, nodeId, localPort, password: password ?? null },
    ),
  frpRoomJoin: (roomId: number, password?: string) =>
    invoke<Record<string, unknown>>("frp_room_join", { roomId, password: password ?? null }),
  frpRoomLeave: (roomId: number) => invoke<Record<string, unknown>>("frp_room_leave", { roomId }),
  frpRoomClose: (roomId: number) => invoke<Record<string, unknown>>("frp_room_close", { roomId }),
  /** 房主端：起 frpc 暴露本地端口 */
  frpRoomHostStart: (roomId: number) =>
    invoke<{ roomId: number; tunnelId: number; name: string; local: string }>(
      "frp_room_host_start",
      { roomId },
    ),
  /** 访问端：起 frpc 把房主的代理绑到本地端口；把 `connect` 给用户去连 */
  frpRoomVisitorStart: (roomId: number) =>
    invoke<{ roomId: number; tunnelId: number; bindPort: number; connect: string }>(
      "frp_room_visitor_start",
      { roomId },
    ),
  frpRoomStop: (roomId: number) =>
    invoke<{ roomId: number; tunnelId: number }>("frp_room_stop", { roomId }),

  // ── 共享游戏文件（版本 / 库 / 素材 / Java）───────────────────────────
  // 游戏本体是**全局共享**的，不在实例目录里 —— 所以「文件」页只看得到 instance.json，
  // 而删实例也不会回收它。下面三个接口分别用来：显示占用、算能不能清、清掉。
  /** 这套文件占了多少（版本目录与 Java 可归属到实例；库/素材是共享池，只报总量） */
  sharedGameUsage: (instanceId: string) =>
    invoke<{
      mcVersion: string;
      versionBytes: number;
      javaVersion: number;
      javaBytes: number;
      librariesBytes: number;
      assetsBytes: number;
      instancesTotal: number;
    }>("shared_game_usage", { instanceId }, { silent: true }),
  /** 删实例前算：这套文件还有没有别的实例在用，能不能顺带清 */
  planInstanceCleanup: (instanceId: string) =>
    invoke<{
      mcVersion: string;
      javaVersion: number;
      versionBytes: number;
      javaBytes: number;
      totalBytes: number;
      othersSameVersion: number;
      othersSameJava: number;
      canCleanVersion: boolean;
      canCleanJava: boolean;
    }>("plan_instance_cleanup", { instanceId }, { silent: true }),
  /** 清掉该实例独占的共享文件，返回释放的字节数 */
  cleanupInstanceShared: (instanceId: string) =>
    invoke<{ freedBytes: number }>("cleanup_instance_shared", { instanceId }),
  importInstanceImage: (sourcePath: string) => invoke<string>("import_instance_image", { sourcePath }),
  importBackgroundImage: (sourcePath: string) => invoke<string>("import_background_image", { sourcePath }),
  scanMinecraftImport: (source: string) => invoke<void>("scan_minecraft_import", { source }),
  importInstancePack: (filePath: string) =>
    invoke<{ instance: Instance; pendingDownloads: number }>("import_instance_pack", { filePath }),
  playtimeStats: () => invoke<PlaytimeStats>("playtime_stats"),
  // 读磁盘上的实例日志（内存里的实时日志会随崩溃一起消失，日志页需要这个回退）
  readInstanceLog: (instanceId: string) => invoke<string>("read_instance_log", { instanceId }),
  // 游戏内设置（Pojav 控制层）：存在安卓 SharedPreferences 里，经原生桥读写
  getPojavPrefs: () => invoke<Record<string, unknown>>("get_pojav_prefs"),
  setPojavPrefs: (patch: Record<string, unknown>) => invoke<void>("set_pojav_prefs", { patch }),
  logDebug: (msg: string) => invoke<void>("log_debug", { msg }, { silent: true }),
  listWorldBackups: (instanceId: string, world: string) =>
    invoke<WorldBackupInfo[]>("list_world_backups", { instanceId, world }),
  createWorldBackup: (instanceId: string, world: string) =>
    invoke<WorldBackupInfo>("create_world_backup", { instanceId, world }),
  restoreWorldBackup: (instanceId: string, world: string, filename: string) =>
    invoke<void>("restore_world_backup", { instanceId, world, filename }),
  /** 渲染器健康检查：有问题时返回建议切换的信息，否则 null */
  checkRendererHealth: (instanceId: string) =>
    invoke<RendererIssue | null>("check_renderer_health", { instanceId }),
  /** 同上，对象是「最近一次启动过的实例」——玩家回到启动器时用（可能没有退出事件） */
  checkRendererHealthLatest: () =>
    invoke<RendererIssue | null>("check_renderer_health_latest"),
  deleteWorldBackup: (instanceId: string, world: string, filename: string) =>
    invoke<void>("delete_world_backup", { instanceId, world, filename }),
  estimateDownload: (mcVersion: string) =>
    invoke<{
      download_files: number;
      download_bytes: number;
      assets_known: boolean;
    }>("estimate_download", { mcVersion }),
  estimateImport: (source: string, rawIds: string[]) =>
    invoke<{
      import_files: number;
      import_bytes: number;
    }>("estimate_import", { source, rawIds }),
  importMinecraftFolder: (
    source: string,
    name: string,
    rawIds: string[],
    mcVersions: string[],
    loaders: string[],
    loaderVersions: (string | null)[],
    mode: "copy" | "symlink"
  ) =>
    invoke<{
      instance_id: string;
      total_bytes: number;
      file_count: number;
      symlink_fallback?: boolean;
    }[]>("import_minecraft_folder", {
      source,
      name,
      rawIds,
      mcVersions,
      loaders,
      loaderVersions,
      mode,
    }),

  // accounts
  listAccounts: () => invoke<Account[]>("list_accounts"),
  loginOffline: (username: string) => invoke<Account>("login_offline", { username }),
  loginMsStart: () => invoke<{ userCode: string; verificationUri: string; expiresIn: number }>("login_ms_start"),
  loginMsPoll: () => invoke<Account>("login_ms_poll"),
  logoutAccount: (uuid: string) => invoke<void>("logout_account", { uuid }),

  // browse & content
  browse: (
    provider: string,
    query: string,
    projectType: string,
    category: string,
    page: number,
    gameVersion?: string,
    loader?: string,
    sort?: string,
    pageSize?: number
  ) =>
    invoke<{ hits: ProjectHit[]; total: number; cf_error?: string | null; cf_count?: number }>("browse", {
      provider,
      query,
      projectType,
      category,
      page,
      gameVersion: gameVersion ?? "",
      loader: loader ?? "",
      sort: sort ?? "downloads",
      pageSize: pageSize ?? 20,
    }),
  projectVersions: (provider: string, projectId: string, mcVersion: string, loader: string) =>
    invoke<{ provider: string; versions: ProjectVersion[] }>("project_versions", {
      provider,
      projectId,
      mcVersion,
      loader,
    }),
  projectDependencies: (provider: string, projectId: string, versionId: string) =>
    invoke<ProjectDependency[]>(
      "project_dependencies",
      {
        provider,
        projectId,
        versionId,
      },
      { silent: true }
    ),
  mcWikiUrl: (name: string, slug?: string, provider?: string) =>
    invoke<string>("mc_wiki_url", { name, slug, provider }, { silent: true }),
  curseforgeCategories: (projectType: string) =>
    invoke<{ categories: { id: number; name: string }[] }>("curseforge_categories", { projectType }),
  projectInfo: (provider: string, projectId: string) =>
    invoke<ProjectHit>("project_info", { provider, projectId }),

  // ---- 内容中心翻译 ----
  translateModDescriptions: (provider: string, slugs: string[]) =>
    invoke<{
      translations: Record<string, string>;
      failed: string[];
      rateLimited: boolean;
      error?: string | null;
    }>("translate_mod_descriptions", { provider, slugs }),
  translateProjectBody: (provider: string, slug: string, translate: boolean) =>
    invoke<{
      body: string | null;
      bodyCached: boolean;
      original: string;
      supported: boolean;
      error?: string | null;
    }>("translate_project_body", { provider, slug, translate }),
  reportStaleTranslation: (provider: string, slug: string) =>
    invoke<string>("report_translation_stale", { provider, slug }),
  reportTranslationQuality: (
    provider: string,
    slug: string,
    issueType: string,
    userSuggestion?: string,
    userComment?: string
  ) =>
    invoke<string>("report_translation_quality", {
      provider,
      slug,
      issueType,
      userSuggestion: userSuggestion ?? null,
      userComment: userComment ?? null,
    }),
  /** service 不传 = 全部清空，返回释放的字节数 */
  clearTranslationCache: (service?: "default" | "custom") =>
    invoke<number>("clear_translation_cache", { service: service ?? null }),
  testTranslateApi: (base: string, key: string, model: string) =>
    invoke<void>("test_translate_api", { base, key, model }),
  installContent: (
    instanceId: string,
    provider: string,
    projectId: string,
    versionId: string,
    kind: string
  ) =>
    invoke<{ ok: boolean; filename?: string; mods?: number }>(
      "install_content",
      {
        instanceId,
        provider,
        projectId,
        versionId,
        kind,
      },
      { silent: true }
    ),
  checkUpdates: (instanceId: string, kind: string) =>
    invoke<UpdateInfo[]>("check_updates", { instanceId, kind }),
  applyUpdate: (
    instanceId: string,
    kind: string,
    oldFilename: string,
    provider: string,
    projectId: string,
    newVersionId: string
  ) =>
    invoke<{ ok: boolean; filename?: string }>(
      "apply_update",
      {
        instanceId,
        kind,
        oldFilename,
        provider,
        projectId,
        newVersionId,
      },
      { silent: true }
    ),
  uninstallContent: (instanceId: string, kind: string, filename: string) =>
    invoke<void>("uninstall_content", { instanceId, kind, filename }),
  listContent: (instanceId: string, kind: string) =>
    invoke<{ items: ContentItem[]; onDisk: string[] }>("list_content", { instanceId, kind }),
  identifyContent: (instanceId: string, kind: string) =>
    invoke<void>("identify_content", { instanceId, kind }, { silent: true }),
  toggleContentEnabled: (instanceId: string, kind: string, filename: string, enabled: boolean) =>
    invoke<void>("toggle_content_enabled", { instanceId, kind, filename, enabled }),
  importLocalFile: (instanceId: string, kind: string, sourcePath: string) =>
    invoke<{ ok: boolean }>("import_local_file", { instanceId, kind, sourcePath }),
  saveTextFile: (path: string, content: string) => invoke<void>("save_text_file", { path, content }),

  // instance file manager
  listInstanceDir: (instanceId: string, rel: string) =>
    invoke<{ rel: string; entries: FsEntry[] }>("list_instance_dir", { instanceId, rel }, { silent: true }),
  readInstanceFile: (instanceId: string, rel: string) =>
    invoke<{ rel: string; content: string; size: number; modified: number }>(
      "read_instance_file",
      { instanceId, rel },
      { silent: true }
    ),
  writeInstanceFile: (instanceId: string, rel: string, content: string) =>
    invoke<{ rel: string; size: number; modified: number }>(
      "write_instance_file",
      { instanceId, rel, content },
      { silent: true }
    ),
  createInstanceEntry: (instanceId: string, rel: string, isDir: boolean) =>
    invoke<{ rel: string; is_dir: boolean }>("create_instance_entry", { instanceId, rel, isDir }),
  deleteInstancePath: (instanceId: string, rel: string) =>
    invoke<void>("delete_instance_path", { instanceId, rel }),
  renameInstancePath: (instanceId: string, rel: string, newName: string) =>
    invoke<{ rel: string; name: string }>("rename_instance_path", { instanceId, rel, newName }),
  extractGameIcons: (instanceId?: string) =>
    invoke<{ name: string; label: string; path: string }[]>("extract_game_icons", {
      instanceId: instanceId ?? null,
    }),

  // skins
  listSkins: () =>
    invoke<{ name: string; filename: string; path: string; size: number; modified: number }[]>("list_skins"),
  readSkinDataUrl: (filename: string) => invoke<string>("read_skin_data_url", { filename }),
  saveSkinFromData: (name: string, data: string) =>
    invoke<{ name: string; filename: string; path: string; size: number; modified: number }>("save_skin_from_data", {
      name,
      data,
    }),
  downloadSkinFromUrl: (name: string, url: string) =>
    invoke<{ name: string; filename: string; path: string; size: number; modified: number }>(
      "download_skin_from_url",
      { name, url },
    ),
  deleteSkin: (filename: string) => invoke<void>("delete_skin", { filename }),
  fetchPlayerSkin: (username: string) =>
    invoke<{ data_url: string; model: string; cape_data_url: string | null }>("fetch_player_skin", { username }),
  fetchImageDataURL: (url: string) => invoke<string>("fetch_image_data_url", { url }),
  fetchPlayerCapes: (accountUuid: string) =>
    invoke<{ id: string; name: string; data_url: string; active: boolean }[]>("fetch_player_capes", {
      accountUuid,
    }),
  applySkinToAccount: (accountUuid: string, skinData: string, variant: string) =>
    invoke<void>("apply_skin_to_account", { accountUuid, skinData, variant }),
  applyCapeToAccount: (accountUuid: string, capeId: string | null) =>
    invoke<void>("apply_cape_to_account", { accountUuid, capeId }),
  applySkinOffline: (skinData: string, variant: string, uuid: string) =>
    invoke<void>("apply_skin_offline", { skinData, variant, uuid }),
  getOfflineSkin: (uuid: string) =>
    invoke<{ src: string; variant: "slim" | "classic" | null } | null>("get_offline_skin", {
      uuid,
    }),

  // multiplayer servers
  listServers: (instanceId: string) =>
    invoke<{ servers: ServerEntry[] }>("list_servers", { instanceId }).then((r) => r.servers),
  pingServer: (address: string) => invoke<ServerStatus>("ping_mc_server", { address }),

  // hosted game servers
  listHostedServers: () => invoke<ServerConfig[]>("list_hosted_servers"),
  // Paper 支持的 MC 版本（新→旧）：创建服务器时按核心限制版本下拉。
  // Paper 并不支持所有版本 —— 1.8 那条线只有 1.8.8，选 1.8.9 装核心会 404。
  listPaperVersions: () => invoke<string[]>("list_paper_versions"),
  getHostedServer: (id: string) => invoke<ServerConfig>("get_hosted_server", { id }),
  createHostedServer: (name: string, core: string, mcVersion: string) =>
    invoke<ServerConfig>("create_hosted_server", { name, core, mcVersion }),
  updateHostedServer: (patch: Record<string, unknown>) =>
    invoke<ServerConfig>("update_hosted_server", { patch }),
  deleteHostedServer: (id: string) => invoke<void>("delete_hosted_server", { id }),
  installHostedServerCore: (id: string) =>
    invoke<void>("install_hosted_server_core", { id }, { silent: true }),
  startHostedServer: (id: string) =>
    invoke<{ pid: number }>("start_hosted_server", { id }, { silent: true }),
    /** 停止服务器；返回一句给用户看的结果（已优雅停服 / 被强制停止） */
    stopHostedServer: (id: string) => invoke<string>("stop_hosted_server", { id }),
  isHostedServerRunning: (id: string) => invoke<boolean>("is_hosted_server_running", { id }),
  readHostedServerLog: (id: string) => invoke<string[]>("read_hosted_server_log", { id }),
    /** 联机地址（WiFi IP:端口）；未连 WiFi 时为 null */
    hostedServerAddress: (id: string) =>
        invoke<string | null>("hosted_server_address", { id }, { silent: true }),
      /** 设备当前可用内存（MB）：用来提示「服务端内存最多会被用到多少」 */
        deviceAvailableMemory: () =>
          invoke<number | null>("device_available_memory_mb", undefined, { silent: true }),
        /** 电池优化是否已放行；false = 后台服务可能被 ROM 省电策略掐掉 */
        batteryUnrestricted: () => invoke<boolean>("battery_unrestricted", undefined, { silent: true }),
        /** 弹系统「忽略电池优化」请求页（用户点「允许」后长期有效） */
        requestBatteryUnrestricted: () =>
          invoke<void>("request_battery_unrestricted", undefined, { silent: true }),
        /** 设备热状态（0 正常 … 2 中等 … 4 危急 … 6 关机）：服务器变卡可能是手机过热降频 */
        deviceThermalStatus: () =>
          invoke<number>("device_thermal_status", undefined, { silent: true }),
    /** 执行一条服务端控制台命令（RCON），返回服务端输出 */
    serverConsoleCommand: (id: string, command: string) =>
      invoke<string>("server_console_command", { id, command }),
    /** 核心是否已就绪（能否启动） */
    hostedServerCoreInstalled: (id: string) =>
      invoke<boolean>("hosted_server_core_installed", { id }, { silent: true }),
    /**
     * 把服务器文件目录交给系统文件管理器打开（往服务器里拷插件/存档用）。
     * 返回 1 = 已交给外部应用；0 = 本机没有能打开文件夹的应用；-1 = 目录不存在。
     */
    openHostedServerDirectory: (id: string) =>
      invoke<number>("open_hosted_server_directory", { id }, { silent: true }),
    /**
     * 运行态（含空闲休眠）。休眠时 `running=false` 但 `sleeping=true` ——
     * 端口仍被唤醒监听占着，有人连接会自动启动，界面要能区分它和「用户停掉的」。
     */
    hostedServerRuntime: (id: string) =>
      invoke<ServerRuntime | null>("hosted_server_runtime", { id }, { silent: true }),
    /** 读 server.properties 里可编辑的那几项（后端白名单） */
    getHostedServerProperties: (id: string) =>
      invoke<ServerProperties>("get_hosted_server_properties", { id }, { silent: true }),
    /** 写那几项；返回写入后的完整值，直接拿来刷新界面 */
    setHostedServerProperties: (id: string, patch: Record<string, unknown>) =>
      invoke<ServerProperties>("set_hosted_server_properties", { id, patch }),
  openHostedServerFolder: (id: string, sub?: string) =>
    invoke<void>("open_hosted_server_folder", { id, sub: sub ?? null }),
  // 这里原来还有 listHostedServerFolders / listHostedServerFiles 两个方法，它们 invoke 的
  // `list_hosted_server_folders` / `list_hosted_server_files` 后端**根本没注册**
  // （`lib.rs` 的 generate_handler! 里只有 `list_hosted_server_dir`）—— 一旦被调用就是
  // 「command not found」。而全仓没有任何调用者：服务器文件浏览走的正是下面这个
  // `listHostedServerDir`。所以直接删除，免得以后有人顺手用它们踩雷。
  listHostedServerDir: (id: string, rel: string) =>
    invoke<{ rel: string; entries: FsEntry[] }>("list_hosted_server_dir", { id, rel }),
  readHostedServerFile: (id: string, rel: string) =>
    invoke<{ rel: string; content: string; size: number; modified: number }>(
      "read_hosted_server_file",
      { id, rel },
      { silent: true }
    ),
  writeHostedServerFile: (id: string, rel: string, content: string) =>
    invoke<{ rel: string; size: number; modified: number }>(
      "write_hosted_server_file",
      { id, rel, content },
      { silent: true }
    ),
  listHostedServerConfigFiles: (id: string) =>
    invoke<{ name: string; rel: string; size: number; modified: number }[]>(
      "list_hosted_server_config_files",
      { id },
    ),

  // terracotta (陶瓦联机)
  terracottaDetect: () => invoke<TerracottaInfo>("terracotta_detect", undefined, { silent: true }),
  terracottaDownload: () => invoke<string>("terracotta_download", undefined, { silent: true }),
  terracottaLaunch: () => invoke<TerracottaLaunch>("terracotta_launch", undefined, { silent: true }),
  terracottaStop: () => invoke<void>("terracotta_stop", undefined, { silent: true }),
  terracottaStatus: () => invoke<Record<string, unknown>>("terracotta_status", undefined, { silent: true }),

  // ── 陶瓦联机隧道（Android 专用，走独立进程 :tunnel 的本地 HTTP）──────────
  // 为什么不复用上面那三个：那是桌面版「启动 Terracotta.exe 外部进程」的接口，
  // Android 上我们把 .so 放进 :tunnel 进程，主进程只发 HTTP（见 src-tauri/src/terracotta.rs）。
  /** 隧道是否已就绪 */
  terracottaTunnelPing: () => invoke<boolean>("terracotta_ping", undefined, { silent: true }),
  /** 调隧道接口，返回原始 JSON 字符串。path 形如 /state、/host?player=xxx */
  terracottaTunnelRequest: (path: string) => invoke<string>("terracotta_request", { path }, { silent: true }),
  /** 是否已获得陶瓦联机的 VPN 授权（没有 TUN 就没有数据面，客人连不进来） */
  terracottaVpnGranted: () => invoke<boolean>("terracotta_vpn_granted", undefined, { silent: true }),
  /** 弹 VPN 授权对话框（开房前先调用） */
  terracottaRequestVpn: () => invoke<void>("terracotta_request_vpn", undefined, { silent: true }),
  terracottaCreateRoom: (player?: string) =>
    invoke<Record<string, unknown>>("terracotta_create_room", { player: player ?? null }, { silent: true }),
  terracottaJoinRoom: (room: string, player?: string) =>
    invoke<Record<string, unknown>>("terracotta_join_room", { room, player: player ?? null }, { silent: true }),
  terracottaLeave: () => invoke<Record<string, unknown>>("terracotta_leave", undefined, { silent: true }),

  // ── 控制布局：打开原生编辑器 / 导入 / 导出 ──────────────────────────
  /** 打开原生控制布局编辑器（横屏 + 游戏主题，和游戏里那个界面一致）。空 layout = 当前默认 */
  openControlLayoutEditor: (layout?: string, preview?: boolean, saveAs?: string) =>
    invoke<void>(
      "open_control_layout_editor",
      { layout: layout ?? null, preview: !!preview, saveAs: saveAs ?? null },
      { silent: true },
    ),
  /** 导出（系统分享）一份布局 */
  exportControlLayout: (name: string) => invoke<void>("export_control_layout", { name }),
  /** 弹系统文件选择器导入布局（结果异步，用 takeControlImport 取） */
  pickControlLayout: () => invoke<void>("pick_control_layout", undefined, { silent: true }),
  /** 取回导入结果：`{ok, buttons, joysticks, drawers, error}`，没有则是 null */
  takeControlImport: () => invoke<ControlImportResult | null>("take_control_import", undefined, { silent: true }),
  /** 按文件路径导入布局（不经 SAF —— SAF 打不开 Android/data，MT 能拿到那些路径） */
  importControlLayoutByPath: (path: string) =>
    invoke<ControlImportResult>("import_control_layout_by_path", { path }),

  /** 导出（分享）实例日志：打 zip 后走系统分享 */
  exportInstanceLogs: (id: string) => invoke<void>("export_instance_logs", { instanceId: id }),

  /**
   * 告诉原生「日志页在前台」，之后音量- / 音量+ 会被转成 `qk-log-zoom` 事件派给 WebView。
   * 离开日志页必须关掉，否则游戏里音量键会失效。
   */
  setLogZoomCapture: (on: boolean) => invoke<void>("set_log_zoom_capture", { on }, { silent: true }),

  // storage
  getStorageStats: () => invoke<StorageStats>("get_storage_stats"),
  refreshStorageStats: () => invoke<StorageStats>("refresh_storage_stats"),
  clearCache: () => invoke<CacheClearResult>("clear_cache"),

  // 游戏目录（内部 / 应用专属外部 / 自定义）
  getGameDirState: () => invoke<GameDirState>("get_game_dir_state"),
  getGameDirOptions: () => invoke<GameDirOption[]>("get_game_dir_options"),
  requestAllFilesAccess: () => invoke<void>("request_all_files_access", undefined, { silent: true }),
  pickGameDir: () => invoke<void>("pick_game_dir", undefined, { silent: true }),
  takePickedGameDir: () => invoke<string | null>("take_picked_game_dir", undefined, { silent: true }),
  setGameDir: (root: string | null, migrate: boolean) =>
    invoke<GameDirState>("set_game_dir", { root, migrate }),

  // crash analysis
  crashAnalysis: (instanceId: string) =>
    invoke<{ filename: string; modified: number; size: number; kind: string }[]>("list_crash_logs", { id: instanceId }),
  analyzeCrash: (instanceId: string, filename: string) =>
    invoke<CrashDiagnosis>("analyze_crash_log", { id: instanceId, filename }),
  getCrashReportContent: (instanceId: string, filename: string) =>
    invoke<string>("get_crash_report_content", { id: instanceId, filename }),

  // news
  fetchNews: () => invoke<NewsItem[]>("fetch_news"),

  // native (Android 屏幕方向 / 文件选择结果落地)
  /** 设置启动器界面方向：system | portrait | landscape */
  setOrientation: (mode: string) =>
    invoke<void>("set_orientation", { mode }, { silent: true }),
  /**
   * 安卓文件选择器返回的是 `content://` URI，后端只认普通路径。
   * 这里先把它落地到应用私有目录再返回真实路径；桌面端原样返回。
   */
  resolvePickedPath: (path: string) =>
    invoke<string>("resolve_picked_path", { path }, { silent: true }),
  /** 检测系统/VPN 下发的 HTTP 代理（无代理返回 null） */
  detectSystemProxy: () =>
    invoke<string | null>("detect_system_proxy", undefined, { silent: true }),
};
