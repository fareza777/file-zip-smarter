package com.hatchstudio.archive.data

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.SplitOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.Zip4jConfig
import net.lingala.zip4j.model.ZipModel
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Random
import java.util.zip.ZipFile as JvmZipFile

class ZipSplitNormalizerTest {
    @Test
    fun keepsAesPayloadReadableAfterOffsetRewrite() {
        val root = Files.createTempDirectory("hatch-split-aes-test").toFile()
        try {
            val payload = ByteArray(200_000).also { Random(9).nextBytes(it) }
            val splitRoot = File(root, "split").apply { mkdirs() }
            val tail = File(splitRoot, "vol.zip")
            val model = ZipModel().apply {
                setZipFile(tail)
                setSplitArchive(true)
                setSplitLength(65536)
            }
            val params = ZipParameters().apply {
                fileNameInZip = "secret.bin"
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
            ZipOutputStream(
                SplitOutputStream(tail, 65536),
                "secret".toCharArray(),
                Zip4jConfig(StandardCharsets.UTF_8, 4096, false),
                model,
            ).use { zip ->
                zip.putNextEntry(params)
                zip.write(payload)
                zip.closeEntry()
            }
            val parts = splitRoot.listFiles().orEmpty().sortedWith(
                compareBy<File> { part ->
                    part.name.substringAfterLast(".z").toIntOrNull() ?: Int.MAX_VALUE
                },
            )
            val joined = File(root, "joined.zip")
            FileOutputStream(joined).use { output ->
                parts.forEach { part -> part.inputStream().use { it.copyTo(output) } }
            }
            assertTrue(ZipSplitNormalizer.normalize(joined, parts.first().length()))
            val zip = ZipFile(joined, "secret".toCharArray())
            val header = zip.fileHeaders.first { it.fileName.endsWith("secret.bin") }
            val actual = zip.getInputStream(header).use { it.readBytes() }
            assertTrue(payload.contentEquals(actual))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun joinsSplitVolumesAndRewritesOffsets() {
        val root = Files.createTempDirectory("hatch-split-test").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val payload = ByteArray(200_000).also { Random(7).nextBytes(it) }
            File(source, "payload.bin").writeBytes(payload)
            val splitRoot = File(root, "split").apply { mkdirs() }
            val tail = File(splitRoot, "vol.zip")
            ZipFile(tail).createSplitZipFileFromFolder(
                source,
                ZipParameters(),
                true,
                65536,
            )
            val parts = splitRoot.listFiles()
                .orEmpty()
                .sortedWith(
                    compareBy<File> { part ->
                        part.name.substringAfterLast(".z").toIntOrNull() ?: Int.MAX_VALUE
                    },
                )
            assertTrue("expected split parts", parts.size > 1)
            val joined = File(root, "joined.zip")
            FileOutputStream(joined).use { output ->
                parts.forEach { part -> part.inputStream().use { it.copyTo(output) } }
            }
            assertTrue(ZipSplitNormalizer.normalize(joined, parts.first().length()))
            JvmZipFile(joined).use { zip ->
                val entry = zip.getEntry("source/payload.bin")
                assertTrue("payload entry should exist", entry != null)
                val actual = zip.getInputStream(entry).use { it.readBytes() }
                assertEquals(payload.size, actual.size)
                assertTrue(payload.contentEquals(actual))
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
