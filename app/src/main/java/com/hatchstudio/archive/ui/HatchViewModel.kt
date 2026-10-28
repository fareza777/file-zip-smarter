package com.hatchstudio.archive.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hatchstudio.archive.core.ArchiveFailure
import com.hatchstudio.archive.core.ArchiveOpener
import com.hatchstudio.archive.core.ArchiveSource
import com.hatchstudio.archive.core.NativeEngine
import com.hatchstudio.archive.data.Archives
import com.hatchstudio.archive.data.ConflictPolicy
import com.hatchstudio.archive.data.ExtractionJob
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.data.JobState
import com.hatchstudio.archive.data.MultipartResolver
import com.hatchstudio.archive.data.PasswordVault
import com.hatchstudio.archive.data.RecentArchive
import com.hatchstudio.archive.data.RecentStore
import com.hatchstudio.archive.data.SafNode
import com.hatchstudio.archive.data.SafTree
import com.hatchstudio.archive.data.SettingsStore
import com.hatchstudio.archive.data.ThemeMode
import com.hatchstudio.archive.jobs.JobManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class HatchViewModel(application: Application) : AndroidViewModel(application) {
    val settingsStore = SettingsStore(application)
    val recentStore = RecentStore(application)
    val vault = PasswordVault(application)
    val jobManager: JobManager = JobManager.init(application)

    val settings = settingsStore.state
    val recents = recentStore.items
    val jobs = jobManager.jobs

    private val _files = MutableStateFlow(FilesUiState())
    val files: StateFlow<FilesUiState> = _files.asStateFlow()

    private val _inspector = MutableStateFlow<InspectorState?>(null)
    val inspector: StateFlow<InspectorState?> = _inspector.asStateFlow()

    private val _draft = MutableStateFlow<ExtractionDraft?>(null)
    val draft: StateFlow<ExtractionDraft?> = _draft.asStateFlow()

    private val _loading = MutableStateFlow<String?>(null)
    val loading: StateFlow<String?> = _loading.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val _openPassword = MutableStateFlow<OpenPasswordPrompt?>(null)
    val openPassword: StateFlow<OpenPasswordPrompt?> = _openPassword.asStateFlow()

    private val _tabRequest = MutableStateFlow<AppTab?>(null)
    val tabRequest: StateFlow<AppTab?> = _tabRequest.asStateFlow()

    private val _vaultCount = MutableStateFlow(vault.count)
    val vaultCount: StateFlow<Int> = _vaultCount.asStateFlow()

    private var tree: SafTree? = null
    private var currentArchive: CurrentArchive? = null
    private var pendingSource: ArchiveSource? = null
    private var batchSources: List<BatchItem>? = null
    private val sessionPasswords = ConcurrentHashMap<String, String>()

    init {
        settings.value.landingTreeUri?.let { uri ->
            viewModelScope.launch(Dispatchers.IO) {
                loadTree(Uri.parse(uri))
            }
        }
    }

    fun consumeTabRequest(): AppTab? {
        val value = _tabRequest.value
        _tabRequest.value = null
        return value
    }

    fun requestTab(tab: AppTab) {
        _tabRequest.value = tab
    }

    // ---------------- landing folder & file manager ----------------

    fun setLandingTree(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            loadTree(uri)
            settingsStore.setOnboarded(true)
        }
    }

    private fun loadTree(uri: Uri) {
        try {
            val safTree = SafTree(getApplication(), uri)
            val label = runCatching { safTree.displayName() }.getOrDefault("Landing folder")
            tree = safTree
            settingsStore.setLanding(uri, label)
            _files.value = FilesUiState(
                landingSet = true,
                treeLabel = label,
                loading = true,
            )
            loadDirectory(safTree.rootDocumentId, safTree, label)
        } catch (e: SecurityException) {
            tree = null
            settingsStore.clearLanding()
            _files.value = FilesUiState(
                landingSet = false,
                error = "Hatch lost access to the landing folder. Choose it again.",
            )
        }
    }

    private fun loadDirectory(docId: String, safTree: SafTree, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _files.update { it.copy(loading = true, error = null) }
            runCatching {
                val nodes = safTree.list(docId, refresh = true)
                val crumbs = safTree.breadcrumbs(docId)
                _files.update {
                    it.copy(
                        landingSet = true,
                        treeLabel = label,
                        currentDocId = docId,
                        crumbs = crumbs,
                        nodes = nodes,
                        selected = emptySet(),
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                _files.update {
                    it.copy(
                        loading = false,
                        error = error.message ?: "That folder could not be read.",
                    )
                }
            }
        }
    }

    fun refreshFiles() {
        val docId = _files.value.currentDocId ?: return
        val safTree = tree ?: return
        loadDirectory(docId, safTree, _files.value.treeLabel)
    }

    fun browse(node: SafNode) {
        if (!node.isDirectory) return
        val safTree = tree ?: return
        if (!_files.value.landingSet) return
        loadDirectory(node.documentId, safTree, _files.value.treeLabel)
    }

    fun browseRoot() {
        val safTree = tree ?: return
        loadDirectory(safTree.rootDocumentId, safTree, _files.value.treeLabel)
    }

    fun setFileQuery(value: String) {
        _files.update { it.copy(query = value) }
    }

    fun setFileSort(value: FileSort) {
        _files.update { it.copy(sort = value) }
    }

    fun toggleGrid() {
        _files.update { it.copy(grid = !it.grid) }
    }

    fun toggleFileSelection(node: SafNode) {
        _files.update {
            val next = it.selected.toMutableSet()
            if (!next.add(node.documentId)) next.remove(node.documentId)
            it.copy(selected = next)
        }
    }

    fun clearFileSelection() {
        _files.update { it.copy(selected = emptySet()) }
    }

    fun selectedNodes(): List<SafNode> {
        val state = _files.value
        return state.nodes.filter { it.documentId in state.selected }
    }

    fun deleteNodes(nodes: List<SafNode>) {
        val safTree = tree ?: return
        viewModelScope.launch(Dispatchers.IO) {
            var failures = 0
            nodes.forEach { node ->
                if (!safTree.delete(node)) failures++
            }
            if (failures > 0) {
                _events.emit(UiEvent.Message("$failures item(s) could not be deleted by this folder's provider."))
            } else {
                _events.emit(UiEvent.Message(if (nodes.size == 1) "Deleted" else "${nodes.size} items deleted"))
            }
            refreshFiles()
        }
    }

    fun renameNode(node: SafNode, newName: String) {
        val safTree = tree ?: return
        if (newName.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (safTree.rename(node, newName) == null) {
                _events.emit(UiEvent.Message("This provider would not rename that item."))
            }
            refreshFiles()
        }
    }

    fun clearLanding() {
        settingsStore.clearLanding()
        tree = null
        _files.value = FilesUiState()
    }

    // ---------------- archive opening ----------------

    fun openArchiveNode(node: SafNode) {
        val safTree = tree ?: return
        openArchiveFromNode(safTree, node)
    }

    fun openArchiveFromNode(tree: SafTree, node: SafNode) {
        viewModelScope.launch {
            _loading.value = "Preparing ${node.name}"
            val staged = withContext(Dispatchers.IO) {
                runCatching { MultipartResolver.prepare(getApplication(), tree, node) }.getOrNull()
            }
            val source = staged ?: ArchiveSource(
                displayName = node.name,
                size = node.size,
                uri = node.uri,
                treeUri = tree.treeUri,
                documentId = node.documentId,
            )
            openSourceInternal(source, password = null)
        }
    }

    fun openExternalUri(uri: Uri, nameHint: String? = null) {
        viewModelScope.launch {
            _loading.value = "Opening archive…"
            val source = withContext(Dispatchers.IO) { buildSource(uri, nameHint) }
            if (source == null) {
                _loading.value = null
                _events.emit(UiEvent.Message("That file could not be read."))
                return@launch
            }
            openSourceInternal(source, password = null)
        }
    }

    fun openRecent(item: RecentArchive) {
        openExternalUri(Uri.parse(item.uri), item.name)
    }

    private fun buildSource(uri: Uri, nameHint: String?): ArchiveSource? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                val file = File(path)
                if (!file.exists()) return null
                ArchiveSource(
                    displayName = nameHint ?: file.name,
                    size = file.length(),
                    file = file,
                )
            } else {
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                var name = nameHint
                var size = 0L
                getApplication<Application>().contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = name ?: cursor.getString(0)
                        if (!cursor.isNull(1)) size = cursor.getLong(1)
                    }
                }
                ArchiveSource(
                    displayName = name?.takeIf { it.isNotBlank() } ?: "Archive",
                    size = size,
                    uri = uri,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun openSourceInternal(source: ArchiveSource, password: String?) {
        _loading.value = if (source.displayName.length > 28) {
            "Opening ${source.displayName.take(26)}…"
        } else {
            "Opening ${source.displayName}…"
        }
        val cached = password ?: sessionPasswords[source.displayName] ?: vault.get(source.displayName)
        try {
            closeInspector(deleteStaged = true)
            val opened = ArchiveOpener.open(
                context = getApplication(),
                source = source,
                password = cached,
                mode = NativeEngine.MODE_SMART,
                planFile = null,
                multipart = source.multipart,
            )
            if (cached != null) sessionPasswords[source.displayName] = cached
            currentArchive = CurrentArchive(
                opened = opened,
                password = cached,
                source = opened.source,
                ownedStaged = opened.source.ownedPath,
            )
            _inspector.value = InspectorState(
                info = opened.info,
                entries = opened.entries,
                selected = opened.entries.filter { it.canExtract }.map { it.index }.toSet(),
                canReclaim = opened.canReclaim,
                passwordProtected = opened.info.encryptedCount > 0 || cached != null,
            )
            pendingSource = null
            _openPassword.value = null
            _loading.value = null
            if (opened.source.uri != null && opened.source.ownedPath == null) {
                recentStore.add(
                    RecentArchive(
                        uri = opened.source.uri.toString(),
                        name = opened.info.displayName.ifBlank { opened.source.displayName },
                        format = opened.info.format,
                        size = opened.info.archiveSize,
                        entryCount = opened.info.entryCount,
                        openedAt = System.currentTimeMillis(),
                        treeUri = opened.source.treeUri?.toString(),
                    ),
                )
            }
        } catch (failure: ArchiveFailure) {
            _loading.value = null
            if (failure.needsPassword) {
                pendingSource = source
                _openPassword.value = OpenPasswordPrompt(
                    archiveName = source.displayName,
                    message = failure.userMessage,
                    wrong = failure.wrongPassword,
                )
            } else {
                cleanupStaged(source)
                pendingSource = null
                _events.emit(UiEvent.Message(failure.userMessage))
            }
        } catch (e: Exception) {
            _loading.value = null
            cleanupStaged(source)
            _events.emit(UiEvent.Message(e.message ?: "The archive could not be opened."))
        }
    }

    fun retryOpenWithPassword(password: String, remember: Boolean) {
        val source = pendingSource ?: return
        viewModelScope.launch {
            if (remember && password.isNotBlank()) {
                vault.save(source.displayName, password)
                _vaultCount.value = vault.count
            }
            openSourceInternal(source, password)
        }
    }

    fun dismissOpenPassword() {
        pendingSource?.let(::cleanupStaged)
        pendingSource = null
        _openPassword.value = null
    }

    fun closeInspector(deleteStaged: Boolean = true) {
        currentArchive?.opened?.close()
        if (deleteStaged) currentArchive?.ownedStaged?.let(::deleteOwned)
        currentArchive = null
        _inspector.value = null
        _openPassword.value = null
        pendingSource = null
    }

    private fun cleanupStaged(source: ArchiveSource) {
        source.ownedPath?.let(::deleteOwned)
    }

    private fun deleteOwned(file: File) {
        runCatching { if (file.isDirectory) file.deleteRecursively() else file.delete() }
    }

    // ---------------- inspector selection ----------------

    fun setInspectorTab(tab: InspectorTab) = _inspector.update { it?.copy(tab = tab) }

    fun setEntryQuery(query: String) = _inspector.update { it?.copy(query = query) }

    fun setEntryFilter(filter: EntryFilter) = _inspector.update { it?.copy(filter = filter) }

    fun toggleEntry(index: Int) {
        _inspector.update { state ->
            state ?: return@update null
            val entry = state.entries.getOrNull(index) ?: return@update state
            if (!entry.canExtract) return@update state
            val selected = state.selected.toMutableSet()
            if (!selected.add(index)) selected.remove(index)
            state.copy(selected = selected)
        }
    }

    fun selectAllEntries() {
        _inspector.update { state ->
            state?.copy(selected = state.entries.filter { it.canExtract }.map { it.index }.toSet())
        }
    }

    fun clearEntrySelection() {
        _inspector.update { it?.copy(selected = emptySet()) }
    }

    // ---------------- extraction drafts ----------------

    fun prepareExtraction() {
        val state = _inspector.value ?: return
        val opened = currentArchive?.opened ?: return
        if (state.selected.isEmpty()) {
            _events.tryEmit(UiEvent.Message("Choose at least one file to extract."))
            return
        }
        val selectable = state.entries.filter { it.canExtract }.map { it.index }.toSet()
        val allSelected = state.selected.containsAll(selectable)
        val reclaimReason = when {
            !opened.info.reclaim.eligible -> opened.info.reclaim.reason
                ?: "This archive cannot be reclaimed in place."
            !opened.canReclaim ->
                "This archive is read-only or shared. Smart extraction still works."
            !allSelected ->
                "Reclaim extracts the whole archive. Select every entry to use it."
            state.entries.any { it.isSymlink || it.unsafePath } ->
                "This archive contains links or unsafe paths. Use Smart extraction."
            else -> null
        }
        val landing = settings.value.landingTreeUri?.let(Uri::parse)
        batchSources = null
        _draft.value = ExtractionDraft(
            title = opened.info.displayName.ifBlank { opened.source.displayName },
            itemCount = state.selected.size,
            selectedFiles = state.selected.count { !(state.entries.getOrNull(it)?.isDirectory ?: true) },
            selectedBytes = state.selectedBytes,
            destinationTreeUri = landing,
            destinationLabel = settings.value.landingLabel,
            conflict = settings.value.conflict,
            mode = if (reclaimReason == null) JobMode.RECLAIM else JobMode.SMART,
            canReclaim = reclaimReason == null,
            reclaimReason = reclaimReason,
            isBatch = false,
            needsPassword = opened.info.encryptedCount > 0 && currentArchive?.password.isNullOrEmpty(),
        )
    }

    fun prepareBatch(nodes: List<SafNode>) {
        val safTree = tree ?: return
        val usable = nodes.filter {
            !it.isDirectory && Archives.isArchiveName(it.name) && !isSplitPart(it.name)
        }
        val skipped = nodes.count { !it.isDirectory && isSplitPart(it.name) }
        if (skipped > 0) {
            _events.tryEmit(UiEvent.Message("Open split archives one at a time so Hatch can gather all parts."))
        }
        if (usable.isEmpty()) {
            _events.tryEmit(UiEvent.Message("Select one or more whole archives to batch extract."))
            return
        }
        batchSources = usable.map { node ->
            BatchItem(
                source = ArchiveSource(
                    displayName = node.name,
                    size = node.size,
                    uri = node.uri,
                    treeUri = safTree.treeUri,
                    documentId = node.documentId,
                ),
                displayName = node.name,
                size = node.size,
                format = SafTree.archiveKind(node.name) ?: "ARCHIVE",
            )
        }
        val landing = settings.value.landingTreeUri?.let(Uri::parse)
        _draft.value = ExtractionDraft(
            title = "${usable.size} archives",
            itemCount = usable.size,
            selectedFiles = 0,
            selectedBytes = usable.sumOf { it.size },
            destinationTreeUri = landing,
            destinationLabel = settings.value.landingLabel,
            conflict = settings.value.conflict,
            mode = JobMode.SMART,
            canReclaim = false,
            reclaimReason = "Reclaim works one archive at a time.",
            isBatch = true,
            batchCount = usable.size,
        )
    }

    fun setDraftDestination(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val label = runCatching { SafTree(getApplication(), uri).displayName() }.getOrDefault("Folder")
            _draft.update { it?.copy(destinationTreeUri = uri, destinationLabel = label) }
        }
    }

    fun setDraftMode(mode: JobMode) {
        _draft.update { draft ->
            if (draft == null) null
            else if (mode == JobMode.RECLAIM && !draft.canReclaim) draft
            else draft.copy(mode = mode)
        }
    }

    fun setDraftConflict(policy: ConflictPolicy) {
        _draft.update { it?.copy(conflict = policy) }
    }

    fun setDraftRemember(value: Boolean) {
        _draft.update { it?.copy(rememberPassword = value) }
    }

    fun setDraftPassword(value: String) {
        _draft.update { it?.copy(password = value) }
    }

    fun dismissDraft() {
        _draft.value = null
        batchSources = null
    }

    fun startExtraction() {
        val draftState = _draft.value ?: return
        val destination = draftState.destinationTreeUri
        if (destination == null) {
            _events.tryEmit(UiEvent.Message("Choose a destination folder first."))
            return
        }
        val destinationLabel = draftState.destinationLabel ?: "Folder"
        val batch = batchSources
        if (draftState.isBatch && batch != null) {
            val jobs = batch.map { item ->
                ExtractionJob(
                    archiveName = item.displayName,
                    format = item.format,
                    archiveSize = item.size,
                    sourceUri = item.source.uri?.toString(),
                    sourcePath = item.source.file?.absolutePath,
                    ownedSourcePath = item.source.ownedPath?.absolutePath,
                    destinationTreeUri = destination.toString(),
                    destinationLabel = destinationLabel,
                    outputFolder = Archives.outputFolder(item.displayName, settings.value.createFolder),
                    mode = JobMode.SMART,
                    conflict = draftState.conflict,
                    selected = null,
                    totalEntries = 0,
                    totalBytes = 0,
                    multipart = item.source.multipart,
                )
            }
            jobManager.enqueue(jobs, null)
            _draft.value = null
            batchSources = null
            closeInspector(deleteStaged = false)
            _events.tryEmit(UiEvent.OpenQueue)
            return
        }

        val inspectorState = _inspector.value ?: return
        val current = currentArchive ?: return
        if (inspectorState.selected.isEmpty()) {
            _events.tryEmit(UiEvent.Message("Choose at least one entry to extract."))
            return
        }
        val password = current.password ?: draftState.password.takeIf { it.isNotBlank() }
        if (draftState.needsPassword && password.isNullOrBlank()) {
            _events.tryEmit(UiEvent.Message("Enter the archive password before hatching."))
            return
        }
        val reclaim = draftState.mode == JobMode.RECLAIM && draftState.canReclaim
        val selected = if (reclaim) {
            current.opened.entries.indices.toList()
        } else {
            inspectorState.selected.sorted()
        }
        val totalBytes = if (reclaim) {
            current.opened.entries.filter { !it.isDirectory }.sumOf { it.size }
        } else {
            inspectorState.selectedBytes
        }
        val selectAll = inspectorState.selected.containsAll(
            current.opened.entries.map { it.index },
        )
        if (reclaim && !selectAll) {
            _events.tryEmit(UiEvent.Message("Reclaim needs every entry selected. Use Smart extraction for a subset."))
            return
        }
        if (draftState.rememberPassword && !password.isNullOrBlank()) {
            vault.save(current.source.displayName, password)
            _vaultCount.value = vault.count
            sessionPasswords[current.source.displayName] = password
        }
        val owned = current.ownedStaged
        val job = ExtractionJob(
            archiveName = current.opened.info.displayName.ifBlank { current.source.displayName },
            format = current.opened.info.format,
            archiveSize = current.opened.info.archiveSize,
            sourceUri = current.source.uri?.toString(),
            sourcePath = current.source.file?.absolutePath,
            ownedSourcePath = owned?.absolutePath,
            destinationTreeUri = destination.toString(),
            destinationLabel = destinationLabel,
            outputFolder = Archives.outputFolder(
                current.source.displayName,
                settings.value.createFolder,
            ),
            mode = if (reclaim) JobMode.RECLAIM else JobMode.SMART,
            conflict = draftState.conflict,
            selected = if (selectAll) null else selected,
            totalEntries = if (reclaim) current.opened.entries.size else selected.size,
            totalBytes = totalBytes,
            multipart = current.source.multipart,
        )
        jobManager.enqueue(listOf(job), password)
        current.ownedStaged = null
        _draft.value = null
        batchSources = null
        closeInspector(deleteStaged = false)
        _events.tryEmit(UiEvent.OpenQueue)
    }

    private fun isSplitPart(name: String): Boolean {
        val lower = name.lowercase()
        return Regex("\\.z\\d{2}$").containsMatchIn(lower) ||
            Regex("\\.r\\d{2}$").containsMatchIn(lower) ||
            Regex("\\.7z\\.\\d{3}$").containsMatchIn(lower) ||
            Regex("\\.part\\d+\\.rar$").containsMatchIn(lower)
    }

    // ---------------- jobs ----------------

    fun pauseActive() = jobManager.pauseActive()

    fun resumeActive() = jobManager.resumeActive()

    fun stopActive() = jobManager.stopActive()

    fun resumeJob(id: String) {
        val job = jobManager.job(id) ?: return
        val pw = vault.get(job.archiveName)
        jobManager.resumeJob(id, pw)
    }

    fun resumeJobWithPassword(id: String, password: String, remember: Boolean) {
        val job = jobManager.job(id) ?: return
        if (remember) {
            vault.save(job.archiveName, password)
            _vaultCount.value = vault.count
            sessionPasswords[job.archiveName] = password
        }
        jobManager.resumeJob(id, password)
    }

    fun removeJob(id: String): Boolean = jobManager.removeJob(id)

    fun clearFinishedJobs() = jobManager.clearFinished()

    fun activeJob(): ExtractionJob? = jobs.value.firstOrNull {
        it.state == JobState.RUNNING || it.state == JobState.PAUSED
    }

    // ---------------- settings ----------------

    fun setTheme(mode: ThemeMode) = settingsStore.setTheme(mode)

    fun setConflict(policy: ConflictPolicy) = settingsStore.setConflict(policy)

    fun setCreateFolder(value: Boolean) = settingsStore.setCreateFolder(value)

    fun setKeepAwake(value: Boolean) = settingsStore.setKeepAwake(value)

    fun setHaptics(value: Boolean) = settingsStore.setHaptics(value)

    fun clearVault() {
        vault.clear()
        _vaultCount.value = 0
        sessionPasswords.clear()
    }

    fun clearRecents() = recentStore.clear()

    fun clearTransientCaches() {
        viewModelScope.launch(Dispatchers.IO) {
            val root = File(getApplication<Application>().cacheDir, "hatch")
            root.listFiles()?.forEach { it.deleteRecursively() }
            _events.emit(UiEvent.Message("Temporary staging cleared."))
        }
    }

    fun onboardingDone() {
        settingsStore.setOnboarded(true)
    }


    override fun onCleared() {
        currentArchive?.opened?.close()
        currentArchive?.ownedStaged?.let(::deleteOwned)
        currentArchive = null
        super.onCleared()
    }
}
