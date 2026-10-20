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

/// 版本清单地址
pub fn manifest_url(base: &str) -> String {
    if base.is_empty() {
        OFFICIAL_MANIFEST.to_string()
    } else {
        format!("{base}/mc/game/version_manifest_v2.json")
    }
}
