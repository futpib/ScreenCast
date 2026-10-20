package io.github.ddagunts.screencast.nativecast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastAdaptiveProfileControllerTest {
    private val profiles = listOf(
        CastVideoQuality.Profile(1920, 1080, 60, 6_000_000, 4_000_000),
        CastVideoQuality.Profile(1280, 720, 60, 4_000_000, 2_000_000),
        CastVideoQuality.Profile(640, 360, 30, 900_000, 300_000),
    )

    @Test fun sustainedLowRateDownsizesAndStableRecoveryUpsizes() {
        val control = CastAdaptiveProfileController(profiles)
        assertNull(control.update(10_000, 1_000_000))
        assertNull(control.update(14_000, 1_000_000))
        assertEquals(profiles[2], control.update(15_000, 1_000_000))
        assertNull(control.update(20_000, 2_500_000))
        assertNull(control.update(39_000, 2_500_000))
        assertEquals(profiles[1], control.update(40_000, 2_500_000))
        assertNull(control.update(41_000, 1_000_000))
        assertNull(control.update(45_000, 1_000_000))
        assertEquals(profiles[2], control.update(50_000, 1_000_000))
    }

    @Test fun briefCongestionDoesNotReconfigureEncoder() {
        val control = CastAdaptiveProfileController(profiles)
        assertNull(control.update(10_000, 1_000_000))
        assertNull(control.update(13_000, 5_000_000))
        assertNull(control.update(17_000, 1_000_000))
        assertNull(control.update(21_000, 1_000_000))
    }

    @Test fun lowerBitrateSelectsLaterProfileWhenThresholdsAreNotSorted() {
        val mixed = listOf(
            CastVideoQuality.Profile(1920, 1080, 60, 6_000_000, 4_000_000),
            CastVideoQuality.Profile(1280, 720, 60, 4_000_000, 2_000_000),
            CastVideoQuality.Profile(1920, 1080, 30, 4_000_000, 2_500_000),
            CastVideoQuality.Profile(1280, 720, 30, 3_000_000, 1_000_000),
        )
        val control = CastAdaptiveProfileController(mixed)
        control.update(10_000, 2_800_000)
        assertEquals(mixed[1], control.update(15_000, 2_800_000))
        control.update(25_000, 1_500_000)
        assertEquals(mixed[3], control.update(30_000, 1_500_000))
    }
}
