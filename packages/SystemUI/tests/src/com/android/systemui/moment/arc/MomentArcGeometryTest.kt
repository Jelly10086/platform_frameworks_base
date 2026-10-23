/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentArcGeometryTest : SysuiTestCase() {
    @Test
    fun boundsMatchOriginalViewGroupOnBothSides() {
        // Include odd dimensions and landscape to detect changes in rounding or radius basis.
        for ((width, height) in listOf(1080 to 2400, 1081 to 2399, 2400 to 1080, 1600 to 2560)) {
            for (navbar in listOf(0, 48, 120)) {
                for (isLeft in listOf(true, false)) {
                    val geometry = MomentArcGeometry(width, height, navbar, isLeft)
                    for (index in 0 until 13) {
                        assertThat(geometry.bounds(index)).isEqualTo(
                            originalBounds(width, height, navbar, isLeft, index)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun navigationBarOnlyOffsetsVerticalPosition() {
        val withoutBar = MomentArcGeometry(1080, 2400, 0, true)
        val withBar = MomentArcGeometry(1080, 2400, 48, true)
        for (index in 0 until 13) {
            val expected = withoutBar.bounds(index).apply { offset(0, -48) }
            assertThat(withBar.bounds(index)).isEqualTo(expected)
        }
    }

    @Test
    fun introKeepsOriginalPerSlotDurations() {
        assertThat((0 until 13).map { MomentArcGeometry.introDuration(it) }).containsExactly(
            180L, 195L, 210L, 225L, 240L, 255L,
            370L, 385L, 400L, 415L, 430L, 445L, 460L,
        ).inOrder()
    }

    /** Frozen pre-Compose layout formula; do not delegate to the production geometry. */
    private fun originalBounds(w: Int, h: Int, navbar: Int, isLeft: Boolean, index: Int): Rect {
        val cx = w * if (isLeft) 0.1f else 1f - 0.1f
        val cy = h * 0.95f - navbar
        val dimension = min(w, h)
        val iconRadius = dimension * 0.1f / 2f
        val inner = index < 6
        val localIndex = if (inner) index else index - 6
        val total = if (inner) 6 else 7
        val radius = dimension * if (inner) 0.4f else 0.52f
        val spacing = if (inner) 1.5f else 1.2f
        val start = if (inner) 45f else 30f
        val end = if (inner) 135f else 150f
        val angle = start + ((total + 1) / 2f - (localIndex + 1)) *
            ((end - start) / (total + 1)) * spacing
        val horizontal = radius * cos(Math.toRadians(angle.toDouble()))
        val vertical = radius * sin(Math.toRadians(angle.toDouble()))
        val x = if (isLeft) cx + horizontal else cx - horizontal
        val y = cy - vertical
        return Rect((x - iconRadius).toInt(), (y - iconRadius).toInt(),
            (x + iconRadius).toInt(), (y + iconRadius).toInt())
    }
}
