package com.hatchstudio.archive.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.R
import com.hatchstudio.archive.data.AppSettings
import com.hatchstudio.archive.data.ExtractionJob
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.data.JobState
import com.hatchstudio.archive.data.RecentArchive
import com.hatchstudio.archive.ui.components.ArchiveTile
import com.hatchstudio.archive.ui.components.BrandMark
import com.hatchstudio.archive.ui.components.FormatBadge
import com.hatchstudio.archive.ui.components.HatchCard
import com.hatchstudio.archive.ui.components.SectionHeader
import com.hatchstudio.archive.ui.components.StatBlock
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.format.formatPercent
import com.hatchstudio.archive.ui.format.timeAgo
import com.hatchstudio.archive.ui.theme.Bone
import com.hatchstudio.archive.ui.theme.Clay
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.InkSoft
import com.hatchstudio.archive.ui.theme.InstrumentSerif
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono

@Composable
fun HomeScreen(
    settings: AppSettings,
    recents: List<RecentArchive>,
    jobs: List<ExtractionJob>,
    onOpenArchive: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenRecent: (RecentArchive) -> Unit,
    onPickLanding: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val completed = jobs.count { it.state == JobState.COMPLETED }
    val reclaimed = jobs.filter {
        it.state == JobState.COMPLETED &&
            (it.mode == JobMode.RECLAIM || (it.removeSource && it.sourceRemoved))
    }.sumOf { it.archiveSize }
    val active = jobs.firstOrNull { it.state == JobState.RUNNING || it.state == JobState.PAUSED }
    val attention = jobs.firstOrNull {
        it.state == JobState.FAILED || it.state == JobState.INTERRUPTED || it.state == JobState.NEEDS_PASSWORD
    }
    val pending = jobs.count { it.state == JobState.QUEUED || it.state == JobState.NEEDS_PASSWORD }
    val returning = recents.isNotEmpty() || jobs.isNotEmpty() || settings.landingTreeUri != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 112.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BrandMark()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Lime),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "LOCAL",
                    fontFamily = SpaceMono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (returning) {
            CompactHeroCard(onOpenArchive = onOpenArchive)
        } else {
            HeroCard(onOpenArchive = onOpenArchive)
        }

        if (active != null || attention != null || pending > 0) {
            Spacer(Modifier.height(12.dp))
            ActiveMiniCard(
                active = active,
                attention = attention,
                pending = pending,
                onClick = onOpenQueue,
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HatchCard(Modifier.weight(1f)) {
                Column(Modifier.padding(15.dp)) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Lime.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Bolt, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.height(9.dp))
                    StatBlock(formatBytes(reclaimed), "Space reclaimed")
                }
            }
            HatchCard(Modifier.weight(1f)) {
                Column(Modifier.padding(15.dp)) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.height(9.dp))
                    StatBlock(completed.toString(), "Archives finished")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader(
            title = "Recent",
            action = "Browse",
            onAction = onOpenFiles,
        )
        Spacer(Modifier.height(9.dp))
        if (recents.isEmpty()) {
            RecentEmpty(onOpenArchive)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recents.take(1).forEach { recent ->
                    RecentRow(recent, onClick = { onOpenRecent(recent) })
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        LandingRow(settings = settings, onPickLanding = onPickLanding, onOpenFiles = onOpenFiles)
    }
}

