package com.hatchstudio.archive.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class JobMode { SMART, RECLAIM }

enum class JobState {
    QUEUED,
    RUNNING,
    PAUSED,
    NEEDS_PASSWORD,
    COMPLETED,
    FAILED,
    INTERRUPTED;

    val isActive: Boolean
        get() = this == RUNNING || this == PAUSED

    val needsAttention: Boolean
        get() = this == PAUSED || this == FAILED || this == INTERRUPTED || this == NEEDS_PASSWORD
}

enum class ConflictPolicy { REPLACE, KEEP_BOTH }

data class ExtractionJob(
    val id: String = UUID.randomUUID().toString(),
    val archiveName: String,
    val format: String,
    val archiveSize: Long,
    val sourceUri: String?,
    val sourcePath: String?,
    val ownedSourcePath: String?,
    val destinationTreeUri: String,
    val destinationLabel: String,
    val outputFolder: String,
    val mode: JobMode,
    val conflict: ConflictPolicy,
    val selected: List<Int>?,
    val totalEntries: Int,
    val totalBytes: Long,
    val completedEntries: Int = 0,
    val completedBytes: Long = 0,
    val state: JobState = JobState.QUEUED,
    val message: String? = null,
    val multipart: Boolean = false,
    val removeSource: Boolean = false,
    val sourceRemoved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = when {
            state == JobState.COMPLETED -> 1f
            totalBytes > 0 -> (completedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            totalEntries > 0 -> (completedEntries.toDouble() / totalEntries).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }

    val isResumable: Boolean
        get() = state == JobState.PAUSED || state == JobState.FAILED ||
            state == JobState.INTERRUPTED || state == JobState.NEEDS_PASSWORD

    val reclaim: Boolean
        get() = mode == JobMode.RECLAIM

    fun withState(newState: JobState, message: String? = this.message): ExtractionJob =
        copy(state = newState, message = message, updatedAt = System.currentTimeMillis())

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("archiveName", archiveName)
        put("format", format)
        put("archiveSize", archiveSize)
        put("sourceUri", sourceUri ?: JSONObject.NULL)
        put("sourcePath", sourcePath ?: JSONObject.NULL)
        put("ownedSourcePath", ownedSourcePath ?: JSONObject.NULL)
        put("destinationTreeUri", destinationTreeUri)
        put("destinationLabel", destinationLabel)
        put("outputFolder", outputFolder)
        put("mode", mode.name)
        put("conflict", conflict.name)
        selected?.let { list -> put("selected", JSONArray(list)) } ?: put("selected", JSONObject.NULL)
        put("totalEntries", totalEntries)
        put("totalBytes", totalBytes)
        put("completedEntries", completedEntries)
        put("completedBytes", completedBytes)
        put("state", state.name)
        put("message", message ?: JSONObject.NULL)
        put("multipart", multipart)
        put("removeSource", removeSource)
        put("sourceRemoved", sourceRemoved)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): ExtractionJob? {
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val tree = o.optString("destinationTreeUri").takeIf { it.isNotBlank() } ?: return null
            val selected: List<Int>? = if (o.isNull("selected")) {
                null
            } else {
                val array = o.optJSONArray("selected") ?: JSONArray()
                buildList<Int> {
                    for (i in 0 until array.length()) add(array.optInt(i))
                }
            }
            val loadedState = runCatching { JobState.valueOf(o.optString("state")) }
                .getOrDefault(JobState.FAILED)
            val state = when (loadedState) {
                JobState.RUNNING -> JobState.INTERRUPTED
                else -> loadedState
            }
            return ExtractionJob(
                id = id,
                archiveName = o.optString("archiveName", "Archive"),
                format = o.optString("format", "ZIP"),
                archiveSize = o.optLong("archiveSize"),
                sourceUri = o.optString("sourceUri").takeIf { it.isNotBlank() && it != "null" },
                sourcePath = o.optString("sourcePath").takeIf { it.isNotBlank() && it != "null" },
                ownedSourcePath = o.optString("ownedSourcePath")
                    .takeIf { it.isNotBlank() && it != "null" },
                destinationTreeUri = tree,
                destinationLabel = o.optString("destinationLabel", "Folder"),
                outputFolder = o.optString("outputFolder", "Hatch"),
                mode = runCatching { JobMode.valueOf(o.optString("mode")) }.getOrDefault(JobMode.SMART),
                conflict = runCatching { ConflictPolicy.valueOf(o.optString("conflict")) }
                    .getOrDefault(ConflictPolicy.REPLACE),
                selected = selected,
                totalEntries = o.optInt("totalEntries"),
                totalBytes = o.optLong("totalBytes"),
                completedEntries = o.optInt("completedEntries"),
                completedBytes = o.optLong("completedBytes"),
                state = state,
                message = o.optString("message").takeIf { it.isNotBlank() && it != "null" },
                multipart = o.optBoolean("multipart"),
                removeSource = o.optBoolean("removeSource"),
                sourceRemoved = o.optBoolean("sourceRemoved"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
    }
}
