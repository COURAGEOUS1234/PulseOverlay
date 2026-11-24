package com.pulse.overlay

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT with a Hann window. Not thread-safe; one instance per audio thread. */
class Fft(private val n: Int) {
    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
    }

    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val out = FloatArray(n / 2)
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { (-sin(2.0 * PI * it / n)).toFloat() }
    private val rev: IntArray = run {
        val bits = Integer.numberOfTrailingZeros(n)
        IntArray(n) { Integer.reverse(it) ushr (32 - bits) }
    }

    /** Returns n/2 linear magnitudes; a full-scale sine reads ≈ 1.0. The returned array is reused. */
    fun magnitudes(input: FloatArray): FloatArray {
        for (i in 0 until n) {
            re[rev[i]] = input[i] * window[i]
            im[rev[i]] = 0f
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                for (k in 0 until half) {
                    val i = start + k
                    val j = i + half
                    val wr = cosT[k * step]
                    val wi = sinT[k * step]
                    val tr = wr * re[j] - wi * im[j]
                    val ti = wr * im[j] + wi * re[j]
                    re[j] = re[i] - tr
                    im[j] = im[i] - ti
                    re[i] += tr
                    im[i] += ti
                }
                start += size
            }
            size *= 2
        }
        val scale = 4f / n // 2/n for one-sided spectrum, ×2 for Hann coherent gain
        for (k in 0 until n / 2) out[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        return out
    }
}
