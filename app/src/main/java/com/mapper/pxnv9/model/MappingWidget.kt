package com.mapper.pxnv9.model

enum class MappingType {
    TAP,               // Tombol single tap (klakson, lampu, sein, pintu, dll)
    STEERING_WHEEL,    // Rotasi setir 900 / 270 derajat (Arc / circular continuous drag)
    SHIFTER_SLIDER,    // H-Shifter (titik tengah ke posisi gigi 1..6, R)
    PEDAL_CONTINUOUS   // Pedal gas / rem (Axis / Threshold -> hold touch & release)
}

data class PointF(
    var x: Float = 0f,
    var y: Float = 0f
)

data class MappingWidget(
    val id: String,
    var name: String,
    val type: MappingType,
    var centerPoint: PointF,
    // Khusus Shifter: target koordinat posisi tiap gigi ("R", "1", "2", "3", "4", "5", "6")
    var shifterSlots: MutableMap<String, PointF> = mutableMapOf(),
    // Khusus Steering Wheel:
    var steeringRadius: Float = 180f,     // Radius lingkaran setir virtual BUSSID
    var steeringDegrees: Float = 900f,    // 900 derajat atau 270 derajat
    var steeringSensitivity: Float = 1.0f,// Sensitivitas / multiplier
    // Deadzone untuk input axis (-1.0 .. 1.0)
    var deadzone: Float = 0.05f,
    // Binding hardware
    var boundKeyCode: Int = -1,
    var boundAxis: Int = -1
)
