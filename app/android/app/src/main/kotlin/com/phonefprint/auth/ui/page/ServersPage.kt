// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
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
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.ServerManager
import com.phonefprint.auth.ServerState
import com.phonefprint.auth.ui.navigation.LocalNavigator
import com.phonefprint.auth.ui.navigation.Route
import com.phonefprint.auth.ui.theme.UiSettings
import com.phonefprint.auth.ui.theme.barColor
import com.phonefprint.auth.ui.theme.glassEffect
import com.phonefprint.auth.ui.theme.rememberGlassBackdrop
import com.phonefprint.auth.ui.widgets.FingerKeyTopBar
import com.phonefprint.auth.ui.widgets.fingerKeyScrollBehavior
import com.phonefprint.auth.ui.widgets.PageGutter
import com.phonefprint.auth.ui.widgets.StatusLine
import com.phonefprint.auth.ui.widgets.TipCard

/** Tab 2: paired computers. The floating action on this tab opens the pairing form. */
@Composable
fun ServersPage(
    state: ManagerState,
    manager: ServerManager,
    title: String,
    outerPadding: PaddingValues,
    settings: UiSettings,
) {
    val navigator = LocalNavigator.current
    val scrollBehavior = fingerKeyScrollBehavior()
    val backdrop = rememberGlassBackdrop(settings.blurEnabled)

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
            if (state.servers.isEmpty()) {
                item {
                    EmptyServersCard(onAddServer = { navigator.push(Route.Pairing) })
                }
            } else {
                item { SmallTitle("已配对", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                items(state.servers.size, key = { index -> state.servers[index].config.id }) { index ->
                    val server = state.servers[index]
                    ServerCard(
                        server = server,
                        onOpen = { navigator.push(Route.ServerDetail(server.config.id)) },
                        onReconnect = { manager.reconnect(server.config.id) },
                    )
                }
            }

            state.error?.let { message ->
                item {
                    TipCard(modifier = Modifier.padding(horizontal = PageGutter).padding(bottom = PageGutter)) {
                        Text(
                            "操作未完成",
                            style = MiuixTheme.textStyles.headline2,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(message, style = MiuixTheme.textStyles.body2)
                        TextButton("知道了", onClick = manager::clearError, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }

            item {
                Text(
                    "服务器身份由 TLS 指纹固定：地址变化不影响信任关系，指纹变化会立即中断连接。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = PageGutter + 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyServersCard(onAddServer: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
        insideMargin = PaddingValues(16.dp),
    ) {
        Icon(
            MiuixIcons.ScreenMirroring,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("连接你的电脑", style = MiuixTheme.textStyles.headline1)
        Spacer(Modifier.height(8.dp))
        Text(
            "在电脑上运行 sudo fingerkey pair-code <名称>，然后把显示的主机、端口、指纹和一次性 token 填进来。",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(20.dp))
        TextButton(
            text = "添加服务器",
            onClick = onAddServer,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun ServerCard(server: ServerState, onOpen: () -> Unit, onReconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onOpen,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    server.config.name,
                    style = MiuixTheme.textStyles.headline2,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    server.config.endpoint,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            TextButton(
                text = "重连",
                onClick = onReconnect,
                colors = ButtonDefaults.textButtonColors(
                    color = MiuixTheme.colorScheme.surfaceContainerHigh,
                    textColor = MiuixTheme.colorScheme.primary,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        StatusLine(server)
        server.error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
    }
}
