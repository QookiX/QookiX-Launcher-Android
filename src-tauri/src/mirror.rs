//! 下载镜像源预设与连通性测试。
//!
//! 镜像配置保存在 `settings.json` 的 `mirror` / `mirrorCustom` 字段，
//! 每次请求都重新读取设置，切换镜像后立即生效。

use serde_json::{json, Value};

/// 官方源 id（不做任何改写）
pub const OFFICIAL: &str = "official";
/// 自定义镜像 id（使用 `mirror_custom` 里的根地址）
pub const CUSTOM: &str = "custom";

/// 内置镜像根地址。
///
/// 用途只有一个：当用户明明选了「官方源」、而官方接口又慢到不可用时，
/// 把它作为**并行候选**兜底（见 `browse::fetch_list_mirrored`）。
/// 它不代表用户偏好，也不会改设置 —— 只是「多挂一个更快的源」。
pub const BMCLAPI_BASE: &str = "https://bmclapi2.bangbang93.com";

/// Modrinth / CurseForge 的国内中转（社区服务 MCIM）。
///
/// ## 为什么单独有一个
///
/// `BMCLAPI_BASE` 只镜像 **Mojang 官方资源 + 各加载器 maven**，
/// **不含** `cdn.modrinth.com` / `edge.forgecdn.net` ——
/// 而整合包里的模组文件全在这两个 CDN 上。
///
/// 结果就是：国内用户导入整合包时是**直连国外**，几十 KB/s；
/// 而 ZL2 的 `MCIMMirror.kt` 把它们换到 `mod.mcimirror.top`，能到几 MB/s。
/// 用户反馈「导入整合包远慢于 ZL2 / PCL」，根因就在这里。
///
/// ## 为什么放在候选最前
///
/// 国内到 cdn.modrinth.com 的直连速度通常只有中转的百分之几。
/// 中转挂了也不致命：候选列表是「中转 → 官方」，依次尝试，
/// 最坏情况是多一次连接超时，下载本身仍然会走官方源完成。
///
/// ⚠️ 这是**第三方社区服务**，不是官方。要是长期不可用，
/// 把这一条删掉即可（或用「自定义镜像」覆盖）。
pub const MCIM_BASE: &str = "https://mod.mcimirror.top";

/// 能走 MCIM 中转的 CDN 前缀（整域名替换，与 ZL2 的 REPLACE_MIRROR_HOLDERS 一致）。
const MCIM_PREFIXES: [&str; 2] = [
    "https://cdn.modrinth.com/",
    "https://edge.forgecdn.net/",
];

/// 官方版本清单地址
pub const OFFICIAL_MANIFEST: &str =
    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

pub struct MirrorPreset {
    pub id: &'static str,
    pub label: &'static str,
    /// 镜像站根地址；官方源为空串
    pub base: &'static str,
    pub desc: &'static str,
}

/// 内置镜像预设（顺序即前端展示顺序）
pub const PRESETS: &[MirrorPreset] = &[
    MirrorPreset {
        id: OFFICIAL,
        label: "官方源",
        base: "",
        desc: "Mojang / Fabric / Forge 官方地址，海外网络推荐",
    },
    MirrorPreset {
        id: "bmclapi",
        label: "BMCLAPI",
        base: "https://bmclapi2.bangbang93.com",
        desc: "国内公益镜像，覆盖游戏本体、资源文件与依赖库",
    },
];

/// 由设置解析出实际生效的镜像根地址（官方源返回空串）。
pub fn resolve_from(id: &str, custom: &str) -> String {
    if id == CUSTOM {
        return custom.trim().trim_end_matches('/').to_string();
    }
    PRESETS
        .iter()
        .find(|p| p.id == id)
        .map(|p| p.base.to_string())
        .unwrap_or_default()
}

