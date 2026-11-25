package net.kdt.pojavlaunch.customcontrols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudKeyOwnersTest {
    @Test fun releasingOneControlDoesNotReleaseAnother() {
        val keys = HudKeyOwners()
        val joystick = Any()
        val button = Any()
        assertTrue(keys.update(87, joystick, true))
        assertFalse(keys.update(87, button, true))
        assertFalse(keys.update(87, joystick, false))
        assertTrue(keys.update(87, button, false))
    }

    @Test fun pulseAndDuplicateEdgesDoNotReleaseHeldKeyboardOrMouseInput() {
        for (key in intArrayOf(69, ControlData.SPECIALBTN_MOUSEPRI)) {
            val keys = HudKeyOwners()
            val hold = Any()
            val pulse = Any()
            assertTrue(keys.update(key, hold, true))
            assertFalse(keys.update(key, hold, true))
            assertFalse(keys.update(key, pulse, true))
            assertFalse(keys.update(key, pulse, false))
            assertTrue(keys.update(key, hold, false))
            assertFalse(keys.update(key, hold, false))
            assertTrue(keys.update(key, pulse, true))
            assertTrue(keys.update(key, pulse, false))
        }
    }
}
