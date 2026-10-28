package com.hatchstudio.archive.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream

class JobStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("hatch_jobs", Context.MODE_PRIVATE)
    private val jobsDir = File(context.filesDir, "hatch/jobs").apply { mkdirs() }

    fun load(): MutableList<ExtractionJob> {
        val raw = prefs.getString(KEY_JOBS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            val jobs = ArrayList<ExtractionJob>(array.length())
            for (i in 0 until array.length()) {
                ExtractionJob.fromJson(array.getJSONObject(i))?.let { jobs.add(it) }
            }
            jobs.sortedByDescending { it.createdAt }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun save(jobs: List<ExtractionJob>) {
        val array = JSONArray()
        jobs.take(30).forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_JOBS, array.toString()).apply()
    }

    fun doneFile(id: String): File = File(jobsDir, "$id.done")

    fun planFile(id: String): File = File(jobsDir, "$id.plan.json")

    fun loadDone(id: String): CompletedEntries {
        val file = doneFile(id)
        if (!file.exists()) return CompletedEntries()
        val indices = HashSet<Int>()
        var bytes = 0L
        file.forEachLine { line ->
            val parts = line.split('\t')
            if (parts.size >= 2) {
                val index = parts[0].toIntOrNull()
                val size = parts[1].toLongOrNull()
                if (index != null && size != null) {
                    indices.add(index)
                    bytes += size
                }
            }
        }
        return CompletedEntries(indices, bytes)
    }

    fun clearArtifacts(id: String) {
        doneFile(id).delete()
        planFile(id).delete()
    }

    fun cleanupStagedOlderThan(millis: Long) {
        val staged = File(context.cacheDir, "hatch/staged")
        val cutoff = System.currentTimeMillis() - millis
        staged.listFiles()?.forEach { child ->
            if (child.lastModified() < cutoff) child.deleteRecursively()
        }
        File(context.cacheDir, "hatch/work").listFiles()?.forEach { child ->
            if (child.lastModified() < cutoff) child.deleteRecursively()
        }
    }

    companion object {
        private const val KEY_JOBS = "jobs"
    }
}

data class CompletedEntries(
    val indices: Set<Int> = emptySet(),
    val bytes: Long = 0,
)

/**
 * Append-only commit journal. Reclaim mode syncs every committed entry before
 * the archive is truncated; Smart mode only flushes, because a replay is safe.
 */
class JobJournal(file: File) : AutoCloseable {
    private val output = FileOutputStream(file, true)
    val completed: LinkedHashSet<Int> = LinkedHashSet()
    var completedBytes: Long = 0
        private set

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val index = parts[0].toIntOrNull()
                    val size = parts[1].toLongOrNull()
                    if (index != null && size != null) {
                        completed.add(index)
                        completedBytes += size
                    }
                }
            }
        }
    }

    @Synchronized
    fun append(index: Int, bytes: Long, sync: Boolean) {
        if (!completed.add(index)) return
        completedBytes += bytes
        output.write("$index\t$bytes\n".toByteArray())
        output.flush()
        if (sync) output.fd.sync()
    }

    @Synchronized
    override fun close() {
        runCatching { output.fd.sync() }
        runCatching { output.close() }
    }
}
