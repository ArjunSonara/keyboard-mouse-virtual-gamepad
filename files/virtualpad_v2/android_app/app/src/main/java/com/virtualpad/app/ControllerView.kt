package com.virtualpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Full-screen virtual pad, visually identical to a standard Xbox-style
 * layout, but every element sends a keyboard key or mouse movement - see
 * the label on each button for exactly which key it sends.
 *
 * Every touch event calls onStateChanged immediately (event-driven, no
 * polling), so NetworkClient can fire a packet the instant something
 * changes for the lowest possible latency.
 */
class ControllerView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var onStateChanged: ((ControllerState) -> Unit)? = null

    // --- paints ---
    private val outlinePaint = Paint().apply {
        color = Color.parseColor("#3A8FB7"); style = Paint.Style.STROKE
        strokeWidth = 4f; isAntiAlias = true
    }
    private val fillPaint = Paint().apply { color = Color.parseColor("#1E5F7A"); isAntiAlias = true }
    private val fillActivePaint = Paint().apply { color = Color.parseColor("#4FC3F7"); isAntiAlias = true }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 30f; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val swipeHintPaint = Paint().apply { color = Color.parseColor("#223A5580") }

    private data class Circle(val cx: Float, val cy: Float, val r: Float)

    // zones (all touch-hit-testing regions)
    private lateinit var leftStickZone: Circle
    private lateinit var dpadZone: Circle
    private lateinit var lbZone: RectF
    private lateinit var ltZone: RectF
    private lateinit var rbZone: RectF
    private lateinit var rtZone: RectF
    private lateinit var aZone: Circle
    private lateinit var bZone: Circle
    private lateinit var xZone: Circle
    private lateinit var yZone: Circle
    private lateinit var smallIconZone: RectF
    private lateinit var hamburgerZone: RectF
    private var layoutReady = false

    private var state = ControllerState()

    // pointerId -> zone key currently driven by that finger
    private val pointerZone = HashMap<Int, String>()
    private var leftStickCenter = 0f to 0f
    private var lookPointerId: Int? = null
    private var lastLookX = 0f
    private var lastLookY = 0f
    private var accumDx = 0f
    private var accumDy = 0f
    var mouseSensitivity = 1.75f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val W = w.toFloat(); val H = h.toFloat()

        leftStickZone = Circle(W * 0.13f, H * 0.62f, H * 0.16f)
        dpadZone = Circle(W * 0.32f, H * 0.72f, H * 0.14f)

        lbZone = RectF(W * 0.04f, H * 0.06f, W * 0.16f, H * 0.16f)
        ltZone = RectF(W * 0.18f, H * 0.02f, W * 0.28f, H * 0.20f)
        rbZone = RectF(W * 0.84f, H * 0.06f, W * 0.96f, H * 0.16f)
        rtZone = RectF(W * 0.72f, H * 0.02f, W * 0.82f, H * 0.20f)

        val abxyCx = W * 0.87f; val abxyCy = H * 0.60f
        val abxyR = H * 0.075f; val abxySpread = H * 0.11f
        yZone = Circle(abxyCx, abxyCy - abxySpread, abxyR)
        aZone = Circle(abxyCx, abxyCy + abxySpread, abxyR)
        xZone = Circle(abxyCx - abxySpread, abxyCy, abxyR)
        bZone = Circle(abxyCx + abxySpread, abxyCy, abxyR)

        smallIconZone = RectF(W * 0.42f, H * 0.88f, W * 0.49f, H * 0.98f)
        hamburgerZone = RectF(W * 0.51f, H * 0.88f, W * 0.58f, H * 0.98f)

        layoutReady = true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0A0E1A"))
        if (!layoutReady) return

        canvas.drawRect(width * 0.35f, 0f, width.toFloat(), height * 0.85f, swipeHintPaint)

        drawCircleZone(canvas, leftStickZone, false, "")
        drawStick(canvas)
        drawCircleZone(canvas, dpadZone, false, "")
        drawDpadLabels(canvas)

        drawRectZone(canvas, lbZone, "lb", "TAB")
        drawRectZone(canvas, ltZone, "lt", "[")
        drawRectZone(canvas, rbZone, "rb", "Q")
        drawRectZone(canvas, rtZone, "rt", ";")

        drawCircleZone(canvas, yZone, isHeld("y"), "E")
        drawCircleZone(canvas, xZone, isHeld("x"), "SPACE")
        drawCircleZone(canvas, bZone, isHeld("b"), "R")
        drawCircleZone(canvas, aZone, isHeld("a"), "SHIFT")

        drawRectZone(canvas, smallIconZone, "small_icon", "ESC")
        drawRectZone(canvas, hamburgerZone, "hamburger_icon", "B")
    }

    private fun isHeld(zoneKey: String) = pointerZone.values.contains(zoneKey)

    private fun drawCircleZone(canvas: Canvas, c: Circle, active: Boolean, label: String) {
        canvas.drawCircle(c.cx, c.cy, c.r, if (active) fillActivePaint else fillPaint)
        canvas.drawCircle(c.cx, c.cy, c.r, outlinePaint)
        if (label.isNotEmpty()) canvas.drawText(label, c.cx, c.cy + 10f, textPaint)
    }

    private fun drawRectZone(canvas: Canvas, r: RectF, key: String, label: String) {
        val active = isHeld(key)
        canvas.drawRoundRect(r, 16f, 16f, if (active) fillActivePaint else fillPaint)
        canvas.drawRoundRect(r, 16f, 16f, outlinePaint)
        canvas.drawText(label, r.centerX(), r.centerY() + 10f, textPaint)
    }

    private fun drawStick(canvas: Canvas) {
        val cx = leftStickZone.cx + state.stickX * leftStickZone.r * 0.5f
        val cy = leftStickZone.cy + state.stickY * leftStickZone.r * 0.5f
        canvas.drawCircle(cx, cy, leftStickZone.r * 0.45f, fillActivePaint)
    }

    private fun drawDpadLabels(canvas: Canvas) {
        canvas.drawText("H", dpadZone.cx, dpadZone.cy - dpadZone.r * 0.5f, textPaint)
        canvas.drawText("T", dpadZone.cx, dpadZone.cy + dpadZone.r * 0.75f, textPaint)
        canvas.drawText("X", dpadZone.cx - dpadZone.r * 0.6f, dpadZone.cy + 10f, textPaint)
        canvas.drawText("X", dpadZone.cx + dpadZone.r * 0.6f, dpadZone.cy + 10f, textPaint)
    }

    // -------------------------------------------------------------------
    // Touch handling - every branch below calls emitState() immediately,
    // so a packet fires the instant anything changes (event-driven).
    // -------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                state = state.copy(buttons = 0, stickX = 0f, stickY = 0f)
            }
        }
        emitState()
        invalidate()
        return true
    }

    private fun inCircle(c: Circle, x: Float, y: Float) = hypot((x - c.cx).toDouble(), (y - c.cy).toDouble()) <= c.r
    private fun inRect(r: RectF, x: Float, y: Float) = r.contains(x, y)

    private fun handlePointerDown(id: Int, x: Float, y: Float) {
        when {
            inCircle(leftStickZone, x, y) -> {
                pointerZone[id] = "leftstick"
                leftStickCenter = leftStickZone.cx to leftStickZone.cy
                updateLeftStick(x, y)
            }
            inCircle(dpadZone, x, y) -> { pointerZone[id] = "dpad"; updateDpad(x, y) }
            inRect(lbZone, x, y) -> setButton(id, "lb", Btn.LB, true)
            inRect(rbZone, x, y) -> setButton(id, "rb", Btn.RB, true)
            inRect(ltZone, x, y) -> setButton(id, "lt", Btn.LT, true)
            inRect(rtZone, x, y) -> setButton(id, "rt", Btn.RT, true)
            inCircle(yZone, x, y) -> setButton(id, "y", Btn.Y, true)
            inCircle(xZone, x, y) -> setButton(id, "x", Btn.X, true)
            inCircle(bZone, x, y) -> setButton(id, "b", Btn.B, true)
            inCircle(aZone, x, y) -> setButton(id, "a", Btn.A, true)
            inRect(smallIconZone, x, y) -> setButton(id, "small_icon", Btn.SMALL_ICON, true)
            inRect(hamburgerZone, x, y) -> setButton(id, "hamburger_icon", Btn.HAMBURGER_ICON, true)
            else -> {
                if (lookPointerId == null && x >= width * 0.30f) {
                    lookPointerId = id
                    pointerZone[id] = "look"
                    lastLookX = x
                    lastLookY = y
                }
            }
        }
    }

    private fun setButton(id: Int, zoneKey: String, btn: Btn, pressed: Boolean) {
        pointerZone[id] = zoneKey
        state = state.withButton(btn, pressed)
    }

    private fun handlePointerMove(id: Int, x: Float, y: Float) {
        when (pointerZone[id]) {
            "leftstick" -> updateLeftStick(x, y)
            "dpad" -> updateDpad(x, y)
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
            "leftstick" -> state = state.copy(stickX = 0f, stickY = 0f)
            "dpad" -> state = state
                .withButton(Btn.DPAD_UP, false).withButton(Btn.DPAD_DOWN, false)
                .withButton(Btn.DPAD_LEFT, false).withButton(Btn.DPAD_RIGHT, false)
            "look" -> {
                lookPointerId = null
                accumDx = 0f
                accumDy = 0f
            }
            "lb" -> state = state.withButton(Btn.LB, false)
            "rb" -> state = state.withButton(Btn.RB, false)
            "lt" -> state = state.withButton(Btn.LT, false)
            "rt" -> state = state.withButton(Btn.RT, false)
            "y" -> state = state.withButton(Btn.Y, false)
            "x" -> state = state.withButton(Btn.X, false)
            "b" -> state = state.withButton(Btn.B, false)
            "a" -> state = state.withButton(Btn.A, false)
            "small_icon" -> state = state.withButton(Btn.SMALL_ICON, false)
            "hamburger_icon" -> state = state.withButton(Btn.HAMBURGER_ICON, false)
            else -> {}
        }
        pointerZone.remove(id)
    }

    private fun updateLeftStick(x: Float, y: Float) {
        val (cx, cy) = leftStickCenter
        val dx = (x - cx) / leftStickZone.r
        val dy = (y - cy) / leftStickZone.r
        val mag = min(1f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())
        state = state.copy(
            stickX = (Math.cos(angle) * mag).toFloat(),
            stickY = (Math.sin(angle) * mag).toFloat(),
        )
    }

    private fun updateDpad(x: Float, y: Float) {
        val dx = x - dpadZone.cx; val dy = y - dpadZone.cy
        val deadzone = dpadZone.r * 0.25f
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
}
