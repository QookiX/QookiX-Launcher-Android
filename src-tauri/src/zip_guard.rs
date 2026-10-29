//! ZIP 解压预算（zip bomb 防护）。
//!
//! ⚠️ 2026-10-29（外部审计 B13）：
//! 项目里有好几处解压：整合包导入（`modpack.rs`）、内容安装（`browse.rs`）、
//! 局域网同步下载（`lan_sync.rs`）、运行库/资源导入（`natives.rs` / `java.rs`）。
//! 它们都只做了**路径**校验（`enclosed_name()`），没有任何**体量**限制 ——
//! 一个 10 MB 恶意 zip 可以解出几十 GB（zip bomb），把手机存储写满；
//! 或者塞几十万个小文件，把 inode 和「删除实例」都拖死。
//!
//! 这里提供一个统一的小对象，调用方在每条 entry 上调用一次即可。

/// 单个 zip 允许解出的总字节数（8 GiB）。
///
/// 挑这个数：正常整合包最大也就一两个 GB（含存档的极端情况三四个 GB），
/// 8 GiB 留足余量又远小于把手机写满。
pub const MAX_TOTAL_BYTES: u64 = 8 * 1024 * 1024 * 1024;

/// 单个 zip 允许的条目数（含目录）。
///
/// 10 万个：正常实例连 2 万都到不了（模组几百 + 资源包内部文件），
/// 恶意包动辄上百万个。
pub const MAX_ENTRIES: usize = 100_000;

/// 单个条目允许解出的字节数（2 GiB）。
///
/// 一个 jar / 存档不该有 2 GB；超过基本可以断定是炸弹。
pub const MAX_SINGLE_BYTES: u64 = 2 * 1024 * 1024 * 1024;

/// 解压预算计数器。**每个 zip 一个实例**，逐条 entry 调用 [`Self::admit`]。
#[derive(Default)]
pub struct ZipBudget {
    entries: usize,
    bytes: u64,
}

impl ZipBudget {
    pub fn new() -> Self {
        Self::default()
    }

    /// 记账一条 entry；超预算返回 Err（调用方直接中止整个解压）。
    ///
    /// `declared_size` 用 zip 头里声明的解压后大小 —— 它可能被伪造，
    /// 所以调用方**还要**在真正写盘时用 [`Self::add_written`] 记实际写出的字节数。
    pub fn admit(&mut self, name: &str, declared_size: u64) -> Result<(), String> {
        self.entries += 1;
        if self.entries > MAX_ENTRIES {
            return Err(format!(
                "压缩包条目过多（超过 {MAX_ENTRIES} 个），已中止解压"
            ));
        }
        if declared_size > MAX_SINGLE_BYTES {
            return Err(format!(
                "压缩包内单个文件过大（{name}，{}），已中止解压",
                fmt_gb(declared_size)
            ));
        }
        self.bytes = self.bytes.saturating_add(declared_size);
        if self.bytes > MAX_TOTAL_BYTES {
            return Err(format!(
                "压缩包解压后过大（超过 {}），已中止解压",
                fmt_gb(MAX_TOTAL_BYTES)
            ));
        }
        Ok(())
    }

    /// 记录**实际写出**的字节数（防「头里写 1 KB、实际吐出 10 GB」）。
    pub fn add_written(&mut self, n: u64) -> Result<(), String> {
        self.bytes = self.bytes.saturating_add(n);
        if self.bytes > MAX_TOTAL_BYTES {
            return Err(format!(
                "压缩包解压后过大（超过 {}），已中止解压",
                fmt_gb(MAX_TOTAL_BYTES)
            ));
        }
        Ok(())
    }
}

fn fmt_gb(bytes: u64) -> String {
    format!("{:.1} GB", bytes as f64 / 1024.0 / 1024.0 / 1024.0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_single_huge_entry() {
        let mut b = ZipBudget::new();
        assert!(b.admit("a.jar", MAX_SINGLE_BYTES + 1).is_err());
    }

    #[test]
    fn rejects_too_many_entries() {
        let mut b = ZipBudget::new();
        for i in 0..MAX_ENTRIES {
            b.admit(&format!("f{i}"), 1).unwrap();
        }
        assert!(b.admit("one-more", 1).is_err());
    }

    #[test]
    fn rejects_growing_past_total() {
        let mut b = ZipBudget::new();
        // 头里声明很小，实际写很多 —— add_written 要能兜住
        b.admit("ok", 10).unwrap();
        assert!(b.add_written(MAX_TOTAL_BYTES).is_err());
    }

    #[test]
    fn normal_archive_passes() {
        let mut b = ZipBudget::new();
        b.admit("instance.json", 400).unwrap();
        b.admit("mods/a.jar", 2 * 1024 * 1024).unwrap();
        b.add_written(2 * 1024 * 1024).unwrap();
    }
}
