package com.ajc.shottracer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Local-only development prototype. No backend, account login or network permission. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var slider: SeekBar
    private lateinit var importButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var exportButton: Button
    private lateinit var saveButton: Button
    private var source: File? = null
    private var output: File? = null
    private var duration = 0.0
    private var displayedTime = 0.0
    private var seedTime = 0.0
    private var session: TrackingSession? = null
    private var busy = false
    private var generation = 0
    private var transformer: Transformer? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 32)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val padding = (16 * resources.displayMetrics.density).toInt()
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        layout.addView(TextView(this).apply { setText(R.string.app_name); textSize = 28f })
        layout.addView(TextView(this).apply { setText(R.string.prototype_notice) })
        preview = PreviewView(this)
        layout.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            (320 * resources.displayMetrics.density).toInt()))
        preview.onBall = { point ->
            val bitmap = preview.image
            if (!busy && bitmap != null && point.x >= 3 && point.y >= 3 &&
                point.x < bitmap.width - 3 && point.y < bitmap.height - 3) {
                session?.close(); session = null
                preview.overlay?.recycle(); preview.overlay = TrackingSession.watermarkOnly(bitmap.width, bitmap.height)
                preview.ball = point; seedTime = displayedTime; output = null
                preview.invalidate(); status.setText(R.string.hint_analyze); updateControls()
            }
        }
        slider = SeekBar(this).apply { max = 1000 }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) { refresh(duration * bar.progress / 1000) }
        })
        layout.addView(slider)
        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            setText(label); setOnClickListener { action() }; layout.addView(this)
        }
        importButton = button(R.string.action_import) {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*"), 1)
        }
        analyzeButton = button(R.string.action_analyze) { analyze() }
        exportButton = button(R.string.action_export) { exportVideo() }
        saveButton = button(R.string.action_save) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "video/mp4"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "ShotTracer.mp4")
            }, 2)
        }
        status = TextView(this).apply { setText(R.string.hint_import) }; layout.addView(status)
        updateControls()
    }

    private fun updateControls() {
        importButton.isEnabled = !busy
        analyzeButton.isEnabled = !busy && preview.ball != null
        exportButton.isEnabled = !busy && session != null
        saveButton.isEnabled = !busy && output != null
        slider.isEnabled = !busy && source != null
        preview.isEnabled = !busy
    }

    private fun work(action: () -> Unit) {
        busy = true; updateControls()
        worker.execute {
            try { action() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: Exception) { ui { status.setText(R.string.error_processing) } }
            finally { ui { busy = false; updateControls() } }
        }
    }

    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed) action() } }

    @Deprecated("Platform callback retained for dependency-free picker integration")
    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK) return
        val uri = data?.data ?: return
        if (request == 1) {
            generation++
            work {
                val folder = File(filesDir, "projects/${UUID.randomUUID()}").apply { mkdirs() }
                val file = File(folder, "source.mp4")
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                val metadata = MediaMetadataRetriever()
                val seconds: Double
                try {
                    metadata.setDataSource(file.path)
                    seconds = (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble() ?: 0.0) / 1000
                    require(seconds > 0)
                } finally { metadata.release() }
                ui {
                    source = file; duration = seconds; output = null
                    session?.close(); session = null; preview.ball = null
                    preview.overlay?.recycle(); preview.overlay = null
                    busy = false; slider.progress = 0; refresh(0.0)
                    status.setText(R.string.hint_ball)
                }
            }
        } else if (request == 2) {
            val file = output ?: return
            work {
                contentResolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                ui { status.setText(R.string.status_saved) }
            }
        }
    }

    private fun decoded(retriever: MediaMetadataRetriever, seconds: Double): Bitmap =
        retriever.getScaledFrameAtTime((seconds * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST, 640, 640)
            ?: error("frame")

    private fun refresh(time: Double) {
        val file = source ?: return
        val requested = time.coerceIn(0.0, maxOf(0.0, duration - 0.05))
        val token = ++generation
        worker.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                val image = decoded(retriever, requested)
                ui {
                    if (token == generation) {
                        preview.image?.recycle(); preview.image = image
                        displayedTime = requested
                        preview.overlay?.recycle()
                        preview.overlay = session?.overlay(requested) ?: TrackingSession.watermarkOnly(image.width, image.height)
                        preview.invalidate()
                    } else image.recycle()
                }
            } catch (_: Exception) { ui { status.setText(R.string.error_processing) } }
            finally { retriever.release() }
        }
    }

    private fun analyze() {
        val file = source ?: return
        val ball = preview.ball ?: return
        val image = preview.image ?: return
        val width = image.width; val height = image.height
        val start = seedTime; val end = minOf(duration - 0.05, start + 15)
        generation++
        status.setText(R.string.status_analyzing)
        work {
            val result = TrackingSession(width, height, ball.x.toDouble(), ball.y.toDouble())
            val retriever = MediaMetadataRetriever()
            var accepted = false
            try {
                retriever.setDataSource(file.path)
                for (index in 0..maxOf(0, ((end - start) * 30).toInt())) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val time = start + index / 30.0
                    val bitmap = decoded(retriever, time)
                    try { result.push(bitmap, time) } finally { bitmap.recycle() }
                    if (index % 30 == 0) ui { status.text = getString(R.string.status_progress, index / 30) }
                }
                if (result.count() < 4) { ui { status.setText(R.string.error_track) }; return@work }
                accepted = true
                runOnUiThread {
                    if (isDestroyed) { result.close(); return@runOnUiThread }
                    session?.close(); session = result
                    preview.overlay?.recycle(); preview.overlay = result.overlay(displayedTime)
                    preview.invalidate(); output = null
                    status.text = getString(R.string.status_tracked, result.count())
                }
            } finally {
                retriever.release()
                if (!accepted) result.close()
            }
        }
    }

    private fun exportVideo() {
        val file = source ?: return
        val result = session ?: return
        busy = true; output = null; updateControls(); status.setText(R.string.status_exporting)
        val destination = File(file.parentFile, "traced-${UUID.randomUUID()}.mp4")
        val overlay = object : BitmapOverlay() {
            private var width = result.width
            private var height = result.height
            override fun configure(videoSize: Size) {
                super.configure(videoSize); width = videoSize.width; height = videoSize.height
            }
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val raster = result.overlay(presentationTimeUs / 1_000_000.0)
                val scaled = Bitmap.createScaledBitmap(raster, width, height, true)
                if (scaled !== raster) raster.recycle()
                return scaled
            }
        }
        val effects = Effects(emptyList(), listOf(Presentation.createForHeight(720), OverlayEffect(listOf<TextureOverlay>(overlay))))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).setEffects(effects).build()
        transformer = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    output = destination; busy = false; status.setText(R.string.status_exported); updateControls()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    destination.delete(); busy = false; status.setText(R.string.error_processing); updateControls()
                }
            }).build()
        try { transformer!!.start(item, destination.path) }
        catch (_: Exception) {
            destination.delete(); busy = false; status.setText(R.string.error_processing); updateControls()
        }
    }

    override fun onDestroy() {
        transformer?.cancel()
        worker.shutdownNow()
        session?.close(); session = null
        super.onDestroy()
    }
}
