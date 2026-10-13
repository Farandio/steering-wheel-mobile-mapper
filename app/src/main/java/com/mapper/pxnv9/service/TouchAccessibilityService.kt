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
     * 1. Single Tap (Klakson, Lampu, Hazard, dll)
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
     * 2. Continuous / Hold Touch (Pedal Gas / Rem)
     */
    private var activeStrokes = mutableMapOf<String, GestureDescription.StrokeDescription>()

    fun injectContinuousTouch(x: Float, y: Float, isDown: Boolean, strokeId: String = "pedal") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y)
            }

            if (isDown) {
                val stroke = GestureDescription.StrokeDescription(path, 0, 1000L, true)
                activeStrokes[strokeId] = stroke
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            } else {
                val prevStroke = activeStrokes.remove(strokeId)
                if (prevStroke != null) {
                    val finishStroke = prevStroke.continueStroke(path, 0, 50L, false)
                    val gesture = GestureDescription.Builder().addStroke(finishStroke).build()
                    dispatchGesture(gesture, null, null)
                }
            }
        } else {
            if (isDown) {
                injectTap(x, y, 200L)
            }
        }
    }

    /**
     * 3. Setir Rotasi 900 Derajat (Arc / Circular Drag)
     * Pada BUSSID, kemudi virtual diputar dengan menyentuh tepi kemudi dan menggesernya secara melingkar (arc).
     *
     * @param centerX Pusat setir di layar
     * @param centerY Pusat setir di layar
     * @param radius Jari-jari lingkaran setir virtual
     * @param currentNormAngle Sudut fisik setir saat ini (-1.0f s/d 1.0f)
     * @param maxDegrees Derajat fisik setir (900f atau 270f)
     */
    fun injectCircularSteering(
        centerX: Float,
        centerY: Float,
        radius: Float,
        fromNorm: Float,
        toNorm: Float,
        maxDegrees: Float,
        sensitivity: Float
    ) {
        // Hitung sudut rotasi dalam radian (Setir berpusat di arah atas / 90 derajat atau 270)
        // Setir 900 derajat = 2.5 putaran penuh (450 derajat ke kiri, 450 derajat ke kanan)
        val maxRotRadian = (maxDegrees / 2f) * (PI.toFloat() / 180f) * sensitivity

        val startAngle = -PI.toFloat() / 2f + (fromNorm * maxRotRadian)
        val endAngle = -PI.toFloat() / 2f + (toNorm * maxRotRadian)

        val startX = centerX + radius * cos(startAngle)
        val startY = centerY + radius * sin(startAngle)

        val endX = centerX + radius * cos(endAngle)
        val endY = centerY + radius * sin(endAngle)

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 30L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 4. H-Shifter Node Dragging (Tengah -> Slot Gigi atau sebaliknya)
     */
    fun injectShifterSlide(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 50L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
