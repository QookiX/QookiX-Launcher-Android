use serde::{Deserialize, Serialize};

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct MinecraftProfile {
    pub id: String,
    pub name: String,
    #[serde(alias = "mcVersion")]
    pub mc_version: String,
    pub loader: Loader,
    #[serde(alias = "loaderVersion")]
    pub loader_version: Option<String>,
    /// 实例别名：用于 `qookix://launch/<别名>` 协议启动（小写、全局唯一、无空格）。
    /// 与桌面版同名字段对齐；`default` 让旧实例文件（没有这个键）照常读。
    #[serde(default)]
    pub alias: Option<String>,
    pub created: i64,
    #[serde(alias = "lastPlayed")]
    pub last_played: Option<i64>,
    #[serde(alias = "totalPlayTime")]
    pub total_play_time: i64,
    #[serde(alias = "gameDir")]
    pub game_dir: String,
    /// 序列化为前端字段名 `java_path`；读取时兼容旧文件里的 `java_dir`。
    #[serde(rename = "java_path", alias = "java_dir", alias = "javaDir")]
    pub java_dir: String,
    /// 序列化为前端字段名 `jvm_args`；读取时兼容旧文件里的 `java_args`。
    #[serde(rename = "jvm_args", alias = "java_args", alias = "javaArgs")]
    pub java_args: Option<String>,
    #[serde(alias = "gameArgs")]
    pub game_args: Option<String>,
    pub resolution: Option<(i32, i32)>,
    #[serde(alias = "maxMemoryMb")]
    pub max_memory_mb: Option<i32>,
    #[serde(alias = "memoryMode")]
    pub memory_mode: Option<String>,
    #[serde(alias = "accountId")]
    pub account_id: Option<String>,
    pub icon: Option<String>,
    pub mods: Vec<InstalledContent>,
    #[serde(alias = "resourcePacks")]
    pub resource_packs: Vec<InstalledContent>,
    pub shaders: Vec<InstalledContent>,
    pub group: Option<String>,
    #[serde(alias = "isSymlink")]
    pub is_symlink: Option<bool>,
    #[serde(alias = "sourcePath")]
    pub source_path: Option<String>,
    /// 游戏本体是否已下载（前端据此显示「未安装」横幅 / 安装按钮）。
    /// 旧实例文件没有该字段时按 false 处理，等待用户点一次「安装游戏」。
    #[serde(default)]
    pub installed: bool,
    /// 本实例使用的渲染器，见 `launch::resolve_renderer`：
    /// `auto`（**缺省**，按 MC 版本自动挑）/ `global`（跟随全局设置）
    /// / `opengles2`（GL4ES）| `mobileglues` | `vulkan_zink`（显式指定）。
    /// 旧实例文件没有该字段 → None，与 `auto` 等价。
    #[serde(default)]
    pub renderer: Option<String>,
    /// 启动前是否检查游戏文件完整性、缺了自动补全再启动。
    /// `None`（旧实例文件）与 `Some(true)` 都视为**开启**，只有显式 `Some(false)` 关闭。
    #[serde(default, alias = "checkFilesOnLaunch")]
    pub check_files_on_launch: Option<bool>,
}

/// 渲染器健康检查的结论（见 `renderer_health::check`）。
///
/// 前端在用户从游戏回到启动器后弹窗：「当前渲染器在日志里有异常证据，且不是本版本的
/// 推荐渲染器，要不要把这个**实例**切过去」。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct RendererIssue {
    pub instance_id: String,
    /// 本次实际使用的渲染器键：`opengles2` | `mobileglues` | `vulkan_zink`
    pub used: String,
    pub used_name: String,
    /// 按 MC 版本推荐的渲染器键
    pub recommended: String,
    pub recommended_name: String,
    /// 一句人话：日志里发现了什么
    pub reason: String,
    /// 日志证据（最多几条，已经去重并截断）
    pub evidence: Vec<String>,
    /// 该实例的 MC 版本，弹窗文案里用
    pub mc_version: String,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "lowercase")]
pub enum Loader {
    /// 旧 instance.json 里写的是 `"Vanilla"`（本枚举曾漏掉 `rename_all`）——
    /// 每个变体都补 `alias` 才能在升级后继续读出来，否则 serde 报 unknown variant、
    /// 整个实例加载失败。序列化一律输出小写，与前端 `types.ts` 的 `Loader` 对齐。
    #[serde(alias = "Vanilla")]
    Vanilla,
    #[serde(alias = "Fabric")]
    Fabric,
    #[serde(alias = "Quilt")]
    Quilt,
    #[serde(alias = "Forge")]
    Forge,
    #[serde(alias = "NeoForge")]
    NeoForge,
}

