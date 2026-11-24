package com.zhayi.qookix

import android.util.DisplayMetrics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhayi.qookix.control.ForeignControlLayout
import com.zhayi.qookix.compose.ComposeMainActivity
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ForeignControlLayoutTest {
    private val converter = ForeignControlLayout(DisplayMetrics().apply {
        density = 2f
        widthPixels = 1600
        heightPixels = 800
    })

    @Test
    fun modifiedZalithKeepsCustomGeometryAndMenuControls() {
        val source = JSONObject("""{"editorVersion":12,"layers":[{"visibilityType":"in_game","normalButtons":[
          {"text":{"default":"编辑后的按钮"},"position":{"x":3920,"y":8641},"visibilityType":"in_menu",
           "buttonSize":{"type":"percentage","widthPercentage":1500,"heightPercentage":1000,"widthReference":"screen_width","heightReference":"screen_height"},
           "clickEvents":[{"type":"key","key":"key.keyboard.e"},{"type":"launcher_event","key":"launcher.event.scroll_down.single"}],
           "isPenetrable":true,"isSwipple":true}
        ]}]}""")
        val result = checkNotNull(converter.convert(source))
        val button = result.layout.getJSONArray("mControlDataList").getJSONObject(0)
        assertEquals("ZL2", result.format)
        assertEquals("编辑后的按钮", button.getString("name"))
        assertEquals("0.392 * ${'$'}{right}", button.getString("dynamicX"))
        assertEquals("0.8641 * ${'$'}{bottom}", button.getString("dynamicY"))
        assertEquals(120, button.getInt("width"))
        assertEquals(40, button.getInt("height"))
        assertEquals(0.15, button.getDouble("widthFraction"), 0.001)
        assertEquals(0.1, button.getDouble("heightFraction"), 0.001)
        assertFalse(button.getBoolean("widthRelativeToHeight"))
        assertTrue(button.getBoolean("heightRelativeToHeight"))
        assertEquals("[69,-8,0,0]", button.getJSONArray("keycodes").toString())
        assertFalse(button.getBoolean("displayInGame"))
        assertTrue(button.getBoolean("displayInMenu"))
        assertTrue(button.getBoolean("passThruEnabled"))
        assertTrue(button.getBoolean("isSwipeable"))
        assertEquals(0, result.changed)
    }

    @Test
    fun modifiedFclKeepsRemappedKeysSizesAndGameVisibility() {
        val source = JSONObject("""{"controllerVersion":21,"viewGroups":[{"viewData":{"buttonList":[
          {"text":"Remapped","baseInfo":{"visibilityType":"IN_GAME","sizeType":"ABSOLUTE","absoluteWidth":77,"absoluteHeight":42,"xPosition":731,"yPosition":125},
           "event":{"pressEvent":{"outputKeycodes":[42,17,1001],"autoKeep":true},"doubleClickEvent":{"outputKeycodes":[1]}}},
          {"text":"Scroll","baseInfo":{},"event":{"clickEvent":{"outputKeycodes":1003}}}
        ],"directionList":[{}]}}]}""")
        val result = checkNotNull(converter.convert(source))
        val buttons = result.layout.getJSONArray("mControlDataList")
        val button = buttons.getJSONObject(0)
        assertEquals("FCL", result.format)
        assertEquals("Remapped", button.getString("name"))
        assertEquals("0.731 * ${'$'}{right}", button.getString("dynamicX"))
        assertEquals("0.125 * ${'$'}{bottom}", button.getString("dynamicY"))
        assertEquals(77, button.getInt("width"))
        assertEquals(42, button.getInt("height"))
        assertEquals("[340,87,-6,0]", button.getJSONArray("keycodes").toString())
        assertEquals("[256]", button.getJSONObject("doubleClickAction").getJSONArray("keys").toString())
        assertEquals("[-7]", buttons.getJSONObject(1).getJSONObject("clickAction").getJSONArray("keys").toString())
        assertTrue(button.getBoolean("displayInGame"))
        assertFalse(button.getBoolean("displayInMenu"))
        assertTrue(button.getBoolean("isToggle"))
        assertEquals(1, result.changed)
    }

    @Test
    fun importsJoysticksWithoutRequiringRegularButtons() {
        val layouts = listOf(
            """{"editorVersion":12,"layers":[{"joystickButtons":[{"position":{"x":1200,"y":8000},
                "sizePercentage":3000,"triggerMode":"touch","deadZoneRatio":0.25,"lockThreshold":0.4,
                "lockEvents":[{"type":"key","key":"GLFW_KEY_LEFT_SHIFT"}]}]}]}""",
            """{"controllerVersion":21,"viewGroups":[{"viewData":{"directionList":[{"baseInfo":{"xPosition":120,"yPosition":800,
                "absoluteWidth":120,"absoluteHeight":120},"event":{"upKeycode":[17,42],"downKeycode":31,
                "leftKeycode":30,"rightKeycode":32,"followOption":"CENTER_FOLLOW","sneak":true,"sneakKeycode":42}}]}}]}"""
        )
        val joysticks = layouts.map {
            val result = checkNotNull(converter.convert(JSONObject(it)))
            assertEquals(0, result.layout.getJSONArray("mControlDataList").length())
            result.layout.getJSONArray("mJoystickDataList").getJSONObject(0).apply {
                assertEquals(120, getInt("width"))
                assertEquals("0.12 * ${'$'}{right}", getString("dynamicX"))
                assertTrue(getBoolean("displayInGame"))
                assertFalse(getBoolean("displayInMenu"))
            }
        }
        assertEquals("[340]", joysticks[0].getJSONArray("forwardLockKeys").toString())
        assertEquals("[87,340]", joysticks[1].getJSONArray("forwardKeys").toString())
        assertEquals("[83]", joysticks[1].getJSONArray("backwardKeys").toString())
        assertFalse(joysticks[0].getBoolean("triggerOnMove"))
        assertEquals(25, joysticks[0].getInt("deadZonePercent"))
        assertEquals(0.4, joysticks[0].getDouble("forwardLockRatio"), 0.001)
        assertTrue(joysticks[1].getBoolean("centerFollow"))
        assertEquals(340, joysticks[1].getInt("doubleTapKey"))
    }

    @Test
    fun importsHiddenGroupsTextLabelsAndDirectionPads() {
        val zl2 = checkNotNull(converter.convert(JSONObject("""{"layers":[
            {"uuid":"main","normalButtons":[{"text":"Show","clickEvents":[
                {"type":"show_layer","key":"tools"},{"type":"send_text","key":"/home"}]}]},
            {"uuid":"tools","hide":true,"textBoxes":[{"text":{"default":"Tools"}}]}
        ]}"""))).layout.getJSONArray("mControlDataList")
        assertEquals("tools:show", zl2.getJSONObject(0).getJSONObject("pressAction").getJSONArray("groups").getString(0))
        assertEquals("/home", zl2.getJSONObject(0).getJSONObject("pressAction").getString("text"))
        assertTrue(zl2.getJSONObject(1).getBoolean("groupInitiallyHidden"))
        assertTrue(zl2.getJSONObject(1).getBoolean("labelOnly"))

        val fcl = checkNotNull(converter.convert(JSONObject("""{
            "directionStyles":[{"name":"Cross","styleType":"BUTTON"}],
            "viewGroups":[{"id":"movement","viewData":{
                "directionList":[{"style":"Cross","baseInfo":{},"event":{"upKeycode":17}}],
                "buttonList":[{"event":{"longPressEvent":{"outputKeycodes":[17],"bindViewGroup":["movement:hide"],
                    "switchTouchMode":true}}}]
            }}]}"""))).layout
        assertTrue(fcl.getJSONArray("mJoystickDataList").getJSONObject(0).getBoolean("directionPad"))
        val action = fcl.getJSONArray("mControlDataList").getJSONObject(0).getJSONObject("longPressAction")
        assertEquals("[87]", action.getJSONArray("keys").toString())
        assertEquals("movement:hide", action.getJSONArray("groups").getString(0))
        assertTrue(action.getBoolean("switchTouchMode"))
    }

    @Test
    fun importsButtonColorsAndPressedStyles() {
        val source = JSONObject("""{"buttonStyles":[{"name":"Paint","fillColor":-65536,
            "fillColorPressed":-16711936,"strokeWidth":15,"cornerRadius":60,"textSize":12}],
            "viewGroups":[{"viewData":{"buttonList":[{"style":"Paint","baseInfo":{},
            "event":{"pressEvent":{"outputKeycodes":[17]}}}]}}]}""")
        val style = checkNotNull(converter.convert(source)).layout.getJSONArray("mControlDataList")
            .getJSONObject(0).getJSONObject("appearance")
        val normal = style.getJSONObject("normal")
        assertEquals(-65536, normal.getInt("fill"))
        assertEquals(1.5, normal.getDouble("strokeDp"), 0.001)
        assertEquals(6.0, normal.getDouble("radiusDp"), 0.001)
        assertEquals(-16711936, style.getJSONObject("pressed").getInt("fill"))
    }

    @Test
    fun rejectsUnrelatedObjectsAndCountsUnsupportedButtons() {
        assertNull(converter.convert(JSONObject("""{"layers":"broken"}""")))
        val result = checkNotNull(converter.convert(JSONObject("""{"layers":[
            {"hide":true,"normalButtons":[{"clickEvents":[{"type":"key","key":"GLFW_KEY_A"}]}]},
            {"normalButtons":[{"clickEvents":[{"type":"unknown_action","key":"example"}]}]}
        ]}""")))
        assertEquals(0, result.layout.getJSONArray("mControlDataList").length())
        assertEquals(2, result.changed)
    }

    @Test
    fun malformedAndUnsupportedFilesFailAtImportEntry() {
        ActivityScenario.launch(ComposeMainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val file = File.createTempFile("bad-layout-", ".json", activity.cacheDir)
                try {
                    for (contents in listOf("{", """{"layers":"wrong type"}""", """{"viewGroups":[]}""")) {
                        file.writeText(contents)
                        val result = JSONObject(activity.importControlLayoutFromPath(file.path))
                        assertFalse(contents, result.getBoolean("ok"))
                        assertTrue(contents, result.getString("error").isNotBlank())
                    }
                } finally {
                    file.delete()
                }
            }
        }
    }

    @Test
    fun referenceCorpusPreservesEditsAcrossExportVersions() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("checkLayoutCorpus") == "true")
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "layout-corpus")
        val files = root.listFiles { file -> file.extension == "json" }.orEmpty()
        assertEquals(10, files.size)
        for (file in files) {
            val source = JSONObject(file.readText().trimStart('\uFEFF'))
            val result = checkNotNull(converter.convert(source)) { file.name }
            val buttons = result.layout.getJSONArray("mControlDataList")
            assertTrue(file.name, buttons.length() > 0)
            if (file.name.startsWith("edited-")) {
                val edit = (0 until buttons.length()).map { buttons.getJSONObject(it) }
                    .single { it.getString("name") == if (result.format == "ZL2") "Edited ZL2" else "Edited FCL" }
                if (result.format == "ZL2") {
                    assertEquals(file.name, "[297,0,0,0]", edit.getJSONArray("keycodes").toString())
                    assertEquals(file.name, "0.371 * ${'$'}{right}", edit.getString("dynamicX"))
                    assertEquals(file.name, "0.624 * ${'$'}{bottom}", edit.getString("dynamicY"))
                    assertEquals(file.name, 68, edit.getInt("width"))
                    assertEquals(file.name, 44, edit.getInt("height"))
                    assertTrue(file.name, edit.getBoolean("displayInMenu") && !edit.getBoolean("displayInGame"))
                } else {
                    assertEquals(file.name, "[-6,32,0,0]", edit.getJSONArray("keycodes").toString())
                    assertEquals(file.name, "0.673 * ${'$'}{right}", edit.getString("dynamicX"))
                    assertEquals(file.name, "0.352 * ${'$'}{bottom}", edit.getString("dynamicY"))
                    assertEquals(file.name, 72, edit.getInt("width"))
                    assertEquals(file.name, 46, edit.getInt("height"))
                    assertTrue(file.name, !edit.getBoolean("displayInMenu") && edit.getBoolean("displayInGame"))
                }
            }
            android.util.Log.i("LayoutCorpus", "${file.name}: ${result.format}, ${buttons.length()} converted, ${result.changed} adjusted")
        }
    }

    /**
     * 把语料里的每份样例转换成 QookiX 布局并落到 `files/controlmap/corpus-*.json`。
     *
     * 用途：转换断言只能证明「数据对」，还得能真进游戏看控件。这一步把产物准备好，
     * 之后把偏好里的 `defaultCtrl` 指到对应文件即可在游戏里逐份验收。
     */
    @Test
    fun exportCorpusLayoutsForInGameCheck() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("exportCorpusLayouts") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "layout-corpus")
        val files = root.listFiles { file -> file.extension == "json" }.orEmpty()
        val outDir = File(context.filesDir, "controlmap").apply { mkdirs() }
        for (file in files) {
            val source = JSONObject(file.readText().trimStart('\uFEFF'))
            val result = converter.convert(source) ?: continue
            val out = File(outDir, "corpus-${file.name}")
            out.writeText(result.layout.toString())
            android.util.Log.i("LayoutCorpus", "导出 ${out.name}")
        }
        assertTrue(outDir.listFiles { f -> f.name.startsWith("corpus-") }.orEmpty().size >= files.size)
    }
}
