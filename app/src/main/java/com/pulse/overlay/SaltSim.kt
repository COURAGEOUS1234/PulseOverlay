package com.pulse.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Salt on a speaker": grains resting on an invisible vibrating surface along the bottom edge.
 *
 * - Surface: [COLUMNS] columns, each driven by the band under it. Bass = big slow motion,
 *   treble = small fast ripple (visual frequencies, since audio rates can't be drawn at 60 fps).
 * - Lift-off: a resting grain launches only when the surface's upward acceleration beats gravity,
 *   with the surface's velocity plus random scatter. Bass onsets add an extra kick.
 * - Flight: gravity + light drag, bounce with restitution on the pile/surface, settle when slow.
 * - Piles: per-column heightfield from resting-grain counts. Each resting grain keeps a random
 *   depth fraction inside its column's pile, so heaps grow/shrink smoothly with no per-grain
 *   stacking; grains slump toward lower neighbours (angle of repose).
 *
 * No grain-grain collisions (v1). All state is flat float arrays; ~1–2 ms/frame at 20k grains.
 */
class SaltSim(private val density: Float) {

    // ---- settings (applied live)
    var grainPx = 3f
    var bounciness = 0.45f
    var heaviness = 1f
    var sensitivity = 1f
    var targetCount = 20_000
        set(v) { field = v.coerceIn(MIN_GRAINS, MAX_GRAINS) }

    // ---- grain state
    private var cap = 0
    private var active = 0
    private var x = FloatArray(0)
    private var y = FloatArray(0)
    private var vx = FloatArray(0)
    private var vy = FloatArray(0)
    private var depth = FloatArray(0)      // 0..1 position inside the pile while resting
    private var resting = BooleanArray(0)
    private var shade = ByteArray(0)       // 0..2 brightness variant

    // ---- surface / piles
    private val surfaceY = FloatArray(COLUMNS)   // displacement (px, + = up)
    private val surfaceV = FloatArray(COLUMNS)   // velocity (px/s, + = up)
    private val surfaceA = FloatArray(COLUMNS)   // acceleration (px/s², + = up)
    private val restCount = IntArray(COLUMNS)
    private val pileH = FloatArray(COLUMNS)
    private val phase = FloatArray(COLUMNS) { (it * 2.399f) % (2f * PI.toFloat()) } // golden-angle spread

    private var w = 0f
    private var h = 0f
    private var time = 0f
    private var lastBeat = 0f
    private var rng = 0x9E3779B9.toInt()

    // ---- adaptive count
    private var slowTime = 0f
    private var goodTime = 0f

    // ---- drawing buffers (bucketed by colour so each bucket is one drawPoints call)
    private var pts = FloatArray(0)
    private var lines = FloatArray(0)
    private val bucketOf = IntArray(MAX_GRAINS)
    private val counts = IntArray(BUCKETS * 3)
    private val lineCounts = IntArray(BUCKETS * 3)
    private val starts = IntArray(BUCKETS * 3)
    private val lineStarts = IntArray(BUCKETS * 3)
    private val fill = IntArray(BUCKETS * 3)
    private val lineFill = IntArray(BUCKETS * 3)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    private fun rand(): Float { // xorshift32 → [0,1)
        var s = rng
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        rng = s
        return (s ushr 8) * (1f / 16_777_216f)
    }

    private fun ensureCapacity(n: Int) {
        if (n <= cap) return
        fun FloatArray.grow() = copyOf(n)
        x = x.grow(); y = y.grow(); vx = vx.grow(); vy = vy.grow(); depth = depth.grow()
        resting = resting.copyOf(n); shade = shade.copyOf(n)
        for (i in cap until n) seedGrain(i)
        cap = n
        pts = FloatArray(n * 2)
        lines = FloatArray(n * 4)
    }

    private fun seedGrain(i: Int) {
        x[i] = rand() * max(w, 1f)
        y[i] = h
        vx[i] = 0f; vy[i] = 0f
        depth[i] = rand()
        resting[i] = true
        shade[i] = (rand() * 3f).toInt().coerceAtMost(2).toByte()
    }

    private fun column(px: Float) = ((px / w) * COLUMNS).toInt().coerceIn(0, COLUMNS - 1)

    /** Top of the bed (screen y, down = +) at column c. */
    private fun groundY(c: Int) = h - surfaceY[c] - pileH[c]

    fun step(width: Int, height: Int, dt: Float, bands: FloatArray, beat: Float, maxHeightFrac: Float) {
        if (width == 0 || bands.isEmpty()) return
        if (width.toFloat() != w || height.toFloat() != h) {
            w = width.toFloat(); h = height.toFloat()
            for (i in 0 until cap) seedGrain(i)
        }
        ensureCapacity(targetCount)
        if (active == 0 || active > targetCount) active = targetCount
        time += dt

        val g = 3000f * density * heaviness
        val maxRise = h * maxHeightFrac
        val vCap = sqrt(2f * g * maxRise)           // apex never above the Size setting
        val colW = w / COLUMNS
        val areaPerGrain = grainPx * grainPx * 0.9f / colW

        // 1. Surface motion per column (analytic oscillator → exact velocity & acceleration).
        val n = bands.size
        for (c in 0 until COLUMNS) {
            val t = (c + 0.5f) / COLUMNS
            val e = bands[(t * (n - 1)).toInt()].pow(1.3f) * sensitivity
            val f = 5f + 13f * t                                  // Hz: bass slow, treble fast
            val amp = (1f - 0.75f * t) * 22f * density * e          // px: bass moves further
            val om = 2f * PI.toFloat() * f
            val p = om * time + phase[c]
            surfaceY[c] = amp * sin(p)
            surfaceV[c] = amp * om * cos(p)
            surfaceA[c] = -amp * om * om * sin(p)
        }

        // 2. Pile heightfield from last frame's resting grains.
        restCount.fill(0)
        for (i in 0 until active) if (resting[i]) restCount[column(x[i])]++
        for (c in 0 until COLUMNS) pileH[c] = restCount[c] * areaPerGrain

        val kick = beat > 0.9f && lastBeat <= 0.9f
        lastBeat = beat
        val drag = 1f - 0.4f * dt
        val restSpeed = 60f * density

        for (i in 0 until active) {
            val c = column(x[i])
            if (resting[i]) {
                val ground = groundY(c)
                // Lift-off rule: surface pushes up harder than gravity (or a beat kick).
                val over = surfaceA[c] / g - 1f
                val launch = (over > 0f && rand() < min(1f, over * 0.6f)) || (kick && rand() < 0.85f)
                if (launch) {
                    var up = max(surfaceV[c], 0f) * (0.8f + 0.6f * rand())
                    if (kick) up += (250f + 650f * rand()) * density * sensitivity
                    up = min(up + 30f * density * rand(), vCap)
                    vy[i] = -up
                    vx[i] = (rand() * 2f - 1f) * up * 0.28f
                    y[i] = ground // leave from the pile surface
                    resting[i] = false
                } else {
                    depth[i] = min(1f, depth[i] + dt * 0.15f) // settle into the heap over time
                    y[i] = h - surfaceY[c] - pileH[c] * depth[i]
                    // Angle of repose: slump toward a much lower neighbour.
                    if (rand() < 0.03f) {
                        val left = if (c > 0) pileH[c - 1] else Float.MAX_VALUE
                        val right = if (c < COLUMNS - 1) pileH[c + 1] else Float.MAX_VALUE
                        val lower = min(left, right)
                        if (pileH[c] - lower > grainPx * 2.5f) x[i] += if (left < right) -colW else colW
                    }
                    // Quiet shiver while the surface moves but can't throw.
                    if (abs(surfaceV[c]) > 1f) x[i] += (rand() - 0.5f) * 0.6f
                }
                continue
            }

            // Flight.
            vy[i] += g * dt
            vx[i] *= drag
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            if (x[i] < 0f) { x[i] = -x[i]; vx[i] = -vx[i] * 0.5f }
            if (x[i] > w) { x[i] = 2f * w - x[i]; vx[i] = -vx[i] * 0.5f }
            if (y[i] < h - maxRise * 1.05f) { y[i] = h - maxRise * 1.05f; vy[i] = abs(vy[i]) * 0.3f }

            val cc = column(x[i])
            val ground = groundY(cc)
            if (y[i] >= ground && vy[i] > -surfaceV[cc]) {
                val rel = vy[i] + surfaceV[cc] // impact speed relative to the moving surface
                if (rel > restSpeed) {
                    y[i] = ground
                    vy[i] = -rel * bounciness * (0.75f + 0.5f * rand()) - surfaceV[cc]
                    vx[i] = vx[i] * 0.6f + (rand() - 0.5f) * rel * 0.15f
                } else {
                    resting[i] = true
                    depth[i] = rand() * 0.25f // lands near the top of the pile
                    vx[i] = 0f; vy[i] = 0f
                }
            }
        }

        // 3. Keep the frame rate: shed grains if frames are consistently slow, recover slowly.
        if (dt > 1f / 52f) { slowTime += dt; goodTime = 0f } else { slowTime = max(0f, slowTime - dt * 0.25f); goodTime += dt }
        if (slowTime > 1.5f && active > MIN_GRAINS) {
            active = max(MIN_GRAINS, (active * 0.85f).toInt())
            slowTime = 0f
            goodTime = 0f
        } else if (active < targetCount && goodTime > 8f) {
            active = min(targetCount, (active * 1.1f).toInt() + 1) // try a little more after 8 s smooth
            goodTime = 0f
        }
    }

    fun draw(canvas: Canvas, colorAt: (Float) -> Int, mono: Boolean) {
        if (active == 0 || w == 0f) return
        // Bucket grains by (x colour band, shade); streaks for fast grains.
        counts.fill(0); lineCounts.fill(0)
        val fast2 = (700f * density).let { it * it }
        for (i in 0 until active) {
            val b = ((x[i] / w) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1) * 3 + shade[i]
            bucketOf[i] = b
            counts[b]++
            if (!resting[i] && vx[i] * vx[i] + vy[i] * vy[i] > fast2) lineCounts[b]++
        }
        var acc = 0; var lacc = 0
        for (b in counts.indices) {
            starts[b] = acc; fill[b] = acc; acc += counts[b] * 2
            lineStarts[b] = lacc; lineFill[b] = lacc; lacc += lineCounts[b] * 4
        }
        val streak = 0.012f
        for (i in 0 until active) {
            val b = bucketOf[i]
            var k = fill[b]
            pts[k] = x[i]; pts[k + 1] = y[i]; fill[b] = k + 2
            if (!resting[i] && vx[i] * vx[i] + vy[i] * vy[i] > fast2) {
                k = lineFill[b]
                lines[k] = x[i]; lines[k + 1] = y[i]
                lines[k + 2] = x[i] - vx[i] * streak; lines[k + 3] = y[i] - vy[i] * streak
                lineFill[b] = k + 4
            }
        }

        dotPaint.strokeWidth = grainPx
        dotPaint.strokeCap = if (grainPx >= 3f) Paint.Cap.ROUND else Paint.Cap.SQUARE
        streakPaint.strokeWidth = max(1f, grainPx * 0.8f)
        for (b in counts.indices) {
            if (counts[b] == 0) continue
            val base = if (mono) SALT_WHITE else colorAt((b / 3) / (BUCKETS - 1f))
            val c = shaded(base, b % 3)
            dotPaint.color = c
            canvas.drawPoints(pts, starts[b], counts[b] * 2, dotPaint)
            if (lineCounts[b] > 0) {
                streakPaint.color = (c and 0x00FFFFFF) or (0x90 shl 24)
                canvas.drawLines(lines, lineStarts[b], lineCounts[b] * 4, streakPaint)
            }
        }
    }

    /** Slight brightness variation so the bed reads as uneven crystals, not a flat colour. */
    private fun shaded(c: Int, s: Int): Int {
        val f = when (s) { 0 -> 1f; 1 -> 0.82f; else -> 0.66f }
        return Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
    }

    companion object {
        const val MIN_GRAINS = 4_000
        const val MAX_GRAINS = 30_000
        private const val COLUMNS = 160
        private const val BUCKETS = 8
        private val SALT_WHITE = Color.rgb(245, 243, 236)
    }
}
