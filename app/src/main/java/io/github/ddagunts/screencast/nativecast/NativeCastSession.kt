package io.github.ddagunts.screencast.nativecast

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import io.github.ddagunts.screencast.cast.CastChannel
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.cast.CastMessage
import io.github.ddagunts.screencast.cast.CastNs
import io.github.ddagunts.screencast.cast.DEFAULT_RECEIVER_ID
import io.github.ddagunts.screencast.cast.SENDER_ID
import io.github.ddagunts.screencast.cast.connectMsg
import io.github.ddagunts.screencast.cast.launchMsg
import io.github.ddagunts.screencast.cast.pingMsg
import io.github.ddagunts.screencast.cast.pongMsg
import io.github.ddagunts.screencast.media.Resolution
import io.github.ddagunts.screencast.media.ScreenCapture
import io.github.ddagunts.screencast.media.VideoEncoder
import io.github.ddagunts.screencast.util.NetworkUtils
import io.github.ddagunts.screencast.util.logI
import io.github.ddagunts.screencast.util.logW
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal class NativeCastSession(private val context: Context, val device: CastDevice) {
    private data class App(val sessionId: String, val transportId: String)

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val channel = CastChannel(device.host, device.port)
    private val launched = CompletableDeferred<App>()
    private val answered = CompletableDeferred<JSONObject>()
    private val stream = CastStreamingProtocol.VideoStream.random(1280, 720, 30, 3_000_000)
    private val audioStream = CastStreamingProtocol.AudioStream.random()
    private val _state = MutableStateFlow("CONNECTING")
    val state: StateFlow<String> = _state
    private var app: App? = null
    private var sender: CastStreamingSender? = null
    private var encoder: VideoEncoder? = null
    private var audioEncoder: OpusPlaybackEncoder? = null
    private var capture: ScreenCapture? = null
    private var projection: MediaProjection? = null
    private var lastHeartbeat = System.currentTimeMillis()
    @Volatile private var stopped = false
    private var activeAppId = ""

    suspend fun start(resultCode: Int, projectionData: Intent, onProjectionStopped: () -> Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val wifi = NetworkUtils.getWifiNetwork(context)
        if (wifi != null) cm?.bindProcessToNetwork(wifi)
        try {
            channel.connect { e ->
                fail(e?.message ?: "receiver disconnected")
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { collectMessages() }
            scope.launch { heartbeat() }
            channel.send(connectMsg())
            _state.value = "LAUNCHING"
            // Android mirroring is the stock Android sender target. The Chrome
            // mirroring receiver is another stock Cast Streaming target.
            for (id in listOf(CastStreamingProtocol.ANDROID_MIRRORING_APP,
                    CastStreamingProtocol.MIRRORING_APP)) {
                activeAppId = id
                channel.send(launchMsg(id).first)
                if (withTimeoutOrNull(6_000) { launched.await() } != null) break
                if (id == CastStreamingProtocol.MIRRORING_APP) error("mirroring receiver did not launch")
                logW("Android mirroring receiver unavailable; trying default mirroring receiver")
            }
            val target = launched.await()
            app = target
            channel.send(connectMsg(target.transportId))
            _state.value = "NEGOTIATING"
            val mp = createProjection(resultCode, projectionData, onProjectionStopped)
            audioEncoder = OpusPlaybackEncoder.prepare(context, mp)
            val offeredAudio = audioEncoder != null
            val offer = CastStreamingProtocol.offer(1, stream,
                if (offeredAudio) audioStream else null)
            channel.send(CastMessage(SENDER_ID, target.transportId,
                CastStreamingProtocol.NAMESPACE, offer.toString()))
            val answer = withTimeoutOrNull(10_000) { answered.await() }
                ?: error("receiver did not answer the streaming offer")
            val accepted = CastStreamingProtocol.answer(answer, 1, if (offeredAudio) 1 else 0)
            if (!accepted.audioAccepted) {
                audioEncoder?.stop()
                audioEncoder = null
            }
            val mediaSender = CastStreamingSender(device.host, accepted.port, stream,
                if (accepted.audioAccepted) audioStream else null) { encoder?.requestKeyFrame() }
            sender = mediaSender
            mediaSender.start()
            startCapture(mp)
            audioEncoder?.start(
                onFrame = { data, ptsUs -> sender?.sendAudio(data, ptsUs) },
                onFailure = ::fail)
            _state.value = "SENDING"
            scope.launch {
                repeat(100) {
                    if (stopped || _state.value.startsWith("ERROR")) return@launch
                    if (sender?.acknowledged == true) {
                        _state.value = if (audioEncoder != null) "CASTING (video + audio)" else "CASTING (video only)"
                        return@launch
                    }
                    delay(100)
                }
                fail("receiver did not acknowledge video")
                logW("Cast Streaming: no RTCP acknowledgement after 10 seconds")
            }
        } catch (e: CancellationException) {
            releaseMedia()
            channel.close()
            scope.cancel()
            cm?.bindProcessToNetwork(null)
            throw e
        } catch (e: Exception) {
            _state.value = "ERROR: ${e.message ?: "native mirroring failed"}"
            logW("Native Cast: $e")
            releaseMedia()
            stopReceiver()
            channel.close()
            scope.cancel()
            cm?.bindProcessToNetwork(null)
        }
    }

    private fun createProjection(resultCode: Int, projectionData: Intent, onStopped: () -> Unit): MediaProjection {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(resultCode, projectionData)
            ?: error("screen projection was not granted")
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopped && !_state.value.startsWith("ERROR")) onStopped()
            }
        }, Handler(Looper.getMainLooper()))
        return mp
    }

    private fun startCapture(mp: MediaProjection) {
        val video = VideoEncoder(stream.width, stream.height, stream.fps, stream.bitRate,
            keyframeIntervalSec = 1)
        encoder = video
        var codecSps: ByteArray? = null
        var codecPps: ByteArray? = null
        video.start(object : VideoEncoder.Sink {
            override fun onFormat(sps: ByteArray, pps: ByteArray) {
                codecSps = sps; codecPps = pps
            }
            override fun onSample(nalUnits: List<ByteArray>, ptsUs: Long, isKeyFrame: Boolean) {
                val bytes = CastStreamingProtocol.annexB(nalUnits, codecSps, codecPps, isKeyFrame)
                sender?.sendVideo(bytes, ptsUs, isKeyFrame)
            }
        })
        capture = ScreenCapture(context).also {
            it.start(mp, video.inputSurface, ScreenCapture.sizeFor(context, Resolution.P720))
        }
        logI("Native Cast: projection captured for Cast Streaming")
    }

    private suspend fun collectMessages() {
        channel.incoming.collect { msg ->
            val json = runCatching { JSONObject(msg.payloadUtf8) }.getOrNull() ?: return@collect
            when {
                msg.namespace == CastNs.HEARTBEAT && json.optString("type") == "PING" -> {
                    lastHeartbeat = System.currentTimeMillis()
                    runCatching { channel.send(pongMsg()) }
                        .onFailure { fail("receiver disconnected") }
                }
                msg.namespace == CastNs.HEARTBEAT && json.optString("type") == "PONG" ->
                    lastHeartbeat = System.currentTimeMillis()
                msg.namespace == CastNs.RECEIVER && json.optString("type") == "LAUNCH_ERROR" ->
                    logW("Native Cast: launch error ${json.optString("reason")}")
                msg.namespace == CastNs.RECEIVER && json.optString("type") == "RECEIVER_STATUS" -> {
                    val apps = json.optJSONObject("status")?.optJSONArray("applications")
                    if (apps != null) {
                        for (i in 0 until apps.length()) {
                            val entry = apps.optJSONObject(i) ?: continue
                            if (entry.optString("appId") != activeAppId) continue
                            val sid = entry.optString("sessionId")
                            val tid = entry.optString("transportId")
                            if (sid.isNotEmpty() && tid.isNotEmpty()) launched.complete(App(sid, tid))
                        }
                        if (app != null && (0 until apps.length()).none {
                                apps.optJSONObject(it)?.optString("sessionId") == app?.sessionId
                            }) fail("receiver ended the mirroring session")
                    }
                }
                msg.namespace == CastStreamingProtocol.NAMESPACE &&
                    json.optString("type") == "ANSWER" && json.optInt("seqNum", -1) == 1 ->
                    answered.complete(json)
                msg.namespace == CastNs.CONNECTION && json.optString("type") == "CLOSE" &&
                    msg.sourceId == app?.transportId -> fail("receiver closed the session")
            }
        }
    }

    private suspend fun heartbeat() {
        while (!stopped) {
            delay(5_000)
            if (System.currentTimeMillis() - lastHeartbeat > 15_000) {
                fail("receiver heartbeat timed out")
                return
            }
            try {
                channel.send(pingMsg())
            } catch (e: Exception) {
                fail("receiver disconnected")
                return
            }
        }
    }

    suspend fun stop() {
        if (stopped) return
        stopped = true
        releaseMedia()
        stopReceiver()
        channel.close()
        scope.cancel()
        context.getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        _state.value = "STOPPED"
    }

    @Synchronized private fun releaseMedia() {
        runCatching { audioEncoder?.stop() }; audioEncoder = null
        runCatching { capture?.stop() }; capture = null
        runCatching { encoder?.stop() }; encoder = null
        runCatching { sender?.stop() }; sender = null
        runCatching { projection?.stop() }; projection = null
    }

    private fun fail(message: String) {
        if (stopped || _state.value.startsWith("ERROR")) return
        _state.value = "ERROR: $message"
        scope.launch {
            releaseMedia()
            channel.close()
            context.getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
            scope.cancel()
        }
    }

    private suspend fun stopReceiver() {
        app?.let {
            runCatching {
                val body = JSONObject().put("type", "STOP").put("sessionId", it.sessionId)
                    .put("requestId", 1)
                channel.send(CastMessage(SENDER_ID, DEFAULT_RECEIVER_ID, CastNs.RECEIVER,
                    body.toString()))
            }
        }
    }
}
