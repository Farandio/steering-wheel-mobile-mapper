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
 * ARSITEKTUR MULTI-FINGER  (Setir + Gas + Rem bersamaan)
 * ═══════════════════════════════════════════════════════════════════
 *
 * KUNCI 1 — Simpan TARGET, bukan Path
 *   injectCircularSteering / injectPedalAnalog hanya menyimpan
 *   nilai target (steerTargetNorm, gasTargetPressure, …).
 *   Path dihitung ON-DEMAND di doDispatch() dari posisi TERAKHIR
 *   yang benar-benar sudah di-dispatch.
 *
 *   Kenapa ini penting?
 *   Jika dispatch sedang berjalan (inFlight) dan sensor terus
 *   memperbarui target, Path lama punya moveTo di posisi lama
 *   tapi continueStroke berakhir di posisi baru → LOMPAT.
 *   Dengan menghitung path saat dispatch, posisi selalu konsisten.
 *
 * KUNCI 2 — Snapshot di doDispatch(), update di onCompleted()
 *   Saat doDispatch() dimulai, snapshot semua target:
 *     snapSteer = steerTargetNorm
 *   Path dihitung dari currentNorm → snapSteer.
 *   Di onCompleted(), update currentNorm = snapSteer.
 *   Jika ada update baru di antara: dirtyWhileBusy=true →
 *   doDispatch() dipanggil ulang dengan target yang LEBIH BARU,
 *   dan currentNorm sudah di-update sehingga path TETAP KONSISTEN.
 *
 * KUNCI 3 — Handler.post batching
 *   Semua panggilan scheduleDispatch() dalam satu onMotionEvent()
 *   (setir, gas, rem) di-deduplicate oleh removeCallbacks+post
 *   sehingga doDispatch() hanya dipanggil SATU KALI dengan semua
 *   finger states ter-update → satu GestureDescription multi-finger.
 *
 * KUNCI 4 — Setir always-hold
 *   Jari setir tidak pernah dilepas berdasarkan sudut.
 *   Saat lurus (norm=0), jari hold di 12 o'clock pada ring.
 *   Hanya releaseAllTouches() (tombol PAUSE) yang melepas.
 * ═══════════════════════════════════════════════════════════════════
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchAccessibility"
        var instance: TouchAccessibilityService? = null
            private set
    }

    // ─────────────────────────────────────────────────────────────
    // STATE PER-FINGER
    // prevStroke : referensi StrokeDescription terakhir untuk continueStroke
    // isActive   : apakah jari sedang menyentuh layar
    // releaseOnce: jari baru dilepas → kirim 1 dispatch final willContinue=false
    // ─────────────────────────────────────────────────────────────

    private data class Finger(
        var prevStroke: GestureDescription.StrokeDescription? = null,
        var isActive: Boolean = false,
        var releaseOnce: Boolean = false
    )

    private val sf = Finger()  // steering finger
    private val gf = Finger()  // gas finger
    private val bf = Finger()  // brake finger

    // ─── Steering params ──────────────────────────────────────────
    // currentNorm: posisi touch yang BENAR-BENAR sudah di-dispatch
    // targetNorm : nilai terbaru dari sensor (bisa berubah saat inFlight)
    private var steerCX      = 300f
    private var steerCY      = 856f
    private var steerR       = 168f
    private var steerMaxDeg  = 900f
    private var steerSens    = 1.0f
    private var steerCurrentNorm = 0f   // last dispatched position
    private var steerTargetNorm  = 0f   // latest sensor value

    // ─── Gas pedal params ─────────────────────────────────────────
    private var gasBX      = 1700f
    private var gasBY      = 900f
    private var gasTrav    = 140f
    private var gasCurrent = 0f   // last dispatched pressure
    private var gasTarget  = 0f   // latest sensor pressure

    // ─── Brake pedal params ───────────────────────────────────────
    private var brkBX      = 1500f
    private var brkBY      = 900f
    private var brkTrav    = 140f
    private var brkCurrent = 0f
    private var brkTarget  = 0f

    // ─────────────────────────────────────────────────────────────
    // DISPATCH ENGINE
    // ─────────────────────────────────────────────────────────────

    private val handler        = Handler(Looper.getMainLooper())
    private var inFlight       = false
    private var dirtyWhileBusy = false

    /**
     * Jadwalkan satu dispatch cycle.
     *
     * removeCallbacks + post memastikan hanya ADA SATU pending dispatch
     * pada suatu waktu. Tiga panggilan berurutan dalam satu onMotionEvent
     * (setir, gas, rem) menghasilkan tepat SATU doDispatch() di akhir.
     */
    private fun scheduleDispatch() {
        if (inFlight) {
            dirtyWhileBusy = true
            return
        }
        handler.removeCallbacks(::doDispatch)
        handler.post(::doDispatch)
    }

    /**
     * Dispatch SATU GestureDescription berisi semua jari aktif.
     *
     * Path dihitung di sini (bukan disimpan saat update) agar selalu
     * konsisten dengan posisi yang benar-benar sudah di-dispatch.
     */
    private fun doDispatch() {
        if (inFlight) { dirtyWhileBusy = true; return }

        // ── Snapshot semua target sebelum mulai build ───────────
        // Target bisa berubah kapan saja dari onMotionEvent.
        // Snapshot memastikan onCompleted update ke nilai yang TEPAT
        // sesuai apa yang baru saja di-dispatch, bukan nilai yang lebih baru.
        val snapSteer = steerTargetNorm
        val snapGas   = gasTarget
        val snapBrk   = brkTarget

        val builder = GestureDescription.Builder()
        var count   = 0

        var ns: GestureDescription.StrokeDescription? = null
        var ng: GestureDescription.StrokeDescription? = null
        var nb: GestureDescription.StrokeDescription? = null

        // ── Jari Setir ──────────────────────────────────────────
        if (sf.isActive) {
            // Path dari posisi TERAKHIR DI-DISPATCH (currentNorm) ke TARGET terbaru (snapSteer)
            // → ini yang menjamin continueStroke tidak lompat posisi
            val path = buildSteerArc(steerCurrentNorm, snapSteer)
            ns = continued(sf.prevStroke, path, willContinue = true)
            builder.addStroke(ns)
            count++
        }

        // ── Jari Gas ────────────────────────────────────────────
        if (gf.isActive || gf.releaseOnce) {
            val fromY = gasBY - (gasTrav * gasCurrent.coerceIn(0f, 1f))
            val toY   = if (gf.isActive) gasBY - (gasTrav * snapGas.coerceIn(0f, 1f)) else gasBY
            val path  = Path().apply { moveTo(gasBX, fromY); lineTo(gasBX, toY) }
            ng = continued(gf.prevStroke, path, willContinue = gf.isActive)
            builder.addStroke(ng)
            if (gf.releaseOnce) gf.releaseOnce = false
            count++
        }

        // ── Jari Rem ────────────────────────────────────────────
        if (bf.isActive || bf.releaseOnce) {
            val fromY = brkBY - (brkTrav * brkCurrent.coerceIn(0f, 1f))
            val toY   = if (bf.isActive) brkBY - (brkTrav * snapBrk.coerceIn(0f, 1f)) else brkBY
            val path  = Path().apply { moveTo(brkBX, fromY); lineTo(brkBX, toY) }
            nb = continued(bf.prevStroke, path, willContinue = bf.isActive)
            builder.addStroke(nb)
            if (bf.releaseOnce) bf.releaseOnce = false
            count++
        }

        if (count == 0) return

        inFlight = true
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // Update posisi ACTUAL ke snapshot yang baru saja di-dispatch
                if (sf.isActive) { steerCurrentNorm = snapSteer; sf.prevStroke = ns }
                else               sf.prevStroke = null

                if (gf.isActive) { gasCurrent = snapGas; gf.prevStroke = ng }
                else             { gasCurrent = 0f;      gf.prevStroke = null }

                if (bf.isActive) { brkCurrent = snapBrk; bf.prevStroke = nb }
                else             { brkCurrent = 0f;      bf.prevStroke = null }

                inFlight = false
                if (dirtyWhileBusy) {
                    dirtyWhileBusy = false
                    doDispatch()   // dispatch segera dengan target yang LEBIH BARU
                }
            }
            override fun onCancelled(g: GestureDescription?) {
                // Reset semua prev-stroke → stroke berikutnya mulai touch-down baru
                sf.prevStroke = null; gf.prevStroke = null; bf.prevStroke = null
                // Update currentPos ke snapshot sebagai best-estimate
                steerCurrentNorm = snapSteer
                gasCurrent       = if (gf.isActive) snapGas else 0f
                brkCurrent       = if (bf.isActive) snapBrk else 0f
                inFlight = false
            }
        }, null)
    }

    /** Hitung busur setir dari fromNorm ke toNorm (6 titik interpolasi). */
    private fun buildSteerArc(fromNorm: Float, toNorm: Float): Path {
        val maxAngle = (steerMaxDeg / 2f) * steerSens
        val fromRad  = Math.toRadians(((fromNorm * maxAngle) - 90.0)).toFloat()
        val toRad    = Math.toRadians(((toNorm   * maxAngle) - 90.0)).toFloat()
        return Path().apply {
            moveTo(steerCX + steerR * cos(fromRad), steerCY + steerR * sin(fromRad))
            repeat(6) { i ->
                val t   = (i + 1) / 6f
                val rad = fromRad + (toRad - fromRad) * t
                lineTo(steerCX + steerR * cos(rad), steerCY + steerR * sin(rad))
            }
        }
    }

    /**
     * Lanjutkan stroke sebelumnya (drag halus, tanpa angkat jari)
     * atau mulai stroke baru (touch-down) jika belum ada riwayat.
     */
    private fun continued(
        prev: GestureDescription.StrokeDescription?,
        path: Path,
        willContinue: Boolean = true,
        ms: Long = 50L
    ): GestureDescription.StrokeDescription {
        if (prev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try { prev.continueStroke(path, 0L, ms, willContinue) }
            catch (_: Exception) { GestureDescription.StrokeDescription(path, 0L, ms, willContinue) }
        }
        return GestureDescription.StrokeDescription(path, 0L, ms, willContinue)
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
    // PUBLIC: PAUSE — lepas semua jari
    // ─────────────────────────────────────────────────────────────

    fun releaseAllTouches() {
        handler.removeCallbacksAndMessages(null)
        sf.apply { isActive=false; releaseOnce=false; prevStroke=null }
        gf.apply { isActive=false; releaseOnce=false; prevStroke=null }
        bf.apply { isActive=false; releaseOnce=false; prevStroke=null }
        steerCurrentNorm=0f; gasCurrent=0f; brkCurrent=0f
        inFlight=false; dirtyWhileBusy=false
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: SETIR
    // ─────────────────────────────────────────────────────────────

    /**
     * Perbarui target setir.
     * fromNorm DIABAIKAN — service menggunakan steerCurrentNorm sendiri
     * agar path selalu konsisten dengan posisi touch terakhir di layar.
     *
     * Jari setir TIDAK PERNAH dilepas berdasarkan sudut (bahkan 0°).
     * Saat lurus, jari hold di 12 o'clock (puncak ring setir BUSSID).
     */
    fun injectCircularSteering(
        centerX: Float, centerY: Float, radius: Float,
        fromNorm: Float,  // parameter dipertahankan untuk kompatibilitas MappingManager
        toNorm: Float,
        maxDegrees: Float, sensitivity: Float,
        linearity: Float = 1.0f
    ) {
        steerCX = centerX; steerCY = centerY; steerR = radius
        steerMaxDeg = maxDegrees; steerSens = sensitivity
        steerTargetNorm = toNorm   // simpan target, path dihitung saat dispatch
        sf.isActive = true
        scheduleDispatch()
    }

    // ─────────────────────────────────────────────────────────────
    // PUBLIC: PEDAL GAS & REM
    // ─────────────────────────────────────────────────────────────

    /**
     * Perbarui target pedal.
     * Path dari posisi TERAKHIR DI-DISPATCH ke target baru
     * dihitung on-demand di doDispatch() → tidak ada lompatan posisi.
     */
    fun injectPedalAnalog(
        baseX: Float, baseY: Float,
        travelDistance: Float,
        pressure: Float,
        isBrake: Boolean = false
    ) {
        val finger: Finger
        if (isBrake) {
            brkBX=baseX; brkBY=baseY; brkTrav=travelDistance
            finger = bf
            if (pressure > 0.12f) { brkTarget=pressure; finger.isActive=true }
            else if (finger.isActive) { finger.isActive=false; finger.releaseOnce=true; brkTarget=0f }
            else return
        } else {
            gasBX=baseX; gasBY=baseY; gasTrav=travelDistance
            finger = gf
            if (pressure > 0.12f) { gasTarget=pressure; finger.isActive=true }
            else if (finger.isActive) { finger.isActive=false; finger.releaseOnce=true; gasTarget=0f }
            else return
        }
        scheduleDispatch()
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
