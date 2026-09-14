package com.virtualpad.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ElementType {
    BUTTON,
    STICK,
    DPAD,
    SCROLL_WHEEL
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
    var userExplicitDragDist: Boolean = false
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
                userExplicitDragDist = userExplicitDragDist
            )
        }
    }
}

object HudConfig {
    private const val PREFS_NAME = "virtualpad_hud"
    private const val KEY_ACTIVE_PROFILE = "active_profile"
    private const val KEY_PROFILES_LIST = "profiles_list"
    private const val KEY_OPACITY = "hud_opacity"
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

    private fun getBaseElementsForProfile(profile: String): List<HudElement> {
        return if (profile == "Default 2") getDefault2Elements() else getDefaultElements()
    }

    fun getProfiles(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val str = prefs.getString(KEY_PROFILES_LIST, "Default,Default 2,RDR2,FPS,Racing") ?: "Default,Default 2,RDR2,FPS,Racing"
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
