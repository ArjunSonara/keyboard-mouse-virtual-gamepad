package com.virtualpad.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import kotlin.math.abs

data class RecordedInputEvent(val key: String, val isDown: Boolean, val timestampMs: Long)

class MainActivity : Activity(), SensorEventListener {

    private lateinit var controllerView: ControllerView
    private lateinit var networkClient: NetworkClient
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var gearButton: Button

    // Edit Mode Overlay UI Elements
    private lateinit var editOverlay: FrameLayout
    private lateinit var bottomInspector: LinearLayout
    private lateinit var inspectorTitle: TextView
    private lateinit var scaleText: TextView
    private lateinit var scaleSeekBar: SeekBar
    private lateinit var bindKeyButton: Button
    private lateinit var shapeButton: Button
    private lateinit var deleteButton: Button
    private lateinit var modeButton: Button
    private lateinit var turboCpsButton: Button
    private lateinit var macroButton: Button
    private lateinit var dpadControlsRow: LinearLayout
    private lateinit var dpadUpBtn: Button
    private lateinit var dpadDownBtn: Button
    private lateinit var dpadLeftBtn: Button
    private lateinit var dpadRightBtn: Button
    private lateinit var buttonControlsRow: LinearLayout
    private lateinit var keySettingsButton: Button

    // Macro Studio & Live Recording
    private var liveRecordingTarget: HudElement? = null
    private val recordedEvents = mutableListOf<RecordedInputEvent>()
    private lateinit var recordingBanner: LinearLayout
    private var recTitleText: TextView? = null

