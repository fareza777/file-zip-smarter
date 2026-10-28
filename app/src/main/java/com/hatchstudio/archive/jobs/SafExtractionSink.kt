package com.hatchstudio.archive.jobs

import android.util.Log
import com.hatchstudio.archive.core.NativeSink
import com.hatchstudio.archive.data.ConflictPolicy
import com.hatchstudio.archive.data.JobJournal
import com.hatchstudio.archive.data.SafTree
import java.io.FileNotFoundException

/**
 * Turns Hatch's native entry callbacks into DocumentsContract operations and
 * durable journal commits. Files are written from the Rust buffer straight into
 * the destination provider descriptor; no temporary copy of the entry exists.
 */
class SafExtractionSink(
    private val tree: SafTree,
    private val destinationRootId: String,
    private val outputFolder: String,
    private val conflict: ConflictPolicy,
    private val journal: JobJournal,
    private val syncJournal: Boolean,
    private val onProgress: (entries: Int, bytes: Long) -> Unit,
) {
    @Volatile
    var lastError: String? = null
        private set

    private val dirCache = HashMap<String, String>()
    private var baseFolderId: String? = null
    private var currentWritten = 0L
    private var baseBytes = journal.completedBytes

    val completedEntries: Int
        get() = journal.completed.size
    val completedBytes: Long
        get() = journal.completedBytes

    val sink: NativeSink = NativeSink(
        open = ::onEntryOpen,
        progress = ::onEntryProgress,
        committed = ::onEntryCommitted,
    )

    private fun ensureBaseFolder(): String {
        baseFolderId?.let { return it }
        val root = destinationRootId
        val folder = sanitizeSegment(outputFolder)
        val id = if (folder.isNullOrBlank()) {
            root
        } else {
            tree.ensureDirectories(root, folder)
        }
        baseFolderId = id
        return id
    }

    private fun ensureParent(relativeDir: String): String {
        val folder = sanitizeRelative(relativeDir)
        if (folder.isBlank()) return ensureBaseFolder()
        dirCache[folder]?.let { return it }
        var parent = ensureBaseFolder()
        var accumulated = ""
        for (segment in folder.split('/')) {
            if (segment.isBlank()) continue
            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
            val cached = dirCache[accumulated]
            if (cached != null) {
                parent = cached
            } else {
                val created = tree.createDirectory(parent, segment)
                dirCache[accumulated] = created.documentId
                parent = created.documentId
            }
        }
        return parent
    }

    private fun onEntryOpen(index: Int, path: String, isDirectory: Boolean): Int {
        try {
            if (isDirectory) {
                ensureParent(sanitizeRelative(path))
                currentWritten = 0
                return -1
            }
            val clean = sanitizeRelative(path)
            if (clean.isBlank()) {
                lastError = "An entry had an empty file name."
                return -3
            }
            val parentPath = clean.substringBeforeLast('/', "")
            val name = clean.substringAfterLast('/')
            val parentId = ensureParent(parentPath)
            val node = tree.findOrCreateFile(
                parentId = parentId,
                name = name,
                mimeType = SafTree.mimeFor(name),
                keepBoth = conflict == ConflictPolicy.KEEP_BOTH,
            )
            val pfd = tree.openWrite(node)
            currentWritten = 0
            return pfd.detachFd()
        } catch (e: SecurityException) {
            lastError = "Hatch lost write access to the destination folder. Choose it again."
            Log.w(TAG, "SAF permission failure", e)
            return -3
        } catch (e: FileNotFoundException) {
            lastError = "The destination provider could not create a file: ${e.message ?: "unknown error"}"
            Log.w(TAG, "SAF create failure", e)
            return -3
        } catch (e: Exception) {
            lastError = e.message ?: "The destination could not be written."
            Log.w(TAG, "SAF write failure", e)
            return -3
        }
    }

    private fun onEntryProgress(index: Int, bytes: Long, total: Long): Boolean {
        currentWritten = bytes
        onProgress(journal.completed.size, baseBytes + bytes)
        return true
    }

    private fun onEntryCommitted(index: Int, bytes: Long): Boolean {
        return try {
            journal.append(index, bytes, syncJournal)
            baseBytes = journal.completedBytes
            currentWritten = 0
            onProgress(journal.completed.size, baseBytes)
            true
        } catch (e: Exception) {
            lastError = "The extraction journal could not be saved. Hatch stopped before removing any archive data."
            Log.e(TAG, "Journal write failed", e)
            false
        }
    }

    private fun sanitizeRelative(path: String): String {
        return path.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { sanitizeSegment(it) ?: "item" }
    }

    private fun sanitizeSegment(value: String): String? {
        val cleaned = value.replace('/', '_').replace('\\', '_').trim().trim('.')
        if (cleaned.isBlank()) return null
        return cleaned.take(200)
    }

    companion object {
        private const val TAG = "HatchSink"
    }
}
