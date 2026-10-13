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
 * Mengelola pemetaan widget, penangkapan hardware key saat listening,
 * penyimpanan kustom, serta logika setir & H-shifter BUSSID.
 */
object MappingManager {

    val activeWidgets = mutableListOf<MappingWidget>()

    // Callback saat user sedang mendengarkan tombol hardware di edit mode
    var keyListenCallback: ((keyCode: Int, keyName: String) -> Unit)? = null

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

        // 1. Steering Wheel 900 Derajat (Kiri Bawah BUSSID) - Lingkar luar
        activeWidgets.add(
            MappingWidget(
                id = "wheel_steering",
                name = "Setir Kemudi (900°)",
                type = MappingType.STEERING_WHEEL,
                centerPoint = PointF(360f, 820f),
                width = 440f,
                height = 440f,
                steeringRadius = 190f,
                steeringDegrees = 900f,
                steeringSensitivity = 1.0f,
                deadzone = 0.03f,
                boundAxis = MotionEvent.AXIS_X
            )
        )

        // 2. Klakson di Tengah Setir (Titik pusat setir BUSSID berlogo)
        activeWidgets.add(
            MappingWidget(
                id = "btn_horn",
                name = "Klakson (Tengah Setir)",
                type = MappingType.TAP,
                centerPoint = PointF(360f, 820f),
                width = 120f,
                height = 120f,
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_A,
                boundKeyName = "Button A"
            )
        )

        // 3. H-Shifter (Kanan Tengah BUSSID)
        // Posisi netral tengah BUSSID di antara gigi 3 dan 4
        val shifter = MappingWidget(
            id = "h_shifter",
            name = "H-Shifter 6-Speed",
            type = MappingType.SHIFTER_SLIDER,
            centerPoint = PointF(2160f, 650f),
            width = 320f,
            height = 240f
        )
        // Slot gigi BUSSID (relatif terhadap layar Full HD 2400x1080)
        shifter.shifterSlots["R"] = PointF(2020f, 730f)
        shifter.shifterSlots["1"] = PointF(2100f, 570f)
        shifter.shifterSlots["2"] = PointF(2100f, 730f)
        shifter.shifterSlots["3"] = PointF(2180f, 570f)
        shifter.shifterSlots["4"] = PointF(2180f, 730f)
        shifter.shifterSlots["5"] = PointF(2260f, 570f)
        shifter.shifterSlots["6"] = PointF(2260f, 730f)
        activeWidgets.add(shifter)

        // 4. Pedal Gas & Rem (Kanan Bawah)
        activeWidgets.add(
            MappingWidget(
                id = "pedal_gas",
                name = "Pedal Gas",
                type = MappingType.PEDAL_CONTINUOUS,
                centerPoint = PointF(2280f, 870f),
                width = 140f,
                height = 200f,
                boundAxis = MotionEvent.AXIS_GAS
            )
        )

        activeWidgets.add(
            MappingWidget(
                id = "pedal_brake",
                name = "Pedal Rem",
                type = MappingType.PEDAL_CONTINUOUS,
                centerPoint = PointF(2050f, 890f),
                width = 140f,
                height = 180f,
                boundAxis = MotionEvent.AXIS_BRAKE
            )
        )

