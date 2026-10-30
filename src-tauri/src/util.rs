//! 通用小工具：文件名合法性校验、zip 解压、统一 HTTP 客户端等。

// ---------------------------------------------------------------------------
// 统一 HTTP 客户端
// ---------------------------------------------------------------------------

lazy_static::lazy_static! {
    /// (代理签名, 客户端)：设置里切换代理后自动重建，避免每次请求都新建连接池。
    static ref HTTP_CLIENT: std::sync::Mutex<Option<(String, reqwest::Client)>> =
        std::sync::Mutex::new(None);
}

/// 全应用统一的 HTTP 客户端：读取 `settings.json` 的 `proxy_mode` / `proxy` 并应用到
/// 所有网络请求（下载、版本清单、内容中心、账号登录……）。
///
/// 代理模式：
/// - `direct`：直连；
/// - `custom`：使用 `proxy` 指定的地址（支持 http:// 与 socks5://）；
/// - `system`：Android 没有进程级系统代理，按直连处理（WebView 的代理由系统控制）。
pub async fn http_client() -> reqwest::Client {
    let (mode, configured) = match crate::settings::get_settings().await {
        Ok(s) => (s.proxy_mode, s.proxy.unwrap_or_default()),
        Err(_) => (String::from("direct"), String::new()),
    };
    let proxy = resolve_proxy(&mode, &configured);
    cached_client(&mode, &proxy)
}

/// [`http_client`] 的同步版本：直接读 settings.json。
/// 供无法 `await` 的调用点使用（例如只能同步构造客户端的工具函数）。
pub fn http_client_blocking() -> reqwest::Client {
    let map = crate::settings::load_raw_settings_sync();
    let mode = crate::settings::raw_str(&map, &["proxy_mode", "proxyMode"])
        .unwrap_or_else(|| "direct".to_string());
    let configured = crate::settings::raw_str(&map, &["proxy"]).unwrap_or_default();
    let proxy = resolve_proxy(&mode, &configured);
    cached_client(&mode, &proxy)
}

/// 当前生效的代理地址（已解析 `system` 模式），没有代理时返回空串。
/// 供无法使用 HTTP 客户端、却又需要借道代理的调用点使用（例如 TCP 直连的服务器 Ping）。
pub async fn resolved_proxy() -> String {
    let (mode, configured) = match crate::settings::get_settings().await {
        Ok(s) => (s.proxy_mode, s.proxy.unwrap_or_default()),
        Err(_) => (String::from("direct"), String::new()),
    };
    resolve_proxy(&mode, &configured)
}

/// 按代理模式解析出实际要用的代理地址：
/// - `custom`：设置里填写的地址；
/// - `system`：向系统查询（Android 上是 VPN / Wi-Fi 下发的 HTTP 代理，如 Clash 的 127.0.0.1:7890）；
/// - 其它（`direct` / 空）：不使用代理。
fn resolve_proxy(mode: &str, configured: &str) -> String {
    match mode {
        "custom" => configured.trim().to_string(),
        "system" => crate::android_bridge::system_proxy().unwrap_or_default(),
        _ => String::new(),
    }
}

