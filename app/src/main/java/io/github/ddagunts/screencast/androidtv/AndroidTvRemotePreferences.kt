package io.github.ddagunts.screencast.androidtv

import android.content.Context

class AndroidTvRemotePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("atv_remote_settings", Context.MODE_PRIVATE)

    var notificationEnabled: Boolean
        get() = prefs.getBoolean("notification_enabled", false)
        set(value) { prefs.edit().putBoolean("notification_enabled", value).apply() }
}
