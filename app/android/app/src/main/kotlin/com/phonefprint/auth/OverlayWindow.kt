// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Lock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import com.phonefprint.auth.ui.theme.UiPreferences

/**
 * Overlay theme: mirrors the in-app appearance (wallpaper palette when dynamic colour is on) so the
 * card does not look pasted on top of the system UI.
 */
@Composable
private fun overlayThemeController(context: Context): ThemeController {
    val settings = UiPreferences.get(context).settings.value
    val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return remember(settings, dynamic) {
        ThemeController(
            colorSchemeMode = if (dynamic) ColorSchemeMode.MonetSystem else ColorSchemeMode.System,
            isDark = null,
        )
    }
}

/** The window only provides overlay placement; all card layout and controls are Compose/Miuix. */
class OverlayWindow(private val context: Context, private val manager: ServerManager) {
    private val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var current: CardWindow? = null
    private val exiting = mutableSetOf<CardWindow>()
    /** Requests already pushed into the system prompt in direct mode; never auto-prompt the same one twice. */
    private val autoPrompted = mutableSetOf<ApprovalKey>()
    private var keepAlive: View? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun reconcile(approvals: List<PendingApproval>, foreground: Boolean, busy: Set<ApprovalKey>) {
        val direct = UiPreferences.get(context).settings.value.directBiometric
        val runtime = FingerKeyRuntime.get(context)
        val approvalHost = runtime.approvalHost.value
        autoPrompted.retainAll(approvals.mapTo(mutableSetOf()) { it.key })
        if (foreground || !Settings.canDrawOverlays(context)) {
            hide()
            return
        }
        if (direct) {
            // No card in this mode: the system prompt is the whole surface. A 1dp transparent overlay
            // is attached first, because Android lets an app start an activity from the background only
            // while it owns a visible overlay window.
            if (approvalHost != null) return
            val target = approvals.firstOrNull { it.key !in busy && it.key !in autoPrompted }
            if (target == null) {
                hideKeepAlive()
                return
            }
            autoPrompted.add(target.key)
            showKeepAlive()
            handler.postDelayed({ launchAuthorization(target, hideCard = true) }, 250L)
            return
        }
        hideKeepAlive()
        val approval = approvals.firstOrNull { it.key != approvalHost }
        if (approval == null) {
            hide()
            return
        }
        current?.takeIf { it.approval == approval || it.approval.key == approvalHost }?.let {
            it.busy = it.approval.key in busy
            it.count = approvals.size
            return
        }
        hide()
        show(CardWindow(approval, approvals.size, approval.key in busy))
    }

    /** Adds the card window with the shared placement params and a bottom-sheet entry animation. */
    private fun show(card: CardWindow) {
        card.view.alpha = 0f
        try {
            windows.addView(card.view, windowParams())
            current = card
            card.view.post {
                if (current !== card) return@post
                // Measured height, so the card really slides up from below the screen edge.
                val distance = card.view.height.toFloat().takeIf { it > 0f } ?: dp(220).toFloat()
                card.view.translationY = distance
                card.view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(320)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()

            }
        } catch (error: RuntimeException) {
            card.dispose()
            Log.w(TAG, "Overlay unavailable; request remains in notifications", error)
        }
    }

    private fun windowParams(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = 0
    }

