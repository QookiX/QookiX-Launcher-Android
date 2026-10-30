package com.zhayi.qookix.compose.pages

/**
 * 当前陶瓦房间的归属（审计 B24）。
 *
 * 房间是**设备级全局**的：同一时刻只能有一个，而且隧道跑在独立进程里，
 * 离开页面不会停。原来每个服务器详情页只看「隧道状态是不是 hosting」，
 * 于是 A 开的房在 B 的页面也会显示成 B 在分享，甚至能在 B 页面把它关掉。
 *
 * 这里记下房间属于谁，别的页面据此显示「房间属于 A」，而不是假装自己在分享。
 */
object TunnelSharing {
    /** 房间属于哪台服务器（null = 没在分享）。 */
    @Volatile
    var ownerServerId: String? = null

    /** 归属服务器的名字，用于提示文案。 */
    @Volatile
    var ownerServerName: String = ""

    fun claim(serverId: String, name: String) {
        ownerServerId = serverId
        if (name.isNotBlank()) ownerServerName = name
    }

    fun release(serverId: String) {
        if (ownerServerId == serverId) {
            ownerServerId = null
            ownerServerName = ""
        }
    }

    /** 房间是否属于别的服务器（属于自己或压根没房间时为 false）。 */
    fun ownedByOther(serverId: String): Boolean {
        val owner = ownerServerId ?: return false
        return owner != serverId
    }
}
