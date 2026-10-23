/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Color
import android.graphics.Insets
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentArcUiStateTest : SysuiTestCase() {
    @get:Rule val composeRule = createComposeRule()
    private val geometry = MomentArcGeometry(1080, 2400, 48, true)

    @Test
    fun easingMatchesOriginalPlatformInterpolators() {
        val pairs = listOf(
            MomentArcUiState.STANDARD_EASING to AccelerateDecelerateInterpolator(),
            MomentArcUiState.INTRO_EASING to DecelerateInterpolator(1.5f),
            MomentArcUiState.EXIT_EASING to AccelerateInterpolator(),
        )
        pairs.forEach { (easing, original) ->
            for (step in 0..100) {
                val fraction = step / 100f
                assertThat(easing.transform(fraction)).isWithin(.000001f)
                    .of(original.getInterpolation(fraction))
            }
        }
    }

    @Test
    fun panelCurveMatchesColorOsStateChangeCurve() {
        val original = PathInterpolator(.3f, 0f, .1f, 1f)
        for (step in 0..100) {
            val f = step / 100f
            assertThat(MomentArcUiState.PANEL_EASING.transform(f)).isWithin(.002f)
                .of(original.getInterpolation(f))
            assertThat(MomentArcUiState.PANEL_REVERSE_EASING.transform(f)).isWithin(.002f)
                .of(1f - original.getInterpolation(1f - f))
        }
    }

    @Test
    fun panelOpensFromMoreSlotAndReturnsWithoutDroppingBackdrop() {
        val state = mountState()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            state.panelGeometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
            state.openAllApps()
            assertThat(state.panelSource).isEqualTo(RectF(state.icons[5].bounds))
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle {
            assertThat(state.panelInteractive).isTrue()
            assertThat(state.panelProgress).isEqualTo(1f)
            assertThat(state.panelContentAlpha).isEqualTo(1f)
            assertThat(state.backdropAlpha).isEqualTo(128)
            state.returnToArc()
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle {
            assertThat(state.panelVisible).isFalse()
            assertThat(state.panelProgress).isEqualTo(0f)
            assertThat(state.panelContentAlpha).isEqualTo(0f)
            assertThat(state.backdropAlpha).isEqualTo(128)
        }
    }

    @Test
    fun reverseDuringPanelOpeningStartsFromCurrentProgress() {
        val state = mountState()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            state.panelGeometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
            state.openAllApps()
        }
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.runOnIdle {
            val progress = state.panelProgress
            assertThat(progress).isGreaterThan(0f)
            assertThat(progress).isLessThan(1f)
            state.returnToArc()
            assertThat(state.panelProgress).isEqualTo(progress)
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle { assertThat(state.panelVisible).isFalse() }
    }

    @Test
    fun appLaunchShrinksTheShellWithoutShrinkingTheGridAndCompletesOnce() {
        val state = mountState()
        var completions = 0
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            state.panelGeometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
            state.openAllApps()
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle {
            assertThat(state.animateAppLaunch { completions++ }).isTrue()
            assertThat(state.panelInteractive).isFalse()
        }
        composeRule.mainClock.advanceTimeBy(150)
        composeRule.runOnIdle {
            assertThat(state.launchProgress).isGreaterThan(0f)
            assertThat(state.launchProgress).isLessThan(1f)
            assertThat(state.panelContentAlpha).isEqualTo(0f)
            assertThat(completions).isEqualTo(0)
        }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.runOnIdle {
            assertThat(state.launchProgress).isEqualTo(1f)
            assertThat(completions).isEqualTo(1)
        }
    }

    @Test
    fun landscapeKeepsOriginalExitInsteadOfShrinkingThePanel() {
        val state = mountState()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            state.panelGeometry = MomentArcPanelGeometry.calculate(2400, 1080, Insets.NONE, 3f)
            state.openAllApps()
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle {
            assertThat(state.animateAppLaunch {}).isFalse()
            assertThat(state.launchingApp).isFalse()
        }
    }

    @Test
    fun dismissDuringLaunchDoesNotDeliverLateLaunchCompletion() {
        val state = mountState()
        var launchCompletions = 0
        var exitCompletions = 0
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            state.panelGeometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
            state.openAllApps()
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle { assertThat(state.animateAppLaunch { launchCompletions++ }).isTrue() }
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.runOnIdle { state.exit(geometry) { exitCompletions++ } }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            assertThat(launchCompletions).isEqualTo(0)
            assertThat(exitCompletions).isEqualTo(1)
        }
    }

    @Test
    fun continuousGestureOpensFromBandAndDoesNotSelectAppWithoutMovement() {
        val state = mountState()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            state.panelGeometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
            state.startContinuousGesture(340f, 900f)
            state.openAllApps()
            assertThat(state.panelSource.centerX()).isEqualTo(340f)
            assertThat(state.panelSource.width()).isEqualTo(state.icons[5].width.toFloat())
            assertThat(state.gestureSelection).isNull()
        }
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.runOnIdle {
            assertThat(state.endContinuousGesture()).isNull()
            assertThat(state.allAppsRequested).isTrue() // Release without selection keeps the panel.
        }
    }

    @Test
    fun continuousSelectionRequiresCurrentHitBoxAndIsClearedOnCancel() {
        val state = newState()
        val app = MomentArcApp("test", "Test", ColorDrawable(Color.WHITE), MomentArcTarget.App("test"))
        state.startContinuousGesture(120f, 200f)
        assertThat(state.setGestureSelection(app, RectF(200f, 300f, 300f, 400f))).isTrue()
        assertThat(state.selectionAt(240f, 350f)).isSameInstanceAs(app)
        assertThat(state.selectionAt(350f, 350f)).isNull() // Don't launch a stale hovered cell.
        state.endContinuousGesture()
        assertThat(state.selectionAt(240f, 350f)).isNull()
    }

    @Test
    fun introAndSelectionReachOriginalEndPose() {
        val state = mountState()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            assertThat(state.backdropAlpha).isEqualTo(128)
            state.icons.forEach { icon ->
                assertThat(icon.translationX).isWithin(.0001f).of(0f)
                assertThat(icon.translationY).isWithin(.0001f).of(0f)
                assertThat(icon.scaleX).isEqualTo(1f)
                assertThat(icon.alpha).isEqualTo(1f)
            }
            state.select(5)
        }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.runOnIdle {
            state.icons.forEachIndexed { index, icon ->
                assertThat(icon.scaleX).isEqualTo(if (index == 5) 1.15f else 1f)
                assertThat(icon.scaleY).isEqualTo(icon.scaleX)
            }
        }
    }

    @Test
    fun exitDuringIntroFinishesOnceAndDoesNotGetOverwritten() {
        val state = mountState()
        var completions = 0
        composeRule.mainClock.advanceTimeBy(80)
        composeRule.runOnIdle {
            state.exit(geometry) { completions++ }
            state.exit(geometry) { completions += 100 }
        }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle {
            assertThat(completions).isEqualTo(1)
            assertThat(state.backdropAlpha).isEqualTo(0)
            state.icons.forEach { icon ->
                assertThat(icon.alpha).isEqualTo(0f)
                assertThat(icon.scaleX).isEqualTo(.8f)
                assertThat(icon.translationX).isWithin(.0001f)
                    .of(geometry.centerX - (icon.left + icon.width / 2f))
            }
        }
    }

    @Test
    fun leavingCompositionCancelsExitWithoutCompletionCallback() {
        val state = newState()
        val mounted = mutableStateOf(true)
        var completions = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { if (mounted.value) state.Animations() }
        composeRule.runOnIdle {
            state.ready = true
            state.exit(geometry) { completions++ }
        }
        composeRule.mainClock.advanceTimeBy(48)
        composeRule.runOnIdle { mounted.value = false }
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.runOnIdle { assertThat(completions).isEqualTo(0) }
    }

    private fun mountState(): MomentArcUiState {
        val state = newState()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { state.Animations() }
        composeRule.runOnIdle { state.ready = true }
        return state
    }

    private fun newState() = MomentArcUiState().apply {
        repeat(13) { icons.add(MomentArcUiState.Icon(ColorDrawable(Color.WHITE), true)) }
        updateGeometry(geometry)
    }
}
