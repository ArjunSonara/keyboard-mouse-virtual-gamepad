package com.virtualpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
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
    private val disabledOutlinePaint = Paint().apply {
        color = Color.parseColor("#E53935")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        isAntiAlias = true
    }
    private val disabledFillPaint = Paint().apply {
        color = Color.parseColor("#351515")
        isAntiAlias = true
    }
    private val macroGlowPaint = Paint().apply {
        color = Color.parseColor("#E040FB")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val gearBadgeBgPaint = Paint().apply {
        color = Color.parseColor("#E6161B22")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val gearBadgeBorderPaint = Paint().apply {
        color = Color.parseColor("#58A6FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        isAntiAlias = true
    }
    private val gearBadgeIconPaint = Paint().apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val ghostFillPaint = Paint().apply {
        color = Color.parseColor("#15FFFFFF")
        isAntiAlias = true
    }
    private val ghostOutlinePaint = Paint().apply {
        color = Color.parseColor("#5558A6FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        isAntiAlias = true
    }
    private val touchAreaGuidePaint = Paint().apply {
        color = Color.parseColor("#3358A6FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        isAntiAlias = true
    }

    // --- Steering Mode Paints ---
    private val pedalGasFillPaint = Paint().apply { color = Color.parseColor("#143522"); isAntiAlias = true }
    private val pedalGasActiveFillPaint = Paint().apply { color = Color.parseColor("#2EA043"); isAntiAlias = true }
    private val pedalGasOutlinePaint = Paint().apply { color = Color.parseColor("#3FB950"); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }

    private val pedalBrakeFillPaint = Paint().apply { color = Color.parseColor("#381717"); isAntiAlias = true }
    private val pedalBrakeActiveFillPaint = Paint().apply { color = Color.parseColor("#DA3633"); isAntiAlias = true }
    private val pedalBrakeOutlinePaint = Paint().apply { color = Color.parseColor("#F85149"); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }

    private val handbrakeFillPaint = Paint().apply { color = Color.parseColor("#362512"); isAntiAlias = true }
    private val handbrakeActiveFillPaint = Paint().apply { color = Color.parseColor("#D29922"); isAntiAlias = true }
    private val handbrakeOutlinePaint = Paint().apply { color = Color.parseColor("#E3B341"); style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true }

    private val steerModeActiveFillPaint = Paint().apply { color = Color.parseColor("#193B2D"); isAntiAlias = true }
    private val steerModeActiveOutlinePaint = Paint().apply { color = Color.parseColor("#FFD700"); style = Paint.Style.STROKE; strokeWidth = 4.5f; isAntiAlias = true }

    // --- State & Layout ---
    val elements = mutableListOf<HudElement>()
    private var layoutReady = false

    var hudOpacity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            invalidate()
        }

    var buttonFillOpacity: Float = 0.70f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            invalidate()
        }

    var buttonBorderOpacity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            invalidate()
        }

    var buttonTextOpacity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.0f, 1.0f)
            invalidate()
        }

    var areButtonsVisible: Boolean = true
        set(value) {
            field = value
            if (!value && !isEditMode) {
                // Clear any held input when turning buttons off to avoid stuck keys on PC
                pointerZone.clear()
                buttonPointerLastX.clear()
                buttonPointerLastY.clear()
                buttonTouchStart.clear()
                buttonTouchCurrent.clear()
                latchedButtons.clear()
                stopAllTurbo()
                stopAllInstantTap()
                isAutoRunLocked = false
                isStickInLockNotch = false
                autoShiftActive = false
                dynamicStickOriginX = null
                dynamicStickOriginY = null
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
                state = ControllerState()
                emitState()
            }
            invalidate()
        }

    var currentSteeringMode: SteeringMode = SteeringMode.OFF
        set(value) {
            field = value
            pointerZone.clear()
            buttonPointerLastX.clear()
            buttonPointerLastY.clear()
            buttonTouchStart.clear()
            buttonTouchCurrent.clear()
            latchedButtons.clear()
            stopAllTurbo()
            stopAllInstantTap()
            isAutoRunLocked = false
            isStickInLockNotch = false
            autoShiftActive = false
            dynamicStickOriginX = null
            dynamicStickOriginY = null
            lookPointerId = null
            wheelPointerId = null
            visualWheelAngle = 0f
            if (wheelSteeringLeft) {
                wheelSteeringLeft = false
                onMacroKeyRequested?.invoke("a", false)
            }
            if (wheelSteeringRight) {
                wheelSteeringRight = false
                onMacroKeyRequested?.invoke("d", false)
            }
            resetHeliStick()
            accumDx = 0f
            accumDy = 0f
            state = ControllerState()
            emitState()
            invalidate()
            onSteeringModeChanged?.invoke(value)
        }

    var onSteeringModeChanged: ((SteeringMode) -> Unit)? = null

    var isEditMode: Boolean = false
        set(value) {
            field = value
            if (value) {
                // Clear any held input when entering edit mode to avoid stuck keys on PC
                pointerZone.clear()
                buttonPointerLastX.clear()
                buttonPointerLastY.clear()
                buttonTouchStart.clear()
                buttonTouchCurrent.clear()
                latchedButtons.clear()
                stopAllTurbo()
                stopAllInstantTap()
                isAutoRunLocked = false
                isStickInLockNotch = false
                autoShiftActive = false
                dynamicStickOriginX = null
                dynamicStickOriginY = null
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
                state = ControllerState()
                emitState()
            }
            invalidate()
        }

    var onViewportTouch: ((MotionEvent) -> Boolean)? = null
    var isAdjustingViewport: Boolean = false
        set(value) {
            field = value
            if (value) {
                // Clear any held input when entering viewport adjust mode to avoid stuck keys on PC
                pointerZone.clear()
                buttonPointerLastX.clear()
                buttonPointerLastY.clear()
                buttonTouchStart.clear()
                buttonTouchCurrent.clear()
                latchedButtons.clear()
                stopAllTurbo()
                stopAllInstantTap()
                isAutoRunLocked = false
                isStickInLockNotch = false
                autoShiftActive = false
                dynamicStickOriginX = null
                dynamicStickOriginY = null
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
    private val buttonPointerLastX = HashMap<Int, Float>()
    private val buttonPointerLastY = HashMap<Int, Float>()
    private val buttonTouchStart = HashMap<String, Pair<Float, Float>>()
    private val buttonTouchCurrent = HashMap<String, Pair<Float, Float>>()
    private var lookPointerId: Int? = null
    private var lastLookX = 0f
    private var lastLookY = 0f
    private var accumDx = 0f
    private var accumDy = 0f
    var mouseSensitivity = 1.75f

    // Pro Esports Touch Aim Engine
    var isEsportsAimEngineEnabled = true
    var isDpiNormalizationEnabled = true
    var isJitterFilterEnabled = true
    var jitterFilterThreshold = 0.15f
    var aimCurveMode = AimCurveMode.LINEAR
    var sCurveDampening = 0.75f
    var sCurveFlickBoost = 1.35f

    private val dpiScaleFactor: Float by lazy {
        val xdpi = context.resources.displayMetrics.xdpi
        if (xdpi > 50f) 160f / xdpi else 1.0f
    }

    fun processAimDelta(rawDx: Float, rawDy: Float): Pair<Float, Float> {
        // Master Bypass: when Esports Aim Engine is OFF, pass 100% pure raw uncalibrated input
        if (!isEsportsAimEngineEnabled) {
            return Pair(rawDx, rawDy)
        }

        // 1. Physical DPI Normalization
        var dx = if (isDpiNormalizationEnabled) rawDx * dpiScaleFactor else rawDx
        var dy = if (isDpiNormalizationEnabled) rawDy * dpiScaleFactor else rawDy

        // 2. Micro-Jitter Suppression (Pulse/Tremor Deadband)
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (isJitterFilterEnabled && dist < jitterFilterThreshold) {
            return Pair(0f, 0f)
        }

        // 3. Aim Response Curve Profile
        if (aimCurveMode == AimCurveMode.S_CURVE) {
            val factor = when {
                dist < 3.0f -> {
                    // Smoothly blend from sCurveDampening up to 1.0f for sniper micro-adjustments
                    sCurveDampening + (1.0f - sCurveDampening) * (dist / 3.0f)
                }
                dist > 8.0f -> {
                    // Smoothly scale up for instant 180-degree flick turns
                    val excess = (dist - 8.0f).coerceAtMost(25.0f) / 25.0f
                    1.0f + (sCurveFlickBoost - 1.0f) * excess
                }
                else -> 1.0f
            }
            dx *= factor
            dy *= factor
        }

        return Pair(dx, dy)
    }

    // Toggle and Turbo State
    val latchedButtons = HashSet<String>()
    private val turboHandler = Handler(Looper.getMainLooper())
    private val activeTurboRunnables = HashMap<String, Runnable>()
    private val turboPulseState = HashMap<String, Boolean>()

    // Instant Tap / Quick Pulse (One-Shot) State
    private val instantTapHandler = Handler(Looper.getMainLooper())
    private val activeInstantTapRunnables = HashMap<String, Runnable>()
    private val instantTapPulseState = HashMap<String, Boolean>()

    private var visualStickX = 0f
    private var visualStickY = 0f

    // Sprint Lock & Auto-Shift
    var isAutoRunLocked = false
    var isStickInLockNotch = false
    var autoShiftActive = false

    // Stick Mode: true = Sprint Mode (auto-sprint on forward tilt), false = Normal Mode (strictly 8-direction WASD, no shift)
    var stickSprintMode: Boolean = true
        set(value) {
            field = value
            if (!value) {
                resetSprint()
            }
            invalidate()
        }
    var stickFloatingMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                dynamicStickOriginX = null
                dynamicStickOriginY = null
            }
            invalidate()
        }
    var dynamicStickOriginX: Float? = null
    var dynamicStickOriginY: Float? = null
    var stickTouchScale: Float = 1.8f
        set(value) {
            field = value.coerceIn(1.0f, 2.5f)
            invalidate()
        }

    // Touch Optimization / Multi-Touch Booster
    var isTouchOptimizationEnabled: Boolean = true
        set(value) {
            field = value
            invalidate()
        }
    var onOpenStickSettingsRequested: ((HudElement) -> Unit)? = null

    fun resetSprint() {
        isAutoRunLocked = false
        isStickInLockNotch = false
        if (autoShiftActive) {
            setSprintActive(false)
        }
        dynamicStickOriginX = null
        dynamicStickOriginY = null
        visualStickX = 0f
        visualStickY = 0f
        state = state.copy(stickX = 0f, stickY = 0f)
        emitState()
        invalidate()
    }

    // Scroll Wheel
    var onWheelScroll: ((Int) -> Unit)? = null
    var onMiddleClick: ((Boolean) -> Unit)? = null
    private var lastWheelY = 0f
    private var wheelDownY = 0f
    private var wheelDownTime = 0L
    private var wheelScrolledSteps = 0
    private var wheelAccumDy = 0f

    // Macros
    private val macroExecutor = Executors.newSingleThreadExecutor()
    val activeMacroButtons = HashSet<String>()
    var onMacroKeyRequested: ((key: String, pressed: Boolean) -> Unit)? = null
    var isRecordingMacro: Boolean = false
    var onMacroEventRecorded: ((key: String, isDown: Boolean, timestampMs: Long) -> Unit)? = null
    var onOpenKeySettingsRequested: ((HudElement) -> Unit)? = null

    // Steering Mode & Wheel State
    private var steerModePointerId: Int? = null
    private var steerModeDownX = 0f
    private var steerModeDownY = 0f
    private var steerModeCurrentX = 0f
    private var steerModeCurrentY = 0f
    private var steerModeHoveredOption: SteeringMode? = null
    var isRadialSelectorOpen: Boolean = false
        private set
    private val steerModeLongPressHandler = Handler(Looper.getMainLooper())
    private var steerModeLongPressRunnable: Runnable? = null
    private var lastActiveSteeringMode = SteeringMode.PEDALS

    private var wheelPointerId: Int? = null
    var visualWheelAngle: Float = 0f
        private set
    private var wheelPrevTouchX = 0f
    private var wheelPrevTouchY = 0f
    private var wheelSteeringLeft = false
    private var wheelSteeringRight = false
    private var wheelSpringAnimator: android.animation.ValueAnimator? = null

    // Helicopter Flight Stick State
    private var heliStickPointerId: Int? = null
    private var visualHeliStickX = 0f
    private var visualHeliStickY = 0f
    private var heli8Active = false
    private var heli5Active = false
    private var heli4Active = false
    private var heli6Active = false

    val hapticHelper = HapticHelper(context, this)

    init {
        isHapticFeedbackEnabled = true
        hudOpacity = HudConfig.getHudOpacity(context)
        buttonFillOpacity = HudConfig.getButtonFillOpacity(context)
        buttonBorderOpacity = HudConfig.getButtonBorderOpacity(context)
        buttonTextOpacity = HudConfig.getButtonTextOpacity(context)
        stickTouchScale = HudConfig.getStickTouchScale(context)
        stickFloatingMode = HudConfig.isStickFloatingMode(context)
        currentSteeringMode = HudConfig.getSteeringMode(context)
        isEsportsAimEngineEnabled = HudConfig.isEsportsAimEngineEnabled(context)
        isDpiNormalizationEnabled = HudConfig.isDpiNormalizationEnabled(context)
        isJitterFilterEnabled = HudConfig.isJitterFilterEnabled(context)
        jitterFilterThreshold = HudConfig.getJitterFilterThreshold(context)
        aimCurveMode = HudConfig.getAimCurveMode(context)
        sCurveDampening = HudConfig.getSCurveDampening(context)
        sCurveFlickBoost = HudConfig.getSCurveFlickBoost(context)
        elements.addAll(HudConfig.loadLayout(context))
    }

    private var lastDpadQuadrant: Int = -1
    private var stickAtEdge: Boolean = false

    val isLookActive: Boolean
        get() = lookPointerId != null || buttonPointerLastX.isNotEmpty()

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
            buttonFillOpacity = HudConfig.getButtonFillOpacity(context)
            buttonBorderOpacity = HudConfig.getButtonBorderOpacity(context)
            buttonTextOpacity = HudConfig.getButtonTextOpacity(context)
            areButtonsVisible = HudConfig.areButtonsVisible(context)
            currentSteeringMode = HudConfig.getSteeringMode(context)
            isEsportsAimEngineEnabled = HudConfig.isEsportsAimEngineEnabled(context)
            isDpiNormalizationEnabled = HudConfig.isDpiNormalizationEnabled(context)
            isJitterFilterEnabled = HudConfig.isJitterFilterEnabled(context)
            jitterFilterThreshold = HudConfig.getJitterFilterThreshold(context)
            aimCurveMode = HudConfig.getAimCurveMode(context)
            sCurveDampening = HudConfig.getSCurveDampening(context)
            sCurveFlickBoost = HudConfig.getSCurveFlickBoost(context)
            elements.addAll(HudConfig.loadLayout(context))
        }
        layoutReady = true
        onLayoutChanged?.invoke()
        invalidate()
    }

    // -------------------------------------------------------------------
    // Drawing
    // -------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        // Transparent background so live hardware-decoded PC video is 100% visible!
        if (isEditMode) {
            canvas.drawColor(Color.parseColor("#440A0E1A")) // Subtle translucent dark tint only in edit mode
        }
        if (!layoutReady) return
        if (!isEditMode && !areButtonsVisible) {
            // All buttons are turned OFF: clean 100% unobstructed screen view!
            return
        }

        val W = width.toFloat()
        val H = height.toFloat()

        // Apply opacity modulation (Fill, Border, and Text independently controllable down to 0%)
        val alphaMultiplier = if (isEditMode) 1.0f else hudOpacity
        val borderAlpha = if (isEditMode) 255 else (255 * buttonBorderOpacity * alphaMultiplier).toInt().coerceIn(0, 255)
        val fillAlpha = if (isEditMode) 180 else (180 * buttonFillOpacity * alphaMultiplier).toInt().coerceIn(0, 255)
        val textAlpha = if (isEditMode) 255 else (255 * buttonTextOpacity * alphaMultiplier).toInt().coerceIn(0, 255)
        val subTextAlpha = if (isEditMode) 200 else (200 * buttonTextOpacity * alphaMultiplier).toInt().coerceIn(0, 255)
        val activeFillAlpha = if (isEditMode) 255 else maxOf(140, (255 * buttonBorderOpacity).toInt().coerceIn(80, 255))

        outlinePaint.alpha = borderAlpha
        customOutlinePaint.alpha = borderAlpha
        fillPaint.alpha = fillAlpha
        fillActivePaint.alpha = activeFillAlpha
        customFillPaint.alpha = fillAlpha
        customFillActivePaint.alpha = activeFillAlpha
        textPaint.alpha = textAlpha
        subTextPaint.alpha = subTextAlpha
        badgePaint.alpha = if (isEditMode) 255 else textAlpha

        // Only draw swipe zone guide in edit mode so gameplay screen is 100% unobstructed!
        if (isEditMode) {
            canvas.drawRect(W * 0.50f, 0f, W, H, swipeHintPaint)
        }

        // Draw edit mode overlay grid & banner
        if (isEditMode) {
            drawEditModeGrid(canvas, W, H)
        }

        // Draw elements in ascending zOrder (higher zOrder drawn on top)
        val sortedList = elements.sortedBy { it.zOrder }
        for (el in sortedList) {
            if (!isEditMode && !el.isEnabled) continue
            if (!isElementVisibleInCurrentMode(el)) continue

            val elOpacity = el.opacity.coerceIn(0.0f, 1.0f)
            if (!isEditMode && elOpacity <= 0f) {
                // Completely invisible button during gameplay: skip drawing to canvas (still touchable)
                continue
            }

            val needsAlphaLayer = elOpacity < 1.0f
            if (needsAlphaLayer) {
                val alphaInt = if (isEditMode) {
                    ((maxOf(0.35f, elOpacity)) * 255).toInt().coerceIn(0, 255)
                } else {
                    (elOpacity * 255).toInt().coerceIn(0, 255)
                }
                canvas.saveLayerAlpha(0f, 0f, W, H, alphaInt)
            }

            when (el.type) {
                ElementType.STICK -> drawStickElement(canvas, el, W, H)
                ElementType.DPAD -> drawDpadElement(canvas, el, W, H)
                ElementType.BUTTON -> drawButtonElement(canvas, el, W, H)
                ElementType.SCROLL_WHEEL -> drawScrollWheelElement(canvas, el, W, H)
                ElementType.STEERING_WHEEL -> drawSteeringWheelElement(canvas, el, W, H)
            }

            if (needsAlphaLayer) {
                canvas.restore()
            }
        }

        // Highlight selected element in Edit Mode
        if (isEditMode && selectedElement != null) {
            drawSelectionHighlight(canvas, selectedElement!!, W, H)
        }

        // Draw radial mode quick selector overlay if open
        if (!isEditMode && isRadialSelectorOpen) {
            drawRadialModeSelector(canvas, W, H)
        }
    }

    fun isElementVisibleInCurrentMode(el: HudElement): Boolean {
        // Mode button is ALWAYS visible in all modes
        if (el.id == "steer_mode") return true

        val mode1Elements = setOf("pedal_gas", "pedal_brake", "steer_left", "steer_right")
        val mode2Elements = setOf("steering_wheel", "wheel_gas", "wheel_brake")
        val heliElements = setOf("heli_stick", "heli_8", "heli_4", "heli_5", "heli_6")
        val sharedSteeringElements = setOf("steer_handbrake")

        return when (currentSteeringMode) {
            SteeringMode.OFF -> {
                // Steering Mode 0: OFF (Normal Joystick mode)
                // Hide all steering-specific and helicopter controls
                if (el.id in mode1Elements || el.id in mode2Elements || el.id in heliElements || el.id in sharedSteeringElements || el.type == ElementType.STEERING_WHEEL) {
                    false
                } else {
                    true
                }
            }
            SteeringMode.PEDALS -> {
                // Steering Mode 1: PEDALS (Pedals on left, Arrows on right)
                // Hide leftstick, Mode 2 wheel controls, and helicopter controls
                if (el.id == "leftstick" || (el.type == ElementType.STICK && el.id != "rightstick" && el.id != "heli_stick") ||
                    el.id in mode2Elements || el.id in heliElements || el.type == ElementType.STEERING_WHEEL) {
                    false
                } else {
                    true
                }
            }
            SteeringMode.WHEEL -> {
                // Steering Mode 2: WHEEL (Steering Wheel on left, dedicated lever & brake on right)
                // Hide leftstick, Mode 1 pedals & arrows, and helicopter controls
                if (el.id == "leftstick" || (el.type == ElementType.STICK && el.id != "rightstick" && el.id != "heli_stick") ||
                    el.id in mode1Elements || el.id in heliElements) {
                    false
                } else {
                    true
                }
            }
            SteeringMode.HELICOPTER -> {
                // Steering Mode 3: HELICOPTER (Normal Joystick on left for WASD, Heli Joystick on right for pitch/roll)
                // Hide Mode 1 pedals/arrows, Mode 2 wheel/pedals, shared steering handbrake, and old individual 8/4/5/6 buttons.
                if (el.id in mode1Elements || el.id in mode2Elements || el.id in sharedSteeringElements || el.type == ElementType.STEERING_WHEEL ||
                    el.id in setOf("heli_8", "heli_4", "heli_5", "heli_6")) {
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun drawSteeringWheelElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val r = H * 0.17f * el.scale

        canvas.save()
        canvas.rotate(visualWheelAngle, cx, cy)

        // Outer rim
        val rimPaint = Paint(outlinePaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = r * 0.22f
            color = Color.parseColor("#1C2433")
        }
        canvas.drawCircle(cx, cy, r, rimPaint)

        // Outer rim border
        val rimBorderPaint = Paint(outlinePaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = if (wheelPointerId != null) Color.parseColor("#4FC3F7") else Color.parseColor("#3A8FB7")
        }
        canvas.drawCircle(cx, cy, r + r * 0.11f, rimBorderPaint)
        canvas.drawCircle(cx, cy, r - r * 0.11f, rimBorderPaint)

        // Top racing centering stripe (at 12 o'clock / -90 deg)
        val stripePaint = Paint().apply {
            color = Color.parseColor("#FFD700")
            style = Paint.Style.STROKE
            strokeWidth = r * 0.22f
            isAntiAlias = true
        }
        val stripeRect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(stripeRect, -100f, 20f, false, stripePaint)

        // 3 Spokes connecting to center hub
        val spokePaint = Paint().apply {
            color = Color.parseColor("#252E3E")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val spokeBorderPaint = Paint().apply {
            color = Color.parseColor("#3A8FB7")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        val spokeW = r * 0.16f
        val hubR = r * 0.36f

        // Left spoke (-180 deg)
        val leftRect = RectF(cx - r, cy - spokeW / 2f, cx - hubR * 0.7f, cy + spokeW / 2f)
        canvas.drawRoundRect(leftRect, 6f, 6f, spokePaint)
        canvas.drawRoundRect(leftRect, 6f, 6f, spokeBorderPaint)

        // Right spoke (0 deg)
        val rightRect = RectF(cx + hubR * 0.7f, cy - spokeW / 2f, cx + r, cy + spokeW / 2f)
        canvas.drawRoundRect(rightRect, 6f, 6f, spokePaint)
        canvas.drawRoundRect(rightRect, 6f, 6f, spokeBorderPaint)

        // Bottom spoke (90 deg)
        val bottomRect = RectF(cx - spokeW / 2f, cy + hubR * 0.7f, cx + spokeW / 2f, cy + r)
        canvas.drawRoundRect(bottomRect, 6f, 6f, spokePaint)
        canvas.drawRoundRect(bottomRect, 6f, 6f, spokeBorderPaint)

        // Center hub
        val hubBgPaint = Paint().apply {
            color = if (wheelPointerId != null) Color.parseColor("#183048") else Color.parseColor("#121722")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(cx, cy, hubR, hubBgPaint)
        canvas.drawCircle(cx, cy, hubR, rimBorderPaint)

        // Horn badge in center
        val hubIconPaint = Paint().apply {
            color = Color.WHITE
            textSize = hubR * 0.85f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val fm = hubIconPaint.fontMetrics
        val hubBaseline = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText("🚗", cx, hubBaseline, hubIconPaint)

        canvas.restore()

        // Draw steer angle display with dynamic steering indicators below wheel
        if (!isEditMode) {
            val angleText = "${visualWheelAngle.toInt()}°"
            val leftChevrons = if (visualWheelAngle < -15f) "◀◀" else "◀"
            val rightChevrons = if (visualWheelAngle > 15f) "▶▶" else "▶"
            val angleDisplay = "$leftChevrons  $angleText  $rightChevrons"
            val anglePaint = Paint(subTextPaint).apply {
                color = if (visualWheelAngle < -15f || visualWheelAngle > 15f) Color.parseColor("#00E5FF") else Color.parseColor("#B0BEC5")
                textSize = min(W, H) * 0.026f * el.scale
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(angleDisplay, cx, cy + r + 34f, anglePaint)
        } else {
            canvas.drawText("STEERING WHEEL", cx, cy + r + 34f, textPaint)
        }
    }

    private fun getRadialChipPositions(el: HudElement, W: Float, H: Float): List<Pair<SteeringMode, PointF>> {
        val btnCx = el.xPct * W
        val btnCy = el.yPct * H
        val dist = (H * 0.28f).coerceIn(120f, 220f)

        val angles = if (btnCx > W * 0.65f) {
            listOf(-165f, -140f, -115f, -90f)
        } else if (btnCx < W * 0.35f) {
            listOf(-90f, -65f, -40f, -15f)
        } else {
            listOf(-145f, -110f, -75f, -40f)
        }
        val modes = listOf(SteeringMode.OFF, SteeringMode.PEDALS, SteeringMode.WHEEL, SteeringMode.HELICOPTER)
        return modes.mapIndexed { idx, mode ->
            val rad = Math.toRadians(angles[idx].toDouble())
            val px = (btnCx + Math.cos(rad) * dist).toFloat().coerceIn(80f, W - 80f)
            val py = (btnCy + Math.sin(rad) * dist).toFloat().coerceIn(50f, H - 50f)
            Pair(mode, PointF(px, py))
        }
    }

    private fun updateRadialHover(el: HudElement, x: Float, y: Float) {
        val chips = getRadialChipPositions(el, width.toFloat(), height.toFloat())
        val threshold = 75f
        val closest = chips.minByOrNull { hypot((x - it.second.x).toDouble(), (y - it.second.y).toDouble()).toFloat() }
        val newHover = if (closest != null && hypot((x - closest.second.x).toDouble(), (y - closest.second.y).toDouble()).toFloat() <= threshold) {
            closest.first
        } else null
        if (newHover != steerModeHoveredOption) {
            steerModeHoveredOption = newHover
            if (newHover != null) hapticHelper.click()
            invalidate()
        }
    }

    private fun drawRadialModeSelector(canvas: Canvas, W: Float, H: Float) {
        val el = elements.firstOrNull { it.id == "steer_mode" } ?: return
        val btnCx = el.xPct * W
        val btnCy = el.yPct * H
        val chips = getRadialChipPositions(el, W, H)

        // Backdrop tint
        canvas.drawColor(Color.parseColor("#66000000"))

        val linePaint = Paint().apply {
            color = Color.parseColor("#4458A6FF")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
            isAntiAlias = true
        }

        val chipBgPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val chipBorderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            isAntiAlias = true
        }
        val chipTextPaint = Paint().apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        for ((mode, pos) in chips) {
            canvas.drawLine(btnCx, btnCy, pos.x, pos.y, linePaint)

            val isHovered = steerModeHoveredOption == mode
            val isActive = currentSteeringMode == mode
            val chipW = if (isHovered) 120f else 105f
            val chipH = if (isHovered) 48f else 40f
            val rect = RectF(pos.x - chipW, pos.y - chipH, pos.x + chipW, pos.y + chipH)

            chipBgPaint.color = when {
                isHovered -> Color.parseColor("#1F6FEB")
                isActive -> Color.parseColor("#238636")
                else -> Color.parseColor("#E6161B22")
            }
            chipBorderPaint.color = when {
                isHovered -> Color.parseColor("#58A6FF")
                isActive -> Color.parseColor("#3FB950")
                else -> Color.parseColor("#484F58")
            }
            chipBorderPaint.strokeWidth = if (isHovered) 5f else 3f

            canvas.drawRoundRect(rect, 24f, 24f, chipBgPaint)
            canvas.drawRoundRect(rect, 24f, 24f, chipBorderPaint)

            val label = when (mode) {
                SteeringMode.OFF -> "🕹 NORMAL"
                SteeringMode.PEDALS -> "🚦 PEDALS"
                SteeringMode.WHEEL -> "🛞 WHEEL"
                SteeringMode.HELICOPTER -> "🚁 HELI"
            }
            val fm = chipTextPaint.fontMetrics
            val baseline = pos.y - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, pos.x, baseline, chipTextPaint)
        }

        // Draw finger pointer dot
        val fingerDotPaint = Paint().apply {
            color = Color.parseColor("#58A6FF")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val fingerRingPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        canvas.drawCircle(steerModeCurrentX, steerModeCurrentY, 14f, fingerDotPaint)
        canvas.drawCircle(steerModeCurrentX, steerModeCurrentY, 20f, fingerRingPaint)
    }

    private fun updateSteeringWheel(el: HudElement, x: Float, y: Float) {
        val cx = el.xPct * width
        val cy = el.yPct * height

        val v1x = wheelPrevTouchX - cx
        val v1y = wheelPrevTouchY - cy
        val v2x = x - cx
        val v2y = y - cy

        val r1 = hypot(v1x.toDouble(), v1y.toDouble()).toFloat()
        val r2 = hypot(v2x.toDouble(), v2y.toDouble()).toFloat()

        if (r1 > 15f && r2 > 15f) {
            val cross = v1x * v2y - v1y * v2x
            val dot = v1x * v2x + v1y * v2y
            val deltaRad = kotlin.math.atan2(cross.toDouble(), dot.toDouble())
            val deltaDeg = Math.toDegrees(deltaRad).toFloat()
            if (!deltaDeg.isNaN()) {
                visualWheelAngle = (visualWheelAngle + deltaDeg).coerceIn(-180f, 180f)
            }
        }
        wheelPrevTouchX = x
        wheelPrevTouchY = y

        val steerNorm = (visualWheelAngle / 135f).coerceIn(-1.0f, 1.0f)
        state = state.copy(stickX = steerNorm)

        val isLeft = steerNorm < -0.15f
        val isRight = steerNorm > 0.15f
        if (isLeft != wheelSteeringLeft) {
            wheelSteeringLeft = isLeft
            onMacroKeyRequested?.invoke("a", isLeft)
            if (isLeft) hapticHelper.tick()
        }
        if (isRight != wheelSteeringRight) {
            wheelSteeringRight = isRight
            onMacroKeyRequested?.invoke("d", isRight)
            if (isRight) hapticHelper.tick()
        }
    }

    private fun animateWheelReturn() {
        val startAngle = visualWheelAngle
        if (startAngle == 0f) return
        wheelSpringAnimator?.cancel()
        wheelSpringAnimator = android.animation.ValueAnimator.ofFloat(startAngle, 0f).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                visualWheelAngle = it.animatedValue as Float
                val steerNorm = (visualWheelAngle / 135f).coerceIn(-1.0f, 1.0f)
                state = state.copy(stickX = steerNorm)
                emitState()
                invalidate()
            }
        }
        wheelSpringAnimator?.start()
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

    private fun drawHeliStickElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val r = H * 0.16f * el.scale

        // Main stick base circle
        val currentFill = if (isEditMode && !el.isEnabled) disabledFillPaint else fillPaint
        val currentOutline = if (isEditMode && !el.isEnabled) disabledOutlinePaint else outlinePaint
        canvas.drawCircle(cx, cy, r, currentFill)
        canvas.drawCircle(cx, cy, r, currentOutline)

        // Guide crosshair & inner ring
        val guidePaint = Paint(outlinePaint).apply {
            strokeWidth = 2f
            color = Color.parseColor("#384B66")
        }
        canvas.drawLine(cx - r * 0.75f, cy, cx + r * 0.75f, cy, guidePaint)
        canvas.drawLine(cx, cy - r * 0.75f, cx, cy + r * 0.75f, guidePaint)
        canvas.drawCircle(cx, cy, r * 0.5f, guidePaint)

        // Direction indicators (Up: 8, Down: 5, Left: 4, Right: 6)
        val dirPaint = Paint().apply {
            textSize = r * 0.20f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            color = Color.parseColor("#80B0D0")
        }
        val activeDirPaint = Paint().apply {
            textSize = r * 0.22f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            color = Color.parseColor("#4FC3F7")
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val upLabel = "▲ ${el.dpadUpKey.uppercase().replace("NUM", "")}"
        val downLabel = "▼ ${el.dpadDownKey.uppercase().replace("NUM", "")}"
        val leftLabel = "◀ ${el.dpadLeftKey.uppercase().replace("NUM", "")}"
        val rightLabel = "▶ ${el.dpadRightKey.uppercase().replace("NUM", "")}"

        canvas.drawText(upLabel, cx, cy - r * 0.60f, if (heli8Active) activeDirPaint else dirPaint)
        canvas.drawText(downLabel, cx, cy + r * 0.80f, if (heli5Active) activeDirPaint else dirPaint)
        canvas.drawText(leftLabel, cx - r * 0.58f, cy + r * 0.08f, if (heli4Active) activeDirPaint else dirPaint)
        canvas.drawText(rightLabel, cx + r * 0.58f, cy + r * 0.08f, if (heli6Active) activeDirPaint else dirPaint)

        // Thumb stick head
        val headX = if (!isEditMode) cx + visualHeliStickX * r * 0.5f else cx
        val headY = if (!isEditMode) cy + visualHeliStickY * r * 0.5f else cy
        val headFill = if (isEditMode && !el.isEnabled) disabledFillPaint else if (heliStickPointerId != null) customFillActivePaint else fillActivePaint
        canvas.drawCircle(headX, headY, r * 0.44f, headFill)
        if (heliStickPointerId != null) {
            canvas.drawCircle(headX, headY, r * 0.44f + 2f, toggleLatchedPaint)
        }

        // Center stick label
        textPaint.textSize = min(W, H) * 0.028f * el.scale
        val stickLabel = el.label.ifEmpty { "HELI 🚁" }
        canvas.drawText(stickLabel, cx, cy - r * 0.12f, textPaint)

        subTextPaint.textSize = min(W, H) * 0.016f * el.scale
        canvas.drawText("PITCH / ROLL", cx, cy + r * 0.16f, subTextPaint)

        // In Edit Mode: Draw mini gear ⚙ icon badge at bottom-right of the stick
        if (isEditMode) {
            val badgeR = (r * 0.22f).coerceIn(15f, 30f)
            val badgeX = cx + r * 0.72f
            val badgeY = cy + r * 0.72f
            canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBgPaint)
            canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBorderPaint)
            gearBadgeIconPaint.textSize = badgeR * 1.25f
            val fm = gearBadgeIconPaint.fontMetrics
            val baseline = badgeY - (fm.ascent + fm.descent) / 2f
            canvas.drawText("⚙", badgeX, baseline, gearBadgeIconPaint)
        }
    }

    private fun drawStickElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        if (el.id == "heli_stick") {
            drawHeliStickElement(canvas, el, W, H)
            return
        }
        val cx = if (stickFloatingMode && !isEditMode && dynamicStickOriginX != null) dynamicStickOriginX!! else el.xPct * W
        val cy = if (stickFloatingMode && !isEditMode && dynamicStickOriginY != null) dynamicStickOriginY!! else el.yPct * H
        val r = H * 0.16f * el.scale

        val notchX = cx
        val notchY = cy - r * 1.55f
        val notchR = r * 0.38f

        // Only draw sprint notch & track stem in Sprint Mode
        if (stickSprintMode) {
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
        }

        // Main stick base circle
        val currentFill = if (isEditMode && !el.isEnabled) disabledFillPaint else fillPaint
        val currentOutline = if (isEditMode && !el.isEnabled) disabledOutlinePaint else outlinePaint
        canvas.drawCircle(cx, cy, r, currentFill)
        canvas.drawCircle(cx, cy, r, currentOutline)

        // Thumb stick head
        val headX = when {
            stickSprintMode && (isAutoRunLocked || isStickInLockNotch) -> notchX
            !isEditMode -> cx + visualStickX * r * 0.5f
            else -> cx
        }
        val headY = when {
            stickSprintMode && (isAutoRunLocked || isStickInLockNotch) -> notchY
            !isEditMode -> cy + visualStickY * r * 0.5f
            else -> cy
        }
        val headFill = when {
            isEditMode && !el.isEnabled -> disabledFillPaint
            isAutoRunLocked && stickSprintMode -> customFillActivePaint
            else -> fillActivePaint
        }
        canvas.drawCircle(headX, headY, r * 0.45f, headFill)
        if (isAutoRunLocked && stickSprintMode && el.isEnabled) {
            canvas.drawCircle(headX, headY, r * 0.45f + 2f, toggleLatchedPaint)
        }

        textPaint.textSize = min(W, H) * 0.035f * el.scale
        val stickLabel = when {
            isAutoRunLocked && stickSprintMode -> "AUTO RUN 🔒"
            stickSprintMode -> el.label.ifEmpty { "STICK" }
            else -> el.label.ifEmpty { "STICK" }
        }
        canvas.drawText(stickLabel, cx, cy + r * 0.85f, textPaint)

        // Mode indicator below label
        subTextPaint.textSize = min(W, H) * 0.018f * el.scale
        if (isEditMode && !el.isEnabled) {
            badgePaint.textSize = min(W, H) * 0.020f * el.scale
            badgePaint.color = Color.parseColor("#FF5252")
            canvas.drawText("🚫 OFF", cx, cy + r * 1.05f, badgePaint)
        } else {
            val modeLabel = if (stickSprintMode) "⚡ Sprint" else "🚶 Normal"
            canvas.drawText(modeLabel, cx, cy + r * 1.05f, subTextPaint)
        }

        // In Edit Mode: Draw mini gear ⚙ icon badge at bottom-right of the stick
        if (isEditMode) {
            val badgeR = (r * 0.22f).coerceIn(15f, 30f)
            val badgeX = cx + r * 0.72f
            val badgeY = cy + r * 0.72f
            canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBgPaint)
            canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBorderPaint)
            gearBadgeIconPaint.textSize = badgeR * 1.25f
            val fm = gearBadgeIconPaint.fontMetrics
            val baseline = badgeY - (fm.ascent + fm.descent) / 2f
            canvas.drawText("⚙", badgeX, baseline, gearBadgeIconPaint)

            // Outer detection guide
            if (el == selectedElement && stickTouchScale > 1.0f) {
                canvas.drawCircle(cx, cy, r * stickTouchScale, touchAreaGuidePaint)
            }
        }
    }

    private fun formatKeyDisplay(key: String): String = when (key.lowercase()) {
        "mouse_left", "lmb" -> "LMB"
        "mouse_right", "rmb" -> "RMB"
        "mouse_middle", "mmb" -> "MMB"
        "num8" -> "NUM 8"
        "num4" -> "NUM 4"
        "num5" -> "NUM 5"
        "num6" -> "NUM 6"
        "num7" -> "NUM 7"
        "num9" -> "NUM 9"
        "num1" -> "NUM 1"
        "num2" -> "NUM 2"
        "num3" -> "NUM 3"
        "num0" -> "NUM 0"
        else -> key.uppercase()
    }

    private fun drawDpadElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val r = H * 0.14f * el.scale

        val currentFill = if (isEditMode && !el.isEnabled) disabledFillPaint else fillPaint
        val currentOutline = if (isEditMode && !el.isEnabled) disabledOutlinePaint else outlinePaint
        canvas.drawCircle(cx, cy, r, currentFill)
        canvas.drawCircle(cx, cy, r, currentOutline)

        textPaint.textSize = min(W, H) * 0.030f * el.scale
        // Draw directional keys on each quadrant!
        canvas.drawText(formatKeyDisplay(el.dpadUpKey), cx, cy - r * 0.5f, textPaint)
        canvas.drawText(formatKeyDisplay(el.dpadDownKey), cx, cy + r * 0.70f, textPaint)
        canvas.drawText(formatKeyDisplay(el.dpadLeftKey), cx - r * 0.6f, cy + 10f, textPaint)
        canvas.drawText(formatKeyDisplay(el.dpadRightKey), cx + r * 0.6f, cy + 10f, textPaint)

        if (isEditMode && !el.isEnabled) {
            badgePaint.textSize = min(W, H) * 0.020f * el.scale
            badgePaint.color = Color.parseColor("#FF5252")
            canvas.drawText("🚫 OFF", cx, cy + 8f, badgePaint)
        } else {
            subTextPaint.textSize = min(W, H) * 0.020f * el.scale
            canvas.drawText("D-PAD", cx, cy + 8f, subTextPaint)
        }
    }

    private fun drawButtonElement(canvas: Canvas, el: HudElement, W: Float, H: Float) {
        val cx = el.xPct * W
        val cy = el.yPct * H
        val zoneKey = getZoneKey(el)

        // Drag displacement calculation (Free Fire / mobile drag-to-aim effect)
        val touchStart = buttonTouchStart[zoneKey]
        val touchCurr = buttonTouchCurrent[zoneKey]
        val r = getButtonRadius(el, H)
        val (dragDx, dragDy) = if (touchStart != null && touchCurr != null && !isEditMode && el.showGhostShadow && el.maxDragDistance > 0f) {
            val startDistFromCenter = hypot((touchStart.first - cx).toDouble(), (touchStart.second - cy).toDouble()).toFloat()
            val (rawDx, rawDy) = if (startDistFromCenter > r) {
                // Thumb fell in the hitbox area outside the button circle:
                // Automatically drag from center towards the thumb!
                Pair(touchCurr.first - cx, touchCurr.second - cy)
            } else {
                // Thumb touched inside the button:
                // Drag displacement tracks finger movement from touch start
                Pair(touchCurr.first - touchStart.first, touchCurr.second - touchStart.second)
            }
            val dist = hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
            val maxDrag = r * el.maxDragDistance
            if (dist > maxDrag && dist > 0f) {
                Pair(rawDx * (maxDrag / dist), rawDy * (maxDrag / dist))
            } else {
                Pair(rawDx, rawDy)
            }
        } else {
            Pair(0f, 0f)
        }
        val isDragging = hypot(dragDx.toDouble(), dragDy.toDouble()).toFloat() > 4f

        // 1. If dragging, draw the stationary Ghost Shadow at the home position (cx, cy)
        if (isDragging && el.showGhostShadow) {
            val hasRot = el.rotation != 0f
            if (hasRot) {
                canvas.save()
                canvas.rotate(el.rotation, cx, cy)
            }
            when (el.shape) {
                ButtonShape.CIRCLE -> {
                    val r = getButtonRadius(el, H)
                    canvas.drawCircle(cx, cy, r, ghostFillPaint)
                    canvas.drawCircle(cx, cy, r, ghostOutlinePaint)
                }
                ButtonShape.SQUARE -> {
                    val rect = getButtonSquare(el, cx, cy, H)
                    canvas.drawRoundRect(rect, 14f, 14f, ghostFillPaint)
                    canvas.drawRoundRect(rect, 14f, 14f, ghostOutlinePaint)
                }
                ButtonShape.ROUNDED_RECT -> {
                    val rect = getButtonRect(el, cx, cy, W, H)
                    canvas.drawRoundRect(rect, 18f, 18f, ghostFillPaint)
                    canvas.drawRoundRect(rect, 18f, 18f, ghostOutlinePaint)
                }
            }
            if (hasRot) {
                canvas.restore()
            }
        }

        // 2. In Edit Mode: If selected and has touch area padding > 1.0x, draw the touch detection area guide
        if (isEditMode && el == selectedElement && el.touchPadding > 1.0f) {
            val r = getButtonRadius(el, H) * el.touchPadding
            canvas.drawCircle(cx, cy, r, touchAreaGuidePaint)
        }

        // 3. Draw the actual active button (at home pos if not dragging, or dragged pos if dragging)
        val drawCx = cx + dragDx
        val drawCy = cy + dragDy

        val hasRotation = el.rotation != 0f
        if (hasRotation) {
            canvas.save()
            canvas.rotate(el.rotation, drawCx, drawCy)
        }

        val active = isButtonActive(el)

        val isLatched = el.isToggle && latchedButtons.contains(zoneKey)
        val isTurboPulse = el.isTurbo && (turboPulseState[zoneKey] == true)
        val isMacroActive = activeMacroButtons.contains(el.id)

        val currentFill = when {
            isEditMode && !el.isEnabled -> disabledFillPaint
            el.id == "steer_mode" -> if (active) customFillActivePaint else if (currentSteeringMode != SteeringMode.OFF) steerModeActiveFillPaint else fillPaint
            el.isCustom && active -> customFillActivePaint
            el.isCustom -> customFillPaint
            active -> fillActivePaint
            else -> fillPaint
        }
        val currentOutline = when {
            isEditMode && !el.isEnabled -> disabledOutlinePaint
            el.id == "steer_mode" -> if (currentSteeringMode != SteeringMode.OFF) steerModeActiveOutlinePaint else outlinePaint
            el.isCustom -> customOutlinePaint
            else -> outlinePaint
        }

        when (el.shape) {
            ButtonShape.CIRCLE -> {
                val r = getButtonRadius(el, H)
                canvas.drawCircle(drawCx, drawCy, r, currentFill)
                canvas.drawCircle(drawCx, drawCy, r, currentOutline)
                if (isMacroActive) {
                    canvas.drawCircle(drawCx, drawCy, r + 2f, macroGlowPaint)
                } else if (isLatched) {
                    canvas.drawCircle(drawCx, drawCy, r + 2f, toggleLatchedPaint)
                } else if (isTurboPulse) {
                    canvas.drawCircle(drawCx, drawCy, r + 2f, turboGlowPaint)
                }
            }
            ButtonShape.SQUARE -> {
                val rect = getButtonSquare(el, drawCx, drawCy, H)
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
                val rect = getButtonRect(el, drawCx, drawCy, W, H)
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

        drawButtonLabels(canvas, el, drawCx, drawCy, W, H)

        if (hasRotation) {
            canvas.restore()
        }

        // In Edit Mode: Draw mini gear ⚙ icon badge at top-right corner
        if (isEditMode) {
            val (badgeX, badgeY) = getGearBadgeCenter(el, cx, cy, W, H)
            val badgeR = getGearBadgeRadius(el, H)
            canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBgPaint)
            canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBorderPaint)
            gearBadgeIconPaint.textSize = badgeR * 1.25f
            val fm = gearBadgeIconPaint.fontMetrics
            val baseline = badgeY - (fm.ascent + fm.descent) / 2f
            canvas.drawText("⚙", badgeX, baseline, gearBadgeIconPaint)
        }
    }

    private fun drawButtonLabels(canvas: Canvas, el: HudElement, cx: Float, cy: Float, W: Float, H: Float) {
        val r = getButtonRadius(el, H)

        val rawLabel = when (el.id) {
            "steer_mode" -> when (currentSteeringMode) {
                SteeringMode.OFF -> "🚗 NORMAL"
                SteeringMode.PEDALS -> "🚦 PEDALS"
                SteeringMode.WHEEL -> "🛞 WHEEL"
                SteeringMode.HELICOPTER -> "🚁 HELI"
            }
            "pedal_gas", "wheel_gas" -> el.label.ifEmpty { "ACCEL" }
            "pedal_brake", "wheel_brake" -> el.label.ifEmpty { "BRAKE" }
            "steer_left" -> el.label.ifEmpty { "◀ LEFT" }
            "steer_right" -> el.label.ifEmpty { "RIGHT ▶" }
            "steer_handbrake" -> el.label.ifEmpty { "(P) SPACE" }
            "heli_8" -> el.label.ifEmpty { "8" }
            "heli_4" -> el.label.ifEmpty { "4" }
            "heli_5" -> el.label.ifEmpty { "5" }
            "heli_6" -> el.label.ifEmpty { "6" }
            else -> el.label.trim()
        }
        val mainLabel = when {
            rawLabel.isNotEmpty() -> {
                if (rawLabel.contains(" (") && rawLabel.endsWith(")")) {
                    rawLabel.substringBefore(" (").trim()
                } else {
                    rawLabel
                }
            }
            el.isCustom -> "C${el.customSlot + 1}"
            else -> el.id.uppercase()
        }

        if (el.key.isNotEmpty()) {
            textPaint.textSize = min(W, H) * 0.026f * el.scale
            subTextPaint.textSize = min(W, H) * 0.018f * el.scale
            val keyText = formatKeyDisplay(el.key)
            canvas.drawText(mainLabel, cx, cy - 3f * el.scale, textPaint)
            canvas.drawText("[$keyText]", cx, cy + 18f * el.scale, subTextPaint)
        } else {
            textPaint.textSize = min(W, H) * 0.030f * el.scale
            canvas.drawText(mainLabel, cx, cy + 10f * el.scale, textPaint)
        }

        // In Edit Mode, if disabled, draw prominent OFF badge
        if (isEditMode && !el.isEnabled) {
            badgePaint.textSize = min(W, H) * 0.019f * el.scale
            badgePaint.color = Color.parseColor("#FF5252")
            canvas.drawText("🚫 OFF", cx, cy - r * 0.55f, badgePaint)
        }

        // Visual badge indicators (⚡M for Macro, 🔒 for Toggle, ⚡ for Turbo, 🎯 for Swipe Aim)
        if (el.swipeToAim) {
            badgePaint.textSize = min(W, H) * 0.019f * el.scale
            badgePaint.color = Color.parseColor("#58A6FF")
            canvas.drawText("🎯", cx - r * 0.55f, cy - r * 0.40f, badgePaint)
        }
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
        } else if (el.isInstantTap) {
            val zoneKey = getZoneKey(el)
            val isPulsing = instantTapPulseState[zoneKey] == true
            badgePaint.textSize = min(W, H) * 0.022f * el.scale
            badgePaint.color = if (isPulsing) Color.parseColor("#FF9100") else Color.parseColor("#B3FF9100")
            canvas.drawText("⏱️", cx + r * 0.55f, cy - r * 0.40f, badgePaint)
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

        val currentFill = when {
            isEditMode && !el.isEnabled -> disabledFillPaint
            isActive -> fillActivePaint
            else -> fillPaint
        }
        val currentOutline = when {
            isEditMode && !el.isEnabled -> disabledOutlinePaint
            isActive -> turboGlowPaint
            else -> outlinePaint
        }

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

        // Center MMB label badge
        val mmbPaint = Paint().apply {
            color = Color.parseColor("#58A6FF")
            textSize = min(W, H) * 0.016f * el.scale
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("MMB", cx, cy - 24f * el.scale, mmbPaint)
        if (isEditMode && !el.isEnabled) {
            badgePaint.textSize = min(W, H) * 0.016f * el.scale
            badgePaint.color = Color.parseColor("#FF5252")
            canvas.drawText("🚫 OFF", cx, cy + rect.height() * 0.62f, badgePaint)
        } else {
            canvas.drawText("WHEEL", cx, cy + rect.height() * 0.62f, subTextPaint)
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
            ElementType.STEERING_WHEEL -> {
                val r = H * 0.17f * el.scale + 12f
                canvas.drawCircle(cx, cy, r, selectedOutlinePaint)
            }
            ElementType.DPAD -> {
                val r = H * 0.14f * el.scale + 12f
                canvas.drawCircle(cx, cy, r, selectedOutlinePaint)
            }
            ElementType.BUTTON -> {
                val hasRot = el.rotation != 0f
                if (hasRot) {
                    canvas.save()
                    canvas.rotate(el.rotation, cx, cy)
                }
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
                if (hasRot) {
                    canvas.restore()
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
            } else {
                val el = elements.firstOrNull { it.id == btnId }
                val targetKey = el?.key?.ifEmpty { null }
                if (targetKey != null) {
                    onMacroKeyRequested?.invoke(targetKey, pressed)
                }
                when (btnId) {
                    "pedal_gas", "wheel_gas" -> {
                        if (targetKey == null) onMacroKeyRequested?.invoke("w", pressed)
                        state = state.copy(stickY = if (pressed) -1.0f else 0f)
                    }
                    "pedal_brake", "wheel_brake" -> {
                        if (targetKey == null) onMacroKeyRequested?.invoke("s", pressed)
                        state = state.copy(stickY = if (pressed) 1.0f else 0f)
                    }
                    "steer_left" -> {
                        if (targetKey == null) onMacroKeyRequested?.invoke("a", pressed)
                        state = state.copy(stickX = if (pressed) -1.0f else 0f)
                    }
                    "steer_right" -> {
                        if (targetKey == null) onMacroKeyRequested?.invoke("d", pressed)
                        state = state.copy(stickX = if (pressed) 1.0f else 0f)
                    }
                    "steer_handbrake" -> {
                        if (targetKey == null) onMacroKeyRequested?.invoke("space", pressed)
                        state = state.withButton(Btn.A, pressed)
                    }
                }
            }
        }
    }

    private fun isButtonActive(el: HudElement): Boolean {
        if (isEditMode) return false
        val zoneKey = getZoneKey(el)
        if (el.isTurbo) {
            return turboPulseState[zoneKey] == true
        }
        if (el.isInstantTap) {
            return instantTapPulseState[zoneKey] == true
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

    private fun startInstantTap(zoneKey: String, durationMs: Int) {
        stopInstantTap(zoneKey)
        val safeDuration = durationMs.coerceIn(30, 500).toLong()

        instantTapPulseState[zoneKey] = true
        setButtonState(zoneKey, true)
        emitState()
        invalidate()

        val runnable = Runnable {
            instantTapPulseState[zoneKey] = false
            setButtonState(zoneKey, false)
            emitState()
            invalidate()
            activeInstantTapRunnables.remove(zoneKey)
        }
        activeInstantTapRunnables[zoneKey] = runnable
        instantTapHandler.postDelayed(runnable, safeDuration)
    }

    private fun stopInstantTap(zoneKey: String) {
        activeInstantTapRunnables.remove(zoneKey)?.let { instantTapHandler.removeCallbacks(it) }
        instantTapPulseState[zoneKey] = false
        setButtonState(zoneKey, false)
        emitState()
        invalidate()
    }

    fun stopAllInstantTap() {
        activeInstantTapRunnables.values.forEach { instantTapHandler.removeCallbacks(it) }
        activeInstantTapRunnables.clear()
        instantTapPulseState.clear()
    }

    private fun drawEditGearBadge(canvas: Canvas, el: HudElement, cx: Float, cy: Float, W: Float, H: Float) {
        val (badgeX, badgeY) = getGearBadgeCenter(el, cx, cy, W, H)
        val badgeR = getGearBadgeRadius(el, H)
        canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBgPaint)
        canvas.drawCircle(badgeX, badgeY, badgeR, gearBadgeBorderPaint)
        gearBadgeIconPaint.textSize = badgeR * 1.25f
        val fm = gearBadgeIconPaint.fontMetrics
        val baseline = badgeY - (fm.ascent + fm.descent) / 2f
        canvas.drawText("⚙", badgeX, baseline, gearBadgeIconPaint)
    }

    private fun getButtonRadius(el: HudElement, H: Float): Float = H * 0.075f * el.scale

    private fun getButtonSquare(el: HudElement, cx: Float, cy: Float, H: Float): RectF {
        val halfSide = H * 0.075f * el.scale
        return RectF(cx - halfSide, cy - halfSide, cx + halfSide, cy + halfSide)
    }

    private fun getButtonRect(el: HudElement, cx: Float, cy: Float, W: Float, H: Float): RectF {
        val isSystem = el.id in listOf("small_icon", "hamburger_icon")
        val isPedal = el.id in listOf("pedal_gas", "pedal_brake", "wheel_gas", "wheel_brake")
        val halfW = (if (isSystem) W * 0.038f else if (isPedal) W * 0.048f else W * 0.06f) * el.scale
        val halfH = (if (isPedal) H * 0.075f else H * 0.05f) * el.scale
        return RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    fun getGearBadgeRadius(el: HudElement, H: Float): Float {
        return (H * 0.024f * el.scale).coerceIn(15f, 30f)
    }

    fun getGearBadgeCenter(el: HudElement, cx: Float, cy: Float, W: Float, H: Float): Pair<Float, Float> {
        val (localX, localY) = when (el.shape) {
            ButtonShape.CIRCLE -> {
                val r = getButtonRadius(el, H)
                val offset = r * 0.72f
                Pair(cx + offset, cy - offset)
            }
            ButtonShape.SQUARE -> {
                val halfSide = H * 0.075f * el.scale
                val offset = halfSide * 0.82f
                Pair(cx + offset, cy - offset)
            }
            ButtonShape.ROUNDED_RECT -> {
                val rect = getButtonRect(el, cx, cy, W, H)
                Pair(rect.right - 14f * el.scale, rect.top + 14f * el.scale)
            }
        }
        return if (el.rotation != 0f) {
            val rad = Math.toRadians(el.rotation.toDouble())
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()
            val dx = localX - cx
            val dy = localY - cy
            Pair(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
        } else {
            Pair(localX, localY)
        }
    }

    // -------------------------------------------------------------------
    // Touch Hit Testing
    // -------------------------------------------------------------------
    fun findElementAt(x: Float, y: Float): HudElement? {
        if (!isEditMode && !areButtonsVisible) return null
        val W = width.toFloat()
        val H = height.toFloat()
        val sortedList = elements.sortedByDescending { it.zOrder }

        // --- PASS 1: Exact Hit Testing ---
        var exactMatch: HudElement? = null
        for (el in sortedList) {
            if (!isEditMode && !el.isEnabled) continue
            if (!isElementVisibleInCurrentMode(el)) continue
            val cx = el.xPct * W
            val cy = el.yPct * H
            val isExactHit = when (el.type) {
                ElementType.STICK -> {
                    val r = H * 0.16f * el.scale
                    if (el.id == "heli_stick") {
                        hypot(x - cx, y - cy) <= r
                    } else {
                        val notchX = cx
                        val notchY = cy - r * 1.55f
                        val notchR = r * 0.38f
                        val hitMain = hypot(x - cx, y - cy) <= r
                        val hitNotch = stickSprintMode && hypot(x - notchX, y - notchY) <= notchR * 1.6f
                        hitMain || hitNotch
                    }
                }
                ElementType.STEERING_WHEEL -> {
                    val r = H * 0.17f * el.scale
                    hypot((x - cx).toDouble(), (y - cy).toDouble()).toFloat() <= r * 1.35f
                }
                ElementType.DPAD -> {
                    val r = H * 0.14f * el.scale
                    hypot(x - cx, y - cy) <= r
                }
                ElementType.SCROLL_WHEEL -> {
                    val rect = getScrollWheelRect(el, cx, cy, W, H)
                    rect.contains(x, y)
                }
                ElementType.BUTTON -> {
                    val (testX, testY) = if (el.rotation != 0f) {
                        val rad = Math.toRadians(-el.rotation.toDouble())
                        val cos = Math.cos(rad).toFloat()
                        val sin = Math.sin(rad).toFloat()
                        val dx = x - cx
                        val dy = y - cy
                        Pair(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
                    } else {
                        Pair(x, y)
                    }
                    when (el.shape) {
                        ButtonShape.CIRCLE -> {
                            val r = getButtonRadius(el, H)
                            hypot(testX - cx, testY - cy) <= r
                        }
                        ButtonShape.SQUARE -> {
                            val rect = getButtonSquare(el, cx, cy, H)
                            rect.contains(testX, testY)
                        }
                        ButtonShape.ROUNDED_RECT -> {
                            val rect = getButtonRect(el, cx, cy, W, H)
                            rect.contains(testX, testY)
                        }
                    }
                }
            }
            if (isExactHit) {
                if (isEditMode) return el
                val isHeld = pointerZone.values.contains(getZoneKey(el))
                if (!isHeld) {
                    // Multi-Touch De-confliction: Prioritize unheld button so adjacent simultaneous touches never fight
                    return el
                } else if (exactMatch == null) {
                    exactMatch = el
                }
            }
        }
        if (exactMatch != null) return exactMatch

        // In Edit Mode, only exact hits count (so dragging and positioning elements is pixel-precise)
        if (isEditMode) return null

        // --- PASS 2: Proximity Hit Testing (Nearby Touch Detection Area & Joystick Outer Catchment Zone) ---
        var bestElement: HudElement? = null
        var bestDistance = Float.MAX_VALUE

        for (el in sortedList) {
            if (!isEditMode && !el.isEnabled) continue
            if (!isElementVisibleInCurrentMode(el)) continue
            val cx = el.xPct * W
            val cy = el.yPct * H
            when (el.type) {
                ElementType.BUTTON -> {
                    // When Touch Optimization is enabled, ensure a minimum 35% proximity margin
                    val effectivePadding = if (isTouchOptimizationEnabled) {
                        el.touchPadding.coerceAtLeast(1.35f)
                    } else {
                        el.touchPadding
                    }
                    if (effectivePadding > 1.0f) {
                        val (testX, testY) = if (el.rotation != 0f) {
                            val rad = Math.toRadians(-el.rotation.toDouble())
                            val cos = Math.cos(rad).toFloat()
                            val sin = Math.sin(rad).toFloat()
                            val dx = x - cx
                            val dy = y - cy
                            Pair(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
                        } else {
                            Pair(x, y)
                        }
                        val r = getButtonRadius(el, H)
                        val expandedR = r * effectivePadding
                        val dist = hypot(testX - cx, testY - cy)
                        if (dist <= expandedR) {
                            val isHeld = pointerZone.values.contains(getZoneKey(el))
                            // Prioritize unheld elements so simultaneous multi-finger touches don't duplicate
                            val priorityDist = (dist - r) + (if (isHeld) 500f else 0f)
                            if (priorityDist < bestDistance) {
                                bestDistance = priorityDist
                                bestElement = el
                            }
                        }
                    }
                }
                ElementType.STICK -> {
                    if (stickTouchScale > 1.0f) {
                        val r = H * 0.16f * el.scale
                        val expandedR = r * stickTouchScale
                        val dist = hypot(x - cx, y - cy)
                        if (dist <= expandedR) {
                            val distFromEdge = dist - r
                            if (distFromEdge < bestDistance) {
                                bestDistance = distFromEdge
                                bestElement = el
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        return bestElement
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

    private fun isPointInsideButton(el: HudElement, x: Float, y: Float, paddingMultiplier: Float = 1.0f): Boolean {
        val W = width.toFloat()
        val H = height.toFloat()
        val cx = el.xPct * W
        val cy = el.yPct * H
        val (testX, testY) = if (el.rotation != 0f) {
            val rad = Math.toRadians(-el.rotation.toDouble())
            val cos = Math.cos(rad).toFloat()
            val sin = Math.sin(rad).toFloat()
            val dx = x - cx
            val dy = y - cy
            Pair(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
        } else {
            Pair(x, y)
        }
        val effPadding = kotlin.math.max(el.touchPadding, 1.0f) * paddingMultiplier
        return when (el.shape) {
            ButtonShape.CIRCLE -> {
                val r = getButtonRadius(el, H) * effPadding
                hypot((testX - cx).toDouble(), (testY - cy).toDouble()).toFloat() <= r
            }
            ButtonShape.SQUARE -> {
                val baseRect = getButtonSquare(el, cx, cy, H)
                if (effPadding > 1.0f) {
                    val expandX = (baseRect.width() * (effPadding - 1.0f)) / 2f
                    val expandY = (baseRect.height() * (effPadding - 1.0f)) / 2f
                    val rect = RectF(baseRect.left - expandX, baseRect.top - expandY, baseRect.right + expandX, baseRect.bottom + expandY)
                    rect.contains(testX, testY)
                } else {
                    baseRect.contains(testX, testY)
                }
            }
            ButtonShape.ROUNDED_RECT -> {
                val baseRect = getButtonRect(el, cx, cy, W, H)
                if (effPadding > 1.0f) {
                    val expandX = (baseRect.width() * (effPadding - 1.0f)) / 2f
                    val expandY = (baseRect.height() * (effPadding - 1.0f)) / 2f
                    val rect = RectF(baseRect.left - expandX, baseRect.top - expandY, baseRect.right + expandX, baseRect.bottom + expandY)
                    rect.contains(testX, testY)
                } else {
                    baseRect.contains(testX, testY)
                }
            }
        }
    }

    // -------------------------------------------------------------------
    // Touch Events Handling
    // -------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isAdjustingViewport) {
            return onViewportTouch?.invoke(event) ?: false
        }
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

                val W = width.toFloat()
                val H = height.toFloat()

                // Check if user tapped the gear icon badge on any STICK element
                val stickGearTarget = elements.filter { it.type == ElementType.STICK }
                    .firstOrNull { stickEl ->
                        val stickCx = stickEl.xPct * W
                        val stickCy = stickEl.yPct * H
                        val stickR = H * 0.16f * stickEl.scale
                        val sBadgeR = (stickR * 0.22f).coerceIn(15f, 30f)
                        val sBadgeX = stickCx + stickR * 0.72f
                        val sBadgeY = stickCy + stickR * 0.72f
                        val sTouchRadius = (sBadgeR * 1.5f).coerceAtLeast(32f)
                        hypot(event.x - sBadgeX, event.y - sBadgeY) <= sTouchRadius
                    }
                if (stickGearTarget != null) {
                    selectedElement = stickGearTarget
                    onElementSelected?.invoke(stickGearTarget)
                    invalidate()
                    onOpenStickSettingsRequested?.invoke(stickGearTarget)
                    return true
                }

                // Check if user tapped the gear icon badge on any BUTTON element
                val gearTarget = elements.filter { it.type == ElementType.BUTTON }
                    .sortedByDescending { it.zOrder }
                    .firstOrNull { btnEl ->
                        val btnCx = btnEl.xPct * W
                        val btnCy = btnEl.yPct * H
                        val (gx, gy) = getGearBadgeCenter(btnEl, btnCx, btnCy, W, H)
                        val gr = getGearBadgeRadius(btnEl, H)
                        val touchRadius = (gr * 1.5f).coerceAtLeast(32f)
                        hypot(event.x - gx, event.y - gy) <= touchRadius
                    }

                if (gearTarget != null) {
                    selectedElement = gearTarget
                    onElementSelected?.invoke(gearTarget)
                    invalidate()
                    onOpenKeySettingsRequested?.invoke(gearTarget)
                    return true
                }

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
                val historySize = event.historySize
                val pointerCount = event.pointerCount
                // 1. Process all intermediate micro-movements captured by the hardware digitizer between VSYNC frames
                for (h in 0 until historySize) {
                    for (p in 0 until pointerCount) {
                        handlePointerMove(event.getPointerId(p), event.getHistoricalX(p, h), event.getHistoricalY(p, h))
                    }
                }
                // 2. Process the latest current position for the frame
                for (p in 0 until pointerCount) {
                    handlePointerMove(event.getPointerId(p), event.getX(p), event.getY(p))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                handlePointerUp(event.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerZone.clear()
                buttonPointerLastX.clear()
                buttonPointerLastY.clear()
                buttonTouchStart.clear()
                buttonTouchCurrent.clear()
                stopAllTurbo()
                stopAllInstantTap()
                isAutoRunLocked = false
                isStickInLockNotch = false
                autoShiftActive = false
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
                resetHeliStick()
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
            if (el.id == "steer_mode") {
                steerModePointerId = id
                steerModeDownX = x
                steerModeDownY = y
                steerModeCurrentX = x
                steerModeCurrentY = y
                steerModeHoveredOption = null
                isRadialSelectorOpen = false
                steerModeLongPressRunnable?.let { steerModeLongPressHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (steerModePointerId == id) {
                        isRadialSelectorOpen = true
                        hapticHelper.heavyClick()
                        invalidate()
                    }
                }
                steerModeLongPressRunnable = runnable
                steerModeLongPressHandler.postDelayed(runnable, 450L)
                pointerZone[id] = "steer_mode"
                hapticHelper.click()
                return
            }

            when (el.type) {
                ElementType.STEERING_WHEEL -> {
                    wheelPointerId = id
                    wheelSpringAnimator?.cancel()
                    wheelPrevTouchX = x
                    wheelPrevTouchY = y
                    pointerZone[id] = "steering_wheel"
                    hapticHelper.click()
                }
                ElementType.STICK -> {
                    if (el.id == "heli_stick") {
                        heliStickPointerId = id
                        pointerZone[id] = "heli_stick"
                        updateHeliStick(el, x, y)
                        return
                    }
                    if (stickSprintMode && isAutoRunLocked) {
                        // Tapping stick cancels auto-run lock!
                        resetSprint()
                        hapticHelper.click()
                        return
                    }
                    if (pointerZone.values.contains("stick")) {
                        return
                    }
                    if (stickFloatingMode && !isEditMode) {
                        dynamicStickOriginX = x
                        dynamicStickOriginY = y
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
                    wheelDownY = y
                    wheelDownTime = System.currentTimeMillis()
                    wheelScrolledSteps = 0
                    wheelAccumDy = 0f
                    hapticHelper.click()
                }
                ElementType.BUTTON -> {
                    val isHeavy = el.id in listOf("lt", "rt") || el.key in listOf("mouse_left", "mouse_right")
                    val zoneKey = getZoneKey(el)
                    pointerZone[id] = zoneKey
                    buttonTouchStart[zoneKey] = Pair(x, y)
                    buttonTouchCurrent[zoneKey] = Pair(x, y)

                    if (el.swipeToAim && el.xPct >= 0.50f) {
                        buttonPointerLastX[id] = x
                        buttonPointerLastY[id] = y
                    }

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
                    } else if (el.isInstantTap) {
                        if (isHeavy) hapticHelper.heavyClick() else hapticHelper.click()
                        startInstantTap(zoneKey, el.instantTapDurationMs)
                    } else {
                        if (isHeavy) hapticHelper.heavyClick() else hapticHelper.click()
                        setButtonState(zoneKey, true)
                    }
                }
            }
        } else {
            // Check if touch is in left dark box empty area (left 50%) and stickFloatingMode is active!
            if (!isEditMode && areButtonsVisible && currentSteeringMode == SteeringMode.OFF && stickFloatingMode && x < width * 0.50f && !pointerZone.values.contains("stick")) {
                val stickEl = elements.firstOrNull { it.type == ElementType.STICK }
                if (stickEl != null) {
                    dynamicStickOriginX = x
                    dynamicStickOriginY = y
                    pointerZone[id] = "stick"
                    hapticHelper.tick()
                    updateStick(stickEl, x, y)
                    emitState()
                    invalidate()
                    return
                }
            }
            // Swipe look zone fallthrough (right 50% of screen)
            if (lookPointerId == null && x >= width * 0.50f) {
                lookPointerId = id
                pointerZone[id] = "look"
                lastLookX = x
                lastLookY = y
            }
        }
    }

    private fun handlePointerMove(id: Int, x: Float, y: Float) {
        when (pointerZone[id]) {
            "steer_mode" -> {
                steerModeCurrentX = x
                steerModeCurrentY = y
                if (isRadialSelectorOpen) {
                    val steerEl = elements.firstOrNull { it.id == "steer_mode" }
                    if (steerEl != null) {
                        updateRadialHover(steerEl, x, y)
                    }
                }
            }
            "steering_wheel" -> {
                val wheelEl = elements.firstOrNull { it.type == ElementType.STEERING_WHEEL }
                if (wheelEl != null) {
                    updateSteeringWheel(wheelEl, x, y)
                }
            }
            "stick" -> {
                val stickEl = elements.firstOrNull { it.type == ElementType.STICK }
                stickEl?.let { updateStick(it, x, y) }
            }
            "heli_stick" -> {
                val heliEl = elements.firstOrNull { it.id == "heli_stick" }
                heliEl?.let { updateHeliStick(it, x, y) }
            }
            "dpad" -> {
                val dpadEl = elements.firstOrNull { it.type == ElementType.DPAD }
                dpadEl?.let { updateDpad(it, x, y) }
            }
            "scroll_wheel" -> {
                val dy = y - lastWheelY
                lastWheelY = y
                wheelAccumDy += dy
                val stepThreshold = 18f
                while (wheelAccumDy <= -stepThreshold) {
                    onWheelScroll?.invoke(120)
                    hapticHelper.tick()
                    wheelScrolledSteps++
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
                    wheelScrolledSteps++
                    wheelAccumDy -= stepThreshold
                    if (isRecordingMacro) {
                        val now = System.currentTimeMillis()
                        onMacroEventRecorded?.invoke("wheel_down", true, now)
                        onMacroEventRecorded?.invoke("wheel_down", false, now + 30)
                    }
                }
            }
            "look" -> {
                val (pDx, pDy) = processAimDelta((x - lastLookX) * mouseSensitivity, (y - lastLookY) * mouseSensitivity)
                accumDx += pDx
                accumDy += pDy
                lastLookX = x
                lastLookY = y
            }
            else -> {
                // Button Swipe-to-Aim & Visual Drag Tracking
                val zone = pointerZone[id]
                if (zone != null) {
                    val el = elements.firstOrNull { getZoneKey(it) == zone }
                    if (el != null && !el.swipeToAim && !isEditMode) {
                        // For buttons with Swipe-to-Aim OFF (e.g. Reload, Jump, Crouch):
                        // As soon as thumb swipes off the button's area, release click and transition to mouse look!
                        val cushionMultiplier = if (isTouchOptimizationEnabled) 1.85f else 1.15f
                        val isInside = isPointInsideButton(el, x, y, paddingMultiplier = cushionMultiplier)
                        if (!isInside) {
                            if (isRecordingMacro) {
                                val keyName = el.key.ifEmpty { el.id }
                                onMacroEventRecorded?.invoke(keyName, false, System.currentTimeMillis())
                            }
                            if (activeTurboRunnables.containsKey(zone)) {
                                stopTurbo(zone)
                            }
                            if (activeInstantTapRunnables.containsKey(zone)) {
                                stopInstantTap(zone)
                            }
                            if (!el.isToggle || !latchedButtons.contains(zone)) {
                                val otherPointersHolding = pointerZone.filter { it.key != id && it.value == zone }
                                if (otherPointersHolding.isEmpty()) {
                                    setButtonState(zone, false)
                                }
                            }
                            buttonTouchStart.remove(zone)
                            buttonTouchCurrent.remove(zone)
                            buttonPointerLastX.remove(id)
                            buttonPointerLastY.remove(id)

                            // Handover logic (50/50 clean split down the middle):
                            // 1. If on RIGHT side (x >= 50% width and el.xPct >= 50%), handover to mouse look
                            // 2. If on LEFT side (x < 50% width) and stickFloatingMode is ON, handover to floating joystick!
                            if (el.xPct >= 0.50f && x >= width * 0.50f && lookPointerId == null) {
                                pointerZone[id] = "look"
                                lookPointerId = id
                                lastLookX = x
                                lastLookY = y
                            } else if (stickFloatingMode && currentSteeringMode == SteeringMode.OFF && x < width * 0.50f && !pointerZone.values.contains("stick")) {
                                val stickEl = elements.firstOrNull { it.type == ElementType.STICK }
                                if (stickEl != null) {
                                    dynamicStickOriginX = x
                                    dynamicStickOriginY = y
                                    pointerZone[id] = "stick"
                                    hapticHelper.tick()
                                    updateStick(stickEl, x, y)
                                } else {
                                    pointerZone.remove(id)
                                }
                            } else {
                                pointerZone.remove(id)
                            }
                            emitState()
                            invalidate()
                            return
                        }
                    }

                    buttonTouchCurrent[zone] = Pair(x, y)
                }
                val lastX = buttonPointerLastX[id]
                val lastY = buttonPointerLastY[id]
                if (lastX != null && lastY != null) {
                    val (pDx, pDy) = processAimDelta((x - lastX) * mouseSensitivity, (y - lastY) * mouseSensitivity)
                    accumDx += pDx
                    accumDy += pDy
                    buttonPointerLastX[id] = x
                    buttonPointerLastY[id] = y
                }
            }
        }
    }

    private fun handlePointerUp(id: Int) {
        val zone = pointerZone[id]
        when (zone) {
            "steer_mode" -> {
                steerModeLongPressRunnable?.let { steerModeLongPressHandler.removeCallbacks(it) }
                if (isRadialSelectorOpen) {
                    isRadialSelectorOpen = false
                    val chosenMode = steerModeHoveredOption
                    if (chosenMode != null) {
                        currentSteeringMode = chosenMode
                        HudConfig.setSteeringMode(context, currentSteeringMode)
                        if (chosenMode != SteeringMode.OFF) {
                            lastActiveSteeringMode = chosenMode
                        }
                        hapticHelper.heavyClick()
                    }
                } else {
                    // Quick tap (<450ms): toggle between OFF and lastActiveSteeringMode
                    val newMode = if (currentSteeringMode == SteeringMode.OFF) lastActiveSteeringMode else SteeringMode.OFF
                    currentSteeringMode = newMode
                    HudConfig.setSteeringMode(context, currentSteeringMode)
                    hapticHelper.click()
                }
                steerModePointerId = null
                steerModeHoveredOption = null
                pointerZone.remove(id)
                invalidate()
            }
            "steering_wheel" -> {
                wheelPointerId = null
                pointerZone.remove(id)
                if (wheelSteeringLeft) {
                    wheelSteeringLeft = false
                    onMacroKeyRequested?.invoke("a", false)
                }
                if (wheelSteeringRight) {
                    wheelSteeringRight = false
                    onMacroKeyRequested?.invoke("d", false)
                }
                state = state.copy(stickX = 0f)
                animateWheelReturn()
            }
            "heli_stick" -> {
                heliStickPointerId = null
                pointerZone.remove(id)
                resetHeliStick()
            }
            "stick" -> {
                dynamicStickOriginX = null
                dynamicStickOriginY = null
                if (stickSprintMode && isStickInLockNotch) {
                    // Lock auto-run forward!
                    isAutoRunLocked = true
                    isStickInLockNotch = false
                    visualStickX = 0f
                    visualStickY = -1.0f
                    state = state.copy(stickX = 0f, stickY = -1.0f)
                    setSprintActive(true)
                    hapticHelper.heavyClick()
                } else {
                    isAutoRunLocked = false
                    if (autoShiftActive) setSprintActive(false)
                    visualStickX = 0f
                    visualStickY = 0f
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
                val elapsed = System.currentTimeMillis() - wheelDownTime
                val movement = kotlin.math.abs(lastWheelY - wheelDownY)
                if (wheelScrolledSteps == 0 && elapsed < 350L && movement < 32f) {
                    // Tap on Scroll Wheel = Middle Mouse Button (MMB / Ping) Click!
                    hapticHelper.heavyClick()
                    onMiddleClick?.invoke(true)
                    postDelayed({
                        onMiddleClick?.invoke(false)
                    }, 60L)
                    if (isRecordingMacro) {
                        val now = System.currentTimeMillis()
                        onMacroEventRecorded?.invoke("mouse_middle", true, now)
                        onMacroEventRecorded?.invoke("mouse_middle", false, now + 60)
                    }
                }
            }
            "look" -> {
                lookPointerId = null
                // Subpixel Carry-Forward: Do NOT clear accumDx and accumDy when lifting the finger.
                // Keeping the fractional remainder preserves fine aiming precision across discrete swipes/taps.
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
                    } else if (activeInstantTapRunnables.containsKey(zone)) {
                        stopInstantTap(zone)
                    } else if (latchedButtons.contains(zone)) {
                        // Latched in Toggle mode - stay ON!
                        setButtonState(zone, true)
                    } else {
                        // Multi-Touch Reference Counting: only release button if no OTHER active finger is still holding this button!
                        val otherPointersHolding = pointerZone.filter { it.key != id && it.value == zone }
                        if (otherPointersHolding.isEmpty()) {
                            setButtonState(zone, false)
                        }
                    }
                }
            }
        }
        if (zone != null) {
            val otherPointersHolding = pointerZone.filter { it.key != id && it.value == zone }
            if (otherPointersHolding.isEmpty()) {
                buttonTouchStart.remove(zone)
                buttonTouchCurrent.remove(zone)
            }
        }
        buttonPointerLastX.remove(id)
        buttonPointerLastY.remove(id)
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
        val cx = if (stickFloatingMode && !isEditMode && dynamicStickOriginX != null) dynamicStickOriginX!! else el.xPct * width
        val cy = if (stickFloatingMode && !isEditMode && dynamicStickOriginY != null) dynamicStickOriginY!! else el.yPct * height
        val r = height * 0.16f * el.scale

        val notchX = cx
        val notchY = cy - r * 1.55f
        val notchR = r * 0.38f

        // Check if finger dragged into Auto-Run Lock Notch (Sprint Mode only)
        if (stickSprintMode) {
            val distToNotch = hypot((x - notchX).toDouble(), (y - notchY).toDouble()).toFloat()
            if (distToNotch <= notchR * 1.6f) {
                if (!isStickInLockNotch) {
                    isStickInLockNotch = true
                    hapticHelper.edgeBump()
                }
                visualStickX = 0f
                visualStickY = -1.0f
                state = state.copy(stickX = 0f, stickY = -1.0f)
                setSprintActive(true)
                return
            }
        }
        isStickInLockNotch = false

        val dx = (x - cx) / r
        val dy = (y - cy) / r
        val mag = min(1f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
        val targetStickX = (Math.cos(angle) * mag).toFloat()
        val targetStickY = (Math.sin(angle) * mag).toFloat()

        visualStickX = targetStickX
        visualStickY = targetStickY

        // In Normal Mode (Walk Mode): strictly 8-direction WASD with NO shift involved.
        // Clamp sent stickY to -0.75f so PC server's hardcoded sprint threshold (-0.82f) is never crossed.
        val sentStickY = if (!stickSprintMode && targetStickY < -0.75f) -0.75f else targetStickY

        state = state.copy(
            stickX = targetStickX,
            stickY = sentStickY
        )

        // Dynamic auto-sprint on forward tilt (> 80% mag and stickY < -0.55f) — Sprint Mode only
        if (stickSprintMode) {
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
        } else {
            if (autoShiftActive) {
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

    /**
     * Subpixel mouse delta accumulation with carry-forward:
     * - Windows and Interception mouse events only accept integer deltas (counts / mickeys).
     * - Truncating (e.g. via .toInt()) without carry-forward would discard fractional micro-movements
     *   (such as 0.4px per touch sample), creating a deadzone / "sticky" feel during fine aiming adjustments.
     * - Here, we extract the integer portion (sendDx, sendDy) to transmit immediately over the wire,
     *   and subtract that exact integer from accumDx / accumDy.
     * - The fractional remainder (e.g. 0.4f) remains in accumDx / accumDy and is added into the next
     *   incoming touch delta. Once accumulated fractional movements cross 1.0 (or -1.0), the next integer
     *   step is immediately emitted.
     * - This guarantees zero drift over time: sum(sendDx) + accumDx == sum(all historical touch deltas).
     */
    private fun emitState() {
        val sendDx = accumDx.toInt()
        val sendDy = accumDy.toInt()
        accumDx -= sendDx.toFloat()
        accumDy -= sendDy.toFloat()
        val out = state.copy(mouseDx = sendDx, mouseDy = sendDy)
        onStateChanged?.invoke(out)
    }

    /**
     * Resets and flushes all active touch pointers, button hold states, and buffers.
     * Called during Touch Optimization to ensure a completely clean input state.
     */
    fun resetTouchPointers() {
        steerModeLongPressRunnable?.let { steerModeLongPressHandler.removeCallbacks(it) }
        isRadialSelectorOpen = false
        steerModePointerId = null
        steerModeHoveredOption = null
        wheelPointerId = null
        visualWheelAngle = 0f
        if (wheelSteeringLeft) {
            wheelSteeringLeft = false
            onMacroKeyRequested?.invoke("a", false)
        }
        if (wheelSteeringRight) {
            wheelSteeringRight = false
            onMacroKeyRequested?.invoke("d", false)
        }
        pointerZone.clear()
        buttonPointerLastX.clear()
        buttonPointerLastY.clear()
        buttonTouchStart.clear()
        buttonTouchCurrent.clear()
        stopAllTurbo()
        stopAllInstantTap()
        isAutoRunLocked = false
        isStickInLockNotch = false
        autoShiftActive = false
        lookPointerId = null
        accumDx = 0f
        accumDy = 0f
        state = ControllerState()
        emitState()
        invalidate()
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
            if (newKey in listOf("mouse_left", "mouse_right")) {
                it.swipeToAim = true
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

    fun updateSelectedRotation(angleDeg: Float) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.rotation = ((angleDeg % 360f) + 360f) % 360f
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun updateSelectedLabel(newLabel: String) {
        selectedElement?.let {
            it.label = newLabel
            invalidate()
            onLayoutChanged?.invoke()
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
        elements.remove(current)
        selectedElement = null
        onElementSelected?.invoke(null)
        onLayoutChanged?.invoke()
        invalidate()
        return true
    }

    fun toggleSelectedElementEnabled(): Boolean {
        val current = selectedElement ?: return false
        current.isEnabled = !current.isEnabled
        onElementSelected?.invoke(current)
        onLayoutChanged?.invoke()
        invalidate()
        return current.isEnabled
    }

    fun setElementEnabled(element: HudElement, enabled: Boolean) {
        element.isEnabled = enabled
        onElementSelected?.invoke(selectedElement)
        onLayoutChanged?.invoke()
        invalidate()
    }

    fun restoreStockElement(el: HudElement): Boolean {
        if (elements.any { it.id == el.id }) return false
        val maxZ = (elements.maxOfOrNull { it.zOrder } ?: 20) + 1
        val restored = el.copy(zOrder = maxZ, isEnabled = true)
        elements.add(restored)
        selectedElement = restored
        onElementSelected?.invoke(restored)
        onLayoutChanged?.invoke()
        invalidate()
        return true
    }

    fun updateSelectedButtonMode(
        isToggle: Boolean,
        isTurbo: Boolean,
        turboCps: Int? = null,
        isInstantTap: Boolean = false,
        instantTapDurationMs: Int? = null
    ) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.isToggle = isToggle
                it.isTurbo = isTurbo
                if (turboCps != null) {
                    it.turboCps = turboCps
                }
                it.isInstantTap = isInstantTap
                if (instantTapDurationMs != null) {
                    it.instantTapDurationMs = instantTapDurationMs
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

    fun updateSelectedSwipeToAim(enabled: Boolean) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.swipeToAim = enabled
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun updateSelectedTouchPadding(newPadding: Float) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.touchPadding = newPadding.coerceIn(1.0f, 2.5f)
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun updateSelectedGhostShadow(enabled: Boolean) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.showGhostShadow = enabled
                it.userExplicitGhostShadow = true
                invalidate()
                onLayoutChanged?.invoke()
            }
        }
    }

    fun updateSelectedMaxDragDistance(distance: Float) {
        selectedElement?.let {
            if (it.type == ElementType.BUTTON) {
                it.maxDragDistance = distance.coerceIn(0.0f, 6.0f)
                it.userExplicitDragDist = true
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

    private val steeringButtonIds = setOf(
        "pedal_gas", "pedal_brake", "wheel_gas", "wheel_brake",
        "steer_left", "steer_right", "steer_handbrake"
    )

    private fun requestMacroKey(key: String, pressed: Boolean) {
        onMacroKeyRequested?.invoke(key, pressed)

        val matchingEl = elements.firstOrNull {
            it.type == ElementType.BUTTON &&
            it.key.equals(key, ignoreCase = true) &&
            it.id !in steeringButtonIds
        }
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
        stopAllInstantTap()
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
        stopAllInstantTap()
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

    fun updateSelectedOpacity(opacity: Float) {
        selectedElement?.opacity = opacity
        invalidate()
    }

    fun resetHeliStick() {
        visualHeliStickX = 0f
        visualHeliStickY = 0f
        if (heli8Active) { heli8Active = false; onMacroKeyRequested?.invoke("8", false) }
        if (heli5Active) { heli5Active = false; onMacroKeyRequested?.invoke("5", false) }
        if (heli4Active) { heli4Active = false; onMacroKeyRequested?.invoke("4", false) }
        if (heli6Active) { heli6Active = false; onMacroKeyRequested?.invoke("6", false) }
        invalidate()
    }

    fun updateHeliStickDirectionKey(direction: String, key: String) {
        val heliEl = elements.firstOrNull { it.id == "heli_stick" } ?: return
        when (direction) {
            "up" -> heliEl.dpadUpKey = key
            "down" -> heliEl.dpadDownKey = key
            "left" -> heliEl.dpadLeftKey = key
            "right" -> heliEl.dpadRightKey = key
        }
        invalidate()
    }

    fun updateHeliStick(el: HudElement, x: Float, y: Float) {
        val cx = el.xPct * width
        val cy = el.yPct * height
        val r = height * 0.16f * el.scale
        val dx = (x - cx) / r
        val dy = (y - cy) / r
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val normX = if (dist > 1f) dx / dist else dx
        val normY = if (dist > 1f) dy / dist else dy
        visualHeliStickX = normX
        visualHeliStickY = normY

        val upKey = el.dpadUpKey.ifEmpty { "8" }
        val downKey = el.dpadDownKey.ifEmpty { "5" }
        val leftKey = el.dpadLeftKey.ifEmpty { "4" }
        val rightKey = el.dpadRightKey.ifEmpty { "6" }

        val needUp = normY < -0.35f
        val needDown = normY > 0.35f
        val needLeft = normX < -0.35f
        val needRight = normX > 0.35f

        if (needUp != heli8Active) { heli8Active = needUp; onMacroKeyRequested?.invoke(upKey, needUp) }
        if (needDown != heli5Active) { heli5Active = needDown; onMacroKeyRequested?.invoke(downKey, needDown) }
        if (needLeft != heli4Active) { heli4Active = needLeft; onMacroKeyRequested?.invoke(leftKey, needLeft) }
        if (needRight != heli6Active) { heli6Active = needRight; onMacroKeyRequested?.invoke(rightKey, needRight) }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllTurbo()
        stopAllInstantTap()
    }
}
