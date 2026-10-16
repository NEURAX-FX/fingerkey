// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val fingerprint: String,
) {
    val endpoint: String get() = "${if (host.contains(':')) "[$host]" else host}:$port"

    fun normalized(): ServerConfig {
        val pin = fingerprint.trim().lowercase()
        require(pin.matches(Regex("[0-9a-f]{64}"))) { "TLS 指纹必须是 64 位十六进制字符" }
        val address = host.trim().removeSurrounding("[", "]")
        require(address.isNotEmpty() && address.none { it.isWhitespace() || it in "/?#@[]" }) {
            "请输入主机名或 IP，不要包含协议或路径"
        }
        require(port in 1..65535) { "端口必须在 1–65535 之间" }
        return copy(id = pin, name = name.trim().ifEmpty { address }, host = address, fingerprint = pin)
    }
}

data class ApprovalKey(val serverId: String, val id: String)

@Serializable
data class PendingApproval(
    val id: String,
    val nonce: String,
    val user: String,
    val service: String,
    val tty: String,
    val reason: String = "",
    val command: String = "",
    val expiresAt: Long,
    val serverId: String,
    val serverName: String,
) {
    val key: ApprovalKey get() = ApprovalKey(serverId, id)
}

@Serializable
data class DecisionResult(
    val id: String = "",
    val status: String? = null,
    val key: String? = null,
    val error: String? = null,
) {
    val terminal: Boolean get() = error == null && status in setOf("approved", "denied", "expired")
}

enum class LinkState { IDLE, CONNECTING, CONNECTED, AUTHENTICATED, RETRYING, UNREGISTERED, PIN_MISMATCH, STOPPED }

data class ServerState(
    val config: ServerConfig,
    val linkState: LinkState = LinkState.IDLE,
    val error: String? = null,
)

data class ManagerState(
    val publicKey: String = "",
    val loading: Boolean = true,
    val servers: List<ServerState> = emptyList(),
    val approvals: List<PendingApproval> = emptyList(),
    val soundEnabled: Boolean = true,
    val busy: Set<ApprovalKey> = emptySet(),
    val pairing: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)
