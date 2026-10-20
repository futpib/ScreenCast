package io.github.ddagunts.screencast.nativecast

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Cast Streaming's media protocol is distinct from WebRTC SDP/RTP. The OFFER
// uses the com.google.cast.webrtc namespace, but media uses encrypted Cast RTP.
internal object CastStreamingProtocol {
    const val NAMESPACE = "urn:x-cast:com.google.cast.webrtc"
    const val ANDROID_MIRRORING_APP = "674A0243"
    const val MIRRORING_APP = "0F5096E8"
    const val AUDIO_MIRRORING_APP = "85CDB22F"
    const val VIDEO_PAYLOAD_TYPE = 96
    const val AUDIO_PAYLOAD_TYPE = 127
    const val AUDIO_SAMPLE_RATE = 48_000
    const val TARGET_DELAY_MS = 200
    const val MAX_PACKET_BYTES = 1472
    private const val MAX_PAYLOAD_BYTES = MAX_PACKET_BYTES - 23

    data class VideoStream(
        val ssrc: Int,
        val key: ByteArray,
        val ivMask: ByteArray,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitRate: Int,
        val targetDelayMs: Int = TARGET_DELAY_MS,
        val minBitRate: Int = 750_000,
    ) {
        init {
            require(key.size == 16 && ivMask.size == 16)
        }

        companion object {
            fun random(width: Int, height: Int, fps: Int, bitRate: Int): VideoStream {
                val rng = SecureRandom()
                return VideoStream(rng.nextInt(Int.MAX_VALUE - 1) + 1,
                    ByteArray(16).also(rng::nextBytes),
                    ByteArray(16).also(rng::nextBytes), width, height, fps, bitRate)
            }
        }
    }

    data class AudioStream(val ssrc: Int, val key: ByteArray, val ivMask: ByteArray) {
        init { require(key.size == 16 && ivMask.size == 16) }

        companion object {
            fun random(): AudioStream {
                val rng = SecureRandom()
                return AudioStream(rng.nextInt(Int.MAX_VALUE - 1) + 1,
                    ByteArray(16).also(rng::nextBytes),
                    ByteArray(16).also(rng::nextBytes))
            }
        }
    }

    fun offer(seqNum: Int, stream: VideoStream, audio: AudioStream? = null): JSONObject =
        offer(seqNum, listOf(stream), audio)

    fun offer(seqNum: Int, videoStreams: List<VideoStream>, audio: AudioStream? = null): JSONObject {
        require(videoStreams.isNotEmpty())
        val streams = JSONArray()
        if (audio != null) streams.put(audioOfferStream(audio))
        videoStreams.forEachIndexed { index, stream -> streams.put(JSONObject()
                .put("index", index + if (audio == null) 0 else 1)
                .put("type", "video_source")
                .put("codecName", "h264")
                .put("rtpProfile", "cast")
                .put("rtpPayloadType", VIDEO_PAYLOAD_TYPE)
                .put("ssrc", stream.ssrc.toLong() and 0xffffffffL)
                .put("targetDelay", stream.targetDelayMs)
                .put("aesKey", stream.key.toHex())
                .put("aesIvMask", stream.ivMask.toHex())
                .put("timeBase", "1/90000")
                .put("maxFrameRate", "${stream.fps}/1")
                .put("maxBitRate", stream.bitRate)
                .put("errorRecoveryMode", "intra_mb_refresh")
                .put("profile", "baseline")
                .put("level", when {
                    stream.width * stream.height >= 1920 * 1080 && stream.fps > 30 -> "4.2"
                    stream.width * stream.height >= 1920 * 1080 -> "4"
                    stream.width * stream.height >= 1280 * 720 && stream.fps > 30 -> "3.2"
                    stream.width * stream.height >= 1280 * 720 -> "3.1"
                    else -> "3"
                })
                .put("resolutions", JSONArray().apply {
                    CastVideoQuality.profiles.filter {
                        it.width <= stream.width && it.height <= stream.height
                    }.map { it.width to it.height }.distinct().forEach { (width, height) ->
                        put(JSONObject().put("width", width).put("height", height))
                    }
                })
                .put("receiverRtcpEventLog", false)) }
        return offerMessage(seqNum, streams)
    }

