package com.zhayi.qookix.compose.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity

/**
 * 缩放感知的屏幕宽度（dp）。
 *
 * ── 为什么不能用 `LocalConfiguration.screenWidthDp` ────────────────────
 * 它是**缩放的基准宽度**，不随设置里的「界面缩放」变化 —— 缩放只改了
 * `Density.density`。所以用户把缩放调到 150% 时，853dp 横屏的实际可用
 * 宽度只剩 853/1.5 ≈ 569dp，但按 853 判断会把内容挤没（设置页 2026-10-16
 * 就踩过一次，「界面缩放」四个字被挤成逐字竖排）。
 *
 * 这个坑在项目里已经重复出现过多次（SettingsPage 的注释记的是第 10 次），
 * 所以抽成公共函数，新增响应式判断时统一走这里，别再各写一套。
 *
 * 算法：基准 density 由 `Configuration.densityDpi / 160` 推导（160dpi = 1.0x），
 * 用「当前 density ÷ 基准 density」得到缩放倍数，再除掉。
 */
@Composable
fun scaledScreenWidthDp(): Float {
    val cfg = LocalConfiguration.current
    val density = LocalDensity.current.density
    val baseline = cfg.densityDpi / 160f
    if (baseline <= 0f) return cfg.screenWidthDp.toFloat()
    val scale = density / baseline
    return if (scale > 0f) cfg.screenWidthDp / scale else cfg.screenWidthDp.toFloat()
}
