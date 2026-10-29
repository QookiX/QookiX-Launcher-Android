package com.zhayi.qookix

import android.app.Notification
import android.content.Context
import android.os.Build

/**
 * 兼容各 API 的通知 Builder。
 *
 * ⚠️ 2026-10-29（外部审计 B10 核实成立）：
 * `Notification.Builder(Context, String channelId)` 是 **API 26（O）** 才引入的构造函数，
 * 而本项目 `minSdk = 24`。多个前台服务直接写了双参构造 ——
 * 在 Android 7.x（API 24/25）上会抛 `NoSuchMethodError`，服务一启动就崩。
 *
 * 统一走这里：API 26+ 带 channel（8.0 起不带 channel 的通知会被系统丢弃），
 * 以下用单参构造（那时还没有 channel 概念）。
 *
 * 注意：调用方仍需在 API 26+ 自己创建 `NotificationChannel`（各服务里已经有 `createChannel()`）。
 */
internal fun qkNotificationBuilder(context: Context, channelId: String): Notification.Builder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Notification.Builder(context, channelId)
    } else {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }
