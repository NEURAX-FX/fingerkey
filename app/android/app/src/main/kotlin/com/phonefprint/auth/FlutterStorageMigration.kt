package com.phonefprint.auth

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/** Read-only, one-time importer for the Flutter app's default secure-storage namespace. */
internal class FlutterStorageMigration(private val context: Context) {
    private val data = preferences("FlutterSecureStorage")
    private val keys = preferences("FlutterSecureKeyStorage")
    private val config = preferences("FlutterSecureStorageConfiguration:FlutterSecureStorage")
    private val globalConfig = preferences("FlutterSecureStorageConfiguration")

    fun hasRoster(): Boolean = data.contains(PREFIX + "roster")

    fun read(): DeviceSnapshot? {
        val entries = data.all
        // ESP keysets alone are created by plugin initialization even without ESP values.
        val values = entries.keys.filterNot { it.startsWith("__androidx_security_crypto_encrypted_prefs_") }
        if (values.isEmpty()) return null
        require(values.all { it.startsWith(PREFIX) && !it.endsWith("_BACKUP") }) {
            "Unsupported encrypted or interrupted Flutter storage migration"
        }
        require(listOf("ed25519_private_key", "roster", "sound_enabled").any { data.contains(PREFIX + it) }) {
            "Flutter storage contains unrecognized records; identity recovery is required"
        }
        require(config.all.keys.none { it.endsWith("_MIGRATED") } &&
            globalConfig.all.keys.none { it.endsWith("_MIGRATED") } &&
            keys.all.keys.none { it.endsWith("_BACKUP") }) { "Interrupted Flutter cipher migration" }
        val savedKey = marker("FlutterSecureSAlgorithmKey")
        val savedStorage = marker("FlutterSecureSAlgorithmStorage")
        // This is the plugin's historical fallback when either algorithm marker is absent.
        val keyAlgorithm = if (savedKey == null || savedStorage == null) "RSA_ECB_PKCS1Padding" else savedKey
        val storageAlgorithm = if (savedKey == null || savedStorage == null) "AES_CBC_PKCS7Padding" else savedStorage
        require(keyAlgorithm == "RSA_ECB_PKCS1Padding" || keyAlgorithm == "RSA_ECB_OAEPwithSHA_256andMGF1Padding") {
            "Unsupported Flutter key cipher: $keyAlgorithm"
        }
        require(storageAlgorithm == "AES_CBC_PKCS7Padding" || storageAlgorithm == "AES_GCM_NoPadding") {
            "Unsupported Flutter storage cipher: $storageAlgorithm"
        }
        val aes = unwrapKey(keyAlgorithm, storageAlgorithm)
        fun value(name: String): String? = data.getString(PREFIX + name, null)?.let {
            decryptValue(it, aes, storageAlgorithm)
        }
        val seed = value("ed25519_private_key")
        val servers = SnapshotCodec.legacyServers(value("roster"))
        require(seed != null || servers.isEmpty()) { "Flutter roster exists but the identity seed is missing" }
        val sound = when (value("sound_enabled")) {
            null, "1" -> true
            "0" -> false
            else -> error("Invalid Flutter sound preference")
        }
        return DeviceSnapshot(seed?.let { Identity(SnapshotCodec.decodeBase64(it)) } ?: Identity.generate(), servers, sound)
    }

    private fun unwrapKey(keyAlgorithm: String, storageAlgorithm: String): SecretKey {
        val wrappedName = if (storageAlgorithm == "AES_GCM_NoPadding") GCM_KEY else CBC_KEY
        val wrapped = keys.getString(wrappedName, null) ?: error("Flutter wrapped storage key is missing")
        val oaep = keyAlgorithm == "RSA_ECB_OAEPwithSHA_256andMGF1Padding"
        val alias = context.packageName + ".FlutterSecureStoragePluginKey" + if (oaep) "OAEP" else ""
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = store.getKey(alias, null) as? PrivateKey ?: error("Flutter AndroidKeyStore key is missing")
        val cipher = Cipher.getInstance(
            if (oaep) "RSA/ECB/OAEPPadding" else "RSA/ECB/PKCS1Padding",
            "AndroidKeyStoreBCWorkaround",
        )
        if (oaep) {
            cipher.init(Cipher.UNWRAP_MODE, privateKey,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        } else {
            cipher.init(Cipher.UNWRAP_MODE, privateKey)
        }
        return cipher.unwrap(SnapshotCodec.decodeBase64(wrapped), "AES", Cipher.SECRET_KEY) as SecretKey
    }

    private fun decryptValue(value: String, key: SecretKey, storageAlgorithm: String): String {
        val bytes = SnapshotCodec.decodeBase64(value)
        val gcm = storageAlgorithm == "AES_GCM_NoPadding"
        val ivSize = if (gcm) 12 else 16
        require(bytes.size >= ivSize + 16) { "Truncated Flutter ciphertext" }
        val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding")
        val iv = bytes.copyOfRange(0, ivSize)
        cipher.init(Cipher.DECRYPT_MODE, key, if (gcm) GCMParameterSpec(128, iv) else IvParameterSpec(iv))
        return cipher.doFinal(bytes, ivSize, bytes.size - ivSize).toString(Charsets.UTF_8)
    }

    private fun marker(name: String): String? = config.getString(name, null) ?: globalConfig.getString(name, null)
    private fun preferences(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    companion object {
        const val PREFIX = "VGhpcyBpcyB0aGUgcHJlZml4IGZvciBhIHNlY3VyZSBzdG9yYWdlCg_"
        const val CBC_KEY = "VGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"
        const val GCM_KEY = "AESVGhpcyBpcyB0aGUga2V5IGZvciBhIHNlY3VyZSBzdG9yYWdlIEFFUyBLZXkK"
    }
}
