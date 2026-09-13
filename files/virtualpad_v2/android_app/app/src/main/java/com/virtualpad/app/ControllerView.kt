package com.virtualpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Full-screen virtual pad supporting customizable HUD elements:
 * - Dynamic repositioning and scaling (0.5x .. 2.2x)
 * - Custom key binding per button
 * - Support for up to 16 extra custom buttons (custom_0 .. custom_15)
 * - Deterministic Z-order hit testing
 * - Interactive Edit Mode with live visual feedback and selection
 *
 * Every touch event in gameplay mode calls onStateChanged immediately
 * for ultra-low latency event-driven transmission.
 */
class ControllerView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onStateChanged: ((ControllerState) -> Unit)? = null
    var onElementSelected: ((HudElement?) -> Unit)? = null
    var onLayoutChanged: (() -> Unit)? = null

    // --- paints ---
    private val outlinePaint = Paint().apply {
        color = Color.parseColor("#3A8FB7")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val customOutlinePaint = Paint().apply {
        color = Color.parseColor("#A855F7")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val selectedOutlinePaint = Paint().apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val selectedHandlePaint = Paint().apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#14FFFFFF")
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val fillPaint = Paint().apply {
        color = Color.parseColor("#1E5F7A")
        isAntiAlias = true
    }
    private val fillActivePaint = Paint().apply {
        color = Color.parseColor("#4FC3F7")
        isAntiAlias = true
    }
    private val customFillPaint = Paint().apply {
        color = Color.parseColor("#4A154B")
        isAntiAlias = true
    }
    private val customFillActivePaint = Paint().apply {
        color = Color.parseColor("#C084FC")
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val subTextPaint = Paint().apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 20f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val editBannerPaint = Paint().apply {
        color = Color.parseColor("#FFD700")
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val swipeHintPaint = Paint().apply {
        color = Color.parseColor("#223A5580")
    }

    // --- State & Layout ---
    val elements = mutableListOf<HudElement>()
    private var layoutReady = false

    var isEditMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                // Clear any held input when entering edit mode to avoid stuck keys on PC
                pointerZone.clear()
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
                state = ControllerState()
                emitState()
            }
            invalidate()
        }

    var selectedElement: HudElement? = null
        private set

    // Drag tracking in Edit Mode
    private var dragPointerId: Int = -1
    private var dragStartFingerX: Float = 0f
    private var dragStartFingerY: Float = 0f
    private var dragStartElXPct: Float = 0f
    private var dragStartElYPct: Float = 0f

    // Gameplay input tracking
    private var state = ControllerState()
    private val pointerZone = HashMap<Int, String>()
    private var lookPointerId: Int? = null
    private var lastLookX = 0f
    private var lastLookY = 0f
    private var accumDx = 0f
    private var accumDy = 0f
    var mouseSensitivity = 1.75f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (elements.isEmpty()) {
            elements.addAll(HudConfig.loadLayout(context))
        }
        layoutReady = true
        invalidate()
    }

    // -------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0A0E1A"))
        if (!layoutReady) return

        val W = width.toFloat()
        val H = height.toFloat()

        // Swipe zone hint
        canvas.drawRect(W * 0.35f, 0f, W, H * 0.85f, swipeHintPaint)

        // Draw edit mode overlay grid & banner
        if (isEditMode) {
            drawEditModeGrid(canvas, W, H)
        }

        // Draw elements in ascending zOrder (so higher zOrder is drawn on top)
        val sortedList = elements.sortedBy { it.zOrder }
        for (el in sortedList) {
            when (el.type) {
                ElementType.STICK -> drawStickElement(canvas, el, W, H)
                ElementType.DPAD -> drawDpadElement(canvas, el, W, H)
                ElementType.BUTTON -> drawButtonElement(canvas, el, W, H)
            }
        }

        // Highlight selected element in Edit Mode
        if (isEditMode && selectedElement != null) {
            drawSelectionHighlight(canvas, selectedElement!!, W, H)
        }
    }

    private fun drawEditModeGrid(canvas: Canvas, W: Float, H: Float) {
        for (i in 1..9) {
            val gx = W * (i / 10f)
            canvas.drawLine(gx, 0f, gx, H, gridPaint)
            val gy = H * (i / 10f)
            canvas.drawLine(0f, gy, W, gy, gridPaint)
        }
        canvas.drawText("HUD EDIT MODE - Tap to select, drag to reposition", W * 0.5f, 50f, editBannerPaint)
    }

    private fun drawStickElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val r = H * 0.16f * el.scale

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, outlinePaint)

        // Thumb stick head
        val headX = if (!isEditMode) cx + state.stickX * r * 0.5f else cx
        val headY = if (!isEditMode) cy + state.stickY * r * 0.5f else cy
        canvas.drawCircle(headX, headY, r * 0.45f, fillActivePaint)

        textPaint.textSize = min(W, H) * 0.035f * el.scale
        canvas.drawText(el.label.ifEmpty { "STICK" }, cx, cy + r * 0.85f, textPaint)
    }

    private fun drawDpadElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val r = H * 0.14f * el.scale

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, outlinePaint)

        textPaint.textSize = min(W, H) * 0.032f * el.scale
        canvas.drawText("H", cx, cy - r * 0.5f, textPaint)
        canvas.drawText("T", cx, cy + r * 0.70f, textPaint)
        canvas.drawText("X", cx - r * 0.6f, cy + 10f, textPaint)
        canvas.drawText("X", cx + r * 0.6f, cy + 10f, textPaint)

        subTextPaint.textSize = min(W, H) * 0.022f * el.scale
        canvas.drawText("D-PAD", cx, cy + 8f, subTextPaint)
    }

    private fun drawButtonElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val active = isButtonActive(el)

        val isRect = el.id in listOf("lb", "lt", "rb", "rt", "small_icon", "hamburger_icon")
        val currentFill = when {
            el.isCustom && active -> customFillActivePaint
            el.isCustom -> customFillPaint
            active -> fillActivePaint
            else -> fillPaint
        }
        val currentOutline = if (el.isCustom) customOutlinePaint else outlinePaint

        if (isRect) {
            val rect = getButtonRect(el, cx, cy, W, H)
            canvas.drawRoundRect(rect, 16f, 16f, currentFill)
            canvas.drawRoundRect(rect, 16f, 16f, currentOutline)
            drawButtonLabels(canvas, el, cx, cy, W, H)
        } else {
            val r = getButtonRadius(el, H)
            canvas.drawCircle(cx, cy, r, currentFill)
            canvas.drawCircle(cx, cy, r, currentOutline)
            drawButtonLabels(canvas, el, cx, cy, W, H)
        }
    }

    private fun formatKeyDisplay(key: String): String = when (key.lowercase()) {
        "mouse_left", "lmb" -> "LMB"
        "mouse_right", "rmb" -> "RMB"
        "mouse_middle", "mmb" -> "MMB"
        else -> key.uppercase()
    }

    private fun drawButtonLabels(canvas: Canvas, el: HudElement, cx: Float, cy: Float, W: Float, H: Float) {
        textPaint.textSize = min(W, H) * 0.030f * el.scale
        subTextPaint.textSize = min(W, H) * 0.020f * el.scale

        if (el.isCustom) {
            val mainLabel = el.label.ifEmpty { "C${el.customSlot + 1}" }
            canvas.drawText(mainLabel, cx, cy - 2f, textPaint)
            if (el.key.isNotEmpty()) {
                val keyText = formatKeyDisplay(el.key)
                canvas.drawText("[$keyText]", cx, cy + 22f * el.scale, subTextPaint)
            }
        } else {
            // Stock button label formatting
            canvas.drawText(el.label, cx, cy + 10f, textPaint)
        }
    }

    private fun drawSelectionHighlight(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H

        when (el.type) {
            ElementType.STICK -> {
                val r = H * 0.16f * el.scale + 12f
                canvas.drawCircle(cx, cy, r, selectedOutlinePaint)
            }
            ElementType.DPAD -> {
                val r = H * 0.14f * el.scale + 12f
                canvas.drawCircle(cx, cy, r, selectedOutlinePaint)
            }
            ElementType.BUTTON -> {
                if (el.id in listOf("lb", "lt", "rb", "rt", "small_icon", "hamburger_icon")) {
                    val rect = getButtonRect(el, cx, cy, W, H)
                    rect.inset(-8f, -8f)
                    canvas.drawRoundRect(rect, 20f, 20f, selectedOutlinePaint)
                } else {
                    val r = getButtonRadius(el, H) + 8f
                    canvas.drawCircle(cx, cy, r, selectedOutlinePaint)
                }
            }
        }
        // Corner handle markers
        canvas.drawCircle(cx - 25f, cy - 25f, 6f, selectedHandlePaint)
        canvas.drawCircle(cx + 25f, cy - 25f, 6f, selectedHandlePaint)
        canvas.drawCircle(cx - 25f, cy + 25f, 6f, selectedHandlePaint)
        canvas.drawCircle(cx + 25f, cy + 25f, 6f, selectedHandlePaint)
    }

    private fun isButtonActive(el: HudElement): Boolean {
        if (isEditMode) return false
        val zoneKey = if (el.isCustom) "custom_${el.customSlot}" else "btn_${el.id}"
        return pointerZone.values.contains(zoneKey)
    }

    private fun getButtonRadius(el: HudElement, H: Float): Float = H * 0.075f * el.scale

    private fun getButtonRect(el: HudElement, cx: Float, cy: Float, W: Float, H: Float): RectF {
        val isSystem = el.id in listOf("small_icon", "hamburger_icon")
        val halfW = (if (isSystem) W * 0.038f else W * 0.06f) * el.scale
        val halfH = H * 0.05f * el.scale
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    // -------------------------------------------------------------------
    // Touch Hit Testing
    // -------------------------------------------------------------------
    fun findElementAt(x: Float, y: Float): HudElement? {
        val W = width.toFloat()
        val H = height.toFloat()
        // Evaluate in descending zOrder (topmost first)
        val sortedList = elements.sortedByDescending { it.zOrder }
        for (el in sortedList) {
            val cx = el.xPct * W
            val cy = el.yPct * H
            when (el.type) {
                ElementType.STICK -> {
                    val r = H * 0.16f * el.scale
                    if (hypot(x - cx, y - cy) <= r) return el
                }
                ElementType.DPAD -> {
                    val r = H * 0.14f * el.scale
                    if (hypot(x - cx, y - cy) <= r) return el
                }
                ElementType.BUTTON -> {
                    if (el.id in listOf("lb", "lt", "rb", "rt", "small_icon", "hamburger_icon")) {
                        val rect = getButtonRect(el, cx, cy, W, H)
                        if (rect.contains(x, y)) return el
                    } else {
                        val r = getButtonRadius(el, H)
                        if (hypot(x - cx, y - cy) <= r) return el
                    }
                }
            }
        }
        return null
    }

    private fun getStockBtn(id: String): Btn? = when (id) {
        "lb" -> Btn.LB
        "rb" -> Btn.RB
        "lt" -> Btn.LT
        "rt" -> Btn.RT
        "y" -> Btn.Y
        "x" -> Btn.X
        "b" -> Btn.B
        "a" -> Btn.A
        "lsb" -> Btn.LSB
        "rsb" -> Btn.RSB
        "small_icon" -> Btn.SMALL_ICON
        "hamburger_icon" -> Btn.HAMBURGER_ICON
        else -> null
    }

    // -------------------------------------------------------------------
    // Touch Events Handling
    // -------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditMode) {
            return handleEditTouchEvent(event)
        }
        return handleGameplayTouchEvent(event)
    }

    private fun handleEditTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragPointerId = event.getPointerId(0)
                dragStartFingerX = event.x
                dragStartFingerY = event.y

                val clicked = findElementAt(event.x, event.y)
                selectedElement = clicked
                onElementSelected?.invoke(clicked)

                clicked?.let {
                    dragStartElXPct = it.xPct
                    dragStartElYPct = it.yPct
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (selectedElement != null && dragPointerId != -1) {
                    val idx = event.findPointerIndex(dragPointerId)
                    if (idx >= 0) {
                        val currX = event.getX(idx)
                        val currY = event.getY(idx)
                        val dxPct = (currX - dragStartFingerX) / width
                        val dyPct = (currY - dragStartFingerY) / height
                        selectedElement?.let { el ->
                            el.xPct = (dragStartElXPct + dxPct).coerceIn(0.04f, 0.96f)
                            el.yPct = (dragStartElYPct + dyPct).coerceIn(0.04f, 0.96f)
                        }
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragPointerId = -1
                onLayoutChanged?.invoke()
                invalidate()
            }
        }
        return true
    }

    private fun handleGameplayTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                handlePointerDown(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    handlePointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                handlePointerUp(event.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerZone.clear()
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
                state = ControllerState()
            }
        }
        emitState()
        invalidate()
        return true
    }

    private fun handlePointerDown(id: Int, x: Float, y: Float) {
        val el = findElementAt(x, y)
        if (el != null) {
            when (el.type) {
                ElementType.STICK -> {
                    pointerZone[id] = "stick"
                    updateStick(el, x, y)
                }
                ElementType.DPAD -> {
                    pointerZone[id] = "dpad"
                    updateDpad(el, x, y)
                }
                ElementType.BUTTON -> {
                    if (el.isCustom && el.customSlot >= 0) {
                        pointerZone[id] = "custom_${el.customSlot}"
                        state = state.withCustomButton(el.customSlot, true)
                    } else {
                        pointerZone[id] = "btn_${el.id}"
                        val btn = getStockBtn(el.id)
                        if (btn != null) {
                            state = state.withButton(btn, true)
                        }
                    }
                }
            }
        } else {
            // Swipe look zone fallthrough (right side of screen)
            if (lookPointerId == null && x >= width * 0.30f) {
                lookPointerId = id
                pointerZone[id] = "look"
                lastLookX = x
                lastLookY = y
            }
        }
    }

    private fun handlePointerMove(id: Int, x: Float, y: Float) {
        when (pointerZone[id]) {
            "stick" -> {
                val stickEl = elements.firstOrNull { it.type == ElementType.STICK }
                stickEl?.let { updateStick(it, x, y) }
            }
            "dpad" -> {
                val dpadEl = elements.firstOrNull { it.type == ElementType.DPAD }
                dpadEl?.let { updateDpad(it, x, y) }
            }
            "look" -> {
                val dx = (x - lastLookX) * mouseSensitivity
                val dy = (y - lastLookY) * mouseSensitivity
                accumDx += dx
                accumDy += dy
                lastLookX = x
                lastLookY = y
            }
        }
    }

    private fun handlePointerUp(id: Int) {
        when (val zone = pointerZone[id]) {
            "stick" -> state = state.copy(stickX = 0f, stickY = 0f)
            "dpad" -> state = state
                .withButton(Btn.DPAD_UP, false).withButton(Btn.DPAD_DOWN, false)
                .withButton(Btn.DPAD_LEFT, false).withButton(Btn.DPAD_RIGHT, false)
            "look" -> {
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
            }
            else -> {
                if (zone != null) {
                    if (zone.startsWith("custom_")) {
                        val slot = zone.removePrefix("custom_").toIntOrNull()
                        if (slot != null) {
                            state = state.withCustomButton(slot, false)
                        }
                    } else if (zone.startsWith("btn_")) {
                        val btnId = zone.removePrefix("btn_")
                        val btn = getStockBtn(btnId)
                        if (btn != null) {
                            state = state.withButton(btn, false)
                        }
                    }
                }
            }
        }
        pointerZone.remove(id)
    }

    private fun updateStick(el: HudElement, x: Float, y: Float) {
        val cx = el.xPct * width
        val cy = el.yPct * height
        val r = height * 0.16f * el.scale
        val dx = (x - cx) / r
        val dy = (y - cy) / r
        val mag = min(1f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
        state = state.copy(
            stickX = (Math.cos(angle) * mag).toFloat(),
            stickY = (Math.sin(angle) * mag).toFloat(),
        )
    }

    private fun updateDpad(el: HudElement, x: Float, y: Float) {
        val cx = el.xPct * width
        val cy = el.yPct * height
        val r = height * 0.14f * el.scale
        val dx = x - cx
        val dy = y - cy
        val deadzone = r * 0.25f
        if (hypot(dx.toDouble(), dy.toDouble()) < deadzone) {
            state = state
                .withButton(Btn.DPAD_UP, false).withButton(Btn.DPAD_DOWN, false)
                .withButton(Btn.DPAD_LEFT, false).withButton(Btn.DPAD_RIGHT, false)
            return
        }
        val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
        state = state
            .withButton(Btn.DPAD_RIGHT, angle in -45.0..45.0)
            .withButton(Btn.DPAD_DOWN, angle in 45.0..135.0)
            .withButton(Btn.DPAD_LEFT, angle !in -135.0..135.0)
            .withButton(Btn.DPAD_UP, angle in -135.0..-45.0)
    }

    private fun emitState() {
        val sendDx = accumDx.toInt()
        val sendDy = accumDy.toInt()
        accumDx -= sendDx.toFloat()
        accumDy -= sendDy.toFloat()
        val out = state.copy(mouseDx = sendDx, mouseDy = sendDy)
        onStateChanged?.invoke(out)
    }

    // -------------------------------------------------------------------
    // HUD Customizer API (Called by MainActivity Editor Overlay)
    // -------------------------------------------------------------------
    fun selectElement(element: HudElement?) {
        selectedElement = element
        invalidate()
    }

    fun updateSelectedScale(newScale: Float) {
        selectedElement?.let {
            it.scale = newScale.coerceIn(0.5f, 2.2f)
            invalidate()
            onLayoutChanged?.invoke()
        }
    }

    fun updateSelectedKey(newKey: String, newLabel: String? = null) {
        selectedElement?.let {
            it.key = newKey
            if (newLabel != null) {
                it.label = newLabel
            } else if (it.isCustom) {
                it.label = "C${it.customSlot + 1} (${formatKeyDisplay(newKey)})"
            }
            invalidate()
            onLayoutChanged?.invoke()
        }
    }

    fun addCustomButton(): HudElement? {
        // Find next available customSlot in 0..15
        val usedSlots = elements.filter { it.isCustom }.map { it.customSlot }.toSet()
        val nextSlot = (0..15).firstOrNull { it !in usedSlots } ?: return null

        val maxZ = (elements.maxOfOrNull { it.zOrder } ?: 0) + 1
        val newEl = HudElement(
            id = "custom_$nextSlot",
            label = "C${nextSlot + 1}",
            key = "f",
            type = ElementType.BUTTON,
            xPct = 0.50f,
            yPct = 0.50f,
            scale = 1.0f,
            isCustom = true,
            customSlot = nextSlot,
            zOrder = maxZ
        )
        elements.add(newEl)
        selectedElement = newEl
        onElementSelected?.invoke(newEl)
        onLayoutChanged?.invoke()
        invalidate()
        return newEl
    }

    fun deleteSelectedElement(): Boolean {
        val current = selectedElement ?: return false
        if (!current.isCustom) return false // Stock controls cannot be deleted

        elements.remove(current)
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
        return true
    }

    fun resetToDefault() {
        elements.clear()
        elements.addAll(HudConfig.resetLayout(context))
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
    }
}
