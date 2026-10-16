// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement

/** One reader and one bounded writer per server. Stopping permanently retires this instance. */
internal class ServerConnection(
    private val identity: Identity,
    private val config: ServerConfig,
    private val onState: (LinkState, String?) -> Unit,
    private val onSnapshot: (List<PendingApproval>) -> Unit,
    private val onPending: (PendingApproval) -> Unit,
    private val onDecision: (DecisionResult) -> Unit,
    private val discover: () -> ServerConfig? = { null },
) {
    private val lock = Any()
    @Volatile private var stopped = false
    @Volatile private var authenticated = false
    private var socket: SSLSocket? = null
    private var queue: ArrayBlockingQueue<ByteArray>? = null
    private var worker: Thread? = null

    fun start() = synchronized(lock) {
        check(worker == null) { "Connection already started" }
        if (!stopped) worker = Thread(::runLoop, "fingerkey-link-${config.id.take(8)}").apply { isDaemon = true; start() }
    }

    fun stop() = synchronized(lock) {
        stopped = true
        authenticated = false
        queue = null
        runCatching { socket?.close() }
        worker?.interrupt()
    }

    fun sendDecision(id: String, decision: String, signature: String) = synchronized(lock) {
        check(!stopped && authenticated) { "${config.name} 当前离线" }
        check(queue?.offer(protocolJson.encodeToString(DecisionFrame(id, decision, signature)).encodeToByteArray()) == true) { "发送队列已满，请稍后重试" }
    }

    private fun runLoop() {
        var backoff = 1L
        var endpoint = config
        while (!stopped) {
            onState(LinkState.CONNECTING, null)
            var writer: Thread? = null
            var terminal = false
            var failure: String? = null
            try {
                val next = createPinnedSocket(config)
                synchronized(lock) {
                    if (stopped) { next.close(); return }
                    socket = next
                }
                next.connectPinned(endpoint)
                if (stopped) return
                onState(LinkState.CONNECTED, null)
                next.outputStream.writeMessage(HelloFrame(identity.publicKeyBase64))
                val message = next.inputStream.readMessage()
                check(message.type == "welcome") { "Unexpected handshake response" }
                val welcome = protocolJson.decodeFromJsonElement<WelcomeFrame>(message)
                if (!welcome.registered) throw Unregistered()
                val outbound = ArrayBlockingQueue<ByteArray>(16)
                synchronized(lock) {
                    if (stopped) return
                    queue = outbound
                    authenticated = true
                }
                next.soTimeout = 35_000
                writer = Thread({ writeLoop(next, outbound) }, "fingerkey-write-${config.id.take(8)}").apply { isDaemon = true; start() }
                backoff = 1
                onSnapshot(welcome.pending.map { it.toApproval(config) })
                onState(LinkState.AUTHENTICATED, null)
                while (!stopped) {
                    val frame = next.inputStream.readMessage()
                    when (frame.type) {
                        "pending" -> onPending(protocolJson.decodeFromJsonElement<PendingFrame>(frame).toApproval(config))
                        "decision-result" -> onDecision(protocolJson.decodeFromJsonElement<DecisionResult>(frame))
                        "pong" -> Unit
                        else -> error("Unexpected frame type: ${frame.type}")
                    }
                }
            } catch (_: InterruptedException) {
                return
            } catch (error: Exception) {
                if (stopped) return
                when {
                    generateSequence<Throwable>(error) { it.cause }.any { it is PinMismatch } -> {
                        terminal = true
                        onState(LinkState.PIN_MISMATCH, PinMismatch().message)
                    }
                    error is Unregistered -> {
                        terminal = true
                        onState(LinkState.UNREGISTERED, error.message)
                    }
                    else -> failure = error.message ?: "连接失败"
                }
            } finally {
                synchronized(lock) {
                    authenticated = false
                    queue = null
                    runCatching { socket?.close() }
                    socket = null
                }
                writer?.interrupt()
                try { writer?.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
            if (terminal || stopped) return
            onState(LinkState.RETRYING, failure)
            // A discovered address is only a temporary candidate; never overwrite the public endpoint.
            endpoint = try { discover() ?: config } catch (_: Exception) { config }
            try { Thread.sleep(backoff * 1000) } catch (_: InterruptedException) { return }
            backoff = (backoff * 2).coerceAtMost(30)
        }
    }

    private fun writeLoop(socket: SSLSocket, outbound: ArrayBlockingQueue<ByteArray>) {
        try {
            while (!stopped && !socket.isClosed) {
                val payload = outbound.poll(15, TimeUnit.SECONDS) ?: PING
                socket.outputStream.writeFrame(payload)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Closing this exact generation wakes the reader, which owns reconnect/state reporting.
            runCatching { socket.close() }
        }
    }

    companion object {
        private val PING = "{\"type\":\"ping\"}".encodeToByteArray()

        /** Never retry a one-time token. Persist only after this succeeds. */
        fun pair(identity: Identity, config: ServerConfig, token: String) {
            require(token.isNotBlank()) { "请输入一次性配对 token" }
            createPinnedSocket(config).use { socket ->
                socket.connectPinned(config)
                socket.outputStream.writeMessage(HelloFrame(identity.publicKeyBase64, token = token.trim()))
                val response = socket.inputStream.readMessage()
                if (response.type != "registered") throw Unregistered()
            }
        }
    }
}
