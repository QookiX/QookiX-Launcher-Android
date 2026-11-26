package com.zhayi.qookix.control

import android.util.DisplayMetrics
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

internal class ForeignControlLayout(private val metrics: DisplayMetrics) {
    data class Result(val layout: JSONObject, val format: String, val changed: Int)

    fun convert(source: JSONObject): Result? {
        val zalith = source.optJSONArray("layers")
        val groups = zalith ?: source.optJSONArray("viewGroups") ?: return null
        val isZalith = zalith != null
        val buttons = JSONArray()
        val joysticks = JSONArray()
        val layers = JSONArray()
        val styles = source.optJSONArray(if (isZalith) "styles" else "buttonStyles").objects()
            .associateBy { it.optString(if (isZalith) "uuid" else "name") }
        val fclDirectionStyles = source.optJSONArray("directionStyles").objects()
            .associate { it.optString("name") to it.optString("styleType") }
        val styleList = JSONArray()
        for ((id, style) in styles) {
            if (id.isBlank()) continue
            val entry = JSONObject().put("id", id)
            if (isZalith) {
                style.optJSONObject("lightStyle")?.let { entry.put("appearance", zalithAppearance(it)) }
                if (!style.optBoolean("commonStyle")) {
                    style.optJSONObject("darkStyle")?.let { entry.put("darkAppearance", zalithAppearance(it)) }
                }
                entry.put("animateSwap", style.optBoolean("animateSwap"))
            } else {
                entry.put("appearance", JSONObject().put("normal", fclStyle(style, ""))
                    .put("pressed", fclStyle(style, "Pressed")))
            }
            styleList.put(entry)
        }
        var changed = 0
        for (group in groups.objects()) {
            val data = if (isZalith) group else group.optJSONObject("viewData") ?: continue
            val items = data.optJSONArray(if (isZalith) "normalButtons" else "buttonList")
            val directions = data.optJSONArray(if (isZalith) "joystickButtons" else "directionList")
            val groupId = group.optString(if (isZalith) "uuid" else "id")
            val hidden = group.optBoolean("hide") || group.optString("visibility") in setOf("INVISIBLE", "GONE")
            if (hidden && groupId.isBlank()) {
                changed += (items?.length() ?: 0) + (directions?.length() ?: 0)
                continue
            }
            if (groupId.isNotBlank()) {
                layers.put(JSONObject()
                    .put("id", groupId)
                    .put("name", group.optString("name"))
                    .put("visibilityType", group.optString("visibilityType", "always").lowercase())
                    .put("hide", hidden)
                    .put("hideWhenMouse", group.optBoolean("hideWhenMouse"))
                    .put("hideWhenGamepad", group.optBoolean("hideWhenGamepad")))
            }
            for (item in directions.objects()) {
                val directionPad = !isZalith && fclDirectionStyles[item.optString("style")] == "BUTTON"
                changed += convertJoystick(item, group, isZalith, joysticks, groupId, hidden, directionPad)
            }
            val labels = if (isZalith) data.optJSONArray("textBoxes").objects() else emptySequence()
            for ((item, labelOnly) in items.objects().map { it to false } + labels.map { it to true }) {
                val output = ButtonActions()
                if (!labelOnly) {
                    if (isZalith) zalithActions(item, output) else fclActions(item, output)
                }
                if (!labelOnly && output.keys.isEmpty() && output.actions.isEmpty() && output.scrollWhileHeld == 0) {
                    changed++
                    continue
                }
                val geometry = if (isZalith) item else item.optJSONObject("baseInfo") ?: JSONObject()
                val label = when (val value = item.opt("text")) {
                    is JSONObject -> value.optString("default")
                    is String -> value
                    else -> ""
                }
                val visibility = geometry.optString("visibilityType", group.optString("visibilityType", "always")).lowercase()
                val size = if (isZalith) item.optJSONObject("buttonSize") ?: JSONObject() else geometry
                val position = if (isZalith) item.optJSONObject("position") ?: JSONObject() else geometry
                val scale = if (isZalith) 10000.0 else 1000.0
                val x = fraction(position.optDouble(if (isZalith) "x" else "xPosition", 0.0), scale)
                val y = fraction(position.optDouble(if (isZalith) "y" else "yPosition", 0.0), scale)
                val approximateSize = size.optString(if (isZalith) "type" else "sizeType").equals("wrap_content", ignoreCase = true)
                if (output.adjusted || approximateSize) changed++
                val button = JSONObject()
                    .put("name", label.take(80))
                    .put("keycodes", JSONArray(output.keys.toList().take(4) + List((4 - output.keys.size).coerceAtLeast(0)) { 0 }))
                    .put("dynamicX", "$x * ${'$'}{right}").put("dynamicY", "$y * ${'$'}{bottom}")
                    .put("width", sizeDp(size, "Width", isZalith)).put("height", sizeDp(size, "Height", isZalith))
                    .put("isToggle", output.toggle && output.keys.all { it > 0 || it in setOf(-3, -4, -6) })
                    .put("passThruEnabled", output.pointerFollow || (isZalith && item.optBoolean("isPenetrable")))
                    .put("isSwipeable", isZalith && item.optBoolean("isSwipple"))
                    .put("displayInGame", visibility !in setOf("menu", "in_menu"))
                    .put("displayInMenu", visibility !in setOf("game", "in_game"))
                    .put("opacity", 1).put("bgColor", if (labelOnly) 0 else 0x80212121.toInt())
                    .put("strokeColor", 0x90ffffff.toInt()).put("strokeWidth", if (labelOnly) 0 else 1)
                    .put("cornerRadius", 35).put("labelOnly", labelOnly).put("scrollWhileHeld", output.scrollWhileHeld)
                    .put("autoClick", output.autoClick)
                if (groupId.isNotBlank()) button.put("groupId", groupId).put("groupInitiallyHidden", hidden)
                for (axis in listOf("Width", "Height")) {
                    val relative = relativeSize(size, axis, isZalith) ?: continue
                    putRelativeSize(button, axis.lowercase(), relative)
                }
                val styleId = item.optString(if (isZalith) "buttonStyle" else "style")
                if (styleId.isNotBlank()) button.put("styleId", styleId)
                styles[styleId]?.let { style ->
                    if (isZalith) {
                        style.optJSONObject("lightStyle")?.let { button.put("appearance", zalithAppearance(it)) }
                        if (!style.optBoolean("commonStyle")) {
                            style.optJSONObject("darkStyle")?.let { button.put("darkAppearance", zalithAppearance(it)) }
                        }
                        if (style.optBoolean("animateSwap")) changed++
                    } else {
                        button.put("appearance", JSONObject().put("normal", fclStyle(style, ""))
                            .put("pressed", fclStyle(style, "Pressed")))
                    }
                }
                for ((name, action) in output.actions) button.put(name, action)
                buttons.put(button)
            }
        }
        return Result(JSONObject().put("version", 9).put("scaledAt", 100)
            .put("mControlDataList", buttons).put("mDrawerDataList", JSONArray())
            .put("mJoystickDataList", joysticks).put("mLayerDataList", layers)
            .put("mStyleDataList", styleList),
            if (isZalith) "ZL2" else "FCL", changed)
    }