fn cached_client(mode: &str, proxy: &str) -> reqwest::Client {
    let signature = format!("{mode}|{proxy}");

    if let Ok(guard) = HTTP_CLIENT.lock() {
        if let Some((sig, client)) = guard.as_ref() {
            if *sig == signature {
                return client.clone();
            }
        }
    }

    // ⚠️ 2026-10-27 真机：下载 97.7MB 的游戏本体卡在 25.6MB 不动，等 10 分钟才报错。
    //
    // 原因：原来只有 `.timeout(600s)` —— reqwest 的 timeout 是**整个请求的总时长**
    //（连接 + 读完 body），不是「多久没数据就断」。于是：
    //   · 连接被中间设备挂起 → 干等 600 秒才失败，用户看到的就是「卡住」；
    //   · 网速慢于约 170KB/s → 600 秒下不完 97.7MB，正常下载也会被判超时。
    //
    // 改法：用 **read_timeout（空闲超时）** 做真正的主力 ——
    // 连续 30 秒拿不到任何数据才断，连接挂起能立刻脱身；
    // 总超时放宽到 1 小时，只兜「一直有数据但极慢」的极端情况。
    let mut builder = reqwest::Client::builder()
        .user_agent("QookiX-Launcher-Android")
        .connect_timeout(std::time::Duration::from_secs(15))
        .read_timeout(std::time::Duration::from_secs(30))
        .timeout(std::time::Duration::from_secs(3600));
    let addr = proxy.trim();
    if !addr.is_empty() {
        if let Ok(p) = reqwest::Proxy::all(addr) {
            builder = builder.proxy(p);
        } else {
            crate::util::log_line(&format!("代理地址无效，已忽略: {addr}"));
        }
    }

    let client = builder.build().unwrap_or_default();
    if let Ok(mut guard) = HTTP_CLIENT.lock() {
        *guard = Some((signature, client.clone()));
    }
    client
}

/// 清空 HTTP 客户端缓存，设置变更后调用可让新代理立刻生效。
pub fn reset_http_client() {
    if let Ok(mut guard) = HTTP_CLIENT.lock() {
        *guard = None;
    }
}

// 诊断日志拆到 `launch_log`（它要同时管「装完诊断」和「本次启动」两个落点，
// 放在这里会让 util 越长越像杂物间）。调用方继续写 `crate::util::log_line`。
pub use crate::launch_log::{clear_launch_log, log_line, set_launch_log};

/// 校验是否为安全的单层文件名（不允许路径分隔符 / 相对路径）。
pub fn is_safe_filename(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && !name.contains('/')
        && !name.contains('\\')
        && !name.contains(':')
}

/// 校验相对路径是否安全（不允许 `..` 逃逸、绝对路径、空路径）。
pub fn is_safe_rel(rel: &str) -> bool {
    if rel.is_empty() {
        return true;
    }
    let trimmed = rel.trim();
    if trimmed.starts_with('/') || trimmed.starts_with('\\') {
        return false;
    }
    !trimmed
        .split(['/', '\\'])
        .any(|seg| seg.is_empty() || seg == "." || seg == "..")
}

/// 递归把 zip 解压到目标目录，返回解压文件数。
/// `allowed_exts` 非空时只解压匹配扩展名的文件。
pub fn extract_zip(zip_path: &std::path::Path, dest: &std::path::Path, allowed_exts: &[&str]) -> Result<usize, String> {
    let file = std::fs::File::open(zip_path).map_err(|e| format!("打开 zip 失败: {e}"))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("解析 zip 失败: {e}"))?;
    let mut count = 0usize;
    for i in 0..archive.len() {
        let mut entry = archive
            .by_index(i)
            .map_err(|e| format!("读取 zip 条目失败: {e}"))?;
        let name = entry.name().to_string();
        // 防 zip 炸弹路径逃逸
        if !is_safe_rel(&name) {
            continue;
        }
        let out = dest.join(&name);
        if entry.is_dir() {
            std::fs::create_dir_all(&out).map_err(|e| format!("创建目录失败: {e}"))?;
            continue;
        }
        if !allowed_exts.is_empty() {
            let ext = std::path::Path::new(&name)
                .extension()
                .and_then(|s| s.to_str())
                .unwrap_or("")
                .to_lowercase();
            if !allowed_exts.iter().any(|a| a == &ext.as_str()) {
                continue;
            }
        }
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {e}"))?;
        }
        let mut out_file = std::fs::File::create(&out).map_err(|e| format!("创建文件失败: {e}"))?;
        std::io::copy(&mut entry, &mut out_file).map_err(|e| format!("写出文件失败: {e}"))?;
        count += 1;
    }
    Ok(count)
}

// ---------------------------------------------------------------------------
// 内容元数据（mod / 资源包 / 光影包）解析
// ---------------------------------------------------------------------------

use sha1::Digest as _;

