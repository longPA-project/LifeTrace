package com.example.sensorcollector.lifelogging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCadenceTest {
    private val cadence = CaptureCadence()

    @Test
    fun `first capture is immediately due`() {
        assertEquals(0L, cadence.nextDelayMs(10_000L, 60_000L))
    }

    @Test
    fun `sensor callbacks cannot postpone a capture indefinitely`() {
        cadence.onCaptureStarted(0L)
        cadence.onCaptureFinished(1_000L, success = true)
        assertEquals(59_000L, cadence.nextDelayMs(1_000L, 60_000L))
        assertEquals(1L, cadence.nextDelayMs(59_999L, 60_000L))
        assertEquals(0L, cadence.nextDelayMs(60_000L, 60_000L))
    }

    @Test
    fun `new policy immediately brings forward or defers the original deadline`() {
        cadence.onCaptureStarted(0L)
        cadence.onCaptureFinished(1_000L, success = true)
        assertEquals(0L, cadence.nextDelayMs(20_000L, 15_000L))
        assertEquals(100_000L, cadence.nextDelayMs(20_000L, 120_000L))
    }

    @Test
    fun `only one capture may be active`() {
        assertTrue(cadence.onCaptureStarted(0L))
        assertFalse(cadence.onCaptureStarted(1L))
        assertNull(cadence.nextDelayMs(100_000L, 15_000L))
        cadence.onCaptureFinished(100_000L, success = true)
        assertEquals(0L, cadence.nextDelayMs(100_000L, 15_000L))
    }

    @Test
    fun `failure enforces a retry floor even after a slow exposure`() {
        cadence.onCaptureStarted(0L)
        cadence.onCaptureFinished(100_000L, success = false)
        assertEquals(5_000L, cadence.nextDelayMs(100_000L, 15_000L))
    }

    @Test
    fun `reset starts a new session without old deadlines`() {
        cadence.onCaptureStarted(0L)
        cadence.onCaptureFinished(100L, success = true)
        cadence.reset()
        assertEquals(0L, cadence.nextDelayMs(200L, 60_000L))
    }
}
