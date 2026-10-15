package com.mapper.pxnv9.manager

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
 * Mengelola pemetaan widget, multi-profil game, penangkapan hardware key,
 * getaran haptic feedback, live input tracking, dan mode ADB/Root fallback.
 */
object MappingManager {

    val activeWidgets = mutableListOf<MappingWidget>()

    // Callback saat user sedang mendengarkan tombol hardware di edit mode (keyCode, scanCode, keyName)
    var keyListenCallback: ((keyCode: Int, scanCode: Int, keyName: String) -> Unit)? = null

    // Callback saat user sedang scan axis analog pedal (axisId, axisName)
    var axisListenCallback: ((axisId: Int, axisName: String) -> Unit)? = null

    // Live Input Observers untuk HUD
    var onSteeringAngleChanged: ((degrees: Float, normalized: Float) -> Unit)? = null
    var onGearChanged: ((gear: String) -> Unit)? = null

    // Multi-profil Game
    var currentProfileName: String = "BUSSID Default"
    private const val PREFS_NAME = "pxn_v9_mapping_prefs"
    private const val KEY_PROFILE_PREFIX = "profile_"
    private const val KEY_PROFILE_LIST = "profile_names_list"
    private const val KEY_ACTIVE_PROFILE = "active_profile_name"

    // Status Pause / Resume Mapping (Agar user bisa navigasi HP / buka menu game tanpa sentuhan tertahan)
    var isMappingPaused: Boolean = false

    // Shizuku / Root Injection Mode Flag
    var useAdbZeroLatencyMode: Boolean = false

    private var lastSteeringNormVal = 0f
    private var isThrottlePressed = false
    private var isBrakePressed = false
    var currentGear: String = "N"
    var isClutchDepressed: Boolean = false
    var lastGearBeforeClutch: String? = null
    var onClutchChanged: ((isDepressed: Boolean) -> Unit)? = null

    fun releaseAllTouches() {
        TouchAccessibilityService.instance?.releaseAllTouches()
    }

    init {
        setupDefaultBussidProfile()
    }

