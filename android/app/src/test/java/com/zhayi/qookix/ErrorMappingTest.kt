package com.zhayi.qookix

import com.zhayi.qookix.compose.bridge.ApiError
import com.zhayi.qookix.compose.bridge.errorResFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 错误码 → 文案资源的映射（A2 错误契约）。
 *
 * 这层是纯函数，不需要 Context —— 也正是把它抽出来的原因：
 * `friendlyError(context, throwable)` 需要设备，映射表不需要，
 * 于是分类逻辑可以在 CI 的 JVM 单测里被覆盖。
 */
class ErrorMappingTest {

    @Test
    fun network_maps_to_network_hint() {
        assertEquals(R.string.error_network, errorResFor(ApiError.CODE_NETWORK))
    }

    @Test
    fun not_found_maps_to_not_found_hint() {
        assertEquals(R.string.error_not_found, errorResFor(ApiError.CODE_NOT_FOUND))
    }

    @Test
    fun timeout_maps_to_timeout_hint() {
        // 桥层自己的超时（Rust 侧不会发这个 code，见 RustBridge.invoke）
        assertEquals(R.string.error_timeout, errorResFor(ApiError.CODE_TIMEOUT))
    }

    @Test
    fun io_and_parse_have_dedicated_hints() {
        assertEquals(R.string.error_disk_io, errorResFor(ApiError.CODE_IO))
        assertEquals(R.string.error_bad_response, errorResFor(ApiError.CODE_PARSE))
    }

    @Test
    fun auth_and_unknown_fall_back_to_original_text() {
        // auth：原文通常已经说了「去设置填 Key」/「重新登录」，换成笼统文案反而丢信息
        assertNull(errorResFor(ApiError.CODE_AUTH))
        // unknown：宁可视原文，也不要用套话掩盖真正的未知错误
        assertNull(errorResFor(ApiError.CODE_UNKNOWN))
        assertNull(errorResFor("某个未来才有的 code"))
    }

    @Test
    fun api_error_keeps_message_and_retryable_flag() {
        val e = ApiError(ApiError.CODE_NETWORK, "连接失败", true)
        assertEquals("连接失败", e.message)
        assertEquals(true, e.retryable)
        assertEquals(true, e.isNetwork)
    }
}
