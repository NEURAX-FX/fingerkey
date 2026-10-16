// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Small helper over the shared miuix-nav back stack. */
class Navigator(val backStack: NavBackStack) {
    fun push(key: NavKey) {
        if (key !in backStack) backStack.add(key)
    }

    fun replace(key: NavKey) {
        if (backStack.isEmpty()) backStack.add(key) else backStack[backStack.lastIndex] = key
    }

    fun pop() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun current(): NavKey? = backStack.lastOrNull()
}

val LocalNavigator = staticCompositionLocalOf<Navigator> {
    error("LocalNavigator was not provided")
}
