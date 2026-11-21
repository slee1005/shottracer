package com.ajc.shottracer

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

internal data class ShotInfo(val value: String, val visible: Boolean)
internal val shotInfoKeys = listOf("total", "carry", "height")

internal data class EditPoint(val x: Double, val y: Double)
internal data class TimedPoint(val time: Double, val x: Double, val y: Double)
internal data class EditPopup(var kind: String = "text", var label: String = "", var value: String = "",
    var anchor: String = "landing", var delay: Double = 0.0, var hide: String = "after_shot", var duration: Double = 3.0)
internal data class EditShape(var kind: String, var a: EditPoint, var b: EditPoint, var color: String = "#FFD400", var width: Double = 1.0)
internal data class EditShot(
    val id: String = UUID.randomUUID().toString().take(6), var start: Double = 0.0,
    var ball: EditPoint? = null, var landing: EditPoint? = null, var apex: EditPoint? = null,
    var flight: Double = 4.0, var apexFraction: Double = .5, var speed: Double = 1.0,
    var color: String = "#FF2D2D", var manual: Boolean = false,
    var base: List<TimedPoint> = emptyList(), var camera: List<TimedPoint> = emptyList(),
    var swing: MutableList<TimedPoint> = mutableListOf(), var popups: MutableList<EditPopup> = mutableListOf(),
    var baseModel: DoubleArray? = null, var trackingMode: String = "flight",
    var autoHands: List<TimedPoint> = emptyList(), var autoClub: List<TimedPoint> = emptyList(),
    var masks: List<PersonMask> = emptyList(), var timingLandingEstimated: Boolean? = null, var landingConfirmed: Boolean = false,
    val shotInfo: MutableMap<String, ShotInfo> = mutableMapOf(),
    var cameraTransforms: List<CameraTransform> = emptyList()
) {
    fun hasTrace() = ball != null && (edited() || base.isNotEmpty()) &&
        (!landingConfirmed || manual || base.isNotEmpty())
    fun readyToAnalyze() = ball != null && landing != null && landingConfirmed && flight in .2..15.0
    fun model(): DoubleArray? {
        val base = baseModel ?: return null
        if (trackingMode == "putt" || manual || apex != null) return null
        val result = if (landing == null && abs(flight - base[10]) < 1e-8) base else
            NativeCore.refit(base, observations(), landing?.let { doubleArrayOf(it.x, it.y) }, flight) ?: return null
        return if (abs(flight * apexFraction - NativeCore.modelApex(result)) > 1e-5)
            NativeCore.reshape(result, observations(), flight * apexFraction) ?: result else result
    }
    fun apexSeconds() = model()?.let { NativeCore.modelApex(it) } ?: flight * apexFraction
    fun observations() = base.drop(1).flatMap { listOf(it.time, it.x, it.y) }.toDoubleArray()
    fun modelPoint(time: Double): EditPoint? = model()?.let { NativeCore.modelPoint(it, time)?.let { p -> EditPoint(p[0], p[1]) } }
    fun motion(time: Double): EditPoint = interpolate(camera, time).let { EditPoint(it.x, it.y) }
    fun toFrame(p: EditPoint, time: Double): EditPoint = cameraAt(cameraTransforms,time)?.frame(p)
        ?: motion(time).let { EditPoint(p.x+it.x,p.y+it.y) }
    fun toReference(p: EditPoint, time: Double): EditPoint = cameraAt(cameraTransforms,time)?.reference(p)
        ?: motion(time).let { EditPoint(p.x-it.x,p.y-it.y) }
    fun endpoint() = landing ?: modelPoint(flight) ?: base.lastOrNull()?.let { EditPoint(it.x, it.y) } ?: EditPoint(.75, .65)
    fun peak(): EditPoint {
        if (trackingMode == "putt") return apex ?: ground(flight*.5)
        val a = ball ?: EditPoint(.2, .8); val b = endpoint()
        return apex ?: model()?.let { m -> NativeCore.modelPoint(m, NativeCore.modelApex(m))?.let { EditPoint(it[0], it[1]) } } ?: EditPoint((a.x + b.x) / 2, min(a.y, b.y) - .25)
    }
    fun curve(time: Double): EditPoint {
        val a = ball ?: EditPoint(.2, .8); val b = peak(); val c = endpoint()
        val p = NativeCore.curve(doubleArrayOf(a.x, a.y, b.x, b.y, c.x, c.y), flight, apexFraction, speed, time)
        return EditPoint(p[0], p[1])
    }
    fun hasGroundPath() = ball != null && (base.size >= 2 || landing != null)
    fun ground(time: Double): EditPoint {
        val p=unbentGround(time); val middle=apex ?: return p
        val reference=unbentGround(flight*.5)
        val f=(time/max(.01,flight)).coerceIn(0.0,1.0); val weight=4*f*(1-f)
        // Pass through the editable midpoint, keeping endpoints and observations exact.
        return EditPoint(p.x+(middle.x-reference.x)*weight,p.y+(middle.y-reference.y)*weight)
    }
    private fun unbentGround(time: Double): EditPoint {
        val f = (time / max(.01,flight)).coerceIn(0.0,1.0)
        // A manually placed landing is a complete putt even without analysis.
        // Never interpolate an empty observation list from the frame origin.
        if (base.size < 2) {
            val a = ball ?: EditPoint(0.0,0.0); val b = landing ?: a
            return EditPoint(a.x+(b.x-a.x)*f,a.y+(b.y-a.y)*f)
        }
        val origin=base.firstOrNull()?.time ?: start
        val span = max(.01,(base.lastOrNull()?.time ?: origin)-origin)
        val p = interpolate(base,origin+f*span); val end = base.lastOrNull()
        val ease = ((f-.6)/.4).coerceIn(0.0,1.0).pow(2)
        return EditPoint(p.x + (landing?.x?.minus(end?.x ?: p.x) ?: 0.0)*ease,
            p.y + (landing?.y?.minus(end?.y ?: p.y) ?: 0.0)*ease)
    }
    fun edited() = baseModel != null || manual || landing != null || apex != null
}
internal fun interpolate(points: List<TimedPoint>, time: Double): TimedPoint {
    if (points.isEmpty()) return TimedPoint(time, 0.0, 0.0)
    if (time <= points.first().time) return points.first()
    if (time >= points.last().time) return points.last()
    var low = 0; var high = points.lastIndex
    while (high - low > 1) { val mid = (low + high) / 2; if (points[mid].time < time) low = mid else high = mid }
    val a = points[low]; val b = points[high]; val f = (time - a.time) / max(1e-9, b.time - a.time)
    return TimedPoint(time, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
}
internal fun samples(rows: DoubleArray): List<TimedPoint> = rows.asList().chunked(3).map { TimedPoint(it[0], it[1], it[2]) }

/** Persist edits and immutable automatic observations separately; undo never re-analyzes. */
internal class EditorScene {
    var width = 640; var height = 480
    var selected = 0
    val shots = mutableListOf(EditShot())
    val shapes = mutableListOf<EditShape>()
    var preset = "classic"; var lineWidth = 1.0; var glow = 1.0; var hold = 1.0
    var head = true; var marker = true; var swingEnabled = true
    var personOcclusion = true; var autoSwing = "off"
    // Legacy "both" was a default, so it does not prove an explicit club-path choice.
    var clubTracingEnabled = false
    val autoWristsEnabled get() = autoSwing == "both" || autoSwing == "hands"

    var logo: String? = null; var logoSize = .12; var logoOpacity = .85; var logoCorner = "bottom-right"
    var slowmo = false; var slowFactor = 3.0; var slowBefore = .4; var slowAfter = 1.2
    val shot: EditShot get() = shots[selected.coerceIn(0, shots.lastIndex)]
    fun copy() = read(json())
    fun json(): JSONObject {
        fun point(p: EditPoint?) = p?.let { JSONArray(listOf(it.x, it.y)) } ?: JSONObject.NULL
        fun rows(p: List<TimedPoint>) = JSONArray(p.map { JSONArray(listOf(it.time, it.x, it.y)) })
        return JSONObject().apply {
            put("version", 1); put("width", width); put("height", height); put("selected", selected)
            put("shots", JSONArray(shots.map { s -> JSONObject().apply {
                put("id", s.id); put("start", s.start); put("ball", point(s.ball)); put("landing", point(s.landing)); put("apex", point(s.apex))
                put("flight", s.flight); put("apexFraction", s.apexFraction); put("speed", s.speed); put("color", s.color); put("manual", s.manual)
                put("base", rows(s.base)); put("camera", rows(s.camera)); put("swing", rows(s.swing))
                put("cameraTransforms", JSONArray(s.cameraTransforms.map { JSONArray(it.values()) }))
                put("timingLandingEstimated",s.timingLandingEstimated ?: JSONObject.NULL)
                put("trackingMode", s.trackingMode); put("autoHands", rows(s.autoHands)); put("autoClub", rows(s.autoClub))
                put("masks", JSONArray(s.masks.map { JSONObject().put("time", it.time).put("path", it.path) }))
                put("landingConfirmed", s.landingConfirmed)
                put("shotInfo", JSONObject().apply {
                    s.shotInfo.forEach { (key, info) ->
                        put(key, JSONObject().put("value", info.value).put("visible", info.visible))
                    }
                })
                put("baseModel", s.baseModel?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
                put("popups", JSONArray(s.popups.map { p -> JSONObject().apply {
                    put("kind", p.kind); put("label", p.label); put("value", p.value); put("anchor", p.anchor)
                    put("delay", p.delay); put("hide", p.hide); put("duration", p.duration)
                } }))
            } }))
            put("shapes", JSONArray(shapes.map { s -> JSONObject().apply {
                put("kind", s.kind); put("a", point(s.a)); put("b", point(s.b)); put("color", s.color); put("width", s.width)
            } }))
            put("preset", preset); put("lineWidth", lineWidth); put("glow", glow); put("hold", hold)
            put("personOcclusion",personOcclusion); put("autoSwing",autoSwing); put("clubTracingEnabled",clubTracingEnabled)
            put("head", head); put("marker", marker); put("swingEnabled", swingEnabled)
            put("logo", logo ?: JSONObject.NULL); put("logoSize", logoSize); put("logoOpacity", logoOpacity); put("logoCorner", logoCorner)
            put("slowmo", slowmo); put("slowFactor", slowFactor); put("slowBefore", slowBefore); put("slowAfter", slowAfter)
        }
    }
    companion object {
        fun read(j: JSONObject): EditorScene {
            fun point(a: JSONArray?) = a?.let { EditPoint(it.optDouble(0), it.optDouble(1)) }
            fun rows(a: JSONArray?): List<TimedPoint> = (0 until (a?.length() ?: 0)).map { i ->
                val r = a!!.getJSONArray(i); TimedPoint(r.getDouble(0), r.getDouble(1), r.getDouble(2)) }.sortedBy { it.time }
            return EditorScene().apply {
                width = j.optInt("width", 640); height = j.optInt("height", 480)
                j.optJSONArray("shots")?.let { array ->
                    shots.clear()
                    for (i in 0 until min(4, array.length())) {
                        val s = array.getJSONObject(i)
                        val shot = EditShot(s.optString("id", UUID.randomUUID().toString().take(6)), s.optDouble("start", 0.0),
                            point(s.optJSONArray("ball")), point(s.optJSONArray("landing")), point(s.optJSONArray("apex")),
                            s.optDouble("flight", 4.0).coerceIn(.2, 15.0), s.optDouble("apexFraction", .5).coerceIn(.35, .62),
                            s.optDouble("speed", 1.0).coerceIn(.5, 4.0), s.optString("color", "#FF2D2D"), s.optBoolean("manual"),
                            rows(s.optJSONArray("base")), rows(s.optJSONArray("camera")), rows(s.optJSONArray("swing")).toMutableList())
                        s.optJSONArray("popups")?.let { p -> for (k in 0 until p.length()) {
                            val v = p.getJSONObject(k); shot.popups.add(EditPopup(v.optString("kind", "text"), v.optString("label"),
                                v.optString("value"), v.optString("anchor", "landing"), v.optDouble("delay"),
                                v.optString("hide", if (v.has("duration")) "time" else "after_shot"), v.optDouble("duration", 3.0)))
                        } }
                        shot.timingLandingEstimated=if(s.isNull("timingLandingEstimated")) null else s.optBoolean("timingLandingEstimated")
                        shot.landingConfirmed = s.optBoolean("landingConfirmed")
                        val info = s.optJSONObject("shotInfo")
                        for (key in shotInfoKeys) {
                            val entry = info?.optJSONObject(key) ?: continue
                            shot.shotInfo[key] = ShotInfo(entry.optString("value"), entry.optBoolean("visible"))
                        }
                        shot.trackingMode = s.optString("trackingMode","flight")
                        shot.autoHands = rows(s.optJSONArray("autoHands")); shot.autoClub = rows(s.optJSONArray("autoClub"))
                        s.optJSONArray("cameraTransforms")?.let { a ->
                            shot.cameraTransforms=cameraSamples((0 until a.length()).flatMap { index ->
                                val row=a.getJSONArray(index)
                                if(row.length()!=7) emptyList() else (0 until 7).map { row.getDouble(it) }
                            }.toDoubleArray())
                        }
                        s.optJSONArray("masks")?.let { a -> shot.masks = (0 until a.length()).map { i -> a.getJSONObject(i).let { PersonMask(it.getDouble("time"),it.getString("path")) } }.sortedBy { it.time } }
                        shots.add(shot)
                        s.optJSONArray("baseModel")?.takeIf { it.length() == 16 }?.let { a -> shot.baseModel = DoubleArray(16) { a.getDouble(it) } }
                    }
                    if (shots.isEmpty()) shots.add(EditShot())
                }
                selected = j.optInt("selected").coerceIn(0, shots.lastIndex)
                j.optJSONArray("shapes")?.let { a -> for (i in 0 until a.length()) {
                    val s = a.getJSONObject(i); val p = point(s.optJSONArray("a")); val q = point(s.optJSONArray("b"))
                    if (p != null && q != null) shapes.add(EditShape(s.optString("kind", "line"), p, q,
                        s.optString("color", "#FFD400"), s.optDouble("width", 1.0)))
                } }
                preset = j.optString("preset", "classic"); lineWidth = j.optDouble("lineWidth", 1.0).coerceIn(.2, 5.0)
                glow = j.optDouble("glow", 1.0).coerceIn(0.0, 3.0); hold = j.optDouble("hold", 1.0)
                personOcclusion = j.optBoolean("personOcclusion",true); autoSwing = j.optString("autoSwing","off"); clubTracingEnabled = j.optBoolean("clubTracingEnabled",false)
                head = j.optBoolean("head", true); marker = j.optBoolean("marker", true); swingEnabled = j.optBoolean("swingEnabled", true)
                logo = if (j.isNull("logo")) null else j.optString("logo").takeIf { it.isNotBlank() }
                logoSize = j.optDouble("logoSize", .12); logoOpacity = j.optDouble("logoOpacity", .85); logoCorner = j.optString("logoCorner", "bottom-right")
                slowmo = j.optBoolean("slowmo"); slowFactor = j.optDouble("slowFactor", 3.0).coerceIn(1.0, 8.0)
                slowBefore = j.optDouble("slowBefore", .4); slowAfter = j.optDouble("slowAfter", 1.2)
            }
        }
    }
}

internal data class ShotMeasurement(val amount: Double, val unit: String) {
    val metres: Double get() = amount * when (unit) {
        "yds" -> .9144
        "ft" -> .3048
        else -> 1.0
    }
}

internal fun shotMeasurement(value: String?): ShotMeasurement? {
    val match = Regex("""^([0-9]+(?:[.,][0-9]+)?)\s*(m|yd|yds|ft|미터|야드|피트)?$""", RegexOption.IGNORE_CASE)
        .matchEntire(value.orEmpty().trim()) ?: return null
    val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
    val unit = when (match.groupValues[2].lowercase()) {
        "yd", "yds", "야드" -> "yds"
        "ft", "피트" -> "ft"
        else -> "m"
    }
    return ShotMeasurement(amount, unit)
}

internal fun distanceInput(value: String, unit: String): String {
    val measurement = shotMeasurement(value) ?: return value
    val amount = if (measurement.unit == unit) measurement.amount
        else measurement.metres / if (unit == "yds") .9144 else 1.0
    return java.math.BigDecimal.valueOf(amount).setScale(6, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString()
}

internal class PathProgress(points: List<EditPoint>, width: Int, height: Int) {
    private val lengths = DoubleArray(points.size).apply {
        for (i in 1 until size) {
            val a = points[i - 1]
            val b = points[i]
            this[i] = this[i - 1] + hypot((b.x - a.x) * width, (b.y - a.y) * height)
        }
    }

    fun fraction(phase: Double): Double {
        if (lengths.last() <= 1e-9) return if (phase >= 1) 1.0 else 0.0
        val index = phase.coerceIn(0.0, 1.0) * lengths.lastIndex
        val lower = index.toInt()
        val upper = min(lower + 1, lengths.lastIndex)
        return (lengths[lower] + (lengths[upper] - lengths[lower]) * (index - lower)) / lengths.last()
    }
}

private class ShotTrajectory(shot: EditShot, width: Int, height: Int) {
    private val model = shot.model()
    val total = shotMeasurement(shot.shotInfo["total"]?.value)
    private val carry = shotMeasurement(shot.shotInfo["carry"]?.value)?.metres ?: 0.0
    private val profile = if (shot.trackingMode == "putt" || shot.shotInfo.isEmpty()) doubleArrayOf(shot.flight, 1.0, 0.0)
        else NativeCore.shotInfoProfile(carry, total?.metres ?: 0.0,
            shotMeasurement(shot.shotInfo["height"]?.value)?.metres ?: 0.0, shot.flight)
    val airTime = profile[0]
    private val riseScale = profile[1]
    private val rollRatio = profile[2]
    private val rollTime = airTime * rollRatio
    val duration = airTime + rollTime
    val landing = if (shot.hasTrace()) shot.endpoint() else null
    private val airProgress = if (total != null && shot.shotInfo["total"]?.visible == true && landing != null)
        PathProgress((0..128).map { point(shot, airTime * it / 128) }, width, height) else null
    private val carryShare = if (total != null && rollTime > 0) carry / total.metres else 1.0

    fun point(shot: EditShot, time: Double): EditPoint {
        val fraction = (time / airTime).coerceIn(0.0, 1.0)
        val sourceTime = fraction * shot.flight
        val start = shot.ball
        if (start != null && landing != null && time > airTime && rollTime > 0) {
            val progress = rollProgress(time)
            return EditPoint(landing.x + (landing.x - start.x) * rollRatio * progress,
                landing.y + (landing.y - start.y) * rollRatio * progress)
        }
        val point = if (shot.trackingMode == "putt") shot.ground(sourceTime)
            else model?.let { NativeCore.modelPoint(it, sourceTime)?.let { p -> EditPoint(p[0], p[1]) } }
                ?: shot.curve(sourceTime / shot.speed)
        if (start == null || landing == null || riseScale == 1.0) return point
        val groundY = start.y + (landing.y - start.y) * fraction
        return EditPoint(point.x, groundY + (point.y - groundY) * riseScale)
    }

    private fun rollProgress(time: Double): Double {
        val remaining = 1 - ((time - airTime) / rollTime).coerceIn(0.0, 1.0)
        return 1 - remaining * remaining
    }

    fun distance(time: Double): String? {
        val measurement = total ?: return null
        val path = airProgress ?: return null
        val fraction = if (time > airTime && rollTime > 0) carryShare + (1 - carryShare) * rollProgress(time)
            else carryShare * path.fraction(time / airTime)
        val number = java.text.NumberFormat.getNumberInstance().apply {
            isGroupingUsed = false
            maximumFractionDigits = if (measurement.amount < 10 || time >= duration) 2 else 0
            roundingMode = if (time >= duration) java.math.RoundingMode.HALF_UP else java.math.RoundingMode.DOWN
        }.format(if (time >= duration) measurement.amount else measurement.amount * fraction)
        return "$number ${measurement.unit}"
    }
}

private fun drawDistance(canvas: Canvas, label: String, point: PointF, w: Int, h: Int, paint: Paint) {
    val size = min(w, h).toFloat()
    paint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    paint.textSize = max(1f, size * .052f)
    val labelWidth = paint.measureText(label)
    if (labelWidth > w * .85f) paint.textSize *= w * .85f / labelWidth
    val half = paint.measureText(label) / 2
    val margin = size * .018f
    val x = (point.x - half).coerceIn(margin, w - 2 * half - margin)
    val y = max(margin - paint.ascent(), min(h - margin - paint.descent(), point.y - size * .045f))
    paint.strokeWidth = max(1f, paint.textSize * .12f)
    paint.style = Paint.Style.STROKE
    paint.color = Color.BLACK
    canvas.drawText(label, x, y, paint)
    paint.style = Paint.Style.FILL
    paint.color = Color.WHITE
    canvas.drawText(label, x, y, paint)
}

private fun ribbon(points: List<PointF>, width: Float): Path {
    val distinct = points.fold(mutableListOf<PointF>()) { result, point ->
        if (result.lastOrNull() != point) result.add(point)
        result
    }
    val path = Path()
    if (distinct.size < 2) return path
    val edges = distinct.mapIndexed { index, point ->
        val a = distinct[max(0, index - 1)]
        val b = distinct[min(distinct.lastIndex, index + 1)]
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy).coerceAtLeast(.001f)
        val half = width * (.14f + .70f * index / distinct.lastIndex)
        val x = -dy * half / length
        val y = dx * half / length
        PointF(point.x + x, point.y + y) to PointF(point.x - x, point.y - y)
    }
    path.moveTo(edges.first().first.x, edges.first().first.y)
    edges.drop(1).forEach { path.lineTo(it.first.x, it.first.y) }
    edges.asReversed().forEach { path.lineTo(it.second.x, it.second.y) }
    path.close()
    return path
}

private fun drawLandingSplash(canvas: Canvas, point: PointF, size: Float, progress: Float, paint: Paint) {
    val spread = size * .09f
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = max(1f, size * .006f)
    for (ring in 0..1) {
        val phase = ((progress - ring * .16f) / (1 - ring * .16f)).coerceIn(0f, 1f)
        if (progress < ring * .16f) continue
        val radius = spread * (.15f + phase)
        paint.color = Color.argb(((1 - phase) * 220).toInt(), 87, 225, 255)
        canvas.drawOval(point.x - radius, point.y - radius * .35f, point.x + radius, point.y + radius * .35f, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = Color.argb(((1 - progress) * 245).toInt(), 171, 244, 255)
    for (drop in -2..2) {
        val x = point.x + drop * spread * progress * .55f
        val y = point.y - spread * (1.15f - abs(drop) * .15f) * 4 * progress * (1 - progress)
        canvas.drawCircle(x, y, max(1f, size * .007f) * (1 - progress * .6f), paint)
    }
    paint.alpha = 255
}

/** One renderer instance serves both preview and export from an immutable scene snapshot. */
internal class EditorRenderer(private val scene: EditorScene, private val shotInfoLabel: (String) -> String) {
    private val logo = scene.logo?.let { BitmapFactory.decodeFile(it) }
    private val maskCache = android.util.LruCache<String,Bitmap>(6)
    private val trajectories = scene.shots.associate { it.id to ShotTrajectory(it, scene.width, scene.height) }
    fun overlay(time: Double): Bitmap {
        val w = scene.width; val h = scene.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        fun color(text: String) = try { Color.parseColor(text) } catch (_: Exception) { Color.RED }
        fun pos(p: EditPoint, motion: EditPoint = EditPoint(0.0, 0.0)) = PointF(((p.x + motion.x) * w).toFloat(), ((p.y + motion.y) * h).toFloat())
        val occupied=mutableListOf<PopupBox>()
        for (s in scene.shots) {
            fun frame(p: EditPoint) = pos(s.toFrame(p,time))
            val trajectory = trajectories.getValue(s.id)
            val airTime = trajectory.airTime
            val duration = trajectory.duration
            val landing = trajectory.landing
            val elapsed = (time - s.start) * s.speed
            val visible = elapsed >= 0 && (scene.hold < 0 || time <= s.start + duration / s.speed + scene.hold)
            val points = mutableListOf<EditPoint>()
            if (visible && s.hasTrace() && (s.trackingMode != "putt" || s.hasGroundPath())) {
                val from = if (scene.preset == "comet") max(0.0, elapsed - .7) else 0.0
                val to = min(duration, elapsed)
                for (index in 0..128) points.add(trajectory.point(s, from.coerceAtMost(to) + (to - from).coerceAtLeast(0.0) * index / 128))
            }
            canvas.saveLayer(0f,0f,w.toFloat(),h.toFloat(),null)
            if (points.size >= 2) {
                val width = (max(1.5, h / 240.0) * scene.lineWidth).toFloat()
                val path = ribbon(points.map(::frame), width)
                paint.style = Paint.Style.FILL
                paint.color = color(s.color)
                if (scene.preset != "minimal" && scene.glow > 0) {
                    paint.maskFilter = BlurMaskFilter((width * scene.glow).toFloat(), BlurMaskFilter.Blur.NORMAL)
                    paint.alpha = 140
                    canvas.drawPath(path, paint)
                    paint.maskFilter = null
                }
                paint.alpha = 255
                canvas.drawPath(path, paint)
                val end = frame(points.last())
                if (scene.head) { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(end.x, end.y, width, paint) }
                if (scene.marker && elapsed >= airTime && landing != null) {
                    val contact = frame(landing)
                    paint.style = Paint.Style.STROKE
                    paint.color = color(s.color)
                    paint.strokeWidth = 2f
                    canvas.drawCircle(contact.x, contact.y, (h * .02).toFloat(), paint)
                }
            }
            val splashAge = time - (s.start + airTime / s.speed)
            if (scene.marker && s.hasTrace() && splashAge >= -1e-6 && splashAge < .65) {
                drawLandingSplash(canvas, frame(s.endpoint()), min(w, h).toFloat(), (splashAge / .65).coerceAtLeast(0.0).toFloat(), paint)
            }
            if (scene.personOcclusion) s.masks.minByOrNull { abs(it.time-time) }?.takeIf { abs(it.time-time) <= .11 }?.let { m ->
                val mask = maskCache.get(m.path) ?: BitmapFactory.decodeFile(m.path)?.also { maskCache.put(m.path,it) }
                if (mask != null) canvas.drawBitmap(mask,null,Rect(0,0,w,h),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            canvas.restore()
            points.lastOrNull()?.let { tip ->
                trajectory.distance(min(elapsed, duration))?.let { label ->
                    // Sample source-time camera offsets symmetrically so seeks and exports agree.
                    val before = s.toFrame(tip,time - .045)
                    val after = s.toFrame(tip,time + .045)
                    val now = s.toFrame(tip,time)
                    val stable = EditPoint((before.x + 2 * now.x + after.x) / 4, (before.y + 2 * now.y + after.y) / 4)
                    drawDistance(canvas, label, pos(stable), w, h, paint)
                }
            }
            val infoCards = if (s.hasTrace()) shotInfoKeys.filter { it != "total" }.mapNotNull { key ->
                val info = s.shotInfo[key]?.takeIf { it.visible && it.value.isNotBlank() } ?: return@mapNotNull null
                val value = if (key == "carry") shotMeasurement(info.value)?.let { "${distanceInput(info.value, it.unit)} ${it.unit}" } ?: info.value else info.value
                EditPopup(label = shotInfoLabel(key), value = value, anchor = "top-right")
            } else emptyList()
            for (p in s.popups + infoCards) {
                val window=popupWindow(s,p) ?: continue
                if(!window.contains(time)) continue
                val at = when (p.anchor) {
                    "start" -> frame(s.ball ?: EditPoint(.1, .8))
                    "apex" -> frame(s.peak())
                    "landing" -> frame(s.endpoint())
                    "top-right" -> PointF(w * .72f, h * .08f)
                    "bottom-left" -> PointF(w * .04f, h * .8f)
                    "bottom-right" -> PointF(w * .72f, h * .8f)
                    else -> PointF(w * .04f, h * .08f)
                }
                val value=if(p.kind=="hangtime" && p.value.isEmpty()) String.format(java.util.Locale.ROOT,"%.2f s",s.flight) else p.value
                if(p.label.isBlank() && value.isBlank()) continue
                paint.style=Paint.Style.FILL; paint.alpha=255; paint.typeface=Typeface.DEFAULT_BOLD
                val labelSize=max(10f,h*.025f); val valueSize=max(15f,h*.044f)
                paint.textSize=labelSize; val labelWidth=paint.measureText(p.label)
                paint.textSize=valueSize; val valueWidth=paint.measureText(value)
                val bw=min(w*.9f,max(labelWidth,valueWidth)+24); val bh=max(40f,h*.12f)
                val box=placePopup(at.x.toDouble(),at.y.toDouble(),bw.toDouble(),bh.toDouble(),w.toDouble(),h.toDouble(),occupied,p.anchor.startsWith("bottom"))
                occupied.add(box); val x=box.x.toFloat(); val y=box.y.toFloat()
                paint.color=Color.argb(235,18,20,26); canvas.drawRoundRect(x,y,x+bw,y+bh,6f,6f,paint)
                paint.color=color(s.color); canvas.drawRect(x,y,x+3,y+bh,paint)
                fun text(line: String,size: Float,baseline: Float,ink: Int) {
                    paint.textSize=size; paint.color=ink
                    val clipped=android.text.TextUtils.ellipsize(line,android.text.TextPaint(paint),max(1f,bw-20),android.text.TextUtils.TruncateAt.END).toString()
                    canvas.drawText(clipped,x+10,y+baseline,paint)
                }
                if(value.isBlank()) text(p.label,valueSize,bh*.66f,Color.WHITE)
                else {
                    text(p.label,labelSize,bh*.34f,Color.LTGRAY)
                    text(value,valueSize,bh*.82f,Color.WHITE)
                }
            }
        }
        for (shape in scene.shapes) {
            paint.style = Paint.Style.STROKE; paint.color = color(shape.color); paint.strokeWidth = (max(1.5, h / 240.0) * shape.width).toFloat()
            val a = pos(shape.a); val b = pos(shape.b)
            if (shape.kind == "circle") canvas.drawCircle(a.x, a.y, hypot(b.x - a.x, b.y - a.y), paint) else canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        logo?.let {
            val width = (w * scene.logoSize).toFloat(); val height = width * it.height / it.width
            val x = if (scene.logoCorner.endsWith("right")) w - width - w * .03f else w * .03f
            val y = if (scene.logoCorner.startsWith("bottom")) h - height - h * .05f else h * .03f
            paint.style = Paint.Style.FILL; paint.alpha = (scene.logoOpacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(it, null, RectF(x, y, x + width, y + height), paint); paint.alpha = 255
        }
        val watermark = TrackingSession.watermarkOnly(w, h); canvas.drawBitmap(watermark, 0f, 0f, null); watermark.recycle()
        return bitmap
    }
}
