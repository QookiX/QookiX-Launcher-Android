package com.zhayi.qookix.compose.pages.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zhayi.qookix.R

/**
 * 「关于」页的**同行致谢**。
 *
 * 单独成文件的原因：`AboutAndContentPanes.kt` 已经顶到体量预算上限
 * （`scripts/check-file-size.mjs`），而这段内容与页面其余部分没有耦合。
 *
 * ── 为什么要写这一节 ────────────────────────────────────────────────
 * 安卓上跑 Java 版 Minecraft 这条路是别人先蹚出来的：没有 PojavLauncher，
 * 就没有本项目的 JVM 引导与游戏内层；Zalith Launcher 2 的源码让我们确认了
 * LWJGL 模块的组织方式和游戏内 DNS 的解法；Fold Craft Launcher 等同类项目
 * 也各自趟过一批坑。这些是**同行**，不是依赖，所以不放在第三方声明里，
 * 单独致谢。
 */
@Composable
fun AboutCreditsSection(onOpenUrl: (String) -> Unit) {
    SettingsGroup(stringResource(R.string.about_credits_title)) {
        CreditsRow(
            name = "PojavLauncher",
            desc = stringResource(R.string.about_credits_pojav),
            url = "https://github.com/PojavLauncherTeam/PojavLauncher",
            onOpenUrl = onOpenUrl,
        )
        CreditsRow(
            name = "Zalith Launcher 2",
            desc = stringResource(R.string.about_credits_zl2),
            url = "https://github.com/ZalithLauncher/ZalithLauncher2",
            onOpenUrl = onOpenUrl,
        )
        CreditsRow(
            name = "Fold Craft Launcher",
            desc = stringResource(R.string.about_credits_fcl),
            url = "https://github.com/FCL-Team/FoldCraftLauncher",
            onOpenUrl = onOpenUrl,
        )
        CreditsRow(
            name = "Plain Craft Launcher 2",
            desc = stringResource(R.string.about_credits_pcl2),
            url = "https://github.com/Hex-Dragon/PCL2",
            onOpenUrl = onOpenUrl,
        )
        CreditsRow(
            name = "HMCL",
            desc = stringResource(R.string.about_credits_hmcl),
            url = "https://github.com/HMCL-dev/HMCL",
            onOpenUrl = onOpenUrl,
        )
        CreditsRow(
            name = "Axolotl Launcher",
            desc = stringResource(R.string.about_credits_axolotl),
            url = "https://github.com/Mystic-Stars/Axolotl",
            onOpenUrl = onOpenUrl,
        )
        CreditsRow(
            name = "Terracotta",
            desc = stringResource(R.string.about_credits_terracotta),
            url = "https://github.com/burningtnt/Terracotta",
            onOpenUrl = onOpenUrl,
            showDivider = false,
        )
    }
}

@Composable
private fun CreditsRow(
    name: String,
    desc: String,
    url: String,
    onOpenUrl: (String) -> Unit,
    showDivider: Boolean = true,
) {
    SettingsRow(
        title = name,
        subtitle = desc,
        onClick = { onOpenUrl(url) },
        showDivider = showDivider,
    ) {
        Text(
            text = stringResource(R.string.about_open),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