/// 文件 sha1（hex 小写），失败返回 None。
/// 把任意字符串收拾成安全的文件名片段：保留中文/字母数字与 `-_.`，其余换成 `-`。
///
/// 导出整合包、下载插件都要按「项目名/插件名」拼文件名，而它们可能含空格、
/// 斜杠、emoji —— 直接当文件名会写出目录外或触发文件系统报错。共用一份。
pub fn safe_stem(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for ch in s.chars() {
        if ch.is_alphanumeric() || matches!(ch, '-' | '_' | '.') {
            out.push(ch);
        } else if !out.ends_with('-') {
            out.push('-');
        }
    }
    let trimmed = out.trim_matches('-').to_string();
    if trimmed.is_empty() {
        "file".to_string()
    } else {
        trimmed
    }
}

pub fn file_sha1(path: &std::path::Path) -> Option<String> {
    let mut file = std::fs::File::open(path).ok()?;
    let mut hasher = sha1::Sha1::new();
    std::io::copy(&mut file, &mut hasher).ok()?;
    Some(format!("{:x}", hasher.finalize()))
}

/// jar 内解析出的 mod 元数据。
#[derive(Default, Clone, Debug)]
pub struct ModJarMeta {
    pub mod_id: Option<String>,
    pub name: Option<String>,
    pub version: Option<String>,
    pub authors: Option<Vec<String>>,
    pub description: Option<String>,
    /// 已落地的 icon 本地路径或 http(s) URL
    pub icon: Option<String>,
}

fn save_icon_buf(buf: Vec<u8>) -> Option<String> {
    if buf.len() < 8 {
        return None;
    }
    // 不能写 std::env::temp_dir()：安卓上它指向 /data/local/tmp，应用无权写入，
    // write 直接失败 -> 返回 None -> 整合包/模组图标全部显示不出来。
    // 统一落到应用私有目录 `<data>/icons/mod-icons/`。
    let dir = std::path::Path::new(&crate::settings::data_dir_sync()?)
        .join("icons")
        .join("mod-icons");
    std::fs::create_dir_all(&dir).ok()?;
    let f = dir.join(format!("{}.png", uuid::Uuid::new_v4().simple()));
    std::fs::write(&f, &buf).ok()?;
    Some(f.to_string_lossy().to_string())
}

fn parse_json_authors(val: &serde_json::Value) -> Option<Vec<String>> {
    let a = val.get("authors")?;
    if let Some(arr) = a.as_array() {
        let v: Vec<String> = arr
            .iter()
            .filter_map(|x| {
                x.as_str()
                    .map(|s| s.to_string())
                    .or_else(|| x.get("name").and_then(|n| n.as_str()).map(|s| s.to_string()))
            })
            .filter(|s| !s.is_empty())
            .collect();
        if v.is_empty() { None } else { Some(v) }
    } else if let Some(s) = a.as_str() {
        if s.is_empty() { None } else { Some(vec![s.to_string()]) }
    } else {
        None
    }
}

/// 极简 mods.toml 值解析：支持 "str" / 'str' / ["a","b"] / bare。
fn parse_toml_value(s: &str) -> Option<String> {
    let s = s.trim();
    if s.is_empty() {
        return None;
    }
    if s.starts_with('[') && s.ends_with(']') && s.len() >= 2 {
        let inner = &s[1..s.len() - 1];
        let items: Vec<String> = inner.split(',').filter_map(|item| parse_toml_value(item)).collect();
        return Some(items.join(", "));
    }
    if s.starts_with('"') && s.ends_with('"') && s.len() >= 2 {
        return Some(s[1..s.len() - 1].to_string());
    }
    if s.starts_with('\'') && s.ends_with('\'') && s.len() >= 2 {
        return Some(s[1..s.len() - 1].to_string());
    }
    Some(s.to_string())
}

fn split_authors(s: &str) -> Vec<String> {
    s.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect()
}

