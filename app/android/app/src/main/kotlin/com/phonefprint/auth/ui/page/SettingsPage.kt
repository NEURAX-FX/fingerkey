// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.page

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import com.phonefprint.auth.ManagerState
import com.phonefprint.auth.ServerManager
import com.phonefprint.auth.ui.navigation.LocalNavigator
import com.phonefprint.auth.ui.navigation.Route
import com.phonefprint.auth.ui.theme.ThemeModeOption
import com.phonefprint.auth.ui.theme.ThemeState
import com.phonefprint.auth.ui.theme.UiPreferences
import com.phonefprint.auth.ui.theme.UiSettings
import com.phonefprint.auth.ui.theme.barColor
import com.phonefprint.auth.ui.theme.rememberGlassBackdrop
import com.phonefprint.auth.ui.theme.glassEffect
import com.phonefprint.auth.ui.theme.seedSwatches
import com.phonefprint.auth.ui.theme.supportsBackdropBlur
import com.phonefprint.auth.ui.widgets.FingerKeyTopBar
import com.phonefprint.auth.ui.widgets.fingerKeyScrollBehavior
import com.phonefprint.auth.ui.widgets.PageGutter
import com.phonefprint.auth.ui.widgets.TipCard

/** Tab 3: appearance, preferences, background permissions and identity. */
@Composable
fun SettingsPage(
    context: Context,
    state: ManagerState,
    manager: ServerManager,
    settings: UiSettings,
    title: String,
    outerPadding: PaddingValues,
    onOpenOverlaySettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val navigator = LocalNavigator.current
    val preferences = UiPreferences.get(context)
    val scrollBehavior = fingerKeyScrollBehavior()
    val backdrop = rememberGlassBackdrop(settings.blurEnabled)

    var showPublicKey by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showThemeMode by remember { mutableStateOf(false) }
    var showPalette by remember { mutableStateOf(false) }
    var showSpec by remember { mutableStateOf(false) }
    var showSeed by remember { mutableStateOf(false) }
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = remember(resumeTick) {
        Permissions(
            overlay = Settings.canDrawOverlays(context),
            batteryExempt = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName),
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        )
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
            item { SmallTitle("外观", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            item {
                PreferenceCard {
                    ArrowPreference(
                        title = "深浅色",
                        summary = "跟随系统或固定一种外观",
                        endActions = { ValueLabel(themeModeLabel(settings.themeMode)) },
                        onClick = { showThemeMode = true },
                    )
                    SwitchPreference(
                        checked = settings.dynamicColor && ThemeState.isDynamicColorAvailable,
                        onCheckedChange = { enabled -> preferences.update { it.copy(dynamicColor = enabled) } },
                        title = "动态取色",
                        summary = if (ThemeState.isDynamicColorAvailable) {
                            "从壁纸提取配色（Material You）"
                        } else {
                            "需要 Android 12 及以上"
                        },
                        enabled = ThemeState.isDynamicColorAvailable,
                    )
                    ArrowPreference(
                        title = "调色板风格",
                        summary = "生成配色的算法，影响整体色调",
                        endActions = { ValueLabel(paletteLabel(settings.paletteStyle)) },
                        onClick = { showPalette = true },
                    )
                    ArrowPreference(
                        title = "色板规范",
                        summary = "2025 更鲜亮，2021 更克制",
                        endActions = { ValueLabel(specLabel(settings.colorSpec)) },
                        onClick = { showSpec = true },
                    )
                    if (!settings.dynamicColor) {
                        ArrowPreference(
                            title = "种子色",
                            summary = "动态取色关闭时用作配色源",
                            endActions = { SeedSwatch(settings.seedColor) },
                            onClick = { showSeed = true },
                        )
                    }
                    if (supportsBackdropBlur) {
                        SwitchPreference(
                            checked = settings.blurEnabled,
                            onCheckedChange = { enabled -> preferences.update { it.copy(blurEnabled = enabled) } },
                            title = "界面模糊",
                            summary = "顶栏与底栏使用毛玻璃效果",
                        )
                    }
                }
            }

            item { SmallTitle("通用", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            item {
                PreferenceCard {
                    SwitchPreference(
                        checked = state.soundEnabled,
                        onCheckedChange = manager::setSoundEnabled,
                        title = "请求提示音",
                        summary = "收到新审批时播放提示音",
                        enabled = !state.loading,
                    )
                    SwitchPreference(
                        checked = settings.directBiometric,
                        onCheckedChange = { on -> preferences.update { it.copy(directBiometric = on) } },
                        title = "直接弹出指纹",
                        summary = "收到请求立刻验证指纹；关闭后需先点「指纹批准」",
                    )
                }
            }

            item { SmallTitle("后台运行", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            item {
                PreferenceCard {
                    ArrowPreference(
                        title = "通知",
                        summary = "在通知栏接收审批请求",
                        endActions = { PermissionLabel(permissions.notifications) },
                        onClick = onRequestNotifications,
                    )
                    ArrowPreference(
                        title = "悬浮审批",
                        summary = "在其他应用上层直接显示审批卡片",
                        endActions = { PermissionLabel(permissions.overlay) },
                        onClick = onOpenOverlaySettings,
                    )
                    ArrowPreference(
                        title = "电池优化",
                        summary = "允许在后台保持服务器连接",
                        endActions = { PermissionLabel(permissions.batteryExempt) },
                        onClick = onOpenBatterySettings,
                    )
                }
            }

            item { SmallTitle("身份", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            item {
                PreferenceCard {
                    ArrowPreference(
                        title = "设备公钥",
                        summary = if (state.publicKey.isNotEmpty()) "服务器用它验证审批签名" else "尚未就绪",
                        onClick = { showPublicKey = true },
                        enabled = state.publicKey.isNotEmpty(),
                    )
                    ArrowPreference(
                        title = "重置设备身份",
                        summary = "删除全部配对并生成新密钥",
                        onClick = { confirmReset = true },
                    )
                }
            }

            item { SmallTitle("关于", textColor = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            item {
                PreferenceCard {
                    ArrowPreference(
                        title = "关于 FingerKey",
                        summary = "版本与协议说明",
                        onClick = { navigator.push(Route.About) },
                    )
                }
            }

            state.error?.let { message ->
                item {
                    TipCard(modifier = Modifier.padding(horizontal = PageGutter).padding(top = PageGutter)) {
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
        }
    }

    OverlayDialog(
        show = showPublicKey,
        title = "设备公钥",
        summary = "私钥只保存在这台手机上，不会上传。服务器持有的是公钥。",
        onDismissRequest = { showPublicKey = false },
    ) {
        SelectionContainer {
            Text(
                state.publicKey,
                Modifier.padding(vertical = 16.dp),
                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            )
        }
        TextButton(
            text = "完成",
            onClick = { showPublicKey = false },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }

    OverlayDialog(
        show = confirmReset,
        title = "重置设备身份？",
        summary = "所有服务器连接与配对都会被删除，之后必须重新配对。外观等设置会保留。",
        onDismissRequest = { confirmReset = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onClick = { confirmReset = false }, modifier = Modifier.weight(1f))
            TextButton(
                text = "重置身份",
                onClick = { confirmReset = false; manager.resetIdentity() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }

    OverlayDialog(
        show = showThemeMode,
        title = "深浅色",
        onDismissRequest = { showThemeMode = false },
    ) {
        ThemeModeOption.entries.forEach { option ->
            ChoiceRow(
                label = themeModeLabel(option),
                selected = settings.themeMode == option,
                onClick = {
                    preferences.update { it.copy(themeMode = option) }
                    showThemeMode = false
                },
            )
        }
    }

    OverlayDialog(
        show = showPalette,
        title = "调色板风格",
        onDismissRequest = { showPalette = false },
    ) {
        ThemePaletteStyle.entries.forEach { style ->
            ChoiceRow(
                label = paletteLabel(style),
                selected = settings.paletteStyle == style,
                onClick = {
                    preferences.update { it.copy(paletteStyle = style) }
                    showPalette = false
                },
            )
        }
    }

    OverlayDialog(
        show = showSpec,
        title = "色板规范",
        onDismissRequest = { showSpec = false },
    ) {
        ThemeColorSpec.entries.forEach { spec ->
            ChoiceRow(
                label = specLabel(spec),
                selected = settings.colorSpec == spec,
                onClick = {
                    preferences.update { it.copy(colorSpec = spec) }
                    showSpec = false
                },
            )
        }
    }

    OverlayDialog(
        show = showSeed,
        title = "种子色",
        summary = "切换后会立刻按新配色重建界面。",
        onDismissRequest = { showSeed = false },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            seedSwatches.chunked(5).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(color, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color.toArgb() == settings.seedColor.toArgb()) {
                                Icon(
                                    MiuixIcons.Ok,
                                    contentDescription = "当前种子色",
                                    modifier = Modifier.size(20.dp),
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
        TextButton(
            text = "完成",
            onClick = { showSeed = false },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

@Composable
private fun PreferenceCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter).padding(bottom = PageGutter),
        insideMargin = PaddingValues(vertical = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    BasicComponent(
        title = label,
        onClick = onClick,
        endActions = {
            RadioButton(selected = selected, onClick = onClick)
        },
    )
}

@Composable
private fun ValueLabel(text: String) {
    Text(
        text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun PermissionLabel(granted: Boolean) {
    Text(
        if (granted) "已允许" else "未允许",
        style = MiuixTheme.textStyles.footnote1,
        color = if (granted) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun SeedSwatch(color: Color) {
    Box(Modifier.size(20.dp).background(color, CircleShape))
}

private fun themeModeLabel(mode: ThemeModeOption): String = when (mode) {
    ThemeModeOption.SYSTEM -> "跟随系统"
    ThemeModeOption.LIGHT -> "浅色"
    ThemeModeOption.DARK -> "深色"
}

private fun paletteLabel(style: ThemePaletteStyle): String = when (style) {
    ThemePaletteStyle.TonalSpot -> "淡雅"
    ThemePaletteStyle.Neutral -> "中性"
    ThemePaletteStyle.Vibrant -> "鲜明"
    ThemePaletteStyle.Expressive -> "表现力"
    ThemePaletteStyle.Rainbow -> "彩虹"
    ThemePaletteStyle.FruitSalad -> "果缤纷"
    ThemePaletteStyle.Monochrome -> "单色"
    ThemePaletteStyle.Fidelity -> "保真"
    ThemePaletteStyle.Content -> "内容优先"
}

private fun specLabel(spec: ThemeColorSpec): String = when (spec) {
    ThemeColorSpec.Spec2021 -> "Material 2021"
    ThemeColorSpec.Spec2025 -> "Material 2025"
}

private data class Permissions(
    val overlay: Boolean,
    val batteryExempt: Boolean,
    val notifications: Boolean,
)
