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
