package com.ajc.shottracer

import android.content.Context
import android.graphics.*
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.nio.ByteOrder
import kotlin.math.*

internal data class PersonMask(val time: Double, val path: String)

/** One CPU task per analysis, with packaged weights. No network or background service. */
internal class PersonAnalysis(context: Context, private val folder: File) : AutoCloseable {
    private val pose = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build())
        .setRunningMode(RunningMode.VIDEO).setNumPoses(1).setOutputSegmentationMasks(true).build())
    val masks = mutableListOf<PersonMask>()
    init { check(folder.mkdirs() || folder.isDirectory) }
    fun push(bitmap: Bitmap, time: Double) {
        val w = bitmap.width; val h = bitmap.height
        // MPImage.close() recycles its backing bitmap; keep the decoder's frame owned by its caller.
        val image = BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888,false)).build()
        val result = try { pose.detectForVideo(image, (time * 1000).roundToLong()) } finally { image.close() }
        val images = result.segmentationMasks().orElse(emptyList())
        try {
            images.firstOrNull()?.let { mask ->
                val floats = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val alpha = IntArray(mask.width*mask.height) { ((floats.get(it).coerceIn(0f,1f)*255).toInt() shl 24) or 0xffffff }
                val raw = Bitmap.createBitmap(alpha,mask.width,mask.height,Bitmap.Config.ARGB_8888)
                // Persist compact alpha masks; never keep the full video in memory.
                val small = Bitmap.createScaledBitmap(raw, min(256,w), max(1, h*min(256,w)/w), true)
                val file = File(folder,"${(time*1_000_000).roundToLong()}.png")
                file.outputStream().use { check(small.compress(Bitmap.CompressFormat.PNG,100,it)) }
                masks.add(PersonMask(time,file.path))
                if (small !== raw) small.recycle()
                raw.recycle()
            }
        } finally { images.forEach { it.close() } }
    }
    override fun close() { pose.close() }
}
