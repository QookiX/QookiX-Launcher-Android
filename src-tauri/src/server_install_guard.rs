//! 服务端内容 / 插件安装的兼容性守卫（纯函数，Windows 单测可覆盖）。
//!
//! 拆出来的原因（2026-11-24 审查）：`hangar.rs` / `browse.rs` 都已顶到体量预算，
//! 而「只挑 Paper 且版本匹配的插件」「Bukkit 插件不能装进原版/Fabric 服」这些判定
//! 不需要网络，抽成纯函数才能加回归测试，而不是靠真机/公网碰运气。
//!
//! 上层（`hangar::install_plugin` / `browse::install_content_to_server`）只负责
//! 取数据、下载和进度事件；**能不能装、装哪一版**全部由这里决定。

use crate::models::ServerCore;
use serde_json::Value;

/// Hangar 平台标识。Bukkit / Spigot / Purpur 等 Paper 系服务端统一用 `PAPER` 构建。
pub const HANGAR_PLATFORM: &str = "PAPER";

/// 版本对象声明的「支持的游戏版本」列表（该平台没有声明时为空）。
pub fn declared_versions(version: &Value, platform: &str) -> Vec<String> {
    version["platformDependencies"][platform]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|v| v.as_str().map(|s| s.to_string()))
                .collect()
        })
        .unwrap_or_default()
}

/// 一个可安装的 Hangar 版本 + 兼容性是「已声明」还是「接口没给、未验证」。
pub struct PickedVersion<'a> {
    pub version: &'a Value,
    pub compat_verified: bool,
}

/// 从版本列表里挑一个能装到 `mc` 的版本：优先 `Release` 渠道，其次任意渠道。
///
/// 返回 `None` = 没有任何兼容版本（也可能是这批数据里没有，调用方要带上
/// 「已检查 N 个版本」的提示，**不能静默报「无匹配」**）。
pub fn pick_hangar_version<'a>(
    versions: &'a [Value],
    platform: &str,
    mc: &str,
) -> Option<PickedVersion<'a>> {
    let is_release = |v: &Value| v["channel"]["name"].as_str() == Some("Release");
    let compatible: Vec<&Value> = versions
        .iter()
        .filter(|v| declared_versions(v, platform).iter().any(|d| d == mc))
        .collect();
    if let Some(v) = compatible.iter().copied().find(|v| is_release(v)) {
        return Some(PickedVersion { version: v, compat_verified: true });
    }
    if let Some(v) = compatible.first().copied() {
        return Some(PickedVersion { version: v, compat_verified: true });
    }
    // 部分插件不填 platformDependencies：按「未知」处理，可装但必须标记未验证
    let unknown: Vec<&Value> = versions
        .iter()
        .filter(|v| declared_versions(v, platform).is_empty())
        .collect();
    if let Some(v) = unknown.iter().copied().find(|v| is_release(v)) {
        return Some(PickedVersion { version: v, compat_verified: false });
    }
    unknown.first().copied().map(|v| PickedVersion { version: v, compat_verified: false })
}

/// 这一页里是否已有「声明支持 mc 的 Release 版本」——分页查询可以提前收手。
pub fn page_has_compatible_release(versions: &[Value], platform: &str, mc: &str) -> bool {
    versions.iter().any(|v| {
        v["channel"]["name"].as_str() == Some("Release")
            && declared_versions(v, platform).iter().any(|d| d == mc)
    })
}

/// 版本候选里所有声明过的游戏版本（去重排序，用于「它支持：…」的报错）。
pub fn collected_supported_versions(versions: &[Value], platform: &str) -> Vec<String> {
    let mut out: Vec<String> = versions
        .iter()
        .flat_map(|v| declared_versions(v, platform))
        .collect();
    out.sort();
    out.dedup();
    out
}

