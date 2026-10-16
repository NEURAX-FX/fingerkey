// Temporary end-to-end executable smoke, removed after verification.
package com.phonefprint.auth

import java.io.File
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeDaemonSmoke {
    @Test fun actualGoDaemons() {
        val smokeRoot = System.getenv("FINGERKEY_SMOKE_DIR")?.let(::File)
            ?: sequenceOf("build/native-smoke", "../build/native-smoke", "../../build/native-smoke")
                .map(::File).firstOrNull { File(it, "fingerkeyd").isFile }
            ?: error("fingerkeyd not found; build daemon into app/build/native-smoke or set FINGERKEY_SMOKE_DIR")
        val root = smokeRoot.absoluteFile
        val identity = Identity(ByteArray(32) { (it + 1).toByte() })
        val servers = (1..2).map { index ->
            val dir = File(root, "server$index").apply { mkdirs() }
            File(dir, "keys").mkdirs()
            val port = ServerSocket(0).use { it.localPort }
            val process = ProcessBuilder(File(root, "fingerkeyd").path,
                "-socket", File(dir, "daemon.sock").path, "-keys-dir", File(dir, "keys").path,
                "-tls-dir", File(dir, "tls").path, "-addr", "127.0.0.1:$port",
                "-advertise-host", "127.0.0.1", "-advertise-port", "$port")
                .redirectErrorStream(true).redirectOutput(File(dir, "daemon.log")).start()
            Triple(dir, port, process)
        }
        val links = mutableListOf<ServerConnection>()
        try {
            val configs = servers.mapIndexed { index, (dir, port, _) ->
                var reply: JsonObject? = null
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (System.nanoTime() < deadline && reply?.get("token") == null) {
                    try { reply = call(dir, "/v1/pair", "{\"name\":\"native\"}") } catch (_: Exception) { Thread.sleep(100) }
                }
                val pair = checkNotNull(reply)
                val fp = pair.getValue("fp").jsonPrimitive.content
                val config = ServerConfig(fp, "Smoke-${index + 1}", "127.0.0.1", port, fp)
                val token = pair.getValue("token").jsonPrimitive.content
                ServerConnection.pair(identity, config, token)
                assertThrows(Unregistered::class.java) { ServerConnection.pair(identity, config, token) }
                config
            }
            val pending = List(2) { LinkedBlockingQueue<PendingApproval>() }
            val results = List(2) { LinkedBlockingQueue<DecisionResult>() }
            val states = List(2) { LinkedBlockingQueue<LinkState>() }
            configs.forEachIndexed { index, config ->
                links += ServerConnection(identity, config,
                    onState = { s, _ -> states[index].offer(s) },
                    onSnapshot = { it.forEach(pending[index]::offer) },
                    onPending = { pending[index].offer(it) },
                    onDecision = { results[index].offer(it) }).also { it.start() }
            }
            fun awaitOnline(index: Int) {
                var state: LinkState?
                do { state = states[index].poll(15, TimeUnit.SECONDS); check(state != null) { "Connection timed out" } } while (state != LinkState.AUTHENTICATED)
            }
            awaitOnline(0); awaitOnline(1)
            val requests = servers.mapIndexed { index, (dir, _, _) ->
                val created = call(dir, "/v1/session", "{\"user\":\"alice\",\"service\":\"sudo\",\"tty\":\"pts/1\",\"reason\":\"native smoke $index\",\"command\":\"id\"}")
                val request = checkNotNull(pending[index].poll(10, TimeUnit.SECONDS)) { "No pending push" }
                assertEquals(created.getValue("id").jsonPrimitive.content, request.id)
                assertEquals(configs[index].id, request.serverId)
                request
            }
            requests.forEachIndexed { index, request ->
                val action = if (index == 0) "approve" else "deny"
                val sig = Base64.getEncoder().encodeToString(identity.sign(signedMessage(action, request, Base64.getDecoder().decode(request.nonce))))
                links[index].sendDecision(request.id, action, sig)
                val verdict = checkNotNull(results[index].poll(10, TimeUnit.SECONDS))
                assertEquals(if (index == 0) "approved" else "denied", verdict.status)
                assertEquals(verdict.status, call(servers[index].first, "/v1/session/${request.id}")["status"]!!.jsonPrimitive.content)
            }
            val badStates = LinkedBlockingQueue<LinkState>()
            links += ServerConnection(identity, configs[0].copy(fingerprint = "00".repeat(32)),
                onState = { s, _ -> badStates.offer(s) }, onSnapshot = {}, onPending = {}, onDecision = {}).also { it.start() }
            var badState: LinkState?
            do {
                badState = badStates.poll(10, TimeUnit.SECONDS)
                check(badState != null) { "Pin failure missing" }
            } while (badState != LinkState.PIN_MISMATCH)
            // Exceeds the daemon's 30s idle limit: real heartbeat must keep BOTH links alive.
            Thread.sleep(32_000)
            for ((index, server) in servers.withIndex()) {
                call(server.first, "/v1/session", "{\"user\":\"heartbeat\",\"service\":\"sudo\"}")
                assertEquals("heartbeat", pending[index].poll(10, TimeUnit.SECONDS)?.user)
            }
            println("NATIVE_SMOKE_OK: 2 Go TLS servers; token pairing + replay rejection; simultaneous pending; Ed25519 approve/deny; wrong pin blocked; heartbeat >30s")
        } finally {
            links.forEach { it.stop() }
            servers.forEach { it.third.destroy(); it.third.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    private fun call(dir: File, path: String, body: String? = null): JsonObject {
        val args = mutableListOf("curl", "--silent", "--show-error", "--fail", "--max-time", "5", "--unix-socket", File(dir, "daemon.sock").path)
        if (body != null) args += listOf("-H", "Content-Type: application/json", "-d", body)
        args += "http://localhost$path"
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val response = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { response }
        return protocolJson.parseToJsonElement(response).jsonObject
    }
}
