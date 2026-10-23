/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Rect
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Pixel geometry shared by Compose rendering and the existing broadcast hit testing. */
internal class MomentArcGeometry(width: Int, height: Int, navbarHeight: Int, isLeft: Boolean) {
    val centerX = width * if (isLeft) .1f else 1f - .1f
    val centerY = height * .95f - navbarHeight
    private val dimension = min(width, height)
    private val leftSide = isLeft

    fun bounds(index: Int): Rect {
        val inner = index < INNER_COUNT
        val localIndex = if (inner) index else index - INNER_COUNT
        val total = if (inner) INNER_COUNT else OUTER_COUNT
        val radius = dimension * if (inner) .4f else .52f
        val spacing = if (inner) 1.5f else 1.2f
        val start = if (inner) 45f else 30f
        val end = if (inner) 135f else 150f
        val angle = start + ((total + 1) / 2f - (localIndex + 1)) *
            ((end - start) / (total + 1)) * spacing
        val horizontal = radius * cos(Math.toRadians(angle.toDouble()))
        val vertical = radius * sin(Math.toRadians(angle.toDouble()))
        val x = if (leftSide) centerX + horizontal else centerX - horizontal
        val y = centerY - vertical
        val iconRadius = dimension * .1f / 2f
        // Truncation, rather than rounding, matches the original ViewGroup's layout/hit boxes.
        return Rect((x - iconRadius).toInt(), (y - iconRadius).toInt(),
            (x + iconRadius).toInt(), (y + iconRadius).toInt())
    }

    companion object {
        const val INNER_COUNT = 6
        const val OUTER_COUNT = 7

        fun introDuration(index: Int) = 180L + index * 15L +
            if (index >= INNER_COUNT) 100L else 0L
    }
}