/// 「没有可装版本」的明确错误：说明**扫了哪些范围、可能不完整**，而不是静默说无匹配。
pub fn hangar_no_version_message(
    project_name: &str,
    mc: &str,
    scanned: usize,
    total: Option<u64>,
    supported: &[String],
) -> String {
    let coverage = match total {
        Some(n) if (n as usize) > scanned => format!(
            "已检查最近的 {scanned} 个版本（该插件共有 {n} 个版本，更早的未覆盖，可能不完整）"
        ),
        Some(n) => format!("已检查全部 {scanned} 个版本（共 {n} 个）"),
        None => format!("已检查最近的 {scanned} 个版本（分页信息缺失，可能不完整）"),
    };
    let support = if supported.is_empty() {
        "（接口未提供它支持的版本）".to_string()
    } else {
        supported.join(", ")
    };
    format!(
        "「{project_name}」没有适配 MC {mc} 的版本（{coverage}）。它声明的支持版本：{support}"
    )
}

/// Hangar 插件只允许装到 Paper 系服务端；其它核心返回明确错误。
pub fn ensure_plugin_core(server_name: &str, core: ServerCore) -> Result<(), String> {
    match core {
        ServerCore::Paper => Ok(()),
        other => Err(format!(
            "「{server_name}」的核心是 {}：Bukkit/Spigot/Paper 系插件只能装到 Paper 系服务端，\
             装进原版/Fabric/Forge 服会让服务端起不来",
            other.as_str()
        )),
    }
}