/// 解析 Forge/NeoForge 的 mods.toml：只取第一个 `[[mods]]` 块作为自身元数据。
fn parse_mods_toml(text: &str) -> Option<ModJarMeta> {
    let mut in_mods = false;
    let mut meta = ModJarMeta::default();
    let mut found_mod_id = false;
    let mut logo_file: Option<String> = None;

    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.starts_with("[[") {
            in_mods = trimmed == "[[mods]]" && !found_mod_id;
            continue;
        }
        if trimmed.starts_with('[') {
            in_mods = false;
            continue;
        }
        let Some(eq) = trimmed.find('=') else { continue };
        let key = trimmed[..eq].trim();
        let value = trimmed[eq + 1..].trim();
        let Some(v) = parse_toml_value(value) else { continue };
        if !in_mods {
            continue;
        }
        match key {
            "modId" => { meta.mod_id = Some(v); found_mod_id = true; }
            "displayName" => { meta.name = Some(v); }
            "version" => { meta.version = Some(v); }
            "authors" => { meta.authors = Some(split_authors(&v)); }
            "description" => { meta.description = Some(v); }
            "logoFile" | "logo" => { logo_file = Some(v); }
            _ => {}
        }
    }
    if found_mod_id {
        meta.icon = logo_file;
        Some(meta)
    } else {
        None
    }
}

/// 从 jar 内部解析 mod 元数据（fabric/quilt/forge/neoforge 通用）。
/// 优先级：fabric.mod.json → quilt.mod.json → META-INF/mods.toml → META-INF/neoforge.mods.toml
pub fn parse_mod_jar(path: &std::path::Path) -> Option<ModJarMeta> {
    use std::io::Read;
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let mut meta = ModJarMeta::default();
    let mut icon_ref: Option<String> = None;
    let mut found = false;

    for name in ["fabric.mod.json", "quilt.mod.json"] {
        if found { break; }
        let Ok(mut entry) = archive.by_name(name) else { continue };
        let mut buf = Vec::new();
        if Read::read_to_end(&mut entry, &mut buf).is_err() { continue; }
        let val: serde_json::Value = match serde_json::from_slice(&buf) { Ok(v) => v, Err(_) => continue };
        meta.mod_id = val.get("id").and_then(|v| v.as_str()).map(|s| s.to_string());
        meta.name = val.get("name").and_then(|v| v.as_str()).map(|s| s.to_string());
        meta.version = val.get("version").and_then(|v| v.as_str()).map(|s| s.to_string());
        meta.description = val.get("description").and_then(|v| v.as_str()).map(|s| s.to_string());
        meta.authors = parse_json_authors(&val);
        icon_ref = val.get("icon").and_then(|v| v.as_str()).map(|s| s.to_string());
        found = true;
    }

    for name in ["META-INF/mods.toml", "META-INF/neoforge.mods.toml"] {
        if found { break; }
        let Ok(mut entry) = archive.by_name(name) else { continue };
        let mut buf = Vec::new();
        if Read::read_to_end(&mut entry, &mut buf).is_err() { continue; }
        let text = String::from_utf8_lossy(&buf).to_string();
        if let Some(m) = parse_mods_toml(&text) {
            icon_ref = m.icon.clone();
            meta = m;
            found = true;
        }
    }

    if !found { return None; }

    if let Some(icon) = icon_ref {
        if icon.starts_with("http://") || icon.starts_with("https://") {
            meta.icon = Some(icon);
        } else if let Ok(mut icon_entry) = archive.by_name(&icon) {
            let mut ibuf = Vec::new();
            if Read::read_to_end(&mut icon_entry, &mut ibuf).is_ok() {
                meta.icon = save_icon_buf(ibuf);
            }
        }
    }
    Some(meta)
}

/// 从 JSON 文本组件提取纯文本（支持 string / object / array 三种形式）。
fn json_text(val: &serde_json::Value) -> Option<String> {
    match val {
        serde_json::Value::String(s) => Some(s.clone()),
        serde_json::Value::Object(o) => {
            o.get("text").and_then(|v| v.as_str())
                .or_else(|| o.get("fallback").and_then(|v| v.as_str()))
                .map(|s| s.to_string())
        }
        serde_json::Value::Array(arr) => {
            let mut out = String::new();
            for item in arr {
                if let Some(t) = json_text(item) { out.push_str(&t); }
            }
            if out.is_empty() { None } else { Some(out) }
        }
        _ => None,
    }
}

