package com.mapper.pxnv9.manager

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.mapper.pxnv9.model.MappingType
import com.mapper.pxnv9.model.MappingWidget
import com.mapper.pxnv9.model.PointF
import com.mapper.pxnv9.service.TouchAccessibilityService

/**
 * Mengelola pemetaan dari hardware input event (PXN V9) menjadi touch action
 */
object MappingManager {

    val activeWidgets = mutableListOf<MappingWidget>()

    // State tracking untuk mencegah dispatch gesture berulang kali jika tidak berubah signifikan
    private var lastSteeringNormX = 0f
    private var isThrottlePressed = false
    private var isBrakePressed = false
    private var currentGear: String = "N"

    init {
        setupDefaultBussidProfile()
    }

    /**
     * Konfigurasi default sesuai PRD:
     * - Kiri Bawah: Virtual steering wheel
     * - Kanan Bawah: Pedal gas & rem
     * - Kanan Tengah: H-Shifter UI
     * - Kiri & Atas: Tombol-tombol aksi
     */
    fun setupDefaultBussidProfile() {
        activeWidgets.clear()

        // 1. Steering Wheel (Kiri Bawah layar default asumsi layar Full HD Landscape 2400 x 1080)
        activeWidgets.add(
            MappingWidget(
                id = "wheel_steering",
                name = "Steering Wheel",
                type = MappingType.STEERING_SLIDER,
                centerPoint = PointF(450f, 800f),
                steeringRadius = 250f,
                boundAxis = MotionEvent.AXIS_X
            )
        )

        // 2. H-Shifter (Kanan Tengah)
        val shifter = MappingWidget(
            id = "h_shifter",
            name = "H-Shifter 6-Speed",
            type = MappingType.SHIFTER_SLIDER,
            centerPoint = PointF(1900f, 650f)
        )
        // Koordinat slot gigi relatif terhadap layout BUSSID
        shifter.shifterSlots["R"] = PointF(1780f, 520f)
        shifter.shifterSlots["1"] = PointF(1850f, 520f)
        shifter.shifterSlots["2"] = PointF(1850f, 780f)
        shifter.shifterSlots["3"] = PointF(1900f, 520f)
        shifter.shifterSlots["4"] = PointF(1900f, 780f)
        shifter.shifterSlots["5"] = PointF(1950f, 520f)
        shifter.shifterSlots["6"] = PointF(1950f, 780f)
        activeWidgets.add(shifter)

        // 3. Pedal Gas & Rem (Kanan Bawah)
        activeWidgets.add(
            MappingWidget(
                id = "pedal_gas",
                name = "Pedal Gas",
                type = MappingType.PEDAL_CONTINUOUS,
                centerPoint = PointF(2250f, 880f),
                boundAxis = MotionEvent.AXIS_GAS
            )
        )

        activeWidgets.add(
            MappingWidget(
                id = "pedal_brake",
                name = "Pedal Rem",
                type = MappingType.PEDAL_CONTINUOUS,
                centerPoint = PointF(2080f, 920f),
                boundAxis = MotionEvent.AXIS_BRAKE
            )
        )

        // 4. Action Buttons (Klakson, Lampu, Sein)
        activeWidgets.add(
            MappingWidget(
                id = "btn_horn",
                name = "Klakson",
                type = MappingType.TAP,
                centerPoint = PointF(450f, 800f),
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_A
            )
        )
        activeWidgets.add(
            MappingWidget(
                id = "btn_hazard",
                name = "Lampu Hazard",
                type = MappingType.TAP,
                centerPoint = PointF(1200f, 150f),
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_Y
            )
        )
    }

