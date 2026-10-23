/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Insets
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentArcAppLoadingTest : SysuiTestCase() {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun returningToArcCancelsLoadingAndDoesNotAcceptLateResults() {
        val state = newState()
        val result = CompletableDeferred<MomentArcApps>()
        var cancelled = false
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MomentArcLoadApps(state) {
                try { result.await() } finally { cancelled = true }
            }
        }
        composeRule.runOnIdle { state.openAllApps() }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle {
            assertThat(state.appsLoading).isTrue()
            state.returnToArc()
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle {
            assertThat(cancelled).isTrue()
            result.complete(MomentArcApps(emptyList(), emptyList()))
        }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle {
            assertThat(state.apps).isNull()
            assertThat(state.appsLoading).isFalse()
        }
    }

    @Test
    fun packageRefreshCancelsObsoleteLoadAndKeepsNewResults() {
        val state = newState()
        val obsolete = CompletableDeferred<MomentArcApps>()
        val fresh = MomentArcApps(emptyList(), listOf(MomentArcAppSection("fresh", emptyList())))
        var loads = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MomentArcLoadApps(state) { if (++loads == 1) obsolete.await() else fresh }
        }
        composeRule.runOnIdle { state.openAllApps() }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { state.invalidateApps() }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { obsolete.complete(MomentArcApps(emptyList(), emptyList())) }
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { assertThat(state.apps).isSameInstanceAs(fresh) }
    }

    private fun newState() = MomentArcUiState().apply {
        repeat(13) { icons.add(MomentArcUiState.Icon(null, false)) }
        updateGeometry(MomentArcGeometry(1080, 2400, 48, true))
        panelGeometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
    }
}
