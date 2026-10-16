// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.PendingApproval
import com.phonefprint.auth.ServerManager
import com.phonefprint.auth.ui.navigation.LocalNavigator
import com.phonefprint.auth.ui.navigation.MainPagerState
import com.phonefprint.auth.ui.navigation.Route
import com.phonefprint.auth.ui.navigation.rememberMainPagerState
import com.phonefprint.auth.ui.page.HomePage
import com.phonefprint.auth.ui.page.ServersPage
import com.phonefprint.auth.ui.page.SettingsPage
import com.phonefprint.auth.ui.theme.UiSettings
import com.phonefprint.auth.ui.theme.barColor
import com.phonefprint.auth.ui.theme.glassEffect
import com.phonefprint.auth.ui.theme.rememberGlassBackdrop
import com.phonefprint.auth.ui.window.LocalWindowLayoutInfo
import com.phonefprint.auth.ui.window.rememberWindowLayoutInfo

private const val TAB_HOME = 0
private const val TAB_SERVERS = 1
private const val TAB_SETTINGS = 2

private val tabs = listOf(
    NavigationItem(label = "审批", icon = MiuixIcons.Tasks),
    NavigationItem(label = "服务器", icon = MiuixIcons.ScreenMirroring),
    NavigationItem(label = "设置", icon = MiuixIcons.Settings),
)

/**
 * Tab shell: outer scaffold owns the navigation bar, the floating action and the window-width
 * decision; each tab renders its own app bar inside the pager, like InstallerX does.
 */
@Composable
fun MainPage(
    context: Context,
    state: ManagerState,
    manager: ServerManager,
    settings: UiSettings,
    onApprove: (PendingApproval) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val windowLayout = rememberWindowLayoutInfo()
    val pagerState = rememberMainPagerState(tabs.size)
    val backdrop = rememberGlassBackdrop(settings.blurEnabled)

    CompositionLocalProvider(LocalWindowLayoutInfo provides windowLayout) {
        if (windowLayout.showNavigationRail) {
            Row(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                NavigationRail {
                    tabs.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = pagerState.selectedPage == index,
                            onClick = { pagerState.animateToPage(index) },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    TabPager(
                        context = context,
                        state = state,
                        manager = manager,
                        settings = settings,
                        pagerState = pagerState,
                        outerPadding = PaddingValues.Zero,
                        backdrop = backdrop,
                        onApprove = onApprove,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onOpenBatterySettings = onOpenBatterySettings,
                        onRequestNotifications = onRequestNotifications,
                    )
                }
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    GlassNavigationBar(backdrop = backdrop, pagerState = pagerState)
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = pagerState.selectedPage == TAB_SERVERS,
                        enter = scaleIn(),
                        exit = scaleOut(),
                    ) {
                        AddServerFab()
                    }
                },
            ) { padding ->
                TabPager(
                    context = context,
                    state = state,
                    manager = manager,
                    settings = settings,
                    pagerState = pagerState,
                    outerPadding = padding,
                    backdrop = backdrop,
                    onApprove = onApprove,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onRequestNotifications = onRequestNotifications,
                )
            }
        }
    }
}

/** Bottom bar that turns into glass when a backdrop is available. */
@Composable
private fun GlassNavigationBar(
    backdrop: LayerBackdrop?,
    pagerState: MainPagerState,
) {
    val barColor = backdrop.barColor()
    if (backdrop == null) {
        NavigationBar {
            TabItems(pagerState)
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)),
                    ),
                ),
            )
            .background(barColor),
    ) {
        NavigationBar(color = Color.Transparent) {
            TabItems(pagerState)
        }
    }
}

@Composable
private fun RowScope.TabItems(pagerState: MainPagerState) {
    tabs.forEachIndexed { index, item ->
        NavigationBarItem(
            selected = pagerState.selectedPage == index,
            onClick = { pagerState.animateToPage(index) },
            icon = item.icon,
            label = item.label,
        )
    }
}

@Composable
private fun AddServerFab() {
    val navigator = LocalNavigator.current
    FloatingActionButton(
        onClick = { navigator.push(Route.Pairing) },
        modifier = Modifier.padding(end = 16.dp),
        containerColor = if (isDynamicColor) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
    ) {
        Icon(
            MiuixIcons.Add,
            contentDescription = "添加服务器",
            modifier = Modifier.size(32.dp),
            tint = if (isDynamicColor) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TabPager(
    context: Context,
    state: ManagerState,
    manager: ServerManager,
    settings: UiSettings,
    pagerState: MainPagerState,
    outerPadding: PaddingValues,
    backdrop: LayerBackdrop?,
    onApprove: (PendingApproval) -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    HorizontalPager(
        state = pagerState.pagerState,
        userScrollEnabled = true,
        overscrollEffect = null,
        beyondViewportPageCount = 1,
        modifier = Modifier
            .fillMaxSize()
            .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
    ) { page ->
        when (page) {
            TAB_HOME -> HomePage(
                context = context,
                state = state,
                manager = manager,
                title = tabs[page].label,
                outerPadding = outerPadding,
                settings = settings,
                onApprove = onApprove,
                onOpenServers = { pagerState.animateToPage(TAB_SERVERS) },
            )

            TAB_SERVERS -> ServersPage(
                state = state,
                manager = manager,
                title = tabs[page].label,
                outerPadding = outerPadding,
                settings = settings,
            )

            else -> SettingsPage(
                context = context,
                state = state,
                manager = manager,
                settings = settings,
                title = tabs[page].label,
                outerPadding = outerPadding,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onOpenBatterySettings = onOpenBatterySettings,
                onRequestNotifications = onRequestNotifications,
            )
        }
    }
}