    fun audioOnlyOffer(seqNum: Int, audio: AudioStream): JSONObject =
        offerMessage(seqNum, JSONArray().put(audioOfferStream(audio)))

    private fun audioOfferStream(audio: AudioStream): JSONObject = JSONObject()
            .put("index", 0)
            .put("type", "audio_source")
            .put("codecName", "opus")
            .put("rtpProfile", "cast")
            .put("rtpPayloadType", AUDIO_PAYLOAD_TYPE)
            .put("ssrc", audio.ssrc.toLong() and 0xffffffffL)
            .put("targetDelay", TARGET_DELAY_MS)
            .put("aesKey", audio.key.toHex())
            .put("aesIvMask", audio.ivMask.toHex())
            .put("timeBase", "1/$AUDIO_SAMPLE_RATE")
            .put("channels", 2)
            .put("bitRate", 128_000)
            .put("receiverRtcpEventLog", false)

    private fun offerMessage(seqNum: Int, streams: JSONArray): JSONObject = JSONObject()
        .put("type", "OFFER")
        .put("seqNum", seqNum)
        .put("offer", JSONObject()
            .put("castMode", "mirroring")
            .put("supportedStreams", streams))

    data class VideoLimits(
        val maxWidth: Int,
        val maxHeight: Int,
        val maxFrameRate: Double,
        val maxPixelsPerSecond: Double,
        val maxBitRate: Int,
        val maxDelayMs: Int,
        val minWidth: Int = 0,
        val minHeight: Int = 0,
        val minBitRate: Int = 300_000,
    )

    data class Answer(val port: Int, val audioAccepted: Boolean,
                      val videoLimits: VideoLimits? = null,
                      val videoIndex: Int = -1)

    fun answer(message: JSONObject, expectedSeq: Int, videoIndex: Int): Answer {
        val selected = answer(message, expectedSeq, listOf(videoIndex),
            if (videoIndex == 1) 0 else null)
        return selected.copy(videoIndex = -1)
    }

    fun answer(message: JSONObject, expectedSeq: Int, videoIndexes: List<Int>,
               audioIndex: Int?): Answer {
        val (answer, indexes) = parseAnswer(message, expectedSeq)
        val selectedVideos = videoIndexes.filter { index ->
            (0 until indexes.length()).any { indexes.optInt(it, -1) == index }
        }
        require(selectedVideos.size == 1) {
            "receiver did not accept H.264 video"
        }
        val videoIndex = selectedVideos.single()
        val video = answer.optJSONObject("constraints")?.optJSONObject("video")
        val dimensions = video?.optJSONObject("maxDimensions")
        val minimum = video?.optJSONObject("minResolution")
        val limits = if (video != null) {
            val frameRate = dimensions?.optString("frameRate", "60")?.let(::parseFrameRate) ?: 60.0
            VideoLimits(
                dimensions?.optInt("width", Int.MAX_VALUE) ?: Int.MAX_VALUE,
                dimensions?.optInt("height", Int.MAX_VALUE) ?: Int.MAX_VALUE,
                frameRate,
                video.optDouble("maxPixelsPerSecond", Double.POSITIVE_INFINITY),
                video.optInt("maxBitRate", Int.MAX_VALUE),
                video.optInt("maxDelay", 1200),
                minimum?.optInt("width", 0) ?: 0,
                minimum?.optInt("height", 0) ?: 0,
                video.optInt("minBitRate", 300_000),
            ).also {
                require(it.maxWidth > 0 && it.maxHeight > 0 && it.maxFrameRate > 0 &&
                    it.maxPixelsPerSecond > 0 && it.maxBitRate > 0 && it.maxDelayMs > 0 &&
                    it.minWidth >= 0 && it.minHeight >= 0 &&
                    it.minWidth <= it.maxWidth && it.minHeight <= it.maxHeight &&
                    it.minBitRate > 0 && it.minBitRate <= it.maxBitRate) {
                    "invalid Cast video constraints"
                }
                require(video.optString("codecName", "h264") == "h264") {
                    "receiver constraints do not allow H.264"
                }
            }
        } else null
        val audioAccepted = audioIndex != null &&
            (0 until indexes.length()).any { indexes.optInt(it, -1) == audioIndex } &&
            audioFits(answer.optJSONObject("constraints")?.optJSONObject("audio"))
        return Answer(answer.getInt("udpPort"), audioAccepted, limits, videoIndex)
    }

