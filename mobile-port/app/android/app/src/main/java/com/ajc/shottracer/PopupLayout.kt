package com.ajc.shottracer

import kotlin.math.*

internal data class PopupWindow(val begins: Double, val ends: Double) {
    fun contains(time: Double) = time+1e-6>=begins && time<=ends+1e-6
}
/** A delay must never make after-shot cards expire before they appear. */
internal fun popupWindow(shot: EditShot, popup: EditPopup): PopupWindow? {
    val event=when(popup.anchor) { "landing" -> shot.flight; "apex" -> shot.flight*shot.apexFraction; else -> 0.0 }
    val begins=shot.start+event+popup.delay
    if(!begins.isFinite()) return null
    val ends=when(popup.hide) {
        "end" -> Double.POSITIVE_INFINITY
        "time" -> begins+max(.1,popup.duration.takeIf { it.isFinite() } ?: 3.0)
        else -> max(shot.start+shot.flight+1,begins+1)
    }
    return PopupWindow(begins,ends)
}
internal fun popupFrame(shot: EditShot,popup: EditPopup,times: DoubleArray,current: Int): Int? {
    if(times.isEmpty()) return null
    val window=popupWindow(shot,popup) ?: return null
    if(current in times.indices && window.contains(times[current])) return current
    var lo=0; var hi=times.size
    while(lo<hi) { val mid=(lo+hi)/2; if(times[mid]+1e-6<window.begins) lo=mid+1 else hi=mid }
    return lo.takeIf { it<times.size && window.contains(times[it]) }
}
internal data class PopupBox(val x: Double,val y: Double,val width: Double,val height: Double) {
    fun overlaps(b: PopupBox,gap: Double) = !(x+width+gap<=b.x || b.x+b.width+gap<=x || y+height+gap<=b.y || b.y+b.height+gap<=y)
}
/** Retain the requested anchor, then stack vertically before using another column. */
internal fun placePopup(x: Double,y: Double,width: Double,height: Double,w: Double,h: Double,used: List<PopupBox>,bottom: Boolean): PopupBox {
    val left=4.0; val top=4.0; val right=max(left,w-width-4); val end=max(top,h-height-4); val gap=4.0
    val startX=x.coerceIn(left,right); val startY=y.coerceIn(top,end)
    val step=height+gap; val count=ceil(h/step).toInt()+1
    val ys=(listOf(startY)+(1..count).map { startY+(if(bottom) -1 else 1)*it*step }+
        (1..count).map { startY+(if(bottom) 1 else -1)*it*step }+(0..count).map { top+it*step }).filter { it in top..end }.distinct()
    for(cx in listOf(startX,left,right).distinct()) for(cy in ys) {
        val box=PopupBox(cx,cy,width,height)
        if(used.none { it.overlaps(box,gap) }) return box
    }
    return PopupBox(startX,startY,width,height)
}
