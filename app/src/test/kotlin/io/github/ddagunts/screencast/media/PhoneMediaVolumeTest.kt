package io.github.ddagunts.screencast.media

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneMediaVolumeTest {
    @Test fun silencesUntilLastCastStopsThenRestoresPreviousLevel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        val first = Any()
        val second = Any()
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val level = maxOf(1, original)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
        try {
            assertTrue(PhoneMediaVolume.silence(context, first))
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertTrue(PhoneMediaVolume.silence(context, second))
            PhoneMediaVolume.restore(context, first)
            assertEquals(0, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            PhoneMediaVolume.restore(context, second)
            assertEquals(level, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            PhoneMediaVolume.restore(context, first)
            PhoneMediaVolume.restore(context, second)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        }
    }

    @Test fun userVolumeChangeDuringCastIsPreserved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audio = context.getSystemService(AudioManager::class.java)
        val owner = Any()
        val original = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        try {
            assertTrue(PhoneMediaVolume.silence(context, owner))
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
            PhoneMediaVolume.restore(context, owner)
            assertEquals(2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally {
            PhoneMediaVolume.restore(context, owner)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
        }
    }
}
