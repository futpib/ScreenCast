package io.github.ddagunts.screencast.cast

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

// The TLS certificate is ephemeral and self-signed. The Cast device-auth
// response binds that exact certificate to a device certificate signed by a
// Google Cast root; no media keys may be sent before this check succeeds.
internal object CastDeviceAuth {
    const val NAMESPACE = "urn:x-cast:com.google.cast.tp.deviceauth"

    fun challenge(nonce: ByteArray): CastMessage {
        require(nonce.size == 16)
        val body = Proto.writer().apply {
            bytes(1, Proto.writer().apply {
                writeNumber(1, 1) // RSASSA-PKCS1-v1_5
                bytes(2, nonce)
                writeNumber(3, 1) // SHA-256
            }.toByteArray())
        }.toByteArray()
        return CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, NAMESPACE, "", body)
    }

    fun freshNonce(): ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)

    fun verify(context: Context, message: CastMessage, nonce: ByteArray,
               tlsCertificate: X509Certificate) {
        val factory = CertificateFactory.getInstance("X.509")
        val roots = listOf("cast/cast_root_ca.der", "cast/eureka_root_ca.der").map { name ->
            context.assets.open(name).use {
                factory.generateCertificate(it) as X509Certificate
            }
        }
        verify(message, nonce, tlsCertificate, roots, Date())
    }

    internal fun verify(message: CastMessage, nonce: ByteArray,
                        tlsCertificate: X509Certificate, roots: List<X509Certificate>, now: Date) {
        require(message.namespace == NAMESPACE && message.payloadBinary != null) {
            "Cast receiver did not provide device authentication"
        }
        val envelope = Proto.parse(message.payloadBinary)
        require(envelope.bytes(3) == null) { "Cast receiver rejected authentication" }
        val response = Proto.parse(envelope.bytes(2) ?: error("missing Cast auth response"))
        val signatureBytes = response.bytes(1) ?: error("missing Cast auth signature")
        val certBytes = response.bytes(2) ?: error("missing Cast device certificate")
        val echoedNonce = response.bytes(5) ?: error("missing Cast auth nonce")
        require(echoedNonce.contentEquals(nonce)) { "Cast auth nonce mismatch" }
        require(response.number(4, 1) == 1) { "unsupported Cast auth signature algorithm" }
        val signatureAlgorithm = when (response.number(6, 0)) {
            0 -> "SHA1withRSA" // Legacy Cast receivers may ignore the SHA-256 request.
            1 -> "SHA256withRSA"
            else -> error("unsupported Cast auth hash algorithm")
        }
        val factory = CertificateFactory.getInstance("X.509")
        fun certificate(bytes: ByteArray) =
            factory.generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        require(roots.isNotEmpty())
        val chain = (listOf(certBytes) + response.allBytes(3)).map(::certificate)
            .filterNot { candidate -> roots.any { it.encoded.contentEquals(candidate.encoded) } }
        require(chain.isNotEmpty()) { "Cast device certificate is a root" }
        val leaf = chain.first()
        require(leaf.basicConstraints < 0) { "Cast device certificate is a CA" }
        require(leaf.keyUsage?.getOrNull(0) != false) { "Cast device certificate cannot sign" }
        val path = factory.generateCertPath(chain)
        val params = PKIXParameters(roots.map { TrustAnchor(it, null) }.toSet()).apply {
            isRevocationEnabled = false
            date = now
        }
        CertPathValidator.getInstance("PKIX").validate(path, params)

        tlsCertificate.checkValidity(now)
        require(tlsCertificate.notAfter.time - now.time <= 4 * 24 * 60 * 60 * 1000L) {
            "Cast TLS certificate has excessive lifetime"
        }
        val verifier = Signature.getInstance(signatureAlgorithm)
        verifier.initVerify(leaf.publicKey)
        verifier.update(nonce)
        verifier.update(tlsCertificate.encoded)
        require(verifier.verify(signatureBytes)) { "Cast device signature did not match TLS certificate" }
    }
}

private class Proto private constructor(private val fields: Map<Int, List<Any>>,
                                        private val output: ByteArrayOutputStream?) {
    fun bytes(field: Int): ByteArray? = fields[field]?.firstOrNull() as? ByteArray
    fun allBytes(field: Int): List<ByteArray> = fields[field].orEmpty().map { it as ByteArray }
    fun number(field: Int, default: Int): Int = (fields[field]?.firstOrNull() as? Int) ?: default
    fun writeNumber(field: Int, value: Int) { tag(field, 0); numberRaw(value) }
    fun bytes(field: Int, value: ByteArray) { tag(field, 2); numberRaw(value.size); output!!.write(value) }
    fun toByteArray(): ByteArray = output!!.toByteArray()
    private fun tag(field: Int, wire: Int) = numberRaw((field shl 3) or wire)
    private fun numberRaw(value: Int) { var n = value
        while (n and 0x7f.inv() != 0) { output!!.write((n and 0x7f) or 0x80); n = n ushr 7 }
        output!!.write(n)
    }
    companion object {
        fun writer() = Proto(emptyMap(), ByteArrayOutputStream())
        fun parse(bytes: ByteArray): Proto {
            val fields = mutableMapOf<Int, MutableList<Any>>()
            var at = 0
            fun varint(): Int {
                var value = 0; var shift = 0
                while (shift <= 28) {
                    require(at < bytes.size) { "truncated Cast auth protobuf" }
                    val octet = bytes[at++].toInt() and 0xff
                    value = value or ((octet and 0x7f) shl shift)
                    if (octet and 0x80 == 0) return value
                    shift += 7
                }
                error("oversized Cast auth protobuf integer")
            }
            while (at < bytes.size) {
                val tag = varint(); val field = tag ushr 3
                require(field != 0) { "invalid Cast auth protobuf field" }
                val value: Any = when (tag and 7) {
                    0 -> varint()
                    2 -> { val length = varint()
                        require(length >= 0 && length <= bytes.size - at) { "invalid Cast auth protobuf length" }
                        bytes.copyOfRange(at, at + length).also { at += length }
                    }
                    else -> error("unsupported Cast auth protobuf wire type")
                }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return Proto(fields, null)
        }
    }
}
