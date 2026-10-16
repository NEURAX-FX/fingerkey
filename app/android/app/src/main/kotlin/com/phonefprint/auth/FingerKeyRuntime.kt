// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/** Process owner: Activity recreation and service restarts must not replace the manager. */
class FingerKeyRuntime private constructor(context: Context) {
    val manager = ServerManager(context.applicationContext)
    val foreground = MutableStateFlow(false)
    val approvalHost = MutableStateFlow<ApprovalKey?>(null)
    val approvalSubmitted = MutableStateFlow<ApprovalKey?>(null)
    // Kept across service restarts so replaying pending state never repeats an alert.
    private val announced = mutableMapOf<Pair<ApprovalKey, String>, Long>()


    internal fun newApprovals(approvals: List<PendingApproval>, now: Long): Set<ApprovalKey> {
        announced.entries.removeAll { it.value <= now }
        return approvals.mapNotNullTo(mutableSetOf()) { approval ->
            val token = approval.key to approval.nonce
            if (announced.put(token, approval.expiresAt) == null) approval.key else null
        }
    }

    companion object {
        const val EXTRA_SERVER_ID = "approval_server_id"
        const val EXTRA_APPROVAL_ID = "approval_id"

        @Volatile private var instance: FingerKeyRuntime? = null

        fun get(context: Context): FingerKeyRuntime = instance ?: synchronized(this) {
            instance ?: FingerKeyRuntime(context).also { instance = it }
        }

        fun approvalIntent(context: Context, key: ApprovalKey, approve: Boolean = false): Intent =
            Intent(context, if (approve) BiometricApprovalActivity::class.java else MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.Builder().scheme("fingerkey").authority("approval")
                    .appendPath(key.serverId).appendPath(key.id)
                    .appendQueryParameter("approve", approve.toString()).build()
                putExtra(EXTRA_SERVER_ID, key.serverId)
                putExtra(EXTRA_APPROVAL_ID, key.id)
                if (approve) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                } else {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            }
    }
}