    // Sensor / Gyroscope
    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var gyroActive = false

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().permitAll().build()
            )
        } catch (_: Exception) {}

        window.setFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        prefs = getSharedPreferences("virtualpad", Context.MODE_PRIVATE)
        networkClient = NetworkClient()

        // Initialize Gyroscope
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        gyroActive = HudConfig.isGyroEnabled(this)

        val root = FrameLayout(this)

        // 1. Controller View
        controllerView = ControllerView(this)
        controllerView.hudOpacity = HudConfig.getHudOpacity(this)
        controllerView.onStateChanged = { state -> networkClient.submit(state) }
        controllerView.onWheelScroll = { delta -> networkClient.sendScrollWheel(delta) }
        controllerView.onMiddleClick = { pressed -> networkClient.sendMacroKey("mouse_middle", pressed) }
        controllerView.onMacroKeyRequested = { key, pressed -> networkClient.sendMacroKey(key, pressed) }
        controllerView.onMacroEventRecorded = { key, isDown, timeMs ->
            recordedEvents.add(RecordedInputEvent(key, isDown, timeMs))
            if (isDown) {
                val count = recordedEvents.count { it.isDown }
                recTitleText?.text = "🔴 RECORDING ($count taps) | Last: ${key.uppercase()} | Tap buttons..."
            }
        }
        controllerView.onOpenKeySettingsRequested = { el ->
            showKeySettingsDialog(el)
        }
        controllerView.stickSprintMode = HudConfig.isStickSprintMode(this)
        controllerView.stickFloatingMode = HudConfig.isStickFloatingMode(this)
        controllerView.stickTouchScale = HudConfig.getStickTouchScale(this)
        controllerView.onOpenStickSettingsRequested = { el ->
            showStickSettingsDialog(el)
        }
        controllerView.onLayoutChanged = {
            val keymap = HudConfig.extractKeymap(controllerView.elements)
            networkClient.sendKeymapSync(keymap)
        }
        root.addView(
            controllerView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 2. Sleek Compact Gear Button (Top-Right)
        gearButton = Button(this).apply {
            text = "⚙"
            textSize = 20f
            setTextColor(Color.WHITE)
            alpha = 0.7f
            background = createCardDrawable(Color.parseColor("#440D1117"), 30f, Color.parseColor("#58A6FF"), 1)
            setPadding(0, 0, 0, 0)
            setOnClickListener { showSettingsDialog() }
        }
        val gearParams = FrameLayout.LayoutParams(90, 90).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 16
            rightMargin = 16
        }
        root.addView(gearButton, gearParams)

        // 3. Edit Mode Overlay
        buildEditOverlay(root)

        // 4. Floating Live Recording Banner
        buildRecordingBanner(root)

        setContentView(root)
        networkClient.start()

        restoreSavedTargetOrPrompt()
    }

    override fun onResume() {
        super.onResume()
        gyroscopeSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        val keymap = HudConfig.extractKeymap(controllerView.elements)
        networkClient.sendKeymapSync(keymap)
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // -------------------------------------------------------------------
    // Gyroscope Motion Aiming
    // -------------------------------------------------------------------
    private var smoothedGyroDx = 0f
    private var smoothedGyroDy = 0f

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (!gyroActive || controllerView.isEditMode) return

        // If Aim-Only is enabled, only engage gyro when touching look pad or holding aim buttons
        if (HudConfig.isGyroAimOnly(this) && !controllerView.isAimActive()) {
            smoothedGyroDx = 0f
            smoothedGyroDy = 0f
            return
        }

        val displayRotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_90
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val isRot270 = displayRotation == Surface.ROTATION_270

        var rawWx = event.values[0]
        var rawWy = event.values[1]
        val rawWz = event.values[2]

        if (isRot270) {
            rawWx = -rawWx
            rawWy = -rawWy
        }

        // Noise deadzone filter (eliminate resting drift / hand tremors)
        val deadzone = 0.015f
        val wx = if (abs(rawWx) < deadzone) 0f else rawWx - kotlin.math.sign(rawWx) * deadzone
        val wy = if (abs(rawWy) < deadzone) 0f else rawWy - kotlin.math.sign(rawWy) * deadzone
        val wz = if (abs(rawWz) < deadzone) 0f else rawWz - kotlin.math.sign(rawWz) * deadzone

        if (wx == 0f && wy == 0f && wz == 0f) {
            smoothedGyroDx *= 0.5f
            smoothedGyroDy *= 0.5f
            if (abs(smoothedGyroDx) > 0.05f || abs(smoothedGyroDy) > 0.05f) {
                controllerView.injectGyroAim(smoothedGyroDx, smoothedGyroDy)
            }
            return
        }

        val sensX = HudConfig.getGyroSensX(this) * 15.0f
        val sensY = HudConfig.getGyroSensY(this) * 15.0f

        // Correct Landscape Mapping:
        // Horizontal aim (turn left/right) combines swivel (wx) and steering wheel roll (-wz)
        var targetDx = (wx - wz * 0.75f) * sensX

        // Vertical aim (tilt up/down) is driven by screen pitch (wy)
        var targetDy = -wy * sensY

        // Filter by selected Axis Mode (Full 2D vs Horizontal Only vs Vertical Only)
        val axisMode = HudConfig.getGyroAxisMode(this)
        if (axisMode == HudConfig.GYRO_AXIS_HORIZONTAL_ONLY) {
            targetDy = 0f
        } else if (axisMode == HudConfig.GYRO_AXIS_VERTICAL_ONLY) {
            targetDx = 0f
        }

        if (HudConfig.isGyroInvertX(this)) targetDx = -targetDx
        if (HudConfig.isGyroInvertY(this)) targetDy = -targetDy

        // Low-pass exponential smoothing filter
        val alpha = HudConfig.getGyroSmoothing(this).coerceIn(0.2f, 0.95f)
        smoothedGyroDx = alpha * targetDx + (1f - alpha) * smoothedGyroDx
        smoothedGyroDy = alpha * targetDy + (1f - alpha) * smoothedGyroDy

        controllerView.injectGyroAim(smoothedGyroDx, smoothedGyroDy)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // -------------------------------------------------------------------
    // UI Helpers
    // -------------------------------------------------------------------
    private fun createCardDrawable(bgColor: Int, cornerRadius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            setCornerRadius(cornerRadius)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    private fun formatKeyDisplay(key: String): String = when (key.lowercase()) {
        "mouse_left", "lmb" -> "LMB"
        "mouse_right", "rmb" -> "RMB"
        "mouse_middle", "mmb" -> "MMB"
        else -> key.uppercase()
    }

    // -------------------------------------------------------------------
    // Settings Menu Dialog (Gear Icon ⚙)
    // -------------------------------------------------------------------
    private fun showSettingsDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        scroll.addView(layout)

        // Title & Status Badge
        val statusText = when (networkClient.mode) {
            TransportMode.USB -> "🟢 Mode: USB Cable (Connected)"
            TransportMode.WIFI -> "🔵 Mode: Wi-Fi (${networkClient.wifiHost.ifEmpty { "Ready" }})"
        }
        val statusBadge = TextView(this).apply {
            text = statusText
            setTextColor(Color.parseColor("#58A6FF"))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 16)
        }
        layout.addView(statusBadge)

        // Action Buttons Row
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 20)
        }

        val customizeBtn = Button(this).apply {
            text = "Customize HUD"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#8A2BE2"), 14f)
            setPadding(24, 10, 24, 10)
        }
        actionsRow.addView(customizeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 12 })

        val connSetupBtn = Button(this).apply {
            text = "Connection Setup"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1E5F7A"), 14f)
            setPadding(24, 10, 24, 10)
        }
        actionsRow.addView(connSetupBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(actionsRow)

        // Section: Game Presets / Profiles
        val currentProfile = HudConfig.getActiveProfile(this)
        val profileLabel = TextView(this).apply {
            text = "Game Preset Profile: $currentProfile"
            setTextColor(Color.WHITE)
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, 12, 0, 8)
        }
        layout.addView(profileLabel)

        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val switchProfileBtn = Button(this).apply {
            text = "Switch Profile"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#374151"), 12f)
            setPadding(20, 8, 20, 8)
        }
        profileRow.addView(switchProfileBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 12 })

        val newProfileBtn = Button(this).apply {
            text = "+ New Preset"
            textSize = 12f
            setTextColor(Color.parseColor("#FFD700"))
            background = createCardDrawable(Color.parseColor("#374151"), 12f)
            setPadding(20, 8, 20, 8)
        }
        profileRow.addView(newProfileBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(profileRow)

        // Row: Share / Export & Import Layout as JSON
        val jsonShareRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }
        val exportJsonBtn = Button(this).apply {
            text = "📋 Export / Share JSON"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val json = HudConfig.exportLayoutJson(controllerView.elements)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("VirtualPad HUD", json)
                clipboard.setPrimaryClip(clip)

                val showBox = EditText(this@MainActivity).apply {
                    setText(json)
                    isFocusable = false
                    textSize = 11f
                    setPadding(16, 16, 16, 16)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("📋 HUD Layout JSON (Copied!)")
                    .setMessage("The HUD layout JSON has been copied to your clipboard. You can paste and share it with anyone!")
                    .setView(showBox)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        jsonShareRow.addView(exportJsonBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })

        val importJsonBtn = Button(this).apply {
            text = "📥 Import / Paste JSON"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#238636"), 12f)
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val input = EditText(this@MainActivity).apply {
                    hint = "Paste HUD layout JSON here..."
                    minLines = 4
                    textSize = 12f
                    setPadding(16, 16, 16, 16)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("📥 Import HUD Layout")
                    .setMessage("Paste someone's HUD layout JSON below to load their controls:")
                    .setView(input)
                    .setPositiveButton("Import") { _, _ ->
                        val text = input.text.toString().trim()
                        val imported = HudConfig.importLayoutJson(text)
                        if (imported != null && imported.isNotEmpty()) {
                            controllerView.elements.clear()
                            controllerView.elements.addAll(imported)
                            val active = HudConfig.getActiveProfile(this@MainActivity)
                            HudConfig.saveLayout(this@MainActivity, imported, active)
                            val keymap = HudConfig.extractKeymap(imported)
                            networkClient.sendKeymapSync(keymap)
                            controllerView.invalidate()
                            Toast.makeText(this@MainActivity, "✅ HUD Layout imported successfully!", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(this@MainActivity, "❌ Invalid HUD JSON format", Toast.LENGTH_LONG).show()
                        }
                    }
                    .setNeutralButton("Paste from Clipboard") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pasteText = clip.getItemAt(0).text.toString()
                            val imported = HudConfig.importLayoutJson(pasteText)
                            if (imported != null && imported.isNotEmpty()) {
                                controllerView.elements.clear()
                                controllerView.elements.addAll(imported)
                                val active = HudConfig.getActiveProfile(this@MainActivity)
                                HudConfig.saveLayout(this@MainActivity, imported, active)
                                val keymap = HudConfig.extractKeymap(imported)
                                networkClient.sendKeymapSync(keymap)
                                controllerView.invalidate()
                                Toast.makeText(this@MainActivity, "✅ HUD Layout pasted and loaded!", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@MainActivity, "❌ Clipboard does not contain valid HUD JSON", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        jsonShareRow.addView(importJsonBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(jsonShareRow)

        // Section: HUD Opacity Slider
        val opacityLabel = TextView(this).apply {
            text = "HUD Opacity: ${(controllerView.hudOpacity * 100).toInt()}%"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(opacityLabel)

        val opacityBar = SeekBar(this).apply {
            max = 100
            progress = (controllerView.hudOpacity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val safeProg = progress.coerceAtLeast(20) // min 20%
                    val op = safeProg / 100f
                    opacityLabel.text = "HUD Opacity: $safeProg%"
                    controllerView.hudOpacity = op
                    HudConfig.setHudOpacity(this@MainActivity, op)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(opacityBar)

        // Section: Mouse Look Sensitivity
        val sensLabel = TextView(this).apply {
            text = "Mouse Sensitivity: ${String.format("%.2f", controllerView.mouseSensitivity)}x"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 16, 0, 4)
        }
        layout.addView(sensLabel)

        val sensBar = SeekBar(this).apply {
            max = 400
            progress = (controllerView.mouseSensitivity * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sens = progress.coerceAtLeast(50) / 100f
                    sensLabel.text = "Mouse Sensitivity: ${String.format("%.2f", sens)}x"
                    controllerView.mouseSensitivity = sens
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(sensBar)

        // Section: Gyroscope Aiming
        val gyroTitle = TextView(this).apply {
            text = "🎯 Gyroscope Motion Aiming"
            setTextColor(Color.parseColor("#58A6FF"))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, 16, 0, 4)
        }
        layout.addView(gyroTitle)

        val gyroCheck = CheckBox(this).apply {
            text = "Enable Gyroscope Motion Aiming"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = HudConfig.isGyroEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                gyroActive = isChecked
                HudConfig.setGyroEnabled(this@MainActivity, isChecked)
            }
        }
        layout.addView(gyroCheck)

        // Gyro Active Axes Selector (Full 2D vs Horizontal Only vs Vertical Only)
        val axisLabel = TextView(this).apply {
            text = "Gyro Motion Axis Mode:"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            setPadding(0, 8, 0, 2)
        }
        layout.addView(axisLabel)

        val axisRadioGroup = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rbFull = RadioButton(this).apply {
            text = "Full 2D (Both Horizontal & Vertical Aim)"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }
        val rbHoriz = RadioButton(this).apply {
            text = "Horizontal Only (Yaw / Left & Right Aim Only)"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }
        val rbVert = RadioButton(this).apply {
            text = "Vertical Only (Pitch / Up & Down Aim Only)"
            setTextColor(Color.WHITE)
            textSize = 12f
            id = View.generateViewId()
        }
        axisRadioGroup.addView(rbFull)
        axisRadioGroup.addView(rbHoriz)
        axisRadioGroup.addView(rbVert)

        when (HudConfig.getGyroAxisMode(this@MainActivity)) {
            HudConfig.GYRO_AXIS_HORIZONTAL_ONLY -> axisRadioGroup.check(rbHoriz.id)
            HudConfig.GYRO_AXIS_VERTICAL_ONLY -> axisRadioGroup.check(rbVert.id)
            else -> axisRadioGroup.check(rbFull.id)
        }

        axisRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                rbHoriz.id -> HudConfig.GYRO_AXIS_HORIZONTAL_ONLY
                rbVert.id -> HudConfig.GYRO_AXIS_VERTICAL_ONLY
                else -> HudConfig.GYRO_AXIS_FULL
            }
            HudConfig.setGyroAxisMode(this@MainActivity, mode)
        }
        layout.addView(axisRadioGroup)

        val gyroAimOnlyCheck = CheckBox(this).apply {
            text = "Aim-Only Ratchet (Active while aiming / holding LT)"
            setTextColor(Color.parseColor("#E6EDF3"))
            textSize = 12f
            isChecked = HudConfig.isGyroAimOnly(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                HudConfig.setGyroAimOnly(this@MainActivity, isChecked)
            }
        }
        layout.addView(gyroAimOnlyCheck)

        // Gyro Inversion Checkboxes Row
        val invertRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        val invertXCheck = CheckBox(this).apply {
            text = "Invert Horizontal"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            isChecked = HudConfig.isGyroInvertX(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                HudConfig.setGyroInvertX(this@MainActivity, isChecked)
            }
        }
        val invertYCheck = CheckBox(this).apply {
            text = "Invert Vertical"
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 12f
            isChecked = HudConfig.isGyroInvertY(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                HudConfig.setGyroInvertY(this@MainActivity, isChecked)
            }
        }
        invertRow.addView(invertXCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        invertRow.addView(invertYCheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(invertRow)

        val sensXLabel = TextView(this).apply {
            text = "↔️ Gyro Horizontal Sensitivity (Yaw): ${String.format("%.1f", HudConfig.getGyroSensX(this@MainActivity))}x"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(sensXLabel)

        val sensXBar = SeekBar(this).apply {
            max = 370
            progress = ((HudConfig.getGyroSensX(this@MainActivity) - 0.30f) * 100).toInt().coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sens = progress / 100f + 0.30f
                    sensXLabel.text = "↔️ Gyro Horizontal Sensitivity (Yaw): ${String.format("%.1f", sens)}x"
                    HudConfig.setGyroSensX(this@MainActivity, sens)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(sensXBar)

        val sensYLabel = TextView(this).apply {
            text = "↕️ Gyro Vertical Sensitivity (Pitch): ${String.format("%.1f", HudConfig.getGyroSensY(this@MainActivity))}x"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(sensYLabel)

        val sensYBar = SeekBar(this).apply {
            max = 370
            progress = ((HudConfig.getGyroSensY(this@MainActivity) - 0.30f) * 100).toInt().coerceAtLeast(0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sens = progress / 100f + 0.30f
                    sensYLabel.text = "↕️ Gyro Vertical Sensitivity (Pitch): ${String.format("%.1f", sens)}x"
                    HudConfig.setGyroSensY(this@MainActivity, sens)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(sensYBar)

        val gyroSmoothLabel = TextView(this).apply {
            val sm = (HudConfig.getGyroSmoothing(this@MainActivity) * 100).toInt()
            text = "Gyro Smoothing: $sm% (Low Jitter)"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(gyroSmoothLabel)

        val gyroSmoothBar = SeekBar(this).apply {
            max = 95
            progress = (HudConfig.getGyroSmoothing(this@MainActivity) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sm = progress.coerceAtLeast(20) / 100f
                    gyroSmoothLabel.text = "Gyro Smoothing: ${(sm * 100).toInt()}% (Low Jitter)"
                    HudConfig.setGyroSmoothing(this@MainActivity, sm)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(gyroSmoothBar)

        // Section: High-End Tactile Haptic Feedback
        val hapticTitle = TextView(this).apply {
            text = "🎮 Tactile Haptic Feedback"
            setTextColor(Color.parseColor("#3FB950"))
            textSize = 14f
            paint.isFakeBoldText = true
            setPadding(0, 16, 0, 4)
        }
        layout.addView(hapticTitle)

        val hapticCheck = CheckBox(this).apply {
            text = "Enable Tactile Haptics (Buttons, Triggers, D-Pad, Boundary)"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = HudConfig.isHapticEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                HudConfig.setHapticEnabled(this@MainActivity, isChecked)
            }
        }
        layout.addView(hapticCheck)

        val hapticIntensityLabel = TextView(this).apply {
            text = "Haptic Strength: ${(HudConfig.getHapticIntensity(this@MainActivity) * 100).toInt()}%"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(hapticIntensityLabel)

        val hapticIntensityBar = SeekBar(this).apply {
            max = 100
            progress = (HudConfig.getHapticIntensity(this@MainActivity) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val intensity = progress.coerceAtLeast(10) / 100f
                    hapticIntensityLabel.text = "Haptic Strength: ${(intensity * 100).toInt()}%"
                    HudConfig.setHapticIntensity(this@MainActivity, intensity)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    controllerView.hapticHelper.heavyClick()
                }
            })
        }
        layout.addView(hapticIntensityBar)

        val testHapticBtn = Button(this).apply {
            text = "⚡ Test Haptic Feedback"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#238636"), 12f)
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                controllerView.hapticHelper.heavyClick()
            }
        }
        layout.addView(testHapticBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 6
            bottomMargin = 16
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle("⚙ VirtualPad Settings")
            .setView(scroll)
            .setPositiveButton("Done", null)
            .create()

        customizeBtn.setOnClickListener {
            dialog.dismiss()
            enterEditMode()
        }

        connSetupBtn.setOnClickListener {
            dialog.dismiss()
            showConnectionDialog()
        }

        switchProfileBtn.setOnClickListener {
            val profiles = HudConfig.getProfiles(this).toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Game Preset")
                .setItems(profiles) { _, which ->
                    val chosen = profiles[which]
                    HudConfig.setActiveProfile(this, chosen)
                    controllerView.loadProfile(chosen)
                    val keymap = HudConfig.extractKeymap(controllerView.elements)
                    networkClient.sendKeymapSync(keymap)
                    profileLabel.text = "Game Preset Profile: $chosen"
                    Toast.makeText(this, "Loaded preset: $chosen", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        newProfileBtn.setOnClickListener {
            val input = EditText(this).apply { hint = "Preset Name (e.g. GTA5, RDR2, FPS)" }
            AlertDialog.Builder(this)
                .setTitle("Create New Preset Profile")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        HudConfig.addProfile(this, name)
                        HudConfig.saveLayout(this, controllerView.elements, name)
                        HudConfig.setActiveProfile(this, name)
                        profileLabel.text = "Game Preset Profile: $name"
                        Toast.makeText(this, "Created and activated preset: $name", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        dialog.show()
    }

    // -------------------------------------------------------------------
    // HUD Edit Mode Overlay
    // -------------------------------------------------------------------
    private fun buildEditOverlay(root: FrameLayout) {
        editOverlay = FrameLayout(this).apply { visibility = View.GONE }

        // Top Editor Bar
        val topEditorBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createCardDrawable(Color.parseColor("#E60D1117"), 0f)
            setPadding(30, 16, 30, 16)
        }

        val title = TextView(this).apply {
            text = "HUD CUSTOMIZER"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 16f
            paint.isFakeBoldText = true
        }
        val titleParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        topEditorBar.addView(title, titleParams)

        val addBtn = Button(this).apply {
            text = "+ Add Button"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#8A2BE2"), 14f)
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                val newEl = controllerView.addCustomButton()
                if (newEl == null) {
                    Toast.makeText(this@MainActivity, "Max custom buttons reached (16)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Added Custom Button ${newEl.customSlot + 1}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topEditorBar.addView(addBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 16 })

        val resetBtn = Button(this).apply {
            text = "Reset Layout"
            textSize = 12f
            setTextColor(Color.parseColor("#FF8A80"))
            background = createCardDrawable(Color.parseColor("#374151"), 14f)
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset HUD")
                    .setMessage("Are you sure you want to reset buttons to default?")
                    .setPositiveButton("Reset") { _, _ ->
                        controllerView.resetToDefault()
                        Toast.makeText(this@MainActivity, "Layout reset to default", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        topEditorBar.addView(resetBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 16 })

        val saveExitBtn = Button(this).apply {
            text = "Save & Exit"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#2ECC71"), 14f)
            setPadding(30, 8, 30, 8)
            setOnClickListener { exitEditMode(save = true) }
        }
        topEditorBar.addView(saveExitBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val topParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP }
        editOverlay.addView(topEditorBar, topParams)

        // Bottom Inspector Card
        bottomInspector = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = createCardDrawable(Color.parseColor("#F2161B22"), 24f, Color.parseColor("#30363D"), 2)
            setPadding(32, 20, 32, 20)
        }

        inspectorTitle = TextView(this).apply {
            text = "Tap any button on screen to select and adjust"
            setTextColor(Color.parseColor("#C9D1D9"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        bottomInspector.addView(inspectorTitle)

        // 1. Shared Scale / Size Slider Row
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 6)
        }
        scaleText = TextView(this).apply {
            text = "Size: 1.0x"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        scaleRow.addView(scaleText)

        scaleSeekBar = SeekBar(this).apply {
            max = 220
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val safeProg = progress.coerceAtLeast(50)
                    val scale = safeProg / 100f
                    scaleText.text = "Size: ${String.format("%.2f", scale)}x"
                    if (fromUser) controllerView.updateSelectedScale(scale)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        scaleRow.addView(scaleSeekBar, LinearLayout.LayoutParams(320, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 12 })
        bottomInspector.addView(scaleRow)

        // 2. Button-Specific Controls Row (Key Binding, Shape, Delete)
        buttonControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 0)
        }

        bindKeyButton = Button(this).apply {
            text = "Bind Key"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(20, 6, 20, 6)
            setOnClickListener { showKeyPickerDialog() }
        }
        buttonControlsRow.addView(bindKeyButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

        keySettingsButton = Button(this).apply {
            text = "⚙️ Key Settings"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#8957E5"), 12f)
            setPadding(18, 6, 18, 6)
            setOnClickListener {
                controllerView.selectedElement?.let { el ->
                    if (el.type == ElementType.BUTTON) {
                        showKeySettingsDialog(el)
                    } else if (el.type == ElementType.STICK) {
                        showStickSettingsDialog(el)
                    }
                }
            }
        }
        buttonControlsRow.addView(keySettingsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

        shapeButton = Button(this).apply {
            text = "Shape: Circle"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#374151"), 12f)
            setPadding(20, 6, 20, 6)
            setOnClickListener { cycleSelectedShape() }
        }
        buttonControlsRow.addView(shapeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

        modeButton = Button(this).apply {
            text = "Mode: Hold ⏱️"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#374151"), 12f)
            setPadding(18, 6, 18, 6)
            setOnClickListener { cycleSelectedButtonMode() }
        }
        buttonControlsRow.addView(modeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

        turboCpsButton = Button(this).apply {
            text = "⚡ 12 CPS"
            textSize = 12f
            setTextColor(Color.parseColor("#FFD600"))
            background = createCardDrawable(Color.parseColor("#3D3200"), 12f, Color.parseColor("#FFD600"), 1)
            setPadding(16, 6, 16, 6)
            visibility = View.GONE
            setOnClickListener {
                val selected = controllerView.selectedElement
                if (selected?.isInstantTap == true) {
                    cycleSelectedInstantTapDuration()
                } else {
                    cycleSelectedTurboCps()
                }
            }
        }
        buttonControlsRow.addView(turboCpsButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

        macroButton = Button(this).apply {
            text = "Macro: OFF"
            textSize = 12f
            setTextColor(Color.parseColor("#E040FB"))
            background = createCardDrawable(Color.parseColor("#34143D"), 12f, Color.parseColor("#E040FB"), 1)
            setPadding(16, 6, 16, 6)
            setOnClickListener { showMacroStudioDialog() }
        }
        buttonControlsRow.addView(macroButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

        deleteButton = Button(this).apply {
            text = "Delete"
            textSize = 12f
            setTextColor(Color.parseColor("#FF6B6B"))
            background = createCardDrawable(Color.parseColor("#491818"), 12f)
            setPadding(20, 6, 20, 6)
            setOnClickListener {
                if (controllerView.deleteSelectedElement()) {
                    Toast.makeText(this@MainActivity, "Button removed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        buttonControlsRow.addView(deleteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bottomInspector.addView(buttonControlsRow)

        // 3. D-Pad Specific Directional Controls Row (Up, Down, Left, Right)
        dpadControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }

        dpadUpBtn = Button(this).apply {
            text = "↑ Up"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(16, 6, 16, 6)
            setOnClickListener { showDpadKeyPickerDialog("up") }
        }
        dpadControlsRow.addView(dpadUpBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        dpadDownBtn = Button(this).apply {
            text = "↓ Down"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(16, 6, 16, 6)
            setOnClickListener { showDpadKeyPickerDialog("down") }
        }
        dpadControlsRow.addView(dpadDownBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        dpadLeftBtn = Button(this).apply {
            text = "← Left"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(16, 6, 16, 6)
            setOnClickListener { showDpadKeyPickerDialog("left") }
        }
        dpadControlsRow.addView(dpadLeftBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        dpadRightBtn = Button(this).apply {
            text = "→ Right"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(16, 6, 16, 6)
            setOnClickListener { showDpadKeyPickerDialog("right") }
        }
        dpadControlsRow.addView(dpadRightBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        bottomInspector.addView(dpadControlsRow)

        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 24
        }
        editOverlay.addView(bottomInspector, bottomParams)

        // Controller element selection callback
        controllerView.onElementSelected = { selected ->
            updateInspector(selected)
        }

        root.addView(editOverlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun updateInspector(el: HudElement?) {
        if (el == null) {
            inspectorTitle.text = "Tap any button on screen to select and adjust"
            scaleSeekBar.isEnabled = false
            buttonControlsRow.visibility = View.VISIBLE
            dpadControlsRow.visibility = View.GONE
            bindKeyButton.isEnabled = false
            shapeButton.isEnabled = false
            modeButton.isEnabled = false
            modeButton.alpha = 0.4f
            modeButton.text = "Mode: Hold ⏱️"
            turboCpsButton.visibility = View.GONE
            macroButton.isEnabled = false
            macroButton.alpha = 0.4f
            macroButton.text = "Macro: OFF"
            deleteButton.isEnabled = false
            deleteButton.alpha = 0.4f
            keySettingsButton.isEnabled = false
            keySettingsButton.alpha = 0.4f
        } else {
            scaleSeekBar.isEnabled = true
            scaleSeekBar.progress = (el.scale * 100).toInt()
            scaleText.text = "Size: ${String.format("%.2f", el.scale)}x"

            when (el.type) {
                ElementType.DPAD -> {
                    inspectorTitle.text = "Selected: D-Pad (Choose a direction to assign key)"
                    buttonControlsRow.visibility = View.GONE
                    dpadControlsRow.visibility = View.VISIBLE

                    dpadUpBtn.text = "↑ [${formatKeyDisplay(el.dpadUpKey)}]"
                    dpadDownBtn.text = "↓ [${formatKeyDisplay(el.dpadDownKey)}]"
                    dpadLeftBtn.text = "← [${formatKeyDisplay(el.dpadLeftKey)}]"
                    dpadRightBtn.text = "→ [${formatKeyDisplay(el.dpadRightKey)}]"
                }
                ElementType.SCROLL_WHEEL -> {
                    inspectorTitle.text = "Selected: Mouse Scroll Wheel (Swipe Up/Down to switch weapons; Tap to Ping/MMB)"
                    buttonControlsRow.visibility = View.GONE
                    dpadControlsRow.visibility = View.GONE
                }
                ElementType.BUTTON -> {
                    val typeStr = if (el.isCustom) "Custom Button ${el.customSlot + 1}" else "Button ${el.id.uppercase()}"
                    val keyDisplay = formatKeyDisplay(el.key)
                    val aimTag = if (el.swipeToAim) " 🎯 Aim" else ""
                    val hitboxTag = if (el.touchPadding > 1.0f) " 📏 ${String.format("%.1f", el.touchPadding)}x" else ""
                    inspectorTitle.text = "Selected: $typeStr ${if (el.key.isNotEmpty()) "[Key: $keyDisplay]" else ""}$aimTag$hitboxTag"

                    buttonControlsRow.visibility = View.VISIBLE
                    dpadControlsRow.visibility = View.GONE

                    keySettingsButton.isEnabled = true
                    keySettingsButton.alpha = 1.0f
                    keySettingsButton.text = "⚙️ Key Settings"

                    bindKeyButton.isEnabled = true
                    bindKeyButton.alpha = 1.0f
                    bindKeyButton.text = if (el.key.isNotEmpty()) "Key: $keyDisplay" else "Bind Key"

                    shapeButton.isEnabled = true
                    shapeButton.alpha = 1.0f
                    shapeButton.text = "Shape: " + when (el.shape) {
                        ButtonShape.CIRCLE -> "Circle"
                        ButtonShape.SQUARE -> "Square"
                        ButtonShape.ROUNDED_RECT -> "Pill"
                    }

                    modeButton.isEnabled = true
                    modeButton.alpha = 1.0f
                    modeButton.text = when {
                        el.isTurbo -> "Mode: Turbo ⚡"
                        el.isInstantTap -> "Mode: Instant ⏱️"
                        el.isToggle -> "Mode: Toggle 🔒"
                        else -> "Mode: Hold"
                    }

                    if (el.isTurbo) {
                        turboCpsButton.visibility = View.VISIBLE
                        turboCpsButton.text = "⚡ ${el.turboCps} CPS"
                        turboCpsButton.setTextColor(Color.parseColor("#FFD600"))
                        turboCpsButton.background = createCardDrawable(Color.parseColor("#3D3200"), 12f, Color.parseColor("#FFD600"), 1)
                    } else if (el.isInstantTap) {
                        turboCpsButton.visibility = View.VISIBLE
                        turboCpsButton.text = "⏱️ ${el.instantTapDurationMs}ms"
                        turboCpsButton.setTextColor(Color.parseColor("#FF9100"))
                        turboCpsButton.background = createCardDrawable(Color.parseColor("#3D2200"), 12f, Color.parseColor("#FF9100"), 1)
                    } else {
                        turboCpsButton.visibility = View.GONE
                    }

                    macroButton.isEnabled = true
                    macroButton.alpha = 1.0f
                    macroButton.text = if (el.macroType.isEmpty() && el.customMacro.isEmpty()) "Macro: OFF" else "Macro: ⚡"

                    deleteButton.isEnabled = el.isCustom
                    deleteButton.alpha = if (el.isCustom) 1.0f else 0.4f
                }
                ElementType.STICK -> {
                    val modeStr = if (controllerView.stickSprintMode) "⚡ Sprint Mode" else "🚶 Walk Mode"
                    val floatStr = if (controllerView.stickFloatingMode) " • 📍 Floating" else ""
                    val touchStr = if (controllerView.stickTouchScale > 1.0f) " • Touch Radius: ${String.format("%.1f", controllerView.stickTouchScale)}x" else ""
                    inspectorTitle.text = "Selected: Movement Stick ($modeStr$floatStr$touchStr) — Tap ⚙ to change"
                    buttonControlsRow.visibility = View.VISIBLE
                    dpadControlsRow.visibility = View.GONE
                    keySettingsButton.isEnabled = true
                    keySettingsButton.alpha = 1.0f
                    keySettingsButton.text = "⚙️ Stick Mode"
                    bindKeyButton.isEnabled = false
                    bindKeyButton.alpha = 0.4f
                    bindKeyButton.text = "WASD (Move)"
                    shapeButton.isEnabled = false
                    shapeButton.alpha = 0.4f
                    modeButton.isEnabled = false
                    modeButton.alpha = 0.4f
                    turboCpsButton.visibility = View.GONE
                    macroButton.isEnabled = false
                    macroButton.alpha = 0.4f
                    macroButton.text = "Macro: OFF"
                    deleteButton.isEnabled = false
                    deleteButton.alpha = 0.4f
                }
            }
        }
    }

    private fun cycleSelectedShape() {
        val selected = controllerView.selectedElement ?: return
        if (selected.type != ElementType.BUTTON) return

        val nextShape = when (selected.shape) {
            ButtonShape.CIRCLE -> ButtonShape.SQUARE
            ButtonShape.SQUARE -> ButtonShape.ROUNDED_RECT
            ButtonShape.ROUNDED_RECT -> ButtonShape.CIRCLE
        }
        controllerView.updateSelectedShape(nextShape)
        updateInspector(selected)
    }

    private fun cycleSelectedButtonMode() {
        val selected = controllerView.selectedElement ?: return
        if (selected.type != ElementType.BUTTON) return

        when {
            !selected.isToggle && !selected.isTurbo && !selected.isInstantTap -> {
                // Hold -> Toggle
                controllerView.updateSelectedButtonMode(isToggle = true, isTurbo = false, isInstantTap = false)
                Toast.makeText(this, "Toggle Mode 🔒: Tap to lock ON, tap again to release", Toast.LENGTH_SHORT).show()
            }
            selected.isToggle -> {
                // Toggle -> Turbo
                controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = true, isInstantTap = false)
                Toast.makeText(this, "Turbo Mode ⚡: Hold for rapid-fire auto-click (${selected.turboCps} CPS)", Toast.LENGTH_SHORT).show()
            }
            selected.isTurbo -> {
                // Turbo -> Instant Tap
                controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = true)
                Toast.makeText(this, "Instant Tap ⏱️: Auto-release after ${selected.instantTapDurationMs}ms (One-Shot)", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Instant Tap -> Hold
                controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = false)
                Toast.makeText(this, "Hold Mode: Standard press & hold", Toast.LENGTH_SHORT).show()
            }
        }
        updateInspector(selected)
    }

    private fun cycleSelectedTurboCps() {
        val selected = controllerView.selectedElement ?: return
        if (selected.type != ElementType.BUTTON || !selected.isTurbo) return

        val speeds = listOf(8, 12, 16, 20, 25)
        val currentIndex = speeds.indexOf(selected.turboCps)
        val nextCps = if (currentIndex != -1 && currentIndex < speeds.size - 1) {
            speeds[currentIndex + 1]
        } else {
            speeds[0]
        }
        controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = true, turboCps = nextCps)
        updateInspector(selected)
        Toast.makeText(this, "Turbo Speed: $nextCps Clicks Per Second", Toast.LENGTH_SHORT).show()
    }

    private fun cycleSelectedInstantTapDuration() {
        val selected = controllerView.selectedElement ?: return
        if (selected.type != ElementType.BUTTON || !selected.isInstantTap) return

        val durations = listOf(5, 10, 15, 25, 50, 100, 200)
        val currentIndex = durations.indexOf(selected.instantTapDurationMs)
        val nextDuration = if (currentIndex != -1 && currentIndex < durations.size - 1) {
            durations[currentIndex + 1]
        } else {
            durations[0]
        }
        controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = true, instantTapDurationMs = nextDuration)
        updateInspector(selected)
        Toast.makeText(this, "Instant Tap Duration: ${nextDuration}ms", Toast.LENGTH_SHORT).show()
    }

    private fun buildRecordingBanner(root: FrameLayout) {
        recordingBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 14, 28, 14)
            background = createCardDrawable(Color.parseColor("#F0111827"), 28f, Color.parseColor("#E040FB"), 2)
            visibility = View.GONE
            elevation = 50f

            val recTitle = TextView(this@MainActivity).apply {
                text = "🔴 RECORDING: Tap buttons on HUD... "
                textSize = 13f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            recTitleText = recTitle
            addView(recTitle)

            val cancelRecBtn = Button(this@MainActivity).apply {
                text = "✕ CANCEL"
                textSize = 12f
                setTextColor(Color.parseColor("#8B949E"))
                background = createCardDrawable(Color.parseColor("#21262D"), 14f)
                setPadding(18, 6, 18, 6)
                setOnClickListener {
                    cancelLiveRecording()
                }
            }
            addView(cancelRecBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 16 })

            val stopRecBtn = Button(this@MainActivity).apply {
                text = "⏹️ DONE"
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = createCardDrawable(Color.parseColor("#E040FB"), 14f)
                setPadding(22, 6, 22, 6)
                setOnClickListener {
                    stopLiveRecordingAndOpenStudio()
                }
            }
            addView(stopRecBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 10 })
        }
        val recParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 20
        }
        root.addView(recordingBanner, recParams)
    }

    private fun cancelLiveRecording() {
        controllerView.isRecordingMacro = false
        controllerView.isEditMode = true
        recordingBanner.visibility = View.GONE
        editOverlay.visibility = View.VISIBLE
        gearButton.visibility = View.VISIBLE
        val targetEl = liveRecordingTarget ?: controllerView.selectedElement
        if (targetEl != null) {
            controllerView.selectElement(targetEl)
            showMacroStudioDialog(targetEl)
        }
    }

    private fun startLiveRecording(targetEl: HudElement) {
        liveRecordingTarget = targetEl
        recordedEvents.clear()
        controllerView.isRecordingMacro = true
        controllerView.isEditMode = false // Exit edit touch handling so HUD buttons receive touch!
        editOverlay.visibility = View.GONE
        gearButton.visibility = View.GONE
        recTitleText?.text = "🔴 RECORDING: Tap buttons on HUD with real rhythm..."
        recordingBanner.visibility = View.VISIBLE
        recordingBanner.bringToFront()
        controllerView.hapticHelper.heavyClick()
        Toast.makeText(this, "🔴 Recording started! Tap HUD buttons with your natural rhythm", Toast.LENGTH_SHORT).show()
    }

    private fun stopLiveRecordingAndOpenStudio() {
        controllerView.isRecordingMacro = false
        controllerView.isEditMode = true // Re-enable layout edit mode
        recordingBanner.visibility = View.GONE
        editOverlay.visibility = View.VISIBLE
        gearButton.visibility = View.VISIBLE
        controllerView.hapticHelper.click()

        val targetEl = liveRecordingTarget ?: controllerView.selectedElement
        val steps = convertRecordedEventsToSteps(recordedEvents)
        if (steps.isEmpty()) {
            Toast.makeText(this, "No buttons pressed during recording", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Captured ${steps.size} macro steps!", Toast.LENGTH_SHORT).show()
        }
        if (targetEl != null) {
            controllerView.selectElement(targetEl)
            showMacroStudioDialog(targetEl, initialSteps = steps)
        }
    }

    private fun convertRecordedEventsToSteps(events: List<RecordedInputEvent>): MutableList<MacroStep> {
        val steps = mutableListOf<MacroStep>()
        if (events.isEmpty()) return steps

        // 1. Identify for each DOWN event its corresponding UP event and duration
        val activeDowns = HashMap<String, RecordedInputEvent>()
        val downDurations = HashMap<RecordedInputEvent, Long>()
        val isCleanTap = HashSet<RecordedInputEvent>()

        for (i in events.indices) {
            val evt = events[i]
            if (evt.isDown) {
                activeDowns[evt.key] = evt
            } else {
                val downEvt = activeDowns.remove(evt.key)
                if (downEvt != null) {
                    val duration = (evt.timestampMs - downEvt.timestampMs).coerceIn(20L, 5000L)
                    downDurations[downEvt] = duration
                    val downIndex = events.indexOf(downEvt)
                    var hasIntervening = false
                    for (j in (downIndex + 1) until i) {
                        if (events[j].key != evt.key) {
                            hasIntervening = true
                            break
                        }
                    }
                    if (!hasIntervening) {
                        isCleanTap.add(downEvt)
                    }
                }
            }
        }

        var lastEventTime = events.first().timestampMs
        val currentlyHeld = HashSet<String>()

        for (i in events.indices) {
            val evt = events[i]
            val waitDelay = evt.timestampMs - lastEventTime

            if (evt.isDown) {
                if (isCleanTap.contains(evt)) {
                    if (waitDelay >= 20L) {
                        steps.add(MacroStep(MacroActionType.WAIT, "", waitDelay.coerceIn(20L, 5000L)))
                    }
                    val duration = downDurations[evt] ?: 50L
                    steps.add(MacroStep(MacroActionType.TAP, evt.key, duration))
                    lastEventTime = evt.timestampMs + duration
                } else {
                    if (waitDelay >= 20L) {
                        steps.add(MacroStep(MacroActionType.WAIT, "", waitDelay.coerceIn(20L, 5000L)))
                    }
                    steps.add(MacroStep(MacroActionType.HOLD, evt.key, 0))
                    currentlyHeld.add(evt.key)
                    lastEventTime = evt.timestampMs
                }
            } else {
                val matchingDown = events.subList(0, i).lastOrNull { it.isDown && it.key == evt.key }
                if (matchingDown != null && isCleanTap.contains(matchingDown)) {
                    continue
                }
                if (currentlyHeld.contains(evt.key)) {
                    if (waitDelay >= 20L) {
                        steps.add(MacroStep(MacroActionType.WAIT, "", waitDelay.coerceIn(20L, 5000L)))
                    }
                    steps.add(MacroStep(MacroActionType.RELEASE, evt.key, 0))
                    currentlyHeld.remove(evt.key)
                    lastEventTime = evt.timestampMs
                }
            }
        }

        for (k in currentlyHeld) {
            steps.add(MacroStep(MacroActionType.RELEASE, k, 0))
        }

        while (steps.isNotEmpty() && steps.last().action == MacroActionType.WAIT) {
            steps.removeAt(steps.size - 1)
        }
        return steps
    }

    private fun showKeyPickerForMacro(title: String, onChosen: (String) -> Unit) {
        val names = VALID_KEY_LIST.map { "${it.displayName} [${it.code}]" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                onChosen(VALID_KEY_LIST[which].code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDelayInputDialog(currentMs: Long, onChosen: (Long) -> Unit) {
        val options = listOf("20ms" to 20L, "30ms" to 30L, "50ms" to 50L, "80ms" to 80L, "100ms" to 100L, "150ms" to 150L, "250ms" to 250L, "500ms" to 500L, "Custom..." to -1L)
        val names = options.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Wait Delay (Milliseconds)")
            .setItems(names) { _, which ->
                val chosen = options[which]
                if (chosen.second > 0) {
                    onChosen(chosen.second)
                } else {
                    val input = EditText(this).apply {
                        setText(currentMs.toString())
                        inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        setPadding(32, 16, 32, 16)
                    }
                    AlertDialog.Builder(this)
                        .setTitle("Enter Custom Delay (ms)")
                        .setView(input)
                        .setPositiveButton("OK") { _, _ ->
                            val ms = input.text.toString().toLongOrNull()?.coerceIn(5L, 10000L) ?: 50L
                            onChosen(ms)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditStepDialog(step: MacroStep, onUpdated: () -> Unit) {
        when (step.action) {
            MacroActionType.TAP -> {
                val opts = arrayOf("Change Key [Current: ${step.key}]", "Change Tap Duration [Current: ${step.durationMs}ms]")
                AlertDialog.Builder(this)
                    .setTitle("Edit Tap Step")
                    .setItems(opts) { _, which ->
                        if (which == 0) {
                            showKeyPickerForMacro("Change Key") { newKey ->
                                step.key = newKey
                                onUpdated()
                            }
                        } else {
                            showDelayInputDialog(step.durationMs) { newMs ->
                                step.durationMs = newMs
                                onUpdated()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            MacroActionType.WAIT -> {
                showDelayInputDialog(step.durationMs) { newMs ->
                    step.durationMs = newMs
                    onUpdated()
                }
            }
            MacroActionType.HOLD, MacroActionType.RELEASE -> {
                showKeyPickerForMacro("Change Key") { newKey ->
                    step.key = newKey
                    onUpdated()
                }
            }
        }
    }

    private fun showMacroStudioDialog(targetElement: HudElement? = null, initialSteps: List<MacroStep>? = null) {
        val selected = targetElement ?: controllerView.selectedElement ?: return
        if (selected.type != ElementType.BUTTON) return

        val currentSteps = mutableListOf<MacroStep>()
        if (initialSteps != null) {
            currentSteps.addAll(initialSteps)
        } else if (selected.customMacro.isNotEmpty()) {
            currentSteps.addAll(MacroStep.listFromJson(selected.customMacro))
        } else if (selected.macroType.isNotEmpty()) {
            when (selected.macroType) {
                "slide_cancel" -> {
                    currentSteps.add(MacroStep(MacroActionType.TAP, "c", 50))
                    currentSteps.add(MacroStep(MacroActionType.WAIT, "", 20))
                    currentSteps.add(MacroStep(MacroActionType.TAP, "c", 50))
                    currentSteps.add(MacroStep(MacroActionType.WAIT, "", 20))
                    currentSteps.add(MacroStep(MacroActionType.TAP, "space", 60))
                }
                "shoot_melee" -> {
                    currentSteps.add(MacroStep(MacroActionType.TAP, "mouse_left", 40))
                    currentSteps.add(MacroStep(MacroActionType.WAIT, "", 30))
                    currentSteps.add(MacroStep(MacroActionType.TAP, "v", 60))
                }
                "super_jump" -> {
                    currentSteps.add(MacroStep(MacroActionType.HOLD, "space", 0))
                    currentSteps.add(MacroStep(MacroActionType.HOLD, "c", 0))
                    currentSteps.add(MacroStep(MacroActionType.WAIT, "", 80))
                    currentSteps.add(MacroStep(MacroActionType.RELEASE, "space", 0))
                    currentSteps.add(MacroStep(MacroActionType.RELEASE, "c", 0))
                }
                "armor_plate" -> {
                    currentSteps.add(MacroStep(MacroActionType.TAP, "4", 2500))
                }
                "quick_180" -> {
                    currentSteps.add(MacroStep(MacroActionType.TAP, "right", 150))
                }
            }
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 16, 28, 12)
            setBackgroundColor(Color.parseColor("#161B22"))
        }

        // --- 1. Header Bar ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = "⚡ Macro Studio: ${selected.label.ifEmpty { selected.id.uppercase() }}"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#8B949E"))
            background = null
            setPadding(8, 0, 8, 0)
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dialogView.addView(headerRow)

        // --- 2. Presets Quick Bar ---
        val presetScroll = HorizontalScrollView(this).apply {
            setPadding(0, 10, 0, 10)
            isHorizontalScrollBarEnabled = false
        }
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val presetLabel = TextView(this).apply {
            text = "Presets: "
            textSize = 11f
            setTextColor(Color.parseColor("#8B949E"))
            gravity = Gravity.CENTER_VERTICAL
        }
        presetRow.addView(presetLabel)

        fun createPresetBtn(name: String, block: () -> Unit) = Button(this).apply {
            text = name
            textSize = 10f
            setTextColor(Color.parseColor("#58A6FF"))
            background = createCardDrawable(Color.parseColor("#21262D"), 10f, Color.parseColor("#30363D"), 1)
            setPadding(14, 4, 14, 4)
            setOnClickListener { block() }
        }

        var updateStudioUiRef: (() -> Unit)? = null

        presetRow.addView(createPresetBtn("Slide Cancel") {
            currentSteps.clear()
            currentSteps.add(MacroStep(MacroActionType.TAP, "c", 50))
            currentSteps.add(MacroStep(MacroActionType.WAIT, "", 20))
            currentSteps.add(MacroStep(MacroActionType.TAP, "c", 50))
            currentSteps.add(MacroStep(MacroActionType.WAIT, "", 20))
            currentSteps.add(MacroStep(MacroActionType.TAP, "space", 60))
            updateStudioUiRef?.invoke()
            Toast.makeText(this, "Loaded Slide Cancel preset", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        presetRow.addView(createPresetBtn("Shoot + Melee") {
            currentSteps.clear()
            currentSteps.add(MacroStep(MacroActionType.TAP, "mouse_left", 40))
            currentSteps.add(MacroStep(MacroActionType.WAIT, "", 30))
            currentSteps.add(MacroStep(MacroActionType.TAP, "v", 60))
            updateStudioUiRef?.invoke()
            Toast.makeText(this, "Loaded Shoot + Melee preset", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        presetRow.addView(createPresetBtn("Super Jump") {
            currentSteps.clear()
            currentSteps.add(MacroStep(MacroActionType.HOLD, "space", 0))
            currentSteps.add(MacroStep(MacroActionType.HOLD, "c", 0))
            currentSteps.add(MacroStep(MacroActionType.WAIT, "", 80))
            currentSteps.add(MacroStep(MacroActionType.RELEASE, "space", 0))
            currentSteps.add(MacroStep(MacroActionType.RELEASE, "c", 0))
            updateStudioUiRef?.invoke()
            Toast.makeText(this, "Loaded Super Jump preset", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        presetRow.addView(createPresetBtn("Armor Plate") {
            currentSteps.clear()
            currentSteps.add(MacroStep(MacroActionType.TAP, "4", 2500))
            updateStudioUiRef?.invoke()
            Toast.makeText(this, "Loaded Armor Plate preset", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        presetRow.addView(createPresetBtn("Clear All") {
            currentSteps.clear()
            updateStudioUiRef?.invoke()
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        presetScroll.addView(presetRow)
        dialogView.addView(presetScroll)

        // --- 3. Mode Tab Selector ---
        var currentTab = 0 // 0: Visual Builder, 1: Text Script
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }

        val tabVisualBtn = Button(this).apply {
            text = "🧩 Visual Builder"
            textSize = 12f
            setPadding(20, 8, 20, 8)
        }
        val tabScriptBtn = Button(this).apply {
            text = "📝 Script / Text"
            textSize = 12f
            setPadding(20, 8, 20, 8)
        }
        val recLiveBtn = Button(this).apply {
            text = "🔴 Live Record"
            textSize = 12f
            setTextColor(Color.parseColor("#FF6B6B"))
            background = createCardDrawable(Color.parseColor("#3D1414"), 12f, Color.parseColor("#FF6B6B"), 1)
            setPadding(20, 8, 20, 8)
        }

        tabRow.addView(tabVisualBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        tabRow.addView(tabScriptBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        tabRow.addView(recLiveBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dialogView.addView(tabRow)

        // --- 4. Main Body: Visual View vs Script View ---
        val contentContainer = FrameLayout(this)

        // VISUAL CONTAINER
        val visualContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val stepsScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 210)
        }
        val stepsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        stepsScrollView.addView(stepsLayout)
        visualContainer.addView(stepsScrollView)

        // Visual Add Action Bar
        val addBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        fun createAddBtn(text: String, color: Int, block: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createCardDrawable(color, 12f)
            setPadding(12, 6, 12, 6)
            setOnClickListener { block() }
        }

        addBar.addView(createAddBtn("+ Tap Key", Color.parseColor("#1F6FEB")) {
            showKeyPickerForMacro("Tap Key") { chosenKey ->
                currentSteps.add(MacroStep(MacroActionType.TAP, chosenKey, 50))
                updateStudioUiRef?.invoke()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })

        addBar.addView(createAddBtn("+ Wait Delay", Color.parseColor("#388E3C")) {
            showDelayInputDialog(50) { ms ->
                currentSteps.add(MacroStep(MacroActionType.WAIT, "", ms))
                updateStudioUiRef?.invoke()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })

        addBar.addView(createAddBtn("+ Hold", Color.parseColor("#E65100")) {
            showKeyPickerForMacro("Hold Key Down") { chosenKey ->
                currentSteps.add(MacroStep(MacroActionType.HOLD, chosenKey, 0))
                updateStudioUiRef?.invoke()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })

        addBar.addView(createAddBtn("+ Release", Color.parseColor("#6A1B9A")) {
            showKeyPickerForMacro("Release Key Up") { chosenKey ->
                currentSteps.add(MacroStep(MacroActionType.RELEASE, chosenKey, 0))
                updateStudioUiRef?.invoke()
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        visualContainer.addView(addBar)
        contentContainer.addView(visualContainer)

        // SCRIPT CONTAINER
        val scriptContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val scriptHint = TextView(this).apply {
            text = "Syntax: TAP <key> <ms> | WAIT <ms> | HOLD <key> | RELEASE <key> (or shorthand: c:50, 30, space:60)"
            textSize = 10f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 6)
        }
        scriptContainer.addView(scriptHint)

        val scriptEdit = EditText(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#58A6FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            background = createCardDrawable(Color.parseColor("#0D1117"), 12f, Color.parseColor("#30363D"), 1)
            setPadding(16, 12, 16, 12)
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 6
        }
        scriptContainer.addView(scriptEdit)

        val scriptBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        val copyScriptBtn = Button(this).apply {
            text = "📋 Copy Script"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#21262D"), 12f, Color.parseColor("#58A6FF"), 1)
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("VirtualPad Macro", scriptEdit.text.toString()))
                Toast.makeText(this@MainActivity, "Macro script copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
        val pasteScriptBtn = Button(this).apply {
            text = "📥 Paste"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#21262D"), 12f, Color.parseColor("#58A6FF"), 1)
            setPadding(14, 6, 14, 6)
            setOnClickListener {
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    scriptEdit.setText(text)
                    Toast.makeText(this@MainActivity, "Pasted script from clipboard", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val applyScriptBtn = Button(this).apply {
            text = "✓ Apply to Steps"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                val parsed = MacroStep.parseScriptText(scriptEdit.text.toString())
                currentSteps.clear()
                currentSteps.addAll(parsed)
                currentTab = 0
                updateStudioUiRef?.invoke()
                Toast.makeText(this@MainActivity, "Parsed ${parsed.size} macro steps!", Toast.LENGTH_SHORT).show()
            }
        }
        scriptBar.addView(copyScriptBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        scriptBar.addView(pasteScriptBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })
        scriptBar.addView(applyScriptBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        scriptContainer.addView(scriptBar)

        contentContainer.addView(scriptContainer)
        dialogView.addView(contentContainer)

        // --- 5. Footer Action Bar ---
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val testBtn = Button(this).apply {
            text = "▶️ Test Macro"
            textSize = 12f
            setTextColor(Color.parseColor("#00E676"))
            background = createCardDrawable(Color.parseColor("#143D24"), 12f, Color.parseColor("#00E676"), 1)
            setPadding(16, 8, 16, 8)
        }

        val cancelBtn = Button(this).apply {
            text = "Cancel"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            background = createCardDrawable(Color.parseColor("#21262D"), 12f)
            setPadding(16, 8, 16, 8)
        }

        val saveBtn = Button(this).apply {
            text = "💾 Save & Apply"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = createCardDrawable(Color.parseColor("#E040FB"), 12f)
            setPadding(20, 8, 20, 8)
        }

        footerRow.addView(testBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f).apply { rightMargin = 8 })
        footerRow.addView(cancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f).apply { rightMargin = 8 })
        footerRow.addView(saveBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        dialogView.addView(footerRow)

        fun updateUi() {
            if (currentTab == 0) {
                tabVisualBtn.setTextColor(Color.WHITE)
                tabVisualBtn.background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
                tabScriptBtn.setTextColor(Color.parseColor("#8B949E"))
                tabScriptBtn.background = createCardDrawable(Color.parseColor("#21262D"), 12f)
                visualContainer.visibility = View.VISIBLE
                scriptContainer.visibility = View.GONE

                stepsLayout.removeAllViews()
                if (currentSteps.isEmpty()) {
                    val emptyTv = TextView(this).apply {
                        text = "No macro steps yet.\nUse [+ Tap Key], [+ Wait Delay], or [🔴 Live Record] to build your combo."
                        textSize = 12f
                        setTextColor(Color.parseColor("#8B949E"))
                        gravity = Gravity.CENTER
                        setPadding(16, 40, 16, 40)
                    }
                    stepsLayout.addView(emptyTv)
                } else {
                    for (i in currentSteps.indices) {
                        val step = currentSteps[i]
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            setPadding(16, 10, 12, 10)
                            background = createCardDrawable(Color.parseColor("#21262D"), 10f, Color.parseColor("#30363D"), 1)
                        }

                        val stepNum = TextView(this).apply {
                            text = "${i + 1}."
                            textSize = 12f
                            setTextColor(Color.parseColor("#58A6FF"))
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setPadding(0, 0, 10, 0)
                        }
                        card.addView(stepNum)

                        val stepDesc = TextView(this).apply {
                            text = when (step.action) {
                                MacroActionType.TAP -> "⬇️ Tap '${step.key.uppercase()}' (${step.durationMs}ms)"
                                MacroActionType.HOLD -> "⬇️ Hold '${step.key.uppercase()}'"
                                MacroActionType.RELEASE -> "⬆️ Release '${step.key.uppercase()}'"
                                MacroActionType.WAIT -> "⏱️ Wait ${step.durationMs}ms"
                            }
                            textSize = 12f
                            setTextColor(Color.WHITE)
                        }
                        card.addView(stepDesc, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

                        val editBtn = Button(this).apply {
                            text = "✏️"
                            textSize = 11f
                            setTextColor(Color.WHITE)
                            background = createCardDrawable(Color.parseColor("#30363D"), 8f)
                            setPadding(12, 4, 12, 4)
                            setOnClickListener {
                                showEditStepDialog(step) { updateUi() }
                            }
                        }
                        card.addView(editBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })

                        if (i > 0) {
                            val upBtn = Button(this).apply {
                                text = "▲"
                                textSize = 11f
                                setTextColor(Color.WHITE)
                                background = createCardDrawable(Color.parseColor("#30363D"), 8f)
                                setPadding(10, 4, 10, 4)
                                setOnClickListener {
                                    val temp = currentSteps[i - 1]
                                    currentSteps[i - 1] = currentSteps[i]
                                    currentSteps[i] = temp
                                    updateUi()
                                }
                            }
                            card.addView(upBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
                        }

                        if (i < currentSteps.size - 1) {
                            val downBtn = Button(this).apply {
                                text = "▼"
                                textSize = 11f
                                setTextColor(Color.WHITE)
                                background = createCardDrawable(Color.parseColor("#30363D"), 8f)
                                setPadding(10, 4, 10, 4)
                                setOnClickListener {
                                    val temp = currentSteps[i + 1]
                                    currentSteps[i + 1] = currentSteps[i]
                                    currentSteps[i] = temp
                                    updateUi()
                                }
                            }
                            card.addView(downBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
                        }

                        val delBtn = Button(this).apply {
                            text = "🗑️"
                            textSize = 11f
                            setTextColor(Color.parseColor("#FF6B6B"))
                            background = createCardDrawable(Color.parseColor("#3D1414"), 8f)
                            setPadding(10, 4, 10, 4)
                            setOnClickListener {
                                currentSteps.removeAt(i)
                                updateUi()
                            }
                        }
                        card.addView(delBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

                        stepsLayout.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8 })
                    }
                }
            } else {
                tabVisualBtn.setTextColor(Color.parseColor("#8B949E"))
                tabVisualBtn.background = createCardDrawable(Color.parseColor("#21262D"), 12f)
                tabScriptBtn.setTextColor(Color.WHITE)
                tabScriptBtn.background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
                visualContainer.visibility = View.GONE
                scriptContainer.visibility = View.VISIBLE
                scriptEdit.setText(MacroStep.toScriptText(currentSteps))
            }
        }
        updateStudioUiRef = ::updateUi

        tabVisualBtn.setOnClickListener {
            if (currentTab == 1) {
                val parsed = MacroStep.parseScriptText(scriptEdit.text.toString())
                currentSteps.clear()
                currentSteps.addAll(parsed)
            }
            currentTab = 0
            updateUi()
        }

        tabScriptBtn.setOnClickListener {
            currentTab = 1
            updateUi()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        closeBtn.setOnClickListener { dialog.dismiss() }
        cancelBtn.setOnClickListener { dialog.dismiss() }

        recLiveBtn.setOnClickListener {
            dialog.dismiss()
            startLiveRecording(selected)
        }

        testBtn.setOnClickListener {
            if (currentTab == 1) {
                val parsed = MacroStep.parseScriptText(scriptEdit.text.toString())
                currentSteps.clear()
                currentSteps.addAll(parsed)
            }
            if (currentSteps.isEmpty()) {
                Toast.makeText(this@MainActivity, "No steps to test. Add steps or use Live Record!", Toast.LENGTH_SHORT).show()
            } else {
                controllerView.testMacro(currentSteps)
                Toast.makeText(this@MainActivity, "▶️ Testing ${currentSteps.size} macro steps...", Toast.LENGTH_SHORT).show()
            }
        }

        saveBtn.setOnClickListener {
            if (currentTab == 1) {
                val parsed = MacroStep.parseScriptText(scriptEdit.text.toString())
                currentSteps.clear()
                currentSteps.addAll(parsed)
            }
            if (currentSteps.isEmpty()) {
                controllerView.updateSelectedMacro("", "")
                updateInspector(selected)
                Toast.makeText(this@MainActivity, "Macro cleared for this button", Toast.LENGTH_SHORT).show()
            } else {
                val json = MacroStep.listToJson(currentSteps)
                controllerView.updateSelectedMacro("custom", json)
                updateInspector(selected)
                Toast.makeText(this@MainActivity, "Saved macro combo (${currentSteps.size} steps)!", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { w ->
            val dm = resources.displayMetrics
            val targetWidth = (dm.widthPixels * 0.88f).toInt()
            val targetHeight = (dm.heightPixels * 0.90f).toInt()
            w.setLayout(targetWidth, targetHeight)
        }
        updateUi()
    }

    private fun showKeyPickerDialog(targetEl: HudElement? = null, onKeyChosen: ((String) -> Unit)? = null) {
        val selected = targetEl ?: controllerView.selectedElement ?: return
        val names = VALID_KEY_LIST.map { "${it.displayName} [${it.code}]" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Assign Key to ${selected.label.ifEmpty { selected.id.uppercase() }}")
            .setItems(names) { _, which ->
                val chosen = VALID_KEY_LIST[which]
                val keyDisplay = formatKeyDisplay(chosen.code)
                val newLabel = if (selected.label.isNotEmpty() && !selected.label.startsWith("C") && !selected.label.startsWith(selected.id.uppercase())) {
                    selected.label
                } else if (selected.isCustom) {
                    "C${selected.customSlot + 1} ($keyDisplay)"
                } else {
                    "${selected.id.uppercase()} ($keyDisplay)"
                }
                controllerView.updateSelectedKey(chosen.code, newLabel)
                updateInspector(selected)
                onKeyChosen?.invoke(chosen.code)
                Toast.makeText(this, "Assigned key: ${chosen.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStickSettingsDialog(targetEl: HudElement) {
        controllerView.selectElement(targetEl)

        var selectedSprint = controllerView.stickSprintMode
        var selectedFloating = controllerView.stickFloatingMode
        var selectedStickTouchScale = controllerView.stickTouchScale
        val initialStickTouchScale = controllerView.stickTouchScale

        val dialog = AlertDialog.Builder(this).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            background = createCardDrawable(Color.parseColor("#161B22"), 20f, Color.parseColor("#30363D"), 2)
        }

        // Header
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }
        val titleText = TextView(this).apply {
            text = "⚙️ Joystick Movement Mode"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#8B949E"))
            background = null
            setPadding(8, 0, 8, 0)
            setOnClickListener {
                controllerView.stickTouchScale = initialStickTouchScale
                dialog.dismiss()
            }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dialogView.addView(headerRow)

        // Description
        val descText = TextView(this).apply {
            text = "Choose joystick behavior for movement during gameplay:"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 14)
        }
        dialogView.addView(descText)

        // Option 1: Sprint Mode Card
        val sprintCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 14)
            isClickable = true
            isFocusable = true
        }
        val sprintRadio = RadioButton(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#58A6FF"))
        }
        sprintCard.addView(sprintRadio, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val sprintTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 0, 0)
        }
        val sprintTitle = TextView(this).apply {
            text = "⚡ Sprint Mode (Auto-Sprint + Notch)"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val sprintDesc = TextView(this).apply {
            text = "Auto-sprint (Shift) on tilt >80%. Drag stick into 🏃 notch to lock auto-run."
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 2, 0, 0)
        }
        sprintTextCol.addView(sprintTitle)
        sprintTextCol.addView(sprintDesc)
        sprintCard.addView(sprintTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        dialogView.addView(sprintCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 10
        })

        // Option 2: Walk / Normal Mode Card
        val normalCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 14)
            isClickable = true
            isFocusable = true
        }
        val normalRadio = RadioButton(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#58A6FF"))
        }
        normalCard.addView(normalRadio, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val normalTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 0, 0)
        }
        val normalTitle = TextView(this).apply {
            text = "🚶 Walk / Normal Mode"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val normalDesc = TextView(this).apply {
            text = "Standard WASD movement only. No auto-sprint, no sprint notch lock."
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 2, 0, 0)
        }
        normalTextCol.addView(normalTitle)
        normalTextCol.addView(normalDesc)
        normalCard.addView(normalTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        dialogView.addView(normalCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        fun updateSelection() {
            sprintRadio.isChecked = selectedSprint
            normalRadio.isChecked = !selectedSprint

            sprintCard.background = if (selectedSprint) {
                createCardDrawable(Color.parseColor("#1B2A3D"), 12f, Color.parseColor("#58A6FF"), 2)
            } else {
                createCardDrawable(Color.parseColor("#0D1117"), 12f, Color.parseColor("#30363D"), 1)
            }

            normalCard.background = if (!selectedSprint) {
                createCardDrawable(Color.parseColor("#1B2A3D"), 12f, Color.parseColor("#58A6FF"), 2)
            } else {
                createCardDrawable(Color.parseColor("#0D1117"), 12f, Color.parseColor("#30363D"), 1)
            }
        }

        sprintCard.setOnClickListener {
            selectedSprint = true
            updateSelection()
        }

        normalCard.setOnClickListener {
            selectedSprint = false
            updateSelection()
        }

        updateSelection()

        // Option 3: Floating / Dynamic Joystick Card
        val floatingCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 14, 16, 14)
            isClickable = true
            isFocusable = true
        }
        val floatingCheck = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
            isChecked = selectedFloating
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#58A6FF"))
        }
        floatingCard.addView(floatingCheck, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val floatingTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 0, 0)
        }
        val floatingTitle = TextView(this).apply {
            text = "📍 Floating / Dynamic Joystick"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val floatingDesc = TextView(this).apply {
            text = "Auto-spawn joystick at thumb touch in the left empty area"
            textSize = 12f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 2, 0, 0)
        }
        floatingTextCol.addView(floatingTitle)
        floatingTextCol.addView(floatingDesc)
        floatingCard.addView(floatingTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun updateFloatingCard() {
            floatingCheck.isChecked = selectedFloating
            floatingCard.background = if (selectedFloating) {
                createCardDrawable(Color.parseColor("#1B2A3D"), 12f, Color.parseColor("#58A6FF"), 2)
            } else {
                createCardDrawable(Color.parseColor("#0D1117"), 12f, Color.parseColor("#30363D"), 1)
            }
        }
        floatingCard.setOnClickListener {
            selectedFloating = !selectedFloating
            updateFloatingCard()
        }
        updateFloatingCard()

        dialogView.addView(floatingCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 10
        })

        // Section: Joystick Outside Touch Detection Area (Catchment Zone)
        val stickAreaLabel = TextView(this).apply {
            text = "🎯 TOUCH DETECTION AREA (OUTSIDE DIRECTION):"
            textSize = 12f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 4)
        }
        dialogView.addView(stickAreaLabel)

        val stickAreaDesc = TextView(this).apply {
            text = "Tap near outside of joystick to automatically snap stick head in that direction and move immediately."
            textSize = 11f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 6)
        }
        dialogView.addView(stickAreaDesc)

        val stickScaleDisplay = TextView(this).apply {
            text = "Catchment Radius: ${String.format("%.2f", selectedStickTouchScale)}x" + if (selectedStickTouchScale > 1.0f) " (Active)" else " (Exact Boundary Only)"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 4)
        }
        dialogView.addView(stickScaleDisplay)

        val stickSeekBar = SeekBar(this).apply {
            max = 150 // 100 to 250 -> 1.00x to 2.50x
            progress = ((selectedStickTouchScale * 100).toInt() - 100).coerceIn(0, 150)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val scale = (prog + 100) / 100f
                    stickScaleDisplay.text = "Catchment Radius: ${String.format("%.2f", scale)}x" + if (scale > 1.0f) " (Active)" else " (Exact Boundary Only)"
                    if (fromUser) {
                        selectedStickTouchScale = scale
                        controllerView.stickTouchScale = scale
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        dialogView.addView(stickSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Quick Preset Chips for Stick Touch Scale: 1.0x, 1.4x, 1.8x, 2.2x, 2.5x
        val stickChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 4, 0, 8)
        }
        val stickChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val stickPresets = listOf(
            1.0f to "1.0x (Exact)",
            1.4f to "1.4x",
            1.8f to "1.8x (Default)",
            2.2f to "2.2x",
            2.5f to "2.5x (Wide)"
        )
        for ((scaleVal, scaleText) in stickPresets) {
            val chip = Button(this).apply {
                text = scaleText
                textSize = 10f
                setTextColor(Color.parseColor("#C9D1D9"))
                background = createCardDrawable(Color.parseColor("#21262D"), 8f)
                setPadding(12, 4, 12, 4)
                setOnClickListener {
                    stickSeekBar.progress = ((scaleVal * 100).toInt() - 100).coerceIn(0, 150)
                    stickScaleDisplay.text = "Catchment Radius: ${String.format("%.2f", scaleVal)}x" + if (scaleVal > 1.0f) " (Active)" else " (Exact Boundary Only)"
                    selectedStickTouchScale = scaleVal
                    controllerView.stickTouchScale = scaleVal
                }
            }
            stickChipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        stickChipScroll.addView(stickChipRow)
        dialogView.addView(stickChipScroll)

        // Apply button
        val applyBtn = Button(this).apply {
            text = "✅ Apply & Save"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#238636"), 12f)
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                controllerView.stickSprintMode = selectedSprint
                HudConfig.setStickSprintMode(this@MainActivity, selectedSprint)
                controllerView.stickFloatingMode = selectedFloating
                HudConfig.setStickFloatingMode(this@MainActivity, selectedFloating)
                controllerView.stickTouchScale = selectedStickTouchScale
                HudConfig.setStickTouchScale(this@MainActivity, selectedStickTouchScale)
                // Reset any active sprint state when switching to normal
                if (!selectedSprint) {
                    controllerView.resetSprint()
                }
                updateInspector(targetEl)
                val statusStr = if (selectedSprint) "⚡ Sprint" else "🚶 Normal"
                val floatStr = if (selectedFloating) " + 📍 Floating" else ""
                Toast.makeText(this@MainActivity,
                    "$statusStr Mode$floatStr enabled",
                    Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        val btnParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 18 }
        dialogView.addView(applyBtn, btnParams)

        dialog.setOnCancelListener {
            controllerView.stickTouchScale = initialStickTouchScale
        }
        dialog.setView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showKeySettingsDialog(targetElement: HudElement? = null) {
        val selected = targetElement ?: controllerView.selectedElement ?: return
        if (selected.type != ElementType.BUTTON) return

        controllerView.selectElement(selected)

        val dialog = AlertDialog.Builder(this).create()
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 16, 28, 16)
            background = createCardDrawable(Color.parseColor("#161B22"), 20f, Color.parseColor("#30363D"), 2)
        }

        // --- 1. Header Row ---
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }
        val titleText = TextView(this).apply {
            text = "⚙️ Key Settings & Stats: ${selected.label.ifEmpty { selected.id.uppercase() }}"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(titleText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.parseColor("#8B949E"))
            background = null
            setPadding(8, 0, 8, 0)
            setOnClickListener { dialog.dismiss() }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dialogView.addView(headerRow)

        // --- 2. Main Two-Column Content ---
        val columnsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }

        // === LEFT COLUMN (Key Name, Binding, and Live Stats Card) ===
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 16, 0)
        }

        // Key Name / Label Section
        val nameLabel = TextView(this).apply {
            text = "KEY NAME / LABEL:"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        leftCol.addView(nameLabel)

        val nameInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameEdit = EditText(this).apply {
            setText(selected.label.ifEmpty { selected.id.uppercase() })
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#6E7681"))
            hint = "Button Label"
            background = createCardDrawable(Color.parseColor("#0D1117"), 10f, Color.parseColor("#30363D"), 1)
            setPadding(20, 12, 20, 12)
            isSingleLine = true
        }
        nameInputRow.addView(nameEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val applyNameBtn = Button(this).apply {
            text = "Apply"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#238636"), 10f)
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                val newName = nameEdit.text.toString().trim()
                if (newName.isNotEmpty()) {
                    controllerView.updateSelectedLabel(newName)
                    titleText.text = "⚙️ Key Settings & Stats: $newName"
                    updateInspector(selected)
                    Toast.makeText(this@MainActivity, "Label updated to: $newName", Toast.LENGTH_SHORT).show()
                }
            }
        }
        nameInputRow.addView(applyNameBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = 8 })
        leftCol.addView(nameInputRow)

        // Quick Preset Label Chips (Aim, Shoot, Reload, Jump, Crouch, Heal, Sprint, Cover, Map, Ping)
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 8, 0, 8)
        }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val quickLabels = listOf("Aim", "Shoot", "Reload", "Jump", "Crouch", "Sprint", "Heal", "Cover", "Map", "Ping", "Ability")
        for (qLabel in quickLabels) {
            val chip = Button(this).apply {
                text = qLabel
                textSize = 10f
                setTextColor(Color.parseColor("#C9D1D9"))
                background = createCardDrawable(Color.parseColor("#21262D"), 8f)
                setPadding(12, 4, 12, 4)
                setOnClickListener {
                    nameEdit.setText(qLabel)
                    controllerView.updateSelectedLabel(qLabel)
                    titleText.text = "⚙️ Key Settings & Stats: $qLabel"
                    updateInspector(selected)
                }
            }
            chipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        chipScroll.addView(chipRow)
        leftCol.addView(chipScroll)

        // PC Key Binding Picker
        val bindKeyBtn = Button(this).apply {
            text = "⌨️ Bound Key: ${formatKeyDisplay(selected.key)} [${selected.key}]"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 10f)
            setPadding(16, 10, 16, 10)
            setOnClickListener {
                showKeyPickerDialog(selected) { newKey ->
                    text = "⌨️ Bound Key: ${formatKeyDisplay(newKey)} [$newKey]"
                    updateInspector(selected)
                }
            }
        }
        leftCol.addView(bindKeyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6; bottomMargin = 10 })

        // Live Key Stats Card
        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createCardDrawable(Color.parseColor("#0D1117"), 12f, Color.parseColor("#30363D"), 1)
            setPadding(16, 12, 16, 12)
        }
        val statsHeader = TextView(this).apply {
            text = "📊 LIVE KEY STATS"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 6)
        }
        statsCard.addView(statsHeader)

        val statsModeText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsShapeText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsRotationText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsScaleText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsMacroText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsSwipeAimText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsTouchPaddingText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsGhostText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }
        val statsDragDistanceText = TextView(this).apply { textSize = 11f; setTextColor(Color.parseColor("#C9D1D9")); setPadding(0, 2, 0, 2) }

        statsCard.addView(statsModeText)
        statsCard.addView(statsShapeText)
        statsCard.addView(statsRotationText)
        statsCard.addView(statsScaleText)
        statsCard.addView(statsMacroText)
        statsCard.addView(statsSwipeAimText)
        statsCard.addView(statsGhostText)
        statsCard.addView(statsDragDistanceText)
        statsCard.addView(statsTouchPaddingText)
        leftCol.addView(statsCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        columnsLayout.addView(leftCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f))

        // === RIGHT COLUMN (Shape, Rotation Slider, Mode & Turbo CPS, Scale, Actions) in ScrollView ===
        val rightScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 0, 0, 0)
        }

        // --- SECTION A: Key Shape Selection ---
        val shapeLabel = TextView(this).apply {
            text = "KEY SHAPE:"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        }
        rightCol.addView(shapeLabel)

        val shapeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shapeBtns = mutableListOf<Button>()
        val shapes = listOf(ButtonShape.CIRCLE to "⚪ Circle", ButtonShape.SQUARE to "⬛ Square", ButtonShape.ROUNDED_RECT to "💊 Pill")

        fun updateShapeButtons() {
            shapes.forEachIndexed { idx, (shape, _) ->
                val btn = shapeBtns[idx]
                if (selected.shape == shape) {
                    btn.background = createCardDrawable(Color.parseColor("#1F6FEB"), 10f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.background = createCardDrawable(Color.parseColor("#21262D"), 10f)
                    btn.setTextColor(Color.parseColor("#8B949E"))
                }
            }
        }

        fun updateStatsCard() {
            statsModeText.text = "• Mode: " + when {
                selected.isTurbo -> "Turbo Rapid-Fire (${selected.turboCps} CPS) ⚡"
                selected.isInstantTap -> "Instant Tap (${selected.instantTapDurationMs} ms) ⏱️"
                selected.isToggle -> "Toggle Latch 🔒"
                else -> "Normal Hold"
            }
            statsShapeText.text = "• Shape: " + when (selected.shape) {
                ButtonShape.CIRCLE -> "Circle (360°)"
                ButtonShape.SQUARE -> "Square"
                ButtonShape.ROUNDED_RECT -> "Rounded Pill"
            }
            statsRotationText.text = "• Rotation: ${selected.rotation.toInt()}°"
            statsScaleText.text = "• Scale: ${String.format("%.2f", selected.scale)}x"
            statsMacroText.text = "• Macro: " + if (selected.macroType.isNotEmpty() || selected.customMacro.isNotEmpty()) "Active ⚡" else "Disabled"
            statsSwipeAimText.text = "• Swipe Aim: " + if (selected.swipeToAim) "Active 🎯" else "Disabled"
            statsGhostText.text = "• Ghost Shadow: " + if (selected.showGhostShadow) "Enabled 👻" else "Disabled"
            statsDragDistanceText.text = if (!selected.showGhostShadow) {
                "• Max Drag Reach: Disabled (Ghost Shadow OFF)"
            } else {
                "• Max Drag Reach: ${String.format("%.1f", selected.maxDragDistance)}x" + if (selected.maxDragDistance == 0f) " (Stationary)" else ""
            }
            statsTouchPaddingText.text = "• Hitbox Area: ${String.format("%.2f", selected.touchPadding)}x" + if (selected.touchPadding > 1.0f) " (Nearby Auto-Detect)" else " (Exact Only)"
        }

        shapes.forEach { (shape, label) ->
            val btn = Button(this).apply {
                text = label
                textSize = 11f
                setPadding(14, 6, 14, 6)
                setOnClickListener {
                    controllerView.updateSelectedShape(shape)
                    updateShapeButtons()
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            shapeBtns.add(btn)
            shapeRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })
        }
        updateShapeButtons()
        rightCol.addView(shapeRow)

        // --- SECTION B: Key Shape Rotation ---
        val rotLabel = TextView(this).apply {
            text = "KEY ROTATION: ${selected.rotation.toInt()}°"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 4)
        }
        rightCol.addView(rotLabel)

        val rotSeekBar = SeekBar(this).apply {
            max = 360
            progress = selected.rotation.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    rotLabel.text = "KEY ROTATION: ${prog}°"
                    if (fromUser) {
                        controllerView.updateSelectedRotation(prog.toFloat())
                        updateStatsCard()
                        updateInspector(selected)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rightCol.addView(rotSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Quick Angle Snap Chips: 0°, 45°, 90°, 135°, 180°, 270°
        val angleSnapScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 4, 0, 8)
        }
        val angleSnapRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val snapAngles = listOf(0, 45, 90, 135, 180, 270)
        for (ang in snapAngles) {
            val angBtn = Button(this).apply {
                text = "$ang°"
                textSize = 10f
                setTextColor(Color.parseColor("#C9D1D9"))
                background = createCardDrawable(Color.parseColor("#21262D"), 8f)
                setPadding(12, 4, 12, 4)
                setOnClickListener {
                    rotSeekBar.progress = ang
                    rotLabel.text = "KEY ROTATION: ${ang}°"
                    controllerView.updateSelectedRotation(ang.toFloat())
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            angleSnapRow.addView(angBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        angleSnapScroll.addView(angleSnapRow)
        rightCol.addView(angleSnapScroll)

        // --- SECTION C: Mode Selection (Hold / Toggle / Turbo) ---
        val modeHeader = TextView(this).apply {
            text = "INPUT BEHAVIOR MODE:"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 6, 0, 4)
        }
        rightCol.addView(modeHeader)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeBtns = mutableListOf<Button>()
        val modes = listOf("HOLD" to "Hold", "TOGGLE" to "Toggle 🔒", "TURBO" to "Turbo ⚡", "INSTANT_TAP" to "Instant ⏱️")

        val turboContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
            visibility = if (selected.isTurbo) View.VISIBLE else View.GONE
        }

        val instantTapContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6, 0, 6)
            visibility = if (selected.isInstantTap) View.VISIBLE else View.GONE
        }

        fun updateModeButtons() {
            val currentMode = when {
                selected.isTurbo -> "TURBO"
                selected.isInstantTap -> "INSTANT_TAP"
                selected.isToggle -> "TOGGLE"
                else -> "HOLD"
            }
            modes.forEachIndexed { idx, (modeCode, _) ->
                val btn = modeBtns[idx]
                if (currentMode == modeCode) {
                    val color = when (modeCode) {
                        "TURBO" -> "#FFD600"
                        "INSTANT_TAP" -> "#FF9100"
                        else -> "#1F6FEB"
                    }
                    val textColor = when (modeCode) {
                        "TURBO", "INSTANT_TAP" -> Color.BLACK
                        else -> Color.WHITE
                    }
                    btn.background = createCardDrawable(Color.parseColor(color), 10f)
                    btn.setTextColor(textColor)
                } else {
                    btn.background = createCardDrawable(Color.parseColor("#21262D"), 10f)
                    btn.setTextColor(Color.parseColor("#8B949E"))
                }
            }
            turboContainer.visibility = if (selected.isTurbo) View.VISIBLE else View.GONE
            instantTapContainer.visibility = if (selected.isInstantTap) View.VISIBLE else View.GONE
        }

        modes.forEach { (modeCode, modeTitle) ->
            val btn = Button(this).apply {
                text = modeTitle
                textSize = 10f
                setPadding(8, 6, 8, 6)
                setOnClickListener {
                    when (modeCode) {
                        "HOLD" -> controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = false)
                        "TOGGLE" -> controllerView.updateSelectedButtonMode(isToggle = true, isTurbo = false, isInstantTap = false)
                        "TURBO" -> controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = true, isInstantTap = false)
                        "INSTANT_TAP" -> controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = true)
                    }
                    updateModeButtons()
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            modeBtns.add(btn)
            modeRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 4 })
        }
        updateModeButtons()
        rightCol.addView(modeRow)

        // Turbo CPS Controls (Slider + Preset chips: 6, 10, 12, 16, 20, 25, 30)
        val cpsLabel = TextView(this).apply {
            text = "⚡ TURBO RATE: ${selected.turboCps} CPS (Clicks / Sec)"
            textSize = 10f
            setTextColor(Color.parseColor("#FFD600"))
            setPadding(0, 4, 0, 2)
        }
        turboContainer.addView(cpsLabel)

        val cpsSeekBar = SeekBar(this).apply {
            max = 26 // 4 + 26 = 30
            progress = (selected.turboCps - 4).coerceIn(0, 26)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val rate = prog + 4
                    cpsLabel.text = "⚡ TURBO RATE: $rate CPS (Clicks / Sec)"
                    if (fromUser) {
                        controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = true, turboCps = rate, isInstantTap = false)
                        updateStatsCard()
                        updateInspector(selected)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        turboContainer.addView(cpsSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val cpsChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 2, 0, 4)
        }
        val cpsChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cpsPresets = listOf(6, 10, 12, 16, 20, 25, 30)
        for (cps in cpsPresets) {
            val chip = Button(this).apply {
                text = "${cps} CPS"
                textSize = 9f
                setTextColor(Color.parseColor("#FFD600"))
                background = createCardDrawable(Color.parseColor("#3D3200"), 6f, Color.parseColor("#FFD600"), 1)
                setPadding(10, 3, 10, 3)
                setOnClickListener {
                    cpsSeekBar.progress = cps - 4
                    cpsLabel.text = "⚡ TURBO RATE: $cps CPS (Clicks / Sec)"
                    controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = true, turboCps = cps, isInstantTap = false)
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            cpsChipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        cpsChipScroll.addView(cpsChipRow)
        turboContainer.addView(cpsChipScroll)
        rightCol.addView(turboContainer)

        // Instant Tap Duration Controls (Slider + Preset chips: 5, 10, 15, 25, 50, 100, 200 ms)
        val instantLabel = TextView(this).apply {
            text = "⏱️ INSTANT TAP DURATION: ${selected.instantTapDurationMs} ms"
            textSize = 10f
            setTextColor(Color.parseColor("#FF9100"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 2)
        }
        val instantSub = TextView(this).apply {
            text = "Auto-releases key after set time (One-Shot), even if finger is held down"
            textSize = 9f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 4)
        }
        instantTapContainer.addView(instantLabel)
        instantTapContainer.addView(instantSub)

        val instantSeekBar = SeekBar(this).apply {
            max = 195 // 5 + 195 = 200
            progress = (selected.instantTapDurationMs - 5).coerceIn(0, 195)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val ms = prog + 5
                    instantLabel.text = "⏱️ INSTANT TAP DURATION: ${ms} ms"
                    if (fromUser) {
                        controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = true, instantTapDurationMs = ms)
                        updateStatsCard()
                        updateInspector(selected)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        instantTapContainer.addView(instantSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val instantChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 2, 0, 4)
        }
        val instantChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val instantPresets = listOf(5, 10, 15, 25, 50, 100, 200)
        for (ms in instantPresets) {
            val chip = Button(this).apply {
                text = if (ms == 15) "15 ms (Def)" else "$ms ms"
                textSize = 9f
                setTextColor(Color.parseColor("#FF9100"))
                background = createCardDrawable(Color.parseColor("#3D2200"), 6f, Color.parseColor("#FF9100"), 1)
                setPadding(10, 3, 10, 3)
                setOnClickListener {
                    instantSeekBar.progress = (ms - 5).coerceIn(0, 195)
                    instantLabel.text = "⏱️ INSTANT TAP DURATION: ${ms} ms"
                    controllerView.updateSelectedButtonMode(isToggle = false, isTurbo = false, isInstantTap = true, instantTapDurationMs = ms)
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            instantChipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        instantChipScroll.addView(instantChipRow)
        instantTapContainer.addView(instantChipScroll)
        rightCol.addView(instantTapContainer)

        // --- SECTION: Swipe to Aim (Camera Rotation while holding) ---
        val swipeAimHeader = TextView(this).apply {
            text = "SWIPE TO AIM (CAMERA ROTATION):"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 4)
        }
        rightCol.addView(swipeAimHeader)

        val swipeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 10, 14, 10)
            isClickable = true
            isFocusable = true
        }
        val swipeCheckbox = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#58A6FF"))
            isChecked = selected.swipeToAim
        }
        swipeCard.addView(swipeCheckbox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val swipeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 0, 0, 0)
        }
        val swipeTitle = TextView(this).apply {
            text = "🎯 Swipe to Aim while Holding"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val swipeDesc = TextView(this).apply {
            text = "Drag screen with same thumb while firing/holding this key to control aim & recoil."
            textSize = 10f
            setTextColor(Color.parseColor("#8B949E"))
        }
        swipeTextCol.addView(swipeTitle)
        swipeTextCol.addView(swipeDesc)
        swipeCard.addView(swipeTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        fun updateSwipeCard() {
            swipeCheckbox.isChecked = selected.swipeToAim
            swipeCard.background = if (selected.swipeToAim) {
                createCardDrawable(Color.parseColor("#1B2A3D"), 10f, Color.parseColor("#58A6FF"), 1)
            } else {
                createCardDrawable(Color.parseColor("#0D1117"), 10f, Color.parseColor("#30363D"), 1)
            }
        }
        updateSwipeCard()

        swipeCard.setOnClickListener {
            val newState = !selected.swipeToAim
            controllerView.updateSelectedSwipeToAim(newState)
            updateSwipeCard()
            updateStatsCard()
            updateInspector(selected)
            HudConfig.saveLayout(this@MainActivity, controllerView.elements)
            Toast.makeText(
                this@MainActivity,
                if (newState) "🎯 Swipe-to-Aim enabled for this button" else "Swipe-to-Aim disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
        rightCol.addView(swipeCard)

        // --- SECTION: Ghost Shadow on Drag ---
        val ghostCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 10, 14, 10)
            isClickable = true
            isFocusable = true
        }
        val ghostCheckbox = CheckBox(this).apply {
            isClickable = false
            isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#58A6FF"))
            isChecked = selected.showGhostShadow
        }
        ghostCard.addView(ghostCheckbox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val ghostTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 0, 0, 0)
        }
        val ghostTitle = TextView(this).apply {
            text = "👻 Ghost Shadow on Drag"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val ghostDesc = TextView(this).apply {
            text = "Displays resting shadow at home position while dragging button face."
            textSize = 10f
            setTextColor(Color.parseColor("#8B949E"))
        }
        ghostTextCol.addView(ghostTitle)
        ghostTextCol.addView(ghostDesc)
        ghostCard.addView(ghostTextCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        var updateDragControlsState: (() -> Unit)? = null

        fun updateGhostCard() {
            ghostCheckbox.isChecked = selected.showGhostShadow
            ghostCard.background = if (selected.showGhostShadow) {
                createCardDrawable(Color.parseColor("#1B2A3D"), 10f, Color.parseColor("#58A6FF"), 1)
            } else {
                createCardDrawable(Color.parseColor("#0D1117"), 10f, Color.parseColor("#30363D"), 1)
            }
            updateDragControlsState?.invoke()
        }

        ghostCard.setOnClickListener {
            val newState = !selected.showGhostShadow
            controllerView.updateSelectedGhostShadow(newState)
            updateGhostCard()
            updateStatsCard()
            updateInspector(selected)
            HudConfig.saveLayout(this@MainActivity, controllerView.elements)
            Toast.makeText(
                this@MainActivity,
                if (newState) "👻 Ghost Shadow & Drag enabled for this key" else "Ghost Shadow & Drag disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
        rightCol.addView(ghostCard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 6 })

        // --- SECTION: Max Drag Distance (Visual Drag Reach from Center) ---
        val dragDistHeader = TextView(this).apply {
            text = "MAX DRAG DISTANCE (FROM CENTER):"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 4)
        }
        rightCol.addView(dragDistHeader)

        val dragDistDesc = TextView(this).apply {
            text = "Controls how far the button face can slide from its center while swiping (requires Ghost Shadow ON)."
            textSize = 10f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 4)
        }
        rightCol.addView(dragDistDesc)

        val dragDistLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(0, 2, 0, 4)
        }
        rightCol.addView(dragDistLabel)

        val dragDistSeekBar = SeekBar(this).apply {
            max = 50 // 0 to 50 -> 0.0x to 5.0x
            progress = (selected.maxDragDistance * 10).toInt().coerceIn(0, 50)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val dist = prog / 10f
                    if (selected.showGhostShadow) {
                        dragDistLabel.text = "📏 DRAG REACH: ${String.format("%.1f", dist)}x radius" + if (dist == 0f) " (Stationary / Locked)" else ""
                    }
                    if (fromUser) {
                        controllerView.updateSelectedMaxDragDistance(dist)
                        updateStatsCard()
                        updateInspector(selected)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rightCol.addView(dragDistSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Quick Preset Chips for Max Drag Distance
        val dragChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 4, 0, 8)
        }
        val dragChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dragPresets = listOf(
            0.0f to "0.0x (Locked)",
            0.4f to "0.4x (Short)",
            0.8f to "0.8x (Default)",
            1.2f to "1.2x",
            1.8f to "1.8x",
            2.8f to "2.8x (Far)"
        )
        for ((dVal, dLabel) in dragPresets) {
            val chip = Button(this).apply {
                text = dLabel
                textSize = 9f
                setTextColor(Color.parseColor("#C9D1D9"))
                background = createCardDrawable(Color.parseColor("#21262D"), 8f)
                setPadding(10, 3, 10, 3)
                setOnClickListener {
                    dragDistSeekBar.progress = (dVal * 10).toInt().coerceIn(0, 50)
                    if (selected.showGhostShadow) {
                        dragDistLabel.text = "📏 DRAG REACH: ${String.format("%.1f", dVal)}x radius" + if (dVal == 0f) " (Stationary / Locked)" else ""
                    }
                    controllerView.updateSelectedMaxDragDistance(dVal)
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            dragChipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        dragChipScroll.addView(dragChipRow)
        rightCol.addView(dragChipScroll)

        updateDragControlsState = {
            val enabled = selected.showGhostShadow
            dragDistSeekBar.isEnabled = enabled
            dragDistSeekBar.alpha = if (enabled) 1.0f else 0.35f
            dragChipScroll.alpha = if (enabled) 1.0f else 0.35f
            for (i in 0 until dragChipRow.childCount) {
                dragChipRow.getChildAt(i).isEnabled = enabled
            }
            dragDistLabel.text = if (!enabled) {
                "📏 DRAG REACH: Disabled (Turn ON Ghost Shadow above to enable drag)"
            } else {
                val dist = selected.maxDragDistance
                "📏 DRAG REACH: ${String.format("%.1f", dist)}x radius" + if (dist == 0f) " (Stationary / Locked)" else ""
            }
        }
        updateGhostCard()

        // --- SECTION D: Size / Scale ---
        val scaleLabel = TextView(this).apply {
            text = "BUTTON SIZE: ${String.format("%.2f", selected.scale)}x"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 8, 0, 4)
        }
        rightCol.addView(scaleLabel)

        val sizeSeekBar = SeekBar(this).apply {
            max = 170 // 50 to 220 -> 0.50x to 2.20x
            progress = ((selected.scale * 100).toInt() - 50).coerceIn(0, 170)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val scale = (prog + 50) / 100f
                    scaleLabel.text = "BUTTON SIZE: ${String.format("%.2f", scale)}x"
                    if (fromUser) {
                        controllerView.updateSelectedScale(scale)
                        updateStatsCard()
                        updateInspector(selected)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rightCol.addView(sizeSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // --- SECTION E: Touch Detection Area (Proximity Hitbox) ---
        val touchAreaHeader = TextView(this).apply {
            text = "TOUCH DETECTION AREA (PROXIMITY HITBOX):"
            textSize = 11f
            setTextColor(Color.parseColor("#58A6FF"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 10, 0, 4)
        }
        rightCol.addView(touchAreaHeader)

        val touchAreaDesc = TextView(this).apply {
            text = "Tap near or slightly outside the button to still trigger it automatically."
            textSize = 10f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(0, 0, 0, 4)
        }
        rightCol.addView(touchAreaDesc)

        val touchAreaLabel = TextView(this).apply {
            text = "🎯 HITBOX RADIUS: ${String.format("%.2f", selected.touchPadding)}x" + if (selected.touchPadding > 1.0f) " (Nearby Auto-Detect)" else " (Exact Only)"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(0, 2, 0, 4)
        }
        rightCol.addView(touchAreaLabel)

        val touchAreaSeekBar = SeekBar(this).apply {
            max = 150 // 100 to 250 -> 1.00x to 2.50x
            progress = ((selected.touchPadding * 100).toInt() - 100).coerceIn(0, 150)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                    val pad = (prog + 100) / 100f
                    touchAreaLabel.text = "🎯 HITBOX RADIUS: ${String.format("%.2f", pad)}x" + if (pad > 1.0f) " (Nearby Auto-Detect)" else " (Exact Only)"
                    if (fromUser) {
                        controllerView.updateSelectedTouchPadding(pad)
                        updateStatsCard()
                        updateInspector(selected)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        rightCol.addView(touchAreaSeekBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Quick Preset Chips for Touch Detection Area
        val touchChipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 4, 0, 8)
        }
        val touchChipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val touchPresets = listOf(
            1.0f to "1.0x (Exact)",
            1.25f to "1.25x",
            1.4f to "1.4x (Default)",
            1.8f to "1.8x",
            2.0f to "2.0x",
            2.5f to "2.5x (Wide)"
        )
        for ((padVal, padText) in touchPresets) {
            val chip = Button(this).apply {
                text = padText
                textSize = 9f
                setTextColor(Color.parseColor("#C9D1D9"))
                background = createCardDrawable(Color.parseColor("#21262D"), 8f)
                setPadding(10, 3, 10, 3)
                setOnClickListener {
                    touchAreaSeekBar.progress = ((padVal * 100).toInt() - 100).coerceIn(0, 150)
                    touchAreaLabel.text = "🎯 HITBOX RADIUS: ${String.format("%.2f", padVal)}x" + if (padVal > 1.0f) " (Nearby Auto-Detect)" else " (Exact Only)"
                    controllerView.updateSelectedTouchPadding(padVal)
                    updateStatsCard()
                    updateInspector(selected)
                }
            }
            touchChipRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 6 })
        }
        touchChipScroll.addView(touchChipRow)
        rightCol.addView(touchChipScroll)

        // --- SECTION F: Macro Studio & Delete Buttons ---
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }

        val macroBtn = Button(this).apply {
            text = "⚡ Macro Studio"
            textSize = 11f
            setTextColor(Color.parseColor("#E040FB"))
            background = createCardDrawable(Color.parseColor("#34143D"), 10f, Color.parseColor("#E040FB"), 1)
            setPadding(14, 8, 14, 8)
            setOnClickListener {
                dialog.dismiss()
                showMacroStudioDialog(selected)
            }
        }
        actionRow.addView(macroBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })

        if (selected.isCustom) {
            val delBtn = Button(this).apply {
                text = "🗑️ Delete"
                textSize = 11f
                setTextColor(Color.parseColor("#FF6B6B"))
                background = createCardDrawable(Color.parseColor("#491818"), 10f)
                setPadding(14, 8, 14, 8)
                setOnClickListener {
                    if (controllerView.deleteSelectedElement()) {
                        Toast.makeText(this@MainActivity, "Button deleted", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                }
            }
            actionRow.addView(delBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        rightCol.addView(actionRow)
        rightScroll.addView(rightCol)
        columnsLayout.addView(rightScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.15f))

        dialogView.addView(columnsLayout)

        updateStatsCard()

        // --- 3. Footer Bar ---
        val footerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, 10, 0, 0)
        }
        val doneBtn = Button(this).apply {
            text = "✓ Done"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = createCardDrawable(Color.parseColor("#238636"), 12f)
            setPadding(32, 10, 32, 10)
            setOnClickListener {
                val newName = nameEdit.text.toString().trim()
                if (newName.isNotEmpty() && newName != selected.label) {
                    controllerView.updateSelectedLabel(newName)
                }
                updateInspector(selected)
                HudConfig.saveLayout(this@MainActivity, controllerView.elements)
                dialog.dismiss()
            }
        }
        footerRow.addView(doneBtn)
        dialogView.addView(footerRow)

        dialog.setView(dialogView)
        dialog.show()
        dialog.window?.let { w ->
            val dm = resources.displayMetrics
            val targetWidth = (dm.widthPixels * 0.90f).toInt()
            val targetHeight = (dm.heightPixels * 0.92f).toInt()
            w.setLayout(targetWidth, targetHeight)
        }
    }

    private fun showDpadKeyPickerDialog(direction: String) {
        val selected = controllerView.selectedElement ?: return
        if (selected.type != ElementType.DPAD) return

        val names = VALID_KEY_LIST.map { "${it.displayName} [${it.code}]" }.toTypedArray()
        val dirTitle = when (direction) {
            "up" -> "D-Pad UP"
            "down" -> "D-Pad DOWN"
            "left" -> "D-Pad LEFT"
            "right" -> "D-Pad RIGHT"
            else -> direction.uppercase()
        }

        AlertDialog.Builder(this)
            .setTitle("Assign Key for $dirTitle")
            .setItems(names) { _, which ->
                val chosen = VALID_KEY_LIST[which]
                controllerView.updateDpadDirectionKey(direction, chosen.code)
                updateInspector(selected)
                Toast.makeText(this, "$dirTitle set to ${chosen.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun enterEditMode() {
        controllerView.isEditMode = true
        gearButton.visibility = View.GONE
        editOverlay.visibility = View.VISIBLE
        updateInspector(controllerView.selectedElement)
    }

    private fun exitEditMode(save: Boolean) {
        if (save) {
            HudConfig.saveLayout(this, controllerView.elements)
            val keymap = HudConfig.extractKeymap(controllerView.elements)
            networkClient.sendKeymapSync(keymap)
            Toast.makeText(this, "HUD layout saved and synced to PC!", Toast.LENGTH_SHORT).show()
        }
        controllerView.isEditMode = false
        editOverlay.visibility = View.GONE
        gearButton.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (controllerView.isEditMode) {
            exitEditMode(save = true)
            return
        }
        super.onBackPressed()
    }

    // -------------------------------------------------------------------
    // Connection Target & Setup
    // -------------------------------------------------------------------
    private fun restoreSavedTargetOrPrompt() {
        val savedMode = prefs.getString("mode", "WIFI")
        val savedIp = prefs.getString("wifi_ip", "") ?: ""

        if (savedMode == "USB") {
            switchToUsb()
        } else if (savedIp.isNotEmpty()) {
            switchToWifi(savedIp)
        } else {
            showConnectionDialog()
        }
    }

    private fun switchToWifi(ip: String) {
        networkClient.mode = TransportMode.WIFI
        networkClient.setWifiTarget(ip)
        prefs.edit().putString("mode", "WIFI").putString("wifi_ip", ip).apply()

        val keymap = HudConfig.extractKeymap(controllerView.elements)
        networkClient.sendKeymapSync(keymap)
    }

    private fun switchToUsb() {
        networkClient.mode = TransportMode.USB
        prefs.edit().putString("mode", "USB").apply()
        val keymap = HudConfig.extractKeymap(controllerView.elements)
        networkClient.activeKeymap = keymap
        networkClient.connectUsb { _ ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    "USB connect failed - run 'adb reverse tcp:6001 tcp:6001' on the PC first",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        networkClient.sendKeymapSync(keymap)
    }

    private fun showConnectionDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val wifiRadio = RadioButton(this).apply { text = "WiFi"; id = View.generateViewId() }
        val usbRadio = RadioButton(this).apply { text = "USB"; id = View.generateViewId() }
        modeGroup.addView(wifiRadio)
        modeGroup.addView(usbRadio)
        layout.addView(modeGroup)

        val currentlyUsb = prefs.getString("mode", "WIFI") == "USB"
        if (currentlyUsb) usbRadio.isChecked = true else wifiRadio.isChecked = true

        val ipInput = EditText(this).apply {
            hint = "PC's local IP, e.g. 192.168.1.42"
            setText(prefs.getString("wifi_ip", ""))
        }
        layout.addView(ipInput)

        val scanButton = Button(this).apply { text = "Scan QR instead" }
        layout.addView(scanButton)

        val usbInfo = TextView(this).apply {
            text = "USB: plug in the phone, then on the PC run:\n" +
                "adb reverse tcp:6001 tcp:6001\n" +
                "No IP needed for USB mode."
            setPadding(0, 20, 0, 0)
        }
        layout.addView(usbInfo)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect to PC")
            .setView(layout)
            .setPositiveButton("Connect") { _, _ ->
                if (usbRadio.isChecked) {
                    switchToUsb()
                } else {
                    val ip = ipInput.text.toString().trim()
                    if (ip.isNotEmpty()) switchToWifi(ip)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        scanButton.setOnClickListener {
            dialog.dismiss()
            requestCameraAndScan()
        }

        dialog.show()
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST
            )
        } else {
            launchScanner()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
        } else {
            Toast.makeText(this, "Camera permission needed to scan the QR code", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchScanner() {
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            setPrompt("Point at the QR code shown by the PC server")
            setOrientationLocked(true)
        }.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        val result: IntentResult? = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result?.contents != null) {
            val parts = result.contents.split(":")
            if (parts.size == 2) {
                switchToWifi(parts[0])
                Toast.makeText(this, "Connected to ${parts[0]}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Unrecognized QR content", Toast.LENGTH_SHORT).show()
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        networkClient.stop()
    }
}
