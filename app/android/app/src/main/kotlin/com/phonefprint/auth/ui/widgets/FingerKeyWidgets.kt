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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

/** Label on the left, value right-aligned — InstallerX's info-row rhythm. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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

/** One-line link summary used by the home app-bar subtitle. */
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
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
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
                start = 0.dp,
                top = innerPadding.calculateTopPadding() + PageGutter,
                end = 0.dp,
                bottom = innerPadding.calculateBottomPadding() + 32.dp,
            ),
            overscrollEffect = null,
            content = content,
        )
    }
}

/**
 * App bar whose single title shrinks and slides from the large-title position (start-aligned,
 * 26dp, below the collapsed row) into the centred collapsed-title slot while the list scrolls.
 *
 * Miuix's TopAppBar only cross-fades its two title layers; this draws one title and interpolates
 * scale plus translation, driven by the same [ScrollBehavior] that feeds the list.
 */
@Composable
fun FingerKeyTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
) {
    val density = LocalDensity.current
    val styles = MiuixTheme.textStyles
    val state = scrollBehavior.state
    val collapsedPx = with(density) { TopAppBarDefaults.CollapsedHeight.toPx() }
    var textSize by remember { mutableStateOf(IntSize.Zero) }
    val largeLinePx = with(density) {
        if (textSize.height > 0) textSize.height.toFloat()
        else styles.title1.fontSize.toPx() * 1.35f
    }
    val expandedPx = collapsedPx + largeLinePx +
        with(density) { TopAppBarDefaults.LargeTitleBottomPadding.toPx() }

    // Tell the scroll behaviour how far it may collapse; it consumes scroll for this distance.
    SideEffect {
        val limit = -(expandedPx - collapsedPx)
        if (state.heightOffsetLimit != limit) state.heightOffsetLimit = limit
    }

    var barWidth by remember { mutableIntStateOf(0) }
    val barHeight = with(density) {
        (expandedPx + state.heightOffset).coerceIn(collapsedPx, expandedPx).toDp()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(color)
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clipToBounds()
                .onSizeChanged { barWidth = it.width },
        ) {
            Text(
                text = title,
                style = styles.title1,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .onSizeChanged { textSize = it }
                    .graphicsLayer {
                        // heightOffset and its limit are both negative; the ratio is 0..1 as the bar collapses.
                        val limit = state.heightOffsetLimit
                        val progress = if (limit == 0f) 0f else (state.heightOffset / limit).coerceIn(0f, 1f)
                        val scale = lerp(1f, styles.title3.fontSize.value / styles.title1.fontSize.value, progress)
                        val startX = TopAppBarDefaults.TitlePadding.toPx()
                        val startY = collapsedPx
                        val endX = (barWidth - textSize.width * scale) / 2f
                        val endY = (collapsedPx - textSize.height * scale) / 2f
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = lerp(startX, endX, progress)
                        translationY = lerp(startY, endY, progress)
                    },
            )
        }
    }
}

/**
 * Shared top-bar scroll behaviour. Miuix snaps with `spring(stiffness = 2500f)`; a softer
 * stiffness makes the collapse settle ~10% slower, which reads less abrupt.
 */
@Composable
fun fingerKeyScrollBehavior(): ScrollBehavior = MiuixScrollBehavior(
    snapAnimationSpec = spring(stiffness = 2050f),
)