    private fun parseFrameRate(value: String): Double {
        val parts = value.split('/')
        require(parts.size in 1..2) { "invalid receiver frame rate" }
        val numerator = parts[0].toDoubleOrNull() ?: error("invalid receiver frame rate")
        val denominator = if (parts.size == 2) parts[1].toDoubleOrNull() ?: 0.0 else 1.0
        require(denominator > 0) { "invalid receiver frame rate" }
        return numerator / denominator
    }

    fun audioOnlyAnswer(message: JSONObject, expectedSeq: Int): Int {
        val (answer, indexes) = parseAnswer(message, expectedSeq)
        require((0 until indexes.length()).any { indexes.optInt(it, -1) == 0 }) {
            "receiver did not accept Opus audio"
        }
        require(audioFits(answer.optJSONObject("constraints")?.optJSONObject("audio"))) {
            "receiver constraints do not allow 48 kHz stereo Opus"
        }
        return answer.getInt("udpPort")
    }

    private fun audioFits(audio: JSONObject?): Boolean = audio == null ||
        (audio.optString("codecName", "opus") == "opus" &&
            audio.optInt("maxSampleRate", AUDIO_SAMPLE_RATE) >= AUDIO_SAMPLE_RATE &&
            audio.optInt("maxChannels", 2) >= 2 &&
            audio.optInt("maxBitRate", 320_000) >= 128_000 &&
            audio.optInt("minBitRate", 32_000) <= 128_000 &&
            audio.optInt("maxDelay", 1200) >= TARGET_DELAY_MS)

    private fun parseAnswer(message: JSONObject, expectedSeq: Int): Pair<JSONObject, JSONArray> {
        require(message.optString("type") == "ANSWER" && message.optInt("seqNum", -1) == expectedSeq) {
            "unexpected Cast Streaming answer"
        }
        require(message.optString("result") == "ok") {
            "receiver rejected mirroring: ${message.optJSONObject("error") ?: message.optString("error")}"
        }
        val answer = message.getJSONObject("answer")
        val indexes = answer.getJSONArray("sendIndexes")
        val port = answer.getInt("udpPort")
        require(port in 1..65535) { "invalid receiver UDP port" }
        return answer to indexes
    }

    fun annexB(nals: List<ByteArray>, sps: ByteArray?, pps: ByteArray?, keyFrame: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        if (keyFrame && sps != null && pps != null) {
            output.write(byteArrayOf(0, 0, 0, 1)); output.write(sps)
            output.write(byteArrayOf(0, 0, 0, 1)); output.write(pps)
        }
        for (nal in nals) {
            if (nal.isEmpty()) continue
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(nal)
        }
        return output.toByteArray()
    }

    class Packetizer(
        private val ssrc: Int,
        private val key: ByteArray,
        private val ivMask: ByteArray,
        private val payloadType: Int,
        private val targetDelayMs: Int,
        sequenceStart: Int = SecureRandom().nextInt(),
    ) {
        constructor(stream: VideoStream, sequenceStart: Int = SecureRandom().nextInt()) :
            this(stream.ssrc, stream.key, stream.ivMask, VIDEO_PAYLOAD_TYPE,
                stream.targetDelayMs, sequenceStart)
        constructor(stream: AudioStream, sequenceStart: Int = SecureRandom().nextInt()) :
            this(stream.ssrc, stream.key, stream.ivMask, AUDIO_PAYLOAD_TYPE,
                TARGET_DELAY_MS, sequenceStart)
        private var sequence = sequenceStart and 0xffff

        fun packetize(frameId: Long, rtpTimestamp: Int, keyFrame: Boolean, data: ByteArray): List<ByteArray> {
            require(data.isNotEmpty())
            val count = (data.size + MAX_PAYLOAD_BYTES - 1) / MAX_PAYLOAD_BYTES
            require(count <= 65536) { "encoded frame exceeds Cast RTP packet limit" }
            val iv = ivMask.clone()
            val id = frameId.toInt()
            for (i in 0..3) iv[8 + i] = (iv[8 + i].toInt() xor (id ushr (24 - i * 8))).toByte()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(data)
            return List(count) { packetId ->
                val first = packetId == 0
                val last = packetId == count - 1
                val extension = first && frameId == 0L
                val start = packetId * MAX_PAYLOAD_BYTES
                val end = minOf(start + MAX_PAYLOAD_BYTES, encrypted.size)
                ByteBuffer.allocate(19 + if (extension) 4 + end - start else end - start)
                    .order(ByteOrder.BIG_ENDIAN)
                    .put(0x80.toByte())
                    .put(((if (last) 0x80 else 0) or payloadType).toByte())
                    .putShort(sequence++.toShort())
                    .putInt(rtpTimestamp)
                    .putInt(ssrc)
                    .put(((if (keyFrame) 0x80 else 0) or 0x40 or if (extension) 1 else 0).toByte())
                    .put(frameId.toByte())
                    .putShort(packetId.toShort())
                    .putShort((count - 1).toShort())
                    .put((if (keyFrame) frameId else maxOf(0, frameId - 1)).toByte())
                    .apply {
                        if (extension) {
                            putShort(((1 shl 10) or 2).toShort())
                            putShort(targetDelayMs.toShort())
                        }
                    }
                    .put(encrypted, start, end - start)
                    .array()
            }
        }
    }