    private fun fclStyle(style: JSONObject, suffix: String): JSONObject = JSONObject()
        .put("fill", style.optInt("fillColor$suffix", style.optInt("fillColor")))
        .put("outline", style.optInt("strokeColor$suffix", style.optInt("strokeColor")))
        .put("text", style.optInt("textColor$suffix", style.optInt("textColor", -1)))
        .put("textSp", style.finiteDouble("textSize$suffix", style.finiteDouble("textSize", 14.0)))
        .put("strokeDp", style.finiteDouble("strokeWidth$suffix", style.finiteDouble("strokeWidth")) / 10.0)
        .put("radiusDp", style.finiteDouble("cornerRadius$suffix", style.finiteDouble("cornerRadius")) / 10.0)

    private fun zalithAppearance(style: JSONObject): JSONObject {
        fun state(pressed: Boolean): JSONObject {
            fun key(name: String) = if (pressed) "pressed" + name.replaceFirstChar { it.uppercase() } else name
            val alpha = style.finiteDouble(key("alpha"), 1.0).coerceIn(0.0, 1.0)
            fun color(name: String, default: Int): Int {
                val argb = style.optLong(key(name), style.optLong(name, default.toLong())).toInt()
                return (argb and 0xffffff) or (((argb ushr 24) * alpha).roundToInt() shl 24)
            }
            val corners = style.optJSONObject(key("borderRadius")) ?: style.optJSONObject("borderRadius")
            return JSONObject().put("fill", color("backgroundColor", 0))
                .put("outline", color("borderColor", 0)).put("text", color("contentColor", -1))
                .put("strokeDp", style.finiteDouble(key("borderWidth")))
                .put("cornerPercent", JSONArray(listOf("topStart", "topEnd", "bottomEnd", "bottomStart")
                    .map { corners?.finiteDouble(it) ?: 0.0 }))
        }
        return JSONObject().put("normal", state(false)).put("pressed", state(true))
    }

