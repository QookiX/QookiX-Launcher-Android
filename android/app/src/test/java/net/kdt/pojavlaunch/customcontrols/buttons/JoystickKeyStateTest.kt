package net.kdt.pojavlaunch.customcontrols.buttons

import org.junit.Assert.assertEquals
import org.junit.Test

class JoystickKeyStateTest {
    @Test fun diagonalAddsOnlyTheNewKeyAndLockKeepsForwardHeld() {
        val edges = mutableListOf<Pair<Int, Boolean>>()
        val state = JoystickKeyState { key, down -> edges.add(key to down) }
        val w = intArrayOf(87)
        val d = intArrayOf(68)
        state.update(w)
        state.update(w, d)
        state.update(d, w)
        state.update(w)
        state.update()
        assertEquals(listOf(87 to true, 68 to true, 68 to false, 87 to false), edges)
    }

    @Test fun duplicateMappingsAndRepeatedResetDoNotDuplicateEdges() {
        val edges = mutableListOf<Pair<Int, Boolean>>()
        val state = JoystickKeyState { key, down -> edges.add(key to down) }
        state.update(intArrayOf(0, 87, 87), intArrayOf(87))
        state.update(intArrayOf(87))
        state.update()
        state.update()
        assertEquals(listOf(87 to true, 87 to false), edges)
    }
}
