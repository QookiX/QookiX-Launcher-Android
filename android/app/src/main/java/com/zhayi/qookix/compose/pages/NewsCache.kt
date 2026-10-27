package com.zhayi.qookix.compose.pages

/**
 * 新闻的进程级缓存。
 *
 * ## 为什么需要
 *
 * `fetch_news` 是**联网**命令（Rust 侧发 HTTP），而首页原来是这样写的：
 *
 * ```kotlin
 * var news by remember { mutableStateOf(emptyList<NewsItemUi>()) }
 * LaunchedEffect(bridgeReady) { bridge.invoke("fetch_news", …) }
 * ```
 *
 * `PageHost` 用 `when (dest)` 切页，**每次切回首页 `HomeRoute` 都会重建**：
 * `remember` 归零 → 新闻区块先空着 → 请求回来才填上。用户看到的就是
 * 「新闻每次都晚一步出来」。
 *
 * 缓存之后：切回来立刻显示上次的结果，后台再静默刷新（有 TTL，不会每次进页面
 * 都打接口）。
 *
 * ## 为什么不用 StateFlow
 *
 * 新闻只有一个消费点（首页），不需要跨页订阅；用普通字段 + 组合时的初始值
 * 就够了，省掉一层状态同步。
 */
internal object NewsCache {

    /** 最近一次成功拉到的新闻（失败时保持上一次，不清空）。 */
    var items: List<NewsItemUi> = emptyList()
        private set

    private var fetchedAt: Long = 0L

    /** 是否需要真去拉一次：没数据，或缓存已经超过 [TTL_MS]。 */
    fun shouldRefresh(now: Long = System.currentTimeMillis()): Boolean =
        items.isEmpty() || now - fetchedAt > TTL_MS

    fun update(list: List<NewsItemUi>, now: Long = System.currentTimeMillis()) {
        items = list
        fetchedAt = now
    }

    /** 10 分钟。新闻不是实时数据，没必要每次进首页都联网。 */
    private const val TTL_MS = 10 * 60_000L
}