/// 服务端内容安装：`kind` → 目录，并校验与服务器核心兼容。
///
/// - `plugin`：仅 Paper 系（Bukkit 插件在 Vanilla/Fabric/Forge 上无效）
/// - `mod`：仅 Fabric / Forge / NeoForge（原版/Paper 不加载模组）
/// - 其它（数据包 / 资源包 / 光影 / 整合包）不属于「服务端内容目录」，
///   返回明确的解释而不是默默装错地方。
pub fn server_content_folder(kind: &str, core: ServerCore) -> Result<&'static str, String> {
    match kind {
        "plugin" => match core {
            ServerCore::Paper => Ok("plugins"),
            other => Err(format!(
                "Bukkit 插件只能安装到 Paper 系服务端；当前服务器核心是 {}，\
                 装进 plugins/ 不会生效，还可能让服务端启动失败",
                other.as_str()
            )),
        },
        "mod" => match core {
            ServerCore::Fabric | ServerCore::Forge | ServerCore::NeoForge => Ok("mods"),
            other => Err(format!(
                "模组只能安装到 Fabric/Forge/NeoForge 服务端；当前服务器核心是 {}，\
                 不加载 mods/ 目录",
                other.as_str()
            )),
        },
        "datapack" => Err(
            "数据包要放进对应世界存档的 datapacks/ 目录，不能作为服务端内容直接安装；\
             请在世界管理里导入数据包"
                .to_string(),
        ),
        "resourcepack" | "shader" => Err(format!(
            "「{kind}」是纯客户端内容，服务端没有对应目录，不能安装到服务器"
        )),
        "modpack" => Err("整合包要整体导入为实例，不能单独安装到服务器".to_string()),
        other => Err(format!("「{other}」不能安装到服务器，只有模组和插件可以")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ver(name: &str, channel: &str, paper_versions: &[&str]) -> Value {
        json!({
            "name": name,
            "channel": { "name": channel },
            "platformDependencies": { HANGAR_PLATFORM: paper_versions },
        })
    }

    #[test]
    fn plugin_only_on_paper() {
        assert_eq!(ensure_plugin_core("服 A", ServerCore::Paper), Ok(()));
        for core in [ServerCore::Vanilla, ServerCore::Fabric, ServerCore::Forge, ServerCore::NeoForge] {
            let err = ensure_plugin_core("服 A", core).unwrap_err();
            assert!(err.contains("Paper"), "{err}");
            assert!(err.contains(core.as_str()), "{err}");
        }
    }

    #[test]
    fn mod_and_plugin_folder_requires_matching_core() {
        assert_eq!(server_content_folder("plugin", ServerCore::Paper).unwrap(), "plugins");
        assert_eq!(server_content_folder("mod", ServerCore::Fabric).unwrap(), "mods");
        assert_eq!(server_content_folder("mod", ServerCore::Forge).unwrap(), "mods");
        assert_eq!(server_content_folder("mod", ServerCore::NeoForge).unwrap(), "mods");
        for (kind, core) in [
            ("mod", ServerCore::Paper),
            ("mod", ServerCore::Vanilla),
            ("plugin", ServerCore::Vanilla),
            ("plugin", ServerCore::Fabric),
        ] {
            let err = server_content_folder(kind, core).unwrap_err();
            assert!(
                err.contains(core.as_str()) || err.contains("Paper"),
                "{kind} on {} 的报错不够明确: {err}",
                core.as_str()
            );
        }
        // 客户端内容 / 数据包 / 整合包：必须给出明确解释，不能默默当 mod/plugin 装
        for kind in ["datapack", "resourcepack", "shader", "modpack", "unknown-kind"] {
            let err = server_content_folder(kind, ServerCore::Paper).unwrap_err();
            assert!(!err.is_empty(), "{kind} 需要明确错误");
        }
        assert!(server_content_folder("datapack", ServerCore::Paper)
            .unwrap_err()
            .contains("datapacks"));
    }

    #[test]
    fn picks_release_version_declaring_server_mc() {
        let versions = vec![
            ver("1.0.0", "Release", &["1.20", "1.21"]),
            ver("1.1.0-SNAPSHOT", "Snapshot", &["1.21"]),
        ];
        let picked = pick_hangar_version(&versions, HANGAR_PLATFORM, "1.21").unwrap();
        assert_eq!(picked.version["name"].as_str(), Some("1.0.0"));
        assert!(picked.compat_verified);
    }

    #[test]
    fn prefers_release_over_newer_snapshot_even_if_snapshot_compatible() {
        let versions = vec![
            ver("2.0-beta", "Beta", &["1.21"]),
            ver("1.9.0", "Release", &["1.21"]),
        ];
        let picked = pick_hangar_version(&versions, HANGAR_PLATFORM, "1.21").unwrap();
        assert_eq!(picked.version["name"].as_str(), Some("1.9.0"));
    }

    #[test]
    fn unknown_declaration_is_installed_but_unverified() {
        let versions = vec![ver("3.0.0", "Release", &[])];
        let picked = pick_hangar_version(&versions, HANGAR_PLATFORM, "1.21").unwrap();
        assert_eq!(picked.version["name"].as_str(), Some("3.0.0"));
        assert!(!picked.compat_verified, "接口没给版本信息时不能谎报已验证");
    }

    #[test]
    fn incompatible_versions_are_rejected_with_scanned_count() {
        let versions = vec![ver("0.1", "Release", &["1.12.2"]), ver("0.2", "Release", &["1.16"])];
        assert!(pick_hangar_version(&versions, HANGAR_PLATFORM, "1.21").is_none());
        let msg = hangar_no_version_message(
            "OldPlugin",
            "1.21",
            2,
            Some(900),
            &collected_supported_versions(&versions, HANGAR_PLATFORM),
        );
        assert!(msg.contains("已检查最近的 2 个版本"), "{msg}");
        assert!(msg.contains("共有 900 个版本"), "分页信息要说明可能不完整: {msg}");
        assert!(msg.contains("1.12.2"), "要告诉用户它支持什么版本: {msg}");
        // 总数未知时也不能静默：明确说「可能不完整」
        let msg2 = hangar_no_version_message("OldPlugin", "1.21", 25, None, &[]);
        assert!(msg2.contains("可能不完整"), "{msg2}");
    }

    #[test]
    fn page_compat_release_detection_for_pagination() {
        let page = vec![ver("1.0", "Snapshot", &["1.20"]), ver("2.0", "Release", &["1.21"])];
        assert!(page_has_compatible_release(&page, HANGAR_PLATFORM, "1.21"));
        assert!(!page_has_compatible_release(&page, HANGAR_PLATFORM, "1.19"));
        let unknown = vec![ver("3.0", "Release", &[])];
        assert!(!page_has_compatible_release(&unknown, HANGAR_PLATFORM, "1.21"));
    }
}
