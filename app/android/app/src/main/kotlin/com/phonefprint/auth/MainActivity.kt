// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.phonefprint.auth.ui.FingerKeyApp

/**
 * Single host activity. Navigation, tabs and page chrome live in Compose; this class only owns the
 * platform pieces: the biometric prompt for in-app approvals and the system-settings entries.
 * Overlay approvals never reach here — they use [BiometricApprovalActivity].
 */
class MainActivity : FragmentActivity() {
    private lateinit var runtime: FingerKeyRuntime
    private var biometricPrompt: BiometricPrompt? = null
    private var biometricApproval: PendingApproval? = null
    private var activityStarted = false
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runtime = FingerKeyRuntime.get(applicationContext)
        ApprovalForegroundService.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            FingerKeyApp(
                context = applicationContext,
                onApprove = ::authenticateAndApprove,
                onOpenOverlaySettings = ::openOverlaySettings,
                onOpenBatterySettings = ::openBatterySettings,
                onRequestNotifications = ::requestNotifications,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        runtime.foreground.value = true
    }

    override fun onStop() {
        activityStarted = false
        runtime.foreground.value = false
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        biometricApproval = null
        super.onStop()
    }

    /** Runs the system prompt and, only on success, hands the live request to the manager. */
    private fun authenticateAndApprove(approval: PendingApproval) {
        if (!activityStarted || biometricApproval != null) return
        val live = runtime.manager.state.value.approvals.firstOrNull {
            it.key == approval.key && it.nonce == approval.nonce
        } ?: return
        if (live.expiresAt <= System.currentTimeMillis() || live.key in runtime.manager.state.value.busy) return
        biometricApproval = live
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val candidate = biometricApproval
                    biometricApproval = null
                    biometricPrompt = null
                    if (!activityStarted || candidate == null) return
                    // Re-read key and nonce after the prompt; the manager validates again before signing.
                    val current = runtime.manager.state.value.approvals.firstOrNull {
                        it.key == candidate.key && it.nonce == candidate.nonce
                    } ?: return
                    runtime.manager.approve(current)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    biometricApproval = null
                    biometricPrompt = null
                    if (activityStarted) Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                }
            },
        )
        biometricPrompt?.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("批准 ${live.serverName} 的请求")
                .setSubtitle("${live.user} · ${live.service}")
                .setDescription("验证指纹后才会向服务器发送签名批准")
                .setNegativeButtonText("取消")
                .build(),
        )
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
