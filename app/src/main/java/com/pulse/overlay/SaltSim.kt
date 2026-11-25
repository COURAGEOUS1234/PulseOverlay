package com.pulse.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Salt on a speaker": a fixed set of grains resting on an invisible vibrating surface along
 * the bottom edge.
 *
 * - Fixed population: grains are created once, at rest on the bottom. Nothing spawns, despawns
 *   or teleports while running. Changing the count or rotating re-lays the whole bed at the bottom.
 * - Surface: [COLUMNS] columns, each driven by the band under it (visual shake only).
 * - Normalised jumps: a column launches its grains on the surface's upswing, and the apex height
 *   is proportional to that band's amplitude: amplitude 1.0 → the full Size height. Bass and
 *   treble at equal loudness jump equally high. Bass onsets add +25%.
 * - Flight: gravity + light drag, no ceiling. Rebound height = bounciness² × fall height; grains
 *   settle when slow.
 * - Piles: per-column heightfield from resting-grain counts. Resting grains sink from the top of
 *   their heap toward the bottom over time and slump toward much lower neighbours.
 * - Colour: one horizontal gradient shader across the screen, so each grain's colour follows its
 *   exact x position (smooth blend, no bands). Three brightness variants = three draw calls.
 */
class SaltSim(private val density: Float) {

    // ---- settings (applied live)
    var grainPx = 3f
    var bounciness = 0.45f
    var heaviness = 1f
    var sensitivity = 1f
    var targetCount = 20_000
        set(v) { field = v.coerceIn(MIN_GRAINS, MAX_GRAINS) }

    // ---- grain state (exactly [count] grains)
    private var count = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var depth = FloatArray(0)      // resting only: 0 = top of heap, 1 = bottom
    private var resting = BooleanArray(0)
    private var shade = ByteArray(0)       // 0..2 brightness variant

    // ---- surface / piles
    private val surfaceY = FloatArray(COLUMNS)   // displacement (px, + = up)
    private val surfaceV = FloatArray(COLUMNS)   // velocity (px/s, + = up)
    private val energy = FloatArray(COLUMNS)     // band amplitude × sensitivity (unclamped)
    private val restCount = IntArray(COLUMNS)
    private val pileH = FloatArray(COLUMNS)
    private val phase = FloatArray(COLUMNS) { (it * 2.399f) % (2f * PI.toFloat()) }
    private val wasUp = BooleanArray(COLUMNS)
    private val fire = BooleanArray(COLUMNS)

    private var w = 0f
    private var h = 0f
    private var time = 0f
    private var lastBeat = 0f
    private var rng = 0x9E3779B9.toInt()

