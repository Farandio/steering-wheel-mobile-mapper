package com.mapper.pxnv9.service

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import java.util.UUID

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager

    // Floating Pill (Selalu tampil di layar)
    private var floatButtonView: View? = null
    private var floatParams: WindowManager.LayoutParams? = null

    // Editor Mode (Overlay interaktif)
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
     * Floating Pill kecil di pojok layar
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
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1B5E20"))
                cornerRadius = 30f
                setStroke(2, Color.WHITE)
            }
            setPadding(28, 16, 28, 16)
        }

        val tvTitle = TextView(this).apply {
            text = "🎮 PXN V9 [EDIT]"
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        pillLayout.addView(tvTitle)

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

        pillLayout.setOnGenericMotionListener { _, event ->
            MappingManager.processMotionEvent(event)
        }

        floatButtonView = pillLayout
        windowManager.addView(floatButtonView, floatParams)
    }

    private fun toggleEditMode() {
        if (!isEditMode) {
            openEditorOverlay()
        } else {
            closeEditorOverlay()
        }
    }

    /**
     * Mode Edit Layar: Tambah Tombol, Tarik (Drag), Hapus (Delete), Ubah Ukuran (Resize), Mapping Tombol
     */
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

            // Toolbar Atas
            val toolbar = LinearLayout(this@OverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EE1A1A1A"))
                    cornerRadius = 16f
                }
                setPadding(16, 12, 16, 12)

                // 1. Tambah Tombol Baru
                addView(Button(this@OverlayService).apply {
                    text = "➕ Tambah Tombol"
                    setOnClickListener {
                        addNewTapWidget()
                    }
                })

                // 2. Sensitivitas & Setir
                addView(Button(this@OverlayService).apply {
                    text = "⚙️ Sensitivitas"
                    setOnClickListener {
                        showSensitivityDialog()
                    }
                })

                // 3. Simpan
                addView(Button(this@OverlayService).apply {
                    text = "💾 Simpan"
                    setBackgroundColor(Color.parseColor("#2E7D32"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        MappingManager.saveCustomMapping(this@OverlayService)
                        Toast.makeText(this@OverlayService, "Konfigurasi Berhasil Disimpan!", Toast.LENGTH_SHORT).show()
                        closeEditorOverlay()
                    }
                })

                // 4. Tutup
                addView(Button(this@OverlayService).apply {
                    text = "✖ Tutup"
                    setOnClickListener {
                        closeEditorOverlay()
                    }
                })
            }

            addView(toolbar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = 20 })

            // Render semua widget saat ini
            refreshWidgetViews(this)
        }

        windowManager.addView(editorOverlayView, editorParams)
    }

    private fun refreshWidgetViews(container: FrameLayout) {
        // Hapus view widget lama kecuali toolbar (anak pertama)
        while (container.childCount > 1) {
            container.removeViewAt(1)
        }

        for (widget in MappingManager.activeWidgets) {
            val v = if (widget.type == MappingType.STEERING_WHEEL) {
                createSteeringHollowView(widget)
            } else {
                createStandardWidgetView(widget)
            }
            container.addView(v)
        }
    }

    /**
     * 4. Setir Donat / Bulat Bolong di Tengah (Ring Wheel)
     * Lingkar luar untuk rotasi sentuh BUSSID, tengah bolong agar tombol klakson bisa ditempatkan di tengahnya.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createSteeringHollowView(widget: MappingWidget): View {
        val size = (widget.steeringRadius * 2).toInt()
        val container = FrameLayout(this).apply {
            val lp = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (widget.centerPoint.x - size / 2).toInt()
                topMargin = (widget.centerPoint.y - size / 2).toInt()
            }
            layoutParams = lp

            // Background bentuk lingkaran berlubang (stroke tebal warna hijau transparan)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(36, Color.parseColor("#AA00E676")) // Ring luar setir
            }

            // Teks label di tepi atas lingkaran
            val label = TextView(this@OverlayService).apply {
                text = "${widget.name}\n(Tarik untuk geser, Klik untuk opsi)"
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#99000000"))
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ))
        }

        attachDragAndClickListener(container, widget, size, size)
        return container
    }

    /**
     * Widget Tombol Standar (Tap, Shifter, Pedal)
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createStandardWidgetView(widget: MappingWidget): View {
        val w = widget.width.toInt().coerceAtLeast(80)
        val h = widget.height.toInt().coerceAtLeast(80)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(if (widget.type == MappingType.TAP) Color.parseColor("#AA0288D1") else Color.parseColor("#AA6A1B9A"))
                setStroke(3, Color.WHITE)
            }
            setPadding(10, 8, 10, 8)

            val tvTitle = TextView(this@OverlayService).apply {
                text = widget.name
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
            }
            addView(tvTitle)

            val tvKey = TextView(this@OverlayService).apply {
                text = "Key: [${widget.boundKeyName}]"
                setTextColor(Color.YELLOW)
                textSize = 10f
                gravity = Gravity.CENTER
            }
            addView(tvKey)

            val lp = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = (widget.centerPoint.x - w / 2).toInt()
                topMargin = (widget.centerPoint.y - h / 2).toInt()
            }
            layoutParams = lp
        }

        attachDragAndClickListener(container, widget, w, h)
        return container
    }

    private fun attachDragAndClickListener(view: View, widget: MappingWidget, w: Int, h: Int) {
        var startLeft = 0
        var startTop = 0
        var downX = 0f
        var downY = 0f

        view.setOnTouchListener { v, event ->
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

                    widget.centerPoint.x = (newLeft + w / 2).toFloat()
                    widget.centerPoint.y = (newTop + h / 2).toFloat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Jika hanya klik singkat (bukan drag): Buka Dialog Opsi (Mapping Tombol, Resize, Delete)
                    if (kotlin.math.abs(event.rawX - downX) < 15 && kotlin.math.abs(event.rawY - downY) < 15) {
                        showWidgetOptionDialog(widget)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Dialog untuk:
     * 1. Hapus Tombol (Delete)
     * 2. Ubah Ukuran (Resize Slider)
     * 3. Mapping Tombol Fisik (User tekan tombol di steering wheel)
     */
    private fun showWidgetOptionDialog(widget: MappingWidget) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            setBackgroundColor(Color.WHITE)

            // Nama Tombol
            val tvName = TextView(this@OverlayService).apply {
                text = "Target: ${widget.name}"
                textSize = 16f
                setTextColor(Color.BLACK)
            }
            addView(tvName)

            // 1. Mapping Tombol Fisik PXN V9
            if (widget.type == MappingType.TAP) {
                val tvKey = TextView(this@OverlayService).apply {
                    text = "Tombol Terikat: ${widget.boundKeyName}"
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 16, 0, 8)
                }
                addView(tvKey)

                val btnListenKey = Button(this@OverlayService).apply {
                    text = "🎮 Tekan Tombol di Setir..."
                    setOnClickListener {
                        text = "⏳ Mendengarkan input tombol PXN..."
                        MappingManager.keyListenCallback = { keyCode, keyName ->
                            widget.boundKeyCode = keyCode
                            widget.boundKeyName = keyName
                            Toast.makeText(this@OverlayService, "Tombol Terikat: $keyName", Toast.LENGTH_SHORT).show()
                            editorOverlayView?.let { refreshWidgetViews(it) }
                        }
                    }
                }
                addView(btnListenKey)
            }

            // 2. Resize Ukuran Tombol / Setir
            val tvResize = TextView(this@OverlayService).apply {
                text = "Ubah Ukuran (Resize): ${(if (widget.type == MappingType.STEERING_WHEEL) widget.steeringRadius else widget.width).toInt()} px"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 24, 0, 8)
            }
            addView(tvResize)

            val sbResize = SeekBar(this@OverlayService).apply {
                if (widget.type == MappingType.STEERING_WHEEL) {
                    min = 100
                    max = 350
                    progress = widget.steeringRadius.toInt()
                } else {
                    min = 60
                    max = 300
                    progress = widget.width.toInt()
                }

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (widget.type == MappingType.STEERING_WHEEL) {
                            widget.steeringRadius = progress.toFloat()
                        } else {
                            widget.width = progress.toFloat()
                            widget.height = progress.toFloat()
                        }
                        tvResize.text = "Ubah Ukuran (Resize): $progress px"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(sbResize)

            // 3. Tombol Hapus (Delete)
            if (widget.type != MappingType.STEERING_WHEEL) { // Setir utama jangan terhapus
                val btnDelete = Button(this@OverlayService).apply {
                    text = "🗑 Hapus Tombol Ini"
                    setBackgroundColor(Color.parseColor("#D32F2F"))
                    setTextColor(Color.WHITE)
                    setPadding(0, 16, 0, 16)
                    setOnClickListener {
                        MappingManager.activeWidgets.remove(widget)
                        Toast.makeText(this@OverlayService, "Tombol ${widget.name} Dihapus", Toast.LENGTH_SHORT).show()
                        editorOverlayView?.let { refreshWidgetViews(it) }
                    }
                }
                addView(btnDelete)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pengaturan Widget")
            .setView(dialogView)
            .setPositiveButton("Selesai") { d, _ ->
                editorOverlayView?.let { refreshWidgetViews(it) }
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

    private fun addNewTapWidget() {
        val newWidget = MappingWidget(
            id = "tap_" + UUID.randomUUID().toString().take(6),
            name = "Tombol Baru",
            type = MappingType.TAP,
            centerPoint = PointF(1200f, 540f), // Ditengah layar
            width = 110f,
            height = 110f,
            boundKeyName = "Belum Diatur"
        )
        MappingManager.activeWidgets.add(newWidget)
        editorOverlayView?.let { refreshWidgetViews(it) }
        Toast.makeText(this, "Tombol ditambahkan ke tengah layar. Tarik ke posisi game & klik untuk mapping!", Toast.LENGTH_LONG).show()
    }

    private fun showSensitivityDialog() {
        val steeringWidget = MappingManager.activeWidgets.firstOrNull { it.type == MappingType.STEERING_WHEEL } ?: return

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            setBackgroundColor(Color.WHITE)

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

            val tvSens = TextView(this@OverlayService).apply {
                text = "Sensitivitas Setir: ${String.format("%.1f", steeringWidget.steeringSensitivity)}x"
                textSize = 15f
                setPadding(0, 20, 0, 10)
            }
            addView(tvSens)

            val sbSens = SeekBar(this@OverlayService).apply {
                max = 30
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

            val tvDz = TextView(this@OverlayService).apply {
                text = "Deadzone: ${(steeringWidget.deadzone * 100).toInt()}%"
                textSize = 15f
                setPadding(0, 20, 0, 10)
            }
            addView(tvDz)

            val sbDz = SeekBar(this@OverlayService).apply {
                max = 20
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
