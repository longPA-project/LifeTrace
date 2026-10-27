package com.example.sensorcollector.lifelogging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateClassifierTest {
    private val classifier = MotionStateClassifier()

    @Test
    fun `missing sensors are unknown rather than stationary`() {
        assertEquals(ActivityState.UNKNOWN, classifier.classify(null, 0L).state)
    }

    @Test
    fun `first accelerometer sample does not create a motion spike`() {
        assertEquals(0.0, classifier.updateAccelerometer(floatArrayOf(0f, 0f, 9.81f), 0L), 0.0)
        assertEquals(ActivityState.STATIONARY, classifier.classify(null, 0L).state)
    }

    @Test
    fun `expired sensor energy is not reused`() {
        classifier.updateGyroscope(floatArrayOf(4f, 0f, 0f), 0L)
        assertEquals(ActivityState.RUNNING, classifier.classify(null, 0L).state)
        val expired = classifier.classify(null, 3_001L)
        assertEquals(ActivityState.UNKNOWN, expired.state)
        assertEquals(0.0, expired.motionEnergy, 0.0)
    }

    @Test
    fun `valid speed supports devices without an IMU`() {
        assertEquals(ActivityState.STATIONARY, classifier.classify(0f, 0L).state)
        assertEquals(ActivityState.WALKING, classifier.classify(1.4f, 0L).state)
        assertEquals(ActivityState.RUNNING, classifier.classify(3.5f, 0L).state)
        assertEquals(ActivityState.IN_VEHICLE, classifier.classify(9f, 0L).state)
    }

    @Test
    fun `invalid and partial measurements do not poison the filter`() {
        classifier.updateAccelerometer(floatArrayOf(Float.NaN, 0f, 0f), 0L)
        classifier.updateGyroscope(floatArrayOf(1f), 0L)
        assertEquals(ActivityState.UNKNOWN, classifier.classify(Float.POSITIVE_INFINITY, 0L).state)
        assertEquals(ActivityState.UNKNOWN, classifier.classify(-1f, 0L).state)
        classifier.updateAccelerometer(floatArrayOf(0f, 0f, 9.81f), 1L)
        assertTrue(classifier.classify(null, 1L).motionEnergy.isFinite())
    }

    @Test
    fun `reset clears stale observations`() {
        classifier.updateGyroscope(floatArrayOf(4f, 0f, 0f), 0L)
        classifier.reset()
        assertEquals(ActivityState.UNKNOWN, classifier.classify(null, 1L).state)
    }

    @Test
    fun `out of order sensor events are ignored`() {
        classifier.updateGyroscope(floatArrayOf(0f, 0f, 0f), 1_000L)
        classifier.updateGyroscope(floatArrayOf(10f, 0f, 0f), 999L)
        assertEquals(ActivityState.STATIONARY, classifier.classify(null, 1_000L).state)
    }
}
