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
 * ═══════════════════════════════════════════════════════════════
 * ARSITEKTUR MULTI-FINGER (Setir + Gas + Rem bersamaan)
 * ═══════════════════════════════════════════════════════════════
 *
 * Setiap kontrol (setir, gas, rem) diwakili oleh FingerState.
 * Semua jari yang aktif dikemas ke dalam SATU GestureDescription
 * per dispatch cycle — ini yang memungkinkan setir & pedal aktif
 * secara simultan tanpa saling membatalkan.
 *
 * Diagram alur:
 *   onMotionEvent
 *     → processMotionEvent (update semua FingerState)
 *       → injectCircularSteering → steeringFinger update
 *       → injectPedalAnalog (gas) → gasFinger update
 *       → injectPedalAnalog (rem) → brakeFinger update
 *       → scheduleDispatch() dipanggil tiap update
 *           └─ Handler.post(dispatchRunnable) ─ DEDUPLICATION ─┐
 *                                                               ↓
 *                                                         doDispatch()
 *                                                   [steeringS + gasS + brakeS]
 *                                                         → 1 GestureDescription
 *                                                         → dispatchGesture()
 *
 * Kunci: Handler.removeCallbacks + post memastikan doDispatch()
 * hanya dipanggil SEKALI setelah semua FingerState diperbarui,
 * tidak tiga kali terpisah yang akan saling membatalkan.
 *
 * ═══════════════════════════════════════════════════════════════
 * ATURAN PENTING SETIR:
 * Jari setir TIDAK PERNAH dilepas berdasarkan sudut 0°.
 * Saat setir lurus (0°), jari tetap menempel di posisi 12 o'clock
 * pada ring setir. Touch hanya dilepas saat PAUSE aktif.
 * ═══════════════════════════════════════════════════════════════
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchAccessibility"
        var instance: TouchAccessibilityService? = null
            private set
    }

    // ─────────────────────────────────────────────────────────
    // STATE MULTI-FINGER
    // ─────────────────────────────────────────────────────────

    /**
     * State satu jari virtual.
     *
     * prevStroke  : referensi stroke terakhir yang di-dispatch.
     *               Dipakai oleh continueStroke agar OS melihat
     *               gerakan sebagai drag halus, bukan tap berulang.
     * pendingPath : jalur terbaru yang akan di-dispatch berikutnya.
     * isActive    : apakah jari ini sedang menahan sentuhan.
     * releaseOnce : jari baru saja dilepas, kirim satu cycle release
     *               terakhir (willContinue=false) agar touch-up bersih.
     */
    private data class FingerState(
        var prevStroke: GestureDescription.StrokeDescription? = null,
        var pendingPath: Path? = null,
        var isActive: Boolean = false,
        var releaseOnce: Boolean = false
    )

    private val steeringFinger = FingerState()
    private val gasFinger      = FingerState()
    private val brakeFinger    = FingerState()

    // ─────────────────────────────────────────────────────────
    // DISPATCHER — Handler-based batching
    // ─────────────────────────────────────────────────────────

    private val handler        = Handler(Looper.getMainLooper())
    private var inFlight       = false
    private var dirtyWhileBusy = false

    /**
     * Jadwalkan satu dispatch cycle.
     *
     * Setiap call menghapus Runnable sebelumnya dan mempost yang baru.
     * Dengan demikian, tiga call berurutan (setir, gas, rem) dalam satu
     * onMotionEvent hanya menghasilkan SATU doDispatch() di akhir.
     */
    private fun scheduleDispatch() {
        if (inFlight) {
            // Ada gesture yang sedang berjalan → tandai dirty,
            // akan di-dispatch ulang saat onCompleted dipanggil.
            dirtyWhileBusy = true
            return
        }
        // removeCallbacks + post = deduplication dalam satu frame
        handler.removeCallbacks(::doDispatch)
        handler.post(::doDispatch)
    }

    /**
     * Bangun dan dispatch SATU GestureDescription berisi semua jari aktif.
     *
     * Menggunakan continueStroke agar tiap jari terasa seperti drag
     * berkelanjutan — bukan tap-tap terpisah yang terlihat patah-patah.
     */
    private fun doDispatch() {
        val builder = GestureDescription.Builder()
        var count   = 0

        // Variabel lokal untuk menyimpan stroke baru (dipakai di onCompleted)
        var ns: GestureDescription.StrokeDescription? = null
        var ng: GestureDescription.StrokeDescription? = null
        var nb: GestureDescription.StrokeDescription? = null

        fun addFinger(
            finger: FingerState,
            save: (GestureDescription.StrokeDescription) -> Unit
        ) {
            val path = finger.pendingPath ?: return
            val shouldRelease = finger.releaseOnce && !finger.isActive
            if (!finger.isActive && !shouldRelease) return

            val willContinue = finger.isActive // false → touch-up bersih
            val stroke = makeContinuedStroke(finger.prevStroke, path, willContinue)
            builder.addStroke(stroke)
            save(stroke)
            if (shouldRelease) finger.releaseOnce = false
            count++
        }

        addFinger(steeringFinger) { ns = it }
        addFinger(gasFinger)      { ng = it }
        addFinger(brakeFinger)    { nb = it }

        if (count == 0) return

        inFlight = true
        dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                // Simpan referensi stroke untuk continueStroke berikutnya
                steeringFinger.prevStroke = if (steeringFinger.isActive) ns else null
                gasFinger.prevStroke      = if (gasFinger.isActive)      ng else null
                brakeFinger.prevStroke    = if (brakeFinger.isActive)    nb else null
                inFlight = false
                if (dirtyWhileBusy) {
                    dirtyWhileBusy = false
                    doDispatch() // dispatch langsung — sudah pasti semua state ter-update
                }
            }
            override fun onCancelled(g: GestureDescription?) {
                // Reset semua prev-stroke, stroke berikutnya mulai baru (touch-down)
                steeringFinger.prevStroke = null
                gasFinger.prevStroke      = null
                brakeFinger.prevStroke    = null
                inFlight = false
            }
        }, null)
    }

    /**
     * Buat StrokeDescription yang melanjutkan stroke sebelumnya (continueStroke)
     * atau stroke baru (touch-down) jika belum ada riwayat.
     *
     * continueStroke menjamin OS tidak mengirim ACTION_UP + ACTION_DOWN
     * di antara dua stroke — hasilnya drag terasa mulus di BUSSID.
     */
    private fun makeContinuedStroke(
        prev: GestureDescription.StrokeDescription?,
        path: Path,
        willContinue: Boolean = true,
        durationMs: Long = 50L
    ): GestureDescription.StrokeDescription {
        if (prev != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return try {
                prev.continueStroke(path, 0L, durationMs, willContinue)
            } catch (_: Exception) {
                // continueStroke gagal (gesture dibatalkan OS) → mulai touch baru
                GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
            }
        }
        return GestureDescription.StrokeDescription(path, 0L, durationMs, willContinue)
    }

    // ─────────────────────────────────────────────────────────
    // ACCESSIBILITY SERVICE LIFECYCLE
    // ─────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchAccessibilityService terhubung.")

        // Daftarkan SOURCE_JOYSTICK | SOURCE_GAMEPAD agar onMotionEvent()
        // menerima axis dari setir & pedal OTG.
        // API 34+: field motionEventSources ada di public SDK.
        // API 26-33: field ada di runtime tapi belum public → pakai refleksi.
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
        // Jangan proses UI tree BUSSID — mencegah game freeze / Unity deadlock
    }

    override fun onInterrupt() {
        Log.w(TAG, "TouchAccessibilityService terputus.")
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        instance = null
    }

    /**
     * Terima axis events (setir, gas, rem, kopling) langsung dari OTG device.
     * Public API sejak API 26 — tidak butuh overlay window interceptor.
     */
    override fun onMotionEvent(event: MotionEvent) {
        MappingManager.processMotionEvent(event, this)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val handled = MappingManager.processKeyEvent(event)
        return if (handled) true else super.onKeyEvent(event)
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC: LEPASKAN SEMUA SENTUHAN (PAUSE)
    // ─────────────────────────────────────────────────────────

    /**
     * Hentikan semua jari virtual sekarang juga.
     * Dipanggil saat mapping di-PAUSE agar user bebas menyentuh layar.
     */
    fun releaseAllTouches() {
        handler.removeCallbacksAndMessages(null)
        steeringFinger.apply { isActive = false; pendingPath = null; prevStroke = null; releaseOnce = false }
        gasFinger.apply      { isActive = false; pendingPath = null; prevStroke = null; releaseOnce = false }
        brakeFinger.apply    { isActive = false; pendingPath = null; prevStroke = null; releaseOnce = false }
        inFlight       = false
        dirtyWhileBusy = false
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC: SETIR ROTASI LINGKARAN
    // ─────────────────────────────────────────────────────────

    /**
     * Gerakkan jari setir sepanjang busur lingkaran.
     *
     * PENTING: Jari setir TIDAK PERNAH dilepas berdasarkan sudut.
     * Saat setir lurus (toNorm ≈ 0), jari tetap hold di posisi
     * 12 o'clock (puncak lingkaran). Ini persis seperti jari sungguhan
     * yang menempel pada ring setir layar touch BUSSID sepanjang waktu.
     *
     * Rumus koordinat identik dengan wheel_overlay_mapper.py:
     *   theta = radians(angle_deg - 90)
     *   x = cx + r * cos(theta)
     *   y = cy + r * sin(theta)
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
        val maxAngle = (maxDegrees / 2f) * sensitivity

        // Hitung posisi sudut — toNorm=0 → sudut=0 → posisi 12 o'clock (atas lingkaran)
        val fromAngleDeg = fromNorm * maxAngle
        val toAngleDeg   = toNorm   * maxAngle

        val fromRad = Math.toRadians((fromAngleDeg - 90.0)).toFloat()
        val toRad   = Math.toRadians((toAngleDeg   - 90.0)).toFloat()

        // Busur interpolasi 6 titik untuk gerak halus di ring setir BUSSID
        val path = Path().apply {
            moveTo(
                centerX + radius * cos(fromRad),
                centerY + radius * sin(fromRad)
            )
            val steps = 6
            for (i in 1..steps) {
                val t   = i / steps.toFloat()
                val rad = fromRad + (toRad - fromRad) * t
                lineTo(
                    centerX + radius * cos(rad),
                    centerY + radius * sin(rad)
                )
            }
        }

        // Setir SELALU aktif (isActive=true) — jari tidak pernah diangkat
        // kecuali releaseAllTouches() dipanggil (saat PAUSE)
        steeringFinger.pendingPath = path
        steeringFinger.isActive    = true
        scheduleDispatch()
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC: PEDAL GAS & REM (Analog Slider Vertikal)
    // ─────────────────────────────────────────────────────────

    /**
     * Gerakkan jari pedal secara vertikal sesuai tekanan.
     *   pressure = 0.0 → jari diangkat (touch-up)
     *   pressure = 1.0 → jari di puncak travel (pedal penuh)
     *
     * Gas dan rem bisa aktif bersamaan dengan setir karena semua
     * masuk dalam SATU GestureDescription via scheduleDispatch().
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
            if (finger.isActive) {
                // Kirim satu cycle release terakhir (willContinue=false) agar
                // touch-up terasa bersih di BUSSID — tidak perlu tunggu 50ms expired
                finger.isActive     = false
                finger.releaseOnce  = true
                scheduleDispatch()
            }
            return
        }

        val targetY = baseY - (travelDistance * pressure.coerceIn(0f, 1f))
        val path = Path().apply {
            moveTo(baseX, baseY)
            lineTo(baseX, targetY)
        }

        finger.pendingPath = path
        finger.isActive    = true
        scheduleDispatch()
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC: TAP TUNGGAL (Klakson, Lampu, Hazard, dll)
    // ─────────────────────────────────────────────────────────

    /**
     * Tap singkat — tidak pakai multi-finger state karena hanya sekali.
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = 40L) {
        if (MappingManager.useAdbZeroLatencyMode && ShellTouchInjector.isAdbConnected) {
            ShellTouchInjector.injectTap(x, y)
            return
        }
        val path    = Path().apply { moveTo(x, y) }
        val stroke  = GestureDescription.StrokeDescription(path, 0L, durationMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC: MACRO TAPS (Telolet / Basuri)
    // ─────────────────────────────────────────────────────────

    fun injectMacroTaps(points: List<com.mapper.pxnv9.model.PointF>, delayBetweenMs: Long = 100L) {
        Thread {
            for (pt in points) {
                injectTap(pt.x, pt.y, 40L)
                try { Thread.sleep(delayBetweenMs) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    // ─────────────────────────────────────────────────────────
    // PUBLIC: H-SHIFTER PATH (Perpindahan Gigi)
    // ─────────────────────────────────────────────────────────

    fun injectShifterPath(
        startX: Float, startY: Float,
        midX: Float,   midY: Float,
        endX: Float,   endY: Float,
        durationMs: Long = 70L
    ) {
        val path = Path().apply {
            moveTo(startX, startY); lineTo(midX, midY); lineTo(endX, endY)
        }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(), null, null
        )
    }

    fun injectShifterSlide(
        startX: Float, startY: Float,
        endX: Float,   endY: Float,
        durationMs: Long = 50L
    ) {
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build(), null, null
        )
    }

    /**
     * Perpindahan gigi multi-titik — menyusuri lorong H-gate BUSSID.
     * Durasi lambat (180ms + hold 120ms) agar bola shifter benar-benar
     * mengikuti lintasan dan tidak dianggap glitch oleh Unity collider.
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
            lineTo(points.last().first, points.last().second) // tahan di titik akhir
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs + holdAtEndMs)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), null, null
        )
    }
}