    fun setupDefaultBussidProfile() {
        activeWidgets.clear()

        // 1. Setir BUSSID — MODE TILT (horizontal slider)
        // centerPoint = titik TENGAH slider tilt di layar BUSSID
        // steeringRadius = HALF-WIDTH slider (px dari tengah ke tepi)
        //   → finger bergerak antara (centerX - radius) dan (centerX + radius)
        //   → norm=-1.0: touchX = centerX - radius (full kiri)
        //   → norm=+1.0: touchX = centerX + radius (full kanan)
        //
        // Kalibrasi: aktifkan mode Tilt di BUSSID, lalu sesuaikan centerPoint
        // agar posisi finger TENGAH tepat di indikator netral BUSSID.
        // Default: layar 1080×1920, slider tilt di tengah bawah area kemudi.
        activeWidgets.add(
            MappingWidget(
                id = "wheel_steering",
                name = "Setir Kemudi BUSSID (Tilt)",
                type = MappingType.STEERING_WHEEL,
                centerPoint = PointF(540f, 900f),   // tengah layar 1080p, area bawah
                width = 800f,                        // total lebar slider (2 × radius)
                height = 60f,
                steeringRadius = 400f,               // ±400px dari tengah (half-width)
                steeringDegrees = 900f,              // lock-to-lock setir fisik PXN V9
                steeringSensitivity = 1.0f,          // 1.0 = proporsional penuh
                deadzone = 0.02f,
                boundAxis = MotionEvent.AXIS_X
            )
        )

        // 2. H-Shifter 6-Speed + Reverse (Terkalibrasi dari PC: Center=1752, 704)
        val shifter = MappingWidget(
            id = "h_shifter",
            name = "H-Shifter BUSSID",
            type = MappingType.SHIFTER_SLIDER,
            centerPoint = PointF(1752f, 704f),
            width = 340f,
            height = 180f,
            requireClutchToShift = false // Fleksibel: dapat ganti gigi langsung saat belok
        )
        // Koordinat slot gigi nyata dari kalibrasi PC
        shifter.shifterSlots["R"] = PointF(1567f, 754f)
        shifter.shifterSlots["1"] = PointF(1662f, 659f)
        shifter.shifterSlots["2"] = PointF(1662f, 754f)
        shifter.shifterSlots["3"] = PointF(1752f, 659f)
        shifter.shifterSlots["4"] = PointF(1752f, 754f)
        shifter.shifterSlots["5"] = PointF(1842f, 659f)
        shifter.shifterSlots["6"] = PointF(1842f, 754f)

        // Binding scanCode/keyCode tombol fisik shifter (Button 16-22 dari setir fisik)
        shifter.gearScanCodeBindings["1"] = 16
        shifter.gearScanCodeBindings["2"] = 17
        shifter.gearScanCodeBindings["3"] = 18
        shifter.gearScanCodeBindings["4"] = 19
        shifter.gearScanCodeBindings["5"] = 20
        shifter.gearScanCodeBindings["6"] = 21
        shifter.gearScanCodeBindings["R"] = 22

        shifter.gearKeyBindings["1"] = KeyEvent.KEYCODE_BUTTON_1
        shifter.gearKeyBindings["2"] = KeyEvent.KEYCODE_BUTTON_2
        shifter.gearKeyBindings["3"] = KeyEvent.KEYCODE_BUTTON_3
        shifter.gearKeyBindings["4"] = KeyEvent.KEYCODE_BUTTON_4
        shifter.gearKeyBindings["5"] = KeyEvent.KEYCODE_BUTTON_5
        shifter.gearKeyBindings["6"] = KeyEvent.KEYCODE_BUTTON_6
        shifter.gearKeyBindings["R"] = KeyEvent.KEYCODE_BUTTON_7
        activeWidgets.add(shifter)

        // 3. Pedal Gas (Pedal Kanan - Tombol 'W' di PC)
        activeWidgets.add(
            MappingWidget(
                id = "pedal_gas",
                name = "Pedal Gas",
                type = MappingType.PEDAL_ANALOG,
                centerPoint = PointF(1700f, 900f),
                width = 140f,
                height = 200f,
                pedalTravelDistance = 140f,
                boundAxis = MotionEvent.AXIS_GAS
            )
        )

        // 4. Pedal Rem (Pedal Tengah - Tombol 'S' di PC)
        activeWidgets.add(
            MappingWidget(
                id = "pedal_brake",
                name = "Pedal Rem",
                type = MappingType.PEDAL_ANALOG,
                centerPoint = PointF(1500f, 900f),
                width = 140f,
                height = 180f,
                pedalTravelDistance = 140f,
                boundAxis = MotionEvent.AXIS_BRAKE
            )
        )

        // 5. Pedal Kopling (Pedal Kiri - Shift di PC)
        activeWidgets.add(
            MappingWidget(
                id = "pedal_clutch",
                name = "Pedal Kopling",
                type = MappingType.PEDAL_ANALOG,
                centerPoint = PointF(1300f, 900f),
                width = 140f,
                height = 180f,
                pedalTravelDistance = 140f,
                boundAxis = MotionEvent.AXIS_Y
            )
        )

        // 6. Tombol Fisik dari Konfigurasi PC:
        // Klakson (Button 0)
        activeWidgets.add(
            MappingWidget(
                id = "btn_horn",
                name = "Klakson",
                type = MappingType.TAP,
                centerPoint = PointF(300f, 856f),
                width = 100f,
                height = 100f,
                boundScanCode = 0,
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_A,
                boundKeyName = "Horn (Button 0)"
            )
        )

        // Pintu (Button 4)
        activeWidgets.add(
            MappingWidget(
                id = "btn_door",
                name = "Buka Pintu",
                type = MappingType.TAP,
                centerPoint = PointF(1850f, 200f),
                width = 80f,
                height = 80f,
                boundScanCode = 4,
                boundKeyCode = KeyEvent.KEYCODE_BUTTON_Y,
                boundKeyName = "Pintu (Button 4)"
            )
        )

        // Handbrake (Button 23)
        activeWidgets.add(
            MappingWidget(
                id = "btn_handbrake",
                name = "Handbrake",
                type = MappingType.TAP,
                centerPoint = PointF(1850f, 500f),
                width = 90f,
                height = 90f,
                boundScanCode = 23,
                boundKeyName = "Handbrake (Button 23)"
            )
        )

        // Lampu Hazard (Button 5)
        activeWidgets.add(
            MappingWidget(
                id = "btn_hazard",
                name = "Lampu Hazard",
                type = MappingType.TAP,
                centerPoint = PointF(200f, 380f),
                width = 80f,
                height = 80f,
                boundScanCode = 5,
                boundKeyName = "Hazard (Button 5)"
            )
        )

        // Sein Kiri (Button 2) & Sein Kanan (Button 1)
        activeWidgets.add(
            MappingWidget(
                id = "btn_sein_kiri",
                name = "Sein Kiri",
                type = MappingType.TAP,
                centerPoint = PointF(120f, 600f),
                width = 80f,
                height = 80f,
                boundScanCode = 2,
                boundKeyName = "Sein Kiri (Button 2)"
            )
        )
        activeWidgets.add(
            MappingWidget(
                id = "btn_sein_kanan",
                name = "Sein Kanan",
                type = MappingType.TAP,
                centerPoint = PointF(220f, 600f),
                width = 80f,
                height = 80f,
                boundScanCode = 1,
                boundKeyName = "Sein Kanan (Button 1)"
            )
        )

        // Kamera (Button 3)
        activeWidgets.add(
            MappingWidget(
                id = "btn_cam",
                name = "Ganti Kamera",
                type = MappingType.TAP,
                centerPoint = PointF(100f, 100f),
                width = 80f,
                height = 80f,
                boundScanCode = 3,
                boundKeyName = "Kamera (Button 3)"
            )
        )

        // Lampu Utama (Button 7) & Dim (Button 14)
        activeWidgets.add(
            MappingWidget(
                id = "btn_lampu",
                name = "Lampu Utama",
                type = MappingType.TAP,
                centerPoint = PointF(280f, 380f),
                width = 80f,
                height = 80f,
                boundScanCode = 7,
                boundKeyName = "Lampu (Button 7)"
            )
        )
        activeWidgets.add(
            MappingWidget(
                id = "btn_dim",
                name = "Lampu Dim",
                type = MappingType.TAP,
                centerPoint = PointF(360f, 380f),
                width = 80f,
                height = 80f,
                boundScanCode = 14,
                boundKeyName = "Dim (Button 14)"
            )
        )

        // Telolet / Basuri (Button 10)
        activeWidgets.add(
            MappingWidget(
                id = "btn_telolet",
                name = "Telolet",
                type = MappingType.TAP,
                centerPoint = PointF(450f, 380f),
                width = 80f,
                height = 80f,
                boundScanCode = 10,
                boundKeyName = "Telolet (Button 10)"
            )
        )
    }

