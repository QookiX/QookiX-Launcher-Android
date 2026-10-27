package com.zhayi.qookix.compose.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * QookiX 的 Material 3 主题。
 *
 * ── 为什么用动态取色（Material You）──────────────────────────────────────
 * 参考对象 ZL2 在测试机（OnePlus 8 / Android 16）上呈现粉紫色调，
 * 实测其系统设置是：
 *     color_source = home_wallpaper
 *     theme_style  = TONAL_SPOT
 * 也就是说**那套颜色不是 ZL2 写死的，是系统按壁纸生成的**。
 *
 * 所以这里不照抄任何具体色值 —— 照抄的话换个壁纸就不对了。
 * Android 12(API 31)+ 直接用 dynamicXxxColorScheme() 读系统色板，
 * 自动得到与 ZL2 一致的观感，且随壁纸联动。
 *
 * ── 静态回退 ────────────────────────────────────────────────────────────
 * API 24~30 没有 Monet，用下面这套固定色板兜底。
 * 底色沿用 QookiX 既有的深色一系（对齐 src/styles.css 的 --bg-0: #0B0D12），
 * 保证旧设备上仍是「同一个应用」而不是换了个 App。
 */
/**
 * 品牌种子色：曲奇金。
 *
 * 用户没选主题色、也没开动态取色时用它 —— 这是 QookiX 的「出厂观感」。
 * 只定义种子，具体每个槽位的颜色由 HCT 色板算法生成（见 [seedToScheme]）。
 */
val QookiXGold = Color(0xFFE89A4B)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFE8B48B),
    onPrimary = Color(0xFF442B10),
    primaryContainer = Color(0xFF61401F),
    onPrimaryContainer = Color(0xFFFFDCC0),

    secondary = Color(0xFFD8C3B0),
    onSecondary = Color(0xFF3B2E22),
    secondaryContainer = Color(0xFF534437),
    onSecondaryContainer = Color(0xFFF5DECA),

    tertiary = Color(0xFFB9CC97),
    onTertiary = Color(0xFF243516),
    tertiaryContainer = Color(0xFF3A4C2A),
    onTertiaryContainer = Color(0xFFD5E8B1),

    // MD3 的层级靠 surface-container 的明度差，而不是描边（见 MD3-REFERENCE.md）
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF13161C),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF4A4540),
    onSurfaceVariant = Color(0xFFCCC5BD),
    surfaceContainerLowest = Color(0xFF0B0D12),
    surfaceContainerLow = Color(0xFF171A20),
    surfaceContainer = Color(0xFF1B1E25),
    surfaceContainerHigh = Color(0xFF25282F),
    surfaceContainerHighest = Color(0xFF30333A),

    outline = Color(0xFF958F88),
    outlineVariant = Color(0xFF4A4540),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF81551F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC0),
    onPrimaryContainer = Color(0xFF2C1600),

    secondary = Color(0xFF6C5C4E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5DECA),
    onSecondaryContainer = Color(0xFF251A10),

    tertiary = Color(0xFF51643F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD5E8B1),
    onTertiaryContainer = Color(0xFF101F05),

    background = Color(0xFFFDF8F5),
    onBackground = Color(0xFF1E1B18),
    surface = Color(0xFFFDF8F5),
    onSurface = Color(0xFF1E1B18),
    surfaceVariant = Color(0xFFE9E1D9),
    onSurfaceVariant = Color(0xFF4A4540),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2EE),
    surfaceContainer = Color(0xFFF1ECE8),
    surfaceContainerHigh = Color(0xFFEBE6E2),
    surfaceContainerHighest = Color(0xFFE5E0DC),

    outline = Color(0xFF7B766F),
    outlineVariant = Color(0xFFCCC5BD),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** 动态取色是否可用（Android 12+）。 */
val dynamicColorAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 把用户选的主题色（`#RRGGBB`）派生成一套 MD3 配色。
 *
 * ── 为什么不能直接把选中的颜色当 primary ─────────────────────────────────
 * MD3 的配色不是"一个颜色"，而是**一组带明度关系的角色**
 * （primary / onPrimary / primaryContainer / surface 各层级…）。
 * 直接把 `#8b5cf6` 填进 `primary`，在深色底上会出现
 * 「亮紫底 + 白字」这种对比度不足的组合，而且 surface 系列还是旧色调，
 * 整体看着像"只有按钮变了色"。
 *
 * 这里做的是**简化版 tonal palette**：
 *   1. 解析 seed 色的 HSL
 *   2. 深色模式用高明度、低饱和度的变体当 primary；
 *      浅色模式反之 —— 保证与各自底色有足够对比
 *   3. surface 系列统一带一点 seed 的色相（chroma 很低），
 *      这样整套 UI 有统一的"色调倾向"，而不是只有强调色变色
 *
 * 注：完整实现应该移植 Material Color Utilities 的 HCT 色彩空间，
 * 但那是一个不小的库；这里用 HSL 近似，视觉上已经能体现"换了主题色"。
 */
