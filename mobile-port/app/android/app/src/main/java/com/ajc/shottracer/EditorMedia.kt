package com.ajc.shottracer

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.Drawable
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import java.io.File
import kotlin.math.max

internal object EditorMedia {
    fun frameTimes(file: File): DoubleArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val times = ArrayList<Double>()
            while (extractor.sampleTime >= 0) {
                times.add(extractor.sampleTime / 1_000_000.0)
                if (!extractor.advance()) break
            }
            return times.distinct().sorted().toDoubleArray().also { require(it.isNotEmpty()) }
        } finally { extractor.release() }
    }
    fun frame(retriever: MediaMetadataRetriever, index: Int): Bitmap {
        val original = retriever.getFrameAtIndex(index) ?: error("frame")
        val scale = minOf(1.0, 640.0 / max(original.width, original.height))
        if (scale == 1.0) return original
        val reduced = Bitmap.createScaledBitmap(original, (original.width * scale).toInt(), (original.height * scale).toInt(), true)
        if (reduced !== original) original.recycle()
        return reduced
    }
    fun nearest(times: DoubleArray, time: Double): Int {
        val found = times.binarySearch(time)
        if (found >= 0) return found
        val next = (-found - 1).coerceIn(0, times.lastIndex)
        return if (next > 0 && time - times[next - 1] < times[next] - time) next - 1 else next
    }
}

/** Minimal vector tools retain accessible labels and do not depend on emoji fonts. */
internal class ToolIcon(private val kind: String) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun line(vararg points: Float) { val p = Path(); p.moveTo(points[0], points[1]); for (i in 2 until points.size step 2) p.lineTo(points[i], points[i + 1]); canvas.drawPath(p, paint) }
        when (kind) {
            "record" -> { paint.color = Color.RED; canvas.drawCircle(12f, 12f, 8f, paint); paint.style = Paint.Style.FILL; canvas.drawCircle(12f, 12f, 5f, paint); paint.style = Paint.Style.STROKE; paint.color = Color.WHITE }
            "stop" -> { paint.style = Paint.Style.FILL; canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint); paint.style = Paint.Style.STROKE }
            "pan" -> { line(12f, 2f, 12f, 22f); line(2f, 12f, 22f, 12f); line(9f, 5f, 12f, 2f, 15f, 5f); line(9f, 19f, 12f, 22f, 15f, 19f) }
            "landing_frame" -> { line(5f, 20f, 5f, 3f, 17f, 7f, 5f, 11f); canvas.drawCircle(17f, 17f, 5f, paint); line(17f, 14f, 17f, 17f, 20f, 17f) }
            "add" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "import", "projects" -> { line(3f, 7f, 10f, 7f, 12f, 4f, 21f, 4f, 21f, 20f, 3f, 20f, 3f, 7f); if (kind == "import") { line(12f, 9f, 12f, 17f); line(8f, 13f, 16f, 13f) } }
            "camera" -> { canvas.drawRoundRect(3f, 6f, 21f, 20f, 2f, 2f, paint); canvas.drawCircle(12f, 13f, 4f, paint); line(8f, 6f, 9f, 3f, 15f, 3f, 16f, 6f) }
            "ball" -> { canvas.drawCircle(12f, 12f, 6f, paint); line(12f, 2f, 12f, 6f); line(12f, 18f, 12f, 22f); line(2f, 12f, 6f, 12f); line(18f, 12f, 22f, 12f) }
            "landing" -> { line(6f, 21f, 6f, 3f, 19f, 7f, 6f, 12f); line(3f, 21f, 15f, 21f) }
            "apex" -> { line(3f, 20f, 12f, 4f, 21f, 20f); canvas.drawCircle(12f, 4f, 2f, paint) }
            "path", "analyze" -> { val p = Path(); p.moveTo(3f, 20f); p.quadTo(8f, -6f, 21f, 15f); canvas.drawPath(p, paint); if (kind == "analyze") line(16f, 12f, 21f, 15f, 22f, 9f) }
            "circle" -> canvas.drawCircle(12f, 12f, 8f, paint)
            "line" -> line(4f, 20f, 20f, 4f)
            "swing" -> line(3f, 19f, 6f, 8f, 14f, 3f, 21f, 9f, 17f, 18f, 8f, 21f)
            "undo", "redo" -> { canvas.save(); if (kind == "redo") canvas.scale(-1f, 1f, 12f, 12f); line(9f, 4f, 3f, 9f, 9f, 14f); val p = Path(); p.moveTo(3f, 9f); p.cubicTo(23f, 5f, 24f, 22f, 10f, 21f); canvas.drawPath(p, paint); canvas.restore() }
            "previous", "next" -> { canvas.save(); if (kind == "next") canvas.scale(-1f, 1f, 12f, 12f); line(5f, 4f, 5f, 20f); line(19f, 4f, 8f, 12f, 19f, 20f); canvas.restore() }
            "play", "review" -> { line(8f, 4f, 20f, 12f, 8f, 20f, 8f, 4f); if (kind == "review") canvas.drawRoundRect(2f, 2f, 23f, 22f, 2f, 2f, paint) }
            "pause" -> { line(8f, 4f, 8f, 20f); line(16f, 4f, 16f, 20f) }
            "export", "save" -> { line(4f, 14f, 4f, 21f, 20f, 21f, 20f, 14f); line(12f, 17f, 12f, 3f); line(7f, 8f, 12f, 3f, 17f, 8f) }
            "fullscreen" -> { line(3f, 9f, 3f, 3f, 9f, 3f); line(15f, 3f, 21f, 3f, 21f, 9f); line(21f, 15f, 21f, 21f, 15f, 21f); line(9f, 21f, 3f, 21f, 3f, 15f) }
            "delete" -> { line(3f, 6f, 21f, 6f); line(7f, 6f, 7f, 21f, 17f, 21f, 17f, 6f); line(9f, 3f, 15f, 3f) }
            else -> { canvas.drawCircle(12f, 12f, 5f, paint); for (i in 0..7) { canvas.save(); canvas.rotate(i * 45f, 12f, 12f); line(12f, 2f, 12f, 5f); canvas.restore() } }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Android compatibility") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class ReviewActivity : Activity() {
    private var video: VideoView? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val path = intent.getStringExtra("path") ?: return finish()
        val file = File(path)
        if (!file.canonicalPath.startsWith(filesDir.canonicalPath + File.separator) || !file.isFile) return finish()
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val view = VideoView(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root); video = view
        val controller = MediaController(this); controller.setAnchorView(view)
        view.setMediaController(controller); view.setVideoURI(Uri.fromFile(file))
        view.setOnPreparedListener { it.isLooping = false; view.start(); controller.show(5000) }
        view.contentDescription = getString(R.string.editor_review)
    }
    override fun onPause() { video?.pause(); super.onPause() }
    override fun onDestroy() { video?.stopPlayback(); super.onDestroy() }
}
