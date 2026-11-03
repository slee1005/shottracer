package com.ajc.shottracer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
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
        result.add(event("landing",shot.start+shot.flight,shot.timingLandingEstimated ?: (shot.landing == null && shot.trackingMode != "putt")))
    return result
}

/** Returns the snapped frame or null when no legal frame exists. Start edits hold
 * the absolute landing time; landing edits hold the start. Automatic data stays immutable. */
internal fun retimeShot(shot: EditShot, key: String, frame: Int, times: DoubleArray): Int? {
    if(shot.ball==null || times.isEmpty() || key !in listOf("ball","landing")) return null
    val known=shot.landing!=null || shot.manual || shot.baseModel!=null || shot.base.size>=2
    if(key=="landing" && !known) return null
    val observed=shot.baseModel==null && !shot.manual && shot.landing==null && shot.base.size>=2
    val end=shot.start + if(observed) shot.base.last().time-shot.base.first().time else shot.flight
    val lo=if(key=="ball") { if(known) max(times.first(),end-15.0) else times.first() } else shot.start+.2
    val hi=if(key=="ball") { if(known) minOf(times.last(),end-.2) else times.last() } else minOf(times.last(),shot.start+15.0)
    fun bound(target: Double, after: Boolean): Int {
        var left=0; var right=times.size
        while(left<right) { val mid=(left+right)/2; if(times[mid]<target || (after && times[mid]==target)) left=mid+1 else right=mid }
        return left
    }
    val first=bound(lo-1e-6,false); val last=bound(hi+1e-6,true)-1
    if(first>=times.size || last<first) return null
    val selected=frame.coerceIn(first,last); val time=times[selected]
    if(known) {
        val estimated=shot.timingLandingEstimated ?: (shot.landing==null && shot.trackingMode!="putt")
        val point=shot.endpoint() // Freeze the spatial endpoint before changing time.
        shot.landing=point; shot.timingLandingEstimated=estimated
    }
    if(key=="ball") { shot.start=time; if(known) shot.flight=(end-time).coerceIn(.2,15.0) }
    else shot.flight=(time-shot.start).coerceIn(.2,15.0)
    return selected
}

/** Captures a marker for the entire gesture, so crossing the other marker never switches it. */
internal class ShotTimelineView(context: Context) : View(context) {
    var events: List<ShotEvent> = emptyList()
    var frames = 1
    var onEvent: ((ShotEvent) -> Unit)? = null
    var onBegin: (() -> Unit)? = null
    var onDrag: ((String,Int) -> Unit)? = null
    var onEnd: ((Boolean) -> Unit)? = null
    private var active: ShotEvent?=null
    private var downX=0f
    private var dragging=false
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val slop=android.view.ViewConfiguration.get(context).scaledTouchSlop
    private fun x(event: ShotEvent) = 16*density + (width-32*density).coerceAtLeast(0f)*event.frame/max(1,frames-1)
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // A marker at the first/last frame lies inside Android's back-gesture edge.
        // Exclude only this narrow handle rail, leaving navigation elsewhere intact.
        systemGestureExclusionRects=listOf(Rect(0,0,w,h))
    }
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
        if(!isEnabled) return false
        if(event.actionMasked==MotionEvent.ACTION_DOWN) {
            active=events.minByOrNull { abs(x(it)-event.x) + abs(event.y-(if(it.key=="ball") 6*density else 14*density)) }
                ?.takeIf { abs(x(it)-event.x)<=24*density }
            if(active==null) return false
            downX=event.x; dragging=false; parent?.requestDisallowInterceptTouchEvent(true)
        }
        val target=active ?: return false
        if(event.actionMasked==MotionEvent.ACTION_MOVE || event.actionMasked==MotionEvent.ACTION_UP) {
            if(!dragging && abs(event.x-downX)>slop) { dragging=true; onBegin?.invoke() }
            if(dragging) {
                val fraction=((event.x-16*density)/max(1f,width-32*density)).coerceIn(0f,1f)
                onDrag?.invoke(target.key,kotlin.math.round(fraction*max(0,frames-1)).toInt())
            }
        }
        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
            if(dragging) onEnd?.invoke(event.actionMasked==MotionEvent.ACTION_CANCEL)
            else if(event.actionMasked==MotionEvent.ACTION_UP && target.inClip) { onEvent?.invoke(target); performClick() }
            active=null; dragging=false; parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
