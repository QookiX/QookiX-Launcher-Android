package com.zhayi.qookix.compose.motion

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 动效总开关与跨层共用的时长常量。
 *
 * 由 Compose [mutableStateOf] 驱动：系统「动画程序时长缩放」变更并在 `onResume`
 * 调用 [refresh] / [syncFrom] 后，运行中的组件会即时响应（关闭动效或恢复动效）。
 */
object QkMotionPolicy {

    private var _animationsEnabled by mutableStateOf(true)

    val animationsEnabled: Boolean
        get() = _animationsEnabled

    val isEnabled: Boolean
        get() = _animationsEnabled

    fun syncFrom(context: Context) {
        _animationsEnabled = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        } catch (_: Exception) {
            true
        }
    }

    fun refresh(context: Context) = syncFrom(context)

    /** 按下反馈比抬起快：抬手要能看到回弹，按下要跟手。 */
    const val PRESS_IN_MS = 80

    /** 共享元素飞行与启动浮层淡出共用的时长。 */
    const val HERO_FLIGHT_MS = 220
    const val OVERLAY_FADE_MS = 220

    /** 飞行兜底按毫秒算，不按帧数（90Hz 设备上 90 帧只有 1 秒）。 */
    const val HERO_LANDING_TIMEOUT_MS = 1000
    const val HERO_TOTAL_TIMEOUT_MS = 1500

    /** 骨架呼吸 / 波浪加载的循环周期。 */
    const val LOOP_MS = 900
    const val LOOP_SLOW_MS = 1100
}
