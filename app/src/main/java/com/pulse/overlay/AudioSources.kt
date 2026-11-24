package com.pulse.overlay

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.audiofx.Visualizer
import android.media.projection.MediaProjection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Receives one analysis frame: linear magnitudes per FFT bin, Hz per bin, and time-domain samples (-1..1). */
fun interface FrameSink {
    fun onFrame(magnitudes: FloatArray, binHz: Float, waveform: FloatArray)
}

interface AudioSource {
    /** Throws if the source can't start (unsupported device, missing permission). */
    fun start(sink: FrameSink)
    fun stop()
}

/**
 * Captures what other apps are playing via AudioPlaybackCapture (Android 10+).
 * Apps that set allowAudioPlaybackCapture="false", and voice-call audio, come through as silence.
 */
class PlaybackCaptureSource(private val projection: MediaProjection) : AudioSource {
    private val sampleRate = 44_100
    private val fftSize = 2048
    private val hop = 512 // ≈11.6 ms of new audio per frame → ~86 analysis frames/s

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // RECORD_AUDIO is checked in MainActivity before the service starts
    override fun start(sink: FrameSink) {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minBuf, hop * 2 * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("AudioRecord failed to initialise for playback capture")
        }
        rec.startRecording()
        record = rec
        running = true
        thread = Thread({ loop(rec, sink) }, "pulse-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun loop(rec: AudioRecord, sink: FrameSink) {
        val pcm = ShortArray(hop)
        val ring = FloatArray(fftSize)
        val fft = Fft(fftSize)
        val binHz = sampleRate.toFloat() / fftSize
        while (running) {
            var read = 0
            while (read < hop && running) {
                val n = rec.read(pcm, read, hop - read)
                if (n < 0) return // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT: recorder stopped
                read += n
            }
            if (!running) return
            System.arraycopy(ring, hop, ring, 0, fftSize - hop)
            for (i in 0 until hop) ring[fftSize - hop + i] = pcm[i] / 32768f
            sink.onFrame(fft.magnitudes(ring), binHz, ring)
        }
    }

    override fun stop() {
        running = false
        record?.let { runCatching { it.stop() } }
        thread?.join(500)
        record?.release()
        record = null
        thread = null
    }
}

/**
 * Legacy route: Visualizer attached to the global output mix (session 0).
 * No consent prompt, but deprecated for session 0 — some ROMs return silence or refuse to create it.
 */
class GlobalVisualizerSource : AudioSource {
    private var visualizer: Visualizer? = null

    override fun start(sink: FrameSink) {
        val v = Visualizer(0) // throws RuntimeException / UnsupportedOperationException when unavailable
        try {
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                private var wave = FloatArray(0)

                override fun onWaveFormDataCapture(vis: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    // 8-bit unsigned PCM
                    wave = FloatArray(waveform.size) { ((waveform[it].toInt() and 0xFF) - 128) / 128f }
                }

                override fun onFftDataCapture(vis: Visualizer, fft: ByteArray, samplingRate: Int) {
                    val n = fft.size
                    val mags = FloatArray(n / 2)
                    mags[0] = abs(fft[0].toFloat()) / 128f
                    for (k in 1 until n / 2) {
                        mags[k] = hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat()) / 128f
                    }
                    val binHz = samplingRate / 1000f / n // samplingRate is in milliHertz
                    sink.onFrame(mags, binHz, wave)
                }
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
        } catch (e: RuntimeException) {
            v.release()
            throw e
        }
        visualizer = v
    }

    override fun stop() {
        visualizer?.let {
            runCatching { it.enabled = false }
            it.release()
        }
        visualizer = null
    }
}
