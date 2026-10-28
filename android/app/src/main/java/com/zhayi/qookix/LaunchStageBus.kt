package com.zhayi.qookix

/**
 * 启动阶段的「准备动作」状态，跨 Compose 与 View 层共享。
 *
 * ## 为什么需要
 *
 * 点启动后，如果实例缺文件会先**补全下载**（真机实例：`1.8.9.jar` 22.5MB）。
 * 这个阶段游戏进程还没起来，而 [StartupOverlay] 只能读**游戏日志**判断阶段 ——
 * 日志是空的，于是永远显示「正在准备运行环境… 已用时 23 秒」，
 * 用户完全看不出在下载什么、还有多少（反馈原话：「启动没动静了」）。
 *
 * Compose 侧的 `TasksRepo` 已经在解析 `install://progress` /
 * `download://progress` 了，这里用一个静态对象把「当前在做什么」传过去，
 * 让启动页能显示「正在补全 1.8.9.jar · 8.1/22.5 MB」。
 *
 * 用 `@Volatile` 而不是 State：写入在 Compose 线程、读取在
 * StartupOverlay 的刷新线程，不需要触发重组。
 */
object LaunchStageBus {

    /** 当前阶段的描述（null = 没有正在进行的准备动作）。 */
    @Volatile
    var detail: String? = null
        private set

    /** 进度 0f..1f；负值表示「不确定」（比如总数未知）。 */
    @Volatile
    var progress: Float = -1f
        private set

    fun update(detail: String?, progress: Float = -1f) {
        this.detail = detail
        this.progress = progress
    }

    fun clear() {
        detail = null
        progress = -1f
    }
}
