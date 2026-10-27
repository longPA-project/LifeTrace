package com.example.sensorcollector.lifelogging

/**
 * Deadlines are anchored to capture attempts so context updates cannot postpone them.
 * Call from one thread, using the same monotonic clock for all timestamps.
 */
class CaptureCadence(private val minimumRetryDelayMs: Long = 5_000L) {
    private var lastAttemptMs: Long? = null
    private var retryAfterMs = 0L
    var isCaptureInFlight: Boolean = false
        private set

    init {
        require(minimumRetryDelayMs > 0L)
    }

    /** Returns null while a capture is in flight, or zero when the next attempt is due. */
    fun nextDelayMs(nowMs: Long, intervalMs: Long): Long? {
        require(intervalMs > 0L)
        if (isCaptureInFlight) return null
        val deadline = lastAttemptMs?.let { it + intervalMs } ?: nowMs
        return (maxOf(deadline, retryAfterMs) - nowMs).coerceAtLeast(0L)
    }

    fun onCaptureStarted(nowMs: Long): Boolean {
        if (isCaptureInFlight) return false
        lastAttemptMs = nowMs
        isCaptureInFlight = true
        return true
    }

    fun onCaptureFinished(nowMs: Long, success: Boolean) {
        isCaptureInFlight = false
        retryAfterMs = if (success) 0L else nowMs + minimumRetryDelayMs
    }

    fun reset() {
        check(!isCaptureInFlight) { "Cannot reset an active capture" }
        lastAttemptMs = null
        retryAfterMs = 0L
    }
}
