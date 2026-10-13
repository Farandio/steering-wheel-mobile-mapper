package com.mapper.pxnv9.manager

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.mapper.pxnv9.model.MappingType
import com.mapper.pxnv9.model.MappingWidget
import com.mapper.pxnv9.model.PointF
import com.mapper.pxnv9.service.TouchAccessibilityService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mengelola pemetaan widget, penyimpanan preferensi (custom mapping),
 * sensitivitas, deadzone, dan kalkulasi rotasi 900 derajat.
 */
object MappingManager {

    val activeWidgets = mutableListOf<MappingWidget>()

    private var lastSteeringNormVal = 0f
    private var isThrottlePressed = false
    private var isBrakePressed = false
    private var currentGear: String = "N"

    private const val PREFS_NAME = "pxn_v9_mapping_prefs"
    private const val KEY_WIDGETS = "saved_widgets"

    init {
        setupDefaultBussidProfile()
    }

    fun setupDefaultBussidProfile() {
        activeWidgets.clear()

        // 1. Steering Wheel 900 Derajat (Kiri Bawah BUSSID)
        activeWidgets.add(
            MappingWidget(
                id = "wheel_steering",
                name = "Setir Kemudi (900°)",
                type = MappingType.STEERING_WHEEL,
                centerPoint = PointF(400f, 750f),
                steeringRadius = 180f,
                steeringDegrees = 900f,
                steeringSensitivity = 1.0f,
                deadzone = 0.03f,
                boundAxis = MotionEvent.AXIS_X
            )
        )

        // 2. H-Shifter (Kanan Tengah BUSSID)
        val shifter = MappingWidget(
            id = "h_shifter",
            name = "H-Shifter 6-Speed",
            type = MappingType.SHIFTER_SLIDER,
            centerPoint = PointF(1850f, 620f)
        )
        shifter.shifterSlots["R"] = PointF(1750f, 500f)
        shifter.shifterSlots["1"] = PointF(1800f, 500f)
        shifter.shifterSlots["2"] = PointF(1800f, 740f)
        shifter.shifterSlots["3"] = PointF(1850f, 500f)
        shifter.shifterSlots["4"] = PointF(1850f, 740f)
        shifter.shifterSlots["5"] = PointF(1900f, 500f)
        shifter.shifterSlots["6"] = PointF(1900f, 740f)
        activeWidgets.add(shifter)

        // 3. Pedal Gas & Rem (Kanan Bawah)
        activeWidgets.add(
            MappingWidget(
                id = "pedal_gas",
                name = "Pedal Gas",
                type = MappingType.PEDAL_CONTINUOUS,
                centerPoint = PointF(2200f, 850f),
                boundAxis = MotionEvent.AXIS_GAS
            )
        )

        activeWidgets.add(
            MappingWidget(
                id = "pedal_brake",
                name = "Pedal Rem",
                type = MappingType.PEDAL_CONTINUOUS,
                centerPoint = PointF(2020f, 880f),
                boundAxis = MotionEvent.AXIS_BRAKE
            )
        )

        // 4. Action Buttons (Klakson, Lampu Hazard, Sein)
        activeWidgets.add(
            MappingWidget(
                id = "btn_horn",
                name = "Klakson",
                type = MappingType.TAP,
                centerPoint = PointF(400f, 750f),
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_A
            )
        )
        activeWidgets.add(
            MappingWidget(
                id = "btn_hazard",
                name = "Lampu Hazard",
                type = MappingType.TAP,
                centerPoint = PointF(1150f, 120f),
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_Y
            )
        )
    }

    /**
     * Simpan konfigurasi kustom ke SharedPreferences
     */
    fun saveCustomMapping(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()

        for (w in activeWidgets) {
            val obj = JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("type", w.type.name)
                put("x", w.centerPoint.x)
                put("y", w.centerPoint.y)
                put("steeringRadius", w.steeringRadius)
                put("steeringDegrees", w.steeringDegrees)
                put("steeringSensitivity", w.steeringSensitivity)
                put("deadzone", w.deadzone)
                put("boundKeyCode", w.boundKeyCode)
                put("boundAxis", w.boundAxis)

                val slotsObj = JSONObject()
                for ((slotKey, pt) in w.shifterSlots) {
                    val slotPointObj = JSONObject().apply {
                        put("x", pt.x)
                        put("y", pt.y)
                    }
                    slotsObj.put(slotKey, slotPointObj)
                }
                put("shifterSlots", slotsObj)
            }
            jsonArray.put(obj)
        }

