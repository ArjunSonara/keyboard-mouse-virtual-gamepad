package com.virtualpad.app

import android.content.Context
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceView
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

data class CustomAspectPreset(
    val id: String,
    val name: String,
    val aspectMode: String,
    val scaleX: Float,
    val scaleY: Float,
    val transX: Float,
    val transY: Float
)

enum class AspectRatioMode(val displayName: String) {
    SAFE_FIT("Safe Fit 16:9"),
    FULL_STRETCH("Full Stretch 20:9"),
    CROP_FILL("Zoom & Crop"),
    PRODUCTIVITY("Productivity 16:10"),
    ULTRAWIDE("Cinematic 21:9")
}

/**
 * Manages video surface scaling, panning, aspect ratio modes, and custom presets.
 */
class ViewportManager(
    private val context: Context,
    private val surfaceView: SurfaceView
) {
    var isViewportLocked: Boolean = true
    var currentAspectMode: AspectRatioMode = AspectRatioMode.SAFE_FIT

    var vScaleX = 1.0f
    var vScaleY = 1.0f
    var vTransX = 0f
    var vTransY = 0f

    private var isScaling = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var panLastX = 0f
    private var panLastY = 0f

    var onViewportChanged: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (isViewportLocked) return false
            isScaling = true
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (isViewportLocked) return true
            val factor = detector.scaleFactor
            if (factor.isFinite() && factor > 0.05f && factor < 20.0f) {
                vScaleX = (vScaleX * factor).coerceIn(0.2f, 6.0f)
                vScaleY = (vScaleY * factor).coerceIn(0.2f, 6.0f)

                val focusX = detector.focusX
                val focusY = detector.focusY
                vTransX += (focusX - lastFocusX)
                vTransY += (focusY - lastFocusY)
                lastFocusX = focusX
                lastFocusY = focusY

                applyTransform()
                onViewportChanged?.invoke()
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
            saveViewportPrefs()
        }
    })

    init {
        loadPreferences()
    }

    fun applyAspectLayout(mode: AspectRatioMode) {
        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(screenW, screenH)
        lp.gravity = Gravity.CENTER

        when (mode) {
            AspectRatioMode.SAFE_FIT -> {
                val vertMargin = (16 * dm.density).toInt()
                val usableH = screenH - (vertMargin * 2)
                val targetW = ((usableH.toDouble() * 16.0) / 9.0).toInt()
                lp.width = targetW
                lp.height = usableH
                lp.setMargins(0, 0, 0, 0)
            }
            AspectRatioMode.FULL_STRETCH -> {
                lp.width = screenW
                lp.height = screenH
                lp.setMargins(0, 0, 0, 0)
            }
            AspectRatioMode.CROP_FILL -> {
                lp.width = screenW
                lp.height = ((screenW.toDouble() * 9.0) / 16.0).toInt()
                lp.setMargins(0, 0, 0, 0)
            }
            AspectRatioMode.PRODUCTIVITY -> {
                val vertMargin = (10 * dm.density).toInt()
                val usableH = screenH - (vertMargin * 2)
                val targetW = ((usableH.toDouble() * 16.0) / 10.0).toInt()
                lp.width = targetW
                lp.height = usableH
                lp.setMargins(0, 0, 0, 0)
            }
            AspectRatioMode.ULTRAWIDE -> {
                lp.width = screenW
                val targetH = ((screenW.toDouble() * 9.0) / 21.0).toInt()
                lp.height = targetH
                lp.setMargins(0, 0, 0, 0)
            }
        }
        surfaceView.layoutParams = lp
        currentAspectMode = mode
        saveViewportPrefs()
        onViewportChanged?.invoke()
    }

    fun applyTransform() {
        surfaceView.post {
            surfaceView.scaleX = vScaleX
            surfaceView.scaleY = vScaleY
            surfaceView.translationX = vTransX
            surfaceView.translationY = vTransY
        }
    }

    fun resetViewport() {
        vScaleX = 1.0f
        vScaleY = 1.0f
        vTransX = 0f
        vTransY = 0f
        applyTransform()
        saveViewportPrefs()
        onViewportChanged?.invoke()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (isViewportLocked) return false

        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                panLastX = event.x
                panLastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScaling) {
                    val dx = event.x - panLastX
                    val dy = event.y - panLastY
                    vTransX += dx
                    vTransY += dy
                    applyTransform()
                    onViewportChanged?.invoke()
                }
                panLastX = event.x
                panLastY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveViewportPrefs()
            }
        }
        return true
    }

    fun saveViewportPrefs() {
        val prefs = context.getSharedPreferences("virtualpad_viewport", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("scale_x", vScaleX)
            .putFloat("scale_y", vScaleY)
            .putFloat("trans_x", vTransX)
            .putFloat("trans_y", vTransY)
            .putString("aspect_mode", currentAspectMode.name)
            .apply()
    }

    private fun loadPreferences() {
        val prefs = context.getSharedPreferences("virtualpad_viewport", Context.MODE_PRIVATE)
        vScaleX = prefs.getFloat("scale_x", 1.0f)
        vScaleY = prefs.getFloat("scale_y", 1.0f)
        vTransX = prefs.getFloat("trans_x", 0f)
        vTransY = prefs.getFloat("trans_y", 0f)
        val modeStr = prefs.getString("aspect_mode", AspectRatioMode.SAFE_FIT.name) ?: AspectRatioMode.SAFE_FIT.name
        currentAspectMode = try { AspectRatioMode.valueOf(modeStr) } catch (_: Exception) { AspectRatioMode.SAFE_FIT }
    }

    fun getSavedPresets(): List<CustomAspectPreset> {
        val prefs = context.getSharedPreferences("virtualpad_viewport", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("custom_presets", "[]") ?: "[]"
        val list = mutableListOf<CustomAspectPreset>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CustomAspectPreset(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        name = obj.optString("name", "Preset ${i + 1}"),
                        aspectMode = obj.optString("aspectMode", AspectRatioMode.SAFE_FIT.name),
                        scaleX = obj.optDouble("scaleX", 1.0).toFloat(),
                        scaleY = obj.optDouble("scaleY", 1.0).toFloat(),
                        transX = obj.optDouble("transX", 0.0).toFloat(),
                        transY = obj.optDouble("transY", 0.0).toFloat()
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun savePreset(name: String) {
        val current = getSavedPresets().toMutableList()
        val preset = CustomAspectPreset(
            id = System.currentTimeMillis().toString(),
            name = name,
            aspectMode = currentAspectMode.name,
            scaleX = vScaleX,
            scaleY = vScaleY,
            transX = vTransX,
            transY = vTransY
        )
        current.add(preset)
        persistPresets(current)
    }

    fun applyPreset(preset: CustomAspectPreset) {
        currentAspectMode = try { AspectRatioMode.valueOf(preset.aspectMode) } catch (_: Exception) { AspectRatioMode.SAFE_FIT }
        vScaleX = preset.scaleX
        vScaleY = preset.scaleY
        vTransX = preset.transX
        vTransY = preset.transY
        applyAspectLayout(currentAspectMode)
        applyTransform()
        saveViewportPrefs()
        onViewportChanged?.invoke()
    }

    fun deletePreset(id: String) {
        val current = getSavedPresets().filter { it.id != id }
        persistPresets(current)
    }

    private fun persistPresets(presets: List<CustomAspectPreset>) {
        val arr = JSONArray()
        for (p in presets) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("aspectMode", p.aspectMode)
                put("scaleX", p.scaleX.toDouble())
                put("scaleY", p.scaleY.toDouble())
                put("transX", p.transX.toDouble())
                put("transY", p.transY.toDouble())
            }
            arr.put(obj)
        }
        context.getSharedPreferences("virtualpad_viewport", Context.MODE_PRIVATE)
            .edit().putString("custom_presets", arr.toString()).apply()
    }
}