/// 从 zip 内 `pack.mcmeta` 解析材质包/光影包的描述。
fn parse_pack_mcmeta(path: &std::path::Path) -> Option<String> {
    use std::io::Read;
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let Ok(mut entry) = archive.by_name("pack.mcmeta") else { return None };
    let mut buf = Vec::new();
    if Read::read_to_end(&mut entry, &mut buf).is_err() { return None }
    let val: serde_json::Value = serde_json::from_slice(&buf).ok()?;
    let pack = val.get("pack")?;
    let desc = pack.get("description").and_then(|d| json_text(d))?;
    Some(desc)
}

/// 从 zip 根目录提取 `pack.png` 作为图标，保存到临时文件并返回路径。
fn extract_zip_icon(path: &std::path::Path) -> Option<String> {
    use std::io::Read;
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let Ok(mut entry) = archive.by_name("pack.png") else { return None };
    let mut buf = Vec::new();
    if Read::read_to_end(&mut entry, &mut buf).is_err() { return None }
    save_icon_buf(buf)
}

/// 从本地 mod / 资源包 / 光影 zip 中提取内嵌图标（返回本地路径或 http URL）。
pub fn extract_archive_icon(path: &std::path::Path, kind: &str) -> Option<String> {
    use std::io::Read;
    let file = std::fs::File::open(path).ok()?;
    let mut archive = zip::ZipArchive::new(file).ok()?;
    let image_candidates: &[&str] = match kind {
        "resourcepack" => &["pack.png", "icon.png", "assets/minecraft/textures/gui/title/icon.png"],
        "shader" => &["icon.png", "pack.png"],
        _ => &["icon.png", "pack.png"],
    };
    for name in image_candidates {
        if let Ok(mut entry) = archive.by_name(name) {
            if entry.is_dir() {
                continue;
            }
            let mut buf = Vec::new();
            if Read::read_to_end(&mut entry, &mut buf).is_err() { continue; }
            if let Some(p) = save_icon_buf(buf) {
                return Some(p);
            }
        }
    }
    let mut icon_ref: Option<String> = None;
    for name in ["modrinth.mod.json", "fabric.mod.json"] {
        if icon_ref.is_some() {
            break;
        }
        if let Ok(mut entry) = archive.by_name(name) {
            let mut buf = Vec::new();
            if Read::read_to_end(&mut entry, &mut buf).is_err() { continue; }
            let val: serde_json::Value = match serde_json::from_slice(&buf) { Ok(v) => v, Err(_) => continue };
            if let Some(icon) = val.get("icon").and_then(|i| i.as_str()) {
                if !icon.is_empty() {
                    icon_ref = Some(icon.to_string());
                }
            }
        }
    }
    if let Some(icon) = icon_ref {
        if icon.starts_with("http://") || icon.starts_with("https://") {
            return Some(icon);
        }
        if let Ok(mut icon_entry) = archive.by_name(&icon) {
            let mut ibuf = Vec::new();
            if Read::read_to_end(&mut icon_entry, &mut ibuf).is_ok() {
                if let Some(p) = save_icon_buf(ibuf) {
                    return Some(p);
                }
            }
        }
    }
    None
}

/// 去除 Minecraft §x 格式码。
fn strip_mc_formatting(s: &str) -> String {
    let mut out = String::new();
    let mut skip_next = false;
    for c in s.chars() {
        if skip_next { skip_next = false; continue; }
        if c == '\u{00a7}' { skip_next = true; continue; }
        out.push(c);
    }
    out.trim().to_string()
}

