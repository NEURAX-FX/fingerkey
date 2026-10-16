package com.phonefprint.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Serializes all instances and commits identity, roster and settings as one authenticated snapshot. */
class DeviceStore(context: Context) {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, FILE_NAME))
    private val boot = this.context.getSharedPreferences("fingerkey_boot", Context.MODE_PRIVATE)

    fun loadOrCreateIdentity(): Identity = access { load().identity }
    fun servers(): List<ServerConfig> = access { load().servers }
    fun soundEnabled(): Boolean = access { load().soundEnabled }

    fun saveServer(config: ServerConfig) = access {
        val server = SnapshotCodec.canonicalServer(config)
        val current = load()
        val next = current.servers.toMutableList()
        val index = next.indexOfFirst { it.fingerprint == server.fingerprint }
        if (index < 0) next.add(server) else next[index] = server
        save(current.copy(servers = next))
    }

    fun removeServer(id: String) = access {
        val canonical = SnapshotCodec.canonicalFingerprint(id)
        val current = load()
        save(current.copy(servers = current.servers.filterNot { it.id == canonical }))
    }

    fun setSoundEnabled(enabled: Boolean) = access {
        save(load().copy(soundEnabled = enabled))
    }

    /** An explicit reset replaces the old identity and clears every pin in the same commit. */
    fun resetIdentity() = access {
        save(DeviceSnapshot(Identity.generate(), emptyList(), load().soundEnabled))
    }

    /** A cheap boot hint only; service startup always validates the authenticated snapshot. */
    fun isConfigured(): Boolean = synchronized(LOCK) {
        if (hasSnapshot()) boot.getBoolean("configured", false)
        else context.getSharedPreferences("fingerkey", Context.MODE_PRIVATE).getBoolean("configured", false) ||
            FlutterStorageMigration(context).hasRoster()
    }

    private fun load(): DeviceSnapshot {
        if (hasSnapshot()) {
            val encrypted = file.openRead().use { it.readBytes() }
            return SnapshotCodec.decode(decrypt(encrypted, KEY_ALIAS)).also { updateBootHint(it) }
        }
        val flutter = FlutterStorageMigration(context).read()
        val native = readPreviousNativeStore(flutter?.identity)
        require(flutter == null || native == null || flutter.identity.publicKeyBase64 == native.identity.publicKeyBase64) {
            "Conflicting Flutter and native identities need manual recovery"
        }
        // Flutter is the installed app's source of truth, rather than a partial native attempt.
        val migrated = flutter ?: native ?: DeviceSnapshot(Identity.generate(), emptyList(), true)
        save(migrated)
        // Legacy preferences are deliberately left intact; this snapshot is now authoritative.
        return migrated
    }

    private fun hasSnapshot(): Boolean = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    private fun save(snapshot: DeviceSnapshot) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore enforces randomized encryption: the provider MUST generate the IV.
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true))
        val iv = cipher.iv
        require(iv.size == 12) { "Unexpected AES-GCM IV size" }
        val encrypted = iv + cipher.doFinal(SnapshotCodec.encode(snapshot))
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
        updateBootHint(snapshot)
    }

    private fun updateBootHint(snapshot: DeviceSnapshot) {
        if (boot.getBoolean("configured", false) != snapshot.servers.isNotEmpty()) {
            boot.edit().putBoolean("configured", snapshot.servers.isNotEmpty()).apply()
        }
    }

    private fun decrypt(bytes: ByteArray, alias: String): ByteArray {
        require(bytes.size >= 28) { "Truncated identity snapshot" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false, alias = alias), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    private fun encryptionKey(create: Boolean, alias: String = KEY_ALIAS): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return existing
        check(existing == null && create && !hasSnapshot()) { "AndroidKeyStore wrapping key is missing or invalid" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    /** Imports the short-lived native layout without relying on a JCA Ed25519 provider. */
    private fun readPreviousNativeStore(fallbackIdentity: Identity?): DeviceSnapshot? {
        val secure = context.getSharedPreferences("fingerkey_secure", Context.MODE_PRIVATE)
        val prefs = context.getSharedPreferences("fingerkey", Context.MODE_PRIVATE)
        val privateKey = secure.getString("private_key", null)
        val publicKey = secure.getString("public_key", null)
        val rawServers = prefs.getString("servers", null)
        if (privateKey == null && publicKey == null && rawServers == null && !prefs.contains("sound_enabled")) {
            check(!prefs.getBoolean("configured", false)) { "Native configured marker exists but identity data is missing" }
            return null
        }
        require((privateKey == null) == (publicKey == null)) { "Incomplete native identity" }
        val servers = rawServers?.let(SnapshotCodec::nativeServers) ?: emptyList()
        require(privateKey != null || servers.isEmpty()) { "Native roster exists but the identity is missing" }
        val identity = if (privateKey != null) {
            val key = PrivateKeyFactory.createKey(decrypt(SnapshotCodec.decodeBase64(privateKey), "fingerkey_identity_wrap"))
                as Ed25519PrivateKeyParameters
            Identity(key.encoded).also {
                require(it.publicKeyBase64 == publicKey) { "Native public key does not match private key" }
            }
        } else fallbackIdentity ?: Identity.generate()
        return DeviceSnapshot(identity, servers, prefs.getBoolean("sound_enabled", true))
    }

    private inline fun <T> access(block: () -> T): T = synchronized(LOCK) {
        try {
            block()
        } catch (failure: StorageRecoveryException) {
            throw failure
        } catch (failure: Exception) {
            throw StorageRecoveryException(
                "Cannot open or save FingerKey's identity and paired servers. Existing data has not been reset. " +
                    "Restore the original app data and AndroidKeyStore on this device, or complete any pending " +
                    "secure-storage migration in the previous app before upgrading. Details: ${failure.message}",
                failure,
            )
        }
    }

    internal companion object {
        const val FILE_NAME = "fingerkey-device-v1.bin"
        const val KEY_ALIAS = "fingerkey_device_snapshot_v1"
        private val LOCK = Any()
    }
}
