package com.example.sensorcollector.lifelogging

data class LifelogSnapshot(
    val id: String,
    val timestampMs: Long,
    val placeName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val motion: MotionSnapshot,
    val samplingDecision: SamplingDecision,
    val eventEstimate: LocalEventEstimate,
    val imageFileName: String
)
