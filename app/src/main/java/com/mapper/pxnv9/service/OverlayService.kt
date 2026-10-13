package com.mapper.pxnv9.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.mapper.pxnv9.manager.MappingManager
import com.mapper.pxnv9.model.MappingType
import com.mapper.pxnv9.model.MappingWidget
import com.mapper.pxnv9.model.PointF

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager

    // Floating Control Pill
    private var floatButtonView: View? = null
    private var floatParams: WindowManager.LayoutParams? = null

    // Fullscreen Transparent Mapping Editor
    private var editorOverlayView: FrameLayout? = null
    private var isEditMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        MappingManager.loadCustomMapping(this)

        setupFloatingPill()
    }

    private fun getOverlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /**
     * Floating Pill kecil yang tidak pernah memblokir sentuhan ke game BUSSID
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingPill() {
        floatParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 80
        }

        val pillLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC1B5E20"))
            setPadding(24, 16, 24, 16)
        }

        val tvTitle = TextView(this).apply {
            text = "🎮 PXN V9 [EDIT]"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        pillLayout.addView(tvTitle)

        // Drag pill di layar atau klik untuk masuk edit mode
        var startX = 0
        var startY = 0
        var touchDownX = 0f
        var touchDownY = 0f

        pillLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = floatParams!!.x
                    startY = floatParams!!.y
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    floatParams!!.x = startX + (event.rawX - touchDownX).toInt()
                    floatParams!!.y = startY + (event.rawY - touchDownY).toInt()
                    windowManager.updateViewLayout(pillLayout, floatParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - touchDownX) < 15 && kotlin.math.abs(event.rawY - touchDownY) < 15) {
                        toggleEditMode()
                    }
                    true
                }
                else -> false
            }
        }

        floatButtonView = pillLayout
        windowManager.addView(floatButtonView, floatParams)
    }

    /**
     * Toggle mode edit posisi kustom widget di atas game
     */
    private fun toggleEditMode() {
        if (!isEditMode) {
            openEditorOverlay()
        } else {
            closeEditorOverlay()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun openEditorOverlay() {
        if (editorOverlayView != null) return
        isEditMode = true

        val editorParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        editorOverlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#44000000")) // Semi-transparan

            // Tombol Toolbar atas
            val toolbar = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#DD000000"))
                setPadding(20, 16, 20, 16)

                val btnSave = Button(this@OverlayService).apply {
                    text = "💾 Simpan"
                    setOnClickListener {
                        MappingManager.saveCustomMapping(this@OverlayService)
                        Toast.makeText(this@OverlayService, "Mapping & Sensitivitas Disimpan!", Toast.LENGTH_SHORT).show()
                        closeEditorOverlay()
                    }
                }
                addView(btnSave)

                val btnSens = Button(this@OverlayService).apply {
                    text = "⚙️ Sensitivitas & Setir"
                    setOnClickListener {
                        showSensitivityDialog()
                    }
                }
                addView(btnSens)

                val btnClose = Button(this@OverlayService).apply {
                    text = "✖ Tutup"
                    setOnClickListener {
                        closeEditorOverlay()
                    }
                }
                addView(btnClose)
            }

            addView(toolbar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))

            // Render semua widget agar bisa di-drag & drop
            for (widget in MappingManager.activeWidgets) {
                val widgetView = createDraggableWidgetView(widget)
                addView(widgetView)
            }
        }

        windowManager.addView(editorOverlayView, editorParams)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDraggableWidgetView(widget: MappingWidget): View {
        val size = (widget.steeringRadius * 2).coerceAtLeast(140f).toInt()
        val tv = TextView(this).apply {
            text = "${widget.name}\n(Tarik)"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#9900838F"))
        }

        val lp = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (widget.centerPoint.x - size / 2).toInt()
            topMargin = (widget.centerPoint.y - size / 2).toInt()
        }
        tv.layoutParams = lp

        var startLeft = 0
        var startTop = 0
        var downX = 0f
        var downY = 0f

        tv.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startLeft = (v.layoutParams as FrameLayout.LayoutParams).leftMargin
                    startTop = (v.layoutParams as FrameLayout.LayoutParams).topMargin
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newLeft = startLeft + (event.rawX - downX).toInt()
                    val newTop = startTop + (event.rawY - downY).toInt()
                    val params = v.layoutParams as FrameLayout.LayoutParams
                    params.leftMargin = newLeft
                    params.topMargin = newTop
                    v.layoutParams = params

                    // Update koordinat pusat
                    widget.centerPoint.x = (newLeft + size / 2).toFloat()
                    widget.centerPoint.y = (newTop + size / 2).toFloat()
                    true
                }
                else -> false
            }
        }

        return tv
    }

    private fun showSensitivityDialog() {
        val steeringWidget = MappingManager.activeWidgets.firstOrNull { it.type == MappingType.STEERING_WHEEL } ?: return

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.WHITE)

            // 1. Pilihan Derajat Putaran Setir (270 vs 900)
            val tvDeg = TextView(this@OverlayService).apply {
                text = "Rotasi Fisik Setir: ${steeringWidget.steeringDegrees.toInt()}°"
                textSize = 15f
            }
            addView(tvDeg)

            val btnToggleDeg = Button(this@OverlayService).apply {
                text = "Ganti ke ${if (steeringWidget.steeringDegrees == 900f) "270°" else "900°"}"
                setOnClickListener {
                    steeringWidget.steeringDegrees = if (steeringWidget.steeringDegrees == 900f) 270f else 900f
                    tvDeg.text = "Rotasi Fisik Setir: ${steeringWidget.steeringDegrees.toInt()}°"
                    text = "Ganti ke ${if (steeringWidget.steeringDegrees == 900f) "270°" else "900°"}"
                }
            }
            addView(btnToggleDeg)

            // 2. Slider Sensitivitas
            val tvSens = TextView(this@OverlayService).apply {
                text = "Sensitivitas Setir: ${String.format("%.1f", steeringWidget.steeringSensitivity)}x"
                textSize = 15f
                setPadding(0, 20, 0, 10)
            }
            addView(tvSens)

            val sbSens = SeekBar(this@OverlayService).apply {
                max = 30 // 0.5x sampai 3.5x
                progress = ((steeringWidget.steeringSensitivity - 0.5f) * 10).toInt().coerceIn(0, 30)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        steeringWidget.steeringSensitivity = 0.5f + (progress / 10f)
                        tvSens.text = "Sensitivitas Setir: ${String.format("%.1f", steeringWidget.steeringSensitivity)}x"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(sbSens)

            // 3. Slider Deadzone
            val tvDz = TextView(this@OverlayService).apply {
                text = "Deadzone: ${(steeringWidget.deadzone * 100).toInt()}%"
                textSize = 15f
                setPadding(0, 20, 0, 10)
            }
            addView(tvDz)

            val sbDz = SeekBar(this@OverlayService).apply {
                max = 20 // 0% sampai 20%
                progress = (steeringWidget.deadzone * 100).toInt().coerceIn(0, 20)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        steeringWidget.deadzone = progress / 100f
                        tvDz.text = "Deadzone: $progress%"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(sbDz)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pengaturan Setir PXN V9")
            .setView(dialogView)
            .setPositiveButton("Terapkan") { d, _ ->
                MappingManager.saveCustomMapping(this)
                d.dismiss()
            }
            .create()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.show()
    }

    private fun closeEditorOverlay() {
        isEditMode = false
        editorOverlayView?.let {
            windowManager.removeView(it)
            editorOverlayView = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeEditorOverlay()
        floatButtonView?.let {
            windowManager.removeView(it)
            floatButtonView = null
        }
    }
}
