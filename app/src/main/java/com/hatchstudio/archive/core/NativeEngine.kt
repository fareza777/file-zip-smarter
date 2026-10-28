package com.hatchstudio.archive.core

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ArchiveFailure(
    val code: String,
    val userMessage: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : IOException(userMessage, cause) {
    val needsPassword: Boolean
        get() = code == "E_PASSWORD_REQUIRED" || code == "E_WRONG_PASSWORD"

    val wrongPassword: Boolean
        get() = code == "E_WRONG_PASSWORD"

    companion object {
        fun from(throwable: Throwable): ArchiveFailure {
            val raw = throwable.message.orEmpty()
            val lower = raw.lowercase()
            if (lower.contains("no space left") || lower.contains("enospc")) {
                return ArchiveFailure(
                    code = "E_NO_SPACE",
                    userMessage = "The destination ran out of space. Free some space, then resume.",
                    detail = raw,
                    cause = throwable,
                )
            }
            val parts = raw.split('|', limit = 3)
            if (parts.size >= 2 && parts[0].startsWith("E_")) {
                return ArchiveFailure(
                    code = parts[0],
                    userMessage = parts[1],
                    detail = parts.getOrNull(2),
                    cause = throwable,
                )
            }
            return ArchiveFailure(
                code = "E_IO",
                userMessage = raw.ifBlank { "The archive could not be opened." },
                cause = throwable,
            )
        }
    }
}

data class ArchiveEntry(
    val index: Int,
    val name: String,
    val path: String,
    val size: Long,
    val compressedSize: Long,
    val isDirectory: Boolean,
    val isEncrypted: Boolean,
    val isSymlink: Boolean,
    val unsafePath: Boolean,
    val modified: Long,
    val method: String,
    val crc32: Long?,
) {
    val displayName: String
        get() = name.trimEnd('/').substringAfterLast('/').ifBlank { name }

    val parent: String
        get() = path.substringBeforeLast('/', "")

    val extension: String
        get() = displayName.substringAfterLast('.', "").lowercase()

    val canExtract: Boolean
        get() = !unsafePath && !isSymlink

    val category: FileCategory
        get() {
            if (isDirectory) return FileCategory.Folder
            return when (extension) {
                "jpg", "jpeg", "png", "webp", "gif", "heic", "avif", "bmp", "svg" -> FileCategory.Image
                "mp4", "mkv", "mov", "webm", "avi", "m4v", "3gp" -> FileCategory.Video
                "mp3", "flac", "wav", "ogg", "m4a", "aac", "opus" -> FileCategory.Audio
                "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub" -> FileCategory.Document
                "apk", "zip", "rar", "7z", "tar", "gz", "xz", "zst" -> FileCategory.Archive
                else -> FileCategory.Other
            }
        }
}

enum class FileCategory(val label: String) {
    Folder("Folder"),
    Image("Image"),
    Video("Video"),
    Audio("Audio"),
    Document("Document"),
    Archive("Archive"),
    Other("File"),
}

data class ReclaimEligibility(
    val eligible: Boolean,
    val reason: String?,
)

data class ArchiveInfo(
    val format: String,
    val displayName: String,
    val archiveSize: Long,
    val entryCount: Int,
    val fileCount: Int,
    val directoryCount: Int,
    val totalSize: Long,
    val packedSize: Long,
    val encryptedCount: Int,
    val solid: Boolean,
    val multipart: Boolean,
    val passwordRequired: Boolean,
    val reclaim: ReclaimEligibility,
    val notes: List<String>,
)

object NativeEngine {
    const val MODE_SMART = 0
    const val MODE_RECLAIM = 1

    init {
        System.loadLibrary("hatch_core")
    }

    @JvmStatic
    private external fun nativeOpen(
        fd: Int,
        path: String?,
        displayName: String,
        password: String?,
        tempDir: String,
        planPath: String?,
        mode: Int,
        multipart: Boolean,
    ): Long

    @JvmStatic
    private external fun nativeClose(handle: Long)

    @JvmStatic
    private external fun nativeEntries(handle: Long): String

    @JvmStatic
    private external fun nativeInfo(handle: Long): String

    @JvmStatic
    private external fun nativePause(handle: Long, paused: Boolean)

    @JvmStatic
    private external fun nativeCancel(handle: Long)

    @JvmStatic
    private external fun nativeExtract(
        handle: Long,
        selected: IntArray,
        completed: IntArray,
        password: String?,
        sink: NativeSink,
    ): Int

    @JvmStatic
    private external fun nativePreview(
        handle: Long,
        index: Int,
        password: String?,
        tempDir: String,
        maxBytes: Long,
    ): ByteArray?

    @JvmStatic
    private external fun nativeVersion(): String

    fun open(
        fd: Int,
        path: String?,
        displayName: String,
        password: String?,
        tempDir: String,
        planPath: String?,
        mode: Int,
        multipart: Boolean,
    ): Long = nativeOpen(fd, path, displayName, password, tempDir, planPath, mode, multipart)

    fun close(handle: Long) = nativeClose(handle)

    fun entries(handle: Long): List<ArchiveEntry> =
        parseEntries(nativeEntries(handle))

    fun info(handle: Long): ArchiveInfo = parseInfo(nativeInfo(handle))

    fun pause(handle: Long, paused: Boolean) = nativePause(handle, paused)

    fun cancel(handle: Long) = nativeCancel(handle)

    fun extract(
        handle: Long,
        selected: IntArray,
        completed: IntArray,
        password: String?,
        sink: NativeSink,
    ): Int = nativeExtract(handle, selected, completed, password, sink)

    fun preview(
        handle: Long,
        index: Int,
        password: String?,
        tempDir: String,
        maxBytes: Long,
    ): ByteArray = nativePreview(handle, index, password, tempDir, maxBytes)
        ?: throw ArchiveFailure("E_IO", "That entry could not be read.")

    fun version(): String = runCatching { nativeVersion() }.getOrDefault("hatch-core")

    private fun parseEntries(json: String): List<ArchiveEntry> {
        val array = JSONArray(json)
        val result = ArrayList<ArchiveEntry>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            result += ArchiveEntry(
                index = o.getInt("index"),
                name = o.optString("name"),
                path = o.optString("path"),
                size = o.optLong("size"),
                compressedSize = o.optLong("compressedSize"),
                isDirectory = o.optBoolean("isDirectory"),
                isEncrypted = o.optBoolean("isEncrypted"),
                isSymlink = o.optBoolean("isSymlink"),
                unsafePath = o.optBoolean("unsafePath"),
                modified = o.optLong("modified"),
                method = o.optString("method"),
                crc32 = if (o.isNull("crc32")) null else o.optLong("crc32"),
            )
        }
        return result
    }

    private fun parseInfo(json: String): ArchiveInfo {
        val o = JSONObject(json)
        val reclaim = o.optJSONObject("reclaim")
        return ArchiveInfo(
            format = o.optString("format", "ARCHIVE"),
            displayName = o.optString("displayName"),
            archiveSize = o.optLong("archiveSize"),
            entryCount = o.optInt("entryCount"),
            fileCount = o.optInt("fileCount"),
            directoryCount = o.optInt("directoryCount"),
            totalSize = o.optLong("totalSize"),
            packedSize = o.optLong("packedSize"),
            encryptedCount = o.optInt("encryptedCount"),
            solid = o.optBoolean("solid"),
            multipart = o.optBoolean("multipart"),
            passwordRequired = o.optBoolean("passwordRequired"),
            reclaim = ReclaimEligibility(
                eligible = reclaim?.optBoolean("eligible") ?: false,
                reason = reclaim?.optString("reason")?.takeIf { it.isNotBlank() && it != "null" },
            ),
            notes = buildList {
                val notes = o.optJSONArray("notes") ?: JSONArray()
                for (i in 0 until notes.length()) add(notes.optString(i))
            },
        )
    }
}

@Keep
class NativeSink(
    private val open: (index: Int, path: String, isDirectory: Boolean) -> Int,
    private val progress: (index: Int, bytes: Long, total: Long) -> Boolean,
    private val committed: (index: Int, bytes: Long) -> Boolean,
) {
    @Keep
    fun onEntryOpen(index: Int, path: String, isDirectory: Boolean): Int =
        open(index, path, isDirectory)

    @Keep
    fun onEntryProgress(index: Int, bytes: Long, total: Long): Boolean =
        progress(index, bytes, total)

    @Keep
    fun onEntryCommitted(index: Int, bytes: Long): Boolean =
        committed(index, bytes)
}
