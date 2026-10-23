/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Insets
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentArcPanelGeometryTest : SysuiTestCase() {
    @Test
    fun portraitBrowserIsLargerButLaunchTargetRemainsMomentSized() {
        val geometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.of(0, 96, 0, 120), 3f)
        assertThat(geometry.scale).isEqualTo(.80f)
        assertThat(geometry.bounds.width()).isEqualTo(864)
        assertThat(geometry.launchBounds.width()).isEqualTo(594)
        assertThat(geometry.animateLaunch).isTrue()
        assertThat(geometry.bounds.left).isGreaterThan(0)
        assertThat(geometry.bounds.top).isAtLeast(96)
        assertThat(geometry.bounds.bottom).isAtMost(2280)
        assertThat(geometry.gridBounds.top).isGreaterThan(geometry.bounds.top)
        assertThat(geometry.gridBounds.bottom).isEqualTo(geometry.handleTouchBounds.top)
        assertThat(geometry.handleVisualBounds.centerY()).isGreaterThan(geometry.bounds.bottom.toFloat())
        assertThat(geometry.handleTouchBounds.bottom).isAtMost(2280)
        assertThat(geometry.handleTouchBounds.height()).isEqualTo(144)
    }

    @Test
    fun landscapeRespectsBothSideInsets() {
        val geometry = MomentArcPanelGeometry.calculate(2400, 1080, Insets.of(60, 72, 40, 48), 3f)
        assertThat(geometry.scale).isEqualTo(.40f)
        assertThat(geometry.bounds.width()).isEqualTo(432)
        assertThat(geometry.launchBounds).isEqualTo(geometry.bounds)
        assertThat(geometry.animateLaunch).isFalse()
        assertThat(geometry.bounds.left).isAtLeast(60)
        assertThat(geometry.bounds.right).isAtMost(2360)
        assertThat(geometry.bounds.bottom).isAtMost(1032)
        assertThat(geometry.handleVisualBounds.bottom).isAtMost(1032f)
        assertThat(geometry.handleTouchBounds.height()).isEqualTo(144)
    }

    @Test
    fun contentUsesTheSameInsetBoundsAsTheWindow() {
        val geometry = MomentArcPanelGeometry.calculate(1080, 2400, Insets.NONE, 3f)
        assertThat(geometry.gridBounds).isEqualTo(Rect(geometry.bounds.left,
            geometry.bounds.top + (56 * 3 * .80f).toInt(), geometry.bounds.right,
            geometry.bounds.bottom - (16 * 3 * .80f).toInt()))
    }
}
