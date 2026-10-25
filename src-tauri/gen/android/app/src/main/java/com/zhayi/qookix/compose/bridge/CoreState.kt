package com.zhayi.qookix.compose.bridge

import android.content.Context
import com.zhayi.qookix.compose.pages.InstanceUi
import com.zhayi.qookix.compose.pages.InstancesRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kotlin 侧的唯一状态镜像（A2 第一步，背景见 `docs/adr/0002-single-source-of-truth.md`）。
 *
 * ## 解决了什么
 *
 * 改造前：实例列表在 `PageHost` 里用 `remember { mutableStateOf(...) }` 存一份，
 * 每个「改了实例集合」的地方手工 `instancesReloadKey++` 去重拉。漏一处就是
 * 「删了模组/换了图标，切走再切回才生效」那类幽灵 bug。
 *
 * 现在：**只有这里持有列表**，UI 用 `collectAsState()` 只读；
 * Rust 写操作完成后推 `state://dirty`，这里统一重拉。
 *
 * ## 为什么不是全量 AppState
 *
 * 权威数据仍在 Rust（`instances.rs` / `servers.rs` / `progress.rs`），
 * 这里只是「最近一次拉到的快照」。不做 diff、不做乐观更新 ——
 * 那部分（禁止 UI 先改再请求）是 A2 的下一步，见 ADR 的增量路径。
 *
 * ## 线程
 *
 * `StateFlow` 本身线程安全；[RustBridge.listen] 的回调由 bridge 切到主线程，
 * [InstancesRepo.load] 的 `invoke` 在后台线程执行、结果回主线程。
 */
object CoreState {

    /** Rust 侧的失效通知事件名（见 `src-tauri/src/app_state.rs`）。 */
    private const val EVENT_DIRTY = "state://dirty"

    private val _instances = MutableStateFlow<List<InstanceUi>>(emptyList())

    /** 实例列表（UI 只读）。 */
    val instances: StateFlow<List<InstanceUi>> = _instances.asStateFlow()

    /** Rust 侧最近一次修订号（0 = 还没收到过任何 dirty 事件），仅供诊断。 */
    @Volatile
    var revision: Long = 0L
        private set

    private var bridge: RustBridge? = null
    private var context: Context? = null
    private var subscribed = false

    /**
     * 绑定 bridge 并拉一次快照。
     *
     * 幂等：重复调用只刷新数据，不会重复订阅事件
     * （`RustBridge.listen` 是**追加**语义，重复订阅会让同一个刷新跑多次）。
     */
    fun attach(context: Context?, bridge: RustBridge) {
        this.context = context
        this.bridge = bridge
        if (!subscribed) {
            subscribed = true
            bridge.listen(EVENT_DIRTY) { payload ->
                runCatching {
                    val o = org.json.JSONObject(payload)
                    revision = o.optLong("revision", revision)
                    o.optString("scope")
                }.getOrNull().let { scope ->
                    // scope 为空 = 老版本/格式异常：宁可多拉一次
                    if (scope.isNullOrBlank() || scope == "instances" || scope == "all") refresh()
                }
            }
        }
        refresh()
    }

    /** 重新拉实例列表。等同于旧的 `instancesReloadKey++`。 */
    fun refresh() {
        val b = bridge ?: return
        InstancesRepo.load(context, b) { r ->
            r.onSuccess { _instances.value = it }
            // 失败保持上一次的快照（不把列表清空 —— 清空会让用户以为实例没了）
        }
    }

    /** 实例被删除后立刻从本地快照里摘掉，避免等到下一次重拉才消失。 */
    fun removeLocally(id: String) {
        _instances.value = _instances.value.filterNot { it.id == id }
    }
}
