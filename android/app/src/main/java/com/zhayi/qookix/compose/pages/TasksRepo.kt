package com.zhayi.qookix.compose.pages

import com.zhayi.qookix.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import android.util.Log
import com.zhayi.qookix.compose.bridge.*
import org.json.JSONObject

/**
 * 下载 / 安装任务的实时状态仓库。
 *
 * ── 数据来源（全部是 Rust 推来的事件）────────────────────────────────────
 *   install://progress   —— 安装阶段（步骤型：manifest/natives/done…）
 *   download://progress  —— 下载阶段（文件数 + 字节数 + 速度）
 *   launch://state       —— 游戏运行状态（running / 退出）
 *   launch://exit        —— 游戏退出（含退出码）
 *
 * 这些事件名与 `src-tauri/src/progress.rs` 里的 emit 一一对应。
 *
 * ── 与旧实现（stores/tasks.ts）的关系 ──────────────────────────────────
 * 旧实现把这些事件累积成 `TaskEntry[]`。这里保留同样的语义：
 *   - 按 taskId 归并
 *   - 保留「进行中 / 已完成」两态
 *   - 速度用滑动窗口平均（旧实现是 5 秒窗口 + 平滑）
 *
 * 但**不照搬它的字段命名**（那是 Web 侧的内部结构）；
 * 对外只暴露 Compose 页面需要的 TaskUi。
 */
class TasksRepo(private val bridge: RustBridge, private val context: android.content.Context) {

    companion object {
        private const val TAG = "TasksRepo"
        /** 速度滑动窗口（毫秒）。与旧实现一致。 */
        private const val SPEED_WINDOW_MS = 5000L
        /** 进度多久没变算停滞（与 UI 提示文案保持一致）。 */
        private const val STALL_MS = 30_000L
        /** 停滞检测心跳间隔。 */
        private const val HEARTBEAT_MS = 5_000L
    }

    /** 内部任务状态（比 TaskUi 多，用于算速度）。 */
    private data class Entry(
        var id: Long = 0,
        /** 归属实例（用于「同一实例别重复开下载」的判断）。 */
        var instanceId: String = "",
        var title: String = "",
        var message: String = "",
        var stage: String = "",
        var fileDone: Int = 0,
        var fileTotal: Int = 0,
        var bytesDone: Long = 0,
        var bytesTotal: Long = 0,
        var stepDone: Int = 0,
        var stepTotal: Int = 0,
        var speed: Double = 0.0,
        var finished: Boolean = false,
        var ok: Boolean = true,
        var samples: MutableList<Pair<Long, Long>> = mutableListOf(),
        var startedAt: Long = System.currentTimeMillis(),
        /** 最后一次「进度有变化」的时刻（用于停滞检测）。 */
        var lastProgressAt: Long = System.currentTimeMillis(),
        /** 上次记录的进度量（字节/步骤/文件，取三者任一变化）。 */
        var lastProgressMark: Long = -1
    )

    private val tasks = linkedMapOf<Long, Entry>()

    /** 订阅者：状态变化时被调用（主线程）。 */
    /**
     * 游戏启动进度（2026-10-17 补，审计 H-5）。
     *
     * `launch://progress` 的 payload = { step: String, progress: Double }。
     * launch.rs 里有 7 处派发（下 Java、解压运行库、校验资源…）。
     *
     * ⚠️ 原来 Compose 完全不监听 —— 启动那几十秒里用户只看到黑屏，
     * 不知道是在下载还是在崩溃边缘。Vue 侧 LaunchProgress.vue 是正常的。
     */
    var launchStep by mutableStateOf("")
        private set
    var launchProgress by mutableStateOf(0f)
        private set
    /** 当前正在运行的实例 id（launch://state 推送，首页显示「运行中」用）。 */
    var runningInstanceId by mutableStateOf<String?>(null)
        private set

