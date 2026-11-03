package com.ajc.shottracer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.PointF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** All camera frames, tracker calls, EGL and codec operations run on one worker. */
internal class LiveShotEngine(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun frame(bitmap: Bitmap)
        fun message(resource: Int)
        fun recording(active: Boolean, finishing: Boolean, audio: Boolean)
        fun saved(file: File, points: Int)
    }
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("ShotTracer-live").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val framePending = AtomicBoolean()
    private val closed = AtomicBoolean()
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var rotation = 0
    private var realtimeClock = false
    private var seed: PointF? = null
    private var armed = false
    private var audioAllowed = false
    private var recorder: LiveRecorder? = null
    private var tracker: TrackingSession? = null
    private var firstTimestamp = 0L
    private var lastSeconds = -1.0
    private var watermark: Bitmap? = null

    private fun ui(action: () -> Unit) { main.post(action) }
    private fun message(resource: Int) { ui { listener.message(resource) } }

    fun resume(displayRotation: Int) {
        if (closed.get()) return
        val token = generation.incrementAndGet()
        worker.post {
            shutdownCamera()
            if (generation.get() != token || closed.get()) return@post
            try {
                check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                val id = manager.cameraIdList.firstOrNull {
                    manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } ?: error("rear camera unavailable")
                val properties = manager.getCameraCharacteristics(id)
                val size = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                    .getOutputSizes(ImageFormat.YUV_420_888)
                    .filter { it.width in 16..640 && it.height in 16..640 && it.width % 2 == 0 && it.height % 2 == 0 }
                    .maxByOrNull { it.width * it.height } ?: error("analysis size unavailable")
                rotation = ((properties.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0) - displayRotation + 360) % 360
                realtimeClock = properties.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                val output = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                reader = output
                output.setOnImageAvailableListener({ source ->
                    if (generation.get() == token && !closed.get()) {
                        try { source.acquireLatestImage()?.use { process(it) } }
                        catch (_: Exception) { finish(failed = true); message(R.string.live_failed) }
                    }
                }, worker)
                manager.openCamera(id, context.mainExecutor, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (generation.get() != token || closed.get()) { device.close(); return }
                        worker.post {
                            if (generation.get() != token || closed.get()) { device.close(); return@post }
                            camera = device
                            try {
                                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                                    listOf(OutputConfiguration(output.surface)), context.mainExecutor,
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(session: CameraCaptureSession) {
                                            if (generation.get() != token || closed.get()) { session.close(); return }
                                            worker.post {
                                                if (generation.get() != token || closed.get()) { session.close(); return@post }
                                                try {
                                                    capture = session
                                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                                        addTarget(output.surface)
                                                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                                        val rates = properties.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                                                        rates?.filter { it.upper <= 30 }?.maxByOrNull { it.lower * 100 + it.upper }?.let {
                                                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                                                        }
                                                    }.build()
                                                    session.setRepeatingRequest(request, null, worker)
                                                    message(R.string.live_hint)
                                                } catch (_: Exception) { failCamera(token) }
                                            }
                                        }
                                        override fun onConfigureFailed(session: CameraCaptureSession) {
                                            session.close(); failCamera(token)
                                        }
                                    }))
                            } catch (_: Exception) { failCamera(token) }
                        }
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); failCamera(token) }
                    override fun onError(device: CameraDevice, error: Int) { device.close(); failCamera(token) }
                })
            } catch (_: Exception) { shutdownCamera(); message(R.string.live_unavailable) }
        }
    }

    private fun failCamera(token: Int) {
        if (generation.get() != token || closed.get()) return
        worker.post {
            if (generation.get() != token || closed.get()) return@post
            generation.incrementAndGet(); finish(); shutdownCamera(); message(R.string.live_interrupted)
        }
    }

    fun select(x: Float, y: Float) {
        worker.post { if (!armed && recorder == null) seed = PointF(x, y) }
    }

    fun record(audio: Boolean) {
        if (closed.get()) return
        worker.post {
            if (camera == null || seed == null || armed || recorder != null) {
                ui { listener.recording(false, false, false) }; return@post
            }
            audioAllowed = audio; armed = true
            message(R.string.live_recording)
        }
    }

    fun stopRecording() { worker.post { finish() } }

    fun pause() {
        generation.incrementAndGet()
        worker.post { shutdownCamera(); finish(); seed = null }
    }

    private fun plane(image: Image.Plane): Yuv420.Plane {
        val source = image.buffer.duplicate()
        val bytes = ByteArray(source.remaining()); source.get(bytes)
        return Yuv420.Plane(bytes, image.rowStride, image.pixelStride)
    }

    private fun process(image: Image) {
        val arrivalTime = SystemClock.elapsedRealtimeNanos()
        val crop = image.cropRect
        val pixels = Yuv420.rotate(Yuv420.decode(crop.width(), crop.height(),
            plane(image.planes[0]), plane(image.planes[1]), plane(image.planes[2]), crop.left, crop.top), rotation)
        val bitmap = Bitmap.createBitmap(pixels.width, pixels.height, Bitmap.Config.ARGB_8888)
        var published = false
        try {
            bitmap.setPixels(pixels.argb, 0, pixels.width, 0, 0, pixels.width, pixels.height)
            if (armed && recorder == null) {
                val point = seed ?: error("ball required")
                tracker = TrackingSession(pixels.width, pixels.height,
                    point.x * pixels.width.toDouble(), point.y * pixels.height.toDouble())
                firstTimestamp = image.timestamp; lastSeconds = -1.0
                val folder = File(context.filesDir, "captures").apply { check(mkdirs() || isDirectory) }
                val file = File(folder, "shot-${UUID.randomUUID()}.partial.mp4")
                val videoStart = if (realtimeClock) firstTimestamp else arrivalTime
                recorder = LiveRecorder(context, file, pixels.width, pixels.height, audioAllowed, videoStart)
                val hasAudio = recorder!!.hasAudio
                ui { listener.recording(true, false, hasAudio) }
            }
            val recording = recorder
            val seconds = if (recording != null) (image.timestamp - firstTimestamp) / 1_000_000_000.0 else 0.0
            val overlay: Bitmap
            if (recording != null) {
                if (seconds <= lastSeconds) return
                lastSeconds = seconds
                tracker!!.push(bitmap, seconds)
                overlay = tracker!!.overlay(seconds)
            } else {
                if (watermark?.width != pixels.width || watermark?.height != pixels.height) {
                    watermark?.recycle(); watermark = TrackingSession.watermarkOnly(pixels.width, pixels.height)
                }
                overlay = watermark!!
            }
            Canvas(bitmap).drawBitmap(overlay, 0f, 0f, null)
            if (recording != null) {
                overlay.recycle()
                recording.frame(bitmap, (seconds * 1_000_000).toLong())
            }
            if (framePending.compareAndSet(false, true)) {
                published = true
                val token = generation.get()
                ui {
                    try {
                        if (generation.get() == token && !closed.get()) listener.frame(bitmap) else bitmap.recycle()
                    } finally { framePending.set(false) }
                }
            }
            if (recording != null && seconds >= 15) finish()
        } finally { if (!published) bitmap.recycle() }
    }

    private fun finish(failed: Boolean = false) {
        armed = false
        val recording = recorder
        recorder = null
        val points = tracker?.count() ?: 0
        tracker?.close(); tracker = null
        if (recording == null) {
            ui { listener.recording(false, false, false) }
            return
        }
        ui { listener.recording(false, true, recording.hasAudio) }
        var saved = if (failed) {
            recording.close(); recording.file.delete(); false
        } else recording.finish()
        val complete = File(recording.file.parentFile, recording.file.name.removeSuffix(".partial.mp4") + ".mp4")
        if (saved) {
            saved = recording.file.renameTo(complete)
            if (!saved) recording.file.delete()
        }
        ui {
            listener.recording(false, false, false)
            if (saved) listener.saved(complete, points) else listener.message(R.string.live_failed)
        }
    }

    private fun shutdownCamera() {
        try { capture?.stopRepeating() } catch (_: Exception) { }
        try { capture?.close() } catch (_: Exception) { }; capture = null
        try { camera?.close() } catch (_: Exception) { }; camera = null
        try { reader?.close() } catch (_: Exception) { }; reader = null
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        worker.post {
            shutdownCamera(); finish()
            watermark?.recycle(); watermark = null
            thread.quitSafely()
        }
    }
}
