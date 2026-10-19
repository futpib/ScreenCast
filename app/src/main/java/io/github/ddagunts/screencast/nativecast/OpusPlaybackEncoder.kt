package io.github.ddagunts.screencast.nativecast

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlin.concurrent.thread

internal class OpusPlaybackEncoder private constructor(
    private val recorder: AudioRecord,
    private var handle: Long,
) {
    companion object {
        private const val SAMPLE_RATE = CastStreamingProtocol.AUDIO_SAMPLE_RATE
        private const val FRAME_BYTES = 480 * 2 * 2

        @SuppressLint("MissingPermission")
        fun prepare(context: Context, projection: MediaProjection): OpusPlaybackEncoder? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                logW("Native Cast audio: playback capture needs Android 10 and audio permission")
                return null
            }
            var recorder: AudioRecord? = null
            var handle = 0L
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                require(minBuffer > 0) { "playback capture does not support 48 kHz stereo" }
                recorder = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(maxOf(minBuffer, FRAME_BYTES * 8))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                require(recorder.state == AudioRecord.STATE_INITIALIZED) { "playback capture did not initialize" }
                handle = NativeOpusEncoder.create()
                require(handle != 0L) { "Opus encoder did not initialize" }
                recorder.startRecording()
                require(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "playback capture did not start"
                }
                logI("Native Cast audio: playback capture and 10 ms Opus encoder ready")
                return OpusPlaybackEncoder(recorder, handle)
            } catch (e: Throwable) {
                logW("Native Cast audio unavailable: $e")
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                if (handle != 0L) runCatching { NativeOpusEncoder.destroy(handle) }
                return null
            }
        }
    }

    @Volatile private var running = false
    private val nativeLock = Any()
    private var worker: Thread? = null

    fun start(onFrame: (ByteArray, Long) -> Unit, onFailure: (String) -> Unit) {
        running = true
        worker = thread(name = "native-cast-opus", isDaemon = true) { pump(onFrame, onFailure) }
    }

    fun stop() {
        running = false
        runCatching { recorder.stop() }
        if (Thread.currentThread() != worker) runCatching { worker?.join(500) }
        runCatching { recorder.release() }
        synchronized(nativeLock) {
            if (handle != 0L) NativeOpusEncoder.destroy(handle)
            handle = 0L
        }
    }

    private fun pump(onFrame: (ByteArray, Long) -> Unit, onFailure: (String) -> Unit) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val pcm = ByteArray(FRAME_BYTES)
        var nextPtsUs = 0L
        try {
            while (running) {
                var total = 0
                while (total < FRAME_BYTES && running) {
                    val count = recorder.read(pcm, total, FRAME_BYTES - total, AudioRecord.READ_BLOCKING)
                    if (count <= 0) error("playback capture read failed: $count")
                    total += count
                }
                if (!running) break
                if (nextPtsUs == 0L) nextPtsUs = System.nanoTime() / 1_000 - 10_000
                val packet = synchronized(nativeLock) {
                    if (!running || handle == 0L) null else NativeOpusEncoder.encode(handle, pcm)
                } ?: error("Opus encoding failed")
                onFrame(packet, nextPtsUs)
                nextPtsUs += 10_000
            }
        } catch (e: Exception) {
            if (running) {
                logW("Native Cast audio stopped: $e")
                onFailure("playback audio capture stopped")
            }
        }
    }
}
