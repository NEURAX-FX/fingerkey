// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Layer backdrop for the glass chrome (app bars, navigation bar). Paints the surface colour first
 * so the blurred copy never alpha-blends into the wallpaper.
 *
 */
@Composable
fun rememberGlassBackdrop(enabled: Boolean): LayerBackdrop? {
    if (!enabled || !isRenderEffectSupported()) return null
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
}

/** App bar / navigation bar colour: transparent while the backdrop is drawing, surface otherwise. */
@Composable
fun LayerBackdrop?.barColor(): Color = this?.let { Color.Transparent } ?: MiuixTheme.colorScheme.surface

/** Standard glass effect for bars drawn on top of a scrolling layer. */
@Composable
fun Modifier.glassEffect(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    blurRadius: Float = 25f,
    shape: Shape = RectangleShape,
): Modifier {
    if (!enabled || backdrop == null) return this
    val blend = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)
    return then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(blendColors = listOf(BlendColorEntry(color = blend))),
        ),
    )
}

/** True when the running device can actually render backdrop blur (Android 12+ graphics stack). */
val supportsBackdropBlur: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isRenderEffectSupported()
