package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.media.ScreenCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Owns the single MediaProjection capture session used by automatic mirroring.
// Android 14 permits one virtual display per grant. Each transport replaces its
// surface on that display; a failed transport never consumes a second grant.
class MirrorProjectionService : Service() {
    class Lease internal constructor(
        val id: String,
        val projection: MediaProjection,
        private val capture: ScreenCapture,
    ) {
        private var attached = false

        @Synchronized fun attach(surface: Surface, size: ScreenCapture.Size) {
            if (attached) capture.replaceSurface(surface, size)
            else {
                capture.start(projection, surface, size)
                attached = true
            }
            capture.setPaused(false)
        }

        @Synchronized fun detach() {
            if (attached) capture.setPaused(true)
        }

        @Synchronized internal fun close() {
            capture.stop()
            runCatching { projection.stop() }
            attached = false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automatic mirroring", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (data == null || !MirrorAttemptRegistry.isActive(id)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    _error.value = null
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                        if (Build.VERSION.SDK_INT >= 29)
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
                    val previous = _lease.value
                    _lease.value = null
                    previous?.close()
                    val projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
                        .getMediaProjection(code, data) ?: error("screen projection unavailable")
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            if (_lease.value?.id == id) {
                                val ended = _lease.value
                                _lease.value = null
                                ended?.close()
                                stopTransports()
                                stopSelf()
                            }
                        }
                    }, Handler(Looper.getMainLooper()))
                    _lease.value = Lease(id, projection, ScreenCapture(this))
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, notification(id))
                } catch (e: Exception) {
                    _error.value = e.message ?: "screen projection failed"
                    stopSelf()
                }
            }
            ACTION_STOP -> if (_lease.value == null ||
                intent.getStringExtra(EXTRA_ID) == _lease.value?.id) {
                stopTransports()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val lease = _lease.value
        _lease.value = null
        lease?.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun stopTransports() {
        if (NativeCastForegroundService.sessionFlow.value != null)
            startService(Intent(this, NativeCastForegroundService::class.java)
                .setAction(NativeCastForegroundService.ACTION_STOP))
        if (WebRtcForegroundService.sessionFlow.value != null)
            startService(Intent(this, WebRtcForegroundService::class.java)
                .setAction(WebRtcForegroundService.ACTION_STOP))
        if (CastForegroundService.devicesFlow.value.isNotEmpty())
            startService(Intent(this, CastForegroundService::class.java)
                .setAction(CastForegroundService.ACTION_STOP_ALL))
    }

    private fun notification(id: String = _lease.value?.id.orEmpty()): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, MirrorProjectionService::class.java).setAction(ACTION_STOP)
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mirroring your screen")
            .setContentText("Tap Stop to end screen sharing")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.ddagunts.screencast.mirror.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.mirror.STOP"
        const val EXTRA_ID = "mirror_projection_id"
        const val EXTRA_RESULT_CODE = "mirror_projection_code"
        const val EXTRA_RESULT_DATA = "mirror_projection_data"
        private const val CHANNEL_ID = "mirror_projection"
        private const val NOTIFICATION_ID = 5
        private val _lease = MutableStateFlow<Lease?>(null)
        val lease: StateFlow<Lease?> = _lease
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error
    }
}
