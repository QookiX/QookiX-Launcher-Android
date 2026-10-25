package com.zhayi.qookix

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File

/**
 * 陶瓦联机要用到的两项环境信息（游戏内面板与 [com.zhayi.qookix.services.GameService]
 * 的自动开房都要用，所以抽出来放一处，避免两边各写一份）。
 */

/**
 * 当前游玩账号名：读 `files/accounts/` 里第一个 json 的 `username`（拿不到就 `player`）。
 *
 * 用途：陶瓦房间里的玩家标识 —— 不带名字时房间里会显示 `Terracotta Anonymous Host`。
 */
internal fun currentPlayerName(ctx: Context): String {
    return try {
        val dir = File(ctx.filesDir, "accounts")
        val f = dir.listFiles()?.firstOrNull { it.name.endsWith(".json") } ?: return "player"
        Regex("\"username\"\\s*:\\s*\"([^\"]+)\"").find(f.readText())?.groupValues?.get(1) ?: "player"
    } catch (t: Throwable) {
        "player"
    }
}

/**
 * 系统里是否有别的 VPN 在生效。
 *
 * 为什么陶瓦联机要关心这个：EasyTier 需要 TUN，而 Android **只允许一个 VPN** ——
 * 玩家开着 Clash / v2ray 的 TUN 模式时，陶瓦拿不到 TUN 只能降级，实测就是**连不上房间**
 * （真机复现：关掉手机上的 VPN 后立刻就能加入）。所以有 VPN 时要在界面上直说「先关掉」。
 *
 * 口径与 `MainActivity.isVpnActive()` 一致（那边是给代理判断用的）。
 */
internal fun isVpnActive(ctx: Context): Boolean {
    return try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    } catch (t: Throwable) {
        false
    }
}
