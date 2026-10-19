package io.github.ddagunts.screencast.nativecast

import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class CastStreamingSender(
    private val host: String,
    private val port: Int,
    private val stream: CastStreamingProtocol.VideoStream,
    private val onPictureLoss: () -> Unit,
) {
    private data class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean)
    private data class SentFrame(val id: Long, val packets: List<ByteArray>)

    private val queue = ArrayBlockingQueue<Frame>(8)
    private val socket = DatagramSocket().apply { soTimeout = 1 }
    private val peer = InetSocketAddress(InetAddress.getByName(host), port)
    private val packetizer = CastStreamingProtocol.Packetizer(stream)
    private val history = ArrayDeque<SentFrame>(120)
    @Volatile private var running = true
    private var worker: Thread? = null
    private var nextFrameId = 0L
    private var checkpoint = -1L
    private var packetCount = 0
    private var octetCount = 0
    private var lastRtp = 0
    private var lastNtp = 0L
    private var lastReportMs = 0L
    @Volatile var acknowledged = false; private set

    fun start() {
        worker = thread(name = "cast-streaming-udp", isDaemon = true) { run() }
    }

    fun sendVideo(data: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!running || data.isEmpty()) return
        val frame = Frame(data, ptsUs, keyFrame)
        if (!queue.offer(frame)) {
            queue.poll()
            queue.offer(frame)
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        if (Thread.currentThread() != worker) runCatching { worker?.join(500) }
        socket.close()
        queue.clear()
    }

    private fun run() {
        val recv = ByteArray(1500)
        while (running) {
            try {
                queue.poll(2, TimeUnit.MILLISECONDS)?.let { sendFrame(it) }
                receiveFeedback(recv)
                val now = System.currentTimeMillis()
                if (nextFrameId > 0 && now - lastReportMs >= 500) {
                    send(CastStreamingProtocol.senderReport(
                        stream.ssrc, lastNtp, lastRtp, packetCount, octetCount))
                    lastReportMs = now
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (running) logW("Cast Streaming UDP: $e")
            }
        }
    }

    private fun sendFrame(frame: Frame) {
        val id = nextFrameId++
        val rtp = ((frame.ptsUs * 90) / 1000).toInt()
        val packets = packetizer.packetize(id, rtp, frame.key, frame.data)
        for (packet in packets) {
            send(packet)
            packetCount++
            octetCount += packet.size
        }
        lastRtp = rtp
        lastNtp = CastStreamingProtocol.ntpNow()
        history.addLast(SentFrame(id, packets))
        while (history.size > 120) history.removeFirst()
        if (id == 0L) logI("Cast Streaming: first video frame sent to $host:$port")
    }

    private fun receiveFeedback(buffer: ByteArray) {
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: java.net.SocketTimeoutException) { return }
            if (packet.address != peer.address) continue
            val feedback = CastStreamingProtocol.feedback(
                buffer.copyOf(packet.length), stream.ssrc, checkpoint)
            feedback.checkpoint?.let {
                if (it >= 0) acknowledged = true
                checkpoint = maxOf(checkpoint, it)
                while (history.isNotEmpty() && history.first.id <= checkpoint) history.removeFirst()
            }
            for (nack in feedback.nacks) {
                val sent = history.firstOrNull { it.id == nack.frameId } ?: continue
                if (nack.packetId == 0xffff) sent.packets.forEach(::send)
                else sent.packets.getOrNull(nack.packetId)?.let(::send)
            }
            if (feedback.pictureLoss) onPictureLoss()
        }
    }

    private fun send(bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, peer))
    }
}
