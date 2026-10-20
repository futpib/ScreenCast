package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.ddagunts.screencast.CastForegroundService
import io.github.ddagunts.screencast.MirrorAttemptRegistry
import io.github.ddagunts.screencast.MirrorProjectionService
import io.github.ddagunts.screencast.NativeCastForegroundService
import io.github.ddagunts.screencast.StartPermissionActivity
import io.github.ddagunts.screencast.WebRtcForegroundService
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.media.StreamConfigStore
import io.github.ddagunts.screencast.webrtc.WebRtcConfigStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class MirrorIntentState(
    val device: CastDevice? = null,
    val message: String = "Choose a TV to mirror your screen.",
    val busy: Boolean = false,
    val casting: Boolean = false,
    val retrying: Boolean = false,
    val error: String? = null,
)

// Automatic mirroring owns one MediaProjection grant while transports retry.
class MirrorIntentViewModel(app: Application) : AndroidViewModel(app) {
    private enum class Method { NATIVE, WEBRTC, HLS }
    private sealed interface Result {
        data object Ended : Result
        data class Failed(val reason: String) : Result
    }

    private val _state = MutableStateFlow(MirrorIntentState())
    val state: StateFlow<MirrorIntentState> = _state
    private var job: Job? = null
    private var attemptId: String? = null
    private var method: Method? = null
    private var runToken = 0

    init {
        viewModelScope.launch {
            combine(NativeCastForegroundService.sessionFlow,
                WebRtcForegroundService.sessionFlow,
                CastForegroundService.devicesFlow) { native, webRtc, hls ->
                Triple(native, webRtc, hls.values.firstOrNull())
            }.collect { (native, webRtc, hls) ->
                if (job?.isActive == true) return@collect
                val existing = when {
                    native != null -> Triple(Method.NATIVE, native.device, native.status)
                    webRtc != null -> Triple(Method.WEBRTC, webRtc.device, webRtc.status)
                    hls != null -> Triple(Method.HLS, hls.device, hls.castState)
                    else -> null
                }
                if (existing == null) {
                    if (_state.value.busy) {
                        method = null
                        _state.value = MirrorIntentState()
                    }
                } else if (_state.value.device?.host != existing.second.host || !_state.value.busy) {
                    method = existing.first
                    val casting = existing.third.startsWith("CASTING")
                    val failed = existing.third.startsWith("ERROR")
                    _state.value = MirrorIntentState(existing.second,
                        if (casting && existing.first == Method.HLS)
                            "Mirroring to ${existing.second.name} using video playback (about 5–10 s delay)"
                        else if (casting) "Mirroring to ${existing.second.name}"
                        else if (failed) "The previous cast failed. Stop it before trying again."
                        else "Connecting to ${existing.second.name}…",
                        busy = true, casting = casting)
                }
            }
        }
    }

    fun start(device: CastDevice, wholeScreen: Boolean = true) {
        if (job?.isActive == true) return
        if (NativeCastForegroundService.sessionFlow.value != null ||
            WebRtcForegroundService.sessionFlow.value != null ||
            CastForegroundService.devicesFlow.value.isNotEmpty()) {
            _state.value = MirrorIntentState(error = "Stop the current screen cast before starting another.")
            return
        }
        val methods = buildList {
            add(Method.NATIVE)
            if (WebRtcConfigStore(getApplication()).appId.isNotBlank()) add(Method.WEBRTC)
            add(Method.HLS)
        }
        val token = ++runToken
        val id = UUID.randomUUID().toString()
        attemptId = id
        MirrorAttemptRegistry.begin(id)
        job = viewModelScope.launch {
            var lastError = "No connection method succeeded"
            try {
                requestProjection(id, wholeScreen)
                val consent = MirrorAttemptRegistry.consent.filterNotNull().first { it.id == id }
                if (!consent.granted) {
                    _state.value = MirrorIntentState(message = "Screen capture was cancelled.")
                    return@launch
                }
                val projection = withTimeoutOrNull(10_000) {
                    MirrorProjectionService.lease.first { it?.id == id }
                }
                if (projection == null) {
                    _state.value = MirrorIntentState(error = MirrorProjectionService.error.value
                        ?: "Could not start screen capture")
                    return@launch
                }
                for ((index, next) in methods.withIndex()) {
                    if (MirrorProjectionService.lease.value?.id != id) {
                        _state.value = MirrorIntentState(error = "Screen sharing ended.")
                        return@launch
                    }
                    method = next
                    _state.value = MirrorIntentState(
                        device = device,
                        message = if (index == 0) "Connecting to ${device.name}…"
                            else retryMessage(next, device.name),
                        busy = true,
                        retrying = index > 0,
                    )
                    when (val result = attempt(next, device, id)) {
                        Result.Ended -> {
                            _state.value = MirrorIntentState()
                            return@launch
                        }
                        is Result.Failed -> {
                            lastError = result.reason
                            if (!stopAndWait(next, device.host)) {
                                _state.value = MirrorIntentState(
                                    error = "The previous cast could not stop cleanly: $lastError")
                                return@launch
                            }
                        }
                    }
                }
                _state.value = MirrorIntentState(error = "Could not mirror to ${device.name}: $lastError")
            } catch (_: CancellationException) {
                // Stop was requested while a consent dialog or connection was pending.
            } catch (e: Exception) {
                _state.value = MirrorIntentState(
                    error = "Could not start mirroring: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                if (runToken == token) {
                    attemptId?.let(MirrorAttemptRegistry::cancel)
                    stopProjection(id)
                    attemptId = null
                    method = null
                    job = null
                }
            }
        }
    }