    // =========================================================================
    // FITUR 3: MULTI-PROFIL MANAGEMENT
    // =========================================================================

    fun getSavedProfileList(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILE_LIST, "[\"BUSSID Default\"]") ?: "[\"BUSSID Default\"]"
        val list = mutableListOf<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
        } catch (e: Exception) {
            list.add("BUSSID Default")
        }
        return list
    }

    fun saveCurrentProfile(context: Context, profileName: String = currentProfileName) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentProfileName = profileName

        // Update daftar profil
        val list = getSavedProfileList(context).toMutableList()
        if (!list.contains(profileName)) {
            list.add(profileName)
            val arr = JSONArray(list)
            prefs.edit().putString(KEY_PROFILE_LIST, arr.toString()).apply()
        }
        prefs.edit().putString(KEY_ACTIVE_PROFILE, profileName).apply()

        // Simpan data widget profil
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
                put("pedalTravelDistance", w.pedalTravelDistance)
                put("deadzone", w.deadzone)
                put("boundKeyCode", w.boundKeyCode)
                put("boundScanCode", w.boundScanCode)
                put("boundKeyName", w.boundKeyName)
                put("boundAxis", w.boundAxis)
                put("requireClutchToShift", w.requireClutchToShift)

                val slotsObj = JSONObject()
                for ((slotKey, pt) in w.shifterSlots) {
                    val slotPointObj = JSONObject().apply {
                        put("x", pt.x)
                        put("y", pt.y)
                    }
                    slotsObj.put(slotKey, slotPointObj)
                }
                put("shifterSlots", slotsObj)

                val gearBindingsObj = JSONObject()
                for ((gear, code) in w.gearKeyBindings) {
                    gearBindingsObj.put(gear, code)
                }
                put("gearKeyBindings", gearBindingsObj)

                val gearScanBindingsObj = JSONObject()
                for ((gear, scode) in w.gearScanCodeBindings) {
                    gearScanBindingsObj.put(gear, scode)
                }
                put("gearScanCodeBindings", gearScanBindingsObj)

                val gearNamesObj = JSONObject()
                for ((gear, name) in w.gearKeyNames) {
                    gearNamesObj.put(gear, name)
                }
                put("gearKeyNames", gearNamesObj)
            }
            jsonArray.put(obj)
        }

        prefs.edit().putString(KEY_PROFILE_PREFIX + profileName, jsonArray.toString()).apply()
    }

    fun loadProfile(context: Context, profileName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROFILE_PREFIX + profileName, null)
        if (jsonStr == null) {
            setupDefaultBussidProfile()
            return
        }

        try {
            val jsonArray = JSONArray(jsonStr)
            activeWidgets.clear()
            currentProfileName = profileName
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
                    pedalTravelDistance = obj.optDouble("pedalTravelDistance", 160.0).toFloat(),
                    deadzone = obj.optDouble("deadzone", 0.03).toFloat(),
                    boundKeyCode = obj.optInt("boundKeyCode", -1),
                    boundScanCode = obj.optInt("boundScanCode", -1),
                    boundKeyName = obj.optString("boundKeyName", "None"),
                    boundAxis = obj.optInt("boundAxis", -1),
                    requireClutchToShift = obj.optBoolean("requireClutchToShift", true)
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

                val gearBindingsObj = obj.optJSONObject("gearKeyBindings")
                if (gearBindingsObj != null) {
                    val keys = gearBindingsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        widget.gearKeyBindings[k] = gearBindingsObj.getInt(k)
                    }
                }

                val gearScanBindingsObj = obj.optJSONObject("gearScanCodeBindings")
                if (gearScanBindingsObj != null) {
                    val keys = gearScanBindingsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        widget.gearScanCodeBindings[k] = gearScanBindingsObj.getInt(k)
                    }
                }

                val gearNamesObj = obj.optJSONObject("gearKeyNames")
                if (gearNamesObj != null) {
                    val keys = gearNamesObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        widget.gearKeyNames[k] = gearNamesObj.getString(k)
                    }
                }

                activeWidgets.add(widget)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            setupDefaultBussidProfile()
        }
    }

    fun loadCustomMapping(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeName = prefs.getString(KEY_ACTIVE_PROFILE, "BUSSID Default") ?: "BUSSID Default"
        loadProfile(context, activeName)
    }

    // =========================================================================
    // FITUR 5: HAPTIC FEEDBACK & FORCE FEEDBACK EMULATION
    // =========================================================================

    fun triggerHapticFeedback(context: Context, durationMs: Long = 40L) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun processMotionEvent(event: MotionEvent, context: Context? = null): Boolean {
        // Jika sedang mendengarkan scanning axis saat tambah tombol/mapping:
        val axisScanListener = axisListenCallback
        if (axisScanListener != null) {
            val allAxes = intArrayOf(
                MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
                MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_RTRIGGER,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_THROTTLE, MotionEvent.AXIS_RUDDER, MotionEvent.AXIS_WHEEL
            )
            for (ax in allAxes) {
                val v = event.getAxisValue(ax)
                if (kotlin.math.abs(v) > 0.25f) {
                    val name = MotionEvent.axisToString(ax).removePrefix("AXIS_")
                    axisScanListener(ax, name)
                    axisListenCallback = null
                    return true
                }
            }
        }

        // Jika sedang di-pause oleh user (misal untuk navigasi HP / chatting / buka menu):
        if (isMappingPaused) {
            return false
        }

        val service = TouchAccessibilityService.instance ?: return false

        // Membaca semua event MotionEvent dari perangkat eksternal USB / OTG tanpa filter ketat
        // Persis seperti cara kerja WinMM joyGetPosEx di Python

        // 1. Setir Kemudi Axis X (900 Derajat)
        val steeringWidget = activeWidgets.firstOrNull { it.type == MappingType.STEERING_WHEEL }
        val steeringAxisId = steeringWidget?.boundAxis?.takeIf { it != -1 } ?: MotionEvent.AXIS_X
        val steeringAxisVal = event.getAxisValue(steeringAxisId)
        if (steeringWidget != null) {
            val effectiveVal = if (kotlin.math.abs(steeringAxisVal) < steeringWidget.deadzone) 0f else steeringAxisVal
            // Hitung derajat live untuk HUD selalu, meskipun delta kecil
            val liveAngle = (effectiveVal * (steeringWidget.steeringDegrees / 2f))
            onSteeringAngleChanged?.invoke(liveAngle, effectiveVal)

            // Selalu update target setir setiap frame agar dispatch dapat menggabungkan
            // steering + pedal dalam satu GestureDescription (threshold dihapus).
            service.injectCircularSteering(
                centerX = steeringWidget.centerPoint.x,
                centerY = steeringWidget.centerPoint.y,
                radius = steeringWidget.steeringRadius,
                fromNorm = lastSteeringNormVal,
                toNorm = effectiveVal,
                maxDegrees = steeringWidget.steeringDegrees,
                sensitivity = steeringWidget.steeringSensitivity,
                linearity = steeringWidget.steeringLinearity
            )

            // Haptic getaran saat setir mentok kiri/kanan (Lock-to-lock)
            if (kotlin.math.abs(effectiveVal) >= 0.98f && kotlin.math.abs(lastSteeringNormVal) < 0.98f) {
                context?.let { triggerHapticFeedback(it, 60L) }
            }

            lastSteeringNormVal = effectiveVal
        }

        // 2. Pedal Gas & Rem (Progresif Analog & Dynamic Bound Axis)
        // Mendukung berbagai rentang hardware pedal OTG (0..1 atau -1..1):
        fun normalizePedal(rawVal: Float, deadzone: Float): Float {
            // Jika rentang pedal -1.0 (dilepas) sampai 1.0 (diinjak):
            val normVal = if (rawVal < 0f) {
                // rentang -1.0..1.0 dinormalisasi ke 0.0..1.0
                ((rawVal + 1.0f) / 2.0f).coerceIn(0f, 1f)
            } else {
                rawVal.coerceIn(0f, 1f)
            }
            return if (normVal < deadzone) 0f else normVal
        }

        val gasWidget = activeWidgets.firstOrNull { it.id == "pedal_gas" }
        if (gasWidget != null) {
            val gasAxis = if (gasWidget.boundAxis != -1) gasWidget.boundAxis else MotionEvent.AXIS_GAS
            var gasVal = event.getAxisValue(gasAxis)
            if (gasVal == 0f && gasWidget.boundAxis == -1) gasVal = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
            if (gasVal == 0f && gasWidget.boundAxis == -1) gasVal = event.getAxisValue(MotionEvent.AXIS_RZ)
            val effGas = normalizePedal(gasVal, gasWidget.deadzone)
            service.injectPedalAnalog(
                baseX = gasWidget.centerPoint.x,
                baseY = gasWidget.centerPoint.y,
                travelDistance = gasWidget.pedalTravelDistance,
                pressure = effGas,
                isBrake = false
            )
        }

        val brakeWidget = activeWidgets.firstOrNull { it.id == "pedal_brake" }
        if (brakeWidget != null) {
            val brakeAxis = if (brakeWidget.boundAxis != -1) brakeWidget.boundAxis else MotionEvent.AXIS_BRAKE
            var brakeVal = event.getAxisValue(brakeAxis)
            if (brakeVal == 0f && brakeWidget.boundAxis == -1) brakeVal = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
            if (brakeVal == 0f && brakeWidget.boundAxis == -1) brakeVal = event.getAxisValue(MotionEvent.AXIS_Z)
            val effBrake = normalizePedal(brakeVal, brakeWidget.deadzone)
            service.injectPedalAnalog(
                baseX = brakeWidget.centerPoint.x,
                baseY = brakeWidget.centerPoint.y,
                travelDistance = brakeWidget.pedalTravelDistance,
                pressure = effBrake,
                isBrake = true
            )
        }

        // 3. Pedal Kopling (Clutch Simulation: Netralkan Transmisi & Syarat Pindah Gigi)
        val clutchWidget = activeWidgets.firstOrNull { it.id == "pedal_clutch" }
        if (clutchWidget != null) {
            val clutchAxis = if (clutchWidget.boundAxis != -1) clutchWidget.boundAxis else MotionEvent.AXIS_Y
            var clutchVal = event.getAxisValue(clutchAxis)
            if (clutchVal == 0f && clutchWidget.boundAxis == -1) clutchVal = event.getAxisValue(MotionEvent.AXIS_Z)
            
            // Injakan kopling aktif jika melebihi threshold 30%
            val clutchPressedNow = kotlin.math.abs(clutchVal) > 0.30f
            if (clutchPressedNow != isClutchDepressed) {
                isClutchDepressed = clutchPressedNow
                onClutchChanged?.invoke(isClutchDepressed)

                val shifterWidget = activeWidgets.firstOrNull { it.type == MappingType.SHIFTER_SLIDER }
                if (shifterWidget != null) {
                    val neutralX = shifterWidget.centerPoint.x
                    val neutralY = shifterWidget.centerPoint.y

                    if (isClutchDepressed) {
                        // Pedal kopling diinjak: Putus kopling -> Tarik tuas ke posisi NETRAL virtual di game
                        if (currentGear != "N") {
                            lastGearBeforeClutch = currentGear
                            val activeSlot = shifterWidget.shifterSlots[currentGear]
                            val startX = activeSlot?.x ?: neutralX
                            val startY = activeSlot?.y ?: neutralY

                            val waypoints = listOf(
                                Pair(startX, startY),
                                Pair(startX, neutralY),
                                Pair(neutralX, neutralY)
                            )
                            service.injectMultiPointPath(waypoints, durationMs = 140L, holdAtEndMs = 80L)
                            currentGear = "N"
                            onGearChanged?.invoke("N (Kopling)")
                            context?.let { triggerHapticFeedback(it, 30L) }
                        }
                    } else {
                        // Pedal kopling dilepas: Sambungkan kembali gigi fisik jika tuas sedang berada di suatu gigi
                        val physicalSlot = lastGearBeforeClutch
                        if (physicalSlot != null && physicalSlot != "N") {
                            val targetPos = shifterWidget.shifterSlots[physicalSlot]
                            if (targetPos != null) {
                                currentGear = physicalSlot
                                onGearChanged?.invoke(physicalSlot)
                                context?.let { triggerHapticFeedback(it, 40L) }

                                val waypoints = listOf(
                                    Pair(neutralX, neutralY),
                                    Pair(targetPos.x, neutralY),
                                    Pair(targetPos.x, targetPos.y)
                                )
                                service.injectMultiPointPath(waypoints, durationMs = 160L, holdAtEndMs = 100L)
                            }
                        }
                    }
                }
            }
        }

        // 4. Custom TAP / Macro Widgets yang diikat ke Axis Analog (Gas/Rem/Kopling/Trigger)
        for (w in activeWidgets) {
            if ((w.type == MappingType.TAP || w.type == MappingType.MACRO_TAP) && w.boundAxis != -1) {
                val axisVal = event.getAxisValue(w.boundAxis)
                val isTriggered = kotlin.math.abs(axisVal) > 0.35f
                if (isTriggered) {
                    if (w.type == MappingType.MACRO_TAP && w.macroPoints.isNotEmpty()) {
                        service.injectMacroTaps(w.macroPoints, w.macroDelayMs)
                    } else {
                        service.injectTap(w.centerPoint.x, w.centerPoint.y)
                    }
                }
            }
        }

        return true
    }

    fun processKeyEvent(event: KeyEvent, context: Context? = null): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scanCode
        val action = event.action

        // Format nama tombol yang ramah & tidak pernah hanya "UNKNOWN"
        val rawName = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
        val readableName = if (rawName == "UNKNOWN" || keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            if (scanCode > 0) "Button (Scan $scanCode)" else "Button (ID $keyCode)"
        } else {
            rawName
        }

        // Jika user sedang menunggu input tombol hardware pada dialog "Tekan tombol di setir..."
        val listener = keyListenCallback
        if (listener != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                listener(keyCode, scanCode, readableName)
                keyListenCallback = null
            }
            return true
        }

        if (isMappingPaused) {
            return false
        }

        val service = TouchAccessibilityService.instance ?: return false

        // Tap Tombol Kustom & Macro (Cocokkan KeyCode ATAU ScanCode untuk tombol UNKNOWN)
        val tapWidget = activeWidgets.firstOrNull {
            (it.type == MappingType.TAP || it.type == MappingType.MACRO_TAP) &&
            ((it.boundKeyCode != -1 && it.boundKeyCode != KeyEvent.KEYCODE_UNKNOWN && it.boundKeyCode == keyCode) ||
             (it.boundScanCode != -1 && it.boundScanCode == scanCode))
        }
        if (tapWidget != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (tapWidget.type == MappingType.MACRO_TAP && tapWidget.macroPoints.isNotEmpty()) {
                    service.injectMacroTaps(tapWidget.macroPoints, tapWidget.macroDelayMs)
                } else {
                    service.injectTap(tapWidget.centerPoint.x, tapWidget.centerPoint.y)
                }
                context?.let { triggerHapticFeedback(it, 35L) }
            }
            return true
        }

        // H-Shifter Presisi (BUSSID Live Knob Tracking & Constrained H-Rail)
        val shifterWidget = activeWidgets.firstOrNull { it.type == MappingType.SHIFTER_SLIDER }
        if (shifterWidget != null) {
            val targetGear = mapKeyCodeToGear(keyCode, scanCode)
            if (targetGear != null) {
                val neutralX = shifterWidget.centerPoint.x
                val neutralY = shifterWidget.centerPoint.y
                val targetPos = shifterWidget.shifterSlots[targetGear]

                if (targetPos != null) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        // CEK KOPLING: Jika requireClutchToShift aktif & kopling belum diinjak, tolak perpindahan gigi
                        if (shifterWidget.requireClutchToShift && !isClutchDepressed) {
                            // Getar haptic peringatan (misal suara/sensasi gear grind / gigi nyangkut)
                            context?.let { triggerHapticFeedback(it, 120L) }
                            onGearChanged?.invoke("Harus Injak Kopling!")
                            return true
                        }

                        val prevGear = currentGear
                        currentGear = targetGear
                        lastGearBeforeClutch = targetGear
                        onGearChanged?.invoke(targetGear)
                        context?.let { triggerHapticFeedback(it, 50L) }

                        // Tentukan koordinat awal sentuhan:
                        // Jika kopling sedang diinjak, bola tuas game saat ini ada di Netral (N).
                        // Jika tidak ada kopling dan pindah langsung (misal 5 ke 2), mulai dari posisi gigi lama.
                        val startPt = if (prevGear == "N" || isClutchDepressed) {
                            Pair(neutralX, neutralY)
                        } else {
                            val pos = shifterWidget.shifterSlots[prevGear]
                            if (pos != null) Pair(pos.x, pos.y) else Pair(neutralX, neutralY)
                        }

                        // Bangun jalur H-gate terpandu
                        val waypoints = mutableListOf<Pair<Float, Float>>()
                        waypoints.add(startPt)

                        if (startPt.first == neutralX && startPt.second == neutralY) {
                            // Dari Netral -> Geser ke koridor kolom gigi target -> Dorong ke slot gigi target
                            waypoints.add(Pair(targetPos.x, neutralY))
                            waypoints.add(Pair(targetPos.x, targetPos.y))
                        } else if (prevGear == targetGear) {
                            // Sudah di gigi yang sama
                            waypoints.add(Pair(targetPos.x, targetPos.y))
                        } else {
                            // Antar Gigi Acak (misal Gigi 5 ke 2, Gigi 6 ke 3, Gigi 4 ke R):
                            val sameColumn = kotlin.math.abs(startPt.first - targetPos.x) < 25f
                            if (sameColumn) {
                                // Kolom sama (misal 1 ke 2, 3 ke 4, 5 ke 6): Gerak vertikal lurus lewat tengah
                                waypoints.add(Pair(startPt.first, neutralY))
                                waypoints.add(Pair(targetPos.x, targetPos.y))
                            } else {
                                // Beda kolom acak (misal 5 ke 2, 6 ke 3, 4 ke R, 1 ke 5):
                                // Tarik keluar dari gigi lama ke koridor netral -> geser horizontal ke kolom baru -> dorong ke slot gigi baru
                                waypoints.add(Pair(startPt.first, neutralY))
                                waypoints.add(Pair(targetPos.x, neutralY))
                                waypoints.add(Pair(targetPos.x, targetPos.y))
                            }
                        }

                        service.injectMultiPointPath(waypoints, durationMs = 180L, holdAtEndMs = 120L)
                    } else if (action == KeyEvent.ACTION_UP && lastGearBeforeClutch == targetGear) {
                        // Tuas fisik dilepas ke Netral
                        lastGearBeforeClutch = null

                        if (currentGear != "N") {
                            currentGear = "N"
                            onGearChanged?.invoke("N")

                            val waypoints = listOf(
                                Pair(targetPos.x, targetPos.y), // Mulai tepat di bola gigi yang aktif
                                Pair(targetPos.x, neutralY),     // Keluar ke koridor horizontal
                                Pair(neutralX, neutralY)         // Masuk kembali ke titik tengah (Netral)
                            )
                            service.injectMultiPointPath(waypoints, durationMs = 140L, holdAtEndMs = 80L)
                        }
                    }
                }
                return true
            }
        }

        return false
    }

    fun mapKeyCodeToGear(keyCode: Int, scanCode: Int = 0): String? {
        val shifterWidget = activeWidgets.firstOrNull { it.type == MappingType.SHIFTER_SLIDER }
        if (shifterWidget != null) {
            // Prioritas 1: Cek scanCode jika tombol terdeteksi sebagai UNKNOWN
            if (scanCode > 0 && shifterWidget.gearScanCodeBindings.isNotEmpty()) {
                for ((gear, boundScan) in shifterWidget.gearScanCodeBindings) {
                    if (boundScan == scanCode) return gear
                }
            }
            // Prioritas 2: Cek keyCode manual
            if (shifterWidget.gearKeyBindings.isNotEmpty()) {
                for ((gear, boundCode) in shifterWidget.gearKeyBindings) {
                    if (boundCode != -1 && boundCode != KeyEvent.KEYCODE_UNKNOWN && boundCode == keyCode) return gear
                }
            }
        }

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
