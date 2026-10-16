// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log

/** Notification failures are presentation failures, never connection failures. */
class ApprovalNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val posted = mutableMapOf<ApprovalKey, PendingApproval>()

    fun reconcile(approvals: List<PendingApproval>, newKeys: Set<ApprovalKey>, soundEnabled: Boolean) {
        val liveKeys = approvals.mapTo(mutableSetOf()) { it.key }
        posted.keys.toList().filterNot { it in liveKeys }.forEach(::cancel)
        approvals.forEach { approval ->
            if (posted[approval.key] != approval) {
                show(approval, approval.key in newKeys, soundEnabled)
            }
        }
    }

    fun clear() = posted.keys.toList().forEach(::cancel)

    private fun show(approval: PendingApproval, newRequest: Boolean, soundEnabled: Boolean) {
        val alert = newRequest && soundEnabled
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannels()
            val pendingIntent = PendingIntent.getActivity(
                appContext, 0, FingerKeyRuntime.approvalIntent(appContext, approval.key, approve = true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val detail = buildString {
                append("${approval.serverName} · ${approval.user} · ${approval.service}")
                if (approval.tty.isNotBlank()) append("\nTerminal: ${approval.tty}")
                if (approval.reason.isNotBlank()) append("\nReason: ${approval.reason}")
                if (approval.command.isNotBlank()) append("\nCommand: ${approval.command}")
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = when {
                    !newRequest -> REPLAY_CHANNEL_ID
                    alert -> CHANNEL_ID
                    else -> SILENT_CHANNEL_ID
                }
                Notification.Builder(appContext, channel)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appContext).apply {
                    setPriority(if (newRequest) Notification.PRIORITY_HIGH else Notification.PRIORITY_LOW)
                    setSound(if (alert) Settings.System.DEFAULT_NOTIFICATION_URI else null)
                    setDefaults(0)
                }
            }
            builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Approval requested · ${approval.serverName}")
                .setContentText("${approval.user} · ${approval.service}")
                .setStyle(Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setTimeoutAfter((approval.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            }
            manager.notify(tag(approval.key), NOTIFICATION_ID, builder.build())
            posted[approval.key] = approval
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to post approval notification", error)
        }
    }

    private fun cancel(key: ApprovalKey) {
        posted.remove(key)
        try {
            manager.cancel(tag(key), NOTIFICATION_ID)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to cancel approval notification", error)
        }
    }

    private fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Approval requests", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(Settings.System.DEFAULT_NOTIFICATION_URI, Notification.AUDIO_ATTRIBUTES_DEFAULT)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(SILENT_CHANNEL_ID, "Approval requests (silent)", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(REPLAY_CHANNEL_ID, "Pending approvals", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    // Length prefixes retain the entire composite key without hash or delimiter collisions.
    private fun tag(key: ApprovalKey) = "approval:${key.serverId.length}:${key.serverId}${key.id.length}:${key.id}"

    companion object {
        private const val TAG = "ApprovalNotifier"
        private const val CHANNEL_ID = "approval_requests_sound_v2"
        private const val SILENT_CHANNEL_ID = "approval_requests_silent_v2"
        private const val REPLAY_CHANNEL_ID = "approval_requests_pending_v2"
        private const val NOTIFICATION_ID = 1
    }
}
