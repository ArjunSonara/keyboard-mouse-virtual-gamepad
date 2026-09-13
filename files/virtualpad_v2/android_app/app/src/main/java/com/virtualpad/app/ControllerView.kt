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
 * - Custom key binding per button and per D-Pad direction (Up, Down, Left, Right)
 * - Custom button shapes: Circle, Square, and Pill / Rounded Rect
 * - HUD Opacity adjustment (0.2x .. 1.0x)
 * - Gyroscope motion injection
 * - Interactive Edit Mode with live visual feedback and selection
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

    var hudOpacity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.2f, 1.0f)
            invalidate()
        }

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
            hudOpacity = HudConfig.getHudOpacity(context)
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

        // Apply opacity modulation
        val alphaMultiplier = if (isEditMode) 1.0f else hudOpacity
        val alpha255 = (255 * alphaMultiplier).toInt().coerceIn(40, 255)
        outlinePaint.alpha = alpha255
        customOutlinePaint.alpha = alpha255
        fillPaint.alpha = (180 * alphaMultiplier).toInt().coerceIn(30, 255)
        fillActivePaint.alpha = 255
        customFillPaint.alpha = (180 * alphaMultiplier).toInt().coerceIn(30, 255)
        customFillActivePaint.alpha = 255
        textPaint.alpha = alpha255
        subTextPaint.alpha = (200 * alphaMultiplier).toInt().coerceIn(30, 255)

        // Swipe zone hint
        canvas.drawRect(W * 0.35f, 0f, W, H * 0.85f, swipeHintPaint)

        // Draw edit mode overlay grid & banner
        if (isEditMode) {
            drawEditModeGrid(canvas, W, H)
        }

        // Draw elements in ascending zOrder (higher zOrder drawn on top)
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

    private fun formatKeyDisplay(key: String): String = when (key.lowercase()) {
        "mouse_left", "lmb" -> "LMB"
        "mouse_right", "rmb" -> "RMB"
        "mouse_middle", "mmb" -> "MMB"
        else -> key.uppercase()
    }

    private fun drawDpadElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val r = H * 0.14f * el.scale

        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, outlinePaint)

        textPaint.textSize = min(W, H) * 0.030f * el.scale
        // Draw directional keys on each quadrant!
        canvas.drawText(formatKeyDisplay(el.dpadUpKey), cx, cy - r * 0.5f, textPaint)
        canvas.drawText(formatKeyDisplay(el.dpadDownKey), cx, cy + r * 0.70f, textPaint)
        canvas.drawText(formatKeyDisplay(el.dpadLeftKey), cx - r * 0.6f, cy + 10f, textPaint)
        canvas.drawText(formatKeyDisplay(el.dpadRightKey), cx + r * 0.6f, cy + 10f, textPaint)

        subTextPaint.textSize = min(W, H) * 0.020f * el.scale
        canvas.drawText("D-PAD", cx, cy + 8f, subTextPaint)
    }

    private fun drawButtonElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val active = isButtonActive(el)

        val currentFill = when {
            el.isCustom && active -> customFillActivePaint
            el.isCustom -> customFillPaint
            active -> fillActivePaint
            else -> fillPaint
        }
        val currentOutline = if (el.isCustom) customOutlinePaint else outlinePaint

        when (el.shape) {
            ButtonShape.CIRCLE -> {
                val r = getButtonRadius(el, H)
                canvas.drawCircle(cx, cy, r, currentFill)
                canvas.drawCircle(cx, cy, r, currentOutline)
            }
            ButtonShape.SQUARE -> {
                val rect = getButtonSquare(el, cx, cy, H)
                canvas.drawRoundRect(rect, 14f, 14f, currentFill)
                canvas.drawRoundRect(rect, 14f, 14f, currentOutline)
            }
            ButtonShape.ROUNDED_RECT -> {
                val rect = getButtonRect(el, cx, cy, W, H)
                canvas.drawRoundRect(rect, 18f, 18f, currentFill)
                canvas.drawRoundRect(rect, 18f, 18f, currentOutline)
            }
        }
        drawButtonLabels(canvas, el, cx, cy, W, H)
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
                when (el.shape) {
                    ButtonShape.CIRCLE -> {
                        val r = getButtonRadius(el, H) + 8f
                        canvas.drawCircle(cx, cy, r, selectedOutlinePaint)
                    }
                    ButtonShape.SQUARE -> {
                        val rect = getButtonSquare(el, cx, cy, H)
                        rect.inset(-8f, -8f)
                        canvas.drawRoundRect(rect, 18f, 18f, selectedOutlinePaint)
                    }
                    ButtonShape.ROUNDED_RECT -> {
                        val rect = getButtonRect(el, cx, cy, W, H)
                        rect.inset(-8f, -8f)
                        canvas.drawRoundRect(rect, 22f, 22f, selectedOutlinePaint)
                    }
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

    private fun getButtonSquare(el: HudElement, cx: Float, cy: Float, H: Float): RectF {
        val halfSide = H * 0.075f * el.scale
        return RectF(cx - halfSide, cy - halfSide, cx + halfSide, cy + halfSide)
    }

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
                    when (el.shape) {
                        ButtonShape.CIRCLE -> {
                            val r = getButtonRadius(el, H)
                            if (hypot(x - cx, y - cy) <= r) return el
                        }
                        ButtonShape.SQUARE -> {
                            val rect = getButtonSquare(el, cx, cy, H)
                            if (rect.contains(x, y)) return el
                        }
                        ButtonShape.ROUNDED_RECT -> {
                            val rect = getButtonRect(el, cx, cy, W, H)
                            if (rect.contains(x, y)) return el
                        }
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

    /**
     * Injects motion from gyroscope into mouse look stream.
     */
    fun injectGyroAim(dx: Float, dy: Float) {
        accumDx += dx
        accumDy += dy
        emitState()
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

    fun updateDpadDirectionKey(direction: String, newKey: String) {
        selectedElement?.let { el ->
            if (el.type == ElementType.DPAD) {
                when (direction.lowercase()) {
                    "up" -> el.dpadUpKey = newKey
                    "down" -> el.dpadDownKey = newKey
                    "left" -> el.dpadLeftKey = newKey
                    "right" -> el.dpadRightKey = newKey
                }
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun updateSelectedShape(shape: ButtonShape) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.shape = shape
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun addCustomButton(): HudElement? {
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
            zOrder = maxZ,
            shape = ButtonShape.CIRCLE
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
        if (!current.isCustom) return false

        elements.remove(current)
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
        return true
    }

    fun resetToDefault(profileName: String = "Default") {
        elements.clear()
        elements.addAll(HudConfig.resetLayout(context, profileName))
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
    }

    fun loadProfile(profileName: String) {
        elements.clear()
        elements.addAll(HudConfig.loadLayout(context, profileName))
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
    }
}
