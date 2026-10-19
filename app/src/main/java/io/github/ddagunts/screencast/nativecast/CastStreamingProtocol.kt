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
    const val VIDEO_PAYLOAD_TYPE = 96
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
    ) {
        init {
            require(key.size == 16 && ivMask.size == 16)
        }

        companion object {
            fun random(width: Int, height: Int, fps: Int, bitRate: Int): VideoStream {
                val rng = SecureRandom()
                return VideoStream(rng.nextInt(), ByteArray(16).also(rng::nextBytes),
                    ByteArray(16).also(rng::nextBytes), width, height, fps, bitRate)
            }
        }
    }

    fun offer(seqNum: Int, stream: VideoStream): JSONObject = JSONObject()
        .put("type", "OFFER")
        .put("seqNum", seqNum)
        .put("offer", JSONObject()
            .put("castMode", "mirroring")
            .put("supportedStreams", JSONArray().put(JSONObject()
                .put("index", 0)
                .put("type", "video_source")
                .put("codecName", "h264")
                .put("rtpProfile", "cast")
                .put("rtpPayloadType", VIDEO_PAYLOAD_TYPE)
                .put("ssrc", stream.ssrc.toLong() and 0xffffffffL)
                .put("targetDelay", TARGET_DELAY_MS)
                .put("aesKey", stream.key.toHex())
                .put("aesIvMask", stream.ivMask.toHex())
                .put("timeBase", "1/90000")
                .put("maxFrameRate", "${stream.fps}/1")
                .put("maxBitRate", stream.bitRate)
                .put("profile", "baseline")
                .put("level", when {
                    stream.width * stream.height >= 1920 * 1080 -> "4"
                    stream.width * stream.height >= 1280 * 720 -> "3.1"
                    else -> "3"
                })
                .put("resolutions", JSONArray().put(JSONObject()
                    .put("width", stream.width).put("height", stream.height)))
                .put("receiverRtcpEventLog", false))))

    fun answerPort(message: JSONObject, expectedSeq: Int): Int {
        require(message.optString("type") == "ANSWER" && message.optInt("seqNum", -1) == expectedSeq) {
            "unexpected Cast Streaming answer"
        }
        require(message.optString("result") == "ok") {
            "receiver rejected mirroring: ${message.optJSONObject("error") ?: message.optString("error")}"
        }
        val answer = message.getJSONObject("answer")
        val indexes = answer.getJSONArray("sendIndexes")
        require((0 until indexes.length()).any { indexes.optInt(it, -1) == 0 }) {
            "receiver did not accept H.264 video"
        }
        val port = answer.getInt("udpPort")
        require(port in 1..65535) { "invalid receiver UDP port" }
        return port
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

    class Packetizer(private val stream: VideoStream, sequenceStart: Int = SecureRandom().nextInt()) {
        private var sequence = sequenceStart and 0xffff

        fun packetize(frameId: Long, rtpTimestamp: Int, keyFrame: Boolean, data: ByteArray): List<ByteArray> {
            require(data.isNotEmpty())
            val count = (data.size + MAX_PAYLOAD_BYTES - 1) / MAX_PAYLOAD_BYTES
            require(count <= 65536) { "encoded frame exceeds Cast RTP packet limit" }
            val iv = stream.ivMask.clone()
            val id = frameId.toInt()
            for (i in 0..3) iv[8 + i] = (iv[8 + i].toInt() xor (id ushr (24 - i * 8))).toByte()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(stream.key, "AES"), IvParameterSpec(iv))
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
                    .put(((if (last) 0x80 else 0) or VIDEO_PAYLOAD_TYPE).toByte())
                    .putShort(sequence++.toShort())
                    .putInt(rtpTimestamp)
                    .putInt(stream.ssrc)
                    .put(((if (keyFrame) 0x80 else 0) or 0x40 or if (extension) 1 else 0).toByte())
                    .put(frameId.toByte())
                    .putShort(packetId.toShort())
                    .putShort((count - 1).toShort())
                    .put((if (keyFrame) frameId else maxOf(0, frameId - 1)).toByte())
                    .apply {
                        if (extension) {
                            putShort(((1 shl 10) or 2).toShort())
                            putShort(TARGET_DELAY_MS.toShort())
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
        val ms = System.currentTimeMillis()
        return ((ms / 1000 + 2_208_988_800L) shl 32) or
            (((ms % 1000) shl 32) / 1000)
    }

    data class Nack(val frameId: Long, val packetId: Int)
    data class Feedback(val checkpoint: Long?, val nacks: List<Nack>, val pictureLoss: Boolean)

    fun feedback(data: ByteArray, senderSsrc: Int, checkpointHint: Long): Feedback {
        var offset = 0
        var checkpoint: Long? = null
        val nacks = ArrayList<Nack>()
        var pictureLoss = false
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
        return Feedback(checkpoint, nacks, pictureLoss)
    }

    private fun expandFrameId(low: Int, reference: Long): Long {
        val delta = (low - reference.toInt()) and 0xff
        return reference + if (delta >= 128) delta - 256 else delta
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
