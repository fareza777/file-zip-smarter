package com.hatchstudio.archive.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.data.CleanerItem
import com.hatchstudio.archive.ui.CleanerState
import com.hatchstudio.archive.ui.components.ArchiveTile
import com.hatchstudio.archive.ui.components.EmptyPanel
import com.hatchstudio.archive.ui.components.PrimaryAction
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono

/**
 * Batch "extract and delete the source" review. Everything here is opt-in:
 * the user sees each archive, its folder and the bytes that would be returned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerSheet(
    state: CleanerState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onStart: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Lime),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.CleaningServices, null, tint = Ink, modifier = Modifier.size(23.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Space cleaner", style = MaterialTheme.typography.headlineMedium.copy(fontSize = 27.sp))
                    Text(
                        "Extract archives, verify them, then remove the source.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, "Close", modifier = Modifier.size(21.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            when {
                state.scanning -> {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(38.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Scanning ${state.folderLabel.ifBlank { "your landing folder" }}",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.foldersScanned} folders checked · no files are moved yet",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.error != null -> {
                    EmptyPanel(
                        title = "The scan stopped",
                        body = state.error,
                        icon = Icons.Rounded.CleaningServices,
                        action = {
                            PrimaryAction(
                                text = "Try again",
                                onClick = onRetry,
                                showArrow = false,
                            )
                        },
                    )
                }

                state.items.isEmpty() -> {
                    EmptyPanel(
                        title = "Nothing to clean here",
                        body = "No whole ZIP, RAR, 7z, TAR, GZIP, XZ, ZSTD or BZ2 archives were found in ${state.folderLabel.ifBlank { "this folder" }}. Split sets are skipped so no volume is orphaned.",
                        icon = Icons.Rounded.CleaningServices,
                        action = {
                            PrimaryAction(
                                text = "Scan again",
                                onClick = onRetry,
                                showArrow = false,
                            )
                        },
                    )
                }

                else -> {
                    SummaryCard(state)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${state.items.size} archive${if (state.items.size == 1) "" else "s"} · ${formatBytes(state.totalBytes)}",
                            fontFamily = SpaceMono,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (state.selected.size == state.items.size) "Clear all" else "Select all",
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onToggleAll)
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.5.sp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            CleanerRow(
                                item = item,
                                selected = item.id in state.selected,
                                onClick = { onToggle(item.id) },
                            )
                        }
                        if (state.truncated) {
                            item {
                                Text(
                                    "Showing the first ${state.items.size} archives found. Clean these, then scan again.",
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PrimaryAction(
                        text = if (state.selected.isEmpty()) {
                            "Choose archives to free space"
                        } else {
                            "Extract ${state.selected.size} & free ${formatBytes(state.selectedBytes)}"
                        },
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.selected.isNotEmpty(),
                        showArrow = false,
                        icon = { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(19.dp)) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "ZipHatch writes every file first, confirms the job, and only then deletes the source archive. Keep enough free space for the unpacked files.",
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(state: CleanerState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (state.selected.isEmpty()) {
            MaterialTheme.colorScheme.surface
        } else Lime.copy(alpha = 0.42f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.selected.isEmpty()) "POTENTIAL TO FREE" else "READY TO FREE",
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    letterSpacing = 1.1.sp,
                    color = if (state.selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else Ink.copy(alpha = 0.65f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    formatBytes(if (state.selected.isEmpty()) state.totalBytes else state.selectedBytes),
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (state.selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else Ink,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${state.selected.size} selected",
                    fontFamily = SpaceMono,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (state.selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else Ink
                )
                Text(
                    state.folderLabel.ifBlank { "Landing folder" },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = if (state.selected.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else Ink.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CleanerRow(item: CleanerItem, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Lime.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {            Box(
                Modifier
                    .size(23.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Lime
                        else MaterialTheme.colorScheme.surface,
                    )
                    .border(
                        1.5.dp,
                        if (selected) Ink.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
            if (selected) {
                Icon(Icons.Rounded.Check, null, modifier = Modifier.size(15.dp), tint = Ink)
            }
        }
        Spacer(Modifier.width(9.dp))
        ArchiveTile(format = item.node.extension.uppercase().ifBlank { "ARC" }, size = 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.node.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.folder,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatBytes(item.size),
            fontFamily = SpaceMono,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
