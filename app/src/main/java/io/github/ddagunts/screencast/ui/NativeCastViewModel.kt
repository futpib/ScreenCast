package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.ddagunts.screencast.NativeCastForegroundService
import io.github.ddagunts.screencast.NativeProjectionRequestActivity
import io.github.ddagunts.screencast.cast.CastDevice

class NativeCastViewModel(app: Application) : AndroidViewModel(app) {
    val session = NativeCastForegroundService.sessionFlow

    fun start(device: CastDevice) {
        if (session.value != null) return
        val ctx = getApplication<Application>()
        ctx.startActivity(Intent(ctx, NativeProjectionRequestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(NativeCastForegroundService.EXTRA_DEVICE_NAME, device.name)
            putExtra(NativeCastForegroundService.EXTRA_DEVICE_HOST, device.host)
            putExtra(NativeCastForegroundService.EXTRA_DEVICE_PORT, device.port)
        })
    }

    fun stop() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, NativeCastForegroundService::class.java)
            .setAction(NativeCastForegroundService.ACTION_STOP))
    }
}
