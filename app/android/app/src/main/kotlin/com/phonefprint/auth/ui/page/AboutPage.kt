// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.page

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.phonefprint.auth.ui.widgets.PageGutter
import com.phonefprint.auth.ui.widgets.PushedPage

/** Version and protocol summary; also the place to reach the repository. */
@Composable
fun AboutPage(context: Context, onBack: () -> Unit, onOpenRepository: () -> Unit) {
    val version = rememberVersion(context)
    PushedPage(title = "关于", onBack = onBack) {
        item { SmallTitle("应用", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text("FingerKey", style = MiuixTheme.textStyles.headline1)
                Spacer(Modifier.height(6.dp))
                Text("版本 $version", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(10.dp))
                Text(
                    "用手机指纹代替密码，批准 Linux 上的 sudo / pkexec 等提权操作。",
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }

        item { SmallTitle("工作原理", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
                insideMargin = PaddingValues(16.dp),
            ) {
                Text("PAM 模块向本机 daemon 申请会话，daemon 通过 TLS 把请求推送到手机。", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(8.dp))
                Text("手机用系统指纹确认后，以 Ed25519 私钥签名；daemon 校验签名通过才放行。", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(8.dp))
                Text(
                    "证书指纹固定（pinning）保证只与自己的电脑通信；一次性 token 只在 60 秒内可配对。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            TextButton(
                text = "查看项目主页",
                onClick = onOpenRepository,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun rememberVersion(context: Context): String = remember(context) {
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未知")
}
