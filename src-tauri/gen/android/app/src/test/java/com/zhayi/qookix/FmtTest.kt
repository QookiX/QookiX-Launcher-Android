package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.fmtBytes
import com.zhayi.qookix.compose.pages.fmtSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 单测的「第一根桩」。
 *
 * 目的不只是测 `fmtBytes` —— 更是**证明这条测试链是通的**
 * （`app/src/test` → `./gradlew testArm64DebugUnitTest` → CI）。
 * 之前这个目录根本不存在，所以 CI 里任何 Kotlin 侧的回归都只能靠真机发现。
 *
 * 后续往里加东西的优先级（按评审 Phase 3）：
 *   1. `friendlyError` 的错误码映射（纯字符串 → 文案，不需要 Context 的部分）
 *   2. 实例/任务状态机的迁移函数（等 A2 落地后再补）
 *
 * 注意：`String.format("%.1f")` 用的是默认 Locale，小数点在不同地区可能是逗号，
 * 所以这里只对整数单位（B）做精确断言，其余只校验单位后缀。
 */
class FmtTest {

    @Test
    fun bytes_below_one_kb_is_exact() {
        assertEquals("0 B", fmtBytes(0))
        assertEquals("512 B", fmtBytes(512))
        assertEquals("1023 B", fmtBytes(1023))
    }

    @Test
    fun bytes_kb_mb_gb_use_expected_unit() {
        assertTrue(fmtBytes(1024).endsWith(" KB"))
        assertTrue(fmtBytes(1536).endsWith(" KB"))
        assertTrue(fmtBytes(1024L * 1024).endsWith(" MB"))
        assertTrue(fmtBytes(1024L * 1024 * 1024).endsWith(" GB"))
    }

    @Test
    fun bytes_unit_boundaries_do_not_jump_backwards() {
        // 1023.9 KB 这类临界值不能被格式化回 "B"
        assertTrue(fmtBytes(1024 * 1024 - 1).contains("KB"))
        assertTrue(fmtBytes(1024L * 1024 * 1024 - 1).contains("MB"))
    }

    @Test
    fun speed_zero_is_empty() {
        assertEquals("", fmtSpeed(0.0))
        assertEquals("", fmtSpeed(-1.0))
    }

    @Test
    fun speed_positive_has_per_second_suffix() {
        assertTrue(fmtSpeed(1024.0).endsWith("/s"))
    }
}
