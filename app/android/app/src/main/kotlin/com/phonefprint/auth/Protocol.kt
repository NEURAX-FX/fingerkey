// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val MAX_FRAME_SIZE = 1 shl 20
internal val protocolJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
internal data class HelloFrame(val pubkey: String, val name: String = "phone", val token: String? = null, val type: String = "hello")

@Serializable
internal data class WelcomeFrame(val type: String, val registered: Boolean = false, val key: String? = null, val pending: List<PendingFrame> = emptyList())

@Serializable
internal data class PendingFrame(
    val id: String,
    val nonce: String,
    val user: String = "",
    val service: String = "",
    val tty: String = "",
    val reason: String = "",
    val command: String = "",
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
internal data class DecisionFrame(val id: String, val decision: String, val sig: String, val type: String = "decision")

internal class PinMismatch : CertificateException("TLS 指纹不匹配；连接已停止，请核实服务器身份")
internal class Unregistered : Exception("服务器不认可此设备密钥，请重新配对")

/** Socket is published before connect so stop can close an in-progress handshake. */
internal fun createPinnedSocket(config: ServerConfig): SSLSocket {
    val trust = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String): Unit = throw CertificateException("Client certificates not accepted")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("Empty certificate chain")
            val expected = config.fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
            if (!MessageDigest.isEqual(actual, expected)) throw PinMismatch()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return (SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory.createSocket() as SSLSocket).apply {
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "" }
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        keepAlive = true
    }
}

internal fun SSLSocket.connectPinned(config: ServerConfig) {
    connect(InetSocketAddress(config.host, config.port), 5_000)
    soTimeout = 10_000
    startHandshake()
}

internal fun InputStream.readFrame(): ByteArray {
    val input = DataInputStream(this)
    val size = input.readInt()
    require(size in 1..MAX_FRAME_SIZE) { "Invalid frame length: $size" }
    return ByteArray(size).also(input::readFully)
}

internal fun OutputStream.writeFrame(payload: ByteArray) {
    require(payload.size in 1..MAX_FRAME_SIZE) { "Invalid frame length" }
    write(byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()))
    write(payload)
    flush()
}

internal fun InputStream.readMessage(): JsonObject = protocolJson.decodeFromString(JsonObject.serializer(), readFrame().decodeToString(throwOnInvalidSequence = true))
internal val JsonObject.type: String? get() = get("type")?.jsonPrimitive?.content
internal inline fun <reified T> OutputStream.writeMessage(value: T) = writeFrame(protocolJson.encodeToString(value).encodeToByteArray())

internal fun PendingFrame.toApproval(server: ServerConfig): PendingApproval {
    require(id.isNotEmpty() && expiresAt > 0 && expiresAt <= Long.MAX_VALUE / 1000) { "Invalid pending request" }
    require(user.encodeToByteArray().size <= 255 && service.encodeToByteArray().size <= 255 && tty.encodeToByteArray().size <= 255) { "Approval context exceeds protocol limit" }
    return PendingApproval(id, nonce, user, service, tty, reason, command, expiresAt * 1000, server.id, server.name)
}

internal fun signedMessage(action: String, approval: PendingApproval, nonce: ByteArray): ByteArray {
    require(action == "approve" || action == "deny")
    require(nonce.size == 32) { "Invalid approval nonce" }
    val domain = "phone-fprint-auth/v1".encodeToByteArray()
    val actionBytes = action.encodeToByteArray()
    val fields = arrayOf(approval.user.encodeToByteArray(), approval.service.encodeToByteArray(), approval.tty.encodeToByteArray())
    require(fields.all { it.size <= 255 }) { "Approval context exceeds protocol limit" }
    val output = ByteArray(domain.size + actionBytes.size + 2 + 3 + fields.sumOf { it.size } + nonce.size)
    var offset = 0
    fun put(bytes: ByteArray) { bytes.copyInto(output, offset); offset += bytes.size }
    put(domain)
    output[offset++] = 0
    put(actionBytes)
    output[offset++] = 0
    fields.forEach { output[offset++] = it.size.toByte(); put(it) }
    put(nonce)
    return output
}
