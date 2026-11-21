package com.ajc.shottracer

import kotlin.math.abs

/** Normalized reference -> upright frame. Off-diagonal terms include image aspect. */
internal data class CameraTransform(val time: Double, val m00: Double, val m01: Double,
    val m02: Double, val m10: Double, val m11: Double, val m12: Double) {
    fun frame(p: EditPoint) = EditPoint(m00*p.x+m01*p.y+m02, m10*p.x+m11*p.y+m12)
    fun reference(p: EditPoint): EditPoint {
        val determinant=m00*m11-m01*m10
        if (abs(determinant)<1e-10) return p
        val x=p.x-m02; val y=p.y-m12
        return EditPoint((m11*x-m01*y)/determinant,(-m10*x+m00*y)/determinant)
    }
    fun values() = listOf(time,m00,m01,m02,m10,m11,m12)
}

internal fun cameraSamples(values: DoubleArray): List<CameraTransform> {
    if (values.size%7!=0) return emptyList()
    return values.asList().chunked(7).filter { row -> row.all { it.isFinite() } && abs(row[1]*row[5]-row[2]*row[4])>1e-10 }
        .map { CameraTransform(it[0],it[1],it[2],it[3],it[4],it[5],it[6]) }.sortedBy { it.time }
}

internal fun cameraAt(rows: List<CameraTransform>, time: Double): CameraTransform? {
    if (rows.isEmpty()) return null
    if (time<=rows.first().time) return rows.first()
    if (time>=rows.last().time) return rows.last()
    var low=0; var high=rows.lastIndex
    while(high-low>1) { val mid=(low+high)/2; if(rows[mid].time<time) low=mid else high=mid }
    val a=rows[low].values(); val b=rows[high].values()
    val f=(time-a[0])/(b[0]-a[0])
    val v=(1..6).map { a[it]+f*(b[it]-a[it]) }
    return CameraTransform(time,v[0],v[1],v[2],v[3],v[4],v[5])
}
