package io.github.ddagunts.screencast.nativecast

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CastStreamingProtocolTest {
    private val stream = CastStreamingProtocol.VideoStream(
        0x12345678, ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 20).toByte() },
        1280, 720, 30, 3_000_000)

    @Test fun offerAndAnswerSelectH264AndUdpPort() {
        val offer = CastStreamingProtocol.offer(7, stream)
        val video = offer.getJSONObject("offer").getJSONArray("supportedStreams").getJSONObject(0)
        assertEquals("mirroring", offer.getJSONObject("offer").getString("castMode"))
        assertEquals("h264", video.getString("codecName"))
        assertEquals(96, video.getInt("rtpPayloadType"))
        assertEquals("1/90000", video.getString("timeBase"))
        assertEquals(32, video.getString("aesKey").length)
        val answer = JSONObject("""{"type":"ANSWER","seqNum":7,"result":"ok",
            "answer":{"udpPort":2345,"sendIndexes":[0]}}""")
        assertEquals(2345, CastStreamingProtocol.answerPort(answer, 7))
        assertThrows(IllegalArgumentException::class.java) {
            CastStreamingProtocol.answerPort(answer, 8)
        }
    }

    @Test fun encryptedRtpFrameRoundTripsAcrossPackets() {
        val data = ByteArray(3000) { (it % 251).toByte() }
        val packets = CastStreamingProtocol.Packetizer(stream, 65535)
            .packetize(0, 90_000, true, data)
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.size <= 1472 })
        assertEquals(0x80, packets[0][0].toInt() and 0xff)
        assertEquals(96, packets[0][1].toInt() and 0xff)
        assertEquals(0xe0, packets.last()[1].toInt() and 0xff)
        assertEquals(65535, readShort(packets[0], 2))
        assertEquals(0, readShort(packets[1], 2))
        assertEquals(90_000, readInt(packets[0], 4))
        assertEquals(stream.ssrc, readInt(packets[0], 8))
        assertEquals(0xc1, packets[0][12].toInt() and 0xff)
        assertEquals(0xc0, packets[1][12].toInt() and 0xff)
        assertEquals(0x0402, readShort(packets[0], 19))
        assertEquals(200, readShort(packets[0], 21))
        val encrypted = packets.flatMapIndexed { i, p ->
            p.drop(if (i == 0) 23 else 19)
        }.toByteArray()
        val decrypted = Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(stream.key, "AES"), IvParameterSpec(stream.ivMask))
            doFinal(encrypted)
        }
        assertArrayEquals(data, decrypted)
    }

    @Test fun laterFrameUsesXoredIvAndReferenceFrame() {
        val frameId = 0x01020304L
        val packet = CastStreamingProtocol.Packetizer(stream, 1)
            .packetize(frameId, 100, false, byteArrayOf(1, 2, 3))[0]
        assertEquals(0x40, packet[12].toInt() and 0xff)
        assertEquals(3, packet[18].toInt() and 0xff)
        val iv = stream.ivMask.clone()
        for (i in 0..3) iv[8 + i] = (iv[8 + i].toInt() xor
            (frameId.toInt() ushr (24 - i * 8))).toByte()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(stream.key, "AES"), IvParameterSpec(iv))
        assertArrayEquals(byteArrayOf(1, 2, 3), cipher.doFinal(packet.copyOfRange(19, packet.size)))
    }

    @Test fun castFeedbackExpandsNacksAndPictureLoss() {
        val feedback = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .put(0x8f.toByte()).put(206.toByte()).putShort(5)
            .putInt(99).putInt(stream.ssrc).putInt(0x43415354)
            .put(0xff.toByte()).put(1).putShort(200)
            .put(0).putShort(4).put(0b00000101)
            .array()
        val pli = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(0x81.toByte()).put(206.toByte()).putShort(2)
            .putInt(99).putInt(stream.ssrc).array()
        val result = CastStreamingProtocol.feedback(feedback + pli, stream.ssrc, -1)
        assertEquals(-1L, result.checkpoint)
        assertEquals(listOf(4, 5, 7), result.nacks.map { it.packetId })
        assertTrue(result.pictureLoss)
        assertFalse(CastStreamingProtocol.feedback(feedback, 123, -1).nacks.isNotEmpty())
    }

    @Test fun senderReportHasRtcpFields() {
        val report = CastStreamingProtocol.senderReport(stream.ssrc,
            0x1020304050607080L, 9000, 12, 2000)
        assertEquals(28, report.size)
        assertEquals(200, report[1].toInt() and 0xff)
        assertEquals(6, readShort(report, 2))
        assertEquals(stream.ssrc, readInt(report, 4))
        assertEquals(9000, readInt(report, 16))
        assertEquals(12, readInt(report, 20))
    }

    private fun readShort(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
    private fun readInt(bytes: ByteArray, at: Int) =
        ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.BIG_ENDIAN).int
}
