package com.example.sensorcollector.lifelogging

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SnapshotQueueStore(context: Context) {
    private val rootDir = File(context.filesDir, "lifelog_queue").apply { mkdirs() }
    private val snapshotsDir = File(rootDir, "snapshots").apply { mkdirs() }
    private val imagesDir = File(rootDir, "images").apply { mkdirs() }

    fun imagesDir(): File = imagesDir

    fun save(snapshot: LifelogSnapshot) {
        val target = File(snapshotsDir, "${snapshot.id}.json")
        target.writeText(toJson(snapshot).toString())
    }

    fun listSnapshots(): List<LifelogSnapshot> {
        return snapshotsDir.listFiles()
            ?.sortedBy(File::getName)
            ?.mapNotNull { file ->
                runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            ?: emptyList()
    }

    fun delete(snapshotId: String) {
        File(snapshotsDir, "$snapshotId.json").delete()
    }

    fun delete(snapshotId: String, imageFileName: String?) {
        delete(snapshotId)
        imageFileName?.let { File(imagesDir, it).delete() }
    }

    fun deleteExpired(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()) {
        listSnapshots().forEach { snapshot ->
            if (nowMs - snapshot.timestampMs >= maxAgeMs) {
                delete(snapshot.id, snapshot.imageFileName)
            }
        }
    }

    private fun toJson(snapshot: LifelogSnapshot): JSONObject {
        return JSONObject().apply {
            put("id", snapshot.id)
            put("timestampMs", snapshot.timestampMs)
            put("placeName", snapshot.placeName)
            put("latitude", snapshot.latitude)
            put("longitude", snapshot.longitude)
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

    private fun fromJson(json: JSONObject): LifelogSnapshot {
        val motion = json.getJSONObject("motion")
        val sampling = json.getJSONObject("samplingDecision")
        val event = json.getJSONObject("eventEstimate")

        return LifelogSnapshot(
            id = json.getString("id"),
            timestampMs = json.getLong("timestampMs"),
            placeName = json.optString("placeName").takeIf { it.isNotBlank() },
            latitude = json.optDouble("latitude").takeUnless { it.isNaN() },
            longitude = json.optDouble("longitude").takeUnless { it.isNaN() },
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
