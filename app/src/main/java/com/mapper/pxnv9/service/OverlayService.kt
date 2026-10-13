package com.mapper.pxnv9.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.mapper.pxnv9.manager.MappingManager

/**
 * OverlayService bertugas:
 * 1. Menampilkan Floating Indicator / Button saat bermain game BUSSID.
 * 2. Menyediakan container overlay transparan yang menerima dan menangkap hardware
 *    MotionEvent (Axis setir & pedal) secara global saat game sedang berjalan di foreground.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: FrameLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Setup Floating Overlay View
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Window params:
        // Ukuran 1x1 pixel atau full layar transparan tergantung mode Edit / Play
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 100
        }

        overlayView = FrameLayout(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true

            // Indikator visual HUD floating badge
            val badge = TextView(this@OverlayService).apply {
                text = "PXN V9 Active"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#AA000000"))
                setPadding(20, 10, 20, 10)
            }
            addView(badge)

            // Menangkap input hardware axis (Steering Wheel Axis X, Throttle/Brake Axis)
            setOnGenericMotionListener { _, event ->
                MappingManager.processMotionEvent(event)
            }

            // Menangkap hardware key events (Buttons & Gears) jika overlay fokus
            setOnKeyListener { _, _, event ->
                MappingManager.processKeyEvent(event)
            }
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
}
