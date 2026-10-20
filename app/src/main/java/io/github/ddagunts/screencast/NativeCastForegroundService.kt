package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.ddagunts.screencast.cast.CastDevice
import io.github.ddagunts.screencast.media.PhoneMediaVolume
import io.github.ddagunts.screencast.nativecast.NativeCastSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class NativeCastForegroundService : Service() {
    data class Snapshot(val device: CastDevice, val status: String)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var session: NativeCastSession? = null
    private var stateJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioOnly = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        PhoneMediaVolume.restoreStale(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Native Cast", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> scope.launch { stopSession(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (session != null) return
        val host = intent.getStringExtra(EXTRA_DEVICE_HOST) ?: return
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: host
        val port = intent.getIntExtra(EXTRA_DEVICE_PORT, 8009)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        audioOnly = intent.getBooleanExtra(EXTRA_AUDIO_ONLY, false)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) return
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(name, "Connecting"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
        val lock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            if (audioOnly) PowerManager.PARTIAL_WAKE_LOCK else PowerManager.SCREEN_DIM_WAKE_LOCK,
            "screencast:native-cast")
        lock.acquire()
        wakeLock = lock
        val device = CastDevice(name, host, port)
        val s = NativeCastSession(this, device, audioOnly)
        session = s
        _session.value = Snapshot(device, "CONNECTING")
        stateJob = scope.launch {
            s.state.collect { status ->
                _session.value = Snapshot(device, status)
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(name, status))
            }
        }
        scope.launch {
            s.start(code, data) {
                scope.launch { stopSession(); stopSelf() }
            }
        }
    }

    private suspend fun stopSession() {
        val s = session
        session = null
        stateJob?.cancel()
        stateJob = null
        s?.stop()
        _session.value = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        runBlocking(Dispatchers.IO) { stopSession() }
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(name: String, status: String): Notification {
        val stop = PendingIntent.getService(this, 0,
            Intent(this, NativeCastForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${if (audioOnly) "Cast phone audio" else "Native Cast"} → $name")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.ddagunts.screencast.native.START"
        const val ACTION_STOP = "io.github.ddagunts.screencast.native.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_HOST = "device_host"
        const val EXTRA_DEVICE_PORT = "device_port"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_AUDIO_ONLY = "audio_only"
        private const val CHANNEL_ID = "native_cast"
        private const val NOTIFICATION_ID = 3
        private val _session = MutableStateFlow<Snapshot?>(null)
        val sessionFlow: StateFlow<Snapshot?> = _session
    }
}
