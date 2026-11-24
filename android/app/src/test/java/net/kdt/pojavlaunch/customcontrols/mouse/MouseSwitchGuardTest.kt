package net.kdt.pojavlaunch.customcontrols.mouse

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MouseSwitchGuardTest {
    @Test
    fun suppressesOnlyTheFirst33MsAfterSwitch() {
        MouseSwitchGuard.reset()
        assertFalse(MouseSwitchGuard.shouldSuppressClick(1000))
        MouseSwitchGuard.markSwitched(1000)
        assertTrue(MouseSwitchGuard.shouldSuppressClick(1000))
        assertTrue(MouseSwitchGuard.shouldSuppressClick(1032))
        assertFalse(MouseSwitchGuard.shouldSuppressClick(1033))
        assertFalse(MouseSwitchGuard.shouldSuppressClick(999))
        MouseSwitchGuard.reset()
        assertFalse(MouseSwitchGuard.shouldSuppressClick(1001))
    }
}