/// 已安装内容（mod / 资源包 / 光影）的记录，字段与前端 `InstalledContent` 对齐。
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct InstalledContent {
    pub filename: String,
    /// "modrinth" | "curseforge" | "manual"
    pub source: String,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub slug: Option<String>,
    #[serde(default)]
    pub version_id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub version: Option<String>,
    /// Mod 内部 id（fabric 的 sodium / forge 的 modId）
    #[serde(default)]
    pub mod_id: Option<String>,
    #[serde(default)]
    pub authors: Option<Vec<String>>,
    #[serde(default)]
    pub description: Option<String>,
    pub installed_at: i64,
    pub size: i64,
    #[serde(default)]
    pub icon: Option<String>,
    #[serde(default)]
    pub enabled: bool,
}

impl InstalledContent {
    /// 构造一个"手动导入"的空记录，后续可被识别/填充。
    pub fn manual(filename: impl Into<String>) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs())
            .unwrap_or(0) as i64;
        InstalledContent {
            filename: filename.into(),
            source: "manual".to_string(),
            project_id: None,
            slug: None,
            version_id: None,
            name: None,
            version: None,
            mod_id: None,
            authors: None,
            description: None,
            installed_at: now,
            size: 0,
            icon: None,
            enabled: true,
        }
    }
}

#[derive(Clone, Debug)]
pub struct Account {
    pub uuid: String,
    pub username: String,
    pub created: i64,
    pub account_type: AccountType,
    pub msa_refresh_token: Option<String>,
    pub xuid: Option<String>,
    pub expires_at: Option<i64>,
    pub skin_face_base64: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum AccountType {
    Offline,
    Microsoft,
}

impl AccountType {
    pub fn as_str(&self) -> &'static str {
        match self {
            AccountType::Offline => "offline",
            AccountType::Microsoft => "microsoft",
        }
    }

    /// 兼容旧数据：`Offline` / `offline` / `microsoft` / `Microsoft` 均可解析。
    fn parse(raw: &str) -> AccountType {
        if raw.eq_ignore_ascii_case("microsoft") {
            AccountType::Microsoft
        } else {
            AccountType::Offline
        }
    }
}

/// 线上格式（与前端 `Account` 判别联合一致）：
/// `{"type":"offline"|"microsoft","uuid":..,"username":..,"created":..,"msa_expires_at":..}`
/// 同时兼容旧文件里的 `account_type: "Offline"` 写法。
impl Serialize for Account {
    fn serialize<S: serde::Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        use serde::ser::SerializeMap;
        let mut map = serializer.serialize_map(None)?;
        map.serialize_entry("type", self.account_type.as_str())?;
        map.serialize_entry("uuid", &self.uuid)?;
        map.serialize_entry("username", &self.username)?;
        map.serialize_entry("created", &self.created)?;
        map.serialize_entry("msa_refresh_token", &self.msa_refresh_token)?;
        map.serialize_entry("xuid", &self.xuid)?;
        map.serialize_entry("expires_at", &self.expires_at)?;
        map.serialize_entry("skin_face_base64", &self.skin_face_base64)?;
        if let Some(exp) = self.expires_at {
            map.serialize_entry("msa_expires_at", &exp)?;
        }
        map.end()
    }
}

