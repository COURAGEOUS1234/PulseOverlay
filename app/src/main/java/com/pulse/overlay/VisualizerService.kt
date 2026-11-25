package com.pulse.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast

/**
 * Foreground service that owns the audio source and the system overlay window.
 * Started with projection data → playback capture; without → global Visualizer.
 */
class VisualizerService : Service() {

    private lateinit var prefs: Prefs
    private lateinit var windowManager: WindowManager
    private val processor = SpectrumProcessor(bandCount = 64)
    private var overlay: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var source: AudioSource? = null
    private var projection: MediaProjection? = null
    private var backdrop: ScreenBackdrop? = null
    private var backdropFailed = false
    private var artWatcher: MediaArtWatcher? = null
    private var artHintShown = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() = stopSelf() // user revoked capture from the system UI
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPrefs() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        windowManager = getSystemService(WindowManager::class.java)
        prefs.listen(prefListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        teardownAudio() // restarting with a new mode or a fresh projection token

        val data: Intent? = intent?.let {
            if (Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") it.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        val resultCode = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val useCapture = data != null

        // Must be in the foreground with the right type BEFORE getMediaProjection() on Android 14+.
        val type = if (useCapture) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, buildNotification(useCapture), type)
        } catch (e: Exception) {
            fail("Couldn't start foreground service: ${e.message}")
            return START_NOT_STICKY
        }

        showOverlay()

        val src: AudioSource = try {
            if (useCapture && data != null) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val mp = mpm.getMediaProjection(resultCode, data)
                    ?: throw IllegalStateException("Capture permission was not granted")
                mp.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
                projection = mp
                PlaybackCaptureSource(mp)
            } else {
                GlobalVisualizerSource()
            }
        } catch (e: Exception) {
            fail(e.message ?: "Couldn't create audio source")
            return START_NOT_STICKY
        }

        try {
            src.start(processor)
            source = src
            running = true
            updateBackdrop()
        } catch (e: Exception) {
            val hint = if (useCapture) "" else " — try Playback capture mode instead"
            fail("Audio source failed: ${e.message}$hint")
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = OverlayView(this, processor)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            title = "PulseOverlay"
        }
        overlay = view
        params = lp
        applyPrefs(updateWindow = false)
        windowManager.addView(view, lp)
    }

    private fun applyPrefs(updateWindow: Boolean = true) {
        val view = overlay ?: return
        val lp = params ?: return
        view.style = prefs.style
        view.sizeFraction = prefs.size
        view.monochrome = prefs.colorMode == ColorMode.WHITE
        view.salt.apply {
            grainPx = prefs.grainPx
            targetCount = prefs.grainCount
            bounciness = prefs.bounciness
            heaviness = prefs.heaviness
            sensitivity = prefs.sensitivity
        }
        lp.alpha = prefs.opacity // ≤ 0.8 keeps touch pass-through working on Android 12+
        if (updateWindow && view.isAttachedToWindow) windowManager.updateViewLayout(view, lp)
        updateBackdrop()
        updateArtWatcher()
    }

    /** Real glass blur needs a screen copy: only in playback-capture mode, Android 12+. */
    private fun updateBackdrop(forceRestart: Boolean = false) {
        val mp = projection
        val want = prefs.style == VizStyle.GLASS && mp != null && Build.VERSION.SDK_INT >= 31 && !backdropFailed
        if (want && mp != null) {
            val bd = backdrop ?: ScreenBackdrop(this, mp).also { backdrop = it }
            try {
                if (forceRestart || !bd.isActive) bd.start()
                overlay?.backdrop = bd
            } catch (e: Exception) {
                backdropFailed = true // e.g. user shared a single app, or the ROM refuses a second display
                overlay?.backdrop = null
                Toast.makeText(applicationContext, "Glass blur unavailable here (${e.javaClass.simpleName}); using frosted glass", Toast.LENGTH_LONG).show()
            }
        } else {
            overlay?.backdrop = null
            backdrop?.pause()
        }
    }

    private fun updateArtWatcher() {
        if (prefs.colorMode != ColorMode.ALBUM_ART) {
            artWatcher?.stop()
            artWatcher = null
            overlay?.albumPalette = null
            return
        }
        if (artWatcher != null) return
        val watcher = MediaArtWatcher(this) { palette -> overlay?.albumPalette = palette }
        if (watcher.start()) {
            artWatcher = watcher
        } else if (!artHintShown) {
            artHintShown = true
            Toast.makeText(applicationContext, "Album colours need notification access: open Pulse Overlay to grant it", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (overlay?.backdrop != null) updateBackdrop(forceRestart = true) // rotation: resize the screen copy
    }

    private fun buildNotification(capture: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Visualiser", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, VisualizerService::class.java).setAction(ACTION_STOP), flags
        )
        val openPi = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), flags)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Visualiser running")
            .setContentText(if (capture) "Playback capture" else "Global Visualizer")
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    private fun fail(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        stopSelf()
    }

    private fun teardownAudio() {
        source?.stop()
        source = null
        overlay?.backdrop = null
        backdrop?.release()
        backdrop = null
        backdropFailed = false
        projection?.let {
            it.unregisterCallback(projectionCallback) // else our own stop() would stopSelf() a restarted session
            it.stop()
        }
        projection = null
    }

    override fun onDestroy() {
        running = false
        teardownAudio()
        prefs.unlisten(prefListener)
        artWatcher?.stop()
        artWatcher = null
        overlay?.let { if (it.isAttachedToWindow) windowManager.removeView(it) }
        overlay = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "pulse"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.pulse.overlay.STOP"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        @Volatile var running = false
            private set

        fun startCapture(ctx: Context, resultCode: Int, data: Intent) {
            ctx.startForegroundService(
                Intent(ctx, VisualizerService::class.java)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }

        fun startVisualizer(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, VisualizerService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, VisualizerService::class.java))
        }
    }
}
