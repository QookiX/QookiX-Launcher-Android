package com.zhayi.qookix.compose.lansync

/*
 * 局域网同步页的纯格式化工具（2026-11-04 从 LanSyncPage.kt 拆出）。
 *
 * 拆出来是因为 LanSyncPage 长期顶在 744 行的预算上，UI-08 加断点续传时
 * 又要腾 7 行。这两个函数与页面状态、Compose 都无关，本就不该和 UI 混在一起。
 */

/** 1024 进制的人类可读体积：512 B / 1.5 KB / 27.3 MB。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unit = 0
    while (size >= 1024 && unit < units.lastIndex) {
        size /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format("%.1f %s", size, units[unit])
}

/** 剩余时间的口语化写法：45 秒 / 3 分 20 秒 / 1 时 05 分。 */
internal fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> "即将完成"
    seconds < 60 -> "${seconds} 秒"
    seconds < 3600 -> "${seconds / 60} 分 ${seconds % 60} 秒"
    else -> "${seconds / 3600} 时 ${(seconds % 3600) / 60} 分"
}
