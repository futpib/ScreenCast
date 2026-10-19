package io.github.ddagunts.screencast.mediapush

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal class DlnaClient(private val device: DlnaDevice) {
    suspend fun loadAndPlay(item: MediaItem, url: String) {
        call("SetAVTransportURI", mapOf("InstanceID" to "0", "CurrentURI" to url,
            "CurrentURIMetaData" to DlnaProtocol.didl(item, url)))
        call("Play", mapOf("InstanceID" to "0", "Speed" to "1"))
    }

    suspend fun play() { call("Play", mapOf("InstanceID" to "0", "Speed" to "1")) }
    suspend fun pause() { call("Pause", mapOf("InstanceID" to "0")) }
    suspend fun stop() { call("Stop", mapOf("InstanceID" to "0")) }

    suspend fun transportState(): String? = DlnaProtocol.transportState(
        call("GetTransportInfo", mapOf("InstanceID" to "0")))

    private suspend fun call(action: String, args: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val connection = URL(device.controlUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            connection.setRequestProperty("SOAPACTION", "\"${device.serviceType}#$action\"")
            val body = DlnaProtocol.envelope(device.serviceType, action, args)
                .toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val reply = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText().take(16_384) }.orEmpty()
                require(status in 200..299) { "DLNA $action failed: HTTP $status ${reply.take(180)}" }
                reply
            } finally {
                connection.disconnect()
            }
        }
}
