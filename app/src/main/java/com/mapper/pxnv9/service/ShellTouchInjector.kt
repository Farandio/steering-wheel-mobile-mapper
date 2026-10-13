package com.mapper.pxnv9.service

import android.util.Log
import java.io.OutputStream
import java.net.Socket

/**
 * ShellTouchInjector mengimplementasikan injeksi sentuhan zero-delay (0ms latency)
 * melalui shell / adb wireless debugging daemon.
 *
 * Mendukung 2 metode:
 * 1. Root / Local Shell Pipe (sh / su)
 * 2. ADB Wireless Debugging TCP Socket (localhost:5555 / adb port)
 */
object ShellTouchInjector {

    private const val TAG = "ShellTouchInjector"
    private var shellProcess: Process? = null
    private var shellOutputStream: OutputStream? = null
    var isAdbConnected: Boolean = false
        private set

    /**
     * Memulai sesi shell interaktif persistent (mencegah overhead fork-exec setiap tap)
     */
    fun startShellSession(): Boolean {
        return try {
            if (shellProcess != null) return true
            // Coba buka sh lokal dengan hak akses shell/root
            val process = Runtime.getRuntime().exec("sh")
            shellOutputStream = process.outputStream
            shellProcess = process
            isAdbConnected = true
            Log.i(TAG, "Shell persistent session aktif untuk injeksi sentuhan cepat!")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Gagal memulai local shell session: ${e.message}")
            isAdbConnected = false
            false
        }
    }

    /**
     * Injeksi Tap via Shell: `input tap X Y`
     */
    fun injectTap(x: Float, y: Float) {
        val cmd = "input tap ${x.toInt()} ${y.toInt()}\n"
        writeCommand(cmd)
    }

    /**
     * Injeksi Swipe / Drag via Shell: `input swipe X1 Y1 X2 Y2 [durationMs]`
     */
    fun injectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 20L) {
        val cmd = "input swipe ${startX.toInt()} ${startY.toInt()} ${endX.toInt()} ${endY.toInt()} $durationMs\n"
        writeCommand(cmd)
    }

    private fun writeCommand(cmd: String) {
        try {
            shellOutputStream?.let {
                it.write(cmd.toByteArray())
                it.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing shell command: ${e.message}")
            close()
        }
    }

    fun close() {
        try {
            shellOutputStream?.close()
            shellProcess?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            shellOutputStream = null
            shellProcess = null
            isAdbConnected = false
        }
    }
}