    private fun JSONObject.finiteDouble(key: String, default: Double = 0.0): Double =
        optDouble(key, default).takeIf { it.isFinite() } ?: default

    private fun convertJoystick(
        item: JSONObject, group: JSONObject, zalith: Boolean, result: JSONArray,
        groupId: String, hidden: Boolean, directionPad: Boolean,
    ): Int {
        val geometry = if (zalith) item else item.optJSONObject("baseInfo") ?: return 1
        val position = if (zalith) geometry.optJSONObject("position") ?: return 1 else geometry
        val scale = if (zalith) 10000.0 else 1000.0
        val x = fraction(position.optDouble(if (zalith) "x" else "xPosition"), scale)
        val y = fraction(position.optDouble(if (zalith) "y" else "yPosition"), scale)
        val visibility = geometry.optString("visibilityType", group.optString("visibilityType", "in_game")).lowercase()
        val size = if (zalith && item.optString("sizeType").equals("dp", ignoreCase = true)) {
            item.optDouble("sizeDp", 200.0).coerceIn(12.0, 400.0).roundToInt()
        } else if (zalith) {
            val heightDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density.coerceAtLeast(0.1f)
            (heightDp * fraction(item.optDouble("sizePercentage", 2500.0), scale)).roundToInt().coerceIn(12, 400)
        } else minOf(sizeDp(geometry, "Width", false), sizeDp(geometry, "Height", false))
        val joystick = JSONObject()
            .put("dynamicX", "$x * ${'$'}{right}").put("dynamicY", "$y * ${'$'}{bottom}")
            .put("width", size).put("height", size)
            .put("directionPad", directionPad)
            .put("displayInGame", visibility !in setOf("menu", "in_menu"))
            .put("displayInMenu", visibility !in setOf("game", "in_game"))
            .put("opacity", 1).put("bgColor", 0x80212121.toInt())
            .put("strokeColor", 0x90ffffff.toInt()).put("strokeWidth", 1)
        if (groupId.isNotBlank()) joystick.put("groupId", groupId).put("groupInitiallyHidden", hidden)
        val relative = if (zalith) {
            if (item.optString("sizeType").equals("dp", ignoreCase = true)) null
            else RelativeSize(fraction(item.optDouble("sizePercentage", 2500.0), 10000.0), true)
        } else {
            val width = relativeSize(geometry, "Width", false)
            val height = relativeSize(geometry, "Height", false)
            width?.takeIf { it == height }
        }
        if (relative != null) {
            putRelativeSize(joystick, "width", relative)
            putRelativeSize(joystick, "height", relative)
        }
        var adjusted = false
        if (zalith) {
            fun keys(events: JSONArray): JSONArray {
                val codes = events.objects().mapNotNull { event ->
                    val raw = event.optString("key")
                    ImportedControlKeys.glfw[ImportedControlKeys.zalithAliases[raw] ?: raw]
                        .takeIf { event.optString("type") == "key" }
                }.toList()
                adjusted = adjusted || codes.size != events.length()
                return JSONArray(codes.distinct())
            }
            val lockKeys = item.optJSONArray("lockEvents")?.let(::keys)
            joystick.put("forwardLock", item.optBoolean("canLock", true) && lockKeys?.length() != 0)
            if (lockKeys != null) joystick.put("forwardLockKeys", lockKeys)
            item.optJSONObject("directionEvents")?.let { events ->
                val directions = listOf("east", "north_east", "north", "north_west", "west", "south_west", "south", "south_east")
                joystick.put("directionKeys", JSONArray(directions.map { events.optJSONArray(it)?.let(::keys) ?: JSONArray() }))
            }
            joystick.put("absolute", true)
                .put("triggerOnMove", !item.optString("triggerMode", "drag").equals("touch", ignoreCase = true))
                .put("deadZonePercent", (fraction(item.optDouble("deadZoneRatio", 0.5), 1.0) * 100).roundToInt())
                .put("forwardLockRatio", fraction(item.optDouble("lockThreshold", 0.3), 1.0))
        } else {
            val event = item.optJSONObject("event") ?: return 1
            val directions = mapOf("up" to "forward", "down" to "backward", "left" to "left", "right" to "right")
            for ((source, target) in directions) {
                val codes = when (val value = event.opt("${source}Keycode")) {
                    is JSONArray -> List(value.length()) { value.optInt(it) }
                    is Number -> listOf(value.toInt())
                    else -> emptyList()
                }
                val keys = codes.mapNotNull { ImportedControlKeys.glfw[ImportedControlKeys.fcl[it]] }.distinct()
                joystick.put("${target}Keys", JSONArray(keys))
                adjusted = adjusted || codes.any { ImportedControlKeys.glfw[ImportedControlKeys.fcl[it]] == null }
            }
            val follow = event.optString("followOption", "FIXED")
            joystick.put("absolute", follow == "FIXED")
                .put("centerFollow", follow == "CENTER_FOLLOW")
            if (event.optBoolean("sneak")) {
                val sneak = ImportedControlKeys.glfw[ImportedControlKeys.fcl[event.optInt("sneakKeycode", 42)]]
                if (sneak != null) joystick.put("doubleTapKey", sneak) else adjusted = true
            }
            adjusted = adjusted || follow !in setOf("FIXED", "CENTER_FOLLOW", "FOLLOW") ||
                sizeDp(geometry, "Width", false) != sizeDp(geometry, "Height", false)
        }
        result.put(joystick)
        return if (adjusted) 1 else 0
    }

