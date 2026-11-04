package com.ajc.shottracer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer

/** Camera-worker confined. Queues are bounded while codecs announce muxer formats. */
internal class LiveRecorder(
    context: Context, val file: File, width: Int, height: Int,
    audioAllowed: Boolean, private val videoStartNs: Long
) : AutoCloseable {
    private var video: MediaCodec? = null
    private var audio: MediaCodec? = null
    private var microphone: AudioRecord? = null
    private var surface: EncoderSurface? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var videoEos = false
    private var audioEos = false
    private var audioSamples = 0L
    private var audioOffsetUs = 0L
    private var lastVideoUs = 0L
    private var frames = 0
    private var closed = false
    private data class Packet(val video: Boolean, val bytes: ByteArray, val timeUs: Long, val flags: Int)
    private val pending = ArrayList<Packet>()
    private var pendingBytes = 0
    val hasAudio: Boolean get() = microphone != null

    init {
        try {
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            video = codec
            codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = EncoderSurface(codec.createInputSurface(), width, height)
            codec.start()
            if (audioAllowed && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // A busy/unavailable microphone must not prevent silent video capture.
                try {
                    val minimum = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    check(minimum > 0)
                    val recorder = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, 44100,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 4, 16384))
                    microphone = recorder
                    check(recorder.state == AudioRecord.STATE_INITIALIZED)
                    val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    audio = encoder
                    encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 96000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                    }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    encoder.start(); recorder.startRecording()
                    check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    audioOffsetUs = maxOf(0L, (SystemClock.elapsedRealtimeNanos() - videoStartNs) / 1000)
                } catch (_: Exception) { releaseAudio() }
            }
            muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Exception) { close(); file.delete(); throw error }
    }

    fun frame(bitmap: Bitmap, timeUs: Long) {
        check(!closed)
        drain(video!!, true)
        pumpAudio()
        surface!!.draw(bitmap, timeUs)
        lastVideoUs = timeUs; frames++
        drain(video!!, true)
        audio?.let { drain(it, false) }
    }

    private fun pumpAudio() {
        val encoder = audio ?: return
        val recorder = microphone ?: return
        drain(encoder, false)
        repeat(8) {
            val index = encoder.dequeueInputBuffer(0)
            if (index < 0) return
            val buffer = encoder.getInputBuffer(index)!!
            buffer.clear()
            val bytes = ByteArray(minOf(4096, buffer.remaining()) and -2)
            val count = recorder.read(bytes, 0, bytes.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0)
            val timeUs = audioOffsetUs + audioSamples * 1_000_000 / 44100
            if (count > 0) { buffer.put(bytes, 0, count); audioSamples += count / 2 }
            encoder.queueInputBuffer(index, 0, count, timeUs, 0)
            drain(encoder, false)
            if (count == 0) return
        }
    }

    private fun drain(codec: MediaCodec, isVideo: Boolean, waitUs: Long = 0) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, waitUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val track = muxer!!.addTrack(codec.outputFormat)
                    if (isVideo) videoTrack = track else audioTrack = track
                    if (videoTrack >= 0 && (audio == null || audioTrack >= 0)) {
                        muxer!!.start(); started = true
                        pending.sortedBy { it.timeUs }.forEach { write(it) }
                        pending.clear(); pendingBytes = 0
                    }
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); buffer.get(bytes)
                            val packet = Packet(isVideo, bytes, info.presentationTimeUs, info.flags)
                            if (started) write(packet) else {
                                pendingBytes += bytes.size
                                check(pending.size < 180 && pendingBytes <= 8 * 1024 * 1024)
                                pending.add(packet)
                            }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (isVideo) videoEos = true else audioEos = true
                            return
                        }
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        }
    }

    private fun write(packet: Packet) {
        val info = MediaCodec.BufferInfo().apply { set(0, packet.bytes.size, packet.timeUs, packet.flags) }
        muxer!!.writeSampleData(if (packet.video) videoTrack else audioTrack, ByteBuffer.wrap(packet.bytes), info)
    }

    fun finish(): Boolean {
        if (closed) return false
        var success = false
        try {
            pumpAudio()
            microphone?.stop()
            video!!.signalEndOfInputStream()
            audio?.let { encoder ->
                val deadline = SystemClock.elapsedRealtime() + 2000
                var index = encoder.dequeueInputBuffer(10000)
                while (index < 0 && SystemClock.elapsedRealtime() < deadline) {
                    drain(encoder, false); index = encoder.dequeueInputBuffer(10000)
                }
                check(index >= 0)
                encoder.queueInputBuffer(index, 0, 0,
                    maxOf(lastVideoUs, audioOffsetUs + audioSamples * 1_000_000 / 44100), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            val deadline = SystemClock.elapsedRealtime() + 3000
            while ((!videoEos || (audio != null && !audioEos)) && SystemClock.elapsedRealtime() < deadline) {
                if (!videoEos) drain(video!!, true, 10000)
                if (!audioEos) audio?.let { drain(it, false, 10000) }
            }
            success = frames > 0 && started && videoEos && (audio == null || audioEos)
            if (started) { muxer!!.stop(); started = false }
        } catch (_: Exception) { success = false }
        finally { close() }
        if (!success) file.delete()
        return success
    }

    private fun releaseAudio() {
        try { microphone?.stop() } catch (_: Exception) { }
        try { microphone?.release() } catch (_: Exception) { }; microphone = null
        try { audio?.stop() } catch (_: Exception) { }
        try { audio?.release() } catch (_: Exception) { }; audio = null
    }

    override fun close() {
        if (closed) return
        closed = true
        try { surface?.close() } catch (_: Exception) { }; surface = null
        releaseAudio()
        try { video?.stop() } catch (_: Exception) { }
        try { video?.release() } catch (_: Exception) { }; video = null
        if (started) { try { muxer?.stop() } catch (_: Exception) { } }
        try { muxer?.release() } catch (_: Exception) { }; muxer = null
    }
}
