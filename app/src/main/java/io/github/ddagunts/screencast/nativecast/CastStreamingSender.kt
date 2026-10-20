package io.github.ddagunts.screencast.nativecast

import android.os.Process
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread

internal class CastStreamingSender(
    private val host: String,
    private val port: Int,
    video: CastStreamingProtocol.VideoStream?,
    audio: CastStreamingProtocol.AudioStream? = null,
    initialBitrate: Int = video?.bitRate ?: 0,
    private val onBitrateChange: (Int) -> Unit = {},
    private val onCapturePaused: (Boolean) -> Unit = {},
    private val onTransportFailure: (String) -> Unit = {},
    private val onPictureLoss: () -> Unit,
) {
    private data class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean)
    private data class SentFrame(val id: Long, val ptsUs: Long, val sentAtMs: Long,
                                 val packets: List<ByteArray>)

    private class Stream(
        val ssrc: Int,
        val packetizer: CastStreamingProtocol.Packetizer,
        val sampleRate: Int,
        capacity: Int,
    ) {
        val queue = ArrayBlockingQueue<Frame>(capacity)
        val history = ArrayDeque<SentFrame>(120)
        var nextFrameId = 0L
        var checkpoint = -1L
        var packetCount = 0
        var octetCount = 0
        var lastRtp = 0
        var lastNtp = 0L
        var lastReportMs = 0L
        var targetDelayMs = CastStreamingProtocol.TARGET_DELAY_MS
        @Volatile var acknowledged = false
    }

    private val videoStream = video?.let {
        Stream(it.ssrc, CastStreamingProtocol.Packetizer(it), 90_000, 8)
    }
    private val audioStream = audio?.let {
        Stream(it.ssrc, CastStreamingProtocol.Packetizer(it), CastStreamingProtocol.AUDIO_SAMPLE_RATE, 16)
    }
    private val streams = listOfNotNull(videoStream, audioStream)
    init { require(streams.isNotEmpty()) { "Cast Streaming needs an audio or video stream" } }
    private val socket = DatagramSocket().apply { soTimeout = 1 }
    private val peer = InetSocketAddress(InetAddress.getByName(host), port)
    private val rateController = video?.let {
        CastRateController(initialBitrate, minBitrate = it.minBitRate,
            maxBitrate = it.bitRate)
    }
    private val inFlightController = CastInFlightController()
    private var pacingBytes = 24_000.0
    private var pacingAtNs = System.nanoTime()
    private var lastControlMs = 0L
    private var lastReceiverKeyFrameRequestMs = 0L
    @Volatile private var recoveringVideo = false
    @Volatile private var capturePaused = false
    @Volatile private var videoQueueOverrun = false
    @Volatile private var running = true
    private var worker: Thread? = null
    val acknowledged: Boolean get() = (videoStream ?: audioStream)?.acknowledged == true
    val audioAcknowledged: Boolean get() = audioStream?.acknowledged == true
    val videoBitrate: Int get() = rateController?.bitrate ?: 0
    val videoCapturePaused: Boolean get() = capturePaused

    fun resetVideoForFormatChange() {
        videoStream?.let { stream -> synchronized(stream.queue) {
            stream.queue.clear()
            recoveringVideo = true
        } }
    }

    fun start() {
        worker = thread(name = "cast-streaming-udp", isDaemon = true) { run() }
    }

    fun sendVideo(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        val stream = videoStream ?: return
        if (!running || data.isEmpty()) return
        synchronized(stream.queue) {
            if (capturePaused) return
            if (recoveringVideo && !keyFrame) return
            if (recoveringVideo && keyFrame) {
                stream.queue.clear()
                recoveringVideo = false
            }
            if (!stream.queue.offer(Frame(data, ptsUs, keyFrame))) {
                stream.queue.clear()
                if (keyFrame) stream.queue.offer(Frame(data, ptsUs, true))
                else {
                    recoveringVideo = true
                    videoQueueOverrun = true
                }
            }
        }
    }

    fun sendAudio(data: ByteArray, ptsUs: Long) {
        audioStream?.let { enqueue(it, Frame(data, ptsUs, true)) }
    }

    private fun enqueue(stream: Stream, frame: Frame) {
        if (!running || frame.data.isEmpty()) return
        if (!stream.queue.offer(frame)) {
            stream.queue.poll()
            stream.queue.offer(frame)
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        if (Thread.currentThread() != worker) runCatching { worker?.join(500) }
        socket.close()
        streams.forEach { it.queue.clear() }
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val recv = ByteArray(1500)
        while (running) {
            try {
                if (videoQueueOverrun) {
                    videoQueueOverrun = false
                    rateController?.onPictureLoss(System.nanoTime() / 1_000_000)?.let(onBitrateChange)
                    onPictureLoss()
                }
                audioStream?.queue?.poll()?.let { sendFrame(audioStream, it) }
                videoStream?.queue?.poll()?.let { sendFrame(videoStream, it) }
                receiveFeedback(recv)
                val now = System.nanoTime() / 1_000_000
                videoStream?.let { video -> when (inFlightController.update(now, video.acknowledged,
                        video.history.firstOrNull()?.ptsUs,
                        video.history.lastOrNull()?.ptsUs,
                        video.targetDelayMs)) {
                    CastInFlightController.Action.PAUSE -> {
                        rateController?.onBackpressure(now)?.let(onBitrateChange)
                        synchronized(video.queue) {
                            capturePaused = true
                            recoveringVideo = true
                            video.queue.clear()
                        }
                        onCapturePaused(true)
                    }
                    CastInFlightController.Action.RESUME -> {
                        synchronized(video.queue) { capturePaused = false }
                        onCapturePaused(false)
                    }
                    CastInFlightController.Action.FAIL -> {
                        onTransportFailure("receiver stopped acknowledging video")
                        return
                    }
                    CastInFlightController.Action.NONE -> Unit
                } }
                for (stream in streams) {
                    if (stream.nextFrameId > 0 && now - stream.lastReportMs >= 500) {
                        send(CastStreamingProtocol.senderReport(stream.ssrc, stream.lastNtp,
                            stream.lastRtp, stream.packetCount, stream.octetCount))
                        stream.lastReportMs = now
                    }
                }
                if (now - lastControlMs >= 500) {
                    val oldestAge = videoStream?.history?.firstOrNull()?.let { now - it.sentAtMs } ?: 0L
                    rateController?.update(now, oldestAge,
                        videoStream?.targetDelayMs ?: CastStreamingProtocol.TARGET_DELAY_MS)
                        ?.let(onBitrateChange)
                    lastControlMs = now
                }
                if (videoStream?.queue?.isEmpty() != false && audioStream?.queue?.isEmpty() != false) {
                    Thread.sleep(1)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) logW("Cast Streaming UDP: $e")
            }
        }
    }

    private fun sendFrame(stream: Stream, frame: Frame) {
        val id = stream.nextFrameId++
        val rtp = ((frame.ptsUs * stream.sampleRate) / 1_000_000).toInt()
        val ntp = CastStreamingProtocol.ntpForPts(frame.ptsUs)
        if (id == 0L) {
            send(CastStreamingProtocol.senderReport(stream.ssrc, ntp, rtp,
                stream.packetCount, stream.octetCount))
            stream.lastReportMs = System.nanoTime() / 1_000_000
        }
        val packets = stream.packetizer.packetize(id, rtp, frame.key, frame.data)
        for ((index, packet) in packets.withIndex()) {
            if (videoStream != null && stream == videoStream) paceVideo(packet.size)
            send(packet)
            stream.packetCount++
            stream.octetCount += packet.size
            if (videoStream != null && stream == videoStream && index % 4 == 3) {
                audioStream?.queue?.poll()?.let { sendFrame(audioStream, it) }
            }
        }
        stream.lastRtp = rtp
        stream.lastNtp = ntp
        stream.history.addLast(SentFrame(id, frame.ptsUs, System.nanoTime() / 1_000_000, packets))
        while (stream.history.size > 120) stream.history.removeFirst()
        if (id == 0L) logI("Cast Streaming: first ${if (videoStream != null && stream == videoStream) "video" else "audio"} frame sent to $host:$port")
    }

    private fun receiveFeedback(buffer: ByteArray) {
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { return }
            if (packet.address != peer.address || packet.port != peer.port ||
                packet.length < 12) continue
            for (stream in streams) {
                val feedback = CastStreamingProtocol.feedback(
                    buffer.copyOf(packet.length), stream.ssrc, stream.checkpoint)
                feedback.checkpoint?.let {
                    feedback.targetDelayMs?.takeIf { delay -> delay in 1..5_000 }?.let { delay ->
                        stream.targetDelayMs = delay
                    }
                    if (it >= 0) stream.acknowledged = true
                    if (videoStream != null && stream == videoStream && it > stream.checkpoint) {
                        val now = System.nanoTime() / 1_000_000
                        val sentAt = stream.history.lastOrNull { frame -> frame.id <= it }?.sentAtMs
                        rateController?.onCheckpoint(now, it - stream.checkpoint,
                            sentAt?.let { time -> now - time })
                        inFlightController.onCheckpoint(now)
                    }
                    stream.checkpoint = maxOf(stream.checkpoint, it)
                    while (stream.history.isNotEmpty() && stream.history.first.id <= stream.checkpoint) {
                        stream.history.removeFirst()
                    }
                }
                for (nack in feedback.nacks) {
                    val sent = stream.history.firstOrNull { it.id == nack.frameId } ?: continue
                    if (System.nanoTime() / 1_000_000 - sent.sentAtMs >
                        stream.targetDelayMs + 250L) {
                        if (videoStream != null && stream == videoStream) requestReceiverKeyFrame()
                        continue
                    }
                    if (videoStream != null && stream == videoStream) rateController?.onNack()
                    if (nack.packetId == 0xffff) sent.packets.forEach(::send)
                    else sent.packets.getOrNull(nack.packetId)?.let(::send)
                }
                if (videoStream != null && stream == videoStream && feedback.pictureLoss) {
                    rateController?.onPictureLoss(System.nanoTime() / 1_000_000)?.let(onBitrateChange)
                    requestReceiverKeyFrame()
                }
            }
        }
    }

    private fun requestReceiverKeyFrame() {
        val now = System.nanoTime() / 1_000_000
        if (now - lastReceiverKeyFrameRequestMs >= 500) {
            lastReceiverKeyFrameRequestMs = now
            onPictureLoss()
        }
    }

    private fun paceVideo(bytes: Int) {
        val rateBytesPerSecond = maxOf(5_000_000, (rateController?.bitrate ?: 3_000_000) * 3) / 8.0
        while (running) {
            val now = System.nanoTime()
            pacingBytes = minOf(24_000.0,
                pacingBytes + (now - pacingAtNs).coerceAtLeast(0) * rateBytesPerSecond / 1_000_000_000)
            pacingAtNs = now
            if (pacingBytes >= bytes) {
                pacingBytes -= bytes
                return
            }
            val waitNs = ((bytes - pacingBytes) * 1_000_000_000 / rateBytesPerSecond).toLong()
            LockSupport.parkNanos(waitNs.coerceAtLeast(100_000))
        }
    }

    private fun send(bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, peer))
    }
}
