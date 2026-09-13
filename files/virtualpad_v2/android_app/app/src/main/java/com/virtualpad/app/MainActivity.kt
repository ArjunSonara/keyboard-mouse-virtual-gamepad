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
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
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
    private lateinit var dpadControlsRow: LinearLayout
    private lateinit var dpadUpBtn: Button
    private lateinit var dpadDownBtn: Button
    private lateinit var dpadLeftBtn: Button
    private lateinit var dpadRightBtn: Button
    private lateinit var buttonControlsRow: LinearLayout

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

        setContentView(root)
        networkClient.start()

        restoreSavedTargetOrPrompt()
    }

    override fun onResume() {
        super.onResume()
        gyroscopeSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    // -------------------------------------------------------------------
    // Gyroscope Motion Aiming
    // -------------------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (!gyroActive || controllerView.isEditMode) return

        val wx = event.values[0] // Angular speed around X (pitch)
        val wy = event.values[1] // Angular speed around Y (yaw)

        // Noise deadzone filter
        if (abs(wx) < 0.02f && abs(wy) < 0.02f) return

        val sens = HudConfig.getGyroSensitivity(this) * 14.0f
        val dx = -wy * sens
        val dy = -wx * sens

        controllerView.injectGyroAim(dx, dy)
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

        val gyroSensLabel = TextView(this).apply {
            text = "Gyro Sensitivity: ${String.format("%.1f", HudConfig.getGyroSensitivity(this@MainActivity))}x"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(gyroSensLabel)

        val gyroSensBar = SeekBar(this).apply {
            max = 300
            progress = (HudConfig.getGyroSensitivity(this@MainActivity) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val sens = progress.coerceAtLeast(50) / 100f
                    gyroSensLabel.text = "Gyro Sensitivity: ${String.format("%.1f", sens)}x"
                    HudConfig.setGyroSensitivity(this@MainActivity, sens)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(gyroSensBar)

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

        shapeButton = Button(this).apply {
            text = "Shape: Circle"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#374151"), 12f)
            setPadding(20, 6, 20, 6)
            setOnClickListener { cycleSelectedShape() }
        }
        buttonControlsRow.addView(shapeButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 14 })

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
            deleteButton.isEnabled = false
            deleteButton.alpha = 0.4f
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
                ElementType.BUTTON -> {
                    val typeStr = if (el.isCustom) "Custom Button ${el.customSlot + 1}" else "Button ${el.id.uppercase()}"
                    val keyDisplay = formatKeyDisplay(el.key)
                    inspectorTitle.text = "Selected: $typeStr ${if (el.key.isNotEmpty()) "[Key: $keyDisplay]" else ""}"

                    buttonControlsRow.visibility = View.VISIBLE
                    dpadControlsRow.visibility = View.GONE

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

                    deleteButton.isEnabled = el.isCustom
                    deleteButton.alpha = if (el.isCustom) 1.0f else 0.4f
                }
                ElementType.STICK -> {
                    inspectorTitle.text = "Selected: Left Movement Joystick"
                    buttonControlsRow.visibility = View.VISIBLE
                    dpadControlsRow.visibility = View.GONE
                    bindKeyButton.isEnabled = false
                    bindKeyButton.alpha = 0.4f
                    bindKeyButton.text = "WASD (Move)"
                    shapeButton.isEnabled = false
                    shapeButton.alpha = 0.4f
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

    private fun showKeyPickerDialog() {
        val selected = controllerView.selectedElement ?: return
        val names = VALID_KEY_LIST.map { "${it.displayName} [${it.code}]" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Assign Key to ${selected.label.ifEmpty { selected.id.uppercase() }}")
            .setItems(names) { _, which ->
                val chosen = VALID_KEY_LIST[which]
                val keyDisplay = formatKeyDisplay(chosen.code)
                val newLabel = if (selected.isCustom) {
                    "C${selected.customSlot + 1} ($keyDisplay)"
                } else {
                    "${selected.id.uppercase()} ($keyDisplay)"
                }
                controllerView.updateSelectedKey(chosen.code, newLabel)
                updateInspector(selected)
                Toast.makeText(this, "Assigned key: ${chosen.displayName}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        networkClient.connectUsb { _ ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    "USB connect failed - run 'adb reverse tcp:6001 tcp:6001' on the PC first",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        runOnUiThread {
            val keymap = HudConfig.extractKeymap(controllerView.elements)
            networkClient.sendKeymapSync(keymap)
        }
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
