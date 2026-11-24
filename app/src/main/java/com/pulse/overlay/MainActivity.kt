package com.pulse.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Settings + start/stop. UI built in code so the project needs no resources or libraries. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        status.text = if (VisualizerService.running) "● Running — switch to any music app" else "○ Stopped"
    }

    // ---- Start flow: overlay permission → RECORD_AUDIO → (capture consent) → service ----

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow \"Display over other apps\", then come back and tap Start")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions(perms.toTypedArray(), REQ_PERMS)
            return
        }
        launchSource()
    }

    private fun launchSource() {
        when (prefs.mode) {
            SourceMode.CAPTURE -> {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                // System dialog says "screen recording"; only audio is captured, no virtual display is created.
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
            }
            SourceMode.GLOBAL_VISUALIZER -> {
                VisualizerService.startVisualizer(this)
                onStarted()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val idx = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (idx >= 0 && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED) launchSource()
        else toast("Audio permission is required to read the music signal")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode == RESULT_OK && data != null) {
            VisualizerService.startCapture(this, resultCode, data)
            onStarted()
        } else {
            toast("Capture was declined")
        }
    }

    private fun onStarted() {
        status.text = "● Running — switch to any music app"
        // Step aside so the overlay shows over other apps. Delayed so the service reaches
        // startForeground() while we're still visible (required for the microphone FGS type).
        window.decorView.postDelayed({ moveTaskToBack(true) }, 800)
    }

    // ---- UI ----

    private fun buildUi(): View {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Pulse Overlay"; textSize = 26f })
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(4), 0, dp(16)) }
        root.addView(status)

        root.addView(header("Audio source"))
        root.addView(radioGroup(SourceMode.entries.map { it.label }, prefs.mode.ordinal) {
            prefs.mode = SourceMode.entries[it]
            if (VisualizerService.running) toast("Restart the visualiser to switch source")
        })

        root.addView(header("Style"))
        root.addView(radioGroup(VizStyle.entries.map { it.label }, prefs.style.ordinal) {
            prefs.style = VizStyle.entries[it]
        })

        root.addView(header("Opacity"))
        root.addView(slider(Prefs.MIN_OPACITY, Prefs.MAX_OPACITY, prefs.opacity) { prefs.opacity = it })

        root.addView(header("Size"))
        root.addView(slider(0.1f, 0.7f, prefs.size) { prefs.size = it })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        }
        buttons.addView(Button(this).apply { text = "Start"; setOnClickListener { start() } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                VisualizerService.stop(this@MainActivity)
                status.text = "○ Stopped"
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)

        root.addView(TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            text = "Style, opacity and size update live. Touches pass through the overlay. " +
                "Apps that block audio capture show nothing in Playback capture mode — try Global Visualizer for those."
        })
        return ScrollView(this).apply { addView(root) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun radioGroup(labels: List<String>, checked: Int, onPick: (Int) -> Unit) =
        RadioGroup(this).apply {
            labels.forEachIndexed { i, label ->
                addView(RadioButton(this@MainActivity).apply { id = View.generateViewId(); text = label; tag = i })
            }
            check(getChildAt(checked).id)
            setOnCheckedChangeListener { group, id -> (group.findViewById<View>(id).tag as? Int)?.let(onPick) }
        }

    private fun slider(min: Float, max: Float, value: Float, onChange: (Float) -> Unit) =
        SeekBar(this).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) onChange(min + (max - min) * p / 100f)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
    }
}
