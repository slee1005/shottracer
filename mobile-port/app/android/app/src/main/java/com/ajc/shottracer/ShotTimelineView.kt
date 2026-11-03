package com.ajc.shottracer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

internal data class ShotEvent(val key: String, val time: Double, val frame: Int, val inClip: Boolean, val estimated: Boolean = false)

/** Events use source time, independent of tracer speed and exported slow motion.
 * Index mapping matches the frame slider, including variable-rate source clips. */
internal fun shotEvents(shot: EditShot, times: DoubleArray, duration: Double): List<ShotEvent> {
    if (shot.ball == null || times.isEmpty()) return emptyList()
    fun event(key: String, time: Double, estimated: Boolean = false) = ShotEvent(key,time,
        EditorMedia.nearest(times,time),time >= times.first()-1e-6 && time <= duration+1e-6,estimated)
    val result = mutableListOf(event("ball",shot.start))
    if (shot.landing != null || shot.manual || shot.baseModel != null || (shot.trackingMode == "putt" && shot.base.size >= 2))
        result.add(event("landing",shot.start+shot.flight,shot.landing == null && shot.trackingMode != "putt"))
    return result
}

/** Decorative rail above the frame scrubber; native buttons supply accessible actions. */
internal class ShotTimelineView(context: Context) : View(context) {
    var events: List<ShotEvent> = emptyList()
    var frames = 1
    var onEvent: ((ShotEvent) -> Unit)? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun x(event: ShotEvent) = 16*density + (width-32*density).coerceAtLeast(0f)*event.frame/max(1,frames-1)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        for (event in events) {
            val x=x(event); val y=if(event.key == "ball") 6*density else 14*density
            paint.color=if(event.key == "ball") Color.YELLOW else Color.GREEN
            paint.alpha=if(event.inClip) 255 else 110; paint.strokeWidth=1.5f*density; paint.style=Paint.Style.STROKE
            canvas.drawLine(x,y,x,height.toFloat(),paint)
            if(event.key == "ball") { paint.style=Paint.Style.FILL; canvas.drawCircle(x,y,4*density,paint) }
            else { val flag=Path().apply { moveTo(x,y-5*density); lineTo(x+8*density,y-2*density); lineTo(x,y+density); close() }; canvas.drawPath(flag,paint) }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val nearest=events.filter { it.inClip }.minByOrNull { abs(x(it)-event.x) + (if(it.key=="ball") abs(event.y-6*density) else abs(event.y-14*density)) }
        if (nearest == null || abs(x(nearest)-event.x) > 24*density) return false
        if(event.actionMasked == MotionEvent.ACTION_UP) { onEvent?.invoke(nearest); performClick() }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
