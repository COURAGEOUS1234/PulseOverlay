package com.pulse.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Low-resolution live copy of the screen, used only as the backdrop the glass bars blur.
 * An overlay can't read other apps' pixels directly, so this mirrors the display through the
 * existing MediaProjection into an ImageReader. Frames stay in memory and are never saved.
 *
 * Android 14+ allows ONE createVirtualDisplay per projection, so the display is created once
 * and paused/resized via setSurface()/resize() instead of being recreated.
 */
class ScreenBackdrop(private val context: Context, private val projection: MediaProjection) {

    /** Latest frame; only the top-left [frameWidth]×[frameHeight] region is valid (row padding). */
    @Volatile var frame: Bitmap? = null
        private set
    @Volatile var frameWidth = 0
        private set
    @Volatile var frameHeight = 0
        private set

    private val thread = HandlerThread("pulse-backdrop").apply { start() }
    private val handler = Handler(thread.looper)
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val ring = arrayOfNulls<Bitmap>(3) // triple buffer: UI may still be drawing the previous frame
    private var ringIdx = 0
    private var lastFrameMs = 0L

    val isActive: Boolean get() = reader != null

    /** Starts (or resumes / resizes after rotation). Throws SecurityException if the projection refuses. */
    fun start() {
        val (sw, sh) = screenSize()
        val w = (sw / SCALE).coerceAtLeast(1)
        val h = (sh / SCALE).coerceAtLeast(1)
        val dpi = (context.resources.displayMetrics.densityDpi / SCALE).coerceAtLeast(1)

        val newReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        newReader.setOnImageAvailableListener({ onImage(it, w, h) }, handler)

        val vd = display
        if (vd == null) {
            display = projection.createVirtualDisplay(
                "pulse-backdrop", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, handler,
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = newReader.surface
        }
        reader?.close()
        reader = newReader
    }

    fun pause() {
        display?.surface = null
        reader?.close()
        reader = null
        frame = null
    }

    fun release() {
        pause()
        display?.release()
        display = null
        thread.quitSafely()
    }

    private fun onImage(r: ImageReader, w: Int, h: Int) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameMs < MIN_FRAME_MS) return
            lastFrameMs = now
            val plane = img.planes[0]
            val rowPixels = plane.rowStride / plane.pixelStride
            ringIdx = (ringIdx + 1) % ring.size
            var bmp = ring[ringIdx]
            if (bmp == null || bmp.width != rowPixels || bmp.height != h) {
                bmp = Bitmap.createBitmap(rowPixels, h, Bitmap.Config.ARGB_8888)
                ring[ringIdx] = bmp
            }
            plane.buffer.rewind()
            bmp.copyPixelsFromBuffer(plane.buffer)
            frameWidth = w
            frameHeight = h
            frame = bmp
        } catch (_: RuntimeException) {
            // Reader closed mid-frame during pause/rotation: drop it.
        } finally {
            img.close()
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    companion object {
        private const val SCALE = 4          // quarter resolution: it gets blurred anyway
        private const val MIN_FRAME_MS = 33L // ~30 fps is plenty for a blurred backdrop
    }
}
