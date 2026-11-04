package com.ajc.shottracer

/** Keep the original fitted image area reachable even for letterboxed video. */
internal fun viewportPan(value: Float, fittedExtent: Float, zoom: Float): Float {
    val limit = fittedExtent.coerceAtLeast(0f) * (zoom - 1f).coerceAtLeast(0f) / 2f
    return value.coerceIn(-limit, limit)
}
