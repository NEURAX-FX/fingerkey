// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

private val LocalIsDark = staticCompositionLocalOf { false }
private val LocalSettings = staticCompositionLocalOf { UiSettings() }

/** Mirror of the active appearance settings, readable from any composable. */
object ThemeState {
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalIsDark.current

    val settings: UiSettings
        @Composable @ReadOnlyComposable
        get() = LocalSettings.current

    /** Wallpaper-driven Monet palettes exist from Android 12. */
    val isDynamicColorAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
}

/**
 * Theme facade: picks the Miuix colour engine from the stored appearance preferences.
 *
 * Dynamic colour resolves the wallpaper palette through [ColorSchemeMode.MonetSystem]. With it
 * disabled the same engine still runs, keyed on the user's seed colour, so palette style and
 * colour spec keep working.
 */
@Composable
fun FingerKeyTheme(settings: UiSettings, content: @Composable () -> Unit) {
    val isDark = when (settings.themeMode) {
        ThemeModeOption.SYSTEM -> isSystemInDarkTheme()
        ThemeModeOption.LIGHT -> false
        ThemeModeOption.DARK -> true
    }
    val useDynamic = settings.dynamicColor && ThemeState.isDynamicColorAvailable

    val mode = when (settings.themeMode) {
        ThemeModeOption.SYSTEM -> if (useDynamic) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
        ThemeModeOption.LIGHT -> if (useDynamic) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        ThemeModeOption.DARK -> if (useDynamic) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
    }
    // Dynamic colour follows the wallpaper (null key); otherwise the seed drives the palette engine.
    val keyColor = if (useDynamic) null else settings.seedColor

    val controller = remember(mode, keyColor, settings.paletteStyle, settings.colorSpec, isDark) {
        ThemeController(
            colorSchemeMode = mode,
            keyColor = keyColor,
            paletteStyle = settings.paletteStyle,
            colorSpec = settings.colorSpec,
            isDark = isDark,
        )
    }

    SystemBarAppearance(isDark)

    CompositionLocalProvider(
        LocalIsDark provides isDark,
        LocalSettings provides settings,
    ) {
        MiuixTheme(controller = controller, content = content)
    }
}

/** Light/dark status-bar icons plus the un-enforced navigation bar scrim Android 10+ adds. */
@Composable
private fun SystemBarAppearance(isDark: Boolean) {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !isDark
    }
    DisposableEffect(configuration) {
        val window = (view.context as? Activity)?.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window?.isNavigationBarContrastEnforced = false
        }
        onDispose { }
    }
}

/** Seed colours offered when dynamic colour is off. */
val seedSwatches: List<Color> = listOf(
    Color(0xFF6750A4),
    Color(0xFF0B6BCB),
    Color(0xFF2E7D32),
    Color(0xFFB3261E),
    Color(0xFFE65100),
    Color(0xFF00897B),
    Color(0xFF7B1FA2),
    Color(0xFF9E9D24),
    Color(0xFF455A64),
    Color(0xFFD81B60),
)
