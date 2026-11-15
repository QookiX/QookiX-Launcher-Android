package com.zhayi.qookix.compose.motion

import android.content.Context
import android.provider.Settings

/**
 * 动效总开关与跨层共用的时长常量。
 *
 * 系统「动画程序时长缩放」设为 0 时（开发者选项关动画 / 部分无障碍设置），
 * 所有语义 spec 走 snap()，无限循环动画改静态。之前 QookiXMotion 注释里承诺的
 * 「低端机上能整体调」一直没有实现入口。
 */
object QkMotionPolicy {

    /** 由 `ComposeMainActivity.onCreate` 同步一次；系统设置改动后下次冷启动生效。 */
    @Volatile
    var animationsEnabled: Boolean = true
        private set

    fun syncFrom(context: Context) {
        animationsEnabled = try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        } catch (_: Exception) {
            true
        }
    }

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