    // ---- drawing (grouped by shade only; colour comes from the gradient shader)
    private var pts = Array(3) { FloatArray(0) }
    private var lines = Array(3) { FloatArray(0) }
    private val ptN = IntArray(3)
    private val lineN = IntArray(3)
    private val dotPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG) }
    private val streakPaints = Array(3) { Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND } }

    private fun rand(): Float { // xorshift32 → [0,1)
        var s = rng
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        rng = s
        return (s ushr 8) * (1f / 16_777_216f)
    }

    /** Lays [n] grains at rest along the bottom. The only place grains are ever created. */
    private fun layBed(n: Int) {
        count = n
        x = FloatArray(n) { rand() * w * 0.9999f }
        y = FloatArray(n) { h }
        vx = FloatArray(n)
        vy = FloatArray(n)
        depth = FloatArray(n) { rand() }
        resting = BooleanArray(n) { true }
        shade = ByteArray(n) { (rand() * 3f).toInt().coerceAtMost(2).toByte() }
        pts = Array(3) { FloatArray(n * 2) }
        lines = Array(3) { FloatArray(n * 4) }
    }

    private fun column(px: Float) = ((px / w) * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)

    fun step(width: Int, height: Int, dt: Float, bands: FloatArray, beat: Float, maxHeightFrac: Float) {
        if (width == 0 || height == 0 || bands.isEmpty()) return
        if (width.toFloat() != w || height.toFloat() != h || count != targetCount) {
            w = width.toFloat(); h = height.toFloat()
            layBed(targetCount)
        }
        time += dt

        val g = 3000f * density * heaviness
        val fullJump = h * maxHeightFrac              // apex for amplitude 1.0
        val colW = w / COLUMNS
        val areaPerGrain = grainPx * grainPx * 0.9f / colW

        // 1. Surface shake per column (visual only; launch height is normalised below).
        val n = bands.size
        for (c in 0 until COLUMNS) {
            val t = (c + 0.5f) / COLUMNS
            val e = bands[(t * (n - 1)).toInt()] * sensitivity
            energy[c] = e
            val f = 5f + 13f * t                       // Hz: bass slow, treble fast
            val amp = (1f - 0.6f * t) * 6f * density * e
            val om = 2f * PI.toFloat() * f
            val p = om * time + phase[c]
            surfaceY[c] = amp * sin(p)
            surfaceV[c] = amp * om * cos(p)
            // Fire once per cycle on the upswing.
            val up = cos(p) > 0f
            fire[c] = up && !wasUp[c] && e > LAUNCH_THRESHOLD
            wasUp[c] = up
        }

        // 2. Pile heightfield from current resting grains.
        restCount.fill(0)
        for (i in 0 until count) if (resting[i]) restCount[column(x[i])]++
        for (c in 0 until COLUMNS) pileH[c] = restCount[c] * areaPerGrain

        val kick = beat > 0.9f && lastBeat <= 0.9f
        lastBeat = beat
        val drag = 1f - 0.4f * dt
        val restSpeed = 60f * density

        for (i in 0 until count) {
            val c = column(x[i])
            if (resting[i]) {
                val e = energy[c]
                val launch = (fire[c] && rand() < min(1f, (e - LAUNCH_THRESHOLD) * 2.5f)) ||
                    (kick && e > LAUNCH_THRESHOLD && rand() < 0.8f)
                if (launch) {
                    // Normalised: apex = amplitude × full Size height (+25% on a beat).
                    var apex = e * fullJump * (0.7f + 0.35f * rand())
                    if (kick) apex *= 1.25f
                    val up = sqrt(2f * g * apex)
                    y[i] = h - surfaceY[c] - pileH[c]  // leave from the top of the heap
                    vy[i] = -up
                    vx[i] = (rand() * 2f - 1f) * up * 0.22f
                    resting[i] = false
                } else {
                    depth[i] = min(1f, depth[i] + dt * 0.15f) // sink into the heap over time
                    y[i] = h - surfaceY[c] - pileH[c] * (1f - depth[i])
                    // Angle of repose: slump toward a much lower neighbour.
                    if (rand() < 0.03f) {
                        val left = if (c > 0) pileH[c - 1] else Float.MAX_VALUE
                        val right = if (c < COLUMNS - 1) pileH[c + 1] else Float.MAX_VALUE
                        if (pileH[c] - min(left, right) > grainPx * 2.5f) {
                            x[i] = (x[i] + if (left < right) -colW else colW).coerceIn(0f, w - 0.01f)
                        }
                    }
                }
                continue
            }

            // Flight: gravity, drag, side walls. No ceiling.
            vy[i] += g * dt
            vx[i] *= drag
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            if (x[i] < 0f) { x[i] = -x[i]; vx[i] = -vx[i] * 0.5f }
            if (x[i] >= w) { x[i] = (2f * w - x[i]).coerceIn(0f, w - 0.01f); vx[i] = -vx[i] * 0.5f }

            val cc = column(x[i])
            val ground = h - surfaceY[cc] - pileH[cc]
            if (y[i] >= ground && vy[i] > 0f) {
                y[i] = ground
                if (vy[i] > restSpeed) {
                    vy[i] = -vy[i] * bounciness * (0.85f + 0.3f * rand()) // rebound ∝ fall speed
                    vx[i] = vx[i] * 0.6f + (rand() - 0.5f) * abs(vy[i]) * 0.1f
                } else {
                    resting[i] = true
                    depth[i] = rand() * 0.2f // lands on top of the heap, then sinks
                    vx[i] = 0f; vy[i] = 0f
                }
            }
        }
    }

    fun draw(canvas: Canvas, colorAt: (Float) -> Int, mono: Boolean) {
        if (count == 0 || w == 0f) return
        ptN.fill(0); lineN.fill(0)
        val fast2 = (700f * density).let { it * it }
        val streak = 0.012f
        for (i in 0 until count) {
            val s = shade[i].toInt()
            val p = pts[s]
            var k = ptN[s]
            p[k] = x[i]; p[k + 1] = y[i]
            ptN[s] = k + 2
            if (!resting[i] && vx[i] * vx[i] + vy[i] * vy[i] > fast2) {
                val l = lines[s]
                k = lineN[s]
                l[k] = x[i]; l[k + 1] = y[i]
                l[k + 2] = x[i] - vx[i] * streak; l[k + 3] = y[i] - vy[i] * streak
                lineN[s] = k + 4
            }
        }

        // Smooth gradient across the full width, one shader per brightness variant.
        val stops = IntArray(GRADIENT_STOPS) { if (mono) SALT_WHITE else colorAt(it / (GRADIENT_STOPS - 1f)) }
        for (s in 0 until 3) {
            val f = SHADES[s]
            val colors = IntArray(GRADIENT_STOPS) { scale(stops[it], f) }
            val dot = dotPaints[s]
            dot.shader = LinearGradient(0f, 0f, w, 0f, colors, null, Shader.TileMode.CLAMP)
            dot.strokeWidth = grainPx
            dot.strokeCap = if (grainPx >= 3f) Paint.Cap.ROUND else Paint.Cap.SQUARE
            if (ptN[s] > 0) canvas.drawPoints(pts[s], 0, ptN[s], dot)
            if (lineN[s] > 0) {
                val st = streakPaints[s]
                st.shader = dot.shader
                st.alpha = 0x90
                st.strokeWidth = max(1f, grainPx * 0.8f)
                canvas.drawLines(lines[s], 0, lineN[s], st)
            }
        }
    }

    private fun scale(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    companion object {
        const val MIN_GRAINS = 4_000
        const val MAX_GRAINS = 30_000
        private const val COLUMNS = 160
        private const val GRADIENT_STOPS = 7
        private const val LAUNCH_THRESHOLD = 0.06f
        private val SHADES = floatArrayOf(1f, 0.82f, 0.66f)
        private val SALT_WHITE = Color.rgb(245, 243, 236)
    }
}
