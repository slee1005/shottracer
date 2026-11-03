package com.ajc.shottracer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

class LiveCameraActivity : Activity(), LiveShotEngine.Listener {
    private lateinit var engine: LiveShotEngine
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var sound: TextView
    private lateinit var recordButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var reviewButton: ImageButton
    private val fileWorker = Executors.newSingleThreadExecutor()
    private var resumed = false
    private var requested = false
    private var recording = false
    private var finishing = false
    private var saving = false
    private var output: File? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        engine = LiveShotEngine(this, this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val pad = (16 * resources.displayMetrics.density).toInt()
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        setContentView(layout)
        layout.addView(TextView(this).apply { setText(R.string.live_title); textSize = 18f })
        layout.addView(TextView(this).apply { setText(R.string.live_instructions); textSize = 11f; maxLines = 2 })
        preview = PreviewView(this).apply { contentDescription = getString(R.string.live_preview); background=android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.BLACK); cornerRadius=20*resources.displayMetrics.density }; clipToOutline=true }
        layout.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        preview.onBall = { point ->
            val bitmap = preview.image
            if (!recording && !finishing && bitmap != null && point.x >= 3 && point.y >= 3 &&
                point.x < bitmap.width - 3 && point.y < bitmap.height - 3) {
                preview.ball = point; preview.invalidate()
                engine.select(point.x / bitmap.width, point.y / bitmap.height)
                status.setText(R.string.live_ready); controls()
            }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        layout.addView(actions)
        fun button(label: Int, symbol: String, action: () -> Unit) = ImageButton(this).apply {
            contentDescription = getString(label); tooltipText = getString(label); setImageDrawable(ToolIcon(symbol))
            background=android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.rgb(27,35,45)); cornerRadius=18*resources.displayMetrics.density }
            setPadding(24, 24, 24, 24); setOnClickListener { action() }
            actions.addView(this, LinearLayout.LayoutParams(0, (52 * resources.displayMetrics.density).toInt(), 1f))
        }
        recordButton = button(R.string.live_record, "record") {
            recording = true; output = null; controls()
            engine.record(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        }
        stopButton = button(R.string.live_stop, "stop") { finishing = true; controls(); engine.stopRecording() }
        reviewButton = button(R.string.editor_review, "review") {
            output?.let { startActivity(Intent(this, ReviewActivity::class.java).putExtra("path", it.path)) }
        }
        saveButton = button(R.string.action_save, "save") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "video/mp4"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "ShotTracer-live.mp4")
            }, 2)
        }
        status = TextView(this).apply { setText(R.string.live_preparing) }; layout.addView(status)
        sound = TextView(this).apply { setText(R.string.live_audio_hint) }; layout.addView(sound)
        output = File(filesDir, "captures").listFiles()?.filter { it.name.endsWith(".mp4") && !it.name.endsWith(".partial.mp4") }
            ?.maxByOrNull { it.lastModified() }
        controls()
    }

    private fun controls() {
        recordButton.isEnabled = resumed && !recording && !finishing && !saving && preview.ball != null
        stopButton.isEnabled = recording && !finishing
        saveButton.isEnabled = !recording && !finishing && !saving && output != null
        reviewButton.isEnabled = saveButton.isEnabled
        preview.isEnabled = resumed && !recording && !finishing && !saving
    }

    override fun onResume() {
        super.onResume(); resumed = true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (!requested) {
            requested = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
        }
        controls()
    }

    private fun startCamera() {
        val rotation = when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        engine.resume(rotation)
    }

    override fun onRequestPermissionsResult(request: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (request == 1 && resumed) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else status.setText(R.string.live_denied)
        }
    }

    override fun onPause() {
        resumed = false; engine.pause()
        preview.ball = null; preview.image?.recycle(); preview.image = null; preview.invalidate()
        controls(); super.onPause()
    }

    override fun frame(bitmap: Bitmap) {
        if (!resumed || isDestroyed) { bitmap.recycle(); return }
        preview.image?.recycle(); preview.image = bitmap; preview.invalidate()
    }

    override fun message(resource: Int) { if (!isDestroyed) status.setText(resource) }

    override fun recording(active: Boolean, finishing: Boolean, audio: Boolean) {
        if (isDestroyed) return
        recording = active; this.finishing = finishing
        if (active) sound.setText(if (audio) R.string.live_audio_on else R.string.live_audio_off)
        if (finishing) status.setText(R.string.live_finishing)
        controls()
    }

    override fun saved(file: File, points: Int) {
        if (isDestroyed) return
        output = file
        status.setText(if (points >= 4) R.string.live_saved else R.string.live_no_track)
        controls()
    }

    @Deprecated("Platform callback retained for dependency-free document saving")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request != 2 || result != RESULT_OK) return
        val uri = data?.data ?: return
        val file = output ?: return
        saving = true; controls()
        fileWorker.execute {
            val success = try {
                contentResolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }; true
            } catch (_: Exception) { false }
            runOnUiThread { if (!isDestroyed) {
                saving = false; status.setText(if (success) R.string.status_saved else R.string.live_failed); controls()
            } }
        }
    }

    override fun onDestroy() {
        engine.close(); fileWorker.shutdown()
        super.onDestroy()
    }
}
