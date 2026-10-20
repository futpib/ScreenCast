package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.MediaPushService
import io.github.ddagunts.screencast.StartPermissionActivity
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.mediapush.DlnaDevice
import io.github.ddagunts.screencast.mediapush.DlnaDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MediaPushViewModel(app: Application) : AndroidViewModel(app) {
    data class SharedSource(val id: Long, val source: String, val local: Boolean,
                            val mime: String)
    private val _shared = MutableStateFlow<SharedSource?>(null)
    val shared: StateFlow<SharedSource?> = _shared
    private val discovery = DlnaDiscovery(app)
    private val _devices = MutableStateFlow<List<DlnaDevice>>(emptyList())
    val devices: StateFlow<List<DlnaDevice>> = _devices
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    val session = MediaPushService.sessionFlow
    val serviceError = MediaPushService.errorFlow

    init { scan() }

    fun acceptSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (android.os.Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri != null && uri.scheme == "content") {
            _shared.value = SharedSource(android.os.SystemClock.elapsedRealtimeNanos(),
                uri.toString(), true, intent.type.orEmpty())
            return
        }
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (Uri.parse(url).scheme in listOf("http", "https"))
            _shared.value = SharedSource(android.os.SystemClock.elapsedRealtimeNanos(), url, false, "")
    }

    fun scan() {
        viewModelScope.launch {
            runCatching { discovery.scan() }
                .onSuccess { found ->
                    _devices.value = (found + _devices.value).distinctBy { it.controlUrl }
                    _error.value = null
                }
                .onFailure { _error.value = it.message ?: "DLNA discovery failed" }
        }
    }

    fun addDescription(url: String) {
        viewModelScope.launch {
            runCatching { discovery.resolve(url.trim()) ?: error("No AVTransport service found") }
                .onSuccess { device ->
                    _devices.value = (_devices.value + device).distinctBy { it.controlUrl }
                    _error.value = null
                }
                .onFailure { _error.value = it.message ?: "Cannot read DLNA description" }
        }
    }

    fun startCast(device: CastDevice, source: String, local: Boolean, mime: String) {
        start(Intent(getApplication(), MediaPushService::class.java)
            .putExtra(MediaPushService.EXTRA_PROTOCOL, MediaPushService.PROTOCOL_CAST)
            .putExtra(MediaPushService.EXTRA_TARGET_NAME, device.name)
            .putExtra(MediaPushService.EXTRA_HOST, device.host)
            .putExtra(MediaPushService.EXTRA_PORT, device.port), source, local, mime)
    }

    fun startDlna(device: DlnaDevice, source: String, local: Boolean, mime: String) {
        start(Intent(getApplication(), MediaPushService::class.java)
            .putExtra(MediaPushService.EXTRA_PROTOCOL, MediaPushService.PROTOCOL_DLNA)
            .putExtra(MediaPushService.EXTRA_TARGET_NAME, device.name)
            .putExtra(MediaPushService.EXTRA_HOST, device.host)
            .putExtra(MediaPushService.EXTRA_CONTROL_URL, device.controlUrl)
            .putExtra(MediaPushService.EXTRA_SERVICE_TYPE, device.serviceType), source, local, mime)
    }

    fun startAutomatic(
        castDevice: CastDevice?, dlnaDevice: DlnaDevice?,
        source: String, local: Boolean, mime: String,
    ) {
        if (session.value != null) return
        if (castDevice == null) {
            if (dlnaDevice != null) startDlna(dlnaDevice, source, local, mime)
            return
        }
        val intent = Intent(getApplication(), MediaPushService::class.java)
            .putExtra(MediaPushService.EXTRA_PROTOCOL, MediaPushService.PROTOCOL_CAST)
            .putExtra(MediaPushService.EXTRA_TARGET_NAME, castDevice.name)
            .putExtra(MediaPushService.EXTRA_HOST, castDevice.host)
            .putExtra(MediaPushService.EXTRA_PORT, castDevice.port)
        if (dlnaDevice != null) {
            intent.putExtra(MediaPushService.EXTRA_FALLBACK_CONTROL_URL, dlnaDevice.controlUrl)
            intent.putExtra(MediaPushService.EXTRA_FALLBACK_SERVICE_TYPE, dlnaDevice.serviceType)
        }
        start(intent, source, local, mime)
    }

    private fun start(intent: Intent, source: String, local: Boolean, mime: String) {
        if (session.value != null) return
        if (source.isBlank()) {
            _error.value = "Choose a file or enter a direct media URL"
            return
        }
        if (!local && runCatching { Uri.parse(source).scheme in listOf("http", "https") }
                .getOrDefault(false).not()) {
            _error.value = "Use a direct HTTP or HTTPS media URL"
            return
        }
        _error.value = null
        val app = getApplication<Application>()
        app.startActivity(Intent(app, StartPermissionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setAction(MediaPushService.ACTION_START)
            .putExtras(intent)
            .putExtra(StartPermissionActivity.EXTRA_DESTINATION,
                StartPermissionActivity.DESTINATION_MEDIA)
            .putExtra(MediaPushService.EXTRA_SOURCE, source)
            .putExtra(MediaPushService.EXTRA_LOCAL, local)
            .putExtra(MediaPushService.EXTRA_MIME, mime))
    }

    fun stop() = action(MediaPushService.ACTION_STOP)
    fun pause() = action(MediaPushService.ACTION_PAUSE)
    fun play() = action(MediaPushService.ACTION_PLAY)

    private fun action(value: String) {
        val app = getApplication<Application>()
        app.startService(Intent(app, MediaPushService::class.java).setAction(value))
    }
}