    /** 1dp transparent overlay kept while a background activity launch is in flight. */
    private fun showKeepAlive() {
        if (keepAlive != null) return
        val view = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
        }
        keepAlive = view
        runCatching { windows.addView(view, params) }
        handler.postDelayed({ hideKeepAlive() }, 6000L)
    }

    private fun hideKeepAlive() {
        val view = keepAlive ?: return
        keepAlive = null
        runCatching { windows.removeViewImmediate(view) }
    }

    fun hide(immediate: Boolean = false) {
        if (immediate) exiting.toList().forEach(::remove)
        val old = current ?: return
        current = null
        old.busy = true
        old.view.animate().cancel()
        if (immediate) {
            remove(old)
        } else {
            exiting.add(old)
            val distance = old.view.height.toFloat().takeIf { it > 0f } ?: dp(220).toFloat()
            old.view.animate()
                .alpha(0f)
                .translationY(distance)
                .setDuration(220)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction { remove(old) }
                .start()
        }
    }

    private fun remove(card: CardWindow) {
        card.view.animate().cancel()
        exiting.remove(card)
        try {
            windows.removeViewImmediate(card.view)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Overlay already removed", error)
        }
        card.dispose()
    }

    /**
     * Hands the request to the invisible biometric host. The sheet stays on screen while the system
     * prompt is up, so cancelling the prompt leaves the request open for a retry.
     */
    private fun launchAuthorization(approval: PendingApproval, hideCard: Boolean) {
        val runtime = FingerKeyRuntime.get(context)
        if (manager.state.value.approvals.none { it == approval } || approval.expiresAt <= System.currentTimeMillis()) return
        runtime.approvalHost.value = approval.key
        runtime.approvalSubmitted.value = null
        try {
            context.startActivity(FingerKeyRuntime.approvalIntent(context, approval.key, approve = true))
            if (hideCard) hide()
        } catch (error: RuntimeException) {
            runtime.approvalHost.value = null
            Log.w(TAG, "Unable to launch system biometric approval", error)
        }
    }

    private inner class CardWindow(
        val approval: PendingApproval,
        initialCount: Int,
        initialBusy: Boolean,
    ) : SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        private val savedStateController = SavedStateRegistryController.create(this)
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
        var count by mutableIntStateOf(initialCount)
        var busy by mutableStateOf(initialBusy)
        val view: ComposeView

        init {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            view = ComposeView(context).apply {
                setViewTreeLifecycleOwner(this@CardWindow)
                setViewTreeSavedStateRegistryOwner(this@CardWindow)
                setContent {
                    MiuixTheme(controller = overlayThemeController(context)) {
                        OverlayCard(
                            approval = approval,
                            count = count,
                            busy = busy,
                            onDismiss = {
                                // Leaving the sheet counts as "not authenticating": tell the server now
                                // instead of making the sudo prompt wait for the request to expire.
                                if (current === this@CardWindow) {
                                    live(approval)?.let(manager::deny)
                                    hide()
                                }
                            },
                            onApprove = { if (current === this@CardWindow) launchAuthorization(approval, hideCard = false) },
                        )
                    }
                }
            }
        }

        fun dispose() {
            view.disposeComposition()
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private fun live(approval: PendingApproval): PendingApproval? = manager.state.value.approvals.firstOrNull {
        it == approval && it.expiresAt > System.currentTimeMillis() && it.key !in manager.state.value.busy
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object { private const val TAG = "ApprovalOverlay" }
}

/**
 * Request card styled after InstallerX's install sheet: app icon tile + name + subject line, a
 * label/value info block, the command in a monospace well, then the decision buttons.
 */
/**
 * Request card in InstallerX's card idiom: icon tile + name + subject, a remaining-time meter, a
 * grouped detail block, the command in its own well, then the decision buttons.
 */
/**
 * Reduced take on InstallerX's install sheet: centred icon + subject with a status chip, label/value
 * cards, a dedicated block for the command, then the decision buttons.
 */
@Composable
private fun OverlayCard(
    approval: PendingApproval,
    count: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(approval.key) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val remaining = ((approval.expiresAt - now) / 1000).coerceAtLeast(0)
    val enabled = !busy && remaining > 0
    val icon = remember(context) { appIconBitmap(context) }
    val urgent = remaining in 1..10

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(enabled = false, onClick = {})
            .padding(bottom = navBarInset()),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .size(width = 34.dp, height = 4.dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                Icon(
                    MiuixIcons.Close,
                    contentDescription = "不想认证",
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                "批准请求",
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.title3,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(40.dp))
        }

        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)),
                    )
                } else {
                    Icon(
                        MiuixIcons.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                approval.serverName,
                style = MiuixTheme.textStyles.title2,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${approval.user} · ${approval.service}",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            urgent -> MiuixTheme.colorScheme.errorContainer
                            else -> MiuixTheme.colorScheme.surfaceContainerHighest
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    buildString {
                        append(
                            when {
                                busy -> "等待服务器确认…"
                                remaining > 0 -> "剩余 ${remaining}s"
                                else -> "已过期"
                            },
                        )
                        if (count > 1) append(" · 共 $count 个请求")
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = when {
                        urgent -> MiuixTheme.colorScheme.onErrorContainer
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 16.dp,
            insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailRow("用户", approval.user)
                DetailRow("服务", approval.service)
                if (approval.tty.isNotBlank()) DetailRow("终端", approval.tty)
            }
        }

        if (approval.command.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(14.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
            ) {
                Text("命令", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    approval.command,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState()),
                    style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (approval.reason.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 16.dp,
                insideMargin = PaddingValues(14.dp),
                colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainerHigh),
            ) {
                Text("原因", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    approval.reason,
                    modifier = Modifier.fillMaxWidth(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(
            text = "确认指纹",
            onClick = onApprove,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.primaryContainer,
                textColor = MiuixTheme.colorScheme.onPrimaryContainer,
            ),
        )
        }
    }
}

/** Height of the system navigation bar, so the sheet clears the gesture pill. */
@Composable
private fun navBarInset() = with(LocalDensity.current) {
    val id = LocalContext.current.resources.getIdentifier("navigation_bar_height", "dimen", "android")
    val px = if (id > 0) LocalContext.current.resources.getDimensionPixelSize(id) else 0
    px.toDp() + 8.dp
}

/** Label on the left, value on the right — InstallerX's info-row rhythm. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MiuixTheme.textStyles.body2,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body2,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Launcher icon as a bitmap so the card header can show it without an extra image loader. */
private fun appIconBitmap(context: Context): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(context.packageName)
    val size = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(96)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()