    private class ButtonActions {
        val keys = linkedSetOf<Int>()
        val actions = mutableMapOf<String, JSONObject>()
        var adjusted = false
        var toggle = false
        var pointerFollow = false
        var scrollWhileHeld = 0
        var autoClick = false
        fun add(key: Int?) {
            if (key == null || key == 0 || (key !in keys && keys.size >= 4)) adjusted = true
            else keys.add(key)
        }
    }

    private fun zalithActions(item: JSONObject, out: ButtonActions) {
        out.toggle = item.optBoolean("isToggleable")
        val groups = mutableListOf<String>()
        val text = StringBuilder()
        for (event in item.optJSONArray("clickEvents").objects()) {
            val raw = event.optString("key").trim()
            val key = ImportedControlKeys.zalithAliases[raw] ?: raw
            when (event.optString("type")) {
                "switch_layer" -> { if (raw.isNotEmpty()) groups.add(raw); continue }
                "show_layer" -> { if (raw.isNotEmpty()) groups.add("$raw:show"); continue }
                "hide_layer" -> { if (raw.isNotEmpty()) groups.add("$raw:hide"); continue }
                "send_text" -> { text.append(event.optString("key")); continue }
            }
            if (event.optString("type") == "launcher_event" &&
                key in setOf("launcher.event.scroll_up", "launcher.event.scroll_down")) {
                val direction = if (key.endsWith("_up")) 1 else -1
                if (out.scrollWhileHeld != 0 && out.scrollWhileHeld != direction) out.adjusted = true
                out.scrollWhileHeld = direction
                continue
            }
            out.add(when (event.optString("type")) {
                "key", "launcher_event" -> when (key) {
                    "GLFW_MOUSE_BUTTON_LEFT", "GLFW_MOUSE_BUTTON_1" -> -3
                    "GLFW_MOUSE_BUTTON_RIGHT", "GLFW_MOUSE_BUTTON_2" -> -4
                    "GLFW_MOUSE_BUTTON_MIDDLE", "GLFW_MOUSE_BUTTON_3" -> -6
                    "launcher.event.switch_ime" -> -1
                    "launcher.event.switch_menu" -> -9
                    "launcher.event.scroll_up.single" -> -7
                    "launcher.event.scroll_down.single" -> -8
                    else -> ImportedControlKeys.glfw[key]
                }
                else -> null
            })
        }
        if (groups.isNotEmpty() || text.isNotEmpty()) {
            out.actions["pressAction"] = JSONObject().put("groups", JSONArray(groups)).put("text", text.toString())
        }
    }