/// 用 jar/zip 内部元数据回填 `InstalledContent` 中缺失的字段。
pub fn fill_content_from_jar(rec: &mut crate::models::InstalledContent, jar_path: &std::path::Path) {
    if let Some(meta) = parse_mod_jar(jar_path) {
        if rec.mod_id.is_none() { rec.mod_id = meta.mod_id; }
        let name_is_placeholder = rec.name.as_deref() == Some(rec.filename.as_str());
        if rec.name.is_none() || name_is_placeholder {
            if let Some(n) = meta.name.filter(|n| !n.is_empty()) { rec.name = Some(n); }
        }
        if rec.version.is_none() { rec.version = meta.version; }
        if rec.authors.is_none() { rec.authors = meta.authors; }
        if rec.description.is_none() { rec.description = meta.description; }
        if rec.icon.is_none() { rec.icon = meta.icon; }
        if rec.slug.is_none() { rec.slug = rec.mod_id.clone(); }
        return;
    }
    if let Some(desc) = parse_pack_mcmeta(jar_path) {
        let name_is_placeholder = rec.name.as_deref() == Some(rec.filename.as_str());
        if rec.name.is_none() || name_is_placeholder {
            let clean = strip_mc_formatting(&desc);
            if !clean.is_empty() { rec.name = Some(clean); }
        }
        if rec.description.is_none() { rec.description = Some(desc); }
    }
    if rec.icon.is_none() {
        if let Some(icon) = extract_zip_icon(jar_path) {
            rec.icon = Some(icon);
        }
    }
    let name_is_placeholder = rec.name.as_deref() == Some(rec.filename.as_str());
    if rec.name.is_none() || name_is_placeholder {
        let stem = rec.filename.trim_end_matches(".zip").trim_end_matches(".jar");
        let pretty: String = stem.replace('_', " ");
        if !pretty.is_empty() { rec.name = Some(pretty); }
    }
}

// ── 全局 tokio 运行时（阶段 4：替代 tauri::async_runtime）──
static QK_RT: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();

pub fn runtime() -> &'static tokio::runtime::Runtime {
    QK_RT.get_or_init(|| tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("tokio runtime"))
}

pub fn block_on<F: std::future::Future>(f: F) -> F::Output {
    // ⚠️ 在 tokio 上下文里再调 `runtime().block_on` 会 panic：
    //    "Cannot start a runtime from within a runtime"。
    //
    // 而 panic 会被 JNI 边界的 `catch_unwind` 吞掉，变成
    // `{"ok":false,"error":"nativeInvoke panic"}` —— 用户看到「未知错误」，
    // 我们看到一条没有线索的信封，排查成本极高。
    //
    // 这里提前断言并**写明原因和修法**，让同一个 panic 变成可读的线索。
    assert!(
        tokio::runtime::Handle::try_current().is_err(),
        "util::block_on 不能在 tokio 上下文里调用（否则 runtime().block_on 会 panic \
         \"Cannot start a runtime from within a runtime\"）。\
         调用方应该改成 async fn 并 .await，而不是同步 block_on。"
    );
    runtime().block_on(f)
}

pub fn spawn<F>(f: F) where F: std::future::Future<Output = ()> + Send + 'static { runtime().spawn(f); }

/// 取锁并在**中毒后继续用**（等价于 `lock().unwrap()` 但不会永久打死这个域）。
///
/// ⚠️ 为什么（2026-10-26 审查 A-P2-10）：这些静态 Mutex 保护的域（进度取消表、
/// 账号登录流程、游戏进程句柄…）一旦有线程持锁 panic，`lock().unwrap()` 之后
/// 每次调用都会继续 panic —— 一个偶发崩溃就变成「该功能永久不可用」。
/// 这些结构里的数据即使经历过 panic 也仍然是可用的（都是标志位/句柄/表），
/// 所以直接取回内部值，比整个域瘫痪更好。
pub fn lock_recover<T>(m: &std::sync::Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    match m.lock() {
        Ok(g) => g,
        Err(poisoned) => {
            tracing::warn!("互斥锁曾中毒，继续使用内部数据");
            poisoned.into_inner()
        }
    }
}

/// 同上，但锁在 `OnceLock<Mutex<T>>` 里（`get().unwrap().lock().unwrap()` 的替代）。
pub fn cell_lock<T>(
    cell: &'static std::sync::OnceLock<std::sync::Mutex<T>>,
) -> Option<std::sync::MutexGuard<'static, T>> {
    cell.get().map(lock_recover)
}
