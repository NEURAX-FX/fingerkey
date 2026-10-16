// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.biometric.PromptContentItemPlainText
import androidx.biometric.PromptVerticalListContentView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Invisible host for the system biometric sheet.
 *
 * It draws nothing and uses a translucent, animation-free window, so approving a request from the
 * overlay never switches the user out of whatever app is on screen — only the system prompt appears,
 * floating above it.
 */
class BiometricApprovalActivity : FragmentActivity() {
    private lateinit var runtime: FingerKeyRuntime
    private var request: PendingApproval? = null
    private var prompt: BiometricPrompt? = null
    private var successful = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = FingerKeyRuntime.get(applicationContext)
        val key = ApprovalKey(
            intent.getStringExtra(FingerKeyRuntime.EXTRA_SERVER_ID).orEmpty(),
            intent.getStringExtra(FingerKeyRuntime.EXTRA_APPROVAL_ID).orEmpty(),
        )
        val current = runtime.manager.state.value.approvals.firstOrNull { it.key == key }
        if (savedInstanceState != null || current == null || !canApprove(current) ||
            runtime.approvalHost.value?.let { it != key } == true
        ) {
            finish()
            return
        }
        val live = current ?: run { finish(); return }
        request = live
        runtime.approvalHost.value = key
        // Keep the host window as a single transparent point in the top-right corner: nothing of ours
        // flashes on screen, only the system prompt appears.
        setContentView(View(this))
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0f)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setGravity(Gravity.TOP or Gravity.END)
        val dot = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        window.setLayout(dot, dot)

        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (finished) return
                val live = request
                if (live == null || !canApprove(live)) {
                    cancelAuthorization()
                    return
                }
                successful = true
                runtime.approvalSubmitted.value = live.key
                finished = true
                runtime.manager.approve(live)
                // Service retains the host lock until the server's terminal result removes this request.
                finish()
                overridePendingTransition(0, 0)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (finished) return
                // The prompt's own negative button is the reject action; cancelling keeps the request open.
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    val live = request
                    if (live != null) {
                        successful = true
                        runtime.approvalSubmitted.value = live.key
                        runtime.manager.deny(live)
                    }
                    finished = true
                    finish()
                    overridePendingTransition(0, 0)
                    return
                }
                if (errorCode != BiometricPrompt.ERROR_CANCELED && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    Toast.makeText(this@BiometricApprovalActivity, errString, Toast.LENGTH_SHORT).show()
                }
                cancelAuthorization()
            }
        })
        lifecycleScope.launch {
            runtime.manager.state.collect {
                if (!finished && !successful && request?.let(::canApprove) != true) cancelAuthorization()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("批准 ${live.serverName} 的请求")
            .setSubtitle("${live.user} · ${live.service}")
            .setNegativeButtonText("拒绝")
            // Single touch on the sensor approves: no extra confirm step.
            .setConfirmationRequired(false)
            .apply {
                // Android 15+ renders our request details inside the system prompt itself.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    setContentView(
                        PromptVerticalListContentView.Builder().apply {
                            val detail = live.command.ifBlank { live.reason }
                            if (detail.isNotBlank()) {
                                addListItem(PromptContentItemPlainText(detail.take(200)))
                            }
                        }.build(),
                    )
                }
            }
            .build()
        prompt?.authenticate(info)
    }

    private fun canApprove(candidate: PendingApproval): Boolean {
        val state = runtime.manager.state.value
        return candidate.expiresAt > System.currentTimeMillis() && candidate.key !in state.busy &&
            state.approvals.any { it == candidate } &&
            state.servers.any { it.config.id == candidate.serverId && it.linkState == LinkState.AUTHENTICATED }
    }

    private fun cancelAuthorization() {
        if (finished) return
        finished = true
        runtime.approvalSubmitted.value = null
        if (runtime.approvalHost.value == request?.key) runtime.approvalHost.value = null
        prompt?.cancelAuthentication()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!successful && runtime.approvalHost.value == request?.key) runtime.approvalHost.value = null
        prompt?.cancelAuthentication()
        super.onDestroy()
    }
}
