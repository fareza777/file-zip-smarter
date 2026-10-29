package com.hatchstudio.archive.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.data.ZipCompression
import com.hatchstudio.archive.data.ZipCreator
import com.hatchstudio.archive.ui.ZipCreateDraft
import com.hatchstudio.archive.ui.ZipCreateProgress
import com.hatchstudio.archive.ui.components.PrimaryAction
import com.hatchstudio.archive.ui.components.SegmentedControl
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono

private val SPLIT_OPTIONS = listOf(
    0L,
    100L * 1024 * 1024,
    500L * 1024 * 1024,
    1024L * 1024 * 1024,
    2048L * 1024 * 1024,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateZipSheet(
    draft: ZipCreateDraft,
    progress: ZipCreateProgress?,
    onDismiss: () -> Unit,
    onName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onCompression: (ZipCompression) -> Unit,
    onSplit: (Long) -> Unit,
    onPickDestination: () -> Unit,
    onStart: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var revealPassword by remember { mutableStateOf(false) }
    var customMb by remember {
        mutableStateOf(
            if (draft.splitBytes > 0 && SPLIT_OPTIONS.none { it == draft.splitBytes }) {
                (draft.splitBytes / (1024L * 1024)).toString()
            } else "",
        )
    }
    val running = progress != null

    ModalBottomSheet(
        onDismissRequest = { if (!running) onDismiss() },
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
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Lime),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Archive,
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Create a ZIP", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (draft.scanning) "Scanning the selection…" else
                            "${draft.fileCount} files · ${formatBytes(draft.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "ARCHIVE NAME",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = draft.name,
                onValueChange = onName,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !running,
                placeholder = { Text("ZipHatch.zip", fontSize = 15.sp) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                onClick = { if (!running) onPickDestination() },
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
                            "WRITE TO",
                            fontFamily = SpaceMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            draft.destinationLabel ?: "Choose a folder",
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                            maxLines = 1,
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

            Spacer(Modifier.height(14.dp))
            Text(
                "PASSWORD",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = draft.password,
                onValueChange = onPassword,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !running,
                placeholder = { Text("Optional · AES-256", fontSize = 15.sp) },
                visualTransformation = if (revealPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                shape = RoundedCornerShape(15.dp),
                leadingIcon = { Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(19.dp)) },
                trailingIcon = {
                    IconButton(onClick = { revealPassword = !revealPassword }) {
                        Icon(
                            if (revealPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (revealPassword) "Hide password" else "Show password",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                supportingText = {
                    Text(
                        if (draft.encrypted) "AES-256 encryption · works with ZArchiver, 7-Zip and WinRAR"
                        else "Leave empty for a normal ZIP",
                        fontSize = 11.5.sp,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "COMPRESSION",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            SegmentedControl(
                options = ZipCompression.entries.map { it.label },
                selectedIndex = ZipCompression.entries.indexOf(draft.compression).coerceAtLeast(0),
                onSelect = { if (!running) onCompression(ZipCompression.entries[it]) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                when (draft.compression) {
                    ZipCompression.STORE -> "No compression. Fastest, but the ZIP stays about the same size."
                    ZipCompression.BALANCED -> "Default DEFLATE. A good balance of speed and size."
                    ZipCompression.MAXIMUM -> "Smaller files, slower on large folders."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(14.dp))
            Text(
                "SPLIT VOLUME",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SPLIT_OPTIONS.forEach { bytes ->
                    val selected = draft.splitBytes == bytes
                    Surface(
                        shape = RoundedCornerShape(11.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        ),
                        onClick = {
                            if (!running) {
                                customMb = ""
                                onSplit(bytes)
                            }
                        },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected) {
                                Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                ZipCreator.splitLabel(bytes),
                                fontFamily = SpaceMono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(
                value = customMb,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(5)
                    customMb = digits
                    digits.toLongOrNull()?.takeIf { it > 0 }?.let { mb ->
                        onSplit(mb * 1024L * 1024L)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !running,
                label = { Text("Custom size", fontSize = 13.sp) },
                placeholder = { Text("e.g. 250", fontSize = 14.sp) },
                trailingIcon = {
                    Text(
                        "MB",
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                shape = RoundedCornerShape(15.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                if (draft.splitBytes > 0) {
                    "Creates .z01, .z02 … plus a final .zip that ZipHatch and ZArchiver can join."
                } else {
                    "One ZIP file. Split volumes are useful for chat apps and FAT32 drives."
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (running) {
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.total <= 0) 0f
                        else (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    if (progress.total > 0) {
                        "Zipping ${progress.done} of ${progress.total} · ${formatBytes(progress.bytes)} read"
                    } else {
                        "Zipping ${progress.done} items · ${formatBytes(progress.bytes)} read"
                    },
                    fontFamily = SpaceMono,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Spacer(Modifier.height(18.dp))
                PrimaryAction(
                    text = if (draft.splitBytes > 0) "Create split ZIP" else "Create ZIP",
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !draft.scanning && draft.destinationDocId != null,
                    showArrow = false,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "ZipHatch writes into private scratch space first, then moves the finished archive into your folder. Nothing is overwritten.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

