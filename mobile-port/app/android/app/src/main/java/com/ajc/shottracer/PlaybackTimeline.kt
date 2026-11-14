package com.ajc.shottracer

/** Frame exposed at this source time. Never show a future VFR frame early. */
internal fun playbackFrame(times: DoubleArray, sourceTime: Double): Int {
    require(times.isNotEmpty())
    var left=0; var right=times.size
    while(left<right) {
        val mid=(left+right)/2
        if(times[mid]<=sourceTime) left=mid+1 else right=mid
    }
    return (left-1).coerceIn(0,times.lastIndex)
}
