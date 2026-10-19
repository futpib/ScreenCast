package io.github.ddagunts.screencast.mediapush

import android.content.Context
import io.github.ddagunts.screencast.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL

internal class DlnaDiscovery(private val context: Context) {
    suspend fun scan(): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val ip = NetworkUtils.getWifiIpAddress(context) ?: return@withContext emptyList()
        val locations = linkedSetOf<String>()
        DatagramSocket(null).use { socket ->
            socket.bind(InetSocketAddress(ip, 0))
            socket.soTimeout = 500
            val request = DlnaProtocol.searchRequest()
            socket.send(DatagramPacket(request, request.size,
                InetAddress.getByName("239.255.255.250"), 1900))
            val deadline = System.currentTimeMillis() + 3000
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val reply = DatagramPacket(buffer, buffer.size)
                try { socket.receive(reply) } catch (_: java.net.SocketTimeoutException) { continue }
                DlnaProtocol.location(String(reply.data, reply.offset, reply.length, Charsets.UTF_8))
                    ?.let(locations::add)
            }
        }
        locations.mapNotNull { location -> runCatching { resolve(location) }.getOrNull() }
            .distinctBy { it.controlUrl }
            .sortedBy { it.name }
    }

    suspend fun resolve(location: String): DlnaDevice? = withContext(Dispatchers.IO) {
        require(location.startsWith("http://", true))
        val connection = URL(location).openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        try {
            require(connection.responseCode == 200) { "DLNA description returned ${connection.responseCode}" }
            val xml = connection.inputStream.bufferedReader().use { it.readText() }
            require(xml.length < 256_000) { "DLNA description too large" }
            DlnaProtocol.parseDescription(xml, location)
        } finally {
            connection.disconnect()
        }
    }
}
