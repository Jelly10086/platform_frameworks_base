/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.compose.animation.scene

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ThresholdSwipeDistanceTest {
    private val distance = ThresholdSwipeDistance(56.dp, 8.dp, 100.dp)

    @Test
    fun commitThresholds_areSymmetricAndDensityIndependent() {
        for (density in listOf(1f, 3f)) {
            for (direction in listOf(-1f, 1f)) {
                fun commits(offset: Float, velocity: Float): Boolean =
                    distance.shouldCommit(
                        Density(density),
                        offset * density * direction,
                        360f * density * direction,
                        velocity * density * direction,
                    )

                assertThat(commits(55f, 0f)).isFalse()
                assertThat(commits(56f, 0f)).isTrue()
                assertThat(commits(7f, 1000f)).isFalse()
                assertThat(commits(8f, 99f)).isFalse()
                assertThat(commits(8f, 100f)).isTrue()
                assertThat(commits(12f, 120f)).isTrue()
                assertThat(commits(8f, 0f)).isFalse()
                assertThat(commits(100f, -250f)).isFalse()
                assertThat(commits(-100f, 250f)).isFalse()
            }
        }
    }
}
