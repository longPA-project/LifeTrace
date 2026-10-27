package com.example.sensorcollector.lifelogging

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LscDatasetExporter(private val context: Context) {

    data class ExportResult(
        val imageRelativePath: String,
        val metadataRelativePath: String,
        val snapshotMetadataRelativePath: String
    )

    data class ExportLocations(
        val datasetDirectory: String,
        val metadataFile: String,
        val snapshotMetadataFile: String
    )

    private val contentResolver: ContentResolver = context.contentResolver

    fun export(snapshot: LifelogSnapshot, imageFile: File): ExportResult {
        val localTimeZone = TimeZone.getDefault()
        val imageRelativeDirectory = buildImageRelativeDirectory(snapshot.timestampMs, localTimeZone)
        val imageDisplayName = buildImageFileName(snapshot.timestampMs, localTimeZone)
        val imageRelativePath = "$imageRelativeDirectory/$imageDisplayName"
        val metadataRelativeDirectory = DATASET_RELATIVE_DIR

        upsertFile(
            relativePath = imageRelativeDirectory,
            displayName = imageDisplayName,
            mimeType = "image/jpeg",
            bytes = imageFile.readBytes()
        )

        updateMinuteMetadata(snapshot, imageDisplayName, metadataRelativeDirectory)
        appendSnapshotMetadata(snapshot, imageRelativePath, metadataRelativeDirectory)

        return ExportResult(
            imageRelativePath = imageRelativePath,
            metadataRelativePath = "$metadataRelativeDirectory/$MAIN_METADATA_FILE",
            snapshotMetadataRelativePath = "$metadataRelativeDirectory/$SNAPSHOT_METADATA_FILE"
        )
    }

    fun exportLocations(): ExportLocations {
        val documentsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val datasetRoot = File(documentsRoot, DATASET_PUBLIC_DIR_NAME)
        return ExportLocations(
            datasetDirectory = datasetRoot.absolutePath,
            metadataFile = File(datasetRoot, MAIN_METADATA_FILE).absolutePath,
            snapshotMetadataFile = File(datasetRoot, SNAPSHOT_METADATA_FILE).absolutePath
        )
    }

    private fun updateMinuteMetadata(
        snapshot: LifelogSnapshot,
        imageDisplayName: String,
        relativeDirectory: String
    ) {
        val existingRows = readCsv(relativeDirectory, MAIN_METADATA_FILE)
        val updatedRows = if (existingRows.isEmpty()) {
            mutableListOf(MAIN_METADATA_HEADER.toMutableList())
        } else {
            existingRows.mapTo(mutableListOf()) { it.toMutableList() }
        }

        val minuteId = formatMinuteId(snapshot.timestampMs)
        val rowIndex = updatedRows.indexOfFirst { it.getOrNull(1) == minuteId }
        if (rowIndex >= 0) {
            val row = updatedRows[rowIndex]
            val existingImages = parseImageList(row.getOrNull(MAIN_METADATA_HEADER.lastIndex).orEmpty())
            row[MAIN_METADATA_HEADER.lastIndex] = formatImageList(existingImages + imageDisplayName)
        } else {
            val rowNumber = (updatedRows.size).toString()
            updatedRows += buildMinuteMetadataRow(
                rowNumber = rowNumber,
                snapshot = snapshot,
                imageDisplayName = imageDisplayName
            ).toMutableList()
        }

        writeCsv(relativeDirectory, MAIN_METADATA_FILE, updatedRows)
    }

    private fun appendSnapshotMetadata(
        snapshot: LifelogSnapshot,
        imageRelativePath: String,
        relativeDirectory: String
    ) {
        val existingRows = readCsv(relativeDirectory, SNAPSHOT_METADATA_FILE)
        val rows = if (existingRows.isEmpty()) {
            mutableListOf(SNAPSHOT_METADATA_HEADER.toMutableList())
        } else {
            existingRows.mapTo(mutableListOf()) { it.toMutableList() }
        }

        rows += buildSnapshotMetadataRow(snapshot, imageRelativePath).toMutableList()
        writeCsv(relativeDirectory, SNAPSHOT_METADATA_FILE, rows)
    }

    private fun buildMinuteMetadataRow(
        rowNumber: String,
        snapshot: LifelogSnapshot,
        imageDisplayName: String
    ): List<String> {
        return listOf(
            rowNumber,
            formatMinuteId(snapshot.timestampMs),
            formatUtcTime(snapshot.timestampMs),
            formatLocalTime(snapshot.timestampMs),
            snapshot.latitude?.toString().orEmpty(),
            snapshot.longitude?.toString().orEmpty(),
            snapshot.altitude?.toString().orEmpty(),
            snapshot.placeName.orEmpty(),
            TimeZone.getDefault().id,
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            "",
            formatImageList(listOf(imageDisplayName))
        )
    }

    private fun buildSnapshotMetadataRow(
        snapshot: LifelogSnapshot,
        imageRelativePath: String
    ): List<String> {
        return listOf(
            snapshot.id,
            snapshot.timestampMs.toString(),
            formatMinuteId(snapshot.timestampMs),
            formatUtcTime(snapshot.timestampMs),
            formatLocalTime(snapshot.timestampMs),
            snapshot.latitude?.toString().orEmpty(),
            snapshot.longitude?.toString().orEmpty(),
            snapshot.altitude?.toString().orEmpty(),
            snapshot.placeName.orEmpty(),
            TimeZone.getDefault().id,
            imageRelativePath,
            snapshot.motion.state.name,
            snapshot.motion.speedMps?.toString().orEmpty(),
            snapshot.motion.motionEnergy.toString(),
            snapshot.samplingDecision.mode.name,
            snapshot.samplingDecision.intervalMs.toString(),
            snapshot.samplingDecision.reasons.joinToString("|"),
            snapshot.eventEstimate.eventType.name,
            snapshot.eventEstimate.confidence.toString(),
            snapshot.eventEstimate.worthUploading.toString(),
            snapshot.eventEstimate.possibleNewEvent.toString(),
            snapshot.eventEstimate.reasons.joinToString("|")
        )
    }

    private fun buildImageRelativeDirectory(timestampMs: Long, localTimeZone: TimeZone): String {
        val yearMonth = SimpleDateFormat("yyyyMM", Locale.US).apply {
            timeZone = localTimeZone
        }.format(Date(timestampMs))
        val day = SimpleDateFormat("dd", Locale.US).apply {
            timeZone = localTimeZone
        }.format(Date(timestampMs))
        return "$DATASET_RELATIVE_DIR/images/$yearMonth/$day"
    }

    private fun buildImageFileName(timestampMs: Long, localTimeZone: TimeZone): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = localTimeZone
        }.format(Date(timestampMs)) + "_000.jpg"
    }

    private fun formatMinuteId(timestampMs: Long): String {
        return SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(timestampMs))
    }

    private fun formatUtcTime(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(timestampMs))
    }

    private fun formatLocalTime(timestampMs: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(timestampMs))
    }

    private fun formatImageList(imageNames: List<String>): String {
        return imageNames.joinToString(prefix = "[", postfix = "]") { "'$it'" }
    }

    private fun parseImageList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.length < 2) {
            return emptyList()
        }
        return trimmed.removePrefix("[").removeSuffix("]")
            .split(",")
            .map { it.trim().removePrefix("'").removeSuffix("'") }
            .filter { it.isNotBlank() }
    }

    private fun readCsv(relativeDirectory: String, displayName: String): List<List<String>> {
        val uri = findFileUri(relativeDirectory, displayName) ?: return emptyList()
        val content = contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }.orEmpty()
        if (content.isBlank()) {
            return emptyList()
        }
        return content.lines()
            .filter { it.isNotEmpty() }
            .map(::parseCsvLine)
    }

    private fun writeCsv(relativeDirectory: String, displayName: String, rows: List<List<String>>) {
        val content = rows.joinToString(separator = "\n") { row ->
            row.joinToString(separator = ",", transform = ::escapeCsv)
        } + "\n"

        val uri = findFileUri(relativeDirectory, displayName) ?: createFileUri(
            relativePath = relativeDirectory,
            displayName = displayName,
            mimeType = "text/csv"
        )

        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        } ?: throw IOException("Failed to write $displayName")
    }

    private fun upsertFile(
        relativePath: String,
        displayName: String,
        mimeType: String,
        bytes: ByteArray
    ) {
        val uri = findFileUri(relativePath, displayName) ?: createFileUri(relativePath, displayName, mimeType)
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Failed to write $displayName")
    }

    private fun createFileUri(relativePath: String, displayName: String, mimeType: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        return contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: throw IOException("Failed to create $displayName")
    }

    private fun findFileUri(relativePath: String, displayName: String): Uri? {
        val normalizedRelativePath = "$relativePath/"
        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(normalizedRelativePath, displayName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return Uri.withAppendedPath(MediaStore.Files.getContentUri("external"), id.toString())
            }
        }
        return null
    }

    private fun escapeCsv(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }

    companion object {
        private const val DATASET_PUBLIC_DIR_NAME = "SensorCollector"
        private const val DATASET_RELATIVE_DIR = "Documents/SensorCollector"
        private const val MAIN_METADATA_FILE = "lsc22_metadata.csv"
        private const val SNAPSHOT_METADATA_FILE = "sensorcollector_snapshot_metadata.csv"

        private val MAIN_METADATA_HEADER = listOf(
            "",
            "minute_id",
            "utc_time",
            "local_time",
            "latitude",
            "longitude",
            "altitude",
            "semantic_name",
            "time_zone",
            "heart_rate(bpm)",
            "heart_rate_conf",
            "calories",
            "distance",
            "artist name",
            "song name",
            "album name",
            "sleep_level",
            "awake",
            "minutesToFallAsleep",
            "minutesAsleep",
            "minutesAwake",
            "minutesAfterWakeup",
            "timeInBed",
            "sleep_efficiency",
            "ImageID"
        )

        private val SNAPSHOT_METADATA_HEADER = listOf(
            "snapshot_id",
            "timestamp_ms",
            "minute_id",
            "utc_time",
            "local_time",
            "latitude",
            "longitude",
            "altitude",
            "semantic_name",
            "time_zone",
            "image_path",
            "motion_state",
            "speed_mps",
            "motion_energy",
            "sampling_mode",
            "sampling_interval_ms",
            "sampling_reasons",
            "event_type",
            "event_confidence",
            "worth_uploading",
            "possible_new_event",
            "event_reasons"
        )
    }
}
