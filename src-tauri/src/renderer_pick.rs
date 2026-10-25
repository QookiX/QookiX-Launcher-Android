//! 按 MC 版本挑默认渲染器（实例设置为「自动」时用）。
//!
//! 从 `launch.rs` 拆出来（Q2 体量棘轮：那个文件只许变小），同时方便单测 ——
//! 版本号解析错了不会报错，只会安静地把 1.21.x 丢给 GL4ES 然后在真机上崩。

/// MC 版本的主/次版本号：`26.3` → (26, 3)、`1.21.8` → (1, 21)、`1.8.9` → (1, 8)。
///
/// ⚠️ 不能只取第一段。MC 从 **1.21** 起用到 `glMapBufferRange`（桌面 GL 特性），
/// 而 GL4ES 是 GL→GLES 转换层、不实现缓冲区映射；只判 `major` 会把 1.21.x
/// 全判成「老版本」，落到 GL4ES 上直接崩：`Can't map buffer, opengl error 0`。
pub(crate) fn version_parts(mc_version: &str) -> (u32, u32) {
    let mut parts = mc_version
        .trim()
        .split(['.', '-', '_', ' '])
        .filter(|s| !s.is_empty());
    let major = parts.next().and_then(|s| s.parse::<u32>().ok()).unwrap_or(0);
    let minor = parts.next().and_then(|s| s.parse::<u32>().ok()).unwrap_or(0);
    (major, minor)
}

/// 按 MC 版本自动挑渲染器（实例设置为「自动」时用）。
///
/// - **26.x 起**：MC 换了整套着色器体系（UBO + 新的 `core/*` 管线），GL4ES 在第一步就
///   `GL_INVALID_VALUE`，必须走 MobileGlues（我们另外给 MG 打了 UBO 词边界补丁，见
///   `MobileGlues-src` 那边的修改）。
/// - **1.21.x**：用到 `glMapBufferRange`，GL4ES 不实现缓冲区映射 → 也必须 MG
///   （真机崩在 `java.lang.IllegalStateException: Can't map buffer, opengl error 0`）。
///   这条以前漏了：只判 `>= 26`，于是 1.21.x 全部被判成「老版本」丢给 GL4ES。
/// - **更老的 1.x**：GL4ES 兼容性最好（1.8.9 / 1.18.2 等都实测能跑）。反过来 MG
///   在这些版本上会翻不动 `shaders/post/*`（实测 1.8.9 报 `Invalid #version`，
///   MC 判定「无效着色器」直接崩在启动阶段），所以不能拿 MG 当老版本的默认。
///
/// 自动结果只是**默认值**：实例设置里随时可以改成「跟随全局」或具体渲染器。
pub(crate) fn auto_renderer_for(mc_version: &str) -> &'static str {
    let (major, minor) = version_parts(mc_version);
    if major >= 26 || (major == 1 && minor >= 21) {
        "mobileglues"
    } else {
        "opengles2"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn version_parts_reads_major_and_minor() {
        assert_eq!(version_parts("1.21.8"), (1, 21));
        assert_eq!(version_parts("26.3"), (26, 3));
        assert_eq!(version_parts("1.8.9"), (1, 8));
        assert_eq!(version_parts("26.3-snapshot-3"), (26, 3));
        assert_eq!(version_parts("1.21.8-pre1"), (1, 21));
        assert_eq!(version_parts(""), (0, 0));
    }

    /// 关键回归：1.21.x 必须走 MobileGlues。
    /// 以前只判 `major >= 26`，1.21.x 被判成老版本 → GL4ES → 真机必崩。
    #[test]
    fn mc_1_21_and_newer_need_mobileglues() {
        assert_eq!(auto_renderer_for("1.21"), "mobileglues");
        assert_eq!(auto_renderer_for("1.21.8"), "mobileglues");
        assert_eq!(auto_renderer_for("1.21.1"), "mobileglues");
        assert_eq!(auto_renderer_for("26.3"), "mobileglues");
        assert_eq!(auto_renderer_for("26.3-snapshot-3"), "mobileglues");
    }

    #[test]
    fn older_versions_stay_on_gl4es() {
        assert_eq!(auto_renderer_for("1.20.6"), "opengles2");
        assert_eq!(auto_renderer_for("1.18.2"), "opengles2");
        assert_eq!(auto_renderer_for("1.8.9"), "opengles2");
        assert_eq!(auto_renderer_for(""), "opengles2");
    }
}
