package com.zhayi.qookix

import com.zhayi.qookix.compose.bridge.QookixApi
import com.zhayi.qookix.compose.bridge.RustBridge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 哪些命令该有超时、哪些不该（见 `RustBridge.invoke` 的 `timeoutMs`）。
 *
 * 判错两个方向都有代价：
 *   - 该有的没有 → Rust 卡死时界面永久转圈，只能杀进程；
 *   - 不该有的有了 → 慢网下正常的大安装被中途判死（比转圈更糟，用户白等）。
 */
class ApiTimeoutTest {

    @Test
    fun long_tasks_are_not_timed_out() {
        listOf(
            "install_game",
            "install_hosted_server_core",
            "import_modpack",
            "download_file",
            "launch_game",
            "check_updates",
        ).forEach { assertEquals("$it 不该有超时", 0L, QookixApi.timeoutFor(it)) }
    }

    @Test
    fun quick_commands_keep_the_timeout() {
        // 保存类命令必须受保护：卡住时用户至少能看到「超时，请重试」。
        listOf(
            "list_instances",
            "get_instance",
            "update_instance",
            "update_settings",
            "update_hosted_server",
            "list_hosted_servers",
        ).forEach {
            assertEquals("$it 应该有超时", RustBridge.DEFAULT_TIMEOUT_MS, QookixApi.timeoutFor(it))
        }
    }
}
