package com.ajc.shottracer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ShotEvent(val key: String, val time: Double, val frame: Int, val inClip: Boolean, val estimated: Boolean = false)

/** Events use source time, independent of tracer speed and exported slow motion.
 * Index mapping matches the frame slider, including variable-rate source clips. */
internal fun shotEvents(shot: EditShot, times: DoubleArray, duration: Double): List<ShotEvent> {
    if (shot.ball == null || times.isEmpty()) return emptyList()
    fun event(key: String, time: Double, estimated: Boolean = false) = ShotEvent(key,time,
        EditorMedia.nearest(times,time),time >= times.first()-1e-6 && time <= duration+1e-6,estimated)
    val result = mutableListOf(event("ball",shot.start))
    if (shot.landing != null || shot.manual || shot.baseModel != null || (shot.trackingMode == "putt" && shot.base.size >= 2)) {
        if (shot.trackingMode != "putt") result.add(event("apex", shot.start + shot.apexSeconds()))
        result.add(event("landing",shot.start+shot.flight,shot.timingLandingEstimated ?: (shot.landing == null && shot.trackingMode != "putt")))
    }
    return result
}

/** Returns the snapped frame or null when no legal frame exists. Start edits hold
 * the absolute landing time; landing edits hold the start. Automatic data stays immutable. */
internal fun retimeShot(shot: EditShot, key: String, frame: Int, times: DoubleArray): Int? {
    if(shot.ball==null || times.isEmpty() || key !in listOf("ball","apex","landing")) return null
    val known=shot.landing!=null || shot.manual || shot.baseModel!=null || shot.base.size>=2
    if(key!="ball" && !known || key=="apex" && shot.trackingMode=="putt") return null
    val observed=shot.baseModel==null && !shot.manual && shot.landing==null && shot.base.size>=2
    val end=shot.start + if(observed) shot.base.last().time-shot.base.first().time else shot.flight
    val lo = when (key) {
        "ball" -> if (known) max(times.first(), end - 15) else times.first()
        "apex" -> shot.start + .35 * shot.flight
        else -> shot.start + .2
    }
    val hi = when (key) {
        "ball" -> if (known) minOf(times.last(), end - .2) else times.last()
        "apex" -> shot.start + .62 * shot.flight
        else -> minOf(times.last(), shot.start + 15)
    }
    fun bound(target: Double, after: Boolean): Int {
        var left=0; var right=times.size
        while(left<right) { val mid=(left+right)/2; if(times[mid]<target || (after && times[mid]==target)) left=mid+1 else right=mid }
        return left
    }
    val first=bound(lo-1e-6,false); val last=bound(hi+1e-6,true)-1
    if(first>=times.size || last<first) return null
    val selected=frame.coerceIn(first,last); val time=times[selected]
    if (key == "apex") {
        shot.apexFraction = ((time - shot.start) / shot.flight).coerceIn(.35, .62)
        return selected
    }
    if(known) {
        val estimated=shot.timingLandingEstimated ?: (shot.landing==null && shot.trackingMode!="putt")
        val point=shot.endpoint() // Freeze the spatial endpoint before changing time.
        shot.landing=point; shot.timingLandingEstimated=estimated
    }
    if(key=="ball") { shot.start=time; if(known) shot.flight=(end-time).coerceIn(.2,15.0) }
    else shot.flight=(time-shot.start).coerceIn(.2,15.0)
    return selected
}

internal fun filmTime(time: Double): String {
    val cs = (time * 100).roundToInt().coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%02d", cs / 6000, cs / 100 % 60, cs % 100)
}

internal fun filmFrames(times: DoubleArray, interval: Double): List<Int> {
    if (times.isEmpty()) return emptyList()
    val count = ceil((times.last() - times.first()) / interval).toInt()
    return (0..count).map { EditorMedia.nearest(times, times.first() + it * interval) }.distinct()
}

