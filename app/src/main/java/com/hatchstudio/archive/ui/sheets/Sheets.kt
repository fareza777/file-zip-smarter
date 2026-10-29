package com.hatchstudio.archive.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hatchstudio.archive.R
import com.hatchstudio.archive.data.ConflictPolicy
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.ui.ExtractionDraft
import com.hatchstudio.archive.ui.components.BrandMark
import com.hatchstudio.archive.ui.components.PrimaryAction
import com.hatchstudio.archive.ui.components.SegmentedControl
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.theme.Bone
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono
import com.hatchstudio.archive.ui.theme.SpaceGrotesk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionSheet(
    draft: ExtractionDraft,
    onDismiss: () -> Unit,
    onPickDestination: () -> Unit,
    onMode: (JobMode) -> Unit,
    onConflict: (ConflictPolicy) -> Unit,
    onRemember: (Boolean) -> Unit,
    onRemoveSource: (Boolean) -> Unit,
    onPassword: (String) -> Unit,
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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (draft.isBatch) "Hatch ${draft.batchCount} archives" else "Hatch this archive",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        draft.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Lime),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Archive, null, modifier = Modifier.size(22.dp), tint = Ink)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "${draft.itemCount} item${if (draft.itemCount == 1) "" else "s"} · ${formatBytes(draft.selectedBytes)}",
                fontFamily = SpaceMono,
                fontSize = 12.sp,
                letterSpacing = 0.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                onClick = onPickDestination,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                ),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "LANDING IN",
                            fontFamily = SpaceMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            draft.destinationLabel ?: "Choose a destination folder",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "CHANGE",
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            val freeBytes = draft.freeBytes
            if (freeBytes != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (draft.enoughSpace) Icons.Rounded.Check else Icons.Rounded.Warning,
                        null,
                        modifier = Modifier.size(15.dp),
                        tint = if (draft.enoughSpace) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (draft.enoughSpace) {
                            "${formatBytes(freeBytes)} free · this job needs about ${formatBytes(draft.requiredBytes)}"
                        } else {
                            "Only ${formatBytes(freeBytes)} free · needs about ${formatBytes(draft.requiredBytes)}"
                        },
                        fontFamily = SpaceMono,
                        fontSize = 11.5.sp,
                        color = if (draft.enoughSpace) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (!draft.isBatch) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "HOW TO WRITE IT",
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ModeOption(
                    selected = draft.mode == JobMode.RECLAIM,
                    enabled = draft.canReclaim,
                    title = "Reclaim in place",
                    subtitle = draft.reclaimReason
                        ?: "Consumes the ZIP while unpacking. Peak extra space is about the largest file.",
                    badge = "SPACE",
                    icon = Icons.Rounded.Bolt,
                    onClick = { if (draft.canReclaim) onMode(JobMode.RECLAIM) },
                )
                Spacer(Modifier.height(8.dp))
                ModeOption(
                    selected = draft.mode == JobMode.SMART,
                    enabled = true,
                    title = "Smart extract",
                    subtitle = "Writes fresh files and leaves the source archive alone.",
                    badge = "SAFE",
                    icon = Icons.Rounded.Layers,
                    onClick = { onMode(JobMode.SMART) },
                )
                if (draft.mode == JobMode.SMART && draft.canRemoveSource) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(15.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onRemoveSource(!draft.removeSource) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = draft.removeSource,
                            onCheckedChange = onRemoveSource,
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary,
                                checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Remove source after verification",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            )
                            Text(
                                "Deletes the archive only once every entry is confirmed.",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (draft.isBatch && draft.canRemoveSource) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRemoveSource(!draft.removeSource) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = draft.removeSource,
                        onCheckedChange = onRemoveSource,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Remove each source after verification",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        )
                        Text(
                            "Each archive is deleted only once all of its entries are confirmed.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (draft.quickClean && !draft.canRemoveSource) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "This app shared the archive as read-only, so the source cannot be deleted. Hatch will unpack it and keep the original.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "IF A NAME EXISTS",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            SegmentedControl(
                options = listOf("Keep both", "Replace"),
                selectedIndex = if (draft.conflict == ConflictPolicy.KEEP_BOTH) 0 else 1,
                onSelect = {
                    onConflict(if (it == 0) ConflictPolicy.KEEP_BOTH else ConflictPolicy.REPLACE)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.needsPassword) {
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = onPassword,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Archive password", fontSize = 15.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    shape = RoundedCornerShape(15.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                    leadingIcon = {
                        Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(19.dp))
                    },
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRemember(!draft.rememberPassword) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = draft.rememberPassword,
                        onCheckedChange = onRemember,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                    Spacer(Modifier.width(2.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Remember this password",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        )
                        Text(
                            "Encrypted on this device only",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        if (draft.mode == JobMode.RECLAIM && draft.canReclaim) {
                            Lime.copy(alpha = 0.35f)
                        } else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Shield,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (draft.mode == JobMode.RECLAIM && draft.canReclaim) {
                        "The source ZIP is truncated only after each entry is verified."
                    } else {
                        "Android SAF writes stay inside the folder you granted. A failed write never corrupts your source."
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            PrimaryAction(
                text = if (draft.isBatch) {
                    if (draft.removeSource) {
                        "Extract ${draft.batchCount} & free space"
                    } else {
                        "Hatch ${draft.batchCount} archives"
                    }
                } else if (draft.mode == JobMode.RECLAIM) {
                    "Reclaim ${formatBytes(draft.selectedBytes)}"
                } else if (draft.removeSource) {
                    "Extract & free space"
                } else {
                    "Start hatching"
                },
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                enabled = draft.destinationTreeUri != null &&
                    draft.enoughSpace &&
                    (!draft.needsPassword || draft.password.isNotBlank()),
                showArrow = false,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    draft.destinationTreeUri == null -> "Choose a destination to continue."
                    !draft.enoughSpace -> {
                        "Free up space or pick another folder. Reclaim needs less room because it consumes the ZIP as it goes."
                    }
                    else -> "You can pause, resume or recover this job from the Queue."
                },
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    subtitle: String,
    badge: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    selected -> Lime.copy(alpha = 0.55f)
                    else -> MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (selected) Ink else MaterialTheme.colorScheme.surfaceVariant,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                modifier = Modifier.size(19.dp),
                tint = if (selected) Lime else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    badge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (selected) Ink else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                    fontFamily = SpaceMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.4.sp,
                    color = if (selected) Lime else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(20.dp), tint = Ink)
        }
    }
}

@Composable
fun OnboardingScreen(
    onChooseFolder: () -> Unit,
    onSkip: () -> Unit,
) {
    // The first screen follows the chosen theme: paper by default, ink at
    // night. A permanently black canvas clashed with the light default.
    val foreground = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(26.dp))
            BrandMark(inverted = false)
            Spacer(Modifier.weight(1f))
            Image(
                painter = painterResource(R.drawable.ic_splash_hatch),
                contentDescription = null,
                modifier = Modifier.size(126.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "Hatch\nwhat's inside.",
                fontFamily = SpaceGrotesk,
                fontWeight = FontWeight.SemiBold,
                fontSize = 52.sp,
                lineHeight = 50.sp,
                letterSpacing = (-2).sp,
                color = foreground,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Extract ZIP, RAR, 7z, TAR, XZ, ZSTD and BZ2 into your own folder. Reclaim a ZIP as it unpacks, or create one with AES-256 and split volumes.",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
                color = muted,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OnboardPill("SAVES SPACE")
                    OnboardPill("AES-256")
                    OnboardPill("ON-DEVICE")
                }
            Spacer(Modifier.weight(1f))
            PrimaryAction(
                text = "Choose a landing folder",
                onClick = onChooseFolder,
                modifier = Modifier.fillMaxWidth(),
                icon = {
                    Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(19.dp))
                },
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Explore first",
                    color = muted,
                    fontSize = 15.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "No account. No telemetry. Your files never leave the device.",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = muted.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun OnboardPill(text: String) {
    Text(
        text,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        letterSpacing = 0.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun PasswordDialog(
    archiveName: String,
    message: String,
    wrong: Boolean,
    onDismiss: () -> Unit,
    onUnlock: (String, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().imePadding(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(22.dp)) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        null,
                        modifier = Modifier.size(23.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (wrong) "That password did not open it" else "This archive is locked",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    archiveName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = if (wrong) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Password", fontSize = 15.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (password.isNotBlank()) onUnlock(password, remember) },
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    shape = RoundedCornerShape(15.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { remember = !remember }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = remember,
                        onCheckedChange = { remember = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "Remember in the encrypted vault",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("Cancel", fontSize = 15.sp)
                    }
                    PrimaryAction(
                        text = "Unlock",
                        onClick = { onUnlock(password, remember) },
                        modifier = Modifier.weight(1.3f),
                        enabled = password.isNotBlank(),
                        showArrow = false,
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingScrim(label: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 12.dp,
        ) {
            Column(
                Modifier.padding(horizontal = 26.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Reading headers locally",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
