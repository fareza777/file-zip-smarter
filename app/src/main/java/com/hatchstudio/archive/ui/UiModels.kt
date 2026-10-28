package com.hatchstudio.archive.ui

import android.net.Uri
import com.hatchstudio.archive.core.ArchiveEntry
import com.hatchstudio.archive.core.ArchiveInfo
import com.hatchstudio.archive.core.ArchiveSource
import com.hatchstudio.archive.core.OpenedArchive
import com.hatchstudio.archive.data.ConflictPolicy
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.data.SafNode
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
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data object OpenQueue : UiEvent
}

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
