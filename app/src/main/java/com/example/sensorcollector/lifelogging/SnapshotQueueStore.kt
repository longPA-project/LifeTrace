package com.example.sensorcollector.lifelogging

import android.content.Context
import java.io.File

class SnapshotQueueStore(context: Context) {
    private val rootDir = File(context.filesDir, "lifelog_queue").apply { mkdirs() }
    private val snapshotsDir = File(rootDir, "snapshots").apply { mkdirs() }
    private val imagesDir = File(rootDir, "images").apply { mkdirs() }

    fun imagesDir(): File = imagesDir

    fun save(snapshot: LifelogSnapshot) {
        val target = File(snapshotsDir, "${snapshot.id}.json")
        target.writeText(LifelogSnapshotJson.toJson(snapshot).toString())
    }

    fun listSnapshots(): List<LifelogSnapshot> {
        return snapshotsDir.listFiles()
            ?.sortedBy(File::getName)
            ?.mapNotNull { file ->
                runCatching {
                    LifelogSnapshotJson.fromJson(org.json.JSONObject(file.readText()))
                }.getOrNull()
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
}
