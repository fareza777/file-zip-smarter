package com.hatchstudio.archive.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.core.ArchiveEntry
import com.hatchstudio.archive.core.ArchiveInfo
import com.hatchstudio.archive.core.FileCategory
import com.hatchstudio.archive.data.CategorySlice
import com.hatchstudio.archive.data.StorageLens
import com.hatchstudio.archive.data.buildStorageLens
import com.hatchstudio.archive.ui.EntryFilter
import com.hatchstudio.archive.ui.InspectorState
import com.hatchstudio.archive.ui.InspectorTab
import com.hatchstudio.archive.ui.components.CategoryIcon
import com.hatchstudio.archive.ui.components.FormatBadge
import com.hatchstudio.archive.ui.components.HatchCard
import com.hatchstudio.archive.ui.components.LabelValueRow
import com.hatchstudio.archive.ui.components.PrimaryAction
import com.hatchstudio.archive.ui.components.SegmentedControl
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.format.formatPercent
import com.hatchstudio.archive.ui.theme.Bone
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.Sea
import com.hatchstudio.archive.ui.theme.SpaceMono
import com.hatchstudio.archive.ui.theme.SpaceGrotesk

private val slicePalette = listOf(
    Color(0xFFB7D86A),
    Color(0xFF7FA7A1),
    Color(0xFFE0A85C),
    Color(0xFFB8877C),
    Color(0xFF9B95C9),
    Color(0xFF7E8A6B),
    Color(0xFFC9C3B2),
)

