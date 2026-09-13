package com.virtualpad.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

class MainActivity : Activity() {

    private lateinit var controllerView: ControllerView
    private lateinit var networkClient: NetworkClient
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var statusButton: Button
    private lateinit var hudButton: Button

    // Edit Mode Overlay UI Elements
    private lateinit var editOverlay: FrameLayout
    private lateinit var bottomInspector: LinearLayout
    private lateinit var inspectorTitle: TextView
    private lateinit var scaleText: TextView
    private lateinit var scaleSeekBar: SeekBar
    private lateinit var bindKeyButton: Button
    private lateinit var deleteButton: Button

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

        val root = FrameLayout(this)

        // 1. Controller View
        controllerView = ControllerView(this)
        controllerView.onStateChanged = { state -> networkClient.submit(state) }
        root.addView(
            controllerView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        // 2. Normal Mode Action Buttons (Top-Right)
        val normalTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        hudButton = Button(this).apply {
            text = "CUSTOMIZE HUD"
            textSize = 12f
            setTextColor(Color.WHITE)
            alpha = 0.75f
            background = createCardDrawable(Color.parseColor("#3B1C54"), 16f, Color.parseColor("#A855F7"), 2)
            setPadding(24, 8, 24, 8)
            setOnClickListener { enterEditMode() }
        }
        val hudParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 }
        normalTopBar.addView(hudButton, hudParams)

        statusButton = Button(this).apply {
            text = "SETUP"
            textSize = 12f
            setTextColor(Color.WHITE)
            alpha = 0.75f
            background = createCardDrawable(Color.parseColor("#1E5F7A"), 16f, Color.parseColor("#3A8FB7"), 2)
            setPadding(24, 8, 24, 8)
            setOnClickListener { showConnectionDialog() }
        }
        normalTopBar.addView(statusButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val topBarParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 16
            rightMargin = 16
        }
        root.addView(normalTopBar, topBarParams)

        // 3. Edit Mode Overlay
        buildEditOverlay(root)

        setContentView(root)
        networkClient.start()

