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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hatchstudio.archive.data.ExtractionJob
import com.hatchstudio.archive.data.JobMode
import com.hatchstudio.archive.data.JobState
import com.hatchstudio.archive.ui.components.ArchiveTile
import com.hatchstudio.archive.ui.components.EmptyPanel
import com.hatchstudio.archive.ui.components.HatchCard
import com.hatchstudio.archive.ui.components.PrimaryAction
import com.hatchstudio.archive.ui.components.ProgressRing
import com.hatchstudio.archive.ui.components.StatBlock
import com.hatchstudio.archive.ui.format.formatBytes
import com.hatchstudio.archive.ui.format.formatPercent
import com.hatchstudio.archive.ui.format.timeAgo
import com.hatchstudio.archive.ui.theme.Ink
import com.hatchstudio.archive.ui.theme.Lime
import com.hatchstudio.archive.ui.theme.SpaceMono
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun QueueScreen(
    jobs: List<ExtractionJob>,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onResumeJob: (ExtractionJob) -> Unit,
    onPassword: (ExtractionJob) -> Unit,
    onRemove: (String) -> Unit,
    onClearFinished: () -> Unit,
    onOpenArchive: () -> Unit,
) {
    val active = jobs.firstOrNull { it.state.isActive }
    val waiting = jobs.filter {
        it.state == JobState.QUEUED || it.state == JobState.NEEDS_PASSWORD
    }
    val attention = jobs.filter {
        (it.state == JobState.FAILED || it.state == JobState.INTERRUPTED) && it.id != active?.id
    }
    val finished = jobs.filter { it.state == JobState.COMPLETED }
    val moved = jobs.filter { it.state == JobState.COMPLETED }.sumOf { it.totalBytes }
    val reclaimed = jobs.filter {
        it.state == JobState.COMPLETED &&
            (it.mode == JobMode.RECLAIM || (it.removeSource && it.sourceRemoved))
    }.sumOf { it.archiveSize }
    val finishedGroups = finished.take(12).groupBy { dayLabel(it.updatedAt) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 12.dp,
            bottom = 112.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Queue",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            active != null -> "One archive at a time, so storage stays predictable"
                            jobs.isEmpty() -> "Nothing running"
                            jobs.size == 1 -> "1 job in this session"
                            else -> "${jobs.size} jobs in this session"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (finished.isNotEmpty()) {
                    TextButton(onClick = onClearFinished) {
                        Text("Clear done", fontSize = 14.sp)
                    }
                }
            }
        }

        if (jobs.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HatchCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(15.dp)) {
                            StatBlock(formatBytes(moved), "Extracted")
                        }
                    }
                    HatchCard(Modifier.weight(1f)) {
                        Column(Modifier.padding(15.dp)) {
                            StatBlock(
                                formatBytes(reclaimed),
                                "Reclaimed",
                                accent = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        if (active != null) {
            item { ActiveJobCard(active, onPause, onResume, onStop) }
        }

        if (jobs.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                EmptyPanel(
                    title = "A calm queue",
                    body = "Open an archive and ZipHatch will show progress, speed and reclaimed space here. Jobs survive a reboot.",
                    icon = Icons.Rounded.Archive,
                ) {
                    PrimaryAction("Open an archive", onOpenArchive, showArrow = false)
                }
            }
        }

        if (waiting.isNotEmpty()) {
            item {
                QueueLabel("Waiting · ${waiting.size}")
            }
            items(waiting, key = { it.id }) { job ->
                WaitingRow(job, onPassword = { onPassword(job) }, onRemove = { onRemove(job.id) })
            }
        }

        if (attention.isNotEmpty()) {
            item { QueueLabel("Needs attention") }
            items(attention, key = { it.id }) { job ->
                AttentionRow(job, onResume = { onResumeJob(job) }, onRemove = { onRemove(job.id) })
            }
        }

        finishedGroups.forEach { (day, group) ->
            item(key = "finished-$day") { QueueLabel("Finished · $day") }
            items(group, key = { it.id }) { job ->
                FinishedRow(job, onRemove = { onRemove(job.id) })
            }
        }

        if (jobs.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        "A reclaim ZIP is deleted only after every entry is written and verified.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueLabel(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(top = 8.dp, bottom = 1.dp),
        fontFamily = SpaceMono,
        fontWeight = FontWeight.Bold,
        fontSize = 11.5.sp,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActiveJobCard(
    job: ExtractionJob,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    HatchCard(
        modifier = Modifier.fillMaxWidth(),
        color = if (job.reclaim) Lime else MaterialTheme.colorScheme.surface,
        contentColor = if (job.reclaim) Ink else MaterialTheme.colorScheme.onSurface,
        border = !job.reclaim,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (job.reclaim) Ink.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Archive,
                        null,
                        modifier = Modifier.size(24.dp),
                        tint = if (job.reclaim) Ink else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.archiveName,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        buildString {
                            append(if (job.reclaim) "In-place reclaim" else "Smart extract")
                            append(" · ")
                            append(job.format.uppercase())
                        },
                        fontFamily = SpaceMono,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        color = if (job.reclaim) Ink.copy(alpha = 0.66f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ProgressRing(
                    progress = job.progress,
                    size = 56.dp,
                    strokeWidth = 5.dp,
                    trackColor = if (job.reclaim) Ink.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    progressColor = if (job.reclaim) Ink else MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        formatPercent(job.progress),
                        fontFamily = SpaceMono,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (job.reclaim) Ink else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (job.state == JobState.PAUSED) "Paused" else "Extracting files",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatBytes(job.completedBytes)} of ${formatBytes(job.totalBytes)} · ${job.completedEntries}/${job.totalEntries} files",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = if (job.reclaim) Ink.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { job.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = if (job.reclaim) Ink else MaterialTheme.colorScheme.primary,
                trackColor = if (job.reclaim) Ink.copy(alpha = 0.14f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.height(46.dp).weight(1f).clip(RoundedCornerShape(15.dp))
                        .clickable(onClick = if (job.state == JobState.PAUSED) onResume else onPause),
                    shape = RoundedCornerShape(15.dp),
                    color = if (job.reclaim) Ink else MaterialTheme.colorScheme.primary,
                    contentColor = if (job.reclaim) Lime else MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(
                            if (job.state == JobState.PAUSED) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            null,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (job.state == JobState.PAUSED) "Resume" else "Pause",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))
                Surface(
                    modifier = Modifier.height(46.dp).clip(RoundedCornerShape(15.dp)).clickable(onClick = onStop),
                    shape = RoundedCornerShape(15.dp),
                    color = if (job.reclaim) Ink.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (job.reclaim) Ink else MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Stop", style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "→ ${job.destinationLabel} / ${job.outputFolder}",
                fontFamily = SpaceMono,
                fontSize = 11.sp,
                color = if (job.reclaim) Ink.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WaitingRow(job: ExtractionJob, onPassword: () -> Unit, onRemove: () -> Unit) {
    HatchCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ArchiveTile(job.format, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.archiveName,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (job.state == JobState.NEEDS_PASSWORD) "Password needed" else "Waiting for the runner",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (job.state == JobState.NEEDS_PASSWORD) {
                Surface(
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onPassword),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Unlock", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Icon(
                    Icons.Rounded.Close,
                    "Remove",
                    modifier = Modifier.size(20.dp).clickable(onClick = onRemove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AttentionRow(job: ExtractionJob, onResume: () -> Unit, onRemove: () -> Unit) {
    HatchCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(21.dp),
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        job.archiveName,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        job.message ?: if (job.state == JobState.INTERRUPTED) {
                            "Stopped before this job could finish. Resume to verify what landed."
                        } else "This job could not finish.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onResume) { Text("Resume", fontSize = 14.sp) }
                TextButton(onClick = onRemove) { Text("Remove", fontSize = 14.sp) }
            }
        }
    }
}

@Composable
private fun FinishedRow(job: ExtractionJob, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (job.reclaim) Lime.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                job.archiveName,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (job.reclaim) append("ZIP reclaimed · ")
                    else if (job.removeSource && job.sourceRemoved) append("Source removed · ")
                    append(formatBytes(job.completedBytes))
                    append(" · ")
                    append(timeAgo(job.updatedAt))
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Rounded.Close,
            "Remove",
            modifier = Modifier.size(18.dp).clickable(onClick = onRemove),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun dayLabel(millis: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return "Today"
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Yesterday"
    return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
}
