package com.hatchstudio.archive.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.BuildConfig
import com.hatchstudio.archive.data.AppSettings
import com.hatchstudio.archive.data.ConflictPolicy
import com.hatchstudio.archive.data.ThemeMode
import com.hatchstudio.archive.ui.components.HatchCard
import com.hatchstudio.archive.ui.components.SegmentedControl
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono

@Composable
fun SettingsScreen(
    settings: AppSettings,
    vaultCount: Int,
    onTheme: (ThemeMode) -> Unit,
    onPickLanding: () -> Unit,
    onClearLanding: () -> Unit,
    onConflict: (ConflictPolicy) -> Unit,
    onCreateFolder: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onClearVault: () -> Unit,
    onClearRecents: () -> Unit,
    onClearCaches: () -> Unit,
) {
    var showLicenses by remember { mutableStateOf(false) }
    var confirmClearVault by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 116.dp),
    ) {
        Text(
            "Studio",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Lime))
            Spacer(Modifier.width(7.dp))
            Text(
                "Hatch ${BuildConfig.VERSION_NAME} · local-first, no account",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsLabel("APPEARANCE")
        Spacer(Modifier.height(8.dp))
        HatchCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Rounded.Palette)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Theme", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
                        Text(
                            "Warm paper by day, ink at night",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeTile(
                        "Paper",
                        Icons.Rounded.LightMode,
                        Modifier.weight(1f),
                        selected = settings.theme == ThemeMode.LIGHT,
                        onClick = { onTheme(ThemeMode.LIGHT) },
                    )
                    ThemeTile(
                        "Ink",
                        Icons.Rounded.DarkMode,
                        Modifier.weight(1f),
                        selected = settings.theme == ThemeMode.DARK,
                        onClick = { onTheme(ThemeMode.DARK) },
                    )
                    ThemeTile(
                        "Auto",
                        Icons.Rounded.SettingsBrightness,
                        Modifier.weight(1f),
                        selected = settings.theme == ThemeMode.SYSTEM,
                        onClick = { onTheme(ThemeMode.SYSTEM) },
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SettingsLabel("LANDING FOLDER")
        Spacer(Modifier.height(8.dp))
        HatchCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsIcon(Icons.Rounded.Folder)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        settings.landingLabel ?: "No folder selected",
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Every write stays inside this grant",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (settings.landingTreeUri != null) {
                    Text(
                        "CHANGE",
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable(onClick = onPickLanding)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        "CHOOSE",
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable(onClick = onPickLanding)
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            if (settings.landingTreeUri != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                TextButton(
                    onClick = onClearLanding,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Disconnect folder", fontSize = 13.5.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SettingsLabel("EXTRACTION")
        Spacer(Modifier.height(8.dp))
        HatchCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(Icons.Rounded.Tune)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Name conflicts", style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp))
                        Text(
                            "What happens when a file already exists",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(11.dp))
                SegmentedControl(
                    options = listOf("Keep both", "Replace"),
                    selectedIndex = if (settings.conflict == ConflictPolicy.KEEP_BOTH) 0 else 1,
                    onSelect = {
                        onConflict(if (it == 0) ConflictPolicy.KEEP_BOTH else ConflictPolicy.REPLACE)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            SettingsSwitch(
                title = "Create a folder per archive",
                subtitle = "Keeps Downloads tidy. Off writes files directly.",
                checked = settings.createFolder,
                onChecked = onCreateFolder,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            SettingsSwitch(
                title = "Keep the screen awake",
                subtitle = "Only while a job is running",
                checked = settings.keepAwake,
                onChecked = onKeepAwake,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            SettingsSwitch(
                title = "Haptic feedback",
                subtitle = "A light tick on important actions",
                checked = settings.haptics,
                onChecked = onHaptics,
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsLabel("PRIVACY")
        Spacer(Modifier.height(8.dp))
        HatchCard(Modifier.fillMaxWidth()) {
            SettingsRow(
                icon = Icons.Rounded.Lock,
                title = "Password vault",
                subtitle = if (vaultCount == 0) "Empty" else "$vaultCount saved on this device",
                action = if (vaultCount > 0) "CLEAR" else null,
                onAction = { confirmClearVault = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            SettingsRow(
                icon = Icons.Rounded.History,
                title = "Recent archives",
                subtitle = "A local list of what you opened",
                action = "CLEAR",
                onAction = onClearRecents,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            SettingsRow(
                icon = Icons.Rounded.DeleteSweep,
                title = "Temporary staging",
                subtitle = "Free space used by multipart prep",
                action = "CLEAR",
                onAction = onClearCaches,
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsLabel("ABOUT")
        Spacer(Modifier.height(8.dp))
        HatchCard(Modifier.fillMaxWidth()) {
            SettingsRow(
                icon = Icons.Rounded.Memory,
                title = "Rust core + Compose",
                subtitle = "ZIP · RAR · 7Z · TAR · GZ · multipart",
                action = null,
                onAction = {},
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            SettingsRow(
                icon = Icons.Rounded.Security,
                title = "Open-source licenses",
                subtitle = "Includes the UnRAR extractor notice",
                action = "READ",
                onAction = { showLicenses = true },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.AutoAwesome,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "No analytics, no uploads. Hatch works fully offline.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "HATCH · SPACE-SAVING EXTRACTION",
            modifier = Modifier.fillMaxWidth(),
            fontFamily = SpaceMono,
            fontSize = 10.5.sp,
            letterSpacing = 1.1.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Licenses & notices") },
            text = {
                Column(Modifier.height(380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "Hatch's Rust core and Android app are open source. Copyright and license texts ship in the repository's NOTICE file.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("UnRAR", fontWeight = FontWeight.Bold)
                    Text(
                        "RAR extraction uses the UnRAR library, Copyright © 1993–2026 Alexander Roshal. The UnRAR source is used under its freeware distribution permission, which allows extraction and prohibits using it to reverse-engineer the RAR compression algorithm or to create a RAR compressor. Hatch only extracts RAR archives.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Other components", fontWeight = FontWeight.Bold)
                    Text(
                        "zip-rs, tar, flate2, sevenz-rust2, unrar, zstd, JNI bindings and Jetpack Compose are distributed under MIT, Apache-2.0 or BSD licenses. Their notices are retained in the source tree and in the APK's open-source notices.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Hatch does not include any proprietary compression code and does not create RAR archives.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }

    if (confirmClearVault) {
        AlertDialog(
            onDismissRequest = { confirmClearVault = false },
            title = { Text("Forget saved passwords?") },
            text = {
                Text(
                    "Hatch will delete every password in its encrypted local vault. Archives that need a password will ask again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearVault()
                    confirmClearVault = false
                }) { Text("Forget all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearVault = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun SettingsLabel(text: String) {
    Text(
        text,
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp))
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String?,
    onAction: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action != null, onClick = onAction)
            .padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIcon(icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.5.sp))
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (action != null) {
            Text(
                action,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // No chevron for informational rows: a disclosure that does nothing
        // reads as a broken tap target.
    }
}

@Composable
private fun ThemeTile(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
