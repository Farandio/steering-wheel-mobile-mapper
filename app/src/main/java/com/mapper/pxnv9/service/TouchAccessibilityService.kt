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

/**
 * Service injeksi sentuhan via AccessibilityService.dispatchGesture (tanpa Root).
 *
 * ═══════════════════════════════════════════════════════════════════
 * ARSITEKTUR: SINGLE-DISPATCH LOOP + TILT STEERING
 * ═══════════════════════════════════════════════════════════════════
 *
 * STEERING MODE: TILT (Horizontal Slider)
 * ──────────────────────────────────────────────────────────────────
 * Mirip logika tilt HP di BUSSID:
 *   • Jari ditempatkan di posisi HORIZONTAL proporsional terhadap sudut setir.
 *   • Putar kiri  → jari bergeser ke kiri  dari tengah slider
 *   • Putar kanan → jari bergeser ke kanan dari tengah slider
 *   • Lurus (0°)  → jari hold di tengah slider
 *   • Jari TIDAK PERNAH diangkat (willContinue=true selalu).
 *
 * Formula posisi X:
 *   touchX = steerCX + (norm × steerHalfWidth)
 *   steerHalfWidth = steerR   ← dipinjam untuk half-width slider
 *   norm ∈ [-1.0 … +1.0]     ← sudah diperhitungkan MappingManager
 *                                (termasuk deadzone + sensitivity)
 *
 * LOOP ENGINE:
 *   doDispatch() → dispatchGesture(16ms) → onCompleted → doDispatch()
 *   Zero-gap → ~62fps → setir + pedal selalu dalam 1 GestureDescription.
 *   (Multiple dispatchGesture() SALING CANCEL — harus 1 GestureDescription.)
 *
 * PEDAL:
 *   Hold touch di satu titik (seperti keyboard W/S di Python).
 *   Tidak ada drag — cukup touchDown + hold → touchUp saat dilepas.
 * ═══════════════════════════════════════════════════════════════════
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG       = "TouchAccessibility"
        private const val STROKE_MS = 16L   // ~62fps
        var instance: TouchAccessibilityService? = null
            private set
    }

    // ─────────────────────────────────────────────────────────────
    // STATE: SETIR (mode TILT / horizontal slider)
    //
    // steerCX      = titik tengah slider horizontal di layar BUSSID
    // steerCY      = Y posisi slider (baris slider di layar)
    // steerR       = half-width slider (px dari tengah ke tepi kiri/kanan)
    // steerMaxDeg  = derajat lock-to-lock setir fisik (misal 900°)
    // steerSens    = sensitivitas (1.0 = normal)
    //
    // steerCurrentNorm = norm posisi terakhir yg benar-benar di-dispatch
    // steerTargetNorm  = norm sensor terbaru (bisa berubah saat loop jalan)
    // ─────────────────────────────────────────────────────────────
    private var steerCX          = 540f   // default: tengah layar 1080p
    private var steerCY          = 900f   // default: area bawah layar BUSSID
    private var steerR           = 400f   // default: ±400px dari tengah
    private var steerMaxDeg      = 900f
    private var steerSens        = 1.0f
    private var steerCurrentNorm = 0f
    private var steerTargetNorm  = 0f
    private var steerActive      = false
    private var steerPrevStroke: GestureDescription.StrokeDescription? = null

    // ─────────────────────────────────────────────────────────────
    // STATE: PEDAL GAS & REM (hold-tap di satu titik)
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
    // DISPATCH ENGINE
    // ─────────────────────────────────────────────────────────────
    private val handler     = Handler(Looper.getMainLooper())
    private var loopRunning = false

    /** Mulai loop jika belum berjalan. */
    private fun ensureLoop() {
        if (!loopRunning) {
            loopRunning = true
            doDispatch()
        }
        // Loop sedang jalan → state terbaru dibaca di siklus berikutnya (< 16ms)
    }

    /**
     * Satu siklus dispatch: bangun GestureDescription dari semua finger aktif,
     * lalu segera panggil doDispatch() lagi dari onCompleted (zero-gap).
     */
    private fun doDispatch() {
        // ── Snapshot semua state ─────────────────────────────────
        val snapSteer = steerTargetNorm
        val snapGasX  = gasX;  val snapGasY  = gasY
        val snapBrkX  = brkX;  val snapBrkY  = brkY

        val builder = GestureDescription.Builder()
        var count   = 0
        var ns: GestureDescription.StrokeDescription? = null
        var ng: GestureDescription.StrokeDescription? = null
        var nb: GestureDescription.StrokeDescription? = null

        // ── Jari Setir (tilt / horizontal slider) ───────────────
        if (steerActive) {
            val path = buildTiltSlide(steerCurrentNorm, snapSteer)
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
                // Update posisi actual setir → snapshot yang baru saja di-dispatch
                if (steerActive) { steerCurrentNorm = snapSteer; steerPrevStroke = ns }
                else               steerPrevStroke = null

                if (gasActive) gasPrevStroke = ng  else gasPrevStroke = null
                if (brkActive) brkPrevStroke = nb  else brkPrevStroke = null

                val stillActive = steerActive || gasActive || brkActive ||
                                  gasRelease  || brkRelease
                if (stillActive) doDispatch() else loopRunning = false
            }

            override fun onCancelled(g: GestureDescription?) {
                // Reset prev-stroke → gesture berikutnya mulai touch-down baru
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
    // TILT SLIDER PATH
    // ─────────────────────────────────────────────────────────────

    /**
     * Hitung path horizontal dari posisi lama ke posisi baru.
     *
     *   touchX = steerCX + (norm × steerR)
     *   norm ∈ [-1.0 … +1.0]   (sudah diperhitungkan MappingManager)
     *   steerR = half-width slider (px)
     *
     * Contoh: steerCX=540, steerR=400, norm=+0.5 → touchX=740 (ke kanan)
     *         norm=-1.0 → touchX=140 (full kiri)
     *         norm=0.0  → touchX=540 (tengah / lurus)
     */
    private fun buildTiltSlide(fromNorm: Float, toNorm: Float): Path {
        val fromX = steerCX + (fromNorm * steerR)
        val toX   = steerCX + (toNorm   * steerR)
        return Path().apply {
            moveTo(fromX, steerCY)
            lineTo(toX,   steerCY)
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
    // PUBLIC: PAUSE
    // ─────────────────────────────────────────────────────────────

    fun releaseAllTouches() {
        handler.removeCallbacksAndMessages(null)
        steerActive = false; gasActive = false; brkActive = false
        gasRelease  = false; brkRelease = false
        steerPrevStroke = null; gasPrevStroke = null; brkPrevStroke = null
        steerCurrentNorm = 0f
        loopRunning = false
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: SETIR (TILT MODE)
    // ─────────────────────────────────────────────────────────────

    /**
     * Perbarui target setir.
     *
     * [centerX]  = titik X TENGAH slider tilt di layar BUSSID
     * [centerY]  = titik Y slider
     * [radius]   = HALF-WIDTH slider (px). Finger bergerak ±radius dari center.
     *              Sesuaikan agar tidak melewati batas layar:
     *              centerX - radius ≥ 0  dan  centerX + radius ≤ lebar layar
     * [toNorm]   = -1.0 (full kiri) .. 0.0 (lurus) .. +1.0 (full kanan)
     *              (sudah diperhitungkan deadzone & sensitivity oleh MappingManager)
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
        steerCX      = centerX
        steerCY      = centerY
        steerR       = radius       // dipakai sebagai half-width slider
        steerMaxDeg  = maxDegrees
        steerSens    = sensitivity
        steerTargetNorm = toNorm
        steerActive  = true
        ensureLoop()
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: PEDAL GAS & REM
    // ─────────────────────────────────────────────────────────────

    /**
     * Injeksi pedal sebagai HOLD TOUCH di satu titik (seperti keyboard W/S Python).
     * pressure > 0.12 → tekan (touchDown + hold)
     * pressure ≤ 0.12 → lepas (touchUp)
     * travelDistance diabaikan — tidak perlu drag.
     */
    fun injectPedalAnalog(
        baseX: Float, baseY: Float,
        travelDistance: Float,
        pressure: Float,
        isBrake: Boolean = false
    ) {
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
