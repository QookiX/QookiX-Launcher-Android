package com.zhayi.qookix.compose.pages.settings

import com.zhayi.qookix.compose.theme.QkSpacing

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhayi.qookix.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 「外观」设置子页。
 *
 * 对应旧界面 SettingsView.vue 的 appearance tab。
 * 涉及的设置项（`settings.rs` 的 Settings 结构）：
 *   theme            system / light / dark
 *   theme_color      主题色（十六进制）
 *   ui_scale         界面缩放（60~200）
 *   background_image 背景图路径
 *   show_home_*      首页三个分区开关
 *   show_news        是否显示新闻
 */
/** 界面缩放的合法范围（见滑杆处的注释：上限从 200 收到 150）。 */
private const val UI_SCALE_MIN = 60f
private const val UI_SCALE_MAX = 150f

@Composable
fun AppearancePane(
    state: SettingsUiState,
    onChange: (SettingsPatch) -> Unit,
    // 弹图片选择器（由外层注入 —— 需要 Activity，这里拿不到）。
    // 传 null 表示宿主没提供（例如预览环境），按钮就变成禁用态。
    onPickBackground: (() -> Unit)? = null,
) {
    // 自定义取色面板的开关（HSL 三滑杆）
    var showColorPicker by remember { mutableStateOf(false) }

    if (showColorPicker) {
        HslColorPickerDialog(
            initial = state.themeColor,
            onConfirm = {
                onChange(SettingsPatch.ThemeColor(it))
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false },
        )
    }

    SettingsPane(title = stringResource(R.string.settings_appearance_title)) {
        // ── 主题 ──────────────────────────────────────────────────────
        SettingsGroup(stringResource(R.string.settings_appearance_theme_group)) {
            // ── 主题色 ────────────────────────────────────────────────
            //
            // ⚠️ 这块之前**完全缺失**：`themeColor` 在 state 里有字段、
            // 注释里也写了，但没有任何 UI 能改它 —— 用户永远只能用默认色。
            //
            // 对着旧 UI `SettingsView.vue` 补上：
            //   9 个预设色块 + 一个「自定义」入口（HSL 三滑杆面板）。
            // 旧注释解释了为什么不用系统取色器：
            //   「系统原生 <input type="color"> 那个对话框跟主题色场景对不上」。
            SettingsRow(
                title = stringResource(R.string.settings_appearance_theme_color),
                subtitle = stringResource(R.string.settings_appearance_theme_color_desc),
                column = true,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ThemeColorPresets.forEach { hex ->
                        ThemeColorSwatch(
                            hex = hex,
                            selected = state.themeColor.equals(hex, ignoreCase = true),
                            onClick = { onChange(SettingsPatch.ThemeColor(hex)) },
                        )
                    }
                    // 自定义入口：比预设小一圈 + 虚线环，
                    // 一眼看出是「入口」而不是第 10 个预设（照搬旧 UI 的视觉区分）。
                    val isPreset = ThemeColorPresets.any {
                        it.equals(state.themeColor, ignoreCase = true)
                    }
                    Surface(
                        onClick = { showColorPicker = true },
                        shape = CircleShape,
                        color = if (isPreset) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            parseHexColorOrNull(state.themeColor)
                                ?: MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isPreset) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(
                                        R.string.settings_appearance_custom_color
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            SettingsRow(
                title = stringResource(R.string.settings_appearance_dark_mode),
                subtitle = stringResource(R.string.settings_appearance_dark_mode_desc),
            column = true,
            ) {
                // 加横向滚动：窄屏下 chip 会溢出，后面的选项点不到（详见 GeneralPane 的说明）
                 FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(QkSpacing.xs),
                 ) {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(
                            selected = state.theme == m.id,
                            onClick = { onChange(SettingsPatch.Theme(m.id)) },
                            label = { Text(stringResource(m.labelRes)) },
                        )
                    }
                }
            }

            // ── 3D 预览渲染方式：选项已撤（2026-10-26 审查 P1-9）──────────
            //
            // 「流畅（webview）」依赖让隐藏 WebView 直接显示，而那条链路已经死了
            // （`RustBridge.findTauriWebView` 恒返回 null）：选了之后预览框一片空白、
            // 连「预览不可用」的提示都被 SkinPage 的分支吃掉，等于给用户一个骗人的开关。
            // 现在固定走 compose，等链路重新接通再把这行加回来。

            SettingsRow(
                title = stringResource(R.string.settings_appearance_ui_scale),
                subtitle = if (state.uiScale == 100) {
                    stringResource(R.string.settings_appearance_ui_scale_desc)
                } else {
                    stringResource(R.string.settings_appearance_ui_scale_desc_changed)
                },
            ) {                Text(
                    text = "${state.uiScale}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 滑杆单独一行（放进 trailing 会被压得太窄）
            Column(modifier = Modifier.padding(horizontal = QkSpacing.md, vertical = 4.dp)) {
                Slider(
                    value = state.uiScale.toFloat(),
                    onValueChange = { onChange(SettingsPatch.UiScale(it.toInt())) },
                    // ⚠️ 上限从 200 收到 150（2026-10-16 修，真机实测）。
                    //
                    // 200% 时布局**直接崩坏**：侧边栏吃掉半屏、导航项滚出屏幕、
                    // 设置页的垂直 tab 列表把内容挤没 —— 而「把缩放调回去」这个
                    // 操作本身也变得极难完成（**用户被自己的设置锁住**）。
                    //
                    // 150% 仍是有效的无障碍放大档位，但布局还撑得住。
                    valueRange = UI_SCALE_MIN..UI_SCALE_MAX,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── 恢复默认（防「调大了回不去」）─────────────────────
                //
                // 缩放是**全局生效**的：一旦调得过大，「点这个滑杆把它调小」
                // 这个操作本身都可能因为控件跑到屏幕外而做不到。
                //
                // 所以必须有一个**不依赖滑杆精度**的一键复位入口：
                // 放在滑杆正下方、始终可见，只在非默认时出现。
                if (state.uiScale != 100) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onChange(SettingsPatch.UiScale(100)) }) {
                            Text(stringResource(R.string.settings_appearance_reset_ui_scale))
                        }
                    }
                }
            }

            // ── 避让挖孔屏（2026-10-29 新增）─────────────────────────────
            //
            // 原来是无条件 `.windowInsetsPadding(WindowInsets.displayCutout)`，
            // 非挖孔屏设备上四边白留一圈边距。现在交给用户决定。
            val cutoutCtx = androidx.compose.ui.platform.LocalContext.current
            SettingsRow(
                title = stringResource(R.string.settings_appearance_cutout_inset),
                subtitle = stringResource(R.string.settings_appearance_cutout_inset_desc),
                trailing = {
                    Switch(
                        checked = com.zhayi.qookix.compose.UiPrefs.cutoutInset,
                        onCheckedChange = { on ->
                            // 两处都写：内存镜像驱动根布局即时变化，pref 保证重启保留
                            com.zhayi.qookix.compose.UiPrefs.cutoutInset = on
                            (cutoutCtx as? com.zhayi.qookix.compose.ComposeMainActivity)?.setCutoutInsetPref(on)
                        },
                    )
                },
            )

            // ── 侧边栏宽度：**已移除**（2026-10-29 用户要求）───────────────
            //
            // 原来这里有一个 140–260dp 的滑杆。现在侧栏改成了「展开 168 / 收起 88」
            // 两态固定宽度（长按切换），这个滑杆调的值根本不参与布局 ——
            // 用户拖了半天没任何变化，就是「没用」。
            //
            // 数据链路（Settings.sidebar_width / SettingsPatch.SidebarWidth）暂时留着，
            // 删除要动 Rust models + patch 枚举 + 迁移逻辑，收益不大；
            // 界面入口没了，就不会再有人误以为它能调。
            SettingsRow(
                title = stringResource(R.string.settings_appearance_background),
                subtitle = if (state.backgroundImage.isBlank())
                    stringResource(R.string.settings_appearance_background_unset)
                else stringResource(R.string.settings_appearance_background_set),
                showDivider = false,
            ) {
                TextButton(onClick = { onPickBackground?.invoke() }) {
                    Text(
                        if (state.backgroundImage.isBlank())
                            stringResource(R.string.settings_appearance_background_choose)
                        else stringResource(R.string.settings_appearance_background_change)
                    )
                }
            }

            if (state.backgroundImage.isNotBlank()) {
                SettingsRow(
                    title = stringResource(R.string.settings_appearance_background_clear),
                    subtitle = stringResource(
                        R.string.settings_appearance_background_clear_desc
                    ),
                    showDivider = false,
                ) {
                    TextButton(onClick = {
                        onChange(SettingsPatch.BackgroundImage(""))
                    }) {
                        Text(
                            text = stringResource(R.string.settings_appearance_clear),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        // ── 首页分区开关 ──────────────────────────────────────────────
        SettingsGroup(stringResource(R.string.settings_appearance_home_group)) {
            SettingsRow(
                title = stringResource(R.string.settings_appearance_home_downloads),
                subtitle = stringResource(R.string.settings_appearance_home_downloads_desc),
            ) {
                Switch(
                    checked = state.showHomeDownloads,
                    onCheckedChange = { onChange(SettingsPatch.ShowHomeDownloads(it)) },
                )
            }
            SettingsRow(
                title = stringResource(R.string.settings_appearance_home_recent),
                subtitle = stringResource(R.string.settings_appearance_home_recent_desc),
            ) {
                Switch(
                    checked = state.showHomeRecent,
                    onCheckedChange = { onChange(SettingsPatch.ShowHomeRecent(it)) },
                )
            }
            SettingsRow(
                title = stringResource(R.string.settings_appearance_home_stats),
                subtitle = stringResource(R.string.settings_appearance_home_stats_desc),
            ) {
                Switch(
                    checked = state.showHomeStats,
                    onCheckedChange = { onChange(SettingsPatch.ShowHomeStats(it)) },
                )
            }
            SettingsRow(
                title = stringResource(R.string.settings_appearance_home_news),
                subtitle = stringResource(R.string.settings_appearance_home_news_desc),
                showDivider = false,
            ) {
                Switch(
                    checked = state.showNews,
                    onCheckedChange = { onChange(SettingsPatch.ShowNews(it)) },
                )
            }
        }
    }
}

/** 深浅模式。 */
enum class ThemeMode(val id: String, @StringRes val labelRes: Int) {
    System("system", R.string.settings_appearance_theme_system),
    Light("light", R.string.settings_appearance_theme_light),
    Dark("dark", R.string.settings_appearance_theme_dark),
}

/**
 * 3D 皮肤预览的渲染方式。
 *
 * ── 为什么做成选项而不是直接选一个 ──────────────────────────────────
 * 两条路各有取舍，而且**代价是用户能感知的**（耗电/流畅度），
 * 不该由我替他决定：
 *
 *   [Compose]（默认）画面在 WebView 里离屏渲染后"搬"回 Compose 显示
 *             —— 界面 100% Compose，但每帧要过一遍
 *             readPixels→编码→base64→binder→解码，实测 22–25fps。
 *
 *   [WebView] 直接让那个 WebView 显示出来（它本来就在，承载 IPC）
 *             —— 实测它的 rAF 能跑 90fps，所以这里能到 ~60fps，
 *             但这一块由浏览器合成，更耗电。
 */
enum class PreviewRenderMode(val id: String, @StringRes val labelRes: Int) {
    Compose("compose", R.string.settings_appearance_preview_battery),
    WebView("webview", R.string.settings_appearance_preview_smooth),
}

/** 设置项的当前值（从 Rust `get_settings` 读来的子集）。 */
data class SettingsUiState(
    val theme: String = "system",
    val themeColor: String = "",
    val uiScale: Int = 100,
    /**
     * 侧边栏宽度（dp，2026-10-17 新增，用户可调）。
     *
     * 为什么可调：不同人屏幕/视力差异大 —— 有人觉得 168dp 太窄看不清
     * 「内容服务」这种 4 字标签，有人觉得太宽占掉内容区。固定值必然有人不满意。
     *
     * 范围 140~260：
     *   - 下限 140dp：再窄「内容服务」会被截断成「内容…」
     *   - 上限 260dp：再宽横屏下内容区会被挤得太窄
     */
    val sidebarWidth: Int = 168,
    /** 3D 预览渲染方式：compose（省电）/ webview（流畅）。 */
    val previewRenderMode: String = "compose",
    val backgroundImage: String = "",
    val showHomeDownloads: Boolean = true,
    val showHomeRecent: Boolean = true,
    val showHomeStats: Boolean = true,
    val showNews: Boolean = true,

    // ── 常规 ──────────────────────────────────────────────────────────────
    val closeBehavior: String = "minimize",
    val keepOpen: Boolean = true,
    val autoLaunch: Boolean = false,
    val orientation: String = "system",
    val mirror: String = "official",

    // ── 下载 ──────────────────────────────────────────────────────────────
    // ⚠️ 2026-10-29：默认 4 → 8，和 Rust 侧（settings.rs / download.rs）对齐。
    // 之前这里是 4，界面一打开就把 4 写回后端，实际并发被压到 4 路 ——
    // 用户反馈「导入整合包远慢于 ZL2 / PCL」的直接原因之一。
    val downloadThreads: Int = 8,
    val downloadChunkThreads: Int = 4,
    val autoUpdate: Boolean = true,
    val updateSource: String = "github",

    // ── 游戏 ──────────────────────────────────────────────────────────────
    val memoryMode: String = "auto",
    val maxMemoryMb: Int = 2048,
    val minMemoryMb: Int = 512,
    val jvmArgs: String = "",
    val gameArgs: String = "",
    val javaPath: String = "",

    // ── 存储 ──────────────────────────────────────────────────────────────
    val dataDir: String = "",
    val storageSummary: String = "",

    // ── 网络代理 ──────────────────────────────────────────────────────────
    val proxyMode: String = "system",
    val proxyAddress: String = "",

    // ── 内容服务 ──────────────────────────────────────────────────────────
    val curseforgeKey: String = "",
    val translateProvider: String = "default",
    /** 自动翻译正文（Rust 字段名：`body_translate_auto`）。 */
    val autoTranslate: Boolean = false,
    /** 自定义翻译 API（provider 选「自定义」时用）。 */
    val translateApiBase: String = "",
    val translateApiKey: String = "",
    val translateApiModel: String = "",

    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * 设置变更。
 *
 * 用 sealed class 而不是「整个 SettingsUiState 回写」的原因：
 * Rust 的 `update_settings` 接的是一个 **patch 对象**（只传改动的字段），
 * 传全量会把并发改动的其它字段覆盖掉。
 */
sealed class SettingsPatch {
    // 外观
    data class Theme(val value: String) : SettingsPatch()
    /** 主题色（`#RRGGBB`）。Rust 字段名是 snake_case 的 `theme_color`。 */
    data class ThemeColor(val value: String) : SettingsPatch()
    data class UiScale(val value: Int) : SettingsPatch()
    /** 侧边栏宽度（dp）。2026-10-17 新增，用户可调。 */
    data class SidebarWidth(val value: Int) : SettingsPatch()
    data class PreviewRenderMode(val value: String) : SettingsPatch()
    data class ShowHomeDownloads(val value: Boolean) : SettingsPatch()
    data class ShowHomeRecent(val value: Boolean) : SettingsPatch()
    data class ShowHomeStats(val value: Boolean) : SettingsPatch()
    data class ShowNews(val value: Boolean) : SettingsPatch()

    // 常规
    data class CloseBehavior(val value: String) : SettingsPatch()
    data class KeepOpen(val value: Boolean) : SettingsPatch()
    data class AutoLaunch(val value: Boolean) : SettingsPatch()
    data class Orientation(val value: String) : SettingsPatch()
    data class Mirror(val value: String) : SettingsPatch()

    // 下载
    data class DownloadThreads(val value: Int) : SettingsPatch()
    data class DownloadChunkThreads(val value: Int) : SettingsPatch()
    data class AutoUpdate(val value: Boolean) : SettingsPatch()
    data class UpdateSource(val value: String) : SettingsPatch()

    // 游戏
    data class MemoryMode(val value: String) : SettingsPatch()
    data class MaxMemory(val value: Int) : SettingsPatch()
    data class MinMemory(val value: Int) : SettingsPatch()

    // 启动参数（文本字段）
    data class JvmArgs(val value: String) : SettingsPatch()
    data class GameArgs(val value: String) : SettingsPatch()
    data class JavaPath(val value: String) : SettingsPatch()

    // 背景图（文本字段：空串 = 清除）
    data class BackgroundImage(val value: String) : SettingsPatch()

    // 内容服务
    data class CurseforgeKey(val value: String) : SettingsPatch()
    data class TranslateProvider(val value: String) : SettingsPatch()

    /** 自动翻译正文。Rust 侧字段名是 `body_translate_auto`（见 toJson）。 */
    data class AutoTranslate(val value: Boolean) : SettingsPatch()

    // 网络代理
    data class ProxyMode(val value: String) : SettingsPatch()
    data class ProxyAddress(val value: String) : SettingsPatch()

    // 自定义翻译 API
    data class TranslateApiBase(val value: String) : SettingsPatch()
    data class TranslateApiKey(val value: String) : SettingsPatch()
    data class TranslateApiModel(val value: String) : SettingsPatch()

    /**
     * 转成 Rust `update_settings` 期望的 JSON 字段名。
     *
     * ⚠️ 全部 camelCase —— `models.rs::Settings` 带
     * `#[serde(rename_all = "camelCase")]`，读和写都是这个规则。
     */
    fun toJson(): Pair<String, Any> = when (this) {
        is Theme -> "theme" to value
        // ⚠️ snake_case 例外：`settings.rs` 里的字段是 `theme_color`。
        // 写成 camelCase 会被 Rust **静默忽略**（patch 里未知字段不报错）——
        // 用户选了颜色却毫无变化，且不提示。与 jvm_args 那组是同类坑。
        is ThemeColor -> "theme_color" to value
        is UiScale -> "uiScale" to value
        is SidebarWidth -> "sidebarWidth" to value
        is PreviewRenderMode -> "previewRenderMode" to value
        is ShowHomeDownloads -> "showHomeDownloads" to value
        is ShowHomeRecent -> "showHomeRecent" to value
        is ShowHomeStats -> "showHomeStats" to value
        is ShowNews -> "showNews" to value

        is CloseBehavior -> "closeBehavior" to value
        is KeepOpen -> "keepOpen" to value
        is AutoLaunch -> "autoLaunch" to value
        is Orientation -> "orientation" to value
        is Mirror -> "mirror" to value

        is DownloadThreads -> "downloadThreads" to value
        is DownloadChunkThreads -> "downloadChunkThreads" to value
        is AutoUpdate -> "autoUpdate" to value
        is UpdateSource -> "updateSource" to value

        is MemoryMode -> "memoryMode" to value
        is MaxMemory -> "maxMemoryMb" to value
        is MinMemory -> "minMemoryMb" to value

        // ⚠️ 这三个是**例外**：snake_case，不是 camelCase。
        //
        // `models.rs` 里它们显式写了：
        //   #[serde(rename = "java_path", alias = "java_dir", alias = "javaDir")]
        //   #[serde(rename = "jvm_args",  alias = "java_args", alias = "javaArgs")]
        //   #[serde(alias = "gameArgs")]   ← 注意这个没有 rename，所以键是 `game_args`
        //
        // 旧前端也是这么发的（`settings.settings.jvm_args`）。
        // 一开始我按 camelCase 写，Rust 侧会**静默忽略**这两个字段 ——
        // 用户改了 JVM 参数却毫无效果，且没有任何报错。
        is JvmArgs -> "jvm_args" to value
        is GameArgs -> "game_args" to value
        is JavaPath -> "java_path" to value

        // 背景图：空串表示清除。
        // 注意字段名也是 snake_case（`background_image`），
        // 与 jvm_args 那组同属显式 rename 的例外。
        is BackgroundImage -> "background_image" to value

        // 内容服务。
        // ⚠️ 这三个又是 snake_case 例外（`models.rs` 里是
        // `curseforge_api_key` / `translate_provider` / `body_translate_auto`）。
        // 写成 camelCase 会被 Rust 静默忽略 —— patch 里未知字段不报错。
        is CurseforgeKey -> "curseforge_api_key" to value
        is TranslateProvider -> "translate_provider" to value
        is AutoTranslate -> "body_translate_auto" to value

        // 代理字段也是 snake_case（proxy_mode / proxy）
        is ProxyMode -> "proxy_mode" to value
        is ProxyAddress -> "proxy" to value

        is TranslateApiBase -> "translate_api_base" to value
        is TranslateApiKey -> "translate_api_key" to value
        is TranslateApiModel -> "translate_api_model" to value
    }
}

// ── 主题色 ────────────────────────────────────────────────────────────────

/**
 * 9 个预设主题色（逐字取自旧 UI `SettingsView.vue` 的 `themeColorPresets`）。
 *
 * 顺序也不改 —— 旧界面的色块是横向排布，顺序影响"哪个在最顺手的位置"。
 */
val ThemeColorPresets = listOf(
    "#e89a4b",
    "#ff6b35",
    "#e5534b",
    "#ec4899",
    "#8b5cf6",
    "#5aa2f0",
    "#22d3ee",
    "#4ec9a0",
    "#f5c518",
)

/** 一个圆形预设色块；选中时加一圈描边。 */
@Composable
private fun ThemeColorSwatch(
    hex: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val color = parseHexColorOrNull(hex) ?: MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        modifier = Modifier
            .size(32.dp)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    )
                } else {
                    Modifier
                },
            ),
    ) {}
}

/** 解析 `#RRGGBB` / `#AARRGGBB`；失败返回 null。 */
internal fun parseHexColorOrNull(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000L or v)
        8 -> Color(v)
        else -> null
    }
}

