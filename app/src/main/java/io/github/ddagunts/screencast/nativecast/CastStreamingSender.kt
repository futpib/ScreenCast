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
import kotlin.concurrent.thread

internal class CastStreamingSender(
    private val host: String,
    private val port: Int,
    video: CastStreamingProtocol.VideoStream,
    audio: CastStreamingProtocol.AudioStream? = null,
    private val onPictureLoss: () -> Unit,
) {
    private data class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean)
    private data class SentFrame(val id: Long, val packets: List<ByteArray>)

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
        @Volatile var acknowledged = false
    }

    private val videoStream = Stream(video.ssrc, CastStreamingProtocol.Packetizer(video), 90_000, 8)
    private val audioStream = audio?.let {
        Stream(it.ssrc, CastStreamingProtocol.Packetizer(it), CastStreamingProtocol.AUDIO_SAMPLE_RATE, 16)
    }
    private val streams = listOfNotNull(videoStream, audioStream)
    private val socket = DatagramSocket().apply { soTimeout = 1 }
    private val peer = InetSocketAddress(InetAddress.getByName(host), port)
    @Volatile private var running = true
    private var worker: Thread? = null
    val acknowledged: Boolean get() = videoStream.acknowledged

    fun start() {
        worker = thread(name = "cast-streaming-udp", isDaemon = true) { run() }
    }

    fun sendVideo(data: ByteArray, ptsUs: Long, keyFrame: Boolean) =
        enqueue(videoStream, Frame(data, ptsUs, keyFrame))

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
                audioStream?.queue?.poll()?.let { sendFrame(audioStream, it) }
                videoStream.queue.poll()?.let { sendFrame(videoStream, it) }
                receiveFeedback(recv)
                val now = System.currentTimeMillis()
                for (stream in streams) {
                    if (stream.nextFrameId > 0 && now - stream.lastReportMs >= 500) {
                        send(CastStreamingProtocol.senderReport(stream.ssrc, stream.lastNtp,
                            stream.lastRtp, stream.packetCount, stream.octetCount))
                        stream.lastReportMs = now
                    }
                }
                if (videoStream.queue.isEmpty() && audioStream?.queue?.isEmpty() != false) {
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
        val packets = stream.packetizer.packetize(id, rtp, frame.key, frame.data)
        for (packet in packets) {
            send(packet)
            stream.packetCount++
            stream.octetCount += packet.size
        }
        stream.lastRtp = rtp
        stream.lastNtp = CastStreamingProtocol.ntpForPts(frame.ptsUs)
        stream.history.addLast(SentFrame(id, packets))
        while (stream.history.size > 120) stream.history.removeFirst()
        if (id == 0L) logI("Cast Streaming: first ${if (stream == videoStream) "video" else "audio"} frame sent to $host:$port")
    }

    private fun receiveFeedback(buffer: ByteArray) {
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { return }
            if (packet.address != peer.address || packet.length < 12) continue
            for (stream in streams) {
                val feedback = CastStreamingProtocol.feedback(
                    buffer.copyOf(packet.length), stream.ssrc, stream.checkpoint)
                feedback.checkpoint?.let {
                    if (it >= 0) stream.acknowledged = true
                    stream.checkpoint = maxOf(stream.checkpoint, it)
                    while (stream.history.isNotEmpty() && stream.history.first.id <= stream.checkpoint) {
                        stream.history.removeFirst()
                    }
                }
                for (nack in feedback.nacks) {
                    val sent = stream.history.firstOrNull { it.id == nack.frameId } ?: continue
                    if (nack.packetId == 0xffff) sent.packets.forEach(::send)
                    else sent.packets.getOrNull(nack.packetId)?.let(::send)
                }
                if (stream == videoStream && feedback.pictureLoss) onPictureLoss()
            }
        }
    }

    private fun send(bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, peer))
    }
}
