package com.zhayi.qookix.tunnel

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log

/**
 * 陶瓦联机（Terracotta / EasyTier）用的 VPN（TUN）。
 *
 * ## 为什么必须有这个类
 *
 * EasyTier 的**数据面**要走 TUN：房主要把mesh 上的入站流量转成「连本机 25565」，
 * 客人才连得进来。而 Terracotta 的 Android 产物并不自己申请 VPN —— 它通过
 * `TerracottaAndroidAPI.VpnServiceCallback` 把请求交给宿主App，由宿主来建隧道
 * （官方 Java 文档写得很明确：「Developer must make sure either
 * `VpnServiceRequest#startVpnService` or `#reject()` is invoked, **or Terracotta would
 * stuck and EasyTier cannot submit a new VpnService Request**」，且必须在 30 秒内答复）。
 *
 * 我们原来的实现是**什么都不做**（只打一行日志），于是：
 *   ① 违反了契约 → Terracotta 卡住，EasyTier 再也提交不了新的 VPN 请求
 *     （真机日志里连续两条 `[Android]: Cannot request VpnService`）；
 *   ② 手机侧没有 TUN → 房间开得出、房间码也有，但**客人永远连不进来**
 *     （电脑端加入时直接报「连接发生错误」，而房主日志里profiles 只有自己）。
 *
 * ## 为什么要跑在 `:tunnel` 进程
 *
 * `VpnService.Builder` 必须由一个**正在运行的 VpnService 实例**创建，而
 * `libterracotta.so` 只在 `:tunnel` 进程里加载（AGPL 的进程隔离例外，见
 * `TerracottaTunnelService` 的类注释）。跨进程没法把 `ParcelFileDescriptor` 交给 native。
 */
class TerracottaVpnService : VpnService() {

    companion object {
        private const val TAG = "TcTunnel"

        /** 正在运行的实例；`VpnService.Builder` 只能由它创建。 */
        @Volatile
        var instance: TerracottaVpnService? = null
            private set

        /**
         * 造一个 Builder；服务还没起来时返回 null。
         *
         * 调用方（`TerracottaTunnelService`）需要先 `startService` 拉起本服务，
         * 因为 `establish()` 要求服务处于运行状态。
         *
         * 注意 `VpnService.Builder` 是 **inner class**，必须带接收者创建
         * （写 `VpnService.Builder(it)` 编译不过）。
         */
        fun newBuilder(): VpnService.Builder? = instance?.Builder()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "VPN 服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 标准 VpnService 模式：建立接口后由系统维持绑定；用户/系统撤销时走 onRevoke。
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN 被系统撤销")
        // 不主动 stopSelf：让 Terracotta/EasyTier 自己发现隧道没了
        super.onRevoke()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "VPN 服务销毁")
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        // VpnService 不提供 Binder 绑定（系统通过 startService + establish 交互）
        return null
    }
}