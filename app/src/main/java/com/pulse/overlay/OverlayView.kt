package com.pulse.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Full-screen, non-interactive drawing surface. Redraws every vsync while attached. */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context, private val processor: SpectrumProcessor) : View(context) {

    var style: VizStyle = VizStyle.BARS
    var sizeFraction: Float = 0.3f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val hsv = floatArrayOf(0f, 0.85f, 1f)
    private var shown = FloatArray(0)
    private var shownWave = FloatArray(SpectrumProcessor.WAVE_POINTS)
    private var hueShift = 0f
    private val density = resources.displayMetrics.density

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Render-side easing so the 20 Hz Visualizer source still animates smoothly at 60/120 Hz.
        val target = processor.bands
        if (shown.size != target.size) shown = FloatArray(target.size)
        for (i in shown.indices) shown[i] += (target[i] - shown[i]) * 0.35f
        val w = processor.wave
        if (w.size == shownWave.size) for (i in w.indices) shownWave[i] += (w[i] - shownWave[i]) * 0.5f
        hueShift = (hueShift + 0.25f) % 360f

        when (style) {
            VizStyle.BARS -> drawBars(canvas, mirrored = false)
            VizStyle.MIRROR -> drawBars(canvas, mirrored = true)
            VizStyle.WAVE -> drawWave(canvas)
            VizStyle.RING -> drawRing(canvas)
        }
        postInvalidateOnAnimation()
    }

    private fun color(i: Int, n: Int): Int {
        hsv[0] = (hueShift + 280f * i / n) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun drawBars(canvas: Canvas, mirrored: Boolean) {
        val n = shown.size
        if (n == 0) return
        val h = height.toFloat()
        val maxH = h * sizeFraction * if (mirrored) 0.5f else 1f
        val slot = width.toFloat() / n
        val barW = slot * 0.72f
        val radius = barW * 0.35f
        for (i in 0 until n) {
            val bh = shown[i] * maxH
            if (bh < density) continue
            val left = i * slot + (slot - barW) / 2f
            fill.color = color(i, n)
            canvas.drawRoundRect(left, h - bh, left + barW, h + radius, radius, radius, fill)
            if (mirrored) canvas.drawRoundRect(left, -radius, left + barW, bh, radius, radius, fill)
        }
    }

    private fun drawWave(canvas: Canvas) {
        val pts = shownWave
        val level = if (shown.isEmpty()) 0f else shown.average().toFloat()
        var peak = 0.05f
        for (v in pts) peak = max(peak, abs(v))
        val amp = height * sizeFraction * 0.5f * min(1f, level * 2.2f) / peak
        val baseY = height * (1f - sizeFraction * 0.5f)
        val dx = width.toFloat() / (pts.size - 1)

        path.reset()
        path.moveTo(0f, baseY - pts[0] * amp)
        for (i in 1 until pts.size) {
            val x0 = (i - 1) * dx
            val y0 = baseY - pts[i - 1] * amp
            val y1 = baseY - pts[i] * amp
            path.quadTo(x0, y0, (x0 + i * dx) / 2f, (y0 + y1) / 2f)
        }
        stroke.strokeWidth = 4f * density
        stroke.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(color(0, 3), color(1, 3), color(2, 3)), null, Shader.TileMode.CLAMP
        )
        canvas.drawPath(path, stroke)
        stroke.shader = null
    }

    private fun drawRing(canvas: Canvas) {
        val n = shown.size
        if (n == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val minDim = min(width, height).toFloat()
        val baseR = minDim * 0.16f
        val maxLen = minDim * sizeFraction * 0.7f
        stroke.strokeWidth = max(2f * density, (PI * baseR / n).toFloat() * 0.7f)
        for (i in 0 until n) {
            val len = shown[i] * maxLen
            if (len < density) continue
            stroke.color = color(i, n)
            val t = PI * (i + 0.5) / n
            for (a in doubleArrayOf(-PI / 2 + t, -PI / 2 - t)) { // mirror left/right, bass at the top
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                canvas.drawLine(cx + c * baseR, cy + s * baseR, cx + c * (baseR + len), cy + s * (baseR + len), stroke)
            }
        }
    }
}
