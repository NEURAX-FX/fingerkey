// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One shared, short LAN browse; TLS pinning still authenticates the discovered candidate. */
internal class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var active: CountDownLatch? = null
    private var nextBrowse = 0L
    private val endpoints = mutableMapOf<String, Pair<String, Int>>()

    fun find(config: ServerConfig): ServerConfig? {
        var owner = false
        val completion = synchronized(lock) {
            active ?: if (System.currentTimeMillis() < nextBrowse) null else CountDownLatch(1).also {
                active = it
                endpoints.clear()
                owner = true
            }
        }
        if (completion != null) {
            if (owner) browse(completion) else completion.await(5, TimeUnit.SECONDS)
        }
        return synchronized(lock) {
            endpoints[config.fingerprint]?.let { (host, port) -> config.copy(host = host, port = port) }
        }
    }

    @Suppress("DEPRECATION")
    private fun browse(completion: CountDownLatch) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val pin = info.attributes["fp"]?.toString(Charsets.UTF_8)?.lowercase() ?: return
                            val host = info.host?.hostAddress ?: return
                            synchronized(lock) {
                                if (active === completion && info.port in 1..65535) endpoints[pin] = host to info.port
                            }
                        }
                    })
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { completion.countDown() }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
        }
        try {
            nsd.discoverServices("_phonefprint._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            completion.await(4, TimeUnit.SECONDS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            synchronized(lock) { active = null; nextBrowse = System.currentTimeMillis() + 30_000 }
            completion.countDown()
        }
    }
}
