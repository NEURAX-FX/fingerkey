// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.nestedscroll.nestedScroll
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import com.phonefprint.auth.ui.theme.ThemeState
import com.phonefprint.auth.ui.theme.barColor
import com.phonefprint.auth.ui.theme.glassEffect
import com.phonefprint.auth.ui.theme.rememberGlassBackdrop
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import com.phonefprint.auth.LinkState
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.ServerState

// Page rhythm: every page keeps 12dp side gutters and stacks cards 12dp apart.
val PageGutter = 12.dp
val CardSpacing = 12.dp

/** Standard pushed-page back affordance. */
@Composable
fun FingerKeyBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(start = 12.dp)) {
        Icon(MiuixIcons.Back, "返回")
    }
}

/** A coloured dot plus the human-readable link state. */
@Composable
fun StatusLine(state: ServerState) {
    val color = statusColor(state.linkState)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(statusText(state), style = MiuixTheme.textStyles.footnote1, color = color)
    }
}

@Composable
fun statusColor(linkState: LinkState): Color = when (linkState) {
    LinkState.AUTHENTICATED -> MiuixTheme.colorScheme.primary
    LinkState.PIN_MISMATCH, LinkState.UNREGISTERED -> MiuixTheme.colorScheme.error
    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

fun statusText(state: ServerState): String = when (state.linkState) {
    LinkState.AUTHENTICATED -> "在线 · 已认证"
    LinkState.CONNECTING -> "正在连接…"
    LinkState.CONNECTED -> "已连接，正在认证…"
    LinkState.RETRYING -> "离线，正在重试"
    LinkState.UNREGISTERED -> "服务器不再认可此密钥"
    LinkState.PIN_MISMATCH -> "TLS 指纹不匹配，已阻止连接"
    LinkState.STOPPED -> "连接已停止"
    LinkState.IDLE -> "等待连接"
}

/** Monospace, selectable-looking value block used for fingerprints and keys. */
@Composable
fun MonoValue(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Title above a value, used for the detail rows on the home and about pages. */
@Composable
fun LabeledRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body1)
    }
}

fun connectionSummary(state: ManagerState): String {
    val online = state.servers.count { it.linkState == LinkState.AUTHENTICATED }
    return when {
        state.loading -> "正在读取设备身份"
        state.servers.isEmpty() -> "尚未配对服务器"
        online == 0 -> "${state.servers.size} 台服务器均离线"
        else -> "$online / ${state.servers.size} 台服务器在线"
    }
}

/**
 * Accent-tinted call-out card. Mirrors InstallerX's tip card: quiet primary container wash with an
 * optional trailing action.
 */
@Composable
fun TipCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = if (isDynamicColor) 0.45f else 0.3f),
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
        insideMargin = PaddingValues(16.dp),
    ) {
        content()
    }
}

/** Big number under a caption, sized so two of them fit a phone row. */
@Composable
fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                value,
                style = MiuixTheme.textStyles.headline1,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Scaffold for pages pushed on top of the tab shell: glass app bar with a back action, then a
 * padded lazy column. Mirrors the InstallerX secondary-page layout.
 */
@Composable
fun PushedPage(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val settings = ThemeState.settings
    val backdrop = rememberGlassBackdrop(settings.blurEnabled)
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = title,
                subtitle = subtitle,
                modifier = Modifier.glassEffect(backdrop),
                color = backdrop.barColor(),
                navigationIcon = { FingerKeyBackButton(onBack) },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = PageGutter,
                top = innerPadding.calculateTopPadding() + PageGutter,
                end = PageGutter,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
            overscrollEffect = null,
            content = content,
        )
    }
}
