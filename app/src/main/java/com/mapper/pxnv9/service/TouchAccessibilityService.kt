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
 * CATATAN PENTING: Tilt BUSSID ≠ Touch Injection
 *   Mode "Tilt" BUSSID membaca sensor gyroscope/accelerometer secara
 *   langsung dari hardware. Kita TIDAK BISA mengontrolnya via
 *   dispatchGesture (touch injection) tanpa root/system permission.
 *   → Gunakan mode KEMUDI / RING di BUSSID.
 *
 * ARSITEKTUR: SINGLE-DISPATCH LOOP (Ring Mode)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Loop: doDispatch() → dispatchGesture(16ms) → onCompleted → doDispatch()
 *   Zero-gap, ~62fps. Setir + pedal dalam 1 GestureDescription.
 *   (dispatchGesture() ganda saling cancel → harus 1 GestureDescription.)
 *
 * SAFETY: Watchdog auto-release
 *   Jika tidak ada input wheel selama INPUT_TIMEOUT_MS → lepas semua touch.
 *   Mencegah layar terblokir ketika setir tidak digerakkan / app di-minimize.
 *
 * SAFETY: isMappingPaused check
 *   Jika mapping di-pause → loop berhenti → tidak ada touch yang diinjeksi
 *   → user bisa klik layar normal.
 * ═══════════════════════════════════════════════════════════════════
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG              = "TouchAccessibility"
        private const val STROKE_MS        = 16L    // ~62fps
        private const val INPUT_TIMEOUT_MS = 800L   // auto-release jika wheel diam >800ms
        var instance: TouchAccessibilityService? = null
            private set
    }

    // ─────────────────────────────────────────────────────────────
    // STATE: SETIR (ring arc mode)
    // ─────────────────────────────────────────────────────────────
    private var steerCX          = 300f
    private var steerCY          = 856f
    private var steerR           = 168f
    private var steerMaxDeg      = 900f
    private var steerSens        = 1.0f
    private var steerCurrentNorm = 0f    // posisi terakhir yg di-dispatch
    private var steerTargetNorm  = 0f    // nilai sensor terbaru
    private var steerActive      = false
    private var steerPrevStroke: GestureDescription.StrokeDescription? = null

    // ─────────────────────────────────────────────────────────────
    // STATE: PEDAL (hold touch di satu titik — bukan drag)
    // ─────────────────────────────────────────────────────────────
    private var gasX          = 1700f
    private var gasY          = 900f
    private var gasActive     = false
    private var gasRelease    = false
    private var gasPrevStroke: GestureDescription.StrokeDescription? = null

    private var brkX          = 1500f
    private var brkY          = 900f
    private var brkActive     = false
    private var brkRelease    = false
    private var brkPrevStroke: GestureDescription.StrokeDescription? = null

    // ─────────────────────────────────────────────────────────────
    // DISPATCH ENGINE + SAFETY
    // ─────────────────────────────────────────────────────────────
    private val handler     = Handler(Looper.getMainLooper())
    private var loopRunning = false

    /**
     * Watchdog: auto-release semua touch jika wheel tidak bergerak >INPUT_TIMEOUT_MS.
     * Dicegah agar layar tidak terblokir saat setir diam atau app di-minimize.
     */
    private val inputWatchdog = Runnable {
        Log.d(TAG, "Watchdog: input timeout → releaseAllTouches()")
        releaseAllTouches()
    }

    /** Reset watchdog setiap kali ada input dari wheel. */
    private fun resetWatchdog() {
        handler.removeCallbacks(inputWatchdog)
        handler.postDelayed(inputWatchdog, INPUT_TIMEOUT_MS)
    }

    /** Mulai loop jika belum berjalan. */
    private fun ensureLoop() {
        if (!loopRunning) {
            loopRunning = true
            doDispatch()
        }
        // Loop sedang jalan → state terbaru dibaca di siklus berikutnya (< 16ms)
    }

    /**
     * Satu siklus dispatch.
     *
     * SAFETY CHECK: jika mapping di-pause → berhenti dan lepas semua touch.
     * Mencegah touch terinjeksi saat user navigasi HP / buka menu.
     */
    private fun doDispatch() {
        // ── SAFETY: pause check ──────────────────────────────────
        if (MappingManager.isMappingPaused) {
            loopRunning = false
            releaseAllTouches()
            return
        }

        // ── Snapshot semua state ─────────────────────────────────
        val snapSteer = steerTargetNorm
        val snapGasX  = gasX;  val snapGasY  = gasY
        val snapBrkX  = brkX;  val snapBrkY  = brkY

        val builder = GestureDescription.Builder()
        var count   = 0
        var ns: GestureDescription.StrokeDescription? = null
        var ng: GestureDescription.StrokeDescription? = null
        var nb: GestureDescription.StrokeDescription? = null

        // ── Jari Setir (ring arc) ────────────────────────────────
        if (steerActive) {
            val path = buildSteerArc(steerCurrentNorm, snapSteer)
            ns = makeStroke(steerPrevStroke, path, willContinue = true)
            builder.addStroke(ns)
            count++
        }

        // ── Jari Gas (hold-tap di satu titik) ───────────────────
        if (gasActive || gasRelease) {
            val path = Path().apply { moveTo(snapGasX, snapGasY) }
            ng = makeStroke(gasPrevStroke, path, willContinue = gasActive)
            builder.addStroke(ng)
            if (gasRelease) gasRelease = false
            count++
        }

        // ── Jari Rem (hold-tap di satu titik) ───────────────────
        if (brkActive || brkRelease) {
            val path = Path().apply { moveTo(snapBrkX, snapBrkY) }
            nb = makeStroke(brkPrevStroke, path, willContinue = brkActive)
            builder.addStroke(nb)
            if (brkRelease) brkRelease = false
            count++
        }

        if (count == 0) { loopRunning = false; return }

        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (steerActive) { steerCurrentNorm = snapSteer; steerPrevStroke = ns }
                else               steerPrevStroke = null

                if (gasActive) gasPrevStroke = ng else gasPrevStroke = null
                if (brkActive) brkPrevStroke = nb else brkPrevStroke = null

                val stillActive = steerActive || gasActive || brkActive ||
                                  gasRelease  || brkRelease
                if (stillActive) doDispatch() else loopRunning = false
            }

            override fun onCancelled(g: GestureDescription?) {
                steerPrevStroke = null; gasPrevStroke = null; brkPrevStroke = null
                steerCurrentNorm = snapSteer
                if (steerActive || gasActive || brkActive) {
                    handler.postDelayed(::doDispatch, 16L)
                } else {
                    loopRunning = false
                }
            }
        }, null)
    }

    // ─────────────────────────────────────────────────────────────
    // STEERING ARC PATH (ring mode)
    // ─────────────────────────────────────────────────────────────

    /**
     * Busur setir dari fromNorm ke toNorm pada lingkaran setir BUSSID.
     * touchX = centerX + R·cos(angle),  touchY = centerY + R·sin(angle)
     * angle = norm × (maxDeg/2) × sens − 90°  (−90° agar 0 = puncak ring)
     */
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
    // HELPER
    // ─────────────────────────────────────────────────────────────

    private fun makeStroke(
        prev: GestureDescription.StrokeDescription?,
        path: Path,
        willContinue: Boolean
    ): GestureDescription.StrokeDescription {
        if (prev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try { prev.continueStroke(path, 0L, STROKE_MS, willContinue) }
            catch (_: Exception) { GestureDescription.StrokeDescription(path, 0L, STROKE_MS, willContinue) }
        }
        return GestureDescription.StrokeDescription(path, 0L, STROKE_MS, willContinue)
    }

    // ─────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchAccessibilityService terhubung.")
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

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) { }

    override fun onInterrupt() {
        Log.w(TAG, "TouchAccessibilityService terputus.")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
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
    // PUBLIC: PAUSE — lepas semua touch (user bisa klik layar normal)
    // ─────────────────────────────────────────────────────────────

    fun releaseAllTouches() {
        handler.removeCallbacks(inputWatchdog)
        handler.removeCallbacks(::doDispatch)
        steerActive = false; gasActive = false; brkActive = false
        gasRelease  = false; brkRelease = false
        steerPrevStroke = null; gasPrevStroke = null; brkPrevStroke = null
        steerCurrentNorm = 0f
        loopRunning = false
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: SETIR (ring arc mode)
    // ─────────────────────────────────────────────────────────────

    /**
     * Perbarui target setir.
     *
     * Setiap panggilan mereset watchdog → jika wheel diam >800ms,
     * auto-release mencegah layar terblokir.
     *
     * fromNorm DIABAIKAN — service melacak steerCurrentNorm sendiri.
     */
    fun injectCircularSteering(
        centerX: Float, centerY: Float, radius: Float,
        fromNorm: Float,   // kompatibilitas MappingManager — tidak dipakai
        toNorm: Float,
        maxDegrees: Float, sensitivity: Float,
        linearity: Float = 1.0f
    ) {
        if (MappingManager.isMappingPaused) return

        steerCX      = centerX
        steerCY      = centerY
        steerR       = radius
        steerMaxDeg  = maxDegrees
        steerSens    = sensitivity
        steerTargetNorm = toNorm
        steerActive  = true

        resetWatchdog()  // ← KUNCI: auto-release jika wheel berhenti bergerak
        ensureLoop()
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: PEDAL GAS & REM
    // ─────────────────────────────────────────────────────────────

    /**
     * Injeksi pedal sebagai HOLD TOUCH di satu titik (seperti keyboard W/S Python).
     * pressure > 0.12 → touchDown + hold
     * pressure ≤ 0.12 → touchUp
     */
    fun injectPedalAnalog(
        baseX: Float, baseY: Float,
        travelDistance: Float,
        pressure: Float,
        isBrake: Boolean = false
    ) {
        if (MappingManager.isMappingPaused) return

        if (isBrake) {
            brkX = baseX; brkY = baseY
            if (pressure > 0.12f) { brkActive = true }
            else if (brkActive)   { brkActive = false; brkRelease = true }
            else return
        } else {
            gasX = baseX; gasY = baseY
            if (pressure > 0.12f) { gasActive = true }
            else if (gasActive)   { gasActive = false; gasRelease = true }
            else return
        }
        ensureLoop()
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
