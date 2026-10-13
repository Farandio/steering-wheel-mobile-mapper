package com.mapper.pxnv9.model

enum class MappingType {
    TAP,               // Tombol single tap (klakson, lampu, sein, dll)
    STEERING_SLIDER,   // Rotasi setir (Axis X -> horizontal/arc drag di area setir)
    SHIFTER_SLIDER,    // H-Shifter (titik tengah ke posisi gigi 1..6, R)
    PEDAL_CONTINUOUS   // Pedal gas / rem (Axis / Threshold -> hold touch & release)
}

data class PointF(
    var x: Float,
    var y: Float
)

data class MappingWidget(
    val id: String,
    val name: String,
    val type: MappingType,
    // Koordinat target di layar
    var centerPoint: PointF,
    // Khusus Shifter: target posisi gigi (misal "1" -> PointF, "2" -> PointF, dll)
    var shifterSlots: MutableMap<String, PointF> = mutableMapOf(),
    // Khusus Steering: radius atau lebar sapuan drag
    var steeringRadius: Float = 200f,
    // Deadzone untuk axis (0.0 .. 1.0)
    var deadzone: Float = 0.05f,
    // Kode tombol atau Axis identifier
    var boundKeyCode: Int = -1,
    var boundAxis: Int = -1
)
