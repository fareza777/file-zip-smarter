package com.hatchstudio.archive.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract

/**
 * Free-space checks for SAF destinations.
 *
 * DocumentsContract exposes no quota, so the tree document id is mapped back to
 * its StorageVolume. Primary storage shares the /data volume; removable cards
 * use their own volume. If the volume cannot be resolved we return null rather
 * than guess and block a valid extraction.
 */
object StorageSpace {
    const val HEADROOM: Long = 16L * 1024 * 1024

    fun requiredBytes(selectedBytes: Long, reclaimPeakBytes: Long, reclaim: Boolean): Long {
        val base = if (reclaim) reclaimPeakBytes else selectedBytes
        return (base + HEADROOM).coerceAtLeast(HEADROOM)
    }

    fun availableBytes(context: Context, treeUri: Uri?): Long? {
        if (treeUri == null) return null
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrNull() ?: return null
        val volumeId = documentId.substringBefore(':', "")
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        val volume = manager.storageVolumes.firstOrNull { candidate ->
            when {
                volumeId.isBlank() -> false
                volumeId.equals("primary", true) -> candidate.isPrimary
                else -> candidate.uuid?.equals(volumeId, ignoreCase = true) == true
            }
        }
        if (volume != null) {
            val uuid = volume.storageUuid
            if (uuid != null) {
                val allocatable = runCatching { manager.getAllocatableBytes(uuid) }.getOrNull()
                if (allocatable != null && allocatable > 0) return allocatable
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.let { directory ->
                    runCatching { StatFs(directory.absolutePath).availableBytes }
                        .getOrNull()
                        ?.let { if (it > 0) return it }
                }
            }
        }
        if (volume?.isPrimary == true || volumeId.equals("primary", true)) {
            return runCatching {
                StatFs(Environment.getDataDirectory().absolutePath).availableBytes
            }.getOrNull()
        }
        return null
    }

    fun hasRoom(available: Long?, required: Long): Boolean =
        available == null || available >= required
}
