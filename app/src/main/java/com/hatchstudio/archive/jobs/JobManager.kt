package com.hatchstudio.archive.jobs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hatchstudio.archive.core.ArchiveFailure
import com.hatchstudio.archive.core.ArchiveOpener
import com.hatchstudio.archive.core.ArchiveSource
import com.hatchstudio.archive.core.NativeEngine
import com.hatchstudio.archive.data.ExtractionJob
import com.hatchstudio.archive.data.JobJournal
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.data.JobState
import com.hatchstudio.archive.data.JobStore
import com.hatchstudio.archive.data.PasswordVault
import com.hatchstudio.archive.data.SafTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class JobManager private constructor(private val context: Context) {
    private val store = JobStore(context)
    private val vault = PasswordVault(context)
    private val settings = com.hatchstudio.archive.data.SettingsStore(context)
    private val _jobs: MutableStateFlow<List<ExtractionJob>> = MutableStateFlow(store.load())
    val jobs: StateFlow<List<ExtractionJob>> = _jobs.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pumpMutex = Mutex()
    private val passwords = ConcurrentHashMap<String, String>()
    private val active = AtomicBoolean(false)

    @Volatile private var activeJobId: String? = null
    @Volatile private var activeHandle: Long = 0L
    @Volatile private var nativeCallActive = false
    @Volatile private var lastSaveAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        store.cleanupStagedOlderThan(3L * 24 * 60 * 60 * 1000)
        // A process death leaves a RUNNING marker behind. Never let it look
        // like work is still happening.
        val loaded = _jobs.value.map {
            if (it.state == JobState.RUNNING) {
                it.copy(
                    state = JobState.INTERRUPTED,
                    message = null,
                    updatedAt = System.currentTimeMillis(),
                )
            } else it
        }
        _jobs.value = loaded
        store.save(loaded)
    }

    fun job(id: String): ExtractionJob? = _jobs.value.firstOrNull { it.id == id }

    fun enqueue(items: List<ExtractionJob>, password: String?) {
        if (items.isEmpty()) return
        items.forEach { job ->
            if (password != null) passwords[job.id] = password
            if (job.state == JobState.QUEUED) {
                job.sourcePath?.let { path ->
                    // Source is already staged; nothing to do until the job runs.
                    Log.d(TAG, "Queued ${job.archiveName} from $path")
                }
            }
        }
        _jobs.update { current -> items + current }
        store.save(_jobs.value)
        ensurePump()
    }

    fun pauseActive() {
        val job = activeJobId?.let(::job) ?: return
        if (job.state != JobState.RUNNING && job.state != JobState.QUEUED) return
        if (nativeCallActive && activeHandle != 0L) {
            NativeEngine.pause(activeHandle, true)
        }
        mutate(job.id) { it.copy(state = JobState.PAUSED, message = "Paused", updatedAt = now()) }
        releaseWakeLock()
        ExtractionService.stop(context)
    }

    fun resumeActive() {
        val job = activeJobId?.let(::job)
            ?: _jobs.value.firstOrNull { it.state == JobState.PAUSED }
            ?: return
        if (job.state != JobState.PAUSED) return
        if (nativeCallActive && activeHandle != 0L && activeJobId == job.id) {
            acquireWakeLock()
            ExtractionService.start(context)
            mutate(job.id) { it.copy(state = JobState.RUNNING, message = null, updatedAt = now()) }
            NativeEngine.pause(activeHandle, false)
        } else {
            // A paused job has already unwound the native call. Requeue it so
            // the pump reopens the archive with the crash-recovery journal.
            resumeJob(job.id, passwords[job.id] ?: vault.get(job.archiveName))
        }
    }

    fun resumeJob(id: String, password: String? = null) {
        val job = job(id) ?: return
        if (password != null) passwords[id] = password
        if (activeJobId == id && nativeCallActive) {
            if (job.state == JobState.PAUSED) {
                acquireWakeLock()
                ExtractionService.start(context)
                mutate(id) { it.copy(state = JobState.RUNNING, message = null, updatedAt = now()) }
                NativeEngine.pause(activeHandle, false)
            }
            return
        }
        if (job.state == JobState.NEEDS_PASSWORD && password == null &&
            vault.get(job.archiveName) == null
        ) {
            return
        }
        mutate(id) { it.copy(state = JobState.QUEUED, message = null, updatedAt = now()) }
        ensurePump()
    }

    fun stopActive() {
        if (nativeCallActive && activeHandle != 0L) {
            NativeEngine.cancel(activeHandle)
        }
    }

    fun removeJob(id: String): Boolean {
        if (activeJobId == id) return false
        val job = _jobs.value.firstOrNull { it.id == id } ?: return false
        // A reclaim job that has begun has already consumed archive bytes.
        // Removing it would hide the only map of what remains. Keep it.
        if (job.reclaim && job.state != JobState.COMPLETED && job.state != JobState.QUEUED) {
            return false
        }
        cleanupOwnedSource(job)
        store.clearArtifacts(id)
        _jobs.update { it.filterNot { j -> j.id == id } }
        store.save(_jobs.value)
        passwords.remove(id)
        return true
    }

    fun clearFinished() {
        val finished = _jobs.value.filter { it.state == JobState.COMPLETED }
        finished.forEach(::cleanupOwnedSource)
        _jobs.update { list -> list.filterNot { it in finished } }
        store.save(_jobs.value)
    }

    fun reclaimableJobs(): List<ExtractionJob> = _jobs.value.filter { it.reclaim }

    private fun ensurePump() {
        scope.launch {
            pumpMutex.withLock {
                active.set(true)
                try {
                    while (true) {
                        val next = _jobs.value
                            .filter { it.state == JobState.QUEUED }
                            .minByOrNull { it.createdAt } ?: break
                        val shouldContinue = runJob(next)
                        if (!shouldContinue) break
                    }
                } finally {
                    active.set(false)
                }
            }
        }
    }

    private suspend fun runJob(job: ExtractionJob): Boolean {
        if (_jobs.value.firstOrNull { it.id == job.id }?.state != JobState.QUEUED) return true
        activeJobId = job.id
        mutate(job.id) {
            it.copy(state = JobState.RUNNING, message = "Reading archive", updatedAt = now())
        }
        acquireWakeLock()
        ExtractionService.start(context)

        var handle = 0L
        var journal: JobJournal? = null
        var archive: com.hatchstudio.archive.core.OpenedArchive? = null
        try {
            val source = sourceFor(job)
            val password = passwords[job.id] ?: vault.get(job.archiveName)
            val destTreeUri = Uri.parse(job.destinationTreeUri)
            val tree = SafTree(context, destTreeUri)
            try {
                tree.list(tree.rootDocumentId, refresh = true)
            } catch (e: SecurityException) {
                throw ArchiveFailure(
                    "E_SAF",
                    "Hatch lost access to the destination folder. Choose it again.",
                    e.message,
                    e,
                )
            }
            val planFile = if (job.reclaim) store.planFile(job.id) else null
            val opened = ArchiveOpener.open(
                context = context,
                source = source,
                password = password,
                mode = if (job.reclaim) NativeEngine.MODE_RECLAIM else NativeEngine.MODE_SMART,
                planFile = planFile,
                multipart = job.multipart,
            )
            archive = opened
            handle = opened.handle
            activeHandle = handle
            archive = opened
            if (job.totalEntries <= 0) {
                val batchBytes = opened.entries.filter { !it.isDirectory }.sumOf { it.size }
                mutate(job.id) {
                    it.copy(
                        totalEntries = opened.entries.size,
                        totalBytes = batchBytes,
                        format = opened.info.format,
                        message = "Extracting",
                        updatedAt = now(),
                    )
                }
            } else {
                mutate(job.id) { it.copy(message = "Extracting", updatedAt = now()) }
            }

            val done = store.loadDone(job.id)
            journal = JobJournal(store.doneFile(job.id))
            val completedSet = journal.completed
            val selectedBase = if (job.reclaim) {
                opened.entries.indices.toList()
            } else {
                job.selected ?: opened.entries.indices.toList()
            }
            if (!job.reclaim && selectedBase.any { it !in opened.entries.indices }) {
                throw ArchiveFailure(
                    "E_CORRUPT",
                    "The archive no longer matches this job's file selection. Open it again from Files.",
                )
            }
            val selectedNow = selectedBase.filter { it !in completedSet && it in opened.entries.indices }
            // Reclaim must account for every uncommitted entry. Smart can skip
            // unsafe/link entries, which the native engine refuses to write.
            val finalSelected = if (job.reclaim) {
                opened.entries.indices.filter { it !in completedSet }
            } else {
                selectedNow
            }
            if (job.reclaim && completedSet.isNotEmpty()) {
                val missing = opened.entries.indices.filter { it !in completedSet && it !in finalSelected }
                if (missing.isNotEmpty()) {
                    throw ArchiveFailure(
                        "E_CORRUPT",
                        "The reclaim journal is incomplete. Remaining entries cannot be safely mapped.",
                    )
                }
            }
            if (finalSelected.isEmpty()) {
                finishSuccess(job, opened, journal)
                return true
            }

            val sink = SafExtractionSink(
                tree = tree,
                destinationRootId = tree.rootDocumentId,
                outputFolder = job.outputFolder,
                conflict = job.conflict,
                journal = journal,
                syncJournal = job.reclaim,
                onProgress = { count, bytes ->
                    updateProgress(job.id, count, bytes)
                },
            )
            nativeCallActive = true
            val result = try {
                NativeEngine.extract(
                    handle = handle,
                    selected = finalSelected.toIntArray(),
                    completed = completedSet.toIntArray(),
                    password = password,
                    sink = sink.sink,
                )
            } finally {
                nativeCallActive = false
            }
            if (result == 2) {
                mutate(job.id) {
                    it.copy(
                        state = JobState.PAUSED,
                        completedEntries = sink.completedEntries,
                        completedBytes = sink.completedBytes,
                        message = "Stopped. You can resume this job.",
                        updatedAt = now(),
                    )
                }
                ExtractionService.stop(context)
                return false
            }

            if (result != 0) {
                throw ArchiveFailure("E_IO", sink.lastError ?: "Extraction did not finish.")
            }
            finishSuccess(job, opened, journal)
            return true
        } catch (failure: ArchiveFailure) {
            Log.w(TAG, "Job ${job.archiveName} failed", failure)
            journal?.close()
            journal = null
            val current = job(job.id) ?: job
            val state = if (failure.needsPassword) JobState.NEEDS_PASSWORD else JobState.FAILED
            mutate(job.id) {
                it.copy(
                    state = state,
                    message = failure.userMessage,
                    completedEntries = current.completedEntries,
                    completedBytes = current.completedBytes,
                    updatedAt = now(),
                )
            }
            releaseWakeLock()
            ExtractionService.stop(context)
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected extraction failure", e)
            journal?.close()
            journal = null
            mutate(job.id) {
                it.copy(
                    state = JobState.FAILED,
                    message = e.message ?: "Extraction failed.",
                    updatedAt = now(),
                )
            }
            releaseWakeLock()
            ExtractionService.stop(context)
            return false
        } finally {
            runCatching { NativeEngine.close(handle) }
            activeHandle = 0L
            nativeCallActive = false
            archive?.close()
            if (activeJobId == job.id) activeJobId = null
            journal?.close()
            releaseWakeLock()
        }
    }

    private fun finishSuccess(job: ExtractionJob, opened: com.hatchstudio.archive.core.OpenedArchive, journal: JobJournal) {
        val source = opened.source
        mutate(job.id) {
            it.copy(
                state = JobState.COMPLETED,
                completedEntries = it.totalEntries,
                completedBytes = it.totalBytes,
                message = if (it.reclaim) "Archive reclaimed" else "Extracted",
                updatedAt = now(),
            )
        }
        store.clearArtifacts(job.id)
        if (job.reclaim) {
            // DocumentsContract is the supported delete path for SAF documents;
            // ContentResolver.delete only works when the provider implements it.
            source.uri?.let { uri ->
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
                }.onFailure {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
            source.file?.delete()
        }
        cleanupOwnedSource(job)
        passwords.remove(job.id)
        _jobs.update { current ->
            current.filterNot { it.id != job.id && it.state == JobState.COMPLETED && it.updatedAt < now() - 7L * 24 * 3600 * 1000 }
        }
        store.save(_jobs.value)
        ExtractionService.stop(context)
    }

    private fun updateProgress(jobId: String, entries: Int, bytes: Long) {
        _jobs.update { current ->
            current.map {
                if (it.id == jobId) {
                    it.copy(
                        completedEntries = maxOf(it.completedEntries, entries),
                        completedBytes = maxOf(it.completedBytes, bytes),
                        state = if (it.state == JobState.QUEUED) JobState.RUNNING else it.state,
                        updatedAt = now(),
                    )
                } else it
            }
        }
        val now = now()
        if (now - lastSaveAt > 1200) {
            lastSaveAt = now
            store.save(_jobs.value)
        }
    }

    private fun mutate(id: String, transform: (ExtractionJob) -> ExtractionJob) {
        _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
        store.save(_jobs.value)
    }

    private fun sourceFor(job: ExtractionJob): ArchiveSource {
        val file = job.sourcePath?.let(::File)
        val uri = job.sourceUri?.let(Uri::parse)
        val owned = job.ownedSourcePath?.let(::File)
        return ArchiveSource(
            displayName = job.archiveName,
            size = job.archiveSize,
            uri = uri,
            file = file,
            multipart = job.multipart,
            ownedPath = owned,
        )
    }

    private fun cleanupOwnedSource(job: ExtractionJob) {
        job.ownedSourcePath?.let { path ->
            val file = File(path)
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
    }

    private fun acquireWakeLock() {
        if (!settings.state.value.keepAwake) return
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hatch:extraction").apply {
            setReferenceCounted(false)
            acquire(6L * 60 * 60 * 1000)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val TAG = "HatchJobs"
        @Volatile private var instance: JobManager? = null

        fun init(context: Context): JobManager =
            instance ?: synchronized(this) {
                instance ?: JobManager(context.applicationContext).also { instance = it }
            }

        fun get(): JobManager? = instance
    }
}
