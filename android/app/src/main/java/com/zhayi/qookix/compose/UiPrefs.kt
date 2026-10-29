package com.zhayi.qookix.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 需要**跨页面即时生效**的界面偏好。
 *
 * ## 为什么不用 SharedPreferences 直接读
 *
 * `SharedPreferences` 的变化不会触发 Compose 重组。像「避让挖孔屏」这种开关，
 * 用户在外观页一拨，根布局的 padding 就要立刻跟着变 —— 必须是一个
 * `mutableStateOf`。
 *
 * ## 持久化在哪
 *
 * 真实值仍存在 SharedPreferences（`ComposeMainActivity.cutoutInsetPref()`），
 * 这里只是**内存镜像**：启动时读一次，改的时候同时写两处。
 * 这样既能在重启后保留，又能即时驱动界面。
 */
object UiPrefs {

    /**
     * 是否避让挖孔屏的 `displayCutout`。
     *
     * - `true`（默认）：横屏时给系统挖孔/刘海留出安全边距，内容不会被摄像头压住；
     * - `false`：完全铺满。挖孔屏机型上侧栏会被压住一部分，但视觉更「满」。
     *
     * 为什么要给开关：2026-10-28 加这个避让是为了修 issue #2 的遮挡问题，
     * 但它让四边都多出一点空白 —— 不是挖孔屏的设备上纯属浪费。
     * 用户 2026-10-29 要求能自己关掉。
     */
    var cutoutInset by mutableStateOf(true)
}
