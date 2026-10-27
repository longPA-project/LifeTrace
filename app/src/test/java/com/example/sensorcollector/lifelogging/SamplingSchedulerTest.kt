package com.example.sensorcollector.lifelogging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingSchedulerTest {
    private val scheduler = SamplingScheduler()

    @Test
    fun `initial observation establishes a baseline without a transition`() {
        val decision = scheduler.onContextUpdated(context(), 0L)
        assertEquals(SamplingMode.LOW_FREQUENCY, decision.mode)
        assertEquals(60_000L, decision.intervalMs)
        assertEquals(listOf("steady_context"), decision.reasons)
    }

    @Test
    fun `motion transition must persist before starting a finite boost`() {
        scheduler.onContextUpdated(context(), 0L)
        assertLow(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 1_000L))
        assertLow(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 3_999L))
        val changed = scheduler.onContextUpdated(context(state = ActivityState.WALKING), 4_000L)
        assertEquals(15_000L, changed.intervalMs)
        assertTrue("state_changed" in changed.reasons)
        assertHigh(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 93_999L))
        assertLow(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 94_000L))
    }

    @Test
    fun `alternating motion and unknown measurements do not fabricate transitions`() {
        scheduler.onContextUpdated(context(), 0L)
        scheduler.onContextUpdated(context(state = ActivityState.WALKING), 1_000L)
        scheduler.onContextUpdated(context(state = ActivityState.UNKNOWN), 2_000L)
        assertLow(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 4_000L))
        assertLow(scheduler.onContextUpdated(context(), 5_000L))
    }

    @Test
    fun `place changes are normalized and debounced`() {
        scheduler.onContextUpdated(context(place = " Library ", placeTime = 0L), 0L)
        assertLow(scheduler.onContextUpdated(context(place = "LIBRARY", placeTime = 1_000L), 1_000L))
        assertLow(scheduler.onContextUpdated(context(place = "Canteen", placeTime = 2_000L), 2_000L))
        val changed = scheduler.onContextUpdated(context(place = "Canteen", placeTime = 2_000L), 12_000L)
        assertHigh(changed)
        assertTrue("place_changed" in changed.reasons)
    }

    @Test
    fun `missing and expired place names are not new places`() {
        scheduler.onContextUpdated(context(place = "Library", placeTime = 0L), 0L)
        scheduler.onContextUpdated(context(place = "Canteen", placeTime = 1_000L), 1_000L)
        assertLow(scheduler.onContextUpdated(context(), 5_000L))
        assertLow(scheduler.onContextUpdated(context(place = "Canteen", placeTime = 0L), 310_000L))
    }

    @Test
    fun `slow movement accumulates against a fixed anchor`() {
        scheduler.onContextUpdated(context(latitude = 0.0, fixTime = 0L), 0L)
        assertLow(scheduler.onContextUpdated(context(latitude = 0.0002, fixTime = 10_000L), 10_000L))
        assertLow(scheduler.onContextUpdated(context(latitude = 0.0004, fixTime = 20_000L), 20_000L))
        val moved = scheduler.onContextUpdated(context(latitude = 0.0006, fixTime = 30_000L), 30_000L)
        assertHigh(moved)
        assertTrue("location_shifted" in moved.reasons)
    }

    @Test
    fun `overlapping uncertainty circles suppress GPS drift`() {
        scheduler.onContextUpdated(context(latitude = 0.0, accuracy = 35f, fixTime = 0L), 0L)
        assertLow(scheduler.onContextUpdated(context(latitude = 0.0006, accuracy = 35f, fixTime = 10_000L), 10_000L))
    }

    @Test
    fun `invalid stale inaccurate and out of order fixes cannot trigger a boost`() {
        scheduler.onContextUpdated(context(latitude = 0.0, fixTime = 10_000L), 10_000L)
        val invalid = listOf(
            context(latitude = Double.NaN, fixTime = 11_000L),
            context(latitude = 91.0, fixTime = 11_000L),
            context(latitude = 0.01, accuracy = 200f, fixTime = 11_000L),
            context(latitude = 0.01, fixTime = 9_000L),
            context(latitude = 0.01, fixTime = 90_000L)
        )
        invalid.forEach { assertLow(scheduler.onContextUpdated(it, 11_000L)) }
        assertLow(scheduler.onContextUpdated(context(latitude = 0.01, fixTime = 0L), 40_000L))
    }

    @Test
    fun `teleporting fixes are rejected without poisoning the anchor`() {
        scheduler.onContextUpdated(context(latitude = 0.0, fixTime = 0L), 0L)
        assertLow(scheduler.onContextUpdated(context(latitude = 1.0, fixTime = 1_000L), 1_000L))
        val moved = scheduler.onContextUpdated(context(latitude = 0.0006, fixTime = 10_000L), 10_000L)
        assertHigh(moved)
    }

    @Test
    fun `a long GPS outage establishes a new baseline`() {
        scheduler.onContextUpdated(context(latitude = 0.0, fixTime = 0L), 0L)
        assertLow(scheduler.onContextUpdated(context(latitude = 1.0, fixTime = 60_000L), 60_000L))
    }

    @Test
    fun `continuous movement cannot extend a boost indefinitely`() {
        scheduler.onContextUpdated(context(latitude = 0.0, fixTime = 0L), 0L)
        for (step in 1..18) {
            val nowMs = step * 10_000L
            val decision = scheduler.onContextUpdated(
                context(latitude = step * 0.001, fixTime = nowMs),
                nowMs
            )
            assertHigh(decision)
        }
        val cooldown = scheduler.onContextUpdated(context(latitude = 0.019, fixTime = 190_000L), 190_000L)
        assertLow(cooldown)
        assertTrue("boost_cooldown" in cooldown.reasons)
        assertLow(scheduler.onContextUpdated(context(latitude = 0.020, fixTime = 200_000L), 200_000L))
        assertHigh(scheduler.onContextUpdated(context(latitude = 0.022, fixTime = 220_000L), 220_000L))
    }

    @Test
    fun `resource limits override high frequency and charging bypasses only low battery`() {
        scheduler.onContextUpdated(context(), 0L)
        scheduler.onContextUpdated(context(state = ActivityState.WALKING), 1_000L)
        val walking = context(state = ActivityState.WALKING)
        val lowBattery = scheduler.onContextUpdated(
            walking, 4_000L, SamplingResources(batteryPercent = 10)
        )
        assertEquals(120_000L, lowBattery.intervalMs)

        val charging = scheduler.onContextUpdated(
            walking, 5_000L, SamplingResources(batteryPercent = 10, isCharging = true)
        )
        assertHigh(charging)

        val powerSaving = scheduler.onContextUpdated(
            walking, 6_000L, SamplingResources(powerSaveMode = true)
        )
        assertEquals(120_000L, powerSaving.intervalMs)

        val warm = scheduler.onContextUpdated(
            walking, 7_000L, SamplingResources(thermalState = ThermalState.WARM)
        )
        assertEquals(60_000L, warm.intervalMs)

        val hot = scheduler.onContextUpdated(
            walking, 8_000L, SamplingResources(isCharging = true, thermalState = ThermalState.HOT)
        )
        assertEquals(300_000L, hot.intervalMs)
        assertEquals(SamplingMode.LOW_FREQUENCY, hot.mode)
        assertTrue("thermal_hot" in hot.reasons)
    }

    @Test
    fun `reset and backwards clock discard old boost state`() {
        scheduler.onContextUpdated(context(), 10_000L)
        scheduler.onContextUpdated(context(state = ActivityState.WALKING), 11_000L)
        assertHigh(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 14_000L))
        assertLow(scheduler.onContextUpdated(context(state = ActivityState.WALKING), 1L))
        scheduler.reset()
        assertLow(scheduler.onContextUpdated(context(), 2L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid intervals fail at configuration time`() {
        SamplingConfig(highFrequencyIntervalMs = 0L)
    }

    private fun context(
        state: ActivityState = ActivityState.STATIONARY,
        place: String? = null,
        placeTime: Long? = null,
        latitude: Double? = null,
        accuracy: Float = 5f,
        fixTime: Long? = null
    ) = ContextSnapshot(
        placeName = place,
        latitude = latitude,
        longitude = latitude?.let { 0.0 },
        motion = MotionSnapshot(null, 0.0, state),
        locationAccuracyMeters = latitude?.let { accuracy },
        locationElapsedRealtimeMs = fixTime,
        placeElapsedRealtimeMs = placeTime
    )

    private fun assertHigh(decision: SamplingDecision) {
        assertEquals(SamplingMode.HIGH_FREQUENCY, decision.mode)
    }

    private fun assertLow(decision: SamplingDecision) {
        assertEquals(SamplingMode.LOW_FREQUENCY, decision.mode)
    }
}
