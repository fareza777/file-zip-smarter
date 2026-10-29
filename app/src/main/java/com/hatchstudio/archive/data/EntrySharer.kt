package com.hatchstudio.archive.data

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
 * Stages a single archive entry in Hatch's private cache and hands it to the
 * Android share sheet. Nothing is written to the user's folder and the staged
 * copy is replaced on the next share.
 */
object EntrySharer {

    fun stage(context: Context, handle: Long, password: String?, entry: ArchiveEntry): File {
        val dir = File(context.cacheDir, "hatch/share").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        val name = ArchiveOpener.safeFileName(entry.displayName)
            .let { if (it == "." || it == "..") "archive-entry" else it }
        val target = File(dir, name)

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

    fun send(context: Context, file: File, mimeType: String): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull() ?: return false

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share ${file.name}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}
