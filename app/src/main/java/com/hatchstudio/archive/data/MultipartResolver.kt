package com.hatchstudio.archive.data

import android.content.Context
import com.hatchstudio.archive.core.ArchiveSource
import net.lingala.zip4j.ZipFile
import java.io.File
import java.io.FileOutputStream

/**
 * SAF exposes one document at a time, so split archives cannot be opened by
 * name. This resolver finds sibling parts through the tree permission and
 * reassembles them in private cache storage. Extracted files still go straight
 * to the user's destination, but the source object is staged.
 */
object MultipartResolver {
    private val zipPart = Regex("(?i)^(.*)\\.(z(\\d{2})|zip)$")
    private val sevenZipPart = Regex("(?i)^(.*)\\.7z\\.(\\d{3,})$")
    private val newRarPart = Regex("(?i)^(.*)\\.part(\\d+)\\.rar$")
    private val oldRarPart = Regex("(?i)^(.*)\\.r(\\d{2})$")

    fun prepare(context: Context, tree: SafTree, node: SafNode): ArchiveSource? {
        if (node.isDirectory) return null
        val siblings = siblingsOf(tree, node) ?: return null
        val result = when {
            zipPart.matches(node.name) || node.extension == "zip" -> prepareZip(context, tree, node, siblings)
            sevenZipPart.matches(node.name) || node.name.endsWith(".7z.001", true) ->
                prepareSevenZip(context, tree, node, siblings)
            newRarPart.matches(node.name) || oldRarPart.matches(node.name) || node.extension == "rar" ->
                prepareRar(context, tree, node, siblings)
            else -> null
        }
        return result
    }

    private fun siblingsOf(tree: SafTree, node: SafNode): List<SafNode>? {
        val parent = node.documentId.substringBeforeLast('/', "")
        if (parent.isBlank()) return null
        return runCatching { tree.list(parent, refresh = true) }.getOrNull()
    }

    private fun prepareZip(
        context: Context,
        tree: SafTree,
        node: SafNode,
        siblings: List<SafNode>,
    ): ArchiveSource? {
        val match = zipPart.matchEntire(node.name) ?: return null
        val base = match.groupValues[1]
        val matching = siblings.filter { s ->
            val m = zipPart.matchEntire(s.name) ?: return@filter false
            m.groupValues[1].equals(base, ignoreCase = true)
        }
        // Only .z01/.z02/... are split parts. "zip" also starts with z and is
        // three characters long, so a plain ZIP used to be concatenated with
        // itself here: a wasted full-size copy, a doubled archive size and a
        // reclaim job that could only delete the staging copy.
        val sequential = matching
            .filter { part ->
                val ext = part.extension
                ext.length == 3 && ext[0] == 'z' && ext[1].isDigit() && ext[2].isDigit()
            }
            .sortedBy { it.extension.drop(1).toIntOrNull() ?: 0 }
        val tail = matching.firstOrNull { it.extension.equals("zip", true) }
        if (tail == null || sequential.isEmpty()) return null

        val dir = stageDir(context)
        val staged = ArrayList<File>()
        for (part in sequential + tail) {
            val input = tree.openRead(part)?.let { java.io.FileInputStream(it.fileDescriptor) }
                ?: continue
            val copy = File(dir, part.name)
            input.use { source ->
                copy.outputStream().use { output -> source.copyTo(output, 1024 * 1024) }
            }
            staged += copy
        }
        val target = File(dir, "joined-" + base + ".zip")
        FileOutputStream(target).use { output ->
            staged.forEach { part ->
                part.inputStream().use { it.copyTo(output, 1024 * 1024) }
            }
            output.fd.sync()
        }
        // Standard split volumes use offsets relative to the disk a local
        // header starts on. Rewrite them in the joined private copy so the
        // Rust seek-based reader sees one ordinary ZIP. Encrypted entries are
        // preserved byte-for-byte; no password is needed here.
        val splitLength = sequential.firstOrNull { it.name.endsWith(".z01", true) }?.size
            ?: sequential.firstOrNull()?.size
            ?: 0L
        val normalized = splitLength > 0 && ZipSplitNormalizer.normalize(target, splitLength)
        if (!normalized) {
            // Fallback for unusual split sets (for example Zip64). Zip4j can
            // merge many of them; a plain concatenation remains the last
            // resort for simple byte-split archives.
            val tailFile = staged.firstOrNull { it.name.equals(tail.name, ignoreCase = true) }
            val merged = File(dir, "merged-" + base + ".zip")
            val mergedOk = tailFile != null && runCatching {
                ZipFile(tailFile).mergeSplitFiles(merged)
                merged.isFile && merged.length() > 0
            }.getOrDefault(false)
            if (mergedOk) {
                return ArchiveSource(
                    displayName = base + ".zip",
                    size = merged.length(),
                    file = merged,
                    multipart = true,
                    ownedPath = dir,
                )
            }
        }
        return ArchiveSource(
            displayName = base + ".zip",
            size = target.length(),
            file = target,
            multipart = true,
            ownedPath = dir,
        )
    }

