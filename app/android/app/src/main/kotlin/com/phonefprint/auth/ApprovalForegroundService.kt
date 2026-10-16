// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The sole network lifecycle owner; presentation failure never cancels the manager. */
class ApprovalForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: FingerKeyRuntime
    private lateinit var notifier: ApprovalNotifier
    private lateinit var overlay: OverlayWindow
    private var expiryJob: Job? = null
    private var ringtone: Ringtone? = null
    private var foregroundText: String? = null

    override fun onCreate() {
        super.onCreate()
        // Meet the foreground-service deadline before identity/storage initialization.
        val notification = foregroundNotification("Starting approval connections…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        runtime = FingerKeyRuntime.get(applicationContext)
        notifier = ApprovalNotifier(this)
        overlay = OverlayWindow(this, runtime.manager)
        runtime.manager.start()
        scope.launch {
            combine(runtime.manager.state, runtime.foreground, runtime.approvalHost) { state, foreground, host -> Triple(state, foreground, host) }
                .collect { (state, foreground, host) -> render(state, foreground, host) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runtime.manager.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        if (::overlay.isInitialized) overlay.hide(immediate = true)
        if (::notifier.isInitialized) notifier.clear()
        runCatching { ringtone?.stop() }
        if (::runtime.isInitialized) runtime.manager.stop()
        super.onDestroy()
    }

    private fun render(state: ManagerState, foreground: Boolean, approvalHost: ApprovalKey?) {
        expiryJob?.cancel()
        val now = System.currentTimeMillis()
        val onlineServers = state.servers.filter { it.linkState == LinkState.AUTHENTICATED }
        val approvals = state.approvals.filter { approval ->
            approval.expiresAt > now && onlineServers.any { it.config.id == approval.serverId }
        }
        if (approvalHost != null && state.approvals.none { it.key == approvalHost }) {
            runtime.approvalSubmitted.value = null
            runtime.approvalHost.value = null
        } else if (approvalHost != null && runtime.approvalSubmitted.value == approvalHost && approvalHost !in state.busy) {
            val request = state.approvals.firstOrNull { it.key == approvalHost }
            if (request != null) {
                scope.launch {
                    delay(1200)
                    val latest = runtime.manager.state.value
                    if (runtime.approvalSubmitted.value == approvalHost && approvalHost !in latest.busy &&
                        latest.approvals.any { it.key == approvalHost }
                    ) {
                        runtime.approvalSubmitted.value = null
                        runtime.approvalHost.value = null
                    }
                }
            }
        }
        val newKeys = runtime.newApprovals(approvals, now)
        val summary = when {
            state.loading -> "Loading device identity…"
            state.error != null && state.servers.isEmpty() -> "Setup error · open FingerKey for details"
            state.servers.isEmpty() -> "No servers configured"
            else -> "${onlineServers.size} of ${state.servers.size} servers online · ${approvals.size} pending"
        }
        if (summary != foregroundText) {
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, foregroundNotification(summary))
                foregroundText = summary
            } catch (error: RuntimeException) {
                Log.w(TAG, "Unable to update service notification", error)
            }
        }
        val presentationForeground = foreground || approvalHost != null
        if (presentationForeground) {
            notifier.clear()
            if (foreground && newKeys.isNotEmpty() && state.soundEnabled) playForegroundSound()
        } else {
            notifier.reconcile(approvals, newKeys, state.soundEnabled)
        }
        try {
            overlay.reconcile(approvals, foreground || approvalHost != null, state.busy)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Overlay unavailable; notifications remain available", error)
            overlay.hide()
        }
        approvals.minOfOrNull { it.expiresAt }?.let { nextExpiry ->
            expiryJob = scope.launch {
                delay((nextExpiry - System.currentTimeMillis()).coerceAtLeast(1))
                expiryJob = null
                render(runtime.manager.state.value, runtime.foreground.value, runtime.approvalHost.value)
            }
        }
    }

    private fun playForegroundSound() {
        try {
            ringtone?.stop()
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            ringtone?.play()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to play approval sound", error)
        }
    }

    private fun foregroundNotification(summary: String): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Approval connections", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).apply { setPriority(Notification.PRIORITY_LOW) }
        })
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("FingerKey")
            .setContentText(summary)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ApprovalService"
        private const val CHANNEL_ID = "approval_link"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, ApprovalForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApprovalForegroundService::class.java))
        }
    }
}
