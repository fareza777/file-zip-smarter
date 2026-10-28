package com.hatchstudio.archive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hatchstudio.archive.data.ExtractionJob
import com.hatchstudio.archive.data.JobState
import com.hatchstudio.archive.data.SafNode
import com.hatchstudio.archive.ui.AppTab
import com.hatchstudio.archive.ui.HatchViewModel
import com.hatchstudio.archive.ui.OpenPasswordPrompt
import com.hatchstudio.archive.ui.UiEvent
import com.hatchstudio.archive.ui.components.BottomDock
import com.hatchstudio.archive.ui.components.HatchBackdrop
import com.hatchstudio.archive.ui.screens.FilesScreen
import com.hatchstudio.archive.ui.screens.HomeScreen
import com.hatchstudio.archive.ui.screens.InspectorScreen
import com.hatchstudio.archive.ui.screens.QueueScreen
import com.hatchstudio.archive.ui.screens.SettingsScreen
import com.hatchstudio.archive.ui.sheets.ExtractionSheet
import com.hatchstudio.archive.ui.sheets.LoadingScrim
import com.hatchstudio.archive.ui.sheets.OnboardingScreen
import com.hatchstudio.archive.ui.sheets.PasswordDialog
import com.hatchstudio.archive.ui.theme.HatchTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_QUEUE = "com.hatchstudio.archive.OPEN_QUEUE"
    }

    private val vm: HatchViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                com.hatchstudio.archive.data.ThemeMode.SYSTEM -> isSystemInDarkTheme()
                com.hatchstudio.archive.data.ThemeMode.LIGHT -> false
                com.hatchstudio.archive.data.ThemeMode.DARK -> true
            }
            HatchTheme(darkTheme = dark) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                SideEffect {
                    controller.isAppearanceLightStatusBars = !dark
                    controller.isAppearanceLightNavigationBars = !dark
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HatchRoot(vm, dark)
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) {
            vm.onboardingDone()
            vm.openExternalUri(uri)
            return
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
            vm.requestTab(AppTab.QUEUE)
        }
    }
}

