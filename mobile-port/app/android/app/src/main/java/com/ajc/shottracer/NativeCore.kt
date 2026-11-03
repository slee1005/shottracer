package com.ajc.shottracer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

internal object NativeCore {
    init { System.loadLibrary("shottracer_mobile") }
    external fun create(width: Int, height: Int, x: Double, y: Double): Long
    external fun mode(handle: Long, mode: Int): Int
    external fun club(previous: ByteArray, current: ByteArray, next: ByteArray, mask: ByteArray, width: Int, height: Int, pose: DoubleArray, motion: DoubleArray): DoubleArray?
    external fun destroy(handle: Long)
    external fun push(handle: Long, pixels: IntArray, time: Double): Int
    external fun count(handle: Long): Int
    external fun samples(handle: Long, camera: Boolean): DoubleArray
    external fun curve(controls: DoubleArray, flight: Double, apex: Double, speed: Double, time: Double): DoubleArray
    external fun fit(points: DoubleArray, x: Double, y: Double, t0Min: Double, t0Max: Double): DoubleArray?
    external fun refit(base: DoubleArray, points: DoubleArray, landing: DoubleArray?, flight: Double): DoubleArray?
    external fun modelPoint(model: DoubleArray, time: Double): DoubleArray?
    external fun modelApex(model: DoubleArray): Double
    external fun reshape(model: DoubleArray, samples: DoubleArray, apex: Double): DoubleArray?
    external fun render(handle: Long, time: Double): IntArray
}

/** Lock protects the handle from concurrent preview/export callbacks and destruction. */
internal class TrackingSession(val width: Int, val height: Int, x: Double, y: Double, putt: Boolean = false) : AutoCloseable {
    private var handle = NativeCore.create(width, height, x, y)
    init { require(handle != 0L); check(NativeCore.mode(handle, if (putt) 1 else 0) == 0) }

    @Synchronized fun push(bitmap: Bitmap, time: Double) {
        check(handle != 0L && bitmap.width == width && bitmap.height == height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        check(NativeCore.push(handle, pixels, time) >= 0)
    }

    @Synchronized fun count(): Int = if (handle != 0L) NativeCore.count(handle) else 0
    @Synchronized fun samples(camera: Boolean = false): DoubleArray = NativeCore.samples(handle, camera)

    /** The same complete overlay, including watermark, is used by ImageView and Transformer. */
    @Synchronized fun overlay(time: Double): Bitmap {
        check(handle != 0L)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(NativeCore.render(handle, time), 0, width, 0, 0, width, height)
        return watermark(bitmap)
    }

    companion object {
        fun watermarkOnly(width: Int, height: Int): Bitmap = watermark(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888))

        private fun watermark(bitmap: Bitmap): Bitmap {
            val width = bitmap.width; val height = bitmap.height
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = maxOf(10f, height * 0.032f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val text = "Create by AJC_shottracer"
            paint.color = Color.BLACK; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
            canvas.drawText(text, width / 2f, height * 0.975f - paint.fontMetrics.descent, paint)
            paint.color = Color.WHITE; paint.style = Paint.Style.FILL
            canvas.drawText(text, width / 2f, height * 0.975f - paint.fontMetrics.descent, paint)
            return bitmap
        }
    }

    @Synchronized override fun close() {
        if (handle != 0L) { NativeCore.destroy(handle); handle = 0 }
    }
}
