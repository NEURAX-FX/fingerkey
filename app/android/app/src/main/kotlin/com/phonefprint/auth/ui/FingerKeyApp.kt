// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.phonefprint.auth.FingerKeyRuntime
import com.phonefprint.auth.PendingApproval
import com.phonefprint.auth.ui.navigation.LocalNavigator
import com.phonefprint.auth.ui.navigation.Navigator
import com.phonefprint.auth.ui.navigation.Route
import com.phonefprint.auth.ui.page.AboutPage
import com.phonefprint.auth.ui.page.PairingPage
import com.phonefprint.auth.ui.page.ServerDetailPage
import com.phonefprint.auth.ui.theme.FingerKeyTheme
import com.phonefprint.auth.ui.theme.UiPreferences

private const val REPOSITORY_URL = "https://github.com/7emotions/fingerkey"

/**
 * Application shell: appearance preferences drive the theme, one back stack drives navigation.
 * Pushed pages animate through miuix-nav with corner clipping and predictive back.
 */
@Composable
fun FingerKeyApp(
    context: Context,
    onApprove: (PendingApproval) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val manager = remember(context) { FingerKeyRuntime.get(context).manager }
    val state by manager.state.collectAsStateWithLifecycle()
    val preferences = remember(context) { UiPreferences.get(context) }
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack<Route>(Route.Main)
    val navigator = remember(backStack) { Navigator(backStack) }

    FingerKeyTheme(settings = settings) {
        CompositionLocalProvider(LocalNavigator provides navigator) {
            val backdropColor = MiuixTheme.colorScheme.surface
            val effects = remember(backdropColor) {
                NavDisplayEffects(
                    enableCornerClip = true,
                    cornerClipRadius = 32.dp,
                    cornerClipMode = NavCornerClipMode.Leading,
                    dimAmount = 0.4f,
                    backdropColor = backdropColor,
                )
            }
            NavDisplay(
                backStack = backStack,
                onBack = { navigator.pop() },
                transition = NavTransitions.MiuixDefault,
                effects = effects,
            ) {
                entry<Route.Main> {
                    MainPage(
                        context = context,
                        state = state,
                        manager = manager,
                        settings = settings,
                        onApprove = onApprove,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRequestNotifications = onRequestNotifications,
                    )
                }
                entry<Route.Pairing>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    PairingPage(state = state, manager = manager, onBack = { navigator.pop() })
                }
                entry<Route.ServerDetail>(swipeDismiss = NavSwipeDirection.LeftToRight) { route ->
                    ServerDetailPage(
                        fingerprint = route.fingerprint,
                        state = state,
                        manager = manager,
                        onBack = { navigator.pop() },
                        onRepair = { navigator.push(Route.Pairing) },
                    )
                }
                entry<Route.About>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                    AboutPage(
                        context = context,
                        onBack = { navigator.pop() },
                        onOpenRepository = { openRepository(context) },
                    )
                }
            }
        }
    }
}

private fun openRepository(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