@Composable
private fun HatchRoot(vm: HatchViewModel, dark: Boolean) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val inspector by vm.inspector.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val openPassword by vm.openPassword.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val vaultCount by vm.vaultCount.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var pickingDestination by remember { mutableStateOf(false) }
    var jobPassword by remember { mutableStateOf<ExtractionJob?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            if (pickingDestination) vm.setDraftDestination(uri) else vm.setLandingTree(uri)
        }
        pickingDestination = false
    }
    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            vm.openExternalUri(uri)
        }
    }

    val pickLanding: (Boolean) -> Unit = { destination ->
        pickingDestination = destination
        val start = if (destination) {
            draft?.destinationTreeUri ?: settings.landingTreeUri?.let(Uri::parse)
        } else {
            settings.landingTreeUri?.let(Uri::parse)
        }
        treePicker.launch(start)
    }

    LaunchedEffect(Unit) {
        vm.tabRequest.collect { request ->
            if (request != null) {
                tab = request
                vm.consumeTabRequest()
            }
        }
    }
    // A finished reclaim deletes its archive. Re-read the provider whenever
    // Files becomes visible so the list never shows a consumed source.
    LaunchedEffect(tab, settings.onboarded) {
        if (settings.onboarded && tab == AppTab.FILES) vm.refreshFiles()
    }
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.Message -> scope.launch { snackbar.showSnackbar(event.text) }
                UiEvent.OpenQueue -> tab = AppTab.QUEUE
            }
        }
    }

    val onboarding = !settings.onboarded
    BackHandler(enabled = true) {
        when {
            onboarding -> {
                vm.onboardingDone()
                (context as? android.app.Activity)?.finish()
            }
            inspector != null -> vm.closeInspector()
            tab != AppTab.HOME -> tab = AppTab.HOME
            else -> (context as? android.app.Activity)?.finish()
        }
    }

    Box(Modifier.fillMaxSize()) {
        HatchBackdrop {
            if (onboarding) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    OnboardingScreen(
                        onChooseFolder = { pickLanding(false) },
                        onSkip = { vm.onboardingDone() },
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(
                        Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.fillMaxSize().widthIn(max = 760.dp)) {
                            when (tab) {
                                AppTab.HOME -> HomeScreen(
                                    settings = settings,
                                    recents = recents,
                                    jobs = jobs,
                                    onOpenArchive = { archivePicker.launch(arrayOf("*/*")) },
                                    onOpenFiles = { tab = AppTab.FILES },
                                    onOpenQueue = { tab = AppTab.QUEUE },
                                    onOpenRecent = vm::openRecent,
                                    onPickLanding = { pickLanding(false) },
                                    onOpenSettings = { tab = AppTab.SETTINGS },
                                )
                                AppTab.FILES -> FilesScreen(
                                    state = files,
                                    onBrowseRoot = vm::browseRoot,
                                    onBrowse = vm::browse,
                                    onRefresh = vm::refreshFiles,
                                    onQueryChange = vm::setFileQuery,
                                    onSortChange = vm::setFileSort,
                                    onToggleGrid = vm::toggleGrid,
                                    onToggleSelection = vm::toggleFileSelection,
                                    onClearSelection = vm::clearFileSelection,
                                    onOpenNode = { node ->
                                        when {
                                            node.isDirectory -> vm.browse(node)
                                            node.isArchive -> vm.openArchiveNode(node)
                                            else -> openNode(context, node) { message ->
                                                scope.launch { snackbar.showSnackbar(message) }
                                            }
                                        }
                                    },
                                    onOpenArchive = vm::openArchiveNode,
                                    onPickLanding = { pickLanding(false) },
                                    onOpenArchivePicker = { archivePicker.launch(arrayOf("*/*")) },
                                    onBatchExtract = vm::prepareBatch,
                                    onDelete = vm::deleteNodes,
                                    onRename = vm::renameNode,
                                )
                                AppTab.QUEUE -> QueueScreen(
                                    jobs = jobs,
                                    onPause = vm::pauseActive,
                                    onResume = vm::resumeActive,
                                    onStop = vm::stopActive,
                                    onResumeJob = { job ->
                                        if (job.state == JobState.NEEDS_PASSWORD) {
                                            jobPassword = job
                                        } else {
                                            vm.resumeJob(job.id)
                                        }
                                    },
                                    onPassword = { jobPassword = it },
                                    onRemove = { id ->
                                        if (!vm.removeJob(id)) {
                                            scope.launch {
                                                snackbar.showSnackbar(
                                                    "An in-progress reclaim keeps its recovery map. Finish or stop it first.",
                                                )
                                            }
                                        }
                                    },
                                    onClearFinished = vm::clearFinishedJobs,
                                    onOpenArchive = { archivePicker.launch(arrayOf("*/*")) },
                                )
                                AppTab.SETTINGS -> SettingsScreen(
                                    settings = settings,
                                    vaultCount = vaultCount,
                                    onTheme = vm::setTheme,
                                    onPickLanding = { pickLanding(false) },
                                    onClearLanding = vm::clearLanding,
                                    onConflict = vm::setConflict,
                                    onCreateFolder = vm::setCreateFolder,
                                    onKeepAwake = vm::setKeepAwake,
                                    onHaptics = vm::setHaptics,
                                    onClearVault = vm::clearVault,
                                    onClearRecents = vm::clearRecents,
                                    onClearCaches = vm::clearTransientCaches,
                                )
                            }

                            if (inspector != null) {
                                InspectorScreen(
                                    state = inspector!!,
                                    onClose = { vm.closeInspector() },
                                    onTab = vm::setInspectorTab,
                                    onQuery = vm::setEntryQuery,
                                    onFilter = vm::setEntryFilter,
                                    onToggle = vm::toggleEntry,
                                    onSelectAll = vm::selectAllEntries,
                                    onClearSelection = vm::clearEntrySelection,
                                    onPrepare = vm::prepareExtraction,
                                )
                            }
                        }
                    }
                    if (inspector == null) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
                                BottomDock(selected = tab, onSelect = { tab = it })
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .padding(bottom = if (inspector == null) 100.dp else 18.dp),
        )

        if (loading != null) LoadingScrim(loading!!)
    }

    draft?.let { draftState ->
        ExtractionSheet(
            draft = draftState,
            onDismiss = vm::dismissDraft,
            onPickDestination = { pickLanding(true) },
            onMode = vm::setDraftMode,
            onConflict = vm::setDraftConflict,
            onRemember = vm::setDraftRemember,
            onPassword = vm::setDraftPassword,
            onStart = vm::startExtraction,
        )
    }

    openPassword?.let { prompt ->
        PasswordDialog(
            archiveName = prompt.archiveName,
            message = prompt.message,
            wrong = prompt.wrong,
            onDismiss = vm::dismissOpenPassword,
            onUnlock = vm::retryOpenWithPassword,
        )
    }

    jobPassword?.let { job ->
        PasswordDialog(
            archiveName = job.archiveName,
            message = "Enter the password to continue this queued extraction.",
            wrong = false,
            onDismiss = { jobPassword = null },
            onUnlock = { password, remember ->
                vm.resumeJobWithPassword(job.id, password, remember)
                jobPassword = null
            },
        )
    }
}

private fun openNode(
    context: android.content.Context,
    node: SafNode,
    onError: (String) -> Unit,
) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(node.uri, node.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
        .onFailure {
            onError("No app on this device can open ${node.name}.")
        }
}
