package com.example.sensorcollector.lifelogging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Heuristic motion classification; stale measurements are excluded, not treated as stillness. */
class MotionStateClassifier {
    private var lastAccelerationMagnitude: Double? = null
    private var accelerationTimestampMs: Long? = null
    private var gyroscopeTimestampMs: Long? = null
    private var latestAccelerationEnergy = 0.0
    private var latestGyroscopeEnergy = 0.0

    fun updateAccelerometer(values: FloatArray, nowMs: Long = monotonicTimeMs()): Double {
        val magnitude = magnitude(values) ?: return latestAccelerationEnergy
        val previousTimestamp = accelerationTimestampMs
        if (previousTimestamp != null && nowMs <= previousTimestamp) return latestAccelerationEnergy
        val previousMagnitude = lastAccelerationMagnitude
        latestAccelerationEnergy = if (previousTimestamp == null || previousMagnitude == null ||
            nowMs - previousTimestamp > SENSOR_TIMEOUT_MS
        ) {
            0.0
        } else {
            val delta = abs(magnitude - previousMagnitude)
            smooth(latestAccelerationEnergy, delta, nowMs - previousTimestamp)
        }
        lastAccelerationMagnitude = magnitude
        accelerationTimestampMs = nowMs
        return latestAccelerationEnergy
    }

    fun updateGyroscope(values: FloatArray, nowMs: Long = monotonicTimeMs()): Double {
        val magnitude = magnitude(values) ?: return latestGyroscopeEnergy
        val previousTimestamp = gyroscopeTimestampMs
        if (previousTimestamp != null && nowMs <= previousTimestamp) return latestGyroscopeEnergy
        latestGyroscopeEnergy = if (previousTimestamp == null || nowMs - previousTimestamp > SENSOR_TIMEOUT_MS) {
            magnitude
        } else {
            smooth(latestGyroscopeEnergy, magnitude, nowMs - previousTimestamp)
        }
        gyroscopeTimestampMs = nowMs
        return latestGyroscopeEnergy
    }

    fun classify(speedMps: Float?, nowMs: Long = monotonicTimeMs()): MotionSnapshot {
        val speed = speedMps?.takeIf { it.isFinite() && it >= 0f }
        val accelerationFresh = accelerationTimestampMs?.let { nowMs - it in 0..SENSOR_TIMEOUT_MS } == true
        val gyroscopeFresh = gyroscopeTimestampMs?.let { nowMs - it in 0..SENSOR_TIMEOUT_MS } == true
        val acceleration = if (accelerationFresh) latestAccelerationEnergy else 0.0
        val rotation = if (gyroscopeFresh) latestGyroscopeEnergy else 0.0
        val energy = acceleration + rotation * ROTATION_ENERGY_WEIGHT
        val state = when {
            speed == null && !accelerationFresh && !gyroscopeFresh -> ActivityState.UNKNOWN
            speed != null && speed >= VEHICLE_SPEED_MPS -> ActivityState.IN_VEHICLE
            energy >= RUNNING_ENERGY_THRESHOLD -> ActivityState.RUNNING
            speed != null && speed >= RUNNING_SPEED_MPS -> ActivityState.RUNNING
            speed != null && speed >= WALKING_SPEED_MPS -> ActivityState.WALKING
            energy >= WALKING_ENERGY_THRESHOLD ||
                acceleration >= WALKING_ACCELERATION_THRESHOLD ||
                rotation >= WALKING_ROTATION_THRESHOLD -> ActivityState.WALKING
            else -> ActivityState.STATIONARY
        }
        return MotionSnapshot(speed, energy, state)
    }

    fun reset() {
        lastAccelerationMagnitude = null
        accelerationTimestampMs = null
        gyroscopeTimestampMs = null
        latestAccelerationEnergy = 0.0
        latestGyroscopeEnergy = 0.0
    }

    private fun magnitude(values: FloatArray): Double? {
        if (values.size < 3 || !values[0].isFinite() || !values[1].isFinite() || !values[2].isFinite()) {
            return null
        }
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        return sqrt(x * x + y * y + z * z)
    }

    private fun smooth(previous: Double, current: Double, elapsedMs: Long): Double {
        val weight = 1.0 - exp(-elapsedMs / FILTER_TIME_CONSTANT_MS)
        return previous + weight * (current - previous)
    }

    private companion object {
        const val SENSOR_TIMEOUT_MS = 3_000L
        const val FILTER_TIME_CONSTANT_MS = 500.0
        const val ROTATION_ENERGY_WEIGHT = 0.6
        const val VEHICLE_SPEED_MPS = 6f
        const val RUNNING_SPEED_MPS = 2.8f
        const val WALKING_SPEED_MPS = 0.8f
        const val RUNNING_ENERGY_THRESHOLD = 2.4
        const val WALKING_ENERGY_THRESHOLD = 0.65
        const val WALKING_ACCELERATION_THRESHOLD = 0.2
        const val WALKING_ROTATION_THRESHOLD = 0.12

        fun monotonicTimeMs(): Long = System.nanoTime() / 1_000_000L
    }
}