    /**
     * 任务列表的**单一真相源**（A2）。
     *
     * 改造前只有一个 `onChange` 单槽位：第二个订阅者会把第一个顶掉
     * （`InstanceDetailRoute` 想单独订阅就只能靠外层转手传快照）。
     * StateFlow 天生多订阅，页面各自 `collectAsState()` 即可。
     */
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(DownloadsUiState())
    val state: kotlinx.coroutines.flow.StateFlow<DownloadsUiState> = _state

    /**
     * 兼容旧调用点（单槽位）。新代码请直接 collect [state]。
     */
    private var onChange: ((DownloadsUiState) -> Unit)? = null

    /**
     * 游戏退出的订阅者（instanceId, exitCode）。
     *
     * 与 onChange 分开：任务列表用不到它，但**渲染器健康检查**要用 ——
     * 旧 UI 的 RendererHintDialog 就是盯着退出事件，在启动日志里发现
     * 渲染器失败特征时提示一键切换（1.8.9 + MobileGlues 会白屏卡住，
     * 等系统收掉 Activity 要几十秒，用户完全不知道发生了什么）。
     */
    private var onExit: ((String, Int) -> Unit)? = null

    /**
     * 停滞检测的心跳：定时重算并推 UI。
     *
     * 下载**完全卡死**时不会有任何事件推送 —— 光靠事件驱动的话
     * `stalled` 永远算不出来（sampleSpeed 没机会被调用）。
     */
    private var heartbeat: android.os.Handler? = null

    /** 本仓库持有的订阅句柄（按句柄退订，不按事件名）。 */
    private val subs = mutableListOf<RustBridge.Subscription>()

