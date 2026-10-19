package io.github.ddagunts.screencast.mediapush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaItemTest {
    @Test fun `URL media types select Cast stream behavior`() {
        assertEquals("BUFFERED", MediaItem.fromUrl("https://example.test/movie.mp4").streamType)
        assertEquals("NONE", MediaItem.fromUrl("https://example.test/photo.jpg").streamType)
        assertEquals("LIVE", MediaItem.fromUrl("https://example.test/live.m3u8").streamType)
    }

    @Test fun `file URI is not a remote media URL`() {
        assertThrows(IllegalArgumentException::class.java) { MediaItem.fromUrl("file:///tmp/movie.mp4") }
    }
}