impl<'de> Deserialize<'de> for Account {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        struct Raw {
            #[serde(default)]
            uuid: String,
            #[serde(default)]
            username: String,
            #[serde(default)]
            created: i64,
            #[serde(default, rename = "type")]
            kind: Option<String>,
            #[serde(default)]
            account_type: Option<String>,
            #[serde(default)]
            msa_refresh_token: Option<String>,
            #[serde(default)]
            xuid: Option<String>,
            #[serde(default)]
            expires_at: Option<i64>,
            #[serde(default)]
            msa_expires_at: Option<i64>,
            #[serde(default)]
            skin_face_base64: Option<String>,
        }
        let raw = Raw::deserialize(deserializer)?;
        let account_type = AccountType::parse(
            raw.kind
                .as_deref()
                .or(raw.account_type.as_deref())
                .unwrap_or("offline"),
        );
        Ok(Account {
            uuid: raw.uuid,
            username: raw.username,
            created: raw.created,
            account_type,
            msa_refresh_token: raw.msa_refresh_token,
            xuid: raw.xuid,
            expires_at: raw.expires_at.or(raw.msa_expires_at),
            skin_face_base64: raw.skin_face_base64,
        })
    }
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Runtime {
    pub name: String,
    pub version_string: String,
    pub java_version: i32,
    pub arch: String,
    pub path: String,
    pub is_internal: bool,
    #[serde(default)]
    pub is_ported: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VersionList {
    pub latest: std::collections::HashMap<String, String>,
    pub versions: Vec<VersionInfo>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct VersionInfo {
    pub id: String,
    pub r#type: String,
    pub url: String,
    pub sha1: Option<String>,
    pub release_time: String,
    pub inherits_from: Option<String>,
    pub java_version: Option<JavaVersionInfo>,
    pub libraries: Vec<DependentLibrary>,
    pub downloads: std::collections::HashMap<String, ClientDownload>,
    pub asset_index: Option<AssetIndex>,
    pub main_class: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct JavaVersionInfo {
    pub component: String,
    pub major_version: i32,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DependentLibrary {
    pub name: String,
    pub downloads: Option<LibraryDownloads>,
    pub rules: Option<Vec<LibraryRule>>,
    pub natives: Option<std::collections::HashMap<String, String>>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct LibraryDownloads {
    pub artifact: Option<LibraryArtifact>,
    pub classifiers: Option<std::collections::HashMap<String, LibraryArtifact>>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct LibraryArtifact {
    pub path: String,
    pub sha1: String,
    pub size: i32,
    pub url: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct LibraryRule {
    pub action: String,
    pub os: Option<OsRule>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct OsRule {
    pub name: String,
    pub version: Option<String>,
    pub arch: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ClientDownload {
    pub path: String,
    pub sha1: String,
    pub size: i32,
    pub url: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct AssetIndex {
    pub id: String,
    pub sha1: String,
    pub size: i32,
    pub total_size: i32,
    pub url: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct Settings {
    pub data_dir: String,
    pub java_path: Option<String>,
    pub max_memory_mb: i32,
    pub min_memory_mb: i32,
    pub memory_mode: String,
    pub jvm_args: String,
    pub game_args: String,
    pub download_threads: i32,
    pub download_chunk_threads: i32,
    pub curseforge_api_key: Option<String>,
    /// 内容描述 / 正文的翻译服务：
    /// "default"（自建翻译服务）| "custom"（自定义 OpenAI 兼容接口）| "baidu_web"（前端开浏览器）。
    #[serde(default = "default_translate_provider")]
    pub translate_provider: String,
    /// 自定义翻译接口的 base 地址（如 https://api.deepseek.com/v1）。
    #[serde(default)]
    pub translate_api_base: String,
    /// 自定义翻译接口的密钥。
    #[serde(default)]
    pub translate_api_key: Option<String>,
    /// 自定义翻译接口使用的模型名。
    #[serde(default)]
    pub translate_api_model: String,
    /// 打开资源详情时自动加载正文译文（默认关）。
    #[serde(default)]
    pub body_translate_auto: bool,
    pub theme: String,
    pub theme_color: String,
    pub close_behavior: String,
    pub auto_launch: bool,
    pub keep_open: bool,
    pub ms_client_id: String,
    pub selected_account: Option<String>,
    pub proxy_mode: String,
    pub proxy: Option<String>,
    pub mirror: String,
    pub mirror_custom: Option<String>,
    pub background_image: Option<String>,
    pub background_blur: i32,
    pub background_dim: i32,
    pub glass_blur: i32,
    /// 启动器界面缩放（百分比，100 = 原始大小）。
    /// 不同机型 CSS 视口差别很大（实测 853×384 与 792×360），
    /// 与其为每台机器写死尺寸，不如让用户自己按舒服的密度调。
    /// `serde(default)`：旧 settings.json 没有这个字段也能正常读。
    #[serde(default = "default_ui_scale")]
    pub ui_scale: i32,
    pub show_sidebar_collapse_btn: bool,
    pub show_news: bool,
    /// 首页分区总开关：有下载任务时的进度卡片 / 最近游玩 / 游玩统计。
    /// 这三个分区本来就只在「有数据」时出现，这些开关让用户能彻底关掉。
    pub show_home_downloads: bool,
    pub show_home_recent: bool,
    pub show_home_stats: bool,
    pub dismissed_update_version: Option<String>,
    pub auto_update: bool,
    pub update_source: String,
    /// 启动器界面方向："system"（跟随系统）| "portrait"（锁定竖屏）| "landscape"（锁定横屏）。
    /// 启动器默认锁定横屏：竖屏下桌面式侧边栏会把内容挤到只剩一条缝。
    #[serde(default = "default_orientation")]
    pub orientation: String,
}

fn default_translate_provider() -> String {
    "default".to_string()
}

fn default_ui_scale() -> i32 {
    100
}

fn default_orientation() -> String {
    "landscape".to_string()
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct DownloadProgress {
    pub task_id: String,
    pub progress: f32,
    pub message: String,
    pub total_bytes: i64,
    pub downloaded_bytes: i64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct GameStatus {
    pub is_running: bool,
    pub instance_id: Option<String>,
    pub pid: Option<u32>,
    pub exit_code: Option<i32>,
    pub error: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CrashReport {
    pub timestamp: i64,
    pub instance_id: String,
    pub error_message: String,
    pub stack_trace: String,
}

// ---------------------------------------------------------------------------
// 固定项（首页 / 侧边栏）
// ---------------------------------------------------------------------------

/// 固定位置：首页快捷卡片 / 侧边栏图标
#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PinItem {
    /// 唯一标识：`instanceId:type:key:target`
    pub id: String,
    /// "server" | "world" | "instance"
    #[serde(rename = "type")]
    pub pin_type: String,
    /// "home" | "sidebar"
    pub target: String,
    #[serde(default)]
    pub instance_id: String,
    #[serde(default)]
    pub instance_name: String,
    #[serde(default)]
    pub instance_icon: Option<String>,
    #[serde(default)]
    pub mc_version: String,
    #[serde(default)]
    pub loader: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub address: Option<String>,
    #[serde(default)]
    pub world: Option<String>,
    #[serde(default)]
    pub icon: Option<String>,
}

// ---------------------------------------------------------------------------
// 实例分组
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct InstanceGroup {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub created: u64,
}

// ---------------------------------------------------------------------------
// 存储统计
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct StorageCategory {
    pub key: String,
    pub label: String,
    pub size: u64,
    pub files: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct InstanceStorage {
    pub id: String,
    pub name: String,
    pub size: u64,
    pub files: u64,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct StorageStats {
    pub categories: Vec<StorageCategory>,
    pub instances: Vec<InstanceStorage>,
    pub servers: Vec<InstanceStorage>,
    pub total: u64,
    pub instance_count: u64,
    pub server_count: u64,
    pub updated_at: u64,
    pub cached: bool,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CacheClearResult {
    pub freed: u64,
}

// ---------------------------------------------------------------------------
// 世界存档备份
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct BackupInfo {
    pub filename: String,
    pub size: u64,
    pub modified: u64,
}

// ---------------------------------------------------------------------------
// 游玩时长统计
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PlaytimeStats {
    pub total_seconds: i64,
    pub by_instance: Vec<PlaytimeByInstance>,
    pub by_day: Vec<PlaytimeByDay>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct PlaytimeByInstance {
    pub id: String,
    pub name: String,
    pub icon: Option<String>,
    pub seconds: i64,
    pub last_played: Option<i64>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PlaytimeByDay {
    pub day: i64,
    pub seconds: i64,
}

// ---------------------------------------------------------------------------
// 多人游戏服务器
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ServerEntry {
    pub name: String,
    pub address: String,
    pub icon: Option<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ServerStatus {
    pub online: bool,
    pub address: String,
    pub name: Option<String>,
    pub version: Option<String>,
    pub players_online: Option<i64>,
    pub players_max: Option<i64>,
    pub motd: Option<String>,
    pub favicon: Option<String>,
    pub latency_ms: Option<i64>,
    pub error: Option<String>,
}

// ---------------------------------------------------------------------------
// 崩溃日志
// ---------------------------------------------------------------------------

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CrashLogEntry {
    pub filename: String,
    pub modified: u64,
    pub size: u64,
    pub kind: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CrashCause {
    pub id: String,
    pub severity: String,
    pub title: String,
    pub reason: String,
    pub advice: String,
    pub evidence: String,
    pub confidence: u32,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CrashDetail {
    pub key: String,
    pub value: String,
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct CrashDiagnosis {
    pub severity: String,
    pub title: String,
    pub reason: String,
    pub advice: String,
    pub excerpt: String,
    pub exit_code: Option<i32>,
    pub crash_report: Option<String>,
    pub affected_mods: Vec<String>,
    pub causes: Vec<CrashCause>,
    pub stacktrace: Vec<String>,
    pub details: Vec<CrashDetail>,
    pub confidence: u32,
}

// ── 托管服务器（多人游戏开服）────────────────────────────────────────
// 字段与桌面版 QookiX-Launcher 保持一致，这样 server.json 两端通用、也能手动编辑。

/// 服务端核心类型
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ServerCore {
    Vanilla,
    Paper,
    Fabric,
    Forge,
    NeoForge,
}

impl ServerCore {
    pub fn as_str(&self) -> &'static str {
        match self {
            ServerCore::Vanilla => "vanilla",
            ServerCore::Paper => "paper",
            ServerCore::Fabric => "fabric",
            ServerCore::Forge => "forge",
            ServerCore::NeoForge => "neoforge",
        }
    }
    pub fn parse(s: &str) -> Option<Self> {
        match s.trim().to_ascii_lowercase().as_str() {
            "vanilla" | "原版" => Some(ServerCore::Vanilla),
            "paper" | "paper1.20.5+" | "papermc" => Some(ServerCore::Paper),
            "fabric" | "fabricmc" => Some(ServerCore::Fabric),
            "forge" => Some(ServerCore::Forge),
            "neoforge" | "neo-forge" => Some(ServerCore::NeoForge),
            _ => None,
        }
    }
}

/// 端口默认值
fn default_server_port() -> u16 {
    25565
}
/// 内存默认按「手机可用内存的 40%、上限 2GB」在创建时计算，这里只做兜底
fn default_server_max_mem() -> u32 {
    1024
}
fn default_server_min_mem() -> u32 {
    512
}
fn default_server_motd() -> String {
    "A QookiX Server".to_string()
}
/// 空闲休眠的默认时长（分钟）。0 = 关闭该功能。
///
/// 默认 30 分钟是刻意的：手机开服的核心矛盾是「朋友随时能进来」与「一直挂着费电占内存」，
/// 无人在线时休眠、有人连接自动唤醒，正好同时满足两边 —— 所以对**新建与既有**的服务器
/// 默认开启。它只在「一个人都没有」时才会触发，不会打断正在玩的人。
fn default_sleep_timeout_min() -> u32 {
    30
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    pub id: String,
    pub name: String,
    pub core: ServerCore,
    pub mc_version: String,
    #[serde(default = "default_server_port")]
    pub port: u16,
    #[serde(default = "default_server_max_mem")]
    pub max_memory_mb: u32,
    #[serde(default = "default_server_min_mem")]
    pub min_memory_mb: u32,
    #[serde(default = "default_server_motd")]
    pub motd: String,
    #[serde(default)]
    pub eula: bool,
    pub created: u64,
    #[serde(default)]
    pub last_started: Option<u64>,
    #[serde(default)]
    pub java_path: Option<String>,
    #[serde(default)]
    pub jvm_args: Option<String>,
    #[serde(default)]
    pub stop_command: Option<String>,
    /// 空闲休眠：连续无人在线超过这个分钟数就自动优雅停服（0 = 关闭）。
    #[serde(default = "default_sleep_timeout_min")]
    pub sleep_timeout_min: u32,
    /// 休眠期间显示在服务器卡片上的提示语。留空（None）用界面自带的文案。
    #[serde(default)]
    pub sleep_hint: Option<String>,
    /// 唤醒过程中的提示语。留空（None）用界面自带的文案。
    #[serde(default)]
    pub wake_hint: Option<String>,
    /// **只报给界面、不落 `server.json`** 的运行期字段：`server.properties` 里的
    /// `max-players`。服务器卡片副标题要显示「Paper 1.21.4 · 最多 20 名玩家」，
    /// 而这个数在 properties 里、不在 server.json 里（见 `servers.rs::with_props`）。
    /// 读不到就是 None，界面退回显示端口。
    #[serde(default, skip_deserializing, skip_serializing_if = "Option::is_none")]
    pub max_players: Option<i64>,
    /// **只报给界面、不落 `server.json`** 的运行期字段：这台服务器是否算「运行中」。
    ///
    /// 含**空闲休眠**：休眠时 JVM 停了，但唤醒监听仍占着端口 —— 列表里必须能
    /// 给出「停止」，否则用户以为它已经停了，实际端口一直被占。
    ///
    /// 这个字段原来根本不存在，列表里的 `running` 恒为 false → 卡片永远显示
    /// 「启动」，正在跑的服务器在列表上再也停不掉（2026-10-25 审查发现）。
    #[serde(default, skip_deserializing, skip_serializing_if = "is_false")]
    pub running: bool,
    /// **只报给界面、不落 `server.json`** 的运行期字段：局域网地址 `ip:port`。
    /// 未运行时为 None（界面据此决定显不显示地址）。
    #[serde(default, skip_deserializing, skip_serializing_if = "Option::is_none")]
    pub address: Option<String>,
}

/// serde 的 `skip_serializing_if` 用：`running == false` 时不写进 JSON。
fn is_false(v: &bool) -> bool {
    !*v
}