    fun senderReport(ssrc: Int, ntp: Long, rtp: Int, packets: Int, octets: Int): ByteArray =
        ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
            .put(0x80.toByte()).put(200.toByte()).putShort(6)
            .putInt(ssrc).putLong(ntp).putInt(rtp).putInt(packets).putInt(octets).array()

    fun ntpNow(): Long {
        return ntpAtMs(System.currentTimeMillis())
    }

    fun ntpForPts(ptsUs: Long): Long {
        val capturedAgoUs = System.nanoTime() / 1000 - ptsUs
        return ntpAtMs(System.currentTimeMillis() - capturedAgoUs / 1000)
    }

    private fun ntpAtMs(ms: Long): Long {
        return ((ms / 1000 + 2_208_988_800L) shl 32) or
            (((ms % 1000) shl 32) / 1000)
    }

    data class Nack(val frameId: Long, val packetId: Int)
    data class Feedback(val checkpoint: Long?, val nacks: List<Nack>, val pictureLoss: Boolean,
                        val targetDelayMs: Int?)

    fun feedback(data: ByteArray, senderSsrc: Int, checkpointHint: Long): Feedback {
        var offset = 0
        var checkpoint: Long? = null
        val nacks = ArrayList<Nack>()
        var pictureLoss = false
        var targetDelayMs: Int? = null
        while (offset + 4 <= data.size) {
            val header = ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN)
            val flags = header.get().toInt() and 0xff
            val type = header.get().toInt() and 0xff
            val size = (header.short.toInt() and 0xffff) * 4 + 4
            if (flags ushr 6 != 2 || size < 4 || offset + size > data.size) break
            val subtype = flags and 31
            if (type == 206 && size >= 12 && readInt(data, offset + 8) == senderSsrc) {
                if (subtype == 1) pictureLoss = true
                if (subtype == 15 && size >= 20 && readInt(data, offset + 12) == 0x43415354) {
                    val cp = expandFrameId(data[offset + 16].toInt() and 0xff, checkpointHint)
                    checkpoint = maxOf(checkpoint ?: Long.MIN_VALUE, cp)
                    targetDelayMs = ((data[offset + 18].toInt() and 0xff) shl 8) or
                        (data[offset + 19].toInt() and 0xff)
                    val losses = data[offset + 17].toInt() and 0xff
                    for (i in 0 until minOf(losses, (size - 20) / 4)) {
                        val at = offset + 20 + 4 * i
                        val frame = expandFrameId(data[at].toInt() and 0xff, cp + 1)
                        val packet = ((data[at + 1].toInt() and 0xff) shl 8) or (data[at + 2].toInt() and 0xff)
                        nacks += Nack(frame, packet)
                        if (packet != 0xffff) for (bit in 0..7) {
                            if ((data[at + 3].toInt() and (1 shl bit)) != 0) nacks += Nack(frame, packet + bit + 1)
                        }
                    }
                }
            }
            offset += size
        }
        return Feedback(checkpoint, nacks, pictureLoss, targetDelayMs)
    }

    private fun expandFrameId(low: Int, reference: Long): Long {
        val delta = (low - reference.toInt()) and 0xff
        return reference + if (delta >= 128) delta - 256 else delta
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
