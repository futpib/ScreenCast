package io.github.ddagunts.screencast.nativecast

internal class CastRateController(
    initialBitrate: Int,
    private val minBitrate: Int = 750_000,
    private val maxBitrate: Int = 6_000_000,
) {
    var bitrate = initialBitrate
        private set

    private var lastChangeMs = 0L
    private var lastCongestionMs = 0L
    private var lastProgressMs = 0L
    private var pendingLosses = 0
    private var acknowledgedFrames = 0L
    private var receivedCheckpoint = false

    fun onCheckpoint(nowMs: Long, frames: Long) {
        if (!receivedCheckpoint) {
            lastChangeMs = nowMs
            lastCongestionMs = nowMs
        }
        receivedCheckpoint = true
        lastProgressMs = nowMs
        acknowledgedFrames += frames.coerceAtLeast(0)
    }

    fun onNack() {
        pendingLosses++
    }

    fun onPictureLoss(nowMs: Long): Int? = decrease(nowMs)

    fun update(nowMs: Long, oldestInFlightMs: Long): Int? {
        if (!receivedCheckpoint) return null
        val congested = pendingLosses >= 2 ||
            (oldestInFlightMs > 600 && nowMs - lastProgressMs > 400)
        pendingLosses = 0
        if (congested) return decrease(nowMs)
        if (nowMs - lastChangeMs < 3_000 || nowMs - lastCongestionMs < 3_000 ||
            nowMs - lastProgressMs > 1_000 || acknowledgedFrames < 12) return null
        val next = minOf(maxBitrate, bitrate + maxOf(100_000, bitrate / 10))
        if (next == bitrate) return null
        bitrate = next
        lastChangeMs = nowMs
        acknowledgedFrames = 0
        return bitrate
    }

    private fun decrease(nowMs: Long): Int? {
        lastCongestionMs = nowMs
        if (lastChangeMs != 0L && nowMs - lastChangeMs < 700) return null
        val next = maxOf(minBitrate, bitrate * 3 / 4)
        if (next == bitrate) return null
        bitrate = next
        lastChangeMs = nowMs
        acknowledgedFrames = 0
        return bitrate
    }
}
