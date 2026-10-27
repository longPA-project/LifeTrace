package com.example.sensorcollector.lifelogging

import org.json.JSONArray
import org.json.JSONObject

object LifelogSnapshotJson {
    fun toJson(snapshot: LifelogSnapshot): JSONObject {
        return JSONObject().apply {
            put("id", snapshot.id)
            put("timestampMs", snapshot.timestampMs)
            put("placeName", snapshot.placeName)
            put("latitude", snapshot.latitude)
            put("longitude", snapshot.longitude)
            put("altitude", snapshot.altitude)
            put("imageFileName", snapshot.imageFileName)
            put("motion", JSONObject().apply {
                put("speedMps", snapshot.motion.speedMps)
                put("motionEnergy", snapshot.motion.motionEnergy)
                put("state", snapshot.motion.state.name)
            })
            put("samplingDecision", JSONObject().apply {
                put("mode", snapshot.samplingDecision.mode.name)
                put("intervalMs", snapshot.samplingDecision.intervalMs)
                put("reasons", JSONArray(snapshot.samplingDecision.reasons))
            })
            put("eventEstimate", JSONObject().apply {
                put("eventType", snapshot.eventEstimate.eventType.name)
                put("confidence", snapshot.eventEstimate.confidence)
                put("worthUploading", snapshot.eventEstimate.worthUploading)
                put("possibleNewEvent", snapshot.eventEstimate.possibleNewEvent)
                put("reasons", JSONArray(snapshot.eventEstimate.reasons))
            })
        }
    }

    fun fromJson(json: JSONObject): LifelogSnapshot {
        val motion = json.getJSONObject("motion")
        val sampling = json.getJSONObject("samplingDecision")
        val event = json.getJSONObject("eventEstimate")

        return LifelogSnapshot(
            id = json.getString("id"),
            timestampMs = json.getLong("timestampMs"),
            placeName = json.optString("placeName").takeIf { it.isNotBlank() },
            latitude = json.optDouble("latitude").takeUnless { it.isNaN() },
            longitude = json.optDouble("longitude").takeUnless { it.isNaN() },
            altitude = json.optDouble("altitude").takeUnless { it.isNaN() },
            motion = MotionSnapshot(
                speedMps = motion.optDouble("speedMps").takeUnless { it.isNaN() }?.toFloat(),
                motionEnergy = motion.optDouble("motionEnergy"),
                state = ActivityState.valueOf(motion.getString("state"))
            ),
            samplingDecision = SamplingDecision(
                mode = SamplingMode.valueOf(sampling.getString("mode")),
                intervalMs = sampling.getLong("intervalMs"),
                reasons = sampling.getJSONArray("reasons").toStringList()
            ),
            eventEstimate = LocalEventEstimate(
                eventType = CoarseEventType.valueOf(event.getString("eventType")),
                confidence = event.getDouble("confidence"),
                worthUploading = event.getBoolean("worthUploading"),
                possibleNewEvent = event.getBoolean("possibleNewEvent"),
                reasons = event.getJSONArray("reasons").toStringList()
            ),
            imageFileName = json.getString("imageFileName")
        )
    }

    private fun JSONArray.toStringList(): List<String> {
        return buildList {
            for (index in 0 until length()) {
                add(getString(index))
            }
        }
    }
}
