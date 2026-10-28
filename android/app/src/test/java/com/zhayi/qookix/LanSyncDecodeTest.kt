package com.zhayi.qookix

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatReader
import com.google.zxing.qrcode.QRCodeWriter
import com.zhayi.qookix.compose.lansync.decodeLuminance
import com.zhayi.qookix.compose.lansync.rotateCounterClockwise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 扫码解码的纯逻辑测试。
 *
 * 背景（2026-10-28）：真机反馈「扫码没反应」，根因是解码时调了
 * `LuminanceSource.rotateCounterClockwise()` —— `PlanarYUVLuminanceSource`
 * 不支持旋转，每帧都抛 `UnsupportedOperationException`，被外层吞掉，
 * 于是永远解不出码。这里用 ZXing 自己生成二维码，直接喂给解码函数，
 * 保证「原方向 / 旋转 90°」两条路径都能出结果。
 */
class LanSyncDecodeTest {

    private val content = "qookix://lan/pair?host=192.168.1.23&port=8765&token=abc123"

    /** 生成一张 size×size 的二维码亮度图（8 位灰度）。 */
    private fun qrLuminance(size: Int = 480): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(size * size) { i ->
            val x = i % size
            val y = i / size
            // 黑 = 0，白 = 255（ZXing 的 HybridBinarizer 认这个约定）
            if (matrix.get(x, y)) 0 else 255.toByte()
        }
    }

    /** 顺时针旋转 90°（模拟「相机传感器横向、手机竖持」时的帧方向）。 */
    private fun rotateClockwise(data: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 原 (x, y) → 新 (nx = h - 1 - y, ny = x)
                out[x * h + (h - 1 - y)] = data[y * w + x]
            }
        }
        return out
    }

    @Test
    fun decodes_qr_in_original_orientation() {
        val size = 480
        val reader = MultiFormatReader()
        val result = decodeLuminance(qrLuminance(size), size, size, reader)
        assertEquals(content, result)
    }

    @Test
    fun decodes_qr_when_frame_is_rotated_90() {
        val size = 480
        val rotated = rotateClockwise(qrLuminance(size), size, size)
        // 顺时针转了 90°，解码函数内部会再逆时针转回来
        val result = decodeLuminance(rotated, size, size, MultiFormatReader())
        assertEquals(content, result)
    }

    @Test
    fun returns_null_for_blank_frame() {
        val size = 240
        val blank = ByteArray(size * size) { 255.toByte() }
        assertNull(decodeLuminance(blank, size, size, MultiFormatReader()))
    }

    @Test
    fun rotate_counter_clockwise_is_lossless_for_twice() {
        val w = 4
        val h = 3
        val src = ByteArray(w * h) { it.toByte() }
        val twice = rotateCounterClockwise(rotateCounterClockwise(src, w, h), h, w)
        // 两次 90° = 180°：首尾对应翻转
        assertNotNull(twice)
        assertEquals(src.size, twice.size)
        for (i in src.indices) {
            assertEquals(src[src.size - 1 - i], twice[i])
        }
    }

    @Test
    fun rejects_short_buffer() {
        val reader = MultiFormatReader()
        assertNull(decodeLuminance(ByteArray(10), 100, 100, reader))
    }
}
