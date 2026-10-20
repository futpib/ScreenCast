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
import io.github.ddagunts.screencast.media.ScreenCapture
import io.github.ddagunts.screencast.MirrorProjectionService
import io.github.ddagunts.screencast.media.PhoneMediaVolume
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

internal class NativeCastSession(private val context: Context, val device: CastDevice,
                                 private val audioOnly: Boolean = false,
                                 private val projectionLease: MirrorProjectionService.Lease? = null) {
    private data class App(val sessionId: String, val transportId: String)

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val channel = CastChannel(context, device.host, device.port)
    private val launched = CompletableDeferred<App>()
    private val answered = CompletableDeferred<JSONObject>()
    private var stream: CastStreamingProtocol.VideoStream? = null
    private val audioStream = CastStreamingProtocol.AudioStream.random()
    private val _state = MutableStateFlow("CONNECTING")
    val state: StateFlow<String> = _state
    private var app: App? = null
    private var sender: CastStreamingSender? = null
    private var encoder: VideoEncoder? = null
    private var audioEncoder: OpusPlaybackEncoder? = null
    @Volatile private var capture: ScreenCapture? = null
    private val qualityLock = Any()
    private var activeProfile: CastVideoQuality.Profile? = null
    private var profileController: CastAdaptiveProfileController? = null
    private var projection: MediaProjection? = null
    private var lastHeartbeat = System.currentTimeMillis()
    @Volatile private var stopped = false
    private var activeAppId = ""

    suspend fun start(resultCode: Int, projectionData: Intent?, onProjectionStopped: () -> Unit) {
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
            val appIds = if (audioOnly) listOf(CastStreamingProtocol.AUDIO_MIRRORING_APP)
                else listOf(CastStreamingProtocol.ANDROID_MIRRORING_APP,
                    CastStreamingProtocol.MIRRORING_APP)
            for (id in appIds) {
                activeAppId = id
                channel.send(launchMsg(id).first)
                if (withTimeoutOrNull(6_000) { launched.await() } != null) break
                if (id == appIds.last()) error("Cast Streaming receiver did not launch")
                logW("Android mirroring receiver unavailable; trying default mirroring receiver")
            }
            val target = launched.await()
            app = target
            channel.send(connectMsg(target.transportId))
            _state.value = "NEGOTIATING"
            val mp = projectionLease?.projection ?: createProjection(resultCode,
                projectionData ?: error("screen projection grant missing"), onProjectionStopped)
            audioEncoder = OpusPlaybackEncoder.prepare(context, mp)
            if (audioOnly && audioEncoder == null) error("playback audio capture is unavailable")
            val offeredAudio = audioEncoder != null
            val offeredProfiles = if (audioOnly) emptyList() else CastVideoQuality.profiles.filter {
                VideoEncoder.avcEncoderFor(it.width, it.height, it.fps) != null
            }.also { require(it.isNotEmpty()) { "phone has no supported H.264 encoder" } }
            val offeredVideos = offeredProfiles.map {
                CastStreamingProtocol.VideoStream.random(it.width, it.height, it.fps, it.bitrate)
            }
            val offer = if (audioOnly) CastStreamingProtocol.audioOnlyOffer(1, audioStream)
                else CastStreamingProtocol.offer(1, offeredVideos,
                    if (offeredAudio) audioStream else null)
            channel.send(CastMessage(SENDER_ID, target.transportId,
                CastStreamingProtocol.NAMESPACE, offer.toString()))
            val answer = withTimeoutOrNull(10_000) { answered.await() }
                ?: error("receiver did not answer the streaming offer")
            val acceptedPort: Int
            val audioAccepted: Boolean
            var initialBitrate = 0
            if (audioOnly) {
                acceptedPort = CastStreamingProtocol.audioOnlyAnswer(answer, 1)
                audioAccepted = true
            } else {
                val offset = if (offeredAudio) 1 else 0
                val accepted = CastStreamingProtocol.answer(answer, 1,
                    offeredVideos.indices.map { it + offset }, if (offeredAudio) 0 else null)
                acceptedPort = accepted.port
                audioAccepted = accepted.audioAccepted
                val offerProfile = offeredProfiles[accepted.videoIndex - offset]
                val offeredVideo = offeredVideos[accepted.videoIndex - offset]
                require((accepted.videoLimits?.minBitRate ?: 0) <= offeredVideo.bitRate) {
                    "receiver minimum bitrate exceeds the selected video offer"
                }
                val profiles = CastVideoQuality.candidates(accepted.videoLimits) { candidate ->
                    candidate.width <= offerProfile.width && candidate.height <= offerProfile.height &&
                        candidate.fps <= offerProfile.fps &&
                        VideoEncoder.avcEncoderFor(candidate.width, candidate.height, candidate.fps) != null
                }
                val profile = profiles.firstOrNull()
                    ?: error("receiver and phone have no compatible H.264 capture profile")
                activeProfile = profile
                profileController = CastAdaptiveProfileController(profiles)
                stream = offeredVideo.copy(width = profile.width, height = profile.height,
                    fps = profile.fps, bitRate = minOf(profile.bitrate, offeredVideo.bitRate),
                    targetDelayMs = minOf(CastStreamingProtocol.TARGET_DELAY_MS,
                        accepted.videoLimits?.maxDelayMs ?: CastStreamingProtocol.TARGET_DELAY_MS),
                    minBitRate = accepted.videoLimits?.minBitRate ?: 300_000)
                initialBitrate = CastVideoQuality.initialBitrate(stream!!, profile)
                logI("Native Cast: negotiated ${profile.width}x${profile.height}@${profile.fps}, " +
                    "bitrate ${stream!!.bitRate}, receiver limits ${accepted.videoLimits}")
            }
            if (!audioAccepted) {
                audioEncoder?.stop()
                audioEncoder = null
            }
            val mediaSender = CastStreamingSender(device.host, acceptedPort,
                if (audioOnly) null else stream,
                if (audioAccepted) audioStream else null,
                initialBitrate = initialBitrate,
                onPictureLoss = { encoder?.requestKeyFrame() },
                onBitrateChange = { rate -> synchronized(qualityLock) {
                    encoder?.setBitrate(minOf(rate, activeProfile?.bitrate ?: rate))
                } },
                onCapturePaused = { paused ->
                    runCatching {
                        synchronized(qualityLock) {
                            if (projectionLease != null) {
                                if (paused) projectionLease.detach()
                                else encoder?.let { video ->
                                    activeProfile?.let { profile ->
                                        projectionLease.attach(video.inputSurface, captureSize(profile))
                                    }
                                }
                            } else capture?.setPaused(paused)
                            if (!paused) encoder?.requestKeyFrame()
                        }
                    }.onFailure { fail("capture flow control failed: ${it.message}") }
                },
                onTransportFailure = ::fail)
            sender = mediaSender
            mediaSender.start()
            if (!audioOnly) {
                startCapture(mp, initialBitrate)
                scope.launch { adaptCaptureQuality() }
            }
            audioEncoder?.start(
                onFrame = { data, ptsUs -> sender?.sendAudio(data, ptsUs) },
                onFailure = ::fail)
            _state.value = "SENDING"
            scope.launch {
                repeat(100) {
                    if (stopped || _state.value.startsWith("ERROR")) return@launch
                    if (sender?.acknowledged == true) {
                        _state.value = if (audioOnly) "CASTING (audio only)"
                            else "CASTING (video only)"
                        if (audioEncoder != null) scope.launch audioAcknowledgement@{
                            repeat(100) {
                                if (stopped || _state.value.startsWith("ERROR")) return@audioAcknowledgement
                                if (sender?.audioAcknowledged == true) {
                                    PhoneMediaVolume.silence(context, this@NativeCastSession)
                                    _state.value = if (audioOnly) "CASTING (audio only)"
                                        else "CASTING (video + audio)"
                                    return@audioAcknowledgement
                                }
                                delay(100)
                            }
                        }
                        return@launch
                    }
                    delay(100)
                }
                fail("receiver did not acknowledge ${if (audioOnly) "audio" else "video"}")
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

    private fun startCapture(mp: MediaProjection, initialBitrate: Int) {
        val profile = activeProfile ?: error("capture profile was not selected")
        val video = createVideoEncoder(profile, initialBitrate)
        encoder = video
        if (projectionLease != null) {
            projectionLease.attach(video.inputSurface, captureSize(profile))
        } else {
            capture = ScreenCapture(context).also {
                it.start(mp, video.inputSurface, captureSize(profile))
            }
        }
        logI("Native Cast: projection captured for Cast Streaming")
    }

    private fun createVideoEncoder(profile: CastVideoQuality.Profile, bitrate: Int): VideoEncoder {
        val video = VideoEncoder(profile.width, profile.height, profile.fps, bitrate,
            keyframeIntervalSec = 1,
            codecName = VideoEncoder.avcEncoderFor(profile.width, profile.height, profile.fps))
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
        return video
    }

    private fun captureSize(profile: CastVideoQuality.Profile) = ScreenCapture.Size(
        profile.width, profile.height, context.resources.configuration.densityDpi)

    private suspend fun adaptCaptureQuality() {
        while (!stopped && !_state.value.startsWith("ERROR")) {
            delay(1_000)
            val mediaSender = sender ?: return
            if (!mediaSender.acknowledged || mediaSender.videoCapturePaused) continue
            val next = profileController?.update(System.nanoTime() / 1_000_000,
                mediaSender.videoBitrate) ?: continue
            runCatching {
                synchronized(qualityLock) {
                    val display = capture
                    if (projectionLease == null && display == null) error("capture stopped")
                    if (projectionLease != null) projectionLease.detach() else display!!.setPaused(true)
                    mediaSender.resetVideoForFormatChange()
                    encoder?.stop()
                    val video = createVideoEncoder(next,
                        minOf(mediaSender.videoBitrate, next.bitrate))
                    encoder = video
                    if (projectionLease != null) projectionLease.attach(video.inputSurface, captureSize(next))
                    else display!!.replaceSurface(video.inputSurface, captureSize(next))
                    activeProfile = next
                    if (projectionLease == null) display!!.setPaused(false)
                    video.requestKeyFrame()
                    logI("Native Cast: adapted capture to ${next.width}x${next.height}@${next.fps}")
                }
            }.onFailure { fail("capture quality change failed: ${it.message}") }
        }
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
        PhoneMediaVolume.restore(context, this)
        synchronized(qualityLock) {
            if (projectionLease != null) runCatching { projectionLease?.detach() }
            else runCatching { capture?.stop() }
            capture = null
            runCatching { encoder?.stop() }; encoder = null
        }
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
