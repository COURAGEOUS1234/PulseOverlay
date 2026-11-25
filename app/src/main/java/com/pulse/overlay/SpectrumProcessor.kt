package com.pulse.overlay

import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns raw FFT frames into log-spaced, auto-gained, smoothed bands in 0..1.
 * Written from the audio thread, read from the UI thread via volatile array swaps.
 */
class SpectrumProcessor(private val bandCount: Int = 64) : FrameSink {

    @Volatile var bands: FloatArray = FloatArray(bandCount)
        private set

    @Volatile var wave: FloatArray = FloatArray(WAVE_POINTS)
        private set

    /** 0..1 envelope that jumps to 1 on a detected bass hit and decays over ~0.2 s. */
    @Volatile var beat = 0f
        private set

    /** Mean band level 0..1. */
    @Volatile var level = 0f
        private set

    private var bassAvg = 0f
    private var lastBeatNanos = 0L
    private val bassBands: Int

    private val smooth = FloatArray(bandCount)
    private val raw = FloatArray(bandCount)
    private var peakDb = PEAK_FLOOR_DB
    private var lastNanos = 0L

    // Band edges and per-band treble tilt, precomputed.
    private val edges = FloatArray(bandCount + 1) {
        MIN_HZ * (MAX_HZ / MIN_HZ).pow(it.toFloat() / bandCount)
    }
    private val tilt = FloatArray(bandCount) {
        val centre = sqrt(edges[it] * edges[it + 1])
        TILT_DB_PER_OCTAVE * log2(centre / MIN_HZ)
    }

    init {
        bassBands = (0 until bandCount).count { edges[it + 1] <= BASS_HZ }.coerceAtLeast(1)
    }

    override fun onFrame(magnitudes: FloatArray, binHz: Float, waveform: FloatArray) {
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.2f)
        lastNanos = now

        val maxBin = magnitudes.size - 1
        if (maxBin < 1 || binHz <= 0f) return
        var frameMax = -120f
        for (b in 0 until bandCount) {
            val lo = (edges[b] / binHz).toInt().coerceIn(1, maxBin)
            val hi = (edges[b + 1] / binHz).toInt().coerceIn(lo, maxBin)
            var m = 0f
            for (k in lo..hi) m = max(m, magnitudes[k])
            val db = 20f * log10(m + 1e-7f) + tilt[b]
            raw[b] = db
            frameMax = max(frameMax, db)
        }

        // Auto-gain: follow loud peaks instantly, release slowly; never below the floor so silence stays dark.
        peakDb = max(frameMax, peakDb - PEAK_RELEASE_DB_PER_SEC * dt).coerceAtLeast(PEAK_FLOOR_DB)
        val bottom = peakDb - RANGE_DB

        val attack = 1f - exp(-dt / ATTACK_SEC)
        val decay = DECAY_PER_SEC * dt
        for (b in 0 until bandCount) {
            val v = ((raw[b] - bottom) / RANGE_DB).coerceIn(0f, 1f)
            val s = smooth[b]
            smooth[b] = if (v > s) s + (v - s) * attack else max(v, s - decay)
        }
        bands = smooth.copyOf()

        // Beat: bass jumps clearly above its own recent average (spectral-flux style onset).
        var bass = 0f
        for (b in 0 until bassBands) bass += smooth[b]
        bass /= bassBands
        bassAvg += (bass - bassAvg) * (1f - exp(-dt / BASS_AVG_SEC))
        var env = beat * exp(-dt / BEAT_DECAY_SEC)
        if (bass - bassAvg > BEAT_JUMP && bass > 0.3f && now - lastBeatNanos > BEAT_GAP_NANOS) {
            env = 1f
            lastBeatNanos = now
        }
        beat = env
        level = smooth.average().toFloat()

        if (waveform.isNotEmpty()) {
            val step = waveform.size.toFloat() / WAVE_POINTS
            wave = FloatArray(WAVE_POINTS) { waveform[(it * step).toInt().coerceAtMost(waveform.size - 1)] }
        }
    }

    companion object {
        const val WAVE_POINTS = 128
        private const val BASS_HZ = 160f
        private const val BASS_AVG_SEC = 0.45f
        private const val BEAT_DECAY_SEC = 0.18f
        private const val BEAT_JUMP = 0.12f
        private const val BEAT_GAP_NANOS = 150_000_000L
        private const val MIN_HZ = 40f
        private const val MAX_HZ = 16_000f
        private const val RANGE_DB = 42f
        private const val PEAK_FLOOR_DB = -38f
        private const val PEAK_RELEASE_DB_PER_SEC = 6f
        private const val TILT_DB_PER_OCTAVE = 3f
        private const val ATTACK_SEC = 0.025f
        private const val DECAY_PER_SEC = 1.8f
    }
}
