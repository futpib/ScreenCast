package io.github.ddagunts.screencast.androidtv

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Activity and notification share the same authenticated connection. Keep it
// only while a UI or the foreground service owns it.
object AndroidTvRemotes {
    private val remotes = mutableMapOf<String, AndroidTvRemote>()
    private var uiOwners = 0
    private val _notificationRemote = MutableStateFlow<AndroidTvRemote?>(null)
    val notificationRemote: StateFlow<AndroidTvRemote?> = _notificationRemote

    @Synchronized fun getOrCreate(context: Context, device: AndroidTvDevice): AndroidTvRemote =
        remotes.getOrPut(device.host) { AndroidTvRemote(context.applicationContext, device) }

    @Synchronized operator fun get(host: String): AndroidTvRemote? = remotes[host]

    @Synchronized fun acquireUi() { uiOwners++ }

    @Synchronized fun releaseUi() {
        uiOwners--
        prune()
    }

    @Synchronized fun retainNotification(remote: AndroidTvRemote?) {
        _notificationRemote.value = remote
        prune()
    }

    @Synchronized fun forget(context: Context, host: String) {
        remotes.remove(host)?.close()
        AndroidTvCertStore(context).forget(host)
        if (_notificationRemote.value?.device?.host == host) _notificationRemote.value = null
    }

    private fun prune() {
        if (uiOwners != 0) return
        val iterator = remotes.iterator()
        while (iterator.hasNext()) {
            val remote = iterator.next().value
            if (remote !== _notificationRemote.value) {
                remote.close()
                iterator.remove()
            }
        }
    }
}
