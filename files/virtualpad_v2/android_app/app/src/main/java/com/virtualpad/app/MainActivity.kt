package com.virtualpad.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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

    companion object {
        private const val CAMERA_PERMISSION_REQUEST = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        controllerView = ControllerView(this)
        controllerView.onStateChanged = { state -> networkClient.submit(state) }
        root.addView(
            controllerView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        statusButton = Button(this).apply {
            text = "SETUP"
            alpha = 0.6f
            setOnClickListener { showConnectionDialog() }
        }
        val btnParams = FrameLayout.LayoutParams(220, 100).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = 10
            rightMargin = 10
        }
        root.addView(statusButton, btnParams)

        setContentView(root)
        networkClient.start()

        restoreSavedTargetOrPrompt()
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
        runOnUiThread { statusButton.text = "USB" }
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
