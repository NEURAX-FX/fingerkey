// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Type-safe destinations. Every key is `@Serializable` so the back stack survives process death.
 * Parameters travel in the key itself, never in process-wide mutable state.
 */
@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Main : Route

    @Serializable
    data object Pairing : Route

    @Serializable
    data object About : Route

    @Serializable
    data class ServerDetail(val fingerprint: String) : Route
}
