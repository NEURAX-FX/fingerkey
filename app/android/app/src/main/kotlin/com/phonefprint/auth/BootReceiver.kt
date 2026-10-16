// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restarts the native link owner after boot when at least one server exists. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            if (DeviceStore(context).isConfigured()) ApprovalForegroundService.start(context)
        }
            .onFailure { Log.w(TAG, "Boot start blocked: ${it.message}") }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
