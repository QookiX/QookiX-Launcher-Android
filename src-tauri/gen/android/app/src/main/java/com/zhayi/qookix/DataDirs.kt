package com.zhayi.qookix

import java.io.File

/**
 * 数据目录名的唯一来源（2026-10-17 从 `instances` / `servers` 改为 `game` / `server`）。
 *
 * ⚠️ 为什么要有这个类：
 * Kotlin 侧原本有 4 处硬编码路径（`GameActivity` 两处、`ServerService`、
 * `ServerDocsProvider`），改名时必须同步，否则会出现「Rust 写 game/、
 * Kotlin 读 instances/」的错位 —— 表现是 options.txt 读写不到、
 * 崩溃日志收不上来、服务器文件管理器打不开。
 *
 * **命名理由**（用户反馈「别的启动器都是 .minecraft，我这显得格格不入」）：
 * Android 上不能用 `.minecraft` —— `files/` 是 `Context.getFilesDir()` 的标准路径，
 * 且沙箱本身已隔离，`.` 前缀没有隐藏意义。折中方案用 `game` / `server`：
 * 比 `instances` / `servers` 短、直白，玩家一眼能懂。
 *
 * **迁移策略（方案 B：新写旧读）**：
 *   - 写：永远写新目录 `game/`
 *   - 读：新目录存在就用新目录，否则回退旧目录 `instances/`
 * 这样老用户数据不会"消失"，新装用户从零就是新名字。
 */
object DataDirs {
    /** 实例目录的新名字。 */
    const val GAME = "game"

    /** 实例目录的旧名字（只读兼容）。 */
    const val GAME_LEGACY = "instances"

    /** 服务器目录的新名字。 */
    const val SERVER = "server"

    /** 服务器目录的旧名字（只读兼容）。 */
    const val SERVER_LEGACY = "servers"

    /**
     * 解析实例目录根：优先 `game/`，不存在则回退 `instances/`。
     *
     * ⚠️ 只在**旧目录存在且新目录不存在**时回退 —— 一旦有了新目录就不再
     * 看旧目录，避免两个目录各有一半实例、界面上分裂。
     */
    fun instancesRoot(filesDir: File): File {
        val newDir = File(filesDir, GAME)
        val legacy = File(filesDir, GAME_LEGACY)
        return if (newDir.exists() || !legacy.exists()) newDir else legacy
    }

    /** 解析单个实例目录。 */
    fun instanceDir(filesDir: File, instanceId: String): File =
        File(instancesRoot(filesDir), instanceId)

    /** 解析服务器目录根：优先 `server/`，不存在则回退 `servers/`。 */
    fun serversRoot(filesDir: File): File {
        val newDir = File(filesDir, SERVER)
        val legacy = File(filesDir, SERVER_LEGACY)
        return if (newDir.exists() || !legacy.exists()) newDir else legacy
    }

    /** 解析单个服务器目录。 */
    fun serverDir(filesDir: File, serverId: String): File =
        File(serversRoot(filesDir), serverId)
}
