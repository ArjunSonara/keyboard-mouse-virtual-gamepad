package com.virtualpad.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ElementType {
    BUTTON,
    STICK,
    DPAD
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
    var zOrder: Int = 0
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
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): HudElement {
            val typeStr = obj.optString("type", ElementType.BUTTON.name)
            val type = try { ElementType.valueOf(typeStr) } catch (_: Exception) { ElementType.BUTTON }
            return HudElement(
                id = obj.getString("id"),
                label = obj.optString("label", ""),
                key = obj.optString("key", ""),
                type = type,
                xPct = obj.optDouble("xPct", 0.5).toFloat(),
                yPct = obj.optDouble("yPct", 0.5).toFloat(),
                scale = obj.optDouble("scale", 1.0).toFloat(),
                isCustom = obj.optBoolean("isCustom", false),
                customSlot = obj.optInt("customSlot", -1),
                zOrder = obj.optInt("zOrder", 0)
            )
        }
    }
}

object HudConfig {
    private const val PREFS_NAME = "virtualpad_hud"
    private const val KEY_LAYOUT = "hud_layout_json"

    fun getDefaultElements(): List<HudElement> {
        val list = mutableListOf<HudElement>()
        var z = 1

        // Left Stick & D-Pad (Base Controls)
        list.add(HudElement(id = "leftstick", label = "STICK", key = "", type = ElementType.STICK, xPct = 0.13f, yPct = 0.62f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "dpad", label = "D-PAD", key = "", type = ElementType.DPAD, xPct = 0.32f, yPct = 0.72f, scale = 1.0f, zOrder = z++))

        // Bumpers & Triggers
        list.add(HudElement(id = "lb", label = "LB (TAB)", key = "tab", type = ElementType.BUTTON, xPct = 0.10f, yPct = 0.11f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "lt", label = "LT ([)", key = "[", type = ElementType.BUTTON, xPct = 0.23f, yPct = 0.11f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "rt", label = "RT (;)", key = ";", type = ElementType.BUTTON, xPct = 0.77f, yPct = 0.11f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "rb", label = "RB (Q)", key = "q", type = ElementType.BUTTON, xPct = 0.90f, yPct = 0.11f, scale = 1.0f, zOrder = z++))

        // ABXY Diamond
        val abxyCx = 0.87f
        val abxyCy = 0.60f
        val abxySpreadY = 0.11f
        val abxySpreadX = 0.065f // adjusted for 16:9 landscape aspect
        list.add(HudElement(id = "y", label = "Y (E)", key = "e", type = ElementType.BUTTON, xPct = abxyCx, yPct = abxyCy - abxySpreadY, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "a", label = "A (SHIFT)", key = "shift", type = ElementType.BUTTON, xPct = abxyCx, yPct = abxyCy + abxySpreadY, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "x", label = "X (SPACE)", key = "space", type = ElementType.BUTTON, xPct = abxyCx - abxySpreadX, yPct = abxyCy, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "b", label = "B (R)", key = "r", type = ElementType.BUTTON, xPct = abxyCx + abxySpreadX, yPct = abxyCy, scale = 1.0f, zOrder = z++))

        // Stick Click Buttons
        list.add(HudElement(id = "lsb", label = "LSB (CTRL)", key = "ctrl", type = ElementType.BUTTON, xPct = 0.13f, yPct = 0.88f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "rsb", label = "RSB (C)", key = "c", type = ElementType.BUTTON, xPct = 0.87f, yPct = 0.88f, scale = 1.0f, zOrder = z++))

        // Center System Buttons
        list.add(HudElement(id = "small_icon", label = "ESC", key = "esc", type = ElementType.BUTTON, xPct = 0.45f, yPct = 0.93f, scale = 1.0f, zOrder = z++))
        list.add(HudElement(id = "hamburger_icon", label = "B (MENU)", key = "b", type = ElementType.BUTTON, xPct = 0.55f, yPct = 0.93f, scale = 1.0f, zOrder = z++))

        return list
    }

    fun loadLayout(context: Context): MutableList<HudElement> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_LAYOUT, null) ?: return getDefaultElements().toMutableList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<HudElement>()
            for (i in 0 until array.length()) {
                list.add(HudElement.fromJson(array.getJSONObject(i)))
            }
            if (list.isEmpty()) getDefaultElements().toMutableList() else list
        } catch (_: Exception) {
            getDefaultElements().toMutableList()
        }
    }

    fun saveLayout(context: Context, elements: List<HudElement>) {
        val array = JSONArray()
        elements.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAYOUT, array.toString())
            .apply()
    }

    fun resetLayout(context: Context): List<HudElement> {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAYOUT)
            .apply()
        return getDefaultElements()
    }

    /**
     * Extracts full keymap dictionary for the PC server:
     * e.g. { "a": "shift", "lb": "tab", "custom_0": "m", ... }
     */
    fun extractKeymap(elements: List<HudElement>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (el in elements) {
            if (el.type == ElementType.BUTTON && el.key.isNotEmpty()) {
                val targetKey = if (el.isCustom && el.customSlot >= 0) "custom_${el.customSlot}" else el.id
                map[targetKey] = el.key
            }
        }
        return map
    }
}
