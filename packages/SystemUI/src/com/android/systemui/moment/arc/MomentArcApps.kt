/*
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.systemui.moment.arc

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.icu.text.AlphabeticIndex
import android.os.UserHandle
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal sealed interface MomentArcTarget {
    data class App(val packageName: String) : MomentArcTarget
    data class Shortcut(val packageName: String, val shortcutId: String, val userId: Int) : MomentArcTarget
    data class Activity(val component: ComponentName, val userId: Int) : MomentArcTarget
}

internal data class MomentArcApp(val key: String, val label: String, val icon: Drawable, val target: MomentArcTarget)
internal data class MomentArcAppSection(val label: String, val apps: List<MomentArcApp>)
internal data class MomentArcApps(val arcApps: List<MomentArcApp>, val sections: List<MomentArcAppSection>)

/** No package defaults or de-duplication: repeated configured slots and shortcuts are intentional. */
internal fun momentArcConfiguredTargets(
    inner: List<MomentArcTarget?>,
    outer: List<MomentArcTarget?>,
): List<Pair<Int, MomentArcTarget>> = (inner + outer).mapIndexedNotNull { slot, target ->
    target?.let { slot to it }
}

/** Runs on the caller's background dispatcher; each row launches the activity it was loaded from. */
internal suspend fun loadMomentArcApps(context: Context, user: UserHandle): List<MomentArcApp> {
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val activities = launcherApps.getActivityList(null, user)
        .sortedBy { it.componentName.flattenToString() }
        .distinctBy { it.componentName.packageName }
    return activities.mapNotNull { activity ->
        currentCoroutineContext().ensureActive()
        runCatching {
            MomentArcApp(
                activity.componentName.flattenToString(), activity.label.toString(),
                activity.getBadgedIcon(context.resources.displayMetrics.densityDpi),
                MomentArcTarget.Activity(activity.componentName, user.identifier),
            )
        }.getOrNull()
    }
}

internal fun groupMomentArcApps(apps: List<MomentArcApp>, locale: Locale): List<MomentArcAppSection> {
    val index = AlphabeticIndex<Any>(locale).addLabels(Locale.ENGLISH).buildImmutableIndex()
    val collator = Collator.getInstance(locale)
    return apps.groupBy { index.getBucketIndex(it.label) }.toSortedMap().map { (bucket, entries) ->
        MomentArcAppSection(
            index.getBucket(bucket)?.label?.takeIf { it.isNotEmpty() } ?: "#",
            entries.sortedWith { a, b ->
                val comparison = collator.compare(a.label, b.label)
                if (comparison != 0) comparison else a.key.compareTo(b.key)
            },
        )
    }
}
