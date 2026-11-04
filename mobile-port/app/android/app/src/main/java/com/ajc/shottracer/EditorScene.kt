package com.ajc.shottracer

import android.graphics.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

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
    var masks: List<PersonMask> = emptyList(), var timingLandingEstimated: Boolean? = null
) {
    fun model(): DoubleArray? {
        val base = baseModel ?: return null
        if (trackingMode == "putt" || manual || apex != null) return null
        val result = if (landing == null && abs(flight - base[10]) < 1e-8) base else
            NativeCore.refit(base, observations(), landing?.let { doubleArrayOf(it.x, it.y) }, flight) ?: return null
        return if (abs(apexFraction - NativeCore.modelApex(base) / base[10]) > .001)
            NativeCore.reshape(result, observations(), flight * apexFraction) ?: result else result
    }
    fun observations() = base.drop(1).flatMap { listOf(it.time, it.x, it.y) }.toDoubleArray()
    fun modelPoint(time: Double): EditPoint? = model()?.let { NativeCore.modelPoint(it, time)?.let { p -> EditPoint(p[0], p[1]) } }
    fun motion(time: Double): EditPoint = interpolate(camera, time).let { EditPoint(it.x, it.y) }
    fun endpoint() = landing ?: modelPoint(flight) ?: base.lastOrNull()?.let { EditPoint(it.x, it.y) } ?: EditPoint(.75, .65)
    fun peak(): EditPoint {
        if (trackingMode == "putt") return apex ?: ground(flight*.5)
        val a = ball ?: EditPoint(.2, .8); val b = endpoint()
        return apex ?: modelPoint(flight * apexFraction) ?: EditPoint((a.x + b.x) / 2, min(a.y, b.y) - .25)
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
    fun showAutoClub() = swingEnabled && clubTracingEnabled && autoSwing in listOf("both","club")
    fun visibleAutoSwing() = if (clubTracingEnabled) autoSwing else when(autoSwing) { "both" -> "hands"; "club" -> "off"; else -> autoSwing }
    fun configureAutoSwing(mode: String) {
        autoSwing = mode.takeIf { it in listOf("both","hands","club","off") } ?: "off"
        clubTracingEnabled = autoSwing in listOf("both","club")
    }
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
                put("timingLandingEstimated",s.timingLandingEstimated ?: JSONObject.NULL)
                put("trackingMode", s.trackingMode); put("autoHands", rows(s.autoHands)); put("autoClub", rows(s.autoClub))
                put("masks", JSONArray(s.masks.map { JSONObject().put("time", it.time).put("path", it.path) }))
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
                        shot.trackingMode = s.optString("trackingMode","flight")
                        shot.autoHands = rows(s.optJSONArray("autoHands")); shot.autoClub = rows(s.optJSONArray("autoClub"))
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

/** One renderer instance serves both preview and export from an immutable scene snapshot. */
internal class EditorRenderer(private val scene: EditorScene) {
    private val logo = scene.logo?.let { BitmapFactory.decodeFile(it) }
    private val maskCache = android.util.LruCache<String,Bitmap>(6)
    private val models = scene.shots.associate { it.id to it.model() }
    fun overlay(time: Double): Bitmap {
        val w = scene.width; val h = scene.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        fun color(text: String) = try { Color.parseColor(text) } catch (_: Exception) { Color.RED }
        fun pos(p: EditPoint, motion: EditPoint = EditPoint(0.0, 0.0)) = PointF(((p.x + motion.x) * w).toFloat(), ((p.y + motion.y) * h).toFloat())
        val occupied=mutableListOf<PopupBox>()
        for (s in scene.shots) {
            val motion = s.motion(time)
            val model = models[s.id]
            fun curve(t: Double): EditPoint = if (s.trackingMode == "putt" || (model==null && !s.manual && s.apex==null && s.base.isNotEmpty())) s.ground(t) else model?.let { NativeCore.modelPoint(it, t)?.let { p -> EditPoint(p[0], p[1]) } } ?: s.curve(t / s.speed)
            val duration = if (s.edited() || s.trackingMode == "putt") s.flight else max(.01, (s.base.lastOrNull()?.time ?: s.start) - s.start)
            val elapsed = (time - s.start) * s.speed
            val visible = time >= s.start && (scene.hold < 0 || time <= s.start + duration / s.speed + scene.hold)
            val points = mutableListOf<EditPoint>()
            if (visible && s.ball != null && (s.trackingMode != "putt" || s.hasGroundPath())) {
                val from = if (scene.preset == "comet") max(0.0, elapsed - .7) else 0.0
                if (s.edited() || s.trackingMode == "putt") {
                    for (i in 0..128) { val t = duration * i / 128; if (t in from..min(duration, elapsed)) points.add(curve(t)) }
                    if (elapsed >= 0) points.add(curve(min(duration, elapsed)))
                } else if (s.base.isNotEmpty()) {
                    s.base.filter { it.time - s.start in from..elapsed }.forEach { points.add(EditPoint(it.x, it.y)) }
                    if (elapsed >= 0) interpolate(s.base, s.start + min(duration, elapsed)).let { points.add(EditPoint(it.x, it.y)) }
                }
            }
            canvas.saveLayer(0f,0f,w.toFloat(),h.toFloat(),null)
            if (points.size >= 2) {
                val path = Path(); points.forEachIndexed { i, p -> val q = pos(p, motion); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
                val width = max(1.5, h / 240.0) * scene.lineWidth
                paint.style = Paint.Style.STROKE; paint.color = color(s.color); paint.strokeWidth = width.toFloat()
                if (scene.preset != "minimal" && scene.glow > 0) {
                    paint.maskFilter = BlurMaskFilter((width * scene.glow * if (scene.preset == "neon") 2 else 1).toFloat(), BlurMaskFilter.Blur.NORMAL)
                    paint.alpha = 170; paint.strokeWidth = (width * 2).toFloat(); canvas.drawPath(path, paint); paint.maskFilter = null
                }
                paint.alpha = 255; paint.color = Color.BLACK; paint.strokeWidth = (width + 2).toFloat(); canvas.drawPath(path, paint)
                paint.color = color(s.color); paint.strokeWidth = width.toFloat(); canvas.drawPath(path, paint)
                if (scene.preset != "minimal") { paint.color = if (scene.preset == "neon") color(s.color) else Color.WHITE; paint.strokeWidth = (width * .45).toFloat(); canvas.drawPath(path, paint) }
                val end = pos(points.last(), motion)
                if (scene.head) { paint.style = Paint.Style.FILL; paint.color = Color.WHITE; canvas.drawCircle(end.x, end.y, width.toFloat(), paint) }
                if (scene.marker && elapsed >= duration) { paint.style = Paint.Style.STROKE; paint.color = color(s.color); paint.strokeWidth = 2f; canvas.drawCircle(end.x, end.y, (h * .02).toFloat(), paint) }
            }
            if (scene.swingEnabled && s.swing.size > 1) {
                val path = Path(); s.swing.filter { it.time <= time }.forEachIndexed { i, p -> val q = pos(EditPoint(p.x, p.y), motion); if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y) }
                paint.style = Paint.Style.STROKE; paint.color = Color.CYAN; paint.strokeWidth = max(2f, h / 240f); canvas.drawPath(path, paint)
            }
            if (scene.swingEnabled) {
                for ((rows,c) in listOf(s.autoHands to Color.rgb(45,212,191),s.autoClub to Color.rgb(251,191,36))) {
                    if ((rows === s.autoHands && scene.autoSwing !in listOf("both","hands")) || (rows === s.autoClub && !scene.showAutoClub())) continue
                    val path = Path(); var last: TimedPoint? = null
                    for (p in rows) if (p.time <= time && p.time >= time-2.0) {
                        val q = pos(EditPoint(p.x,p.y),motion)
                        if (last == null || p.time-last.time > .18) path.moveTo(q.x,q.y) else path.lineTo(q.x,q.y)
                        last = p
                    }
                    paint.style=Paint.Style.STROKE; paint.color=c; paint.strokeWidth=max(1.5f,h/240f); canvas.drawPath(path,paint); paint.style=Paint.Style.FILL
                }
            }
            if (scene.personOcclusion) s.masks.minByOrNull { abs(it.time-time) }?.takeIf { abs(it.time-time) <= .11 }?.let { m ->
                val mask = maskCache.get(m.path) ?: BitmapFactory.decodeFile(m.path)?.also { maskCache.put(m.path,it) }
                if (mask != null) canvas.drawBitmap(mask,null,Rect(0,0,w,h),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
            }
            canvas.restore()
            for (p in s.popups) {
                val window=popupWindow(s,p) ?: continue
                if(!window.contains(time)) continue
                val at = when (p.anchor) {
                    "start" -> pos(s.ball ?: EditPoint(.1, .8), motion)
                    "apex" -> pos(s.peak(), motion)
                    "landing" -> pos(s.endpoint(), motion)
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
