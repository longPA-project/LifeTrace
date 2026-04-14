package com.example.sensorcollector.lifelogging

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UploadClient(
    private val uploadBaseUrl: String
) {
    data class UploadResult(
        val success: Boolean,
        val detail: String
    )

    fun isConfigured(): Boolean = uploadBaseUrl.isNotBlank()

    fun upload(snapshot: LifelogSnapshot, imageFile: File): UploadResult {
        if (!isConfigured() || !imageFile.exists()) {
            return UploadResult(false, "upload disabled or image missing")
        }

        val boundary = "----SensorCollector${System.currentTimeMillis()}"
        val requestBody = buildRequestBody(boundary, snapshot, imageFile)
        val connection = (URL(uploadBaseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 8000
            readTimeout = 12000
            useCaches = false
            setFixedLengthStreamingMode(requestBody.size)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Connection", "close")
        }

        return runCatching {
            connection.outputStream.use { output ->
                output.write(requestBody)
                output.flush()
            }
            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                UploadResult(true, "HTTP $responseCode")
            } else {
                UploadResult(false, "HTTP $responseCode")
            }
        }.getOrElse { error ->
            UploadResult(false, error.message ?: error.javaClass.simpleName)
        }.also {
            connection.disconnect()
        }
    }

    private fun buildRequestBody(
        boundary: String,
        snapshot: LifelogSnapshot,
        imageFile: File
    ): ByteArray {
        return ByteArrayOutputStream().use { output ->
            writeJsonPart(output, boundary, snapshot)
            writeFilePart(output, boundary, imageFile)
            output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            output.toByteArray()
        }
    }

    private fun writeJsonPart(
        output: ByteArrayOutputStream,
        boundary: String,
        snapshot: LifelogSnapshot
    ) {
        val payload = buildMetadataJson(snapshot)

        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"metadata\"\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(payload.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun writeFilePart(output: ByteArrayOutputStream, boundary: String, imageFile: File) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write(
            "Content-Disposition: form-data; name=\"image\"; filename=\"${imageFile.name}\"\r\n"
                .toByteArray(Charsets.UTF_8)
        )
        output.write("Content-Type: image/jpeg\r\n\r\n".toByteArray(Charsets.UTF_8))
        imageFile.inputStream().use { input -> input.copyTo(output) }
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun buildMetadataJson(snapshot: LifelogSnapshot): String {
        return buildString {
            append('{')
            appendJsonField("id", snapshot.id)
            append(',')
            appendJsonField("timestampMs", snapshot.timestampMs)
            append(',')
            appendJsonField("placeName", snapshot.placeName)
            append(',')
            appendJsonField("latitude", snapshot.latitude)
            append(',')
            appendJsonField("longitude", snapshot.longitude)
            append(',')
            appendJsonField("speedMps", snapshot.motion.speedMps)
            append(',')
            appendJsonField("motionEnergy", snapshot.motion.motionEnergy)
            append(',')
            appendJsonField("state", snapshot.motion.state.name)
            append(',')
            appendJsonField("samplingMode", snapshot.samplingDecision.mode.name)
            append(',')
            appendJsonField("samplingIntervalMs", snapshot.samplingDecision.intervalMs)
            append(',')
            appendJsonField("eventType", snapshot.eventEstimate.eventType.name)
            append(',')
            appendJsonField("confidence", snapshot.eventEstimate.confidence)
            append(',')
            appendJsonField("worthUploading", snapshot.eventEstimate.worthUploading)
            append(',')
            appendJsonField("possibleNewEvent", snapshot.eventEstimate.possibleNewEvent)
            append(',')
            appendJsonField("samplingReasons", snapshot.samplingDecision.reasons)
            append(',')
            appendJsonField("eventReasons", snapshot.eventEstimate.reasons)
            append(',')
            appendJsonField("imageFileName", snapshot.imageFileName)
            append('}')
        }
    }

    private fun StringBuilder.appendJsonField(name: String, value: Any?) {
        append('"')
        append(escapeJson(name))
        append("\":")
        append(jsonValue(value))
    }

    private fun jsonValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${escapeJson(value)}\""
            is Number, is Boolean -> value.toString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> jsonValue(item) }
            else -> "\"${escapeJson(value.toString())}\""
        }
    }

    private fun escapeJson(value: String): String {
        val builder = StringBuilder(value.length)
        value.forEach { char ->
            when (char) {
                '\\' -> builder.append("\\\\")
                '"' -> builder.append("\\\"")
                '\b' -> builder.append("\\b")
                '\u000C' -> builder.append("\\f")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> {
                    if (char.code < 0x20) {
                        builder.append("\\u%04x".format(char.code))
                    } else {
                        builder.append(char)
                    }
                }
            }
        }
        return builder.toString()
    }
}