@Composable
fun InspectorScreen(
    state: InspectorState,
    onClose: () -> Unit,
    onTab: (InspectorTab) -> Unit,
    onQuery: (String) -> Unit,
    onFilter: (EntryFilter) -> Unit,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onPrepare: () -> Unit,
    onPreview: (Int) -> Unit,
    onShare: () -> Unit,
    thumbnails: Map<Int, Bitmap> = emptyMap(),
) {
    var showReclaimInfo by remember { mutableStateOf(false) }
    val info = state.info
    val lens = remember(info, state.entries) { buildStorageLens(info, state.entries) }
    val selectedBytes = state.selectedBytes
    val previewable = if (state.selected.size == 1) {
        state.entries.firstOrNull { it.index in state.selected && !it.isDirectory && it.canExtract }
    } else null

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Rounded.ArrowBack, "Close inspector", modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.displayName.ifBlank { "Archive" },
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${info.format.uppercase()} · ${formatBytes(info.archiveSize)}" +
                        if (info.encryptedCount > 0) " · encrypted" else "",
                    fontFamily = SpaceMono,
                    fontSize = 11.5.sp,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FormatBadge(info.format)
        }

        Spacer(Modifier.height(9.dp))
        SegmentedControl(
            options = listOf("Contents", "Storage Lens"),
            selectedIndex = state.tab.ordinal,
            onSelect = { onTab(if (it == 0) InspectorTab.CONTENTS else InspectorTab.LENS) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )

        if (state.tab == InspectorTab.CONTENTS) {
            ContentsTab(
                state = state,
                lens = lens,
                thumbnails = thumbnails,
                modifier = Modifier.weight(1f),
                onQuery = onQuery,
                onFilter = onFilter,
                onToggle = onToggle,
                onSelectAll = onSelectAll,
                onClearSelection = onClearSelection,
                onReclaimInfo = { showReclaimInfo = true },
            )
        } else {
            LensTab(
                info = info,
                lens = lens,
                state = state,
                modifier = Modifier.weight(1f),
                onReclaimInfo = { showReclaimInfo = true },
                onPrepare = onPrepare,
            )
        }

        if (state.tab == InspectorTab.CONTENTS) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                ),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                state.selected.isEmpty() -> "Nothing selected"
                                state.allSelected -> "All ${state.selected.size} files selected"
                                else -> "${state.selected.size} selected"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (state.selected.isEmpty()) "Tap a file to choose it"
                            else formatBytes(selectedBytes),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.selected.isNotEmpty()) {
                        IconButton(
                            onClick = onShare,
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Share,
                                if (state.selected.size == 1) "Share this file" else "Share selected files",
                                modifier = Modifier.size(21.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (previewable != null) {
                        IconButton(
                            onClick = { onPreview(previewable.index) },
                            modifier = Modifier.size(46.dp),
                        ) {
                            Icon(
                                Icons.Rounded.Visibility,
                                "Preview this file",
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .height(52.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .clickable(enabled = state.selected.isNotEmpty(), onClick = onPrepare),
                        shape = RoundedCornerShape(17.dp),
                        color = if (state.selected.isEmpty()) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else MaterialTheme.colorScheme.primary,
                        contentColor = if (state.selected.isEmpty()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (state.allSelected) "Extract all" else "Extract",
                                style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp),
                            )
                            Spacer(Modifier.width(9.dp))
                            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showReclaimInfo) {
        AlertDialog(
            onDismissRequest = { showReclaimInfo = false },
            title = { Text("How reclaim works") },
            text = {
                Column {
                    Text(
                        "ZipHatch reads a ZIP from its last entry backwards and truncates the source archive after each verified write. Your extracted files grow while the archive shrinks, so peak extra space is about the largest single file instead of the whole unpacked set.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Android SAF rarely exposes a writable file descriptor for a ZIP. When that is unavailable, ZipHatch stages the archive privately and reclaims the private copy, then removes the original only if you granted delete access. Otherwise it falls back to Smart extraction and never touches the source.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "A crash-safe journal resumes a partial reclaim instead of starting over. Reclaim is offered only for single-file, unencrypted ZIPs whose headers parse cleanly.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showReclaimInfo = false }) { Text("Got it") } },
        )
    }
}

@Composable
private fun ContentsTab(
    state: InspectorState,
    lens: StorageLens,
    thumbnails: Map<Int, Bitmap>,
    modifier: Modifier = Modifier,
    onQuery: (String) -> Unit,
    onFilter: (EntryFilter) -> Unit,
    onToggle: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onReclaimInfo: () -> Unit,
) {
    val entries = remember(state.entries, state.query, state.filter) {
        state.entries.filter { entry ->
            val matchesQuery = state.query.isBlank() ||
                entry.path.contains(state.query, ignoreCase = true)
            val matchesFilter = when (state.filter) {
                EntryFilter.ALL -> true
                EntryFilter.FILES -> !entry.isDirectory
                EntryFilter.IMAGES -> entry.category == FileCategory.Image
                EntryFilter.VIDEO -> entry.category == FileCategory.Video
                EntryFilter.DOCUMENTS -> entry.category == FileCategory.Document
                EntryFilter.ARCHIVES -> entry.category == FileCategory.Archive
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(12.dp))
        TextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            placeholder = { Text("Filter by name or folder", fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, modifier = Modifier.size(20.dp)) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Rounded.Close, "Clear", modifier = Modifier.size(19.dp))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
        Spacer(Modifier.height(9.dp))
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(EntryFilter.entries.toList()) { filter ->
                val selected = state.filter == filter
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(11.dp)).clickable { onFilter(filter) },
                    shape = RoundedCornerShape(11.dp),
                    color = if (selected) Ink else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) Bone else MaterialTheme.colorScheme.onSurfaceVariant,
                    border = if (selected) null else androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    ),
                ) {
                    Text(
                        filter.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${entries.size} of ${state.entries.size}",
                fontFamily = SpaceMono,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (state.allSelected) "Clear all" else "Select all",
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { if (state.allSelected) onClearSelection() else onSelectAll() }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (state.canReclaim) {
            ReclaimStrip(
                title = "Reclaim ready",
                body = "Delete the ZIP as it empties · +${formatBytes(lens.reclaimPeakEstimate)} peak",
                onClick = onReclaimInfo,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 2.dp,
                bottom = 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            items(entries, key = { it.index }) { entry ->
                EntryRow(
                    entry = entry,
                    selected = entry.index in state.selected,
                    thumbnail = thumbnails[entry.index],
                    onToggle = { onToggle(entry.index) },
                )
            }
        }
    }
}

@Composable
private fun ReclaimStrip(
    title: String,
    body: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Lime.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Shield, null, modifier = Modifier.size(18.dp), tint = Ink)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5.sp), color = Ink)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Ink.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.Info, null, modifier = Modifier.size(17.dp), tint = Ink.copy(alpha = 0.6f))
    }
}

