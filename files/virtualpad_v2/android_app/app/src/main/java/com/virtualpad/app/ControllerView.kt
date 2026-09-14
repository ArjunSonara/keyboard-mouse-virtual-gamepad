package com.virtualpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.Executors
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
    private val toggleLatchedPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val turboGlowPaint = Paint().apply {
        color = Color.parseColor("#FFD600")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val badgePaint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    private val macroGlowPaint = Paint().apply {
        color = Color.parseColor("#E040FB")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
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
                latchedButtons.clear()
                stopAllTurbo()
                isAutoRunLocked = false
                isStickInLockNotch = false
                autoShiftActive = false
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

    // Toggle and Turbo State
    val latchedButtons = HashSet<String>()
    private val turboHandler = Handler(Looper.getMainLooper())
    private val activeTurboRunnables = HashMap<String, Runnable>()
    private val turboPulseState = HashMap<String, Boolean>()

    // Sprint Lock & Auto-Shift
    var isAutoRunLocked = false
    var isStickInLockNotch = false
    var autoShiftActive = false

    // Scroll Wheel
    var onWheelScroll: ((Int) -> Unit)? = null
    private var lastWheelY = 0f
    private var wheelAccumDy = 0f

    // Macros
    private val macroExecutor = Executors.newSingleThreadExecutor()
    val activeMacroButtons = HashSet<String>()
    var onMacroKeyRequested: ((key: String, pressed: Boolean) -> Unit)? = null
    var isRecordingMacro: Boolean = false
    var onMacroEventRecorded: ((key: String, isDown: Boolean, timestampMs: Long) -> Unit)? = null

    val hapticHelper = HapticHelper(context, this)

    init {
        isHapticFeedbackEnabled = true
    }

    private var lastDpadQuadrant: Int = -1
    private var stickAtEdge: Boolean = false

    val isLookActive: Boolean
        get() = lookPointerId != null

    fun isButtonHeld(btn: Btn): Boolean {
        return (state.buttons and (1 shl btn.bit)) != 0
    }

    fun isAimActive(): Boolean {
        return isLookActive || isButtonHeld(Btn.LT) || isButtonHeld(Btn.RT)
    }

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
                ElementType.SCROLL_WHEEL -> drawScrollWheelElement(canvas, el, W, H)
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

        val notchX = cx
        val notchY = cy - r * 1.55f
        val notchR = r * 0.38f

        // Draw track stem leading to the Auto-Run notch
        canvas.drawLine(cx, cy - r, notchX, notchY + notchR, outlinePaint)

        // Draw Auto-Run Lock Notch Circle
        val notchFill = if (isAutoRunLocked || isStickInLockNotch) fillActivePaint else fillPaint
        val notchOutline = when {
            isAutoRunLocked -> toggleLatchedPaint
            isStickInLockNotch -> turboGlowPaint
            else -> outlinePaint
        }
        canvas.drawCircle(notchX, notchY, notchR, notchFill)
        canvas.drawCircle(notchX, notchY, notchR, notchOutline)

        // Draw 🏃 Icon inside notch
        val iconPaint = Paint().apply {
            color = Color.WHITE
            textSize = notchR * 1.1f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("🏃", notchX, notchY + notchR * 0.35f, iconPaint)

        // Main stick base circle
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, outlinePaint)

        // Thumb stick head
        val headX = when {
            isAutoRunLocked || isStickInLockNotch -> notchX
            !isEditMode -> cx + state.stickX * r * 0.5f
            else -> cx
        }
        val headY = when {
            isAutoRunLocked || isStickInLockNotch -> notchY
            !isEditMode -> cy + state.stickY * r * 0.5f
            else -> cy
        }
        canvas.drawCircle(headX, headY, r * 0.45f, if (isAutoRunLocked) customFillActivePaint else fillActivePaint)
        if (isAutoRunLocked) {
            canvas.drawCircle(headX, headY, r * 0.45f + 2f, toggleLatchedPaint)
        }

        textPaint.textSize = min(W, H) * 0.035f * el.scale
        val stickLabel = if (isAutoRunLocked) "AUTO RUN 🔒" else el.label.ifEmpty { "STICK" }
        canvas.drawText(stickLabel, cx, cy + r * 0.85f, textPaint)
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
        val zoneKey = getZoneKey(el)
        val isLatched = el.isToggle && latchedButtons.contains(zoneKey)
        val isTurboPulse = el.isTurbo && (turboPulseState[zoneKey] == true)
        val isMacroActive = activeMacroButtons.contains(el.id)

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
                if (isMacroActive) {
                    canvas.drawCircle(cx, cy, r + 2f, macroGlowPaint)
                } else if (isLatched) {
                    canvas.drawCircle(cx, cy, r + 2f, toggleLatchedPaint)
                } else if (isTurboPulse) {
                    canvas.drawCircle(cx, cy, r + 2f, turboGlowPaint)
                }
            }
            ButtonShape.SQUARE -> {
                val rect = getButtonSquare(el, cx, cy, H)
                canvas.drawRoundRect(rect, 14f, 14f, currentFill)
                canvas.drawRoundRect(rect, 14f, 14f, currentOutline)
                if (isMacroActive) {
                    canvas.drawRoundRect(rect, 14f, 14f, macroGlowPaint)
                } else if (isLatched) {
                    canvas.drawRoundRect(rect, 14f, 14f, toggleLatchedPaint)
                } else if (isTurboPulse) {
                    canvas.drawRoundRect(rect, 14f, 14f, turboGlowPaint)
                }
            }
            ButtonShape.ROUNDED_RECT -> {
                val rect = getButtonRect(el, cx, cy, W, H)
                canvas.drawRoundRect(rect, 18f, 18f, currentFill)
                canvas.drawRoundRect(rect, 18f, 18f, currentOutline)
                if (isMacroActive) {
                    canvas.drawRoundRect(rect, 18f, 18f, macroGlowPaint)
                } else if (isLatched) {
                    canvas.drawRoundRect(rect, 18f, 18f, toggleLatchedPaint)
                } else if (isTurboPulse) {
                    canvas.drawRoundRect(rect, 18f, 18f, turboGlowPaint)
                }
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

        // Visual badge indicators (⚡M for Macro, 🔒 for Toggle, ⚡ for Turbo)
        val r = getButtonRadius(el, H)
        if (el.macroType.isNotEmpty() || el.customMacro.isNotEmpty()) {
            val isRunning = activeMacroButtons.contains(el.id)
            badgePaint.textSize = min(W, H) * 0.022f * el.scale
            badgePaint.color = if (isRunning) Color.parseColor("#E040FB") else Color.parseColor("#CCBA68C8")
            canvas.drawText("⚡M", cx + r * 0.55f, cy - r * 0.40f, badgePaint)
        } else if (el.isToggle) {
            val zoneKey = getZoneKey(el)
            val isLatched = latchedButtons.contains(zoneKey)
            badgePaint.textSize = min(W, H) * 0.022f * el.scale
            badgePaint.color = if (isLatched) Color.parseColor("#00E676") else Color.parseColor("#80FFFFFF")
            canvas.drawText("🔒", cx + r * 0.55f, cy - r * 0.40f, badgePaint)
        } else if (el.isTurbo) {
            val zoneKey = getZoneKey(el)
            val isPulsing = turboPulseState[zoneKey] == true
            badgePaint.textSize = min(W, H) * 0.022f * el.scale
            badgePaint.color = if (isPulsing) Color.parseColor("#FFD600") else Color.parseColor("#B3FFD600")
            canvas.drawText("⚡", cx + r * 0.55f, cy - r * 0.40f, badgePaint)
        }
    }

    private fun getScrollWheelRect(el: HudElement, cx: Float, cy: Float, W: Float, H: Float): RectF {
        val halfW = W * 0.022f * el.scale
        val halfH = H * 0.14f * el.scale
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    private fun drawScrollWheelElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val rect = getScrollWheelRect(el, cx, cy, W, H)
        val isActive = pointerZone.values.contains("scroll_wheel")

        val currentFill = if (isActive) fillActivePaint else fillPaint
        val currentOutline = if (isActive) turboGlowPaint else outlinePaint

        // Background pill
        canvas.drawRoundRect(rect, 20f, 20f, currentFill)
        canvas.drawRoundRect(rect, 20f, 20f, currentOutline)

        // Notch indicator lines
        val linePaint = Paint().apply {
            color = Color.parseColor("#B0BEC5")
            strokeWidth = 3f * el.scale
            isAntiAlias = true
        }
        val halfW = rect.width() * 0.5f
        for (i in -2..2) {
            val notchY = cy + i * (rect.height() * 0.14f)
            val lineHalfW = if (i == 0) halfW * 0.7f else halfW * 0.45f
            canvas.drawLine(cx - lineHalfW, notchY, cx + lineHalfW, notchY, linePaint)
        }

        // Top & Bottom arrows
        val arrowPaint = Paint().apply {
            color = Color.WHITE
            textSize = min(W, H) * 0.022f * el.scale
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("▲", cx, rect.top + 26f * el.scale, arrowPaint)
        canvas.drawText("▼", cx, rect.bottom - 12f * el.scale, arrowPaint)
        canvas.drawText("WHEEL", cx, cy + rect.height() * 0.62f, subTextPaint)
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
            ElementType.SCROLL_WHEEL -> {
                val rect = getScrollWheelRect(el, cx, cy, W, H)
                rect.inset(-8f, -8f)
                canvas.drawRoundRect(rect, 24f, 24f, selectedOutlinePaint)
            }
        }
        // Corner handle markers
        canvas.drawCircle(cx - 25f, cy - 25f, 6f, selectedHandlePaint)
        canvas.drawCircle(cx + 25f, cy - 25f, 6f, selectedHandlePaint)
        canvas.drawCircle(cx - 25f, cy + 25f, 6f, selectedHandlePaint)
        canvas.drawCircle(cx + 25f, cy + 25f, 6f, selectedHandlePaint)
    }

    fun getZoneKey(el: HudElement): String {
        return if (el.isCustom && el.customSlot >= 0) "custom_${el.customSlot}" else "btn_${el.id}"
    }

    private fun setButtonState(zoneKey: String, pressed: Boolean) {
        if (zoneKey.startsWith("custom_")) {
            val slot = zoneKey.removePrefix("custom_").toIntOrNull()
            if (slot != null) {
                state = state.withCustomButton(slot, pressed)
            }
        } else if (zoneKey.startsWith("btn_")) {
            val btnId = zoneKey.removePrefix("btn_")
            val btn = getStockBtn(btnId)
            if (btn != null) {
                state = state.withButton(btn, pressed)
            }
        }
    }

    private fun isButtonActive(el: HudElement): Boolean {
        if (isEditMode) return false
        val zoneKey = getZoneKey(el)
        if (el.isTurbo) {
            return turboPulseState[zoneKey] == true
        }
        if (el.isToggle && latchedButtons.contains(zoneKey)) {
            return true
        }
        return pointerZone.values.contains(zoneKey)
    }

    private fun startTurbo(zoneKey: String, cps: Int) {
        stopTurbo(zoneKey)
        val safeCps = cps.coerceIn(4, 30)
        val halfPeriodMs = (1000L / (safeCps * 2)).coerceAtLeast(15L)

        var pulseOn = true
        turboPulseState[zoneKey] = true
        setButtonState(zoneKey, true)
        emitState()
        invalidate()

        val runnable = object : Runnable {
            override fun run() {
                if (!pointerZone.values.contains(zoneKey)) {
                    stopTurbo(zoneKey)
                    return
                }
                pulseOn = !pulseOn
                turboPulseState[zoneKey] = pulseOn
                setButtonState(zoneKey, pulseOn)
                emitState()
                invalidate()
                turboHandler.postDelayed(this, halfPeriodMs)
            }
        }
        activeTurboRunnables[zoneKey] = runnable
        turboHandler.postDelayed(runnable, halfPeriodMs)
    }

    private fun stopTurbo(zoneKey: String) {
        activeTurboRunnables.remove(zoneKey)?.let { turboHandler.removeCallbacks(it) }
        turboPulseState[zoneKey] = false
        setButtonState(zoneKey, false)
        emitState()
        invalidate()
    }

    fun stopAllTurbo() {
        activeTurboRunnables.values.forEach { turboHandler.removeCallbacks(it) }
        activeTurboRunnables.clear()
        turboPulseState.clear()
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
                    val notchX = cx
                    val notchY = cy - r * 1.55f
                    val notchR = r * 0.38f
                    if (hypot(x - cx, y - cy) <= r || hypot(x - notchX, y - notchY) <= notchR * 1.6f) return el
                }
                ElementType.DPAD -> {
                    val r = H * 0.14f * el.scale
                    if (hypot(x - cx, y - cy) <= r) return el
                }
                ElementType.SCROLL_WHEEL -> {
                    val rect = getScrollWheelRect(el, cx, cy, W, H)
                    if (rect.contains(x, y)) return el
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
        if (isRecordingMacro) {
            return handleGameplayTouchEvent(event)
        }
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
                stopAllTurbo()
                isAutoRunLocked = false
                isStickInLockNotch = false
                autoShiftActive = false
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
                    if (isAutoRunLocked) {
                        // Tapping stick cancels auto-run lock!
                        isAutoRunLocked = false
                        isStickInLockNotch = false
                        setSprintActive(false)
                        state = state.copy(stickX = 0f, stickY = 0f)
                        hapticHelper.click()
                        emitState()
                        invalidate()
                        return
                    }
                    pointerZone[id] = "stick"
                    updateStick(el, x, y)
                }
                ElementType.DPAD -> {
                    pointerZone[id] = "dpad"
                    updateDpad(el, x, y)
                }
                ElementType.SCROLL_WHEEL -> {
                    pointerZone[id] = "scroll_wheel"
                    lastWheelY = y
                    wheelAccumDy = 0f
                    hapticHelper.click()
                }
                ElementType.BUTTON -> {
                    val isHeavy = el.id in listOf("lt", "rt") || el.key in listOf("mouse_left", "mouse_right")
                    val zoneKey = getZoneKey(el)
                    pointerZone[id] = zoneKey

                    if (isRecordingMacro) {
                        val keyName = el.key.ifEmpty { el.id }
                        onMacroEventRecorded?.invoke(keyName, true, System.currentTimeMillis())
                        if (isHeavy) hapticHelper.heavyClick() else hapticHelper.click()
                        setButtonState(zoneKey, true)
                        invalidate()
                        return
                    }

                    if (el.macroType.isNotEmpty() || el.customMacro.isNotEmpty()) {
                        executeMacro(el)
                        return
                    }

                    if (el.isToggle) {
                        if (latchedButtons.contains(zoneKey)) {
                            latchedButtons.remove(zoneKey)
                            setButtonState(zoneKey, false)
                            hapticHelper.click()
                        } else {
                            latchedButtons.add(zoneKey)
                            setButtonState(zoneKey, true)
                            if (isHeavy) hapticHelper.heavyClick() else hapticHelper.click()
                        }
                    } else if (el.isTurbo) {
                        if (isHeavy) hapticHelper.heavyClick() else hapticHelper.click()
                        startTurbo(zoneKey, el.turboCps)
                    } else {
                        if (isHeavy) hapticHelper.heavyClick() else hapticHelper.click()
                        setButtonState(zoneKey, true)
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
            "scroll_wheel" -> {
                val dy = y - lastWheelY
                lastWheelY = y
                wheelAccumDy += dy
                val stepThreshold = 22f
                while (wheelAccumDy <= -stepThreshold) {
                    onWheelScroll?.invoke(120)
                    hapticHelper.tick()
                    wheelAccumDy += stepThreshold
                    if (isRecordingMacro) {
                        val now = System.currentTimeMillis()
                        onMacroEventRecorded?.invoke("wheel_up", true, now)
                        onMacroEventRecorded?.invoke("wheel_up", false, now + 30)
                    }
                }
                while (wheelAccumDy >= stepThreshold) {
                    onWheelScroll?.invoke(-120)
                    hapticHelper.tick()
                    wheelAccumDy -= stepThreshold
                    if (isRecordingMacro) {
                        val now = System.currentTimeMillis()
                        onMacroEventRecorded?.invoke("wheel_down", true, now)
                        onMacroEventRecorded?.invoke("wheel_down", false, now + 30)
                    }
                }
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
            "stick" -> {
                if (isStickInLockNotch) {
                    // Lock auto-run forward!
                    isAutoRunLocked = true
                    isStickInLockNotch = false
                    state = state.copy(stickX = 0f, stickY = -1.0f)
                    setSprintActive(true)
                    hapticHelper.heavyClick()
                } else {
                    isAutoRunLocked = false
                    setSprintActive(false)
                    state = state.copy(stickX = 0f, stickY = 0f)
                    stickAtEdge = false
                }
            }
            "dpad" -> {
                if (isRecordingMacro && lastDpadQuadrant != -1) {
                    val quadToKey = mapOf(0 to "right", 1 to "down", 2 to "left", 3 to "up")
                    quadToKey[lastDpadQuadrant]?.let { onMacroEventRecorded?.invoke(it, false, System.currentTimeMillis()) }
                }
                state = state
                    .withButton(Btn.DPAD_UP, false).withButton(Btn.DPAD_DOWN, false)
                    .withButton(Btn.DPAD_LEFT, false).withButton(Btn.DPAD_RIGHT, false)
                lastDpadQuadrant = -1
            }
            "scroll_wheel" -> {
                // Wheel gesture ended
            }
            "look" -> {
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
            }
            else -> {
                if (zone != null) {
                    if (isRecordingMacro) {
                        val el = elements.firstOrNull { getZoneKey(it) == zone }
                        if (el != null && el.type == ElementType.BUTTON) {
                            val keyName = el.key.ifEmpty { el.id }
                            onMacroEventRecorded?.invoke(keyName, false, System.currentTimeMillis())
                        }
                        setButtonState(zone, false)
                        invalidate()
                    } else if (activeTurboRunnables.containsKey(zone)) {
                        stopTurbo(zone)
                    } else if (latchedButtons.contains(zone)) {
                        // Latched in Toggle mode - stay ON!
                        setButtonState(zone, true)
                    } else {
                        setButtonState(zone, false)
                    }
                }
            }
        }
        pointerZone.remove(id)
    }

    private fun setSprintActive(active: Boolean) {
        autoShiftActive = active
        val shiftEl = elements.firstOrNull { it.type == ElementType.BUTTON && it.key.equals("shift", ignoreCase = true) }
        if (shiftEl != null) {
            setButtonState(getZoneKey(shiftEl), active)
        } else {
            state = state.withButton(Btn.A, active)
        }
        emitState()
        invalidate()
    }

    private fun updateStick(el: HudElement, x: Float, y: Float) {
        val cx = el.xPct * width
        val cy = el.yPct * height
        val r = height * 0.16f * el.scale

        val notchX = cx
        val notchY = cy - r * 1.55f
        val notchR = r * 0.38f

        // Check if finger dragged into Auto-Run Lock Notch
        val distToNotch = hypot((x - notchX).toDouble(), (y - notchY).toDouble()).toFloat()
        if (distToNotch <= notchR * 1.6f) {
            if (!isStickInLockNotch) {
                isStickInLockNotch = true
                hapticHelper.edgeBump()
            }
            state = state.copy(stickX = 0f, stickY = -1.0f)
            setSprintActive(true)
            return
        }
        isStickInLockNotch = false

        val dx = (x - cx) / r
        val dy = (y - cy) / r
        val mag = min(1f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
        val targetStickX = (Math.cos(angle) * mag).toFloat()
        val targetStickY = (Math.sin(angle) * mag).toFloat()

        state = state.copy(
            stickX = targetStickX,
            stickY = targetStickY
        )

        // Dynamic auto-sprint on forward tilt (> 80% mag and stickY < -0.55f)
        if (mag >= 0.80f && targetStickY <= -0.55f) {
            if (!autoShiftActive) {
                hapticHelper.tick()
                setSprintActive(true)
            }
        } else if (mag < 0.70f || targetStickY > -0.40f) {
            if (autoShiftActive && !isAutoRunLocked) {
                setSprintActive(false)
            }
        }

        // Boundary deflection tactile bump
        if (mag >= 0.96f) {
            if (!stickAtEdge) {
                stickAtEdge = true
                hapticHelper.edgeBump()
            }
        } else if (mag < 0.85f) {
            stickAtEdge = false
        }
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
            lastDpadQuadrant = -1
            return
        }
        val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))
        val currentQuad = when {
            angle in -45.0..45.0 -> 0    // Right
            angle in 45.0..135.0 -> 1    // Down
            angle in -135.0..-45.0 -> 3  // Up
            else -> 2                   // Left
        }
        if (currentQuad != lastDpadQuadrant) {
            val oldQuad = lastDpadQuadrant
            lastDpadQuadrant = currentQuad
            hapticHelper.tick()
            if (isRecordingMacro) {
                val now = System.currentTimeMillis()
                val quadToKey = mapOf(0 to "right", 1 to "down", 2 to "left", 3 to "up")
                quadToKey[oldQuad]?.let { onMacroEventRecorded?.invoke(it, false, now) }
                quadToKey[currentQuad]?.let { onMacroEventRecorded?.invoke(it, true, now) }
            }
        }
        state = state
            .withButton(Btn.DPAD_RIGHT, currentQuad == 0)
            .withButton(Btn.DPAD_DOWN, currentQuad == 1)
            .withButton(Btn.DPAD_LEFT, currentQuad == 2)
            .withButton(Btn.DPAD_UP, currentQuad == 3)
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

    fun updateSelectedButtonMode(isToggle: Boolean, isTurbo: Boolean, turboCps: Int? = null) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.isToggle = isToggle
                it.isTurbo = isTurbo
                if (turboCps != null) {
                    it.turboCps = turboCps
                }
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun updateSelectedMacro(macroType: String, customMacro: String? = null) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.macroType = macroType
                if (customMacro != null) {
                    it.customMacro = customMacro
                }
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun testMacro(steps: List<MacroStep>) {
        hapticHelper.click()
        macroExecutor.execute {
            try {
                for (step in steps) {
                    when (step.action) {
                        MacroActionType.TAP -> {
                            requestMacroKey(step.key, true)
                            Thread.sleep(step.durationMs.coerceIn(10, 5000))
                            requestMacroKey(step.key, false)
                        }
                        MacroActionType.HOLD -> requestMacroKey(step.key, true)
                        MacroActionType.RELEASE -> requestMacroKey(step.key, false)
                        MacroActionType.WAIT -> Thread.sleep(step.durationMs.coerceIn(5, 10000))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun executeMacro(el: HudElement) {
        if (el.macroType.isEmpty() && el.customMacro.isEmpty()) return
        activeMacroButtons.add(el.id)
        invalidate()
        hapticHelper.click()

        macroExecutor.execute {
            try {
                if (el.macroType == "custom" || (el.macroType.isEmpty() && el.customMacro.isNotEmpty())) {
                    val steps = MacroStep.listFromJson(el.customMacro)
                    for (step in steps) {
                        when (step.action) {
                            MacroActionType.TAP -> {
                                requestMacroKey(step.key, true)
                                Thread.sleep(step.durationMs.coerceIn(10, 5000))
                                requestMacroKey(step.key, false)
                            }
                            MacroActionType.HOLD -> requestMacroKey(step.key, true)
                            MacroActionType.RELEASE -> requestMacroKey(step.key, false)
                            MacroActionType.WAIT -> Thread.sleep(step.durationMs.coerceIn(5, 10000))
                        }
                    }
                } else {
                    when (el.macroType) {
                        "slide_cancel" -> {
                            requestMacroKey("c", true)
                            Thread.sleep(50)
                            requestMacroKey("c", false)
                            Thread.sleep(20)
                            requestMacroKey("c", true)
                            Thread.sleep(50)
                            requestMacroKey("c", false)
                            Thread.sleep(20)
                            requestMacroKey("space", true)
                            Thread.sleep(60)
                            requestMacroKey("space", false)
                        }
                        "shoot_melee" -> {
                            requestMacroKey("mouse_left", true)
                            Thread.sleep(40)
                            requestMacroKey("mouse_left", false)
                            Thread.sleep(30)
                            requestMacroKey("v", true)
                            Thread.sleep(60)
                            requestMacroKey("v", false)
                        }
                        "super_jump" -> {
                            requestMacroKey("space", true)
                            requestMacroKey("c", true)
                            Thread.sleep(80)
                            requestMacroKey("space", false)
                            requestMacroKey("c", false)
                        }
                        "quick_180" -> {
                            injectGyroAim(1800f, 0f)
                        }
                        "armor_plate" -> {
                            requestMacroKey("4", true)
                            Thread.sleep(2500)
                            requestMacroKey("4", false)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                activeMacroButtons.remove(el.id)
                postInvalidate()
            }
        }
    }

    private fun requestMacroKey(key: String, pressed: Boolean) {
        onMacroKeyRequested?.invoke(key, pressed)

        val matchingEl = elements.firstOrNull { it.type == ElementType.BUTTON && it.key.equals(key, ignoreCase = true) }
        if (matchingEl != null) {
            setButtonState(getZoneKey(matchingEl), pressed)
        } else {
            when (key.lowercase()) {
                "mouse_left" -> state = state.withButton(Btn.RT, pressed)
                "mouse_right" -> state = state.withButton(Btn.LT, pressed)
                "space" -> state = state.withButton(Btn.X, pressed)
                "c" -> state = state.withButton(Btn.RSB, pressed)
                "shift" -> state = state.withButton(Btn.A, pressed)
                "ctrl" -> state = state.withButton(Btn.LSB, pressed)
                "tab" -> state = state.withButton(Btn.LB, pressed)
                "e" -> state = state.withButton(Btn.RB, pressed)
                "r" -> state = state.withButton(Btn.Y, pressed)
                "v" -> {
                    val customV = elements.firstOrNull { it.isCustom && it.key.equals("v", ignoreCase = true) }
                    if (customV != null) setButtonState(getZoneKey(customV), pressed)
                }
                "4" -> {
                    val custom4 = elements.firstOrNull { it.isCustom && it.key.equals("4", ignoreCase = true) }
                    if (custom4 != null) setButtonState(getZoneKey(custom4), pressed)
                }
            }
        }
        emitState()
    }

    fun resetToDefault(profileName: String = "Default") {
        latchedButtons.clear()
        stopAllTurbo()
        isAutoRunLocked = false
        isStickInLockNotch = false
        autoShiftActive = false
        elements.clear()
        elements.addAll(HudConfig.resetLayout(context, profileName))
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
    }

    fun loadProfile(profileName: String) {
        latchedButtons.clear()
        stopAllTurbo()
        isAutoRunLocked = false
        isStickInLockNotch = false
        autoShiftActive = false
        elements.clear()
        elements.addAll(HudConfig.loadLayout(context, profileName))
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllTurbo()
    }
}
