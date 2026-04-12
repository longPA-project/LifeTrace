package com.example.sensorcollector.lifelogging

import android.location.Location

class SamplingScheduler(
    private val highFrequencyIntervalMs: Long = 5_000L,
    private val lowFrequencyIntervalMs: Long = 30_000L,
    private val placeChangeBoostMs: Long = 120_000L,
    private val stateChangeBoostMs: Long = 90_000L
) {
    private var lastContext: ContextSnapshot? = null
    private var highFrequencyUntilMs: Long = 0L

    fun onContextUpdated(
        current: ContextSnapshot,
        nowMs: Long = System.currentTimeMillis()
    ): SamplingDecision {
        val previous = lastContext
        val reasons = mutableListOf<String>()

        if (previous != null) {
            val previousPlace = previous.placeName?.takeIf { it.isNotBlank() }
            val currentPlace = current.placeName?.takeIf { it.isNotBlank() }
            if (previousPlace != currentPlace && currentPlace != null) {
                reasons += "place_changed"
                highFrequencyUntilMs = maxOf(highFrequencyUntilMs, nowMs + placeChangeBoostMs)
            }

            if (previous.motion.state != current.motion.state &&
                current.motion.state != ActivityState.UNKNOWN
            ) {
                reasons += "state_changed"
                highFrequencyUntilMs = maxOf(highFrequencyUntilMs, nowMs + stateChangeBoostMs)
            }

            val movedFar = movedEnough(previous, current)
            if (movedFar) {
                reasons += "location_shifted"
                highFrequencyUntilMs = maxOf(highFrequencyUntilMs, nowMs + placeChangeBoostMs)
            }
        }

        lastContext = current
        val highFrequency = nowMs < highFrequencyUntilMs
        if (highFrequency && reasons.isEmpty()) {
            reasons += "boost_window_active"
        }

        return SamplingDecision(
            mode = if (highFrequency) SamplingMode.HIGH_FREQUENCY else SamplingMode.LOW_FREQUENCY,
            intervalMs = if (highFrequency) highFrequencyIntervalMs else lowFrequencyIntervalMs,
            reasons = reasons.ifEmpty { listOf("steady_context") }
        )
    }

    private fun movedEnough(previous: ContextSnapshot, current: ContextSnapshot): Boolean {
        val previousLat = previous.latitude ?: return false
        val previousLng = previous.longitude ?: return false
        val currentLat = current.latitude ?: return false
        val currentLng = current.longitude ?: return false

        val results = FloatArray(1)
        Location.distanceBetween(previousLat, previousLng, currentLat, currentLng, results)
        return results[0] >= 50f
    }
}