@Composable
private fun HeroCard(onOpenArchive: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(276.dp),
        shape = RoundedCornerShape(30.dp),
        color = Ink,
        contentColor = Bone,
    ) {
        Box(Modifier.fillMaxSize()) {
            // Glow and faint hatching keep the panel from feeling like a plain card.
            Box(
                Modifier
                    .size(190.dp)
                    .align(Alignment.TopEnd)
                    .offsetForHero()
                    .clip(CircleShape)
                    .background(Lime.copy(alpha = 0.13f)),
            )
            Image(
                painter = painterResource(R.drawable.ic_splash_hatch),
                contentDescription = null,
                modifier = Modifier
                    .size(106.dp)
                    .align(Alignment.TopEnd)
                    .padding(top = 20.dp, end = 12.dp),
                contentScale = ContentScale.Fit,
            )
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Lime),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "SPACE-SAVING EXTRACTION",
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        letterSpacing = 1.1.sp,
                        color = Lime,
                    )
                }
                Spacer(Modifier.height(11.dp))
                Text(
                    text = "Unpack.\nLose the copy.",
                    fontFamily = com.hatchstudio.archive.ui.theme.SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 38.sp,
                    lineHeight = 38.sp,
                    letterSpacing = (-1.2).sp,
                    color = Bone,
                    modifier = Modifier.fillMaxWidth(0.78f),
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "Files land in your folder. Reclaim eats the ZIP as it unpacks.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 20.sp),
                    color = Bone.copy(alpha = 0.68f),
                    modifier = Modifier.fillMaxWidth(0.83f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onOpenArchive),
                    shape = RoundedCornerShape(16.dp),
                    color = Lime,
                    contentColor = Ink,
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Open an archive",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 17.sp),
                        )
                        Icon(
                            Icons.Rounded.ArrowForward,
                            null,
                            modifier = Modifier.size(20.dp),
                            tint = Ink.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

private fun Modifier.offsetForHero(): Modifier = this.then(
    Modifier.padding(top = 10.dp, end = 10.dp),
)

@Composable
private fun CompactHeroCard(onOpenArchive: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .clickable(onClick = onOpenArchive),
        shape = RoundedCornerShape(26.dp),
        color = Ink,
        contentColor = Bone,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Lime))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "SPACE-SAVING EXTRACTION",
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = Lime,
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Open an archive",
                    fontFamily = com.hatchstudio.archive.ui.theme.SpaceGrotesk,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 25.sp,
                    letterSpacing = (-0.6).sp,
                    color = Bone,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "ZIP, RAR, 7z · reclaim as it unpacks",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = Bone.copy(alpha = 0.62f),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Lime),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Add, null, modifier = Modifier.size(24.dp), tint = Ink)
            }
        }
    }
}

@Composable
private fun ActiveMiniCard(
    active: ExtractionJob?,
    attention: ExtractionJob?,
    pending: Int,
    onClick: () -> Unit,
) {
    val focus = active ?: attention
    val status = when {
        active == null -> null
        active.state == JobState.PAUSED -> "Paused"
        else -> "Extracting files"
    }
    val attentionLabel = when (attention?.state) {
        JobState.NEEDS_PASSWORD -> "Password needed"
        JobState.INTERRUPTED -> "Stopped early · review the queue"
        JobState.FAILED -> attention.message ?: "Could not finish · review the queue"
        else -> null
    }
    HatchCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = focus?.archiveName ?: "$pending archive${if (pending == 1) "" else "s"} waiting",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    active != null -> Column {
                        if (attentionLabel != null || status != null) {
                            Text(
                                status ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                        LinearProgressIndicator(
                            progress = { active.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        )
                    }
                    attention != null -> Text(
                        attentionLabel ?: "Tap to review the queue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    else -> Text(
                        "Tap to review the queue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (active != null) formatPercent(active.progress) else "VIEW",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RecentEmpty(onOpenArchive: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .clickable(onClick = onOpenArchive)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.FolderOpen,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "Open your first archive",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecentRow(recent: RecentArchive, onClick: () -> Unit) {
    HatchCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(44.dp)
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
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = recent.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${formatBytes(recent.size)} · ${recent.entryCount} entries · ${timeAgo(recent.openedAt)}",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            FormatBadge(recent.format)
        }
    }
}

@Composable
private fun LandingRow(
    settings: AppSettings,
    onPickLanding: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val hasLanding = settings.landingTreeUri != null
    HatchCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (hasLanding) onOpenFiles else onPickLanding),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, null, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (hasLanding) "LANDING FOLDER" else "SET A LANDING FOLDER",
                    fontFamily = SpaceMono,
                    fontSize = 11.5.sp,
                    letterSpacing = 0.7.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    settings.landingLabel ?: "Choose where ZipHatch lands your files",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (hasLanding) "OPEN" else "CHOOSE",
                fontFamily = SpaceMono,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
