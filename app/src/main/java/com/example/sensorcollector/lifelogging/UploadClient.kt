package com.example.sensorcollector.lifelogging

import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UploadClient(
    private val uploadBaseUrl: String
) {
    fun isConfigured(): Boolean = uploadBaseUrl.isNotBlank()

    fun upload(snapshot: LifelogSnapshot, imageFile: File): Boolean {
        if (!isConfigured() || !imageFile.exists()) {
            return false
        }

        val boundary = "----SensorCollector${System.currentTimeMillis()}"
        val connection = (URL(uploadBaseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }

        return runCatching {
            DataOutputStream(connection.outputStream).use { output ->
                writeJsonPart(output, boundary, snapshot)
                writeFilePart(output, boundary, imageFile)
                output.writeBytes("--$boundary--\r\n")
                output.flush()
            }
            connection.responseCode in 200..299
        }.getOrDefault(false)
    }

    private fun writeJsonPart(
        output: DataOutputStream,
        boundary: String,
        snapshot: LifelogSnapshot
    ) {
        val payload = JSONObject().apply {
            put("id", snapshot.id)
            put("timestampMs", snapshot.timestampMs)
            put("placeName", snapshot.placeName)
            put("latitude", snapshot.latitude)
            put("longitude", snapshot.longitude)
            put("speedMps", snapshot.motion.speedMps)
            put("motionEnergy", snapshot.motion.motionEnergy)
            put("state", snapshot.motion.state.name)
            put("samplingMode", snapshot.samplingDecision.mode.name)
            put("samplingIntervalMs", snapshot.samplingDecision.intervalMs)
            put("eventType", snapshot.eventEstimate.eventType.name)
            put("confidence", snapshot.eventEstimate.confidence)
            put("worthUploading", snapshot.eventEstimate.worthUploading)
            put("possibleNewEvent", snapshot.eventEstimate.possibleNewEvent)
        }.toString()

        output.writeBytes("--$boundary\r\n")
        output.writeBytes("Content-Disposition: form-data; name=\"metadata\"\r\n")
        output.writeBytes("Content-Type: application/json; charset=UTF-8\r\n\r\n")
        output.write(payload.toByteArray(Charsets.UTF_8))
        output.writeBytes("\r\n")
    }

    private fun writeFilePart(output: DataOutputStream, boundary: String, imageFile: File) {
        output.writeBytes("--$boundary\r\n")
        output.writeBytes(
            "Content-Disposition: form-data; name=\"image\"; filename=\"${imageFile.name}\"\r\n"
        )
        output.writeBytes("Content-Type: image/jpeg\r\n\r\n")
        imageFile.inputStream().use { input -> input.copyTo(output) }
        output.writeBytes("\r\n")
    }
}
