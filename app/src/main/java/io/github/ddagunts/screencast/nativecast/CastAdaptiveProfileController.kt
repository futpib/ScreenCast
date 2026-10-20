package io.github.ddagunts.screencast.nativecast

internal class CastAdaptiveProfileController(
    private val profiles: List<CastVideoQuality.Profile>,
) {
    init { require(profiles.isNotEmpty()) }
    private var index = 0
    private var lowSinceMs: Long? = null
    private var highSinceMs: Long? = null
    private var lastSwitchMs = -10_000L

    fun update(nowMs: Long, bitrate: Int): CastVideoQuality.Profile? {
        if (bitrate < profiles[index].minBitrate && index < profiles.lastIndex) {
            val lower = (index + 1..profiles.lastIndex).firstOrNull {
                profiles[it].minBitrate <= bitrate
            } ?: profiles.lastIndex
            highSinceMs = null
            if (lowSinceMs == null) lowSinceMs = nowMs
            if (nowMs - lowSinceMs!! >= 5_000 && nowMs - lastSwitchMs >= 10_000) {
                index = lower
                lastSwitchMs = nowMs
                lowSinceMs = null
                return profiles[index]
            }
        } else {
            lowSinceMs = null
            val next = profiles.getOrNull(index - 1)
            if (next != null && bitrate >= next.minBitrate * 6 / 5) {
                if (highSinceMs == null) highSinceMs = nowMs
                if (nowMs - highSinceMs!! >= 20_000 && nowMs - lastSwitchMs >= 10_000) {
                    index--
                    lastSwitchMs = nowMs
                    highSinceMs = null
                    return profiles[index]
                }
            } else highSinceMs = null
        }
        return null
    }
}
