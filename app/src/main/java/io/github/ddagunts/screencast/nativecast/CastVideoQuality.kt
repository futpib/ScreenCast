package io.github.ddagunts.screencast.nativecast

import kotlin.math.floor

internal object CastVideoQuality {
    data class Profile(val width: Int, val height: Int, val fps: Int, val bitrate: Int,
                       val minBitrate: Int)

    // The receiver selects an offer; each candidate stays within its limits.
    val profiles = listOf(
        Profile(3840, 2160, 30, 12_000_000, 8_000_000),
        Profile(1920, 1080, 60, 10_000_000, 4_000_000),
        Profile(1280, 720, 60, 6_000_000, 2_000_000),
        Profile(1920, 1080, 30, 7_000_000, 2_500_000),
        Profile(1280, 720, 30, 4_000_000, 1_000_000),
        Profile(960, 540, 30, 2_000_000, 650_000),
        Profile(640, 360, 30, 1_000_000, 300_000),
    )

    fun initialBitrate(stream: CastStreamingProtocol.VideoStream, profile: Profile): Int {
        require(stream.minBitRate <= stream.bitRate)
        return minOf(stream.bitRate, maxOf(3_000_000, profile.minBitrate, stream.minBitRate))
    }

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
