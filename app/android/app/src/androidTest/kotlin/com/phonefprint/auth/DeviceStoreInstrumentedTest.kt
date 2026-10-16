package com.phonefprint.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.MGF1ParameterSpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Run on API 24 as well as a current API: these exercise real AndroidKeyStore behavior. */
@RunWith(AndroidJUnit4::class)
class DeviceStoreInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val snapshot: File get() = File(context.noBackupFilesDir, DeviceStore.FILE_NAME)
    private val store: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val fingerprint = "ab".repeat(32)

    @Before @After fun clearStorage() {
        check(context.packageName.endsWith(".storagecheck")) { "Storage tests must never run against a real user installation" }
        listOf("", ".bak", ".new").forEach { File(snapshot.path + it).delete() }
        listOf("fingerkey", "fingerkey_secure", "fingerkey_boot", "FlutterSecureStorage", "FlutterSecureKeyStorage",
            "FlutterSecureStorageConfiguration", "FlutterSecureStorageConfiguration:FlutterSecureStorage").forEach {
            assertTrue(prefs(it).edit().clear().commit())
        }
        listOf(DeviceStore.KEY_ALIAS, "fingerkey_identity_wrap", context.packageName + ".FlutterSecureStoragePluginKey",
            context.packageName + ".FlutterSecureStoragePluginKeyOAEP").forEach { store.deleteEntry(it) }
    }

    @Test fun keyReloadUsesProviderGeneratedIvAndSamePublicKey() {
        val first = DeviceStore(context).loadOrCreateIdentity()
        val firstCiphertext = snapshot.readBytes()
        DeviceStore(context).setSoundEnabled(false)
        assertFalse(firstCiphertext.take(12) == snapshot.readBytes().take(12))
        val loaded = DeviceStore(context).loadOrCreateIdentity()
        assertEquals(first.publicKeyBase64, loaded.publicKeyBase64)
        val message = "approve".toByteArray()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Base64.decode(first.publicKeyBase64), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(loaded.sign(message)))
    }

    @Test fun resetPreservesSoundAndClearsEveryServer() {
        val device = DeviceStore(context)
        val original = device.loadOrCreateIdentity().publicKeyBase64
        device.saveServer(server())
        device.setSoundEnabled(false)
        device.resetIdentity()
        val reload = DeviceStore(context)
        assertNotEquals(original, reload.loadOrCreateIdentity().publicKeyBase64)
        assertTrue(reload.servers().isEmpty())
        assertFalse(reload.soundEnabled())
        assertFalse(reload.isConfigured())
    }

    @Test fun corruptionAndMissingKeystoreNeverCreateReplacementIdentity() {
        DeviceStore(context).loadOrCreateIdentity()
        val bytes = snapshot.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        snapshot.writeBytes(bytes)
        recoveryFailure { DeviceStore(context).loadOrCreateIdentity() }
        assertArrayEquals(bytes, snapshot.readBytes())
        store.deleteEntry(DeviceStore.KEY_ALIAS)
        recoveryFailure { DeviceStore(context).loadOrCreateIdentity() }
        assertFalse(store.containsAlias(DeviceStore.KEY_ALIAS))
        assertArrayEquals(bytes, snapshot.readBytes())
    }

    @Test fun atomicBackupRestoresLastCompleteIdentity() {
        val original = DeviceStore(context).loadOrCreateIdentity().publicKeyBase64
        File(snapshot.path + ".bak").writeBytes(snapshot.readBytes())
        snapshot.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(original, DeviceStore(context).loadOrCreateIdentity().publicKeyBase64)
    }

    @Test fun concurrentStoreInstancesDoNotLoseRosterUpdates() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..16).map { index ->
                executor.submit {
                    val fp = index.toString(16).padStart(64, '0')
                    DeviceStore(context).saveServer(ServerConfig(fp, "host$index", "host$index", 443, fp))
                }
            }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(16, DeviceStore(context).servers().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun canonicalFingerprintsUpdateRatherThanDuplicate() {
        val device = DeviceStore(context)
        device.saveServer(server().copy(id = "arbitrary", fingerprint = fingerprint.uppercase()))
        device.saveServer(server().copy(name = "updated"))
        assertEquals(listOf(server().copy(name = "updated")), DeviceStore(context).servers())
        device.removeServer(fingerprint.uppercase())
        assertTrue(device.servers().isEmpty())
    }

    @Test fun migratesBothRsaCiphersWithBothStorageCiphersAndGlobalMarkers() {
        for (oaep in listOf(false, true)) for (gcm in listOf(false, true)) {
            clearStorage()
            val expected = legacyFixture(oaep, gcm, globalMarkers = true)
            val device = DeviceStore(context)
            assertEquals(expected.publicKeyBase64, device.loadOrCreateIdentity().publicKeyBase64)
            assertEquals(listOf(server()), device.servers())
            assertFalse(device.soundEnabled())
            assertTrue(prefs("FlutterSecureStorage").contains(FlutterStorageMigration.PREFIX + "ed25519_private_key"))
            device.resetIdentity()
            assertNotEquals(expected.publicKeyBase64, DeviceStore(context).loadOrCreateIdentity().publicKeyBase64)
            assertTrue(DeviceStore(context).servers().isEmpty())
        }
    }

    @Test fun migratesHistoricalMarkerlessCbcAndCurrentNamespacedOaepGcm() {
        val historical = legacyFixture(oaep = false, gcm = false, markers = false)
        assertEquals(historical.publicKeyBase64, DeviceStore(context).loadOrCreateIdentity().publicKeyBase64)
        clearStorage()
        val current = legacyFixture(oaep = true, gcm = true)
        // Namespaced markers must take precedence over conflicting global markers.
        assertTrue(prefs("FlutterSecureStorageConfiguration").edit()
            .putString("FlutterSecureSAlgorithmKey", "RSA_ECB_PKCS1Padding")
            .putString("FlutterSecureSAlgorithmStorage", "AES_CBC_PKCS7Padding").commit())
        assertEquals(current.publicKeyBase64, DeviceStore(context).loadOrCreateIdentity().publicKeyBase64)
    }

    @Test fun malformedMigrationIsVisibleAndLeavesLegacyDataUntouched() {
        legacyFixture(oaep = true, gcm = true)
        val legacy = prefs("FlutterSecureStorage")
        assertTrue(legacy.edit().putString(FlutterStorageMigration.PREFIX + "roster", "invalid ciphertext").commit())
        val original = legacy.all
        recoveryFailure { DeviceStore(context).loadOrCreateIdentity() }
        assertEquals(original, legacy.all)
        assertFalse(snapshot.exists())
        assertFalse(store.containsAlias(DeviceStore.KEY_ALIAS))
    }

    @Test fun unsupportedEncryptedPreferencesAndPartialNativeKeysNeedRecovery() {
        assertTrue(prefs("FlutterSecureStorage").edit().putString("encrypted-key-name", "ciphertext").commit())
        recoveryFailure { DeviceStore(context).loadOrCreateIdentity() }
        assertFalse(snapshot.exists())
        clearStorage()
        assertTrue(prefs("fingerkey_secure").edit().putString("public_key", "orphaned").commit())
        recoveryFailure { DeviceStore(context).loadOrCreateIdentity() }
        assertFalse(snapshot.exists())
    }

    private fun legacyFixture(
        oaep: Boolean,
        gcm: Boolean,
        markers: Boolean = true,
        globalMarkers: Boolean = false,
    ): Identity {
        val identity = Identity(ByteArray(32) { it.toByte() })
        val alias = context.packageName + ".FlutterSecureStoragePluginKey" + if (oaep) "OAEP" else ""
        val generator = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setEncryptionPaddings(if (oaep) KeyProperties.ENCRYPTION_PADDING_RSA_OAEP else KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
            .build())
        val pair = generator.generateKeyPair()
        val wrap = Cipher.getInstance(if (oaep) "RSA/ECB/OAEPPadding" else "RSA/ECB/PKCS1Padding")
        if (oaep) wrap.init(Cipher.WRAP_MODE, pair.public,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        else wrap.init(Cipher.WRAP_MODE, pair.public)
        val aes = SecretKeySpec(ByteArray(16) { (it + 3).toByte() }, "AES")
        assertTrue(prefs("FlutterSecureKeyStorage").edit().putString(
            if (gcm) FlutterStorageMigration.GCM_KEY else FlutterStorageMigration.CBC_KEY,
            Base64.toBase64String(wrap.wrap(aes)),
        ).commit())
        if (markers) {
            assertTrue(prefs(if (globalMarkers) "FlutterSecureStorageConfiguration" else "FlutterSecureStorageConfiguration:FlutterSecureStorage")
                .edit().putString("FlutterSecureSAlgorithmKey", if (oaep) "RSA_ECB_OAEPwithSHA_256andMGF1Padding" else "RSA_ECB_PKCS1Padding")
                .putString("FlutterSecureSAlgorithmStorage", if (gcm) "AES_GCM_NoPadding" else "AES_CBC_PKCS7Padding").commit())
        }
        fun encrypt(value: String): String {
            val cipher = Cipher.getInstance(if (gcm) "AES/GCM/NoPadding" else "AES/CBC/PKCS7Padding")
            val iv = ByteArray(if (gcm) 12 else 16) { (it + 19).toByte() }
            cipher.init(Cipher.ENCRYPT_MODE, aes, if (gcm) GCMParameterSpec(128, iv) else IvParameterSpec(iv))
            return Base64.toBase64String(iv + cipher.doFinal(value.toByteArray()))
        }
        assertTrue(prefs("FlutterSecureStorage").edit()
            .putString(FlutterStorageMigration.PREFIX + "ed25519_private_key", encrypt(Base64.toBase64String(identity.seed())))
            .putString(FlutterStorageMigration.PREFIX + "roster", encrypt("""[{"name":"computer","fingerprint":"${fingerprint.uppercase()}","lastAddr":"host:443"}]"""))
            .putString(FlutterStorageMigration.PREFIX + "sound_enabled", encrypt("0")).commit())
        return identity
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun server() = ServerConfig(fingerprint, "computer", "host", 443, fingerprint)
    private fun recoveryFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected a visible recovery error")
        } catch (failure: StorageRecoveryException) {
            assertTrue(failure.message!!.contains("Existing data has not been reset"))
        }
    }
}
