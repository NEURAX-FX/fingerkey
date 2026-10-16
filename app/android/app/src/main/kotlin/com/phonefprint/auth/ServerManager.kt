// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All roster/link/request mutations run on Main; disk and network work run on IO. */
class ServerManager(context: Context) {
    private val store = DeviceStore(context.applicationContext)
    private val discovery = LanDiscovery(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutations = Mutex()
    private val mutableState = MutableStateFlow(ManagerState())
    val state = mutableState.asStateFlow()
    private val links = mutableMapOf<String, ServerConnection>()
    private val pending = linkedMapOf<ApprovalKey, PendingApproval>()
    private val dismissed = mutableMapOf<ApprovalKey, Long>()
    private val outgoing = mutableMapOf<ApprovalKey, Outgoing>()
    private var identity: Identity? = null
    private var started = false
    private var generation = 0
    private var expiryJob: Job? = null
    private var pairingJob: Job? = null

    fun start() = launchAction {
        mutations.withLock {
            started = true
            load()
            if (!started) return@withLock
            state.value.servers.forEach { if (it.config.id !in links) attach(it.config) }
            if (expiryJob == null) expiryJob = scope.launch {
                while (isActive) { delay(500); expire() }
            }
        }
    }

    fun stop() {
        scope.launch {
            started = false
            retireAll()
            expiryJob?.cancel()
            expiryJob = null
            mutableState.value = state.value.copy(servers = state.value.servers.map { it.copy(linkState = LinkState.STOPPED) })
        }
    }

    private suspend fun load() {
        if (identity != null) return
        val data = withContext(Dispatchers.IO) {
            Triple(store.loadOrCreateIdentity(), store.servers().map { it.normalized() }, store.soundEnabled())
        }
        identity = data.first
        mutableState.value = state.value.copy(publicKey = data.first.publicKeyBase64, loading = false,
            servers = data.second.distinctBy { it.id }.map(::ServerState), soundEnabled = data.third, error = null)
    }

    fun pair(config: ServerConfig, token: String) {
        if (state.value.pairing) return
        pairingJob = launchAction {
            val clean = config.normalized()
            require(token.trim().isNotEmpty()) { "请输入一次性配对 token" }
            mutations.withLock { load() }
            val version = generation
            val currentIdentity = checkNotNull(identity)
            mutableState.value = state.value.copy(pairing = true, error = null)
            try {
                withContext(Dispatchers.IO) { ServerConnection.pair(currentIdentity, clean, token) }
                mutations.withLock {
                    check(version == generation) { "设备身份已更改，请重新配对" }
                    withContext(Dispatchers.IO) { store.saveServer(clean) }
                    detach(clean.id)
                    upsert(ServerState(clean))
                    if (started) attach(clean)
                    mutableState.value = state.value.copy(notice = "已配对 ${clean.name}")
                }
            } finally { mutableState.value = state.value.copy(pairing = false) }
        }
    }

    fun remove(serverId: String) = launchAction {
        mutations.withLock {
            withContext(Dispatchers.IO) { store.removeServer(serverId) }
            detach(serverId)
            mutableState.value = state.value.copy(servers = state.value.servers.filterNot { it.config.id == serverId })
        }
    }

    fun reconnect(serverId: String) = launchAction {
        mutations.withLock {
            val config = state.value.servers.firstOrNull { it.config.id == serverId }?.config ?: return@withLock
            links.remove(serverId)?.stop()
            if (started) attach(config)
        }
    }

    fun resetIdentity() = launchAction {
        mutations.withLock {
            generation++
            pairingJob?.cancel()
            retireAll()
            identity = null
            withContext(Dispatchers.IO) { store.resetIdentity() }
            mutableState.value = ManagerState(soundEnabled = state.value.soundEnabled)
            load()
        }
    }

    fun setSoundEnabled(enabled: Boolean) = launchAction {
        mutations.withLock {
            withContext(Dispatchers.IO) { store.setSoundEnabled(enabled) }
            mutableState.value = state.value.copy(soundEnabled = enabled)
        }
    }

    fun clearError() { mutableState.value = state.value.copy(error = null, notice = null) }
    fun dismiss(key: ApprovalKey) {
        scope.launch { pending[key]?.let { dismissed[key] = it.expiresAt }; publishPending() }
    }

    /** Only the Activity biometric success callback invokes this method. */
    fun approve(approval: PendingApproval) = decide(approval, "approve")
    fun deny(approval: PendingApproval) = decide(approval, "deny")

    private fun decide(approval: PendingApproval, action: String) = launchAction {
        val key = approval.key
        if (key in state.value.busy) return@launchAction
        val live = pending[key]
        check(live == approval && live.expiresAt > System.currentTimeMillis()) { "请求已失效或已由其他设备处理" }
        check(state.value.servers.any { it.config.id == key.serverId && it.linkState == LinkState.AUTHENTICATED }) { "服务器当前离线" }
        val previous = outgoing[key]
        check(previous == null || previous.action == action) { "上次决定尚未确认，不能发送相反决定" }
        val deviceIdentity = checkNotNull(identity)
        val version = generation
        mutableState.value = state.value.copy(busy = state.value.busy + key, error = null)
        try {
            val signature = previous?.signature ?: withContext(Dispatchers.Default) {
                Base64.encodeToString(deviceIdentity.sign(signedMessage(action, approval, Base64.decode(approval.nonce, Base64.DEFAULT))), Base64.NO_WRAP)
            }
            check(version == generation && pending[key] == approval && approval.expiresAt > System.currentTimeMillis()) { "请求已失效" }
            outgoing[key] = Outgoing(approval, action, signature, System.currentTimeMillis() + 16_000)
            checkNotNull(links[key.serverId]) { "服务器当前离线" }.sendDecision(key.id, action, signature)
        } catch (error: Exception) {
            mutableState.value = state.value.copy(busy = state.value.busy - key)
            throw error
        }
    }

    private fun attach(config: ServerConfig) {
        if (config.id in links) return
        lateinit var connection: ServerConnection
        fun event(action: () -> Unit) { scope.launch { if (links[config.id] === connection) action() } }
        connection = ServerConnection(checkNotNull(identity), config,
            onState = { status, error -> event {
                upsert(ServerState(config, status, error))
                if (status == LinkState.UNREGISTERED || status == LinkState.PIN_MISMATCH) clearRequests(config.id)
                if (status == LinkState.AUTHENTICATED) {
                    outgoing.filterKeys { it.serverId == config.id }.values.toList().forEach { sent ->
                        if (sent.approval.expiresAt > System.currentTimeMillis()) {
                            runCatching { connection.sendDecision(sent.approval.id, sent.action, sent.signature) }
                                .onFailure { mutableState.value = state.value.copy(error = it.message) }
                        }
                    }
                }
            } },
            onSnapshot = { requests -> event {
                pending.keys.removeAll { it.serverId == config.id }
                requests.filter { it.expiresAt > System.currentTimeMillis() }.forEach { pending[it.key] = it }
                publishPending()
            } },
            onPending = { request -> event {
                if (request.expiresAt > System.currentTimeMillis()) { pending[request.key] = request; publishPending() }
            } },
            onDecision = { result -> event { settle(config.id, result) } },
            discover = { discovery.find(config) },
        )
        links[config.id] = connection
        upsert(ServerState(config, LinkState.CONNECTING))
        connection.start()
    }

    private fun settle(serverId: String, result: DecisionResult) {
        val key = ApprovalKey(serverId, result.id)
        if (result.terminal) {
            pending.remove(key)
            dismissed.remove(key)
            outgoing.remove(key)
            mutableState.value = state.value.copy(busy = state.value.busy - key,
                notice = "${state.value.servers.firstOrNull { it.config.id == serverId }?.config?.name.orEmpty()}：${result.status} ${result.key.orEmpty()}")
            publishPending()
        } else if (result.error != null) {
            outgoing.remove(key)
            mutableState.value = state.value.copy(busy = state.value.busy - key, error = "服务器拒绝决定：${result.error}")
        }
    }

    private fun expire() {
        val now = System.currentTimeMillis()
        pending.values.removeAll { it.expiresAt <= now }
        dismissed.values.removeAll { it <= now }
        outgoing.entries.removeAll { it.value.approval.expiresAt <= now }
        val timedOut = outgoing.filterValues { it.deadline <= now }.keys.intersect(state.value.busy)
        mutableState.value = state.value.copy(busy = state.value.busy.filter { it in outgoing && it !in timedOut }.toSet(),
            error = if (timedOut.isNotEmpty()) "未收到服务器确认；重连后会查询原决定，或可再次发送同一决定" else state.value.error)
        publishPending()
    }

    private fun publishPending() { mutableState.value = state.value.copy(approvals = pending.values.filter { it.key !in dismissed }) }
    private fun upsert(value: ServerState) {
        val existing = state.value.servers
        mutableState.value = state.value.copy(servers = if (existing.any { it.config.id == value.config.id }) existing.map { if (it.config.id == value.config.id) value else it } else existing + value)
    }
    private fun clearRequests(serverId: String) {
        pending.keys.removeAll { it.serverId == serverId }
        outgoing.keys.removeAll { it.serverId == serverId }
        dismissed.keys.removeAll { it.serverId == serverId }
        mutableState.value = state.value.copy(busy = state.value.busy.filterNot { it.serverId == serverId }.toSet())
        publishPending()
    }
    private fun detach(serverId: String) { links.remove(serverId)?.stop(); clearRequests(serverId) }
    private fun retireAll() {
        links.values.forEach { it.stop() }; links.clear()
        pending.clear(); outgoing.clear(); dismissed.clear()
        mutableState.value = state.value.copy(approvals = emptyList(), busy = emptySet())
    }
    private fun launchAction(action: suspend () -> Unit): Job = scope.launch {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableState.value = state.value.copy(loading = false, error = error.message ?: "操作失败") }
    }
    private data class Outgoing(val approval: PendingApproval, val action: String, val signature: String, val deadline: Long)
}
