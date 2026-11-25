package com.pulse.overlay

import android.content.Context
import android.content.SharedPreferences

enum class SourceMode(val label: String) {
    CAPTURE("Playback capture (Android 10+, most reliable)"),
    GLOBAL_VISUALIZER("Global Visualizer (no consent prompt, device-dependent)"),
}

enum class VizStyle(val label: String) {
    BARS("Bars – bottom"),
    MIRROR("Bars – top + bottom"),
    WAVE("Waveform"),
    RING("Ring – centre"),
    GLASS("Liquid glass bars"),
    MATTER("Morphing matter"),
    SALT("Salt on a speaker"),
}

enum class ColorMode(val label: String) {
    RAINBOW("Shifting rainbow"),
    ALBUM_ART("From album art (needs notification access)"),
    WHITE("Plain white (salt)"),
}

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("pulse", Context.MODE_PRIVATE)

    var mode: SourceMode
        get() = sp.getString(K_MODE, null)
            ?.let { runCatching { SourceMode.valueOf(it) }.getOrNull() } ?: SourceMode.CAPTURE
        set(v) = sp.edit().putString(K_MODE, v.name).apply()

    var style: VizStyle
        get() = sp.getString(K_STYLE, null)
            ?.let { runCatching { VizStyle.valueOf(it) }.getOrNull() } ?: VizStyle.BARS
        set(v) = sp.edit().putString(K_STYLE, v.name).apply()

    var colorMode: ColorMode
        get() = sp.getString(K_COLOR, null)
            ?.let { runCatching { ColorMode.valueOf(it) }.getOrNull() } ?: ColorMode.RAINBOW
        set(v) = sp.edit().putString(K_COLOR, v.name).apply()

    /** Window alpha. Capped at 0.8: above that, Android 12+ blocks touches passing through the overlay. */
    var opacity: Float
        get() = sp.getFloat(K_OPACITY, 0.7f).coerceIn(MIN_OPACITY, MAX_OPACITY)
        set(v) = sp.edit().putFloat(K_OPACITY, v.coerceIn(MIN_OPACITY, MAX_OPACITY)).apply()

    /** Fraction of screen height the visual may occupy. */
    var size: Float
        get() = sp.getFloat(K_SIZE, 0.3f).coerceIn(0.1f, 0.7f)
        set(v) = sp.edit().putFloat(K_SIZE, v.coerceIn(0.1f, 0.7f)).apply()

    // ---- Salt theme
    var grainPx: Float
        get() = sp.getFloat("grain_px", 3f).coerceIn(1f, 8f)
        set(v) = sp.edit().putFloat("grain_px", v.coerceIn(1f, 8f)).apply()

    var grainCount: Int
        get() = sp.getInt("grain_count", 20_000).coerceIn(SaltSim.MIN_GRAINS, SaltSim.MAX_GRAINS)
        set(v) = sp.edit().putInt("grain_count", v.coerceIn(SaltSim.MIN_GRAINS, SaltSim.MAX_GRAINS)).apply()

    var bounciness: Float
        get() = sp.getFloat("bounce", 0.45f).coerceIn(0.1f, 0.8f)
        set(v) = sp.edit().putFloat("bounce", v.coerceIn(0.1f, 0.8f)).apply()

    var heaviness: Float
        get() = sp.getFloat("heavy", 1f).coerceIn(0.4f, 2f)
        set(v) = sp.edit().putFloat("heavy", v.coerceIn(0.4f, 2f)).apply()

    var sensitivity: Float
        get() = sp.getFloat("sens", 1f).coerceIn(0.3f, 3f)
        set(v) = sp.edit().putFloat("sens", v.coerceIn(0.3f, 3f)).apply()

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val MIN_OPACITY = 0.15f
        const val MAX_OPACITY = 0.8f
        private const val K_MODE = "mode"
        private const val K_STYLE = "style"
        private const val K_OPACITY = "opacity"
        private const val K_SIZE = "size"
        private const val K_COLOR = "color"
    }
}
