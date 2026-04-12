package com.example.sensorcollector.lifelogging

import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

class MotionStateClassifier(
    private val gravityReference: Double = SensorManager.GRAVITY_EARTH.toDouble()
) {
    private var lastAccelerationMagnitude = gravityReference
    private var latestMotionEnergy = 0.0

    fun updateMotionEnergy(values: FloatArray): Double {
        val x = values.getOrNull(0)?.toDouble() ?: 0.0
        val y = values.getOrNull(1)?.toDouble() ?: 0.0
        val z = values.getOrNull(2)?.toDouble() ?: 0.0
        val magnitude = sqrt(x * x + y * y + z * z)
        val delta = abs(magnitude - lastAccelerationMagnitude)
        lastAccelerationMagnitude = magnitude
        latestMotionEnergy = latestMotionEnergy * 0.8 + delta * 0.2
        return latestMotionEnergy
    }

    fun classify(speedMps: Float?): MotionSnapshot {
        val state = when {
            speedMps == null -> ActivityState.UNKNOWN
            speedMps >= 6.0f -> ActivityState.IN_VEHICLE
            speedMps >= 2.4f -> ActivityState.RUNNING
            speedMps >= 0.8f -> ActivityState.WALKING
            latestMotionEnergy >= 1.2 -> ActivityState.WALKING
            else -> ActivityState.STATIONARY
        }

        return MotionSnapshot(
            speedMps = speedMps,
            motionEnergy = latestMotionEnergy,
            state = state
        )
    }
}
