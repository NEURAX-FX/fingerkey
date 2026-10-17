// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.page

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import com.phonefprint.auth.LinkState
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.PendingApproval
import com.phonefprint.auth.ServerManager
import com.phonefprint.auth.ui.theme.UiSettings
import com.phonefprint.auth.ui.theme.barColor
import com.phonefprint.auth.ui.theme.glassEffect
import com.phonefprint.auth.ui.theme.rememberGlassBackdrop
import com.phonefprint.auth.ui.widgets.CardSpacing
import com.phonefprint.auth.ui.widgets.MonoValue
import com.phonefprint.auth.ui.widgets.FingerKeyTopBar
import com.phonefprint.auth.ui.widgets.fingerKeyScrollBehavior
import com.phonefprint.auth.ui.widgets.PageGutter
import com.phonefprint.auth.ui.widgets.StatCard
import com.phonefprint.auth.ui.widgets.TipCard
import com.phonefprint.auth.ui.widgets.connectionSummary

/** Tab 1: live link status on top, everything waiting for a decision below. */
@Composable
fun HomePage(
    context: Context,
    state: ManagerState,
    manager: ServerManager,
    title: String,
    outerPadding: PaddingValues,
    settings: UiSettings,
    onApprove: (PendingApproval) -> Unit,
    onOpenServers: () -> Unit,
) {
    val scrollBehavior = fingerKeyScrollBehavior()
    // Per-page pair: this list records the layer, this app bar samples it. Sharing the shell's
    // backdrop would make the bar sample a layer that contains itself and hang the render thread.
    val backdrop = rememberGlassBackdrop(settings.blurEnabled)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            FingerKeyTopBar(
                title = title,
                modifier = Modifier.glassEffect(backdrop),
                color = backdrop.barColor(),
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
                bottom = outerPadding.calculateBottomPadding() + 32.dp,
            ),
            overscrollEffect = null,
        ) {
            item {
                StatusHero(state = state, onOpenServers = onOpenServers)
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(horizontal = PageGutter)
                        .padding(bottom = CardSpacing),
                    horizontalArrangement = Arrangement.spacedBy(CardSpacing),
                ) {
                    StatCard(
                        title = "在线服务器",
                        value = "${state.servers.count { it.linkState == LinkState.AUTHENTICATED }} / ${state.servers.size}",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = onOpenServers,
                    )
                    StatCard(
                        title = "待处理请求",
                        value = "${state.approvals.size}",
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }

            item {
                DeviceCard(state = state, soundEnabled = state.soundEnabled)
            }

            if (state.approvals.isEmpty()) {
                item {
                    EmptyApprovalsCard(state = state, loading = state.loading)
                }
            } else {
                item { SmallTitle("待处理请求", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                items(state.approvals.size, key = { index -> state.approvals[index].serverId + ":" + state.approvals[index].id }) { index ->
                    ApprovalRequestCard(
                        approval = state.approvals[index],
                        state = state,
                        manager = manager,
                        now = now,
                        onApprove = onApprove,
                    )
                }
            }

            state.error?.let { message ->
                item {
                    ActionErrorCard(message = message, onDismiss = manager::clearError)
                }
            }
        }
    }
}

/**
 * Large status card: container colour follows link health and a big translucent icon sits in the
 * bottom-end corner — InstallerX's home signature, at its original scale.
 */
@Composable
private fun StatusHero(state: ManagerState, onOpenServers: () -> Unit) {
    val online = state.servers.count { it.linkState == LinkState.AUTHENTICATED }
    val hasServers = state.servers.isNotEmpty()
    val healthy = hasServers && online > 0

    val container = when {
        healthy && isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
        !healthy && isDynamicColor -> MiuixTheme.colorScheme.errorContainer
        healthy -> MiuixTheme.colorScheme.surfaceContainerHigh
        else -> MiuixTheme.colorScheme.errorContainer
    }
    val content = when {
        healthy && isDynamicColor -> MiuixTheme.colorScheme.onSecondaryContainer
        !healthy && isDynamicColor -> MiuixTheme.colorScheme.onErrorContainer
        else -> MiuixTheme.colorScheme.onSurface
    }
    val headline = when {
        state.loading -> "正在读取设备身份"
        !hasServers -> "尚未配对服务器"
        online == 0 -> "服务器全部离线"
        state.approvals.isNotEmpty() -> "${state.approvals.size} 个请求等待批准"
        else -> "指纹批准已就绪"
    }
    val description = when {
        state.loading -> "首次启动会生成 Ed25519 设备密钥"
        !hasServers -> "在电脑上执行 sudo fingerkey pair-code my-phone，再回到「服务器」页右下角添加"
        online == 0 -> "确认电脑上的 fingerkeyd 正在运行，且手机与电脑在同一网络"
        state.approvals.isNotEmpty() -> "点开请求卡片，验证指纹后即可放行"
        else -> "$online 台服务器在线，提权请求会自动推送到这里"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .padding(bottom = CardSpacing),
        colors = CardDefaults.defaultColors(color = container),
        onClick = onOpenServers,
        showIndication = true,
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.matchParentSize().offset(28.dp, 12.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    imageVector = MiuixIcons.ScreenMirroring,
                    contentDescription = null,
                    modifier = Modifier.size(132.dp),
                    tint = content.copy(alpha = 0.22f),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    headline,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
                Text(
                    description,
                    style = MiuixTheme.textStyles.body2,
                    color = content.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

/** Device identity and background-service summary. */
@Composable
private fun DeviceCard(state: ManagerState, soundEnabled: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .padding(bottom = CardSpacing),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        BasicComponent(
            title = "已配对服务器",
            summary = if (state.servers.isEmpty()) "无" else state.servers.joinToString("、") { it.config.name },
        )
        BasicComponent(
            title = "批准提示音",
            summary = if (soundEnabled) "已开启" else "已关闭",
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "设备公钥",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            MonoValue(state.publicKey.ifEmpty { "尚未生成" })
        }
    }
}

@Composable
private fun EmptyApprovalsCard(state: ManagerState, loading: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .padding(bottom = CardSpacing),
        insideMargin = PaddingValues(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                MiuixIcons.Ok,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MiuixTheme.colorScheme.outline,
            )
            Text(
                if (loading) "正在准备…" else "暂无待审批请求",
                style = MiuixTheme.textStyles.headline2,
            )
            Text(
                if (state.servers.isEmpty()) {
                    "先在「服务器」中添加一台电脑"
                } else {
                    "新的请求会显示在这里，离开应用后也能通过通知收到"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ActionErrorCard(message: String, onDismiss: () -> Unit) {
    TipCard(
        modifier = Modifier.padding(horizontal = PageGutter).padding(bottom = CardSpacing),
    ) {
        Text("操作未完成", style = MiuixTheme.textStyles.headline2, color = MiuixTheme.colorScheme.error)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MiuixTheme.textStyles.body2)
        TextButton("知道了", onClick = onDismiss, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ApprovalRequestCard(
    approval: PendingApproval,
    state: ManagerState,
    manager: ServerManager,
    now: Long,
    onApprove: (PendingApproval) -> Unit,
) {
    val remaining = ((approval.expiresAt - now) / 1000).coerceAtLeast(0)
    val online = state.servers.any {
        it.config.id == approval.serverId && it.linkState == LinkState.AUTHENTICATED
    }
    val busy = approval.key in state.busy
    val actionable = remaining > 0 && online && !busy

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter)
            .padding(bottom = CardSpacing),
        insideMargin = PaddingValues(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    approval.serverName,
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${approval.user}  ·  ${approval.service}",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                if (remaining > 0) "${remaining}s" else "已过期",
                style = MiuixTheme.textStyles.footnote1,
                color = if (remaining in 1..10) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (approval.command.isNotBlank()) {
                Text(
                    approval.command,
                    Modifier
                        .fillMaxWidth()
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                )
            }
            if (approval.reason.isNotBlank()) Text("原因　${approval.reason}", style = MiuixTheme.textStyles.body2)
            if (approval.tty.isNotBlank()) {
                Text(
                    "终端　${approval.tty}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        if (!online || busy || remaining == 0L) {
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    remaining == 0L -> "请求已过期，无需处理"
                    !online -> "该服务器离线，恢复连接后仍可处理"
                    else -> "正在等待服务器确认…"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = "拒绝",
                onClick = { manager.deny(approval) },
                modifier = Modifier.weight(1f),
                enabled = actionable,
            )
            TextButton(
                text = "指纹批准",
                onClick = { onApprove(approval) },
                modifier = Modifier.weight(1.2f),
                enabled = actionable,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        TextButton(
            text = "隐藏此请求",
            onClick = { manager.dismiss(approval.key) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            enabled = !busy,
            colors = ButtonDefaults.textButtonColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            ),
        )
    }
}
