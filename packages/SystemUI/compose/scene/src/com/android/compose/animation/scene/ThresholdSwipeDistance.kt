/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.compose.animation.scene

import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/** A full-content swipe with independent distance and fling commit thresholds. */
class ThresholdSwipeDistance(
    private val positionalThreshold: Dp,
    private val minimumFlingDistance: Dp,
    private val velocityThreshold: Dp,
) : UserActionDistance by DefaultSwipeDistance {
    internal val settleSpec = spring<Float>(dampingRatio = 0.85f, stiffness = 250f)

    internal fun shouldCommit(
        density: Density,
        offset: Float,
        distance: Float,
        velocity: Float,
    ): Boolean = with(density) {
        // Normalize both directions so reversing towards the source always cancels the swipe.
        val direction = if (distance < 0f) -1f else 1f
        val displacement = offset * direction
        val speed = velocity * direction
        displacement >= 0f && speed >= 0f &&
            (displacement >= positionalThreshold.toPx() ||
                (displacement >= minimumFlingDistance.toPx() &&
                    speed >= velocityThreshold.toPx()))
    }
}
