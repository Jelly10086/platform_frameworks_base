/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.shade.ui.composable

import android.testing.TestableLooper
import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ShadePanelSwipeTest : SysuiTestCase() {
    @get:Rule val composeTestRule = createComposeRule()
    private var clicks = 0
    private var displacement = 0f
    private var flingVelocity = 0f
    private val verticalScrollState = ScrollState(0)
    private val connection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            displacement += available.x
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            flingVelocity = available.x
            return available
        }
    }

    private fun setContent(
        enabled: Boolean = true,
        diagonalOnly: Boolean = false,
        blockVerticalScroll: Boolean = false,
    ) {
        composeTestRule.setContent {
            Box(Modifier.nestedScroll(connection)) {
                Box(
                    Modifier.size(240.dp)
                        .testTag("clock")
                        .shadePanelSwipe(
                            enabled = enabled,
                            diagonalOnly = diagonalOnly,
                            blockVerticalScroll = blockVerticalScroll,
                        )
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            TextView(context).apply {
                                text = "12:00"
                                setOnClickListener { clicks++ }
                            }
                        },
                    )
                }
            }
        }
    }

    private fun setScrollableContent(enabled: Boolean) {
        composeTestRule.setContent {
            Column(Modifier.size(240.dp).verticalScroll(verticalScrollState)) {
                Box(
                    Modifier.size(240.dp)
                        .testTag("clock")
                        .shadePanelSwipe(
                            enabled = enabled,
                            blockVerticalScroll = true,
                        )
                        .clickable { clicks++ }
                )
                Spacer(Modifier.size(240.dp))
            }
        }
    }

    @Test
    fun staticHeader_blocksParentVerticalScrollWithoutClicking() {
        setScrollableContent(enabled = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(center, center - Offset(0f, height / 3f), 120L)
        }
        composeTestRule.runOnIdle {
            assertThat(verticalScrollState.value).isEqualTo(0)
            assertThat(clicks).isEqualTo(0)
        }
    }

    @Test
    fun disabled_preservesParentVerticalScroll() {
        setScrollableContent(enabled = false)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(center, center - Offset(0f, height / 3f), 120L)
        }
        composeTestRule.runOnIdle { assertThat(verticalScrollState.value).isGreaterThan(0) }
    }

    @Test
    fun tap_preservesClockClick() {
        setContent(blockVerticalScroll = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput { click() }
        composeTestRule.runOnIdle {
            assertThat(clicks).isEqualTo(1)
            assertThat(displacement).isEqualTo(0f)
        }
    }

    @Test
    fun fastHorizontalSwipe_cancelsClockClickAndForwardsVelocity() {
        setContent()
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(center + Offset(width / 4f, 0f), center - Offset(width / 4f, 0f), 60L)
        }
        composeTestRule.runOnIdle {
            assertThat(clicks).isEqualTo(0)
            assertThat(displacement).isLessThan(0f)
            assertThat(flingVelocity).isLessThan(0f)
        }
    }

    @Test
    fun fastRightSwipe_forwardsPositiveDisplacementAndVelocityWithoutClicking() {
        setContent(blockVerticalScroll = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(center - Offset(width / 4f, 0f), center + Offset(width / 4f, 0f), 60L)
        }
        composeTestRule.runOnIdle {
            assertThat(clicks).isEqualTo(0)
            assertThat(displacement).isGreaterThan(0f)
            assertThat(flingVelocity).isGreaterThan(0f)
        }
    }

    @Test
    fun rightDownDiagonal_switchesPanelInsteadOfBeingBlocked() {
        setContent(diagonalOnly = true, blockVerticalScroll = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(
                center + Offset(-width / 6f, -height / 5f),
                center + Offset(width / 6f, height / 5f),
                120L,
            )
        }
        composeTestRule.runOnIdle {
            assertThat(clicks).isEqualTo(0)
            assertThat(displacement).isGreaterThan(0f)
        }
    }

    @Test
    fun downwardDiagonal_switchesPanelEvenWhenVerticalDisplacementIsLarger() {
        setContent(blockVerticalScroll = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(
                center + Offset(width / 6f, -height / 5f),
                center + Offset(-width / 6f, height / 5f),
                120L,
            )
        }
        composeTestRule.runOnIdle {
            assertThat(clicks).isEqualTo(0)
            assertThat(displacement).isLessThan(0f)
        }
    }

    @Test
    fun mediaHeader_diagonalSwipeSwitchesPanel() {
        setContent(diagonalOnly = true, blockVerticalScroll = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(
                center + Offset(width / 6f, -height / 5f),
                center + Offset(-width / 6f, height / 5f),
                120L,
            )
        }
        composeTestRule.runOnIdle {
            assertThat(clicks).isEqualTo(0)
            assertThat(displacement).isLessThan(0f)
        }
    }

    @Test
    fun mediaHeader_horizontalSwipeIsLeftToCarousel() {
        setContent(diagonalOnly = true, blockVerticalScroll = true)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(centerRight, centerLeft, 120L)
        }
        composeTestRule.runOnIdle { assertThat(displacement).isEqualTo(0f) }
    }

    @Test
    fun predominantlyVerticalDiagonal_doesNotSwitchPanel() {
        setContent()
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(center, center + Offset(-width / 12f, height / 3f), 120L)
        }
        composeTestRule.runOnIdle { assertThat(displacement).isEqualTo(0f) }
    }

    @Test
    fun disabled_doesNotForwardSwipes() {
        setContent(enabled = false)
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(centerRight, centerLeft, 60L)
        }
        composeTestRule.runOnIdle { assertThat(displacement).isEqualTo(0f) }
    }

    @Test
    fun verticalSwipe_doesNotForwardHorizontalScroll() {
        setContent()
        composeTestRule.onNodeWithTag("clock").performTouchInput {
            swipe(center, center + Offset(0f, height / 3f), 60L)
        }
        composeTestRule.runOnIdle { assertThat(displacement).isEqualTo(0f) }
    }
}
