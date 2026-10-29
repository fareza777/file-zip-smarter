package com.hatchstudio.archive.ui

import android.graphics.Bitmap
import android.net.Uri
import com.hatchstudio.archive.core.ArchiveEntry
import com.hatchstudio.archive.core.ArchiveInfo
import com.hatchstudio.archive.core.ArchiveSource
import com.hatchstudio.archive.core.OpenedArchive
import com.hatchstudio.archive.data.CleanerItem
import com.hatchstudio.archive.data.ConflictPolicy
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.data.SafNode
import com.hatchstudio.archive.data.StorageSpace
import com.hatchstudio.archive.data.ZipCompression
import java.io.File

enum class AppTab { HOME, FILES, QUEUE, SETTINGS }
enum class InspectorTab { CONTENTS, LENS }
enum class EntryFilter(val label: String) {
    ALL("All"),
    FILES("Files"),
    IMAGES("Images"),
    VIDEO("Video"),
    DOCUMENTS("Docs"),
    ARCHIVES("Archives"),
}

enum class FileSort(val label: String) {
    NAME("Name"),
    SIZE("Size"),
    MODIFIED("Newest"),
    TYPE("Type"),
}

data class FilesUiState(
    val landingSet: Boolean = false,
    val treeLabel: String = "Landing folder",
    val currentDocId: String? = null,
    val crumbs: List<SafNode> = emptyList(),
    val nodes: List<SafNode> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val sort: FileSort = FileSort.NAME,
    val grid: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class InspectorState(
    val info: ArchiveInfo,
    val entries: List<ArchiveEntry>,
    val selected: Set<Int>,
    val canReclaim: Boolean,
    val passwordProtected: Boolean,
    val tab: InspectorTab = InspectorTab.CONTENTS,
    val query: String = "",
    val filter: EntryFilter = EntryFilter.ALL,
) {
    val selectedBytes: Long
        get() = entries.filter { it.index in selected && !it.isDirectory }.sumOf { it.size }
    val selectedCount: Int get() = selected.size
    val allSelected: Boolean
        get() = entries.filter { it.canExtract }.all { it.index in selected }
}

data class ExtractionDraft(
    val title: String,
    val itemCount: Int,
    val selectedFiles: Int,
    val selectedBytes: Long,
    val destinationTreeUri: Uri?,
    val destinationLabel: String?,
    val conflict: ConflictPolicy,
    val mode: JobMode = JobMode.SMART,
    val canReclaim: Boolean,
    val reclaimReason: String?,
    val isBatch: Boolean = false,
    val batchCount: Int = 0,
    val needsPassword: Boolean = false,
    val password: String = "",
    val rememberPassword: Boolean = true,
    val freeBytes: Long? = null,
    val reclaimPeakBytes: Long = 0,
    val removeSource: Boolean = false,
    val canRemoveSource: Boolean = false,
    val quickClean: Boolean = false,
) {
    val requiredBytes: Long
        get() = StorageSpace.requiredBytes(
            selectedBytes = selectedBytes,
            reclaimPeakBytes = reclaimPeakBytes,
            reclaim = mode == JobMode.RECLAIM,
        )

    val enoughSpace: Boolean
        get() = StorageSpace.hasRoom(freeBytes, requiredBytes)
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object OpenQueue : UiEvent
}

data class ZipCreateDraft(
    val nodes: List<SafNode>,
    val name: String,
    val password: String = "",
    val compression: ZipCompression = ZipCompression.BALANCED,
    val splitBytes: Long = 0,
    val destinationTreeUri: Uri?,
    val destinationDocId: String?,
    val destinationLabel: String?,
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val scanning: Boolean = true,
) {
    val encrypted: Boolean get() = password.isNotBlank()
}

data class ZipCreateProgress(
    val done: Int,
    val total: Int,
    val bytes: Long,
    val label: String = "Building ZIP",
)

data class OpenPasswordPrompt(
    val archiveName: String,
    val message: String,
    val wrong: Boolean = false,
)

data class CurrentArchive(
    val opened: OpenedArchive,
    val password: String?,
    val source: ArchiveSource,
    var ownedStaged: File?,
)

data class BatchItem(
    val source: ArchiveSource,
    val displayName: String,
    val size: Long,
    val format: String,
)

data class CleanerState(
    val scanning: Boolean = false,
    val folderLabel: String = "",
    val items: List<CleanerItem> = emptyList(),
    val selected: Set<String> = emptySet(),
    val foldersScanned: Int = 0,
    val truncated: Boolean = false,
    val error: String? = null,
) {
    val selectedItems: List<CleanerItem>
        get() = items.filter { it.id in selected }
    val selectedBytes: Long
        get() = selectedItems.sumOf { it.size }
    val totalBytes: Long
        get() = items.sumOf { it.size }
}

enum class PreviewKind { IMAGE, TEXT, OTHER }

data class EntryPreview(
    val name: String,
    val mimeType: String,
    val size: Long,
    val kind: PreviewKind,
    val loading: Boolean = true,
    val error: String? = null,
    val image: Bitmap? = null,
    val text: String? = null,
    val file: File? = null,
)
