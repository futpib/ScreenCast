package io.github.ddagunts.screencast.nativecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastRateControllerTest {
    @Test fun growsOnlyWithRecentAcknowledgementsAndNoCongestion() {
        val control = CastRateController(3_000_000)
        control.onCheckpoint(1_000, 1)
        assertNull(control.update(2_000, 0))
        assertNull(control.update(4_000, 0))
        control.onCheckpoint(4_100, 12)
        assertEquals(3_300_000, control.update(4_200, 100))
        assertNull(control.update(4_500, 100))
    }

    @Test fun repeatedLossOrStalledCheckpointCutsBitrate() {
        val control = CastRateController(3_000_000)
        control.onCheckpoint(1_000, 1)
        control.onNack()
        assertNull(control.update(2_000, 100))
        control.onNack()
        control.onNack()
        assertEquals(2_250_000, control.update(2_500, 100))
        assertNull(control.update(2_600, 900))
        assertEquals(1_687_500, control.update(3_300, 900))
    }

    @Test fun bitrateStaysWithinBounds() {
        val control = CastRateController(3_000_000)
        control.onCheckpoint(1_000, 1)
        var now = 2_000L
        repeat(20) {
            control.onPictureLoss(now)
            now += 1_000
        }
        assertEquals(750_000, control.bitrate)
        repeat(30) {
            control.onCheckpoint(now, 12)
            control.update(now + 100, 0)
            now += 3_100
        }
        assertEquals(6_000_000, control.bitrate)
    }

    @Test fun receiverBackpressureDropsRateWithinNegotiatedLimit() {
        val control = CastRateController(1_000_000, maxBitrate = 1_000_000)
        control.onCheckpoint(1_000, 1)
        assertEquals(750_000, control.onBackpressure(2_000))
        assertNull(control.update(2_500, 0, 200))
        control.onCheckpoint(6_000, 12)
        assertEquals(850_000, control.update(6_100, 0, 200))
    }
}
