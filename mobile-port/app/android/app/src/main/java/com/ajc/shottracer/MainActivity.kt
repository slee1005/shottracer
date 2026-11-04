package com.ajc.shottracer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var preview: PreviewView
    private lateinit var slider: SeekBar
    private lateinit var status: TextView
    private lateinit var counter: TextView
    private lateinit var timeline: ShotTimelineView
    private lateinit var startTimeButton: Button
    private lateinit var landingTimeButton: Button
    private lateinit var shotBar: LinearLayout
    private lateinit var tools: HorizontalScrollView
    private lateinit var top: HorizontalScrollView
    private lateinit var branding: LinearLayout
    private lateinit var flightModeButton: Button
    private lateinit var puttModeButton: Button
    private lateinit var workflow: LinearLayout
    private lateinit var startButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var resetButton: Button
    private val buttons = mutableMapOf<String, ImageButton>()
    private var source: File? = null
    private var output: File? = null
    private var times = doubleArrayOf(0.0)
    private var duration = 0.0
    private var frameIndex = 0
    private var displayed = 0
    private var scene = EditorScene()
    private var renderer = EditorRenderer(scene)
    private var tool = "ball"
    private var pending: EditPoint? = null
    private var busy = false
    private var busyTicket = 0
    private var decoding = false
    private var playing = false
    private var fullscreen = false
    private var timelineBefore: String?=null
    @Volatile private var generation = 0
    private var transformer: Transformer? = null
    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private val currentTime: Double get() = times[displayed.coerceIn(0, times.lastIndex)]
    private fun text(key: String): String {
        val id = resources.getIdentifier("editor_${key.replace('-', '_')}", "string", packageName)
        return if (id != 0) getString(id) else key
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    private fun toolbar(parent: LinearLayout): LinearLayout {
        val bar = row(); val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
        parent.addView(scroll, LinearLayout.LayoutParams(-1, dp(50))); return bar
    }
    private fun icon(parent: LinearLayout, key: String, action: () -> Unit): ImageButton {
        val button = ImageButton(this).apply {
            setImageDrawable(ToolIcon(key)); contentDescription = text(key); tooltipText = text(key)
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = GradientDrawable().apply { setColor(Color.rgb(27, 35, 45)); cornerRadius = dp(16).toFloat() }
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(dp(2), 0, dp(2), 0) })
        buttons[key] = button; return button
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(12,22,29),Color.rgb(9,13,20))); setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars()); view.setPadding(dp(6) + bars.left, dp(6) + bars.top, dp(6) + bars.right, dp(6) + bars.bottom); insets
            }
        }
        setContentView(root)
        branding = row(); root.addView(branding,LinearLayout.LayoutParams(-1,dp(44)))
        branding.addView(ImageView(this).apply { setImageResource(R.drawable.brand_logo); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(40),dp(40)).apply { marginEnd=dp(8) })
        branding.addView(TextView(this).apply { setText(R.string.app_name); textSize=22f; setTextColor(Color.WHITE); typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) },LinearLayout.LayoutParams(0,-2,1f))
        fun modeButton(label: String, mode: String) = Button(this).apply {
            text=text(label); textSize=11f; minWidth=0; setPadding(dp(6),0,dp(6),0); isAllCaps=false
            setOnClickListener { switchMode(mode) }; branding.addView(this,LinearLayout.LayoutParams(dp(78),dp(42)))
        }
        flightModeButton=modeButton("mode_flight","flight"); puttModeButton=modeButton("putt","putt")
        val upper = toolbar(root); top = upper.parent as HorizontalScrollView
        icon(upper, "import") { startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*").putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 4), 1) }
        icon(upper, "camera") { stopPlay(); startActivity(Intent(this, LiveCameraActivity::class.java)) }
        icon(upper, "projects") { projects() }
        icon(upper, "undo") { restore(undo, redo) }; icon(upper, "redo") { restore(redo, undo) }
        icon(upper, "settings") { settings() }
        icon(upper, "fullscreen") { fullscreen = !fullscreen; tools.visibility = if (fullscreen) View.GONE else View.VISIBLE; shotBar.visibility = if (fullscreen) View.GONE else View.VISIBLE; workflow.visibility = if (fullscreen) View.GONE else View.VISIBLE; branding.visibility = if (fullscreen) View.GONE else View.VISIBLE }
        shotBar = row(); root.addView(shotBar, LinearLayout.LayoutParams(-1, dp(38)))
        workflow = row(); root.addView(workflow, LinearLayout.LayoutParams(-1, dp(44)))
        fun actionButton(key: String, action: () -> Unit) = Button(this).apply {
            text = text(key); isAllCaps=false; textSize = 12f; minWidth = 0; setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }; workflow.addView(this, LinearLayout.LayoutParams(0, -1, 1f))
        }
        startButton = actionButton("start_unset") { stopPlay(); tool = "ball"; pending = null; preview.panEnabled = false; status.text = text("hint_ball"); controls() }
        analyzeButton = actionButton("analyze_action") { analyze() }
        resetButton = actionButton("reset_action") { confirmReset() }
        preview = PreviewView(this).apply { zoomEnabled = true; background=GradientDrawable().apply { setColor(Color.BLACK); cornerRadius=dp(20).toFloat(); setStroke(dp(1),Color.rgb(35,50,63)) }; clipToOutline=true }
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        preview.onBall = { p -> touch(EditPoint(p.x / scene.width.toDouble(), p.y / scene.height.toDouble())) }
        preview.onHandleStart = { remember() }
        preview.onHandle = { key, p -> handle(key, EditPoint(p.x.toDouble(), p.y.toDouble())); changed(false) }
        preview.onHandleEnd = { persist() }
        counter = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 11f; gravity = android.view.Gravity.CENTER }
        root.addView(counter)
        slider = SeekBar(this).apply { max = 1 }
        slider.setPadding(dp(16),0,dp(16),0)
        val scrubber = FrameLayout(this)
        root.addView(scrubber,LinearLayout.LayoutParams(-1,dp(52)))
        scrubber.addView(slider,FrameLayout.LayoutParams(-1,dp(30),android.view.Gravity.BOTTOM))
        timeline=ShotTimelineView(this).apply { onEvent={ jumpToEvent(it) }; onBegin={ beginTimeEdit() }; onDrag={ key,frame -> dragTime(key,frame) }; onEnd={ cancel -> endTimeEdit(cancel) } }
        scrubber.addView(timeline,FrameLayout.LayoutParams(-1,dp(22),android.view.Gravity.TOP))
        val eventRow=row(); root.addView(eventRow,LinearLayout.LayoutParams(-1,dp(44)))
        fun eventButton()=Button(this).apply {
            isAllCaps=false; textSize=10f; maxLines=2; minWidth=0; setPadding(dp(4),0,dp(4),0)
            background=GradientDrawable().apply { setColor(Color.rgb(23,32,43)); cornerRadius=dp(12).toFloat() }
            eventRow.addView(this,LinearLayout.LayoutParams(0,-1,1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        startTimeButton=eventButton().apply { setTextColor(Color.YELLOW); setOnClickListener { timeline.events.firstOrNull { it.key=="ball" }?.let { jumpToEvent(it) } } }
        landingTimeButton=eventButton().apply { setTextColor(Color.GREEN); setOnClickListener { timeline.events.firstOrNull { it.key=="landing" }?.let { jumpToEvent(it) } } }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) { if (fromUser) { stopPlay(); refresh(value) } }
            override fun onStartTrackingTouch(bar: SeekBar) { stopPlay() }
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        val transport = toolbar(root)
        icon(transport, "previous") { stopPlay(); refresh(frameIndex - 1) }
        icon(transport, "play") { if (playing) stopPlay() else { playing = true; playNext() } }
        icon(transport, "next") { stopPlay(); refresh(frameIndex + 1) }
        icon(transport, "analyze") { analyze() }; icon(transport, "export") { exportVideo() }
        icon(transport, "review") { output?.let { review(it) } }; icon(transport, "save") { saveOutput() }
        val lower = toolbar(root); tools = lower.parent as HorizontalScrollView
        for (key in listOf("ball", "landing", "apex", "path", "circle", "line", "swing", "pan")) icon(lower, key) {
            tool = key; pending = null; preview.panEnabled = key == "pan"; status.text = text("hint_$key"); handles(); controls()
        }
        icon(lower, "delete") { if (scene.shapes.isNotEmpty()) { remember(); scene.shapes.removeAt(scene.shapes.lastIndex); changed() } else deleteShot() }
        icon(lower, "landing_frame") { if (scene.shot.ball != null && currentTime > scene.shot.start) { remember(); scene.shot.landing = scene.shot.endpoint(); scene.shot.flight = (currentTime - scene.shot.start).coerceIn(.2, 15.0); tool = "landing"; preview.panEnabled = false; status.text = text("hint_landing"); changed() } }
        status = TextView(this).apply { setText(R.string.hint_import); setTextColor(Color.LTGRAY); textSize = 11f; maxLines = 3; setPadding(dp(8),dp(5),dp(8),dp(5)) }
        root.addView(status); renderShots(); controls()
        // Restore the last local project without accessing the user's original video.
        File(filesDir, "projects").listFiles()?.filter { File(it, "source.mp4").isFile }?.maxByOrNull { File(it, "mobile-project.json").lastModified() }?.let { load(File(it, "source.mp4")) }
    }
    private fun controls() {
        for ((key, button) in buttons) {
            button.isEnabled = !busy && timelineBefore==null && when (key) {
                "previous", "next", "play", "ball", "landing", "path", "circle", "line", "swing", "pan", "settings", "export", "add" -> source != null
                "apex" -> source != null && scene.shot.trackingMode != "putt"
                "analyze" -> scene.shot.ball != null
                "undo" -> undo.isNotEmpty()
                "redo" -> redo.isNotEmpty()
                "review", "save" -> output?.isFile == true
                else -> true
            }
            button.alpha = if (button.isEnabled) 1f else .35f
            button.imageTintList = android.content.res.ColorStateList.valueOf(if (key == tool) Color.rgb(45,212,191) else Color.WHITE)
            if (key == "play") button.setImageDrawable(ToolIcon(if (playing) "pause" else "play"))
        }
        updateTimeline()
        slider.isEnabled = !busy && timelineBefore==null && source != null
        preview.isEnabled = !busy && timelineBefore==null && !decoding
        fun tone(button: Button, active: Boolean) {
            button.background=GradientDrawable().apply { setColor(if(active) Color.rgb(45,212,191) else Color.rgb(27,35,45)); cornerRadius=dp(18).toFloat() }
            button.setTextColor(if(active) Color.rgb(7,30,32) else Color.LTGRAY); button.alpha=if(button.isEnabled) 1f else .4f
        }
        for ((button,mode) in listOf(flightModeButton to "flight",puttModeButton to "putt")) { button.isEnabled=!busy && timelineBefore==null && source != null; tone(button,scene.shot.trackingMode==mode) }
        startButton.text = text(if (scene.shot.ball != null) "start_checked" else "start_unset")
        startButton.isEnabled = !busy && timelineBefore==null && !decoding && source != null
        analyzeButton.isEnabled = !busy && timelineBefore==null && !decoding && source != null && scene.shot.ball != null
        resetButton.isEnabled = !busy && timelineBefore==null && source != null
        tone(startButton,scene.shot.ball != null); tone(analyzeButton,true); tone(resetButton,false)
        for (i in 0 until shotBar.childCount) shotBar.getChildAt(i).isEnabled = !busy && timelineBefore==null && source != null && (i < scene.shots.size || scene.shots.size < 4)
    }
    private fun beginTimeEdit() {
        if(busy || timelineBefore!=null) return
        stopPlay(); timelineBefore=scene.json().toString(); pending=null; preview.resetViewport()
    }
    private fun dragTime(key: String, frame: Int) {
        if(timelineBefore==null || busy) return
        val target=retimeShot(scene.shot,key,frame,times) ?: return
        tool=key; preview.panEnabled=false; changed(false)
        if(frameIndex!=target) refresh(target)
    }
    private fun endTimeEdit(cancel: Boolean) {
        val before=timelineBefore ?: return; timelineBefore=null
        if(cancel) { scene=EditorScene.read(JSONObject(before)); changed(false) }
        else if(before!=scene.json().toString()) { undo.addLast(before); if(undo.size>50) undo.removeFirst(); redo.clear(); persist() }
        controls()
    }
    private fun updateTimeline() {
        val events=if(source != null) shotEvents(scene.shot,times,duration) else emptyList()
        timeline.events=events; timeline.frames=times.size; timeline.isEnabled=!busy; timeline.invalidate()
        for ((button,key) in listOf(startTimeButton to "ball",landingTimeButton to "landing")) {
            val event=events.firstOrNull { it.key==key }
            val name=text(if(key=="ball") "timeline_start" else if(event?.estimated==true) "timeline_estimated" else "timeline_landing")
            button.text=if(event==null) text(if(key=="ball") "timeline_start_unset" else "timeline_landing_unset")
                else if(event.inClip) getString(R.string.editor_timeline_event,name,event.time,event.frame+1)
                else getString(R.string.editor_timeline_outside,name,event.time)
            button.isEnabled=!busy && timelineBefore==null && event?.inClip==true; button.alpha=if(button.isEnabled) 1f else .5f
        }
    }
    private fun jumpToEvent(event: ShotEvent) {
        if(busy || !event.inClip) return
        stopPlay(); tool=event.key; pending=null; preview.panEnabled=false; preview.resetViewport()
        refresh(event.frame); status.text=text(if(event.key=="ball") "hint_ball" else "hint_landing"); controls()
    }
    private fun renderShots() {
        shotBar.removeAllViews()
        scene.shots.forEachIndexed { i, _ ->
            val button = Button(this).apply {
                text = getString(R.string.editor_shot_number, i + 1); textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0)
                isAllCaps=false; background=GradientDrawable().apply { setColor(if (i == scene.selected) Color.rgb(26,65,66) else Color.rgb(23,32,43)); cornerRadius=dp(18).toFloat() }
                setTextColor(if (i == scene.selected) Color.rgb(45,212,191) else Color.LTGRAY)
                isEnabled = !busy
                setOnClickListener { scene.selected = i; pending = null; persist(); handles(); renderShots(); controls() }
            }
            shotBar.addView(button, LinearLayout.LayoutParams(dp(62), -1))
        }
        icon(shotBar, "add") {
            if (scene.shots.size < 4 && source != null) { remember(); scene.shots.add(EditShot(start = currentTime)); scene.selected = scene.shots.lastIndex; tool = "ball"; changed(); renderShots() }
            else status.text = text("max_shots")
        }
    }
    private fun remember() { undo.addLast(scene.json().toString()); if (undo.size > 50) undo.removeFirst(); redo.clear() }
    private fun restore(from: ArrayDeque<String>, to: ArrayDeque<String>) {
        if (from.isEmpty()) return
        to.addLast(scene.json().toString()); scene = EditorScene.read(JSONObject(from.removeLast())); changed(); renderShots()
    }
    private fun persist() {
        val folder = source?.parentFile ?: return
        val temp = File(folder, "mobile-project.json.tmp"); temp.writeText(scene.json().toString())
        java.nio.file.Files.move(temp.toPath(), File(folder, "mobile-project.json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    private fun changed(save: Boolean = true) {
        output = null; renderer = EditorRenderer(scene.copy()); overlay(); handles(); controls(); if (save) persist()
    }
    private fun overlay() { preview.overlay?.recycle(); preview.overlay = renderer.overlay(currentTime); preview.invalidate() }
    private fun reference(p: EditPoint): EditPoint { val m = scene.shot.motion(currentTime); return EditPoint(p.x - m.x, p.y - m.y) }
    private fun handle(key: String, p: EditPoint) {
        val point = reference(p)
        when {
            key == "ball" -> { scene.shot.ball = point; scene.shot.manual = true }
            key == "landing" -> { scene.shot.landing = point; scene.shot.timingLandingEstimated=false }
            key == "apex" -> scene.shot.apex = point
            key.startsWith("shape") -> {
                val parts = key.split(":"); val shape = scene.shapes[parts[1].toInt()]
                if (parts[2] == "a") shape.a = p else shape.b = p
            }
        }
    }
    private fun handles() {
        val s = scene.shot; val m = s.motion(currentTime)
        fun handle(key: String, p: EditPoint) = EditorHandle(key, PointF((p.x + m.x).toFloat(), (p.y + m.y).toFloat()),if(key=="ball") text("timeline_start") else if(key=="landing") text("timeline_landing") else "")
        val points = mutableListOf<EditorHandle>()
        s.ball?.let { points.add(handle("ball", it)) }
        if (s.ball != null && (s.edited() || s.base.isNotEmpty())) { points.add(handle("landing", s.endpoint())); if (s.trackingMode != "putt") points.add(handle("apex", s.peak())) }
        if (tool in listOf("line", "circle")) scene.shapes.forEachIndexed { i, shape -> points.add(EditorHandle("shape:$i:a", PointF(shape.a.x.toFloat(), shape.a.y.toFloat()))); points.add(EditorHandle("shape:$i:b", PointF(shape.b.x.toFloat(), shape.b.y.toFloat()))) }
        preview.ball = null; preview.handles = points; preview.invalidate()
    }
    private fun touch(p: EditPoint) {
        if (busy || source == null) return
        val s = scene.shot; val r = reference(p)
        when (tool) {
            "ball" -> { remember(); s.ball = p; s.start = currentTime; s.base = emptyList(); s.baseModel = null; s.camera = emptyList(); s.autoHands=emptyList(); s.autoClub=emptyList(); s.masks=emptyList(); s.manual = false; s.landing = null; s.timingLandingEstimated=null; s.apex = null; changed(); analyze() }
            "landing", "apex" -> { remember(); if (s.ball == null) { s.ball = EditPoint(.2, .8); s.start = currentTime }; handle(tool, p); changed() }
            "path" -> { remember(); if (pending == null) { s.ball = p; s.start = currentTime; s.manual = true; pending = p } else { s.landing = r; pending = null }; changed() }
            "circle", "line" -> { if (pending == null) { pending = p; status.text = text("second_point") } else { remember(); scene.shapes.add(EditShape(tool, pending!!, p)); pending = null; changed() } }
            "swing" -> { remember(); s.swing.removeAll { abs(it.time - currentTime) < .00001 }; s.swing.add(TimedPoint(currentTime, r.x, r.y)); s.swing.sortBy { it.time }; changed(); refresh(frameIndex + 2) }
        }
    }
    private fun stopPlay() { playing = false; main.removeCallbacksAndMessages("play"); buttons["play"]?.setImageDrawable(ToolIcon("play")) }
    private fun playNext() {
        if (!playing || busy || frameIndex >= times.lastIndex) { stopPlay(); return }
        buttons["play"]?.setImageDrawable(ToolIcon("pause")); refresh(frameIndex + 1)
    }
    private fun work(action: () -> Unit) {
        val ticket = ++busyTicket
        busy = true; stopPlay(); controls()
        worker.execute { try { action() } catch (_: Exception) { ui { status.setText(R.string.error_processing) } } finally { ui { if (ticket == busyTicket) { busy = false; controls() } } } }
    }
    private fun ui(action: () -> Unit) { runOnUiThread { if (!isDestroyed) action() } }
    private fun load(file: File, done: (() -> Unit)? = null) {
        generation++; work {
            val rows = EditorMedia.frameTimes(file)
            val saved = File(file.parentFile, "mobile-project.json")
            val restored = if (saved.isFile) EditorScene.read(JSONObject(saved.readText())) else EditorScene()
            val retriever = MediaMetadataRetriever()
            val frame: Bitmap
            val seconds: Double
            try { retriever.setDataSource(file.path); frame = EditorMedia.frame(retriever, 0); seconds = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDouble() ?: 0.0) / 1000 }
            finally { retriever.release() }
            ui {
                source = file; times = rows; duration = max(seconds, rows.last()); scene = restored; scene.width = frame.width; scene.height = frame.height
                frameIndex = 0; displayed = 0; undo.clear(); redo.clear(); preview.image?.recycle(); preview.image = frame
                renderer = EditorRenderer(scene.copy()); overlay(); handles(); slider.max = times.lastIndex; slider.progress = 0
                counter.text = getString(R.string.editor_frame_count, 1, times.size, rows[0]); renderShots(); status.setText(R.string.hint_ball)
                output = file.parentFile?.listFiles()?.filter { it.name.startsWith("traced-") && it.extension == "mp4" }?.maxByOrNull { it.lastModified() }
                busy = false; controls(); done?.invoke()
            }
        }
    }
    private fun refresh(index: Int) {
        val file = source ?: return
        frameIndex = index.coerceIn(0, times.lastIndex); slider.progress = frameIndex
        val requested = frameIndex; val token = ++generation; decoding = true; controls()
        worker.execute {
            if (token != generation) return@execute
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path); val image = EditorMedia.frame(retriever, requested)
                ui {
                    if (token == generation) {
                        preview.image?.recycle(); preview.image = image; displayed = requested; decoding = false
                        overlay(); handles(); counter.text = getString(R.string.editor_frame_count, requested + 1, times.size, currentTime); controls()
                        val factor = if (scene.slowmo && slowIntervals(scene, duration).any { currentTime >= it.first && currentTime < it.second }) scene.slowFactor else 1.0
                        if (playing) main.postDelayed({ playNext() }, max(1L, ((times[min(times.lastIndex, requested + 1)] - currentTime) * 1000 * factor).toLong()))
                    } else image.recycle()
                }
            } catch (_: Exception) { ui { decoding = false; stopPlay(); controls(); status.setText(R.string.error_processing) } }
            finally { retriever.release() }
        }
    }
    @Deprecated("Platform picker callback") override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data); if (result != RESULT_OK) return
        val uri = data?.data
        if (request == 1) {
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(uri)
            work {
                var first: File? = null
                for (u in uris) { val folder = File(filesDir, "projects/${UUID.randomUUID()}").apply { mkdirs() }; val file = File(folder, "source.mp4")
                    contentResolver.openInputStream(u)!!.use { input -> file.outputStream().use { input.copyTo(it) } }; if (first == null) first = file }
                ui { busy = false; first?.let { load(it) } }
            }
        } else if (request == 2 && uri != null) {
            val file = output ?: return; work { contentResolver.openOutputStream(uri, "w")!!.use { out -> file.inputStream().use { it.copyTo(out) } }; ui { status.setText(R.string.status_saved) } }
        } else if (request == 3 && uri != null) {
            val file = File(source?.parentFile ?: return, "logo.png")
            work { contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }; ui { remember(); scene.logo = file.path; changed() } }
        }
    }
    private fun switchMode(mode: String) {
        if (busy || scene.shot.trackingMode == mode) return
        stopPlay(); remember(); scene.shot.apply { trackingMode=mode; base=emptyList(); baseModel=null; landing=null; timingLandingEstimated=null; apex=null; manual=false; flight=4.0 }
        tool="ball"; pending=null; preview.panEnabled=false; changed(); status.text=text("mode_changed")
    }
    private fun analyze() {
        val file = source ?: return; val s = scene.shot; val ball = s.ball ?: return
        if (busy) return
        status.setText(R.string.status_analyzing)
        val width = scene.width; val height = scene.height; val id = s.id; val start = s.start; val rows = times.copyOf(); val putt = s.trackingMode == "putt"
        work {
            val result = TrackingSession(width,height,ball.x*width,ball.y*height,putt)
            val retriever = MediaMetadataRetriever()
            var personError = false
            val person = try { PersonAnalysis(this,File(file.parentFile,"analysis-${UUID.randomUUID()}")) } catch (e: Exception) { android.util.Log.w("ShotTracer","Person task initialization failed",e); personError=true; null }
            try {
                retriever.setDataSource(file.path)
                var lastSample = Double.NEGATIVE_INFINITY; var lastPerson = Double.NEGATIVE_INFINITY
                for (i in rows.indices) if (rows[i] >= max(0.0,start-1.2) && rows[i] <= min(duration,start+15) && rows[i]-lastSample >= 1.0/30-1e-6) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val time = rows[i]; val frame = EditorMedia.frame(retriever,i)
                    try {
                        if (time >= start) result.push(frame,time)
                        if (person != null && !personError && time-lastPerson >= (if (abs(time-start) <= 1.2) 1.0/30 else 1.0/15)-1e-6) {
                            val cam = samples(result.samples(true)).lastOrNull()
                            try { person.push(frame,time,EditPoint(cam?.x ?: 0.0,cam?.y ?: 0.0)) } catch (e: Exception) { android.util.Log.w("ShotTracer","Person frame analysis failed",e); personError=true }
                            lastPerson=time
                        }
                    } finally { frame.recycle() }
                    lastSample=time
                    if (i%15 == 0) ui { status.text=getString(R.string.editor_analysis_progress,((time-max(0.0,start-1.2))/(min(duration,start+15)-max(0.0,start-1.2))*100).toInt().coerceIn(0,100)) }
                }
                val observations = samples(result.samples()); val camera = samples(result.samples(true))
                val model = if (!putt && observations.size >= 4) NativeCore.fit(observations.drop(1).flatMap { listOf(it.time,it.x,it.y) }.toDoubleArray(),ball.x,ball.y,observations[0].time,observations[1].time-.00001) else null
                val impact = model?.get(3) ?: observations.firstOrNull()?.time ?: start
                ui {
                    remember(); scene.shots.firstOrNull { it.id == id }?.let {
                        it.base=observations; it.camera=camera; it.baseModel=model; it.start=impact
                        it.autoHands=person?.hands?.filter { p -> p.time in impact-1.2..impact+1.2 } ?: emptyList()
                        it.autoClub=person?.clubs?.filter { p -> p.time in impact-1.2..impact+1.2 } ?: emptyList()
                        it.masks=person?.masks?.toList() ?: emptyList()
                        if (putt && observations.size >= 3) it.flight=max(.2,observations.last().time-impact)
                        if (model != null) { it.flight=model[10]; it.apexFraction=(NativeCore.modelApex(model)/model[10]).coerceIn(.35,.62) }
                    }
                    changed()
                    status.text=getString(R.string.editor_analysis_result,observations.size,scene.shot.autoHands.size,scene.shot.autoClub.size) +
                        (if (observations.size < 3) "\n"+getString(R.string.error_track) else "") + (if (personError) "\n"+text("person_failed") else "")
                }
            } finally { retriever.release(); result.close(); person?.close() }
        }
    }
    private fun review(file: File) { stopPlay(); startActivity(Intent(this, ReviewActivity::class.java).putExtra("path", file.path)) }
    private fun saveOutput(done: (() -> Unit)? = null) {
        val file=output ?: return
        busy=true; controls(); status.setText(R.string.editor_album_saving)
        worker.execute {
            val saved=try { PhotoAlbum.save(this,file); true } catch (_: Exception) { false }
            ui { busy=false; controls(); status.setText(if(saved) R.string.editor_album_saved else R.string.editor_album_failed); done?.invoke() }
        }
    }
    private fun exportFinished(file: File,done: (() -> Unit)?) {
        output=file; saveOutput(done)
    }
    private fun exportVideo(done: (() -> Unit)? = null) {
        val file = source ?: return; stopPlay(); persist()
        val snapshot = scene.copy(); val draw = EditorRenderer(snapshot); val destination = File(file.parentFile, "traced-${UUID.randomUUID()}.mp4")
        busyTicket++; busy = true; controls(); status.setText(R.string.status_exporting)
        if (snapshot.slowmo) {
            val rows = times.copyOf(); val clipDuration = duration
            worker.execute {
                var recording: LiveRecorder? = null
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.path)
                    recording = LiveRecorder(this, destination, snapshot.width, snapshot.height, false, 0)
                    val intervals = slowIntervals(snapshot, clipDuration)
                    val total = clipDuration + intervals.sumOf { (it.second - it.first) * (snapshot.slowFactor - 1) }
                    for (i in 0 until ceil(total * 30).toInt()) {
                        val time = sourceTime(i / 30.0, intervals, snapshot.slowFactor)
                        val raw = EditorMedia.frame(retriever, EditorMedia.nearest(rows, time)); val frame = raw.copy(Bitmap.Config.ARGB_8888, true); raw.recycle(); val overlay = draw.overlay(time)
                        Canvas(frame).drawBitmap(overlay, 0f, 0f, null); overlay.recycle()
                        recording.frame(frame, i * 1_000_000L / 30); frame.recycle()
                    }
                    check(recording.finish()); ui { exportFinished(destination,done) }
                } catch (_: Exception) { recording?.close(); destination.delete(); ui { busy = false; controls(); status.setText(R.string.error_processing) } }
                finally { retriever.release() }
            }
            return
        }
        val overlay = object : BitmapOverlay() {
            private var width = snapshot.width; private var height = snapshot.height
            override fun configure(videoSize: Size) { super.configure(videoSize); width = videoSize.width; height = videoSize.height }
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val raster = draw.overlay(presentationTimeUs / 1_000_000.0)
                val scaled = Bitmap.createScaledBitmap(raster, width, height, true); if (scaled !== raster) raster.recycle(); return scaled
            }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(file))).setEffects(Effects(emptyList(), listOf(OverlayEffect(listOf<TextureOverlay>(overlay))))).build()
        transformer = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) { exportFinished(destination,done) }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { destination.delete(); busy = false; controls(); status.setText(R.string.error_processing) }
        }).build()
        try { transformer!!.start(item, destination.path) } catch (_: Exception) { destination.delete(); busy = false; controls(); status.setText(R.string.error_processing) }
    }
    private fun deleteShot() { if (scene.shots.size > 1) { remember(); scene.shots.removeAt(scene.selected); scene.selected = min(scene.selected, scene.shots.lastIndex); changed(); renderShots() } }
    private fun confirmReset() {
        if (busy || source == null) return
        stopPlay()
        AlertDialog.Builder(this).setMessage(text("reset_confirm")).setPositiveButton(text("reset_action")) { _, _ ->
            remember(); val width = scene.width; val height = scene.height
            scene = EditorScene().apply { this.width = width; this.height = height }
            tool = "ball"; pending = null; preview.panEnabled = false
            changed(); renderShots(); controls(); status.text = text("hint_ball"); refresh(0)
        }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun clearProject() {
        stopPlay(); generation++; source = null; output = null; times = doubleArrayOf(0.0); duration = 0.0
        frameIndex = 0; displayed = 0; decoding = false; scene = EditorScene(); renderer = EditorRenderer(scene)
        undo.clear(); redo.clear(); pending = null; tool = "ball"; preview.panEnabled = false
        preview.image?.recycle(); preview.image = null; preview.overlay?.recycle(); preview.overlay = null
        preview.handles = emptyList(); preview.invalidate(); slider.max = 1; slider.progress = 0; counter.text = ""
        renderShots(); controls(); status.setText(R.string.hint_import)
    }
    private fun deleteProjects(folders: List<File>) {
        if (busy || folders.isEmpty()) return
        AlertDialog.Builder(this).setTitle(text("delete_projects")).setMessage(text("delete_projects_confirm"))
            .setPositiveButton(text("delete_selected")) { _, _ ->
                val root = File(filesDir, "projects").canonicalFile
                val allowed = folders.filter { it.canonicalFile.parentFile == root && File(it, "source.mp4").isFile }
                work {
                    val deleted = allowed.filter { it.deleteRecursively() }
                    ui {
                        if (source?.isFile == false) clearProject()
                        status.text = if (deleted.size == allowed.size) getString(R.string.editor_projects_deleted, deleted.size) else text("delete_failed")
                    }
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun chooseDeletions(folders: List<File>) {
        val selected = BooleanArray(folders.size)
        val dialog = AlertDialog.Builder(this).setTitle(text("delete_projects"))
            .setMultiChoiceItems(folders.map { it.name.take(8) }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setPositiveButton(text("delete_selected")) { _, _ -> deleteProjects(folders.filterIndexed { i, _ -> selected[i] }) }
            .setNeutralButton(text("select_all"), null).setNegativeButton(android.R.string.cancel, null).create()
        dialog.setOnShowListener {
            val delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE); delete.isEnabled = false
            dialog.listView.setOnItemClickListener { _, _, i, _ -> selected[i] = dialog.listView.isItemChecked(i); delete.isEnabled = selected.any { it } }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val checked = !selected.all { it }; selected.indices.forEach { selected[it] = checked; dialog.listView.setItemChecked(it, checked) }; delete.isEnabled = checked
            }
        }
        dialog.show()
    }
    private fun projects() {
        if (busy) return
        stopPlay(); val folders = File(filesDir, "projects").listFiles()?.filter { File(it, "source.mp4").isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        AlertDialog.Builder(this).setTitle(text("projects")).setItems(folders.map { it.name.take(8) }.toTypedArray()) { _, index -> load(File(folders[index], "source.mp4")) }
            .setNeutralButton(text("batch")) { _, _ ->
                fun next(index: Int) { if (index < folders.size) load(File(folders[index], "source.mp4")) { exportVideo { next(index + 1) } } }
                if (folders.isNotEmpty()) next(0)
            }.setNegativeButton(text("delete_projects")) { _, _ -> if (folders.isNotEmpty()) chooseDeletions(folders) }.setPositiveButton(android.R.string.cancel, null).show()
    }
    private fun motionReport(): String {
        val shot=scene.shot
        val impact=shot.baseModel?.get(3) ?: shot.base.firstOrNull()?.time ?: shot.start
        fun summary(rows: List<TimedPoint>): DoubleArray? = NativeCore.motion(rows.flatMap { listOf(it.time,it.x*scene.width,it.y*scene.height) }.toDoubleArray())
        val ballRows=shot.base.drop(1).filter { it.time>=impact && it.time<=impact+2 }
        val ball=summary(ballRows)
        val launch=summary(ballRows.takeWhile { it.time <= (ballRows.firstOrNull()?.time ?: 0.0)+.25 })
        val club=summary(shot.autoClub.filter { it.time>=impact-.25 && it.time<=impact+.05 })
        return text("motion_title")+"\n"+(ball?.let { getString(R.string.editor_motion_ball,it[0],hypot(it[1],it[2]),it[3]) } ?: text("motion_missing"))+"\n"+
            (launch?.takeIf { hypot(it[1],it[2])>=2 }?.let { getString(R.string.editor_motion_direction,atan2(-it[2],it[1])*180/Math.PI) } ?: "")+"\n"+
            (club?.let { getString(R.string.editor_motion_club,it[3],it[4].toInt()) } ?: text("motion_club_missing"))+"\n"+text("motion_calibration")
    }
    private fun settings() {
        stopPlay(); val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val edits = mutableListOf<() -> Unit>()
        var settingsDialog: AlertDialog?=null
        fun number(label: String, value: Double, min: Double, max: Double, set: (Double) -> Unit) {
            layout.addView(TextView(this).apply { text = text(label) })
            val field = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(value.toString()) }; layout.addView(field)
            edits.add { field.text.toString().toDoubleOrNull()?.let { set(it.coerceIn(min, max)) } }
        }
        fun check(label: String, value: Boolean, set: (Boolean) -> Unit) { val box = CheckBox(this).apply { text = text(label); isChecked = value }; layout.addView(box); edits.add { set(box.isChecked) } }
        fun choice(label: String, options: List<String>, selected: String, set: (String) -> Unit) {
            layout.addView(TextView(this).apply { text = text(label) }); val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { text(if (it == "flight") "mode_flight" else it) }); spinner.setSelection(max(0, options.indexOf(selected)))
            layout.addView(spinner); edits.add { set(options[spinner.selectedItemPosition]) }
        }
        layout.addView(TextView(this).apply { text=motionReport(); setPadding(0,0,0,dp(12)); setTextIsSelectable(true) })
        choice("tracking_mode", listOf("flight","putt"), scene.shot.trackingMode) { if (scene.shot.trackingMode != it) { scene.shot.trackingMode=it; scene.shot.base=emptyList(); scene.shot.baseModel=null; scene.shot.landing=null; scene.shot.timingLandingEstimated=null; scene.shot.apex=null; scene.shot.manual=false } }
        check("person_occlusion",scene.personOcclusion) { scene.personOcclusion=it }
        choice("auto_swing",listOf("both","hands","club","off"),scene.autoSwing) { scene.autoSwing=it }
        choice("preset", listOf("classic", "tv", "neon", "comet", "minimal"), scene.preset) { scene.preset = it }
        layout.addView(TextView(this).apply { text = text("color") }); val color = EditText(this).apply { setText(scene.shot.color); inputType = InputType.TYPE_CLASS_TEXT }; layout.addView(color)
        edits.add { try { Color.parseColor(color.text.toString()); scene.shot.color = color.text.toString() } catch (_: Exception) { } }
        number("flight", scene.shot.flight, .2, 15.0) { scene.shot.flight = it }
        if (scene.shot.trackingMode != "putt") number("apex_time", scene.shot.apexFraction * scene.shot.flight, .07, 9.3) { scene.shot.apexFraction = (it / scene.shot.flight).coerceIn(.35, .62) }
        number("speed", scene.shot.speed, .5, 4.0) { scene.shot.speed = it }
        number("width", scene.lineWidth, .2, 5.0) { scene.lineWidth = it }; number("glow", scene.glow, 0.0, 3.0) { scene.glow = it }
        number("hold", scene.hold, -1.0, 30.0) { scene.hold = it }
        check("head", scene.head) { scene.head = it }; check("marker", scene.marker) { scene.marker = it }; check("swing_enabled", scene.swingEnabled) { scene.swingEnabled = it }
        check("slowmo", scene.slowmo) { scene.slowmo = it }; number("slow_factor", scene.slowFactor, 1.0, 8.0) { scene.slowFactor = it }
        number("slow_before", scene.slowBefore, 0.0, 5.0) { scene.slowBefore = it }; number("slow_after", scene.slowAfter, 0.0, 10.0) { scene.slowAfter = it }
        number("logo_size", scene.logoSize, .02, .5) { scene.logoSize = it }; number("logo_opacity", scene.logoOpacity, 0.0, 1.0) { scene.logoOpacity = it }
        choice("anchor", listOf("top-left", "top-right", "bottom-left", "bottom-right"), scene.logoCorner) { scene.logoCorner = it }
        fun action(key: String, invoke: () -> Unit) { layout.addView(Button(this).apply { text = text(key); setOnClickListener { invoke() } }) }
        action("popup") { remember(); edits.forEach { it() }; changed(); settingsDialog?.dismiss(); popups() }; action("popup_clear") { remember(); scene.shot.popups.clear(); changed() }
        action("logo") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 3) }
        action("logo_clear") { remember(); scene.logo = null; changed() }
        action("delete_shot") { deleteShot() }
        settingsDialog=AlertDialog.Builder(this).setTitle(text("settings")).setView(ScrollView(this).apply { addView(layout) }).setPositiveButton(android.R.string.ok) { _, _ -> remember(); edits.forEach { it() }; changed() }.setNegativeButton(android.R.string.cancel, null).show()
    }
    private fun previewPopup(index: Int): Boolean {
        val p=scene.shot.popups.getOrNull(index) ?: return false
        val frame=popupFrame(scene.shot,p,times,displayed)
        if(frame==null) { status.text=text("popup_outside"); return false }
        stopPlay(); refresh(frame); status.text=text("popup_previewing"); return true
    }
    private fun popups() {
        if(scene.shot.popups.isEmpty()) { popup(); return }
        val rows=scene.shot.popups.map { p -> getString(R.string.editor_popup_summary,p.label,p.value) +
            (if(popupFrame(scene.shot,p,times,displayed)==null) getString(R.string.editor_popup_unreachable_suffix) else "") }+text("popup_new")
        AlertDialog.Builder(this).setTitle(text("popup")).setItems(rows.toTypedArray()) { _,i -> popup(i.takeIf { it<scene.shot.popups.size }) }
            .setNegativeButton(android.R.string.cancel,null).show()
    }
    private fun popup(index: Int?=null) {
        val original=index?.let { scene.shot.popups.getOrNull(it) } ?: EditPopup(anchor="top-left")
        var editing=index
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0) }
        val warning=TextView(this).apply { text=text("popup_outside"); setTextColor(Color.rgb(251,191,36)); visibility=View.GONE }; layout.addView(warning)
        val kinds=listOf("distance","carry","speed","apex","hangtime","club","player","text")
        val kind=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,kinds.map { text("popup_$it") }); setSelection(max(0,kinds.indexOf(original.kind))) }; layout.addView(kind)
        val label=EditText(this).apply { hint=text("popup_label"); setText(original.label) }
        val value=EditText(this).apply { hint=text("popup_value"); setText(original.value) }; layout.addView(label); layout.addView(value)
        val anchors=listOf("top-left","top-right","bottom-left","bottom-right","landing","apex","start")
        val anchor=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,anchors.map { text(it) }); setSelection(max(0,anchors.indexOf(original.anchor))) }; layout.addView(anchor)
        val delay=EditText(this).apply { hint=text("delay"); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED; setText(original.delay.toString()) }; layout.addView(delay)
        val hides=listOf("after_shot","end","time")
        val hide=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,hides.map { text("hide_$it") }); setSelection(max(0,hides.indexOf(original.hide))) }; layout.addView(hide)
        val duration=EditText(this).apply { hint=text("popup_duration"); setText(original.duration.toString()); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }; layout.addView(duration)
        val builder=AlertDialog.Builder(this).setTitle(text(if(index==null) "popup" else "popup_edit"))
            .setView(ScrollView(this).apply { addView(layout) }).setPositiveButton(text("popup_save_preview"),null).setNegativeButton(android.R.string.cancel,null)
        if(index!=null) builder.setNeutralButton(text("popup_delete")) { _,_ -> remember(); scene.shot.popups.removeAt(index); changed() }
        val dialog=builder.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val k=kinds[kind.selectedItemPosition]
            val delta=delay.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            val seconds=(duration.text.toString().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 3.0).coerceIn(.1,30.0)
            val p=EditPopup(k,label.text.toString().trim().ifEmpty { text("popup_$k") },value.text.toString().trim(),anchors[anchor.selectedItemPosition],delta,hides[hide.selectedItemPosition],seconds)
            remember(); val i=editing
            if(i!=null && i in scene.shot.popups.indices) scene.shot.popups[i]=p
            else { scene.shot.popups.add(p); editing=scene.shot.popups.lastIndex }
            changed()
            if(previewPopup(editing!!)) dialog.dismiss() else warning.visibility=View.VISIBLE
        } }
        dialog.show()
    }
    override fun onPause() { if(timelineBefore!=null) endTimeEdit(true); stopPlay(); super.onPause() }
    override fun onDestroy() { transformer?.cancel(); worker.shutdownNow(); super.onDestroy() }
}
internal fun slowIntervals(scene: EditorScene, duration: Double): List<Pair<Double, Double>> {
    val windows = scene.shots.filter { it.ball != null }.map { max(0.0, it.start - scene.slowBefore) to min(duration, it.start + scene.slowAfter) }.filter { it.second > it.first }.sortedBy { it.first }
    val result = mutableListOf<Pair<Double, Double>>()
    for (w in windows) { if (result.isNotEmpty() && w.first <= result.last().second) { val last = result.removeAt(result.lastIndex); result.add(last.first to max(last.second, w.second)) } else result.add(w) }
    return result
}
internal fun sourceTime(output: Double, windows: List<Pair<Double, Double>>, factor: Double): Double {
    var source = 0.0; var clock = 0.0
    for ((a, b) in windows) { if (output <= clock + a - source) return source + output - clock; clock += a - source
        if (output <= clock + (b - a) * factor) return a + (output - clock) / factor; clock += (b - a) * factor; source = b }
    return source + output - clock
}
