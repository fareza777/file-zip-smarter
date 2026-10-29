package com.hatchstudio.archive.data

import android.content.Context
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.Zip4jConfig
import net.lingala.zip4j.model.ZipModel
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.AesVersion
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class ZipCompression(val label: String) {
    STORE("Store"),
    BALANCED("Balanced"),
    MAXIMUM("Max"),
}

/**
 * Builds a standards-compliant ZIP (optional AES-256 password, optional split
 * volumes) from SAF nodes. The archive is written to private cache first so a
 * failed write can never leave a half-archive in the user's folder, then each
 * completed part is copied through the destination tree.
 */
object ZipCreator {
    data class Request(
        val baseName: String,
        val password: String?,
        val compression: ZipCompression = ZipCompression.BALANCED,
        val splitBytes: Long,
        val destinationTreeUri: String,
        val destinationDocId: String,
        val nodes: List<SafNode>,
    )

    data class Result(
        val files: List<String>,
        val bytes: Long,
        val entries: Int,
    )

    private sealed interface Item {
        val path: String

        data class Folder(override val path: String) : Item
        data class FileItem(
            override val path: String,
            val node: SafNode,
        ) : Item
    }

    fun scan(source: SafTree, nodes: List<SafNode>): Pair<Int, Long> {
        var files = 0
        var bytes = 0L
        fun walk(node: SafNode) {
            if (node.isDirectory) {
                runCatching { source.list(node.documentId, refresh = true) }
                    .getOrDefault(emptyList())
                    .forEach(::walk)
            } else {
                files++
                bytes += node.size
            }
        }
        nodes.forEach(::walk)
        return files to bytes
    }

    fun create(
        context: Context,
        source: SafTree,
        destination: SafTree,
        request: Request,
        onProgress: (done: Int, total: Int, bytes: Long) -> Unit,
    ): Result {
        val items = collect(source, request.nodes)
        if (items.isEmpty()) {
            throw IOException("There is nothing selected to zip.")
        }
        val work = File(context.cacheDir, "hatch/zips/${System.currentTimeMillis()}")
        if (!work.mkdirs() && !work.isDirectory) {
            throw IOException("ZipHatch could not create a temporary workspace.")
        }
        try {
            val base = sanitizeZipName(request.baseName)
            val output = File(work, base)
            if (request.splitBytes > 0 && output.exists()) output.delete()
            writeArchive(source, output, items, request, onProgress)
            val parts = archiveParts(work, output)
            if (parts.isEmpty()) throw IOException("The ZIP was not written.")
            val unique = uniqueBaseName(destination, request.destinationDocId, base)
            val copied = ArrayList<String>()
            try {
                parts.forEach { part ->
                    val name = partNameFor(part, base, unique)
                    val target = destination.findOrCreateFile(
                        request.destinationDocId,
                        name,
                        "application/zip",
                        keepBoth = false,
                    )
                    val pfd = destination.openWrite(target)
                    try {
                        FileInputStream(part).use { input ->
                            FileOutputStream(pfd.fileDescriptor).use { out ->
                                input.copyTo(out, 1024 * 1024)
                                out.fd.sync()
                            }
                        }
                    } finally {
                        runCatching { pfd.close() }
                    }
                    copied += target.name
                }
            } catch (error: Throwable) {
                copied.forEach { name ->
                    destination.child(request.destinationDocId, name, refresh = true)
                        ?.let { destination.delete(it) }
                }
                throw error
            }
            return Result(
                files = copied,
                bytes = parts.sumOf { it.length() },
                entries = items.count { it is Item.FileItem },
            )
        } finally {
            work.deleteRecursively()
        }
    }