        // 5. Tombol Aksi Umum (Lampu Hazard)
        activeWidgets.add(
            MappingWidget(
                id = "btn_hazard",
                name = "Lampu Hazard",
                type = MappingType.TAP,
                centerPoint = PointF(180f, 380f),
                width = 90f,
                height = 90f,
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_Y,
                boundKeyName = "Button Y"
            )
        )
    }

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
                put("width", w.width)
                put("height", w.height)
                put("steeringRadius", w.steeringRadius)
                put("steeringDegrees", w.steeringDegrees)
                put("steeringSensitivity", w.steeringSensitivity)
                put("deadzone", w.deadzone)
                put("boundKeyCode", w.boundKeyCode)
                put("boundKeyName", w.boundKeyName)
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
                    width = obj.optDouble("width", 120.0).toFloat(),
                    height = obj.optDouble("height", 120.0).toFloat(),
                    steeringRadius = obj.optDouble("steeringRadius", 180.0).toFloat(),
                    steeringDegrees = obj.optDouble("steeringDegrees", 900.0).toFloat(),
                    steeringSensitivity = obj.optDouble("steeringSensitivity", 1.0).toFloat(),
                    deadzone = obj.optDouble("deadzone", 0.03).toFloat(),
                    boundKeyCode = obj.optInt("boundKeyCode", -1),
                    boundKeyName = obj.optString("boundKeyName", "None"),
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

        // Setir Kemudi Axis X (900 Derajat)
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

        // Pedal Gas & Rem
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
        val keyCode = event.keyCode
        val action = event.action

        // Jika user sedang menunggu input tombol hardware pada dialog "Tekan tombol di setir..."
        val listener = keyListenCallback
        if (listener != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
                listener(keyCode, name)
                keyListenCallback = null
            }
            return true
        }

        val service = TouchAccessibilityService.instance ?: return false

        // Tap Tombol Kustom
        val tapWidget = activeWidgets.firstOrNull { it.type == MappingType.TAP && it.boundKeyCode == keyCode }
        if (tapWidget != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                service.injectTap(tapWidget.centerPoint.x, tapWidget.centerPoint.y)
            }
            return true
        }

        // H-Shifter (Dengan simulasi jalur lorong H-gate BUSSID)
        val shifterWidget = activeWidgets.firstOrNull { it.type == MappingType.SHIFTER_SLIDER }
        if (shifterWidget != null) {
            val targetGear = mapKeyCodeToGear(keyCode)
            if (targetGear != null) {
                val neutralX = shifterWidget.centerPoint.x
                val neutralY = shifterWidget.centerPoint.y
                val targetPos = shifterWidget.shifterSlots[targetGear]

                if (targetPos != null) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        val prevGear = currentGear
                        currentGear = targetGear

                        if (prevGear == "N" || prevGear == targetGear) {
                            // Dari Netral -> Masuk ke Gigi Target
                            service.injectShifterPath(
                                startX = neutralX,
                                startY = neutralY,
                                midX = targetPos.x,
                                midY = neutralY,
                                endX = targetPos.x,
                                endY = targetPos.y,
                                durationMs = 60L
                            )
                        } else {
                            // Pindah Langsung Antar Gigi (Contoh: Gigi 1 -> Gigi 3, Gigi 2 -> Gigi 5):
                            // 1. Dari slot gigi sebelumnya -> Lorong tengah kolom sebelumnya
                            // 2. Geser horizontal di koridor tengah -> Lorong kolom target
                            // 3. Masuk vertikal -> Slot gigi target
                            val prevPos = shifterWidget.shifterSlots[prevGear]
                            if (prevPos != null) {
                                val waypoints = listOf(
                                    Pair(prevPos.x, prevPos.y),         // Mulai di gigi sebelumnya (misal Gigi 1)
                                    Pair(prevPos.x, neutralY),           // Turun ke koridor tengah
                                    Pair(targetPos.x, neutralY),         // Geser ke kolom target (misal kolom 3/4)
                                    Pair(targetPos.x, targetPos.y)       // Masuk ke gigi target (misal Gigi 3)
                                )
                                service.injectMultiPointPath(waypoints, durationMs = 85L)
                            } else {
                                // Fallback jika posisi lama tidak ada
                                service.injectShifterPath(
                                    startX = neutralX,
                                    startY = neutralY,
                                    midX = targetPos.x,
                                    midY = neutralY,
                                    endX = targetPos.x,
                                    endY = targetPos.y,
                                    durationMs = 60L
                                )
                            }
                        }
                    } else if (action == KeyEvent.ACTION_UP && currentGear == targetGear) {
                        // Kembali ke Netral (Tuas fisik dilepas ke N)
                        currentGear = "N"
                        service.injectShifterPath(
                            startX = targetPos.x,
                            startY = targetPos.y,
                            midX = targetPos.x,
                            midY = neutralY,
                            endX = neutralX,
                            endY = neutralY,
                            durationMs = 50L
                        )
                    }
                }
                return true
            }
        }

        return false
    }

    fun mapKeyCodeToGear(keyCode: Int): String? {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_1 -> "1"
            KeyEvent.KEYCODE_BUTTON_2 -> "2"
            KeyEvent.KEYCODE_BUTTON_3 -> "3"
            KeyEvent.KEYCODE_BUTTON_4 -> "4"
            KeyEvent.KEYCODE_BUTTON_5 -> "5"
            KeyEvent.KEYCODE_BUTTON_6 -> "6"
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_7 -> "R"
            else -> null
        }
    }
}
