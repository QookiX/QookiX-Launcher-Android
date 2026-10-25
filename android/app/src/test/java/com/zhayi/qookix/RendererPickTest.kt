package com.zhayi.qookix

import com.zhayi.qookix.compose.pages.CreateRepo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 新建实例时按版本挑默认渲染器。
 *
 * 这条规则错一次就是「新用户点启动就崩」，而且不会报错 —— 只会安静地
 * 把 1.21.x 交给 GL4ES（真机崩在 `Can't map buffer, opengl error 0`）。
 * 纯函数，不碰设备，放进 JVM 单测。
 */
class RendererPickTest {

    @Test
    fun mc_1_21_and_newer_pick_mobileglues() {
        // 旧实现只取版本号第一段（1.21.8 → "1"），这条断言当时是红的。
        assertEquals("mobileglues", CreateRepo.autoRendererFor("1.21"))
        assertEquals("mobileglues", CreateRepo.autoRendererFor("1.21.8"))
        assertEquals("mobileglues", CreateRepo.autoRendererFor("1.21.1"))
        assertEquals("mobileglues", CreateRepo.autoRendererFor("26.3"))
        assertEquals("mobileglues", CreateRepo.autoRendererFor("26.3-snapshot-3"))
    }

    @Test
    fun older_versions_stay_on_gl4es() {
        assertEquals("opengles2", CreateRepo.autoRendererFor("1.20.6"))
        assertEquals("opengles2", CreateRepo.autoRendererFor("1.18.2"))
        assertEquals("opengles2", CreateRepo.autoRendererFor("1.8.9"))
        assertEquals("opengles2", CreateRepo.autoRendererFor(""))
    }
}
