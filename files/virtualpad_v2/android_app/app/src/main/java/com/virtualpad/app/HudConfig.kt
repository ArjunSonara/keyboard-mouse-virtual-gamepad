package com.virtualpad.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ElementType {
    BUTTON,
    STICK,
    DPAD,
    SCROLL_WHEEL,
    STEERING_WHEEL
}

enum class SteeringMode(val id: Int, val displayName: String) {
    OFF(0, "Normal Stick"),
    PEDALS(1, "Pedals & Arrows"),
    WHEEL(2, "Steering Wheel"),
    HELICOPTER(3, "Helicopter (8/4/5/6)")
}

enum class AimCurveMode {
    LINEAR,
    S_CURVE
}

enum class ButtonShape {
    CIRCLE,
    SQUARE,
    ROUNDED_RECT
}

data class KeyOption(val code: String, val displayName: String)

/**
 * Validated list of keys recognized by Interception and games.
 */
val VALID_KEY_LIST = listOf(
    KeyOption("mouse_left", "Left Click (LMB / Shoot)"),
    KeyOption("mouse_right", "Right Click (RMB / Aim)"),
    KeyOption("mouse_middle", "Middle Click (MMB / Ping)"),
    KeyOption("space", "Space"),
    KeyOption("enter", "Enter"),
    KeyOption("tab", "Tab"),
    KeyOption("esc", "Esc"),
    KeyOption("shift", "Left Shift"),
    KeyOption("ctrl", "Left Ctrl"),
    KeyOption("alt", "Left Alt"),
    KeyOption("backspace", "Backspace"),
    KeyOption("capslock", "Caps Lock"),
    KeyOption("e", "E (Interact)"),
    KeyOption("r", "R (Reload)"),
    KeyOption("f", "F (Action)"),
    KeyOption("q", "Q (Cover)"),
    KeyOption("c", "C (Crouch/EagleEye)"),
    KeyOption("z", "Z (Prone)"),
    KeyOption("v", "V (Camera/Melee)"),
    KeyOption("x", "X (Special)"),
    KeyOption("m", "M (Map)"),
    KeyOption("b", "B (Journal/Bag)"),
    KeyOption("h", "H (Whistle)"),
    KeyOption("t", "T (Chat/Track)"),
    KeyOption("g", "G (Grenade)"),
    KeyOption("1", "1 (Weapon 1)"),
    KeyOption("2", "2 (Weapon 2)"),
    KeyOption("3", "3 (Weapon 3)"),
    KeyOption("4", "4 (Weapon 4)"),
    KeyOption("5", "5 (Item 5)"),
    KeyOption("6", "6 (Item 6)"),
    KeyOption("7", "7"),
    KeyOption("8", "8"),
    KeyOption("9", "9"),
    KeyOption("0", "0"),
    KeyOption("a", "A"),
    KeyOption("d", "D"),
    KeyOption("i", "I (Inventory)"),
    KeyOption("j", "J (Journal)"),
    KeyOption("k", "K"),
    KeyOption("l", "L"),
    KeyOption("n", "N"),
    KeyOption("o", "O"),
    KeyOption("p", "P"),
    KeyOption("s", "S"),
    KeyOption("u", "U"),
    KeyOption("w", "W"),
    KeyOption("y", "Y"),
    KeyOption("[", "[ (Aim / RMB)"),
    KeyOption(";", "; (Shoot / LMB)"),
    KeyOption("]", "]"),
    KeyOption(",", ", (Comma)"),
    KeyOption(".", ". (Period)"),
    KeyOption("/", "/ (Slash)"),
    KeyOption("-", "- (Minus)"),
    KeyOption("=", "= (Equals)"),
    KeyOption("`", "` (Tilde)"),
    KeyOption("f1", "F1"),
    KeyOption("f2", "F2"),
    KeyOption("f3", "F3"),
    KeyOption("f4", "F4"),
    KeyOption("f5", "F5"),
    KeyOption("f6", "F6"),
    KeyOption("f7", "F7"),
    KeyOption("f8", "F8"),
    KeyOption("f9", "F9"),
    KeyOption("f10", "F10"),
    KeyOption("f11", "F11"),
    KeyOption("f12", "F12"),
    KeyOption("up", "Up Arrow"),
    KeyOption("down", "Down Arrow"),
    KeyOption("left", "Left Arrow"),
    KeyOption("right", "Right Arrow")
)

enum class MacroActionType {
    TAP,        // Tap key for durationMs
    HOLD,       // Press down key (held until RELEASE)
    RELEASE,    // Release key
    WAIT        // Pause/delay for durationMs
}

data class MacroStep(
    var action: MacroActionType,
    var key: String = "",
    var durationMs: Long = 50
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("a", action.name)
        if (key.isNotEmpty()) o.put("k", key)
        if (durationMs > 0) o.put("d", durationMs)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): MacroStep {
            val a = try { MacroActionType.valueOf(o.optString("a", "TAP")) } catch (_: Exception) { MacroActionType.TAP }
            return MacroStep(
                action = a,
                key = o.optString("k", ""),
                durationMs = o.optLong("d", 50)
            )
        }

        fun listToJson(steps: List<MacroStep>): String {
            val arr = JSONArray()
            for (s in steps) {
                arr.put(s.toJson())
            }
            return arr.toString()
        }

        fun listFromJson(jsonStr: String): MutableList<MacroStep> {
            val list = mutableListOf<MacroStep>()
            if (jsonStr.isBlank()) return list
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    list.add(fromJson(arr.getJSONObject(i)))
                }
            } catch (_: Exception) {
                return parseScriptText(jsonStr)
            }
            return list
        }

        fun toScriptText(steps: List<MacroStep>): String {
            val sb = StringBuilder()
            for (step in steps) {
                when (step.action) {
                    MacroActionType.TAP -> sb.append("TAP ${step.key.lowercase()} ${step.durationMs}ms\n")
                    MacroActionType.HOLD -> sb.append("HOLD ${step.key.lowercase()}\n")
                    MacroActionType.RELEASE -> sb.append("RELEASE ${step.key.lowercase()}\n")
                    MacroActionType.WAIT -> sb.append("WAIT ${step.durationMs}ms\n")
                }
            }
            return sb.toString().trimEnd()
        }

        fun parseScriptText(text: String): MutableList<MacroStep> {
            val list = mutableListOf<MacroStep>()
            if (text.isBlank()) return list

            if (text.trim().startsWith("[") && text.trim().endsWith("]")) {
                try {
                    val arr = JSONArray(text.trim())
                    for (i in 0 until arr.length()) {
                        list.add(fromJson(arr.getJSONObject(i)))
                    }
                    if (list.isNotEmpty()) return list
                } catch (_: Exception) {}
            }

            val lines = text.split("\n", ";", ",")
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue

                val parts = line.split("\\s+".toRegex())
                val cmd = parts[0].uppercase()

                when {
                    cmd == "TAP" -> {
                        val key = parts.getOrNull(1)?.lowercase() ?: "space"
                        val msStr = parts.getOrNull(2)?.replace("ms", "", ignoreCase = true) ?: "50"
                        val ms = msStr.toLongOrNull()?.coerceIn(5, 10000) ?: 50L
                        list.add(MacroStep(MacroActionType.TAP, key, ms))
                    }
                    cmd == "HOLD" || cmd == "DOWN" || cmd == "PRESS" -> {
                        val key = parts.getOrNull(1)?.lowercase() ?: "shift"
                        list.add(MacroStep(MacroActionType.HOLD, key, 0))
                    }
                    cmd == "RELEASE" || cmd == "UP" -> {
                        val key = parts.getOrNull(1)?.lowercase() ?: "shift"
                        list.add(MacroStep(MacroActionType.RELEASE, key, 0))
                    }
                    cmd == "WAIT" || cmd == "SLEEP" || cmd == "DELAY" -> {
                        val msStr = (parts.getOrNull(1) ?: parts.getOrNull(0) ?: "50").replace("ms", "", ignoreCase = true)
                        val ms = msStr.toLongOrNull()?.coerceIn(5, 10000) ?: 50L
                        list.add(MacroStep(MacroActionType.WAIT, "", ms))
                    }
                    line.all { it.isDigit() } -> {
                        val ms = line.toLongOrNull()?.coerceIn(5, 10000) ?: 50L
                        list.add(MacroStep(MacroActionType.WAIT, "", ms))
                    }
                    line.contains(":") -> {
                        val sub = line.split(":")
                        val k = sub[0].trim().lowercase()
                        val ms = sub.getOrNull(1)?.trim()?.replace("ms", "", ignoreCase = true)?.toLongOrNull() ?: 50L
                        if (k == "wait" || k == "delay" || k == "sleep") {
                            list.add(MacroStep(MacroActionType.WAIT, "", ms))
                        } else {
                            list.add(MacroStep(MacroActionType.TAP, k, ms))
                        }
                    }
                    else -> {
                        val key = parts[0].lowercase()
                        val ms = parts.getOrNull(1)?.replace("ms", "", ignoreCase = true)?.toLongOrNull() ?: 50L
                        list.add(MacroStep(MacroActionType.TAP, key, ms))
                    }
                }
            }
            return list
        }
    }
}

