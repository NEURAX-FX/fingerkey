// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun fragmentedAndCoalescedFramesKeepMessageBoundaries() {
        val wire = ByteArrayOutputStream().apply {
            writeFrame("{\"type\":\"pong\"}".encodeToByteArray())
            writeFrame("{\"type\":\"welcome\",\"registered\":true}".encodeToByteArray())
        }
        val chunks = object : FilterInputStream(ByteArrayInputStream(wire.toByteArray())) {
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int = super.read(bytes, offset, minOf(2, count))
        }
        assertEquals("pong", chunks.readMessage().type)
        val welcome = protocolJson.decodeFromString<WelcomeFrame>(chunks.readFrame().decodeToString())
        assertTrue(welcome.registered)
        assertThrows(EOFException::class.java) { chunks.readFrame() }
    }

    @Test fun invalidLengthAndTruncatedPayloadAreRejected() {
        for (header in listOf(byteArrayOf(0, 16, 0, 1), byteArrayOf(-1, -1, -1, -1), byteArrayOf(0, 0, 0, 0))) {
            assertThrows(IllegalArgumentException::class.java) { ByteArrayInputStream(header).readFrame() }
        }
        assertThrows(EOFException::class.java) { ByteArrayInputStream(byteArrayOf(0, 0, 0, 4, 1, 2)).readFrame() }
    }

    @Test fun signatureFormatUsesUtf8ByteLengthsAndNonce() {
        val approval = PendingApproval("id", "", "用户", "sudo", "", expiresAt = 1, serverId = "s", serverName = "server")
        val nonce = ByteArray(32) { 0x42 }
        val prefix = "phone-fprint-auth/v1\u0000approve\u0000\u0006用户\u0004sudo\u0000".encodeToByteArray()
        assertArrayEquals(prefix + nonce, signedMessage("approve", approval, nonce))
        assertThrows(IllegalArgumentException::class.java) { signedMessage("approve", approval, ByteArray(31)) }
        assertThrows(IllegalArgumentException::class.java) { signedMessage("approve", approval.copy(user = "用".repeat(86)), nonce) }
        assertFalse(signedMessage("approve", approval, nonce).contentEquals(signedMessage("deny", approval, nonce)))
    }

    @Test fun endpointsDeduplicatePinsWithoutDroppingIpv6() {
        val config = ServerConfig("ignored", "", " [2001:db8::1] ", 443, "AB".repeat(32)).normalized()
        assertEquals("ab".repeat(32), config.id)
        assertEquals("[2001:db8::1]:443", config.endpoint)
        assertThrows(IllegalArgumentException::class.java) { config.copy(fingerprint = "beef").normalized() }
        assertThrows(IllegalArgumentException::class.java) { config.copy(port = 65536).normalized() }
    }
}
