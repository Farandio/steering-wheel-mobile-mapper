package com.mapper.pxnv9.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Service utama untuk menginjeksi event sentuhan (Touch Injection) menggunakan
 * AccessibilityService.dispatchGesture API tanpa perlu Root.
 */
class TouchAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TouchAccessibility"
        var instance: TouchAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "TouchAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Jangan intercept atau proses UI tree BUSSID agar game tidak stuck/freeze
    }

    override fun onInterrupt() {
        Log.w(TAG, "TouchAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val handled = com.mapper.pxnv9.manager.MappingManager.processKeyEvent(event)
        if (handled) {
            return true
        }
        return super.onKeyEvent(event)
    }

    // =========================================================================
    // GESTURE & TOUCH INJECTION
    // =========================================================================

    /**
     * 1. Single Tap (Klakson, Lampu, Hazard, Pintu, Wiper, dll)
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = 40L) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 2. Analog Progressive Pedal (Pedal Gas / Rem Progresif):
     * Menggeser sentuhan dari pangkal pedal ke atas/bawah sesuai persentase injakan (0.0f - 1.0f).
     * Jika dilepas (pressure <= 0.05f), sentuhan diangkat.
     */
    fun injectPedalAnalog(baseX: Float, baseY: Float, travelDistance: Float, pressure: Float, durationMs: Long = 30L) {
        val targetY = baseY - (travelDistance * pressure.coerceIn(0f, 1f))
        val path = Path().apply {
            moveTo(baseX, baseY)
            lineTo(baseX, targetY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 3. Setir Rotasi 900 Derajat (Arc / Circular Drag)
     * Titik sentuh berada pada lingkar luar setir virtual BUSSID, sehingga bagian tengah tetap bebas untuk klakson.
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
        val maxRotRadian = (maxDegrees / 2f) * (PI.toFloat() / 180f) * sensitivity

        // Terapkan kurva non-linearitas: sign(x) * |x|^linearity
        // Nilai linearity > 1.0 membuat respon di tengah lebih halus/tenang untuk kestabilan jalan lurus
        val curvedFrom = kotlin.math.sign(fromNorm) * kotlin.math.abs(fromNorm).toDouble().let { Math.pow(it, linearity.toDouble()).toFloat() }
        val curvedTo = kotlin.math.sign(toNorm) * kotlin.math.abs(toNorm).toDouble().let { Math.pow(it, linearity.toDouble()).toFloat() }

        val startAngle = -PI.toFloat() / 2f + (curvedFrom * maxRotRadian)
        val endAngle = -PI.toFloat() / 2f + (curvedTo * maxRotRadian)

        val startX = centerX + radius * cos(startAngle)
        val startY = centerY + radius * sin(startAngle)

        val endX = centerX + radius * cos(endAngle)
        val endY = centerY + radius * sin(endAngle)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 25L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Eksekusi Macro Tap (Klakson Telolet / Basuri)
     */
    fun injectMacroTaps(points: List<com.mapper.pxnv9.model.PointF>, delayBetweenMs: Long = 100L) {
        Thread {
            for (pt in points) {
                injectTap(pt.x, pt.y, 40L)
                try {
                    Thread.sleep(delayBetweenMs)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }.start()
    }

    /**
     * 4. H-Shifter BUSSID:
     * Pada BUSSID H-Shifter, dari titik netral / tengah ke gigi (1, 2, 3, 4, 5, 6, R)
     * memiliki lorong H (misal gigi 1 harus lewat atas lalu kiri).
     * Jika midWayPoint diberikan, gesture mengikuti lintasan siku (L-shape / H-gate path).
     */
    fun injectShifterPath(startX: Float, startY: Float, midX: Float, midY: Float, endX: Float, endY: Float, durationMs: Long = 70L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(midX, midY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun injectShifterSlide(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 50L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Jalur multi-titik untuk perpindahan gigi langsung (misal Gigi 1 -> Gigi 3, Gigi 2 -> Gigi 4):
     * Titik Asal -> Lorong Netral Asal -> Lorong Netral Target -> Titik Target
     */
    fun injectMultiPointPath(points: List<Pair<Float, Float>>, durationMs: Long = 80L) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
