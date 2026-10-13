package com.mapper.pxnv9

import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.InputDevice
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mapper.pxnv9.service.OverlayService
import com.mapper.pxnv9.service.TouchAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var tvDeviceStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var btnToggleOverlay: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)

        findViewById<Button>(R.id.btnGrantAccessibility).setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Izin Overlay sudah aktif", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnCalibrateHardware).setOnClickListener {
            showHardwareCalibrationDialog()
        }

        findViewById<Button>(R.id.btnToggleAdb).setOnClickListener {
            showAdbWirelessDialog()
        }

        btnToggleOverlay.setOnClickListener {
            if (TouchAccessibilityService.instance == null) {
                Toast.makeText(this, "Aktifkan Accessibility Service terlebih dahulu!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(this, OverlayService::class.java)
            startService(intent)
            Toast.makeText(this, "Overlay PXN V9 dijalankan", Toast.LENGTH_SHORT).show()
        }

        checkConnectedDevices()
    }

    /**
     * Wizard Kalibrasi Hardware Otomatis (Auto-Detect Axis Setir, Gas, Rem, Kopling)
     */
    private var calibrationListener: ((axisId: Int, axisName: String, value: Float) -> Unit)? = null

    private fun showHardwareCalibrationDialog() {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)

            val tvInstructions = android.widget.TextView(this@MainActivity).apply {
                text = "Putar setir PXN atau tekan pedal gas/rem untuk mendeteksi Axis secara live:"
                textSize = 14f
                setPadding(0, 0, 0, 16)
            }
            addView(tvInstructions)

            val tvDetected = android.widget.TextView(this@MainActivity).apply {
                text = "Menunggu input hardware..."
                textSize = 15f
                setTextColor(android.graphics.Color.parseColor("#00E676"))
                setPadding(0, 8, 0, 16)
            }
            addView(tvDetected)

            calibrationListener = { axisId, axisName, value ->
                tvDetected.text = "Terdeteksi: $axisName (ID: $axisId)\nNilai Input: ${String.format("%.2f", value)}"
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Kalibrasi Axis Hardware")
            .setView(dialogView)
            .setPositiveButton("Simpan Kalibrasi") { d, _ ->
                calibrationListener = null
                com.mapper.pxnv9.manager.MappingManager.saveCurrentProfile(this)
                Toast.makeText(this, "Kalibrasi Axis berhasil disimpan!", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("Tutup") { d, _ ->
                calibrationListener = null
                d.dismiss()
            }
            .setOnDismissListener { calibrationListener = null }
            .show()
    }

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        calibrationListener?.let { listener ->
            val axes = intArrayOf(
                android.view.MotionEvent.AXIS_X,
                android.view.MotionEvent.AXIS_Y,
                android.view.MotionEvent.AXIS_Z,
                android.view.MotionEvent.AXIS_RZ,
                android.view.MotionEvent.AXIS_GAS,
                android.view.MotionEvent.AXIS_BRAKE,
                android.view.MotionEvent.AXIS_RTRIGGER,
                android.view.MotionEvent.AXIS_LTRIGGER
            )
            for (axis in axes) {
                val value = event.getAxisValue(axis)
                if (kotlin.math.abs(value) > 0.15f) {
                    val name = android.view.MotionEvent.axisToString(axis).removePrefix("AXIS_")
                    listener(axis, name, value)
                    break
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun showAdbWirelessDialog() {
        val dialogView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)

            val tvInfo = android.widget.TextView(this@MainActivity).apply {
                text = "⚡ Mode ADB Wireless Debugging (Zero Latency)\n\n" +
                        "1. Aktifkan Opsi Pengembang (Developer Options) di HP Anda.\n" +
                        "2. Aktifkan 'Wireless Debugging' (Debugging Nirkabel).\n" +
                        "3. Mode ini menginjeksi sentuhan tanpa delay (0ms) setara aplikasi Mantis Gamepad Pro.\n"
                textSize = 13f
            }
            addView(tvInfo)

            val btnConnect = Button(this@MainActivity).apply {
                text = if (com.mapper.pxnv9.manager.MappingManager.useAdbZeroLatencyMode) "Matikan Mode ADB" else "Aktifkan Sesi Shell / ADB"
                setBackgroundColor(android.graphics.Color.parseColor("#00838F"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    val success = com.mapper.pxnv9.service.ShellTouchInjector.startShellSession()
                    com.mapper.pxnv9.manager.MappingManager.useAdbZeroLatencyMode = success
                    if (success) {
                        Toast.makeText(this@MainActivity, "Mode ADB / Shell Zero Latency AKTIF!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Mode Standar (Accessibility Non-Root) Aktif", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(btnConnect)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Pengaturan ADB Zero Latency")
            .setView(dialogView)
            .setPositiveButton("Tutup") { d, _ -> d.dismiss() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isServiceActive = TouchAccessibilityService.instance != null
        tvServiceStatus.text = if (isServiceActive) {
            "Accessibility Service: AKTIF"
        } else {
            "Accessibility Service: NONAKTIF (Perlu Diizinkan)"
        }
        checkConnectedDevices()
    }

    private fun checkConnectedDevices() {
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        val deviceIds = inputManager.inputDeviceIds
        val connectedNames = mutableListOf<String>()

        for (id in deviceIds) {
            val device = inputManager.getInputDevice(id) ?: continue
            val sources = device.sources
            if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            ) {
                connectedNames.add("${device.name} (ID: $id)")
            }
        }

        if (connectedNames.isNotEmpty()) {
            tvDeviceStatus.text = "Steering Wheel Terdeteksi:\n" + connectedNames.joinToString("\n")
        } else {
            tvDeviceStatus.text = "Steering Wheel: Tidak ada Gamepad/Wheel terhubung"
        }
    }
}
