package com.ajc.shottracer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

internal class PreviewView(context: Context) : View(context) {
    var image: Bitmap? = null
    var overlay: Bitmap? = null
    var ball: PointF? = null
    var onBall: ((PointF) -> Unit)? = null
    private val rectangle = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init { contentDescription = context.getString(R.string.preview_label); isClickable = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = image ?: return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        rectangle.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(bitmap, null, rectangle, paint)
        overlay?.let { canvas.drawBitmap(it, null, rectangle, paint) }
        ball?.let {
            paint.color = Color.YELLOW; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            canvas.drawCircle(rectangle.left + it.x * scale, rectangle.top + it.y * scale, 12f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (event.action == MotionEvent.ACTION_UP && rectangle.contains(event.x, event.y)) {
            val bitmap = image ?: return false
            onBall?.invoke(PointF((event.x - rectangle.left) / rectangle.width() * bitmap.width,
                                (event.y - rectangle.top) / rectangle.height() * bitmap.height))
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
