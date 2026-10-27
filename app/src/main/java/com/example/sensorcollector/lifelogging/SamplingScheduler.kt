package com.example.sensorcollector.lifelogging

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Confirms context transitions and bounds their high-frequency sampling windows.
 * Call from one thread; observation and update timestamps must share a monotonic clock.
 */
class SamplingScheduler(private val config: SamplingConfig = SamplingConfig()) {
    private data class PendingChange<T>(val value: T, val sinceMs: Long)

    private data class LocationFix(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val timestampMs: Long
    )

    private var acceptedPlace: String? = null
    private var acceptedState = ActivityState.UNKNOWN
    private var pendingPlace: PendingChange<String>? = null
    private var pendingState: PendingChange<ActivityState>? = null
    private var locationAnchor: LocationFix? = null
    private var lastLocation: LocationFix? = null
    private var boostStartedAtMs: Long? = null
    private var boostUntilMs = 0L
    private var cooldownUntilMs = 0L
    private var lastUpdateMs: Long? = null

    fun onContextUpdated(
        current: ContextSnapshot,
        nowMs: Long = System.nanoTime() / 1_000_000L,
        resources: SamplingResources = SamplingResources()
    ): SamplingDecision {
        require(nowMs >= 0L)
        if (lastUpdateMs?.let { nowMs < it } == true) reset()
        lastUpdateMs = nowMs
        if (boostStartedAtMs != null && nowMs >= boostUntilMs) {
            cooldownUntilMs = boostUntilMs + config.boostCooldownMs
            boostStartedAtMs = null
        }

        val reasons = mutableListOf<String>()
        var boostDurationMs = 0L
        if (confirmPlace(current, nowMs)) {
            reasons += "place_changed"
            boostDurationMs = config.placeChangeBoostMs
        }
        if (confirmState(current.motion.state, nowMs)) {
            reasons += "state_changed"
            boostDurationMs = maxOf(boostDurationMs, config.stateChangeBoostMs)
        }
        if (updateLocation(current, nowMs)) {
            reasons += "location_shifted"
            boostDurationMs = maxOf(boostDurationMs, config.placeChangeBoostMs)
        }

        if (boostDurationMs > 0L) {
            if (boostStartedAtMs == null && nowMs < cooldownUntilMs) {
                reasons += "boost_cooldown"
            } else {
                val startedAt = boostStartedAtMs ?: nowMs.also { boostStartedAtMs = it }
                // Repeated transitions share one budget until the cooldown completes.
                boostUntilMs = minOf(
                    maxOf(boostUntilMs, nowMs + boostDurationMs),
                    startedAt + config.maxContinuousBoostMs
                )
            }
        }

        val boosting = boostStartedAtMs != null && nowMs < boostUntilMs
        if (reasons.isEmpty()) reasons += if (boosting) "boost_window_active" else "steady_context"
        var intervalMs = if (boosting) config.highFrequencyIntervalMs else config.lowFrequencyIntervalMs
        if (resources.powerSaveMode) {
            intervalMs = maxOf(intervalMs, config.powerLimitedIntervalMs)
            reasons += "power_save"
        }
        if (!resources.isCharging && resources.batteryPercent?.let { it <= config.lowBatteryPercent } == true) {
            intervalMs = maxOf(intervalMs, config.powerLimitedIntervalMs)
            reasons += "low_battery"
        }
        when (resources.thermalState) {
            ThermalState.NORMAL -> Unit
            ThermalState.WARM -> {
                intervalMs = maxOf(intervalMs, config.lowFrequencyIntervalMs)
                reasons += "thermal_warm"
            }
            ThermalState.HOT -> {
                intervalMs = maxOf(intervalMs, config.thermalLimitedIntervalMs)
                reasons += "thermal_hot"
            }
        }

        return SamplingDecision(
            mode = if (intervalMs < config.lowFrequencyIntervalMs) {
                SamplingMode.HIGH_FREQUENCY
            } else {
                SamplingMode.LOW_FREQUENCY
            },
            intervalMs = intervalMs,
            reasons = reasons
        )
    }

    fun reset() {
        acceptedPlace = null
        acceptedState = ActivityState.UNKNOWN
        pendingPlace = null
        pendingState = null
        locationAnchor = null
        lastLocation = null
        boostStartedAtMs = null
        boostUntilMs = 0L
        cooldownUntilMs = 0L
        lastUpdateMs = null
    }

    private fun confirmPlace(current: ContextSnapshot, nowMs: Long): Boolean {
        val observedAt = current.placeElapsedRealtimeMs
        val place = current.placeName
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() && observedAt != null && nowMs - observedAt in 0..config.maxPlaceAgeMs }
        if (place == null || place == acceptedPlace) {
            pendingPlace = null
            return false
        }
        if (acceptedPlace == null) {
            acceptedPlace = place
            return false
        }
        val pending = pendingPlace?.takeIf { it.value == place }
            ?: PendingChange(place, nowMs).also { pendingPlace = it }
        if (nowMs - pending.sinceMs < config.placeConfirmationMs) return false
        acceptedPlace = place
        pendingPlace = null
        return true
    }

    private fun confirmState(state: ActivityState, nowMs: Long): Boolean {
        if (state == ActivityState.UNKNOWN || state == acceptedState) {
            pendingState = null
            return false
        }
        if (acceptedState == ActivityState.UNKNOWN) {
            acceptedState = state
            return false
        }
        val pending = pendingState?.takeIf { it.value == state }
            ?: PendingChange(state, nowMs).also { pendingState = it }
        if (nowMs - pending.sinceMs < config.stateConfirmationMs) return false
        acceptedState = state
        pendingState = null
        return true
    }

    private fun updateLocation(current: ContextSnapshot, nowMs: Long): Boolean {
        val latitude = current.latitude ?: return false
        val longitude = current.longitude ?: return false
        val accuracy = current.locationAccuracyMeters ?: return false
        val timestamp = current.locationElapsedRealtimeMs ?: return false
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracy.isFinite() || accuracy <= 0f || accuracy > config.maxLocationAccuracyMeters ||
            nowMs - timestamp !in 0..config.maxLocationAgeMs
        ) {
            return false
        }

        val fix = LocationFix(latitude, longitude, accuracy, timestamp)
        val previous = lastLocation
        if (previous != null) {
            val elapsedMs = timestamp - previous.timestampMs
            if (elapsedMs <= 0L) return false
            if (elapsedMs > config.maxLocationAgeMs) {
                // A gap in observations is not evidence of a place transition.
                locationAnchor = fix
            } else if (minimumDisplacement(previous, fix) / (elapsedMs / 1_000.0) > config.maxTravelSpeedMps) {
                return false
            }
        }
        lastLocation = fix
        // Keep the anchor between fixes so gradual movement still crosses the threshold.
        val anchor = locationAnchor ?: fix.also { locationAnchor = it }
        if (minimumDisplacement(anchor, fix) < config.displacementThresholdMeters) return false
        locationAnchor = fix
        return true
    }

    private fun minimumDisplacement(from: LocationFix, to: LocationFix): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val haversine = sin(latitudeDelta / 2).let { it * it } +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
            sin(longitudeDelta / 2).let { it * it }
        val distance = 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        // Require movement beyond both uncertainty circles, not merely different coordinates.
        return (distance - from.accuracyMeters - to.accuracyMeters).coerceAtLeast(0.0)
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        val WHITESPACE = Regex("""\s+""")
    }
}
