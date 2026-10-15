package com.mapper.pxnv9.model

enum class MappingType {
    TAP,               // Tombol single tap (klakson, lampu, sein, pintu, wiper, dll)
    STEERING_WHEEL,    // Setir (mode RING: arc circular drag / mode TILT: horizontal slider)
    SHIFTER_SLIDER,    // H-Shifter (titik tengah ke posisi gigi 1..6, R)
    PEDAL_ANALOG,      // Pedal gas / rem analog progresif (tinggi injakan menyesuaikan slider layar)
    MACRO_TAP          // Tombol makro beruntun (misal Klakson Telolet / Basuri nada 1-2-3 berulang)
}

/**
 * Mode injeksi setir:
 * RING  — drag melingkar di atas ring setir BUSSID (metode lama, butuh kalibrasi akurat)
 * TILT  — geser horizontal proporsional (simulasi tilt HP), lebih sederhana & stabil
 */
enum class SteeringMode { RING, TILT }

data class PointF(
    var x: Float = 0f,
    var y: Float = 0f
)

data class MappingWidget(
    val id: String,
    var name: String,
    val type: MappingType,
    var centerPoint: PointF,
    // Ukuran widget (lebar / tinggi untuk resize)
    var width: Float = 120f,
    var height: Float = 120f,
    // Khusus Shifter: target koordinat posisi tiap gigi ("R", "1", "2", "3", "4", "5", "6")
    var shifterSlots: MutableMap<String, PointF> = mutableMapOf(),
    // Binding manual tombol hardware untuk tiap gigi (KeyCode atau ScanCode untuk tombol UNKNOWN)
    var gearKeyBindings: MutableMap<String, Int> = mutableMapOf(),
    var gearScanCodeBindings: MutableMap<String, Int> = mutableMapOf(),
    var gearKeyNames: MutableMap<String, String> = mutableMapOf(),
    // Khusus Steering Wheel:
    var steeringRadius: Float = 180f,     // Radius lingkaran setir virtual BUSSID
    var steeringDegrees: Float = 900f,    // 900 derajat atau 270 derajat
    var steeringSensitivity: Float = 1.0f,// Sensitivitas / multiplier
    var steeringLinearity: Float = 1.0f,  // Kurva respons non-linearitas kemudi (0.5 - 2.5)
    var autoCenterSmoothing: Boolean = true, // Smoothing interpolator saat kemudi kembali ke tengah
    // Khusus Pedal Analog & Kopling:
    var pedalTravelDistance: Float = 160f, // Panjang sapuan slider pedal vertikal di layar (px)
    // Deadzone untuk input axis (-1.0 .. 1.0)
    var deadzone: Float = 0.05f,
    // Binding hardware (KeyCode & ScanCode dari tombol PXN V9 atau Axis ID terkalibrasi)
    var boundKeyCode: Int = -1,
    var boundScanCode: Int = -1,
    var boundKeyName: String = "None",
    var boundAxis: Int = -1,
    // Khusus Shifter / Kopling:
    var requireClutchToShift: Boolean = true, // Wajib injak kopling untuk oper gigi
    // Khusus Macro: daftar koordinat tap berurutan dan delay ms
    var macroPoints: MutableList<PointF> = mutableListOf(),
    var macroDelayMs: Long = 120L
)
