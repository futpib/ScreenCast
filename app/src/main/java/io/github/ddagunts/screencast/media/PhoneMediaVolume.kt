package io.github.ddagunts.screencast.media

import android.content.Context
import android.media.AudioManager
import androidx.core.content.edit
import io.github.ddagunts.screencast.util.logW

object PhoneMediaVolume {
    private const val PREFS = "phone_media_volume"
    private const val SAVED_LEVEL = "saved_level"
    private val owners = mutableSetOf<Any>()

    @Synchronized fun silence(context: Context, owner: Any): Boolean {
        if (!owners.add(owner)) return true
        if (owners.size > 1) return true
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio == null || audio.isVolumeFixed) {
            owners.remove(owner)
            return false
        }
        return runCatching {
            val level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (level > 0) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
                    putInt(SAVED_LEVEL, level)
                }
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
            }
            true
        }.getOrElse {
            logW("Could not silence phone media during Cast: $it")
            owners.remove(owner)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(SAVED_LEVEL) }
            false
        }
    }

    @Synchronized fun restore(context: Context, owner: Any) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        restoreSaved(context)
    }

    @Synchronized fun restoreStale(context: Context) {
        if (owners.isEmpty()) restoreSaved(context)
    }

    private fun restoreSaved(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val level = prefs.getInt(SAVED_LEVEL, -1)
        if (level < 0) return
        val restored = runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            if (audio != null && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                check(audio.getStreamVolume(AudioManager.STREAM_MUSIC) != 0)
            }
        }.onFailure { logW("Could not restore phone media volume: $it") }
        if (restored.isSuccess) prefs.edit { remove(SAVED_LEVEL) }
    }
}
