package com.hatchstudio.archive.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional, archive-named password memory. Values are encrypted with an
 * Android Keystore AES-GCM key. Nothing leaves the device; the key never
 * leaves the keystore.
 */
class PasswordVault(context: Context) {
    private val prefs = context.getSharedPreferences("hatch_vault", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    val count: Int
        get() = readMap().size

    @Synchronized
    fun save(archiveName: String, password: String) {
        if (password.isBlank()) return
        val map = readMap().toMutableMap()
        map[archiveName] = encrypt(password)
        writeMap(map)
    }

    @Synchronized
    fun get(archiveName: String): String? {
        val encrypted = readMap()[archiveName] ?: return null
        return runCatching { decrypt(encrypted) }.getOrNull()
    }

    @Synchronized
    fun remove(archiveName: String) {
        val map = readMap().toMutableMap()
        map.remove(archiveName)
        writeMap(map)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_MAP).apply()
    }

    fun names(): List<String> = readMap().keys.sorted()

    private fun readMap(): Map<String, String> {
        val raw = prefs.getString(KEY_MAP, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key -> put(key, obj.getString(key)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeMap(map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(KEY_MAP, obj.toString()).apply()
    }

    private fun secretKey(): SecretKey {
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val joined = ByteArray(iv.size + encrypted.size)
        iv.copyInto(joined, 0)
        encrypted.copyInto(joined, iv.size)
        return Base64.encodeToString(joined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val joined = Base64.decode(value, Base64.NO_WRAP)
        if (joined.size <= IV_SIZE) return ""
        val iv = joined.copyOfRange(0, IV_SIZE)
        val cipherText = joined.copyOfRange(IV_SIZE, joined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "hatch.password.vault.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_MAP = "vault"
        private const val IV_SIZE = 12
    }
}