data class HudElement(
    val id: String,
    var label: String,
    var key: String,
    val type: ElementType,
    var xPct: Float,
    var yPct: Float,
    var scale: Float = 1.0f,
    val isCustom: Boolean = false,
    val customSlot: Int = -1, // 0..15 for custom buttons, -1 for stock
    var zOrder: Int = 0,
    var shape: ButtonShape = ButtonShape.CIRCLE,
    var dpadUpKey: String = "h",
    var dpadDownKey: String = "t",
    var dpadLeftKey: String = "x",
    var dpadRightKey: String = "x",
    var isToggle: Boolean = false,
    var isTurbo: Boolean = false,
    var turboCps: Int = 12,
    var macroType: String = "",
    var customMacro: String = "",
    var rotation: Float = 0f,
    var swipeToAim: Boolean = false,
    var touchPadding: Float = 1.0f,
    var showGhostShadow: Boolean = false,
    var userExplicitGhostShadow: Boolean = false,
    var maxDragDistance: Float = 0.8f,
    var userExplicitDragDist: Boolean = false,
    var isInstantTap: Boolean = false,
    var instantTapDurationMs: Int = 15,
    var isEnabled: Boolean = true,
    var opacity: Float = 1.0f
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("label", label)
        obj.put("key", key)
        obj.put("type", type.name)
        obj.put("xPct", xPct.toDouble())
        obj.put("yPct", yPct.toDouble())
        obj.put("scale", scale.toDouble())
        obj.put("isCustom", isCustom)
        obj.put("customSlot", customSlot)
        obj.put("zOrder", zOrder)
        obj.put("shape", shape.name)
        obj.put("rotation", rotation.toDouble())
        obj.put("isEnabled", isEnabled)
        obj.put("opacity", opacity.toDouble())
        if (type == ElementType.DPAD) {
            obj.put("dpadUpKey", dpadUpKey)
            obj.put("dpadDownKey", dpadDownKey)
            obj.put("dpadLeftKey", dpadLeftKey)
            obj.put("dpadRightKey", dpadRightKey)
        }
        if (type == ElementType.BUTTON) {
            obj.put("isToggle", isToggle)
            obj.put("isTurbo", isTurbo)
            obj.put("turboCps", turboCps)
            obj.put("macroType", macroType)
            obj.put("customMacro", customMacro)
            obj.put("swipeToAim", swipeToAim)
            obj.put("touchPadding", touchPadding.toDouble())
            obj.put("showGhostShadow", showGhostShadow)
            obj.put("userExplicitGhostShadow", userExplicitGhostShadow)
            obj.put("maxDragDistance", maxDragDistance.toDouble())
            obj.put("userExplicitDragDist", userExplicitDragDist)
            obj.put("isInstantTap", isInstantTap)
            obj.put("instantTapDurationMs", instantTapDurationMs)
        }
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): HudElement {
            val typeStr = obj.optString("type", ElementType.BUTTON.name)
            val type = try { ElementType.valueOf(typeStr) } catch (_: Exception) { ElementType.BUTTON }
            val shapeStr = obj.optString("shape", ButtonShape.CIRCLE.name)
            val shape = try { ButtonShape.valueOf(shapeStr) } catch (_: Exception) { ButtonShape.CIRCLE }
            val keyStr = obj.optString("key", "")
            val idStr = obj.getString("id")
            val isMouseKey = keyStr.lowercase() in listOf("mouse_left", "mouse_right", "mouse_middle") || idStr.lowercase() in listOf("rt", "lt")
            val defaultSwipeAim = isMouseKey
            val swipeToAim = obj.optBoolean("swipeToAim", defaultSwipeAim)
            val defaultPadding = if (isMouseKey) 1.4f else 1.0f
            val touchPadding = obj.optDouble("touchPadding", defaultPadding.toDouble()).toFloat()
            val defaultGhostShadow = isMouseKey
            val showGhostShadow = if (obj.has("userExplicitGhostShadow")) {
                obj.optBoolean("showGhostShadow", defaultGhostShadow)
            } else {
                defaultGhostShadow
            }
            val userExplicitGhostShadow = obj.optBoolean("userExplicitGhostShadow", false)
            val hasExplicitDrag = obj.optBoolean("userExplicitDragDist", false)
            val maxDragDistance = if (hasExplicitDrag) {
                obj.optDouble("maxDragDistance", 0.8).toFloat()
            } else {
                0.8f
            }
            val userExplicitDragDist = hasExplicitDrag
            val isInstantTap = obj.optBoolean("isInstantTap", false)
            val instantTapDurationMs = obj.optInt("instantTapDurationMs", 15)
            val isEnabled = obj.optBoolean("isEnabled", true)
            return HudElement(
                id = idStr,
                label = obj.optString("label", ""),
                key = keyStr,
                type = type,
                xPct = obj.optDouble("xPct", 0.5).toFloat(),
                yPct = obj.optDouble("yPct", 0.5).toFloat(),
                scale = obj.optDouble("scale", 1.0).toFloat(),
                isCustom = obj.optBoolean("isCustom", false),
                customSlot = obj.optInt("customSlot", -1),
                zOrder = obj.optInt("zOrder", 0),
                shape = shape,
                dpadUpKey = obj.optString("dpadUpKey", "h"),
                dpadDownKey = obj.optString("dpadDownKey", "t"),
                dpadLeftKey = obj.optString("dpadLeftKey", "x"),
                dpadRightKey = obj.optString("dpadRightKey", "x"),
                isToggle = obj.optBoolean("isToggle", false),
                isTurbo = obj.optBoolean("isTurbo", false),
                turboCps = obj.optInt("turboCps", 12),
                macroType = obj.optString("macroType", ""),
                customMacro = obj.optString("customMacro", ""),
                rotation = obj.optDouble("rotation", 0.0).toFloat(),
                swipeToAim = swipeToAim,
                touchPadding = touchPadding,
                showGhostShadow = showGhostShadow,
                userExplicitGhostShadow = userExplicitGhostShadow,
                maxDragDistance = maxDragDistance,
                userExplicitDragDist = userExplicitDragDist,
                isInstantTap = isInstantTap,
                instantTapDurationMs = instantTapDurationMs,
                isEnabled = isEnabled,
                opacity = obj.optDouble("opacity", 1.0).toFloat()
            )
        }
    }
}

object HudConfig {
    private const val PREFS_NAME = "virtualpad_hud"
    private const val KEY_ACTIVE_PROFILE = "active_profile"
    private const val KEY_PROFILES_LIST = "profiles_list"
    private const val KEY_OPACITY = "hud_opacity"
    private const val KEY_FILL_OPACITY = "hud_fill_opacity"
    private const val KEY_BORDER_OPACITY = "hud_border_opacity"
    private const val KEY_TEXT_OPACITY = "hud_text_opacity"
    private const val KEY_GYRO_ENABLED = "gyro_enabled"
    private const val KEY_GYRO_SENSITIVITY = "gyro_sensitivity"
    private const val KEY_GYRO_AIM_ONLY = "gyro_aim_only"

    fun getDefaultElements(): List<HudElement> {
        val list = mutableListOf<HudElement>()
        var z = 1

        // Left Stick & D-Pad (Base Controls)
        list.add(HudElement(id = "leftstick", label = "STICK", key = "", type = ElementType.STICK, xPct = 0.13f, yPct = 0.62f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "dpad", label = "D-PAD", key = "", type = ElementType.DPAD, xPct = 0.32f, yPct = 0.72f, scale = 1.0f, zOrder = z++, dpadUpKey = "h", dpadDownKey = "t", dpadLeftKey = "x", dpadRightKey = "x"))

        // Bumpers & Triggers
        list.add(HudElement(id = "lb", label = "LB (TAB)", key = "tab", type = ElementType.BUTTON, xPct = 0.10f, yPct = 0.11f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))
        list.add(HudElement(id = "lt", label = "LT (RMB)", key = "mouse_right", type = ElementType.BUTTON, xPct = 0.23f, yPct = 0.11f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT, swipeToAim = true))
        list.add(HudElement(id = "rt", label = "RT (LMB)", key = "mouse_left", type = ElementType.BUTTON, xPct = 0.77f, yPct = 0.11f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT, swipeToAim = true))
        list.add(HudElement(id = "rb", label = "RB (Q)", key = "q", type = ElementType.BUTTON, xPct = 0.90f, yPct = 0.11f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))

        // ABXY Diamond
        val abxyCx = 0.87f
        val abxyCy = 0.60f
        val abxySpreadY = 0.11f
        val abxySpreadX = 0.065f
        list.add(HudElement(id = "y", label = "Y (E)", key = "e", type = ElementType.BUTTON, xPct = abxyCx, yPct = abxyCy - abxySpreadY, scale = 1.0f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "a", label = "A (SHIFT)", key = "shift", type = ElementType.BUTTON, xPct = abxyCx, yPct = abxyCy + abxySpreadY, scale = 1.0f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "x", label = "X (SPACE)", key = "space", type = ElementType.BUTTON, xPct = abxyCx - abxySpreadX, yPct = abxyCy, scale = 1.0f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "b", label = "B (R)", key = "r", type = ElementType.BUTTON, xPct = abxyCx + abxySpreadX, yPct = abxyCy, scale = 1.0f, zOrder = z++, shape = ButtonShape.CIRCLE))

        // Stick Click Buttons
        list.add(HudElement(id = "lsb", label = "LSB (CTRL)", key = "ctrl", type = ElementType.BUTTON, xPct = 0.13f, yPct = 0.88f, scale = 1.0f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "rsb", label = "RSB (C)", key = "c", type = ElementType.BUTTON, xPct = 0.87f, yPct = 0.88f, scale = 1.0f, zOrder = z++, shape = ButtonShape.CIRCLE))

        // Center System Buttons
        list.add(HudElement(id = "small_icon", label = "ESC", key = "esc", type = ElementType.BUTTON, xPct = 0.45f, yPct = 0.93f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))
        list.add(HudElement(id = "hamburger_icon", label = "B (MENU)", key = "b", type = ElementType.BUTTON, xPct = 0.55f, yPct = 0.93f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))

        // Mouse Scroll Wheel Strip (Right Edge)
        list.add(HudElement(id = "scroll_wheel", label = "WHEEL", key = "mouse_wheel", type = ElementType.SCROLL_WHEEL, xPct = 0.96f, yPct = 0.45f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))

        return list
    }

