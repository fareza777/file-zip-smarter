package com.hatchstudio.archive.data

import java.io.File
import java.io.RandomAccessFile

/**
 * A split ZIP stores local-header offsets relative to the disk they start on.
 * Once ZipHatch has copied every volume into one contiguous private file, those
 * offsets must be translated to absolute file offsets before the Rust ZIP
 * reader can seek through them. The on-disk data is not changed.
 *
 * Only the classic ZIP32 end record is handled. Zip64 sets are left to the
 * caller's merge fallback instead of being patched in place.
 */
object ZipSplitNormalizer {
    private const val EOCD = 0x06054b50L
    private const val CENTRAL = 0x02014b50L

    fun normalize(joined: File, splitLength: Long): Boolean {
        if (splitLength <= 0 || joined.length() < 22) return false
        return runCatching {
            RandomAccessFile(joined, "rw").use { file ->
                val eocd = findEocd(file) ?: return false
                val disk = readU16(file, eocd + 4)
                val cdDisk = readU16(file, eocd + 6)
                val total = readU16(file, eocd + 10)
                val cdSize = readU32(file, eocd + 12)
                val cdOffset = readU32(file, eocd + 16)
                if (total == 0xFFFF || cdOffset == 0xFFFFFFFFL) return false
                if (disk == 0 && cdDisk == 0) return true

                val cdStart = cdDisk * splitLength + cdOffset
                if (cdStart < 0 || cdStart + cdSize > file.length()) return false
                if (readU32(file, cdStart) != CENTRAL) return false

                data class Patch(val diskField: Long, val offsetField: Long, val absolute: Long)
                val patches = ArrayList<Patch>(total)
                var cursor = cdStart
                repeat(total) {
                    if (readU32(file, cursor) != CENTRAL) return false
                    val nameLen = readU16(file, cursor + 28)
                    val extraLen = readU16(file, cursor + 30)
                    val commentLen = readU16(file, cursor + 32)
                    val fileDisk = readU16(file, cursor + 34)
                    val local = readU32(file, cursor + 42)
                    val absolute = fileDisk * splitLength + local
                    if (absolute < 0 || absolute >= file.length()) return false
                    patches += Patch(cursor + 34, cursor + 42, absolute)
                    cursor += 46 + nameLen + extraLen + commentLen
                    if (cursor > file.length()) return false
                }

                patches.forEach { patch ->
                    writeU16(file, patch.diskField, 0)
                    writeU32(file, patch.offsetField, patch.absolute)
                }
                writeU16(file, eocd + 4, 0)
                writeU16(file, eocd + 6, 0)
                writeU16(file, eocd + 8, total)
                writeU32(file, eocd + 16, cdStart)
                file.fd.sync()
                true
            }
        }.getOrDefault(false)
    }

    private fun findEocd(file: RandomAccessFile): Long? {
        val length = file.length()
        val start = maxOf(0L, length - 22 - 0xFFFF)
        var offset = length - 22
        while (offset >= start) {
            if (readU32(file, offset) == EOCD) {
                val comment = readU16(file, offset + 20)
                if (offset + 22 + comment <= length) return offset
            }
            offset--
        }
        return null
    }

    private fun readU16(file: RandomAccessFile, offset: Long): Int {
        file.seek(offset)
        val a = file.read()
        val b = file.read()
        if (a < 0 || b < 0) throw java.io.EOFException()
        return a or (b shl 8)
    }

    private fun readU32(file: RandomAccessFile, offset: Long): Long {
        file.seek(offset)
        var value = 0L
        repeat(4) { shift ->
            val byte = file.read()
            if (byte < 0) throw java.io.EOFException()
            value = value or (byte.toLong() shl (shift * 8))
        }
        return value
    }

    private fun writeU16(file: RandomAccessFile, offset: Long, value: Int) {
        file.seek(offset)
        file.write(value and 0xFF)
        file.write((value ushr 8) and 0xFF)
    }

    private fun writeU32(file: RandomAccessFile, offset: Long, value: Long) {
        file.seek(offset)
        repeat(4) { shift -> file.write(((value ushr (shift * 8)) and 0xFF).toInt()) }
    }
}
