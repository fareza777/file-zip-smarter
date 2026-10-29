package com.hatchstudio.archive.data

import com.hatchstudio.archive.core.ArchiveEntry
import com.hatchstudio.archive.core.ArchiveInfo
import com.hatchstudio.archive.core.FileCategory

data class CategorySlice(
    val category: FileCategory,
    val bytes: Long,
    val count: Int,
)

data class StorageLens(
    val slices: List<CategorySlice>,
    val largest: List<ArchiveEntry>,
    val duplicateCount: Int,
    val duplicateBytes: Long,
    val symlinkCount: Int,
    val unsafeCount: Int,
    val reclaimableBytes: Long,
    val overheadBytes: Long,
) {
    val totalCategoryBytes: Long get() = slices.sumOf { it.bytes }
}

fun buildStorageLens(info: ArchiveInfo, entries: List<ArchiveEntry>): StorageLens {
    val files = entries.filter { !it.isDirectory }
    val byCategory = files.groupBy { it.category }
        .map { (category, list) -> CategorySlice(category, list.sumOf { it.size }, list.size) }
        .sortedByDescending { it.bytes }

    val duplicateGroups = files
        .filter { it.canExtract }
        .groupBy { it.displayName.lowercase() + ":" + it.size }
        .values
        .filter { it.size > 1 }
    val duplicateBytes = duplicateGroups.sumOf { group -> group.sumOf { it.size } } -
        duplicateGroups.sumOf { group -> group.first().size }

    val largest = files.sortedByDescending { it.size }.take(4)
    val extractable = files.filter { it.canExtract }.sumOf { it.size }
    val reclaimable = if (info.reclaim.eligible) info.archiveSize else 0L
    val declaredOverhead = (info.totalSize - info.archiveSize).coerceAtLeast(0)
    return StorageLens(
        slices = byCategory,
        largest = largest,
        duplicateCount = duplicateGroups.sumOf { it.size - 1 },
        duplicateBytes = duplicateBytes.coerceAtLeast(0),
        symlinkCount = entries.count { it.isSymlink },
        unsafeCount = entries.count { it.unsafePath },
        reclaimableBytes = reclaimable,
        overheadBytes = declaredOverhead.takeIf { extractable > 0 } ?: 0,
    )
}

object Archives {
    fun stem(displayName: String): String {
        val base = displayName.substringAfterLast('/').substringAfterLast('\\')
        val lower = base.lowercase()
        for (ext in listOf(
            ".tar.gz", ".tgz", ".tar.xz", ".txz", ".tar.zst", ".tzst",
            ".tar.bz2", ".tbz2", ".tbz", ".zip", ".rar", ".7z", ".tar",
            ".gz", ".xz", ".zst", ".zstd", ".bz2",
        )) {
            if (lower.endsWith(ext)) return base.dropLast(ext.length).ifBlank { "Archive" }
        }
        return base.substringBeforeLast('.', base).ifBlank { "Archive" }
    }

    fun outputFolder(displayName: String, createFolder: Boolean): String {
        if (!createFolder) return ""
        return stem(displayName)
            .replace('/', '_')
            .replace('\\', '_')
            .trim()
            .take(80)
            .ifBlank { "Hatch" }
    }

    fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".zipx") ||
            lower.endsWith(".rar") || lower.endsWith(".7z") ||
            lower.endsWith(".tar") || lower.endsWith(".tar.gz") ||
            lower.endsWith(".tgz") || lower.endsWith(".gz") ||
            lower.endsWith(".xz") || lower.endsWith(".txz") ||
            lower.endsWith(".zst") || lower.endsWith(".zstd") || lower.endsWith(".tzst") ||
            lower.endsWith(".bz2") || lower.endsWith(".tbz2") || lower.endsWith(".tbz") ||
            Regex("\\.z\\d{2}$").containsMatchIn(lower) ||
            Regex("\\.r\\d{2}$").containsMatchIn(lower) ||
            Regex("\\.7z\\.\\d{3}$").containsMatchIn(lower) ||
            Regex("\\.part\\d+\\.rar$").containsMatchIn(lower)
    }

    fun formatLabel(info: ArchiveInfo): String = when (info.format) {
        "TAR.GZ", "TAR.XZ", "TAR.ZST", "TAR.BZ2" -> info.format
        else -> info.format
    }
}
