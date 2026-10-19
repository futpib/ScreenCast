package io.github.ddagunts.screencast

import android.content.Context

// Advanced mode selector. The home screen offers user goals; this enum keeps
// direct transport selection available from Advanced modes:
//   * HLS — Ktor stream server + the Default Media Receiver. Casts the
//     phone screen to a Chromecast.
//   * NATIVE — stock Cast Streaming receiver + encrypted RTP over UDP.
//   * WEBRTC — custom receiver + RTCPeerConnection. Same target device,
//     low-latency path.
//   * MEDIA — direct file/URL playback through Cast or DLNA.
//   * REMOTE — Android TV / Google TV remote control over the polo
//     pairing + remote-v2 protocols.
// Persisted so Advanced modes reopens with the last direct selection.
enum class CastMode {
    HLS,
    NATIVE,
    WEBRTC,
    MEDIA,
    REMOTE;

    companion object {
        val DEFAULT = HLS
        fun fromName(name: String?): CastMode =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var castMode: CastMode
        get() = CastMode.fromName(prefs.getString(KEY_CAST_MODE, null))
        set(value) { prefs.edit().putString(KEY_CAST_MODE, value.name).apply() }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_CAST_MODE = "cast_mode"
    }
}
