package com.pulse.overlay

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Full-screen, non-interactive drawing surface. Redraws every vsync while attached. */
@SuppressLint("ViewConstructor")
class OverlayView(context: Context, private val processor: SpectrumProcessor) : View(context) {

    var style: VizStyle = VizStyle.BARS
    var sizeFraction: Float = 0.3f

    /** Album-art palette ([ArtPalette.SIZE] stops) or null for the shifting rainbow. */
    @Volatile var albumPalette: IntArray? = null

    /** Live screen copy for real glass blur; null → frosted fallback. */
    @Volatile var backdrop: ScreenBackdrop? = null

    private val density = resources.displayMetrics.density
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
    private var lastNanos = 0L
    private var time = 0f

    // Palette easing: stops glide to the new album's colours over ~0.5 s.
    private val pal = IntArray(ArtPalette.SIZE)
    private var palActive = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        time += dt

        // Render-side easing so the 20 Hz Visualizer source still animates smoothly at 60/120 Hz.
        val target = processor.bands
        if (shown.size != target.size) shown = FloatArray(target.size)
        for (i in shown.indices) shown[i] += (target[i] - shown[i]) * 0.35f
        val w = processor.wave
        if (w.size == shownWave.size) for (i in w.indices) shownWave[i] += (w[i] - shownWave[i]) * 0.5f
        hueShift = (hueShift + 0.25f) % 360f
        updatePalette()

        when (style) {
            VizStyle.BARS -> drawBars(canvas, mirrored = false)
            VizStyle.MIRROR -> drawBars(canvas, mirrored = true)
            VizStyle.WAVE -> drawWave(canvas)
            VizStyle.RING -> drawRing(canvas)
            VizStyle.GLASS -> drawGlass(canvas)
            VizStyle.MATTER -> drawMatter(canvas, dt)
        }
        postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- colour

    private fun updatePalette() {
        val target = albumPalette
        if (target == null || target.size != pal.size) {
            palActive = false
            return
        }
        if (!palActive) {
            target.copyInto(pal)
            palActive = true
        } else {
            for (k in pal.indices) pal[k] = lerpColor(pal[k], target[k], 0.06f)
        }
    }

    /** Colour at position t ∈ 0..1 across the visual. */
    private fun colorAt(t: Float): Int {
        if (palActive) {
            val pos = t.coerceIn(0f, 1f) * (pal.size - 1)
            val i = floor(pos).toInt().coerceAtMost(pal.size - 2)
            return lerpColor(pal[i], pal[i + 1], pos - i)
        }
        hsv[0] = (hueShift + 280f * t) % 360f
        return Color.HSVToColor(hsv)
    }

    private fun color(i: Int, n: Int) = colorAt(i.toFloat() / max(1, n - 1))

    private fun gradientColors(stops: Int, towardWhite: Float = 0f) = IntArray(stops) {
        lerpColor(colorAt(it.toFloat() / (stops - 1)), Color.WHITE, towardWhite)
    }

