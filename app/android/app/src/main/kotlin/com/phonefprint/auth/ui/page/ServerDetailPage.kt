// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.ServerManager
import com.phonefprint.auth.ui.widgets.PageGutter
import com.phonefprint.auth.ui.widgets.PushedPage
import com.phonefprint.auth.ui.widgets.InfoRow
import com.phonefprint.auth.ui.widgets.StatusLine

/** Detail page for one paired computer: identity, status and the lifecycle actions. */
@Composable
fun ServerDetailPage(
    fingerprint: String,
    state: ManagerState,
    manager: ServerManager,
    onBack: () -> Unit,
    onRepair: () -> Unit,
) {
    val server = state.servers.firstOrNull { it.config.id == fingerprint }
    var confirmForget by remember { mutableStateOf(false) }

    PushedPage(title = server?.config?.name ?: "服务器详情", onBack = onBack) {
        if (server == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text("该服务器已不在配对列表中。", style = MiuixTheme.textStyles.body1)
                }
            }
            return@PushedPage
        }

        item { SmallTitle("状态", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(vertical = 8.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    StatusLine(server)
                    Spacer(Modifier.height(10.dp))
                    InfoRow("地址", server.config.endpoint)
                    if (server.error != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(server.error, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    }
                }
            }
        }

        item { SmallTitle("身份", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text("TLS 证书指纹（SHA-256）", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        server.config.fingerprint,
                        style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        item { SmallTitle("操作", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                TextButton(
                    text = "重新连接",
                    onClick = { manager.reconnect(server.config.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                Spacer(Modifier.height(10.dp))
                TextButton(
                    text = "重新配对",
                    onClick = onRepair,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextButton(
                    text = "忘记此服务器",
                    onClick = { confirmForget = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.errorContainer,
                        textColor = MiuixTheme.colorScheme.onErrorContainer,
                    ),
                )
            }
        }
        item {
            Text(
                "忘记只会删除本机上的配对记录并停止连接，不会修改服务器。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }

    OverlayDialog(
        show = confirmForget,
        title = "忘记 ${server?.config?.name ?: "此服务器"}？",
        summary = "将停止连接、清除该服务器的待处理请求并删除配对记录。设备身份与其他服务器不受影响。",
        onDismissRequest = { confirmForget = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onClick = { confirmForget = false }, modifier = Modifier.weight(1f))
            TextButton(
                text = "忘记",
                onClick = { confirmForget = false; server?.let { manager.remove(it.config.id) }; onBack() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
