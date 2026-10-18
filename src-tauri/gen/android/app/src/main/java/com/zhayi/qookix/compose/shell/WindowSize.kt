package com.zhayi.qookix.compose.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 窗口尺寸档位（MD3 window size class）。
 *
 * 为什么自己算而不引 `material3-window-size-class`：
 * 那个库要用 Activity 的 WindowMetricsCalculator，在 Compose 里还得额外接线；
 * 我们只需要按宽度选布局，读 LocalConfiguration 就够，少一个依赖。
 *
 * 档位阈值取自 MD3 规范：
 *   Compact  < 600dp   手机竖屏        → 底部 NavigationBar
 *   Medium   600~839dp 手机横屏/小平板 → 左侧 NavigationRail
 *   Expanded ≥ 840dp   平板/大屏       → 左侧 NavigationRail（更宽）
 *
 * 实测本机（OnePlus 8 / 420dpi）：竖屏 411dp → Compact；
 * 横屏 914dp → Expanded。所以横屏一定走侧栏。
 */
enum class WindowSize { Compact, Medium, Expanded }

@Composable
fun rememberWindowSize(): WindowSize {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w < 600 -> WindowSize.Compact
        w < 840 -> WindowSize.Medium
        else -> WindowSize.Expanded
    }
}

/** 是否用左侧导航栏（横屏/平板）。 */
val WindowSize.useRail: Boolean
    get() = this != WindowSize.Compact
