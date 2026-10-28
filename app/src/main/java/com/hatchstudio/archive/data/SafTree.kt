package com.hatchstudio.archive.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
import android.webkit.MimeTypeMap
import androidx.annotation.Keep
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

@Keep
data class SafNode(
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
    val uri: Uri,
) {
    val isDirectory: Boolean
        get() = mimeType == Document.MIME_TYPE_DIR

    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isArchive: Boolean
        get() = extension in ARCHIVE_EXTENSIONS

    val isSplitPart: Boolean
        get() = SPLIT_PATTERN.matches(name)

    companion object {
        val ARCHIVE_EXTENSIONS = setOf(
            "zip", "rar", "7z", "tar", "gz", "tgz", "z01", "001", "r00", "part1",
        )
        private val SPLIT_PATTERN = Regex(
            "(?i).+(\\.(z\\d{2}|r\\d{2}|\\d{3})|\\.part\\d+\\.rar)$",
        )
    }
}

/**
 * Thin, cached wrapper around a persisted DocumentsContract tree. Every write
 * goes through the provider, so it stays inside the permission the user gave.
 */
class SafTree(
    context: Context,
    val treeUri: Uri,
) {
    private val resolver: ContentResolver = context.contentResolver
    val rootDocumentId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val childCache = ConcurrentHashMap<String, List<SafNode>>()
    private val rootUri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId)

    fun displayName(): String = runCatching {
        queryDocument(rootDocumentId)?.name
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Landing folder"

    fun list(parentId: String, refresh: Boolean = false): List<SafNode> {
        if (!refresh) childCache[parentId]?.let { return it }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val result = ArrayList<SafNode>()
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: "application/octet-stream"
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
                result += SafNode(
                    documentId = id,
                    name = name,
                    mimeType = mime,
                    size = size,
                    modified = modified,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                )
            }
        }
        result.sortWith(
            compareByDescending<SafNode> { it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
        childCache[parentId] = result
        return result
    }

    fun queryDocument(documentId: String): SafNode? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return SafNode(
                documentId = cursor.getString(0) ?: documentId,
                name = cursor.getString(1) ?: "Folder",
                mimeType = cursor.getString(2) ?: Document.MIME_TYPE_DIR,
                size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                modified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                uri = uri,
            )
        }
        return null
    }

    fun child(parentId: String, name: String, refresh: Boolean = false): SafNode? {
        return list(parentId, refresh).firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    fun createDirectory(parentId: String, name: String): SafNode {
        val existing = child(parentId, name, refresh = true)
        if (existing?.isDirectory == true) return existing
        if (existing != null) {
            // A file occupies the directory name; release it before creating.
            runCatching { DocumentsContract.deleteDocument(resolver, existing.uri) }
        }
        val uri = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
            Document.MIME_TYPE_DIR,
            name,
        ) ?: throw FileNotFoundException("The folder could not be created: $name")
        val id = DocumentsContract.getDocumentId(uri)
        invalidate(parentId)
        return SafNode(id, name, Document.MIME_TYPE_DIR, 0, System.currentTimeMillis(), uri)
    }

    fun ensureDirectories(baseParentId: String, relativePath: String): String {
        var parent = baseParentId
        if (relativePath.isBlank()) return parent
        for (segment in relativePath.split('/')) {
            if (segment.isBlank()) continue
            parent = createDirectory(parent, segment).documentId
        }
        return parent
    }

    fun findOrCreateFile(
        parentId: String,
        name: String,
        mimeType: String,
        keepBoth: Boolean,
    ): SafNode {
        val existing = child(parentId, name, refresh = true)
        if (existing != null && !existing.isDirectory && !keepBoth) {
            return existing
        }
        if (existing != null && existing.isDirectory) {
            runCatching { DocumentsContract.deleteDocument(resolver, existing.uri) }
        }
        var candidate = name
        if (keepBoth && (existing != null || child(parentId, candidate, true) != null)) {
            candidate = uniqueName(parentId, name)
        }
        val uri = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
            mimeType,
            candidate,
        ) ?: throw FileNotFoundException("The file could not be created: $candidate")
        invalidate(parentId)
        return SafNode(
            documentId = DocumentsContract.getDocumentId(uri),
            name = candidate,
            mimeType = mimeType,
            size = 0,
            modified = System.currentTimeMillis(),
            uri = uri,
        )
    }

    private fun uniqueName(parentId: String, name: String): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..999) {
            val candidate = "$stem ($i)$ext"
            if (child(parentId, candidate, true) == null) return candidate
        }
        return "$stem-${System.currentTimeMillis()}$ext"
    }

    fun delete(node: SafNode): Boolean {
        val parent = node.documentId.substringBeforeLast('/', "")
        val ok = runCatching { DocumentsContract.deleteDocument(resolver, node.uri) }.getOrDefault(false)
        if (parent.isNotBlank()) invalidate(parent)
        return ok
    }

    fun rename(node: SafNode, newName: String): SafNode? {
        val uri = runCatching {
            DocumentsContract.renameDocument(resolver, node.uri, newName)
        }.getOrNull() ?: return null
        val parent = node.documentId.substringBeforeLast('/', "")
        if (parent.isNotBlank()) invalidate(parent)
        return queryDocument(DocumentsContract.getDocumentId(uri))
    }

    fun openWrite(node: SafNode): android.os.ParcelFileDescriptor {
        // "wt" truncates where supported. Providers that reject it fall back to
        // rw plus an explicit ftruncate.
        val truncated = runCatching { resolver.openFileDescriptor(node.uri, "wt") }.getOrNull()
        if (truncated != null) return truncated
        val pfd = resolver.openFileDescriptor(node.uri, "rw")
            ?: throw FileNotFoundException("This provider does not allow writing ${node.name}.")
        runCatching { Os.ftruncate(pfd.fileDescriptor, 0) }
        return pfd
    }

    fun openRead(node: SafNode): android.os.ParcelFileDescriptor? =
        resolver.openFileDescriptor(node.uri, "r")

    fun invalidate(parentId: String? = null) {
        if (parentId == null) childCache.clear() else childCache.remove(parentId)
    }

    fun breadcrumbs(currentId: String): List<SafNode> {
        val chain = ArrayList<SafNode>()
        var id = currentId
        val root = queryDocument(rootDocumentId)
        while (true) {
            val node = queryDocument(id) ?: break
            chain.add(0, node)
            if (id == rootDocumentId) break
            id = id.substringBeforeLast('/', "")
            if (id.isBlank()) break
        }
        if (chain.isEmpty() && root != null) chain.add(root)
        return chain
    }

    fun copyToFile(node: SafNode, target: java.io.File): Long {
        val input = resolver.openInputStream(node.uri)
            ?: throw FileNotFoundException("Could not read ${node.name}")
        input.use { source ->
            target.outputStream().use { sink ->
                source.copyTo(sink, 1024 * 1024)
                (sink as? java.io.FileOutputStream)?.fd?.sync()
            }
        }
        return target.length()
    }

    companion object {
        fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }

        fun archiveKind(name: String): String? {
            val lower = name.lowercase()
            return when {
                lower.endsWith(".zip") || lower.endsWith(".zipx") || Regex("\\.z\\d{2}$").containsMatchIn(lower) -> "ZIP"
                lower.endsWith(".rar") || lower.endsWith(".r00") -> "RAR"
                lower.endsWith(".7z") || Regex("\\.7z\\.\\d{3}$").containsMatchIn(lower) -> "7Z"
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> "TAR.GZ"
                lower.endsWith(".tar") -> "TAR"
                lower.endsWith(".gz") -> "GZIP"
                else -> null
            }
        }
    }
}
