package io.github.ddagunts.screencast.mediapush

import android.content.ContentResolver
import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import java.io.InputStream
import java.security.SecureRandom

internal class LocalMediaServer(
    private val resolver: ContentResolver,
    private val item: MediaItem,
    private val host: String,
    private val port: Int,
) {
    private val token = ByteArray(16).also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val url: String = "http://$host:$port/media/$token"
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        require(item.local && item.size > 0)
        engine = embeddedServer(CIO, host = host, port = port) {
            install(AutoHeadResponse)
            routing {
                get("/media/{token}") {
                    if (call.parameters["token"] != token) {
                        call.respondText("not found", status = HttpStatusCode.NotFound)
                        return@get
                    }
                    val rangeHeader = call.request.headers[HttpHeaders.Range]
                    val range = MediaByteRange.parse(rangeHeader, item.size)
                    if (range == null) {
                        call.response.headers.append(HttpHeaders.ContentRange, "bytes */${item.size}")
                        call.respondText("range not satisfiable", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                        return@get
                    }
                    call.response.headers.append(HttpHeaders.AcceptRanges, "bytes")
                    call.response.headers.append("transferMode.dlna.org", "Streaming")
                    if (rangeHeader != null) {
                        call.response.headers.append(HttpHeaders.ContentRange,
                            "bytes ${range.first}-${range.last}/${item.size}")
                    }
                    call.respondBytesWriter(
                        contentType = ContentType.parse(item.mimeType),
                        status = if (rangeHeader == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                        contentLength = range.length,
                    ) {
                        resolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                            skipTo(input, range.first)
                            val buffer = ByteArray(64 * 1024)
                            var remaining = range.length
                            while (remaining > 0) {
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) break
                                writeFully(buffer, 0, count)
                                remaining -= count
                            }
                        } ?: error("selected media is no longer available")
                    }
                }
            }
        }.also { it.start(wait = false) }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun skipTo(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (input.read() >= 0) remaining--
            else error("selected media ended before requested byte range")
        }
    }
}
