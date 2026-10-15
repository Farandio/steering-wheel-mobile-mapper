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
 * FAKTA KRITIS Android dispatchGesture:
 *   Dua panggilan dispatchGesture() yang tumpang-tindih SALING CANCEL.
 *   → Setir + Pedal HARUS dalam SATU GestureDescription.
 *
 * ARSITEKTUR: SINGLE-DISPATCH CONTINUOUS LOOP
 * ═══════════════════════════════════════════════════════════════════
 *
 * Loop berjalan seperti ini (mirip Python poll_controller_loop):
 *
 *   doDispatch()                    ← bangun GestureDescription dari semua state
 *     ↓ dispatchGesture (16ms)
 *   onCompleted()                   ← segera panggil doDispatch() lagi (zero-gap)
 *     ↓
 *   doDispatch()                    ← baca state TERBARU (mungkin berubah)
 *     ↓ dispatchGesture (16ms)
 *   ...
 *
 * Hasilnya: ~62fps, mirip dengan Python poll 10ms + moveTo() langsung.
 *
 * STROKE DURATION 16ms (bukan 50ms):
 *   Python moveTo() = instan. Android dispatch punya minimum duration.
 *   16ms adalah nilai terkecil yang stabil → paling mendekati Python.
 *
 * PEDAL = HOLD TOUCH di satu titik (bukan drag):
 *   Python: gas = keyboard W hold (digital on/off, bukan analog drag).
 *   BUSSID mobile: gas button = hold touch → sama persis.
 *   Simpan (x,y) titik pedal → dispatch path: moveTo(x,y) saja.
 *   Tidak ada drag distance → tidak ada lompatan posisi.
 *
 * SETIR always-hold:
 *   Jari setir TIDAK PERNAH dilepas (even saat norm=0).
 *   Python: mouseDown() satu kali → moveTo() terus sampai lurus baru mouseUp().
 *   Android: willContinue=true selalu, sampai releaseAllTouches().
 * ═══════════════════════════════════════════════════════════════════
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG       = "TouchAccessibility"
        private const val STROKE_MS = 16L   // ~62fps, mendekati Python 10ms poll
        var instance: TouchAccessibilityService? = null
            private set
    }

    // ─────────────────────────────────────────────────────────────
    // STATE: SETIR
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
    private var gasRelease    = false   // kirim 1 dispatch terakhir willContinue=false
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

    /**
     * Mulai loop jika belum berjalan.
     * Loop berjalan sendiri via onCompleted → doDispatch → onCompleted → ...
     * Dipanggil kapan saja state berubah (setir/gas/rem diinjak).
     */
    private fun ensureLoop() {
        if (!loopRunning) {
            loopRunning = true
            doDispatch()
        }
        // Jika loop sudah berjalan: state terbaru akan dibaca di dispatch berikutnya (< 16ms)
    }

    /**
     * Satu siklus dispatch.
     * Bangun GestureDescription dari semua finger aktif lalu dispatch.
     * Di onCompleted, langsung panggil doDispatch() lagi → zero-gap.
     */
    private fun doDispatch() {
        // Snapshot semua state di awal (sensor bisa berubah kapan saja dari onMotionEvent)
        val snapSteer = steerTargetNorm
        val snapGasX  = gasX;  val snapGasY  = gasY
        val snapBrkX  = brkX;  val snapBrkY  = brkY

        val builder = GestureDescription.Builder()
        var count   = 0

        var ns: GestureDescription.StrokeDescription? = null
        var ng: GestureDescription.StrokeDescription? = null
        var nb: GestureDescription.StrokeDescription? = null

        // ── Jari Setir ──────────────────────────────────────────
        if (steerActive) {
            val path = buildSteerArc(steerCurrentNorm, snapSteer)
            ns = makeStroke(steerPrevStroke, path, willContinue = true)
            builder.addStroke(ns)
            count++
        }

        // ── Jari Gas ────────────────────────────────────────────
        // Pedal = hold touch di satu titik (seperti hold keyboard W di Python)
        if (gasActive || gasRelease) {
            val path = Path().apply { moveTo(snapGasX, snapGasY) }
            ng = makeStroke(gasPrevStroke, path, willContinue = gasActive)
            builder.addStroke(ng)
            if (gasRelease) gasRelease = false
            count++
        }

        // ── Jari Rem ────────────────────────────────────────────
        if (brkActive || brkRelease) {
            val path = Path().apply { moveTo(snapBrkX, snapBrkY) }
            nb = makeStroke(brkPrevStroke, path, willContinue = brkActive)
            builder.addStroke(nb)
            if (brkRelease) brkRelease = false
            count++
        }

        if (count == 0) {
            loopRunning = false
            return
        }

        val gesture = builder.build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // Update posisi actual setir ke snapshot yang baru saja di-dispatch
                if (steerActive) { steerCurrentNorm = snapSteer; steerPrevStroke = ns }
                else               steerPrevStroke = null

                // Update prev-stroke pedal
                if (gasActive) gasPrevStroke = ng  else gasPrevStroke = null
                if (brkActive) brkPrevStroke = nb  else brkPrevStroke = null

                // Cek apakah masih ada yang aktif
                val stillActive = steerActive || gasActive || brkActive ||
                                  gasRelease || brkRelease
                if (stillActive) {
                    // Zero-gap: langsung dispatch berikutnya tanpa delay
                    doDispatch()
                } else {
                    loopRunning = false
                }
            }

            override fun onCancelled(g: GestureDescription?) {
                // Reset semua prev-stroke → gesture berikutnya mulai touch-down baru
                steerPrevStroke = null; gasPrevStroke = null; brkPrevStroke = null
                steerCurrentNorm = snapSteer

                val stillActive = steerActive || gasActive || brkActive
                if (stillActive) {
                    // Retry setelah 1 frame
                    handler.postDelayed(::doDispatch, 16L)
                } else {
                    loopRunning = false
                }
            }
        }, null)
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    /** Lanjutkan stroke sebelumnya atau buat stroke baru jika belum ada. */
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

    /** Busur setir dari fromNorm ke toNorm (8 titik interpolasi untuk kelancaran). */
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
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchAccessibilityService terhubung.")

        // Daftarkan SOURCE_JOYSTICK|SOURCE_GAMEPAD agar onMotionEvent()
        // menerima axis setir & pedal OTG.
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
    // PUBLIC: SETIR
    // ─────────────────────────────────────────────────────────────

    /**
     * Perbarui target setir.
     * Dipanggil setiap onMotionEvent() → state di-update, loop membaca di siklus berikutnya.
     * fromNorm DIABAIKAN — service melacak steerCurrentNorm sendiri.
     */
    fun injectCircularSteering(
        centerX: Float, centerY: Float, radius: Float,
        fromNorm: Float,   // dipertahankan untuk kompatibilitas MappingManager
        toNorm: Float,
        maxDegrees: Float, sensitivity: Float,
        linearity: Float = 1.0f
    ) {
        steerCX = centerX; steerCY = centerY; steerR = radius
        steerMaxDeg = maxDegrees; steerSens = sensitivity
        steerTargetNorm = toNorm
        steerActive = true
        ensureLoop()
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: PEDAL GAS & REM
    // ─────────────────────────────────────────────────────────────

    /**
     * Injeksi pedal sebagai HOLD TOUCH di satu titik.
     * Persis seperti Python: keyboard W/S hold (digital on/off, bukan analog drag).
     *
     * pressure > 0.12 → tekan (hold touch di titik pedal)
     * pressure ≤ 0.12 → lepas (willContinue=false → BUSSID angkat jari)
     *
     * travelDistance DIABAIKAN — tidak perlu drag, cukup hold di baseX,baseY.
     */
    fun injectPedalAnalog(
        baseX: Float, baseY: Float,
        travelDistance: Float,
        pressure: Float,
        isBrake: Boolean = false
    ) {
        if (isBrake) {
            brkX = baseX; brkY = baseY
            if (pressure > 0.12f) {
                brkActive = true
            } else if (brkActive) {
                brkActive  = false
                brkRelease = true
            } else return
        } else {
            gasX = baseX; gasY = baseY
            if (pressure > 0.12f) {
                gasActive = true
            } else if (gasActive) {
                gasActive  = false
                gasRelease = true
            } else return
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

    /**
     * Perpindahan gigi multi-titik (H-gate BUSSID).
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
