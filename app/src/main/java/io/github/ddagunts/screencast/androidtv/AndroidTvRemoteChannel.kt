package io.github.ddagunts.screencast.androidtv

import io.github.ddagunts.screencast.util.logD
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

// A single reader dispatches handshake and status messages in wire order.
// There is no lossy SharedFlow between opening TLS and attaching a subscriber.
class AndroidTvRemoteChannel(
    private val host: String,
    private val port: Int = 6466,
    private val clientMaterial: AndroidTvCertFactory.Material,
    private val expectedServerCertSha256: String,
) {
    private val lifecycleLock = Any()
    @Volatile private var socket: SSLSocket? = null
    private var closed = false
    private var ready = false
    private val writeLock = Mutex()

    suspend fun run(onMessage: suspend (RemoteMessage) -> Unit) = withContext(Dispatchers.IO) {
        val factory = AndroidTvSocketFactory(clientMaterial, expectedServerCertSha256)
        try {
            val s = factory.connect(host, port, onSocket = { opened ->
                synchronized(lifecycleLock) {
                    check(!closed) { "Remote connection cancelled" }
                    socket = opened
                }
            })
            s.soTimeout = 8_000
            val handshakeStarted = System.nanoTime()
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = AndroidTvFraming.readFrame(s.inputStream)
                if (!ready && System.nanoTime() - handshakeStarted > 8_000_000_000L) {
                    throw SocketTimeoutException("TV remote handshake timed out")
                }
                val messages = try {
                    RemoteMessage.decodeAll(frame)
                } catch (e: IllegalArgumentException) {
                    logW("Ignoring invalid ATV message: ${e.message}")
                    continue
                } catch (e: IllegalStateException) {
                    logW("Ignoring invalid ATV message: ${e.message}")
                    continue
                }
                for (msg in messages) {
                    currentCoroutineContext().ensureActive()
                    when (msg) {
                        is RemoteMessage.PingRequest -> {
                            send(RemoteMessage.PingResponse(msg.val1))
                            onMessage(msg)
                        }
                        is RemoteMessage.Unknown -> logD("ATV unknown field=${msg.fieldNumber}")
                        else -> onMessage(msg)
                    }
                }
            }
        } finally {
            close()
        }
    }

    fun markReady() {
        // TVs send regular pings. A silent half-open socket must eventually
        // re-enter reconnect, including after Wi-Fi changes or phone sleep.
        ready = true
        socket?.soTimeout = 30_000
    }

    suspend fun send(msg: RemoteMessage) = withContext(Dispatchers.IO) {
        val data = msg.encode()
        writeLock.withLock {
            val s = socket ?: error("ATV channel not connected")
            try {
                AndroidTvFraming.writeFrame(s.outputStream, data)
            } catch (e: Exception) {
                // Unblock the reader so write failures use the same retry path.
                close()
                throw e
            }
        }
    }

    fun close() {
        synchronized(lifecycleLock) {
            closed = true
            runCatching { socket?.close() }
            socket = null
        }
    }
}