    /**
     * 开始监听全部任务相关事件。
     *
     * @param onChange 兼容用的单槽位回调；新代码请 collect [state]，传 null 即可。
     */
    fun start(
        onChange: ((DownloadsUiState) -> Unit)? = null,
        onExit: ((String, Int) -> Unit)? = null,
    ) {
        this.onChange = onChange
        this.onExit = onExit

        // 重复 start（页面重进）时先退掉上一轮，免得同一事件被处理两次。
        unsubscribe()
        subs += bridge.listen("install://progress") { payload -> onInstall(payload) }
        subs += bridge.listen("download://progress") { payload -> onDownload(payload) }
        subs += bridge.listen("launch://state") { payload -> onLaunchState(payload) }
        subs += bridge.listen("launch://exit") { payload -> onLaunchExit(payload) }
        // 启动进度（审计 H-5）：step 文案 + progress 百分比
        subs += bridge.listen("launch://progress") { payload ->
            runCatching {
                val o = JSONObject(payload)
                o.optString("step") to o.optDouble("progress", 0.0).toFloat()
            }.getOrNull()?.let { (step, p) ->
                launchStep = step
                launchProgress = if (p > 1f) p / 100f else p
            }
        }

        Log.i(TAG, "已订阅 install/download/launch 事件")

        // ── 停滞检测心跳 ────────────────────────────────────────────────
        //
        // 下载**完全卡死**时不会有任何事件推送 —— 光靠事件驱动的话
        // `stalled` 永远算不出来（sampleSpeed 没机会被调用）。
        // 这里每 5 秒重算一次并推给 UI，让「下载停滞」提示能出现。
        heartbeat = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                emit()
                heartbeat?.postDelayed(this, HEARTBEAT_MS)
            }
        }
        heartbeat?.postDelayed(tick, HEARTBEAT_MS)

        emit()
    }

    fun stop() {
        heartbeat?.removeCallbacksAndMessages(null)
        heartbeat = null
        onChange = null
        onExit = null
        unsubscribe()
    }

    /**
     * 只退订**本仓库自己的**订阅。
     *
     * 以前按事件名退订，会把页面上同名事件的订阅一起删掉；反过来页面退出时
     * 也会把这里的订阅带走（下载进度条从此不动）。见 `RustBridge.unlisten`。
     */
    private fun unsubscribe() {
        subs.forEach { bridge.unlisten(it) }
        subs.clear()
    }

    /** 取消一个任务（调 Rust 的 cancel_install）。 */
    fun cancel(taskId: Long, onDone: (Result<Unit>) -> Unit = {}) {
        bridge.invoke("cancel_install", """{"taskId":$taskId}""") { r ->
            r.fold(
                onSuccess = { onDone(Result.success(Unit)) },
                onFailure = { e ->
                    Log.w(TAG, "取消任务 $taskId 失败: ${e.message}")
                    onDone(Result.failure(e))
                },
            )
        }
    }

    /** 清空已完成的任务（纯本地操作，与旧实现 clearFinished 一致）。 */
    fun clearFinished() {
        tasks.entries.removeAll { it.value.finished }
        emit()
    }

    // ── 事件处理 ──────────────────────────────────────────────────────────

    private fun onInstall(payload: String) {
        runCatching {
            val o = JSONObject(payload)
            val id = o.optLong("taskId", -1)
            if (id < 0) return
            val e = get(id)

            e.stage = o.optString("stage")
            e.message = o.optString("message")
            e.stepDone = o.optInt("done")
            e.stepTotal = o.optInt("total")
            e.title = titleFor(e, o)
            // 记下归属实例 —— 「补全文件」用它防止同一实例重复开任务
            o.optString("instanceId").takeIf { it.isNotBlank() }?.let { e.instanceId = it }
            // 下载类阶段用字节/文件数，其余用步骤数
            if (isInstallStage(e.stage)) {
                e.bytesTotal = 0
            }

            if (e.stage == "done") {
                e.finished = true
                e.ok = o.optBoolean("ok", true)
                e.speed = 0.0
            }
            emit()
        }.onFailure { Log.w(TAG, "解析 install://progress 失败", it) }
    }

    private fun onDownload(payload: String) {
        runCatching {
            val o = JSONObject(payload)
            val id = o.optLong("taskId", -1)
            if (id < 0) return
            val e = get(id)

            e.stage = o.optString("phase")
            e.fileDone = o.optInt("done")
            e.fileTotal = o.optInt("total")
            e.bytesDone = maxOf(e.bytesDone, o.optLong("bytesDone", 0))
            val bt = o.optLong("bytesTotal", 0)
            if (bt > 0) e.bytesTotal = maxOf(e.bytesTotal, bt)
            e.ok = o.optBoolean("ok", true)
            e.title = titleFor(e, o)

            val current = o.optString("current")
            if (current.isNotBlank()) e.message = current

            sampleSpeed(e)
            emit()
        }.onFailure { Log.w(TAG, "解析 download://progress 失败", it) }
    }

    private fun onLaunchState(payload: String) {
        runCatching {
            val o = JSONObject(payload)
            val state = o.optString("state")
            val instanceId = o.optString("instanceId")
            Log.i(TAG, "游戏状态: $state ($instanceId)")
            runningInstanceId = if (state == "running" && instanceId.isNotBlank()) instanceId else null
        }.onFailure { Log.w(TAG, "解析 launch://state 失败", it) }
    }

    private fun onLaunchExit(payload: String) {
        runCatching {
            val o = JSONObject(payload)
            val code = o.optInt("code", 0)
            // 事件里的实例 id 字段名可能是 instanceId 或 instance_id（两处历史写法）
            val instanceId = o.optString("instanceId")
                .ifBlank { o.optString("instance_id") }
            Log.i(TAG, "游戏退出，code=$code ($instanceId)")
            onExit?.invoke(instanceId, code)
        }.onFailure { Log.w(TAG, "解析 launch://exit 失败", it) }
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────

    private fun get(id: Long): Entry =
        tasks.getOrPut(id) { Entry(id = id, startedAt = System.currentTimeMillis()) }

    /**
     * 滚动窗口平均速度。
     *
     * 与旧实现 `sampleSpeed` 同思路：保留最近 5 秒的采样点，
     * 用首尾差算平均，再做指数平滑（避免数字跳变）。
     */
    private fun sampleSpeed(e: Entry) {
        val now = System.currentTimeMillis()
        e.samples.add(now to e.bytesDone)
        e.samples.removeAll { now - it.first > SPEED_WINDOW_MS }
        if (e.samples.size >= 3) {
            val first = e.samples.first()
            val last = e.samples.last()
            val dt = (last.first - first.first) / 1000.0
            if (dt > 0.5) {
                val inst = maxOf(0.0, (last.second - first.second) / dt)
                e.speed = if (e.speed > 0) e.speed * 0.7 + inst * 0.3 else inst
            }
        }

        // ── 停滞检测 ──────────────────────────────────────────────────
        //
        // Rust 下载器没有读超时：连接挂起时请求一直不返回，
        // 既不报错也不重试。前端只能自己看「进度有没有动过」——
        // 用字节/步骤/文件数三者之和做标记，任一项变化就算有进展。
        val mark = e.bytesDone + e.stepDone * 1000L + e.fileDone * 100_000L
        if (mark != e.lastProgressMark) {
            e.lastProgressMark = mark
            e.lastProgressAt = now
        }
    }

    private fun isInstallStage(stage: String): Boolean =
        stage in setOf("manifest", "natives", "modpack-install", "done")

    /** 任务标题：优先用实例名，退回来源，最后退回 id。 */
    private fun titleFor(e: Entry, o: JSONObject): String {
        val name = o.optString("instanceName")
        if (name.isNotBlank()) return name
        val source = o.optString("source")
        if (source.isNotBlank()) return source
        return e.title.ifBlank { context.getString(R.string.task_default_title, e.id) }
    }

    /** 组装给 UI 的状态。 */
    /**
     * 某个实例是否已有**进行中**的安装/下载任务。
     *
     * ── 为什么需要它 ────────────────────────────────────────────────
     * 「补全文件」按钮原来只用组件内的 `var repairing` 拦重复点击，
     * 那是**局部状态**：用户离开实例详情页再回来，它就重置成 false，
     * 于是能对同一个实例再开一次任务。
     *
     * 真机实测后果（用户报的 bug）：下载页出现**两条一模一样的任务**
     *（同一个 534.6MB 的下载被跑了两遍），两条互相覆盖同一个目标文件、
     * 抢同一条带宽 —— 速度掉到十几 KB/s，而且进度条看起来像卡住。
     *
     * 后端 `repair_instance_files` **没有**防重入（每次调用都新建一个
     * `TaskCtx`、分配新 task id），所以这道闸门只能加在前端。
     */
    fun hasActiveTaskFor(instanceId: String): Boolean =
        tasks.values.any { !it.finished && it.instanceId == instanceId }

    /**
     * 取某个实例**正在进行中**的任务（取开始时间最早的那条）。
     *
     * ── 为什么需要它 ────────────────────────────────────────────────
     * [cancel] 早就有了，但要取消得先进下载页翻到那条任务 ——
     * 首页 / 实例详情页既看不到进度、也没有取消入口。
     * 详情页（`InstanceDetailRoute`）现在用这个把「这个实例正在下什么」
     * 显示成一条带进度条和取消按钮的提示条（与老 Vue
     * `InstanceDetailView.vue` 的 `installTask` 计算属性等价）。
     *
     * ⚠️ 只按 `instanceId` 归并，**不**过滤任务类型：
     * 创建实例后的后台安装、点「补全文件」、装内容都会带上 instanceId
     *（见 `onInstall`/`onDownload` 里对 `instanceId`/`instanceName` 的解析），
     * 从用户视角它们就是同一件事：「这个实例正在下东西」。
     *
     * 注意：任务对象只带 `instanceId`（String），不带实例名，
     * 所以「按实例名匹配」的老做法在这里做不到 —— 这也是为什么条目
     * 可能为空的历史原因，不是 bug。
     */
    fun activeTaskFor(instanceId: String): TaskUi? =
        tasks.values
            .filter { !it.finished && it.instanceId == instanceId }
            .minByOrNull { it.startedAt }
            ?.toUi()

    fun snapshot(tab: DownloadsTab = DownloadsTab.Active): DownloadsUiState {
        val all = tasks.values.map { it.toUi() }
        return DownloadsUiState(
            tab = tab,
            active = all.filter { !it.finished },
            finished = all.filter { it.finished },
        )
    }

    private fun emit() {
        val s = snapshot()
        // 单一真相源先更新（collect 的页面立刻能看到），再兼容旧回调
        _state.value = s
        val cb = onChange ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { cb(s) }
    }

    private fun Entry.toUi(): TaskUi {
        // 进度：安装阶段按步骤数，下载阶段按字节数（有字节时优先字节）
        val fraction = when {
            bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).coerceIn(0.0, 1.0).toFloat()
            stepTotal > 0 -> (stepDone.toDouble() / stepTotal).coerceIn(0.0, 1.0).toFloat()
            fileTotal > 0 -> (fileDone.toDouble() / fileTotal).coerceIn(0.0, 1.0).toFloat()
            finished -> 1f
            else -> 0f
        }

        val progressText = when {
            finished -> if (ok) context.getString(R.string.task_done) else context.getString(R.string.task_failed)
            bytesTotal > 0 -> "${fmtBytes(bytesDone)} / ${fmtBytes(bytesTotal)}"
            stepTotal > 0 -> "$stepDone / $stepTotal"
            fileTotal > 0 -> context.getString(R.string.task_files_progress, fileDone, fileTotal)
            else -> context.getString(R.string.task_preparing)
        }

        return TaskUi(
            id = id,
            title = title.ifBlank { context.getString(R.string.task_default_title, id) },
            message = message,
            fraction = fraction,
            progressText = progressText,
            // 速度 + 预估剩余时间：`37.3 MB/s · 剩余 2 分 10 秒`
            speedText = if (!finished && speed > 0) {
                val base = fmtSpeed(speed)
                if (bytesTotal > 0 && bytesDone in 0 until bytesTotal) {
                    val secs = ((bytesTotal - bytesDone) / speed).toLong()
                    if (secs in 1..86_400) "$base · 剩余 ${fmtEta(secs)}" else base
                } else base
            } else "",
            // 停滞：进度 30 秒没动过（前置条件：已经下过一点东西，
            // 否则「刚创建任务还没开始」会被误判成停滞）
            stalled = !finished && lastProgressMark >= 0 &&
                (System.currentTimeMillis() - lastProgressAt) > STALL_MS,
            finished = finished,
            stage = stage,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            fileDone = fileDone,
            fileTotal = fileTotal,
            stepDone = stepDone,
            stepTotal = stepTotal,
        )
    }
}

/** 字节数格式化（对齐旧前端 `utils/format.ts` 的 fmtBytes）。 */
internal fun fmtBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}

/** 速度格式化（对齐旧前端 `fmtSpeed`）。 */
/** 预估剩余时间的口语化格式：`45 秒` / `2 分 10 秒` / `1 小时 5 分`。 */
internal fun fmtEta(seconds: Long): String {
    if (seconds < 60) return "${seconds} 秒"
    val m = seconds / 60
    if (m < 60) {
        val s = seconds % 60
        return if (s == 0L) "$m 分" else "$m 分 $s 秒"
    }
    val h = m / 60
    val mm = m % 60
    return if (mm == 0L) "$h 小时" else "$h 小时 $mm 分"
}

internal fun fmtSpeed(bytesPerSec: Double): String {
    if (bytesPerSec <= 0) return ""
    return fmtBytes(bytesPerSec.toLong()) + "/s"
}