    /**
     * 2nd Default HUD Preset (Action / FPS Dual Trigger layout).
     * Features large dedicated LT(LMB) & RT(RMB) triggers, C1(M), C2(F), C3(Enter),
     * and optimized combat positioning.
     */
    fun getDefault2Elements(): List<HudElement> {
        val list = mutableListOf<HudElement>()
        var z = 1

        // Top Left cluster
        list.add(HudElement(id = "custom_btn_0", label = "C1 (M)", key = "m", type = ElementType.BUTTON, xPct = 0.08f, yPct = 0.14f, scale = 0.95f, isCustom = true, customSlot = 0, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "lb", label = "LB (TAB)", key = "tab", type = ElementType.BUTTON, xPct = 0.24f, yPct = 0.09f, scale = 1.10f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))

        // Mid Left trigger & stick clicks
        list.add(HudElement(id = "rsb", label = "RSB (C)", key = "c", type = ElementType.BUTTON, xPct = 0.07f, yPct = 0.37f, scale = 0.95f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "lt", label = "LT (LMB)", key = "mouse_left", type = ElementType.BUTTON, xPct = 0.25f, yPct = 0.33f, scale = 1.55f, zOrder = z++, shape = ButtonShape.CIRCLE, swipeToAim = true))

        // Bottom Left Movement
        list.add(HudElement(id = "leftstick", label = "STICK", key = "", type = ElementType.STICK, xPct = 0.15f, yPct = 0.75f, scale = 1.15f, zOrder = z++))
        list.add(HudElement(id = "dpad", label = "D-PAD", key = "", type = ElementType.DPAD, xPct = 0.35f, yPct = 0.70f, scale = 1.00f, zOrder = z++, dpadUpKey = "h", dpadDownKey = "t", dpadLeftKey = "x", dpadRightKey = "x"))
        list.add(HudElement(id = "hamburger_icon", label = "B (MENU)", key = "b", type = ElementType.BUTTON, xPct = 0.34f, yPct = 0.92f, scale = 0.85f, zOrder = z++, shape = ButtonShape.CIRCLE))

        // Top Right cluster
        list.add(HudElement(id = "rb", label = "RB (Q)", key = "q", type = ElementType.BUTTON, xPct = 0.90f, yPct = 0.10f, scale = 1.10f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))
        list.add(HudElement(id = "rt", label = "RT (RMB)", key = "mouse_right", type = ElementType.BUTTON, xPct = 0.74f, yPct = 0.20f, scale = 1.65f, zOrder = z++, shape = ButtonShape.CIRCLE, swipeToAim = true))

        // Mid Right Actions & Extras
        list.add(HudElement(id = "custom_btn_1", label = "C2 (F)", key = "f", type = ElementType.BUTTON, xPct = 0.73f, yPct = 0.44f, scale = 0.95f, isCustom = true, customSlot = 1, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "small_icon", label = "ESC", key = "esc", type = ElementType.BUTTON, xPct = 0.93f, yPct = 0.33f, scale = 0.95f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))
        list.add(HudElement(id = "y", label = "Y (E)", key = "e", type = ElementType.BUTTON, xPct = 0.82f, yPct = 0.44f, scale = 0.95f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "a", label = "A (SHIFT)", key = "shift", type = ElementType.BUTTON, xPct = 0.82f, yPct = 0.60f, scale = 0.95f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "x", label = "X (SPACE)", key = "space", type = ElementType.BUTTON, xPct = 0.93f, yPct = 0.68f, scale = 0.95f, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "b", label = "B (R)", key = "r", type = ElementType.BUTTON, xPct = 0.73f, yPct = 0.84f, scale = 0.95f, zOrder = z++, shape = ButtonShape.CIRCLE))

        // Bottom Right
        list.add(HudElement(id = "custom_btn_2", label = "C3 (ENTER)", key = "enter", type = ElementType.BUTTON, xPct = 0.83f, yPct = 0.92f, scale = 0.85f, isCustom = true, customSlot = 2, zOrder = z++, shape = ButtonShape.CIRCLE))
        list.add(HudElement(id = "lsb", label = "LSB (CTRL)", key = "ctrl", type = ElementType.BUTTON, xPct = 0.93f, yPct = 0.88f, scale = 0.95f, zOrder = z++, shape = ButtonShape.CIRCLE))

        // Mouse Scroll Wheel Strip (Right Edge)
        list.add(HudElement(id = "scroll_wheel", label = "WHEEL", key = "mouse_wheel", type = ElementType.SCROLL_WHEEL, xPct = 0.97f, yPct = 0.48f, scale = 1.0f, zOrder = z++, shape = ButtonShape.ROUNDED_RECT))

        return list
    }

    enum class ClawStyle(val id: Int, val displayName: String) {
        TWO_FINGER(2, "2-Finger (Thumbs)"),
        THREE_FINGER(3, "3-Finger Claw"),
        FOUR_FINGER(4, "4-Finger Claw"),
        CONTROLLER(5, "Controller Panel")
    }

    enum class BindingRole {
        PRIMARY_TRIGGER,
        SECONDARY_TRIGGER,
        JUMP,
        SPRINT,
        CROUCH,
        RELOAD,
        INTERACT,
        ABILITY_1,
        ABILITY_2,
        ABILITY_3,
        ABILITY_4,
        WHEEL_MENU,
        MAP_MENU,
        EXTRA_1,
        EXTRA_2,
        EXTRA_3
    }

    data class GameBinding(
        val action: String,
        val key: String,
        val role: BindingRole,
        val shape: ButtonShape = ButtonShape.CIRCLE,
        val swipeToAim: Boolean = false
    )

    data class GamePreset(
        val id: String,
        val name: String,
        val category: String,
        val bindings: List<GameBinding>
    )

    private fun gb(action: String, key: String, role: BindingRole, shape: ButtonShape = ButtonShape.CIRCLE, swipeToAim: Boolean = false) =
        GameBinding(action, key, role, shape, swipeToAim)