private fun seedToScheme(seed: Color, dark: Boolean): androidx.compose.material3.ColorScheme {
    // 用 Material Color Utilities 的 HCT 算法生成整套色板（materialkolor 封装）。
    //
    // ⚠️ 2026-10-27：这里原来是**手写的 HSL 近似** —— hslColor(h, s*0.75f, 0.78f)
    // 那一套。HSL 的明度/饱和度和 M3 的 HCT 不是一回事：手动算出来的 primary
    // 对比度不稳、surface 容易发脏（色相带进去却压不住彩度），这正是一眼看上去
    // 「配色不像 MD3」的根源。换成 HCT 后，同一颗种子生成的每一档都在标准
    // tone 上，层次和对比度天然成立。
    //
    // TonalSpot 是 M3 默认风格（色相不跑偏、够克制）；想更鲜艳可以换 Vibrant。
    return com.materialkolor.dynamicColorScheme(
        primary = seed,
        isDark = dark,
        // 纯黑底：OLED 省电，但深色层次会消失（surface 全黑，卡片分不出来）。
        // 我们要 ZL2 那种「一档亮度分层」，所以不开。
        isAmoled = false,
        style = com.materialkolor.PaletteStyle.TonalSpot,
    )
}

/** RGB → HSL（h 角度、s/l 都是 0–1）。 */
private fun rgbToHsl(c: Color): Triple<Float, Float, Float> {
    val r = c.red; val g = c.green; val b = c.blue
    val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
    val l = (mx + mn) / 2f
    val d = mx - mn
    if (d == 0f) return Triple(0f, 0f, l)
    val s = d / (1f - kotlin.math.abs(2f * l - 1f))
    val h = when (mx) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return Triple(((h % 360f) + 360f) % 360f, s, l)
}

/** HSL → Color（h 角度、s/l 都是 0–1）。 */
private fun hslColor(h: Float, s: Float, l: Float): Color {
    val hh = ((h % 360f) + 360f) % 360f
    val ss = s.coerceIn(0f, 1f); val ll = l.coerceIn(0f, 1f)
    val c = (1f - kotlin.math.abs(2f * ll - 1f)) * ss
    val x = c * (1f - kotlin.math.abs(((hh / 60f) % 2f) - 1f))
    val m = ll - c / 2f
    val (r1, g1, b1) = when {
        hh < 60f -> Triple(c, x, 0f)
        hh < 120f -> Triple(x, c, 0f)
        hh < 180f -> Triple(0f, c, x)
        hh < 240f -> Triple(0f, x, c)
        hh < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
    )
}

/** 解析 `#RRGGBB` / `#AARRGGBB`；失败返回 null。 */
private fun parseHexOrNull(hex: String): Color? {
    val h = hex.trim().removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000L or v)
        8 -> Color(v)
        else -> null
    }
}

/**
 * 主题入口。
 *
 * ⚠️⚠️ 2026-10-15 修（用户反馈「点了色块没变化 + 改深浅模式没反应」）：
 *
 * 之前这个函数**只有 dynamicColor / darkTheme 两个分支，根本没有
 * `themeColor` 参数** —— 用户在设置里选的主题色被写进 `settings.json`
 * 就再也没有下文，渲染时完全不读。深浅模式同样：调用方写的是裸的
 * `QookiXTheme { }`，`darkTheme` 一直吃默认值（跟随系统），
 * 用户选的 light/dark 被忽略。
 *
 * 也就是说：**「存下来了」不等于「生效了」**。当时只验证了
 * settings.json 落盘，没验证渲染结果 —— 这是漏测。
 *
 * @param darkTheme 是否深色。由调用方把三态（system/light/dark）换算后传入。
 * @param themeColor 用户选的主题色（`#RRGGBB`）。**非空时压过动态取色** ——
 *        用户显式选的颜色优先级最高。
 * @param dynamicColor 是否启用 Material You 动态取色（仅当 themeColor 为空时生效）。
 */
// ⚠️ 2026-10-23（分支 exp/kotlin-upgrade 记录）：试过切 `MaterialExpressiveTheme`，**失败** ——
// material3 **1.4.0** 里它和 `ExperimentalMaterial3ExpressiveApi` 都是 **internal** ✗，
// 编译期直接 "Cannot access ... it is internal in file"。
// 公开的 expressive API 要更晚的版本，而那些版本要求 **AGP 9.1 + compileSdk 37** ✗（另一场大升级）。
// 所以这条分支只保留 Kotlin 2.2.21 + Compose BOM 2025.09.01（能编过、Compose 更新到 1.9.2），
// 主题仍是常规 MaterialTheme；Expressive 观感走「手工靠拢」那条路（见 master 的讨论）。
@Composable
fun QookiXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeColor: String = "",
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val seed = remember(themeColor) {
        themeColor.takeIf { it.isNotBlank() }?.let { parseHexOrNull(it) }
    }
    val colorScheme = when {
        // 用户选了主题色 → 由它派生的配色（深浅各自一套）
        seed != null -> seedToScheme(seed, darkTheme)

        dynamicColor && dynamicColorAvailable ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> FallbackDark
        else -> FallbackLight
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = QookiXTypography,
        shapes = QookiXShapes,
        content = content,
    )
}

