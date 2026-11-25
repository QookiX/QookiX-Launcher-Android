package net.kdt.pojavlaunch.customcontrols.buttons

import com.zhayi.qookix.control.McBindingKeys
import net.kdt.pojavlaunch.customcontrols.ControlData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlPressTest {
    @Test fun keepsOriginalChordAfterRebindAndConfigMutation() {
        var binding = 66
        val configured = intArrayOf(69, 340)
        val press = ControlPress(configured, true) { binding }
        binding = 67
        configured[1] = 341
        assertArrayEquals(intArrayOf(66, 340), press.keys)
        assertTrue(press.autoClick)
        assertArrayEquals(intArrayOf(67, 341), ControlPress(configured, false) { binding }.keys)
    }

    @Test fun resolvesMouseButtonsAndPreservesFallback() {
        val binding = McBindingKeys.resolveModern("key.mouse.right")
        assertEquals(ControlData.SPECIALBTN_MOUSESEC, binding)
        assertArrayEquals(intArrayOf(binding), ControlPress(intArrayOf(69), false) { binding }.keys)
        assertArrayEquals(intArrayOf(69), ControlPress(intArrayOf(69), false) { 0 }.keys)
        assertArrayEquals(intArrayOf(binding), ControlPress(intArrayOf(binding), false) { 69 }.keys)
    }
}
