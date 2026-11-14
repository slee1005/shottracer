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
internal data class PersonSample(val time: Double, val gray: ByteArray, val body: ByteArray,
    val hands: EditPoint?, val height: Double, val motion: EditPoint)

/** One CPU task per analysis, with packaged weights. No network or background service. */
internal class PersonAnalysis(context: Context, private val folder: File) : AutoCloseable {
    private val pose = PoseLandmarker.createFromOptions(context, PoseLandmarker.PoseLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build())
        .setRunningMode(RunningMode.VIDEO).setNumPoses(1).setOutputSegmentationMasks(true).build())
    val masks = mutableListOf<PersonMask>()
    val hands = mutableListOf<TimedPoint>()
    val clubs = mutableListOf<TimedPoint>()
    private var before: PersonSample? = null
    private var previous: PersonSample? = null
    init { check(folder.mkdirs() || folder.isDirectory) }
    fun push(bitmap: Bitmap, time: Double, motion: EditPoint) {
        val w = bitmap.width; val h = bitmap.height
        // MPImage.close() recycles its backing bitmap; keep the decoder's frame owned by its caller.
        val image = BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888,false)).build()
        val result = try { pose.detectForVideo(image, (time * 1000).roundToLong()) } finally { image.close() }
        val pixels = IntArray(w*h); bitmap.getPixels(pixels,0,w,0,0,w,h)
        val gray = ByteArray(w*h) { i -> val p = pixels[i]; ((77*(p shr 16 and 255)+150*(p shr 8 and 255)+29*(p and 255)) shr 8).toByte() }
        val body = ByteArray(w*h)
        val images = result.segmentationMasks().orElse(emptyList())
        try {
            images.firstOrNull()?.let { mask ->
                val floats = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val alpha = IntArray(mask.width*mask.height) { ((floats.get(it).coerceIn(0f,1f)*255).toInt() shl 24) or 0xffffff }
                val raw = Bitmap.createBitmap(alpha,mask.width,mask.height,Bitmap.Config.ARGB_8888)
                val expanded = Bitmap.createScaledBitmap(raw,w,h,true)
                val values = IntArray(w*h); expanded.getPixels(values,0,w,0,0,w,h)
                values.forEachIndexed { i,v -> body[i]=(v ushr 24).toByte() }
                // Persist compact alpha masks; never keep the full video in memory.
                val small = Bitmap.createScaledBitmap(raw, min(256,w), max(1, h*min(256,w)/w), true)
                val file = File(folder,"${(time*1_000_000).roundToLong()}.png")
                file.outputStream().use { check(small.compress(Bitmap.CompressFormat.PNG,100,it)) }
                masks.add(PersonMask(time,file.path))
                if (small !== raw && small !== expanded) small.recycle()
                if (expanded !== raw) expanded.recycle(); raw.recycle()
            }
        } finally { images.forEach { it.close() } }
        val p = result.landmarks().firstOrNull()
        fun visible(index: Int) = p != null && p[index].visibility().orElse(0f) >= .4f && p[index].presence().orElse(0f) >= .4f
        val hand = if (visible(15) && visible(16)) EditPoint((p!![15].x()+p[16].x())/2.0,(p[15].y()+p[16].y())/2.0) else null
        val height = if (visible(11) && visible(12) && visible(27) && visible(28))
            max(8.0, ((p!![27].y()+p[28].y()-p[11].y()-p[12].y())/2*h).toDouble()) else 0.0
        if (hand != null) hands.add(TimedPoint(time,hand.x-motion.x,hand.y-motion.y))
        val current = PersonSample(time,gray,body,hand,height,motion)
        val a = before; val b = previous
        if (a != null && b?.hands != null && b.height >= 8 && time-a.time < .3) {
            NativeCore.club(a.gray,b.gray,current.gray,b.body,w,h,
                doubleArrayOf(b.hands.x*w,b.hands.y*h,b.height),
                doubleArrayOf((a.motion.x-b.motion.x)*w,(a.motion.y-b.motion.y)*h,(motion.x-b.motion.x)*w,(motion.y-b.motion.y)*h))?.let {
                clubs.add(TimedPoint(b.time,it[0]-b.motion.x,it[1]-b.motion.y))
            }
        }
        before = previous; previous = current
    }
    override fun close() { pose.close() }
}
