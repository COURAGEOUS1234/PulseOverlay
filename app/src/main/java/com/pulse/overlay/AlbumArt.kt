package com.pulse.overlay

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Does nothing itself. Being an enabled notification listener is what lets the app read
 * other apps' media sessions (title + album art) through MediaSessionManager.
 */
class PulseNotificationListener : NotificationListenerService()

object ArtPalette {
    const val SIZE = 4
    private const val BUCKETS = 18 // 20° hue buckets

    /** Returns exactly [SIZE] vivid colours sorted by hue, or a monochrome ramp for greyscale art. */
    fun extract(src: Bitmap): IntArray {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val small = Bitmap.createScaledBitmap(soft, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)

        val weight = FloatArray(BUCKETS)
        val sumR = FloatArray(BUCKETS)
        val sumG = FloatArray(BUCKETS)
        val sumB = FloatArray(BUCKETS)
        val hsv = FloatArray(3)
        var lumSum = 0f
        for (c in px) {
            Color.colorToHSV(c, hsv)
            lumSum += hsv[2]
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
            val b = ((hsv[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            val w = hsv[1] * hsv[2]
            weight[b] += w
            sumR[b] += Color.red(c) * w
            sumG[b] += Color.green(c) * w
            sumB[b] += Color.blue(c) * w
        }

        // Pick the heaviest buckets, at least 2 buckets (40°) apart so the gradient has range.
        val order = (0 until BUCKETS).sortedByDescending { weight[it] }
        val picked = mutableListOf<Int>()
        for (b in order) {
            if (weight[b] < 1.5f || picked.size == SIZE) break
            if (picked.none { circDist(it, b) < 2 }) picked += b
        }
        if (picked.isEmpty()) {
            // Greyscale art: luminous silver ramp that still reads on dark and light screens.
            val v = (lumSum / px.size).coerceIn(0.3f, 0.8f)
            return IntArray(SIZE) { Color.HSVToColor(floatArrayOf(0f, 0f, (v + 0.12f * it).coerceAtMost(1f))) }
        }

        val colors = picked.sorted().map { b ->
            val w = weight[b]
            val c = Color.rgb((sumR[b] / w).toInt(), (sumG[b] / w).toInt(), (sumB[b] / w).toInt())
            Color.colorToHSV(c, hsv)
            hsv[1] = max(hsv[1], 0.55f) // keep it vivid against busy backgrounds
            hsv[2] = max(hsv[2], 0.85f)
            Color.HSVToColor(hsv)
        }
        // Resample to exactly SIZE stops so the overlay can lerp stop-by-stop between songs.
        return IntArray(SIZE) { i -> colors[(i * colors.size) / SIZE] }
    }

    private fun circDist(a: Int, b: Int): Int {
        val d = kotlin.math.abs(a - b)
        return minOf(d, BUCKETS - d)
    }
}

/**
 * Follows whichever media session is playing and reports a palette from its album art.
 * Requires notification-listener access; [start] returns false without it.
 */
class MediaArtWatcher(
    private val context: Context,
    private val onPalette: (IntArray?) -> Unit,
) {
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, PulseNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val watched = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private var current: MediaController? = null
    private var lastKey: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { watch(it) }

    fun start(): Boolean {
        if (!isAccessGranted(context)) return false
        return try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            watch(msm.getActiveSessions(component))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        unwatch()
        worker.shutdownNow()
    }

    private fun watch(list: List<MediaController>?) {
        unwatch()
        list.orEmpty().forEach { c ->
            val cb = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    if (c.sessionToken == current?.sessionToken) update(metadata)
                }
                override fun onPlaybackStateChanged(state: PlaybackState?) = choose()
            }
            c.registerCallback(cb, main)
            watched += c to cb
        }
        choose()
    }

    private fun unwatch() {
        watched.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        watched.clear()
    }

    private fun choose() {
        val list = watched.map { it.first }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: current?.takeIf { cur -> list.any { it.sessionToken == cur.sessionToken } }
            ?: list.firstOrNull()
        if (best?.sessionToken != current?.sessionToken || best == null) {
            current = best
            lastKey = null
            update(best?.metadata)
        }
    }

    private fun update(m: MediaMetadata?) {
        if (m == null) {
            onPalette(null)
            return
        }
        val key = listOf(
            m.getString(MediaMetadata.METADATA_KEY_TITLE),
            m.getString(MediaMetadata.METADATA_KEY_ARTIST),
            m.getString(MediaMetadata.METADATA_KEY_ALBUM),
        ).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        val bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        worker.execute {
            val art = bitmap ?: uri?.let { loadLocal(it) }
            val palette = art?.let { runCatching { ArtPalette.extract(it) }.getOrNull() }
            main.post { if (key == lastKey) onPalette(palette) }
        }
    }

    /** Only local schemes; http(s) art would need network code and isn't worth it here. */
    private fun loadLocal(s: String): Bitmap? = runCatching {
        val uri = Uri.parse(s)
        if (uri.scheme !in setOf("content", "android.resource", "file")) return null
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }.getOrNull()

    companion object {
        fun isAccessGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(context, PulseNotificationListener::class.java))
    }
}
