package io.github.ddagunts.screencast.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.ddagunts.screencast.NativeCastForegroundService
import io.github.ddagunts.screencast.StartPermissionActivity
import io.github.ddagunts.screencast.cast.CastDevice

class NativeCastViewModel(app: Application) : AndroidViewModel(app) {
    val session = NativeCastForegroundService.sessionFlow

    fun start(device: CastDevice, audioOnly: Boolean = false) {
        if (session.value != null) return
        val ctx = getApplication<Application>()
        ctx.startActivity(Intent(ctx, StartPermissionActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(StartPermissionActivity.EXTRA_DESTINATION,
                StartPermissionActivity.DESTINATION_NATIVE)
            putExtra(StartPermissionActivity.EXTRA_REQUEST_AUDIO, true)
            putExtra(NativeCastForegroundService.EXTRA_AUDIO_ONLY, audioOnly)
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