    // ---------------------------------------------------------------- classic styles

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
        stroke.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, gradientColors(3), null, Shader.TileMode.CLAMP)
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

    // ---------------------------------------------------------------- liquid glass

    private val glassRects = Array(GLASS_BARS) { RectF() }
    private val glassOn = BooleanArray(GLASS_BARS)
    private val glassPath = Path()
    private val sheenMatrix = Matrix()
    private val sheenShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(Color.argb(150, 255, 255, 255), Color.argb(30, 255, 255, 255), Color.TRANSPARENT),
        floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP,
    )
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var glassNode: RenderNode? = null

    private fun drawGlass(canvas: Canvas) {
        val n = shown.size
        if (n == 0) return
        val h = height.toFloat()
        val maxH = h * sizeFraction
        val slot = width.toFloat() / GLASS_BARS
        val barW = slot * 0.78f
        val rad = barW / 2f
        val lift = processor.beat * 6f * density // whole row hops slightly on the beat

        glassPath.reset()
        for (g in 0 until GLASS_BARS) {
            val from = g * n / GLASS_BARS
            val to = max(from + 1, (g + 1) * n / GLASS_BARS)
            var e = 0f
            for (k in from until to) e += shown[k]
            e /= (to - from)
            val bh = e * maxH + lift
            glassOn[g] = bh > 3f * density
            if (!glassOn[g]) continue
            val l = g * slot + (slot - barW) / 2f
            glassRects[g].set(l, h - max(bh, barW), l + barW, h + rad)
            glassPath.addRoundRect(glassRects[g], rad, rad, Path.Direction.CW)
        }
        if (glassPath.isEmpty) return

        // 1. What's behind, blurred and slightly saturated, only inside the bar shapes.
        val blurred = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated && drawBlurredBackdrop(canvas)
        if (!blurred) {
            fill.color = Color.argb(60, 255, 255, 255) // frosted fallback: no access to the pixels behind
            canvas.drawPath(glassPath, fill)
        }

        // 2. Tint, sheen, rim and specular highlight per bar.
        stroke.strokeWidth = 1.2f * density
        stroke.color = Color.argb(110, 255, 255, 255)
        for (g in 0 until GLASS_BARS) {
            if (!glassOn[g]) continue
            val r = glassRects[g]
            val c = color(g, GLASS_BARS)
            fill.color = Color.argb(if (blurred) 70 else 110, Color.red(c), Color.green(c), Color.blue(c))
            canvas.drawRoundRect(r, rad, rad, fill)

            sheenMatrix.setScale(1f, min(r.height(), 140f * density))
            sheenMatrix.postTranslate(0f, r.top)
            sheenShader.setLocalMatrix(sheenMatrix)
            fill.shader = sheenShader
            canvas.drawRoundRect(r, rad, rad, fill)
            fill.shader = null

            canvas.drawRoundRect(r, rad, rad, stroke)

            fill.color = Color.argb(170, 255, 255, 255)
            val sx = r.left + barW * 0.22f
            val sy = r.top + rad * 0.45f
            canvas.drawRoundRect(sx, sy, sx + barW * 0.16f, sy + min(r.height() * 0.3f, 28f * density),
                barW * 0.08f, barW * 0.08f, fill)
        }
    }

    @TargetApi(31)
    private fun drawBlurredBackdrop(canvas: Canvas): Boolean {
        val bd = backdrop ?: return false
        val bmp = bd.frame ?: return false
        val node = glassNode ?: RenderNode("glass").also { glassNode = it }
        if (node.width != width || node.height != height) {
            node.setPosition(0, 0, width, height)
            val sat = ColorMatrix().apply { setSaturation(1.35f) }
            node.setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(sat)),
                    RenderEffect.createBlurEffect(18f * density, 18f * density, Shader.TileMode.CLAMP),
                )
            )
        }
        srcRect.set(0, 0, bd.frameWidth, bd.frameHeight)
        dstRect.set(0f, 0f, width.toFloat(), height.toFloat())
        val rc = node.beginRecording()
        rc.drawBitmap(bmp, srcRect, dstRect, null)
        node.endRecording()
        canvas.save()
        canvas.clipPath(glassPath)
        canvas.drawRenderNode(node)
        canvas.restore()
        return true
    }

    // ---------------------------------------------------------------- morphing matter

    private class Pore(val x: Float, val y: Float, val speed: Float, val size: Float, val phase: Float)

    private val pores = Random(7).let { rnd ->
        List(PORES) { Pore(rnd.nextFloat(), rnd.nextFloat(), 0.02f + rnd.nextFloat() * 0.05f, rnd.nextFloat(), rnd.nextFloat() * 6.28f) }
    }
    private var scroll = 0f
    private var rimNode: RenderNode? = null
    private var coreNode: RenderNode? = null
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.CLEAR }

    /**
     * Gooey metaballs: soft blobs drawn into a RenderNode, blurred, then alpha-thresholded so
     * neighbours fuse and split like liquid. Two layers (pale rim + saturated core) give depth;
     * drifting "pores" punched out before the blur become morphing cells.
     */
    private fun drawMatter(canvas: Canvas, dt: Float) {
        val n = shown.size
        if (n == 0 || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val maxH = h * sizeFraction
        val level = processor.level
        val beat = processor.beat
        var treble = 0f
        for (k in (n * 2 / 3) until n) treble += shown[k]
        treble /= (n - n * 2 / 3)

        scroll = (scroll + dt * (18f + level * 220f + beat * 120f) * density) % (w + 2 * w / MATTER_BLOBS)
        val hw = Build.VERSION.SDK_INT >= 31 && canvas.isHardwareAccelerated
        if (hw) {
            drawMatterLayer(canvas, coreOnly = false, w, h, maxH, level, beat, treble)
            drawMatterLayer(canvas, coreOnly = true, w, h, maxH, level, beat, treble)
        } else {
            // Pre-Android 12: no RenderEffect, so plain soft circles (no fusing).
            matterBlobs(w, h, maxH, beat, 1f) { x, y, r, t ->
                fill.color = colorAt(t)
                fill.alpha = 190
                canvas.drawCircle(x, y, r, fill)
            }
            fill.alpha = 255
        }
    }

    @TargetApi(31)
    private fun drawMatterLayer(
        canvas: Canvas, coreOnly: Boolean, w: Float, h: Float, maxH: Float,
        level: Float, beat: Float, treble: Float,
    ) {
        val node = (if (coreOnly) coreNode else rimNode) ?: RenderNode(if (coreOnly) "core" else "rim").also {
            if (coreOnly) coreNode = it else rimNode = it
        }
        if (node.width != width || node.height != height) {
            node.setPosition(0, 0, width, height)
            // alpha' = 20·α − 9·255 → hard edge around α≈0.45 after the blur = liquid surface
            val threshold = ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f, 0f, 0f,
                0f, 0f, 0f, 20f, -9f * 255f,
            ))
            node.setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(threshold)),
                    RenderEffect.createBlurEffect(14f * density, 14f * density, Shader.TileMode.DECAL),
                )
            )
        }
        val rc = node.beginRecording()
        val scale = if (coreOnly) 0.62f else 1f
        fill.shader = LinearGradient(0f, 0f, w, 0f,
            gradientColors(5, towardWhite = if (coreOnly) 0f else 0.6f), null, Shader.TileMode.CLAMP)
        // A floor keeps the matter anchored to the bottom edge so blobs grow out of one body.
        val floorH = (if (coreOnly) 6f else 14f) * density + level * maxH * 0.18f * scale
        rc.drawRect(-w * 0.1f, h - floorH, w * 1.1f, h + 40f * density, fill)
        matterBlobs(w, h, maxH, beat, scale) { x, y, r, _ -> rc.drawCircle(x, y, r, fill) }
        fill.shader = null

        // Pores drift sideways and breathe with the treble.
        val matterTop = h - maxH * (0.25f + level * 0.75f)
        for (p in pores) {
            if (coreOnly != (p.size < 0.5f)) continue // split pores between the layers
            val px = ((p.x + time * p.speed) % 1f) * w
            val py = matterTop + (h - matterTop) * (0.15f + 0.8f * p.y)
            val pr = (if (coreOnly) 4f else 7f) * density * (1f + 1.6f * treble) *
                (0.7f + 0.3f * sin(time * 2f + p.phase))
            rc.drawCircle(px, py, pr, clearPaint)
        }
        node.endRecording()
        canvas.drawRenderNode(node)
    }

    /** Emits blob circles (x, y, radius, colour position) along the bottom, flowing with [scroll]. */
    private inline fun matterBlobs(
        w: Float, h: Float, maxH: Float, beat: Float, scale: Float,
        emit: (Float, Float, Float, Float) -> Unit,
    ) {
        val n = shown.size
        val slot = w / MATTER_BLOBS
        for (i in 0 until MATTER_BLOBS + 2) {
            var x = ((i * slot + scroll) % (w + 2 * slot)) - slot
            x += sin(time * 0.9f + i * 1.7f) * slot * 0.35f
            val t = (x / w).coerceIn(0f, 1f)
            val e = shown[(t * (n - 1)).toInt()] // energy of the band under this point of the screen
            val r = (slot * 0.42f + e * maxH * 0.42f + beat * slot * 0.35f) * scale
            val y = h - r * 0.3f - e * maxH * 0.38f * scale + sin(time * 1.3f + i) * 3f * density
            emit(x, y, r, t)
        }
    }

    companion object {
        private const val GLASS_BARS = 20
        private const val MATTER_BLOBS = 18
        private const val PORES = 28

        private fun lerpColor(a: Int, b: Int, f: Float): Int {
            val t = f.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
            )
        }
    }
}