/// 当前生效的镜像根地址（官方源 → **空串**）。
///
/// ⚠️ 2026-10-27：`version.rs` / `servers.rs` 里原来把 `BMCLAPI_BASE` 硬编码进
/// URL 生成，用户在设置里选「官方源」也照样走镜像 —— 真机表现是「我已经改成官方了，
/// 补全时下载链接还是 bmclapi」。
///
/// 约定：返回空串 = 官方源，调用方**不做任何 URL 改写**（不是替换成官方地址，
/// 那样会二次拼接）。读不到设置时按官方处理（`OFFICIAL` 是默认值）。
pub fn current_base() -> String {
    let map = crate::settings::load_raw_settings_sync();
    let id = map
        .get("mirror")
        .and_then(|v| v.as_str())
        .unwrap_or(OFFICIAL);
    let custom = map
        .get("mirrorCustom")
        .or_else(|| map.get("mirror_custom"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    resolve_from(id, custom)
}

/// 镜像预设列表（供前端渲染）
pub fn presets() -> Value {
    Value::Array(
        PRESETS
            .iter()
            .map(|p| json!({ "id": p.id, "label": p.label, "base": p.base, "desc": p.desc }))
            .collect(),
    )
}

struct Rule {
    /// 官方地址前缀
    prefix: &'static str,
    /// 镜像侧对应的路径前缀（以 `/` 开头）
    target: &'static str,
}

/// 前缀越长越靠前，避免被更短的通用前缀抢先匹配
const RULES: &[Rule] = &[
    Rule {
        prefix: OFFICIAL_MANIFEST,
        target: "/mc/game/version_manifest_v2.json",
    },
    Rule {
        prefix: "https://piston-meta.mojang.com/mc/assets/",
        target: "/mc/assets/",
    },
    Rule {
        prefix: "https://piston-meta.mojang.com/mc/game/",
        target: "/mc/game/",
    },
    Rule {
        prefix: "https://piston-meta.mojang.com/v1/packages/",
        target: "/v1/packages/",
    },
    Rule {
        prefix: "https://piston-meta.mojang.com/",
        target: "/",
    },
    Rule {
        prefix: "https://launchermeta.mojang.com/mc/",
        target: "/mc/",
    },
    Rule {
        prefix: "https://launchermeta.mojang.com/v1/packages/",
        target: "/v1/packages/",
    },
    Rule {
        prefix: "https://piston-data.mojang.com/",
        target: "/",
    },
    Rule {
        prefix: "https://launcher.mojang.com/",
        target: "/",
    },
    Rule {
        prefix: "https://files.minecraftforge.net/maven/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://maven.neoforged.net/releases/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://maven.neoforged.net/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://maven.minecraftforge.net/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://maven.quiltmc.org/repository/release/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://maven.fabricmc.net/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://libraries.minecraft.net/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://repo1.maven.org/maven2/",
        target: "/maven/",
    },
    Rule {
        prefix: "https://resources.download.minecraft.net/",
        target: "/assets/",
    },
    Rule {
        prefix: "https://meta.fabricmc.net/",
        target: "/fabric-meta/",
    },
];

/// 把单个官方地址改写到镜像；无法识别或官方源时原样返回。
/// 无状态，可在下载线程中安全复用。
pub fn map(base: &str, url: &str) -> String {
    if base.is_empty() || url.is_empty() {
        return url.to_string();
    }
    for r in RULES {
        if let Some(rest) = url.strip_prefix(r.prefix) {
            return format!("{}{}{}", base, r.target, rest);
        }
    }
    url.to_string()
}

/// 一次下载的候选地址列表（按顺序尝试）。
///
/// 顺序：镜像（用户选了才有）→ 官方 → BMCLAPI 兜底 → Maven Central 兜底。
///
/// ## 为什么要有后两个兜底
///
/// 真机实测（默认「官方源」）：
/// * `maven.neoforged.net` 一条请求都出不去 → NeoForge installer 的 31 个依赖
///   全部失败，加载器永远装不完（界面停在「正在安装加载器…」）。
/// * 就算 BMCLAPI 能连，它也没有 `noexception` / `guava:20.0` 这类第三方包 →
///   installer 报 `These libraries failed to download` 后直接失败退出。
///
/// 这两类地址的包在 Maven Central 上都有，所以最后再挂一条 Central。
pub fn candidate_urls(mirror_base: &str, url: &str) -> Vec<String> {
    let mut urls: Vec<String> = Vec::new();

    // ① Modrinth / CurseForge → MCIM 中转（**放最前**，国内直连这两个 CDN 只有几十 KB/s）。
    //
    // 这一条和我们自己的 mirror_base 无关：BMCLAPI 本来就不镜像这两个域名，
    // 即使用户选了「BMCLAPI」，map() 也匹配不到，最终还是会直连。
    // 中转失败会回退到下面的官方 URL，所以不会让下载失败。
    for prefix in MCIM_PREFIXES {
        if let Some(rest) = url.strip_prefix(prefix) {
            urls.push(format!("{MCIM_BASE}/{rest}"));
            break;
        }
    }

    if !mirror_base.is_empty() {
        let mapped = map(mirror_base, url);
        if mapped != url {
            urls.push(mapped);
        }
    }
    urls.push(url.to_string());
    if mirror_base.is_empty() {
        let mapped = map(BMCLAPI_BASE, url);
        if mapped != url {
            urls.push(mapped);
        }
    }
    for prefix in [
        "https://maven.neoforged.net/releases/",
        "https://maven.minecraftforge.net/",
    ] {
        if let Some(rest) = url.strip_prefix(prefix) {
            urls.push(format!("https://repo1.maven.org/maven2/{rest}"));
            break;
        }
    }
    urls
}

/// 版本清单地址
pub fn manifest_url(base: &str) -> String {
    if base.is_empty() {
        OFFICIAL_MANIFEST.to_string()
    } else {
        format!("{base}/mc/game/version_manifest_v2.json")
    }
}
