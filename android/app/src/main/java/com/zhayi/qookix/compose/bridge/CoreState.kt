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

    /**
     * 最近一次拉列表失败的原因（null = 正常）。
     * 以前失败是静默的，界面会显示「还没有任何实例」—— 和「数据被删了」长得一样。
     */
    private val _instancesError = MutableStateFlow<String?>(null)
    val instancesError: StateFlow<String?> = _instancesError.asStateFlow()

    /** Rust 侧最近一次修订号（0 = 还没收到过任何 dirty 事件），仅供诊断。 */
    @Volatile
    var revision: Long = 0L
        private set

    /**
     * 服务器域变更次数（`scope=servers`）。
     *
     * 服务器列表页现在按它重拉，而不是「进页面拉一次、建完要退出重进」。
     * 没有做 [instances] 那样的全局镜像：服务器列表只有一个消费页，
     * 页面持有的是短生命周期的缓存，硬拉一个全局副本反而多一处要同步。
     */
    private val _serversRevision = MutableStateFlow(0L)
    val serversRevision: StateFlow<Long> = _serversRevision.asStateFlow()

    /**
     * 正在飞行的写操作 key（见 [beginWrite]）。
     *
     * UI 用它来做两件事：**防重复提交**（同一个 key 在飞时按钮置灰）
     * 与「操作中」指示。这替代了以前「先改列表、失败再回滚」的乐观更新 ——
     * 列表只由 [refresh] 的结果决定。
     */
    private val _pending = MutableStateFlow<Set<String>>(emptySet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private var bridge: RustBridge? = null
    private var context: Context? = null
    private var subscribed = false

    /** 实例维度的写操作 key（删除 / 改名 / 换图标共用）。 */
    fun instanceKey(id: String): String = "instance:$id"

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
                val scope = runCatching {
                    val o = org.json.JSONObject(payload)
                    revision = o.optLong("revision", revision)
                    o.optString("scope")
                }.getOrNull()
                when (scope) {
                    // scope 为空 = 老版本/格式异常：宁可两边都刷
                    null, "", "all" -> {
                        refresh()
                        _serversRevision.value += 1
                    }
                    "instances" -> refresh()
                    "servers" -> _serversRevision.value += 1
                    else -> Unit
                }
            }
        }
        refresh()
    }

    /**
     * 重新拉实例列表。等同于旧的 `instancesReloadKey++`。
     *
     * ## 为什么要串行化
     *
     * 批量操作（导入整合包后连着几条 dirty、连续删几个实例）会让
     * `list_instances` 并发在飞。多个响应**到达顺序不确定**，
     * 后到的旧数据会把新数据盖掉 —— 表现就是「列表偶尔回退到操作前」。
     * 这里保证同一时刻只有一个请求，期间的触发合并成一次补跑。
     */
    fun refresh() {
        val b = bridge ?: return
        if (refreshing) {
            refreshQueued = true
            return
        }
        refreshing = true
        InstancesRepo.load(context, b) { r ->
            refreshing = false
            r.onSuccess {
                _instances.value = it
                _instancesError.value = null
            }
            // 失败保留旧快照，但必须记下原因（空列表 + 零提示 = 用户以为数据没了）
            r.onFailure { e ->
                val ctx = context
                _instancesError.value = ctx?.getString(
                    com.zhayi.qookix.R.string.instances_read_failed,
                    friendlyError(ctx, e),
                ) ?: e.message
            }
            if (refreshQueued) {
                refreshQueued = false
                refresh()
            }
        }
    }

    private var refreshing = false
    private var refreshQueued = false

    /**
     * 登记一次写操作。返回 false 表示同一个 key 已有请求在飞
     *（调用方应当直接 return，不要重复发请求）。
     */
    fun beginWrite(key: String): Boolean {
        if (_pending.value.contains(key)) return false
        _pending.value = _pending.value + key
        return true
    }

    /** 解除登记。**无论成功失败都要调**，否则按钮会永远置灰。 */
    fun endWrite(key: String) {
        _pending.value = _pending.value - key
    }
}
