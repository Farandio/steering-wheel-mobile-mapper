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
        if (com.mapper.pxnv9.manager.MappingManager.useAdbZeroLatencyMode && ShellTouchInjector.isAdbConnected) {
            ShellTouchInjector.injectTap(x, y)
            return
        }

        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    // State tracking untuk continuous touch pedal gas & rem (agar hold klik tidak putus di BUSSID)
    private var isGasTouching: Boolean = false
    private var isBrakeTouching: Boolean = false
    private var gasStroke: GestureDescription.StrokeDescription? = null
    private var brakeStroke: GestureDescription.StrokeDescription? = null

    /**
     * 2. Analog Progressive Pedal (Pedal Gas / Rem Touch Hold & Drag):
     * Menggunakan Touch Hold (tekan dan tahan) pada tombol virtual BUSSID.
     * Menggunakan continueStroke agar virtual finger tetap menempel (Hold) di collider game.
     */
    fun injectPedalAnalog(baseX: Float, baseY: Float, travelDistance: Float, pressure: Float, isBrake: Boolean = false) {
        val isPressed = pressure > 0.12f

        if (isPressed) {
            val targetY = baseY - (travelDistance * pressure.coerceIn(0f, 1f))
            val path = Path().apply {
                moveTo(baseX, baseY)
                lineTo(baseX, targetY)
            }

            try {
                val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val prev = if (isBrake) brakeStroke else gasStroke
                    val isTouching = if (isBrake) isBrakeTouching else isGasTouching
                    if (isTouching && prev != null) {
                        prev.continueStroke(path, 0, 50L, true)
                    } else {
                        GestureDescription.StrokeDescription(path, 0, 50L, true)
                    }
                } else {
                    GestureDescription.StrokeDescription(path, 0, 50L)
                }

                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        if (isBrake) {
                            brakeStroke = stroke
                            isBrakeTouching = true
                        } else {
                            gasStroke = stroke
                            isGasTouching = true
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        if (isBrake) {
                            brakeStroke = null
                            isBrakeTouching = false
                        } else {
                            gasStroke = null
                            isGasTouching = false
                        }
                    }
                }, null)
            } catch (e: Exception) {
                if (isBrake) {
                    brakeStroke = null
                    isBrakeTouching = false
                } else {
                    gasStroke = null
                    isGasTouching = false
                }
            }
        } else {
            // Pedal dilepas: Lepaskan stroke hold (Touch Up)
            if (isBrake) {
                brakeStroke = null
                isBrakeTouching = false
            } else {
                gasStroke = null
                isGasTouching = false
            }
        }
    }

    // State pelacak stroke sentuhan kontinu (Continuous Drag) untuk setir kemudi
    private var currentSteeringStroke: GestureDescription.StrokeDescription? = null
    private var isSteeringTouching: Boolean = false

    /**
     * 3. Setir Rotasi 900 Derajat (Circular Continuous Touch & Drag):
     * Menggunakan rumus trigonometri yang 100% PERSIS dengan wheel_overlay_mapper.py di PC:
     * theta_rad = radians(angle_deg - 90)
     * screen_x = cx + int(r * cos(theta_rad))
     * screen_y = cy + int(r * sin(theta_rad))
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
        val isBackToCenter = kotlin.math.abs(toNorm) < 0.02f

        if (isBackToCenter) {
            currentSteeringStroke = null
            isSteeringTouching = false
            return
        }

        // Hitung sudut derajat persis seperti Python:
        val maxAngle = (maxDegrees / 2f) * sensitivity
        val fromAngleDeg = fromNorm * maxAngle
        val toAngleDeg = toNorm * maxAngle

        // Rumus posisi persis Python: theta_rad = radians(angle_deg - 90)
        val fromRad = Math.toRadians((fromAngleDeg - 90.0)).toFloat()
        val toRad = Math.toRadians((toAngleDeg - 90.0)).toFloat()

        val startX = centerX + (radius * cos(fromRad))
        val startY = centerY + (radius * sin(fromRad))
        val endX = centerX + (radius * cos(toRad))
        val endY = centerY + (radius * sin(toRad))

        val path = Path().apply {
            moveTo(startX, startY)
            val steps = 8
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                val curRad = fromRad + (toRad - fromRad) * t
                val px = centerX + (radius * cos(curRad))
                val py = centerY + (radius * sin(curRad))
                lineTo(px, py)
            }
        }

        try {
            val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val prev = currentSteeringStroke
                if (isSteeringTouching && prev != null) {
                    prev.continueStroke(path, 0, 45L, true)
                } else {
                    GestureDescription.StrokeDescription(path, 0, 45L, true)
                }
            } else {
                GestureDescription.StrokeDescription(path, 0, 45L)
            }

            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    currentSteeringStroke = stroke
                    isSteeringTouching = true
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    currentSteeringStroke = null
                    isSteeringTouching = false
                }
            }, null)
        } catch (e: Exception) {
            currentSteeringStroke = null
            isSteeringTouching = false
            val fallbackStroke = GestureDescription.StrokeDescription(path, 0, 40L)
            val fallbackGesture = GestureDescription.Builder().addStroke(fallbackStroke).build()
            dispatchGesture(fallbackGesture, null, null)
        }
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
     * Jalur multi-titik untuk perpindahan gigi presisi H-gate:
     * Menyusuri lorong H lalu menahan sentuhan di slot target (dwell time) agar Unity collider BUSSID mengunci gigi.
     * Durasi drag diperlambat sedikit (180ms - 220ms) agar bola shifter kuning BUSSID benar-benar ditarik
     * menyusuri lorong dan tidak dianggap glitch / sentuhan kilat oleh game.
     */
    fun injectMultiPointPath(points: List<Pair<Float, Float>>, durationMs: Long = 180L, holdAtEndMs: Long = 120L) {
        if (points.size < 2) return
        val path = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) {
                lineTo(points[i].first, points[i].second)
            }
            // Tahan di titik akhir (micro move di tempat) agar terdeteksi solid oleh sensor slot BUSSID
            val last = points.last()
            lineTo(last.first, last.second)
        }
        val totalDuration = durationMs + holdAtEndMs
        val stroke = GestureDescription.StrokeDescription(path, 0, totalDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
