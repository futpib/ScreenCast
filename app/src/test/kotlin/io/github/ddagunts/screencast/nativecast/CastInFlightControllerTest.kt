package io.github.ddagunts.screencast.nativecast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastInFlightControllerTest {
    @Test fun pausesOnMediaDurationAndResumesAfterCheckpointProgress() {
        val control = CastInFlightController()
        control.onCheckpoint(1_000)
        assertEquals(CastInFlightController.Action.NONE,
            control.update(1_100, true, 0, 400_000, 200))
        assertEquals(CastInFlightController.Action.PAUSE,
            control.update(1_200, true, 0, 466_000, 200))
        assertEquals(CastInFlightController.Action.NONE,
            control.update(1_300, true, 200_000, 466_000, 200))
        control.onCheckpoint(1_400)
        assertEquals(CastInFlightController.Action.RESUME,
            control.update(1_400, true, 300_000, 466_000, 200))
    }

    @Test fun waitsForFirstReceiverCheckpointAndUsesNegotiatedDelay() {
        val control = CastInFlightController()
        assertEquals(CastInFlightController.Action.NONE,
            control.update(1_000, false, 0, 1_000_000, 200))
        control.onCheckpoint(1_100)
        assertEquals(CastInFlightController.Action.NONE,
            control.update(1_200, true, 0, 900_000, 500))
        assertEquals(CastInFlightController.Action.PAUSE,
            control.update(1_300, true, 0, 1_000_000, 500))
        assertEquals(CastInFlightController.Action.RESUME,
            control.update(1_400, true, null, null, 500))
    }

    @Test fun largeReceiverDelayCannotDisableInteractiveBackpressure() {
        val control = CastInFlightController()
        control.onCheckpoint(1_000)
        assertEquals(CastInFlightController.Action.PAUSE,
            control.update(1_100, true, 0, 1_500_000, 5_000))
    }

    @Test fun stalledPausedReceiverFailsSoMirroringCanFallBack() {
        val control = CastInFlightController()
        control.onCheckpoint(1_000)
        assertEquals(CastInFlightController.Action.PAUSE,
            control.update(1_100, true, 0, 500_000, 200))
        assertEquals(CastInFlightController.Action.NONE,
            control.update(5_999, true, 0, 500_000, 200))
        assertEquals(CastInFlightController.Action.FAIL,
            control.update(6_000, true, 0, 500_000, 200))
    }
}
