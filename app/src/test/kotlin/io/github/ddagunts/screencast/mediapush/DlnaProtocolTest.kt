package io.github.ddagunts.screencast.mediapush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DlnaProtocolTest {
    @Test fun `extracts location from SSDP response`() {
        assertEquals("http://192.0.2.1:1234/desc.xml", DlnaProtocol.location(
            "HTTP/1.0 200 OK\r\nLOCATION: http://192.0.2.1:1234/desc.xml\r\n\r\n"))
    }

    @Test fun `parses renderer and resolves AVTransport URL`() {
        val xml = """<root><device><friendlyName>Living Room</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/upnp/control/avtransport</controlURL></service>
            </serviceList></device></root>"""
        val device = DlnaProtocol.parseDescription(xml, "http://192.0.2.1:1234/desc.xml")!!
        assertEquals("Living Room", device.name)
        assertEquals("http://192.0.2.1:1234/upnp/control/avtransport", device.controlUrl)
    }

    @Test fun `metadata is XML escaped within SOAP`() {
        val item = MediaItem("content://test", "A & B <test>", "video/mp4", true, 123)
        val didl = DlnaProtocol.didl(item, "http://192.0.2.1/video?a=1&b=2")
        assertTrue(didl.contains("A &amp; B &lt;test&gt;"))
        assertTrue(didl.contains("size=\"123\""))
        val soap = DlnaProtocol.envelope("urn:schemas-upnp-org:service:AVTransport:1",
            "SetAVTransportURI", mapOf("CurrentURIMetaData" to didl))
        assertTrue(soap.contains("&lt;DIDL-Lite"))
        assertTrue(soap.contains("&amp;amp;"))
    }

    @Test fun `client sets media URI then starts playback`() = runBlocking {
        val actions = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/control") { exchange ->
            actions += exchange.requestHeaders.getFirst("SOAPACTION")
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            if (actions.size == 1) assertTrue(body.contains("http://example.test/movie.mp4"))
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val device = DlnaDevice("Fixture", "127.0.0.1",
                "http://127.0.0.1:${server.address.port}/control",
                "urn:schemas-upnp-org:service:AVTransport:1")
            DlnaClient(device).loadAndPlay(
                MediaItem.fromUrl("http://example.test/movie.mp4"),
                "http://example.test/movie.mp4")
            assertEquals(listOf(
                "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
                "\"urn:schemas-upnp-org:service:AVTransport:1#Play\""), actions)
        } finally {
            server.stop(0)
        }
    }
}
