// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** Appearance choices surfaced in 设置 → 外观. */
enum class ThemeModeOption { SYSTEM, LIGHT, DARK }

data class UiSettings(
    val themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    val dynamicColor: Boolean = true,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.Spec2025,
    val blurEnabled: Boolean = true,
    /** true: a new request opens the biometric prompt immediately; false: wait for the sheet button. */
    val directBiometric: Boolean = false,
    val seedColor: Color = DefaultSeedColor,
)

/** Material-ish purple that keeps the stock Miuix palettes recognisable before any choice is made. */
val DefaultSeedColor = Color(0xFF6750A4)

/**
 * Persisted appearance state. One instance per process; Compose reads it through
 * [LocalUiPreferences] and mutates it with [UiPreferences.update].
 */
class UiPreferences private constructor(context: Context) {
    private val store = context.getSharedPreferences("fingerkey.ui", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val settings: StateFlow<UiSettings> = mutable.asStateFlow()

    fun update(transform: (UiSettings) -> UiSettings) {
        val next = transform(mutable.value)
        mutable.value = next
        store.edit()
            .putString(KEY_THEME_MODE, next.themeMode.name)
            .putBoolean(KEY_DYNAMIC, next.dynamicColor)
            .putString(KEY_PALETTE, next.paletteStyle.name)
            .putString(KEY_SPEC, next.colorSpec.name)
            .putBoolean(KEY_BLUR, next.blurEnabled)
            .putBoolean(KEY_DIRECT_BIOMETRIC, next.directBiometric)
            .putInt(KEY_SEED, next.seedColor.toArgb())
            .apply()
    }

    private fun load(): UiSettings {
        val defaults = UiSettings()
        return UiSettings(
            themeMode = store.getString(KEY_THEME_MODE, null)
                ?.let { name -> ThemeModeOption.entries.firstOrNull { it.name == name } }
                ?: defaults.themeMode,
            dynamicColor = store.getBoolean(KEY_DYNAMIC, defaults.dynamicColor),
            paletteStyle = store.getString(KEY_PALETTE, null)
                ?.let { name -> ThemePaletteStyle.entries.firstOrNull { it.name == name } }
                ?: defaults.paletteStyle,
            colorSpec = store.getString(KEY_SPEC, null)
                ?.let { name -> ThemeColorSpec.entries.firstOrNull { it.name == name } }
                ?: defaults.colorSpec,
            blurEnabled = store.getBoolean(KEY_BLUR, defaults.blurEnabled),
            directBiometric = store.getBoolean(KEY_DIRECT_BIOMETRIC, defaults.directBiometric),
            seedColor = store.getInt(KEY_SEED, 0).takeIf { it != 0 }?.let { Color(it) } ?: defaults.seedColor,
        )
    }

    companion object {
        private const val KEY_THEME_MODE = "themeMode"
        private const val KEY_DYNAMIC = "dynamicColor"
        private const val KEY_PALETTE = "paletteStyle"
        private const val KEY_SPEC = "colorSpec"
        private const val KEY_BLUR = "blurEnabled"
        private const val KEY_DIRECT_BIOMETRIC = "directBiometric"
        private const val KEY_SEED = "seedColor"

        @Volatile private var instance: UiPreferences? = null

        fun get(context: Context): UiPreferences = instance ?: synchronized(this) {
            instance ?: UiPreferences(context.applicationContext).also { instance = it }
        }
    }
}
