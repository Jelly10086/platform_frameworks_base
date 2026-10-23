/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MomentArcAppsTest : SysuiTestCase() {
    @Test
    fun configuredTargetsKeepRingOrderShortcutsDuplicatesAndSlotKeys() {
        val app = MomentArcTarget.App("test.app")
        val shortcut = MomentArcTarget.Shortcut("test.app", "shortcut", 0)
        val configured = momentArcConfiguredTargets(
            listOf(app, null, shortcut, null, app),
            listOf(null, shortcut, app),
        )
        assertThat(configured).containsExactly(
            0 to app, 2 to shortcut, 4 to app, 6 to shortcut, 7 to app,
        ).inOrder()
    }

    @Test
    fun emptyConfigurationDoesNotInsertDefaultPackages() {
        assertThat(momentArcConfiguredTargets(List(5) { null }, List(7) { null })).isEmpty()
    }

    @Test
    fun alphabeticalGroupsRemainContiguousAndSortNamesInsideEachGroup() {
        val apps = listOf("Beta", "Apple", "Bravo", "Alpha").map { label ->
            MomentArcApp(label, label, ColorDrawable(Color.WHITE), MomentArcTarget.App(label))
        }
        val groups = groupMomentArcApps(apps, Locale.ENGLISH)
        assertThat(groups.map { it.label }).containsExactly("A", "B").inOrder()
        assertThat(groups.flatMap { it.apps }.map { it.label })
            .containsExactly("Alpha", "Apple", "Beta", "Bravo").inOrder()
    }
}