    val ALL_GAME_PRESETS: List<GamePreset> = listOf(
        // 1. Open World & Action
        GamePreset("gta5", "Grand Theft Auto V", "Open World", listOf(
            gb("FIRE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP / BRAKE", "space", BindingRole.JUMP),
            gb("SPRINT / HORN", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("STEALTH", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("ENTER CAR", "f", BindingRole.INTERACT),
            gb("COVER", "q", BindingRole.ABILITY_1),
            gb("ABILITY", "capslock", BindingRole.ABILITY_2),
            gb("GRENADE", "g", BindingRole.ABILITY_3),
            gb("CAMERA", "v", BindingRole.ABILITY_4),
            gb("WEAPONS", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MENU", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PHONE", "up", BindingRole.EXTRA_1),
            gb("LIGHTS", "h", BindingRole.EXTRA_2),
            gb("TALK", "e", BindingRole.EXTRA_3)
        )),
        GamePreset("rdr2", "Red Dead Redemption 2", "Open World", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / DRAW", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP / CLIMB", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("HORSE / MOUNT", "f", BindingRole.INTERACT),
            gb("COVER", "q", BindingRole.ABILITY_1),
            gb("DEAD EYE", "capslock", BindingRole.ABILITY_2),
            gb("EAGLE EYE", "mouse_middle", BindingRole.ABILITY_3),
            gb("WHISTLE", "h", BindingRole.ABILITY_4),
            gb("WEAPONS", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("INTERACT", "e", BindingRole.EXTRA_1),
            gb("JOURNAL", "j", BindingRole.EXTRA_2),
            gb("SATCHEL", "b", BindingRole.EXTRA_3)
        )),
        GamePreset("rdr1", "Red Dead Redemption 1", "Open World", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("MOUNT", "f", BindingRole.INTERACT),
            gb("COVER", "q", BindingRole.ABILITY_1),
            gb("DEAD EYE", "capslock", BindingRole.ABILITY_2),
            gb("WHISTLE", "h", BindingRole.ABILITY_3),
            gb("WEAPONS", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("INTERACT", "e", BindingRole.EXTRA_1)
        )),
        GamePreset("cyberpunk", "Cyberpunk 2077", "Open World", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / BLOCK", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SLIDE / DUCK", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("ACTION / CAR", "f", BindingRole.INTERACT),
            gb("MELEE", "q", BindingRole.ABILITY_1),
            gb("CYBERWARE", "e", BindingRole.ABILITY_2),
            gb("INHALER", "x", BindingRole.ABILITY_3),
            gb("CALL CAR", "v", BindingRole.ABILITY_4),
            gb("SCANNER", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PHONE", "t", BindingRole.EXTRA_1),
            gb("GADGET", "g", BindingRole.EXTRA_2),
            gb("WEAPON 1", "1", BindingRole.EXTRA_3)
        )),
        GamePreset("tsushima", "Ghost of Tsushima", "Open World", listOf(
            gb("LIGHT ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("PARRY / GUARD", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DODGE / ROLL", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "c", BindingRole.CROUCH),
            gb("HEAL", "r", BindingRole.RELOAD),
            gb("INTERACT", "e", BindingRole.INTERACT),
            gb("HEAVY ATTACK", "q", BindingRole.ABILITY_1),
            gb("GHOST STANCE", "v", BindingRole.ABILITY_2),
            gb("GHOST WEAPON", "f", BindingRole.ABILITY_3),
            gb("BOW / AIM", "alt", BindingRole.ABILITY_4),
            gb("STANCE WHEEL", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("WIND", "t", BindingRole.EXTRA_1),
            gb("FLUTE", "z", BindingRole.EXTRA_2)
        )),
        GamePreset("horizon", "Horizon Zero Dawn / FW", "Open World", listOf(
            gb("FIRE BOW", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM BOW", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SLIDE / CROUCH", "c", BindingRole.CROUCH),
            gb("HEAL BERRIES", "v", BindingRole.RELOAD),
            gb("LOOT / USE", "e", BindingRole.INTERACT),
            gb("FOCUS SCAN", "v", BindingRole.ABILITY_1),
            gb("HEAVY MELEE", "f", BindingRole.ABILITY_2),
            gb("CALL MOUNT", "x", BindingRole.ABILITY_3),
            gb("TRAP / POTION", "f", BindingRole.ABILITY_4),
            gb("WEAPON WHEEL", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("ROLL", "ctrl", BindingRole.EXTRA_1),
            gb("SKILLS", "k", BindingRole.EXTRA_2)
        )),
        GamePreset("witcher3", "The Witcher 3: Wild Hunt", "Open World", listOf(
            gb("FAST ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("PARRY / SENSE", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DODGE", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("ROLL", "alt", BindingRole.CROUCH),
            gb("CAST SIGN", "q", BindingRole.RELOAD),
            gb("INTERACT", "e", BindingRole.INTERACT),
            gb("STRONG ATTACK", "shift", BindingRole.ABILITY_1),
            gb("POTION 1", "r", BindingRole.ABILITY_2),
            gb("POTION 2", "f", BindingRole.ABILITY_3),
            gb("CALL ROACH", "x", BindingRole.ABILITY_4),
            gb("SIGN WHEEL", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("INVENTORY", "i", BindingRole.EXTRA_1),
            gb("STEEL SWORD", "1", BindingRole.EXTRA_2),
            gb("SILVER SWORD", "2", BindingRole.EXTRA_3)
        )),
        GamePreset("death_stranding", "Death Stranding", "Open World", listOf(
            gb("LEFT STRAP", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("RIGHT STRAP", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH / REST", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("PICK UP", "f", BindingRole.INTERACT),
            gb("ODRADEK SCAN", "tab", BindingRole.ABILITY_1),
            gb("HOLD BREATH", "alt", BindingRole.ABILITY_2),
            gb("DROP CARGO", "v", BindingRole.ABILITY_3),
            gb("SHOUT / LIKE", "g", BindingRole.ABILITY_4),
            gb("CARGO MENU", "c", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("EQUIP", "1", BindingRole.EXTRA_1),
            gb("VEHICLE", "e", BindingRole.EXTRA_2)
        )),
        GamePreset("crimson_desert", "Crimson Desert", "Open World", listOf(
            gb("LIGHT ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("HEAVY / GUARD", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("DODGE / ROLL", "f", BindingRole.CROUCH),
            gb("POTION", "1", BindingRole.RELOAD),
            gb("INTERACT", "r", BindingRole.INTERACT),
            gb("SKILL 1", "e", BindingRole.ABILITY_1),
            gb("SKILL 2", "q", BindingRole.ABILITY_2),
            gb("ABILITY", "c", BindingRole.ABILITY_3),
            gb("MOUNT", "alt", BindingRole.ABILITY_4),
            gb("INVENTORY", "i", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),

        // 2. Tactical Shooters & Battle Royale
        GamePreset("cs2_val", "Counter-Strike 2 / Valorant", "Tactical Shooter", listOf(
            gb("FIRE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("ADS / SCOPE", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("WALK", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("DEFUSE / USE", "e", BindingRole.INTERACT),
            gb("ABILITY 1", "q", BindingRole.ABILITY_1),
            gb("ABILITY 2", "c", BindingRole.ABILITY_2),
            gb("ULTIMATE", "x", BindingRole.ABILITY_3),
            gb("PLANT SPIKE", "4", BindingRole.ABILITY_4),
            gb("SCOREBOARD", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PRIMARY", "1", BindingRole.EXTRA_1),
            gb("PISTOL", "2", BindingRole.EXTRA_2),
            gb("KNIFE", "3", BindingRole.EXTRA_3)
        )),
        GamePreset("apex", "Apex Legends", "Battle Royale", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / ADS", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SLIDE / DUCK", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("INTERACT / LOOT", "e", BindingRole.INTERACT),
            gb("TACTICAL", "q", BindingRole.ABILITY_1),
            gb("ULTIMATE", "z", BindingRole.ABILITY_2),
            gb("GRENADE", "g", BindingRole.ABILITY_3),
            gb("HEAL / SHIELD", "4", BindingRole.ABILITY_4),
            gb("INVENTORY", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MELEE", "v", BindingRole.EXTRA_1),
            gb("PING ENEMY", "f", BindingRole.EXTRA_2),
            gb("HOLSTER", "3", BindingRole.EXTRA_3)
        )),
        GamePreset("r6", "Rainbow Six Siege", "Tactical Shooter", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / ADS", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("VAULT / RAPPEL", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("DEFUSE / USE", "f", BindingRole.INTERACT),
            gb("GADGET", "mouse_middle", BindingRole.ABILITY_1),
            gb("PRIMARY GADGET", "g", BindingRole.ABILITY_2),
            gb("SECONDARY GADGET", "4", BindingRole.ABILITY_3),
            gb("DRONE / CAM", "5", BindingRole.ABILITY_4),
            gb("SCOREBOARD", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PING", "z", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("LEAN LEFT", "q", BindingRole.EXTRA_1),
            gb("LEAN RIGHT", "e", BindingRole.EXTRA_2),
            gb("MELEE", "v", BindingRole.EXTRA_3)
        )),
        GamePreset("pubg", "PUBG: Battlegrounds", "Battle Royale", listOf(
            gb("FIRE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / SCOPE", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP / VAULT", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("LOOT / OPEN", "f", BindingRole.INTERACT),
            gb("LEAN LEFT", "q", BindingRole.ABILITY_1),
            gb("LEAN RIGHT", "e", BindingRole.ABILITY_2),
            gb("GRENADE", "5", BindingRole.ABILITY_3),
            gb("FIRST AID", "7", BindingRole.ABILITY_4),
            gb("INVENTORY", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PRONE", "z", BindingRole.EXTRA_1),
            gb("FIRE MODE", "b", BindingRole.EXTRA_2),
            gb("ENERGY DRINK", "8", BindingRole.EXTRA_3)
        )),
        GamePreset("arc_raiders", "ARC Raiders", "Shooter", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / ADS", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SLIDE / DUCK", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("EXTRACT / USE", "e", BindingRole.INTERACT),
            gb("GADGET 1", "q", BindingRole.ABILITY_1),
            gb("GADGET 2", "z", BindingRole.ABILITY_2),
            gb("GRENADE", "g", BindingRole.ABILITY_3),
            gb("HEAL PACK", "4", BindingRole.ABILITY_4),
            gb("INVENTORY", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PING", "f", BindingRole.EXTRA_1),
            gb("MELEE", "v", BindingRole.EXTRA_2)
        )),
        GamePreset("rust", "Rust", "Survival PvP", listOf(
            gb("ATTACK / FIRE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / BLOCK", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("DUCK", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("USE / OPEN", "e", BindingRole.INTERACT),
            gb("HOTBAR 1", "1", BindingRole.ABILITY_1),
            gb("HOTBAR 2", "2", BindingRole.ABILITY_2),
            gb("HOTBAR 3", "3", BindingRole.ABILITY_3),
            gb("HOTBAR 4", "4", BindingRole.ABILITY_4),
            gb("INVENTORY", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "g", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("CRAFT", "q", BindingRole.EXTRA_1),
            gb("VOICE CHAT", "v", BindingRole.EXTRA_2)
        )),

        // 3. Action & Souls-Like
        GamePreset("elden_ring", "Elden Ring", "Action / Souls-like", listOf(
            gb("LIGHT ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("GUARD / PARRY", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("ROLL / DODGE", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "x", BindingRole.CROUCH),
            gb("FLASK / ITEM", "r", BindingRole.RELOAD),
            gb("INTERACT", "e", BindingRole.INTERACT),
            gb("JUMP", "f", BindingRole.ABILITY_1),
            gb("ASH OF WAR", "q", BindingRole.ABILITY_2),
            gb("LOCK-ON", "mouse_middle", BindingRole.ABILITY_3),
            gb("HEAVY ATTACK", "g", BindingRole.ABILITY_4),
            gb("MAP", "m", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MENU", "esc", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("SPELL CYCLE", "up", BindingRole.EXTRA_1),
            gb("ITEM CYCLE", "down", BindingRole.EXTRA_2),
            gb("TWO-HAND", "y", BindingRole.EXTRA_3)
        )),
        GamePreset("gow", "God of War Ragnarök", "Action", listOf(
            gb("LIGHT ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM AXE / GUARD", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DODGE / ROLL", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("BLOCK", "q", BindingRole.CROUCH),
            gb("SPARTAN RAGE", "r", BindingRole.RELOAD),
            gb("INTERACT", "e", BindingRole.INTERACT),
            gb("HEAVY ATTACK", "f", BindingRole.ABILITY_1),
            gb("RECALL AXE", "1", BindingRole.ABILITY_2),
            gb("BLADES", "2", BindingRole.ABILITY_3),
            gb("ATREUS ARROW", "f", BindingRole.ABILITY_4),
            gb("JOURNAL / MAP", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MENU", "esc", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("LOCK-ON", "mouse_middle", BindingRole.EXTRA_1),
            gb("QUICK TURN", "x", BindingRole.EXTRA_2)
        )),
        GamePreset("nier", "Nier: Automata", "Action", listOf(
            gb("LIGHT ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("HEAVY ATTACK", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("EVADE / DASH", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("POD FIRE", "ctrl", BindingRole.CROUCH),
            gb("POD PROGRAM", "1", BindingRole.RELOAD),
            gb("INTERACT", "e", BindingRole.INTERACT),
            gb("LOCK-ON", "q", BindingRole.ABILITY_1),
            gb("SELF-DESTRUCT", "b", BindingRole.ABILITY_2),
            gb("HEAL ITEM", "f", BindingRole.ABILITY_3),
            gb("MENU", "esc", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),
        GamePreset("hades", "Hades / Hades II", "Action Roguelike", listOf(
            gb("ATTACK", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("SPECIAL", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DASH", "space", BindingRole.JUMP),
            gb("CAST", "q", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CALL / HEX", "f", BindingRole.CROUCH),
            gb("TALK / USE", "e", BindingRole.RELOAD),
            gb("GIFT", "g", BindingRole.INTERACT),
            gb("CODEX", "c", BindingRole.ABILITY_1),
            gb("BOONS", "b", BindingRole.ABILITY_2),
            gb("PAUSE", "esc", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),
        GamePreset("hollow_knight", "Hollow Knight: Silksong", "Metroidvania", listOf(
            gb("SLASH / ATTACK", "x", BindingRole.PRIMARY_TRIGGER),
            gb("CAST / HEAL", "a", BindingRole.SECONDARY_TRIGGER),
            gb("JUMP", "z", BindingRole.JUMP),
            gb("DASH", "c", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SUPER DASH", "s", BindingRole.CROUCH),
            gb("DREAM NAIL", "d", BindingRole.RELOAD),
            gb("INVENTORY", "i", BindingRole.INTERACT),
            gb("LOOK UP", "up", BindingRole.ABILITY_1),
            gb("LOOK DOWN", "down", BindingRole.ABILITY_2),
            gb("MAP", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PAUSE", "esc", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),

        // 4. Story & Survival Horror
        GamePreset("tlou", "The Last of Us Part I / II", "Story & Horror", listOf(
            gb("SHOOT / STRIKE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM WEAPON", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DODGE / CLIMB", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH / PRONE", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("INTERACT / GRAB", "e", BindingRole.INTERACT),
            gb("LISTEN MODE", "q", BindingRole.ABILITY_1),
            gb("HEALTH KIT", "4", BindingRole.ABILITY_2),
            gb("BRICK / BOTTLE", "g", BindingRole.ABILITY_3),
            gb("FLASHLIGHT", "f", BindingRole.ABILITY_4),
            gb("BACKPACK", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MELEE", "v", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PRIMARY", "1", BindingRole.EXTRA_1),
            gb("SECONDARY", "2", BindingRole.EXTRA_2)
        )),
        GamePreset("alan_wake2", "Alan Wake 2", "Story & Horror", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / FOCUS", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DODGE", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("INTERACT", "f", BindingRole.INTERACT),
            gb("BOOST LIGHT", "mouse_middle", BindingRole.ABILITY_1),
            gb("HEAL", "4", BindingRole.ABILITY_2),
            gb("FLARE", "g", BindingRole.ABILITY_3),
            gb("MIND PLACE", "tab", BindingRole.ABILITY_4),
            gb("INVENTORY", "i", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("SWITCH GUN", "q", BindingRole.EXTRA_1)
        )),
        GamePreset("stalker2", "S.T.A.L.K.E.R. 2", "Story & Horror", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / ADS", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("LOOT / USE", "f", BindingRole.INTERACT),
            gb("DETECTOR", "o", BindingRole.ABILITY_1),
            gb("BOLT", "6", BindingRole.ABILITY_2),
            gb("MEDKIT", "x", BindingRole.ABILITY_3),
            gb("BANDAGE", "c", BindingRole.ABILITY_4),
            gb("PDA", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("TORCH", "l", BindingRole.EXTRA_1),
            gb("NIGHT VISION", "n", BindingRole.EXTRA_2),
            gb("WEAPON 1", "1", BindingRole.EXTRA_3)
        )),
        GamePreset("bioshock", "Bioshock", "Story & Horror", listOf(
            gb("FIRE WEAPON", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("FIRE PLASMID", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "c", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("USE / LOOT", "e", BindingRole.INTERACT),
            gb("FIRST AID", "f", BindingRole.ABILITY_1),
            gb("EVE HYPO", "v", BindingRole.ABILITY_2),
            gb("NEXT GUN", "1", BindingRole.ABILITY_3),
            gb("NEXT PLASMID", "q", BindingRole.ABILITY_4),
            gb("RADIAL MENU", "shift", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),
        GamePreset("halflife2", "Half-Life 2 / Portal 2", "Story & Horror", listOf(
            gb("FIRE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("ALT FIRE", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("DUCK", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("USE", "e", BindingRole.INTERACT),
            gb("FLASHLIGHT", "f", BindingRole.ABILITY_1),
            gb("GRAVITY GUN", "1", BindingRole.ABILITY_2),
            gb("WEAPON 2", "2", BindingRole.ABILITY_3),
            gb("WEAPON 3", "3", BindingRole.ABILITY_4),
            gb("SUIT ZOOM", "z", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PAUSE", "esc", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),
        GamePreset("life_is_strange", "Life is Strange: Reunion", "Narrative", listOf(
            gb("LOOK / CHOOSE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("REWIND / POWER", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("PAUSE", "esc", BindingRole.JUMP),
            gb("JOG / SPRINT", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("BACK", "backspace", BindingRole.CROUCH),
            gb("SPECIAL", "q", BindingRole.RELOAD),
            gb("INTERACT", "e", BindingRole.INTERACT),
            gb("JOURNAL", "tab", BindingRole.ABILITY_1),
            gb("PHONE / SMS", "c", BindingRole.ABILITY_2),
            gb("PHOTO", "p", BindingRole.ABILITY_3)
        )),
        GamePreset("edith_finch", "What Remains of Edith Finch", "Narrative", listOf(
            gb("INTERACT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("ZOOM", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("CONTINUE", "space", BindingRole.JUMP),
            gb("WALK FASTER", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "ctrl", BindingRole.CROUCH),
            gb("JOURNAL", "tab", BindingRole.RELOAD),
            gb("EXAMINE", "e", BindingRole.INTERACT)
        )),
        GamePreset("detroit", "Detroit: Become Human", "Narrative", listOf(
            gb("ACTION / QTE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("CANCEL / QTE", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("MIND PALACE", "mouse_middle", BindingRole.JUMP),
            gb("WALK FASTER", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("INTERACT", "e", BindingRole.CROUCH),
            gb("QTE 3", "q", BindingRole.RELOAD),
            gb("LOOK", "f", BindingRole.INTERACT),
            gb("PAUSE", "esc", BindingRole.ABILITY_1),
            gb("FLOWCHART", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),

        // 5. RPG & Bethesda Classics
        GamePreset("bg3", "Baldur's Gate 3", "RPG & Strategy", listOf(
            gb("SELECT / MOVE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("CANCEL / MENU", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("END TURN / JUMP", "space", BindingRole.JUMP),
            gb("HIGHLIGHT LOOT", "alt", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("STEALTH", "c", BindingRole.CROUCH),
            gb("SHORT REST", "r", BindingRole.RELOAD),
            gb("INTERACT / TALK", "e", BindingRole.INTERACT),
            gb("ACTION 1", "1", BindingRole.ABILITY_1),
            gb("ACTION 2", "2", BindingRole.ABILITY_2),
            gb("ACTION 3", "3", BindingRole.ABILITY_3),
            gb("ACTION 4", "4", BindingRole.ABILITY_4),
            gb("PARTY SHEET", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("CAM ROT L", "q", BindingRole.EXTRA_1),
            gb("CAM ROT R", "e", BindingRole.EXTRA_2),
            gb("JOURNAL", "j", BindingRole.EXTRA_3)
        )),
        GamePreset("skyrim", "Skyrim", "RPG", listOf(
            gb("RIGHT HAND", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("LEFT HAND", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("SPRINT", "alt", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SNEAK", "ctrl", BindingRole.CROUCH),
            gb("SHOUT / POWER", "z", BindingRole.RELOAD),
            gb("ACTIVATE", "e", BindingRole.INTERACT),
            gb("READY WEAPON", "r", BindingRole.ABILITY_1),
            gb("PERSPECTIVE", "f", BindingRole.ABILITY_2),
            gb("FAVORITES", "q", BindingRole.ABILITY_3),
            gb("WAIT", "t", BindingRole.ABILITY_4),
            gb("TWEEN MENU", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("INVENTORY", "i", BindingRole.EXTRA_1),
            gb("MAGIC", "p", BindingRole.EXTRA_2)
        )),
        GamePreset("fallout_nv", "Fallout: New Vegas", "RPG", listOf(
            gb("ATTACK / FIRE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / BLOCK", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP", "space", BindingRole.JUMP),
            gb("RUN / WALK", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SNEAK", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("ACTIVATE", "e", BindingRole.INTERACT),
            gb("V.A.T.S.", "v", BindingRole.ABILITY_1),
            gb("PIP-BOY", "tab", BindingRole.ABILITY_2),
            gb("HOLSTER GUN", "r", BindingRole.ABILITY_3),
            gb("AMMO SWAP", "2", BindingRole.ABILITY_4),
            gb("ITEMS", "f1", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "f3", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("STATS", "f2", BindingRole.EXTRA_1)
        )),
        GamePreset("mass_effect", "Mass Effect (Trilogy)", "RPG & Sci-Fi", listOf(
            gb("SHOOT", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("AIM / ADS", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("STORM / COVER", "space", BindingRole.JUMP),
            gb("COMBAT ROLL", "space", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CROUCH", "ctrl", BindingRole.CROUCH),
            gb("RELOAD", "r", BindingRole.RELOAD),
            gb("USE / INTERACT", "e", BindingRole.INTERACT),
            gb("POWER 1", "1", BindingRole.ABILITY_1),
            gb("POWER 2", "2", BindingRole.ABILITY_2),
            gb("POWER 3", "3", BindingRole.ABILITY_3),
            gb("MELEE", "f", BindingRole.ABILITY_4),
            gb("POWER WHEEL", "shift", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("WEAPON WHEEL", "tab", BindingRole.EXTRA_1),
            gb("MEDIGEL", "v", BindingRole.EXTRA_2)
        )),
        GamePreset("poe2", "Path of Exile 2", "ARPG", listOf(
            gb("SKILL 1 / MOVE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("SKILL 2", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("DODGE ROLL", "space", BindingRole.JUMP),
            gb("SKILL 3", "q", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SKILL 4", "w", BindingRole.CROUCH),
            gb("SKILL 5", "e", BindingRole.RELOAD),
            gb("INTERACT", "f", BindingRole.INTERACT),
            gb("FLASK 1", "1", BindingRole.ABILITY_1),
            gb("FLASK 2", "2", BindingRole.ABILITY_2),
            gb("FLASK 3", "3", BindingRole.ABILITY_3),
            gb("FLASK 4", "4", BindingRole.ABILITY_4),
            gb("INVENTORY", "i", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("MAP", "tab", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("PASSIVE TREE", "p", BindingRole.EXTRA_1)
        )),
        GamePreset("disco_elysium", "Disco Elysium", "CRPG", listOf(
            gb("WALK / CHOOSE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("CANCEL", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("HIGHLIGHT ALL", "tab", BindingRole.JUMP),
            gb("RUN / JOG", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("JOURNAL", "j", BindingRole.CROUCH),
            gb("INVENTORY", "i", BindingRole.RELOAD),
            gb("THOUGHT CAB", "t", BindingRole.INTERACT),
            gb("CHARACTER", "c", BindingRole.ABILITY_1),
            gb("HEALTH", "1", BindingRole.ABILITY_2),
            gb("MORALE", "2", BindingRole.ABILITY_3),
            gb("MAP", "m", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),
        GamePreset("dota2", "Dota 2", "MOBA", listOf(
            gb("SELECT / MOVE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("ATTACK / ACTION", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("STOP / HOLD", "s", BindingRole.JUMP),
            gb("ATTACK MOVE", "a", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("CENTER HERO", "space", BindingRole.CROUCH),
            gb("ABILITY 1", "q", BindingRole.RELOAD),
            gb("ABILITY 2", "w", BindingRole.INTERACT),
            gb("ABILITY 3", "e", BindingRole.ABILITY_1),
            gb("ULTIMATE", "r", BindingRole.ABILITY_2),
            gb("ITEM 1", "z", BindingRole.ABILITY_3),
            gb("ITEM 2", "x", BindingRole.ABILITY_4),
            gb("SCOREBOARD", "tab", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("CHAT WHEEL", "y", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT)
        )),
        GamePreset("minecraft", "Minecraft", "Sandbox", listOf(
            gb("ATTACK / MINE", "mouse_left", BindingRole.PRIMARY_TRIGGER, swipeToAim = true),
            gb("USE / PLACE", "mouse_right", BindingRole.SECONDARY_TRIGGER, swipeToAim = true),
            gb("JUMP / SWIM", "space", BindingRole.JUMP),
            gb("SNEAK", "shift", BindingRole.SPRINT, shape = ButtonShape.ROUNDED_RECT),
            gb("SPRINT", "ctrl", BindingRole.CROUCH),
            gb("OFFHAND", "f", BindingRole.RELOAD),
            gb("INVENTORY", "e", BindingRole.INTERACT),
            gb("DROP ITEM", "q", BindingRole.ABILITY_1),
            gb("HOTBAR 1", "1", BindingRole.ABILITY_2),
            gb("HOTBAR 2", "2", BindingRole.ABILITY_3),
            gb("HOTBAR 3", "3", BindingRole.ABILITY_4),
            gb("PERSPECTIVE", "f5", BindingRole.WHEEL_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("CHAT", "t", BindingRole.MAP_MENU, shape = ButtonShape.ROUNDED_RECT),
            gb("HOTBAR 4", "4", BindingRole.EXTRA_1),
            gb("HOTBAR 5", "5", BindingRole.EXTRA_2),
            gb("COORDINATES", "f3", BindingRole.EXTRA_3)
        ))
    )

    private fun getCoordsForRole(role: BindingRole, claw: ClawStyle): CoordsResult {
        return when (claw) {
            ClawStyle.TWO_FINGER -> when (role) {
                BindingRole.PRIMARY_TRIGGER -> CoordsResult(0.75f, 0.28f, 1.55f, ButtonShape.CIRCLE, true)
                BindingRole.SECONDARY_TRIGGER -> CoordsResult(0.88f, 0.22f, 1.35f, ButtonShape.CIRCLE, true)
                BindingRole.JUMP -> CoordsResult(0.86f, 0.72f, 1.25f, ButtonShape.CIRCLE, false)
                BindingRole.SPRINT -> CoordsResult(0.12f, 0.35f, 1.15f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.CROUCH -> CoordsResult(0.28f, 0.78f, 1.10f, ButtonShape.CIRCLE, false)
                BindingRole.RELOAD -> CoordsResult(0.75f, 0.60f, 1.05f, ButtonShape.CIRCLE, false)
                BindingRole.INTERACT -> CoordsResult(0.86f, 0.50f, 1.05f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_1 -> CoordsResult(0.94f, 0.60f, 1.00f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_2 -> CoordsResult(0.73f, 0.46f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_3 -> CoordsResult(0.73f, 0.76f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_4 -> CoordsResult(0.84f, 0.88f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.WHEEL_MENU -> CoordsResult(0.08f, 0.12f, 1.00f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.MAP_MENU -> CoordsResult(0.22f, 0.12f, 0.95f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.EXTRA_1 -> CoordsResult(0.94f, 0.88f, 0.90f, ButtonShape.CIRCLE, false)
                BindingRole.EXTRA_2 -> CoordsResult(0.62f, 0.12f, 0.90f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.EXTRA_3 -> CoordsResult(0.73f, 0.88f, 0.90f, ButtonShape.CIRCLE, false)
            }
            ClawStyle.THREE_FINGER -> when (role) {
                BindingRole.PRIMARY_TRIGGER -> CoordsResult(0.76f, 0.11f, 1.60f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.JUMP -> CoordsResult(0.90f, 0.11f, 1.35f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.SECONDARY_TRIGGER -> CoordsResult(0.85f, 0.32f, 1.40f, ButtonShape.CIRCLE, true)
                BindingRole.SPRINT -> CoordsResult(0.12f, 0.35f, 1.15f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.CROUCH -> CoordsResult(0.28f, 0.78f, 1.10f, ButtonShape.CIRCLE, false)
                BindingRole.INTERACT -> CoordsResult(0.74f, 0.44f, 1.05f, ButtonShape.CIRCLE, false)
                BindingRole.RELOAD -> CoordsResult(0.74f, 0.64f, 1.05f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_1 -> CoordsResult(0.86f, 0.52f, 1.00f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_2 -> CoordsResult(0.74f, 0.82f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_3 -> CoordsResult(0.86f, 0.70f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_4 -> CoordsResult(0.86f, 0.88f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.WHEEL_MENU -> CoordsResult(0.08f, 0.12f, 1.00f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.MAP_MENU -> CoordsResult(0.22f, 0.12f, 0.95f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.EXTRA_1 -> CoordsResult(0.95f, 0.70f, 0.90f, ButtonShape.CIRCLE, false)
                BindingRole.EXTRA_2 -> CoordsResult(0.62f, 0.12f, 0.90f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.EXTRA_3 -> CoordsResult(0.95f, 0.88f, 0.90f, ButtonShape.CIRCLE, false)
            }
            ClawStyle.FOUR_FINGER -> when (role) {
                BindingRole.SPRINT -> CoordsResult(0.10f, 0.11f, 1.30f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.CROUCH -> CoordsResult(0.24f, 0.11f, 1.25f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.INTERACT -> CoordsResult(0.37f, 0.11f, 1.15f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.PRIMARY_TRIGGER -> CoordsResult(0.75f, 0.11f, 1.65f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.SECONDARY_TRIGGER -> CoordsResult(0.90f, 0.11f, 1.35f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.JUMP -> CoordsResult(0.92f, 0.35f, 1.25f, ButtonShape.CIRCLE, false)
                BindingRole.RELOAD -> CoordsResult(0.77f, 0.35f, 1.10f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_1 -> CoordsResult(0.77f, 0.53f, 1.05f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_2 -> CoordsResult(0.91f, 0.53f, 1.05f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_3 -> CoordsResult(0.77f, 0.70f, 1.00f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_4 -> CoordsResult(0.91f, 0.70f, 1.00f, ButtonShape.CIRCLE, false)
                BindingRole.WHEEL_MENU -> CoordsResult(0.08f, 0.35f, 1.00f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.MAP_MENU -> CoordsResult(0.08f, 0.50f, 0.95f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.EXTRA_1 -> CoordsResult(0.77f, 0.87f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.EXTRA_2 -> CoordsResult(0.91f, 0.87f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.EXTRA_3 -> CoordsResult(0.63f, 0.87f, 0.90f, ButtonShape.CIRCLE, false)
            }
            ClawStyle.CONTROLLER -> when (role) {
                BindingRole.PRIMARY_TRIGGER -> CoordsResult(0.77f, 0.11f, 1.0f, ButtonShape.ROUNDED_RECT, true)
                BindingRole.SECONDARY_TRIGGER -> CoordsResult(0.23f, 0.11f, 1.0f, ButtonShape.ROUNDED_RECT, true)
                BindingRole.ABILITY_1 -> CoordsResult(0.10f, 0.11f, 1.0f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.ABILITY_2 -> CoordsResult(0.90f, 0.11f, 1.0f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.JUMP -> CoordsResult(0.805f, 0.60f, 1.0f, ButtonShape.CIRCLE, false)
                BindingRole.SPRINT -> CoordsResult(0.87f, 0.71f, 1.0f, ButtonShape.CIRCLE, false)
                BindingRole.INTERACT -> CoordsResult(0.87f, 0.49f, 1.0f, ButtonShape.CIRCLE, false)
                BindingRole.RELOAD -> CoordsResult(0.935f, 0.60f, 1.0f, ButtonShape.CIRCLE, false)
                BindingRole.CROUCH -> CoordsResult(0.13f, 0.88f, 1.0f, ButtonShape.CIRCLE, false)
                BindingRole.ABILITY_3 -> CoordsResult(0.87f, 0.88f, 1.0f, ButtonShape.CIRCLE, false)
                BindingRole.WHEEL_MENU -> CoordsResult(0.32f, 0.48f, 1.0f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.MAP_MENU -> CoordsResult(0.55f, 0.93f, 1.0f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.ABILITY_4 -> CoordsResult(0.73f, 0.49f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.EXTRA_1 -> CoordsResult(0.73f, 0.71f, 0.95f, ButtonShape.CIRCLE, false)
                BindingRole.EXTRA_2 -> CoordsResult(0.64f, 0.11f, 0.95f, ButtonShape.ROUNDED_RECT, false)
                BindingRole.EXTRA_3 -> CoordsResult(0.36f, 0.11f, 0.95f, ButtonShape.ROUNDED_RECT, false)
            }
        }
    }

    private data class CoordsResult(
        val x: Float,
        val y: Float,
        val scale: Float,
        val shape: ButtonShape,
        val swipe: Boolean
    )

    private data class ControllerBtnInfo(val id: String, val label: String, val isCustom: Boolean, val customSlot: Int)

    fun generateGameLayout(preset: GamePreset, claw: ClawStyle): List<HudElement> {
        val list = mutableListOf<HudElement>()
        var z = 1

        if (claw == ClawStyle.CONTROLLER) {
            // 1. Left Movement Stick & Circular D-Pad (matching reference layout)
            list.add(
                HudElement(
                    id = "leftstick",
                    label = "STICK",
                    key = "",
                    type = ElementType.STICK,
                    xPct = 0.13f,
                    yPct = 0.62f,
                    scale = 1.0f,
                    zOrder = z++
                )
            )

            val upK = preset.bindings.firstOrNull { it.key == "up" || it.key == "1" || it.role == BindingRole.EXTRA_1 }?.key ?: "h"
            val downK = preset.bindings.firstOrNull { it.key == "down" || it.key == "2" || it.role == BindingRole.EXTRA_2 }?.key ?: "t"
            val leftK = preset.bindings.firstOrNull { it.key == "left" || it.key == "3" || it.role == BindingRole.EXTRA_3 }?.key ?: "x"
            val rightK = preset.bindings.firstOrNull { it.key == "right" || it.key == "4" }?.key ?: "x"

            list.add(
                HudElement(
                    id = "dpad",
                    label = "D-PAD",
                    key = "",
                    type = ElementType.DPAD,
                    xPct = 0.32f,
                    yPct = 0.72f,
                    scale = 1.0f,
                    zOrder = z++,
                    dpadUpKey = upK,
                    dpadDownKey = downK,
                    dpadLeftKey = leftK,
                    dpadRightKey = rightK
                )
            )

            // 2. Mouse Scroll Wheel Strip (Right Edge)
            list.add(
                HudElement(
                    id = "scroll_wheel",
                    label = "WHEEL",
                    key = "mouse_wheel",
                    type = ElementType.SCROLL_WHEEL,
                    xPct = 0.96f,
                    yPct = 0.45f,
                    scale = 1.0f,
                    zOrder = z++,
                    shape = ButtonShape.ROUNDED_RECT
                )
            )

            // 3. System ESC Button (Bottom Center Left)
            list.add(
                HudElement(
                    id = "small_icon",
                    label = "PAUSE",
                    key = "esc",
                    type = ElementType.BUTTON,
                    xPct = 0.45f,
                    yPct = 0.93f,
                    scale = 1.0f,
                    zOrder = z++,
                    shape = ButtonShape.ROUNDED_RECT
                )
            )

            // 4. Map game bindings to controller buttons and extra slots
            var customSlotIdx = 0
            for (b in preset.bindings) {
                val info = when (b.role) {
                    BindingRole.PRIMARY_TRIGGER -> ControllerBtnInfo("rt", b.action, false, -1)
                    BindingRole.SECONDARY_TRIGGER -> ControllerBtnInfo("lt", b.action, false, -1)
                    BindingRole.ABILITY_1 -> ControllerBtnInfo("lb", b.action, false, -1)
                    BindingRole.ABILITY_2 -> ControllerBtnInfo("rb", b.action, false, -1)
                    BindingRole.JUMP -> ControllerBtnInfo("x", b.action, false, -1)
                    BindingRole.SPRINT -> ControllerBtnInfo("a", b.action, false, -1)
                    BindingRole.INTERACT -> ControllerBtnInfo("y", b.action, false, -1)
                    BindingRole.RELOAD -> ControllerBtnInfo("b", b.action, false, -1)
                    BindingRole.CROUCH -> ControllerBtnInfo("lsb", b.action, false, -1)
                    BindingRole.ABILITY_3 -> ControllerBtnInfo("rsb", b.action, false, -1)
                    BindingRole.MAP_MENU -> ControllerBtnInfo("hamburger_icon", b.action, false, -1)
                    else -> {
                        val slot = customSlotIdx++
                        ControllerBtnInfo("custom_$slot", b.action, true, slot)
                    }
                }

                val res = getCoordsForRole(b.role, claw)
                val isRect = b.role in listOf(
                    BindingRole.PRIMARY_TRIGGER,
                    BindingRole.SECONDARY_TRIGGER,
                    BindingRole.ABILITY_1,
                    BindingRole.ABILITY_2,
                    BindingRole.MAP_MENU,
                    BindingRole.WHEEL_MENU,
                    BindingRole.EXTRA_2,
                    BindingRole.EXTRA_3
                )
                val finalShape = if (isRect) ButtonShape.ROUNDED_RECT else if (b.shape != ButtonShape.CIRCLE) b.shape else res.shape
                val finalSwipe = b.swipeToAim || res.swipe

                list.add(
                    HudElement(
                        id = info.id,
                        label = info.label,
                        key = b.key,
                        type = ElementType.BUTTON,
                        xPct = res.x,
                        yPct = res.y,
                        scale = res.scale,
                        isCustom = info.isCustom,
                        customSlot = info.customSlot,
                        zOrder = z++,
                        shape = finalShape,
                        swipeToAim = finalSwipe
                    )
                )
            }

            return list
        }

        // Left Stick (Floating / Dynamic Mode on left 50% for 2, 3, 4 finger claw)
        list.add(
            HudElement(
                id = "leftstick",
                label = "STICK",
                key = "",
                type = ElementType.STICK,
                xPct = 0.14f,
                yPct = if (claw == ClawStyle.FOUR_FINGER) 0.70f else 0.72f,
                scale = 1.15f,
                zOrder = z++
            )
        )

        // Mouse Scroll Wheel Strip (Right Edge)
        list.add(
            HudElement(
                id = "scroll_wheel",
                label = "WHEEL",
                key = "mouse_wheel",
                type = ElementType.SCROLL_WHEEL,
                xPct = 0.97f,
                yPct = 0.48f,
                scale = 1.0f,
                zOrder = z++,
                shape = ButtonShape.ROUNDED_RECT
            )
        )

        val stockIds = listOf(
            "lt", "rt", "lb", "rb", "a", "b", "x", "y",
            "lsb", "rsb", "small_icon", "hamburger_icon",
            "dpad_up", "dpad_down", "dpad_left", "dpad_right"
        )

        preset.bindings.forEachIndexed { index, b ->
            val isCustom = index >= stockIds.size
            val btnId = if (isCustom) "custom_${index - stockIds.size}" else stockIds[index]
            val customSlot = if (isCustom) index - stockIds.size else -1

            val res = getCoordsForRole(b.role, claw)
            val finalShape = if (b.shape != ButtonShape.CIRCLE) b.shape else res.shape
            val finalSwipe = b.swipeToAim || res.swipe

            list.add(
                HudElement(
                    id = btnId,
                    label = b.action,
                    key = b.key,
                    type = ElementType.BUTTON,
                    xPct = res.x,
                    yPct = res.y,
                    scale = res.scale,
                    isCustom = isCustom,
                    customSlot = customSlot,
                    zOrder = z++,
                    shape = finalShape,
                    swipeToAim = finalSwipe
                )
            )
        }

        return list
    }

    fun findGamePresetForProfile(profile: String): GamePreset? {
        val clean = profile.substringBefore(" (").trim().lowercase()
        return ALL_GAME_PRESETS.firstOrNull {
            it.name.equals(clean, ignoreCase = true) ||
            it.id.equals(clean, ignoreCase = true) ||
            profile.contains(it.name, ignoreCase = true) ||
            profile.contains(it.id, ignoreCase = true)
        }
    }

    fun getBaseElementsForProfile(profile: String): List<HudElement> {
        if (profile == "Default 2") return getDefault2Elements()
        if (profile == "Default") return getDefaultElements()

        val preset = findGamePresetForProfile(profile)
        if (preset != null) {
            val claw = when {
                profile.contains("Controller", ignoreCase = true) -> ClawStyle.CONTROLLER
                profile.contains("4-Finger", ignoreCase = true) -> ClawStyle.FOUR_FINGER
                profile.contains("3-Finger", ignoreCase = true) -> ClawStyle.THREE_FINGER
                else -> ClawStyle.TWO_FINGER
            }
            return generateGameLayout(preset, claw)
        }

        if (profile.equals("Racing", ignoreCase = true)) {
            val p = ALL_GAME_PRESETS.firstOrNull { it.id == "gta5" }
            if (p != null) return generateGameLayout(p, ClawStyle.TWO_FINGER)
        }
        if (profile.equals("RDR2", ignoreCase = true)) {
            val p = ALL_GAME_PRESETS.firstOrNull { it.id == "rdr2" }
            if (p != null) return generateGameLayout(p, ClawStyle.TWO_FINGER)
        }
        if (profile.equals("FPS", ignoreCase = true)) {
            val p = ALL_GAME_PRESETS.firstOrNull { it.id == "cs2_val" }
            if (p != null) return generateGameLayout(p, ClawStyle.FOUR_FINGER)
        }

        return getDefaultElements()
    }

    fun getProfiles(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultList = "Default,Default 2,RDR2,FPS,Racing"
        val str = prefs.getString(KEY_PROFILES_LIST, defaultList) ?: defaultList
        return str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun getActiveProfile(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_PROFILE, "Default") ?: "Default"
    }

    fun setActiveProfile(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_PROFILE, name).apply()
    }

    fun addProfile(context: Context, name: String) {
        val list = getProfiles(context).toMutableList()
        if (!list.contains(name)) {
            list.add(name)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PROFILES_LIST, list.joinToString(",")).apply()
        }
    }

    fun deleteProfile(context: Context, name: String): Boolean {
        val list = getProfiles(context).toMutableList()
        if (list.size <= 1 || name == "Default" || name == "Default 2") return false
        list.remove(name)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val active = getActiveProfile(context)
        val newActive = if (active == name) "Default" else active
        prefs.edit()
            .putString(KEY_PROFILES_LIST, list.joinToString(","))
            .putString(KEY_ACTIVE_PROFILE, newActive)
            .remove("hud_layout_json_$name")
            .apply()
        return true
    }

    fun loadLayout(context: Context, profile: String = getActiveProfile(context)): MutableList<HudElement> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("hud_layout_json_$profile", null)
            ?: if (profile == "Default") prefs.getString("hud_layout_json", null) else null
            ?: return getBaseElementsForProfile(profile).toMutableList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<HudElement>()
            for (i in 0 until array.length()) {
                list.add(HudElement.fromJson(array.getJSONObject(i)))
            }
            if (list.isEmpty()) {
                getBaseElementsForProfile(profile).toMutableList()
            } else {
                if (list.none { it.type == ElementType.SCROLL_WHEEL }) {
                    val maxZ = (list.maxOfOrNull { it.zOrder } ?: 20) + 1
                    list.add(
                        HudElement(
                            id = "scroll_wheel",
                            label = "WHEEL",
                            key = "mouse_wheel",
                            type = ElementType.SCROLL_WHEEL,
                            xPct = 0.97f,
                            yPct = 0.48f,
                            scale = 1.0f,
                            zOrder = maxZ,
                            shape = ButtonShape.ROUNDED_RECT
                        )
                    )
                }
                list
            }
        } catch (_: Exception) {
            getBaseElementsForProfile(profile).toMutableList()
        }
    }

    fun saveLayout(context: Context, elements: List<HudElement>, profile: String = getActiveProfile(context)) {
        val array = JSONArray()
        elements.forEach { array.put(it.toJson()) }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putString("hud_layout_json_$profile", array.toString())
        if (profile == "Default") {
            editor.putString("hud_layout_json", array.toString())
        }
        editor.apply()
    }

    fun resetLayout(context: Context, profile: String = getActiveProfile(context)): List<HudElement> {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove("hud_layout_json_$profile")
            .apply()
        return getBaseElementsForProfile(profile)
    }

    /**
     * Extracts full keymap dictionary for the PC server:
     * e.g. { "a": "shift", "lb": "tab", "dpad_up": "h", "dpad_down": "t", ... }
     */
    fun extractKeymap(elements: List<HudElement>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (el in elements) {
            if (el.type == ElementType.BUTTON && el.key.isNotEmpty()) {
                val targetKey = if (el.isCustom && el.customSlot >= 0) "custom_${el.customSlot}" else el.id
                map[targetKey] = el.key
            } else if (el.type == ElementType.DPAD) {
                map["dpad_up"] = el.dpadUpKey
                map["dpad_down"] = el.dpadDownKey
                map["dpad_left"] = el.dpadLeftKey
                map["dpad_right"] = el.dpadRightKey
            }
        }
        return map
    }

    // HUD Opacity
    fun getHudOpacity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_OPACITY, 1.0f)
    }

    fun setHudOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_OPACITY, opacity).apply()
    }

    // Button Fill Opacity (Can drop down to 0% for complete transparency)
    fun getButtonFillOpacity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_FILL_OPACITY, 0.70f)
    }

    fun setButtonFillOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_FILL_OPACITY, opacity.coerceIn(0f, 1f)).apply()
    }

    // Button Border / Outline Opacity (0% - 100%)
    fun getButtonBorderOpacity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_BORDER_OPACITY, 1.0f)
    }

    fun setButtonBorderOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_BORDER_OPACITY, opacity.coerceIn(0f, 1f)).apply()
    }

    // Button Text & Action Label Opacity (0% - 100%)
    fun getButtonTextOpacity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_TEXT_OPACITY, 1.0f)
    }

    fun setButtonTextOpacity(context: Context, opacity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_TEXT_OPACITY, opacity.coerceIn(0f, 1f)).apply()
    }

    // Gamepad Buttons Master Visibility
    private const val KEY_BUTTONS_VISIBLE = "buttons_visible"

    fun areButtonsVisible(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_BUTTONS_VISIBLE, true)
    }

    fun setButtonsVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_BUTTONS_VISIBLE, visible).apply()
    }

    // Steering Mode
    private const val KEY_STEERING_MODE = "steering_mode"

    fun getSteeringMode(context: Context): SteeringMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val raw = prefs.all[KEY_STEERING_MODE]
            when (raw) {
                is String -> try { SteeringMode.valueOf(raw) } catch (_: Exception) { SteeringMode.OFF }
                is Int -> SteeringMode.values().firstOrNull { it.id == raw } ?: SteeringMode.OFF
                is Number -> SteeringMode.values().firstOrNull { it.id == raw.toInt() } ?: SteeringMode.OFF
                else -> SteeringMode.OFF
            }
        } catch (_: Exception) {
            SteeringMode.OFF
        }
    }

    fun setSteeringMode(context: Context, mode: SteeringMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_STEERING_MODE, mode.name).apply()
    }

    fun ensureSteeringElements(list: List<HudElement>) {
        val mList = list as? MutableList<HudElement> ?: return
        if (mList.none { it.id == "steering_wheel" }) {
            mList.add(HudElement("steering_wheel", "STEER", "", ElementType.STEERING_WHEEL, 0.16f, 0.65f, 1.2f, shape = ButtonShape.CIRCLE))
        }
        if (mList.none { it.id == "heli_stick" }) {
            mList.add(HudElement("heli_stick", "HELI", "", ElementType.STICK, 0.84f, 0.65f, 1.0f, shape = ButtonShape.CIRCLE, dpadUpKey = "8", dpadDownKey = "5", dpadLeftKey = "4", dpadRightKey = "6"))
        }
    }

    // Pro Esports Touch Aim Engine
    private const val KEY_ESPORTS_ENGINE_ENABLED = "aim_esports_engine_enabled"
    private const val KEY_ULTRA_POLLING_ENABLED = "aim_ultra_polling_enabled"
    private const val KEY_DPI_NORM_ENABLED = "aim_dpi_norm_enabled"
    private const val KEY_JITTER_FILTER_ENABLED = "aim_jitter_filter_enabled"
    private const val KEY_JITTER_THRESHOLD = "aim_jitter_threshold"
    private const val KEY_AIM_CURVE_MODE = "aim_curve_mode"
    private const val KEY_SCURVE_DAMPENING = "aim_scurve_dampening"
    private const val KEY_SCURVE_FLICK_BOOST = "aim_scurve_flick_boost"

    fun isUltraPollingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ULTRA_POLLING_ENABLED, true)
    }

    fun setUltraPollingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ULTRA_POLLING_ENABLED, enabled).apply()
    }

    fun isEsportsAimEngineEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ESPORTS_ENGINE_ENABLED, true)
    }

    fun setEsportsAimEngineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_ESPORTS_ENGINE_ENABLED, enabled).apply()
    }

    fun isDpiNormalizationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_DPI_NORM_ENABLED, true)
    }

    fun setDpiNormalizationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_DPI_NORM_ENABLED, enabled).apply()
    }

    fun isJitterFilterEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_JITTER_FILTER_ENABLED, true)
    }

    fun setJitterFilterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_JITTER_FILTER_ENABLED, enabled).apply()
    }

    fun getJitterFilterThreshold(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_JITTER_THRESHOLD, 0.15f)
    }

    fun setJitterFilterThreshold(context: Context, threshold: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_JITTER_THRESHOLD, threshold).apply()
    }

    fun getAimCurveMode(context: Context): AimCurveMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            val raw = prefs.all[KEY_AIM_CURVE_MODE]
            when (raw) {
                is String -> try { AimCurveMode.valueOf(raw) } catch (_: Exception) { AimCurveMode.LINEAR }
                is Int -> AimCurveMode.values().getOrNull(raw) ?: AimCurveMode.LINEAR
                is Number -> AimCurveMode.values().getOrNull(raw.toInt()) ?: AimCurveMode.LINEAR
                else -> AimCurveMode.LINEAR
            }
        } catch (_: Exception) {
            AimCurveMode.LINEAR
        }
    }

    fun setAimCurveMode(context: Context, mode: AimCurveMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_AIM_CURVE_MODE, mode.name).apply()
    }

    fun getSCurveDampening(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_SCURVE_DAMPENING, 0.75f)
    }

    fun setSCurveDampening(context: Context, dampening: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SCURVE_DAMPENING, dampening).apply()
    }

    fun getSCurveFlickBoost(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_SCURVE_FLICK_BOOST, 1.35f)
    }

    fun setSCurveFlickBoost(context: Context, boost: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_SCURVE_FLICK_BOOST, boost).apply()
    }

    // Gyroscope
    private const val KEY_GYRO_INVERT_X = "gyro_invert_x"
    private const val KEY_GYRO_INVERT_Y = "gyro_invert_y"
    private const val KEY_GYRO_SMOOTHING = "gyro_smoothing"
    private const val KEY_HAPTIC_ENABLED = "haptic_enabled"
    private const val KEY_HAPTIC_INTENSITY = "haptic_intensity"

    fun isGyroEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_GYRO_ENABLED, false)
    }

    fun setGyroEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_GYRO_ENABLED, enabled).apply()
    }

    private const val KEY_GYRO_SENS_X = "gyro_sens_x"
    private const val KEY_GYRO_SENS_Y = "gyro_sens_y"

    fun getGyroSensitivity(context: Context): Float {
        return getGyroSensX(context)
    }

    fun setGyroSensitivity(context: Context, sens: Float) {
        setGyroSensX(context, sens)
        setGyroSensY(context, sens)
    }

    fun getGyroSensX(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_GYRO_SENS_X, prefs.getFloat(KEY_GYRO_SENSITIVITY, 1.5f))
    }

    fun setGyroSensX(context: Context, sens: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_GYRO_SENS_X, sens).apply()
    }

    fun getGyroSensY(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_GYRO_SENS_Y, prefs.getFloat(KEY_GYRO_SENSITIVITY, 1.0f))
    }

    fun setGyroSensY(context: Context, sens: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_GYRO_SENS_Y, sens).apply()
    }

    // Scoped / ADS (Holding RMB) Gyro Sensitivity
    private const val KEY_GYRO_SCOPED_SENS_ENABLED = "gyro_scoped_sens_enabled"
    private const val KEY_GYRO_SCOPED_SENS = "gyro_scoped_sens"

    fun isGyroScopedSensEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_GYRO_SCOPED_SENS_ENABLED, true)
    }

    fun setGyroScopedSensEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_GYRO_SCOPED_SENS_ENABLED, enabled).apply()
    }

    fun getGyroScopedSens(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_GYRO_SCOPED_SENS, 0.75f)
    }

    fun setGyroScopedSens(context: Context, sens: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_GYRO_SCOPED_SENS, sens).apply()
    }

    fun isGyroAimOnly(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_GYRO_AIM_ONLY, false)
    }

    fun setGyroAimOnly(context: Context, aimOnly: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_GYRO_AIM_ONLY, aimOnly).apply()
    }

    fun isGyroInvertX(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_GYRO_INVERT_X, false)
    }

    fun setGyroInvertX(context: Context, invert: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_GYRO_INVERT_X, invert).apply()
    }

    fun isGyroInvertY(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_GYRO_INVERT_Y, false)
    }

    fun setGyroInvertY(context: Context, invert: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_GYRO_INVERT_Y, invert).apply()
    }

    fun getGyroSmoothing(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_GYRO_SMOOTHING, 0.70f)
    }

    fun setGyroSmoothing(context: Context, smoothing: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_GYRO_SMOOTHING, smoothing).apply()
    }

    // High-End Haptics
    fun isHapticEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_HAPTIC_ENABLED, true)
    }

    fun setHapticEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_HAPTIC_ENABLED, enabled).apply()
    }

    fun getHapticIntensity(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_HAPTIC_INTENSITY, 0.80f)
    }

    fun setHapticIntensity(context: Context, intensity: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_HAPTIC_INTENSITY, intensity).apply()
    }

    // Stick Sprint Mode
    private const val KEY_STICK_SPRINT_MODE = "stick_sprint_mode"

    fun isStickSprintMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_STICK_SPRINT_MODE, true)
    }

    fun setStickSprintMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_STICK_SPRINT_MODE, enabled).apply()
    }

    // Stick Floating / Dynamic Mode
    private const val KEY_STICK_FLOATING_MODE = "stick_floating_mode"

    fun isStickFloatingMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_STICK_FLOATING_MODE, false)
    }

    fun setStickFloatingMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_STICK_FLOATING_MODE, enabled).apply()
    }

    // Stick Touch Detection Scale
    private const val KEY_STICK_TOUCH_SCALE = "stick_touch_scale"

    fun getStickTouchScale(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getFloat(KEY_STICK_TOUCH_SCALE, 1.8f)
    }

    fun setStickTouchScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putFloat(KEY_STICK_TOUCH_SCALE, scale).apply()
    }

    // Gyro Axis Modes
    const val GYRO_AXIS_FULL = 0
    const val GYRO_AXIS_HORIZONTAL_ONLY = 1
    const val GYRO_AXIS_VERTICAL_ONLY = 2
    private const val KEY_GYRO_AXIS_MODE = "gyro_axis_mode"

    fun getGyroAxisMode(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_GYRO_AXIS_MODE, GYRO_AXIS_FULL)
    }

    fun setGyroAxisMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_GYRO_AXIS_MODE, mode).apply()
    }

    // Touch Optimization / Multi-Touch Booster
    private const val KEY_TOUCH_OPTIMIZATION_ENABLED = "touch_optimization_enabled"

    fun isTouchOptimizationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_TOUCH_OPTIMIZATION_ENABLED, true)
    }

    fun setTouchOptimizationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_TOUCH_OPTIMIZATION_ENABLED, enabled).apply()
    }

    /**
     * Serializes HUD elements to a shareable JSON string.
     */
    fun exportLayoutJson(elements: List<HudElement>): String {
        val array = JSONArray()
        elements.forEach { array.put(it.toJson()) }
        return array.toString(2)
    }

    /**
     * Deserializes and validates a HUD layout JSON string.
     */
    fun importLayoutJson(jsonStr: String): List<HudElement>? {
        return try {
            val array = JSONArray(jsonStr.trim())
            if (array.length() == 0) return null
            val list = mutableListOf<HudElement>()
            for (i in 0 until array.length()) {
                list.add(HudElement.fromJson(array.getJSONObject(i)))
            }
            if (list.isNotEmpty()) list else null
        } catch (_: Exception) {
            null
        }
    }
}
