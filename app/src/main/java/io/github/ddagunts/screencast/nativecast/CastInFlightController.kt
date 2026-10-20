package io.github.ddagunts.screencast.nativecast

internal class CastInFlightController {
    enum class Action { NONE, PAUSE, RESUME, FAIL }

    private var paused = false
    private var lastProgressMs = 0L

    fun onCheckpoint(nowMs: Long) {
        lastProgressMs = nowMs
    }

    fun update(
        nowMs: Long,
        acknowledged: Boolean,
        oldestPtsUs: Long?,
        latestPtsUs: Long?,
        targetDelayMs: Int,
    ): Action {
        if (!acknowledged) return Action.NONE
        if (paused && nowMs - lastProgressMs >= 5_000) return Action.FAIL

        val inFlightUs = if (oldestPtsUs == null || latestPtsUs == null) 0L
            else (latestPtsUs - oldestPtsUs).coerceAtLeast(0)
        val delayUs = targetDelayMs.coerceIn(100, 750) * 1_000L
        val pauseAtUs = maxOf(450_000L, delayUs * 2)
        val resumeAtUs = delayUs
        if (!paused && inFlightUs >= pauseAtUs) {
            paused = true
            return Action.PAUSE
        }
        if (paused && inFlightUs <= resumeAtUs) {
            paused = false
            return Action.RESUME
        }
        return Action.NONE
    }
}
