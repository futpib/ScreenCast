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
    @Test fun receiverMinimumBitrateDoesNotRejectValidVideoOffer() {
        val profile = CastVideoQuality.profiles.first { it.width == 1920 && it.fps == 60 }
        val stream = CastStreamingProtocol.VideoStream.random(profile.width, profile.height,
            profile.fps, profile.bitrate).copy(minBitRate = 5_000_000)
        val initial = CastVideoQuality.initialBitrate(stream, profile)
        assertEquals(5_000_000, initial)
        CastRateController(initial, minBitrate = stream.minBitRate, maxBitrate = stream.bitRate)
    }

    @Test fun fourKOfferDeclaresSupportedAvcLevel() {
        val profile = CastVideoQuality.profiles.first { it.width == 3840 }
        val stream = CastStreamingProtocol.VideoStream.random(profile.width, profile.height,
            profile.fps, profile.bitrate)
        val video = CastStreamingProtocol.offer(1, stream)
            .getJSONObject("offer").getJSONArray("supportedStreams").getJSONObject(0)
        assertEquals("5.1", video.getString("level"))
        assertEquals(12_000_000, video.getInt("maxBitRate"))
    }
    private val stream = CastStreamingProtocol.VideoStream(
        0x12345678, ByteArray(16) { it.toByte() }, ByteArray(16) { (it + 20).toByte() },
        1280, 720, 30, 3_000_000)

    @Test fun generatedSsrcFitsReceiverSignedIntegerParser() {
        repeat(100) {
            assertTrue(CastStreamingProtocol.VideoStream.random(1280, 720, 30, 3_000_000).ssrc > 0)
            assertTrue(CastStreamingProtocol.AudioStream.random().ssrc > 0)
        }
    }

    @Test fun offerAndAnswerSelectH264AndUdpPort() {
        val offer = CastStreamingProtocol.offer(7, stream)
        val video = offer.getJSONObject("offer").getJSONArray("supportedStreams").getJSONObject(0)
        assertEquals("mirroring", offer.getJSONObject("offer").getString("castMode"))
        assertEquals("h264", video.getString("codecName"))
        assertEquals("intra_mb_refresh", video.getString("errorRecoveryMode"))
        assertEquals(96, video.getInt("rtpPayloadType"))
        assertEquals("1/90000", video.getString("timeBase"))
        assertEquals(32, video.getString("aesKey").length)
        val answer = JSONObject("""{"type":"ANSWER","seqNum":7,"result":"ok",
            "answer":{"udpPort":2345,"sendIndexes":[0]}}""")
        assertEquals(CastStreamingProtocol.Answer(2345, false),
            CastStreamingProtocol.answer(answer, 7, 0))
        assertThrows(IllegalArgumentException::class.java) {
            CastStreamingProtocol.answer(answer, 8, 0)
        }
    }

    @Test fun offerAndAnswerSelectOpusAndVideoSeparately() {
        val audio = CastStreamingProtocol.AudioStream(
            0x55667788, ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val streams = CastStreamingProtocol.offer(4, stream, audio)
            .getJSONObject("offer").getJSONArray("supportedStreams")
        val offeredAudio = streams.getJSONObject(0)
        val offeredVideo = streams.getJSONObject(1)
        assertEquals("opus", offeredAudio.getString("codecName"))
        assertEquals("1/48000", offeredAudio.getString("timeBase"))
        assertEquals(127, offeredAudio.getInt("rtpPayloadType"))
        assertEquals(2, offeredAudio.getInt("channels"))
        assertEquals(1, offeredVideo.getInt("index"))
        val both = JSONObject("""{"type":"ANSWER","seqNum":4,"result":"ok",
            "answer":{"udpPort":2345,"sendIndexes":[0,1]}}""")
        assertEquals(CastStreamingProtocol.Answer(2345, true),
            CastStreamingProtocol.answer(both, 4, 1))
        val videoOnly = JSONObject("""{"type":"ANSWER","seqNum":4,"result":"ok",
            "answer":{"udpPort":2345,"sendIndexes":[1]}}""")
        assertEquals(CastStreamingProtocol.Answer(2345, false),
            CastStreamingProtocol.answer(videoOnly, 4, 1))
        assertThrows(IllegalArgumentException::class.java) {
            CastStreamingProtocol.answer(JSONObject("""{"type":"ANSWER","seqNum":4,"result":"ok",
                "answer":{"udpPort":2345,"sendIndexes":[0]}}"""), 4, 1)
        }
    }

    @Test fun receiverConstraintsBoundCaptureAndBitrate() {
        val answer = JSONObject("""{"type":"ANSWER","seqNum":2,"result":"ok",
            "answer":{"udpPort":5000,"sendIndexes":[0,1],"constraints":{
                "video":{"codecName":"h264","maxDimensions":{"width":1280,
                    "height":720,"frameRate":"30000/1001"},
                    "minResolution":{"width":640,"height":360},
                    "maxPixelsPerSecond":28000000,"minBitRate":500000,
                    "maxBitRate":1500000,"maxDelay":500},
                "audio":{"codecName":"opus","maxSampleRate":48000,"maxChannels":2,
                    "maxBitRate":128000}}}}""")
        val accepted = CastStreamingProtocol.answer(answer, 2, 1)
        assertTrue(accepted.audioAccepted)
        assertEquals(1500000, accepted.videoLimits!!.maxBitRate)
        assertEquals(500000, accepted.videoLimits!!.minBitRate)
        assertEquals(30000.0 / 1001, accepted.videoLimits!!.maxFrameRate, 0.0001)
        val chosen = CastVideoQuality.select(accepted.videoLimits) { true }
        assertEquals(1280, chosen.width)
        assertEquals(720, chosen.height)
        assertEquals(29, chosen.fps)
        assertEquals(1500000, chosen.bitrate)
        assertTrue(chosen.width.toDouble() * chosen.height * chosen.fps <=
            accepted.videoLimits!!.maxPixelsPerSecond)
        val candidates = CastVideoQuality.candidates(accepted.videoLimits) { true }
        assertEquals(candidates.size, candidates.map { Triple(it.width, it.height, it.fps) }
            .distinct().size)
        assertTrue(candidates.all { it.width >= 640 && it.height >= 360 &&
            it.bitrate in 500_000..1_500_000 })
    }

    @Test fun receiverCanChooseLowerLevelVideoOffer() {
        val lower = stream.copy(ssrc = 42, width = 640, height = 360,
            fps = 30, bitRate = 900_000)
        val offer = CastStreamingProtocol.offer(3, listOf(stream, lower))
            .getJSONObject("offer").getJSONArray("supportedStreams")
        assertEquals("3.1", offer.getJSONObject(0).getString("level"))
        assertEquals("3", offer.getJSONObject(1).getString("level"))
        val answer = JSONObject("""{"type":"ANSWER","seqNum":3,"result":"ok",
            "answer":{"udpPort":5000,"sendIndexes":[1]}}""")
        val selected = CastStreamingProtocol.answer(answer, 3, listOf(0, 1), null)
        assertEquals(1, selected.videoIndex)
        assertFalse(selected.audioAccepted)
    }

    @Test fun receiverCanChooseVp8OrVp9WithoutChangingH264Offer() {
        val vp8 = stream.copy(ssrc = 42, codec = CastStreamingProtocol.VideoCodec.VP8,
            resolutions = listOf(640 to 360))
        val vp9 = stream.copy(ssrc = 43, codec = CastStreamingProtocol.VideoCodec.VP9)
        val offers = CastStreamingProtocol.offer(13, listOf(stream, vp8, vp9))
            .getJSONObject("offer").getJSONArray("supportedStreams")
        assertEquals("h264", offers.getJSONObject(0).getString("codecName"))
        assertEquals(96, offers.getJSONObject(0).getInt("rtpPayloadType"))
        assertEquals("vp8", offers.getJSONObject(1).getString("codecName"))
        assertEquals(100, offers.getJSONObject(1).getInt("rtpPayloadType"))
        assertFalse(offers.getJSONObject(1).has("profile"))
        val vp8Resolutions = offers.getJSONObject(1).getJSONArray("resolutions")
        assertEquals(1, vp8Resolutions.length())
        assertEquals(640, vp8Resolutions.getJSONObject(0).getInt("width"))
        assertEquals("vp9", offers.getJSONObject(2).getString("codecName"))
        assertEquals(103, offers.getJSONObject(2).getInt("rtpPayloadType"))
        val codecs = mapOf(0 to stream.codec, 1 to vp8.codec, 2 to vp9.codec)
        for ((index, codec) in listOf(1 to vp8.codec, 2 to vp9.codec)) {
            val answer = JSONObject("""{"type":"ANSWER","seqNum":13,"result":"ok",
                "answer":{"udpPort":5000,"sendIndexes":[$index],"constraints":{
                    "video":{"codecName":"${codec.wireName}","maxDimensions":{
                        "width":1280,"height":720,"frameRate":"30"},
                        "maxBitRate":3000000}}}}""")
            assertEquals(index, CastStreamingProtocol.answer(answer, 13, codecs, null).videoIndex)
        }
        val wrongCodec = JSONObject("""{"type":"ANSWER","seqNum":13,"result":"ok",
            "answer":{"udpPort":5000,"sendIndexes":[1],"constraints":{
                "video":{"codecName":"h264","maxDimensions":{
                    "width":1280,"height":720,"frameRate":"30"},
                    "maxBitRate":3000000}}}}""")
        assertThrows(IllegalArgumentException::class.java) {
            CastStreamingProtocol.answer(wrongCodec, 13, codecs, null)
        }
    }

    @Test fun vp8AndVp9FramesKeepTheirEncodedBytesAndPayloadTypes() {
        val encoded = byteArrayOf(0, 0, 1, 42, 7, 8)
        for (codec in listOf(CastStreamingProtocol.VideoCodec.VP8,
                CastStreamingProtocol.VideoCodec.VP9)) {
            val frame = CastStreamingProtocol.videoFrameData(codec, listOf(encoded),
                null, null, true)
            assertArrayEquals(encoded, frame)
            val video = stream.copy(codec = codec)
            val packet = CastStreamingProtocol.Packetizer(video, 1)
                .packetize(0, 90_000, true, frame).single()
            assertEquals(codec.payloadType, packet[1].toInt() and 0x7f)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(video.key, "AES"),
                IvParameterSpec(video.ivMask))
            assertArrayEquals(encoded, cipher.doFinal(packet.copyOfRange(23, packet.size)))
        }
    }

    @Test fun narrowReceiverBitrateSelectsSmallerCapture() {
        val limits = CastStreamingProtocol.VideoLimits(1920, 1080, 60.0,
            1920.0 * 1080 * 60, 800_000, 200)
        val chosen = CastVideoQuality.select(limits) { true }
        assertEquals(960, chosen.width)
        assertEquals(540, chosen.height)
        assertEquals(800_000, chosen.bitrate)
        val controller = CastRateController(800_000, maxBitrate = chosen.bitrate)
        controller.onCheckpoint(1_000, 100)
        repeat(20) { controller.onCheckpoint(5_000L + it * 4_000, 100)
            controller.update(5_001L + it * 4_000, 0) }
        assertTrue(controller.bitrate <= chosen.bitrate)
    }

    @Test fun audioOnlyOfferAndAnswerDoNotRequireVideo() {
        val audio = CastStreamingProtocol.AudioStream(
            0x55667788, ByteArray(16) { 1 }, ByteArray(16) { 2 })
        val offer = CastStreamingProtocol.audioOnlyOffer(12, audio)
        val streams = offer.getJSONObject("offer").getJSONArray("supportedStreams")
        assertEquals(1, streams.length())
        assertEquals("audio_source", streams.getJSONObject(0).getString("type"))
        assertEquals("opus", streams.getJSONObject(0).getString("codecName"))
        assertEquals(0, streams.getJSONObject(0).getInt("index"))
        val accepted = JSONObject("""{"type":"ANSWER","seqNum":12,"result":"ok",
            "answer":{"udpPort":2345,"sendIndexes":[0]}}""")
        assertEquals(2345, CastStreamingProtocol.audioOnlyAnswer(accepted, 12))
        assertThrows(IllegalArgumentException::class.java) {
            CastStreamingProtocol.audioOnlyAnswer(JSONObject("""{"type":"ANSWER","seqNum":12,
                "result":"ok","answer":{"udpPort":2345,"sendIndexes":[1]}}"""), 12)
        }
    }

    @Test fun opusFrameUsesAudioPayloadAndIndependentEncryption() {
        val audio = CastStreamingProtocol.AudioStream(
            0x55667788, ByteArray(16) { (it + 1).toByte() }, ByteArray(16))
        val opus = byteArrayOf(0x78, 1, 2, 3, 4)
        val packet = CastStreamingProtocol.Packetizer(audio, 10)
            .packetize(0, 48_000, true, opus).single()
        assertEquals(127, packet[1].toInt() and 0x7f)
        assertEquals(audio.ssrc, readInt(packet, 8))
        assertEquals(48_000, readInt(packet, 4))
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(audio.key, "AES"), IvParameterSpec(audio.ivMask))
        assertArrayEquals(opus, cipher.doFinal(packet.copyOfRange(23, packet.size)))
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
        assertEquals(200, result.targetDelayMs)
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