    fun stop() {
        runToken++
        attemptId?.let(MirrorAttemptRegistry::cancel)
        attemptId?.let(::stopProjection)
        job?.cancel()
        val current = method
        val host = _state.value.device?.host
        if (current != null && host != null) sendStop(current, host)
        attemptId = null
        method = null
        job = null
        _state.value = MirrorIntentState()
    }

    private suspend fun attempt(next: Method, device: CastDevice, id: String): Result {
        if (MirrorProjectionService.lease.value?.id != id)
            return Result.Failed("screen sharing ended")
        launch(next, device, id)
        val host = device.host
        val status = when (next) {
            Method.NATIVE -> NativeCastForegroundService.sessionFlow.map { snap ->
                snap?.takeIf { it.device.host == host }?.status
            }
            Method.WEBRTC -> WebRtcForegroundService.sessionFlow.map { snap ->
                snap?.takeIf { it.device.host == host }?.status
            }
            Method.HLS -> CastForegroundService.devicesFlow.map { it[host]?.castState }
        }
        var started = false
        val first = withTimeoutOrNull(50_000) {
            status.onEach {
                if (it != null) started = true
                updateStatus(it, device)
            }.first { it?.startsWith("CASTING") == true ||
                it?.startsWith("ERROR") == true || (started && it == null) }
        } ?: return Result.Failed("Connection ended before mirroring started")
        if (first.startsWith("ERROR")) return Result.Failed(errorFor(next, host))
        _state.value = MirrorIntentState(device,
            if (next == Method.HLS) "Mirroring to ${device.name} using video playback (about 5–10 s delay)"
                else "Mirroring to ${device.name}", busy = true, casting = true)
        val terminal = status.first { it == null || it.startsWith("ERROR") }
        return if (terminal == null) Result.Ended else Result.Failed(errorFor(next, host))
    }

    private fun updateStatus(status: String?, device: CastDevice) {
        if (status == null || status.startsWith("ERROR") || status.startsWith("CASTING")) return
        _state.value = _state.value.copy(device = device,
            message = if (_state.value.retrying) retryMessage(method, device.name)
                else "Connecting to ${device.name}…")
    }

    private fun retryMessage(next: Method?, name: String): String =
        if (next == Method.HLS) "Trying video playback on $name (about 5–10 s delay)."
        else "Trying another way to connect to $name."

    private fun errorFor(next: Method, host: String): String = when (next) {
        Method.NATIVE -> NativeCastForegroundService.sessionFlow.value?.status
            ?.removePrefix("ERROR: ") ?: "Direct connection failed"
        Method.WEBRTC -> WebRtcForegroundService.sessionFlow.value?.errorMessage
            ?: "Low latency connection failed"
        Method.HLS -> CastForegroundService.devicesFlow.value[host]?.errorMessage
            ?: "Video connection failed"
    }

