package com.example.sensorcollector.lifelogging

enum class ActivityState {
    UNKNOWN,
    STATIONARY,
    WALKING,
    RUNNING,
    IN_VEHICLE
}

enum class AppPage {
    CAMERA,
    DATA
}

enum class SamplingMode {
    LOW_FREQUENCY,
    HIGH_FREQUENCY
}

enum class CoarseEventType {
    UNKNOWN,
    COMMUTING,
    STUDYING,
    EATING,
    SHOPPING,
    REPAIRING
}

data class MotionSnapshot(
    val speedMps: Float?,
    val motionEnergy: Double,
    val state: ActivityState
)

data class ContextSnapshot(
    val placeName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val motion: MotionSnapshot
)

data class SamplingDecision(
    val mode: SamplingMode,
    val intervalMs: Long,
    val reasons: List<String>
)

data class LocalEventEstimate(
    val eventType: CoarseEventType,
    val confidence: Double,
    val worthUploading: Boolean,
    val possibleNewEvent: Boolean,
    val reasons: List<String>
)