/** The strip moves under a fixed playhead. Marker and pan gestures share the source clock. */
internal class ShotTimelineView(context: Context) : View(context) {
    var events: List<ShotEvent> = emptyList()
    var times = doubleArrayOf()
    var currentFrame = 0
    var thumbnails: Map<Int, Bitmap> = emptyMap()
    var onSeek: ((Int) -> Unit)? = null
    var onEvent: ((ShotEvent) -> Unit)? = null
    var onBegin: (() -> Unit)? = null
    var onDrag: ((String, Int) -> Unit)? = null
    var onEnd: ((Boolean) -> Unit)? = null
    private var active: ShotEvent? = null
    private var downX = 0f
    private var origin = 0.0
    private var dragging = false
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val tablet = resources.configuration.smallestScreenWidthDp >= 600
    private val tile get() = (if (tablet) 108 else 72) * density
    private val laneHeight get() = minOf(66 * density, height * .56f)
    private val snapshotBottom get() = height - laneHeight
    private val ruler get() = minOf(18 * density, height * .18f)
    private val labelSize get() = minOf(10 * density, laneHeight * .21f)
    private fun markerY(key: String) = snapshotBottom + laneHeight * (.20f + row(key) * .25f)
    private val interval get() = if (times.isEmpty()) 1.0 else max(1.0, ceil((times.last() - times.first()) / 60))
    private val clock get() = times.getOrElse(currentFrame) { 0.0 }
    private fun x(time: Double) = width / 2f + ((time - clock) * tile / interval).toFloat()
    private fun eventX(event: ShotEvent) = x(event.time.coerceIn(times.first(), times.last()))
    private fun row(key: String) = when (key) { "ball" -> 0; "apex" -> 1; else -> 2 }
    private fun color(key: String) = when (key) { "ball" -> 0xffffdb57.toInt(); "apex" -> 0xff58d9ed.toInt(); else -> 0xff75e893.toInt() }
    private fun label(event: ShotEvent): String {
        val name = when (event.key) { "ball" -> R.string.editor_timeline_start; "apex" -> R.string.editor_timeline_apex; else -> R.string.editor_timeline_landing }
        return context.getString(name) + " " + filmTime(event.time)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        systemGestureExclusionRects = listOf(Rect(0, snapshotBottom.toInt().coerceAtLeast(0), w, h))
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (!isEnabled || times.isEmpty()) return
        info.className = "android.widget.SeekBar"
        info.contentDescription = context.getString(R.string.editor_timeline_seek)
        info.stateDescription = filmTime(clock)
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0f, times.lastIndex.toFloat(), currentFrame.toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }
    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled && times.isNotEmpty()) {
            val frame = when (action) {
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id ->
                    arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)?.roundToInt()
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> currentFrame + 1
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> currentFrame - 1
                else -> null
            }
            if (frame != null) {
                onSeek?.invoke(frame.coerceIn(0, times.lastIndex))
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun onDraw(canvas: Canvas) {
        if (times.isEmpty()) return
        canvas.drawColor(0xff101721.toInt())
        paint.textSize = labelSize
        for (index in filmFrames(times, interval)) {
            val at = x(times[index])
            if (at + tile < 0 || at - tile > width) continue
            paint.color = Color.LTGRAY
            canvas.drawText(filmTime(times[index]), at - tile / 2 + 3 * density, ruler * .78f, paint)
            val box = RectF(at - tile / 2, ruler, at + tile / 2 - density, snapshotBottom)
            paint.color = 0xff232c38.toInt()
            canvas.drawRect(box, paint)
            thumbnails[index]?.let { bitmap ->
                val scale = minOf(box.width() / bitmap.width, box.height() / bitmap.height)
                val w = bitmap.width * scale; val h = bitmap.height * scale
                canvas.drawBitmap(bitmap, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), paint)
            }
        }
        paint.color = 0xff1c2632.toInt()
        canvas.drawRect(0f, snapshotBottom, width.toFloat(), height.toFloat(), paint)
        if (events.size > 1) {
            paint.color = color("ball"); paint.strokeWidth = 3 * density
            canvas.drawLine(x(events.first().time), snapshotBottom + 3 * density, x(events.last().time), snapshotBottom + 3 * density, paint)
        }
        for (event in events) {
            val at = eventX(event)
            if (at < -tile || at > width + tile) continue
            val y = markerY(event.key) + labelSize * .35f
            paint.color = color(event.key)
            if (!event.inClip) paint.alpha = 135
            canvas.drawCircle(at, y - 3 * density, 2.5f * density, paint)
            canvas.drawText(label(event), at + 5 * density, y, paint)
        }
        paint.color = Color.LTGRAY
        if (laneHeight >= 58 * density) canvas.drawText(context.getString(R.string.editor_timeline_tracer), 5 * density, height - 3 * density, paint)
        paint.color = Color.WHITE; paint.strokeWidth = 2 * density
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || times.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = events.firstOrNull {
                    val at = eventX(it)
                    val y = markerY(it.key)
                    abs(event.y - y) <= laneHeight / 8 && event.x in (at - 16 * density)..(at + paint.measureText(label(it)) + 10 * density)
                }
                downX = event.x
                origin = active?.time?.coerceIn(times.first(), times.last()) ?: clock
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = event.x - downX
                if (!dragging && abs(delta) > slop) {
                    dragging = true
                    if (active != null) onBegin?.invoke()
                }
                if (dragging) {
                    val frame = EditorMedia.nearest(times, origin + (if (active == null) -delta else delta) * interval / tile)
                    val marker = active
                    if (marker != null) onDrag?.invoke(marker.key, frame)
                    else if (frame != currentFrame) onSeek?.invoke(frame)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                val marker = active
                if (dragging && marker != null) onEnd?.invoke(cancel)
                else if (!dragging && !cancel) {
                    if (marker != null) onEvent?.invoke(marker)
                    else onSeek?.invoke(EditorMedia.nearest(times, clock + (event.x - width / 2f) * interval / tile))
                    performClick()
                }
                active = null
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
