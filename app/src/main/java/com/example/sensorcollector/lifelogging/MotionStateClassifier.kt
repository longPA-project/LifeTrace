package com.example.sensorcollector.lifelogging

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

class MotionStateClassifier(
    private val gravityReference: Double = SensorManager.GRAVITY_EARTH.toDouble()
) {
    private var lastAccelerationMagnitude = gravityReference
    private var latestAccelerationEnergy = 0.0
    private var latestGyroscopeEnergy = 0.0

    fun updateAccelerometer(values: FloatArray): Double {
        val x = values.getOrNull(0)?.toDouble() ?: 0.0
        val y = values.getOrNull(1)?.toDouble() ?: 0.0
        val z = values.getOrNull(2)?.toDouble() ?: 0.0
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastAccelerationMagnitude)
        lastAccelerationMagnitude = magnitude
        latestAccelerationEnergy = latestAccelerationEnergy * 0.8 + delta * 0.2
        return latestAccelerationEnergy
    }

    fun updateGyroscope(values: FloatArray): Double {
        val x = values.getOrNull(0)?.toDouble() ?: 0.0
        val y = values.getOrNull(1)?.toDouble() ?: 0.0
        val z = values.getOrNull(2)?.toDouble() ?: 0.0
        val magnitude = sqrt(x * x + y * y + z * z)
        latestGyroscopeEnergy = latestGyroscopeEnergy * 0.85 + magnitude * 0.15
        return latestGyroscopeEnergy
    }

    fun classify(speedMps: Float?): MotionSnapshot {
        val combinedMotionEnergy = latestAccelerationEnergy + latestGyroscopeEnergy * 0.6
        val state = when {
            speedMps != null && speedMps >= 6.0f -> ActivityState.IN_VEHICLE
            combinedMotionEnergy >= 2.4 -> ActivityState.RUNNING
            combinedMotionEnergy >= 0.65 -> ActivityState.WALKING
            latestAccelerationEnergy >= 0.2 || latestGyroscopeEnergy >= 0.12 -> ActivityState.WALKING
            else -> ActivityState.STATIONARY
        }

        return MotionSnapshot(
            speedMps = speedMps,
            motionEnergy = combinedMotionEnergy,
            state = state
        )
    }
}