/** 把 HSL 转成 `#RRGGBB`。 */
private fun hslToHex(hDeg: Float, sPct: Float, lPct: Float): String {
    val h = ((hDeg % 360f) + 360f) % 360f
    val s = (sPct / 100f).coerceIn(0f, 1f)
    val l = (lPct / 100f).coerceIn(0f, 1f)
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
    val m = l - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(v: Float) = (min(1f, max(0f, v + m)) * 255f + 0.5f).toInt()
    return String.format("#%02X%02X%02X", ch(r1), ch(g1), ch(b1))
}

/** 从 `#RRGGBB` 粗略反推 HSL（用于打开面板时预置滑杆位置）。 */
private fun hexToHsl(hex: String): Triple<Float, Float, Float> {
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: 0xE89A4BL
    val r = ((v shr 16) and 0xFF) / 255f
    val g = ((v shr 8) and 0xFF) / 255f
    val b = (v and 0xFF) / 255f
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d == 0f) return Triple(0f, 0f, l * 100f)
    val s = d / (1f - abs(2f * l - 1f))
    val hDeg = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Triple(((hDeg % 360f) + 360f) % 360f, s * 100f, l * 100f)
}

/**
 * 自定义取色面板（HSL 三滑杆）。
 *
 * 旧 UI 的注释解释了为什么不用系统取色器：
 * 「系统原生 `<input type="color">` 那个对话框跟主题色场景对不上」。
 * Compose 里同理 —— 没有内置取色器，而 HSL 三滑杆对"调一个舒服的主色"
 * 这件事足够直观，也比塞一个色轮控件轻。
 */
@Composable
private fun HslColorPickerDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val start = remember { hexToHsl(initial) }
    var h by remember { mutableStateOf(start.first) }
    var s by remember { mutableStateOf(start.second) }
    var l by remember { mutableStateOf(start.third) }
    val hex = hslToHex(h, s, l)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_appearance_custom_color)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = parseHexColorOrNull(hex)
                            ?: MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(44.dp),
                    ) {}
                    Text(
                        text = hex,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = QkSpacing.sm),
                    )
                }
                Spacer(Modifier.size(12.dp))

                SliderRow(
                    stringResource(R.string.settings_appearance_color_hue),
                    h, 0f..360f, 0,
                ) { h = it }
                SliderRow(
                    stringResource(R.string.settings_appearance_color_saturation),
                    s, 0f..100f, 0,
                ) { s = it }
                SliderRow(
                    stringResource(R.string.settings_appearance_color_lightness),
                    l, 0f..100f, 0,
                ) { l = it }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(hex) }) {
                Text(stringResource(R.string.settings_appearance_apply))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(end = QkSpacing.xs),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}





