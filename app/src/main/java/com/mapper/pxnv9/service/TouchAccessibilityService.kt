package com.mapper.pxnv9.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.mapper.pxnv9.manager.MappingManager
import kotlin.math.cos
import kotlin.math.sin

/**
 * Service injeksi sentuhan via AccessibilityService.dispatchGesture (tanpa Root).
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARSITEKTUR DUA-CHANNEL (seperti Python: mouse independen dari keyboard)
 * ═══════════════════════════════════════════════════════════════════
 *
 * CHANNEL A — SETIR (steering loop independen)
 *   Loop berlari setiap STEER_INTERVAL_MS (16ms ≈ 60fps).
 *   Jari setir TIDAK PERNAH dilepas (always-hold, seperti mouseDown Python).
 *   continueStroke memperbarui posisi tanpa angkat jari.
 *   Loop berjalan sendiri via handler.postDelayed tanpa menunggu pedal.
 *
 * CHANNEL B — PEDAL GAS & REM (hold-tap independen)
 *   Gas dan rem adalah HOLD TOUCH di satu titik (bukan drag analog).
 *   Ini persis seperti Python: keyboard W/S hold.
 *   Masing-masing punya dispatchGesture-nya sendiri yang TIDAK blokir setir.
 *   Saat pedal diinjak: touch-down hold panjang (PEDAL_HOLD_MS).
 *   Saat masih tertekan saat hold habis: perpanjang (continueStroke).
 *   Saat dilepas: stop perpanjangan → touch-up otomatis.
 *
 * KENAPA DUA CHANNEL INDEPENDEN?
 *   dispatchGesture() tidak mengharuskan semua jari dalam satu GestureDescription.
 *   Android mendukung multiple simultaneous gestures dari satu AccessibilityService.
 *   Dengan dua channel terpisah, setir tidak perlu menunggu pedal selesai dan
 *   sebaliknya — persis seperti mouse + keyboard di Python bisa bersamaan.
 * ═══════════════════════════════════════════════════════════════════
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG              = "TouchAccessibility"
        private const val STEER_INTERVAL_MS = 16L   // ~60fps untuk setir halus
        private const val STEER_STROKE_MS   = 20L   // durasi tiap stroke setir (< interval)
        private const val PEDAL_HOLD_MS     = 200L  // durasi satu hold pedal sebelum di-extend
        var instance: TouchAccessibilityService? = null
            private set
    }

    // ─────────────────────────────────────────────────────────────
    // CHANNEL A: SETIR
    // ─────────────────────────────────────────────────────────────

    private var steerCX          = 300f
    private var steerCY          = 856f
    private var steerR           = 168f
    private var steerMaxDeg      = 900f
    private var steerSens        = 1.0f
    private var steerCurrentNorm = 0f   // posisi touch yg sudah di-dispatch
    private var steerTargetNorm  = 0f   // nilai sensor terbaru
    private var steerActive      = false
    private var steerPrevStroke: GestureDescription.StrokeDescription? = null
    private var steerLoopRunning = false

    private val steerHandler = Handler(Looper.getMainLooper())

    private val steerLoop = object : Runnable {
        override fun run() {
            if (!steerActive) {
                steerLoopRunning = false
                steerPrevStroke = null
                return
            }

            val snap = steerTargetNorm
            val path = buildSteerArc(steerCurrentNorm, snap)

            val stroke = if (steerPrevStroke != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { steerPrevStroke!!.continueStroke(path, 0L, STEER_STROKE_MS, true) }
                catch (_: Exception) { GestureDescription.StrokeDescription(path, 0L, STEER_STROKE_MS, true) }
            } else {
                GestureDescription.StrokeDescription(path, 0L, STEER_STROKE_MS, true)
            }

            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        steerCurrentNorm = snap
                        steerPrevStroke  = stroke
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        // Reset stroke agar loop berikutnya mulai touch-down baru
                        steerPrevStroke  = null
                        steerCurrentNorm = snap
                    }
                }, null)

            steerLoopRunning = true
            steerHandler.postDelayed(this, STEER_INTERVAL_MS)
        }
    }

    /** Mulai atau pastikan loop setir berjalan. */
    private fun ensureSteerLoop() {
        if (!steerLoopRunning) {
            steerLoopRunning = true
            steerHandler.post(steerLoop)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // CHANNEL B: PEDAL GAS & REM (hold-tap independen)
    // ─────────────────────────────────────────────────────────────

    private inner class PedalChannel {
        var x          = 0f
        var y          = 0f
        var isPressed  = false   // apakah pedal sedang ditekan
        var inFlight   = false   // apakah dispatch sedang berjalan
        var prevStroke: GestureDescription.StrokeDescription? = null

        fun press(px: Float, py: Float) {
            x = px; y = py
            isPressed = true
            if (!inFlight) dispatch()
        }

        fun release() {
            isPressed = false
            // inFlight akan berakhir sendiri (willContinue=false di dispatch berikutnya)
        }

        fun reset() {
            isPressed  = false
            inFlight   = false
            prevStroke = null
        }

        private fun dispatch() {
            if (inFlight) return
            val path = Path().apply { moveTo(x, y) }

            // Jika masih ditekan → willContinue=true agar BUSSID tidak angkat jari
            val willContinue = isPressed

            val stroke = if (prevStroke != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { prevStroke!!.continueStroke(path, 0L, PEDAL_HOLD_MS, willContinue) }
                catch (_: Exception) { GestureDescription.StrokeDescription(path, 0L, PEDAL_HOLD_MS, willContinue) }
            } else {
                GestureDescription.StrokeDescription(path, 0L, PEDAL_HOLD_MS, willContinue)
            }

            inFlight = true
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        inFlight = false
                        if (isPressed) {
                            // Masih ditekan → perpanjang hold
                            prevStroke = stroke
                            dispatch()
                        } else {
                            // Pedal dilepas → tidak dispatch lagi, touch-up terjadi
                            prevStroke = null
                        }
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        inFlight   = false
                        prevStroke = null
                        if (isPressed) dispatch() // coba lagi
                    }
                }, null)
        }
    }

    private val gasChannel = PedalChannel()
    private val brkChannel = PedalChannel()

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchAccessibilityService terhubung.")

        // Daftarkan SOURCE_JOYSTICK|SOURCE_GAMEPAD agar onMotionEvent()
        // menerima axis setir & pedal OTG.
        // API 34+: field tersedia di public SDK.
        // API 26-33: field ada di runtime → akses via refleksi.
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.motionEventSources = InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD
            } else {
                val f = info.javaClass.getDeclaredField("motionEventSources")
                f.isAccessible = true
                f.setInt(info, InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_GAMEPAD)
            }
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Tidak dapat set motionEventSources: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Jangan proses UI tree BUSSID — mencegah Unity freeze
    }

    override fun onInterrupt() {
        Log.w(TAG, "TouchAccessibilityService terputus.")
    }

    override fun onDestroy() {
        super.onDestroy()
        steerHandler.removeCallbacksAndMessages(null)
        instance = null
    }

    override fun onMotionEvent(event: MotionEvent) {
        MappingManager.processMotionEvent(event, this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val handled = MappingManager.processKeyEvent(event)
        return if (handled) true else super.onKeyEvent(event)
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: PAUSE — lepas semua jari
    // ─────────────────────────────────────────────────────────────

    fun releaseAllTouches() {
        // Hentikan loop setir
        steerActive      = false
        steerLoopRunning = false
        steerHandler.removeCallbacksAndMessages(null)
        steerPrevStroke  = null
        steerCurrentNorm = 0f

        // Reset pedal
        gasChannel.reset()
        brkChannel.reset()
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: SETIR
    // ─────────────────────────────────────────────────────────────

    /**
     * Perbarui target setir dan pastikan loop berjalan.
     * Loop setir independen dari pedal — tidak perlu menunggu satu sama lain.
     *
     * fromNorm DIABAIKAN — service menggunakan steerCurrentNorm sendiri.
     * Jari setir TIDAK PERNAH dilepas berdasarkan sudut (always-hold).
     */
    fun injectCircularSteering(
        centerX: Float, centerY: Float, radius: Float,
        fromNorm: Float,   // dipertahankan untuk kompatibilitas MappingManager
        toNorm: Float,
        maxDegrees: Float, sensitivity: Float,
        linearity: Float = 1.0f
    ) {
        steerCX      = centerX
        steerCY      = centerY
        steerR       = radius
        steerMaxDeg  = maxDegrees
        steerSens    = sensitivity
        steerTargetNorm = toNorm
        steerActive  = true
        ensureSteerLoop()
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: PEDAL GAS & REM
    // ─────────────────────────────────────────────────────────────

    /**
     * Injeksi pedal sebagai HOLD TOUCH di satu titik — persis seperti
     * keyboard W/S hold di Python. Independen dari loop setir.
     *
     * pressure > 0.12 → tekan (hold touch di titik pedal)
     * pressure ≤ 0.12 → lepas (stop hold → BUSSID angkat jari)
     */
    fun injectPedalAnalog(
        baseX: Float, baseY: Float,
        travelDistance: Float,
        pressure: Float,
        isBrake: Boolean = false
    ) {
        val ch = if (isBrake) brkChannel else gasChannel
        if (pressure > 0.12f) {
            ch.press(baseX, baseY)
        } else {
            ch.release()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // INTERNAL: ARC PATH SETIR
    // ─────────────────────────────────────────────────────────────

    /** Hitung busur setir dari fromNorm ke toNorm (8 titik interpolasi). */
    private fun buildSteerArc(fromNorm: Float, toNorm: Float): Path {
        val maxAngle = (steerMaxDeg / 2f) * steerSens
        val fromRad  = Math.toRadians(((fromNorm * maxAngle) - 90.0)).toFloat()
        val toRad    = Math.toRadians(((toNorm   * maxAngle) - 90.0)).toFloat()
        return Path().apply {
            moveTo(steerCX + steerR * cos(fromRad), steerCY + steerR * sin(fromRad))
            repeat(8) { i ->
                val t   = (i + 1) / 8f
                val rad = fromRad + (toRad - fromRad) * t
                lineTo(steerCX + steerR * cos(rad), steerCY + steerR * sin(rad))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: TAP, MACRO, SHIFTER
    // ─────────────────────────────────────────────────────────────

    fun injectTap(x: Float, y: Float, durationMs: Long = 40L) {
        if (MappingManager.useAdbZeroLatencyMode && ShellTouchInjector.isAdbConnected) {
            ShellTouchInjector.injectTap(x, y); return
        }
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(), null, null
        )
    }

    fun injectMacroTaps(points: List<com.mapper.pxnv9.model.PointF>, delayBetweenMs: Long = 100L) {
        Thread {
            for (pt in points) {
                injectTap(pt.x, pt.y, 40L)
                try { Thread.sleep(delayBetweenMs) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    fun injectShifterPath(sx: Float, sy: Float, mx: Float, my: Float, ex: Float, ey: Float, ms: Long = 70L) {
        val path = Path().apply { moveTo(sx, sy); lineTo(mx, my); lineTo(ex, ey) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, ms)).build(), null, null)
    }

    fun injectShifterSlide(sx: Float, sy: Float, ex: Float, ey: Float, ms: Long = 50L) {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, ms)).build(), null, null)
    }

    /**
     * Perpindahan gigi multi-titik (H-gate BUSSID).
     * Durasi lambat agar bola shifter benar-benar menyusuri lorong.
     */
    fun injectMultiPointPath(points: List<Pair<Float, Float>>, durationMs: Long = 180L, holdAtEndMs: Long = 120L) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
            lineTo(points.last().first, points.last().second)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs + holdAtEndMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}
