package io.github.ddagunts.screencast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ddagunts.screencast.androidtv.AndroidTvDevice
import io.github.ddagunts.screencast.androidtv.AndroidTvKey
import io.github.ddagunts.screencast.androidtv.AndroidTvRemote
import io.github.ddagunts.screencast.androidtv.AndroidTvRemotePreferences
import io.github.ddagunts.screencast.androidtv.AndroidTvRemotes
import io.github.ddagunts.screencast.androidtv.AndroidTvState
import io.github.ddagunts.screencast.ui.MainActivity
import io.github.ddagunts.screencast.util.logE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AndroidTvRemoteService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var remote: AndroidTvRemote? = null
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "TV remote controls", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                if (host == null || !AndroidTvRemotePreferences(this).notificationEnabled ||
                    !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val device = AndroidTvDevice(intent.getStringExtra(EXTRA_NAME) ?: host, host,
                    intent.getIntExtra(EXTRA_PORT, 6466))
                val next = AndroidTvRemotes.getOrCreate(this, device)
                if (!next.isPaired) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(next, next.state.value),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
                if (remote !== next) {
                    observer?.cancel()
                    remote = next
                    AndroidTvRemotes.retainNotification(next)
                    observer = scope.launch {
                        next.connect()
                        next.state.collect { state ->
                            if (state is AndroidTvState.Idle || state is AndroidTvState.Error) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            } else {
                                getSystemService(NotificationManager::class.java)
                                    .notify(NOTIFICATION_ID, notification(next, state))
                            }
                        }
                    }
                }
            }
            ACTION_PLAY_PAUSE -> {
                // Pending intents are tied to a host. A stale notification must
                // never send playback commands to a newly selected TV.
                val target = remote?.takeIf { it.device.host == intent.getStringExtra(EXTRA_HOST) }
                if (target?.state?.value is AndroidTvState.Active) scope.launch {
                    runCatching { target.sendKey(AndroidTvKey.MediaPlayPause) }
                        .onFailure { logE("Notification play/pause failed", it) }
                }
                if (remote == null) stopSelf()
            }
            ACTION_DISCONNECT -> {
                if (remote?.device?.host == intent.getStringExtra(EXTRA_HOST)) {
                    remote?.disconnect()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else if (remote == null) stopSelf()
            }
            else -> if (remote == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(target: AndroidTvRemote, state: AndroidTvState): Notification {
        val host = target.device.host
        fun command(action: String) = PendingIntent.getService(this, 0,
            Intent(this, AndroidTvRemoteService::class.java).setAction(action)
                .setData(Uri.parse("screencast-remote://${Uri.encode(host)}"))
                .putExtra(EXTRA_HOST, host),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).setAction(ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = when (state) {
            AndroidTvState.Active -> "Connected"
            is AndroidTvState.Reconnecting -> "Reconnecting…"
            else -> "Connecting…"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TV remote · ${target.device.name}")
            .setContentText(label)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .apply {
                if (state is AndroidTvState.Active) addAction(android.R.drawable.ic_media_pause,
                    "Play / pause", command(ACTION_PLAY_PAUSE))
            }
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", command(ACTION_DISCONNECT))
            .build()
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        if (AndroidTvRemotes.notificationRemote.value === remote) AndroidTvRemotes.retainNotification(null)
        remote = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "io.github.ddagunts.screencast.OPEN_REMOTE"
        private const val ACTION_SHOW = "io.github.ddagunts.screencast.SHOW_REMOTE"
        private const val ACTION_PLAY_PAUSE = "io.github.ddagunts.screencast.REMOTE_PLAY_PAUSE"
        private const val ACTION_DISCONNECT = "io.github.ddagunts.screencast.DISCONNECT_REMOTE"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_PORT = "port"
        private const val CHANNEL_ID = "tv_remote"
        private const val NOTIFICATION_ID = 6466

        fun show(context: Context, device: AndroidTvDevice) {
            check(NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                "Allow ScreenCast notifications in Android settings"
            }
            ContextCompat.startForegroundService(context,
                Intent(context, AndroidTvRemoteService::class.java).setAction(ACTION_SHOW)
                    .putExtra(EXTRA_HOST, device.host).putExtra(EXTRA_NAME, device.name)
                    .putExtra(EXTRA_PORT, device.port))
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, AndroidTvRemoteService::class.java))
        }
    }
}