        prefs.edit().putString(KEY_WIDGETS, jsonArray.toString()).apply()
    }

    /**
     * Muat konfigurasi kustom dari SharedPreferences
     */
    fun loadCustomMapping(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_WIDGETS, null) ?: return

        try {
            val jsonArray = JSONArray(jsonStr)
            activeWidgets.clear()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val type = MappingType.valueOf(obj.getString("type"))
                val widget = MappingWidget(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    type = type,
                    centerPoint = PointF(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat()),
                    steeringRadius = obj.optDouble("steeringRadius", 180.0).toFloat(),
                    steeringDegrees = obj.optDouble("steeringDegrees", 900.0).toFloat(),
                    steeringSensitivity = obj.optDouble("steeringSensitivity", 1.0).toFloat(),
                    deadzone = obj.optDouble("deadzone", 0.03).toFloat(),
                    boundKeyCode = obj.optInt("boundKeyCode", -1),
                    boundAxis = obj.optInt("boundAxis", -1)
                )

                val slotsObj = obj.optJSONObject("shifterSlots")
                if (slotsObj != null) {
                    val keys = slotsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val ptObj = slotsObj.getJSONObject(key)
                        widget.shifterSlots[key] = PointF(
                            ptObj.getDouble("x").toFloat(),
                            ptObj.getDouble("y").toFloat()
                        )
                    }
                }
                activeWidgets.add(widget)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            setupDefaultBussidProfile()
        }
    }

    fun processMotionEvent(event: MotionEvent): Boolean {
        val service = TouchAccessibilityService.instance ?: return false

        val source = event.source
        if ((source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
            (source and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD
        ) {
            return false
        }

        // 1. Setir Kemudi Axis X (Mendukung 900 Derajat)
        val steeringAxisVal = event.getAxisValue(MotionEvent.AXIS_X)
        val steeringWidget = activeWidgets.firstOrNull { it.type == MappingType.STEERING_WHEEL }
        if (steeringWidget != null) {
            val effectiveVal = if (kotlin.math.abs(steeringAxisVal) < steeringWidget.deadzone) 0f else steeringAxisVal
            if (kotlin.math.abs(effectiveVal - lastSteeringNormVal) > 0.02f) {
                service.injectCircularSteering(
                    centerX = steeringWidget.centerPoint.x,
                    centerY = steeringWidget.centerPoint.y,
                    radius = steeringWidget.steeringRadius,
                    fromNorm = lastSteeringNormVal,
                    toNorm = effectiveVal,
                    maxDegrees = steeringWidget.steeringDegrees,
                    sensitivity = steeringWidget.steeringSensitivity
                )
                lastSteeringNormVal = effectiveVal
            }
        }

        // 2. Pedal Gas & Rem
        var gasVal = event.getAxisValue(MotionEvent.AXIS_GAS)
        if (gasVal == 0f) gasVal = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        var brakeVal = event.getAxisValue(MotionEvent.AXIS_BRAKE)
        if (brakeVal == 0f) brakeVal = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)

        val gasWidget = activeWidgets.firstOrNull { it.id == "pedal_gas" }
        if (gasWidget != null) {
            val pressed = gasVal > 0.2f
            if (pressed != isThrottlePressed) {
                isThrottlePressed = pressed
                service.injectContinuousTouch(gasWidget.centerPoint.x, gasWidget.centerPoint.y, pressed, "throttle")
            }
        }

        val brakeWidget = activeWidgets.firstOrNull { it.id == "pedal_brake" }
        if (brakeWidget != null) {
            val pressed = brakeVal > 0.2f
            if (pressed != isBrakePressed) {
                isBrakePressed = pressed
                service.injectContinuousTouch(brakeWidget.centerPoint.x, brakeWidget.centerPoint.y, pressed, "brake")
            }
        }

        return true
    }

    fun processKeyEvent(event: KeyEvent): Boolean {
        val service = TouchAccessibilityService.instance ?: return false
        val keyCode = event.keyCode
        val action = event.action

        val tapWidget = activeWidgets.firstOrNull { it.type == MappingType.TAP && it.boundKeyCode == keyCode }
        if (tapWidget != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                service.injectTap(tapWidget.centerPoint.x, tapWidget.centerPoint.y)
            }
            return true
        }

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
