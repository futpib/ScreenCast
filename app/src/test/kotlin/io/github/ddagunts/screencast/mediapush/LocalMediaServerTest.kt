package io.github.ddagunts.screencast.mediapush

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalMediaServerTest {
    @Test fun `serves whole and ranged local media with receiver headers`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val uri = Uri.parse("content://test/media/video")
        val bytes = "0123456789".toByteArray()
        Shadows.shadowOf(resolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalMediaServer(resolver,
            MediaItem(uri.toString(), "test.mp4", "video/mp4", true, bytes.size.toLong()),
            "127.0.0.1", port)
        server.start()
        try {
            val full = URL(server.url).openConnection() as HttpURLConnection
            full.connectTimeout = 3000
            full.readTimeout = 3000
            assertEquals(200, full.responseCode)
            assertEquals("bytes", full.getHeaderField("Accept-Ranges"))
            assertEquals("0123456789", full.inputStream.bufferedReader().use { it.readText() })
            full.disconnect()

            val partial = URL(server.url).openConnection() as HttpURLConnection
            partial.setRequestProperty("Range", "bytes=3-6")
            partial.connectTimeout = 3000
            partial.readTimeout = 3000
            assertEquals(206, partial.responseCode)
            assertEquals("bytes 3-6/10", partial.getHeaderField("Content-Range"))
            assertEquals("4", partial.getHeaderField("Content-Length"))
            assertNull(partial.getHeaderField("Transfer-Encoding"))
            assertEquals("3456", partial.inputStream.bufferedReader().use { it.readText() })
            partial.disconnect()
        } finally {
            server.stop()
        }
    }
}