    private fun prepareSevenZip(
        context: Context,
        tree: SafTree,
        node: SafNode,
        siblings: List<SafNode>,
    ): ArchiveSource? {
        val match = sevenZipPart.matchEntire(node.name) ?: return null
        val base = match.groupValues[1]
        val matching = siblings.filter { s ->
            val m = sevenZipPart.matchEntire(s.name) ?: return@filter false
            m.groupValues[1].equals(base, ignoreCase = true)
        }.sortedBy { sevenZipPart.matchEntire(it.name)?.groupValues?.get(2)?.toIntOrNull() ?: 0 }
        if (matching.size < 2) return null
        val dir = stageDir(context)
        val target = File(dir, base + ".7z")
        FileOutputStream(target).use { output ->
            for (part in matching) {
                val input = tree.openRead(part)?.let { java.io.FileInputStream(it.fileDescriptor) }
                    ?: continue
                input.use { it.copyTo(output, 1024 * 1024) }
            }
            output.fd.sync()
        }
        return ArchiveSource(
            displayName = base + ".7z",
            size = target.length(),
            file = target,
            multipart = true,
            ownedPath = dir,
        )
    }

    private fun prepareRar(
        context: Context,
        tree: SafTree,
        node: SafNode,
        siblings: List<SafNode>,
    ): ArchiveSource? {
        val newStyle = newRarPart.matchEntire(node.name)
        val oldStyle = oldRarPart.matchEntire(node.name)
        val base = when {
            newStyle != null -> newStyle.groupValues[1]
            oldStyle != null -> oldStyle.groupValues[1]
            node.name.endsWith(".rar", true) -> node.name.dropLast(4)
            else -> return null
        }
        val matching = siblings.filter { s ->
            when {
                newStyle != null || node.name.endsWith(".rar", true) ->
                    newRarPart.matchEntire(s.name)?.groupValues?.get(1)?.equals(base, true) == true ||
                        s.name.equals("$base.rar", true)
                oldStyle != null ->
                    s.name.equals("$base.rar", true) ||
                        oldRarPart.matchEntire(s.name)?.groupValues?.get(1)?.equals(base, true) == true
                else -> false
            }
        }
        if (matching.size < 2) return null
        val ordered = matching.sortedWith(
            compareBy<SafNode> { s ->
                when {
                    s.name.equals("$base.rar", true) -> 0
                    else -> newRarPart.matchEntire(s.name)?.groupValues?.get(2)?.toIntOrNull()
                        ?: (oldRarPart.matchEntire(s.name)?.groupValues?.get(2)?.toIntOrNull()?.plus(1) ?: 999)
                }
            }.thenBy { it.name },
        )
        val first = ordered.firstOrNull() ?: return null
        val dir = stageDir(context)
        for (part in ordered) {
            val input = tree.openRead(part)?.let { java.io.FileInputStream(it.fileDescriptor) }
                ?: continue
            val target = File(dir, part.name)
            input.use { source ->
                target.outputStream().use { out -> source.copyTo(out, 1024 * 1024) }
            }
        }
        val firstCopy = File(dir, first.name)
        if (!firstCopy.exists()) return null
        return ArchiveSource(
            displayName = if (base.endsWith(".rar", true)) base else "$base.rar",
            size = ordered.sumOf { it.size },
            file = firstCopy,
            multipart = true,
            ownedPath = dir,
        )
    }

    private fun stageDir(context: Context): File {
        val root = File(context.cacheDir, "hatch/staged").apply { mkdirs() }
        return File(root, "set-${System.currentTimeMillis()}").apply { mkdirs() }
    }

    fun discard(source: ArchiveSource) {
        source.ownedPath?.let { owned ->
            if (owned.isDirectory) owned.deleteRecursively() else owned.delete()
        }
    }
}
