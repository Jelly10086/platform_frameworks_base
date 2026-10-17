/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.scene.ui.composable.transitions

import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.ThresholdSwipeDistance
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.not
import com.android.systemui.shade.ui.composable.OverlayShade

/** Slide both shade panels together rather than collapsing and expanding them vertically. */
fun TransitionBuilder.betweenShadePanelsTransition(
    fromPanel: ElementKey = OverlayShade.Elements.NotificationsSlidingContent,
    toPanel: ElementKey = OverlayShade.Elements.QuickSettingsSlidingContent,
    exitEdge: Edge = Edge.Start,
    enterEdge: Edge = Edge.End,
) {
    spec = spring(dampingRatio = 0.85f, stiffness = 250f)
    distance = ThresholdSwipeDistance(56.dp, 8.dp, 100.dp)
    // Children must travel with their panel, not morph between two moving containers.
    sharedElement(!OverlayShade.Elements.Scrim, enabled = false)
    translate(fromPanel, exitEdge)
    translate(toPanel, enterEdge)
    sharedElement(OverlayShade.Elements.Scrim, enabled = true)
}
