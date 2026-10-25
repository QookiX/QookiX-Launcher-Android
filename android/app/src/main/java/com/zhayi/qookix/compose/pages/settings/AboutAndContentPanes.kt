package com.zhayi.qookix.compose.pages.settings

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import com.zhayi.qookix.compose.ui.QkImage

/**
 * 「关于」设置子页。
 *
 * 包含版本信息、开源许可、第三方声明。
 *
 * ⚠️ 第三方声明是 **GPL-3.0 的合规要求**，不是装饰：
 *   - 本项目以 GPL-3.0 发布
 *   - 其中 PojavLauncher 部分为 LGPL-3.0（独立动态库 libpojavexec.so）
 *   - Terracotta 为 AGPL-3.0（独立进程 + localhost HTTP 交互，未链接）
 * 旧界面的这条声明在 `SettingsView.vue` 的 about tab 里，
 * 见仓库根的 `THIRD_PARTY_NOTICES.md`。删除界面时必须保留这个入口。
 */
@Composable
fun AboutPane(
    state: SettingsUiState,
    appVersion: String,
    onOpenLicenses: () -> Unit,
    onOpenProject: () -> Unit,
    /** 打开桌面版仓库（可选，没传就禁用按钮）。 */
    onOpenDesktopRepo: (() -> Unit)? = null,
    /** 打开任意 URL（用于开源项目的仓库/协议链接）。 */
    onOpenUrl: ((String) -> Unit)? = null,
    /**
     * 检查更新（2026-10-18 补回）。
     *
     * ⚠️ 旧 Vue 版「关于」页有个「更新」区块（检查更新 / 当前版本 / 自动检查开关），
     * Compose 迁移时整个丢了 —— 但 Rust 侧 `updater.rs` 三个命令一直都在。
     */
    updateState: AppUpdateUiState = AppUpdateUiState(),
    onCheckUpdate: () -> Unit = {},
    onDownloadUpdate: () -> Unit = {},
    onInstallUpdate: (String) -> Unit = {},
    onToggleAutoCheck: ((Boolean) -> Unit)? = null,
    /**
     * 社区贡献者名单。
     *
     * 来源：`android/app/src/main/assets/contributors.json`（随 APK 打包）。
     * 由 PageHost 读出后传进来 —— 这个 composable 不碰 IO，方便预览和测试。
     */
    contributors: List<String> = emptyList(),
) {

    SettingsPane(title = stringResource(R.string.about_title)) {
        // ── 品牌展示卡（格子桌布 + 曲奇 logo）────────────────────────
        //
        // ⚠️ 2026-10-17 恢复：迁移时这一块整体丢了，用户指出
        // 「那个橙色的和白色的网格的背景，相当于桌面启动器也有，
        // 是一个品牌标志，但是你给删掉了」。
        Box(modifier = Modifier.fillMaxWidth()) {
            Column {
                AboutShowcase(height = 160)

                Spacer(Modifier.height(QkSpacing.sm))

                // ⚠️ 不能同时用 verticalAlignment = CenterVertically 和 alignByBaseline ——
                // 后者要求 Row 用默认（Top）对齐才会按基线排列。
                Row {
                    // 两段文字按**基线**对齐（原来各占各的高度，版本号"浮"在标题下方）
                    Text(
                        text = "QookiX Launcher Android",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(10.dp))

                    // 版本号（2026-10-18：连点彩蛋已整体移除）
                    Text(
                        text = "v$appVersion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(horizontal = QkSpacing.xxs),
                    )
                }
            }
        }

        Spacer(Modifier.height(QkSpacing.lg))

        // ══════════════════════════════════════════════════════════════
        // 以下顺序与用户给的截图一致（2026-10-18）：
        //   品牌卡 → 检查更新 → 开发者（两个容器）→ 链接按钮 → 许可 → 依赖
        // ══════════════════════════════════════════════════════════════

        // ── 检查更新 ──────────────────────────────────────────────────
        UpdateSection(
            currentVersion = appVersion,
            state = updateState,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
            onToggleAutoCheck = onToggleAutoCheck,
        )

        Spacer(Modifier.height(QkSpacing.sm))

        // ── 开发者 + 社区贡献（一个卡片）────────────────────────────────
        //
        // 2026-10-18 改：原来是两个独立 Surface 并排（像两块漂浮方块）。
        // 用户要求「做成之前那种卡片的格式，给他们包起来」。
        //
        // 现在这**一个卡片**里有三段：
        //   1. ZhaYi（开发者）
        //   2. 维墨笙（协助开发者）
        //   3. 社区贡献（默认折叠，点开才展开）
        //
        // 贡献者名单来自 `bootstrap-public/contributors.json` ——
        // 构建时打进 APK 的 assets/，用户改完重新打包即可，不需要联网。
        SettingsGroup {
            DeveloperRow(
                name = "ZhaYi",
                role = stringResource(R.string.about_dev_role_author),
                avatarUrl = "https://github.com/ZhaYi-Miao.png",
                onClick = { onOpenUrl?.invoke("https://github.com/ZhaYi-Miao") },
                showDivider = true,
            )
            DeveloperRow(
                name = stringResource(R.string.about_dev_name_contributor),
                role = stringResource(R.string.about_dev_role_contributor),
                avatarUrl = "https://github.com/weimosheng.png",
                onClick = { onOpenUrl?.invoke("https://github.com/weimosheng") },
                showDivider = true,
            )
            // 社区贡献：默认折叠，只显示名字
            ContributorSection(contributors = contributors)
        }

        Spacer(Modifier.height(QkSpacing.sm))

        // ── 链接按钮（两列网格，与截图一致）──────────────────────────
        //
        // 地址都取自旧 Vue 的 SettingsView.vue，不是我自己编的：
        //   官网 https://www.qookix.cn/
        //   仓库/反馈/更新日志 = GitHub
        //   Q 群 https://qm.qq.com/q/91keQnJ8dy
        //   爱发电 = 用户指定的 https://afdian.com/a/weimosheng
        val aboutLinks = listOf(
            AboutLink(R.string.about_official_site, "https://www.qookix.cn/"),
            AboutLink(
                R.string.about_github_repo2,
                "https://github.com/QookiX/QookiX-Launcher-Android",
                LinkKind.GitHub,
            ),
            AboutLink(
                R.string.about_issues,
                "https://github.com/QookiX/QookiX-Launcher-Android/issues",
                LinkKind.GitHub,
            ),
            AboutLink(
                R.string.about_changelog,
                "https://github.com/QookiX/QookiX-Launcher-Android/blob/master/CHANGELOG.md",
                LinkKind.GitHub,
            ),
            AboutLink(R.string.about_qq_group, "https://qm.qq.com/q/91keQnJ8dy"),
            AboutLink(R.string.about_afdian, "https://afdian.com/a/weimosheng"),
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
            maxItemsInEachRow = 2,
        ) {
            aboutLinks.forEach { link ->
                OutlinedButton(
                    onClick = { onOpenUrl?.invoke(link.url) },
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                ) {
                    // 小图标：GitHub 链接给 GitHub 标，其余给「外链」箭头。
                    // 用户要求「相当于一个箭头朝外那种外链的标」。
                    LinkIcon(kind = link.kind)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(link.titleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(QkSpacing.sm))

        // ── 许可证 ────────────────────────────────────────────────────
        SettingsGroup(stringResource(R.string.about_this_project)) {
            SettingsRow(
                title = "QookiX Launcher Android",
                subtitle = "GNU General Public License v3.0",
                onClick = { onOpenUrl?.invoke("https://github.com/QookiX/QookiX-Launcher-Android/blob/master/LICENSE") },
                showDivider = true,
            ) {
                Text(
                    text = stringResource(R.string.about_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SettingsRow(
                title = stringResource(R.string.about_third_party_notices),
                subtitle = stringResource(R.string.about_third_party_notices_desc),
                onClick = onOpenLicenses,
                showDivider = false,
            ) {
                Text(
                    text = stringResource(R.string.about_view),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(QkSpacing.sm))


        Spacer(Modifier.height(QkSpacing.sm))

        // ── 开源协议与依赖清单（2026-10-17 补全）─────────────────────
        //
        // 用户指出：「以前关于页面还有很多我用到的项目的开源协议，
        // 还有他们的仓库链接，这些都是你要在设置里边再补上的，现在都是有缺失」。
        //
        // 分组展示（和旧 Vue 的 aboutDeps 三个分组对应），
        // 每项显示：名称 + 版本 + 许可证徽章，点击打开仓库，右侧可看协议原文。
        DepGroup.entries.forEach { group ->
            val items = ABOUT_DEPS.filter { it.group == group }
            if (items.isNotEmpty()) {
                SettingsGroup(
                    stringResource(
                        R.string.about_dep_group_title,
                        stringResource(group.labelRes),
                        items.size,
                    )
                ) {
                    items.forEachIndexed { idx, dep ->
                        SettingsRow(
                            title = dep.name,
                            subtitle = buildString {
                                if (dep.version.isNotBlank()) append("${dep.version} · ")
                                append(dep.license)
                            },
                            onClick = { onOpenUrl?.invoke(dep.url) },
                            showDivider = idx != items.lastIndex,
                        ) {
                            // 许可证徽章：小圆角标签，颜色按协议类型区分
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = licenseBadgeColor(dep.license),
                            ) {
                                Text(
                                    text = dep.license.substringBefore(' ').substringBefore('/'),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = QkSpacing.xs, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(QkSpacing.sm))
            }
        }

        // ── 免责声明 ──────────────────────────────────────────────────
        Text(
            text = stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = QkSpacing.xxs),
        )
    }
}

/**
 * 许可证徽章配色。
 *
 * 按「传染性强度」给颜色，而不是随机：
 *   - GPL/AGPL（强传染）→ 醒目暖色，提示这类组件改动了必须开源
 *   - LGPL（弱传染）    → 中性色
 *   - MIT/BSD/Apache（宽松）→ 低调色
 *
 * 这不是法律判断，只是让用户在长列表里一眼分辨哪几项义务最重。
 */
@Composable
private fun licenseBadgeColor(license: String): Color {
    val l = license.uppercase()
    return when {
        l.contains("AGPL") -> MaterialTheme.colorScheme.errorContainer
        l.contains("GPL") -> MaterialTheme.colorScheme.tertiaryContainer
        l.contains("LGPL") -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
}

/** 「内容服务」子页（Modrinth / CurseForge 的凭据与翻译服务）。 */
@Composable
fun ContentServicesPane(
    state: SettingsUiState,
    onChange: (SettingsPatch) -> Unit,
    onOpenPlugins: () -> Unit = {},
    /**
     * 清翻译缓存（返回释放的字节数）。
     *
     * 对应旧 UI `SettingsView.vue` 的 clearTranslations。
     * 用途：翻译服务改版后旧译文可能过时，清掉让它重新翻。
     */
    onClearTranslationCache: (() -> Unit)? = null,
    /** 清缓存进行中。 */
    clearingTranslation: Boolean = false,
    /** 上次清理结果的提示（「已清理 N KB」/「缓存是空的」）。 */
    clearTranslationHint: String? = null,
    /** 测试自定义翻译 API 连通性（D1）；null = 不显示按钮。 */
    onTestTranslateApi: (() -> Unit)? = null,
    /** 测试进行中。 */
    testingTranslate: Boolean = false,
    /** 测试结果提示。 */
    translateTestHint: String? = null,
) {
    var editingKey by remember { mutableStateOf(false) }
    // 自定义翻译 API 的三个字段（provider 选「自定义」时才需要）
    var editingApiBase by remember { mutableStateOf(false) }
    var editingApiKey by remember { mutableStateOf(false) }
    var editingApiModel by remember { mutableStateOf(false) }

    SettingsPane(title = stringResource(R.string.content_services_title)) {
        SettingsGroup(stringResource(R.string.content_services_curseforge_group)) {
            SettingsRow(
                title = "API Key",
                subtitle = state.curseforgeKey.ifBlank {
                    stringResource(R.string.content_services_builtin_key)
                },
                showDivider = false,
            ) {
                TextButton(onClick = { editingKey = true }) {
                    Text(
                        if (state.curseforgeKey.isBlank())
                            stringResource(R.string.content_services_set)
                        else stringResource(R.string.content_services_modify)
                    )
                }
            }
        }

        SettingsGroup(stringResource(R.string.content_services_translate_group)) {
            SettingsRow(
                title = stringResource(R.string.content_services_translate_service),
                subtitle = stringResource(R.string.content_services_translate_service_desc),
                column = true,
            ) {
                // ⚠️ 用 FlowRow 而不是 Row + horizontalScroll（2026-10-17 改）。
                //
                // Row+horizontalScroll 虽然"能滑"，但**初始状态第三项被裁一半** ——
                // 竖屏 384dp 宽时，"内置/自定义/百度"三个 chip 放不下，
                // 用户看到的是「内置 自定义 [半截]」，会以为选项不完整或界面坏了。
                //
                // FlowRow 会自动换行：窄屏时"百度"换到第二行，全部完整可见。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                ) {
                    TranslateProvider.entries.forEach { t ->
                        androidx.compose.material3.FilterChip(
                            selected = state.translateProvider == t.id,
                            onClick = { onChange(SettingsPatch.TranslateProvider(t.id)) },
                            label = { Text(stringResource(t.labelRes)) },
                        )
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.content_services_auto_translate),
                subtitle = stringResource(R.string.content_services_auto_translate_desc),
                showDivider = false,
            ) {
                // 这里原本是 `Text("")` —— 一个空的占位，看起来像开关但点不了。
                // 换成真正的 Switch。
                Switch(
                    checked = state.autoTranslate,
                    onCheckedChange = { onChange(SettingsPatch.AutoTranslate(it)) },
                )
            }

            // ── 自定义翻译 API ──────────────────────────────────────────
            //
            // ⚠️ 2026-10-17 修（审计 BUG-2）：
            // 翻译服务选「自定义」时，后端会用 `translate_provider=custom`，
            // 但 base / key / model **没有任何填写入口** —— 用户选了之后
            // 走的是空 base，翻译必然失败，而界面**没有任何提示**。
            //
            // SettingsPatch 里三个写入字段早就有了（TranslateApiBase/Key/Model），
            // 缺的只是触发它们的 UI。这里补上，且只在选了 custom 时才显示，
            // 避免对用内置翻译的用户造成干扰。
            if (state.translateProvider == TranslateProvider.Custom.id) {
                SettingsRow(
                    title = stringResource(R.string.content_services_api_base),
                    subtitle = state.translateApiBase.ifBlank {
                        stringResource(R.string.content_services_api_base_unset)
                    },
                ) {
                    TextButton(onClick = { editingApiBase = true }) {
                        Text(
                            if (state.translateApiBase.isBlank())
                                stringResource(R.string.content_services_set)
                            else stringResource(R.string.content_services_modify)
                        )
                    }
                }
                SettingsRow(
                    title = "API Key",
                    subtitle = state.translateApiKey.ifBlank {
                        stringResource(R.string.content_services_unset)
                    },
                ) {
                    TextButton(onClick = { editingApiKey = true }) {
                        Text(
                            if (state.translateApiKey.isBlank())
                                stringResource(R.string.content_services_set)
                            else stringResource(R.string.content_services_modify)
                        )
                    }
                }
                SettingsRow(
                    title = stringResource(R.string.content_services_model),
                    subtitle = state.translateApiModel.ifBlank {
                        stringResource(R.string.content_services_unset)
                    },
                    showDivider = onTestTranslateApi == null,
                ) {
                    TextButton(onClick = { editingApiModel = true }) {
                        Text(
                            if (state.translateApiModel.isBlank())
                                stringResource(R.string.content_services_set)
                            else stringResource(R.string.content_services_modify)
                        )
                    }
                }
                // 连通性测试（D1）：`test_translate_api` 一直没有 UI ——
                // 用户填完 base/key/model 无从知道能不能用。
                if (onTestTranslateApi != null) {
                    SettingsRow(
                        title = stringResource(R.string.content_services_test_api),
                        subtitle = translateTestHint
                            ?: stringResource(R.string.content_services_test_api_desc),
                        showDivider = false,
                    ) {
                        TextButton(onClick = onTestTranslateApi, enabled = !testingTranslate) {
                            Text(if (testingTranslate) stringResource(R.string.content_services_testing)
                                 else stringResource(R.string.content_services_test))
                        }
                    }
                }
            }

            // 清翻译缓存：翻译服务改版后旧译文可能过时，清掉让它重新翻。
            if (onClearTranslationCache != null) {
                SettingsRow(
                    title = stringResource(R.string.content_services_translate_cache),
                    subtitle = clearTranslationHint
                        ?: stringResource(R.string.content_services_translate_cache_desc),
                    showDivider = false,
                ) {
                    TextButton(
                        onClick = onClearTranslationCache,
                        enabled = !clearingTranslation,
                    ) {
                        Text(
                            if (clearingTranslation)
                                stringResource(R.string.content_services_clearing)
                            else stringResource(R.string.content_services_clear)
                        )
                    }
                }
            }
        }

        SettingsGroup(stringResource(R.string.content_services_plugins_group)) {
            SettingsRow(
                title = stringResource(R.string.content_services_plugins_platform),
                subtitle = stringResource(R.string.content_services_plugins_platform_desc),
                showDivider = false,
            ) {
                TextButton(onClick = onOpenPlugins) {
                    Text(stringResource(R.string.content_services_manage))
                }
            }
        }
    }

    // CurseForge API Key 输入。
    //
    // 为什么需要用户自己填：内置 Key 有速率限制（多人共用必然被限流），
    // 自己申请一个能显著改善搜索体验。这个是可选的。
    if (editingKey) {
        TextInputDialog(
            title = stringResource(R.string.content_services_curseforge_dialog_title),
            initial = state.curseforgeKey,
            label = "API Key",
            helperText = stringResource(R.string.content_services_curseforge_help),
            onDismiss = { editingKey = false },
            onConfirm = { v ->
                editingKey = false
                onChange(SettingsPatch.CurseforgeKey(v))
            },
        )
    }

    // ── 自定义翻译 API 的三个输入框 ──────────────────────────────────────
    // 只在 provider 选「自定义」时才可能被触发（行也只在那时显示）。
    if (editingApiBase) {
        TextInputDialog(
            title = stringResource(R.string.content_services_translate_api_title),
            initial = state.translateApiBase,
            label = stringResource(R.string.content_services_translate_api_label),
            helperText = stringResource(R.string.content_services_translate_api_help),
            onDismiss = { editingApiBase = false },
            onConfirm = { v ->
                editingApiBase = false
                onChange(SettingsPatch.TranslateApiBase(v))
            },
        )
    }
    if (editingApiKey) {
        TextInputDialog(
            title = stringResource(R.string.content_services_translate_key_title),
            initial = state.translateApiKey,
            label = "API Key",
            helperText = stringResource(R.string.content_services_translate_key_help),
            onDismiss = { editingApiKey = false },
            onConfirm = { v ->
                editingApiKey = false
                onChange(SettingsPatch.TranslateApiKey(v))
            },
        )
    }
    if (editingApiModel) {
        TextInputDialog(
            title = stringResource(R.string.content_services_translate_model_title),
            initial = state.translateApiModel,
            label = stringResource(R.string.content_services_translate_model_label),
            helperText = stringResource(R.string.content_services_translate_model_help),
            onDismiss = { editingApiModel = false },
            onConfirm = { v ->
                editingApiModel = false
                onChange(SettingsPatch.TranslateApiModel(v))
            },
        )
    }
}

/** 翻译服务提供方。 */
enum class TranslateProvider(val id: String, @StringRes val labelRes: Int) {
    Builtin("default", R.string.content_services_provider_builtin),
    Custom("custom", R.string.content_services_provider_custom),
    Baidu("baidu_web", R.string.content_services_provider_baidu),
}




/**
 * 关于页的开源依赖清单（移植自旧 Vue `SettingsView.vue` 的 `aboutDeps`）。
 *
 * ⚠️ 2026-10-17 补（用户指出「以前关于页面还有很多我用到的项目的开源协议，
 * 还有他们的仓库链接，这些都是你要在设置里边再补上的，现在都是有缺失」）。
 *
 * ── 为什么这份清单是**法律要求**而不是装饰 ──
 * 本项目以 GPL-3.0 发布，其中包含：
 *   - PojavLauncher 的 LGPL-3.0 部分（libpojavexec.so 动态链接）
 *   - Terracotta 的 AGPL-3.0 部分（独立进程 + localhost HTTP，未链接）
 *   - GL4ES / Mesa / LWJGL / OpenJDK / FreeType / OpenAL 等随包分发组件
 * 各协议均要求**保留版权声明与许可文本**，删除入口即不合规。
 *
 * ── 分组说明 ──
 * frontend = Compose/Android 侧直接依赖
 * rust     = src-tauri 侧直接依赖
 * bundled  = 随 APK 分发的二进制组件（启动器能跑起来的地基）
 */
enum class DepGroup(@StringRes val labelRes: Int) {
    Frontend(R.string.about_dep_group_frontend),
    Rust(R.string.about_dep_group_rust),
    Bundled(R.string.about_dep_group_bundled),
}

/** 一个开源依赖项。 */
data class AboutDep(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val licenseUrl: String,
    val group: DepGroup,
)

/**
 * 全部依赖。
 *
 * ⚠️ 这里的版本号是**声明值**，不是从构建里读的 ——
 * 和旧 Vue 一致（那边也是硬编码）。
 * 真正需要精确的地方是 [license] 和 [licenseUrl]，它们对应法律义务。
 */
val ABOUT_DEPS: List<AboutDep> = listOf(
    // ── Android / Compose 侧 ────────────────────────────────────────
    AboutDep("Jetpack Compose", "BOM 2024.09", "Apache-2.0",
        "https://developer.android.com/jetpack/compose",
        "https://www.apache.org/licenses/LICENSE-2.0", DepGroup.Frontend),
    AboutDep("Material 3", "1.3", "Apache-2.0",
        "https://m3.material.io",
        "https://www.apache.org/licenses/LICENSE-2.0", DepGroup.Frontend),
    AboutDep("Kotlin", "2.0", "Apache-2.0",
        "https://kotlinlang.org",
        "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt", DepGroup.Frontend),
    AboutDep("Kotlin Coroutines", "1.8", "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.coroutines",
        "https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt", DepGroup.Frontend),
    AboutDep("OkHttp", "4.12", "Apache-2.0",
        "https://square.github.io/okhttp/",
        "https://github.com/square/okhttp/blob/master/LICENSE.txt", DepGroup.Frontend),
    AboutDep("Coil", "2.6", "Apache-2.0",
        "https://coil-kt.github.io/coil/",
        "https://github.com/coil-kt/coil/blob/main/LICENSE.txt", DepGroup.Frontend),
    AboutDep("kotlinx.serialization", "1.7", "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.serialization",
        "https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt", DepGroup.Frontend),

    // ── Rust 后端 ──────────────────────────────────────────────────
    AboutDep("Tauri", "2", "MIT / Apache-2.0",
        "https://tauri.app",
        "https://github.com/tauri-apps/tauri/blob/dev/LICENSE", DepGroup.Rust),
    AboutDep("Tokio", "1", "MIT",
        "https://tokio.rs",
        "https://github.com/tokio-rs/tokio/blob/master/LICENSE", DepGroup.Rust),
    AboutDep("reqwest", "0.12", "MIT / Apache-2.0",
        "https://github.com/seanmonstar/reqwest",
        "https://github.com/seanmonstar/reqwest/blob/master/LICENSE", DepGroup.Rust),
    AboutDep("serde", "1", "MIT / Apache-2.0",
        "https://serde.rs",
        "https://github.com/serde-rs/serde/blob/master/LICENSE", DepGroup.Rust),
    AboutDep("jni", "0.21", "MIT / Apache-2.0",
        "https://github.com/jni-rs/jni-rs",
        "https://github.com/jni-rs/jni-rs/blob/master/LICENSE", DepGroup.Rust),

    // ── 随包分发的二进制组件（法律义务最重的一组）──────────────────
    AboutDep("PojavLauncher", "", "GPL-3.0 / LGPL-3.0",
        "https://github.com/PojavLauncherTeam/PojavLauncher",
        "https://github.com/PojavLauncherTeam/PojavLauncher/blob/v3_openjdk/LICENSE", DepGroup.Bundled),
    AboutDep("Terracotta", "", "AGPL-3.0",
        "https://github.com/burningtnt/Terracotta",
        "https://github.com/burningtnt/Terracotta/blob/main/LICENSE", DepGroup.Bundled),
    AboutDep("GL4ES", "1.1.5", "MIT",
        "https://github.com/ptitSeb/gl4es",
        "https://github.com/ptitSeb/gl4es/blob/master/LICENSE", DepGroup.Bundled),
    AboutDep("LWJGL 3", "3.3", "BSD-3-Clause",
        "https://lwjgl.org",
        "https://github.com/LWJGL/lwjgl3/blob/master/LICENSE.md", DepGroup.Bundled),
    AboutDep("Caciocavallo", "", "GPL-2.0 + Classpath",
        "https://github.com/PojavLauncherTeam/caciocavallo",
        "https://github.com/PojavLauncherTeam/caciocavallo/blob/master/LICENSE", DepGroup.Bundled),
    AboutDep("OpenJDK", "17 / 21", "GPL-2.0 + Classpath",
        "https://openjdk.org",
        "https://openjdk.org/legal/gplv2+ce.html", DepGroup.Bundled),
    AboutDep("Mesa (OSMesa / zink)", "", "MIT",
        "https://mesa3d.org",
        "https://gitlab.freedesktop.org/mesa/mesa/-/blob/main/docs/license.rst", DepGroup.Bundled),
    AboutDep("FreeType", "2.13", "FreeType / GPL-2.0",
        "https://freetype.org",
        "https://gitlab.freedesktop.org/freetype/freetype/-/blob/master/docs/FTL.TXT", DepGroup.Bundled),
    AboutDep("OpenAL Soft", "", "LGPL-2.1",
        "https://openal-soft.org",
        "https://github.com/kcat/openal-soft/blob/master/COPYING", DepGroup.Bundled),
    AboutDep("MobileGlues", "", "LGPL-2.1",
        "https://github.com/MobileGL-Dev/MobileGlues-release",
        "https://github.com/MobileGL-Dev/MobileGlues/blob/main/LICENSE", DepGroup.Bundled),
    AboutDep("MobileGlues (Render Backend)", "", "LGPL-2.1",
        "https://gitlab.freedesktop.org/mesa/mesa",
        "https://gitlab.freedesktop.org/mesa/mesa/-/blob/main/docs/license.rst", DepGroup.Bundled),
)






// ════════════════════════════════════════════════════════════════════
// 启动器自更新（2026-10-18 补回）
//
// 旧 Vue 版有完整的 updater（检查 → 下载 → 交系统安装器），Compose 迁移时
// 前端整块丢了，但 Rust `updater.rs`（300 行，3 个命令注册在 lib.rs:352-354）
// 一直都在 —— 也就是说这纯粹是「前端没接」。
// ════════════════════════════════════════════════════════════════════

/**
 * 检查更新的 UI 状态。
 *
 * 对应 Rust `updater::UpdateInfo`：
 * `{ available, version?, current_version, notes?, size?, downloaded_path? }`
 */
data class AppUpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    /** 服务端最新版本号；null = 还没检查过 */
    val latestVersion: String? = null,
    /** 有没有新版本 */
    val available: Boolean = false,
    val notes: String? = null,
    /** 已下载好的 APK 路径；非空说明可以直接装 */
    val downloadedPath: String? = null,
    val error: String? = null,
    val autoCheck: Boolean = true,
    /**
     * 下载进度 0f..1f（-1f = 未知总量）。
     *
     * ⚠️ 2026-10-18 加：Rust 每 200ms 发 `app-update-progress` 事件带着
     * `{downloaded, total}`，但之前 **Compose 端根本没监听** ——
     * 用户点「下载」后只有一个转圈小圈，看不到下了多少，容易以为卡死。
     */
    val progress: Float = -1f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/**
 * 「更新」区块。
 *
 * 三种状态：
 *   1. 还没检查 / 已是最新 → 显示当前版本 + 「检查更新」按钮
 *   2. 有新版本、未下载 → 显示新版本号 + 「下载」
 *   3. 已下载 → 「安装」
 */
@Composable
private fun UpdateSection(
    currentVersion: String,
    state: AppUpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (String) -> Unit,
    onToggleAutoCheck: ((Boolean) -> Unit)?,
) {
    SettingsGroup(stringResource(R.string.about_update_group)) {
        SettingsRow(
            title = stringResource(R.string.about_update_check),
            subtitle = when {
                state.checking -> stringResource(R.string.about_update_checking)
                // 下载中显示「已下 / 总量 MB」，让用户知道在动
                state.downloading && state.totalBytes > 0 -> stringResource(
                    R.string.about_update_downloading,
                    state.downloadedBytes / 1024 / 1024,
                    state.totalBytes / 1024 / 1024,
                )
                state.downloading -> stringResource(R.string.about_update_downloading_unknown)
                state.error != null -> state.error
                state.available && state.downloadedPath != null ->
                    stringResource(R.string.about_update_ready, state.latestVersion.orEmpty())
                state.available ->
                    stringResource(R.string.about_update_available, state.latestVersion.orEmpty())
                state.latestVersion != null ->
                    stringResource(R.string.about_update_latest, currentVersion)
                else ->
                    stringResource(R.string.about_update_hint, currentVersion)
            },
            showDivider = onToggleAutoCheck != null,
            onClick = when {
                // 已下载 → 点这行是「安装」
                state.available && state.downloadedPath != null ->
                    ({ onInstall(state.downloadedPath!!) } as (() -> Unit))
                // 有新版本未下载 → 点这行是「下载」
                state.available -> onDownload
                // 检查中 → 不可点
                state.checking || state.downloading -> null
                // 其它 → 检查
                else -> onCheck
            },
            trailing = {
            when {
                // 有确定进度 → 画确定环，一眼看出还剩多少
                state.downloading && state.progress >= 0f -> {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
                state.checking || state.downloading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                }
                state.available && state.downloadedPath != null -> {
                    Text(
                        text = stringResource(R.string.about_update_install),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.available -> {
                    Text(
                        text = stringResource(R.string.about_update_download),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                else -> {
                    Text(
                        text = stringResource(R.string.about_update_check_short),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            },
        )

        if (onToggleAutoCheck != null) {
            SettingsRow(
                title = stringResource(R.string.about_update_auto),
                subtitle = stringResource(R.string.about_update_auto_desc),
                showDivider = false,
                trailing = {
                Switch(
                    checked = state.autoCheck,
                    onCheckedChange = { onToggleAutoCheck(it) },
                )
                },
            )
        }
    }
}

/**
 * 「关于」页的一个外链按钮。
 *
 * @param kind 决定按钮上的小图标（2026-10-18 加，用户要求
 *             「能跳转到 github 的东西加 github 标，其它加外链箭头」）
 */
private enum class LinkKind { GitHub, External }

private data class AboutLink(
    @StringRes val titleRes: Int,
    val url: String,
    val kind: LinkKind = LinkKind.External,
)

/**
 * 开发者**行**（卡片内的一条），对应旧 Vue 的 `.dev-card`。
 *
 * 2026-10-18 改：原来叫 DevCard 且自己带 Surface（两块独立卡片并排）。
 * 用户要求「做成之前那种卡片的格式，给他们包起来」——
 * 现在它只是**一行**，外层的 `SettingsGroup` 负责卡片背景和圆角，
 * 与「许可证」「依赖」那些区块视觉一致。
 *
 * 头像用 GitHub 头像服务 `https://github.com/<user>.png` ——
 * 不额外打包图片资源，也不会因为开发者换头像而过时。
 */
@Composable
private fun DeveloperRow(
    name: String,
    role: String,
    avatarUrl: String,
    onClick: () -> Unit,
    showDivider: Boolean,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = QkSpacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // QkImage 加载失败时会画占位块；外面再套一层圆底色，
            // 保证「网速慢 / 无网络」时也像个头像而不是灰方块。
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                QkImage(
                    model = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                    cornerRadius = 18.dp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = role,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = QkSpacing.md),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}



// ════════════════════════════════════════════════════════════════════
// 社区贡献（2026-10-18 新增）
//
// 名单来源：`bootstrap-public/contributors.json`
//   → 构建时由 `scripts/build-web-assets.mjs` 拷进 APK 的 `assets/contributors.json`
//   → 运行时用 AssetManager 读出来
//
// 这样改名单只要改那个 JSON、重新打包，不需要动 Kotlin 代码，也不用联网。
// ════════════════════════════════════════════════════════════════════

// ════════════════════════════════════════════════════════════════════
// 社区贡献（2026-10-18）
//
// 名单来源：`bootstrap-public/contributors.json`
//   → 构建时拷进 APK 的 `assets/contributors.json`
//   → 运行时用 AssetManager 读出来
//
// ⚠️ 只显示名字，**不做点击跳转**（用户要求）——
// 这个名单可能非常长（几十上百人），每个人配链接没必要，
// 而且长按误触反而烦人。要联系方式就写进名字里。
// ════════════════════════════════════════════════════════════════════

/**
 * 从 APK 的 `assets/contributors.json` 读取贡献者**名字**列表。
 *
 * JSON 就是一个字符串数组：
 * ```json
 * { "contributors": ["张三", "李四"] }
 * ```
 *
 * 读不到 / 格式不对都返回空列表 —— 这是锦上添花的信息，
 * 不该因为一个 JSON 写错就让「关于」页崩掉。
 */
fun loadContributors(context: android.content.Context): List<String> {
    return runCatching {
        val text = context.assets.open("contributors.json")
            .bufferedReader()
            .use { it.readText() }
        val root = org.json.JSONObject(text)
        val arr = root.optJSONArray("contributors") ?: return emptyList()
        // 兼容两种写法：纯字符串，或 {"name": "..."} 对象（只取 name）
        (0 until arr.length()).mapNotNull { i ->
            val s = arr.optString(i).trim()
            if (s.isNotBlank()) return@mapNotNull s
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("name").trim().takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}

/**
 * 卡片里的「社区贡献」一段：**默认折叠**，点标题展开。
 *
 * 展开后把所有名字用「、」连成一段自然换行的文字 ——
 * 不逐行列出。名单可能上百人，逐行会把「关于」页拉得极长；
 * 一段文字反而好扫，也省空间。
 *
 * 折叠状态用 `rememberSaveable`，转屏后不丢。
 */
@Composable
private fun ContributorSection(contributors: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column {
        // ── 标题行（可点，带展开箭头）───────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = QkSpacing.md, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.about_contributors),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (contributors.isNotEmpty()) {
                Text(
                    text = "${contributors.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        if (expanded) {
            Text(
                text = if (contributors.isEmpty()) {
                    // 名单为空时告诉维护者文件在哪，而不是干瘪的「暂无」
                    stringResource(R.string.about_contributors_empty)
                } else {
                    // 顿号连接，自然换行
                    contributors.joinToString(separator = "、")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = QkSpacing.md, end = 16.dp, bottom = 14.dp),
            )
        }
    }
}



/**
 * 链接按钮上的小图标。
 *
 * - GitHub 链接 → GitHub 的猫头 logo（Material 图标库里**没有品牌图标**，
 *   所以这里手写 SVG path）
 * - 其它链接 → Material 的 `OpenInNew`（就是「方框 + 朝外箭头」，通用外链语义）
 */
@Composable
private fun LinkIcon(kind: LinkKind) {
    when (kind) {
        LinkKind.GitHub -> Icon(
            imageVector = githubIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        LinkKind.External -> Icon(
            imageVector = Icons.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * GitHub 的猫头 logo。
 *
 * ⚠️ 为什么手写而不是用 Material Icons：
 * Google 的 Material Icons 只收通用符号，**不含第三方品牌 logo**。
 * `material-icons-extended` 里翻遍了也没有 GitHub。
 *
 * 路径取自 GitHub 官方 mark（24×24 视口），单色，走 `tint` 上色。
 */
private val githubIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHub",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            // GitHub 猫头轮廓（官方 mark 的 path data）
            moveTo(12f, 2f)
            curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
            curveTo(2f, 16.42f, 4.87f, 20.17f, 8.84f, 21.5f)
            curveTo(9.34f, 21.58f, 9.5f, 21.28f, 9.5f, 21.02f)
            curveTo(9.5f, 20.78f, 9.5f, 20.15f, 9.5f, 19.38f)
            curveTo(6.73f, 19.97f, 6.14f, 18.03f, 6.14f, 18.03f)
            curveTo(5.68f, 16.87f, 5.03f, 16.57f, 5.03f, 16.57f)
            curveTo(4.12f, 15.95f, 5.1f, 15.96f, 5.1f, 15.96f)
            curveTo(6.1f, 16.03f, 6.63f, 16.98f, 6.63f, 16.98f)
            curveTo(7.52f, 18.51f, 8.97f, 18.07f, 9.54f, 17.83f)
            curveTo(9.63f, 17.18f, 9.89f, 16.74f, 10.17f, 16.51f)
            curveTo(7.95f, 16.26f, 5.62f, 15.45f, 5.62f, 11.61f)
            curveTo(5.62f, 10.5f, 6.01f, 9.6f, 6.66f, 8.89f)
            curveTo(6.56f, 8.63f, 6.2f, 7.6f, 6.76f, 6.23f)
            curveTo(6.76f, 6.23f, 7.59f, 5.96f, 9.5f, 7.26f)
            curveTo(10.29f, 7.04f, 11.15f, 6.93f, 12f, 6.93f)
            curveTo(12.85f, 6.93f, 13.71f, 7.04f, 14.5f, 7.26f)
            curveTo(16.41f, 5.96f, 17.24f, 6.23f, 17.24f, 6.23f)
            curveTo(17.8f, 7.6f, 17.44f, 8.63f, 17.34f, 8.89f)
            curveTo(17.99f, 9.6f, 18.38f, 10.5f, 18.38f, 11.61f)
            curveTo(18.38f, 15.46f, 16.04f, 16.25f, 13.82f, 16.5f)
            curveTo(14.17f, 16.78f, 14.48f, 17.33f, 14.48f, 18.17f)
            curveTo(14.48f, 19.37f, 14.47f, 20.34f, 14.47f, 20.58f)
            curveTo(14.47f, 20.84f, 14.63f, 21.15f, 15.14f, 21.06f)
            curveTo(19.11f, 19.72f, 22f, 15.97f, 22f, 12f)
            curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
            close()
        }
    }.build()
}

