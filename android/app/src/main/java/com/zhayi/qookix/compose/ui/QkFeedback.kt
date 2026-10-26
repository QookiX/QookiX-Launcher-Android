package com.zhayi.qookix.compose.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局「一句话反馈」。
 *
 * ── 为什么需要（2026-10-26）──────────────────────────────────────────
 * 这个 App 里**成功操作基本没有反馈**：删掉一个服务器、复制房间码、创建备份，
 * 界面都只是「悄悄变了」。用户不确定做没做成，于是重复点、或者去翻列表确认
 * （审查报告 A-1 / X-3 说的就是这类）。
 *
 * 实现刻意做成**一个全局状态 + 一处渲染**（`LauncherShell` 里的 Snackbar）：
 * 每个页面自己弹 Dialog/Toast 的话，样式和位置又会各不相同。
 *
 * 用法：`QkFeedback.show("已删除服务器")`，不用管生命周期 —— 显示一次后自动清空。
 */
object QkFeedback {

    private val _message = MutableStateFlow<String?>(null)

    /** 待显示的提示（null = 不显示）。只有 UI 层的渲染者读它。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 显示一条提示（覆盖尚未显示的上一条，避免排队堆积）。 */
    fun show(text: String) {
        _message.value = text
    }

    /** 渲染方消费掉之后调用，防止重组时重复弹。 */
    fun clear() {
        _message.value = null
    }
}
