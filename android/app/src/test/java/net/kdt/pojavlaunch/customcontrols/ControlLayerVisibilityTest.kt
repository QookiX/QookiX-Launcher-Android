package net.kdt.pojavlaunch.customcontrols

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.HashMap

class ControlLayerVisibilityTest {
    private fun controls(vararg layers: ControlLayerData): CustomControls = CustomControls().apply {
        mLayerDataList.addAll(layers)
    }

    private fun layer(id: String, type: String = "always", hide: Boolean = false,
                      mouse: Boolean = false, gamepad: Boolean = false) =
        ControlLayerData(id, type, hide, mouse, gamepad)

    @Test
    fun sceneAndDeviceRulesStackOnTheWholeLayer() {
        val layout = controls(layer("a", type = "in_game", mouse = true))
        val flags = HashMap<String, Boolean>()
        fun visible(scene: ControlScene, source: Int, external: Boolean) =
            ControlLayerVisibility.visible(layout, "a", true, flags, scene, true, false, external, source)

        assertTrue(visible(ControlScene.GAMEPLAY, ControlInputSource.SOURCE_NONE, false))
        assertFalse(visible(ControlScene.MENU, ControlInputSource.SOURCE_NONE, false))
        assertFalse(visible(ControlScene.GAMEPLAY, ControlInputSource.SOURCE_MOUSE, true))
        assertTrue(visible(ControlScene.GAMEPLAY, ControlInputSource.SOURCE_GAMEPAD, true))
    }

    @Test
    fun hiddenLayerInitialStateCanBeOverriddenByShowAction() {
        val layout = controls(layer("a", hide = true))
        val flags = HashMap<String, Boolean>()
        fun visible() = ControlLayerVisibility.visible(layout, "a", false, flags,
            ControlScene.GAMEPLAY, true, false, false, ControlInputSource.SOURCE_NONE)

        assertFalse(ControlLayerVisibility.initialVisible(layout, "a", true))
        assertFalse(visible())
        flags["a"] = true
        assertTrue(visible())
    }

    @Test
    fun editorKeepsGroupStateWithoutApplyingLayerRules() {
        val layout = controls(layer("a", type = "in_menu"))
        val flags = HashMap<String, Boolean>()
        assertTrue(ControlLayerVisibility.visible(layout, "a", true, flags,
            ControlScene.GAMEPLAY, false, true, false, ControlInputSource.SOURCE_NONE))
        flags["a"] = false
        assertFalse(ControlLayerVisibility.visible(layout, "a", true, flags,
            ControlScene.GAMEPLAY, false, true, false, ControlInputSource.SOURCE_NONE))
    }

    @Test
    fun allSceneKeepsInGameAndInMenuLayersVisible() {
        val layout = controls(layer("g", type = "in_game"), layer("m", type = "in_menu"))
        val flags = HashMap<String, Boolean>()
        assertTrue(ControlLayerVisibility.visible(layout, "g", true, flags,
            ControlScene.ALL, true, false, false, ControlInputSource.SOURCE_NONE))
        assertTrue(ControlLayerVisibility.visible(layout, "m", true, flags,
            ControlScene.ALL, true, false, false, ControlInputSource.SOURCE_NONE))
    }

    @Test
    fun simulatedMouseHidesMouseLayersEvenInPreview() {
        val layout = controls(layer("a", mouse = true))
        val flags = HashMap<String, Boolean>()
        assertFalse(ControlLayerVisibility.visible(layout, "a", true, flags,
            ControlScene.GAMEPLAY, true, true, true, ControlInputSource.SOURCE_MOUSE))
    }
}
