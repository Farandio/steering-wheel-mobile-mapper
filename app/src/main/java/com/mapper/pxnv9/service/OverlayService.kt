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

    // Background Joystick MotionEvent Interceptor (Menangkap Axis Setir, Gas, Rem, Kopling saat game aktif)
    private var motionInterceptorView: View? = null

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
        setupMotionInterceptor()
    }

    /**
     * Window transparan khusus di background untuk menangkap GenericMotionEvents (Axis analog setir & 3 pedal).
     * Diberi FLAG_NOT_TOUCH_MODAL agar game BUSSID tetap dapat disentuh dengan normal,
     * sambil View menerima joystick/wheel events.
     */
    private fun setupMotionInterceptor() {
        if (motionInterceptorView != null) return

        val interceptorParams = WindowManager.LayoutParams(
            1, // Ukuran 1x1 piksel di pojok agar tidak mengganggu sentuhan game
            1,
            getOverlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        motionInterceptorView = View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnGenericMotionListener { _, event ->
                MappingManager.processMotionEvent(event, this@OverlayService)
            }
            setOnKeyListener { _, keyCode, event ->
                MappingManager.processKeyEvent(event, this@OverlayService)
            }
        }

        try {
            windowManager.addView(motionInterceptorView, interceptorParams)
            motionInterceptorView?.requestFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val tvLiveHud = TextView(this).apply {
            text = " [0° | N]"
            setTextColor(Color.YELLOW)
            textSize = 13f
        }
        pillLayout.addView(tvLiveHud)

        // Helper untuk format teks HUD
        fun refreshHudText(angle: Float = 0f, gear: String = MappingManager.currentGear) {
            val clutchIndicator = if (MappingManager.isClutchDepressed) " (CLUTCH)" else ""
            tvLiveHud.text = " [${angle.toInt()}° | $gear$clutchIndicator]"
        }

        // Observer untuk update HUD live
        MappingManager.onSteeringAngleChanged = { deg, _ ->
            refreshHudText(angle = deg, gear = MappingManager.currentGear)
        }
        MappingManager.onGearChanged = { gear ->
            refreshHudText(gear = gear)
        }
        MappingManager.onClutchChanged = { _ ->
            refreshHudText(gear = MappingManager.currentGear)
        }

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
            MappingManager.processMotionEvent(event, this@OverlayService)
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

                // 2. Profil Game
                addView(Button(this@OverlayService).apply {
                    text = "📁 Profil (${MappingManager.currentProfileName})"
                    setOnClickListener {
                        showProfileSelectionDialog()
                    }
                })

                // 3. Sensitivitas Setir
                addView(Button(this@OverlayService).apply {
                    text = "⚙️ Setir"
                    setOnClickListener {
                        showSensitivityDialog()
                    }
                })

                // 3b. Kalibrasi Pedal (Gas, Rem, Kopling)
                addView(Button(this@OverlayService).apply {
                    text = "🦶 Pedal & Kopling"
                    setOnClickListener {
                        showPedalCalibrationDialog()
                    }
                })

                // 4. Simpan
                addView(Button(this@OverlayService).apply {
                    text = "💾 Simpan"
                    setBackgroundColor(Color.parseColor("#2E7D32"))
                    setTextColor(Color.WHITE)
                    setOnClickListener {
                        MappingManager.saveCurrentProfile(this@OverlayService)
                        Toast.makeText(this@OverlayService, "Profil ${MappingManager.currentProfileName} Disimpan!", Toast.LENGTH_SHORT).show()
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
            // Pedal kopling adalah virtual interlock hardware (tidak ada tombol di BUSSID untuk disentuh),
            // jadi tidak perlu memunculkan kotak di layar sentuh game agar layar bersih.
            if (widget.id == "pedal_clutch") continue

            when (widget.type) {
                MappingType.STEERING_WHEEL -> container.addView(createSteeringHollowView(widget))
                MappingType.SHIFTER_SLIDER -> {
                    // Tampilkan pin bulat [N] (Titik Pusat Netral) yang serasi dengan pin gigi
                    container.addView(createShifterNeutralPinView(widget))
                    // Tampilkan pin individual untuk masing-masing gigi (R, 1, 2, 3, 4, 5, 6)
                    for ((gearName, slotPoint) in widget.shifterSlots) {
                        container.addView(createShifterSlotPinView(widget, gearName, slotPoint))
                    }
                }
                else -> container.addView(createStandardWidgetView(widget))
            }
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
     * Pin Pusat Netral [N] H-Shifter
     * Menggantikan kotak ungu besar agar tampilan rapi, bersih, dan konsisten dengan pin gigi (1-6, R).
     * Klik untuk membuka pengaturan H-Shifter (Kopling, Linearity, dll).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createShifterNeutralPinView(widget: MappingWidget): View {
        val pinSize = 72
        val pin = TextView(this).apply {
            text = "N"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E62E7D32")) // Hijau Netral
                setStroke(3, Color.WHITE)
            }
        }

        val lp = FrameLayout.LayoutParams(pinSize, pinSize).apply {
            leftMargin = (widget.centerPoint.x - pinSize / 2).toInt()
            topMargin = (widget.centerPoint.y - pinSize / 2).toInt()
        }
        pin.layoutParams = lp

        var startLeft = 0
        var startTop = 0
        var downX = 0f
        var downY = 0f

        pin.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startLeft = (v.layoutParams as FrameLayout.LayoutParams).leftMargin
                    startTop = (v.layoutParams as FrameLayout.LayoutParams).topMargin
                    downX = event.rawX
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downX).toInt()
                    val deltaY = (event.rawY - downY).toInt()
                    val newLeft = startLeft + deltaX
                    val newTop = startTop + deltaY
                    val p = v.layoutParams as FrameLayout.LayoutParams
                    p.leftMargin = newLeft
                    p.topMargin = newTop
                    v.layoutParams = p

                    widget.centerPoint.x = (newLeft + pinSize / 2).toFloat()
                    widget.centerPoint.y = (newTop + pinSize / 2).toFloat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - downX) < 15 && kotlin.math.abs(event.rawY - downY) < 15) {
                        showWidgetOptionDialog(widget)
                    } else {
                        Toast.makeText(this@OverlayService, "Posisi Netral [N] Disimpan!", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

        return pin
    }

    /**
     * Pin Individual Gigi H-Shifter (R, 1, 2, 3, 4, 5, 6)
     * Dapat digeser satu per satu ke lubang slot gigi BUSSID secara independen.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createShifterSlotPinView(widget: MappingWidget, gearName: String, slotPoint: PointF): View {
        val pinSize = 64
        val pin = TextView(this).apply {
            text = gearName
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6FF5722")) // Oranye terang
                setStroke(2, Color.WHITE)
            }
        }

        val lp = FrameLayout.LayoutParams(pinSize, pinSize).apply {
            leftMargin = (slotPoint.x - pinSize / 2).toInt()
            topMargin = (slotPoint.y - pinSize / 2).toInt()
        }
        pin.layoutParams = lp

        var startLeft = 0
        var startTop = 0
        var downX = 0f
        var downY = 0f

        pin.setOnTouchListener { v, event ->
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
                    val p = v.layoutParams as FrameLayout.LayoutParams
                    p.leftMargin = newLeft
                    p.topMargin = newTop
                    v.layoutParams = p

                    // Update koordinat slot gigi spesifik
                    slotPoint.x = (newLeft + pinSize / 2).toFloat()
                    slotPoint.y = (newTop + pinSize / 2).toFloat()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(event.rawX - downX) < 15 && kotlin.math.abs(event.rawY - downY) < 15) {
                        showGearCalibrationDialog(widget, gearName)
                    } else {
                        Toast.makeText(this@OverlayService, "Posisi Gigi $gearName diatur!", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }

        return pin
    }

    /**
     * Dialog Kalibrasi Manual Tombol Fisik untuk Tiap Gigi (1-6, R)
     */
    private fun showGearCalibrationDialog(shifterWidget: MappingWidget, gearName: String) {
        val currentKey = shifterWidget.gearKeyNames[gearName] ?: "Default (Button $gearName)"

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            setBackgroundColor(Color.WHITE)

            val tvTitle = TextView(this@OverlayService).apply {
                text = "⚙️ Kalibrasi Tombol Gigi $gearName"
                textSize = 16f
                setTextColor(Color.BLACK)
            }
            addView(tvTitle)

            val tvBound = TextView(this@OverlayService).apply {
                text = "Tombol Terikat: $currentKey"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 16, 0, 12)
            }
            addView(tvBound)

            val btnLearn = Button(this@OverlayService).apply {
                text = "🎮 Tekan Tuas Gigi $gearName di Shifter..."
                setOnClickListener {
                    text = "⏳ Masukkan tuas ke Gigi $gearName pada shifter..."
                    MappingManager.keyListenCallback = { keyCode, scanCode, keyName ->
                        shifterWidget.gearKeyBindings[gearName] = keyCode
                        if (scanCode > 0) shifterWidget.gearScanCodeBindings[gearName] = scanCode
                        shifterWidget.gearKeyNames[gearName] = keyName
                        tvBound.text = "Tombol Terikat: $keyName"
                        text = "✅ Berhasil Terikat: $keyName"
                        Toast.makeText(this@OverlayService, "Gigi $gearName terikat ke $keyName", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            addView(btnLearn)

            val btnReset = Button(this@OverlayService).apply {
                text = "🔄 Reset ke Default PXN"
                setOnClickListener {
                    shifterWidget.gearKeyBindings.remove(gearName)
                    shifterWidget.gearKeyNames.remove(gearName)
                    tvBound.text = "Tombol Terikat: Default (Button $gearName)"
                    Toast.makeText(this@OverlayService, "Gigi $gearName di-reset ke default", Toast.LENGTH_SHORT).show()
                }
            }
            addView(btnReset)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kalibrasi Manual Shifter")
            .setView(dialogView)
            .setPositiveButton("Selesai") { d, _ ->
                MappingManager.saveCurrentProfile(this)
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
                text = if (widget.type == MappingType.PEDAL_ANALOG) {
                    val axisName = when (widget.boundAxis) {
                        MotionEvent.AXIS_GAS -> "AXIS_GAS (22)"
                        MotionEvent.AXIS_BRAKE -> "AXIS_BRAKE (23)"
                        MotionEvent.AXIS_Y -> "AXIS_Y (1)"
                        MotionEvent.AXIS_Z -> "AXIS_Z (11)"
                        MotionEvent.AXIS_RZ -> "AXIS_RZ (14)"
                        MotionEvent.AXIS_RTRIGGER -> "AXIS_RTRIGGER (18)"
                        MotionEvent.AXIS_LTRIGGER -> "AXIS_LTRIGGER (17)"
                        -1 -> "Auto Axis"
                        else -> "Axis ${widget.boundAxis}"
                    }
                    "Axis: [$axisName]"
                } else {
                    "Key: [${widget.boundKeyName}]"
                }
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
                        MappingManager.keyListenCallback = { keyCode, scanCode, keyName ->
                            widget.boundKeyCode = keyCode
                            widget.boundScanCode = scanCode
                            widget.boundKeyName = keyName
                            Toast.makeText(this@OverlayService, "Tombol Terikat: $keyName", Toast.LENGTH_SHORT).show()
                            editorOverlayView?.let { refreshWidgetViews(it) }
                        }
                    }
                }
                addView(btnListenKey)
            }

            // Opsi Khusus Pedal Analog: Kalibrasi Axis Manual & Jarak Travel Pedal
            if (widget.type == MappingType.PEDAL_ANALOG) {
                val tvAxis = TextView(this@OverlayService).apply {
                    text = "Axis Terikat: ${if (widget.boundAxis == -1) "Default (Auto)" else "MotionEvent.AXIS_${widget.boundAxis}"}"
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 16, 0, 8)
                }
                addView(tvAxis)

                val btnPickAxis = Button(this@OverlayService).apply {
                    text = "⚙️ Pilih Axis Manual (Gas / Brake / Clutch / Trigger)"
                    setOnClickListener {
                        val axisOptions = arrayOf(
                            "Default Auto",
                            "AXIS_GAS (22) - Pedal Gas",
                            "AXIS_BRAKE (23) - Pedal Rem",
                            "AXIS_Y (1) - Pedal Kopling PXN",
                            "AXIS_Z (11) - Pedal Kopling Alternatif",
                            "AXIS_RZ (14) - Kopling / Rudder",
                            "AXIS_RTRIGGER (18)",
                            "AXIS_LTRIGGER (17)"
                        )
                        val axisValues = intArrayOf(
                            -1,
                            android.view.MotionEvent.AXIS_GAS,
                            android.view.MotionEvent.AXIS_BRAKE,
                            android.view.MotionEvent.AXIS_Y,
                            android.view.MotionEvent.AXIS_Z,
                            android.view.MotionEvent.AXIS_RZ,
                            android.view.MotionEvent.AXIS_RTRIGGER,
                            android.view.MotionEvent.AXIS_LTRIGGER
                        )
                        AlertDialog.Builder(this@OverlayService)
                            .setTitle("Pilih Axis Hardware")
                            .setItems(axisOptions) { _, idx ->
                                widget.boundAxis = axisValues[idx]
                                tvAxis.text = "Axis Terikat: ${axisOptions[idx]}"
                                Toast.makeText(this@OverlayService, "Axis disimpan: ${axisOptions[idx]}", Toast.LENGTH_SHORT).show()
                            }
                            .create().apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                                }
                            }.show()
                    }
                }
                addView(btnPickAxis)

                val tvTravel = TextView(this@OverlayService).apply {
                    text = "Panjang Sapuan Slider Pedal: ${widget.pedalTravelDistance.toInt()} px"
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 16, 0, 8)
                }
                addView(tvTravel)

                val sbTravel = SeekBar(this@OverlayService).apply {
                    min = 60
                    max = 350
                    progress = widget.pedalTravelDistance.toInt()
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                            widget.pedalTravelDistance = progress.toFloat()
                            tvTravel.text = "Panjang Sapuan Slider Pedal: $progress px"
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    })
                }
                addView(sbTravel)
            }

            // Opsi Khusus H-Shifter: Syarat Injak Kopling Realistis
            if (widget.type == MappingType.SHIFTER_SLIDER) {
                val cbClutch = android.widget.CheckBox(this@OverlayService).apply {
                    text = "🔒 Wajib Injak Kopling Saat Oper Gigi (Realistis)"
                    isChecked = widget.requireClutchToShift
                    textSize = 14f
                    setTextColor(Color.BLACK)
                    setPadding(0, 16, 0, 8)
                    setOnCheckedChangeListener { _, isChecked ->
                        widget.requireClutchToShift = isChecked
                        Toast.makeText(
                            this@OverlayService,
                            if (isChecked) "Mode Realistis Aktif: Harus injak kopling untuk oper gigi!" else "Oper gigi langsung tanpa kopling diizinkan",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                addView(cbClutch)
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

            // 3. Slider Linearity (Kurva Respons Non-Linear Kemudi)
            val tvLin = TextView(this@OverlayService).apply {
                text = "Kurva Respons (Linearity): ${String.format("%.1f", steeringWidget.steeringLinearity)}x\n(>1.0x = Stabil di tengah saat jalan lurus)"
                textSize = 14f
                setPadding(0, 20, 0, 10)
            }
            addView(tvLin)

            val sbLin = SeekBar(this@OverlayService).apply {
                max = 20 // 0.5x sampai 2.5x
                progress = ((steeringWidget.steeringLinearity - 0.5f) * 10).toInt().coerceIn(0, 20)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        steeringWidget.steeringLinearity = 0.5f + (progress / 10f)
                        tvLin.text = "Kurva Respons (Linearity): ${String.format("%.1f", steeringWidget.steeringLinearity)}x\n(>1.0x = Stabil di tengah saat jalan lurus)"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            }
            addView(sbLin)

            // 4. Slider Deadzone
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

            // 5. Kalibrasi Manual Axis Setir Kemudi
            val tvSteerAxis = TextView(this@OverlayService).apply {
                text = "Axis Kemudi: ${if (steeringWidget.boundAxis == -1) "AXIS_X (0) - Default" else "MotionEvent.AXIS_${steeringWidget.boundAxis}"}"
                textSize = 14f
                setTextColor(Color.DKGRAY)
                setPadding(0, 20, 0, 8)
            }
            addView(tvSteerAxis)

            val btnPickSteerAxis = Button(this@OverlayService).apply {
                text = "⚙️ Pilih Axis Setir Kemudi"
                setOnClickListener {
                    val steerAxisOptions = arrayOf(
                        "AXIS_X (0) - Default Setir PXN",
                        "AXIS_Y (1) - Alternatif Setir",
                        "AXIS_Z (11) - Alternatif Axis Z",
                        "AXIS_RZ (14) - Alternatif Axis RZ"
                    )
                    val steerAxisValues = intArrayOf(
                        android.view.MotionEvent.AXIS_X,
                        android.view.MotionEvent.AXIS_Y,
                        android.view.MotionEvent.AXIS_Z,
                        android.view.MotionEvent.AXIS_RZ
                    )
                    AlertDialog.Builder(this@OverlayService)
                        .setTitle("Pilih Axis Setir Kemudi")
                        .setItems(steerAxisOptions) { _, idx ->
                            steeringWidget.boundAxis = steerAxisValues[idx]
                            tvSteerAxis.text = "Axis Kemudi: ${steerAxisOptions[idx]}"
                            Toast.makeText(this@OverlayService, "Axis setir disimpan: ${steerAxisOptions[idx]}", Toast.LENGTH_SHORT).show()
                        }
                        .create().apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            }
                        }.show()
                }
            }
            addView(btnPickSteerAxis)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pengaturan Setir PXN V9")
            .setView(dialogView)
            .setPositiveButton("Terapkan") { d, _ ->
                MappingManager.saveCurrentProfile(this)
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

    /**
     * Dialog Kalibrasi Manual Seluruh Pedal: Gas, Rem, dan Kopling
     */
    private fun showPedalCalibrationDialog() {
        val gasWidget = MappingManager.activeWidgets.firstOrNull { it.id == "pedal_gas" }
        val brakeWidget = MappingManager.activeWidgets.firstOrNull { it.id == "pedal_brake" }
        val clutchWidget = MappingManager.activeWidgets.firstOrNull { it.id == "pedal_clutch" }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 20)
            setBackgroundColor(Color.WHITE)

            val tvTitle = TextView(this@OverlayService).apply {
                text = "🦶 Kalibrasi Pedal (Gas, Rem, Kopling)"
                textSize = 16f
                setTextColor(Color.BLACK)
            }
            addView(tvTitle)

            // 1. Pedal Gas
            if (gasWidget != null) {
                val tvGas = TextView(this@OverlayService).apply {
                    text = "🟢 Pedal Gas Axis: ${if (gasWidget.boundAxis == -1) "AXIS_GAS (22) - Auto" else "MotionEvent.AXIS_${gasWidget.boundAxis}"}"
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 14, 0, 4)
                }
                addView(tvGas)

                val btnGas = Button(this@OverlayService).apply {
                    text = "⚙️ Pilih Axis Pedal Gas"
                    setOnClickListener {
                        val opts = arrayOf("Default Auto (AXIS_GAS / RTRIGGER)", "AXIS_GAS (22)", "AXIS_RTRIGGER (18)", "AXIS_Y (1)", "AXIS_RZ (14)")
                        val vals = intArrayOf(-1, android.view.MotionEvent.AXIS_GAS, android.view.MotionEvent.AXIS_RTRIGGER, android.view.MotionEvent.AXIS_Y, android.view.MotionEvent.AXIS_RZ)
                        AlertDialog.Builder(this@OverlayService)
                            .setTitle("Pilih Axis Gas")
                            .setItems(opts) { _, i ->
                                gasWidget.boundAxis = vals[i]
                                tvGas.text = "🟢 Pedal Gas Axis: ${opts[i]}"
                                Toast.makeText(this@OverlayService, "Axis Gas disimpan!", Toast.LENGTH_SHORT).show()
                            }.create().apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            }.show()
                    }
                }
                addView(btnGas)
            }

            // 2. Pedal Rem
            if (brakeWidget != null) {
                val tvBrake = TextView(this@OverlayService).apply {
                    text = "🔴 Pedal Rem Axis: ${if (brakeWidget.boundAxis == -1) "AXIS_BRAKE (23) - Auto" else "MotionEvent.AXIS_${brakeWidget.boundAxis}"}"
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 14, 0, 4)
                }
                addView(tvBrake)

                val btnBrake = Button(this@OverlayService).apply {
                    text = "⚙️ Pilih Axis Pedal Rem"
                    setOnClickListener {
                        val opts = arrayOf("Default Auto (AXIS_BRAKE / LTRIGGER)", "AXIS_BRAKE (23)", "AXIS_LTRIGGER (17)", "AXIS_RZ (14)", "AXIS_Z (11)")
                        val vals = intArrayOf(-1, android.view.MotionEvent.AXIS_BRAKE, android.view.MotionEvent.AXIS_LTRIGGER, android.view.MotionEvent.AXIS_RZ, android.view.MotionEvent.AXIS_Z)
                        AlertDialog.Builder(this@OverlayService)
                            .setTitle("Pilih Axis Rem")
                            .setItems(opts) { _, i ->
                                brakeWidget.boundAxis = vals[i]
                                tvBrake.text = "🔴 Pedal Rem Axis: ${opts[i]}"
                                Toast.makeText(this@OverlayService, "Axis Rem disimpan!", Toast.LENGTH_SHORT).show()
                            }.create().apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            }.show()
                    }
                }
                addView(btnBrake)
            }

            // 3. Pedal Kopling
            if (clutchWidget != null) {
                val tvClutch = TextView(this@OverlayService).apply {
                    text = "🔵 Pedal Kopling Axis: ${if (clutchWidget.boundAxis == -1) "AXIS_Y (1) - Default PXN" else "MotionEvent.AXIS_${clutchWidget.boundAxis}"}"
                    textSize = 13f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 14, 0, 4)
                }
                addView(tvClutch)

                val btnClutch = Button(this@OverlayService).apply {
                    text = "⚙️ Pilih Axis Pedal Kopling"
                    setOnClickListener {
                        val opts = arrayOf("AXIS_Y (1) - Default Kopling PXN", "AXIS_Z (11) - Alternatif Kopling", "AXIS_RZ (14) - Alternatif Kopling", "AXIS_LTRIGGER (17)", "AXIS_RTRIGGER (18)")
                        val vals = intArrayOf(android.view.MotionEvent.AXIS_Y, android.view.MotionEvent.AXIS_Z, android.view.MotionEvent.AXIS_RZ, android.view.MotionEvent.AXIS_LTRIGGER, android.view.MotionEvent.AXIS_RTRIGGER)
                        AlertDialog.Builder(this@OverlayService)
                            .setTitle("Pilih Axis Kopling")
                            .setItems(opts) { _, i ->
                                clutchWidget.boundAxis = vals[i]
                                tvClutch.text = "🔵 Pedal Kopling Axis: ${opts[i]}"
                                Toast.makeText(this@OverlayService, "Axis Kopling disimpan!", Toast.LENGTH_SHORT).show()
                            }.create().apply {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                            }.show()
                    }
                }
                addView(btnClutch)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Kalibrasi Pedal & Kopling")
            .setView(dialogView)
            .setPositiveButton("Simpan") { d, _ ->
                MappingManager.saveCurrentProfile(this)
                Toast.makeText(this, "Kalibrasi Pedal disimpan!", Toast.LENGTH_SHORT).show()
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

    private fun showProfileSelectionDialog() {
        val profiles = MappingManager.getSavedProfileList(this).toTypedArray()
        val items = profiles.toMutableList().apply { add("➕ Buat Profil Baru...") }.toTypedArray()

        val builder = AlertDialog.Builder(this)
            .setTitle("Pilih Profil Game")
            .setItems(items) { _, which ->
                if (which == items.size - 1) {
                    // Buat Profil Baru
                    showCreateNewProfileDialog()
                } else {
                    val selected = items[which]
                    MappingManager.loadProfile(this, selected)
                    Toast.makeText(this, "Profil Aktif: $selected", Toast.LENGTH_SHORT).show()
                    editorOverlayView?.let { refreshWidgetViews(it) }
                }
            }

        val dialog = builder.create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            @Suppress("DEPRECATION")
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        dialog.show()
    }

    private fun showCreateNewProfileDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Contoh: Truckers of Europe, BUSSID Mod JB5"
            setTextColor(Color.BLACK)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Nama Profil Baru")
            .setView(input)
            .setPositiveButton("Buat & Simpan") { d, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    MappingManager.saveCurrentProfile(this, name)
                    Toast.makeText(this, "Profil '$name' dibuat!", Toast.LENGTH_SHORT).show()
                    editorOverlayView?.let { refreshWidgetViews(it) }
                }
                d.dismiss()
            }
            .setNegativeButton("Batal") { d, _ -> d.dismiss() }
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
        motionInterceptorView?.let {
            windowManager.removeView(it)
            motionInterceptorView = null
        }
    }
}