    private fun writeArchive(
        source: SafTree,
        output: File,
        items: List<Item>,
        request: Request,
        onProgress: (Int, Int, Long) -> Unit,
    ) {
        val password = request.password?.takeIf { it.isNotBlank() }?.toCharArray()
        val split = request.splitBytes.coerceAtLeast(0)
        val raw = if (split > 0) {
            SplitOutputStream(output, split)
        } else {
            FileOutputStream(output)
        }
        val zip = if (split > 0 && raw is SplitOutputStream) {
            // The ZipModel must know about the split layout or the end record
            // will claim a single disk and downstream readers cannot merge it.
            val model = ZipModel().apply {
                setZipFile(output)
                setSplitArchive(true)
                setSplitLength(split)
            }
            ZipOutputStream(
                raw,
                password,
                Zip4jConfig(StandardCharsets.UTF_8, 4096, false),
                model,
            )
        } else {
            ZipOutputStream(raw, password)
        }
        zip.use { zip ->
            var done = 0
            var written = 0L
            for (item in items) {
                val params = ZipParameters().apply {
                    compressionMethod = if (request.compression == ZipCompression.STORE) {
                        CompressionMethod.STORE
                    } else {
                        CompressionMethod.DEFLATE
                    }
                    compressionLevel = when (request.compression) {
                        ZipCompression.STORE -> CompressionLevel.NORMAL
                        ZipCompression.BALANCED -> CompressionLevel.NORMAL
                        ZipCompression.MAXIMUM -> CompressionLevel.MAXIMUM
                    }
                    if (password != null) {
                        isEncryptFiles = true
                        encryptionMethod = EncryptionMethod.AES
                        aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                        aesVersion = AesVersion.TWO
                    }
                }
                when (item) {
                    is Item.Folder -> {
                        params.fileNameInZip = item.path.trimEnd('/') + "/"
                        zip.putNextEntry(params)
                        zip.closeEntry()
                    }
                    is Item.FileItem -> {
                        params.fileNameInZip = item.path
                        zip.putNextEntry(params)
                        val pfd = source.openRead(item.node)
                            ?: throw IOException("Could not read ${item.node.name}.")
                        try {
                            FileInputStream(pfd.fileDescriptor).use { input ->
                                val buffer = ByteArray(1024 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    zip.write(buffer, 0, read)
                                    written += read
                                }
                            }
                        } finally {
                            runCatching { pfd.close() }
                        }
                        zip.closeEntry()
                    }
                }
                done++
                onProgress(done, items.size, written)
            }
        }
    }

    private fun collect(source: SafTree, nodes: List<SafNode>): List<Item> {
        val result = ArrayList<Item>()
        val seen = HashSet<String>()
        fun add(node: SafNode, path: String) {
            if (!seen.add(path.lowercase(Locale.ROOT))) return
            if (node.isDirectory) {
                result += Item.Folder(path)
                val children = runCatching { source.list(node.documentId, refresh = true) }
                    .getOrDefault(emptyList())
                children.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    .forEach { child -> add(child, "$path/${child.name}") }
            } else {
                result += Item.FileItem(path, node)
            }
        }
        nodes.forEach { add(it, it.name) }
        return result
    }

    private fun archiveParts(work: File, output: File): List<File> {
        val base = output.name
        val stem = base.removeSuffix(".zip")
        return work.listFiles()
            ?.filter { file ->
                file.name == base ||
                    file.name.matches(Regex("^${Regex.escape(stem)}\\.z\\d{2,}$", RegexOption.IGNORE_CASE))
            }
            ?.sortedWith(compareBy<File> { part -> part.name.substringAfterLast(".z").toIntOrNull() ?: Int.MAX_VALUE })
            ?: emptyList()
    }

    private fun partNameFor(part: File, base: String, unique: String): String {
        if (part.name == base) return unique
        val suffix = part.name.substringAfter(".z", "")
        return "${unique.removeSuffix(".zip")}.z$suffix"
    }

    private fun uniqueBaseName(tree: SafTree, parentId: String, name: String): String {
        val stem = name.removeSuffix(".zip")
        val existing = runCatching { tree.list(parentId, refresh = true).map { it.name.lowercase() } }
            .getOrDefault(emptyList())
            .toSet()
        for (index in 0..999) {
            val candidate = if (index == 0) "$stem.zip" else "$stem ($index).zip"
            val candidateStem = candidate.removeSuffix(".zip")
            val collision = candidate.lowercase() in existing ||
                (1..99).any { ("$candidateStem.z${it.toString().padStart(2, '0')}").lowercase() in existing }
            if (!collision) return candidate
        }
        return "$stem-${System.currentTimeMillis()}.zip"
    }

    fun sanitizeZipName(value: String): String {
        var base = value.trim().ifBlank { "ZipHatch.zip" }
        base = base.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()
        if (!base.endsWith(".zip", ignoreCase = true)) base += ".zip"
        return base.take(120).ifBlank { "ZipHatch.zip" }
    }

    fun splitLabel(bytes: Long): String = when {
        bytes <= 0 -> "Single file"
        bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
        else -> "${bytes / (1024L * 1024)} MB"
    }
}
