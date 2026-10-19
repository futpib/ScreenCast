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