    /**
     * Memproses MotionEvent dari joystick/steering wheel axis
     */
    fun processMotionEvent(event: MotionEvent): Boolean {
        val service = TouchAccessibilityService.instance ?: return false

        // Filter event hanya dari gamepad/joystick
        val source = event.source
        if ((source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        // 1. Steering Wheel Rotation (Biasanya Axis X atau Axis Z)
        val steeringAxisVal = event.getAxisValue(MotionEvent.AXIS_X)
        val steeringWidget = activeWidgets.firstOrNull { it.type == MappingType.STEERING_SLIDER }
        if (steeringWidget != null) {
            // Evaluasi deadzone
            val effectiveVal = if (kotlin.math.abs(steeringAxisVal) < steeringWidget.deadzone) 0f else steeringAxisVal
            if (kotlin.math.abs(effectiveVal - lastSteeringNormX) > 0.05f) {
                lastSteeringNormX = effectiveVal
                service.injectSteeringSlider(
                    centerX = steeringWidget.centerPoint.x,
                    centerY = steeringWidget.centerPoint.y,
                    radius = steeringWidget.steeringRadius,
                    normalizedRotation = effectiveVal
                )
            }
        }

        // 2. Pedal Gas & Rem (AXIS_GAS / AXIS_RTRIGGER, AXIS_BRAKE / AXIS_LTRIGGER)
        var gasVal = event.getAxisValue(MotionEvent.AXIS_GAS)
        if (gasVal == 0f) gasVal = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        var brakeVal = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        if (brakeVal == 0f) brakeVal = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)

        val gasWidget = activeWidgets.firstOrNull { it.id == "pedal_gas" }
        if (gasWidget != null) {
            val pressed = gasVal > 0.2f
            if (pressed != isThrottlePressed) {
                isThrottlePressed = pressed
                service.injectContinuousTouch(gasWidget.centerPoint.x, gasWidget.centerPoint.y, pressed)
            }
        }

        val brakeWidget = activeWidgets.firstOrNull { it.id == "pedal_brake" }
        if (brakeWidget != null) {
            val pressed = brakeVal > 0.2f
            if (pressed != isBrakePressed) {
                isBrakePressed = pressed
                service.injectContinuousTouch(brakeWidget.centerPoint.x, brakeWidget.centerPoint.y, pressed)
            }
        }

        return true
    }

    /**
     * Memproses KeyEvent (Tombol standar & Shifter gears)
     */
    fun processKeyEvent(event: KeyEvent): Boolean {
        val service = TouchAccessibilityService.instance ?: return false
        val keyCode = event.keyCode
        val action = event.action

        // Cek tombol tap standar
        val tapWidget = activeWidgets.firstOrNull { it.type == MappingType.TAP && it.boundKeyCode == keyCode }
        if (tapWidget != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                service.injectTap(tapWidget.centerPoint.x, tapWidget.centerPoint.y)
            }
            return true
        }

        // Cek H-Shifter (Tombol diskrit untuk Gigi 1 - 6 & R)
        val shifterWidget = activeWidgets.firstOrNull { it.type == MappingType.SHIFTER_SLIDER }
        if (shifterWidget != null) {
            val targetGear = mapKeyCodeToGear(keyCode)
            if (targetGear != null) {
                if (action == KeyEvent.ACTION_DOWN) {
                    currentGear = targetGear
                    val targetPos = shifterWidget.shifterSlots[targetGear]
                    if (targetPos != null) {
                        service.injectShifterSlide(
                            shifterWidget.centerPoint.x,
                            shifterWidget.centerPoint.y,
                            targetPos.x,
                            targetPos.y
                        )
                    }
                } else if (action == KeyEvent.ACTION_UP && currentGear == targetGear) {
                    // Kembali ke netral jika tuas dilepas dari gigi tersebut
                    currentGear = "N"
                    val targetPos = shifterWidget.shifterSlots[targetGear]
                    if (targetPos != null) {
                        service.injectShifterSlide(
                            targetPos.x,
                            targetPos.y,
                            shifterWidget.centerPoint.x,
                            shifterWidget.centerPoint.y
                        )
                    }
                }
                return true
            }
        }

        return false
    }

    private fun mapKeyCodeToGear(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_1 -> "1"
            KeyEvent.KEYCODE_BUTTON_2 -> "2"
            KeyEvent.KEYCODE_BUTTON_3 -> "3"
            KeyEvent.KEYCODE_BUTTON_4 -> "4"
            KeyEvent.KEYCODE_BUTTON_5 -> "5"
            KeyEvent.KEYCODE_BUTTON_6 -> "6"
            KeyEvent.KEYCODE_BUTTON_R1 -> "R"
            else -> null
        }
    }
}