    private fun fclActions(item: JSONObject, out: ButtonActions) {
        val events = item.optJSONObject("event") ?: return
        out.pointerFollow = events.optBoolean("pointerFollow")
        val press = events.optJSONObject("pressEvent")
        out.toggle = press?.optBoolean("autoKeep") == true
        out.autoClick = press?.optBoolean("autoClick") == true
        for (kind in listOf("pressEvent", "clickEvent", "longPressEvent", "doubleClickEvent")) {
            val event = events.optJSONObject(kind) ?: continue
            val keys = when (val value = event.opt("outputKeycodes")) {
                is JSONArray -> List(value.length()) { value.optInt(it) }
                is String, is Number -> listOf(event.optInt("outputKeycodes"))
                else -> emptyList()
            }
            val binding = if (kind == "pressEvent") out else ButtonActions()
            val switchTouchMode = event.optBoolean("switchTouchMode")
            if (event.optBoolean("switchMouseMode") || event.optBoolean("quickInput") ||
                (kind != "pressEvent" && event.optBoolean("autoKeep"))) out.adjusted = true
            if (event.optBoolean("input")) binding.add(-1)
            if (event.optBoolean("openMenu")) binding.add(-9)
            for (code in keys) {
                binding.add(when (code) {
                    1000 -> -3
                    1001 -> -6
                    1002 -> -4
                    1003 -> -7
                    1004 -> -8
                    else -> ImportedControlKeys.glfw[ImportedControlKeys.fcl[code]]
                })
            }
            val text = event.optString("outputText")
            val groups = event.optJSONArray("bindViewGroup")
            val actionKeys = if (binding === out) emptySet() else binding.keys
            out.adjusted = out.adjusted || binding.adjusted
            if (actionKeys.isNotEmpty() || text.isNotEmpty() || (groups?.length() ?: 0) > 0 || switchTouchMode) {
                val action = JSONObject().put("keys", JSONArray(actionKeys)).put("text", text)
                    .put("autoClick", kind == "longPressEvent" && event.optBoolean("autoClick"))
                    .put("switchTouchMode", switchTouchMode)
                if (groups != null) action.put("groups", groups)
                out.actions[kind.removeSuffix("Event") + "Action"] = action
            }
        }
    }

    private fun sizeDp(size: JSONObject, axis: String, zalith: Boolean): Int {
        val relative = relativeSize(size, axis, zalith)
        val dp = if (relative != null) {
            val pixels = if (relative.vertical) minOf(metrics.widthPixels, metrics.heightPixels)
                else maxOf(metrics.widthPixels, metrics.heightPixels)
            pixels / metrics.density.coerceAtLeast(0.1f) * relative.ratio
        } else size.optDouble(if (zalith) "${axis.lowercase()}Dp" else "absolute$axis", 50.0)
        return dp.takeIf { it.isFinite() }?.coerceIn(12.0, 400.0)?.roundToInt() ?: 50
    }

    private data class RelativeSize(val ratio: Double, val vertical: Boolean)

    private fun relativeSize(size: JSONObject, axis: String, zalith: Boolean): RelativeSize? {
        if (!size.optString(if (zalith) "type" else "sizeType").equals("percentage", ignoreCase = true)) return null
        val lower = axis.lowercase()
        val fcl = size.optJSONObject("percentage$axis")
        val reference = if (zalith) size.optString("${lower}Reference") else fcl?.optString("reference")
        val ratio = if (zalith) fraction(size.optDouble("${lower}Percentage", 500.0), 10000.0)
            else fraction(fcl?.optDouble("size", 50.0) ?: 50.0, 1000.0)
        return RelativeSize(ratio, reference.equals("screen_height", ignoreCase = true))
    }

    private fun putRelativeSize(target: JSONObject, axis: String, size: RelativeSize) {
        target.put("${axis}Fraction", size.ratio).put("${axis}RelativeToHeight", size.vertical)
    }

    private fun fraction(value: Double, scale: Double): Double =
        (value.takeIf { it.isFinite() } ?: 0.0).coerceIn(0.0, scale) / scale

    private fun JSONArray?.objects(): Sequence<JSONObject> = sequence {
        if (this@objects != null) for (index in 0 until length()) optJSONObject(index)?.let { yield(it) }
    }
}
