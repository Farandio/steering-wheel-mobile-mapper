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

    // UI Kalibrasi 3 Pedal (Persis GUI Python)
    private lateinit var tvRawAllAxes: TextView
    private lateinit var pbGas: android.widget.ProgressBar
    private lateinit var tvGasPct: TextView
    private lateinit var tvGasAxisLabel: TextView

    private lateinit var pbBrake: android.widget.ProgressBar
    private lateinit var tvBrakePct: TextView
    private lateinit var tvBrakeAxisLabel: TextView

    private lateinit var pbClutch: android.widget.ProgressBar
    private lateinit var tvClutchPct: TextView
    private lateinit var tvClutchAxisLabel: TextView

    // State kalibrasi min/max/rest/pressed pedal
    private var gasRest = 0f
    private var gasPressed = 1.0f
    private var gasAxis = android.view.MotionEvent.AXIS_GAS

    private var brakeRest = 0f
    private var brakePressed = 1.0f
    private var brakeAxis = android.view.MotionEvent.AXIS_BRAKE

    private var clutchRest = 0f
    private var clutchPressed = 1.0f
    private var clutchAxis = android.view.MotionEvent.AXIS_Y

    private var autoDetectTarget: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDeviceStatus = findViewById(R.id.tvDeviceStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)

        // Bind 3 Pedal UI
        tvRawAllAxes = findViewById(R.id.tvRawAllAxes)

        pbGas = findViewById(R.id.pbGas)
        tvGasPct = findViewById(R.id.tvGasPct)
        tvGasAxisLabel = findViewById(R.id.tvGasAxisLabel)

        pbBrake = findViewById(R.id.pbBrake)
        tvBrakePct = findViewById(R.id.tvBrakePct)
        tvBrakeAxisLabel = findViewById(R.id.tvBrakeAxisLabel)

        pbClutch = findViewById(R.id.pbClutch)
        tvClutchPct = findViewById(R.id.tvClutchPct)
        tvClutchAxisLabel = findViewById(R.id.tvClutchAxisLabel)

        // Button Listeners Pedal Gas
        findViewById<Button>(R.id.btnDetectGas).setOnClickListener {
            autoDetectTarget = "gas"
            Toast.makeText(this, "Injak pedal GAS sekarang...", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetGasRest).setOnClickListener {
            gasRest = currentRawAxes[gasAxis] ?: 0f
            Toast.makeText(this, "Titik LEPAS Gas disimpan: $gasRest", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetGasPressed).setOnClickListener {
            gasPressed = currentRawAxes[gasAxis] ?: 1.0f
            Toast.makeText(this, "Titik INJAK Gas disimpan: $gasPressed", Toast.LENGTH_SHORT).show()
        }

        // Button Listeners Pedal Rem
        findViewById<Button>(R.id.btnDetectBrake).setOnClickListener {
            autoDetectTarget = "brake"
            Toast.makeText(this, "Injak pedal REM sekarang...", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetBrakeRest).setOnClickListener {
            brakeRest = currentRawAxes[brakeAxis] ?: 0f
            Toast.makeText(this, "Titik LEPAS Rem disimpan: $brakeRest", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetBrakePressed).setOnClickListener {
            brakePressed = currentRawAxes[brakeAxis] ?: 1.0f
            Toast.makeText(this, "Titik INJAK Rem disimpan: $brakePressed", Toast.LENGTH_SHORT).show()
        }

        // Button Listeners Pedal Kopling
        findViewById<Button>(R.id.btnDetectClutch).setOnClickListener {
            autoDetectTarget = "clutch"
            Toast.makeText(this, "Injak pedal KOPLING sekarang...", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetClutchRest).setOnClickListener {
            clutchRest = currentRawAxes[clutchAxis] ?: 0f
            Toast.makeText(this, "Titik LEPAS Kopling disimpan: $clutchRest", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSetClutchPressed).setOnClickListener {
            clutchPressed = currentRawAxes[clutchAxis] ?: 1.0f
            Toast.makeText(this, "Titik INJAK Kopling disimpan: $clutchPressed", Toast.LENGTH_SHORT).show()
        }

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

    private val currentRawAxes = mutableMapOf<Int, Float>()

    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        // 1. Baca nilai semua axis penting (X, Y, Z, R, RX, RY, GAS, BRAKE, dll)
        val axes = intArrayOf(
            android.view.MotionEvent.AXIS_X,
            android.view.MotionEvent.AXIS_Y,
            android.view.MotionEvent.AXIS_Z,
            android.view.MotionEvent.AXIS_RX,
            android.view.MotionEvent.AXIS_RY,
            android.view.MotionEvent.AXIS_RZ,
            android.view.MotionEvent.AXIS_GAS,
            android.view.MotionEvent.AXIS_BRAKE,
            android.view.MotionEvent.AXIS_RTRIGGER,
            android.view.MotionEvent.AXIS_LTRIGGER,
            android.view.MotionEvent.AXIS_THROTTLE,
            android.view.MotionEvent.AXIS_RUDDER,
            android.view.MotionEvent.AXIS_WHEEL
        )

        for (ax in axes) {
            currentRawAxes[ax] = event.getAxisValue(ax)
        }

        // 2. Format baris teks "Nilai Raw Semua Axis: X: ... | Y: ... | Z: ... | R: ... | U: ... | V: ..."
        val rawX = String.format("%.2f", currentRawAxes[android.view.MotionEvent.AXIS_X] ?: 0f)
        val rawY = String.format("%.2f", currentRawAxes[android.view.MotionEvent.AXIS_Y] ?: 0f)
        val rawZ = String.format("%.2f", currentRawAxes[android.view.MotionEvent.AXIS_Z] ?: 0f)
        val rawR = String.format("%.2f", currentRawAxes[android.view.MotionEvent.AXIS_RZ] ?: currentRawAxes[android.view.MotionEvent.AXIS_RTRIGGER] ?: 0f)
        val rawU = String.format("%.2f", currentRawAxes[android.view.MotionEvent.AXIS_RX] ?: 0f)
        val rawV = String.format("%.2f", currentRawAxes[android.view.MotionEvent.AXIS_RY] ?: 0f)

        tvRawAllAxes.text = "X: $rawX | Y: $rawY | Z: $rawZ | R: $rawR | U: $rawU | V: $rawV"

        // 3. Auto-Detect Axis Pedal saat tombol Auto-Detect ditekan
        autoDetectTarget?.let { target ->
            for (ax in axes) {
                val v = event.getAxisValue(ax)
                if (kotlin.math.abs(v) > 0.25f) {
                    val name = android.view.MotionEvent.axisToString(ax).removePrefix("AXIS_")
                    when (target) {
                        "gas" -> {
                            gasAxis = ax
                            tvGasAxisLabel.text = "Axis: $name"
                            Toast.makeText(this, "Gas dipasangkan ke Axis $name", Toast.LENGTH_SHORT).show()
                        }
                        "brake" -> {
                            brakeAxis = ax
                            tvBrakeAxisLabel.text = "Axis: $name"
                            Toast.makeText(this, "Rem dipasangkan ke Axis $name", Toast.LENGTH_SHORT).show()
                        }
                        "clutch" -> {
                            clutchAxis = ax
                            tvClutchAxisLabel.text = "Axis: $name"
                            Toast.makeText(this, "Kopling dipasangkan ke Axis $name", Toast.LENGTH_SHORT).show()
                        }
                    }
                    autoDetectTarget = null
                    break
                }
            }
        }

        // 4. Hitung persentase injakan (0% - 100%) dan gerakkan ProgressBar
        val curGas = currentRawAxes[gasAxis] ?: 0f
        val pctGas = calculatePedalPct(curGas, gasRest, gasPressed)
        pbGas.progress = pctGas
        tvGasPct.text = "$pctGas%"

        val curBrake = currentRawAxes[brakeAxis] ?: 0f
        val pctBrake = calculatePedalPct(curBrake, brakeRest, brakePressed)
        pbBrake.progress = pctBrake
        tvBrakePct.text = "$pctBrake%"

        val curClutch = currentRawAxes[clutchAxis] ?: 0f
        val pctClutch = calculatePedalPct(curClutch, clutchRest, clutchPressed)
        pbClutch.progress = pctClutch
        tvClutchPct.text = "$pctClutch%"

        // Teruskan juga ke dialog kalibrasi jika aktif
        calibrationListener?.let { listener ->
            for (axis in axes) {
                val value = event.getAxisValue(axis)
                if (kotlin.math.abs(value) > 0.08f) {
                    val name = android.view.MotionEvent.axisToString(axis).removePrefix("AXIS_")
                    listener(axis, name, value)
                    break
                }
            }
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun calculatePedalPct(current: Float, rest: Float, pressed: Float): Int {
        val range = pressed - rest
        if (range == 0f) return 0
        val pct = ((current - rest) / range) * 100f
        return pct.toInt().coerceIn(0, 100)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        calibrationListener?.let { listener ->
            val name = android.view.KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            listener(-keyCode, "BUTTON: $name (Scan: ${event.scanCode})", 1.0f)
            return true
        }
        return super.onKeyDown(keyCode, event)
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
            if (device.isVirtual) continue
            val sources = device.sources
            val isController = (sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
                               (sources and InputDevice.SOURCE_JOYSTICK) != 0 ||
                               (sources and InputDevice.SOURCE_KEYBOARD) != 0

            if (isController) {
                connectedNames.add("🎮 ${device.name} (ID: $id)")
            }
        }

        // Cek juga via UsbManager untuk perangkat OTG langsung
        val usbManager = getSystemService(Context.USB_SERVICE) as? android.hardware.usb.UsbManager
        usbManager?.deviceList?.values?.forEach { usbDev ->
            val desc = "🔌 USB: ${usbDev.productName ?: usbDev.deviceName} [VID:${Integer.toHexString(usbDev.vendorId)}:PID:${Integer.toHexString(usbDev.productId)}]"
            if (!connectedNames.contains(desc)) {
                connectedNames.add(desc)
            }
        }

        if (connectedNames.isNotEmpty()) {
            tvDeviceStatus.text = "Steering Wheel Terdeteksi:\n" + connectedNames.joinToString("\n")
            tvDeviceStatus.setTextColor(android.graphics.Color.parseColor("#00FF66"))
        } else {
            tvDeviceStatus.text = "Steering Wheel: Tidak ada Gamepad/Wheel terhubung\n(Pastikan OTG aktif di Pengaturan HP)"
            tvDeviceStatus.setTextColor(android.graphics.Color.parseColor("#FF5252"))
        }
    }
}