        restoreSavedTargetOrPrompt()
    }

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

    private fun buildEditOverlay(root: FrameLayout) {
        editOverlay = FrameLayout(this).apply {
            visibility = View.GONE
        }

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
        topEditorBar.addView(addBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 })

        val resetBtn = Button(this).apply {
            text = "Reset Layout"
            textSize = 12f
            setTextColor(Color.parseColor("#FF8A80"))
            background = createCardDrawable(Color.parseColor("#374151"), 14f)
            setPadding(24, 8, 24, 8)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Reset HUD")
                    .setMessage("Are you sure you want to reset all buttons and joysticks to default?")
                    .setPositiveButton("Reset") { _, _ ->
                        controllerView.resetToDefault()
                        Toast.makeText(this@MainActivity, "Layout reset to defaults", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        topEditorBar.addView(resetBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 })

        val saveExitBtn = Button(this).apply {
            text = "Save & Exit"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#2ECC71"), 14f)
            setPadding(30, 8, 30, 8)
            setOnClickListener { exitEditMode(save = true) }
        }
        topEditorBar.addView(saveExitBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val topParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP }
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

        // Inspector Controls Row
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 0)
        }

        scaleText = TextView(this).apply {
            text = "Size: 1.0x"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        controlsRow.addView(scaleText)

        scaleSeekBar = SeekBar(this).apply {
            max = 220
            progress = 100 // 1.0x
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val safeProg = progress.coerceAtLeast(50) // Min 0.5x
                    val scale = safeProg / 100f
                    scaleText.text = "Size: ${String.format("%.2f", scale)}x"
                    if (fromUser) {
                        controllerView.updateSelectedScale(scale)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val seekParams = LinearLayout.LayoutParams(320, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = 12
            rightMargin = 20
        }
        controlsRow.addView(scaleSeekBar, seekParams)

        bindKeyButton = Button(this).apply {
            text = "Bind Key"
            textSize = 12f
            setTextColor(Color.WHITE)
            background = createCardDrawable(Color.parseColor("#1F6FEB"), 12f)
            setPadding(20, 6, 20, 6)
            setOnClickListener { showKeyPickerDialog() }
        }
        controlsRow.addView(bindKeyButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 16 })

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
        controlsRow.addView(deleteButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        bottomInspector.addView(controlsRow)

        val bottomParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 24
        }
        editOverlay.addView(bottomInspector, bottomParams)

        // Controller selection callback
        controllerView.onElementSelected = { selected ->
            updateInspector(selected)
        }

        root.addView(
            editOverlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    private fun formatKeyDisplay(key: String): String = when (key.lowercase()) {
        "mouse_left", "lmb" -> "LMB"
        "mouse_right", "rmb" -> "RMB"
        "mouse_middle", "mmb" -> "MMB"
        else -> key.uppercase()
    }

    private fun updateInspector(el: HudElement?) {
        if (el == null) {
            inspectorTitle.text = "Tap any button on screen to select and adjust"
            scaleSeekBar.isEnabled = false
            bindKeyButton.isEnabled = false
            deleteButton.isEnabled = false
            deleteButton.alpha = 0.4f
        } else {
            val typeStr = when (el.type) {
                ElementType.BUTTON -> if (el.isCustom) "Custom Button ${el.customSlot + 1}" else "Button ${el.id.uppercase()}"
                ElementType.STICK -> "Left Stick"
                ElementType.DPAD -> "D-Pad"
            }
            val keyDisplay = formatKeyDisplay(el.key)
            inspectorTitle.text = "Selected: $typeStr ${if (el.key.isNotEmpty()) "[Key: $keyDisplay]" else ""}"

            scaleSeekBar.isEnabled = true
            scaleSeekBar.progress = (el.scale * 100).toInt()
            scaleText.text = "Size: ${String.format("%.2f", el.scale)}x"

            bindKeyButton.isEnabled = (el.type == ElementType.BUTTON)
            bindKeyButton.alpha = if (el.type == ElementType.BUTTON) 1.0f else 0.4f
            bindKeyButton.text = if (el.key.isNotEmpty()) "Key: $keyDisplay" else "Bind Key"

            deleteButton.isEnabled = el.isCustom
            deleteButton.alpha = if (el.isCustom) 1.0f else 0.4f
        }
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

    private fun enterEditMode() {
        controllerView.isEditMode = true
        hudButton.visibility = View.GONE
        statusButton.visibility = View.GONE
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
        hudButton.visibility = View.VISIBLE
        statusButton.visibility = View.VISIBLE
    }

    override fun onBackPressed() {
        if (controllerView.isEditMode) {
            exitEditMode(save = true)
            return
        }
        super.onBackPressed()
    }

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
        statusButton.text = "WiFi:${ip.takeLast(3)}"

        // Sync keymap to PC immediately
        val keymap = HudConfig.extractKeymap(controllerView.elements)
        networkClient.sendKeymapSync(keymap)
    }

    private fun switchToUsb() {
        networkClient.mode = TransportMode.USB
        prefs.edit().putString("mode", "USB").apply()
        statusButton.text = "USB..."
        networkClient.connectUsb { e ->
            runOnUiThread {
                statusButton.text = "USB FAIL"
                Toast.makeText(
                    this,
                    "USB connect failed - run 'adb reverse tcp:6001 tcp:6001' on the PC first",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        runOnUiThread {
            statusButton.text = "USB"
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

        val customizeHudDialogBtn = Button(this).apply {
            text = "Open HUD Customizer"
            setTextColor(Color.parseColor("#A855F7"))
        }
        layout.addView(customizeHudDialogBtn)

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

        customizeHudDialogBtn.setOnClickListener {
            dialog.dismiss()
            enterEditMode()
        }

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
            // Expected payload: "ip:port"
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
