package io.github.ddagunts.screencast.cast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastDeviceAuthTest {
    private val nonce = "0123456789abcdef".toByteArray()
    private val whenValid = Date.from(Instant.parse("2026-10-21T00:00:00Z"))

    private fun fixture(name: String): ByteArray =
        javaClass.getResourceAsStream("/castauth/$name")!!.use { it.readBytes() }

    private fun cert(name: String): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(fixture(name))) as X509Certificate

    @Test fun bundledRootsMatchPublishedChromiumCertificates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = mapOf(
            "cast/cast_root_ca.der" to "809af14700b3fe2611ad597eb1584d6354313b64ccdb3390f097fa3e5826d6ca",
            "cast/eureka_root_ca.der" to "caf6d1e37b532203e01a76bf07187bb731ccd38801565ab2211a2c0ab7f3bc46",
        )
        names.forEach { (name, expected) ->
            val data = context.assets.open(name).use { it.readBytes() }
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(data)) as X509Certificate
            assertEquals(expected, java.security.MessageDigest.getInstance("SHA-256")
                .digest(certificate.encoded).joinToString("") { "%02x".format(it) })
        }
    }

    private fun bytes(field: Int, content: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write((field shl 3) or 2)
        var size = content.size
        while (size >= 128) { write((size and 127) or 128); size = size ushr 7 }
        write(size)
        write(content)
    }.toByteArray()

    private fun response(signature: ByteArray = fixture("signature.bin"),
                         echoedNonce: ByteArray = nonce): CastMessage {
        val auth = bytes(1, signature) + bytes(2, fixture("device.der")) +
            bytes(5, echoedNonce) + byteArrayOf((6 shl 3).toByte(), 1)
        return CastMessage(DEFAULT_RECEIVER_ID, SENDER_ID, CastDeviceAuth.NAMESPACE, "",
            bytes(2, auth))
    }

    @Test fun signedReceiverBindsNonceAndTlsCertificate() {
        val challenge = CastMessage.decode(CastDeviceAuth.challenge(nonce).encode())
        assertArrayEquals(CastDeviceAuth.challenge(nonce).payloadBinary, challenge.payloadBinary)
        val wireResponse = CastMessage.decode(response().encode())
        CastDeviceAuth.verify(wireResponse, nonce, cert("tls.der"),
            listOf(cert("root.der")), whenValid)
    }

    @Test fun replayUntrustedAndTamperedRepliesAreRejected() {
        val valid = response()
        assertThrows(IllegalArgumentException::class.java) {
            CastDeviceAuth.verify(response(echoedNonce = ByteArray(16)), nonce,
                cert("tls.der"), listOf(cert("root.der")), whenValid)
        }
        assertThrows(Exception::class.java) {
            CastDeviceAuth.verify(valid, nonce, cert("tls.der"),
                listOf(cert("tls.der")), whenValid)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CastDeviceAuth.verify(response(signature = ByteArray(256)), nonce,
                cert("tls.der"), listOf(cert("root.der")), whenValid)
        }
    }
}
