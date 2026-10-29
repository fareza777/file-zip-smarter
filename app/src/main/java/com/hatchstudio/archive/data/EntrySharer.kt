package com.hatchstudio.archive.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.hatchstudio.archive.core.ArchiveEntry
import com.hatchstudio.archive.core.ArchiveFailure
import com.hatchstudio.archive.core.ArchiveOpener
import com.hatchstudio.archive.core.NativeEngine
import com.hatchstudio.archive.core.NativeSink
import java.io.File

/**
 * Stages one or more archive entries in ZipHatch's private cache and hands them to
 * the Android share sheet. Nothing is written to the user's folder and the
 * staged copies are replaced on the next share.
 */
object EntrySharer {

    fun stage(context: Context, handle: Long, password: String?, entries: List<ArchiveEntry>): List<File> {
        if (entries.isEmpty()) throw ArchiveFailure("E_IO", "Choose at least one file to share.")
        val dir = File(context.cacheDir, "hatch/share").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        val staged = ArrayList<File>(entries.size)
        try {
            entries.forEach { entry ->
                staged += extractOne(handle, password, entry, dir)
            }
        } catch (error: Throwable) {
            staged.forEach { runCatching { it.delete() } }
            throw error
        }
        return staged
    }

    private fun extractOne(
        handle: Long,
        password: String?,
        entry: ArchiveEntry,
        dir: File,
    ): File {
        val name = ArchiveOpener.safeFileName(entry.displayName)
            .let { if (it == "." || it == "..") "archive-entry" else it }
        val target = uniqueTarget(dir, name)

        val sink = NativeSink(
            open = { _, _, isDirectory ->
                if (isDirectory) {
                    -1
                } else {
                    try {
                        ParcelFileDescriptor.open(
                            target,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_WRITE_ONLY or
                                ParcelFileDescriptor.MODE_TRUNCATE,
                        ).detachFd()
                    } catch (e: Exception) {
                        -3
                    }
                }
            },
            progress = { _, _, _ -> true },
            committed = { _, _ -> true },
        )

        val result = try {
            NativeEngine.extract(
                handle = handle,
                selected = intArrayOf(entry.index),
                completed = IntArray(0),
                password = password,
                sink = sink,
            )
        } catch (e: Exception) {
            throw ArchiveFailure.from(e)
        }

        if (result != 0 || !target.isFile) {
            runCatching { target.delete() }
            throw ArchiveFailure(
                "E_IO",
                "That file could not be prepared for sharing. Try extracting it instead.",
            )
        }
        return target
    }

    private fun uniqueTarget(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 2
        while (candidate.exists() && index < 100) {
            val suffix = if (ext.isBlank()) " ($index)" else " ($index).$ext"
            candidate = File(dir, "$stem$suffix")
            index++
        }
        return candidate
    }

    fun send(context: Context, files: List<File>, mimeTypes: List<String>): Boolean {
        if (files.isEmpty()) return false
        val uris = files.mapNotNull { file ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }.getOrNull()
        }
        if (uris.size != files.size) return false

        val mime = mimeTypes.distinct().singleOrNull() ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newUri(
            context.contentResolver,
            "ZipHatch",
            uris.first(),
        ).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }

        val label = if (files.size == 1) files.first().name else "${files.size} files"
        val chooser = Intent.createChooser(intent, "Share $label").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}
