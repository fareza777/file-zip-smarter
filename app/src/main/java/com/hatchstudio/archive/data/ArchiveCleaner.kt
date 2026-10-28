package com.hatchstudio.archive.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * One archive that Hatch can unpack and then remove. The reported saving is the
 * archive's own size: extracted files are kept, so this is the storage the user
 * gets back once the source is gone.
 */
data class CleanerItem(
    val node: SafNode,
    val folder: String,
    val size: Long,
) {
    val id: String
        get() = node.uri.toString()
}

data class CleanerScan(
    val folderLabel: String,
    val items: List<CleanerItem>,
    val foldersScanned: Int,
    val truncated: Boolean,
)

/**
 * Walks the landing tree and collects whole archives. Split sets are skipped
 * because removing one volume would break the rest of the set.
 */
object ArchiveCleaner {
    const val MAX_ARCHIVES = 300
    const val MAX_FOLDERS = 400
    private const val MAX_DEPTH = 6

    private val splitPart = Regex(
        "(?i).+(\\.(z\\d{2}|r\\d{2}|\\d{3})|\\.part\\d+\\.rar|\\.7z\\.\\d{3,})$",
    )

    fun isWholeArchive(name: String): Boolean =
        Archives.isArchiveName(name) && !splitPart.matches(name)

    suspend fun scan(
        tree: SafTree,
        onProgress: (folders: Int, found: Int) -> Unit = { _, _ -> },
    ): CleanerScan = withContext(Dispatchers.IO) {
        val label = runCatching { tree.displayName() }.getOrDefault("Landing folder")
        val items = ArrayList<CleanerItem>()
        val folderLabels = HashMap<String, String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(tree.rootDocumentId to 0)
        var folders = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            coroutineContext.ensureActive()
            if (folders >= MAX_FOLDERS || items.size >= MAX_ARCHIVES) {
                truncated = true
                break
            }
            val (folderId, depth) = queue.removeFirst()
            folders++
            val nodes = runCatching { tree.list(folderId, refresh = true) }.getOrElse { emptyList() }
            val names = nodes.map { it.name.lowercase() }

            for (node in nodes) {
                if (node.isDirectory) {
                    if (depth < MAX_DEPTH) queue.add(node.documentId to depth + 1)
                    continue
                }
                if (!isWholeArchive(node.name)) continue
                val lower = node.name.lowercase()
                val stem = lower.substringBeforeLast('.')
                // A whole-name ZIP/RAR beside its numbered volumes is still a
                // split set; deleting the head would orphan the tail.
                if (lower.endsWith(".zip") && names.any { it == "$stem.z01" }) continue
                if (lower.endsWith(".rar") && names.any { it == "$stem.r00" }) continue
                val parentId = node.documentId.substringBeforeLast('/', "")
                val folder = folderLabels.getOrPut(parentId) {
                    if (parentId == tree.rootDocumentId) {
                        label
                    } else {
                        tree.breadcrumbs(parentId)
                            .map { it.name }
                            .filter { it.isNotBlank() }
                            .joinToString("/")
                            .ifBlank { label }
                    }
                }
                items += CleanerItem(
                    node = node,
                    folder = folder,
                    size = node.size,
                )
                if (items.size >= MAX_ARCHIVES) {
                    truncated = true
                    break
                }
            }
            onProgress(folders, items.size)
        }

        items.sortWith(compareByDescending<CleanerItem> { it.size }.thenBy { it.node.name.lowercase() })
        CleanerScan(
            folderLabel = label,
            items = items,
            foldersScanned = folders,
            truncated = truncated,
        )
    }
}
