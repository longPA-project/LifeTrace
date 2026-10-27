package com.example.sensorcollector.lifelogging

data class SamplingConfig(
    val highFrequencyIntervalMs: Long = 15_000L,
    val lowFrequencyIntervalMs: Long = 60_000L,
    val placeChangeBoostMs: Long = 120_000L,
    val stateChangeBoostMs: Long = 90_000L,
    val placeConfirmationMs: Long = 10_000L,
    val stateConfirmationMs: Long = 3_000L,
    val maxContinuousBoostMs: Long = 180_000L,
    val boostCooldownMs: Long = 30_000L,
    val displacementThresholdMeters: Double = 50.0,
    val maxLocationAccuracyMeters: Float = 40f,
    val maxLocationAgeMs: Long = 30_000L,
    val maxPlaceAgeMs: Long = 300_000L,
    val placeLookupIntervalMs: Long = 30_000L,
    val maxTravelSpeedMps: Double = 75.0,
    val lowBatteryPercent: Int = 15,
    val powerLimitedIntervalMs: Long = 120_000L,
    val thermalLimitedIntervalMs: Long = 300_000L
) {
    init {
        require(highFrequencyIntervalMs > 0L)
        require(lowFrequencyIntervalMs >= highFrequencyIntervalMs)
        require(placeChangeBoostMs > 0L && stateChangeBoostMs > 0L)
        require(placeConfirmationMs >= 0L && stateConfirmationMs >= 0L)
        require(maxContinuousBoostMs >= maxOf(placeChangeBoostMs, stateChangeBoostMs))
        require(boostCooldownMs >= 0L)
        require(displacementThresholdMeters.isFinite() && displacementThresholdMeters > 0.0)
        require(maxLocationAccuracyMeters.isFinite() && maxLocationAccuracyMeters > 0f)
        require(maxLocationAgeMs > 0L && maxPlaceAgeMs > 0L)
        require(placeLookupIntervalMs in 1L..maxPlaceAgeMs)
        require(maxTravelSpeedMps.isFinite() && maxTravelSpeedMps > 0.0)
        require(lowBatteryPercent in 0..100)
        require(powerLimitedIntervalMs >= lowFrequencyIntervalMs)
        require(thermalLimitedIntervalMs >= powerLimitedIntervalMs)
    }
}

enum class ThermalState {
    NORMAL,
    WARM,
    HOT
}

data class SamplingResources(
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val powerSaveMode: Boolean = false,
    val thermalState: ThermalState = ThermalState.NORMAL
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100)
    }
}
