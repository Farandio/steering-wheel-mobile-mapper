package com.mapper.pxnv9.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.mapper.pxnv9.manager.MappingManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Service utama untuk menginjeksi event sentuhan via AccessibilityService.dispatchGesture (tanpa Root).
 *
 * ARSITEKTUR MULTI-FINGER:
 * Setir, gas, dan rem masing-masing adalah "jari virtual" yang terpisah.
 * Semua jari yang aktif dikemas ke dalam SATU GestureDescription per dispatch cycle
 * sehingga tidak saling membatalkan (setir bisa aktif bersamaan dengan gas/rem).
 *
 * Flow:
 *   onMotionEvent / onKeyEvent
 *     → update FingerState (path, isActive)
 *     → scheduleDispatch()
 *       → doDispatch() : bangun 1 GestureDescription dengan semua jari aktif
 *         → onCompleted : simpan stroke refs untuk continueStroke berikutnya
 *         → jika ada update baru selama in-flight → dispatch ulang segera setelah selesai
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchAccessibility"
        var instance: TouchAccessibilityService? = null
            private set
    }

    // =========================================================================
    // MULTI-FINGER GESTURE STATE
    // =========================================================================

    /**
     * State satu jari virtual.
     * prevStroke : referensi stroke terakhir yang di-dispatch (untuk continueStroke).
     * pendingPath: jalur yang akan di-dispatch pada cycle berikutnya.
     * isActive   : apakah jari ini sedang menahan sentuhan.
     */
    private data class FingerState(
        var prevStroke: GestureDescription.StrokeDescription? = null,
        var pendingPath: Path? = null,
        var isActive: Boolean = false
    )

    private val steeringFinger = FingerState()  // Jari setir (busur lingkaran)
    private val gasFinger      = FingerState()  // Jari gas (slider vertikal)
    private val brakeFinger    = FingerState()  // Jari rem (slider vertikal)

    // Satu gesture per cycle — cegah tumpang tindih dispatch
    private var gestureInFlight = false
    // Ada update masuk saat gesture sedang berjalan → dispatch ulang setelah selesai
    private var dirtyAfterFlight = false

    /**
     * Jadwalkan dispatch cycle berikutnya.
     * Jika sedang in-flight, tandai dirty saja — dispatch akan diulang di onCompleted.
     */
    private fun scheduleDispatch() {
        if (gestureInFlight) {
            dirtyAfterFlight = true
            return
        }
        doDispatch()
    }

    /**
     * Kirim satu GestureDescription berisi semua jari virtual yang aktif.
     * Menggunakan continueStroke agar tiap jari terasa smooth dan tidak "lompat".
     */
    private fun doDispatch() {
        val builder = GestureDescription.Builder()
        var strokeCount = 0

        // Simpan referensi stroke baru untuk tiap jari (dipakai di onCompleted)
        var newSteeringStroke: GestureDescription.StrokeDescription? = null
        var newGasStroke: GestureDescription.StrokeDescription? = null
        var newBrakeStroke: GestureDescription.StrokeDescription? = null

        fun addFinger(finger: FingerState, storeTo: (GestureDescription.StrokeDescription) -> Unit) {
            val path = finger.pendingPath ?: return
            if (!finger.isActive) return
            val stroke = buildContinuedStroke(finger.prevStroke, path, willContinue = true)
            builder.addStroke(stroke)
            storeTo(stroke)
            strokeCount++
        }

        addFinger(steeringFinger) { newSteeringStroke = it }
        addFinger(gasFinger)      { newGasStroke = it }
        addFinger(brakeFinger)    { newBrakeStroke = it }

        if (strokeCount == 0) return

        gestureInFlight = true
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // Simpan referensi stroke untuk continueStroke di cycle berikutnya
                steeringFinger.prevStroke = if (steeringFinger.isActive) newSteeringStroke else null
                gasFinger.prevStroke      = if (gasFinger.isActive)      newGasStroke      else null
                brakeFinger.prevStroke    = if (brakeFinger.isActive)    newBrakeStroke    else null

                gestureInFlight = false
                if (dirtyAfterFlight) {
                    dirtyAfterFlight = false
                    doDispatch()
                }
            }
            override fun onCancelled(g: GestureDescription?) {
                // Reset semua referensi — stroke berikutnya akan mulai baru
                steeringFinger.prevStroke = null
                gasFinger.prevStroke      = null
                brakeFinger.prevStroke    = null
                gestureInFlight = false
            }
        }, null)
    }

    /**
     * Buat StrokeDescription baru.
     * Jika ada prevStroke (jari sudah menyentuh layar sebelumnya), gunakan continueStroke
     * agar pergerakan terasa halus tanpa angkat jari.
     * Jika tidak ada prevStroke (jari baru), buat stroke baru (touch down).
     */
    private fun buildContinuedStroke(
        prev: GestureDescription.StrokeDescription?,
        path: Path,
        willContinue: Boolean,
        durationMs: Long = 50L
    ): GestureDescription.StrokeDescription {
        if (prev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                prev.continueStroke(path, 0L, durationMs, willContinue)
            } catch (e: Exception) {
                // continueStroke gagal (gesture dibatalkan) → mulai stroke baru
                GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
            }
        }
        return GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
    }

    // =========================================================================
    // ACCESSIBILITY SERVICE LIFECYCLE
    // =========================================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchAccessibilityService terhubung.")

        // Daftarkan SOURCE_JOYSTICK | SOURCE_GAMEPAD agar onMotionEvent() menerima
        // axis dari setir & pedal OTG (berlaku di semua versi sejak minSdk = 26).
        // API 34+: field motionEventSources ada di public API.
        // API 26-33: field ada di runtime tapi belum public → akses via refleksi.
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.motionEventSources = InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD
            } else {
                val field = info.javaClass.getDeclaredField("motionEventSources")
                field.isAccessible = true
                field.setInt(info, InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD)
            }
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Tidak dapat set motionEventSources: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Jangan proses UI tree BUSSID — mencegah game freeze
    }

    override fun onInterrupt() {
        Log.w(TAG, "TouchAccessibilityService terputus.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Menerima axis events dari setir & pedal OTG secara langsung via AccessibilityService.
     * Tidak butuh overlay window interceptor — public API sejak API 26.
     */
    override fun onMotionEvent(event: MotionEvent) {
        MappingManager.processMotionEvent(event, this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val handled = MappingManager.processKeyEvent(event)
        return if (handled) true else super.onKeyEvent(event)
    }

    // =========================================================================
    // PUBLIC: LEPASKAN SEMUA SENTUHAN (untuk PAUSE mode)
    // =========================================================================

    /**
     * Hentikan semua jari virtual sekarang.
     * Dipanggil saat mapping di-PAUSE agar user bebas menyentuh layar HP.
     */
    fun releaseAllTouches() {
        steeringFinger.apply { isActive = false; pendingPath = null; prevStroke = null }
        gasFinger.apply      { isActive = false; pendingPath = null; prevStroke = null }
        brakeFinger.apply    { isActive = false; pendingPath = null; prevStroke = null }
        gestureInFlight  = false
        dirtyAfterFlight = false
    }

    // =========================================================================
    // PUBLIC: SETIR (Circular Steering)
    // =========================================================================

    /**
     * Gerakkan jari virtual setir sepanjang busur lingkaran dari posisi lama ke posisi baru.
     * Rumus identik dengan wheel_overlay_mapper.py di PC:
     *   theta_rad = radians(angle_deg - 90)
     *   x = cx + r * cos(theta_rad)
     *   y = cy + r * sin(theta_rad)
     *
     * Menggunakan multi-finger dispatch sehingga setir bisa aktif bersamaan dengan gas/rem.
     */
    fun injectCircularSteering(
        centerX: Float,
        centerY: Float,
        radius: Float,
        fromNorm: Float,
        toNorm: Float,
        maxDegrees: Float,
        sensitivity: Float,
        linearity: Float = 1.0f
    ) {
        // Setir kembali ke tengah → lepaskan jari setir
        if (kotlin.math.abs(toNorm) < 0.02f) {
            steeringFinger.isActive   = false
            steeringFinger.pendingPath = null
            steeringFinger.prevStroke  = null
            return
        }

        val maxAngle   = (maxDegrees / 2f) * sensitivity
        val fromAngle  = fromNorm * maxAngle
        val toAngle    = toNorm   * maxAngle

        // Konversi ke radian (Python-style: angle - 90)
        val fromRad = Math.toRadians((fromAngle - 90.0)).toFloat()
        val toRad   = Math.toRadians((toAngle   - 90.0)).toFloat()

        val path = Path().apply {
            moveTo(
                centerX + radius * cos(fromRad),
                centerY + radius * sin(fromRad)
            )
            // Interpolasi busur dengan 6 titik agar gerak halus di BUSSID
            val steps = 6
            for (i in 1..steps) {
                val t      = i / steps.toFloat()
                val curRad = fromRad + (toRad - fromRad) * t
                lineTo(
                    centerX + radius * cos(curRad),
                    centerY + radius * sin(curRad)
                )
            }
        }

        steeringFinger.pendingPath = path
        steeringFinger.isActive    = true
        scheduleDispatch()
    }

    // =========================================================================
    // PUBLIC: PEDAL GAS & REM (Analog Slider)
    // =========================================================================

    /**
     * Gerakkan jari virtual pedal secara vertikal sesuai tekanan (0.0 = lepas, 1.0 = penuh).
     * Menggunakan multi-finger dispatch sehingga gas/rem bisa aktif bersamaan dengan setir.
     */
    fun injectPedalAnalog(
        baseX: Float,
        baseY: Float,
        travelDistance: Float,
        pressure: Float,
        isBrake: Boolean = false
    ) {
        val finger    = if (isBrake) brakeFinger else gasFinger
        val isPressed = pressure > 0.12f

        if (!isPressed) {
            // Pedal dilepas → hentikan jari ini (touch-up otomatis setelah stroke 50ms habis)
            finger.isActive    = false
            finger.pendingPath = null
            finger.prevStroke  = null
            return
        }

        val targetY = baseY - (travelDistance * pressure.coerceIn(0f, 1f))
        val path    = Path().apply {
            moveTo(baseX, baseY)
            lineTo(baseX, targetY)
        }
        finger.pendingPath = path
        finger.isActive    = true
        scheduleDispatch()
    }

    // =========================================================================
    // PUBLIC: TAP TUNGGAL (Klakson, Lampu, Hazard, dll)
    // =========================================================================

    /**
     * Tap singkat pada koordinat layar (tidak menggunakan multi-finger state,
     * cukup dispatch sekali karena tap tidak perlu di-hold).
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = 40L) {
        if (MappingManager.useAdbZeroLatencyMode && ShellTouchInjector.isAdbConnected) {
            ShellTouchInjector.injectTap(x, y)
            return
        }
        val path   = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // =========================================================================
    // PUBLIC: MACRO TAPS (Telolet / Basuri)
    // =========================================================================

    fun injectMacroTaps(points: List<com.mapper.pxnv9.model.PointF>, delayBetweenMs: Long = 100L) {
        Thread {
            for (pt in points) {
                injectTap(pt.x, pt.y, 40L)
                try { Thread.sleep(delayBetweenMs) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    // =========================================================================
    // PUBLIC: H-SHIFTER PATH (Perpindahan Gigi)
    // =========================================================================

    fun injectShifterPath(
        startX: Float, startY: Float,
        midX: Float,   midY: Float,
        endX: Float,   endY: Float,
        durationMs: Long = 70L
    ) {
        val path   = Path().apply { moveTo(startX, startY); lineTo(midX, midY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun injectShifterSlide(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 50L) {
        val path   = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /**
     * Jalur multi-titik untuk perpindahan gigi presisi H-gate.
     * Durasi diperlambat (180-220ms) agar bola shifter kuning BUSSID
     * benar-benar menyusuri lorong dan tidak dianggap glitch oleh game.
     */
    fun injectMultiPointPath(
        points: List<Pair<Float, Float>>,
        durationMs: Long = 180L,
        holdAtEndMs: Long = 120L
    ) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
            // Tahan di titik akhir agar sensor slot BUSSID mengunci gigi
            lineTo(points.last().first, points.last().second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs + holdAtEndMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
