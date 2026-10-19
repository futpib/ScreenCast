package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastSession
import io.github.ddagunts.screencast.cast.CastState
import io.github.ddagunts.screencast.cast.DEFAULT_MEDIA_RECEIVER
import io.github.ddagunts.screencast.mediapush.DlnaClient
import io.github.ddagunts.screencast.mediapush.DlnaDevice
import io.github.ddagunts.screencast.mediapush.LocalMediaServer
import io.github.ddagunts.screencast.mediapush.MediaItem
import io.github.ddagunts.screencast.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.ServerSocket

class MediaPushService : Service() {
    data class Snapshot(val target: String, val protocol: String, val title: String, val status: String)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var cast: CastSession? = null
    private var dlna: DlnaClient? = null
    private var server: LocalMediaServer? = null
    private var stateJob: Job? = null
    private var startJob: Job? = null
    @Volatile private var active = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Media casting", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (!active) start(intent)
            ACTION_STOP -> scope.launch { stopSession(); stopSelf() }
            ACTION_PAUSE -> control("Pause requested") { cast?.pause() ?: dlna?.pause() }
            ACTION_PLAY -> control("Play requested") { cast?.play() ?: dlna?.play() }
        }
        return START_NOT_STICKY
    }

    private fun control(status: String, command: suspend () -> Unit?) {
        scope.launch {
            runCatching { command() }
                .onSuccess { _session.value?.let { publish(it.copy(status = status)) } }
                .onFailure { _error.value = it.message ?: "Media command failed" }
        }
    }

    private fun start(intent: Intent) {
        val protocol = intent.getStringExtra(EXTRA_PROTOCOL) ?: return
        val name = intent.getStringExtra(EXTRA_TARGET_NAME) ?: return
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: return
        active = true
        publish(Snapshot(name, protocol, source.substringAfterLast('/'), "Connecting"))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(name, "Connecting"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        _error.value = null
        startJob = scope.launch {
            try {
                val local = intent.getBooleanExtra(EXTRA_LOCAL, false)
                val item = if (local) MediaItem.fromDocument(contentResolver, Uri.parse(source))
                    else MediaItem.fromUrl(source, intent.getStringExtra(EXTRA_MIME).orEmpty())
                val mediaUrl = if (item.local) {
                    val host = NetworkUtils.getWifiIpAddress(this@MediaPushService)
                        ?: error("Connect to the same Wi-Fi as the receiver")
                    val port = ServerSocket(0).use { it.localPort }
                    LocalMediaServer(contentResolver, item, host, port).also {
                        it.start()
                        server = it
                    }.url
                } else item.uri
                publish(Snapshot(name, protocol, item.title, "Loading"))
                if (protocol == PROTOCOL_CAST) {
                    val host = intent.getStringExtra(EXTRA_HOST) ?: error("Cast host missing")
                    val session = CastSession(CastDevice(name, host,
                        intent.getIntExtra(EXTRA_PORT, 8009)))
                    cast = session
                    session.startCast(DEFAULT_MEDIA_RECEIVER, mediaUrl, item.mimeType,
                        streamType = item.streamType, title = item.title)
                    if (session.state.value is CastState.Error)
                        error((session.state.value as CastState.Error).message)
                    publish(Snapshot(name, protocol, item.title, "Loading"))
                    stateJob = scope.launch {
                        combine(session.state, session.playerState) { state, player -> state to player }
                            .collect { (state, player) ->
                            when (state) {
                                is CastState.Error -> publish(Snapshot(name, protocol, item.title,
                                    "Error: ${state.message}"))
                                is CastState.Casting -> publish(Snapshot(name, protocol, item.title,
                                    when (player) {
                                        "PLAYING" -> "Playing"
                                        "PAUSED" -> "Paused"
                                        "BUFFERING" -> "Buffering"
                                        else -> "Loading"
                                    }))
                                else -> Unit
                            }
                        }
                    }
                } else if (protocol == PROTOCOL_DLNA) {
                    val client = DlnaClient(DlnaDevice(name,
                        intent.getStringExtra(EXTRA_HOST) ?: error("DLNA host missing"),
                        intent.getStringExtra(EXTRA_CONTROL_URL) ?: error("DLNA control URL missing"),
                        intent.getStringExtra(EXTRA_SERVICE_TYPE) ?: error("DLNA service type missing")))
                    dlna = client
                    client.loadAndPlay(item, mediaUrl)
                    publish(Snapshot(name, protocol, item.title, "Playback requested"))
                } else error("Unknown media protocol")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: e.javaClass.simpleName
                releaseSession(stopReceiver = false)
                _session.value = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                active = false
                stopSelf()
            }
        }
    }

    private fun publish(value: Snapshot) {
        _session.value = value
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(value.target, value.status))
    }

    private suspend fun stopSession() {
        startJob?.cancelAndJoin()
        startJob = null
        releaseSession(stopReceiver = true)
        _session.value = null
        active = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private suspend fun releaseSession(stopReceiver: Boolean) {
        stateJob?.cancel()
        stateJob = null
        val castSession = cast
        cast = null
        if (castSession != null) {
            if (stopReceiver) runCatching { castSession.stopAndClose() }
            else castSession.close()
        }
        val dlnaClient = dlna
        dlna = null
        if (stopReceiver && dlnaClient != null) runCatching { dlnaClient.stop() }
        server?.stop()
        server = null
    }

    override fun onDestroy() {
        runBlocking(Dispatchers.IO) {
            startJob?.cancelAndJoin()
            releaseSession(stopReceiver = true)
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(name: String, status: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, MediaPushService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Media → $name")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.ddagunts.screencast.media.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.media.STOP"
        const val ACTION_PAUSE = "io.github.ddagunts.screencast.media.PAUSE"
        const val ACTION_PLAY = "io.github.ddagunts.screencast.media.PLAY"
        const val PROTOCOL_CAST = "Cast"
        const val PROTOCOL_DLNA = "DLNA"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_TARGET_NAME = "target_name"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_CONTROL_URL = "control_url"
        const val EXTRA_SERVICE_TYPE = "service_type"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_LOCAL = "local"
        const val EXTRA_MIME = "mime"
        private const val CHANNEL_ID = "media_push"
        private const val NOTIFICATION_ID = 4
        private val _session = MutableStateFlow<Snapshot?>(null)
        val sessionFlow: StateFlow<Snapshot?> = _session
        private val _error = MutableStateFlow<String?>(null)
        val errorFlow: StateFlow<String?> = _error
    }
}
