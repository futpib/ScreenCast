package io.github.ddagunts.screencast.cast

import java.io.ByteArrayOutputStream

// Hand-rolled protobuf codec for the Cast V2 envelope. Field numbers + wire types
// mirror cast_channel.proto from the Cast SDK; the binary payload is needed
// for the device authentication challenge and response.
// This avoids pulling in protobuf-kotlin
// (~1 MB APK cost) for a schema that hasn't changed since 2014.
data class CastMessage(
    val sourceId: String,
    val destinationId: String,
    val namespace: String,
    val payloadUtf8: String,
    val payloadBinary: ByteArray? = null,
) {
    fun encode(): ByteArray {
        val buf = ByteArrayOutputStream()
        buf.writeVarint(1 shl 3)          // field 1 protocol_version, varint
        buf.writeVarint(0)                 // CASTV2_1_0
        buf.writeString(2, sourceId)
        buf.writeString(3, destinationId)
        buf.writeString(4, namespace)
        buf.writeVarint(5 shl 3)          // field 5 payload_type, varint
        buf.writeVarint(if (payloadBinary == null) 0 else 1)
        if (payloadBinary == null) buf.writeString(6, payloadUtf8)
        else buf.writeBytes(7, payloadBinary)
        return buf.toByteArray()
    }

    companion object {
        fun decode(bytes: ByteArray): CastMessage {
            var offset = 0
            var sourceId = ""; var destinationId = ""; var namespace = ""; var payload = ""
            var payloadType = 0; var binary: ByteArray? = null
            while (offset < bytes.size) {
                val (tag, nextA) = readVarint(bytes, offset); offset = nextA
                val field = (tag ushr 3).toInt()
                val wire = (tag and 7).toInt()
                // Forwards-compat: skip fields we don't recognise instead of failing
                // the whole frame. A malformed frame from a hostile peer also has
                // to land here — every branch bounds-checks before advancing.
                when (wire) {
                    0 -> { val (value, nextB) = readVarint(bytes, offset); offset = nextB
                        if (field == 5) payloadType = value.toInt() }
                    1 -> { require(offset + 8 <= bytes.size) { "fixed64 past end" }; offset += 8 }
                    5 -> { require(offset + 4 <= bytes.size) { "fixed32 past end" }; offset += 4 }
                    2 -> {
                        val (len, nextB) = readVarint(bytes, offset); offset = nextB
                        require(len >= 0 && len <= bytes.size - offset) { "len-delimited past end ($len)" }
                        val value = String(bytes, offset, len.toInt(), Charsets.UTF_8)
                        when (field) {
                            2 -> sourceId = value
                            3 -> destinationId = value
                            4 -> namespace = value
                            6 -> payload = value
                            7 -> binary = bytes.copyOfRange(offset, offset + len.toInt())
                        }
                        offset += len.toInt()
                    }
                    3, 4 -> error("deprecated group wire type $wire at field $field")
                    else -> error("unknown wire type $wire at field $field")
                }
            }
            require(payloadType == 0 || payloadType == 1) { "unknown Cast payload type" }
            require(payloadType != 1 || binary != null) { "missing binary Cast payload" }
            return CastMessage(sourceId, destinationId, namespace, payload,
                if (payloadType == 1) binary else null)
        }
    }
}

private fun ByteArrayOutputStream.writeString(field: Int, s: String) {
    writeBytes(field, s.toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeBytes(field: Int, b: ByteArray) {
    writeVarint((field shl 3) or 2)
    writeVarint(b.size.toLong())
    write(b)
}

private fun ByteArrayOutputStream.writeVarint(value: Int) = writeVarint(value.toLong())
private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var v = value
    while (v and 0x7FL.inv() != 0L) {
        write(((v and 0x7FL) or 0x80L).toInt())
        v = v ushr 7
    }
    write(v.toInt() and 0x7F)
}

private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int> {
    var result = 0L; var shift = 0; var i = start
    // 64-bit varint is at most 10 bytes; anything longer is malformed input.
    val limit = minOf(bytes.size, start + 10)
    while (i < limit) {
        val b = bytes[i].toInt() and 0xFF; i++
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result to i
        shift += 7
    }
    error("malformed varint at offset $start")
}