    private fun requestProjection(id: String, wholeScreen: Boolean) {
        val app = getApplication<Application>()
        app.startActivity(Intent(app, StartPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(StartPermissionActivity.EXTRA_DESTINATION, StartPermissionActivity.DESTINATION_AUTO)
            putExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID, id)
            putExtra(StartPermissionActivity.EXTRA_WHOLE_SCREEN, wholeScreen)
            putExtra(StartPermissionActivity.EXTRA_REQUEST_AUDIO, true)
        })
    }

    private fun stopProjection(id: String) {
        val app = getApplication<Application>()
        app.startService(Intent(app, MirrorProjectionService::class.java).apply {
            action = MirrorProjectionService.ACTION_STOP
            putExtra(MirrorProjectionService.EXTRA_ID, id)
        })
    }

    private fun launch(next: Method, device: CastDevice, id: String) {
        val app = getApplication<Application>()
        val target = when (next) {
            Method.NATIVE -> NativeCastForegroundService::class.java
            Method.WEBRTC -> WebRtcForegroundService::class.java
            Method.HLS -> CastForegroundService::class.java
        }
        val intent = Intent(app, target).apply {
            putExtra(StartPermissionActivity.EXTRA_MIRROR_ATTEMPT_ID, id)
        }
        when (next) {
            Method.NATIVE -> intent.apply {
                action = NativeCastForegroundService.ACTION_START
                putExtra(NativeCastForegroundService.EXTRA_DEVICE_NAME, device.name)
                putExtra(NativeCastForegroundService.EXTRA_DEVICE_HOST, device.host)
                putExtra(NativeCastForegroundService.EXTRA_DEVICE_PORT, device.port)
            }
            Method.WEBRTC -> intent.apply {
                action = WebRtcForegroundService.ACTION_START
                putExtra(WebRtcForegroundService.EXTRA_DEVICE_NAME, device.name)
                putExtra(WebRtcForegroundService.EXTRA_DEVICE_HOST, device.host)
                putExtra(WebRtcForegroundService.EXTRA_DEVICE_PORT, device.port)
                putExtra(WebRtcForegroundService.EXTRA_APP_ID, WebRtcConfigStore(app).appId)
            }
            Method.HLS -> intent.apply {
                action = CastForegroundService.ACTION_START
                val cfg = StreamConfigStore(app).load()
                putExtra(CastForegroundService.EXTRA_DEVICE_NAME, device.name)
                putExtra(CastForegroundService.EXTRA_DEVICE_HOST, device.host)
                putExtra(CastForegroundService.EXTRA_DEVICE_PORT, device.port)
                putExtra(CastForegroundService.EXTRA_SEGMENT_DURATION, cfg.segmentDurationSec)
                putExtra(CastForegroundService.EXTRA_WINDOW_SIZE, cfg.windowSize)
                putExtra(CastForegroundService.EXTRA_LIVE_EDGE, cfg.liveEdgeFactor)
                putExtra(CastForegroundService.EXTRA_RESOLUTION, cfg.resolution.name)
                putExtra(CastForegroundService.EXTRA_SYNC_START, cfg.syncStart)
                putExtra(CastForegroundService.EXTRA_SYNC_INTERVAL_SEC, cfg.syncIntervalSec)
                putExtra(CastForegroundService.EXTRA_SYNC_DRIFT_MS, cfg.syncDriftThresholdMs)
            }
        }
        ContextCompat.startForegroundService(app, intent)
    }

    private suspend fun stopAndWait(next: Method, host: String): Boolean {
        sendStop(next, host)
        return withTimeoutOrNull(5_000) {
            when (next) {
                Method.NATIVE -> NativeCastForegroundService.sessionFlow.first { it == null }
                Method.WEBRTC -> WebRtcForegroundService.sessionFlow.first { it == null }
                Method.HLS -> CastForegroundService.devicesFlow.first { host !in it }
            }
            true
        } ?: false
    }

    private fun sendStop(next: Method, host: String) {
        val app = getApplication<Application>()
        val intent = when (next) {
            Method.NATIVE -> Intent(app, NativeCastForegroundService::class.java)
                .setAction(NativeCastForegroundService.ACTION_STOP)
            Method.WEBRTC -> Intent(app, WebRtcForegroundService::class.java)
                .setAction(WebRtcForegroundService.ACTION_STOP)
            Method.HLS -> Intent(app, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_STOP_DEVICE)
                .putExtra(CastForegroundService.EXTRA_DEVICE_HOST, host)
        }
        app.startService(intent)
    }
}
