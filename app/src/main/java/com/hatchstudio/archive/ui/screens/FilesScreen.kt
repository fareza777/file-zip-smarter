package com.hatchstudio.archive.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.ui.FileSort
import com.hatchstudio.archive.data.SafNode
import com.hatchstudio.archive.data.SafTree
import com.hatchstudio.archive.ui.FilesUiState
import com.hatchstudio.archive.ui.components.ArchiveTile
import com.hatchstudio.archive.ui.components.CategoryIcon
import com.hatchstudio.archive.ui.components.EmptyPanel
import com.hatchstudio.archive.ui.components.FormatBadge
import com.hatchstudio.archive.ui.components.PrimaryAction
import com.hatchstudio.archive.ui.components.RoundIconButton
import com.hatchstudio.archive.ui.components.ScreenHeader
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.format.timeAgo
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    state: FilesUiState,
    onBrowseRoot: () -> Unit,
    onBrowse: (SafNode) -> Unit,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (FileSort) -> Unit,
    onToggleGrid: () -> Unit,
    onToggleSelection: (SafNode) -> Unit,
    onClearSelection: () -> Unit,
    onOpenNode: (SafNode) -> Unit,
    onOpenArchive: (SafNode) -> Unit,
    onPickLanding: () -> Unit,
    onOpenArchivePicker: () -> Unit,
    onOpenCleaner: () -> Unit,
    onBatchExtract: (List<SafNode>) -> Unit,
    onDelete: (List<SafNode>) -> Unit,
    onRename: (SafNode, String) -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SafNode?>(null) }
    var renameText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val selectedNodes = state.nodes.filter { it.documentId in state.selected }
    val archives = selectedNodes.filter { it.isArchive }
    val selectionMode = selectedNodes.isNotEmpty()
    val sorted = remember(state.nodes, state.query, state.sort) {
        state.nodes
            .filter { it.name.contains(state.query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<SafNode> { it.isDirectory }.then(
                    when (state.sort) {
                        FileSort.NAME -> compareBy<SafNode> { it.name.lowercase() }
                        FileSort.SIZE -> compareByDescending<SafNode> { it.size }
                        FileSort.MODIFIED -> compareByDescending<SafNode> { it.modified }
                        FileSort.TYPE -> compareBy<SafNode> { it.extension }
                            .thenBy { it.name.lowercase() }
                    },
                ),
            )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        ScreenHeader(
            title = if (selectionMode) "${selectedNodes.size} selected" else "Files",
            subtitle = if (selectionMode) {
                if (archives.isNotEmpty()) "${archives.size} archive${if (archives.size == 1) "" else "s"} ready"
                else "Long press to select more"
            } else {
                state.treeLabel
            },
            actions = {
                if (selectionMode) {
                    RoundIconButton(Icons.Rounded.Close, "Clear selection", onClearSelection)
                } else {
                    RoundIconButton(Icons.Rounded.CleaningServices, "Space cleaner", onOpenCleaner)
                    RoundIconButton(Icons.Rounded.Search, "Search", onClick = { showSearch = !showSearch })
                    RoundIconButton(Icons.Rounded.Add, "Open archive", onOpenArchivePicker)
                    Box {
                        RoundIconButton(Icons.Rounded.MoreVert, "More", onClick = { showMenu = true })
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Refresh", fontSize = 16.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = { showMenu = false; onRefresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("Sort", fontSize = 16.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Sort, null) },
                                onClick = { showMenu = false; showSort = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (state.grid) "List view" else "Grid view", fontSize = 16.sp) },
                                leadingIcon = {
                                    Icon(if (state.grid) Icons.Rounded.ViewList else Icons.Rounded.GridOn, null)
                                },
                                onClick = { showMenu = false; onToggleGrid() },
                            )
                            DropdownMenuItem(
                                text = { Text("Change landing folder", fontSize = 16.sp) },
                                leadingIcon = { Icon(Icons.Rounded.Folder, null) },
                                onClick = { showMenu = false; onPickLanding() },
                            )
                        }
                    }
                }
            },
        )

        AnimatedVisibility(showSearch || state.query.isNotEmpty()) {
            Column {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search this folder", fontSize = 16.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(Icons.Rounded.Close, "Clear search", modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }
        }

        if (!state.landingSet) {
            Spacer(Modifier.height(24.dp))
            EmptyPanel(
                title = "Give Hatch a landing folder",
                body = "Pick a folder in Downloads or Documents. Every extraction and the file browser stay inside the access you grant.",
                icon = Icons.Rounded.FolderOpen,
            ) {
                PrimaryAction("Choose landing folder", onPickLanding, showArrow = false)
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))

        if (state.crumbs.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.crumbs.size > 1) {
                IconButton(
                    onClick = {
                        state.crumbs.dropLast(1).lastOrNull()?.let(onBrowse)
                    },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.ArrowBack, "Up", modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(2.dp))
            }
            // At the root the header subtitle already names this folder; a
            // single crumb chip would only repeat it. Show crumbs when nested.
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.crumbs.size <= 1) return@LazyRow
                items(state.crumbs) { crumb ->
                    val isLast = crumb == state.crumbs.lastOrNull()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = crumb.name,
                            modifier = Modifier
                                .clip(RoundedCornerShape(9.dp))
                                .background(
                                    if (isLast) MaterialTheme.colorScheme.surfaceVariant
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                .combinedClickable(
                                    onClick = { if (!isLast) onBrowse(crumb) },
                                )
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                            color = if (isLast) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        if (!isLast) {
                            Icon(
                                Icons.Rounded.ArrowForward,
                                null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        if (state.loading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Box(Modifier.weight(1f)) {
            if (state.error != null) {
                Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                    EmptyPanel(
                        title = "This folder did not open",
                        body = state.error,
                        icon = Icons.Rounded.Folder,
                    ) {
                        PrimaryAction("Try again", onRefresh, showArrow = false)
                    }
                }
            } else if (sorted.isEmpty() && !state.loading) {
                Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
                    EmptyPanel(
                        title = if (state.query.isNotBlank()) "No matches" else "This folder is empty",
                        body = if (state.query.isNotBlank()) "Try a shorter search."
                        else "Extracted files will appear here. Open an archive with the + button.",
                        icon = Icons.Rounded.Folder,
                    ) {
                        PrimaryAction("Open archive", onOpenArchivePicker, showArrow = false)
                    }
                }
            } else if (state.grid) {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sorted.size, key = { sorted[it].documentId }) { index ->
                        val node = sorted[index]
                        GridFileCard(
                            node = node,
                            selected = node.documentId in state.selected,
                            onClick = {
                                if (selectionMode) onToggleSelection(node) else onOpenNode(node)
                            },
                            onLongClick = {
                                if (!selectionMode) onToggleSelection(node)
                            },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(sorted, key = { it.documentId }) { node ->
                        FileRow(
                            node = node,
                            selected = node.documentId in state.selected,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) onToggleSelection(node)
                                else if (node.isArchive) onOpenArchive(node)
                                else onOpenNode(node)
                            },
                            onLongClick = { if (!selectionMode) onToggleSelection(node) },
                        )
                    }
                }
            }

            if (selectionMode) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    SelectionBar(
                        selected = selectedNodes,
                        onClear = onClearSelection,
                        onExtract = { onBatchExtract(archives) },
                        onDelete = { showDelete = true },
                        onRename = {
                            selectedNodes.singleOrNull()?.let {
                                renameTarget = it
                                renameText = it.name
                            }
                        },
                        onShare = {
                            selectedNodes.singleOrNull()?.let { node ->
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = node.mimeType
                                    putExtra(Intent.EXTRA_STREAM, node.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(send, "Share ${node.name}"))
                            }
                        },
                    )
                }
            }
        }
    }

    DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
        FileSort.entries.forEach { sort ->
            DropdownMenuItem(
                text = { Text(sort.label, fontSize = 16.sp) },
                trailingIcon = {
                    if (state.sort == sort) Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                },
                onClick = { onSortChange(sort); showSort = false },
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete ${selectedNodes.size} item${if (selectedNodes.size == 1) "" else "s"}?") },
            text = {
                Text(
                    "This removes the selected items through the folder provider. Folders are only removed when empty. This cannot be undone.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(selectedNodes)
                    showDelete = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Keep") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRename(target, renameText.trim())
                        renameTarget = null
                    },
                    enabled = renameText.trim().isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    node: SafNode,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val format = SafTree.archiveKind(node.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) Lime else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(Icons.Rounded.Check, null, tint = Ink, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        when {
            node.isDirectory -> Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(23.dp))
            }
            // The format is already shown in the trailing lime badge; a
            // second text tile here made every archive row look duplicated.
            format != null -> Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Archive,
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            else -> Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                CategoryIcon(
                    category = when {
                        node.extension in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") ->
                            com.hatchstudio.archive.core.FileCategory.Image
                        node.extension in setOf("mp4", "mkv", "mov", "webm") ->
                            com.hatchstudio.archive.core.FileCategory.Video
                        node.extension in setOf("mp3", "flac", "wav", "m4a") ->
                            com.hatchstudio.archive.core.FileCategory.Audio
                        else -> com.hatchstudio.archive.core.FileCategory.Other
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    if (node.isDirectory) append("Folder")
                    else append(formatBytes(node.size))
                    if (node.modified > 0) append(" · ${timeAgo(node.modified)}")
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (format != null && !selectionMode) {
            Spacer(Modifier.width(8.dp))
            FormatBadge(format)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridFileCard(
    node: SafNode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val format = SafTree.archiveKind(node.name)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(58.dp), contentAlignment = Alignment.Center) {
            when {
                node.isDirectory -> Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(30.dp))
                format != null -> ArchiveTile(format, size = 48.dp)
                else -> Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectionBar(
    selected: List<SafNode>,
    onClear: () -> Unit,
    onExtract: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
) {
    val archives = selected.filter { it.isArchive }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Close, "Clear selection", modifier = Modifier.size(20.dp))
            }
            Text(
                text = "${selected.size}",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (selected.size == 1) {
                IconButton(onClick = onShare, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.Share, "Share", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onRename, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Rounded.DriveFileRenameOutline, "Rename", modifier = Modifier.size(21.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Rounded.Delete, "Delete", modifier = Modifier.size(20.dp))
            }
            if (archives.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .combinedClickable(onClick = onExtract),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (archives.size == 1) "Hatch" else "Hatch ${archives.size}",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        )
                    }
                }
            }
        }
    }
}
