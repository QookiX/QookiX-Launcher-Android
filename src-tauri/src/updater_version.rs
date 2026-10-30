//! 版本比较与更新渠道（从 `updater.rs` 拆出，2026-10-30）。
//!
//! 拆出来的原因：渠道功能给 `updater.rs` 加了上百行，直接顶破体量闸门；
//! 而这些函数都是纯的（无 IO、无全局状态），单独放一个文件更好测。

/// 预发布档位。同一核心版本号（`0.2.1`）下：正式 > rc > beta。
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum PreKind {
    Beta = 0,
    Rc = 1,
    Stable = 2,
}

/// 更新渠道：决定「检查更新」愿意接受哪一档版本。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UpdateChannel {
    /// 只收正式版
    Stable,
    /// 正式 + rc
    Rc,
    /// 全部（beta / rc / 正式）
    Beta,
}

impl UpdateChannel {
    pub fn from_str(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "beta" => Self::Beta,
            "rc" => Self::Rc,
            _ => Self::Stable,
        }
    }

    /// 设置里存的值（小写，与 `from_str` 对称）
    pub fn id(self) -> &'static str {
        match self {
            Self::Stable => "stable",
            Self::Rc => "rc",
            Self::Beta => "beta",
        }
    }

    /// 这个渠道要不要某一档的版本
    pub fn allows(self, kind: PreKind) -> bool {
        match self {
            Self::Beta => true,
            Self::Rc => matches!(kind, PreKind::Stable | PreKind::Rc),
            Self::Stable => matches!(kind, PreKind::Stable),
        }
    }
}

/// 把 `v0.2.1` / `0.2.1-rc4` / `0.2.1-beta19` 解析成可比较的元组。
///
/// ⚠️ 原来只比 `(major, minor, patch)`、**忽略预发布后缀** —— 装了 rc4 的人
/// 永远收不到 rc5（核心版本号相同，被判为「不更新」）。加渠道后必须连档位一起比，
/// 否则 beta / rc 渠道等于摆设。
pub fn parse_version_full(raw: &str) -> (u32, u32, u32, PreKind, u32) {
    let cleaned = raw.trim().trim_start_matches('v');
    let (core, pre) = match cleaned.split_once('-') {
        Some((c, p)) => (c, Some(p)),
        None => (cleaned, None),
    };
    let mut it = core.split('.').map(|p| p.parse::<u32>().unwrap_or(0));
    let (a, b, c) = (
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
        it.next().unwrap_or(0),
    );
    let (kind, num) = match pre {
        None => (PreKind::Stable, 0),
        Some(p) => {
            let lower = p.to_ascii_lowercase();
            let n = lower
                .chars()
                .filter(|c| c.is_ascii_digit())
                .collect::<String>()
                .parse::<u32>()
                .unwrap_or(0);
            if lower.starts_with("rc") {
                (PreKind::Rc, n)
            } else {
                // beta / alpha / 其它一律按最低档
                (PreKind::Beta, n)
            }
        }
    };
    (a, b, c, kind, num)
}

/// `candidate` 是否比 `current` 新
pub fn is_newer(candidate: &str, current: &str) -> bool {
    parse_version_full(candidate) > parse_version_full(current)
}

/// 版本号的档位（给渠道过滤用）
pub fn kind_of(raw: &str) -> PreKind {
    parse_version_full(raw).3
}

// ── 下载校验工具（原来在 updater.rs，一起搬过来压体量）──
pub(crate) async fn fetch_text(client: &reqwest::Client, url: &str) -> Result<String, String> {
    let resp = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("请求失败：{e}"))?;
    resp.text().await.map_err(|e| format!("读取失败：{e}"))
}

/// 从 `SHA256SUMS.txt` 里找出某个文件名的期望值。
///
/// ⚠️ 2026-10-29 修：原来第一行就用 `parts.next()?` —— 遇到空行直接
/// `return None`，后面的行全都不再看。`sha256sum *.apk > SHA256SUMS.txt`
/// 的产物末尾带空行，如果校验值正好排在空行后面就永远查不到。
pub(crate) fn find_sha256(sums: &str, file_name: &str) -> Option<String> {
    for line in sums.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let mut parts = line.split_whitespace();
        let Some(hash) = parts.next() else { continue };
        let name = parts.next().unwrap_or("").trim_start_matches('*');
        if name == file_name {
            return Some(hash.to_ascii_lowercase());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stage_order_within_same_core() {
        // beta19 < rc1 < rc4 < 正式
        assert!(is_newer("0.2.1-rc1", "0.2.1-beta19"));
        assert!(is_newer("0.2.1-rc4", "0.2.1-rc1"));
        assert!(is_newer("0.2.1", "0.2.1-rc4"));
        assert!(!is_newer("0.2.1-rc4", "0.2.1"));
    }

    #[test]
    fn core_version_beats_prerelease() {
        assert!(is_newer("0.2.2-beta1", "0.2.1"));
        assert!(!is_newer("0.2.1", "0.2.2-beta1"));
    }

    #[test]
    fn channel_filters() {
        // 正式渠道只认正式版
        assert!(!UpdateChannel::Stable.allows(kind_of("0.2.1-rc4")));
        assert!(!UpdateChannel::Stable.allows(kind_of("0.2.1-beta1")));
        assert!(UpdateChannel::Stable.allows(kind_of("0.2.1")));
        // rc 渠道认 rc + 正式，不认 beta
        assert!(UpdateChannel::Rc.allows(kind_of("0.2.1-rc4")));
        assert!(!UpdateChannel::Rc.allows(kind_of("0.2.1-beta1")));
        // beta 渠道全认
        assert!(UpdateChannel::Beta.allows(kind_of("0.2.1-beta1")));
    }

    #[test]
    fn channel_roundtrip() {
        assert_eq!(UpdateChannel::from_str("beta"), UpdateChannel::Beta);
        assert_eq!(UpdateChannel::from_str(" RC "), UpdateChannel::Rc);
        assert_eq!(UpdateChannel::from_str(""), UpdateChannel::Stable);
        assert_eq!(UpdateChannel::from_str("stable").id(), "stable");
    }
}
