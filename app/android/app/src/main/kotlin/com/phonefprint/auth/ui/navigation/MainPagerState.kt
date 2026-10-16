// Copyright 2026, FingerKey contributors
// SPDX-License-Identifier: MIT

package com.phonefprint.auth.ui.navigation

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shared handle on the tab pager so bars, rails and the FAB can drive navigation. */
@Stable
class MainPagerState(
    val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    val selectedPage: Int get() = pagerState.currentPage

    fun animateToPage(index: Int) {
        if (index == pagerState.currentPage) return
        scope.launch { pagerState.animateScrollToPage(index) }
    }
}

@Composable
fun rememberMainPagerState(pageCount: Int): MainPagerState {
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { pageCount })
    return remember(pagerState, scope) { MainPagerState(pagerState, scope) }
}
