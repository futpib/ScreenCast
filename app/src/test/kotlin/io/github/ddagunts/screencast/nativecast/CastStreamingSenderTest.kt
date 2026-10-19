package io.github.ddagunts.screencast.nativecast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
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

    private fun receiveRtp(socket: DatagramSocket): DatagramPacket {
        while (true) {
            val packet = DatagramPacket(ByteArray(1500), 1500)
            socket.receive(packet)
            if (packet.length >= 19 && packet.data[1].toInt() and 0x7f == 96) return packet
        }
    }
}
