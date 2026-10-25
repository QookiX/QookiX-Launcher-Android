package com.zhayi.qookix

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.kdt.SideDialogView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 游戏内「联机（陶瓦）」面板：看状态、拿房间码、加入朋友的房间、开始/停止联机。
 *
 * ## 为什么不直接调 Terracotta 的 API
 *
 * `libterracotta.so` 只能在 `:tunnel` 进程里 `System.loadLibrary`（AGPL 的进程隔离例外，
 * 见 `TerracottaTunnelService` 的类注释）—— 游戏进程链接它会把整个启动器拖进 AGPL。
 * 所以这里和启动器前端走**同一条**路：`:tunnel` 把 HTTP 端口写进 `filesDir/tunnel_port`
 * （跨进程共享），本面板读端口后调 `127.0.0.1` 上的 `/state`、`/autohost`、`/guest`、`/waiting`。
 *
 * ## 为什么要这个面板
 *
 * 玩家在游戏里时启动器在后台，唯一能看见的就只有通知栏；想「加入朋友的房间」还得
 * 退出游戏回启动器。这里把状态和房间码搬到游戏内，顺手补上原先**没人调用**的 `/autohost`。
 */
class TerracottaPanel(
    private val ctx: Context,
    parent: ViewGroup,
    private val filesDir: File,
) : SideDialogView(ctx, parent, R.layout.dialog_terracotta) {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var roomInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var noteView: TextView
    private lateinit var addressView: TextView

    /** 当前是否处于「我在开房」的状态，决定底部按钮是「开始」还是「停止」 */
    @Volatile
    private var hosting = false

    /** 加入房间后库里给的连接地址（GuestOK 的 `url`），空表示没在访客态 */
    private var currentAddress = ""

    /** 当前是否处于「我在别人房间里」（访客态），决定按钮是「退出房间」还是「开始联机」 */
    @Volatile
    private var guesting = false

    /** 「复制…」按钮的引用：文案要随状态在「房间码 / 连接地址」之间切 */
    private lateinit var copyButton: Button

    init {
        setTitle(R.string.tc_title)
        setStartButtonListener(R.string.perf_close) { disappear(true) }
    }

    override fun onInflate() {
        statusView = mDialogContent.findViewById(R.id.tc_status)
        codeView = mDialogContent.findViewById(R.id.tc_code)
        roomInput = mDialogContent.findViewById(R.id.tc_room_input)
        toggleButton = mDialogContent.findViewById(R.id.tc_toggle)
        noteView = mDialogContent.findViewById(R.id.tc_note)
        addressView = mDialogContent.findViewById(R.id.tc_address)
        refreshNote()

        copyButton = mDialogContent.findViewById(R.id.tc_copy)
        copyButton.setOnClickListener {
            // 加入房间后要带走的是「连接地址」（MC 里要填的），开房时才是房间码
            val text = currentAddress.ifBlank { currentCode().orEmpty() }
            if (text.isBlank()) return@setOnClickListener
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("terracotta", text))
            Toast.makeText(ctx, R.string.tc_copied, Toast.LENGTH_SHORT).show()
        }

        mDialogContent.findViewById<Button>(R.id.tc_join).setOnClickListener {
            val room = roomInput.text?.toString()?.trim().orEmpty()
            if (room.isEmpty()) {
                Toast.makeText(ctx, R.string.tc_room_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // ① 先停掉自己这边的房间/扫描：正开着房时 setGuesting 直接失败
            //（真机踩过：点「加入房间」毫无反馈，因为失败只体现在返回 JSON 里）。
            // ② 必须把结果说出来 —— 失败要能看出来，不能静默。
            statusView.text = ctx.getString(R.string.tc_joining)
            io.execute {
                val port = tunnelPort()
                if (port == null) {
                    ui.post { Toast.makeText(ctx, R.string.tc_unavailable, Toast.LENGTH_SHORT).show() }
                    return@execute
                }
                // 「先退出当前房间」由服务端 /guest 保证（那里一处修好所有调用方）
                // 带上玩家名，房间成员里才显示你的名字（不然是 Terracotta Anonymous Guest）
                val path = "/guest?room=" + Uri.encode(room) +
                    "&player=" + Uri.encode(currentPlayerName(ctx))
                val body = runCatching { httpGet(port, path) }.getOrNull()
                val ok = body != null &&
                    runCatching { JSONObject(body).optBoolean("ok") }.getOrDefault(false)
                ui.post {
                    Toast.makeText(
                        ctx,
                        if (ok) R.string.tc_joined else R.string.tc_join_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

        toggleButton.setOnClickListener {
            call(
                when {
                    // 开房 / 进别人房间时都是先退出（/waiting 幂等）
                    hosting || guesting -> "/waiting"
                    // 开房带玩家名，房间成员里才显示你的名字（不然是 Anonymous Host）
                    else -> "/autohost?player=" + Uri.encode(currentPlayerName(ctx))
                }
            )
        }

        startPolling()
    }

    /** 系统里挂着别的 VPN 时直说「先关掉」—— EasyTier 抢不到 TUN 就连不上（真机实测） */
    private fun refreshNote() {
        noteView.text = if (isVpnActive(ctx)) {
            ctx.getString(R.string.tc_vpn_hint)
        } else {
            ctx.getString(R.string.tc_note)
        }
    }

    private fun currentCode(): String? {
        val t = codeView.text?.toString()?.trim().orEmpty()
        if (t.isEmpty() || t == ctx.getString(R.string.tc_code_none)) return null
        return t
    }

    /** 每 2 秒刷一次状态的轮询任务；`stop()` 里必须能拿到它才能取消掉 */
    private var tick: Runnable? = null

    /** 每 2 秒刷一次状态；面板收起来（mDisplaying=false）就不再占用网络 */
    private fun startPolling() {
        if (tick != null) return // 已经挂着了就别重复挂
        val t = object : Runnable {
            override fun run() {
                if (mDisplaying) call("/state")
                ui.postDelayed(this, 2000)
            }
        }
        tick = t
        ui.postDelayed(t, 200)
    }

    /**
     * 面板销毁时收掉线程池**和主线程轮询**（GameActivity.onDestroy 里调）。
     *
     * 必须 `removeCallbacks`：`tick` 是每 2 秒自我重投的，只关线程池的话它会一直被
     * 主线程 Handler 持有并继续重投 —— 而 `tick` 又持有这个 panel、panel 持有
     * GameActivity 实例，等于**每进一次游戏再退出就泄漏一个 GameActivity**，
     * 还附赠每 2 秒一次的无效主线程调度。
     */
    fun stop() {
        tick?.let { ui.removeCallbacks(it) }
        tick = null
        runCatching { io.shutdownNow() }
    }

    // ── HTTP（一律丢到后台线程，结果回主线程刷 UI）────────────────────────────

    private fun call(path: String) {
        val port = tunnelPort()
        if (port == null) {
            statusView.text = ctx.getString(R.string.tc_unavailable)
            return
        }
        io.execute {
            val body = runCatching { httpGet(port, path) }.getOrNull()
            ui.post { onResponse(body) }
        }
    }

    private fun tunnelPort(): Int? =
        runCatching { File(filesDir, PORT_FILE).readText().trim().toInt() }.getOrNull()

    private fun httpGet(port: Int, path: String): String {
        val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2000
            readTimeout = 4000
            requestMethod = "GET"
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun onResponse(body: String?) {
        if (body.isNullOrBlank()) {
            statusView.text = ctx.getString(R.string.tc_unavailable)
            return
        }
        val json = runCatching { JSONObject(body) }.getOrNull()
        applyState(
            state = json?.optString("state").orEmpty(),
            room = json?.optString("room").orEmpty(),
            url = json?.optString("url").orEmpty(),
        )
    }

    private fun applyState(state: String, room: String, url: String) {
        // /guest 的返回是 {"ok":..,"state":"guesting"}，没有 room 字段，房间码就用输入框里的
        val code = room.ifBlank {
            if (state.startsWith("guest")) roomInput.text?.toString()?.trim().orEmpty() else ""
        }
        hosting = state.startsWith("host")
        guesting = state.startsWith("guest")
        codeView.text = if (code.isBlank()) ctx.getString(R.string.tc_code_none) else code
        // 访客态在库的 GuestOK 里带 `url` —— 就是要填进 MC「多人游戏 → 直接连接」的地址
        currentAddress = url
        if (url.isNotBlank()) {
            addressView.text = ctx.getString(R.string.tc_address_hint, url)
            addressView.visibility = View.VISIBLE
        } else {
            addressView.visibility = View.GONE
        }
        // 按钮名跟着角色走：开房→停止联机、在别人房里→退出房间、空闲→开始联机
        copyButton.setText(if (url.isNotBlank()) R.string.tc_copy_address else R.string.tc_copy)
        statusView.text = when {
            state.startsWith("host-ok") -> ctx.getString(R.string.tc_status_hosting)
            state.startsWith("host") -> ctx.getString(R.string.tc_status_scanning)
            state.startsWith("guest") -> ctx.getString(R.string.tc_status_guesting)
            else -> ctx.getString(R.string.tc_status_idle)
        }
        toggleButton.setText(
            when {
                hosting -> R.string.tc_stop
                guesting -> R.string.tc_leave
                else -> R.string.tc_start
            }
        )
        // VPN 开关状态可能在这期间变了，跟着刷新提示
        refreshNote()
    }

    private companion object {
        /** 与 `terracotta.rs` 的 `PORT_FILE` 保持一致 */
        const val PORT_FILE = "tunnel_port"
    }
}
