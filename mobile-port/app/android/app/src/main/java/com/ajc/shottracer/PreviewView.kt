package com.ajc.shottracer

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.hypot

internal data class EditorHandle(val key: String, val point: PointF, val label: String = "")
internal class PreviewView(context: Context) : View(context) {
    var image: Bitmap? = null
    var overlay: Bitmap? = null
    var ball: PointF? = null
    var onBall: ((PointF) -> Unit)? = null
    var handles: List<EditorHandle> = emptyList()
    var onHandleStart: (() -> Unit)? = null
    var onHandle: ((String, PointF) -> Unit)? = null
    var onHandleEnd: (() -> Unit)? = null
    var zoomEnabled = false
    var panEnabled = false
    private val rectangle = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f; private var panX = 0f; private var panY = 0f
    private var activeHandle: String? = null
    private var transformed = false
    private var lastX = 0f; private var lastY = 0f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!zoomEnabled) return false
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f); transformed = true; invalidate(); return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!zoomEnabled) return false
            zoom = 1f; panX = 0f; panY = 0f; transformed = true; invalidate(); return true
        }
    })
    init { contentDescription = context.getString(R.string.preview_label); isClickable = true; setBackgroundColor(Color.BLACK) }
    fun resetViewport() { zoom=1f; panX=0f; panY=0f; invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = image ?: return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
        val w = bitmap.width * scale; val h = bitmap.height * scale
        rectangle.set((width - w) / 2 + panX, (height - h) / 2 + panY, (width + w) / 2 + panX, (height + h) / 2 + panY)
        canvas.save(); canvas.clipRect(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, rectangle, paint)
        overlay?.let { canvas.drawBitmap(it, null, rectangle, paint) }
        paint.color = Color.YELLOW; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * resources.displayMetrics.density
        ball?.let { canvas.drawCircle(rectangle.left + it.x * scale, rectangle.top + it.y * scale, 10 * resources.displayMetrics.density, paint) }
        handles.forEach {
            val x = rectangle.left + it.point.x * rectangle.width(); val y = rectangle.top + it.point.y * rectangle.height()
            paint.color = if (it.key == "landing") Color.GREEN else if (it.key == "apex") Color.CYAN else Color.YELLOW
            canvas.drawCircle(x, y, 8 * resources.displayMetrics.density, paint)
            canvas.drawLine(x - 4, y, x + 4, y, paint); canvas.drawLine(x, y - 4, x, y + 4, paint)
            if(it.label.isNotEmpty() && x in 0f..width.toFloat() && y in 0f..height.toFloat()) {
                val ink=paint.color; val density=resources.displayMetrics.density
                paint.style=Paint.Style.FILL; paint.textSize=10*density; paint.typeface=Typeface.DEFAULT_BOLD
                val tw=paint.measureText(it.label); val tx=(x+12*density).coerceIn(4*density,maxOf(4*density,width-tw-8*density))
                val ty=(y-10*density).coerceIn(16*density,maxOf(16*density,height-4*density))
                paint.color=Color.argb(215,7,15,23); canvas.drawRoundRect(tx-3*density,ty-12*density,tx+tw+3*density,ty+3*density,3*density,3*density,paint)
                paint.color=ink; canvas.drawText(it.label,tx,ty,paint); paint.style=Paint.Style.STROKE
            }
        }
        paint.style = Paint.Style.FILL; canvas.restore()
    }
    private fun normalized(e: MotionEvent) = PointF(((e.x - rectangle.left) / rectangle.width()).coerceIn(0f, 1f),
        ((e.y - rectangle.top) / rectangle.height()).coerceIn(0f, 1f))
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || image == null) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            transformed = false; lastX = event.x; lastY = event.y
            activeHandle = handles.minByOrNull {
                hypot(event.x - rectangle.left - it.point.x * rectangle.width(), event.y - rectangle.top - it.point.y * rectangle.height())
            }?.takeIf { hypot(event.x - rectangle.left - it.point.x * rectangle.width(), event.y - rectangle.top - it.point.y * rectangle.height()) < 26 * resources.displayMetrics.density }?.key
            if (activeHandle != null) onHandleStart?.invoke()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (event.pointerCount > 1) {
            transformed = true
            if (activeHandle != null) onHandleEnd?.invoke()
            activeHandle = null
        }
        if (zoomEnabled) { scale.onTouchEvent(event); gestures.onTouchEvent(event) }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            if (activeHandle != null && !scale.isInProgress) onHandle?.invoke(activeHandle!!, normalized(event))
            else if (panEnabled && zoomEnabled) { panX += event.x - lastX; panY += event.y - lastY; transformed = true; invalidate() }
            lastX = event.x; lastY = event.y
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            if (activeHandle != null) { onHandle?.invoke(activeHandle!!, normalized(event)); onHandleEnd?.invoke() }
            else if (!transformed && rectangle.contains(event.x, event.y)) {
                val p = normalized(event); val bitmap = image!!
                onBall?.invoke(PointF(p.x * bitmap.width, p.y * bitmap.height)); performClick()
            }
            activeHandle = null; parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { activeHandle = null; onHandleEnd?.invoke() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