@Composable
private fun EntryRow(
    entry: ArchiveEntry,
    selected: Boolean,
    thumbnail: Bitmap?,
    onToggle: () -> Unit,
) {
    val enabled = entry.canExtract
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        selected -> Lime
                        else -> MaterialTheme.colorScheme.surface
                    },
                )
                .then(
                    if (!selected || !enabled) {
                        Modifier.background(Color.Transparent)
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected && enabled) {
                Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp), tint = Ink)
            } else {
                Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                    drawCircle(
                        color = Color(0xFFB6B0A0),
                        style = Stroke(width = 1.4.dp.toPx()),
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                entry.isDirectory -> Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(18.dp))
                thumbnail != null -> Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                else -> CategoryIcon(entry.category, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isEncrypted) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(12.dp), tint = Sea)
                }
                if (entry.isSymlink) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Rounded.Link, null, modifier = Modifier.size(12.dp), tint = Sea)
                }
                if (entry.unsafePath) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Rounded.WarningAmber, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                buildString {
                    if (entry.isDirectory) append("Folder")
                    else append(formatBytes(entry.size))
                    if (!entry.isDirectory && entry.compressedSize > 0) {
                        append(" · packed ${formatBytes(entry.compressedSize)}")
                    }
                    if (entry.parent.isNotBlank()) append(" · ${entry.parent}")
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (entry.unsafePath) {
            Text(
                "UNSAFE",
                fontFamily = SpaceMono,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LensTab(
    info: ArchiveInfo,
    lens: StorageLens,
    state: InspectorState,
    modifier: Modifier = Modifier,
    onReclaimInfo: () -> Unit,
    onPrepare: () -> Unit,
) {
    val largest = lens.largest.firstOrNull()?.size ?: 0L
    val smartPeak = info.totalSize
    val reclaimPeak = lens.reclaimPeakEstimate

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 14.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            HatchCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("What is inside", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp))
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${info.fileCount} files · ${info.directoryCount} folders",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FormatBadge(info.format)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(
                            slices = lens.slices,
                            total = info.totalSize,
                            modifier = Modifier.size(132.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            lens.slices.take(4).forEachIndexed { index, slice ->
                                LegendRow(slice, slicePalette[index % slicePalette.size])
                            }
                            if (lens.slices.size > 4) {
                                Text(
                                    "+${lens.slices.size - 4} more",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LensFact("Archive", formatBytes(info.archiveSize), Modifier.weight(1f))
                        LensFact("Unpacked", formatBytes(info.totalSize), Modifier.weight(1f))
                        LensFact(
                            "Ratio",
                            if (info.totalSize > 0) {
                                formatPercent(info.archiveSize.toFloat() / info.totalSize.toFloat())
                            } else "—",
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            HatchCard(
                modifier = Modifier.fillMaxWidth(),
                color = if (info.reclaim.eligible) Lime.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (info.reclaim.eligible) Icons.Rounded.Shield else Icons.Rounded.Info,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = if (info.reclaim.eligible) Ink else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (info.reclaim.eligible) "Space forecast" else "Smart extraction only",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (info.reclaim.eligible) {
                            "ZipHatch can consume this ZIP while unpacking. The source ${
                                formatBytes(info.archiveSize)
                            } is returned to you."
                        } else {
                            info.reclaim.reason
                                ?: "This archive is written normally; the source is left untouched."
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    SpaceCompareRow(
                        label = "Smart peak",
                        value = smartPeak,
                        max = maxOf(smartPeak, reclaimPeak, 1),
                        color = Sea,
                    )
                    Spacer(Modifier.height(7.dp))
                    SpaceCompareRow(
                        label = if (info.reclaim.eligible) "Reclaim peak" else "Archive stays",
                        value = if (info.reclaim.eligible) reclaimPeak else info.archiveSize,
                        max = maxOf(smartPeak, reclaimPeak, 1),
                        color = if (info.reclaim.eligible) Lime else MaterialTheme.colorScheme.outline,
                    )
                    if (info.reclaim.eligible) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "≈ ${if (smartPeak > 0) reclaimPeak * 100 / smartPeak else 0}% of the Smart peak, based on the largest file (${formatBytes(largest)}).",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Ink.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap to read the safety model →",
                            modifier = Modifier.clickable(onClick = onReclaimInfo),
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                            color = Ink,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryAction(
                            text = "Reclaim this ZIP",
                            onClick = onPrepare,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.selected.isNotEmpty(),
                            showArrow = false,
                        )
                    }
                }
            }
        }

        item {
            HatchCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Largest files", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
                    Spacer(Modifier.height(8.dp))
                    if (lens.largest.isEmpty()) {
                        Text(
                            "No files inside.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        lens.largest.forEachIndexed { index, entry ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    (index + 1).toString().padStart(2, '0'),
                                    fontFamily = SpaceMono,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        entry.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        entry.parent.ifBlank { "root" },
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    formatBytes(entry.size),
                                    fontFamily = SpaceMono,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            HatchCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Checks", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
                    Spacer(Modifier.height(4.dp))
                    LabelValueRow("Duplicate files", if (lens.duplicateCount == 0) "None detected" else "${lens.duplicateCount} · ${formatBytes(lens.duplicateBytes)}")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    LabelValueRow("Unsafe paths", if (lens.unsafeCount == 0) "None" else "${lens.unsafeCount} blocked")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    LabelValueRow("Symlinks", if (lens.symlinkCount == 0) "None" else "${lens.symlinkCount} skipped")
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    LabelValueRow("Encrypted entries", if (info.encryptedCount == 0) "None" else info.encryptedCount.toString())
                    if (info.notes.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        info.notes.forEach { note ->
                            Text(
                                "• $note",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.WarningAmber,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "ZipHatch validates every path and never follows links outside the destination. Unsafe entries stay unchecked.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val StorageLens.reclaimPeakEstimate: Long
    get() = largest.firstOrNull()?.size ?: 0L

@Composable
private fun DonutChart(slices: List<CategorySlice>, total: Long, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2 + 1.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val sum = slices.sumOf { it.bytes }.coerceAtLeast(1)
            var start = -90f
            if (slices.isEmpty()) {
                drawArc(
                    color = Color(0xFFD8D2C2),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            } else {
                slices.forEachIndexed { index, slice ->
                    val sweep = 360f * slice.bytes.toFloat() / sum
                    drawArc(
                        color = slicePalette[index % slicePalette.size],
                        startAngle = start + 1.2f,
                        sweepAngle = (sweep - 2.4f).coerceAtLeast(0.6f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "UNPACKED",
                fontFamily = SpaceMono,
                fontSize = 9.5.sp,
                letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatBytes(total),
                fontFamily = SpaceGrotesk,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun LegendRow(slice: CategorySlice, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(
            slice.category.label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            formatBytes(slice.bytes),
        fontFamily = SpaceMono,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
}

@Composable
private fun LensFact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Text(
            label.uppercase(),
            fontFamily = SpaceMono,
            fontSize = 10.5.sp,
            letterSpacing = 0.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SpaceCompareRow(label: String, value: Long, max: Long, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                formatBytes(value),
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((value.toFloat() / max).coerceIn(0.02f, 1f))
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
