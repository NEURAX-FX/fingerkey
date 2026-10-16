package com.phonefprint.auth

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.*
import org.junit.Test

class IdentityStorageTest {
    @Test fun rfc8032SeedSignsWithoutPlatformEd25519() {
        val identity = Identity(Hex.decode("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"))
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            Hex.toHexString(Base64.decode(identity.publicKeyBase64)))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555f" +
                "b8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            Hex.toHexString(identity.sign(byteArrayOf())),
        )
    }

    @Test fun snapshotReloadSignsForTheSamePublicKey() {
        val identity = Identity.generate()
        val original = DeviceSnapshot(identity, listOf(server()), false)
        val loaded = SnapshotCodec.decode(SnapshotCodec.encode(original))
        assertEquals(identity.publicKeyBase64, loaded.identity.publicKeyBase64)
        assertEquals(original.servers, loaded.servers)
        assertFalse(loaded.soundEnabled)
        val message = "approval message".toByteArray()
        val verifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(Base64.decode(identity.publicKeyBase64), 0))
            update(message, 0, message.size)
        }
        assertTrue(verifier.verifySignature(loaded.identity.sign(message)))
    }

    @Test fun legacyRosterCanonicalizesAndDeduplicatesIpv6Addresses() {
        val lower = "ab".repeat(32)
        val roster = """[{"name":"old","fingerprint":"${lower.uppercase()}","lastAddr":"host:443"},
            {"name":"new","fingerprint":"$lower","lastAddr":"[fe80::1%wlan0]:8443"}]"""
        assertEquals(listOf(ServerConfig(lower, "new", "fe80::1%wlan0", 8443, lower)), SnapshotCodec.legacyServers(roster))
    }

    @Test fun malformedSnapshotsAndRostersFailRatherThanDisappear() {
        val snapshot = SnapshotCodec.encode(DeviceSnapshot(Identity.generate(), listOf(server()), true)).toString(Charsets.UTF_8)
        fails { SnapshotCodec.decode(snapshot.replace("\"version\":1", "\"version\":2").toByteArray()) }
        fails { SnapshotCodec.decode(snapshot.replace("\"soundEnabled\":true", "\"soundEnabled\":\"true\"").toByteArray()) }
        fails { SnapshotCodec.decode(snapshot.replace("\"port\":443", "\"port\":0").toByteArray()) }
        fails { SnapshotCodec.decode(snapshot.replace("\"publicKey\":\"", "\"publicKey\":\"wrong").toByteArray()) }
        fails { SnapshotCodec.legacyServers("") }
        fails { SnapshotCodec.legacyServers("null") }
        fails { SnapshotCodec.legacyServers("[{}]") }
        fails { Identity(ByteArray(31)) }
    }

    private fun server(): ServerConfig = ServerConfig("ab".repeat(32), "computer", "host", 443, "ab".repeat(32))

    private fun fails(action: () -> Unit) {
        try {
            action()
            fail("Expected malformed storage to be rejected")
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: ClassCastException) {
        } catch (_: NoSuchElementException) {
        }
    }
}
