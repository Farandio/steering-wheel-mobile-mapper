package com.mapper.pxnv9.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi

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
        Log.i(TAG, "TouchAccessibilityService berhasil terkoneksi & aktif.")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Tidak perlu memproses UI tree events aplikasi lain
    }

    override fun onInterrupt() {
        Log.w(TAG, "TouchAccessibilityService diinterupsi.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "TouchAccessibilityService dihentikan.")
    }

    /**
     * Intersepsi KeyEvent hardware jika service diizinkan menangkap filter key events
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Delegasikan pemrosesan ke MappingManager
        val handled = com.mapper.pxnv9.manager.MappingManager.processKeyEvent(event)
        if (handled) {
            return true
        }
        return super.onKeyEvent(event)
    }

    // =========================================================================
    // TOUCH INJECTION PRIMITIVES MENGGUNAKAN ACCESSIBILITY GESTURE
    // =========================================================================

    /**
     * 1. Single Tap pada koordinat (X, Y)
     */
    fun injectTap(x: Float, y: Float, durationMs: Long = 50L) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, null, null)
    }

    /**
     * 2. Continuous / Hold Touch (misal pedal gas/rem ditekan atau dilepas)
     * Pada Android 8.0+ (API 26), StrokeDescription mendukung parameter willContinue
     */
    private var activeStrokes = mutableMapOf<String, GestureDescription.StrokeDescription>()

    fun injectContinuousTouch(x: Float, y: Float, isDown: Boolean, strokeId: String = "pedal") {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x, y) // Hold in-place
            }

            if (isDown) {
                // Mulai gesture hold
                val stroke = GestureDescription.StrokeDescription(path, 0, 1000L, true)
                activeStrokes[strokeId] = stroke
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, null, null)
            } else {
                // Selesaikan/lepaskan gesture
                val prevStroke = activeStrokes.remove(strokeId)
                if (prevStroke != null) {
                    val finishStroke = prevStroke.continueStroke(path, 0, 50L, false)
                    val gesture = GestureDescription.Builder().addStroke(finishStroke).build()
                    dispatchGesture(gesture, null, null)
                }
            }
        } else {
            // Fallback untuk perangkat di bawah Android 8: simulasi tap berdurasi
            if (isDown) {
                injectTap(x, y, 300L)
            }
        }
    }

    /**
     * 3. Slider Rotasi / Drag untuk Setir Kemudi
     * Menggeser touch dari titik tengah ke arah kiri/kanan berdasarkan normalizedRotation (-1.0f .. 1.0f)
     */
    fun injectSteeringSlider(centerX: Float, centerY: Float, radius: Float, normalizedRotation: Float) {
        // Hitung perpindahan x target
        val targetX = centerX + (normalizedRotation * radius)
        val targetY = centerY

        val path = Path().apply {
            moveTo(centerX, centerY)
            lineTo(targetX, targetY)
        }

        // Jalankan swipe dengan durasi instan (misal 40ms) agar responsif
        val stroke = GestureDescription.StrokeDescription(path, 0, 40L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, null, null)
    }

    /**
     * 4. H-Shifter Node Dragging (dari tengah ke posisi slot gigi, atau sebaliknya)
     */
    fun injectShifterSlide(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 60L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        dispatchGesture(gesture, null, null)
    }
}
