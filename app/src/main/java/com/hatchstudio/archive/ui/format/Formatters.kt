package com.hatchstudio.archive.ui.format

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return when {
        value >= 100 -> String.format(Locale.US, "%.0f %s", value, units[unit])
        value >= 10 -> String.format(Locale.US, "%.1f %s", value, units[unit])
        else -> String.format(Locale.US, "%.2f %s", value, units[unit])
    }
}

fun formatCount(value: Int): String =
    if (value >= 1000) String.format(Locale.US, "%,d", value) else value.toString()

fun timeAgo(millis: Long): String {
    if (millis <= 0) return "just now"
    val seconds = ((System.currentTimeMillis() - millis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3600} hr ago"
        seconds < 172_800 -> "yesterday"
        else -> "${seconds / 86_400} days ago"
    }
}

fun formatPercent(value: Float): String = "${(value * 100).toInt().coerceIn(0, 100)}%"

fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

fun formatLeft(remainingBytes: Long, bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "calculating…"
    val seconds = remainingBytes / bytesPerSecond
    return formatDuration(seconds * 1000)
}
