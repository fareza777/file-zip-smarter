package com.hatchstudio.archive.core

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hatchstudio.archive.data.SafTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

data class ArchiveSource(
    val displayName: String,
    val size: Long = 0,
    val uri: Uri? = null,
    val file: File? = null,
    val treeUri: Uri? = null,
    val documentId: String? = null,
    val multipart: Boolean = false,
    val ownedPath: File? = null,
)

data class OpenedArchive(
    val handle: Long,
    val info: ArchiveInfo,
    val entries: List<ArchiveEntry>,
    val source: ArchiveSource,
    val canReclaim: Boolean,
    val canDeleteSource: Boolean,
    val loadedAt: Long = System.currentTimeMillis(),
) {
    fun close() {
        if (handle != 0L) runCatching { NativeEngine.close(handle) }
    }
}

object ArchiveOpener {
    private const val TAG = "HatchOpener"

    suspend fun open(
        context: Context,
        source: ArchiveSource,
        password: String?,
        mode: Int,
        planFile: File?,
        multipart: Boolean = source.multipart,
    ): OpenedArchive = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "hatch/work").apply { mkdirs() }
        try {
            openOnce(context, source, password, mode, planFile, multipart, tempDir)
        } catch (failure: ArchiveFailure) {
            // UnRAR needs a real path for volume discovery. If a direct SAF
            // descriptor cannot be used, make one private copy and retry once.
            val isRar = source.displayName.lowercase().let { it.endsWith(".rar") || it.endsWith(".r00") }
            if (isRar && source.uri != null && source.file == null && !failure.needsPassword) {
                Log.i(TAG, "Direct RAR open failed, staging a private copy", failure)
                val staged = stageSingle(context, source)
                val retried = source.copy(
                    file = staged,
                    uri = null,
                    multipart = multipart,
                    ownedPath = staged,
                )
                openOnce(context, retried, password, mode, planFile, multipart, tempDir)
            } else {
                throw failure
            }
        }
    }

    private fun openOnce(
        context: Context,
        source: ArchiveSource,
        password: String?,
        mode: Int,
        planFile: File?,
        multipart: Boolean,
        tempDir: File,
    ): OpenedArchive {
        var pfd: ParcelFileDescriptor? = null
        val handle: Long
        try {
            pfd = when {
                source.file != null -> {
                    if (!source.file.exists()) {
                        throw ArchiveFailure("E_IO", "That archive is no longer available.")
                    }
                    val flags = if (mode == NativeEngine.MODE_RECLAIM) {
                        ParcelFileDescriptor.MODE_READ_WRITE
                    } else {
                        ParcelFileDescriptor.MODE_READ_ONLY
                    }
                    ParcelFileDescriptor.open(source.file, flags)
                }
                source.uri != null -> {
                    val resolver = context.contentResolver
                    if (mode == NativeEngine.MODE_RECLAIM) {
                        try {
                            resolver.openFileDescriptor(source.uri, "rw")
                        } catch (e: Exception) {
                            throw ArchiveFailure(
                                "E_RECLAIM_UNAVAILABLE",
                                "This folder only allows read access to the archive. Smart extraction is still available.",
                                e.message,
                                e,
                            )
                        }
                    } else {
                        resolver.openFileDescriptor(source.uri, "r")
                    }
                }
                else -> throw ArchiveFailure("E_IO", "No archive was selected.")
            } ?: throw ArchiveFailure("E_IO", "The archive could not be opened.")
        } catch (failure: ArchiveFailure) {
            throw failure
        } catch (e: SecurityException) {
            throw ArchiveFailure(
                "E_IO",
                "ZipHatch lost permission to this archive. Pick it again.",
                e.message,
                e,
            )
        } catch (e: FileNotFoundException) {
            throw ArchiveFailure(
                "E_IO",
                "That archive has moved or been deleted.",
                e.message,
                e,
            )
        } catch (e: IOException) {
            throw ArchiveFailure("E_IO", "The archive could not be opened.", e.message, e)
        }

        val fd = pfd.detachFd()
        try {
            val planPath = if (mode == NativeEngine.MODE_RECLAIM) {
                planFile?.apply { parentFile?.mkdirs() }?.absolutePath
            } else {
                null
            }
            handle = NativeEngine.open(
                fd = fd,
                path = source.file?.absolutePath,
                displayName = source.displayName,
                password = password?.takeIf { it.isNotEmpty() },
                tempDir = tempDir.absolutePath,
                planPath = planPath,
                mode = mode,
                multipart = multipart,
            )
            if (handle <= 0L) {
                throw ArchiveFailure("E_INTERNAL", "The archive engine could not open this file.")
            }
        } catch (e: Throwable) {
            throw ArchiveFailure.from(e)
        }

        try {
            val info = NativeEngine.info(handle)
            val entries = NativeEngine.entries(handle)
            val writable = canOpenWritable(context, source)
            return OpenedArchive(
                handle = handle,
                info = info,
                entries = entries,
                source = source,
                canReclaim = info.reclaim.eligible && mode == NativeEngine.MODE_SMART && writable,
                canDeleteSource = writable && !source.multipart,
            )
        } catch (e: Throwable) {
            runCatching { NativeEngine.close(handle) }
            throw ArchiveFailure.from(e)
        }
    }

    private fun canOpenWritable(context: Context, source: ArchiveSource): Boolean {
        return try {
            if (source.file != null) {
                source.file.canWrite()
            } else {
                val uri = source.uri ?: return false
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    pfd.fileDescriptor.valid()
                } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun stageSingle(context: Context, source: ArchiveSource): File {
        val dir = File(context.cacheDir, "hatch/staged").apply { mkdirs() }
        val target = File(dir, "${System.currentTimeMillis()}-${safeFileName(source.displayName)}")
        val uri = source.uri ?: throw ArchiveFailure("E_IO", "No archive was selected.")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ArchiveFailure("E_IO", "This provider would not share the archive.")
        input.use { stream ->
            target.outputStream().use { output ->
                stream.copyTo(output, 1024 * 1024)
                (output as? java.io.FileOutputStream)?.fd?.sync()
            }
        }
        return target
    }

    fun safeFileName(name: String): String {
        val cleaned = name.replace('/', '_').replace('\\', '_').trim()
        return cleaned.take(120).ifBlank { "archive" }
    }

    suspend fun probeReclaimable(context: Context, source: ArchiveSource): Boolean =
        withContext(Dispatchers.IO) {
            canOpenWritable(context, source)
        }
}
