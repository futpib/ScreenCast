package io.github.ddagunts.screencast.media

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import io.github.ddagunts.screencast.util.logI

class ScreenCapture(private val context: Context) {
    data class Size(val width: Int, val height: Int, val dpi: Int)

    private var virtualDisplay: VirtualDisplay? = null
    private var surface: Surface? = null
    private var paused = false

    @Synchronized fun start(projection: MediaProjection, surface: Surface, size: Size) {
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCast",
            size.width, size.height, size.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, null
        )
        this.surface = surface
        paused = false
        logI("VirtualDisplay created ${size.width}x${size.height}")
    }

    @Synchronized fun setPaused(value: Boolean) {
        val display = virtualDisplay ?: return
        if (paused == value) return
        display.setSurface(if (value) null else surface)
        paused = value
    }

    @Synchronized fun replaceSurface(next: Surface, size: Size) {
        val display = virtualDisplay ?: error("capture display is not active")
        display.setSurface(null)
        display.resize(size.width, size.height, size.dpi)
        surface = next
        display.setSurface(if (paused) null else next)
        logI("VirtualDisplay resized ${size.width}x${size.height}")
    }

    @Synchronized fun stop() {
        virtualDisplay?.release(); virtualDisplay = null
        surface = null
        paused = false
    }

    companion object {
        // Chromecast's Default Media Receiver expects landscape 16:9. The physical (portrait)
        // display is letterboxed inside this surface; this is the same approach Google's Cast
        // sender uses for screen mirroring.
        fun sizeFor(context: Context, resolution: Resolution): Size {
            return Size(resolution.width, resolution.height, context.resources.configuration.densityDpi)
        }
    }
}
