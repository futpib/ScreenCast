package io.github.ddagunts.screencast.webrtc

import android.content.Context
import android.view.Surface
import io.github.ddagunts.screencast.MirrorProjectionService
import io.github.ddagunts.screencast.media.ScreenCapture
import io.github.ddagunts.screencast.util.logI
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

// Feeds libwebrtc from the virtual display already owned by automatic mirror.
// ScreenCapturerAndroid would call getMediaProjection/createVirtualDisplay again.
internal class LeaseScreenCapturer(private val lease: MirrorProjectionService.Lease) : VideoCapturer {
    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null
    private var context: Context? = null
    private var surface: Surface? = null
    private var capturing = false
    @Volatile private var loggedFrame = false

    override fun initialize(surfaceTextureHelper: SurfaceTextureHelper, applicationContext: Context,
                            capturerObserver: CapturerObserver) {
        helper = surfaceTextureHelper
        observer = capturerObserver
        context = applicationContext
    }

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        val texture = helper ?: error("screen capturer was not initialized")
        texture.setTextureSize(width, height)
        loggedFrame = false
        texture.startListening { frame ->
            if (!loggedFrame) {
                loggedFrame = true
                logI("first shared WebRTC frame ${frame.buffer.width}x${frame.buffer.height}")
            }
            observer?.onFrameCaptured(frame)
        }
        val target = Surface(texture.surfaceTexture)
        surface = target
        try {
            lease.attach(target, ScreenCapture.Size(width, height,
                context?.resources?.configuration?.densityDpi ?: 160))
            capturing = true
            observer?.onCapturerStarted(true)
        } catch (e: Exception) {
            texture.stopListening()
            target.release()
            surface = null
            observer?.onCapturerStarted(false)
            throw e
        }
    }

    override fun stopCapture() {
        if (!capturing) return
        lease.detach()
        helper?.stopListening()
        surface?.release()
        surface = null
        capturing = false
        observer?.onCapturerStopped()
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        val target = surface ?: return
        helper?.setTextureSize(width, height)
        lease.attach(target, ScreenCapture.Size(width, height,
            context?.resources?.configuration?.densityDpi ?: 160))
    }

    override fun dispose() { stopCapture() }
    override fun isScreencast() = true
}
