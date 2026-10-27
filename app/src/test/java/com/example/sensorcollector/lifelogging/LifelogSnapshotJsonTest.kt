package com.example.sensorcollector.lifelogging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LifelogSnapshotJsonTest {

    @Test
    fun `snapshot json round trip preserves fields`() {
        val snapshot = LifelogSnapshot(
            id = "snapshot-1",
            timestampMs = 1_720_000_000_000,
            placeName = "Library",
            latitude = 31.2304,
            longitude = 121.4737,
            altitude = 12.0,
            motion = MotionSnapshot(
                speedMps = 1.8f,
                motionEnergy = 0.42,
                state = ActivityState.WALKING
            ),
            samplingDecision = SamplingDecision(
                mode = SamplingMode.HIGH_FREQUENCY,
                intervalMs = 5_000L,
                reasons = listOf("movement_detected", "new_place")
            ),
            eventEstimate = LocalEventEstimate(
                eventType = CoarseEventType.STUDYING,
                confidence = 0.88,
                worthUploading = true,
                possibleNewEvent = true,
                reasons = listOf("campus_building", "user_stationary")
            ),
            imageFileName = "photo_1.jpg"
        )

        val restored = LifelogSnapshotJson.fromJson(LifelogSnapshotJson.toJson(snapshot))

        assertEquals(snapshot, restored)
    }

    @Test
    fun `blank place name becomes null on restore`() {
        val json = LifelogSnapshotJson.toJson(
            LifelogSnapshot(
                id = "snapshot-2",
                timestampMs = 2L,
                placeName = null,
                latitude = null,
                longitude = null,
                altitude = null,
                motion = MotionSnapshot(
                    speedMps = null,
                    motionEnergy = 0.0,
                    state = ActivityState.UNKNOWN
                ),
                samplingDecision = SamplingDecision(
                    mode = SamplingMode.LOW_FREQUENCY,
                    intervalMs = 30_000L,
                    reasons = listOf("steady_context")
                ),
                eventEstimate = LocalEventEstimate(
                    eventType = CoarseEventType.UNKNOWN,
                    confidence = 0.0,
                    worthUploading = false,
                    possibleNewEvent = false,
                    reasons = listOf("context_stable")
                ),
                imageFileName = "photo_2.jpg"
            )
        ).apply {
            put("placeName", "")
        }

        val restored = LifelogSnapshotJson.fromJson(json)

        assertNull(restored.placeName)
        assertNull(restored.latitude)
        assertNull(restored.longitude)
    }
}
