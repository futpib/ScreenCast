package io.github.ddagunts.screencast.nativecast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastStreamingSenderTest {
    @Test fun firstFrameSendsClockMappingBeforeMedia() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val stream = CastStreamingProtocol.VideoStream(
                0x12345678, ByteArray(16), ByteArray(16), 1280, 720, 30, 3_000_000)
            val sender = CastStreamingSender("127.0.0.1", receiver.localPort, stream) {}
            try {
                sender.start()
                sender.sendVideo(byteArrayOf(1, 2, 3), 1_000_000, true)
                val report = DatagramPacket(ByteArray(1500), 1500)
                receiver.receive(report)
                assertEquals(200, report.data[1].toInt() and 0xff)
                assertEquals(stream.ssrc, readInt(report.data, 4))
                assertEquals(90_000, readInt(report.data, 16))
                receiveRtp(receiver)
            } finally {
                sender.stop()
            }
        }
    }

    @Test fun queueOverflowWaitsForKeyFrameBeforeSendingMoreVideo() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 1_000
            val stream = CastStreamingProtocol.VideoStream(
                0x12345678, ByteArray(16), ByteArray(16), 1280, 720, 30, 3_000_000)
            val keyFrameRequested = CountDownLatch(1)
            val sender = CastStreamingSender("127.0.0.1", receiver.localPort, stream) {
                keyFrameRequested.countDown()
            }
            try {
                sender.sendVideo(byteArrayOf(1), 1_000_000, true)
                repeat(8) { sender.sendVideo(byteArrayOf(2), 1_033_333L + it * 33_333L, false) }
                sender.sendVideo(byteArrayOf(3), 1_300_000, false)
                sender.sendVideo(byteArrayOf(4), 1_333_333, true)
                sender.start()
                assertTrue(keyFrameRequested.await(1, TimeUnit.SECONDS))
                val packet = receiveRtp(receiver)
                assertTrue(packet.data[12].toInt() and 0x80 != 0)
                receiver.soTimeout = 200
                assertTrue(runCatching { receiveRtp(receiver) }.isFailure)
            } finally {
                sender.stop()
            }
        }
    }

    @Test fun retransmitsNackedPacketAndRequestsKeyFrameOnPli() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val stream = CastStreamingProtocol.VideoStream(
                0x12345678, ByteArray(16), ByteArray(16), 1280, 720, 30, 3_000_000)
            val keyFrameRequested = CountDownLatch(1)
            val sender = CastStreamingSender("127.0.0.1", receiver.localPort, stream) {
                keyFrameRequested.countDown()
            }
            try {
                sender.start()
                sender.sendVideo(byteArrayOf(1, 2, 3, 4), 1_000_000, true)
                val first = receiveRtp(receiver)
                val from = first.socketAddress
                val bytes = first.data.copyOf(first.length)
                val nack = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
                    .put(0x8f.toByte()).put(206.toByte()).putShort(5)
                    .putInt(99).putInt(stream.ssrc).putInt(0x43415354)
                    .put(0xff.toByte()).put(1).putShort(200)
                    .put(0).putShort(0).put(0)
                    .array()
                receiver.send(DatagramPacket(nack, nack.size, from))
                assertArrayEquals(bytes, receiveRtp(receiver).data.let { it.copyOf(bytes.size) })

                val pli = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                    .put(0x81.toByte()).put(206.toByte()).putShort(2)
                    .putInt(99).putInt(stream.ssrc).array()
                receiver.send(DatagramPacket(pli, pli.size, from))
                assertTrue(keyFrameRequested.await(3, TimeUnit.SECONDS))
            } finally {
                sender.stop()
            }
        }
    }

    @Test fun audioAndVideoUseOneSocketAndDistinctRtpClocks() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val video = CastStreamingProtocol.VideoStream(
                0x12345678, ByteArray(16), ByteArray(16), 1280, 720, 30, 3_000_000)
            val audio = CastStreamingProtocol.AudioStream(
                0x55667788, ByteArray(16), ByteArray(16))
            val sender = CastStreamingSender("127.0.0.1", receiver.localPort, video, audio) {}
            try {
                sender.start()
                sender.sendAudio(byteArrayOf(1, 2, 3), 1_000_000)
                sender.sendVideo(byteArrayOf(4, 5, 6), 1_000_000, true)
                val packets = mutableMapOf<Int, DatagramPacket>()
                while (packets.size < 2) {
                    val packet = DatagramPacket(ByteArray(1500), 1500)
                    receiver.receive(packet)
                    val payload = packet.data[1].toInt() and 0x7f
                    if (payload == 96 || payload == 127) packets[payload] = packet
                }
                assertEquals(packets[96]!!.port, packets[127]!!.port)
                assertEquals(90_000, readInt(packets[96]!!.data, 4))
                assertEquals(48_000, readInt(packets[127]!!.data, 4))
            } finally {
                sender.stop()
            }
        }
    }

    @Test fun audioPacketsAreInterleavedWithLargeVideoFrames() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val video = CastStreamingProtocol.VideoStream(
                0x12345678, ByteArray(16), ByteArray(16), 1280, 720, 30, 3_000_000)
            val audio = CastStreamingProtocol.AudioStream(
                0x55667788, ByteArray(16), ByteArray(16))
            val sender = CastStreamingSender("127.0.0.1", receiver.localPort, video, audio) {}
            try {
                sender.start()
                sender.sendVideo(ByteArray(300_000) { it.toByte() }, 1_000_000, true)
                receiveRtp(receiver)
                sender.sendAudio(byteArrayOf(1, 2, 3), 1_010_000)
                var sawAudio = false
                var sawLastVideoPacket = false
                while (!sawLastVideoPacket) {
                    val packet = DatagramPacket(ByteArray(1500), 1500)
                    receiver.receive(packet)
                    when (packet.data[1].toInt() and 0x7f) {
                        127 -> sawAudio = true
                        96 -> if (packet.data[1].toInt() and 0x80 != 0) sawLastVideoPacket = true
                    }
                }
                assertTrue(sawAudio)
            } finally {
                sender.stop()
            }
        }
    }

    @Test fun stopsCaptureUntilReceiverCatchesUpAndStartsWithKeyFrame() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 3_000
            val stream = CastStreamingProtocol.VideoStream(
                0x12345678, ByteArray(16), ByteArray(16), 1280, 720, 30, 3_000_000)
            val paused = CountDownLatch(1)
            val resumed = CountDownLatch(1)
            val sender = CastStreamingSender("127.0.0.1", receiver.localPort, stream,
                onCapturePaused = { if (it) paused.countDown() else resumed.countDown() }) {}
            try {
                sender.start()
                sender.sendVideo(byteArrayOf(1), 1_000_000, true)
                val first = receiveRtp(receiver)
                sendCheckpoint(receiver, first, stream.ssrc, 0)
                assertTrue(waitUntil(1_000) { sender.acknowledged })
                for (frame in 1..15) {
                    sender.sendVideo(byteArrayOf(2), 1_000_000L + frame * 33_333L, false)
                    receiveRtp(receiver)
                }
                assertTrue(paused.await(1, TimeUnit.SECONDS))
                sender.sendVideo(byteArrayOf(3), 1_500_000, true)
                receiver.soTimeout = 100
                assertTrue(runCatching { receiveRtp(receiver) }.isFailure)
                receiver.soTimeout = 3_000
                sendCheckpoint(receiver, first, stream.ssrc, 14)
                assertTrue(resumed.await(1, TimeUnit.SECONDS))
                sender.sendVideo(byteArrayOf(4), 1_533_333, false)
                sender.sendVideo(byteArrayOf(5), 1_566_666, true)
                val next = receiveRtp(receiver)
                assertTrue(next.data[12].toInt() and 0x80 != 0)
            } finally {
                sender.stop()
            }
        }
    }

    private fun sendCheckpoint(socket: DatagramSocket, packet: DatagramPacket, ssrc: Int,
                               frameId: Int) {
        val bytes = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
            .put(0x8f.toByte()).put(206.toByte()).putShort(4)
            .putInt(99).putInt(ssrc).putInt(0x43415354)
            .put(frameId.toByte()).put(0).putShort(200).array()
        socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(1)
        }
        return condition()
    }

    private fun readInt(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun receiveRtp(socket: DatagramSocket): DatagramPacket {
        while (true) {
            val packet = DatagramPacket(ByteArray(1500), 1500)
            socket.receive(packet)
            if (packet.length >= 19 && packet.data[1].toInt() and 0x7f == 96) return packet
        }
    }
}
