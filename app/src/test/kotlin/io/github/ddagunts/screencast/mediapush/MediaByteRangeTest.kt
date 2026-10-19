package io.github.ddagunts.screencast.mediapush

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaByteRangeTest {
    @Test fun `supports full and partial requests`() {
        assertEquals(MediaByteRange(0, 99), MediaByteRange.parse(null, 100))
        assertEquals(MediaByteRange(10, 19), MediaByteRange.parse("bytes=10-19", 100))
        assertEquals(MediaByteRange(90, 99), MediaByteRange.parse("bytes=-10", 100))
        assertEquals(MediaByteRange(10, 99), MediaByteRange.parse("bytes=10-", 100))
        assertEquals(MediaByteRange(10, 99), MediaByteRange.parse("bytes=10-200", 100))
    }

    @Test fun `rejects unsatisfiable requests`() {
        assertNull(MediaByteRange.parse("bytes=100-", 100))
        assertNull(MediaByteRange.parse("bytes=20-10", 100))
        assertNull(MediaByteRange.parse("bytes=0-1,3-4", 100))
    }
}
