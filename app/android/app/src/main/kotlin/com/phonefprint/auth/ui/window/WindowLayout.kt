// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/** Material window size classes, trimmed to what the shell reacts to. */
enum class WindowLayoutType { COMPACT, MEDIUM, EXPANDED }

data class WindowLayoutInfo(
    val type: WindowLayoutType,
    val isLandscape: Boolean,
    val showNavigationRail: Boolean,
)

val LocalWindowLayoutInfo = compositionLocalOf {
    WindowLayoutInfo(WindowLayoutType.COMPACT, isLandscape = false, showNavigationRail = false)
}

/** Rail on tablets and on anything wide enough that a bottom bar would waste height. */
@Composable
fun rememberWindowLayoutInfo(): WindowLayoutInfo {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return remember(containerSize, density) {
        val widthDp = with(density) { containerSize.width.toDp().value }
        val heightDp = with(density) { containerSize.height.toDp().value }
        val type = when {
            widthDp >= 840f -> WindowLayoutType.EXPANDED
            widthDp >= 600f -> WindowLayoutType.MEDIUM
            else -> WindowLayoutType.COMPACT
        }
        val landscape = widthDp > heightDp
        WindowLayoutInfo(
            type = type,
            isLandscape = landscape,
            showNavigationRail = type == WindowLayoutType.EXPANDED ||
                (type == WindowLayoutType.MEDIUM && landscape),
        )
    }
}
