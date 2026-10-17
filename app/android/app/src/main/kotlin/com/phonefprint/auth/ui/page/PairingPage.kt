// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.page

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.ServerConfig
import com.phonefprint.auth.ServerManager
import com.phonefprint.auth.ui.widgets.PageGutter
import com.phonefprint.auth.ui.widgets.PushedPage

/** Manual pairing: the fields printed by `fingerkey pair-code`. */
@Composable
fun PairingPage(
    state: ManagerState,
    manager: ServerManager,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("4443") }
    var fingerprint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    val pin = fingerprint.trim().lowercase()
    val parsedPort = port.trim().toIntOrNull()
    val valid = host.isNotBlank() &&
        parsedPort != null && parsedPort in 1..65535 &&
        pin.matches(Regex("[0-9a-f]{64}")) &&
        token.isNotBlank()

    PushedPage(title = "添加服务器", onBack = onBack) {
        item { SmallTitle("在电脑上获取配对信息", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text("在电脑上运行：", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        "sudo fingerkey pair-code my-phone",
                        style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "token 一次性有效，60 秒后过期；手机公钥会自动发送，无需复制粘贴。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item { SmallTitle("连接地址", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                TextField(host, { host = it }, Modifier.fillMaxWidth(), label = "主机名或 IP", singleLine = true)
                Spacer(Modifier.height(12.dp))
                TextField(
                    port,
                    { port = it },
                    Modifier.fillMaxWidth(),
                    label = "端口",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        }

        item { SmallTitle("安全验证", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                TextField(
                    fingerprint,
                    { fingerprint = it },
                    Modifier.fillMaxWidth(),
                    label = "TLS 指纹（64 位十六进制）",
                    maxLines = 3,
                )
                Spacer(Modifier.height(12.dp))
                TextField(token, { token = it }, Modifier.fillMaxWidth(), label = "一次性 token", singleLine = true)
            }
        }

        item { SmallTitle("显示名称", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                TextField(name, { name = it }, Modifier.fillMaxWidth(), label = "备注名称（可选）", singleLine = true)
            }
        }

        state.error?.let { message ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Text("配对未完成", style = MiuixTheme.textStyles.headline2, color = MiuixTheme.colorScheme.error)
                    Spacer(Modifier.height(6.dp))
                    Text(message, style = MiuixTheme.textStyles.body2)
                    TextButton("知道了", onClick = manager::clearError, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        item {
            TextButton(
                text = if (state.pairing) "正在验证并配对…" else "配对服务器",
                onClick = {
                    manager.pair(
                        ServerConfig(
                            id = pin,
                            name = name.trim(),
                            host = host.trim(),
                            port = parsedPort ?: 0,
                            fingerprint = pin,
                        ),
                        token.trim(),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = valid && !state.pairing,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        item {
            Text(
                "只会在指纹与你电脑上显示的完全一致时建立连接。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }
}
