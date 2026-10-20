package io.github.ddagunts.screencast.nativecast

import kotlin.math.floor

internal object CastVideoQuality {
    data class Profile(val width: Int, val height: Int, val fps: Int, val bitrate: Int,
                       val minBitrate: Int)

    // Prefer smooth input response, then image detail. All profiles stay inside
    // the H.264 Baseline 4.2 offer and use standard 16:9 encoder dimensions.
    val profiles = listOf(
        Profile(1920, 1080, 60, 6_000_000, 4_000_000),
        Profile(1280, 720, 60, 4_000_000, 2_000_000),
        Profile(1920, 1080, 30, 4_000_000, 2_500_000),
        Profile(1280, 720, 30, 3_000_000, 1_000_000),
        Profile(960, 540, 30, 1_800_000, 650_000),
        Profile(640, 360, 30, 900_000, 300_000),
    )

    fun select(limits: CastStreamingProtocol.VideoLimits?,
               supports: (Profile) -> Boolean): Profile =
        candidates(limits, supports).firstOrNull()
            ?: error("receiver and phone have no compatible H.264 capture profile")

    fun candidates(limits: CastStreamingProtocol.VideoLimits?,
                   supports: (Profile) -> Boolean): List<Profile> {
        return profiles.mapNotNull { offered ->
            val fps = minOf(offered.fps,
                floor(limits?.maxFrameRate ?: offered.fps.toDouble()).toInt())
            if (fps < 15) null else offered.copy(fps = fps,
                bitrate = offered.bitrate * fps / offered.fps,
                minBitrate = offered.minBitrate * fps / offered.fps)
        }.filter { p ->
            (limits == null || (p.width <= limits.maxWidth && p.height <= limits.maxHeight &&
                p.width >= limits.minWidth && p.height >= limits.minHeight &&
                p.width.toDouble() * p.height * p.fps <= limits.maxPixelsPerSecond &&
                limits.maxBitRate >= p.minBitrate)) && supports(p)
        }.distinctBy { Triple(it.width, it.height, it.fps) }.map { p ->
            p.copy(bitrate = minOf(maxOf(p.bitrate, limits?.minBitRate ?: 0),
                limits?.maxBitRate ?: Int.MAX_VALUE))
        }
    }
}
